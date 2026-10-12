package app.copy.data.capture

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ScreenshotPixelsTest {
    @Test fun paddedRowsAndPixelGapsPreserveOnlyTheScreenPixels() {
        // Real pixels differ in every channel; padding must never appear in the saved screenshot.
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val rows = byteArrayOf(99, 1, 2, 3, 4, 5, 6, 7, 8, 99, 99, 99, 99, 9, 10, 11, 12, 13, 14, 15, 16)
        val source = ByteBuffer.wrap(rows).apply { position(1) }
        assertArrayEquals(expected, ScreenshotPixels.packedRgba(source, 2, 2, 4, 12).array())
        assertEquals(1, source.position())
        val gaps = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 99, 99, 5, 6, 7, 8, 99, 99, 99, 99,
            9, 10, 11, 12, 99, 99, 13, 14, 15, 16))
        assertArrayEquals(expected, ScreenshotPixels.packedRgba(gaps, 2, 2, 6, 14).array())
        assertThrows(IllegalArgumentException::class.java) {
            ScreenshotPixels.packedRgba(ByteBuffer.wrap(rows.copyOf(18)), 2, 2, 4, 12)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenshotPixels.packedRgba(source, 2, 2, 4, 6)
        }
    }
}
