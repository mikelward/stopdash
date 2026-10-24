package app.stopdash.ui

import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.LineRef
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.WidgetJourney
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings names every line a favorite ride takes, not only the one it was saved from (maintainer,
 * 2026-10-24): a ride is its two stops.
 */
class JourneyLineLabelsTest {
    private val a = JourneyEnd("490000001S", "Park")
    private val b = JourneyEnd("490000002S", "Hill")
    private val ride = FavoriteJourney(a, b, listOf(LineRef("134", "134", "bus")))

    private fun pin(from: String, vararg lines: String) =
        WidgetJourney(from, lines.mapTo(LinkedHashSet()) { JourneyCall(it, "", null) }, key = ride.key, shownFrom = from)

    @Test
    fun `a ride saved from one route names every route the list found between its stops, by number`() {
        val snapshot = DeparturesSnapshot(emptyList(), java.time.Instant.EPOCH, journeys = listOf(pin(a.stopId, "134", "43")))
        assertEquals(mapOf(ride.key to "43, 134"), journeyLineLabels(listOf(ride), snapshot))
    }

    @Test
    fun `routes found either way round count`() {
        val snapshot = DeparturesSnapshot(emptyList(), java.time.Instant.EPOCH, journeys = listOf(pin(b.stopId, "43")))
        assertEquals(mapOf(ride.key to "43, 134"), journeyLineLabels(listOf(ride), snapshot))
    }

    @Test
    fun `with nothing found yet it names the line it was saved on`() {
        assertEquals(mapOf(ride.key to "134"), journeyLineLabels(listOf(ride), null))
    }

    @Test
    fun `a line hidden from the ride isn't named`() {
        val hiding = ride.copy(hiddenLines = setOf(app.stopdash.domain.HiddenModes.lineKey("43", "43")))
        val snapshot = DeparturesSnapshot(emptyList(), java.time.Instant.EPOCH, journeys = listOf(pin(a.stopId, "134", "43")))
        assertEquals(mapOf(ride.key to "134"), journeyLineLabels(listOf(hiding), snapshot))
    }
}
