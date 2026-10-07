package app.stopdash

import app.stopdash.domain.TripProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where leaving On the way lands ([leavesForNearMe]). */
class OnTheWayExitTest {
    @Test
    fun `done with an arrived trip lands on the near-me list, not the trip options`() {
        assertTrue(leavesForNearMe(TripProgress.Arrived))
    }

    @Test
    fun `back from a trip still under way, or none read back yet, returns to the trip options`() {
        assertFalse(leavesForNearMe(null))
    }

    @Test
    fun `a station's trip options are left once that station's page isn't the one open`() {
        // Big interchanges' ids only.
        assertFalse(tripOptionsLeft("940GZZLUKSX", openStationId = "940GZZLUKSX", hereTripOpen = false))
        assertTrue(tripOptionsLeft("940GZZLUKSX", openStationId = null, hereTripOpen = false))
        assertTrue(tripOptionsLeft("940GZZLUKSX", openStationId = "940GZZLUEUS", hereTripOpen = false))
        // Its trip's From… search, changing that trip's start, is still its own.
        assertFalse(tripOptionsLeft("940GZZLUKSX", openStationId = null, hereTripOpen = false, changingFrom = "940GZZLUKSX"))
        assertTrue(tripOptionsLeft("940GZZLUKSX", openStationId = null, hereTripOpen = false, changingFrom = "940GZZLUEUS"))
    }

    @Test
    fun `the near-me trip's options are left once it closes`() {
        assertFalse(tripOptionsLeft("", openStationId = null, hereTripOpen = true))
        assertTrue(tripOptionsLeft("", openStationId = "940GZZLUKSX", hereTripOpen = false))
    }

    @Test
    fun `plan again from a station supersedes a near-me trip's options, and only then`() {
        assertTrue(supersedesNearMeTrip(replacing = true, from = "", openStationId = "940GZZLUKSX"))
        // A first start, a replaced station trip, or a trip whose origin is unknown leaves them be.
        assertFalse(supersedesNearMeTrip(replacing = false, from = "", openStationId = "940GZZLUKSX"))
        assertFalse(supersedesNearMeTrip(replacing = true, from = "940GZZLUEUS", openStationId = "940GZZLUKSX"))
        assertFalse(supersedesNearMeTrip(replacing = true, from = null, openStationId = "940GZZLUKSX"))
    }

    @Test
    fun `the station flow over Lines lasts through every station it reaches`() {
        // Another station (a route's stop, or a different start picked) is still the flow.
        assertFalse(stationFlowEnded(openStationId = "940GZZLUEUS", changingStart = false))
        // Its From… search, a change of start under way with no station open.
        assertFalse(stationFlowEnded(openStationId = null, changingStart = true))
        assertTrue(stationFlowEnded(openStationId = null, changingStart = false))
    }
}
