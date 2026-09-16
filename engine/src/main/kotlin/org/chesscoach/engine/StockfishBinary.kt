package org.chesscoach.engine

import android.content.Context
import java.io.File

/**
 * Находит исполняемый файл движка.
 *
 * Бинарник намеренно лежит в jniLibs под именем `libstockfish.so`, хотя это не
 * библиотека, а обычный исполняемый ELF. Причина: начиная с Android 10 SELinux
 * запрещает запускать файлы из папки данных приложения, а `nativeLibraryDir`
 * помечен исполняемым. Скачать движок после установки нельзя в принципе —
 * только положить внутрь APK.
 *
 * Его не надо грузить через `System.loadLibrary` — он запускается как процесс.
 */
object StockfishBinary {

    const val FILE_NAME: String = "libstockfish.so"

    fun locate(context: Context): Result<File> {
        val dir = context.applicationInfo.nativeLibraryDir
            ?: return Result.failure(IllegalStateException("nativeLibraryDir не задан"))

        val file = File(dir, FILE_NAME)
        return when {
            !file.exists() -> Result.failure(
                IllegalStateException(
                    "Файл $FILE_NAME не найден в $dir. " +
                        "Скорее всего, CI не положил бинарник в jniLibs/arm64-v8a."
                )
            )
            file.length() == 0L -> Result.failure(
                IllegalStateException("Файл $FILE_NAME пустой")
            )
            !file.canExecute() -> Result.failure(
                IllegalStateException(
                    "Файл $FILE_NAME не исполняемый. Проверьте, что в манифесте " +
                        "стоит android:extractNativeLibs=\"true\"."
                )
            )
            else -> Result.success(file)
        }
    }
}
