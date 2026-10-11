package app.copy.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.copy.domain.CaptureController
import app.copy.domain.CapturePhase
import app.copy.domain.Clip
import app.copy.domain.ClipRepository
import app.copy.domain.LanDevice
import app.copy.domain.LanTransferRepository
import app.copy.domain.LanTransferState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CopyUiState(
    val clips: List<Clip> = emptyList(),
    val capturePhase: CapturePhase = CapturePhase.Idle,
    val importing: Boolean = false,
    val exporting: Boolean = false,
    val requestingConsent: Boolean = false,
    val message: String? = null,
    val lan: LanUiState = LanUiState(),
)

data class LanUiState(
    val devices: List<LanDevice> = emptyList(),
    val discovering: Boolean = false,
    val discoveryMessage: String? = null,
    val transfer: LanTransferState = LanTransferState(),
    val selectedClipId: String? = null,
    val addingDevice: Boolean = false,
    val message: String? = null,
)

class CopyViewModel(
    private val clips: ClipRepository,
    private val capture: CaptureController,
    private val savedState: SavedStateHandle,
    private val lan: LanTransferRepository,
) : ViewModel() {
    private data class Operation(val exporting: Boolean = false, val consent: Boolean = false, val message: String? = null)
    private val operation = MutableStateFlow(Operation())
    private data class LanOperation(val adding: Boolean = false, val message: String? = null)
    private val lanOperation = MutableStateFlow(LanOperation())
    private val selectedClipId = MutableStateFlow(savedState.get<String>(LAN_CLIP_ID))
    private val lanState = combine(lan.devices, lan.discovering, lan.discoveryMessage, lan.transfer, lanOperation) { devices, discovering, message, transfer, op ->
        LanUiState(devices, discovering, message, transfer, addingDevice = op.adding, message = op.message)
    }.combine(selectedClipId) { state, id -> state.copy(selectedClipId = id) }
    private val captureState = combine(clips.clips, capture.phase, clips.importing, operation, capture.requestingConsent) { list, phase, importing, op, requesting ->
        CopyUiState(list, phase, importing, op.exporting, op.consent || requesting, op.message)
    }
    val uiState = captureState.combine(lanState) { state, network -> state.copy(lan = network) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CopyUiState())

    init {
        viewModelScope.launch { capture.message.collect { if (it != null) showMessage(it) } }
        viewModelScope.launch { capture.requestingConsent.collect { if (it) operation.value = operation.value.copy(message = null) } }
    }
    fun refresh() = viewModelScope.launch {
        perform("Could not load clips") {
            clips.refresh()
            val selected = selectedClipId.value
            if (selected != null && clips.clips.value.none { it.id == selected }) dismissTransfer()
        }
    }
    fun beginCaptureRequest(): Boolean {
        if (capture.phase.value.active || capture.requestingConsent.value || operation.value.consent) return false
        operation.value = operation.value.copy(consent = true, message = null)
        return true
    }
    fun endCaptureRequest() { operation.value = operation.value.copy(consent = false) }
    fun record() { capture.record() }
    fun stop() { capture.stop() }
    fun importClip(uri: String) = viewModelScope.launch {
        perform("Import failed") { clips.importClip(uri); showMessage("Imported. Preview before sharing.") }
    }
    fun deleteClip(id: String) = viewModelScope.launch {
        if (lan.transfer.value.active) { showMessage("Wait for the transfer to finish before deleting clips."); return@launch }
        perform("Could not delete clip") { clips.deleteClip(id); showMessage("Clip deleted") }
    }
    fun showTransfer(clip: Clip) {
        selectedClipId.value = clip.id
        savedState[LAN_CLIP_ID] = clip.id
        lanOperation.value = LanOperation()
        lan.startDiscovery()
    }
    fun dismissTransfer() {
        selectedClipId.value = null
        savedState.remove<String>(LAN_CLIP_ID)
        lan.stopDiscovery()
    }
    fun refreshDevices() { lan.stopDiscovery(); lan.startDiscovery() }
    fun resumeDiscovery() { if (selectedClipId.value != null) lan.startDiscovery() }
    fun pauseDiscovery() { lan.stopDiscovery() }
    fun addDevice(address: String) {
        if (lanOperation.value.adding) return
        lanOperation.value = LanOperation(adding = true)
        viewModelScope.launch {
            try { lan.addDevice(address); lanOperation.value = LanOperation(message = "Computer added. Select it below.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { lanOperation.value = LanOperation(message = error.message ?: "Could not find that computer.") }
            finally { lanOperation.value = lanOperation.value.copy(adding = false) }
        }
    }
    fun sendClip(deviceId: String, pin: String) {
        if (lan.transfer.value.active) return
        val clip = clips.clips.value.firstOrNull { it.id == selectedClipId.value } ?: return
        val device = lan.devices.value.firstOrNull { it.id == deviceId }
        if (device == null) { lanOperation.value = LanOperation(message = "Computer is no longer available. Refresh or add its IP address."); return }
        lanOperation.value = LanOperation()
        try { lan.send(clip, device, pin) }
        catch (error: Exception) { lanOperation.value = LanOperation(message = error.message ?: "Could not start the transfer.") }
    }
    fun cancelTransfer() { lan.cancel() }
    override fun onCleared() { lan.stopDiscovery(); super.onCleared() }
    fun prepareExport(id: String) { savedState[EXPORT_ID] = id }
    fun exportTo(destinationUri: String?) {
        val id = savedState.remove<String>(EXPORT_ID) ?: return
        if (destinationUri == null || operation.value.exporting) return
        operation.value = operation.value.copy(exporting = true)
        viewModelScope.launch {
            try { perform("Export failed") { clips.exportClip(id, destinationUri); showMessage("Exported to Files") } }
            finally { operation.value = operation.value.copy(exporting = false) }
        }
    }
    fun showMessage(message: String) { operation.value = operation.value.copy(message = message) }
    private suspend fun perform(prefix: String, action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { showMessage("$prefix: ${error.message ?: error.javaClass.simpleName}") }
    }
    companion object {
        private const val EXPORT_ID = "exportClipId"
        private const val LAN_CLIP_ID = "lanClipId"
    }
}
