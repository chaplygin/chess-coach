package org.chesscoach.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.chesscoach.core.ReleaseTag
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val url: String,
)

/** Обновление, доступное на GitHub. */
data class AvailableUpdate(
    val tag: String,
    val versionName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val percent: Int) : UpdateState

    /** Файл скачан, но система ещё не разрешила этому приложению ставить APK. */
    data class NeedsInstallPermission(val file: File) : UpdateState
    data class ReadyToInstall(val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Проверка, скачивание и запуск установки новой версии с GitHub Releases.
 *
 * Сама установка остаётся за системным установщиком: приложение не может и не
 * должно ставить себя молча, пользователь подтверждает её на экране Android.
 * Подлинность файла проверяет тоже система: поверх установленного приложения
 * она поставит только APK с той же подписью.
 */
class AppUpdater(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private val updatesDir get() = File(context.cacheDir, UPDATES_DIR)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private var startupCheckDone = false

    private val packageInfo get() = context.packageManager.getPackageInfo(context.packageName, 0)

    // Читается из PackageManager, а не BuildConfig: так не нужно включать
    // generateBuildConfig ради одного поля.
    val installedVersionName: String
        get() = runCatching { packageInfo.versionName }.getOrNull() ?: "неизвестно"

    private val installedBuild: Long get() = packageInfo.longVersionCode

    /** Сбой здесь молчит: без сети приложение должно просто открыться. */
    fun checkOnStartup() {
        if (startupCheckDone) return
        startupCheckDone = true
        scope.launch {
            val update = runCatching { fetchUpdate() }.getOrNull()
            if (update != null && _state.value == UpdateState.Idle) {
                _state.value = UpdateState.Available(update)
            }
        }
    }

    fun checkNow() {
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = runCatching { fetchUpdate() }.fold(
                onSuccess = { update -> update?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate },
                onFailure = { UpdateState.Failed("Не удалось проверить обновление: ${it.message}") },
            )
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    fun download(update: AvailableUpdate) {
        _state.value = UpdateState.Downloading(update, 0)
        scope.launch {
            runCatching { downloadApk(update) }.fold(
                onSuccess = { offerInstall(it) },
                onFailure = { _state.value = UpdateState.Failed("Не удалось скачать обновление: ${it.message}") },
            )
        }
    }

    /** Запускает установку; повторяется после выдачи разрешения или если окно системы закрыли. */
    fun offerInstall(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsInstallPermission(file)
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }

        _state.value = UpdateState.ReadyToInstall(file)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".photos", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private suspend fun fetchUpdate(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val connection = open(LATEST_RELEASE_URL)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub ответил HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = json.decodeFromString<ReleaseDto>(body)

            // Скачанные файлы весят больше 100 МБ: устаревшие убираем сразу.
            updatesDir.listFiles()?.forEach { file ->
                if (!file.name.startsWith(release.tagName + ".")) file.delete()
            }

            if (!ReleaseTag.isNewer(release.tagName, installedBuild)) return@withContext null
            val asset = release.assets.firstOrNull { it.name == APK_ASSET }
                ?: return@withContext null
            AvailableUpdate(release.tagName, release.name ?: release.tagName, asset.url, asset.size)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadApk(update: AvailableUpdate): File = withContext(Dispatchers.IO) {
        updatesDir.mkdirs()
        val target = File(updatesDir, update.tag + ".apk")
        if (target.length() == update.sizeBytes) return@withContext target

        val partial = File(updatesDir, update.tag + ".part")
        val connection = open(update.downloadUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub ответил HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var copied = 0L
                    var shownPercent = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val percent = (copied * 100 / update.sizeBytes).toInt().coerceAtMost(100)
                        if (percent != shownPercent) {
                            shownPercent = percent
                            _state.value = UpdateState.Downloading(update, percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        // Оборванная загрузка без исключения дала бы установщику битый файл с
        // невнятной ошибкой "пакет повреждён".
        if (partial.length() != update.sizeBytes) {
            partial.delete()
            throw IOException("файл скачался не полностью")
        }
        if (!partial.renameTo(target)) throw IOException("не удалось сохранить файл")
        target
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "ChessCoach-Android")
        }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/chaplygin/chess-coach/releases/latest"
        const val APK_ASSET = "chesscoach.apk"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val UPDATES_DIR = "updates"
        const val BUFFER_BYTES = 64 * 1024
        const val TIMEOUT_MS = 30_000
    }
}
