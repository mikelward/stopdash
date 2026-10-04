package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** [DeparturesSnapshot.scopedTo], with synthetic stop ids only. */
class DeparturesSnapshotScopeTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private fun stop(id: String, at: Instant = now) = StopArrivals(id, "Stop", emptyList(), at)
    private val call = JourneyCall("b1", "Hill", null)

    @Test
    fun `stops the rider has left are dropped, and the new ones not fetched yet are missing`() {
        val stored = DeparturesSnapshot(stops = listOf(stop("490000001A"), stop("490000002B")), fetchedAt = now)
        val scoped = stored.scopedTo(setOf("490000003C"))
        assertEquals(emptyList<String>(), scoped.stops.map { it.stopId })
        assertEquals(setOf("490000003C"), scoped.missingStopIds)
    }

    @Test
    fun `a stop still near is kept, with the stamp from what's kept`() {
        val stored = DeparturesSnapshot(
            stops = listOf(stop("490000001A", now.minusSeconds(60)), stop("490000002B")),
            fetchedAt = now,
        )
        val scoped = stored.scopedTo(setOf("490000001A"))
        assertEquals(listOf("490000001A"), scoped.stops.map { it.stopId })
        assertEquals(now.minusSeconds(60), scoped.fetchedAt)
        assertEquals(emptySet<String>(), scoped.missingStopIds)
    }

    @Test
    fun `a pinned journey's origin the rider left keeps only its journey`() {
        val stored = DeparturesSnapshot(
            stops = listOf(stop("490000001A"), stop("490000002B")),
            fetchedAt = now,
            journeys = listOf(WidgetJourney("490000001A", setOf(call), "j")),
        )
        val scoped = stored.scopedTo(setOf("490000002B"))
        assertEquals(listOf("490000001A", "490000002B"), scoped.stops.map { it.stopId })
        assertEquals(setOf("490000001A"), scoped.journeyOnlyStopIds)
    }

    @Test
    fun `a line's choice at a stop kept only for its journey is dropped`() {
        val stored = DeparturesSnapshot(
            stops = listOf(stop("490000001A"), stop("490000002B")),
            fetchedAt = now,
            journeys = listOf(WidgetJourney("490000001A", setOf(call), "j")),
            nearbyChoices = listOf(FoldChoice("b1", "inbound", "490000001A"), FoldChoice("b2", "outbound", "490000002B")),
        )
        val scoped = stored.scopedTo(setOf("490000002B"))
        // The journey's origin shows only the journey now, so its choice would fold the line away at
        // the stop that is near; the near stop's own choice stays.
        assertEquals(listOf(FoldChoice("b2", "outbound", "490000002B")), scoped.nearbyChoices)
    }

    @Test
    fun `a missing stop from the old place isn't missing here`() {
        val stored = DeparturesSnapshot(stops = listOf(stop("490000001A")), fetchedAt = now, missingStopIds = setOf("490000009Z"))
        assertEquals(emptySet<String>(), stored.scopedTo(setOf("490000001A")).missingStopIds)
    }

    @Test
    fun `a snapshot already for the set is left as it is`() {
        val stored = DeparturesSnapshot(stops = listOf(stop("490000001A")), fetchedAt = now)
        assertSame(stored, stored.scopedTo(setOf("490000001A")))
    }

    @Test
    fun `a journey-only origin the rider is now near is nearby again, not missing`() {
        val stored = DeparturesSnapshot(
            stops = listOf(stop("490000001A")),
            fetchedAt = now,
            journeys = listOf(WidgetJourney("490000001A", setOf(call), "j")),
            journeyOnlyStopIds = setOf("490000001A"),
        )
        val scoped = stored.scopedTo(setOf("490000001A"))
        assertEquals(emptySet<String>(), scoped.journeyOnlyStopIds)
        assertEquals(emptySet<String>(), scoped.missingStopIds)
    }
}
