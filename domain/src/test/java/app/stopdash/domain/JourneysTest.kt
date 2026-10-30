package app.stopdash.domain

import java.io.BufferedReader
import java.io.StringReader
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

    private val journey = FavoriteJourney(from = JourneyEnd("TOP", "Top", 51.51, -0.12), to = JourneyEnd("MID", "Mid", 51.49, -0.12), lines = listOf(LineRef("example", "", "")))

    // A station model ([Hubs]) of [stations] (a record to its hub, each record its own station) and bus
    // [poles] (a pole to its hub, each pole its own stop), as the bundle groups them.
    private fun hubsOf(stations: Map<String, String> = emptyMap(), poles: Map<String, String> = emptyMap()): Hubs {
        val index = StationIndex(
            stations.map { (id, hub) -> IndexedStation(id, id, listOf("tube"), latitude = 51.5, longitude = -0.12, station = id, modelHub = hub) },
            revision = "abc",
        )
        val tsv = listOf("# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=abc", "L\t1\tbus\t1") +
            poles.map { (id, hub) -> "S\t$id\tB\t$id\t51.500000\t-0.120000\t\t\t\t\t\t1\t\t$id\t\t$hub" }
        return Hubs.of(index, BundledStops.read(BufferedReader(StringReader(tsv.joinToString("\n")))))!!
    }

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

    private val parkToHill = FavoriteJourney(JourneyEnd("PARKN", "Park", 51.500, -0.12), JourneyEnd("HILLN", "Hill", 51.510, -0.12), listOf(LineRef("b1", "B1", "bus")))

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

    private val kingToNorth = FavoriteJourney(JourneyEnd("KING", "King"), JourneyEnd("NORTH", "North End"), listOf(LineRef("example", "", "")))

    @Test
    fun `a train on the other branch is offered with where to change, only when no direct one is due`() {
        val segment = Journeys.segment(kingToNorth, forked, kingToNorth.lineIds.first())!!
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
    fun `a bus ending at the journey's origin goes nowhere, not unchecked`() {
        val parkToLane = parkToHill.copy(to = JourneyEnd("LANEN", "Lane", 51.505, -0.12))
        val segment = Journeys.segment(parkToLane, bus(), parkToLane.lineIds.first())!!
        // Arriving at Park's northbound pole to end at its stop area, and one setting off north.
        val ending = departure("Park", 30, lineId = "b1", mode = "bus").copy(destinationId = "G-PARK")
        val trains = Journeys.trains(segment, rowsAt("PARKN", ending, departure("Hill", 60, lineId = "b1", mode = "bus")), mapOf("b1" to bus()), parkToLane)
        assertEquals(listOf("Hill"), trains.rows.flatMap { row -> row.upcoming.map { it.destination } })
        assertFalse(trains.unresolved)
        assertTrue(trains.misses.isEmpty())
    }

    @Test
    fun `a train sharing no stop past the origin, or a bus, isn't offered as a change`() {
        // From Top, a Bottom B train runs via Side: nothing shared with the way to Mid.
        val segment = Journeys.segment(journey, rail, journey.lineIds.first())!!
        assertTrue(Journeys.trains(segment, rowsAt("TOP", departure("Bottom B", 60)), mapOf("example" to rail)).changes.isEmpty())
        // A bus on the other branch gets no change: its path is too loose to send a rider on.
        val busSegment = Journeys.segment(kingToNorth, forked, kingToNorth.lineIds.first())!!
        val bus = Journeys.trains(busSegment, rowsAt("KING", departure("West End", 60, mode = "bus")), mapOf("example" to forked), kingToNorth)
        assertTrue(bus.changes.isEmpty())
        // Nor on a bus journey whose predictions leave the mode off.
        val busJourney = kingToNorth.copy(lines = kingToNorth.lines.map { it.copy(mode = "bus") })
        val noMode = Journeys.trains(busSegment, rowsAt("KING", departure("West End", 60, mode = "")), mapOf("example" to forked), busJourney)
        assertTrue(noMode.changes.isEmpty())
    }

    @Test
    fun `a station journey boards and alights at its own stops both ways`() {
        assertEquals(JourneySegment("TOP", setOf("MID")), Journeys.segment(journey, rail, journey.lineIds.first()))
        assertEquals(JourneySegment("MID", setOf("TOP")), Journeys.segment(journey.reversed(), rail, journey.reversed().lineIds.first()))
    }

    @Test
    fun `a journey's far end is its station, whichever of its ids a route calls at`() {
        // The station model files MID and MID2 as one station; this line's route calls at MID2.
        fun coded(vararg stops: String) = LineSequence(
            routes = listOf(LineRoute("route", stops.toList())),
            stopNames = mapOf("TOP" to "Top", "TOP2" to "Top Low Level", "MID" to "Mid", "MID2" to "Mid Low Level", "BOTA" to "Bottom A"),
            stationCodes = mapOf("MID" to "1000003", "MID2" to "1000003", "TOP" to "1000004", "TOP2" to "1000004"),
        )
        val viaLow = coded("TOP", "MID2", "BOTA")
        val segment = Journeys.segment(journey, viaLow, journey.lineIds.first())
        assertEquals(JourneySegment("TOP", setOf("MID2")), segment)
        val trains = Journeys.trains(segment!!, rowsAt("TOP", departure("Bottom A", 60)), mapOf("example" to viaLow))
        assertEquals(listOf("Bottom A"), trains.rows.flatMap { it.upcoming }.map { it.destination })
        // A route calling at the far end itself gets off there, never at another id of its station first.
        assertEquals(JourneySegment("TOP", setOf("MID")), Journeys.segment(journey, coded("TOP", "MID2", "MID", "BOTA"), journey.lineIds.first()))
        // The origin, where the journey's departures are listed, stands for its station's other id the route calls at.
        assertEquals(JourneySegment("TOP", setOf("MID")), Journeys.segment(journey, coded("TOP2", "MID", "BOTA"), journey.lineIds.first()))
    }

    @Test
    fun `only trains that call at the other end are kept`() {
        val segment = Journeys.segment(journey, rail, journey.lineIds.first())!!
        val rows = rowsAt("TOP", departure("Bottom A", 120), departure("Bottom B", 60), departure("Bottom A", 600))
        val trains = Journeys.trains(segment, rows, mapOf("example" to rail))
        assertEquals(listOf("Bottom A", "Bottom A"), trains.rows.flatMap { it.upcoming }.map { it.destination })
        assertFalse(trains.pending)
        assertFalse(trains.unresolved)
    }

    @Test
    fun `a journey's row keeps the trains with no time to where its kept trains go, and only those`() {
        val segment = Journeys.segment(journey, rail, journey.lineIds.first())!!
        val toA = UntimedTrain(departure("Bottom A", 300), canceled = true)
        // Bottom B trains don't call at Mid, so neither does a canceled one: never a line of its own on the card.
        val toB = UntimedTrain(departure("Bottom B", 30), canceled = true)
        val rows = DepartureRows.across(
            listOf(
                StopArrivals(
                    "TOP", "TOP", listOf(departure("Bottom A", 120), departure("Bottom B", 60)), fetchedAt = now,
                    untimed = listOf(toA, toB),
                ),
            ),
            now,
        )
        val trains = Journeys.trains(segment, rows, mapOf("example" to rail))
        assertEquals(listOf(toA), trains.rows.flatMap { it.untimed })
    }

    @Test
    fun `a journey's row keeps a train with no time only by the via its kept train runs`() {
        // Two ways from Top to End: by Mid, the journey's far end, or by Side.
        val ways = LineSequence(
            routes = listOf(LineRoute("Top ↔ End by Mid", listOf("TOP", "MID", "END")), LineRoute("Top ↔ End by Side", listOf("TOP", "SIDE", "END"))),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "SIDE" to "Side", "END" to "End"),
        )
        val segment = Journeys.segment(journey, ways, journey.lineIds.first())!!
        val byMid = UntimedTrain(departure("End", 300, mode = "national-rail").copy(via = "Mid"), canceled = true)
        val bySide = UntimedTrain(departure("End", 400, mode = "national-rail").copy(via = "Side"), canceled = true)
        val rows = DepartureRows.across(
            listOf(
                StopArrivals(
                    "TOP", "TOP", listOf(departure("End", 120, mode = "national-rail").copy(via = "Mid")), fetchedAt = now,
                    untimed = listOf(byMid, bySide),
                ),
            ),
            now,
        )
        val trains = Journeys.trains(segment, rows, mapOf("example" to ways))
        assertEquals(listOf(byMid), trains.rows.flatMap { it.untimed })
    }

    @Test
    fun `a journey's warning row for a line whose every train has no time shows the warning alone`() {
        val segment = Journeys.segment(journey, rail, journey.lineIds.first())!!
        val disrupted = LineStatus("example", 6, "Severe Delays")
        val rows = DepartureRows.across(
            listOf(
                StopArrivals(
                    "TOP", "TOP", emptyList(), fetchedAt = now, lines = listOf(LineRef("example", "example", "tube")),
                    untimed = listOf(UntimedTrain(departure("Bottom A", 120), canceled = true)),
                ),
            ),
            now,
            mapOf("example" to disrupted),
        )
        val row = Journeys.trains(segment, rows, mapOf("example" to rail)).rows.single()
        assertEquals(disrupted, row.status)
        assertEquals(emptyList<UntimedTrain>(), row.untimed)
    }

    @Test
    fun `a bus journey's way back boards across the road, found by stop area`() {
        assertEquals(JourneySegment("PARKN", setOf("HILLN")), Journeys.segment(parkToHill, bus(), parkToHill.lineIds.first()))
        assertEquals(JourneySegment("HILLS", setOf("PARKS")), Journeys.segment(parkToHill.reversed(), bus(), parkToHill.reversed().lineIds.first()))
    }

    @Test
    fun `without stop areas the way back is found by name`() {
        assertEquals(JourneySegment("HILLS", setOf("PARKS")), Journeys.segment(parkToHill.reversed(), bus(areas = false), parkToHill.reversed().lineIds.first()))
    }

    @Test
    fun `a stop served one way only is stood in for by the nearest stop on the way back`() {
        val parkToLane = parkToHill.copy(to = JourneyEnd("LANEN", "Lane", 51.505, -0.12))
        // Southbound has no Lane: Loop, about 110 m away, stands in for it.
        assertEquals(JourneySegment("LOOPS", setOf("PARKS")), Journeys.segment(parkToLane.reversed(), bus(), parkToLane.reversed().lineIds.first()))
    }

    @Test
    fun `a station missing from a rail route isn't stood in for by a nearby one`() {
        // MID is gone from the route (a closure, say); SIDE is 100 m from where it was.
        val closed = LineSequence(
            routes = listOf(LineRoute("Top ↔ Bottom B via Side", listOf("TOP", "SIDE", "BOTB"))),
            stopNames = mapOf("TOP" to "Top", "SIDE" to "Side", "BOTB" to "Bottom B"),
            stopPositions = mapOf("TOP" to (51.51 to -0.12), "SIDE" to (51.4909 to -0.12), "BOTB" to (51.48 to -0.12)),
        )
        assertNull(Journeys.segment(journey.copy(lines = journey.lines.map { it.copy(mode = "tube") }), closed, (journey.copy(lines = journey.lines.map { it.copy(mode = "tube") })).lineIds.first()))
    }

    @Test
    fun `with nothing to match the way back can't be placed`() {
        val far = parkToHill.copy(to = JourneyEnd("HILLN", "Hill", 52.0, -0.12))
        assertNull(Journeys.segment(far.reversed(), bus(areas = false, names = false).copy(stopPositions = emptyMap()), far.reversed().lineIds.first()))
    }

    @Test
    fun `every line serving the segment is shown, not just the starred one`() {
        val segment = Journeys.segment(parkToHill, bus(), parkToHill.lineIds.first())!!
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
    fun `a line hidden from the ride is left off it, both ways, while the others stay`() {
        val segment = Journeys.segment(parkToHill, bus(), parkToHill.lineIds.first())!!
        val otherRoute = bus().copy(routes = listOf(LineRoute("Park ↔ Hill", listOf("PARKN", "HILLN"))))
        val rows = rowsAt("PARKN", departure("Hill", 120, "b1", "bus"), departure("Hill", 60, "b2", "bus"))
        val sequences = mapOf("b1" to bus(), "b2" to otherRoute)
        val hiding = Journeys.withLineHidden(listOf(parkToHill), parkToHill, HiddenModes.lineKey("b2", "B2"), hidden = true).single()
        assertEquals(listOf("b1"), Journeys.trains(segment, rows, sequences, hiding).rows.map { it.lineId })
        // The way back hides it too: one ride, both directions.
        assertTrue(hiding.reversed().hides("B2"))
        // Without the ride, or on a ride hiding nothing, both lines are kept.
        assertEquals(setOf("b1", "b2"), Journeys.trains(segment, rows, sequences, parkToHill).rows.mapTo(HashSet()) { it.lineId })
        // A pole beside the origin boarding only the hidden line doesn't board the ride.
        assertFalse(Journeys.boardsTowards(LineRef("b2", "B2", "bus"), hiding, null))
        assertTrue(Journeys.boardsTowards(LineRef("b3", "B3", "bus"), hiding, null))
    }

    @Test
    fun `hiding and showing a ride's line keeps its other hides, any of its lines included`() {
        val other = journey.copy(to = JourneyEnd("BOTA", "Bottom A"))
        val saved = listOf(parkToHill, other)
        val b2 = HiddenModes.lineKey("b2", "B2")
        val b3 = HiddenModes.lineKey("b3", "B3")
        val hidden = Journeys.withLineHidden(Journeys.withLineHidden(saved, parkToHill.reversed(), b2, true), parkToHill, b3, true)
        assertEquals(setOf(b2, b3), hidden[0].hiddenLines)
        // Another ride is untouched.
        assertEquals(other, hidden[1])
        assertEquals(listOf(b2 to "B2", b3 to "B3"), Journeys.hiddenLines(hidden[0]))
        // Each in the app's words, saved in English: a tube line takes its word for "line", a bus route none.
        val central = HiddenModes.lineKey("central", "Central line")
        assertEquals(
            listOf(b2 to "B2", central to "ligne Central"),
            Journeys.hiddenLines(parkToHill.copy(hiddenLines = linkedSetOf(b2, central)), line = { "ligne $it" }),
        )
        // Shown again by its line, whatever label it was hidden under.
        val shown = Journeys.withLineHidden(hidden, parkToHill, HiddenModes.lineKey("B2", "Renamed"), false)
        assertEquals(setOf(b3), shown[0].hiddenLines)
        // The line it was first found on can be hidden too: no line is the ride's own (maintainer, 2026-10-24).
        val b1 = HiddenModes.lineKey("b1", "B1")
        assertEquals(setOf(b2, b3, b1), Journeys.withLineHidden(hidden, parkToHill, b1, true)[0].hiddenLines)
        assertTrue(parkToHill.copy(hiddenLines = setOf(b1)).hides("b1"))
        // A ride not saved, or an entry that's no line's, changes nothing.
        assertEquals(saved, Journeys.withLineHidden(saved, journey.copy(to = JourneyEnd("SIDE", "Side")), b2, true))
        assertEquals(saved, Journeys.withLineHidden(saved, parkToHill, "bus", true))
    }

    @Test
    fun `a bus whose soonest prediction has no mode still uses the bus route-end rule`() {
        val segment = Journeys.segment(parkToHill, bus(), parkToHill.lineIds.first())!!
        // "Town" names no stop: only the bus rule (run to the route's end) places it.
        val rows = rowsAt("PARKN", departure("Town", 60, "b1", ""), departure("Town", 600, "b1", "bus"))
        val trains = Journeys.trains(segment, rows, mapOf("b1" to bus()))
        assertEquals(2, trains.rows.single().upcoming.size)
        assertFalse(trains.unresolved)
    }

    @Test
    fun `another route reaching a different pole of the far end is shown too`() {
        val segment = Journeys.segment(parkToHill, bus(), parkToHill.lineIds.first())!!
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
        val segment = Journeys.segment(parkToHill, bus(), parkToHill.lineIds.first())!!
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
        val segment = Journeys.segment(parkToHill, bus(), parkToHill.lineIds.first())!!
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
        // Named alike 200 m off is still the place (StopPlace's 250 m); further off it's another place, and
        // a stop as close by another name is too.
        assertEquals(listOf("b2"), shown(b2(51.5118 to -0.12)).map { it.lineId })
        assertTrue(shown(b2(51.5125 to -0.12)).isEmpty())
        assertTrue(shown(b2(51.5109 to -0.12, "Ridge")).isEmpty())
    }

    @Test
    fun `a route variant reaching the far end's other stop counts where another reaches its own`() {
        val segment = Journeys.segment(parkToHill, bus(), parkToHill.lineIds.first())!!
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
    fun `a bus in a station's hub reaching a stop in the far end's hub boards the journey`() {
        // A rail journey Top → Mid, each station in its hub. In Top's, stop TOPB boards bus b7 to MIDB, in
        // Mid's; bus b8 from TOPC goes elsewhere; TOPD's tram t9 serves no stop in Mid's hub at all.
        val hubs = hubsOf(
            stations = mapOf("TOP" to "HUBT", "MID" to "HUBM"),
            poles = mapOf("TOPB" to "HUBT", "TOPC" to "HUBT", "TOPD" to "HUBT", "MIDB" to "HUBM", "MIDV" to "MIDV", "NEAR" to "NEAR"),
        )
        val far = listOf(
            StopLocation("MID", "Mid", 51.49, -0.12, listOf(LineRef("example", "Example", "tube"))),
            StopLocation("MIDB", "Mid Road", 51.4905, -0.12, listOf(LineRef("b7", "7", "bus"), LineRef("b8", "8", "bus"))),
            // A short walk from Mid but in a hub of its own: a ride runs between hubs (maintainer, 2026-10-21).
            StopLocation("MIDV", "Mid Village", 51.4895, -0.12, listOf(LineRef("b9", "9", "bus"))),
            StopLocation("DALE", "Dale", 51.45, -0.12, listOf(LineRef("t9", "T9", "tram"))),
        )
        val farEnd = Journeys.farEnd(journey.to, far, hubs)!!
        assertEquals(setOf("MID", "MIDB"), farEnd.ids)
        assertEquals(setOf("example", "b7", "b8"), farEnd.lineIds)
        val around = listOf(
            StopLocation("TOPC", "Top Lane", 51.5115, -0.12, listOf(LineRef("b8", "8", "bus"))),
            StopLocation("TOPB", "Top Road", 51.5102, -0.12, listOf(LineRef("b7", "7", "bus"))),
            StopLocation("TOPD", "Top Tram", 51.5101, -0.12, listOf(LineRef("t9", "T9", "tram"))),
            StopLocation("TOP", "Top", 51.51, -0.12, listOf(LineRef("example", "Example", "tube"))),
            // Right outside Top, but in a hub of its own: never boards the ride.
            StopLocation("NEAR", "Top Corner", 51.5103, -0.12, listOf(LineRef("b7", "7", "bus"))),
            // One the model doesn't hold is only itself.
            StopLocation("FAR", "Far", 51.52, -0.12, listOf(LineRef("b7", "7", "bus"))),
        )
        val poles = Journeys.stopsInHub(journey.from, around, hubs)
        assertEquals(listOf("TOP", "TOPD", "TOPB", "TOPC"), poles.map { it.id })
        val b7 = LineSequence(listOf(LineRoute("Top ↔ Mid", listOf("TOPB", "MIDB"))), mapOf("TOPB" to "Top Road", "MIDB" to "Mid Road"))
        val b8 = LineSequence(listOf(LineRoute("Mid ↔ Top", listOf("MIDB", "TOPC"))), mapOf("TOPC" to "Top Lane", "MIDB" to "Mid Road"))
        // The tram serves nothing near Mid: not weighed, so its route isn't loaded.
        val loading = Journeys.siblingPoles(journey, "TOP", poles, mapOf("example" to rail), farEnd)
        assertEquals(setOf("b7", "b8"), loading.pendingLines)
        // b8 runs the other way: only b7's stop boards the journey, a bus beside a tube journey.
        val placed = Journeys.siblingPoles(journey, "TOP", poles, mapOf("example" to rail, "b7" to b7, "b8" to b8), farEnd)
        assertEquals(listOf("TOPB"), placed.poles.map { it.id })
        assertFalse(placed.capped)
        // A line the origin is fetched for that reaches the far end from there isn't boarded again
        // beside it (a station's twin id, the way-back pole)...
        val through = LineSequence(listOf(LineRoute("Top ↔ Mid", listOf("TOP", "TOPB", "MIDB"))), mapOf("MIDB" to "Mid Road"))
        val twice = Journeys.siblingPoles(journey, "TOP", poles, mapOf("example" to rail, "b7" to through, "b8" to b8), farEnd, originLineIds = setOf("b7"))
        assertTrue(twice.poles.isEmpty())
        // ...but where its route from the origin doesn't get there, the stop beside it boards it.
        val elsewhere = Journeys.siblingPoles(journey, "TOP", poles, mapOf("example" to rail, "b7" to b7, "b8" to b8), farEnd, originLineIds = setOf("b7"))
        assertEquals(listOf("TOPB"), elsewhere.poles.map { it.id })
        // Its departures count, calling at the stop near Mid.
        val rows = rowsAt("TOPB", departure("Mid Road", 60, "b7", "bus"))
        val trains = Journeys.trains(JourneySegment("TOPB", emptySet()), rows, mapOf("b7" to b7), journey, farEnd)
        assertEquals(listOf("b7"), trains.rows.map { it.lineId })
        assertEquals(setOf("MIDB"), trains.reachedIds)
        // Another line at that stop, one that reaches nothing near Mid, has no route loaded: weighed only
        // for the lines it boards, the card isn't held checking on a route never asked for.
        val busier = rowsAt("TOPB", departure("Mid Road", 60, "b7", "bus"), departure("Elsewhere", 90, "b9", "bus"))
        assertTrue(Journeys.trains(JourneySegment("TOPB", emptySet()), busier, mapOf("b7" to b7), journey, farEnd).pending)
        val boarded = Journeys.trains(JourneySegment("TOPB", emptySet()), busier, mapOf("b7" to b7), journey, farEnd, lines = setOf("b7"))
        assertFalse(boarded.pending)
        assertEquals(listOf("b7"), boarded.rows.map { it.lineId })
        // A departure TfL gave no line id may still be one that gets there: never filtered out as another line.
        val unnamed = rowsAt("TOPB", departure("Mid Road", 60, "b7", "bus"), departure("Somewhere", 90, "", "bus"))
        assertTrue(Journeys.trains(JourneySegment("TOPB", emptySet()), unnamed, mapOf("b7" to b7), journey, farEnd, lines = setOf("b7")).unresolved)
    }

    @Test
    fun `a stop near the far end listed under its interchange sibling's id is reached`() {
        // The lookup lists the far end's station as MIDX, in Mid's hub; the line's route calls at its sibling
        // MIDY, the same station by the model, in interchange HUBM.
        val far = listOf(StopLocation("MIDX", "Mid Road", 51.4905, -0.12, listOf(LineRef("r1", "R1", "national-rail")), interchangeId = "HUBM"))
        val farEnd = Journeys.farEnd(journey.to, far, hubsOf(stations = mapOf("MID" to "HUBM", "MIDX" to "HUBM")))!!
        assertEquals(setOf("MIDX"), farEnd.ids)
        val r1 = LineSequence(
            listOf(LineRoute("Top ↔ Mid", listOf("TOPR", "MIDY"))),
            mapOf("TOPR" to "Top Rail", "MIDY" to "Mid Road"),
            stopHubs = mapOf("MIDY" to "HUBM"),
            stationCodes = mapOf("MIDX" to "S1", "MIDY" to "S1"),
        )
        val poles = listOf(StopLocation("TOPR", "Top Rail", 51.5101, -0.12, listOf(LineRef("r1", "R1", "national-rail"))))
        val placed = Journeys.siblingPoles(journey, "TOP", poles, mapOf("r1" to r1), farEnd)
        assertEquals(listOf("TOPR"), placed.poles.map { it.id })
        val rows = rowsAt("TOPR", departure("Mid Road", 60, "r1", "national-rail"))
        val trains = Journeys.trains(JourneySegment("TOPR", emptySet()), rows, mapOf("r1" to r1), journey, farEnd)
        assertEquals(listOf("r1"), trains.rows.map { it.lineId })
    }

    @Test
    fun `a card boards from at most its nearest few stops`() {
        val lines = (1..8).map { "b$it" }
        val farEnd = Journeys.FarEnd(setOf("MIDB"), lines.toSet())
        // Eight stops, each its own line to the stop near Mid, nearest first.
        val poles = lines.mapIndexed { i, line ->
            StopLocation("P$i", "Top Road", 51.51 + i * 0.0001, -0.12, listOf(LineRef(line, line, "bus")))
        }
        val sequences = lines.mapIndexed { i, line ->
            line to LineSequence(listOf(LineRoute("Top ↔ Mid", listOf("P$i", "MIDB"))), mapOf("MIDB" to "Mid Road"))
        }.toMap()
        // Six boarding stops in all: the origin and its five nearest neighbors.
        val capped = Journeys.siblingPoles(journey, "TOP", poles, sequences, farEnd, Journeys.MAX_BOARDING_STOPS - 1)
        assertEquals((0 until 5).map { "P$it" }, capped.poles.map { it.id })
        assertTrue(capped.capped)
        assertEquals(8, Journeys.siblingPoles(journey, "TOP", poles, sequences, farEnd).poles.size)
    }

    @Test
    fun `an end with no position has no stops around it`() {
        val end = JourneyEnd("X", "X")
        assertTrue(Journeys.stopsInHub(end, listOf(StopLocation("X", "X", 51.5, -0.12)), null).isEmpty())
        assertNull(Journeys.farEnd(end, emptyList(), null))
    }

    @Test
    fun `an end is its hub where the model places it, else its own stop and stop area alone`() {
        // TfL's stop area G-TOP holds TOPA, TOPB and TOPC; the model files TOPC in another hub. TOPX is in
        // TOPA's hub but not its area.
        val end = JourneyEnd("TOPA", "Top", 51.51, -0.12, areaId = "G-TOP")
        val around = listOf(
            StopLocation("TOPB", "Top", 51.5101, -0.12, clusterId = "G-TOP"),
            StopLocation("TOPA", "Top", 51.51, -0.12, clusterId = "G-TOP"),
            StopLocation("TOPX", "Top", 51.5102, -0.12, interchangeId = "HUBT"),
            StopLocation("TOPC", "Top", 51.5103, -0.12, clusterId = "G-TOP"),
        )
        // The model loaded: the hub, never a pole of the area in another (Codex, #1047).
        val model = hubsOf(poles = mapOf("TOPA" to "HUBT", "TOPB" to "HUBT", "TOPX" to "HUBT", "TOPC" to "HUBC"))
        assertEquals(listOf("TOPA", "TOPB", "TOPX"), Journeys.stopsInHub(end, around, model).map { it.id })
        // Still loading or unavailable, or a model that doesn't hold the end: its own stop and stop area.
        assertEquals(listOf("TOPA", "TOPB", "TOPC"), Journeys.stopsInHub(end, around, null).map { it.id })
        val elsewhere = hubsOf(poles = mapOf("ELSE" to "HUBE"))
        assertEquals(listOf("TOPA", "TOPB", "TOPC"), Journeys.stopsInHub(end, around, elsewhere).map { it.id })
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
        val segment = Journeys.segment(parkToHill, bus(), parkToHill.lineIds.first())!!
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
    fun `a departure with no line id isn't a definite no`() {
        val segment = Journeys.segment(journey, rail, journey.lineIds.first())!!
        val trains = Journeys.trains(segment, rowsAt("TOP", departure("Bottom A", 60, lineId = "")), mapOf("example" to rail))
        assertTrue(trains.rows.isEmpty())
        assertTrue(trains.unresolved)
        assertEquals(setOf(RouteMiss("", "TOP", RouteStops.Resolution.NoLine, "Bottom A")), trains.misses)
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
        val toMid = Journeys.trains(Journeys.segment(journey, forked, journey.lineIds.first())!!, rowsAt("TOP", departure("Check Front of Train", 60)), sequences)
        assertEquals(1, toMid.rows.single().upcoming.size)
        assertFalse(toMid.unresolved)
        assertTrue("MID" in toMid.reachedIds)
        // Only one branch reaches Side: it can't be vouched for either way, and says so.
        val toSide = FavoriteJourney(JourneyEnd("TOP", "Top", 51.51, -0.12), JourneyEnd("SIDE", "Side", 51.48, -0.12), listOf(LineRef("example", "", "")))
        val sideTrains = Journeys.trains(
            Journeys.segment(toSide, forked, toSide.lineIds.first())!!, rowsAt("TOP", departure("Check Front of Train", 60)), sequences, toSide,
        )
        assertTrue(sideTrains.rows.isEmpty())
        assertTrue(sideTrains.unresolved)
        assertEquals(setOf(RouteMiss("example", "TOP", RouteStops.Resolution.NoDestination, "Check Front of Train")), sideTrains.misses)
    }

    @Test
    fun `a line whose route is loading or failed isn't a definite no`() {
        val segment = Journeys.segment(journey, rail, journey.lineIds.first())!!
        val rows = rowsAt("TOP", departure("Bottom A", 120, "other"))
        val loading = Journeys.trains(segment, rows, mapOf("example" to rail))
        assertTrue(loading.pending)
        // Which line it waits on, for the debug log.
        assertEquals(setOf("other"), loading.pendingLines)
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
        val trains = Journeys.trains(Journeys.segment(journey, rail, journey.lineIds.first())!!, rows, mapOf("example" to rail, "other" to null))
        assertTrue(trains.unresolved)
    }

    @Test
    fun `a train whose path can't be resolved is unresolved, while another branch's is a definite no`() {
        val segment = Journeys.segment(journey, rail, journey.lineIds.first())!!
        val sequences = mapOf("example" to rail)
        val unknown = Journeys.trains(segment, rowsAt("TOP", departure("Nowhere", 120)), sequences)
        assertTrue(unknown.rows.isEmpty())
        assertTrue(unknown.unresolved)
        assertEquals(setOf(RouteMiss("example", "TOP", RouteStops.Resolution.NoMatch, "Nowhere")), unknown.misses)
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
            listOf(StopArrivals("TOP", "Top", emptyList(), fetchedAt = now, lines = listOf(journey.lines.single()))),
            now,
            mapOf("example" to suspended),
        )
        val trains = Journeys.trains(Journeys.segment(journey, rail, journey.lineIds.first())!!, rows, mapOf("example" to rail))
        assertEquals(listOf(suspended), trains.rows.map { it.status })
    }

    @Test
    fun `starring either way round, or from another line, toggles the same journey`() {
        val starred = Journeys.toggle(emptyList(), journey)
        assertEquals(1, starred.size)
        assertTrue(Journeys.toggle(starred, journey.reversed()).isEmpty())
        assertTrue(Journeys.toggle(starred, journey.copy(lines = listOf(app.stopdash.domain.LineRef("other", "", "")))).isEmpty())
    }

    @Test
    fun `the journey declares its line for the origin fetch`() {
        val named = journey.copy(lines = journey.lines.map { it.copy(name = "Example", mode = "tube") })
        assertEquals(LineRef("example", "Example", "tube"), named.lines.single())
        assertEquals(named.lines.single(), named.reversed().lines.single())
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
    fun `journeys go nearest first by their nearer end, the unmeasured last in saved order`() {
        // Synthetic ends on one meridian; the rider's position is synthetic too.
        val south = FavoriteJourney(JourneyEnd("S1", "South 1", 51.45, -0.12), JourneyEnd("S2", "South 2", 51.44, -0.12), listOf(LineRef("example", "", "")))
        val unplaced = journey.copy(to = JourneyEnd("MID", "Mid"))
        val saved = listOf(journey, unplaced, south)
        // Near the south end: the south journey leads, the unmeasured one last.
        assertEquals(listOf(south, journey, unplaced), Journeys.nearestFirst(saved, 51.46, -0.12))
        // Near the north end: the saved order, the unmeasured one still last.
        assertEquals(listOf(journey, south, unplaced), Journeys.nearestFirst(saved, 51.52, -0.12))
        // Turned the other way round, a journey keeps its place: its nearer end is the same.
        assertEquals(
            listOf(south.reversed(), journey, unplaced),
            Journeys.nearestFirst(listOf(journey, unplaced, south.reversed()), 51.46, -0.12),
        )
        // Without a position, the saved order stands.
        assertEquals(saved, Journeys.nearestFirst(saved, null, null))
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
    fun `a relocate holds a far journey's stops back only on a confirmed fix, unopened`() {
        val stops = mapOf(journey.key to setOf("ORIGIN"))
        fun hold(fixConfirmed: Boolean, revealed: Set<String>) =
            Journeys.stopIdsToHoldBack(listOf(journey), 51.47, -0.12, fixConfirmed, revealed, stops)
        assertEquals(setOf("ORIGIN"), hold(fixConfirmed = true, revealed = emptySet()))
        // A retained or last-known fix may be where the rider was: keep fetching.
        assertEquals(emptySet<String>(), hold(fixConfirmed = false, revealed = emptySet()))
        // Opened from its collapsed card: keep fetching it, and only it.
        assertEquals(emptySet<String>(), hold(fixConfirmed = true, revealed = setOf(journey.key)))
        assertEquals(setOf("ORIGIN"), hold(fixConfirmed = true, revealed = setOf("another|ride")))
        // Its own view is open: the screen keeps it, so keep fetching it.
        assertEquals(
            emptySet<String>(),
            Journeys.stopIdsToHoldBack(listOf(journey), 51.47, -0.12, true, emptySet(), stops, openJourneyKey = journey.key),
        )
        // A near journey is never held back.
        assertEquals(
            emptySet<String>(),
            Journeys.stopIdsToHoldBack(listOf(journey), 51.51, -0.12, true, emptySet(), stops),
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
    fun `a relocate that turns a shown journey round waits for its new origin`() {
        val shown = setOf(journey.key)
        // Shown from Top's end; the fresh fix is nearer Mid, so Mid is now the origin.
        assertTrue(Journeys.turnsShownJourney(listOf(journey), 51.509, -0.12, 51.491, -0.12, shown))
        // Still nearer Top: the same origin, nothing to wait for.
        assertFalse(Journeys.turnsShownJourney(listOf(journey), 51.509, -0.12, 51.505, -0.12, shown))
        // Not on screen (held back far away): the screen won't report it, so nothing to wait for.
        assertFalse(Journeys.turnsShownJourney(listOf(journey), 51.509, -0.12, 51.491, -0.12, emptySet()))
        // No end coordinates: the saved direction stands either way.
        val unplaced = journey.copy(to = journey.to.copy(latitude = null, longitude = null))
        assertFalse(Journeys.turnsShownJourney(listOf(unplaced), 51.509, -0.12, 51.491, -0.12, shown))
    }

    @Test
    fun `a card's origin takes its pole's interchange, else its route's`() {
        val sequence = LineSequence(emptyList(), emptyMap(), stopHubs = mapOf("STN" to "HUBSEQ"))
        assertEquals("HUBSEQ", Journeys.originHub("STN", sequence, null))
        val pole = StopLocation("STN", "Across", 51.5, -0.12, interchangeId = "HUBPOL")
        assertEquals("HUBPOL", Journeys.originHub("STN", sequence, pole))
        assertEquals("", Journeys.originHub("STN", null, null))
    }

    @Test
    fun `a trip's ride is the favorite ride between where it boards and gets off`() {
        val leg = TripLeg(
            "tube", "victoria", "Victoria", "940GZZLUEUS", "Euston", "940GZZLUKSX", "King's Cross St. Pancras",
            now, now.plusSeconds(120), fromAt = Coordinates(51.5, -0.12),
        )
        val ride = Journeys.ofLeg(leg)!!
        assertEquals(JourneyEnd("940GZZLUEUS", "Euston", 51.5, -0.12), ride.from)
        assertEquals(JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras"), ride.to)
        assertEquals(listOf(LineRef("victoria", "Victoria", "tube")), ride.lines)
        // A bus's ends keep the stop pair the Planner named them in, as a route page's do.
        val bus = Journeys.ofLeg(leg.copy(mode = "bus", lineId = "73", lineName = "73", fromId = "490A", toId = "490B", fromArea = "490G1", toArea = "490G2"))!!
        assertEquals("490G1", bus.from.areaId)
        assertEquals("490G2", bus.to.areaId)
        // Nothing for a walk, a line a ride can't follow, or an end with no stop.
        assertNull(Journeys.ofLeg(leg.copy(mode = TripLeg.WALKING, lineId = "")))
        assertNull(Journeys.ofLeg(leg.copy(mode = "cable-car", lineId = "london-cable-car")))
        assertNull(Journeys.ofLeg(leg.copy(toId = "")))
    }

    @Test
    fun `a bus ride's way back from across the road is the ride saved already`() {
        // Synthetic poles: each end's two poles share a stop area, one served each way.
        val route = LineSequence(
            routes = listOf(LineRoute("Out", listOf("A1", "M1", "B1")), LineRoute("Back", listOf("B2", "M2", "A2"))),
            stopNames = mapOf("A1" to "Alpha", "A2" to "Alpha", "B1" to "Beta", "B2" to "Beta", "M1" to "Mid", "M2" to "Mid"),
            stopAreas = mapOf("A1" to "AREA_A", "A2" to "AREA_A", "B1" to "AREA_B", "B2" to "AREA_B"),
        )
        val out = FavoriteJourney(JourneyEnd("A1", "Alpha", areaId = "AREA_A"), JourneyEnd("B1", "Beta", areaId = "AREA_B"), listOf(LineRef("1", "1", "bus")))
        val back = FavoriteJourney(JourneyEnd("B2", "Beta", areaId = "AREA_B"), JourneyEnd("A2", "Alpha", areaId = "AREA_A"), listOf(LineRef("1", "1", "bus")))
        assertTrue(Journeys.savedAlready(listOf(out), back, route))
        assertTrue(Journeys.savedAlready(listOf(out), out.reversed(), null))
        // Without the route only the same stops count; another stretch of the route is another ride.
        assertFalse(Journeys.savedAlready(listOf(out), back, null))
        val shorter = FavoriteJourney(JourneyEnd("A1", "Alpha", areaId = "AREA_A"), JourneyEnd("M1", "Mid"), listOf(LineRef("1", "1", "bus")))
        assertFalse(Journeys.savedAlready(listOf(out), shorter, route))
    }
}
