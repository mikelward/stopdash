package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLinesTest {
    // Synthetic lines and stops (SPEC *Privacy*): red is the Planner's, A → B with nothing between.
    private val at = Instant.parse("2026-09-27T08:00:00Z")

    private fun leg(lineId: String, from: String, to: String, path: List<String> = listOf(to), mode: String = "tube") =
        TripLeg(mode, lineId, lineId, from, from, to, to, at, at.plusSeconds(600), path = path)

    private fun train(lineId: String, mode: String = "tube") =
        Departure(lineId = lineId, lineName = lineId, direction = "outbound", destination = "End", platform = null, expectedArrival = at, mode = mode)

    private fun sequence(vararg stops: String) = LineSequence(routes = listOf(LineRoute("route", stops.toList())), stopNames = stops.associateWith { it })

    // Where each ride gets off: the Planner's call ([LegEnd.planned]), as every caller has it until the end is read.
    private val planned = { route: TripRoute -> LegEnd.planned(route, StepFree.ANY) }
    // Getting off anywhere in a hub the stops here aren't in: an end that would change every answer were it read.
    private val byHub = { route: TripRoute -> route.rides.map { it to LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBZ")) } }

    private fun ended(ride: TripLeg) = ride to LegEnd.planned(ride, StepFree.ANY)

    private val ride = leg("red", "A", "B")
    private val route = TripRoute(listOf(ride))
    private val atA = mapOf("A" to listOf(train("red"), train("green")))

    private fun linesOf(sequences: Map<String, LineSequence?>, hidden: Set<String> = emptySet(), arrivals: Map<String, List<Departure>> = atA) =
        RideLines.of(listOf(route), planned, arrivals, emptyMap(), sequences, hidden).getValue(ended(ride))

    @Test
    fun `a line at the ride's boarding stop that runs to its getting-off stop is an equal`() {
        val lines = linesOf(mapOf("green" to sequence("A", "B", "End")))
        assertEquals(listOf("red", "green"), lines.legs.map { it.lineId })
        assertEquals(listOf("red", "green"), lines.timed.map { it.lineId })
        assertEquals(listOf("green"), RideLines.lineIds(listOf(route), atA, emptyMap()))
    }

    @Test
    fun `a ride's candidates are its own mode's, in the plan or on its board, not another ride's`() {
        // A tube ride, two bus rides, then another tube ride: lineIds asks after every ride's.
        val bus7 = leg("7", "D", "E", mode = "bus")
        val bus8 = leg("8", "E", "F", mode = "bus")
        val green = leg("green", "F", "G")
        val plan = TripRoute(listOf(ride, bus7, bus8, green))
        assertEquals(setOf("green", "8", "7", "red"), RideLines.lineIds(listOf(plan), atA, emptyMap()).toSet())
        // The tube ride's are the tube lines: green in the plan and on its board, never the buses.
        assertEquals(listOf("green"), RideLines.candidateIds(ride, listOf(plan), atA, emptyMap()))
        assertEquals(listOf("8"), RideLines.candidateIds(bus7, listOf(plan), atA, emptyMap()))
    }

    @Test
    fun `a line reaching the stop another way is shown but doesn't time the ride`() {
        val lines = linesOf(mapOf("green" to sequence("A", "Z", "B")))
        assertEquals(listOf("red", "green"), lines.legs.map { it.lineId })
        assertEquals(listOf("red"), lines.timed.map { it.lineId })
    }

    @Test
    fun `another stop, a route missing the change, no route, another mode or a hidden line is no equal`() {
        assertEquals(listOf("red"), linesOf(mapOf("green" to sequence("A2", "B"))).legs.map { it.lineId })
        assertEquals(listOf("red"), linesOf(mapOf("green" to sequence("A", "End"))).legs.map { it.lineId })
        assertEquals(listOf("red"), linesOf(emptyMap()).legs.map { it.lineId })
        val bus = mapOf("A" to listOf(train("green", mode = "bus")))
        assertEquals(listOf("red"), linesOf(mapOf("green" to sequence("A", "B")), arrivals = bus).legs.map { it.lineId })
        val hidden = setOf(HiddenModes.lineKey("green", "Green"))
        assertEquals(listOf("red"), linesOf(mapOf("green" to sequence("A", "B")), hidden = hidden).legs.map { it.lineId })
    }

    @Test
    fun `a road's two poles are one stop`() {
        // The Planner's bus boards at pole Bs of pair BG; bus 2 at the other pole, Bn, fetched with it.
        val bus = leg("1", "Bs", "Cn", path = listOf("XG", "CG"), mode = "bus").copy(fromArea = "BG", toArea = "CG")
        val two = LineSequence(
            routes = listOf(LineRoute("north", listOf("Bn", "Xn", "Cn2"))),
            stopNames = mapOf("Bn" to "B", "Xn" to "X", "Cn2" to "C"),
            stopAreas = mapOf("Bn" to "BG", "Xn" to "XG", "Cn2" to "CG"),
        )
        val lines = RideLines.of(
            listOf(TripRoute(listOf(bus))),
            planned,
            mapOf("Bn" to listOf(train("2", mode = "bus"))),
            mapOf("BG" to listOf("Bs", "Bn")),
            mapOf("2" to two),
        ).getValue(ended(bus))
        assertEquals(listOf("1", "2"), lines.legs.map { it.lineId })
        assertEquals("Bn" to "Cn2", lines.legs[1].let { it.fromId to it.toId })
        assertEquals(listOf("1", "2"), lines.timed.map { it.lineId })
    }

    @Test
    fun `a pair's other pole is read only where one of its lines may run the ride`() {
        // The Planner's bus 1 boards at Bs of pair BG and gets off at pair CG. Bn's bus 2 runs there; Bw's
        // bus 3 goes the other way; Bx's bus 4 has no route known; By lists no lines; Bz serves only tube
        // and the ride's own line.
        val bus = leg("1", "Bs", "Cn", path = listOf("XG", "CG"), mode = "bus").copy(fromArea = "BG", toArea = "CG")
        fun pole(id: String, vararg lines: Pair<String, String>) = StopLocation(id, "B", 0.0, 0.0, lines = lines.map { (line, mode) -> LineRef(line, line, mode) })
        val bs = pole("Bs", "1" to "bus")
        val bn = pole("Bn", "2" to "bus")
        val bw = pole("Bw", "3" to "bus")
        val bx = pole("Bx", "4" to "bus")
        val by = pole("By")
        val bz = pole("Bz", "red" to "tube", "1" to "bus")
        val sequences = mapOf(
            "2" to LineSequence(listOf(LineRoute("north", listOf("Bn", "Xn", "Cn2"))), emptyMap(), stopAreas = mapOf("Bn" to "BG", "Xn" to "XG", "Cn2" to "CG")),
            "3" to LineSequence(listOf(LineRoute("south", listOf("Cn2", "Bw"))), emptyMap(), stopAreas = mapOf("Bw" to "BG", "Cn2" to "CG")),
        )
        val poles = listOf(bs, bn, bw, bx, by, bz)
        // The ride's own pole is read anyway; of the rest, those that may have a bus taking the ride.
        assertEquals(listOf(bn, bx, by), RideLines.polesToRead(bus, LegEnd.planned(bus, StepFree.ANY), poles, sequences))
        // A hidden line's pole needn't be read for it.
        assertEquals(listOf(bx, by), RideLines.polesToRead(bus, LegEnd.planned(bus, StepFree.ANY), poles, sequences, hidden = setOf(HiddenModes.lineKey("2", "2"))))
        // A line of another mode at a pole doesn't take a bus ride, whatever its route.
        val tube = pole("Bt", "2" to "tube")
        assertEquals(emptyList<StopLocation>(), RideLines.polesToRead(bus, LegEnd.planned(bus, StepFree.ANY), listOf(tube), sequences))
        // The routes it goes by: the buses that may take the ride, never the ride's own or another mode.
        assertEquals(listOf("2", "3", "4"), RideLines.pairLineIds(bus, poles + tube))
        // Where the ride gets off isn't read yet: a hub end reads the same poles as the Planner's call.
        assertEquals(listOf(bn, bx, by), RideLines.polesToRead(bus, LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBZ")), poles, sequences))
    }

    @Test
    fun `another line counts only once checked as running, the Planner's always`() {
        val green = leg("green", "A", "B")
        val good = LineStatus("green", LineStatus.GOOD_SERVICE, "Good Service")
        val open = { _: TripLeg -> true }
        assertTrue(RideLines.checked(ride, ride, emptyMap(), open))
        assertFalse(RideLines.checked(green, ride, emptyMap(), open))
        assertTrue(RideLines.checked(green, ride, mapOf("green" to good), open))
        assertFalse(RideLines.checked(green, ride, mapOf("green" to LineStatus("green", 20, "Service Closed")), open))
        // Running, but from a stop not checked open (closed, or not checked yet): not a way to go.
        // The Planner's own line is judged where the route is ranked, so it still counts.
        assertFalse(RideLines.checked(green, ride, mapOf("green" to good)) { false })
        assertTrue(RideLines.checked(ride, ride, emptyMap()) { false })
    }

    @Test
    fun `a ride's running lines and its unchecked ones follow the same rule`() {
        val lines = linesOf(mapOf("green" to sequence("A", "B", "End")))
        val good = listOf("red", "green").associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }
        val closed = mapOf("green" to LineStatus("green", 20, "Service Closed"))
        val open = { _: TripLeg -> true }
        assertEquals(listOf("red"), lines.running(emptyMap(), open).map { it.lineId })
        assertEquals(listOf("red"), lines.timedRunning(closed, open).map { it.lineId })
        assertEquals(listOf("red", "green"), lines.running(good, open).map { it.lineId })
        assertEquals(listOf("red", "green"), lines.timedRunning(good, open).map { it.lineId })
        // Green's own stop closed: neither offered nor timing, however it's running.
        val atClosed = { line: TripLeg -> line.lineId != "green" }
        assertEquals(listOf("red"), lines.running(good, atClosed).map { it.lineId })
        assertEquals(listOf("red"), lines.timedRunning(good, atClosed).map { it.lineId })
        // Unknown is unchecked; known, even closed, isn't: that one says why it doesn't count.
        assertEquals(setOf("green"), RideLines.unchecked(listOf(lines), emptyMap()))
        assertEquals(emptySet<String>(), RideLines.unchecked(listOf(lines), closed))
    }

    @Test
    fun `the Planner's line answers to its status once another line keeps the ride usable`() {
        val lines = linesOf(mapOf("green" to sequence("A", "B", "End")))
        val good = { id: String -> LineStatus(id, LineStatus.GOOD_SERVICE, "Good Service") }
        val closed = { id: String -> LineStatus(id, 20, "Service Closed") }
        val open = { _: TripLeg -> true }
        val red = lines.legs.first()
        // Nothing else takes the ride: red is judged where the route is ranked, so it counts here
        // whatever its status.
        for (statuses in listOf(emptyMap(), mapOf("red" to closed("red")), mapOf("red" to closed("red"), "green" to closed("green")))) {
            assertFalse(lines.othersTime(statuses, open))
            assertTrue(lines.vouched(red, statuses, open))
            assertEquals(listOf("red"), lines.timedRunning(statuses, open).map { it.lineId })
        }
        // Green runs, so the route no longer answers to red's status: red, closed or never checked,
        // neither times the ride nor is offered (Codex on #382).
        for (statuses in listOf(mapOf("green" to good("green")), mapOf("red" to closed("red"), "green" to good("green")))) {
            assertTrue(lines.othersTime(statuses, open))
            assertFalse(lines.vouched(red, statuses, open))
            assertEquals(listOf("green"), lines.timedRunning(statuses, open).map { it.lineId })
            assertEquals(listOf("green"), lines.running(statuses, open).map { it.lineId })
        }
        // Red checked as running still counts beside green.
        val both = mapOf("red" to good("red"), "green" to good("green"))
        assertEquals(listOf("red", "green"), lines.timedRunning(both, open).map { it.lineId })
        // A line that only reaches the stop another way doesn't take the ride, so red still ranks it.
        val around = linesOf(mapOf("green" to sequence("A", "Z", "B")))
        assertFalse(around.othersTime(mapOf("green" to good("green")), open))
        assertEquals(listOf("red", "green"), around.running(mapOf("green" to good("green")), open).map { it.lineId })
    }

    @Test
    fun `a line whose route runs through a sibling id of the same station is an equal`() {
        // Green's departures are listed under A, but its route calls at A1: the same station by the model,
        // as TfL does at some stations with several sets of platforms.
        val green = LineSequence(
            routes = listOf(LineRoute("route", listOf("A1", "B", "End"))),
            stopNames = mapOf("A" to "Alpha", "A1" to "Alpha", "B" to "B", "End" to "End"),
            stopHubs = mapOf("A" to "HUB", "A1" to "HUB"),
            stationCodes = mapOf("A" to "SA", "A1" to "SA"),
        )
        val lines = linesOf(mapOf("green" to green))
        assertEquals(listOf("red", "green"), lines.legs.map { it.lineId })
        assertEquals("A" to "B", lines.legs[1].let { it.fromId to it.toId })
        assertEquals(listOf("red", "green"), lines.timed.map { it.lineId })
    }

    @Test
    fun `a line calling at another id of the getting-off stop's station is an equal, getting off there`() {
        // The station model files B and B2 as one station, with no interchange or name in common; green calls at B2.
        fun green(vararg stops: String, codes: Map<String, String> = mapOf("B" to "1000002", "B2" to "1000002")) = LineSequence(
            routes = listOf(LineRoute("route", stops.toList())),
            stopNames = mapOf("A" to "A", "B" to "Beta", "B2" to "Beta Low Level", "End" to "End"),
            stationCodes = codes,
        )
        val lines = linesOf(mapOf("green" to green("A", "B2", "End")))
        assertEquals(listOf("red", "green"), lines.legs.map { it.lineId })
        assertEquals("A" to "B2", lines.legs[1].let { it.fromId to it.toId })
        // And it rides the same stretch, so it may time the ride: getting off at B2 is getting off at B (Codex on #1003).
        assertEquals(listOf("red", "green"), lines.timed.map { it.lineId })
        // Not one station without the model saying so.
        assertEquals(listOf("red"), linesOf(mapOf("green" to green("A", "B2", "End", codes = emptyMap()))).legs.map { it.lineId })
        // Calling at the stop itself, it gets off there, never at another id of its station first.
        assertEquals("B", linesOf(mapOf("green" to green("A", "B2", "B", "End"))).legs[1].toId)
    }

    // Two rides with a change at B: red A → B, then blue B → C.
    private val redToB = leg("red", "A", "B").copy(departure = at, arrival = at.plusSeconds(120), changeAfter = java.time.Duration.ofMinutes(4))
    private val blueToC = leg("blue", "B", "C", path = listOf("X", "C")).copy(
        departure = at.plusSeconds(600),
        arrival = at.plusSeconds(1200),
        changeAfter = java.time.Duration.ofMinutes(1),
    )
    private val changing = TripRoute(listOf(redToB, blueToC))

    private fun through(sequences: Map<String, LineSequence?>, arrivals: Map<String, List<Departure>> = mapOf("A" to listOf(train("red"), train("blue"))), hidden: Set<String> = emptySet()) =
        RideLines.through(listOf(changing), planned, arrivals, emptyMap(), sequences, hidden)

    @Test
    fun `a line running on through the change is a route with one ride fewer`() {
        val routes = through(mapOf("blue" to sequence("A", "B", "X", "C", "End")))
        val ride = routes.single().legs.single()
        assertEquals("blue", ride.lineId)
        assertEquals("A" to "C", ride.fromId to ride.toId)
        assertEquals(listOf("B", "X", "C"), ride.path)
        // The two rides' time on board, without the change between them.
        assertEquals(at, ride.departure)
        assertEquals(at.plusSeconds(120 + 600), ride.arrival)
        assertEquals(java.time.Duration.ofMinutes(1), ride.changeAfter)
    }

    @Test
    fun `the first ride's own line can run through too`() {
        val routes = through(mapOf("red" to sequence("A", "B", "X", "C")))
        assertEquals(listOf("red"), routes.single().legs.map { it.lineId })
    }

    @Test
    fun `a line that stops short, runs the other way, is hidden, has no route, or needs a walk is no way through`() {
        assertTrue(through(mapOf("blue" to sequence("A", "B", "End"))).isEmpty())
        assertTrue(through(mapOf("blue" to sequence("C", "X", "B", "A"))).isEmpty())
        assertTrue(through(emptyMap()).isEmpty())
        val hidden = setOf(HiddenModes.lineKey("blue", "blue"))
        assertTrue(through(mapOf("blue" to sequence("A", "B", "X", "C")), hidden = hidden).isEmpty())
        // A walk between the rides changes station: the rides stay as they are.
        val walking = TripRoute(listOf(redToB, leg("", "B", "B2", mode = TripLeg.WALKING), blueToC.copy(fromId = "B2")))
        assertTrue(RideLines.through(listOf(walking), planned, mapOf("A" to listOf(train("blue"))), emptyMap(), mapOf("blue" to sequence("A", "B", "X", "C"))).isEmpty())
        // Another mode's line isn't a way through a tube change.
        val bus = mapOf("A" to listOf(train("green", mode = "bus")))
        assertTrue(through(mapOf("green" to sequence("A", "B", "X", "C")), arrivals = bus).isEmpty())
    }

    @Test
    fun `a way through the Planner already offers isn't added again`() {
        val direct = TripRoute(listOf(leg("blue", "A", "C", path = listOf("B", "X", "C"))))
        val routes = RideLines.through(listOf(changing, direct), planned, mapOf("A" to listOf(train("blue"))), emptyMap(), mapOf("blue" to sequence("A", "B", "X", "C")))
        assertTrue(routes.isEmpty())
    }

    @Test
    fun `a ride's lines are keyed on the ride and where it gets off`() {
        val sequences = mapOf("green" to sequence("A", "B", "End"))
        val lines = RideLines.of(listOf(route), planned, atA, emptyMap(), sequences)
        assertEquals(setOf(ended(ride)), lines.keys)
        // The same ride given another end is another key: never judged once for both.
        val hub = RideLines.of(listOf(route), byHub, atA, emptyMap(), sequences)
        assertFalse(ended(ride) in hub.keys)
    }

    @Test
    fun `a ride's lines and ways through are the same whatever end they're given`() {
        // Arriving by hub doesn't read the end yet: a hub end finds the lines and ways the Planner's call does.
        val sequences = mapOf("green" to sequence("A", "B", "End"), "blue" to sequence("A", "B", "X", "C", "End"))
        val board = mapOf("A" to listOf(train("red"), train("green"), train("blue")))
        val lines = RideLines.of(listOf(route, changing), planned, board, emptyMap(), sequences)
        val hub = RideLines.of(listOf(route, changing), byHub, board, emptyMap(), sequences)
        assertEquals(lines.map { (key, value) -> key.first to value }, hub.map { (key, value) -> key.first to value })
        assertEquals(setOf("red", "green", "blue"), lines.getValue(ended(ride)).legs.mapTo(HashSet()) { it.lineId })
        val arrivals = mapOf("A" to listOf(train("red"), train("blue")))
        val ways = RideLines.throughWays(listOf(changing), planned, arrivals, emptyMap(), sequences)
        assertEquals(1, ways.size)
        assertEquals(ways, RideLines.throughWays(listOf(changing), byHub, arrivals, emptyMap(), sequences))
    }

    // Arriving by hub at a destination of B and its sibling station Bh (synthetic ids), the last ride's end as
    // the trip page reads it ([RideEnds]): every id of the hub.
    private val atHub = LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBB"), ids = setOf("B", "Bh"))
    private val arrivingByHub = { route: TripRoute -> route.rides.mapIndexed { i, it -> it to if (i == route.rides.lastIndex) atHub else LegEnd.planned(it, StepFree.ANY) } }

    @Test
    fun `a line reaching another station of the destination's hub takes a last ride arriving by hub, cut there`() {
        val green = mapOf("green" to sequence("A", "Bh", "End"))
        // Getting off where the Planner does, at B only: green never calls there.
        assertEquals(listOf("red"), linesOf(green).legs.map { it.lineId })
        // Arriving by hub, green gets the rider there at Bh: shown as an equal, cut and named at Bh, but not
        // timing the ride, as it rides another stretch.
        val lines = RideLines.of(listOf(route), arrivingByHub, atA, emptyMap(), green).getValue(ride to atHub)
        assertEquals(listOf("red", "green"), lines.legs.map { it.lineId })
        assertEquals("Bh", lines.legs[1].toId)
        assertEquals("Bh", lines.legs[1].toName)
        assertEquals(listOf("Bh"), lines.legs[1].path)
        assertEquals(listOf("red"), lines.timed.map { it.lineId })
        // A line calling at Bh before B gets off at Bh, its first call in the hub.
        val first = RideLines.of(listOf(route), arrivingByHub, atA, emptyMap(), mapOf("green" to sequence("A", "Bh", "B"))).getValue(ride to atHub)
        assertEquals("Bh", first.legs[1].toId)
        // A middle ride still gets off at the Planner's stop alone.
        val middle = TripRoute(listOf(ride, leg("blue", "B", "C")))
        assertEquals(listOf("red"), RideLines.of(listOf(middle), arrivingByHub, atA, emptyMap(), green).getValue(ended(ride)).legs.map { it.lineId })
    }

    @Test
    fun `a line through a change to another station of the destination's hub is a way through, arriving by hub`() {
        // Red A → M, then blue M → B; green from A runs on through M to Bh.
        val toM = leg("red", "A", "M").copy(changeAfter = java.time.Duration.ofMinutes(4))
        val mToB = leg("blue", "M", "B", path = listOf("B"))
        val plan = TripRoute(listOf(toM, mToB))
        val arrivals = mapOf("A" to listOf(train("green")))
        val sequences = mapOf("green" to sequence("A", "M", "Bh"))
        assertTrue(RideLines.through(listOf(plan), planned, arrivals, emptyMap(), sequences).isEmpty())
        val joined = RideLines.through(listOf(plan), arrivingByHub, arrivals, emptyMap(), sequences).single().legs.single()
        assertEquals("green", joined.lineId)
        assertEquals("Bh", joined.toId)
    }

    @Test
    fun `arriving by hub, a pole of the Planner's area outside the hub isn't reached`() {
        // The Planner gets red off at B in area BG, which the model split: Bx, its other pole, is a stop
        // outside the destination's hub.
        val inArea = leg("red", "A", "B").copy(toArea = "BG")
        val plan = TripRoute(listOf(inArea))
        val green = mapOf(
            "green" to LineSequence(listOf(LineRoute("route", listOf("A", "Bx", "End"))), mapOf("Bx" to "Bx"), stopAreas = mapOf("Bx" to "BG")),
        )
        // Getting off where the Planner does, the area's other pole is still the same stop.
        val planned = RideLines.of(listOf(plan), planned, atA, emptyMap(), green).getValue(ended(inArea))
        assertEquals(listOf("red", "green"), planned.legs.map { it.lineId })
        // Arriving by hub, only the hub's own ids count.
        val byHub = RideLines.of(listOf(plan), arrivingByHub, atA, emptyMap(), green).getValue(inArea to atHub)
        assertEquals(listOf("red"), byHub.legs.map { it.lineId })
    }
}
