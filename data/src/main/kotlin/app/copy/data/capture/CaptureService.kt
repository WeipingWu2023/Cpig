package app.copy.data.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.copy.data.CopyDependencies
import app.copy.data.R
import app.copy.domain.CapturePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dependencies get() = (application as CopyDependencies).container
    private var engine: CaptureEngine? = null
    private var captureJob: Job? = null
    private var readyExpiryJob: Job? = null
    private var projection: MediaProjection? = null
    private var ending = false
    private var finishMessage = "Capture stopped."
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { stopRecording() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PREPARE -> prepare(requireNotNull(intent))
            RECORD -> startRecording()
            SCREENSHOT -> takeScreenshot()
            STOP -> stopRecording()
            else -> finish("Capture setup ended.")
        }
        return START_NOT_STICKY
    }

    private fun prepare(intent: Intent) {
        if (ending || projection != null || captureJob != null) return
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW))
        try {
            // Meet the foreground deadline before acquiring the projection token.
            startForeground(1, notification(CapturePhase.Preparing), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            if (dependencies.capture.stopRequested) { finish("Capture setup cancelled."); return }
            val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(CONSENT, Intent::class.java) else {
                @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(CONSENT)
            }
            val token = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(RESULT, 0), requireNotNull(consent) { "Missing screen capture permission" })
            projection = requireNotNull(token) { "Screen capture permission expired" }
            token.registerCallback(callback, Handler(Looper.getMainLooper()))
            if (dependencies.capture.stopRequested) { finish("Capture setup cancelled."); return }
            update(CapturePhase.Ready)
            // Unused consent tokens expire on newer Android releases. Never begin recording automatically.
            readyExpiryJob = scope.launch {
                delay(4 * 60_000L)
                if (dependencies.capture.phase.value == CapturePhase.Ready) {
                    finish("Capture setup expired. Tap Record or Screenshot to approve again.")
                }
            }
        } catch (error: Exception) { finish("Could not prepare capture: ${error.message}") }
    }

    private fun startRecording() {
        if (ending || captureJob != null) return
        if (dependencies.capture.stopRequested) { finish("Capture setup cancelled."); return }
        val token = projection ?: run { finish("Approve capture before recording."); return }
        readyExpiryJob?.cancel()
        readyExpiryJob = null
        update(CapturePhase.Preparing)
        val recorder = CaptureEngine(this, token)
        engine = recorder
        captureJob = scope.launch {
            try {
                val clip = withContext(Dispatchers.IO) {
                    recorder.capture(
                        onRecording = { scope.launch {
                            if (!ending && !dependencies.capture.stopRequested && dependencies.capture.phase.value != CapturePhase.Saving) {
                                update(CapturePhase.Recording)
                            }
                        } },
                        onSaving = { scope.launch { if (!ending) update(CapturePhase.Saving) } },
                    )
                }
                dependencies.clips.recorded(clip.file, clip.audioAppearsSilent)
                finish(if (clip.audioAppearsSilent) "Saved. Audio appears silent — preview before sharing." else "Saved. Ready to preview and share.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { finish("Recording failed: ${error.message ?: error.javaClass.simpleName}") }
        }
    }

    private fun stopRecording() {
        if (ending) return
        engine?.let {
            it.requestStop()
            update(CapturePhase.Saving)
        } ?: run {
            captureJob?.cancel()
            finish("Capture cancelled. No new screenshot or recording was saved.")
        }
    }

    private fun takeScreenshot() {
        if (ending || captureJob != null) return
        if (dependencies.capture.stopRequested) { finish("Capture setup cancelled."); return }
        val token = projection ?: run { finish("Approve screen capture before taking a screenshot."); return }
        readyExpiryJob?.cancel()
        readyExpiryJob = null
        update(CapturePhase.Preparing)
        captureJob = scope.launch {
            try {
                val screenshot = withContext(Dispatchers.IO) {
                    ScreenshotEngine(this@CaptureService, token).capture {
                        scope.launch { if (!ending) update(CapturePhase.Saving) }
                    }
                }
                dependencies.clips.recorded(screenshot, false)
                finish("Screenshot saved. Ready to preview, share or send to your computer.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { finish("Screenshot failed: ${error.message ?: error.javaClass.simpleName}") }
        }
    }
    private fun update(phase: CapturePhase) {
        dependencies.capture.update(phase)
        getSystemService(NotificationManager::class.java).notify(1, notification(phase))
    }
    private fun notification(phase: CapturePhase): Notification {
        val launch = requireNotNull(packageManager.getLaunchIntentForPackage(packageName))
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val message = when (phase) {
            CapturePhase.Ready -> R.string.notification_ready
            CapturePhase.Recording -> R.string.notification_recording
            CapturePhase.Saving -> R.string.notification_saving
            else -> R.string.notification_preparing
        }
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notification_title)).setContentText(getString(message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .apply {
                if (phase != CapturePhase.Saving) {
                    val label = if (phase == CapturePhase.Ready || phase == CapturePhase.Preparing) R.string.cancel_setup else R.string.stop_and_save
                    addAction(Notification.Action.Builder(null, getString(label), stop).build())
                }
            }
            .build()
    }
    private fun releaseProjection() {
        projection?.let {
            runCatching { it.unregisterCallback(callback) }
            runCatching { it.stop() }
        }
        projection = null
    }
    private fun finish(message: String) {
        if (ending) return
        ending = true
        finishMessage = message
        readyExpiryJob?.cancel()
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        engine?.requestStop()
        readyExpiryJob?.cancel()
        releaseProjection()
        scope.cancel()
        engine = null
        // Publish Idle only after releasing this service, so a new setup cannot race teardown.
        if (dependencies.capture.phase.value.active) dependencies.capture.finish(finishMessage)
        super.onDestroy()
    }
    companion object {
        internal const val PREPARE = "app.copy.PREPARE"
        internal const val RECORD = "app.copy.RECORD"
        internal const val SCREENSHOT = "app.copy.SCREENSHOT"
        internal const val STOP = "app.copy.STOP"
        internal const val CONSENT = "consent"
        internal const val RESULT = "result"
        private const val CHANNEL = "capture"
    }
}
