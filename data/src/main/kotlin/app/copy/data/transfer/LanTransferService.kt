package app.copy.data.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import app.copy.data.CopyDependencies
import app.copy.domain.LanTransferPhase
import app.copy.domain.LanTransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** A transfer continues when the phone leaves the library screen. */
class LanTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (application as CopyDependencies).container.lan
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Copig local transfers", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SEND -> {
                if (ending) return START_NOT_STICKY
                try {
                    startForeground(NOTIFICATION_ID, notification(repository.transfer.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    if (!repository.beginPending(scope) { mainHandler.post { finish() } }) {
                        if (!repository.transfer.value.active) finish()
                        return START_NOT_STICKY
                    }
                    // Bounded lock: a stuck network request must never keep the phone awake indefinitely.
                    wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Copig:local-transfer")
                        .apply { acquire(30 * 60_000L) }
                    scope.launch { repository.transfer.collect { state ->
                        if (!ending && state.active) getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(state))
                    } }
                } catch (error: RuntimeException) {
                    repository.cancel()
                    finish()
                }
            }
            CANCEL -> repository.cancel()
            else -> finish()
        }
        return START_NOT_STICKY
    }

    private fun notification(state: LanTransferState): Notification {
        val launch = requireNotNull(packageManager.getLaunchIntentForPackage(packageName))
        val open = PendingIntent.getActivity(this, 20, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 21, Intent(this, LanTransferService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val percent = if (state.totalBytes == 0L) 0 else (state.bytesSent * 100 / state.totalBytes).toInt().coerceIn(0, 100)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Copig → ${state.deviceName}")
            .setContentText(state.message ?: state.clipName).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, percent, state.phase != LanTransferPhase.Sending)
            .addAction(Notification.Action.Builder(null, "Cancel transfer", cancel).build())
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        repository.cancel()
        finish()
    }

    private fun finish() {
        if (ending) return
        ending = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        repository.serviceDestroyed()
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        internal const val SEND = "app.copy.LAN_SEND"
        internal const val CANCEL = "app.copy.LAN_CANCEL"
        private const val CHANNEL = "lan_transfer"
        private const val NOTIFICATION_ID = 3
    }
}
