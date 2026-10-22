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

    private fun place(id: String, label: String) = FavoritePlace(id, FavoriteKind.HOME, label, Coordinates(51.5, -0.12))

    @Test
    fun `a place end shows under its place's name now, and is still removed as the one saved`() {
        val saved = listOf(PendingJourney(home, waterloo))
        val shown = PendingJourneys.named(saved, listOf(place("h", "Flat")), FavoritePlace::label)
        assertEquals(listOf("Flat"), shown.map { it.from.name })
        assertEquals("Waterloo", shown.single().to.name)
        assertEquals(saved.single().key, shown.single().key)
        assertEquals(emptyList<PendingJourney>(), PendingJourneys.remove(saved, shown.single()))
    }

    @Test
    fun `a place deleted, named blank or not read keeps the name it was saved with`() {
        val saved = listOf(PendingJourney(home, waterloo))
        assertEquals(saved, PendingJourneys.named(saved, listOf(place("w", "Work")), FavoritePlace::label))
        assertEquals(saved, PendingJourneys.named(saved, listOf(place("h", " ")), FavoritePlace::label))
        assertEquals(saved, PendingJourneys.named(saved, null, FavoritePlace::label))
    }
}
