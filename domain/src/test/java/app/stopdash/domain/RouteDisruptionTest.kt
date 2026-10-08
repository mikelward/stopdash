package app.stopdash.domain

import app.stopdash.domain.RouteDisruption.Signal
import app.stopdash.domain.RouteDisruption.Tier
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A trip on the way meeting a disruption, on synthetic stops A–E and example lines. */
class RouteDisruptionTest {
    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val walk = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
    private val second = TripLeg("tube", "blue", "Blue", "D", "D", "E", "E", at(22), at(30), path = listOf("E"))
    private val trip = ActiveTrip(TripRoute(listOf(ride, walk, second)), "E", startedAt = t0)

    private val waiting = TripProgress.Waiting(ride, at(5))
    private val riding = TripProgress.Riding(ride, "B", 2, at(15), getOffSoon = false)

    private fun status(line: String, severity: Int, description: String) = LineStatus(line, severity, description, "$description on the line")
    private fun good(line: String) = LineStatus(line, LineStatus.GOOD_SERVICE, "Good Service")

    // This alert, TfL placing it on [sections] (each in the order its route runs).
    private fun LineStatus.shutting(vararg sections: List<String>) =
        copy(closures = listOf(PartClosure(severity, description, fullText, sections.toList())))

    private fun signals(
        trip: ActiveTrip = this.trip,
        progress: TripProgress? = waiting,
        statuses: Map<String, LineStatus> = mapOf("red" to good("red"), "blue" to good("blue")),
        directions: Map<Int, String> = emptyMap(),
        closures: Map<String, List<StopDisruption>> = emptyMap(),
        places: Map<String, RouteDisruption.StopPlace> = emptyMap(),
        dismissed: Set<DismissedAlert> = emptySet(),
        now: Instant = at(3),
    ) = RouteDisruption.signals(trip, progress, statuses, directions, closures, places, dismissed, now)

