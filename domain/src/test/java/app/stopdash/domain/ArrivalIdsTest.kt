package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic ids in TfL's shapes only. */
class ArrivalIdsTest {
    private val ids = mapOf("940GZZLUAAA" to "S", "910GBBBB" to "S", "490000001A" to "P")

    @Test
    fun `an id the arrival ids hold is its own station or stop`() {
        assertEquals("S", ids.arrivalOf("940GZZLUAAA"))
        assertEquals("P", ids.arrivalOf("490000001A"))
    }

    @Test
    fun `a platform they don't hold arrives as its station record's station`() {
        // A platform TfL added after the bundle was built: Underground and National Rail alike.
        assertEquals("S", ids.arrivalOf("9400ZZLUAAA"))
        assertEquals("S", ids.arrivalOf("9100BBBB"))
    }

    @Test
    fun `an id that isn't one of them, platform or not, arrives nowhere`() {
        assertNull(ids.arrivalOf("9400ZZLUZZZ"))
        assertNull(ids.arrivalOf("490000009Z"))
        assertNull(ids.arrivalOf("940GZZLUZZZ"))
    }
}
