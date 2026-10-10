package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Journeys saved grayed (SPEC *Journeys*): identity either way round, and add/remove by it. Stock stand-ins. */
class PendingJourneyTest {
    private val home = PendingEnd.Place("h", "Home")
    private val waterloo = PendingEnd.Station("940GZZLUWLO", "Waterloo")

    @Test
    fun `the same two ends either way round are one journey`() {
        assertEquals(PendingJourney(home, waterloo).key, PendingJourney(waterloo, home).key)
        assertEquals("place:h|station:940GZZLUWLO", PendingJourney(waterloo, home).key)
    }

    @Test
    fun `a station and a place with the same id are different ends`() {
        assertNotEquals(PendingEnd.Station("h", "Home").key, home.key)
    }

    @Test
    fun `add keeps one per journey and remove drops it either way round`() {
        val once = PendingJourneys.add(emptyList(), PendingJourney(home, waterloo))
        assertEquals(once, PendingJourneys.add(once, PendingJourney(waterloo, home)))
        assertEquals(emptyList<PendingJourney>(), PendingJourneys.remove(once, PendingJourney(waterloo, home)))
        assertEquals(emptyList<PendingJourney>(), PendingJourneys.remove(emptyList(), PendingJourney(home, waterloo)))
    }
}
