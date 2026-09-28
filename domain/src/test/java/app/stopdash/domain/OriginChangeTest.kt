package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginChangeTest {
    @Test
    fun `back returns to the To search the chip was tapped in`() {
        assertEquals(OriginChange.Landing.NearMePicker, OriginChange.back(OriginChange.NearMe))
        assertEquals(
            OriginChange.Landing.StationPicker("940GZZLUKSX", "King's Cross St. Pancras"),
            OriginChange.back(OriginChange.Station("940GZZLUKSX", "King's Cross St. Pancras")),
        )
    }

    @Test
    fun `back from a From search the chip didn't open goes to the list`() {
        assertNull(OriginChange.back(null))
        assertNull(OriginChange.here(null))
    }

    @Test
    fun `Here goes on at the near-me To search, whichever the change began at`() {
        assertEquals(OriginChange.Landing.NearMePicker, OriginChange.here(OriginChange.NearMe))
        assertEquals(OriginChange.Landing.NearMePicker, OriginChange.here(OriginChange.Station("940GZZLUKSX", "King's Cross St. Pancras")))
    }
}
