package app.copy.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.getValue
import app.copy.CopyApplication
import app.copy.R
import app.copy.domain.Clip
import app.copy.domain.ClipPolicy

/** Android permission/intent boundary. It never owns files or recording resources. */
class MainActivity : ComponentActivity() {
    private val container get() = (application as CopyApplication).container
    private val viewModel: CopyViewModel by viewModels {
        viewModelFactory { initializer { CopyViewModel(container.clips, container.capture, createSavedStateHandle(), container.lan) } }
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) showFloatingControls()
        else viewModel.showMessage(getString(R.string.overlay_permission_reason))
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importClip(uri.toString())
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.exportTo(if (result.resultCode == RESULT_OK) result.data?.data?.toString() else null)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val floatingVisible by FloatingControls.visible.collectAsStateWithLifecycle()
            CopyTheme {
                CopyScreen(state, floatingVisible = floatingVisible, onFloating = ::toggleFloatingControls,
                    onRecord = ::record, onStop = viewModel::stop,
                    onImport = { importFile.launch(arrayOf("video/*", "audio/*")) },
                    onPreview = { openClip(it, share = false) }, onShare = { openClip(it, share = true) },
                    onExport = ::export, onDelete = { viewModel.deleteClip(it.id) }, onCopyPrompt = ::copyPrompt,
                    onSendToComputer = viewModel::showTransfer, onDismissTransfer = viewModel::dismissTransfer,
                    onRefreshDevices = viewModel::refreshDevices, onAddDevice = viewModel::addDevice,
                    onSendClip = viewModel::sendClip, onCancelTransfer = viewModel::cancelTransfer)
            }
        }
        if (savedInstanceState == null) receiveShare(intent)
        viewModel.refresh()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveShare(intent) }
    override fun onStart() { super.onStart(); viewModel.resumeDiscovery() }
    override fun onStop() { viewModel.pauseDiscovery(); super.onStop() }
    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this) && FloatingControls.enabled(this) && !FloatingControls.visible.value) showFloatingControls()
        else if (!Settings.canDrawOverlays(this) && FloatingControls.visible.value) FloatingControls.hide(this)
    }
    private fun record() {
        if (container.capture.phase.value == app.copy.domain.CapturePhase.Ready) viewModel.record()
        else if (viewModel.beginCaptureRequest()) {
            try { CapturePermissionActivity.launch(this) }
            finally { viewModel.endCaptureRequest() }
        }
    }
    private fun toggleFloatingControls() {
        if (FloatingControls.visible.value) {
            FloatingControls.hide(this)
        } else if (Settings.canDrawOverlays(this)) showFloatingControls()
        else {
            try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: ActivityNotFoundException) { viewModel.showMessage(getString(R.string.overlay_permission_reason)) }
        }
    }
    private fun showFloatingControls() {
        try { FloatingControls.show(this) }
        catch (error: RuntimeException) { viewModel.showMessage("Could not show floating controls: ${error.message}") }
    }
    private fun export(clip: Clip) {
        viewModel.prepareExport(clip.id)
        exportFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(clip.mimeType).putExtra(Intent.EXTRA_TITLE, clip.name))
    }
    private fun openClip(clip: Clip, share: Boolean) {
        val uri = Uri.parse(clip.contentUri)
        val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
            if (share) { type = clip.mimeType; putExtra(Intent.EXTRA_STREAM, uri) } else setDataAndType(uri, clip.mimeType)
            clipData = ClipData.newRawUri(clip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(intent, getString(if (share) R.string.share_clip else R.string.preview_clip))) }
        catch (_: ActivityNotFoundException) { viewModel.showMessage(getString(R.string.no_compatible_app)) }
    }
    private fun copyPrompt() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.learning_prompt), ClipPolicy.LEARNING_PROMPT))
        viewModel.showMessage(getString(R.string.prompt_copied))
    }
    private fun receiveShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else {
            @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        intent.action = null
        if (uri != null) viewModel.importClip(uri.toString())
    }
}
