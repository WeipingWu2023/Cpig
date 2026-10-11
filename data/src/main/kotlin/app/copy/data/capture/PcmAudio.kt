package app.copy.data.capture

internal object PcmAudio {
    fun audible(bytes: ByteArray, length: Int): Boolean {
        for (index in 0 until length - 1 step 2) {
            val sample = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
            if (kotlin.math.abs(sample) > 32) return true
        }
        return false
    }
    fun timeUs(samples: Long, rate: Int, offsetUs: Long): Long = offsetUs + samples * 1_000_000L / rate
}
