package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops, lines and positions only (around the obviously-fake (51.5, -0.12)). */
class JourneysTest {
    private val now = Instant.parse("2026-09-23T08:00:00Z")

    // A line with two branches from "Top": via "Mid" to "Bottom A", and via "Side" to "Bottom B".
    // Stations: one id serves both directions.
    private val rail = LineSequence(
        routes = listOf(
            LineRoute("Top ↔ Bottom A via Mid", listOf("TOP", "MID", "BOTA")),
            LineRoute("Top ↔ Bottom B via Side", listOf("TOP", "SIDE", "BOTB")),
            LineRoute("Bottom A ↔ Top via Mid", listOf("BOTA", "MID", "TOP")),
        ),
        stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "SIDE" to "Side", "BOTB" to "Bottom B"),
    )

    private val journey = StarredJourney(
        from = JourneyEnd("TOP", "Top", 51.51, -0.12),
        to = JourneyEnd("MID", "Mid", 51.49, -0.12),
        lineId = "example",
    )

    // A bus route: each stop has a pole per direction ("…N" northbound, "…S" southbound) in one
    // stop area, except "Lane", served northbound only. Southbound it runs past "Lane" via "Loop".
    private fun bus(areas: Boolean = true, names: Boolean = true) = LineSequence(
        routes = listOf(
            LineRoute("Park ↔ Hill", listOf("PARKN", "LANEN", "HILLN")),
            LineRoute("Hill ↔ Park", listOf("HILLS", "LOOPS", "PARKS")),
        ),
        stopNames = if (!names) {
            emptyMap()
        } else {
            mapOf(
                "PARKN" to "Park", "PARKS" to "Park", "LANEN" to "Lane", "HILLN" to "Hill", "HILLS" to "Hill",
                "LOOPS" to "Loop",
            )
        },
        stopPositions = mapOf(
            "PARKN" to (51.500 to -0.12), "PARKS" to (51.5001 to -0.12),
            "LANEN" to (51.505 to -0.12), "LOOPS" to (51.506 to -0.12),
            "HILLN" to (51.510 to -0.12), "HILLS" to (51.5101 to -0.12),
        ),
        stopAreas = if (!areas) {
            emptyMap()
        } else {
            mapOf("PARKN" to "G-PARK", "PARKS" to "G-PARK", "HILLN" to "G-HILL", "HILLS" to "G-HILL", "LANEN" to "G-LANE")
        },
    )

    private val parkToHill = StarredJourney(
        JourneyEnd("PARKN", "Park", 51.500, -0.12), JourneyEnd("HILLN", "Hill", 51.510, -0.12), "b1", "B1", "bus",
    )

    private fun departure(destination: String, inSeconds: Long, lineId: String = "example", mode: String = "tube") =
        Departure(
            lineId = lineId,
            lineName = lineId,
            direction = "outbound",
            destination = destination,
            platform = null,
            expectedArrival = now.plusSeconds(inSeconds),
            mode = mode,
        )

    private fun rowsAt(stopId: String, vararg departures: Departure) = DepartureRows.across(
        listOf(StopArrivals(stopId, stopId, departures.toList(), fetchedAt = now)),
        now,
    )

    @Test
    fun `the nearer end becomes the origin`() {
        assertEquals("TOP", Journeys.oriented(journey, 51.509, -0.12).from.stopId)
        assertEquals("MID", Journeys.oriented(journey, 51.491, -0.12).from.stopId)
    }

    @Test
    fun `without a position the saved direction stands`() {
        assertEquals("TOP", Journeys.oriented(journey, null, null).from.stopId)
        val noCoords = journey.copy(to = journey.to.copy(latitude = null, longitude = null))
        assertEquals("TOP", Journeys.oriented(noCoords, 51.491, -0.12).from.stopId)
    }

    // A line forking past a shared trunk: from "King" both branches run via "Mid" to "Fork", then
    // one to "West End" and the other to "North End".
    private val forked = LineSequence(
        routes = listOf(
            LineRoute("King ↔ West End", listOf("KING", "MID", "FORK", "WEST1", "WEST")),
            LineRoute("King ↔ North End", listOf("KING", "MID", "FORK", "NORTH1", "NORTH")),
        ),
        stopNames = mapOf(
            "KING" to "King", "MID" to "Mid", "FORK" to "Fork", "WEST1" to "West Park", "WEST" to "West End",
            "NORTH1" to "North Park", "NORTH" to "North End",
        ),
    )

    private val kingToNorth = StarredJourney(JourneyEnd("KING", "King"), JourneyEnd("NORTH", "North End"), "example")

    @Test
    fun `a train on the other branch is offered with where to change, only when no direct one is due`() {
        val segment = Journeys.segment(kingToNorth, forked)!!
        val rows = rowsAt(
            "KING",
            departure("West End", 60), departure("North End", 300), departure("West End", 480),
        )
        val trains = Journeys.trains(segment, rows, mapOf("example" to forked), kingToNorth)
        assertEquals(listOf("North End"), trains.rows.flatMap { it.upcoming }.map { it.destination })
        val change = trains.changes.single()
        assertEquals("FORK" to "Fork", change.stopId to change.stopName)
        assertEquals(listOf(60L, 480L), change.row.upcoming.map { it.expectedArrival.epochSecond - now.epochSecond })
        assertFalse("a change isn't an unresolved train", trains.unresolved)
        // A direct train is due, so none is offered, even the one leaving sooner.
        assertTrue(Journeys.changesWithoutDirect(trains.rows, trains.changes).isEmpty())
        // With no direct train at all, every change-train is offered.
        val noDirect = Journeys.trains(segment, rowsAt("KING", departure("West End", 60), departure("West End", 480)), mapOf("example" to forked), kingToNorth)
        assertTrue(noDirect.rows.isEmpty())
        assertEquals(2, Journeys.changesWithoutDirect(noDirect.rows, noDirect.changes).single().row.upcoming.size)
        // A suspended direct line's status-only row is no direct train due: changes are still offered.
        val suspended = trains.rows.single().copy(upcoming = emptyList())
        assertFalse(Journeys.directDue(listOf(suspended)))
        assertEquals(1, Journeys.changesWithoutDirect(listOf(suspended), noDirect.changes).size)
    }

    @Test
    fun `a train sharing no stop past the origin, or a bus, isn't offered as a change`() {
        // From Top, a Bottom B train runs via Side: nothing shared with the way to Mid.
        val segment = Journeys.segment(journey, rail)!!
        assertTrue(Journeys.trains(segment, rowsAt("TOP", departure("Bottom B", 60)), mapOf("example" to rail)).changes.isEmpty())
        // A bus on the other branch gets no change: its path is too loose to send a rider on.
        val busSegment = Journeys.segment(kingToNorth, forked)!!
        val bus = Journeys.trains(busSegment, rowsAt("KING", departure("West End", 60, mode = "bus")), mapOf("example" to forked), kingToNorth)
        assertTrue(bus.changes.isEmpty())
        // Nor on a bus journey whose predictions leave the mode off.
        val busJourney = kingToNorth.copy(mode = "bus")
        val noMode = Journeys.trains(busSegment, rowsAt("KING", departure("West End", 60, mode = "")), mapOf("example" to forked), busJourney)
        assertTrue(noMode.changes.isEmpty())
    }

    @Test
    fun `a station journey boards and alights at its own stops both ways`() {
        assertEquals(JourneySegment("TOP", setOf("MID")), Journeys.segment(journey, rail))
        assertEquals(JourneySegment("MID", setOf("TOP")), Journeys.segment(journey.reversed(), rail))
    }

    @Test
    fun `only trains that call at the other end are kept`() {
        val segment = Journeys.segment(journey, rail)!!
        val rows = rowsAt("TOP", departure("Bottom A", 120), departure("Bottom B", 60), departure("Bottom A", 600))
        val trains = Journeys.trains(segment, rows, mapOf("example" to rail))
        assertEquals(listOf("Bottom A", "Bottom A"), trains.rows.flatMap { it.upcoming }.map { it.destination })
        assertFalse(trains.pending)
        assertFalse(trains.unresolved)
    }

    @Test
    fun `a bus journey's way back boards across the road, found by stop area`() {
        assertEquals(JourneySegment("PARKN", setOf("HILLN")), Journeys.segment(parkToHill, bus()))
        assertEquals(JourneySegment("HILLS", setOf("PARKS")), Journeys.segment(parkToHill.reversed(), bus()))
    }

    @Test
    fun `without stop areas the way back is found by name`() {
        assertEquals(JourneySegment("HILLS", setOf("PARKS")), Journeys.segment(parkToHill.reversed(), bus(areas = false)))
    }

    @Test
    fun `a stop served one way only is stood in for by the nearest stop on the way back`() {
        val parkToLane = parkToHill.copy(to = JourneyEnd("LANEN", "Lane", 51.505, -0.12))
        // Southbound has no Lane: Loop, about 110 m away, stands in for it.
        assertEquals(JourneySegment("LOOPS", setOf("PARKS")), Journeys.segment(parkToLane.reversed(), bus()))
    }

    @Test
    fun `a station missing from a rail route isn't stood in for by a nearby one`() {
        // MID is gone from the route (a closure, say); SIDE is 100 m from where it was.
        val closed = LineSequence(
            routes = listOf(LineRoute("Top ↔ Bottom B via Side", listOf("TOP", "SIDE", "BOTB"))),
            stopNames = mapOf("TOP" to "Top", "SIDE" to "Side", "BOTB" to "Bottom B"),
            stopPositions = mapOf("TOP" to (51.51 to -0.12), "SIDE" to (51.4909 to -0.12), "BOTB" to (51.48 to -0.12)),
        )
        assertNull(Journeys.segment(journey.copy(mode = "tube"), closed))
    }

    @Test
    fun `with nothing to match the way back can't be placed`() {
        val far = parkToHill.copy(to = JourneyEnd("HILLN", "Hill", 52.0, -0.12))
        assertNull(Journeys.segment(far.reversed(), bus(areas = false, names = false).copy(stopPositions = emptyMap())))
    }

    @Test
    fun `every line serving the segment is shown, not just the starred one`() {
        val segment = Journeys.segment(parkToHill, bus())!!
        val otherRoute = bus().copy(routes = listOf(LineRoute("Park ↔ Hill", listOf("PARKN", "HILLN"))))
        val elsewhere = LineSequence(listOf(LineRoute("Park ↔ Dale", listOf("PARKN", "DALEN"))), mapOf("DALEN" to "Dale"))
        val rows = rowsAt(
            "PARKN",
            departure("Hill", 120, "b1", "bus"),
            departure("Hill", 60, "b2", "bus"),
            departure("Dale", 30, "b3", "bus"),
        )
        val trains = Journeys.trains(segment, rows, mapOf("b1" to bus(), "b2" to otherRoute, "b3" to elsewhere))
        assertEquals(setOf("b1", "b2"), trains.rows.mapTo(HashSet()) { it.lineId })
    }

    @Test
    fun `a bus whose soonest prediction has no mode still uses the bus route-end rule`() {
        val segment = Journeys.segment(parkToHill, bus())!!
        // "Town" names no stop: only the bus rule (run to the route's end) places it.
        val rows = rowsAt("PARKN", departure("Town", 60, "b1", ""), departure("Town", 600, "b1", "bus"))
        val trains = Journeys.trains(segment, rows, mapOf("b1" to bus()))
        assertEquals(2, trains.rows.single().upcoming.size)
        assertFalse(trains.unresolved)
    }

    @Test
    fun `another route reaching a different pole of the far end is shown too`() {
        val segment = Journeys.segment(parkToHill, bus())!!
        // b2 boards at the same pole but stops at Hill's other northbound pole, HILLN2.
        val b2 = LineSequence(
            routes = listOf(LineRoute("Park ↔ Hill", listOf("PARKN", "HILLN2"))),
            stopNames = mapOf("PARKN" to "Park", "HILLN2" to "Hill"),
        )
        val rows = rowsAt("PARKN", departure("Hill", 60, "b2", "bus"))
        val trains = Journeys.trains(segment, rows, mapOf("b1" to bus(), "b2" to b2), parkToHill)
        assertEquals(listOf("b2"), trains.rows.map { it.lineId })
    }

    @Test
    fun `another route merely passing near the far end isn't taken to serve it`() {
        val segment = Journeys.segment(parkToHill, bus())!!
        // b2 leaves Park but turns off at Ridge, about 110 m from Hill, and never calls there.
        val b2 = LineSequence(
            routes = listOf(LineRoute("Park ↔ Dale", listOf("PARKN", "RIDGEN", "DALEN"))),
            stopNames = mapOf("PARKN" to "Park", "RIDGEN" to "Ridge", "DALEN" to "Dale"),
            stopPositions = mapOf("PARKN" to (51.500 to -0.12), "RIDGEN" to (51.511 to -0.12), "DALEN" to (51.52 to -0.12)),
        )
        val rows = rowsAt("PARKN", departure("Dale", 60, "b2", "bus"))
        assertTrue(Journeys.trains(segment, rows, mapOf("b1" to bus(), "b2" to b2), parkToHill).rows.isEmpty())
        // Nor can the journey be placed on b2's route page; on its own line the fallback still applies.
        assertNull(Journeys.segment(parkToHill, b2, "b2"))
        assertEquals(JourneySegment("PARKN", setOf("RIDGEN")), Journeys.segment(parkToHill, b2, "b1"))
    }

    @Test
    fun `another line stopping at the far end's place under another name is shown`() {
        val segment = Journeys.segment(parkToHill, bus())!!
        // b2 stops at "Hill Station / High Road": another stop area and name, about 100 m from Hill.
        fun b2(at: Pair<Double, Double>, name: String = "Hill Station / High Road") = LineSequence(
            routes = listOf(LineRoute("Park ↔ Dale", listOf("PARKN", "HIGHN", "DALEN"))),
            stopNames = mapOf("PARKN" to "Park", "HIGHN" to name, "DALEN" to "Dale"),
            stopPositions = mapOf("PARKN" to (51.500 to -0.12), "HIGHN" to at, "DALEN" to (51.52 to -0.12)),
            stopAreas = mapOf("HIGHN" to "G-HIGH"),
        )
        val rows = rowsAt("PARKN", departure("Dale", 60, "b2", "bus"))
        fun shown(line: LineSequence) = Journeys.trains(segment, rows, mapOf("b1" to bus(), "b2" to line), parkToHill).rows
        assertEquals(listOf("b2"), shown(b2(51.5109 to -0.12)).map { it.lineId })
        // The far-end stop it actually reaches is reported, so that stop's closure is checked too.
        assertEquals(
            setOf("HIGHN"),
            Journeys.trains(segment, rows, mapOf("b1" to bus(), "b2" to b2(51.5109 to -0.12)), parkToHill).reachedIds,
        )
        // The same name further off is another place; a stop as close by another name is too.
        assertTrue(shown(b2(51.5125 to -0.12)).isEmpty())
        assertTrue(shown(b2(51.5109 to -0.12, "Ridge")).isEmpty())
    }

    @Test
    fun `a route variant reaching the far end's other stop counts where another reaches its own`() {
        val segment = Journeys.segment(parkToHill, bus())!!
        // b2's short variant stops at Hill itself; its long one at "Hill / High Road", 100 m off.
        val b2 = LineSequence(
            routes = listOf(
                LineRoute("Park ↔ Hill", listOf("PARKN", "HILLN")),
                LineRoute("Park ↔ Dale", listOf("PARKN", "HIGHN", "DALEN")),
            ),
            stopNames = mapOf("PARKN" to "Park", "HILLN" to "Hill", "HIGHN" to "Hill / High Road", "DALEN" to "Dale"),
            stopPositions = mapOf(
                "PARKN" to (51.500 to -0.12), "HILLN" to (51.510 to -0.12),
                "HIGHN" to (51.5109 to -0.12), "DALEN" to (51.52 to -0.12),
            ),
        )
        val rows = rowsAt("PARKN", departure("Dale", 60, "b2", "bus"))
        val trains = Journeys.trains(segment, rows, mapOf("b1" to bus(), "b2" to b2), parkToHill)
        assertEquals(listOf("b2"), trains.rows.map { it.lineId })
    }

    @Test
    fun `a pole beside the origin boarding another line to the far end is a sibling`() {
        fun pole(id: String, vararg lines: String, mode: String = "bus") =
            StopLocation(id, "Park", 51.5, -0.12, lines.map { LineRef(it, it, mode) }, clusterId = "G-PARK", stopLetter = id.takeLast(1))
        // b3 boards at PARKK, beside the origin PARKN, and runs to Hill; b4 at PARKL runs elsewhere.
        val b3 = LineSequence(listOf(LineRoute("Park ↔ Hill", listOf("PARKK", "HILLN"))), mapOf("PARKK" to "Park", "HILLN" to "Hill"))
        val b4 = LineSequence(listOf(LineRoute("Park ↔ Dale", listOf("PARKL", "DALEN"))), mapOf("PARKL" to "Park", "DALEN" to "Dale"))
        val poles = listOf(
            pole("PARKN", "b1"),
            // The way-back pole serves only the origin's line: not looked at.
            pole("PARKS", "b1"),
            pole("PARKK", "b3"),
            pole("PARKL", "b4"),
            pole("PARKT", "t1", mode = "tram"),
        )
        val loading = Journeys.siblingPoles(parkToHill, "PARKN", poles, mapOf("b1" to bus()))
        assertEquals(setOf("b3", "b4"), loading.pendingLines)
        val placed = Journeys.siblingPoles(parkToHill, "PARKN", poles, mapOf("b1" to bus(), "b3" to b3, "b4" to b4))
        assertEquals(listOf("PARKK"), placed.poles.map { it.id })
        assertTrue(placed.settled)
        // A line whose route failed leaves its pole undecided, not ruled out.
        val failed = Journeys.siblingPoles(parkToHill, "PARKN", poles, mapOf("b1" to bus(), "b3" to b3, "b4" to null))
        assertEquals(setOf("b4"), failed.failedLines)
        assertFalse(failed.settled)
        // A line TfL gave no mode is judged by its route, not skipped.
        assertTrue(Journeys.ofMode(LineRef("b5", "b5", ""), "bus"))
        assertFalse(Journeys.ofMode(LineRef("t1", "t1", "tram"), "bus"))
        // Its departures are the card's too.
        val rows = rowsAt("PARKK", departure("Hill", 60, "b3", "bus"))
        val trains = Journeys.trains(JourneySegment("PARKK", emptySet()), rows, mapOf("b3" to b3), parkToHill)
        assertEquals(listOf("b3"), trains.rows.map { it.lineId })
    }

    @Test
    fun `a suspended line kept for its warning reports the far-end stop it serves`() {
        val segment = Journeys.segment(parkToHill, bus())!!
        val b2 = LineSequence(
            routes = listOf(LineRoute("Park ↔ Hill", listOf("PARKN", "HILLN2"))),
            stopNames = mapOf("PARKN" to "Park", "HILLN2" to "Hill"),
        )
        val suspended = LineStatus("b2", 6, "Suspended")
        val rows = DepartureRows.across(
            listOf(StopArrivals("PARKN", "Park", emptyList(), now, lines = listOf(LineRef("b2", "B2", "bus")))),
            now,
            mapOf("b2" to suspended),
        )
        val trains = Journeys.trains(segment, rows, mapOf("b1" to bus(), "b2" to b2), parkToHill)
        assertEquals(listOf("b2"), trains.rows.map { it.lineId })
        assertTrue("HILLN2" in trains.reachedIds)
    }

    @Test
    fun `same place needs the same name start and nearness`() {
        val here = 51.5 to -0.12
        val near = 51.5009 to -0.12
        assertTrue(Journeys.samePlace("Hill", here, "Hill Station  / High Road", near))
        assertTrue(Journeys.samePlace("Hill Station", here, "hill station", near))
        assertFalse(Journeys.samePlace("Hill", here, "Hill Road", near))
        assertFalse(Journeys.samePlace("Hill", here, "Hill", 51.502 to -0.12))
        assertFalse(Journeys.samePlace("Hill", null, "Hill", near))
    }

    @Test
    fun `a departure with no line id isn't a definite no`() {
        val segment = Journeys.segment(journey, rail)!!
        val trains = Journeys.trains(segment, rowsAt("TOP", departure("Bottom A", 60, lineId = "")), mapOf("example" to rail))
        assertTrue(trains.rows.isEmpty())
        assertTrue(trains.unresolved)
        assertEquals(setOf(RouteMiss("", "TOP", RouteStops.Resolution.NoLine)), trains.misses)
    }

    @Test
    fun `a train with no destination yet counts when every way it may run reaches the far end`() {
        // A line that forks after "Mid": on to "Bottom A", or by "Side" to "Bottom B".
        val forked = LineSequence(
            routes = listOf(
                LineRoute("Top ↔ Bottom A", listOf("TOP", "MID", "BOTA")),
                LineRoute("Top ↔ Bottom B", listOf("TOP", "MID", "SIDE", "BOTB")),
            ),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "SIDE" to "Side", "BOTB" to "Bottom B"),
        )
        val sequences = mapOf("example" to forked)
        // TfL's "Check Front of Train": either branch passes Mid, so it's a train to Mid.
        val toMid = Journeys.trains(Journeys.segment(journey, forked)!!, rowsAt("TOP", departure("Check Front of Train", 60)), sequences)
        assertEquals(1, toMid.rows.single().upcoming.size)
        assertFalse(toMid.unresolved)
        assertTrue("MID" in toMid.reachedIds)
        // Only one branch reaches Side: it can't be vouched for either way, and says so.
        val toSide = StarredJourney(JourneyEnd("TOP", "Top", 51.51, -0.12), JourneyEnd("SIDE", "Side", 51.48, -0.12), "example")
        val sideTrains = Journeys.trains(
            Journeys.segment(toSide, forked)!!, rowsAt("TOP", departure("Check Front of Train", 60)), sequences, toSide,
        )
        assertTrue(sideTrains.rows.isEmpty())
        assertTrue(sideTrains.unresolved)
        assertEquals(setOf(RouteMiss("example", "TOP", RouteStops.Resolution.NoDestination)), sideTrains.misses)
    }

    @Test
    fun `a line whose route is loading or failed isn't a definite no`() {
        val segment = Journeys.segment(journey, rail)!!
        val rows = rowsAt("TOP", departure("Bottom A", 120, "other"))
        assertTrue(Journeys.trains(segment, rows, mapOf("example" to rail)).pending)
        assertTrue(Journeys.trains(segment, rows, mapOf("example" to rail, "other" to null)).unresolved)
    }

    @Test
    fun `a suspended line whose route failed to load isn't dropped silently`() {
        val suspended = LineStatus("other", 6, "Suspended")
        val rows = DepartureRows.across(
            listOf(StopArrivals("TOP", "Top", emptyList(), fetchedAt = now, lines = listOf(LineRef("other", "Other", "tube")))),
            now,
            mapOf("other" to suspended),
        )
        val trains = Journeys.trains(Journeys.segment(journey, rail)!!, rows, mapOf("example" to rail, "other" to null))
        assertTrue(trains.unresolved)
    }

    @Test
    fun `a train whose path can't be resolved is unresolved, while another branch's is a definite no`() {
        val segment = Journeys.segment(journey, rail)!!
        val sequences = mapOf("example" to rail)
        val unknown = Journeys.trains(segment, rowsAt("TOP", departure("Nowhere", 120)), sequences)
        assertTrue(unknown.rows.isEmpty())
        assertTrue(unknown.unresolved)
        assertEquals(setOf(RouteMiss("example", "TOP", RouteStops.Resolution.NoMatch)), unknown.misses)
        val otherBranch = Journeys.trains(segment, rowsAt("TOP", departure("Bottom B", 60)), sequences)
        assertTrue(otherBranch.rows.isEmpty())
        assertFalse(otherBranch.unresolved)
        // A definite no is no miss.
        assertTrue(otherBranch.misses.isEmpty())
    }

    @Test
    fun `a suspended line with no trains keeps its warning`() {
        val suspended = LineStatus("example", 6, "Suspended")
        val rows = DepartureRows.across(
            listOf(StopArrivals("TOP", "Top", emptyList(), fetchedAt = now, lines = listOf(journey.line))),
            now,
            mapOf("example" to suspended),
        )
        val trains = Journeys.trains(Journeys.segment(journey, rail)!!, rows, mapOf("example" to rail))
        assertEquals(listOf(suspended), trains.rows.map { it.status })
    }

    @Test
    fun `starring either way round, or from another line, toggles the same journey`() {
        val starred = Journeys.toggle(emptyList(), journey)
        assertEquals(1, starred.size)
        assertTrue(Journeys.toggle(starred, journey.reversed()).isEmpty())
        assertTrue(Journeys.toggle(starred, journey.copy(lineId = "other")).isEmpty())
    }

    @Test
    fun `the journey declares its line for the origin fetch`() {
        val named = journey.copy(lineName = "Example", mode = "tube")
        assertEquals(LineRef("example", "Example", "tube"), named.line)
        assertEquals(named.line, named.reversed().line)
    }

    @Test
    fun `a journey is near within a mile of either end and far beyond, by its nearer end`() {
        // Synthetic ends ~2.2 km apart on one meridian; the rider's position is synthetic too.
        // At one end: near.
        assertNull(Journeys.farMeters(journey, 51.51, -0.12))
        // Between the ends, ~1.1 km from each: near.
        assertNull(Journeys.farMeters(journey, 51.50, -0.12))
        // ~2.2 km beyond the far end: far, by the distance to that nearer end.
        val far = Journeys.farMeters(journey, 51.47, -0.12)!!
        assertTrue(far in 2000.0..2400.0)
    }

    @Test
    fun `a journey with no position or no end coordinates counts as near`() {
        assertNull(Journeys.farMeters(journey, null, null))
        val unplaced = journey.copy(to = JourneyEnd("MID", "Mid"))
        assertNull(Journeys.farMeters(unplaced, 52.5, -0.12))
    }

    @Test
    fun `only a confirmed fix holds a far journey back`() {
        val far = Journeys.farJourneys(listOf(journey), 51.47, -0.12, fixConfirmed = true)
        assertEquals(setOf(journey.key), far.keys)
        // An approximate or unrefreshed fix may be where the rider was: show everything.
        assertTrue(Journeys.farJourneys(listOf(journey), 51.47, -0.12, fixConfirmed = false).isEmpty())
        // A near journey is never held back.
        assertTrue(Journeys.farJourneys(listOf(journey), 51.51, -0.12, fixConfirmed = true).isEmpty())
    }

    @Test
    fun `a held-back journey's stops stop being fetched unless another journey needs them`() {
        val byJourney = mapOf("far" to setOf("A", "B"), "near" to setOf("B", "C"))
        assertEquals(setOf("A"), Journeys.heldBackStopIds(byJourney, setOf("far")))
        assertEquals(emptySet<String>(), Journeys.heldBackStopIds(byJourney, emptySet()))
    }

    @Test
    fun `a relocate holds a far journey's stops back only on a confirmed fix, unrevealed`() {
        val stops = mapOf(journey.key to setOf("ORIGIN"))
        fun hold(fixConfirmed: Boolean, revealed: Boolean) =
            Journeys.stopIdsToHoldBack(listOf(journey), 51.47, -0.12, fixConfirmed, revealed, stops)
        assertEquals(setOf("ORIGIN"), hold(fixConfirmed = true, revealed = false))
        // A retained or last-known fix may be where the rider was: keep fetching.
        assertEquals(emptySet<String>(), hold(fixConfirmed = false, revealed = false))
        assertEquals(emptySet<String>(), hold(fixConfirmed = true, revealed = true))
        // Its own view is open: the screen keeps it, so keep fetching it.
        assertEquals(
            emptySet<String>(),
            Journeys.stopIdsToHoldBack(listOf(journey), 51.47, -0.12, true, false, stops, openJourneyKey = journey.key),
        )
        // A near journey is never held back.
        assertEquals(
            emptySet<String>(),
            Journeys.stopIdsToHoldBack(listOf(journey), 51.51, -0.12, true, false, stops),
        )
    }

    @Test
    fun `a relocate releases a held journey within a mile, or on an unconfirmed fix`() {
        val held = setOf(journey.key)
        assertTrue(Journeys.releasesHeldJourney(listOf(journey), 51.51, -0.12, true, held))
        // An unconfirmed fix holds nothing back, so the screen shows it again.
        assertTrue(Journeys.releasesHeldJourney(listOf(journey), 51.47, -0.12, false, held))
        // Still far on a confirmed fix, or not held (already shown): nothing to wait for.
        assertFalse(Journeys.releasesHeldJourney(listOf(journey), 51.47, -0.12, true, held))
        assertFalse(Journeys.releasesHeldJourney(listOf(journey), 51.51, -0.12, true, emptySet()))
    }

    @Test
    fun `a card's origin takes its pole's interchange, else its route's`() {
        val sequence = LineSequence(emptyList(), emptyMap(), stopHubs = mapOf("STN" to "HUBSEQ"))
        assertEquals("HUBSEQ", Journeys.originHub("STN", sequence, null))
        val pole = StopLocation("STN", "Across", 51.5, -0.12, hubId = "HUBPOL")
        assertEquals("HUBPOL", Journeys.originHub("STN", sequence, pole))
        assertEquals("", Journeys.originHub("STN", null, null))
    }
}
