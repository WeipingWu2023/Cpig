package app.copy.data.capture

import java.nio.ByteBuffer

/** ImageReader rows can contain padding, and pixels can be farther apart than four bytes. */
internal object ScreenshotPixels {
    fun packedRgba(source: ByteBuffer, width: Int, height: Int, pixelStride: Int, rowStride: Int): ByteBuffer {
        require(width > 0 && height > 0 && pixelStride >= 4) { "Invalid screenshot dimensions" }
        val rowBytes = (width - 1).toLong() * pixelStride + 4
        val required = (height - 1).toLong() * rowStride + rowBytes
        val packedBytes = width.toLong() * height * 4
        require(rowStride >= rowBytes && required <= source.remaining() && packedBytes <= Int.MAX_VALUE) {
            "Incomplete screenshot pixels"
        }
        val input = source.duplicate()
        val start = input.position()
        val output = ByteBuffer.allocate(packedBytes.toInt())
        for (row in 0 until height) {
            val offset = start + row * rowStride
            if (pixelStride == 4) {
                input.limit(offset + width * 4).position(offset)
                output.put(input)
                input.limit(source.limit())
            } else {
                for (column in 0 until width) {
                    val pixel = offset + column * pixelStride
                    for (channel in 0 until 4) output.put(input.get(pixel + channel))
                }
            }
        }
        output.flip()
        return output
    }
}
