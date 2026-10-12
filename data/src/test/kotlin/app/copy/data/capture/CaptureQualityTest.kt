package app.copy.data.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureQualityTest {
    @Test fun preservesPhoneDetailAndProvidesSmallerFallbacks() {
        val portrait = CaptureQuality.candidates(1080, 2400)
        assertEquals(listOf(CaptureQuality(1080, 2400), CaptureQuality(864, 1920), CaptureQuality(576, 1280)), portrait)
        assertEquals(13_996_800, portrait.first().bitRate)
        assertEquals(portrait.map { CaptureQuality(it.height, it.width) }, CaptureQuality.candidates(2400, 1080))
        assertEquals(listOf(CaptureQuality(720, 1280)), CaptureQuality.candidates(720, 1280))
        val large = CaptureQuality.candidates(1440, 3200).first()
        assertEquals(CaptureQuality(1152, 2560), large)
        assertTrue(CaptureQuality.candidates(1081, 2401).all { it.width % 2 == 0 && it.height % 2 == 0 })
    }
}
