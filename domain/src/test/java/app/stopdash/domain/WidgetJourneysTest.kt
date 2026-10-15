package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stop ids and lines only. */
class WidgetJourneysTest {
    private val hill = JourneyCall("b1", "Hill", null)
    private val dale = JourneyCall("b1", "Dale", null)
    private val town = JourneyCall("b2", "Town", null)

    @Test
    fun `a check that couldn't finish keeps what the journey knew`() {
        val memory = mapOf("j" to WidgetJourney("490000001A", setOf(hill), "j"))
        assertEquals(memory, WidgetJourneys.merge(memory, setOf("j"), emptyList()))
        // Incomplete: confirmed departures add, nothing is dropped.
        val incomplete = WidgetJourneyCheck("j", "490000001A", setOf(town))
        assertEquals(setOf(hill, town), WidgetJourneys.merge(memory, setOf("j"), listOf(incomplete)).getValue("j").calls)
    }

    @Test
    fun `a complete check drops a departure the route now rejects, keeping ones it didn't see`() {
        val memory = mapOf("j" to WidgetJourney("490000001A", setOf(hill, dale), "j"))
        // Dale is at the stop now and was judged not to call; Hill isn't running just now.
        val complete = WidgetJourneyCheck("j", "490000001A", confirmed = setOf(town), checked = setOf(dale, town))
        assertEquals(setOf(hill, town), WidgetJourneys.merge(memory, setOf("j"), listOf(complete)).getValue("j").calls)
    }

    @Test
    fun `a pin keeps where its departures get off through a check that couldn't place them`() {
        val memory = mapOf("j" to WidgetJourney("490000001A", setOf(hill), "j", alightIds = setOf("490000009Z")))
        // A check that didn't place them keeps the last known; one that did replaces them.
        assertEquals(setOf("490000009Z"), WidgetJourneys.merge(memory, setOf("j"), listOf(WidgetJourneyCheck("j", "490000001A", setOf(town)))).getValue("j").alightIds)
        val placed = WidgetJourneyCheck("j", "490000001A", setOf(town), alightIds = setOf("490000008Y"))
        assertEquals(setOf("490000008Y"), WidgetJourneys.merge(memory, setOf("j"), listOf(placed)).getValue("j").alightIds)
        // A moved origin's pin starts afresh, its old far-end stops with it.
        assertEquals(emptySet<String>(), WidgetJourneys.merge(memory, setOf("j"), listOf(WidgetJourneyCheck("j", "490000003C", setOf(town)))).getValue("j").alightIds)
    }

    @Test
    fun `a moved origin starts afresh, and an unstarred journey is dropped`() {
        val memory = mapOf(
            "j" to WidgetJourney("490000001A", setOf(hill), "j"),
            "gone" to WidgetJourney("490000002B", setOf(dale), "gone"),
        )
        val flipped = WidgetJourneyCheck("j", "490000003C", setOf(town))
        assertEquals(
            mapOf("j" to WidgetJourney("490000003C", setOf(town), "j")),
            WidgetJourneys.merge(memory, setOf("j"), listOf(flipped)),
        )
    }

    private val now = java.time.Instant.parse("2026-01-01T12:00:00Z")
    private fun stop(id: String, at: java.time.Instant = now) = StopArrivals(id, "Stop", emptyList(), at)

    @Test
    fun `apply keeps a starred pin no check covers, and drops an unstarred one with its journey-only stop`() {
        val stored = DeparturesSnapshot(
            stops = listOf(stop("490000001A"), stop("490000002B")),
            fetchedAt = now,
            journeys = listOf(WidgetJourney("490000001A", setOf(hill), "j"), WidgetJourney("490000002B", setOf(dale), "gone")),
            journeyOnlyStopIds = setOf("490000002B"),
        )
        val after = WidgetJourneys.apply(stored, WidgetJourneysReport(setOf("j"), emptyList()), emptyList())!!
        assertEquals(listOf(WidgetJourney("490000001A", setOf(hill), "j")), after.journeys)
        assertEquals(listOf("490000001A"), after.stops.map { it.stopId })
        assertEquals(emptySet<String>(), after.journeyOnlyStopIds)
    }

