package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and lines around the obviously-fake (51.5, -0.12); never a real fix. */
class TowardStopsTest {
    private val here = Coordinates(51.5, -0.12)

    // About 5 km due south: far beyond [TowardStops.REACH_METERS].
    private val south = Coordinates(51.455, -0.12)

    // ~0.001° of latitude is ~111 m; ~0.001° of longitude is ~69 m here.
    private fun stop(id: String, dLat: Double, dLon: Double, vararg lines: String, pair: String = "490G$id") = StopLocation(
        id = id,
        name = "Stop $id",
        latitude = here.latitude + dLat,
        longitude = here.longitude + dLon,
        lines = lines.map { LineRef(it, it.uppercase(), if (it == "red") "tube" else "bus") },
        clusterId = pair,
    )

    // Every line the tests use calls ~4 km south, near the destination, so each goes towards it unless a test says not.
    private val terminus = stop("Z", -0.036, 0.0, *(1..9).map { "$it" }.toTypedArray())

    private fun asks(stops: List<StopLocation>, to: Coordinates = south, network: List<StopLocation> = listOf(terminus)) =
        TowardStops.of(here, to, stops, network)

    @Test
    fun `asks via each bus line's nearest stop towards the destination, nearest first, a stop once`() {
        val stops = listOf(
            stop("C", -0.0036, 0.002, "1", "4"), // ~420 m, south-east
            stop("A", -0.0018, 0.0, "1", "2"), // ~200 m, south
            stop("D", -0.0045, 0.0, "2"), // ~500 m, south
        )
        val asks = asks(stops)
        // Line 1 and 2's nearest is A, line 4's is C: D serves no line A doesn't, so it's only A's fallback.
        assertEquals(listOf("490GA", "490GC"), asks.map { it.via })
        assertEquals(listOf("490GD", null), asks.map { it.fallback })
    }

    @Test
    fun `a stop behind the rider isn't asked about, nor one past the reach`() {
        val stops = listOf(
            stop("B", 0.0027, 0.0, "3"), // ~300 m north: the destination is south
            stop("G", -0.0081, 0.0, "6"), // ~900 m south: past the reach
            stop("A", -0.0018, 0.0, "1"),
        )
        assertEquals(listOf("490GA"), asks(stops).map { it.via })
    }

    @Test
    fun `a stop to the side is asked about when its line heads for the destination`() {
        // ~690 m due east, a little behind (~100 m farther from the destination): its bus runs south.
        val side = stop("E", 0.0005, 0.01, "5")
        assertEquals(listOf("490GE"), asks(listOf(side)).map { it.via })
        // Its line going nowhere near the destination, it takes no request.
        assertTrue(asks(listOf(side), network = emptyList()).isEmpty())
    }

    @Test
    fun `a line that never nears the destination picks no stop, leaving the cap to lines that do`() {
        val stops = listOf(
            stop("W", 0.0, -0.002, "8"), // ~140 m west: a line running east and west only
            stop("X", 0.0, 0.003, "9"), // ~210 m east, likewise
            stop("A", -0.0018, 0.0, "1"),
            stop("B", -0.0027, 0.0, "2"),
            stop("C", 0.0, 0.009, "3"), // ~620 m east: the bus towards the destination, past the nearer two
        )
        val sideways = listOf(stop("Y", 0.0, -0.03, "8", "9"), stop("Q", 0.0, 0.03, "8", "9"))
        val network = listOf(stop("Z", -0.036, 0.0, "1", "2", "3")) + sideways
        assertEquals(listOf("490GA", "490GB", "490GC"), asks(stops, network = network).map { it.via })
    }

    @Test
    fun `a stop's fallback is the next stop for its lines, never one already asked`() {
        val stops = listOf(
            stop("A", -0.0018, 0.0, "1", "2"),
            stop("C", -0.0027, 0.0, "2", "3"), // the next for line 2, but asked for line 3
            stop("E", -0.0036, 0.0, "1"), // the next for line 1
        )
        val asks = asks(stops)
        assertEquals(listOf("490GA", "490GC"), asks.map { it.via })
        assertEquals("490GE", asks.first().fallback)
        // C's only other stop for its lines is A, which is asked already.
        assertEquals(null, asks.last().fallback)
    }

