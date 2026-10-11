package app.copy.data

import android.content.Context
import app.copy.data.capture.AndroidCaptureController
import app.copy.data.storage.AndroidClipRepository
import app.copy.data.transfer.AndroidLanTransferRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-lifetime dependencies, shared by the UI and foreground service. */
class CopyContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val clips = AndroidClipRepository(context.applicationContext)
    val capture = AndroidCaptureController(context.applicationContext)
    val lan = AndroidLanTransferRepository(context.applicationContext)
    init { applicationScope.launch { clips.refresh() } }
}

interface CopyDependencies { val container: CopyContainer }
