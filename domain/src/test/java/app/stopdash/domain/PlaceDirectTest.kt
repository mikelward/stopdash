package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and lines only. */
class PlaceDirectTest {
    private val now = Instant.parse("2026-10-07T08:00:00Z")

    // "rail" runs Top → Mid → Bottom; "bus" runs Near → Far, away from the place.
    private val rail = LineSequence(
        routes = listOf(LineRoute("Top ↔ Bottom", listOf("TOP", "MID", "BOT")), LineRoute("Bottom ↔ Top", listOf("BOT", "MID", "TOP"))),
        stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOT" to "Bottom"),
    )
    private val bus = LineSequence(listOf(LineRoute("Near ↔ Far", listOf("NEAR", "FAR"))), mapOf("NEAR" to "Near", "FAR" to "Far"))

    private fun departure(destination: String, inSeconds: Long, lineId: String = "rail", mode: String = "tube") =
        Departure(lineId, lineId, "", destination, null, now.plusSeconds(inSeconds), mode)

    private fun stop(id: String, name: String, vararg departures: Departure) = StopArrivals(id, name, departures.toList(), now)

    // The place is a short walk from Bottom.
    private val ends = listOf(DirectTrips.End("BOT", "Bottom"))

    @Test
    fun `a line that reaches the place is one row from its nearest stop, its next trains soonest first`() {
        val top = stop("TOP", "Top", departure("Bottom", 300), departure("Bottom", 60), departure("Bottom", 600), departure("Bottom", 900))
        val mid = stop("MID", "Mid", departure("Bottom", 120))
        val result = PlaceDirect.rows(listOf(mid, top), ends, mapOf("rail" to rail), mapOf("TOP" to 100.0, "MID" to 400.0), now)
        val row = result.rows.single()
        assertEquals("TOP", row.fromId)
        assertEquals(listOf(60L, 300L, 600L), row.departures.map { it.expectedArrival.epochSecond - now.epochSecond })
        assertEquals(listOf("BOT"), row.reaches.map { it.id })
        assertFalse(result.pending)
        assertFalse(result.unresolved)
    }