    @Test
    fun `the stops still to reach are the coming rides' ends and a walk's end no ride meets`() {
        fun ids(trip: ActiveTrip, progress: TripProgress?) = RouteDisruption.comingStops(trip, progress).map { it.index to it.value.id }
        // Waiting for the first train: where it boards and gets off, and where the second does. The
        // walk's ends are the rides' stops, so they aren't counted again.
        assertEquals(listOf(0 to "A", 0 to "C", 2 to "D", 2 to "E"), ids(trip, waiting))
        // On board: the stop it boarded at is behind the rider.
        assertEquals(listOf(0 to "C", 2 to "D", 2 to "E"), ids(trip, riding))
        // Walking to the change: only the second ride's ends.
        assertEquals(listOf(2 to "D", 2 to "E"), ids(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(20))))
        // A route ending on foot counts the stop it ends at.
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "F", "F", at(15), at(20))
        val endsOnFoot = ActiveTrip(TripRoute(listOf(ride, walkOn)), "F", startedAt = t0)
        assertEquals(listOf(0 to "A", 0 to "C", 1 to "F"), ids(endsOnFoot, waiting))
        assertEquals(emptyList<Pair<Int, String>>(), ids(trip, TripProgress.Arrived))
        // Another of the ride's lines followed, getting off at its own stop (a bus's other pole, say):
        // that's the stop checked, for the leg the rider is on (Codex, PR #451).
        val green = Departure("green", "Green", "outbound", "C2", null, at(5), "tube", vehicleId = "4")
        val following = OnTheWay.follow(trip, green, ride.copy(lineId = "green", lineName = "Green", toId = "C2", toName = "C2"))
        assertEquals(listOf(0 to "A", 0 to "C2", 2 to "D", 2 to "E"), ids(following, waiting))
        val closed = signals(following, closures = mapOf("C2" to listOf(StopDisruption("Station closed"))))
        assertEquals(listOf("C2"), closed.map { (it as Signal.Stop).stopId })
    }

    @Test
    fun `a coming line not running is high, severe delays or part of it closed is medium, minor delays nothing`() {
        val found = signals(statuses = mapOf("red" to status("red", 2, "Suspended"), "blue" to status("blue", 6, "Severe Delays")))
        assertEquals(listOf(Tier.HIGH, Tier.MEDIUM), found.map { it.tier })
        assertEquals(listOf("red", "blue"), found.map { (it as Signal.Line).lineId })
        assertEquals(Tier.MEDIUM, RouteDisruption.tierOf(3))
        assertEquals(Tier.MEDIUM, RouteDisruption.tierOf(5))
        assertNull(RouteDisruption.tierOf(9))
        assertNull(RouteDisruption.tierOf(7))
        assertEquals(emptyList<Signal>(), signals(statuses = mapOf("red" to status("red", 9, "Minor Delays"))))
    }

    @Test
    fun `a part closure TfL places on the leg's own stretch is high, elsewhere or unplaced medium`() {
        // [stops] is one section, in the order its route runs.
        fun tierWith(severity: Int, description: String, stops: List<String>) =
            signals(statuses = mapOf("red" to status("red", severity, description).let { if (stops.isEmpty()) it else it.shutting(stops) }, "blue" to good("blue"))).single().tier
        // The ride A–B–C runs through the section B to X: High.
        assertEquals(Tier.HIGH, tierWith(3, "Part Suspended", listOf("B", "C", "X")))
        // Boarding inside it, getting off past it: still through it.
        assertEquals(Tier.HIGH, tierWith(5, "Part Closure", listOf("Y", "A", "B")))
        assertEquals(Tier.HIGH, tierWith(11, "Part Closed", listOf("A", "B")))
        // Only meeting its edge (getting off where it starts, boarding where it ends): trains still run there.
        assertEquals(Tier.MEDIUM, tierWith(3, "Part Suspended", listOf("C", "X", "Y")))
        assertEquals(Tier.MEDIUM, tierWith(3, "Part Suspended", listOf("Y", "A")))
        // Two of its stops that aren't calls in a row: the ride doesn't run between them.
        assertEquals(Tier.MEDIUM, tierWith(3, "Part Suspended", listOf("A", "C")))
        // Elsewhere on the line, or not known where: Medium, as before.
        assertEquals(Tier.MEDIUM, tierWith(3, "Part Suspended", listOf("X", "Y")))
        assertEquals(Tier.MEDIUM, tierWith(3, "Part Suspended", emptyList()))
        // Severe delays alone, no closure placed, stay Medium.
        assertEquals(Tier.MEDIUM, tierWith(6, "Severe Delays", emptyList()))
        // Two sections apart, A–X and B–Y: the ride runs A to B between them, through neither.
        val apart = status("red", 3, "Part Suspended").shutting(listOf("X", "A"), listOf("B", "Y"))
        assertEquals(Tier.MEDIUM, signals(statuses = mapOf("red" to apart, "blue" to good("blue"))).single().tier)
        // The same stations shut the other way (C to A): the ride runs A to C, which still runs.
        assertEquals(Tier.MEDIUM, tierWith(3, "Part Suspended", listOf("X", "C", "B", "A")))
    }

    @Test
    fun `a ride's last stretch is placed even when its path leaves out where it gets off`() {
        // A to C, its path naming B alone: the stretch B–C is still the ride's.
        val shortPath = ride.copy(path = listOf("B"))
        val trip = ActiveTrip(TripRoute(listOf(shortPath, walk, second)), "E", startedAt = t0)
        val red = status("red", 3, "Part Suspended").shutting(listOf("B", "C", "X"))
        assertEquals(listOf("A", "B", "C"), RouteDisruption.rideCalls(shortPath))
        assertEquals(listOf("A", "B", "C"), RouteDisruption.rideCalls(ride))
        // On a loop, A–B–C–B, its path naming B early on and leaving out the end: still added.
        assertEquals(listOf("A", "B", "C", "B"), RouteDisruption.rideCalls(ride.copy(toId = "B", path = listOf("B", "C"))))
        assertEquals(Tier.HIGH, signals(trip, TripProgress.Waiting(shortPath, at(5)), mapOf("red" to red, "blue" to good("blue"))).single().tier)
    }

    @Test
    fun `a ride the Planner gave no path for isn't placed`() {
        // Its two ends alone don't say which way it runs between them: a section TfL shuts from A to C
        // on one branch says nothing of a ride A to C by the other (Codex, PR #446).
        val pathless = ride.copy(path = emptyList())
        val trip = ActiveTrip(TripRoute(listOf(pathless, walk, second)), "E", startedAt = t0)
        val red = status("red", 3, "Part Suspended").shutting(listOf("A", "X", "C"))
        assertEquals(emptyList<String>(), RouteDisruption.rideCalls(pathless))
        assertEquals(Tier.MEDIUM, signals(trip, TripProgress.Waiting(pathless, at(5)), mapOf("red" to red, "blue" to good("blue"))).single().tier)
    }

    @Test
    fun `a part closure is placed by the direction's own stops, and on the ride being ridden too`() {
        // Inbound shuts B to C; outbound shuts elsewhere. The leg's trains are seen going inbound.
        val inbound = status("red", 3, "Part Suspended").shutting(listOf("B", "C"))
        val outbound = status("red", 3, "Part Suspended").copy(fullText = "Outbound only").shutting(listOf("X", "Y"))
        val split = inbound.copy(byDirection = mapOf("inbound" to inbound, "outbound" to outbound))
        val statuses = mapOf("red" to split, "blue" to good("blue"))
        assertEquals(Tier.HIGH, signals(statuses = statuses, directions = mapOf(0 to "inbound")).single().tier)
        assertEquals(Tier.MEDIUM, signals(statuses = statuses, directions = mapOf(0 to "outbound")).single().tier)
        assertEquals(Tier.HIGH, signals(progress = riding, statuses = statuses, directions = mapOf(0 to "inbound")).single().tier)
        // The second ride (D–E) is placed by its own calls.
        val blue = mapOf("red" to good("red"), "blue" to status("blue", 5, "Part Closure").shutting(listOf("D", "E")))
        assertEquals(Tier.HIGH, signals(statuses = blue).single().tier)
    }

    @Test
    fun `a ride followed on another of its lines is checked as that line`() {
        // A green train taken on the red ride (the Circle along the Hammersmith & City, say): the green
        // line's alert is the ride's, named as it; the red line's says nothing of the train taken (Codex, PR #451).
        val green = Departure("green", "Green", "outbound", "C", null, at(5), "tube", vehicleId = "4")
        val following = OnTheWay.follow(trip, green, ride.copy(lineId = "green", lineName = "Green"))
        assertEquals(listOf("green", "blue"), RouteDisruption.comingLines(following))
        val statuses = mapOf("red" to status("red", 2, "Suspended"), "green" to status("green", 6, "Severe Delays"), "blue" to good("blue"))
        val signal = signals(following, statuses = statuses).single() as Signal.Line
        assertEquals(listOf("green", "Green", "Severe Delays"), listOf(signal.lineId, signal.lineName, signal.status.description))
        // With no train followed, or once the leg is done with, the Planner's line again.
        assertEquals(listOf("red", "blue"), RouteDisruption.comingLines(trip))
        assertEquals("red", (signals(statuses = statuses).first() as Signal.Line).lineId)
        // A part closure is placed on that line's own stretch: green runs A by X to C, and a section
        // shut A–X is its ride's, where the red line's ride by B wouldn't run through it.
        val byX = OnTheWay.follow(trip, green, ride.copy(lineId = "green", lineName = "Green", path = listOf("X", "C")))
        val shut = mapOf("green" to status("green", 3, "Part Suspended").shutting(listOf("A", "X")), "blue" to good("blue"))
        val placed = signals(byX, statuses = shut).single() as Signal.Line
        assertEquals(listOf("green", Tier.HIGH), listOf(placed.lineId, placed.tier))
        assertEquals(Tier.MEDIUM, (signals(following, statuses = shut).single() as Signal.Line).tier)
    }

    @Test
    fun `the line being ridden counts, a line already done with doesn't`() {
        val statuses = mapOf("red" to status("red", 6, "Severe Delays"), "blue" to good("blue"))
        assertEquals(listOf("red"), signals(progress = riding, statuses = statuses).map { (it as Signal.Line).lineId })
        assertEquals(emptyList<Signal>(), signals(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(20)), statuses))
        assertEquals(emptyList<Signal>(), signals(progress = TripProgress.Arrived, statuses = statuses))
    }

    @Test
    fun `a line not checked is unknown, never a signal`() {
        assertEquals(emptyList<Signal>(), signals(statuses = emptyMap()))
    }

    @Test
    fun `planned work counts from its day, as the trip shows it`() {
        val planned = good("red").copy(planned = listOf(PlannedAlert("Part Closure", "No trains A to C", LocalDate.parse("2026-09-27"), severity = 5)))
        assertEquals(emptyList<Signal>(), signals(statuses = mapOf("red" to planned)))
        val dayCome = signals(statuses = mapOf("red" to planned), now = Instant.parse("2026-09-27T08:00:00Z"))
        assertEquals(listOf(Tier.MEDIUM), dayCome.map { it.tier })
    }

    @Test
    fun `an alert scoped to the other direction from the leg's trains isn't the leg's`() {
        val inbound = status("red", 2, "Suspended")
        val split = inbound.copy(byDirection = mapOf("inbound" to inbound, "outbound" to good("red")))
        assertEquals(emptyList<Signal>(), signals(statuses = mapOf("red" to split), directions = mapOf(0 to "outbound")))
        assertEquals(1, signals(statuses = mapOf("red" to split), directions = mapOf(0 to "inbound")).size)
        // With no direction known it counts both ways.
        assertEquals(1, signals(statuses = mapOf("red" to split)).size)
    }

    @Test
    fun `an alert the rider dismissed isn't a signal, and comes back once it escalates`() {
        val part = status("red", 3, "Part Suspended")
        val dismissed = setOf(DismissedAlert.ofLineStatus(part))
        assertEquals(emptyList<Signal>(), signals(statuses = mapOf("red" to part), dismissed = dismissed))
        val worse = signals(statuses = mapOf("red" to status("red", 2, "Suspended")), dismissed = dismissed)
        assertEquals(listOf(Tier.HIGH), worse.map { it.tier })
    }

    @Test
    fun `a signal's key is its leg and what's known, so an escalation is heard again`() {
        val part = signals(statuses = mapOf("red" to status("red", 3, "Part Suspended"))).single()
        val worse = signals(statuses = mapOf("red" to status("red", 2, "Suspended"))).single()
        assertNotEquals(part.key, worse.key)
        assertEquals(part.key, signals(statuses = mapOf("red" to status("red", 3, "Part Suspended")), now = at(4)).single().key)
    }

    @Test
    fun `a part closure placed once its detail lands is heard again, but a dismissal still holds`() {
        // The first check finds it unplaced (its sections not looked up yet); the next places it on the
        // ride A–B–C: an escalation to High, with a key of its own, so it's heard.
        val unplaced = status("red", 3, "Part Suspended")
        val placed = unplaced.shutting(listOf("A", "B", "C"))
        val before = signals(statuses = mapOf("red" to unplaced, "blue" to good("blue"))).single()
        val after = signals(statuses = mapOf("red" to placed, "blue" to good("blue"))).single()
        assertEquals(Tier.MEDIUM, before.tier)
        assertEquals(Tier.HIGH, after.tier)
        assertNotEquals(before.key, after.key)
        // The rider dismissed this very alert on the trip (its text names the stretch already): placing
        // it doesn't bring it back, so the alert and the screen still agree.
        val dismissed = setOf(DismissedAlert.ofLineStatus(unplaced))
        assertEquals(emptyList<Signal>(), signals(statuses = mapOf("red" to placed, "blue" to good("blue")), dismissed = dismissed))
    }

    @Test
    fun `a part closure placed on the ride is the signal, named, behind any alert shown above it`() {
        val closure = PartClosure(11, "Part Closed", "No trains B to C", listOf(listOf("A", "B", "C")))
        fun shown(severity: Int, description: String, vararg closures: PartClosure) =
            signals(statuses = mapOf("red" to status("red", severity, description).copy(closures = closures.toList()), "blue" to good("blue")))
        // Minor delays shown, TfL ranking them above a part closure on the ride's stretch: the closure
        // is still heard, High, and named for what it is (Codex, PR #446).
        val behindMinor = shown(9, "Minor Delays", closure).single() as Signal.Line
        assertEquals(Tier.HIGH, behindMinor.tier)
        assertTrue(behindMinor.placed)
        assertEquals("Part Closed", behindMinor.status.description)
        assertEquals("No trains B to C", behindMinor.status.fullText)
        // Likewise behind severe delays, which alone are only Medium.
        assertEquals("Part Closed", (shown(6, "Severe Delays", closure).single() as Signal.Line).status.description)
        // Elsewhere on the line, or not placed yet, it's Medium as any part closure is, even behind
        // minor delays, which never alert: it stands in for them, named for itself (Codex, PR #446).
        val elsewhere = closure.copy(sections = listOf(listOf("X", "Y")))
        for (unplaced in listOf(elsewhere, closure.copy(sections = emptyList()))) {
            val behind = shown(9, "Minor Delays", unplaced).single() as Signal.Line
            assertEquals(listOf(Tier.MEDIUM, false, "Part Closed"), listOf(behind.tier, behind.placed, behind.status.description))
        }
        // Behind severe delays, Medium already, the delays are what's said.
        assertEquals("Severe Delays", (shown(6, "Severe Delays", elsewhere).single() as Signal.Line).status.description)
        // Minor delays alone still never alert.
        assertEquals(emptyList<Signal>(), shown(9, "Minor Delays"))
        // The rider dismissed the delays shown, not the closure, which they were never shown: it still
        // comes. Dismissed itself, it doesn't.
        val minor = status("red", 9, "Minor Delays").copy(closures = listOf(closure))
        fun dismissing(vararg alerts: DismissedAlert) =
            signals(statuses = mapOf("red" to minor, "blue" to good("blue")), dismissed = alerts.toSet())
        assertEquals(listOf("Part Closed"), dismissing(DismissedAlert.ofLineStatus(minor)).map { (it as Signal.Line).status.description })
        assertEquals(emptyList<Signal>(), dismissing(DismissedAlert.ofLineStatus(minor.naming(closure))))
    }

    @Test
    fun `the line's next alert stands in for one let go of with Keep going`() {
        // A part closure placed on the ride, picked ahead of severe delays: Keep going lets go of the
        // closure, and the delays still come, rather than the line going quiet for the trip (Codex on #519).
        val closure = PartClosure(11, "Part Closed", "No trains B to C", listOf(listOf("A", "B", "C")))
        val severe = status("red", 6, "Severe Delays").copy(closures = listOf(closure))
        val statuses = mapOf("red" to severe, "blue" to good("blue"))
        val picked = signals(statuses = statuses).single() as Signal.Line
        assertEquals("Part Closed", picked.status.description)
        val keptGoing = trip.copy(disruptionsDismissed = setOf(picked.dismissKey))
        val next = signals(trip = keptGoing, statuses = statuses).single() as Signal.Line
        assertEquals("Severe Delays", next.status.description)
        // Let go of too, nothing's left.
        assertEquals(emptyList<Signal>(), signals(trip = keptGoing.copy(disruptionsDismissed = setOf(picked.dismissKey, next.dismissKey)), statuses = statuses))
        // Only on its own leg: the same alert let go of on another leg doesn't count here.
        val elsewhere = trip.copy(disruptionsDismissed = setOf(picked.copy(legIndex = 2).dismissKey))
        assertEquals("Part Closed", (signals(trip = elsewhere, statuses = statuses).single() as Signal.Line).status.description)
    }

    @Test
    fun `the line's next alert under way stands in for one let go of with Keep going`() {
        // A suspension shown ahead of severe delays, both under way: Keep going lets go of the suspension,
        // and the delays come in its place (Codex on #519).
        val suspended = status("red", 4, "Suspended")
        val statuses = mapOf(
            "red" to suspended.copy(underWay = listOf(LineAlert(4, "Suspended", "Suspended on the line"), LineAlert(6, "Severe Delays", "Severe Delays on the line"))),
            "blue" to good("blue"),
        )
        val picked = signals(statuses = statuses).single() as Signal.Line
        assertEquals("Suspended", picked.status.description)
        val keptGoing = trip.copy(disruptionsDismissed = setOf(picked.dismissKey))
        val next = signals(trip = keptGoing, statuses = statuses).single() as Signal.Line
        assertEquals(listOf("Severe Delays", Tier.MEDIUM), listOf(next.status.description, next.tier))
        // Let go of too, nothing's left; likewise dismissed on the list.
        assertEquals(emptyList<Signal>(), signals(trip = keptGoing.copy(disruptionsDismissed = setOf(picked.dismissKey, next.dismissKey)), statuses = statuses))
        assertEquals(emptyList<Signal>(), signals(statuses = statuses, dismissed = setOf(DismissedAlert.ofLineStatus(picked.status), DismissedAlert.ofLineStatus(next.status))))
    }

    @Test
    fun `an alert already high isn't heard again for a closure placed on the ride`() {
        val suspended = status("red", 2, "Suspended")
        val closure = PartClosure(5, "Part Closure", "No trains B to C", listOf(listOf("A", "B", "C")))
        val before = signals(statuses = mapOf("red" to suspended, "blue" to good("blue"))).single() as Signal.Line
        val after = signals(statuses = mapOf("red" to suspended.copy(closures = listOf(closure)), "blue" to good("blue"))).single() as Signal.Line
        // Its detail landing places a closure on the ride, but the line was High already: the same alert,
        // the same key, not heard again (Codex, PR #446).
        assertEquals(Tier.HIGH, after.tier)
        assertFalse(after.placed)
        assertEquals("Suspended", after.status.description)
        assertEquals(before.key, after.key)
    }

    @Test
    fun `a stop still to reach that's closed or moved is high, other notices aren't signals`() {
        val closures = mapOf(
            "C" to listOf(StopDisruption("Station closed due to strike action")),
            "E" to listOf(StopDisruption("Stop B moved to Example Road")),
            "D" to listOf(StopDisruption("Lift out of order")),
        )
        val found = signals(closures = closures)
        assertEquals(
            listOf(Triple(0, "C", true), Triple(2, "E", false)),
            found.map { it as Signal.Stop }.map { Triple(it.legIndex, it.stopId, it.closed) },
        )
        assertTrue(found.all { it.tier == Tier.HIGH })
    }

    @Test
    fun `a coming station's notice that neither closes nor moves it is a note, never a signal`() {
        val closures = mapOf(
            "C" to listOf(StopDisruption("Station closed due to strike action")),
            "E" to listOf(StopDisruption("Stop B moved to Example Road")),
            "D" to listOf(StopDisruption("Lift out of order")),
        )
        fun notes(progress: TripProgress? = waiting, dismissed: Set<DismissedAlert> = emptySet(), trip: ActiveTrip = this.trip) =
            RouteDisruption.stationNotes(trip, progress, closures, emptyMap(), dismissed, at(3))
        // Only the lift: the closure and the move are signals of their own (maintainer, 2026-10-04).
        assertEquals(listOf(Triple(2, "D", "Lift out of order")), notes().map { Triple(it.legIndex, it.stopId, it.text) })
        // Dismissed on the trip's list, it's let go of here too.
        val dismissed = RouteDisruption.closureCards(trip, waiting, mapOf("D" to closures.getValue("D")), emptyMap(), at(3))
            .map { DismissedAlert.ofStopClosure(it) }.toSet()
        assertEquals(emptyList<RouteDisruption.StationNote>(), notes(dismissed = dismissed))
        // Arrived, nothing is ahead.
        assertEquals(emptyList<RouteDisruption.StationNote>(), notes(progress = TripProgress.Arrived))
        // A station behind the rider is no note: on board, the one boarded at is behind.
        val boarding = mapOf("A" to listOf(StopDisruption("Lift out of order")))
        assertEquals(1, RouteDisruption.stationNotes(trip, waiting, boarding, emptyMap(), emptySet(), at(3)).size)
        assertEquals(0, RouteDisruption.stationNotes(trip, riding, boarding, emptyMap(), emptySet(), at(3)).size)
        // A lift notice beside a closure at the same station is still noted; the closure alerts (Codex, #567).
        val both = mapOf("C" to listOf(StopDisruption("Station closed due to strike action"), StopDisruption("Lift out of order")))
        assertEquals(listOf("Lift out of order"), RouteDisruption.stationNotes(trip, waiting, both, emptyMap(), emptySet(), at(3)).map { it.text })
        // Listed under two overlapping windows beside a closure: said once, and held until the later
        // one ends, not the earlier (Codex, #567).
        val windows = mapOf(
            "C" to listOf(
                StopDisruption("Station closed due to strike action"),
                StopDisruption("Lift out of order", at(0), at(10)),
                StopDisruption("Lift out of order", at(5), at(40)),
            ),
        )
        assertEquals(
            listOf("Lift out of order" to at(40)),
            RouteDisruption.stationNotes(trip, waiting, windows, emptyMap(), emptySet(), at(6)).map { it.text to it.until },
        )
        // An interchange's notice, listed by its stops under different windows, stands while either does
        // (Codex, #567).
        val hubWide = RouteDisruption.StopPlace(hub = "HUBX", hubName = "Example Hub")
        val members = mapOf(
            "C" to listOf(StopDisruption("Lift out of order", at(0), at(10))),
            "D" to listOf(StopDisruption("Lift out of order", at(5), at(40))),
        )
        val hubNote = RouteDisruption.stationNotes(trip, waiting, members, mapOf("C" to hubWide, "D" to hubWide), emptySet(), at(6))
        assertEquals(listOf("Lift out of order" to at(40)), hubNote.map { it.text to it.until })
        // And on both stops' checks, so it stands while either is current (Codex, #567).
        // Each stop with its own listing's end, so a check is never paired with another stop's window (Codex, #567).
        assertEquals(listOf(listOf(mapOf("C" to at(10), "D" to at(40)))), hubNote.map { it.support })
        // A notice only one of them lists stands on that one's check alone (Codex, #567).
        val partly = mapOf(
            "C" to listOf(StopDisruption("Lift out of order"), StopDisruption("Escalator out of order")),
            "D" to listOf(StopDisruption("Lift out of order")),
        )
        assertEquals(
            listOf(listOf(setOf("C", "D"), setOf("C"))),
            RouteDisruption.stationNotes(trip, waiting, partly, mapOf("C" to hubWide, "D" to hubWide), emptySet(), at(6)).map { note -> note.support.map { it.keys } },
        )
        // One outage each stop leads with its own member name for is said once (Codex, #567).
        val kgx = RouteDisruption.StopPlace(
            hub = "HUBKGX", hubName = "King's Cross & St Pancras International",
            aliases = listOf("King's Cross St. Pancras", "St Pancras International"),
        )
        val prefixed = mapOf(
            "C" to listOf(StopDisruption("King's Cross St. Pancras: Lift out of order")),
            "D" to listOf(StopDisruption("St Pancras International: Lift out of order")),
        )
        assertEquals(
            listOf(Triple("King's Cross & St Pancras International", "Lift out of order", listOf(setOf("C", "D")))),
            RouteDisruption.stationNotes(trip, waiting, prefixed, mapOf("C" to kgx, "D" to kgx), emptySet(), at(3))
                .map { note -> Triple(note.stopName, note.text, note.support.map { it.keys }) },
        )
        // Its dismissal is the same notice before the interchange's names, or the interchange itself, are
        // known, though they clean its words and fold its stops differently once in (Codex, #609).
        val named = RouteDisruption.stationNotes(trip, waiting, prefixed, mapOf("C" to kgx, "D" to kgx), emptySet(), at(3))
        val unnamedHub = RouteDisruption.StopPlace(hub = "HUBKGX")
        val unnamed = RouteDisruption.stationNotes(trip, waiting, prefixed, mapOf("C" to unnamedHub, "D" to unnamedHub), emptySet(), at(3))
        assertEquals(named.map { it.dismissKeys }, unnamed.map { it.dismissKeys })
        val unfolded = RouteDisruption.stationNotes(trip, waiting, prefixed, emptyMap(), emptySet(), at(3))
        assertEquals(2, unfolded.size)
        // Each stop's note let go of before the index warmed: the interchange's note after is too.
        assertTrue(named.single().dismissedIn(unfolded.flatMapTo(HashSet()) { it.dismissKeys }))
        // One of them only: the interchange's still has a notice not let go of.
        assertFalse(named.single().dismissedIn(unfolded.first().dismissKeys))
        // And the interchange's let go of: each stop's alone is too.
        assertTrue(unfolded.all { it.dismissedIn(named.single().dismissKeys) })
        // Two places of one name each keep their own note (Codex, #567).
        val sameName = ActiveTrip(
            TripRoute(listOf(TripLeg("tube", "red", "Red", "A", "A", "C", "High Street", at(5), at(15), path = listOf("C")), walk.copy(fromName = "High Street"), second.copy(fromName = "High Street"))),
            "E", startedAt = t0,
        )
        val lifts = mapOf("C" to listOf(StopDisruption("Lift out of order")), "D" to listOf(StopDisruption("Lift out of order")))
        val apart = RouteDisruption.stationNotes(sameName, waiting, lifts, emptyMap(), emptySet(), at(3))
        assertEquals(listOf("C", "D"), apart.map { it.stopId })
        // And each is dismissed on its own (Codex, #609).
        assertFalse(apart[1].dismissedIn(apart[0].dismissKeys))
        // Said once at an interchange, though only one of its stops is also closed (Codex, #567).
        val hub = RouteDisruption.StopPlace(hub = "HUBX", hubName = "Example Hub")
        val shared = both + ("D" to listOf(StopDisruption("Lift out of order")))
        assertEquals(
            listOf("Example Hub" to "Lift out of order"),
            RouteDisruption.stationNotes(trip, waiting, shared, mapOf("C" to hub, "D" to hub), emptySet(), at(3)).map { it.stopName to it.text },
        )
    }

    @Test
    fun `the boarding stop counts until the rider boards`() {
        val closures = mapOf("A" to listOf(StopDisruption("Station closed")))
        assertEquals(listOf("A"), signals(closures = closures).map { (it as Signal.Stop).stopId })
        assertEquals(emptyList<Signal>(), signals(progress = riding, closures = closures))
        // A boarded train that can't be placed any more has still left the stop behind (Codex, PR #441).
        val lost = TripProgress.Lost(ride)
        assertEquals(emptyList<Signal>(), signals(trip.copy(boarded = true), lost, closures = closures))
        assertEquals(emptyList<Signal>(), signals(trip.copy(onBoardSeen = true), waiting, closures = closures))
        assertEquals(listOf("A"), signals(progress = lost, closures = closures).map { (it as Signal.Stop).stopId })
    }

    @Test
    fun `a reworded alert is a new signal, as it would show again once dismissed`() {
        // A line's reason, as its dismissal identity has it (Codex, PR #441).
        val severe = LineStatus("red", 6, "Severe Delays", "A signal failure")
        val reworded = severe.copy(fullText = "A broken-down train")
        val first = signals(statuses = mapOf("red" to severe)).single()
        assertNotEquals(first.key, signals(statuses = mapOf("red" to reworded)).single().key)
        // A stop's notice, its text and its window.
        val closed = signals(closures = mapOf("C" to listOf(StopDisruption("Station closed")))).single()
        val reason = signals(closures = mapOf("C" to listOf(StopDisruption("Station closed due to flooding")))).single()
        val dated = signals(closures = mapOf("C" to listOf(StopDisruption("Station closed", validTo = at(90))))).single()
        assertEquals(3, setOf(closed.key, reason.key, dated.key).size)
    }

    @Test
    fun `a stop notice counts only in its window`() {
        val later = StopDisruption("Station closed", validFrom = at(60))
        assertEquals(emptyList<Signal>(), signals(closures = mapOf("C" to listOf(later))))
        assertEquals(1, signals(closures = mapOf("C" to listOf(later)), now = at(61)).size)
    }

    @Test
    fun `a stop notice dismissed on the trip's closure card isn't a signal`() {
        val notices = listOf(StopDisruption("Station closed"))
        val place = RouteDisruption.StopPlace(area = "940GZZEXC", hub = "HUBEXC")
        val stop = StopArrivals("C", "C", emptyList(), at(3), disruptions = notices, clusterId = place.area, hubId = place.hub)
        val card = DepartureRows.across(listOf(stop), at(3)).single { it.stopDisruption != null }
        val dismissed = setOf(DismissedAlert.ofStopClosure(card))
        assertEquals(emptyList<Signal>(), signals(closures = mapOf("C" to notices), places = mapOf("C" to place), dismissed = dismissed))
        // Matched where the card places it: unplaced, it's another identity.
        assertEquals(1, signals(closures = mapOf("C" to notices), dismissed = dismissed).size)
    }

    @Test
    fun `worst first, then in route order`() {
        val found = signals(
            statuses = mapOf("red" to status("red", 6, "Severe Delays"), "blue" to good("blue")),
            closures = mapOf("E" to listOf(StopDisruption("Station closed"))),
        )
        assertEquals(listOf(Tier.HIGH, Tier.MEDIUM), found.map { it.tier })
        assertTrue(found.first() is Signal.Stop)
    }

    @Test
    fun `a stop's card carries all its notices, judged together, dismissed together`() {
        // A closure and a lift notice at one stop are one card, as the trip shows them (Codex, PR #441).
        val notices = listOf(StopDisruption("Station closed"), StopDisruption("Lift out of order"))
        val cards = RouteDisruption.closureCards(trip, waiting, mapOf("C" to notices), emptyMap(), at(3))
        assertEquals(1, cards.size)
        assertEquals(1, signals(closures = mapOf("C" to notices)).size)
        val dismissed = setOf(DismissedAlert.ofStopClosure(cards.single()))
        assertEquals(emptyList<Signal>(), signals(closures = mapOf("C" to notices), dismissed = dismissed))
    }

    @Test
    fun `a moved stop is read from the notice's own words`() {
        assertTrue(MovedNotice.saysMoved("Stop moved"))
        assertTrue(MovedNotice.saysMoved("Stop B moved to Example Road"))
        assertTrue(MovedNotice.saysMoved("This stop has been temporarily relocated"))
        assertFalse(MovedNotice.saysMoved("Lift moved out of service"))
        assertFalse(MovedNotice.saysMoved("Buses diverted"))
    }

    @Test
    fun `a change is near once the rider can board its ride within a few minutes`() {
        fun near(trip: ActiveTrip, progress: TripProgress?, now: Instant) = RouteDisruption.changeNear(trip, progress, now)?.let { it.index to it.value.lineId }
        // On the first ride, due off at C at 15, then a five-minute walk to D: they can board the
        // second at 20, near from 15, not before.
        assertNull(near(trip, riding, at(14)))
        assertEquals(2 to "blue", near(trip, riding, at(15)))
        // Due off past TfL's predictions: when they can board isn't known.
        assertNull(near(trip, riding.copy(getOffAt = null), at(15)))
        // Walking to it, until 20.
        val walking = trip.copy(legIndex = 1, legStartedAt = at(15))
        assertNull(near(walking, TripProgress.Walking(walk, at(20)), at(14)))
        assertEquals(2 to "blue", near(walking, TripProgress.Walking(walk, at(20)), at(16)))
        // Waiting there, until they're on its train.
        val there = trip.copy(legIndex = 2, legStartedAt = at(20))
        assertEquals(2 to "blue", near(there, TripProgress.Waiting(second, null), at(21)))
        assertEquals(2 to "blue", near(there, TripProgress.Lost(second), at(21)))
        assertNull(near(there.copy(boarded = true), TripProgress.Lost(second), at(21)))
        assertNull(near(there.copy(onBoardSeen = true), TripProgress.Riding(second, "E", 1, at(30), false), at(21)))
        // The first ride isn't boarded at a change, nor one walked to before any ride.
        assertNull(near(trip, waiting, at(5)))
        val walkIn = TripLeg(TripLeg.WALKING, "", "", "S", "S", "A", "A", t0, at(5))
        assertNull(near(ActiveTrip(TripRoute(listOf(walkIn, ride)), "C", startedAt = t0), TripProgress.Walking(walkIn, at(5)), at(3)))
        // A ride straight after another: due off, then the change time the Planner allows.
        val straight = ride.copy(changeAfter = Duration.ofMinutes(3))
        val onward = second.copy(fromId = "C", fromName = "C")
        val changing = ActiveTrip(TripRoute(listOf(straight, onward)), "E", startedAt = t0)
        assertNull(near(changing, riding.copy(leg = straight), at(12)))
        assertEquals(1 to "blue", near(changing, riding.copy(leg = straight), at(13)))
        assertEquals(emptyList<Pair<Int, String>>(), listOfNotNull(near(trip, TripProgress.Arrived, at(15))))
    }

    @Test
    fun `no train of the lines that may take the ride is predicted at a change`() {
        fun due(line: String, destination: String) = Departure(line, line, "outbound", destination, null, at(21), "tube", vehicleId = "v-$line-$destination")
        val blue = LineSequence(
            listOf(LineRoute("D-E", listOf("D", "E")), LineRoute("D-Z", listOf("D", "Z"))),
            mapOf("D" to "D", "E" to "E", "Z" to "Z"),
        )
        // The red line here runs D to Y only.
        val red = LineSequence(listOf(LineRoute("D-Y", listOf("D", "Y"))), mapOf("D" to "D", "Y" to "Y"))
        val routes = mapOf("blue" to blue, "red" to red)
        // The lines the trip would follow on the ride ([RideLines.running]): the Planner's, and red,
        // which the cards offer from D to E.
        val lines = listOf(second, second.copy(lineId = "red", lineName = "Red"))
        // A blue train to E: predicted. None at all, or only a red one its route doesn't take to E: not.
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("blue", "E")), lines, emptyMap()))
        val none = RouteDisruption.unpredicted(2, second, listOf(due("red", "Y")), lines, routes)
        assertEquals(Signal.Unpredicted(2, "blue", "Blue", "D", "D"), none)
        assertEquals(Tier.MEDIUM, none?.tier)
        assertEquals(none, RouteDisruption.unpredicted(2, second, emptyList(), lines, routes))
        // Another of the ride's lines whose route takes it to E is a train predicted for the ride
        // (Codex, PR #451), as is one whose route can't be had.
        val redToE = LineSequence(listOf(LineRoute("D-E", listOf("D", "E"))), mapOf("D" to "D", "E" to "E"))
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("red", "E")), lines, routes + ("red" to redToE)))
        // Not one the trip wouldn't follow: a line the cards don't offer (not running, avoided) isn't
        // counted, whatever its route (Codex, PR #451).
        assertEquals(none, RouteDisruption.unpredicted(2, second, listOf(due("red", "E")), listOf(second), routes + ("red" to redToE)))
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("red", "E")), lines, mapOf("blue" to blue)))
        // A bus at the same stop never is.
        assertEquals(none, RouteDisruption.unpredicted(2, second, listOf(due("10", "E").copy(mode = "bus")), lines, routes))
        // Only blue trains the other way, to Z: by its route, none takes the rider to E.
        assertEquals(none, RouteDisruption.unpredicted(2, second, listOf(due("blue", "Z")), lines, routes))
        // Without the route, or with one that can't place it, it may be the rider's: not a signal.
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("blue", "Z")), lines, emptyMap()))
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("blue", "")), lines, routes))
        // A train TfL names no line for may be the rider's (Codex, PR #443), unless it's another mode's.
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("", "")), lines, routes))
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("", "E").copy(mode = "")), lines, routes))
        assertEquals(none, RouteDisruption.unpredicted(2, second, listOf(due("", "E").copy(mode = "bus")), lines, routes))
        // A ride with no line named can't be told on any board.
        assertNull(RouteDisruption.unpredicted(2, second.copy(lineId = ""), emptyList(), listOf(second.copy(lineId = "")), emptyMap()))
    }
    @Test
    fun `each thing known is one card, however many legs it's on`() {
        val status = status("red", 2, "Suspended")
        val line = Signal.Line(0, "red", "Red", status, Tier.HIGH)
        val other = Signal.Line(0, "red", "Red", status.copy(fullText = "Another alert."), Tier.HIGH)
        assertEquals(listOf<Signal>(line, other), RouteDisruption.cards(listOf(line, line.copy(legIndex = 2), other)))
        // Same words, another severity: another alert, its own card, as Keep going lets each go (Codex on #519).
        val worse = Signal.Line(0, "red", "Red", status.copy(severity = 1), Tier.HIGH)
        assertEquals(listOf<Signal>(line, worse), RouteDisruption.cards(listOf(line, worse)))
    }

    @Test
    fun `a signal added to what's known is said in order and stands no longer than either`() {
        val line = Signal.Line(0, "red", "Red", status("red", 2, "Suspended"), Tier.HIGH)
        val none = Signal.Unpredicted(2, "blue", "Blue", "D", "D")
        assertEquals(RouteDisruption.Found(listOf(none), at(4), mapOf(none.key to at(4))), RouteDisruption.Found.NONE.with(none, at(4)))
        val both = RouteDisruption.Found(listOf(line), at(6)).with(none, at(4))
        assertEquals(listOf<Signal>(line, none), both.signals)
        assertEquals(at(4), both.until)
        // Each stands as long as its own: the added one by its time, the rest by the whole's (Codex on #519).
        assertEquals(at(4), both.standsUntil(none))
        assertEquals(at(4), both.standsUntil(line))
        assertEquals(at(6), RouteDisruption.Found(listOf(line), at(6)).with(none, at(9)).standsUntil(line))
        assertEquals(at(6), RouteDisruption.Found(listOf(line), at(6)).with(none, at(9)).until)
        // Heard once for the leg, whatever the board lists between.
        assertEquals(none.key, none.copy().key)
        assertNotEquals(none.key, none.copy(legIndex = 3).key)
    }

    // A bus route north from Bank (fictional stops past Moorgate), and an alert in TfL's style
    // naming a stretch at its south end (maintainer, 2026-10-01). Made-up words.
    private val busRoute = LineSequence(
        listOf(LineRoute("Bank - North End", listOf("b1", "b2", "b3", "b4", "b5", "b6"))),
        mapOf(
            "b1" to "Bank Station / King William Street", "b2" to "Example Street", "b3" to "Moorgate Station",
            "b4" to "Alpha Road", "b5" to "Beta Road", "b6" to "North End",
        ),
    )
    private val diversion = "EXAMPLE, EC2 - ROUTE 99 is on diversion northbound via Example Street. Buses are not " +
        "serving stops between 'Bank Station/King William Street' and 'Moorgate Station'. Please allow extra time."
    // [text] as its line's only alert under way, as TfL answered it ([LineStatus.soleAlert]).
    private fun sole(text: String?) = LineStatus("99", 6, "Diversion", text, soleAlert = true)
    private fun bus(from: String, to: String, mode: String = "bus") = TripLeg(mode, "99", "99", from, from, to, to, at(5), at(15))

    @Test
    fun `a bus alert naming only stops off the ride is off it`() {
        assertTrue(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion), busRoute))
        // A ride through the stretch, into it, or out of it is on it.
        assertFalse(RouteDisruption.offRide(bus("b1", "b5"), sole(diversion), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b2", "b6"), sole(diversion), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b3", "b4"), sole(diversion), busRoute))
    }

    @Test
    fun `a bus line's several alerts are off the ride only when each one is`() {
        // A route with several alerts under way, each about its far end, flagged a ride none of them
        // reached (maintainer, 2026-10-03). Made-up words.
        val missed = "Bus stop 'Example Street' will not be served."
        fun several(vararg texts: String) = LineStatus(
            "99", 5, "Diversion", texts.first(), soleAlert = false,
            underWay = texts.map { LineAlert(5, "Diversion", it) },
        )
        assertTrue(RouteDisruption.scopableOnRide(several(diversion, missed)))
        assertTrue(RouteDisruption.offRide(bus("b4", "b6"), several(diversion, missed), busRoute))
        // One of them reaching the ride keeps the line on it.
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), several(diversion, "Bus stop 'Beta Road' will not be served."), busRoute))
        // As does one about the whole route, or one giving no stretch.
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), several(diversion).copy(underWay = listOf(LineAlert(5, "Diversion", diversion), LineAlert(6, "Severe Delays", "Severe delays."))), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), several(diversion, "Buses are diverted near Moorgate Station."), busRoute))
        // Several under way whose words weren't kept: unknown, so on.
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion).copy(soleAlert = false), busRoute))
        assertFalse(RouteDisruption.scopableOnRide(sole(diversion).copy(soleAlert = false)))
    }

    @Test
    fun `a bus alert TfL scopes to the other way is off a ride this way`() {
        // TfL tags each alert with the way it affects; one naming a stop the ride's route lists under
        // another name, the other way only, kept the line on (maintainer, 2026-10-03). Made-up words.
        val bothWays = busRoute.copy(
            routes = listOf(
                busRoute.routes.single().copy(direction = "inbound"),
                LineRoute("North End - Bank", listOf("b6", "b5", "b4", "b3", "b2", "b1"), "outbound"),
            ),
        )
        val elsewhere = "Buses are diverted via Example Lane. Bus stop 'Example Lane / Alpha Road' (K) will not be served."
        fun status(vararg alerts: LineAlert) = LineStatus("99", 5, "Diversion", alerts.first().fullText, underWay = alerts.toList())
        val outboundOnly = LineAlert(5, "Diversion", elsewhere, setOf("outbound"))
        val moorgate = LineAlert(5, "Diversion", diversion, setOf("inbound"))
        assertTrue(RouteDisruption.offRide(bus("b4", "b6"), status(moorgate, outboundOnly), bothWays))
        // Not known which way it goes, or this way: it can't be placed, so it stays on.
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), status(moorgate, outboundOnly.copy(directions = null)), bothWays))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), status(moorgate, outboundOnly.copy(directions = setOf("inbound"))), bothWays))
        // A ride the other way is off the inbound one, but the outbound one, unplaced, keeps it on.
        assertFalse(RouteDisruption.offRide(bus("b6", "b4"), status(moorgate, outboundOnly), bothWays))
        // The other way only is off whatever its words: route-wide delays, or prose that places nothing
        // (Codex on #519), as the line's sole alert too.
        val delays = LineAlert(6, "Severe Delays", "Severe delays throughout the route.", setOf("outbound"))
        assertTrue(RouteDisruption.offRide(bus("b4", "b6"), status(moorgate, delays), bothWays))
        val soleDelays = LineStatus("99", 6, "Severe Delays", delays.fullText, soleAlert = true, underWay = listOf(delays))
        assertTrue(RouteDisruption.offRide(bus("b4", "b6"), soleDelays, bothWays))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), soleDelays.copy(underWay = listOf(delays.copy(directions = null))), bothWays))
        // So its route is worth reading, though its words could never place it.
        val busTrip = ActiveTrip(TripRoute(listOf(bus("b4", "b6"))), "North End", startedAt = t0)
        assertEquals(setOf("99"), RouteDisruption.routesWanted(busTrip, mapOf("99" to soleDelays), emptyMap(), emptySet(), at(3)))
        // Not once Keep going let go of it on this trip (Codex on #519).
        val keptGoing = busTrip.copy(disruptionsDismissed = setOf(Signal.Line(0, "99", "99", soleDelays, RouteDisruption.Tier.MEDIUM).dismissKey))
        assertEquals(emptySet<String>(), RouteDisruption.routesWanted(keptGoing, mapOf("99" to soleDelays), emptyMap(), emptySet(), at(3)))
    }

    @Test
    fun `a bus alert whose stretch is all before a stop is behind it, on any route through it`() {
        assertTrue(RouteDisruption.behind("b4", sole(diversion), busRoute))
        assertTrue(RouteDisruption.behind("b6", sole(diversion), busRoute))
        // At the stretch's far end, inside it, or before it, a bus from the stop may still run it.
        assertFalse(RouteDisruption.behind("b3", sole(diversion), busRoute))
        assertFalse(RouteDisruption.behind("b2", sole(diversion), busRoute))
        assertFalse(RouteDisruption.behind("b1", sole(diversion), busRoute))
        // Unknown is ahead: a stop no route calls at, an alert giving no stretch, one of several alerts.
        assertFalse(RouteDisruption.behind("elsewhere", sole(diversion), busRoute))
        assertFalse(RouteDisruption.behind("b4", sole("Buses are diverted near Moorgate Station."), busRoute))
        assertFalse(RouteDisruption.behind("b4", sole(diversion).copy(soleAlert = false), busRoute))
        // A second route through the stop that the alert gives no stretch on: where it applies isn't known.
        val shortWorking = busRoute.copy(routes = busRoute.routes + LineRoute("Alpha Road - Far End", listOf("b4", "b5", "x1")))
        assertFalse(RouteDisruption.behind("b4", sole(diversion), shortWorking))
        // The way back calls at the stop too, with the stretch after it: only the row's own way counts.
        val bothWays = busRoute.copy(
            routes = listOf(
                busRoute.routes.single().copy(direction = "inbound"),
                LineRoute("North End - Bank", listOf("b6", "b5", "b4", "b3", "b2", "b1"), "outbound"),
            ),
        )
        assertTrue(RouteDisruption.behind("b4", sole(diversion), bothWays, "inbound"))
        assertFalse(RouteDisruption.behind("b4", sole(diversion), bothWays, "outbound"))
        assertFalse(RouteDisruption.behind("b4", sole(diversion), bothWays))
        // A loop back through the stretch after the stop has it ahead.
        val loop = busRoute.copy(routes = listOf(LineRoute("Loop", listOf("b1", "b2", "b3", "b4", "b5", "b6", "b1", "b2"))))
        assertFalse(RouteDisruption.behind("b4", sole(diversion), loop))
    }

    @Test
    fun `a bus alert behind a stop is found on a route listing its stops as TfL's route data does`() {
        // Route stops come cleaned ("Bank / King William Street", "Moorgate"), the alert quoting signs.
        val listed = busRoute.copy(
            stopNames = busRoute.stopNames + mapOf("b1" to "Bank / King William Street", "b3" to "Moorgate"),
        )
        assertTrue(RouteDisruption.behind("b4", sole(diversion), listed))
        assertTrue(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion), listed))
        assertFalse(RouteDisruption.offRide(bus("b2", "b6"), sole(diversion), listed))
    }

    @Test
    fun `a ride inside a quoted stretch is on it, though it calls at neither end`() {
        // The stretch's ends quoted, the ride between them (Codex, PR #455).
        val route = LineSequence(
            listOf(LineRoute("North", listOf("q0", "q1", "q2", "q3", "q4", "q5"))),
            mapOf(
                "q0" to "South End", "q1" to "Bank Station / King William Street", "q2" to "Middle Road",
                "q3" to "Inner Road", "q4" to "Moorgate Station", "q5" to "North End",
            ),
        )
        val alert = "Buses are not serving stops between 'Bank Station/King William Street' and 'Moorgate Station'."
        assertFalse(RouteDisruption.offRide(bus("q2", "q3"), sole(alert), route))
        assertTrue(RouteDisruption.offRide(bus("q4", "q5"), sole(alert.replace("'Moorgate Station'", "'Inner Road'")), route))
    }

    @Test
    fun `a loop route's ride is on when any visit of its ends runs through the stretch`() {
        // A to B is run twice: past X, and past C. An alert on C is on the ride, whichever it is (Codex, PR #455).
        val loop = LineSequence(
            listOf(LineRoute("Loop", listOf("lA", "lB", "lX", "lA", "lC", "lD", "lB"))),
            mapOf("lA" to "Alpha Road", "lB" to "Beta Road", "lX" to "Example Street", "lC" to "Gamma Road", "lD" to "Delta Road"),
        )
        val alert = sole("Buses are not serving stops between 'Gamma Road' and 'Delta Road'.")
        assertFalse(RouteDisruption.offRide(bus("lA", "lB"), alert, loop))
        // Off only when no visit runs through it.
        assertTrue(RouteDisruption.offRide(bus("lX", "lA"), alert, loop))
        // Nor only to its first alighting visit: A to the second B runs through C (Codex, PR #455).
        val pastEnd = LineSequence(
            listOf(LineRoute("Loop", listOf("lA", "lB", "lX", "lC", "lD", "lB"))),
            mapOf("lA" to "Alpha Road", "lB" to "Beta Road", "lX" to "Example Street", "lC" to "Gamma Road", "lD" to "Delta Road"),
        )
        assertFalse(RouteDisruption.offRide(bus("lA", "lB"), alert, pastEnd))
    }

    @Test
    fun `a route calling at the other pole of the ride's stop pair still runs the ride`() {
        // The Planner names pole p3 of pair P3. The route calling at p3 meets the stretch before the
        // ride; the one calling at p3's opposite pole runs through it after boarding (Codex, PR #455).
        val poles = LineSequence(
            listOf(
                LineRoute("One way", listOf("p1", "p2", "p3", "p6")),
                LineRoute("Other way", listOf("p3x", "p1", "p2", "p6")),
            ),
            mapOf("p1" to "Alpha Road", "p2" to "Beta Road", "p3" to "Gamma Road", "p3x" to "Gamma Road", "p6" to "North End"),
            stopAreas = mapOf("p3" to "P3", "p3x" to "P3"),
        )
        val alert = sole("Buses are not serving stops between 'Alpha Road' and 'Beta Road'.")
        assertFalse(RouteDisruption.offRide(bus("p3", "p6").copy(fromArea = "P3"), alert, poles))
        // Without the pair only the named pole's route counts, and it's clear of the stretch.
        assertTrue(RouteDisruption.offRide(bus("p3", "p6"), alert, poles))
    }

    @Test
    fun `a route calling at another stand of the ride's bus station still runs the ride`() {
        // The Planner names stand s1 of a bus station, in no pair. One route calls at s1 and boards
        // after the stretch; the other calls only at stand s2 of the same name and runs through it
        // after boarding, so the alert is on the ride, as bus placement would match it (Codex, PR #455).
        val stands = LineSequence(
            listOf(
                LineRoute("One way", listOf("s0", "sA", "sB", "s1", "sN")),
                LineRoute("Other way", listOf("s2", "sA", "sB", "sN")),
            ),
            mapOf("s0" to "South End", "s1" to "Example Bus Station", "s2" to "Example Bus Station", "sA" to "Alpha Road", "sB" to "Beta Road", "sN" to "North End"),
        )
        val alert = sole("Buses are not serving stops between 'Alpha Road' and 'Beta Road'.")
        val leg = bus("s1", "sN").copy(fromName = "Example Bus Station")
        assertFalse(RouteDisruption.offRide(leg, alert, stands))
        // Named otherwise, only the route calling at s1 runs it, and it's clear of the stretch.
        assertTrue(RouteDisruption.offRide(leg.copy(fromName = "Elsewhere"), alert, stands))
    }

    @Test
    fun `a stop named in passing doesn't place a bus alert`() {
        // No stretch given: the stop it names may be an aside, so where it applies isn't known (Codex, PR #455).
        val aside = "Route 99 is on diversion via Example Avenue owing to roadworks near Moorgate Station."
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole(aside), busRoute))
    }

    @Test
    fun `a bus alert is on the ride when its stretch isn't known`() {
        // Naming none of the route's stops, no text, no route, or a ride no route runs.
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("Route 99 is on diversion via Example Avenue."), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole(null), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion), null))
        assertFalse(RouteDisruption.offRide(bus("b6", "b4"), sole(diversion), busRoute))
        // One alert bundling a route-wide effect with the stretch: the stretch isn't all of it (Codex, PR #455).
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("Severe delays throughout the route. $diversion"), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion).copy(description = "Severe Delays"), busRoute))
        // No service at all, under a label inferred from the diversion it also names (Codex, PR #455).
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("No service on route 99. $diversion"), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("Route 99 is not running. $diversion"), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("Route 99 isn't operating due to a diversion. $diversion"), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("Buses aren't running. $diversion"), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("Route 99 isn’t running. $diversion"), busRoute))
        // Every bus cancelled, however it's labeled (Codex, PR #455).
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("All buses are cancelled due to a diversion. $diversion"), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("All route 99 buses are diverted. $diversion"), busRoute))
        // The route itself closed; a road closed is just why it's diverted (Codex, PR #455).
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("Route 99 is closed because of a diversion. $diversion"), busRoute))
        assertTrue(RouteDisruption.offRide(bus("b4", "b6"), sole("Example Road is closed. $diversion"), busRoute))
        // Delays, in the plural TfL writes them, are the whole route's (Codex, PR #455).
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("Severe delays due to roadworks. $diversion"), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("No route 99 buses are operating. $diversion"), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole("Buses are not expected to run today. $diversion"), busRoute))
        // A status about the whole line, whatever stretch its words give (Codex, PR #455).
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion).copy(description = "Suspended", severity = 2), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion).copy(description = "Service Closed", severity = 20), busRoute))
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion).copy(description = "Special Service"), busRoute))
        // A part closure is about part of it.
        assertTrue(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion).copy(description = "Part Suspended", severity = 3), busRoute))
        assertTrue(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion).copy(description = "Part Closure", severity = 5), busRoute))
        // One of several alerts under way: the others' words are lost, and may reach the ride (Codex, PR #455).
        assertFalse(RouteDisruption.offRide(bus("b4", "b6"), sole(diversion).copy(soleAlert = false), busRoute))
        // Not a bus: a line's delays spread along it.
        assertFalse(RouteDisruption.offRide(bus("b4", "b6", mode = "tube"), sole(diversion), busRoute))
    }

    @Test
    fun `a bus line's alert off the ride is left out, and said`() {
        val leg = bus("b4", "b6")
        val busTrip = ActiveTrip(TripRoute(listOf(leg)), "North End", startedAt = t0)
        val alert = LineStatus("99", 6, "Diversion", diversion, soleAlert = true)
        val left = mutableListOf<Int>()
        fun found(sequences: Map<String, LineSequence>) = RouteDisruption.signals(
            busTrip, TripProgress.Waiting(leg, at(5)), mapOf("99" to alert), emptyMap(), emptyMap(), emptyMap(), emptySet(), at(3),
            sequences,
        ) { left += it }
        assertEquals(emptyList<Signal>(), found(mapOf("99" to busRoute)))
        assertEquals(listOf(0), left)
        // Without the route it's heard, as before.
        assertEquals(1, found(emptyMap()).size)
    }

    @Test
    fun `a bus line's split alert TfL places on the ride is high, as a whole line's is`() {
        // A part closure TfL places on the ride's stretch, beside other alerts: High and placed, as
        // [lineSignal] has it, not Medium for being split (Codex on #519). Made-up words.
        val leg = bus("b4", "b6").copy(path = listOf("b5", "b6"))
        val busTrip = ActiveTrip(TripRoute(listOf(leg)), "North End", startedAt = t0)
        val closed = LineAlert(11, "Part Closed", "No buses b4 to b6.")
        val delays = LineAlert(6, "Severe Delays", "Severe delays.")
        fun found(sections: List<List<String>>) = RouteDisruption.signals(
            busTrip, TripProgress.Waiting(leg, at(5)),
            mapOf("99" to LineStatus("99", 6, "Severe Delays", "Severe delays.", underWay = listOf(closed, delays),
                closures = listOf(PartClosure(11, "Part Closed", "No buses b4 to b6.", sections)))),
            emptyMap(), emptyMap(), emptyMap(), emptySet(), at(3),
        ).filterIsInstance<Signal.Line>().associate { it.status.description to (it.tier to it.placed) }
        assertEquals(mapOf("Part Closed" to (Tier.HIGH to true), "Severe Delays" to (Tier.MEDIUM to false)), found(listOf(listOf("b3", "b4", "b5", "b6"))))
        // Placed elsewhere on the route, it's Medium, as any part closure is.
        assertEquals(Tier.MEDIUM to false, found(listOf(listOf("x1", "x2", "x3")))["Part Closed"])
    }

    @Test
    fun `each of a bus line's several alerts that may reach the ride is a signal of its own`() {
        // Keep going lets go of the one seen, by its own identity, and the others still show
        // (maintainer, 2026-10-03). Made-up words.
        val leg = bus("b4", "b6")
        val busTrip = ActiveTrip(TripRoute(listOf(leg)), "North End", startedAt = t0)
        val beta = "Bus stop 'Beta Road' will not be served."
        val north = "Bus stop 'North End' will not be served."
        val status = LineStatus(
            "99", 6, "Diversion", diversion,
            underWay = listOf(LineAlert(6, "Diversion", diversion), LineAlert(6, "Diversion", beta), LineAlert(6, "Diversion", north)),
        )
        val left = mutableListOf<Int>()
        fun found(dismissed: Set<DismissedAlert> = emptySet()) = RouteDisruption.signals(
            busTrip, TripProgress.Waiting(leg, at(5)), mapOf("99" to status), emptyMap(), emptyMap(), emptyMap(), dismissed, at(3),
            mapOf("99" to busRoute),
        ) { left += it }
        // The diversion at Bank is off the ride, and told; the two stops on it are each named for themselves.
        val signals = found().filterIsInstance<Signal.Line>()
        assertEquals(listOf(0), left)
        assertEquals(listOf(beta, north), signals.map { it.status.fullText })
        assertEquals(2, signals.map { it.key }.distinct().size)
        // Each keyed by its own words, as its Dismiss is: one dismissed leaves the other.
        val one = DismissedAlert.ofLineStatus(signals.first().status)
        assertEquals(listOf(north), found(setOf(one)).filterIsInstance<Signal.Line>().map { it.status.fullText })
        // The one the list shows dismissed there doesn't take the others with it (Codex on #519).
        val showsBeta = status.copy(fullText = beta)
        fun foundFor(shown: LineStatus, dismissed: Set<DismissedAlert>, sequences: Map<String, LineSequence> = mapOf("99" to busRoute)) =
            RouteDisruption.signals(
                busTrip, TripProgress.Waiting(leg, at(5)), mapOf("99" to shown), emptyMap(), emptyMap(), emptyMap(), dismissed, at(3), sequences,
            ).filterIsInstance<Signal.Line>().map { it.status.fullText }
        val betaDismissed = setOf(DismissedAlert.ofLineStatus(LineStatus("99", 6, "Diversion", beta)))
        assertEquals(listOf(north), foundFor(showsBeta, betaDismissed))
        assertEquals(setOf("99"), RouteDisruption.routesWanted(busTrip, mapOf("99" to showsBeta), emptyMap(), betaDismissed, at(3)))
        // Alerts that never sound ask for no route, though their way is known (Codex on #519).
        val minor = LineStatus(
            "99", 9, "Minor Delays", "Minor delays.",
            underWay = listOf(LineAlert(9, "Minor Delays", "Minor delays.", setOf("outbound")), LineAlert(9, "Minor Delays", "Slow traffic.", setOf("inbound"))),
        )
        assertEquals(emptySet<String>(), RouteDisruption.routesWanted(busTrip, mapOf("99" to minor), emptyMap(), emptySet(), at(3)))
        // Nor once Keep going let go of every one on this trip (Codex on #519); a new one still asks.
        val keptGoing = busTrip.copy(disruptionsDismissed = status.underWay.map { Signal.Line(0, "99", "99", LineStatus("99", it.severity, it.description, it.fullText), RouteDisruption.Tier.MEDIUM).dismissKey }.toSet())
        assertEquals(emptySet<String>(), RouteDisruption.routesWanted(keptGoing, mapOf("99" to status), emptyMap(), emptySet(), at(3)))
        val another = status.copy(underWay = status.underWay + LineAlert(6, "Diversion", "Bus stop 'Gamma Road' will not be served."))
        assertEquals(setOf("99"), RouteDisruption.routesWanted(keptGoing, mapOf("99" to another), emptyMap(), emptySet(), at(3)))
        // No route to place them on: each may reach the ride, each its own.
        assertEquals(listOf(diversion, beta, north), foundFor(status, emptySet(), emptyMap()))
        // The same alert keys the same however many others are under way.
        assertEquals(signals.first().key, Signal.Line(0, "99", "99", LineStatus("99", 6, "Diversion", beta, soleAlert = true), RouteDisruption.Tier.MEDIUM).key)
    }

    @Test
    fun `a branch the trip took by itself is heard until the rider is past the ride to its fork, never as a card`() {
        // The ride cut to C, where the branch turns off, for the plan's E (maintainer, 2026-10-06).
        val taken = trip.copy(branchTakenLeg = 0, branchTakenTo = "E", branchTakenFork = "C")
        val none = RouteDisruption.noneDirect(taken, waiting)
        assertEquals(Signal.NoneDirect(0, "red", "Red", "E", "C"), none)
        assertEquals(Tier.MEDIUM, none?.tier)
        // Keyed by its leg as every heard key is, which a split shifts ([OffPlan.take]).
        assertEquals("nonedirect/0/red/E/C", none?.key)
        // Riding to the fork, still; past that ride, or arrived, or with none taken, not.
        assertEquals(none, RouteDisruption.noneDirect(taken, riding))
        assertNull(RouteDisruption.noneDirect(taken.copy(legIndex = 1), TripProgress.Walking(walk, at(20))))
        assertNull(RouteDisruption.noneDirect(taken, TripProgress.Arrived))
        assertNull(RouteDisruption.noneDirect(trip, waiting))
        // Said under the trip's step, so no card says it again.
        val line = Signal.Line(2, "blue", "Blue", status("blue", 3, "Part Suspended"), Tier.MEDIUM)
        assertEquals(listOf(line), RouteDisruption.cards(listOf(none!!, line)))
    }

    @Test
    fun `a bus stop gone past on another line than planned is avoided by the planned stop's area`() {
        // Planned on the red bus to C, a pole of a stop area; ridden on the green bus, whose leg names only the pole.
        val planned = ride.copy(mode = "bus", toArea = "490GEXAMPLE")
        val ridden = planned.copy(lineId = "green", lineName = "Green", toArea = "")
        val past = trip.copy(route = TripRoute(listOf(planned, walk, second)), legIndex = 1, pastLeg = 0, pastAtId = "X", pastAtName = "X", offLeg = ridden)
        val missed = checkNotNull(RouteDisruption.missed(past, TripProgress.Walking(walk, at(20))))
        assertEquals(AvoidedLines.stopKey("490GEXAMPLE", "C"), missed.stopAvoid)
    }

    @Test
    fun `the ride on from a stop gone past is the next one that isn't a walk`() {
        val past = trip.copy(legIndex = 1, pastLeg = 0, pastAtId = "X", pastAtName = "X")
        val missed = checkNotNull(RouteDisruption.missed(past, TripProgress.Walking(walk, at(20))))
        // Past the walk to D, the second ride: its line is what a "line closed" leaves out.
        assertEquals(second, RouteDisruption.missedOnward(past, missed))
        assertEquals(second, missed.onward)
        // Named as the line reads alone, worked out with the signal so the card only reads it.
        assertEquals(lineLabel("Blue", "tube"), missed.onwardLabel)
        // And the entry that avoids it, likewise, so the button's tap only reads it.
        assertEquals(AvoidedLines.key(second.lineId, lineLabel("Blue", "tube")), missed.onwardAvoid)
        // And the stop gone past, should it be closed: by the ride's own stop id, as the route names it.
        assertEquals(AvoidedLines.stopKey("C", "C"), missed.stopAvoid)
        // A trip that ends at the stop gone past has no ride on from it.
        val last = ActiveTrip(TripRoute(listOf(ride)), "C", startedAt = t0)
        assertNull(RouteDisruption.missedOnward(last, missed))
    }

    @Test
    fun `a stop the rider was seen past is heard, high, until they board on`() {
        // Off the first ride at C, seen past it, by D (maintainer, 2026-10-06).
        val past = trip.copy(legIndex = 1, pastLeg = 0, pastAtId = "X", pastAtName = "X")
        val walking = TripProgress.Walking(walk, at(20))
        val missed = RouteDisruption.missed(past, walking)
        assertEquals(Signal.Missed(0, "red", "Red", "C", "C", "X", "X", onward = second, onwardLabel = lineLabel("Blue", "tube"), onwardAvoid = AvoidedLines.key(second.lineId, lineLabel("Blue", "tube")), stopAvoid = AvoidedLines.stopKey("C", "C")), missed)
        assertEquals(Tier.HIGH, missed?.tier)
        assertEquals("missed/0/C", missed?.key)
        // A card, with the ride it's on, and a plan again from where they are.
        assertEquals(listOf(missed!!), RouteDisruption.cards(listOf(missed)))
        // Waiting for the next ride, still; on it, or arrived, or with none seen, not.
        assertEquals(missed, RouteDisruption.missed(past.copy(legIndex = 2), waiting))
        // On board seen or said: no longer. Only as the next train's time went by: it stands (Codex, #635).
        assertNull(RouteDisruption.missed(past.copy(legIndex = 2, boarded = true, onBoardSeen = true), waiting))
        assertEquals(missed, RouteDisruption.missed(past.copy(legIndex = 2, boarded = true), waiting))
        assertNull(RouteDisruption.missed(past, TripProgress.Arrived))
        assertNull(RouteDisruption.missed(trip.copy(legIndex = 1), walking))
        // Ridden as another line ran it: named as that line, where they were seen past it (Codex, #635).
        val blue = trip.route.legs[0].copy(lineId = "blue", lineName = "Blue")
        assertEquals("blue", RouteDisruption.missed(past.copy(offLeg = blue), walking)?.lineId)
        assertEquals(blue, RouteDisruption.rideAt(past.copy(offLeg = blue), 0, trip.route.legs[0]))
    }
}
