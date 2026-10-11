package app.copy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {
    @Test fun onlyNumericLocalEndpointsAndActiveTransferPhasesAreAccepted() {
        assertEquals("192.168.1.20" to 53317, LanAddress.parse(" 192.168.1.20 "))
        assertEquals("10.0.0.2" to 50000, LanAddress.parse("10.0.0.2:50000"))
        listOf("https://192.168.1.20", "example.com", "8.8.8.8", "172.32.0.1", "192.168.1.256",
            "192.168.01.2", "192.168.1.1:0", "192.168.1.1:65536", "192.168.1.1/path").forEach {
            assertTrue("Should reject $it", runCatching { LanAddress.parse(it) }.isFailure)
        }
        assertTrue(LanTransferState(LanTransferPhase.Sending).active)
        assertFalse(LanTransferState(LanTransferPhase.Complete).active)
        assertFalse(LanTransferState(LanTransferPhase.Cancelled).active)
    }
}
