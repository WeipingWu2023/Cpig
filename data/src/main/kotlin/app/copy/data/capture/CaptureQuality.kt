package app.copy.data.capture

internal data class CaptureQuality(val width: Int, val height: Int) {
    // Allow more detail per frame than the old fixed 4 Mbps recording.
    val bitRate: Int get() = (width.toLong() * height * 30 * 18 / 100).coerceIn(4_000_000, 20_000_000).toInt()

    companion object {
        fun candidates(screenWidth: Int, screenHeight: Int): List<CaptureQuality> {
            require(screenWidth > 0 && screenHeight > 0)
            return listOf(2560, 1920, 1280).map { limit ->
                val scale = minOf(1.0, limit.toDouble() / maxOf(screenWidth, screenHeight))
                CaptureQuality(maxOf(2, (screenWidth * scale).toInt() / 2 * 2),
                    maxOf(2, (screenHeight * scale).toInt() / 2 * 2))
            }.distinct()
        }
    }
}