    @Test
    fun `two stops never share a fallback, so two refusals never ask the same stop twice`() {
        val stops = listOf(
            stop("A", -0.0018, 0.0, "1"),
            stop("B", -0.0027, 0.0, "2"),
            // The next stop for both A's line and B's.
            stop("M", -0.0036, 0.0, "1", "2"),
            // The one after, for the 2 only.
            stop("N", -0.0045, 0.0, "2"),
        )
        val asks = asks(stops)
        assertEquals(listOf("490GA", "490GB"), asks.map { it.via })
        // A takes M; B, whose next is M too, falls back on N.
        assertEquals(listOf("490GM", "490GN"), asks.map { it.fallback })
        // With no N, B has none rather than M again.
        assertEquals(listOf("490GM", null), asks(stops.dropLast(1)).map { it.fallback })
    }

    @Test
    fun `at most a few stops are asked about, the nearest`() {
        val stops = (1..6).map { stop("S$it", -0.001 * it, 0.0, "$it") }
        val asks = asks(stops)
        assertEquals(TowardStops.MAX_ASKS, asks.size)
        assertEquals(listOf("490GS1", "490GS2", "490GS3"), asks.map { it.via })
        // Each serves one line of its own, so none has another stop to fall back on.
        assertTrue(asks.all { it.fallback == null })
    }

    @Test
    fun `a stop with no stop pair is asked via its own id`() {
        val stops = listOf(stop("490000001Z", -0.0018, 0.0, "1", pair = "Stop 490000001Z"))
        assertEquals(listOf("490000001Z"), asks(stops).map { it.via })
    }

    @Test
    fun `night buses and trains pick no stop of their own`() {
        val stops = listOf(
            stop("N", -0.0018, 0.0, "n9"),
            stop("T", -0.002, 0.0, "red"),
            stop("A", -0.0027, 0.0, "1", "n9"),
        )
        assertEquals(listOf("490GA"), asks(stops).map { it.via })
    }

    @Test
    fun `nothing is asked for a destination within the reach`() {
        val near = Coordinates(51.495, -0.12) // ~560 m
        assertTrue(asks(listOf(stop("A", -0.0018, 0.0, "1")), to = near).isEmpty())
    }

    @Test
    fun `nothing is asked with no stop towards the destination`() {
        assertTrue(asks(listOf(stop("B", 0.0027, 0.0, "3"))).isEmpty())
        assertTrue(asks(emptyList()).isEmpty())
    }

    @Test
    fun `keeps the routes whose first ride is a bus boarding at the stop asked via or passing it`() {
        val at = Instant.parse("2026-10-15T08:00:00Z")
        fun leg(mode: String, line: String, from: String, to: String, departs: Long, arrives: Long, area: String = "", path: List<String> = listOf(to)) = TripLeg(
            mode = mode, lineId = line, lineName = line, fromId = from, fromName = from, toId = to, toName = to,
            departure = at.plus(Duration.ofMinutes(departs)), arrival = at.plus(Duration.ofMinutes(arrives)), fromArea = area, path = path,
        )
        fun busFrom(pole: String, area: String, path: List<String>) = TripRoute(
            listOf(leg(TripLeg.WALKING, "", "", pole, 0, 4), leg("bus", "1", pole, "T", 6, 12, area = area, path = path), leg("tube", "red", "T", "X", 15, 30)),
        )
        // Boarding at the stop pair asked via, by its pair or its pole.
        val boards = busFrom("A1", "490GA", listOf("490GB", "T"))
        val boardsLone = busFrom("490000001Z", "", listOf("T"))
        // Boarding a stop earlier and calling at the one asked via: the Planner met the via on the bus.
        val passes = busFrom("P1", "490GP", listOf("490GA", "T"))
        // Through the stop on foot to a bus elsewhere that never calls there.
        val elsewhere = busFrom("Q1", "490GQ", listOf("490GB", "T"))
        // Through the stop on foot to the station: no bus from there.
        val onFoot = TripRoute(listOf(leg(TripLeg.WALKING, "", "", "T", 0, 14), leg("tube", "red", "T", "X", 15, 30)))
        val walk = TripRoute(listOf(leg(TripLeg.WALKING, "", "", "X", 0, 50)))
        assertEquals(listOf(boards, passes), TowardStops.ridesFrom(listOf(onFoot, boards, passes, elsewhere, walk), "490GA"))
        assertEquals(listOf(boardsLone), TowardStops.ridesFrom(listOf(boardsLone, elsewhere), "490000001Z"))
    }
}
