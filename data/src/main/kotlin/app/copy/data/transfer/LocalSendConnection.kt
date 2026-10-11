package app.copy.data.transfer

import android.annotation.SuppressLint
import app.copy.domain.LanAddress
import app.copy.domain.LanDevice
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/** Each connection has its own certificate verifier. Nothing changes the process-wide TLS defaults. */
internal class LocalSendConnection(private val device: LanDevice, private val bootstrap: Boolean = false) {
    var observedFingerprint: String? = null
        private set

    @SuppressLint("CustomX509TrustManager")
    private val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Client certificates are not supported")
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val certificate = chain.firstOrNull() ?: throw CertificateException("Missing receiver certificate")
            certificate.checkValidity()
            val fingerprint = sha256(certificate.encoded)
            if (!bootstrap && !fingerprint.equals(device.id, ignoreCase = true)) {
                throw CertificateException("The receiver certificate changed. Discover the computer again.")
            }
            observedFingerprint = fingerprint
        }
    }

    fun open(path: String, query: String = ""): HttpsURLConnection {
        val (address, port) = LanAddress.parse("${device.address}:${device.port}")
        require(path in setOf("register", "prepare-upload", "upload", "cancel"))
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        return (URL("https://$address:$port/api/localsend/v2/$path$query").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = tls.socketFactory
            // The pinned certificate identifies this receiver; certificates need not contain the LAN IP in their SAN.
            hostnameVerifier = javax.net.ssl.HostnameVerifier { host, _ -> host == address }
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 60_000
            requestMethod = "POST"
            doOutput = true
            useCaches = false
        }
    }

    companion object {
        fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        fun response(connection: HttpsURLConnection): String {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException(when (status) {
                401 -> "Enter the six-digit PIN shown in Copig Receiver."
                403 -> "The receiver rejected this transfer. Check its window and try again."
                409 -> "The receiver is busy. Finish or cancel its current transfer first."
                422 -> "The receiver found a size or checksum mismatch. The file was not saved."
                429 -> "Too many attempts. Wait briefly before trying again."
                else -> "The receiver returned HTTP $status."
            })
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val bytes = ByteArray(4096)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    if (output.size() + count > 65_536) throw IOException("Receiver response is too large.")
                    output.write(bytes, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }

        fun writeJson(connection: HttpsURLConnection, json: JSONObject) {
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
    }
}
