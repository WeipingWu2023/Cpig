package app.copy.domain

data class Clip(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val mimeType: String,
    val contentUri: String,
    val audioAppearsSilent: Boolean = false,
)

enum class CapturePhase {
    Idle, Preparing, Ready, Recording, Saving;
    val active: Boolean get() = this != Idle
    val canStop: Boolean get() = this == Preparing || this == Ready || this == Recording
}
