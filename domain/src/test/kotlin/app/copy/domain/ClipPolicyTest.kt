package app.copy.domain

import org.junit.Assert.*
import org.junit.Test

class ClipPolicyTest {
    @Test fun supportedFormatsNormalizeWithoutAcceptingArbitraryInput() {
        assertEquals("m4a", ClipPolicy.extension("audio/x-m4a"))
        assertEquals("audio/mp4", ClipPolicy.mime("Import.m4a"))
        assertEquals("png", ClipPolicy.extension("image/png"))
        assertEquals("image/png", ClipPolicy.mime("Copig-screenshot.png"))
        assertNull(ClipPolicy.mime("recording.part"))
        assertThrows(IllegalArgumentException::class.java) { ClipPolicy.extension("text/html") }
        assertThrows(IllegalArgumentException::class.java) { ClipPolicy.extension(null) }
    }
}