    @Test
    fun `a train going the other way, or a line that never gets there, is left out`() {
        val mid = stop("MID", "Mid", departure("Top", 60))
        val near = stop("NEAR", "Near", departure("Far", 60, lineId = "bus", mode = "bus"))
        val result = PlaceDirect.rows(listOf(mid, near), ends, mapOf("rail" to rail, "bus" to bus), emptyMap(), now)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `a train already gone is left out`() {
        val top = stop("TOP", "Top", departure("Bottom", -30), departure("Bottom", 240))
        val row = PlaceDirect.rows(listOf(top), ends, mapOf("rail" to rail), emptyMap(), now).rows.single()
        assertEquals(listOf(240L), row.departures.map { it.expectedArrival.epochSecond - now.epochSecond })
    }

    @Test
    fun `a line whose route is still loading is flagged, not guessed`() {
        val top = stop("TOP", "Top", departure("Bottom", 60))
        val result = PlaceDirect.rows(listOf(top), ends, emptyMap(), emptyMap(), now)
        assertTrue(result.rows.isEmpty())
        assertTrue(result.pending)
    }

    @Test
    fun `rows go nearest stop first`() {
        val top = stop("TOP", "Top", departure("Bottom", 60))
        val near = stop("NEAR", "Near", departure("Bottom", 300, lineId = "bus2", mode = "bus"))
        val bus2 = LineSequence(listOf(LineRoute("Near ↔ Bottom", listOf("NEAR", "BOT"))), mapOf("NEAR" to "Near", "BOT" to "Bottom"))
        val result = PlaceDirect.rows(listOf(top, near), ends, mapOf("rail" to rail, "bus2" to bus2), mapOf("TOP" to 500.0, "NEAR" to 50.0), now)
        assertEquals(listOf("bus2", "rail"), result.rows.map { it.lineId })
    }

    // Top is step-free to the platform only; Mid onto the train; Bottom to the platform only.
    private val access = StepFreeAccess(
        mapOf(
            "TOP" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.PLATFORM))),
            "MID" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.LEVEL))),
            "BOT" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.PLATFORM))),
        ),
    )

    @Test
    fun `a line meets Station when both its stops are step-free to the platform`() {
        val top = stop("TOP", "Top", departure("Bottom", 60))
        val result = PlaceDirect.rows(listOf(top), ends, mapOf("rail" to rail), emptyMap(), now, stepFree = StepFree.STATION, access = access)
        assertEquals(listOf("TOP"), result.rows.map { it.fromId })
        assertFalse(result.unresolved)
    }

    @Test
    fun `Fully leaves out a line whose stop near the place has a step onto the train`() {
        val mid = stop("MID", "Mid", departure("Bottom", 60))
        val result = PlaceDirect.rows(listOf(mid), ends, mapOf("rail" to rail), emptyMap(), now, stepFree = StepFree.FULLY, access = access)
        assertTrue(result.rows.isEmpty())
        assertFalse(result.unresolved)
    }

    @Test
    fun `a station with no step-free data is left out and said, never guessed`() {
        val top = stop("TOP", "Top", departure("Bottom", 60))
        val result = PlaceDirect.rows(listOf(top), ends, mapOf("rail" to rail), emptyMap(), now, stepFree = StepFree.STATION, access = StepFreeAccess.EMPTY)
        assertTrue(result.rows.isEmpty())
        assertTrue(result.unresolved)
    }

    @Test
    fun `a train is judged by the stop near the place it reaches, not another branch's`() {
        // The line forks at Mid: on to Bottom A (a step to the platform), or via Side to Bottom B (step-free).
        val fork = LineSequence(
            routes = listOf(LineRoute("Top ↔ Bottom A", listOf("TOP", "MID", "BOTA")), LineRoute("Top ↔ Bottom B", listOf("TOP", "MID", "SIDE", "BOTB"))),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "SIDE" to "Side", "BOTB" to "Bottom B"),
        )
        val table = StepFreeAccess(
            mapOf(
                "TOP" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.LEVEL))),
                "BOTA" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.NONE))),
                "BOTB" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.LEVEL))),
            ),
        )
        val both = listOf(DirectTrips.End("BOTA", "Bottom A"), DirectTrips.End("BOTB", "Bottom B"))
        val toA = stop("TOP", "Top", departure("Bottom A", 60))
        assertTrue(PlaceDirect.rows(listOf(toA), both, mapOf("rail" to fork), emptyMap(), now, stepFree = StepFree.STATION, access = table).rows.isEmpty())
        val toB = stop("TOP", "Top", departure("Bottom A", 60), departure("Bottom B", 120))
        val row = PlaceDirect.rows(listOf(toB), both, mapOf("rail" to fork), emptyMap(), now, stepFree = StepFree.STATION, access = table).rows.single()
        assertEquals(listOf("Bottom B"), row.departures.map { it.destination })
    }

    @Test
    fun `a train to a stop near the place with no step-free data went unchecked, not missed`() {
        // Bottom A is described (a step), Bottom B isn't: a train to Bottom B is unknown, so said.
        val fork = LineSequence(
            routes = listOf(LineRoute("Top ↔ Bottom A", listOf("TOP", "MID", "BOTA")), LineRoute("Top ↔ Bottom B", listOf("TOP", "MID", "SIDE", "BOTB"))),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "SIDE" to "Side", "BOTB" to "Bottom B"),
        )
        val table = StepFreeAccess(
            mapOf(
                "TOP" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.LEVEL))),
                "BOTA" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.NONE))),
            ),
        )
        val both = listOf(DirectTrips.End("BOTA", "Bottom A"), DirectTrips.End("BOTB", "Bottom B"))
        val result = PlaceDirect.rows(listOf(stop("TOP", "Top", departure("Bottom B", 60))), both, mapOf("rail" to fork), emptyMap(), now, stepFree = StepFree.STATION, access = table)
        assertTrue(result.rows.isEmpty())
        assertTrue(result.unresolved)
    }

    @Test
    fun `a route that failed while its stops near the place have no step-free data is unchecked, not None`() {
        val top = stop("TOP", "Top", departure("Bottom", 60))
        val result = PlaceDirect.rows(listOf(top), ends, mapOf("rail" to null), emptyMap(), now, stepFree = StepFree.STATION, access = StepFreeAccess.EMPTY)
        assertTrue(result.rows.isEmpty())
        assertTrue(result.unresolved)
    }

    @Test
    fun `a train reaches the stop its route calls at, not that station's other id`() {
        // Bottom and its sibling Bottom-X are one station (hub HUBB); the route calls only at Bottom.
        val hubbed = LineSequence(
            routes = listOf(LineRoute("Top ↔ Bottom", listOf("TOP", "MID", "BOT"))),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOT" to "Bottom", "BOTX" to "Bottom"),
            stopHubs = mapOf("BOT" to "HUBB", "BOTX" to "HUBB"),
        )
        val both = listOf(DirectTrips.End("BOTX", "Bottom", "HUBB"), DirectTrips.End("BOT", "Bottom", "HUBB"))
        val row = PlaceDirect.rows(listOf(stop("TOP", "Top", departure("Bottom", 60))), both, mapOf("rail" to hubbed), emptyMap(), now).rows.single()
        assertEquals(listOf(listOf("BOT")), row.trainReaches.map { reaches -> reaches.map { it.id } })
    }

    @Test
    fun `step-free is judged at the stop a train calls at, not that station's other id`() {
        // Bottom (called, a step) and Bottom-X (its sibling, step-free) are one station.
        val hubbed = LineSequence(
            routes = listOf(LineRoute("Top ↔ Bottom", listOf("TOP", "MID", "BOT"))),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOT" to "Bottom", "BOTX" to "Bottom"),
            stopHubs = mapOf("BOT" to "HUBB", "BOTX" to "HUBB"),
        )
        val table = StepFreeAccess(
            mapOf(
                "TOP" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.LEVEL))),
                "BOT" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.NONE))),
                "BOTX" to mapOf("rail" to listOf(StepFreePlatform(StepFreeLevel.LEVEL))),
            ),
        )
        val both = listOf(DirectTrips.End("BOTX", "Bottom", "HUBB"), DirectTrips.End("BOT", "Bottom", "HUBB"))
        val result = PlaceDirect.rows(listOf(stop("TOP", "Top", departure("Bottom", 60))), both, mapOf("rail" to hubbed), emptyMap(), now, stepFree = StepFree.STATION, access = table)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `a replacement bus needs no step-free data`() {
        val near = stop("NEAR", "Near", departure("Bottom", 300, lineId = "rb", mode = "replacement-bus"))
        val rb = LineSequence(listOf(LineRoute("Near ↔ Bottom", listOf("NEAR", "BOT"))), mapOf("NEAR" to "Near", "BOT" to "Bottom"))
        val result = PlaceDirect.rows(listOf(near), ends, mapOf("rb" to rb), emptyMap(), now, stepFree = StepFree.FULLY, access = StepFreeAccess.EMPTY)
        assertEquals(listOf("rb"), result.rows.map { it.lineId })
    }

    @Test
    fun `a train that may take either branch, each reaching a stop near the place, may reach either`() {
        // The line also runs Far ↔ Bottom C, a branch this train, boarding at Top, can't take.
        val fork = LineSequence(
            routes = listOf(
                LineRoute("Top ↔ Bottom A", listOf("TOP", "MID", "BOTA")),
                LineRoute("Top ↔ Bottom B", listOf("TOP", "MID", "BOTB")),
                LineRoute("Far ↔ Bottom C", listOf("FAR", "BOTC")),
            ),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "BOTB" to "Bottom B", "FAR" to "Far", "BOTC" to "Bottom C"),
        )
        // Bottom C is near the place too, but not on a way this train may take: never credited to it.
        val both = listOf(DirectTrips.End("BOTA", "Bottom A"), DirectTrips.End("BOTB", "Bottom B"), DirectTrips.End("BOTC", "Bottom C"))
        // No destination yet: either branch, so both its stops.
        val top = stop("TOP", "Top", departure("", 60))
        val result = PlaceDirect.rows(listOf(top), both, mapOf("rail" to fork), emptyMap(), now)
        assertEquals(listOf(listOf("BOTA", "BOTB")), result.rows.single().trainReaches.map { r -> r.map { it.id } })
        // Alternatives, not two calls on one way: it gets to only one of them.
        assertEquals(listOf(true), result.rows.single().trainForks)
    }

    @Test
    fun `a train that may take either branch reaches a stop near the place its route lists under a sibling id`() {
        // Bottom A's route id is BOTA; the stop near the place is its sibling BOTAX, one station (hub HUBA).
        val fork = LineSequence(
            routes = listOf(
                LineRoute("Top ↔ Bottom A", listOf("TOP", "MID", "BOTA")),
                LineRoute("Top ↔ Bottom B", listOf("TOP", "MID", "BOTB")),
            ),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "BOTAX" to "Bottom A", "BOTB" to "Bottom B"),
            stopHubs = mapOf("BOTA" to "HUBA", "BOTAX" to "HUBA"),
        )
        val both = listOf(DirectTrips.End("BOTAX", "Bottom A", "HUBA"), DirectTrips.End("BOTB", "Bottom B"))
        val result = PlaceDirect.rows(listOf(stop("TOP", "Top", departure("", 60))), both, mapOf("rail" to fork), emptyMap(), now)
        assertEquals(listOf(listOf("BOTAX", "BOTB")), result.rows.single().trainReaches.map { r -> r.map { it.id } })
    }

    @Test
    fun `a departure whose route can't be told is named for the debug log`() {
        // Only Bottom A is near the place, and a train with no destination may take either branch.
        val fork = LineSequence(
            routes = listOf(
                LineRoute("Top ↔ Bottom A", listOf("TOP", "MID", "BOTA")),
                LineRoute("Top ↔ Bottom B", listOf("TOP", "MID", "BOTB")),
            ),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "BOTB" to "Bottom B"),
        )
        val result = PlaceDirect.rows(listOf(stop("TOP", "Top", departure("", 60))), listOf(DirectTrips.End("BOTA", "Bottom A")), mapOf("rail" to fork), emptyMap(), now)
        assertTrue(result.rows.isEmpty())
        assertTrue(result.unresolved)
        assertEquals(listOf("rail" to "TOP"), result.misses.map { it.lineId to it.stopId })
    }

    @Test
    fun `a bus needs no step-free data`() {
        val near = stop("NEAR", "Near", departure("Bottom", 300, lineId = "bus2", mode = "bus"))
        val bus2 = LineSequence(listOf(LineRoute("Near ↔ Bottom", listOf("NEAR", "BOT"))), mapOf("NEAR" to "Near", "BOT" to "Bottom"))
        val result = PlaceDirect.rows(listOf(near), ends, mapOf("bus2" to bus2), emptyMap(), now, stepFree = StepFree.FULLY, access = StepFreeAccess.EMPTY)
        assertEquals(listOf("bus2"), result.rows.map { it.lineId })
    }

    @Test
    fun `no stops near the place is no rows`() {
        val top = stop("TOP", "Top", departure("Bottom", 60))
        val result = PlaceDirect.rows(listOf(top), emptyList(), mapOf("rail" to rail), emptyMap(), now)
        assertTrue(result.rows.isEmpty())
        assertFalse(result.pending)
    }
}
