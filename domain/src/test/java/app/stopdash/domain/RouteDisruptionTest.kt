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
    fun `a signal added to what's known is said in order and stands no longer than either`() {
        val line = Signal.Line(0, "red", "Red", status("red", 2, "Suspended"), Tier.HIGH)
        val none = Signal.Unpredicted(2, "blue", "Blue", "D", "D")
        assertEquals(RouteDisruption.Found(listOf(none), at(4)), RouteDisruption.Found.NONE.with(none, at(4)))
        val both = RouteDisruption.Found(listOf(line), at(6)).with(none, at(4))
        assertEquals(listOf<Signal>(line, none), both.signals)
        assertEquals(at(4), both.until)
        assertEquals(at(6), RouteDisruption.Found(listOf(line), at(6)).with(none, at(9)).until)
        // Heard once for the leg, whatever the board lists between.
        assertEquals(none.key, none.copy().key)
        assertNotEquals(none.key, none.copy(legIndex = 3).key)
    }
}
