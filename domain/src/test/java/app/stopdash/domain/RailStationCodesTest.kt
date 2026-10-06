package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailStationCodesTest {
    private val codes = RailStationCodes(mapOf("STALBCY" to "SAC", "STPADOM" to "STP", "STPX" to "STP", "STPXBOX" to "STP"))

    @Test
    fun `a station code finds its one stop, both ways`() {
        assertEquals("910GSTALBCY", codes.stopIdFor("SAC"))
        assertEquals("910GSTALBCY", codes.stopIdFor("sac"))
        assertEquals("SAC", codes.crsFor("910GSTALBCY"))
    }

    @Test
    fun `a code several stops share, or none has, finds none`() {
        assertNull(codes.stopIdFor("STP"))
        assertNull(codes.stopIdFor("XXX"))
        assertNull(RailStationCodes.EMPTY.stopIdFor("SAC"))
    }
}
