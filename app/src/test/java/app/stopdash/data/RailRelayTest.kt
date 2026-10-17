package app.stopdash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RailRelayTest {
    @Test
    fun `National Rail times come with the rider's key or through the relay, and not with neither`() {
        assertTrue(RailRelay.timesWith("EXAMPLE", relay = null))
        assertTrue(RailRelay.timesWith(null, relay = "https://relay.example"))
        assertFalse(RailRelay.timesWith(" ", relay = null))
        assertFalse(RailRelay.timesWith(null, relay = null))
    }
}
