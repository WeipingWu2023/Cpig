package app.copy.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.copy.CopyApplication
import app.copy.R

/** Transparent permission boundary. Finishing returns to the video, with no library screen underneath. */
class CapturePermissionActivity : ComponentActivity() {
    private val capture get() = (application as CopyApplication).container.capture
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) capture.prepare(result.resultCode, requireNotNull(result.data))
        else { capture.endConsentRequest(); capture.reportMessage(getString(R.string.capture_cancelled)) }
        finishAndRemoveTask()
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestProjection()
        else { capture.endConsentRequest(); capture.reportMessage(getString(R.string.audio_permission_reason)); finishAndRemoveTask() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        confirmLaunch()
        if (savedInstanceState != null) return
        val requested = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requested += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requested += Manifest.permission.POST_NOTIFICATIONS
        try {
            if (requested.isEmpty()) requestProjection() else permissions.launch(requested.toTypedArray())
        } catch (error: RuntimeException) {
            capture.endConsentRequest(); capture.reportMessage("Could not request permission: ${error.message}"); finishAndRemoveTask()
        }
    }
    private fun requestProjection() {
        try { projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
        catch (error: RuntimeException) {
            capture.endConsentRequest(); capture.reportMessage("Could not request capture: ${error.message}"); finishAndRemoveTask()
        }
    }
    override fun onDestroy() {
        if (isFinishing) capture.endConsentRequest()
        super.onDestroy()
    }
    companion object {
        private val handler = Handler(Looper.getMainLooper())
        private var launchWatchdog: Runnable? = null
        private fun confirmLaunch() {
            launchWatchdog?.let(handler::removeCallbacks)
            launchWatchdog = null
        }
        fun launch(context: Context) {
            val capture = (context.applicationContext as CopyApplication).container.capture
            if (!capture.beginConsentRequest()) return
            // Some phones silently block background popups without throwing an exception.
            launchWatchdog = Runnable {
                capture.endConsentRequest()
                capture.reportMessage("Capture prompt was blocked. Allow Copig background pop-up windows in phone settings, or set up capture inside Copig.")
                launchWatchdog = null
            }.also { handler.postDelayed(it, 5_000) }
            try {
                context.startActivity(Intent(context, CapturePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            } catch (error: RuntimeException) {
                confirmLaunch()
                capture.endConsentRequest(); capture.reportMessage("Could not open capture permission: ${error.message}")
            }
        }
    }
}
