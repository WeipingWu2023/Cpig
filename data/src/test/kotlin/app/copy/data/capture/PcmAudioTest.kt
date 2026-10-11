package app.copy.data.capture

import org.junit.Assert.*
import org.junit.Test

class PcmAudioTest {
    @Test fun detectsSignedPcmSamplesWithinTheActualReadLength() {
        assertFalse(PcmAudio.audible(byteArrayOf(0, 0, 32, 0, -32, -1), 6))
        assertTrue(PcmAudio.audible(byteArrayOf(33, 0), 2))
        assertTrue(PcmAudio.audible(byteArrayOf(-33, -1), 2))
        assertTrue(PcmAudio.audible(byteArrayOf(0, -128), 2))
        assertFalse(PcmAudio.audible(byteArrayOf(0, 0, 100), 3))
        assertFalse(PcmAudio.audible(byteArrayOf(0, 0, 100, 0), 2))
    }
    @Test fun audioTimestampsRemainAccurateForLongClips() {
        assertEquals(1_002_000L, PcmAudio.timeUs(44_100, 44_100, 2_000))
        assertEquals(900_000_000L, PcmAudio.timeUs(44_100L * 900, 44_100, 0))
    }
}
