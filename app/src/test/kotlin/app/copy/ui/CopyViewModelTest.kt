package app.copy.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import app.copy.domain.CaptureController
import app.copy.domain.CapturePhase
import app.copy.domain.Clip
import app.copy.domain.ClipRepository
import app.copy.domain.LanDevice
import app.copy.domain.LanTransferPhase
import app.copy.domain.LanTransferRepository
import app.copy.domain.LanTransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CopyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clips = FakeClips()
    private val capture = FakeCapture()
    private val lan = FakeLan()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun model(savedState: SavedStateHandle = SavedStateHandle()) =
        CopyViewModel(clips, capture, savedState, lan).also { store.put("copy", it) }

    @Test fun recordingChangesReachUiAndDuplicateConsentIsBlocked() = runTest(dispatcher) {
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.showMessage("Previous failure")
        assertTrue(vm.beginCaptureRequest())
        advanceUntilIdle()
        assertNull(vm.uiState.value.message)
        assertFalse(vm.beginCaptureRequest())
        vm.endCaptureRequest()
        capture.phase.value = CapturePhase.Recording
        advanceUntilIdle()
        assertEquals(CapturePhase.Recording, vm.uiState.value.capturePhase)
        assertFalse(vm.beginCaptureRequest())
        vm.stop()
        assertTrue(capture.stopped)
    }
    @Test fun restoredExportSelectionUsesRepositoryAndCancelledPickerDoesNotExport() = runTest(dispatcher) {
        val vm = model(SavedStateHandle(mapOf("exportClipId" to "saved.mp4")))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.exportTo("content://destination/one")
        advanceUntilIdle()
        assertEquals("saved.mp4" to "content://destination/one", clips.exported)
        assertFalse(vm.uiState.value.exporting)
        clips.exported = null
        vm.prepareExport("cancelled.mp4")
        vm.exportTo(null)
        advanceUntilIdle()
        assertNull(clips.exported)
    }
    @Test fun readyWaitsForExplicitRecordAndSharedConsentBlocksAnotherRequest() = runTest(dispatcher) {
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.showMessage("Previous recording failed")
        capture.requestingConsent.value = true
        advanceUntilIdle()
        assertTrue(vm.uiState.value.requestingConsent)
        assertNull(vm.uiState.value.message)
        assertFalse(vm.beginCaptureRequest())
        capture.requestingConsent.value = false
        capture.phase.value = CapturePhase.Ready
        advanceUntilIdle()
        assertEquals(CapturePhase.Ready, vm.uiState.value.capturePhase)
        assertFalse(vm.uiState.value.requestingConsent)
        assertEquals(0, capture.recordCalls)
        assertFalse(vm.beginCaptureRequest())
        vm.record()
        assertEquals(1, capture.recordCalls)
        vm.stop()
        assertTrue(capture.stopped)
    }
    @Test fun importFailureBecomesVisibleStateWithoutDiscardingLibrary() = runTest(dispatcher) {
        val existing = Clip("existing.mp4", "existing.mp4", 10, 1, "video/mp4", "content://clips/existing")
        clips.clips.value = listOf(existing)
        clips.failImport = true
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.importClip("content://source/broken")
        advanceUntilIdle()
        assertEquals(listOf(existing), vm.uiState.value.clips)
        assertTrue(vm.uiState.value.message.orEmpty().contains("Import failed"))
    }
    @Test fun selectionDiscoversAndDismissalKeepsTheRunningTransfer() = runTest(dispatcher) {
        val clip = savedClip()
        clips.clips.value = listOf(clip)
        val savedState = SavedStateHandle()
        val vm = model(savedState)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.showTransfer(clip)
        advanceUntilIdle()
        assertEquals(1, lan.discoveryStarts)
        assertEquals(clip.id, savedState.get<String>("lanClipId"))
        assertEquals(clip.id, vm.uiState.value.lan.selectedClipId)

        lan.transfer.value = LanTransferState(phase = LanTransferPhase.Sending)
        vm.dismissTransfer()
        advanceUntilIdle()
        assertEquals(1, lan.discoveryStops)
        assertNull(savedState.get<String>("lanClipId"))
        assertNull(vm.uiState.value.lan.selectedClipId)
        assertTrue(vm.uiState.value.lan.transfer.active)
        assertEquals(0, lan.cancelCalls)

        vm.cancelTransfer()
        advanceUntilIdle()
        assertEquals(1, lan.cancelCalls)
        assertFalse(vm.uiState.value.lan.transfer.active)
    }
    @Test fun restoredSelectionResumesDiscoveryOnlyWhilePanelIsSelected() = runTest(dispatcher) {
        val clip = savedClip()
        clips.clips.value = listOf(clip)
        val vm = model(SavedStateHandle(mapOf("lanClipId" to clip.id)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(clip.id, vm.uiState.value.lan.selectedClipId)

        vm.resumeDiscovery()
        assertEquals(1, lan.discoveryStarts)
        vm.pauseDiscovery()
        assertEquals(1, lan.discoveryStops)
        vm.resumeDiscovery()
        assertEquals(2, lan.discoveryStarts)
        vm.dismissTransfer()
        vm.resumeDiscovery()
        assertEquals(2, lan.discoveryStarts)
    }
    @Test fun sendUsesRetainedClipAndChosenPeerAndRejectsDuplicateActiveSend() = runTest(dispatcher) {
        val clip = savedClip()
        val peer = LanDevice("windows-one", "My computer", "192.168.1.20")
        clips.clips.value = listOf(clip)
        lan.devices.value = listOf(peer)
        val vm = model()
        // Resolve the actual stored clip, rather than passing stale UI metadata to the sender.
        vm.showTransfer(clip.copy(name = "stale.mp4"))
        vm.sendClip(peer.id, "123456")
        assertEquals(listOf(Triple(clip, peer, "123456")), lan.sent)
        vm.sendClip(peer.id, "654321")
        assertEquals(1, lan.sent.size)
    }
    @Test fun activeTransferPreventsDeletingAFileUntilTheTransferFinishes() = runTest(dispatcher) {
        val clip = savedClip()
        clips.clips.value = listOf(clip)
        lan.transfer.value = LanTransferState(phase = LanTransferPhase.Sending, clipName = clip.name)
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.deleteClip(clip.id)
        advanceUntilIdle()
        assertEquals(listOf(clip), clips.clips.value)
        assertEquals(0, clips.deleteCalls)
        assertTrue(vm.uiState.value.message.orEmpty().contains("Wait for the transfer"))

        lan.transfer.value = LanTransferState(phase = LanTransferPhase.Complete, clipName = clip.name)
        vm.deleteClip(clip.id)
        advanceUntilIdle()
        assertEquals(1, clips.deleteCalls)
        assertTrue(clips.clips.value.isEmpty())
    }
    @Test fun disappearingComputerIsReportedWithoutStartingATransfer() = runTest(dispatcher) {
        val clip = savedClip()
        clips.clips.value = listOf(clip)
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.showTransfer(clip)
        vm.sendClip("computer-that-left", "123456")
        advanceUntilIdle()
        assertTrue(lan.sent.isEmpty())
        assertTrue(vm.uiState.value.lan.message.orEmpty().contains("no longer available"))
        assertEquals(clip.id, vm.uiState.value.lan.selectedClipId)
    }
    @Test fun refreshRestartsDiscoveryAndClearsAnUnavailableRestoredClip() = runTest(dispatcher) {
        val vm = model(SavedStateHandle(mapOf("lanClipId" to "removed.mp4")))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.refreshDevices()
        assertEquals(1, lan.discoveryStops)
        assertEquals(1, lan.discoveryStarts)
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.uiState.value.lan.selectedClipId)
        assertEquals(2, lan.discoveryStops)
    }
    @Test fun synchronousSenderValidationFailureAppearsInThePanel() = runTest(dispatcher) {
        val clip = savedClip()
        val peer = LanDevice("windows-one", "My computer", "192.168.1.20")
        clips.clips.value = listOf(clip)
        lan.devices.value = listOf(peer)
        lan.failSend = true
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.showTransfer(clip)
        vm.sendClip(peer.id, "123456")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.lan.message.orEmpty().contains("Receiver changed"))
        assertFalse(vm.uiState.value.lan.transfer.active)
        assertEquals(listOf(clip), vm.uiState.value.clips)
    }
    private fun savedClip() = Clip("saved.mp4", "saved.mp4", 100, 1, "video/mp4", "content://clips/saved")
    private class FakeClips : ClipRepository {
        override val clips = MutableStateFlow<List<Clip>>(emptyList())
        override val importing = MutableStateFlow(false)
        var exported: Pair<String, String>? = null
        var failImport = false
        var deleteCalls = 0
        override suspend fun refresh() = Unit
        override suspend fun importClip(sourceUri: String) { if (failImport) error("Unreadable media") }
        override suspend fun exportClip(id: String, destinationUri: String) { exported = id to destinationUri }
        override suspend fun deleteClip(id: String) { deleteCalls++; clips.value = clips.value.filterNot { it.id == id } }
    }
    private class FakeCapture : CaptureController {
        override val phase = MutableStateFlow(CapturePhase.Idle)
        override val requestingConsent = MutableStateFlow(false)
        override val message = MutableStateFlow<String?>(null)
        var stopped = false
        var recordCalls = 0
        override fun record() { recordCalls++ }
        override fun screenshot() = Unit
        override fun stop() { stopped = true }
    }
    private class FakeLan : LanTransferRepository {
        override val devices = MutableStateFlow<List<LanDevice>>(emptyList())
        override val discovering = MutableStateFlow(false)
        override val discoveryMessage = MutableStateFlow<String?>(null)
        override val transfer = MutableStateFlow(LanTransferState())
        var discoveryStarts = 0
        var discoveryStops = 0
        var cancelCalls = 0
        var failSend = false
        val sent = mutableListOf<Triple<Clip, LanDevice, String>>()
        override fun startDiscovery() { discoveryStarts++; discovering.value = true }
        override fun stopDiscovery() { discoveryStops++; discovering.value = false }
        override suspend fun addDevice(address: String) = Unit
        override fun send(clip: Clip, device: LanDevice, pin: String) {
            if (failSend) error("Receiver changed. Discover it again.")
            sent += Triple(clip, device, pin)
            transfer.value = LanTransferState(phase = LanTransferPhase.Hashing, clipName = clip.name, deviceName = device.name)
        }
        override fun cancel() { cancelCalls++; transfer.value = transfer.value.copy(phase = LanTransferPhase.Cancelled) }
    }
}
