package app.copy.data.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import app.copy.domain.Clip
import app.copy.domain.LanAddress
import app.copy.domain.LanDevice
import app.copy.domain.LanTransferPhase
import app.copy.domain.LanTransferRepository
import app.copy.domain.LanTransferState
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AndroidLanTransferRepository(private val context: Context) : LanTransferRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableDevices = MutableStateFlow<List<LanDevice>>(emptyList())
    private val mutableDiscovering = MutableStateFlow(false)
    private val mutableDiscoveryMessage = MutableStateFlow<String?>(null)
    private val mutableTransfer = MutableStateFlow(LanTransferState())
    override val devices = mutableDevices.asStateFlow()
    override val discovering = mutableDiscovering.asStateFlow()
    override val discoveryMessage = mutableDiscoveryMessage.asStateFlow()
    override val transfer = mutableTransfer.asStateFlow()
    private val deviceLock = Any()
    private val transferLock = Any()
    private val lastSeen = mutableMapOf<String, Long>()
    private val manualDevices = mutableSetOf<String>()
    @Volatile private var discoverySocket: MulticastSocket? = null
    private var discoveryJob: Job? = null
    private var discoveryGeneration = 0
    private var transferJob: Job? = null
    private var pending: Request? = null
    private var terminalState: LanTransferState? = null
    private var serviceAttached = false
    @Volatile private var connection: HttpsURLConnection? = null
    private val senderId: String = context.getSharedPreferences("lan_transfer", Context.MODE_PRIVATE).let { preferences ->
        preferences.getString("sender_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("sender_id", it).apply()
        }
    }

    private data class Request(val clip: Clip, val device: LanDevice, val pin: String)

    @Synchronized override fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        mutableDiscovering.value = true
        mutableDiscoveryMessage.value = "Looking for computers on this Wi-Fi…"
        val generation = ++discoveryGeneration
        discoveryJob = scope.launch { discover(generation) }
    }

    @Synchronized override fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryGeneration++
        discoverySocket?.close()
        discoveryJob = null
        mutableDiscovering.value = false
    }

    override suspend fun addDevice(address: String) = withContext(Dispatchers.IO) {
        val (ip, port) = LanAddress.parse(address)
        // First contact sends only discovery metadata. File bytes and PIN are sent after certificate pinning.
        val client = LocalSendConnection(LanDevice("", "Computer", ip, port), bootstrap = true)
        val active = client.open("register")
        try {
            LocalSendConnection.writeJson(active, senderInfo())
            val info = JSONObject(LocalSendConnection.response(active))
            val fingerprint = requireNotNull(client.observedFingerprint) { "Receiver did not present a certificate." }
            require(info.optString("fingerprint").equals(fingerprint, ignoreCase = true)) {
                "Receiver identity does not match its certificate."
            }
            val device = readDevice(info, ip, port) ?: throw IOException("This computer is not a compatible HTTPS receiver.")
            remember(device, manual = true)
            mutableDiscoveryMessage.value = "Found ${device.name}."
        } finally { active.disconnect() }
    }

    override fun send(clip: Clip, device: LanDevice, pin: String) {
        synchronized(transferLock) {
            if (mutableTransfer.value.active) return
            require(pin.isEmpty() || pin.matches(Regex("[0-9]{6}"))) { "Enter the six-digit PIN shown on the computer." }
            LanAddress.parse("${device.address}:${device.port}")
            require(device.id.matches(Regex("[0-9a-fA-F]{64}"))) { "Discover the receiver before sending." }
            val uri = Uri.parse(clip.contentUri)
            require(uri.scheme == "content" && uri.authority == "${context.packageName}.clips") { "Select a saved Copig clip." }
            require(clip.sizeBytes in 1..8L * 1024 * 1024 * 1024) { "Send a non-empty clip smaller than 8 GB." }
            pending = Request(clip, device, pin)
            terminalState = null
            mutableTransfer.value = LanTransferState(LanTransferPhase.Hashing, clip.name, device.name, totalBytes = clip.sizeBytes,
                message = "Checking the clip before sending…")
        }
        try {
            context.startForegroundService(Intent(context, LanTransferService::class.java).setAction(LanTransferService.SEND))
        } catch (error: RuntimeException) {
            synchronized(transferLock) {
                pending = null
                mutableTransfer.value = mutableTransfer.value.copy(phase = LanTransferPhase.Failed,
                    message = "Open Copig and try sending again: ${error.message.orEmpty()}")
            }
        }
    }

    override fun cancel() {
        synchronized(transferLock) {
            if (!mutableTransfer.value.active) return
            if (transferJob == null) {
                if (serviceAttached) return // The previous upload has finished; its service is already closing.
                pending = null
                mutableTransfer.value = mutableTransfer.value.copy(phase = LanTransferPhase.Cancelled, message = "Transfer cancelled.")
            } else {
                transferJob?.cancel()
                val active = connection
                scope.launch { active?.disconnect() }
            }
        }
    }

    internal fun beginPending(serviceScope: CoroutineScope, complete: () -> Unit): Boolean = synchronized(transferLock) {
        val request = pending ?: return false
        if (transferJob?.isActive == true) return false
        pending = null
        serviceAttached = true
        val job = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { upload(request) }
        transferJob = job
        job.invokeOnCompletion { error ->
            synchronized(transferLock) {
                transferJob = null
                // Cancellation can happen before a lazy job ever enters upload's try/finally.
                if (terminalState == null) terminalState = mutableTransfer.value.copy(
                    phase = if (error is CancellationException) LanTransferPhase.Cancelled else LanTransferPhase.Failed,
                    message = if (error is CancellationException) "Transfer cancelled." else "Transfer interrupted. Please try again.",
                )
                if (!serviceAttached) publishTerminal()
            }
            complete()
        }
        job.start()
        true
    }

    internal fun serviceDestroyed() = synchronized(transferLock) {
        serviceAttached = false
        if (transferJob != null) cancel() else publishTerminal()
    }

    private fun publishTerminal() {
        terminalState?.let { mutableTransfer.value = it }
        terminalState = null
    }

    private suspend fun upload(request: Request) {
        val (clip, device, pin) = request
        val client = LocalSendConnection(device)
        var sessionId: String? = null
        var succeeded = false
        var outcome = LanTransferPhase.Failed
        var message = "Transfer interrupted. Please try again."
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(Uri.parse(clip.contentUri)).use { input ->
                if (input == null) throw IOException("Cannot open this clip.")
                var count = 0L
                val bytes = ByteArray(65_536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val length = input.read(bytes)
                    if (length < 0) break
                    digest.update(bytes, 0, length)
                    count += length
                }
                if (count != clip.sizeBytes) throw IOException("The clip changed. Refresh the library and try again.")
            }
            mutableTransfer.value = mutableTransfer.value.copy(phase = LanTransferPhase.WaitingForReceiver, message = "Waiting for ${device.name}…")
            val fileId = UUID.randomUUID().toString()
            val file = JSONObject().put("id", fileId).put("fileName", clip.name).put("size", clip.sizeBytes)
                .put("fileType", clip.mimeType).put("sha256", LocalSendConnection.hex(digest.digest()))
            val preparation = JSONObject().put("info", senderInfo()).put("files", JSONObject().put(fileId, file))
            val prepare = client.open("prepare-upload", if (pin.isEmpty()) "" else "?pin=${encoded(pin)}")
            connection = prepare
            try {
                currentCoroutineContext().ensureActive()
                LocalSendConnection.writeJson(prepare, preparation)
                val result = JSONObject(LocalSendConnection.response(prepare))
                sessionId = result.getString("sessionId").takeIf { it.isNotBlank() && it.length <= 1024 }
                    ?: throw IOException("Receiver returned an invalid session.")
                val token = result.getJSONObject("files").optString(fileId)
                if (token.isBlank() || token.length > 1024) throw IOException("The receiver did not accept this clip.")
                currentCoroutineContext().ensureActive()
                val send = client.open("upload", "?sessionId=${encoded(requireNotNull(sessionId))}&fileId=${encoded(fileId)}&token=${encoded(token)}")
                connection = send
                try {
                    send.setRequestProperty("Content-Type", "application/octet-stream")
                    send.setFixedLengthStreamingMode(clip.sizeBytes)
                    mutableTransfer.value = mutableTransfer.value.copy(phase = LanTransferPhase.Sending, message = "Sending over your local network…")
                    send.outputStream.use { output ->
                        context.contentResolver.openInputStream(Uri.parse(clip.contentUri)).use { input ->
                            if (input == null) throw IOException("Cannot open this clip.")
                            val bytes = ByteArray(65_536)
                            var sent = 0L
                            var lastUpdate = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val length = input.read(bytes)
                                if (length < 0) break
                                if (sent + length > clip.sizeBytes) throw IOException("The clip changed during transfer.")
                                output.write(bytes, 0, length)
                                sent += length
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastUpdate >= 100 || sent == clip.sizeBytes) {
                                    mutableTransfer.value = mutableTransfer.value.copy(bytesSent = sent)
                                    lastUpdate = now
                                }
                            }
                            if (sent != clip.sizeBytes) throw IOException("The clip changed during transfer.")
                        }
                    }
                    LocalSendConnection.response(send)
                    currentCoroutineContext().ensureActive()
                    succeeded = true
                    outcome = LanTransferPhase.Complete
                    message = "Saved on ${device.name}."
                } finally { send.disconnect() }
            } finally { prepare.disconnect() }
        } catch (cancelled: CancellationException) {
            outcome = LanTransferPhase.Cancelled
            message = "Transfer cancelled. Your phone's clip is still saved."
        } catch (error: Exception) {
            // A disconnected blocking socket can report IOException after coroutine cancellation.
            if (!currentCoroutineContext().isActive) {
                outcome = LanTransferPhase.Cancelled
                message = "Transfer cancelled. Your phone's clip is still saved."
            } else {
                message = when (error) {
                    is SSLException -> "Could not verify the receiver certificate. Discover the computer again."
                    is SocketTimeoutException -> "The computer did not respond. Check Wi-Fi, its receiver window, and Windows Firewall."
                    else -> error.message ?: "Could not send the clip. Check the computer and your Wi-Fi."
                }
            }
        } finally {
            connection?.disconnect()
            connection = null
            if (!succeeded && sessionId != null) withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    val cancel = client.open("cancel", "?sessionId=${encoded(requireNotNull(sessionId))}").apply {
                        connectTimeout = 3_000; readTimeout = 3_000; setFixedLengthStreamingMode(0)
                    }
                    try { cancel.outputStream.close(); LocalSendConnection.response(cancel) } finally { cancel.disconnect() }
                }
            }
            // Keep active until the foreground service is gone, so another Send cannot race its teardown.
            synchronized(transferLock) {
                terminalState = mutableTransfer.value.copy(phase = outcome, message = message)
            }
        }
    }

    private suspend fun discover(generation: Int) {
        var multicastLock: WifiManager.MulticastLock? = null
        var socket: MulticastSocket? = null
        try {
            val wifi = context.getSystemService(WifiManager::class.java)
            multicastLock = wifi?.createMulticastLock("Copig discovery")?.apply { setReferenceCounted(false); acquire() }
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces()).filter { network ->
                network.isUp && !network.isLoopback && Collections.list(network.inetAddresses).any {
                    it is Inet4Address && runCatching { LanAddress.parse(it.hostAddress.orEmpty()) }.isSuccess
                }
            }
            if (interfaces.isEmpty()) throw IOException("Connect your phone and computer to the same Wi-Fi.")
            val group = InetAddress.getByName(GROUP)
            val activeSocket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                soTimeout = 1000
                timeToLive = 1
            }
            socket = activeSocket
            discoverySocket = socket
            val joined = interfaces.count { network -> runCatching {
                activeSocket.joinGroup(InetSocketAddress(group, PORT), network)
            }.isSuccess }
            if (joined == 0) throw IOException("This network interface does not support multicast discovery.")
            val bytes = senderInfo().put("announce", true).toString().toByteArray(Charsets.UTF_8)
            var lastAnnouncement = -5000L
            val buffer = ByteArray(8192)
            while (currentCoroutineContext().isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAnnouncement >= 5000) {
                    interfaces.forEach { network -> runCatching {
                        activeSocket.networkInterface = network
                        activeSocket.send(DatagramPacket(bytes, bytes.size, group, PORT))
                    } }
                    lastAnnouncement = now
                    expireDevices(now)
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    activeSocket.receive(packet)
                    val info = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                    val ip = packet.address.hostAddress.orEmpty()
                    val peer = readDevice(info, ip, info.optInt("port", PORT))
                    if (peer != null && peer.id != senderId) {
                        remember(peer)
                        mutableDiscoveryMessage.value = null
                    }
                } catch (_: SocketTimeoutException) { /* Check cancellation and send another announcement. */ }
                catch (_: org.json.JSONException) { /* Ignore unrelated UDP packets. */ }
            }
        } catch (error: Exception) {
            if (currentCoroutineContext().isActive) mutableDiscoveryMessage.value =
                "Automatic discovery unavailable. Enter the computer's IP address instead. ${error.message.orEmpty()}"
        } finally {
            socket?.close()
            if (discoverySocket === socket) discoverySocket = null
            if (multicastLock?.isHeld == true) multicastLock.release()
            synchronized(this) {
                if (discoveryGeneration == generation) mutableDiscovering.value = false
            }
        }
    }

    private fun readDevice(info: JSONObject, address: String, defaultPort: Int): LanDevice? {
        val port = info.optInt("port", defaultPort)
        if (runCatching { LanAddress.parse("$address:$port") }.isFailure) return null
        if (info.optString("protocol", "https") != "https" || !info.optString("version").startsWith("2.")) return null
        val fingerprint = info.optString("fingerprint").lowercase()
        if (!fingerprint.matches(Regex("[0-9a-f]{64}"))) return null
        val alias = info.optString("alias").trim().take(80)
        if (alias.isBlank()) return null
        return LanDevice(fingerprint, alias, address, port, info.optString("deviceModel").take(80).takeIf { it.isNotBlank() && it != "null" })
    }

    private fun remember(device: LanDevice, manual: Boolean = false) = synchronized(deviceLock) {
        if (lastSeen.size >= 128 && device.id !in lastSeen) return@synchronized
        lastSeen[device.id] = SystemClock.elapsedRealtime()
        if (manual) manualDevices.add(device.id)
        mutableDevices.value = (mutableDevices.value.filterNot { it.id == device.id } + device).sortedBy { it.name.lowercase() }
    }

    private fun expireDevices(now: Long) = synchronized(deviceLock) {
        mutableDevices.value = mutableDevices.value.filter { it.id in manualDevices || now - (lastSeen[it.id] ?: 0L) <= 30_000L }
        val retained = mutableDevices.value.map { it.id }.toSet()
        lastSeen.keys.retainAll(retained)
    }

    private fun senderInfo(): JSONObject = JSONObject().put("alias", "Copig · ${Build.MODEL}")
        .put("version", "2.2").put("deviceModel", Build.MODEL).put("deviceType", "mobile")
        .put("fingerprint", senderId).put("port", PORT).put("protocol", "https").put("download", false)

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val GROUP = "224.0.0.167"
        private const val PORT = 53317
    }
}
