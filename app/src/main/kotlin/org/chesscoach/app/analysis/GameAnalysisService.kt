package org.chesscoach.app.analysis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.chesscoach.app.AppContainer
import org.chesscoach.core.GameReplay
import org.chesscoach.core.GameSummary

/**
 * Сканирует партию движком целиком в фоне.
 *
 * Обычная корутина умерла бы вместе с процессом, как только пользователь
 * свернёт приложение посреди разбора, который занимает минуту-две. Foreground
 * service с уведомлением о прогрессе — единственный способ на Android
 * гарантировать, что систему не убьёт его раньше времени.
 *
 * Движок один на всё приложение: сервис берёт тот же тёплый процесс через
 * [AppContainer], а не поднимает свой.
 */
class GameAnalysisService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeScans = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gameId = intent?.getStringExtra(EXTRA_GAME_ID)
        val movesSan = intent?.getStringArrayListExtra(EXTRA_MOVES)
        if (gameId == null || movesSan == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val initialFen = intent.getStringExtra(EXTRA_INITIAL_FEN)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(0, movesSan.size),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        // Партии открываются подряд, и сканы идут вместе: сервис останавливает
        // только последний из них, иначе короткая партия оборвала бы длинную.
        activeScans.incrementAndGet()
        scope.launch {
            runAnalysis(gameId, movesSan, initialFen)
            if (activeScans.decrementAndGet() == 0) stopSelf()
        }

        return START_NOT_STICKY
    }

    private suspend fun runAnalysis(gameId: String, movesSan: List<String>, initialFen: String?) {
        val positions = GameReplay.replay(movesSan, initialFen)
        if (positions.isEmpty()) {
            GameAnalysisProgress.update(gameId, ScanState.Failed("Не удалось разобрать партию на ходы"))
            return
        }

        val runner = GameAnalysisRunner(AppContainer.get(applicationContext).engine)

        runner.analyze(positions) { done, total ->
            notificationManager.notify(NOTIFICATION_ID, buildNotification(done, total))
            GameAnalysisProgress.update(gameId, ScanState.Running(done, total))
        }.onSuccess { evaluations ->
            GameAnalysisStore.write(applicationContext, gameId, evaluations)
            GameAnalysisProgress.update(gameId, ScanState.Done(evaluations))
        }.onFailure { error ->
            GameAnalysisProgress.update(gameId, ScanState.Failed(error.message ?: "Движок недоступен"))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Разбор партий", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Разбор партии")
            .setContentText("Разбор партии: $done из $total")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total.coerceAtLeast(1), done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "game_analysis"
        private const val NOTIFICATION_ID = 42

        private const val EXTRA_GAME_ID = "game_id"
        private const val EXTRA_MOVES = "moves_san"
        private const val EXTRA_INITIAL_FEN = "initial_fen"

        /** Запускает скан партии. Прогресс приходит через [GameAnalysisProgress]. */
        fun start(context: Context, game: GameSummary) {
            // Сразу, до фактического старта сервиса — экран не должен ждать,
            // пока система поднимет процесс, чтобы показать прогресс вместо кнопки.
            GameAnalysisProgress.update(game.id, ScanState.Running(0, game.movesCount))

            val intent = Intent(context, GameAnalysisService::class.java)
                .putExtra(EXTRA_GAME_ID, game.id)
                .putStringArrayListExtra(EXTRA_MOVES, ArrayList(game.movesSan))
                .putExtra(EXTRA_INITIAL_FEN, game.initialFen)

            ContextCompat.startForegroundService(context, intent)
        }
    }
}
