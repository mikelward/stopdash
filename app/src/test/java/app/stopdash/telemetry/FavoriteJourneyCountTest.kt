package app.stopdash.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The favorite-journeys user property: grayed journeys count too, and an unreadable list is unknown. */
class FavoriteJourneyCountTest {
    @Test
    fun `grayed journeys count with the rest`() {
        assertEquals(3, favoriteJourneyCount(listOf("a", "b"), listOf("c")))
        assertEquals(1, favoriteJourneyCount(emptyList<Any>(), listOf("c")))
        assertEquals(0, favoriteJourneyCount(emptyList<Any>(), emptyList<Any>()))
    }

    @Test
    fun `either list unreadable is unknown, not an undercount`() {
        assertNull(favoriteJourneyCount(null, listOf("c")))
        assertNull(favoriteJourneyCount(listOf("a"), null))
    }
}
