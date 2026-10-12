package app.copy.data.capture

import android.content.Context
import android.content.Intent
import app.copy.domain.CaptureController
import app.copy.domain.CapturePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidCaptureController(private val context: Context) : CaptureController {
    private val mutablePhase = MutableStateFlow(CapturePhase.Idle)
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val mutableRequestingConsent = MutableStateFlow(false)
    override val phase = mutablePhase.asStateFlow()
    override val message = mutableMessage.asStateFlow()
    override val requestingConsent = mutableRequestingConsent.asStateFlow()
    @Volatile internal var stopRequested = false
        private set

    @Synchronized fun beginConsentRequest(allowReady: Boolean = false): Boolean {
        if ((phase.value.active && !(allowReady && phase.value == CapturePhase.Ready)) || mutableRequestingConsent.value) return false
        mutableRequestingConsent.value = true
        mutableMessage.value = null
        return true
    }

    fun endConsentRequest() { mutableRequestingConsent.value = false }
    fun reportMessage(message: String) { mutableMessage.value = message }

    /** Reserves the foreground session, but creates no recording files or virtual display. */
    @Synchronized fun prepare(resultCode: Int, consent: Intent) {
        endConsentRequest()
        if (phase.value.active) return
        stopRequested = false
        mutablePhase.value = CapturePhase.Preparing
        mutableMessage.value = null
        try {
            context.startForegroundService(Intent(context, CaptureService::class.java)
                .setAction(CaptureService.PREPARE)
                .putExtra(CaptureService.CONSENT, consent).putExtra(CaptureService.RESULT, resultCode))
        } catch (error: RuntimeException) {
            finish("Could not prepare capture: ${error.message}")
        }
    }

    @Synchronized override fun record() {
        if (phase.value != CapturePhase.Ready || stopRequested) return
        // Reserve the transition before enqueueing the service action, ignoring repeated taps.
        mutablePhase.value = CapturePhase.Preparing
        sendAction(CaptureService.RECORD)
    }

    @Synchronized override fun screenshot() {
        if (phase.value != CapturePhase.Ready || stopRequested || requestingConsent.value) return
        mutablePhase.value = CapturePhase.Preparing
        sendAction(CaptureService.SCREENSHOT)
    }

    @Synchronized override fun stop() {
        if (!phase.value.canStop || stopRequested) return
        stopRequested = true
        sendAction(CaptureService.STOP)
    }

    private fun sendAction(action: String) {
        try {
            context.startService(Intent(context, CaptureService::class.java).setAction(action))
        } catch (error: RuntimeException) {
            runCatching { context.stopService(Intent(context, CaptureService::class.java)) }
            finish("Capture ended: ${error.message}")
        }
    }

    internal fun update(phase: CapturePhase) { mutablePhase.value = phase }
    internal fun finish(message: String) {
        endConsentRequest()
        mutableMessage.value = message
        mutablePhase.value = CapturePhase.Idle
    }
}
