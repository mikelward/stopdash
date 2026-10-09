package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class OriginChangeTest {
    // Hubs' ids and names, not anyone's place.
    private val searching = ToChoice.NONE.startPicking()
    private val toCanaryWharf = ToChoice(stopId = "940GZZLUCYF", name = "Canary Wharf")
    private val fromSearch = OriginChange.Station("940GZZLUKSX", "King's Cross St. Pancras", searching)
    private val fromRoutes = OriginChange.Station("940GZZLUKSX", "King's Cross St. Pancras", toCanaryWharf)

    @Test
    fun `back returns to the trip the From row was tapped in, as it was`() {
        assertEquals(OriginChange.Landing.NearMe(searching), OriginChange.back(OriginChange.NearMe(searching)))
        assertEquals(OriginChange.Landing.NearMe(toCanaryWharf), OriginChange.back(OriginChange.NearMe(toCanaryWharf)))
        assertEquals(
            OriginChange.Landing.Station("940GZZLUKSX", "King's Cross St. Pancras", searching),
            OriginChange.back(fromSearch),
        )
        // From a trip's routes, back to them, not to a search for somewhere to go.
        assertEquals(
            OriginChange.Landing.Station("940GZZLUKSX", "King's Cross St. Pancras", toCanaryWharf),
            OriginChange.back(fromRoutes),
        )
    }

    @Test
    fun `back returns to a station opened by one of its names in that name's order`() {
        val stPancras = OriginChange.Station("HUBKGX", "St Pancras International", searching, lead = listOf("national-rail"))
        assertEquals(
            OriginChange.Landing.Station("HUBKGX", "St Pancras International", searching, lead = listOf("national-rail")),
            OriginChange.back(stPancras),
        )
    }

    @Test
    fun `back from a From search no trip opened goes to the list`() {
        assertNull(OriginChange.back(null))
        assertNull(OriginChange.here(null))
    }

    @Test
    fun `a change keeps the trip's destination, or its search when there's none`() {
        assertEquals(toCanaryWharf, OriginChange.kept(toCanaryWharf))
        // The To… search reopened over the routes stays a search, the routes behind it.
        assertEquals(toCanaryWharf.startPicking(), OriginChange.kept(toCanaryWharf.startPicking()))
        // Nothing picked yet: the next start opens at its To… search, never its departures.
        assertEquals(searching, OriginChange.kept(ToChoice.NONE))
        assertEquals(searching, OriginChange.kept(searching))
    }

    @Test
    fun `backing out of a station mid-change keeps the next station opening at the trip's To`() {
        // The station was still loading, or failed: the change isn't done, so its To… goes on.
        assertEquals(searching, OriginChange.toAfterStationClosed(OriginChange.NearMe(searching)))
        assertEquals(searching, OriginChange.toAfterStationClosed(fromSearch))
        assertEquals(toCanaryWharf, OriginChange.toAfterStationClosed(OriginChange.NearMe(toCanaryWharf)))
        assertEquals(toCanaryWharf, OriginChange.toAfterStationClosed(fromRoutes))
        // An ordinary From… station leaves nothing behind.
        assertEquals(ToChoice.NONE, OriginChange.toAfterStationClosed(null))
    }

    @Test
    fun `back to the station a change began at keeps the change until its trip appears`() {
        // Its stops load afresh and can fail: backing out of that must still find the change.
        assertEquals(fromRoutes, OriginChange.afterLeaving(fromRoutes, OriginChange.back(fromRoutes)))
        // Landing near me (or on the list) ends it.
        assertNull(OriginChange.afterLeaving(fromRoutes, OriginChange.here(fromRoutes)))
        val nearMe = OriginChange.NearMe(toCanaryWharf)
        assertNull(OriginChange.afterLeaving(nearMe, OriginChange.back(nearMe)))
        assertNull(OriginChange.afterLeaving(null, OriginChange.back(null)))
    }

    @Test
    fun `leaving for near me or the list closes Lines, back to a station keeps it`() {
        // A stop's page from a line's map, then "use my location": no change under way.
        assertTrue(OriginChange.closesLines(OriginChange.here(null)))
        assertTrue(OriginChange.closesLines(OriginChange.here(fromRoutes)))
        assertFalse(OriginChange.closesLines(OriginChange.back(fromRoutes)))
    }

    @Test
    fun `Here goes on as the near-me trip with its To, whichever the change began at`() {
        assertEquals(OriginChange.Landing.NearMe(toCanaryWharf), OriginChange.here(OriginChange.NearMe(toCanaryWharf)))
        assertEquals(OriginChange.Landing.NearMe(toCanaryWharf), OriginChange.here(fromRoutes))
        assertEquals(OriginChange.Landing.NearMe(searching), OriginChange.here(fromSearch))
    }
}