    @Test
    fun `apply drops a pin for the other direction before any check of the new one`() {
        val stored = DeparturesSnapshot(
            stops = listOf(stop("490000001A")),
            fetchedAt = now,
            journeys = listOf(WidgetJourney("490000001A", setOf(hill), "j", shownFrom = "490000001A")),
        )
        val report = WidgetJourneysReport(setOf("j"), emptyList(), mapOf("j" to "490000003C"))
        assertEquals(emptyList<WidgetJourney>(), WidgetJourneys.apply(stored, report, emptyList())!!.journeys)
    }

    @Test
    fun `apply doesn't pin a journey whose origin it has no copy of`() {
        val report = WidgetJourneysReport(setOf("j"), listOf(WidgetJourneyCheck("j", "490000001A", setOf(hill))))
        assertEquals(null, WidgetJourneys.apply(null, report, emptyList()))
        val stored = DeparturesSnapshot(stops = listOf(stop("490000002B")), fetchedAt = now)
        assertEquals(emptyList<WidgetJourney>(), WidgetJourneys.apply(stored, report, emptyList())!!.journeys)
    }

    @Test
    fun `a neighboring pole's pin lives with its journey and goes when the pole no longer boards it`() {
        val poleKey = WidgetJourneys.poleKey("j", "490000002B")
        val stored = DeparturesSnapshot(
            stops = listOf(stop("490000001A"), stop("490000002B")),
            fetchedAt = now,
            journeys = listOf(WidgetJourney("490000001A", setOf(hill), "j"), WidgetJourney("490000002B", setOf(dale), poleKey)),
            journeyOnlyStopIds = setOf("490000002B"),
        )
        // Poles not looked up yet this session: the pole's pin stays.
        val waiting = WidgetJourneys.apply(stored, WidgetJourneysReport(setOf("j"), emptyList()), emptyList())!!
        assertEquals(stored.journeys, waiting.journeys)
        // Looked up, and the pole no longer boards the journey: its pin and journey-only stop go.
        val settled = WidgetJourneysReport(setOf("j"), emptyList(), boarding = mapOf("j" to setOf("j")))
        val after = WidgetJourneys.apply(stored, settled, emptyList())!!
        assertEquals(listOf("j"), after.journeys.map { it.key })
        assertEquals(listOf("490000001A"), after.stops.map { it.stopId })
        // Unstarred: both go.
        assertTrue(WidgetJourneys.apply(stored, WidgetJourneysReport(emptySet(), emptyList()), emptyList())!!.journeys.isEmpty())
        // A check for the pole pins it under its own key.
        val check = WidgetJourneyCheck(poleKey, "490000002B", setOf(town))
        val pinned = WidgetJourneys.apply(stored, WidgetJourneysReport(setOf("j"), listOf(check)), emptyList())!!
        assertEquals(setOf(dale, town), pinned.journeys.single { it.key == poleKey }.calls)
    }

    @Test
    fun `a journey update keeps the missing stops, less any origin it adds`() {
        val stored = DeparturesSnapshot(listOf(stop("490000001A")), now, missingStopIds = setOf("490000002B", "490000003C"))
        val report = WidgetJourneysReport(setOf("j"), listOf(WidgetJourneyCheck("j", "490000002B", setOf(hill))))
        val after = WidgetJourneys.apply(stored, report, listOf(stop("490000002B")))!!
        assertEquals(setOf("490000003C"), after.missingStopIds)
        // It was a nearby stop that failed, now recovered: shown as nearby, not journey-only.
        assertTrue("490000002B" !in after.journeyOnlyStopIds)
    }
}
