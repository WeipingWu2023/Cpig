package app.copy.domain

import kotlinx.coroutines.flow.StateFlow

interface ClipRepository {
    val clips: StateFlow<List<Clip>>
    val importing: StateFlow<Boolean>
    suspend fun refresh()
    suspend fun importClip(sourceUri: String)
    suspend fun exportClip(id: String, destinationUri: String)
    suspend fun deleteClip(id: String)
}

interface CaptureController {
    val phase: StateFlow<CapturePhase>
    val message: StateFlow<String?>
    val requestingConsent: StateFlow<Boolean>
    fun record()
    fun screenshot()
    fun stop()
}
