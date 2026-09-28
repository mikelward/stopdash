package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginChangeTest {
    // A hub's id and name, not anyone's place.
    private val kingsCross = OriginChange.Station("940GZZLUKSX", "King's Cross St. Pancras")

    @Test
    fun `back returns to the To search the From row was tapped in`() {
        assertEquals(OriginChange.Landing.NearMePicker, OriginChange.back(OriginChange.NearMe))
        assertEquals(
            OriginChange.Landing.StationPicker("940GZZLUKSX", "King's Cross St. Pancras"),
            OriginChange.back(kingsCross),
        )
    }

    @Test
    fun `back from a From search the To search didn't open goes to the list`() {
        assertNull(OriginChange.back(null))
        assertNull(OriginChange.here(null))
    }

    @Test
    fun `backing out of a station mid-change keeps the next station opening at its To search`() {
        // The station was still loading, or failed: the change isn't done, so it stays picking.
        assertEquals(ToChoice.NONE.startPicking(), OriginChange.toAfterStationClosed(OriginChange.NearMe))
        assertEquals(ToChoice.NONE.startPicking(), OriginChange.toAfterStationClosed(kingsCross))
        // An ordinary From… station leaves nothing behind.
        assertEquals(ToChoice.NONE, OriginChange.toAfterStationClosed(null))
    }

    @Test
    fun `back to the station a change began at keeps the change until its To search appears`() {
        // Its stops load afresh and can fail: backing out of that must still find the change.
        assertEquals(kingsCross, OriginChange.afterLeaving(kingsCross, OriginChange.back(kingsCross)))
        // Landing near me (or on the list) ends it.
        assertNull(OriginChange.afterLeaving(kingsCross, OriginChange.here(kingsCross)))
        assertNull(OriginChange.afterLeaving(OriginChange.NearMe, OriginChange.back(OriginChange.NearMe)))
        assertNull(OriginChange.afterLeaving(null, OriginChange.back(null)))
    }

    @Test
    fun `Here goes on at the near-me To search, whichever the change began at`() {
        assertEquals(OriginChange.Landing.NearMePicker, OriginChange.here(OriginChange.NearMe))
        assertEquals(OriginChange.Landing.NearMePicker, OriginChange.here(kingsCross))
    }
}
