package app.copy.domain

import kotlinx.coroutines.flow.StateFlow

data class LanDevice(
    val id: String,
    val name: String,
    val address: String,
    val port: Int = 53317,
    val deviceModel: String? = null,
)

enum class LanTransferPhase {
    Idle, Hashing, WaitingForReceiver, Sending, Complete, Failed, Cancelled;
    val active: Boolean get() = this == Hashing || this == WaitingForReceiver || this == Sending
}

data class LanTransferState(
    val phase: LanTransferPhase = LanTransferPhase.Idle,
    val clipName: String = "",
    val deviceName: String = "",
    val bytesSent: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null,
) {
    val active: Boolean get() = phase.active
}

interface LanTransferRepository {
    val devices: StateFlow<List<LanDevice>>
    val discovering: StateFlow<Boolean>
    val discoveryMessage: StateFlow<String?>
    val transfer: StateFlow<LanTransferState>
    fun startDiscovery()
    fun stopDiscovery()
    suspend fun addDevice(address: String)
    fun send(clip: Clip, device: LanDevice, pin: String)
    fun cancel()
}

/** Numeric local IPv4 addresses only: never resolve a user-supplied hostname or URL. */
object LanAddress {
    fun parse(value: String): Pair<String, Int> {
        val parts = value.trim().split(':')
        require(parts.size in 1..2) { "Enter the computer's local IPv4 address, optionally followed by :port." }
        val octets = parts[0].split('.')
        require(octets.size == 4 && octets.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) }) {
            "Enter a local IPv4 address such as 192.168.1.10."
        }
        val numbers = octets.map { it.toInt() }
        require(numbers.all { it in 0..255 }) { "Invalid IPv4 address." }
        require(numbers[0] == 10 || numbers[0] == 172 && numbers[1] in 16..31 ||
            numbers[0] == 192 && numbers[1] == 168 || numbers[0] == 169 && numbers[1] == 254 ||
            numbers[0] == 127) { "Copig transfers only to local network addresses." }
        val port = if (parts.size == 1) 53317 else parts[1].toIntOrNull() ?: 0
        require(port in 1..65535) { "Invalid port." }
        return parts[0] to port
    }
}
