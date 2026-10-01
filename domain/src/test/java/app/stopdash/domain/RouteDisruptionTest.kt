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
    fun `no train of the line that may take the ride is predicted at a change`() {
        fun due(line: String, destination: String) = Departure(line, line, "outbound", destination, null, at(21), "tube", vehicleId = "v-$line-$destination")
        // A blue train to E: predicted. None at all, or only another line's: not.
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("blue", "E")), null))
        val none = RouteDisruption.unpredicted(2, second, listOf(due("red", "E")), null)
        assertEquals(Signal.Unpredicted(2, "blue", "Blue", "D", "D"), none)
        assertEquals(Tier.MEDIUM, none?.tier)
        assertEquals(none, RouteDisruption.unpredicted(2, second, emptyList(), null))
        // Only blue trains the other way, to Z: by its route, none takes the rider to E.
        val blue = LineSequence(
            listOf(LineRoute("D-E", listOf("D", "E")), LineRoute("D-Z", listOf("D", "Z"))),
            mapOf("D" to "D", "E" to "E", "Z" to "Z"),
        )
        assertEquals(none, RouteDisruption.unpredicted(2, second, listOf(due("blue", "Z")), blue))
        // Without the route, or with one that can't place it, it may be the rider's: not a signal.
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("blue", "Z")), null))
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("blue", "")), blue))
        // A train TfL names no line for may be the rider's (Codex, PR #443), unless it's another mode's.
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("", "")), blue))
        assertNull(RouteDisruption.unpredicted(2, second, listOf(due("", "E").copy(mode = "")), blue))
        assertEquals(none, RouteDisruption.unpredicted(2, second, listOf(due("", "E").copy(mode = "bus")), blue))
        // A ride with no line named can't be told on any board.
        assertNull(RouteDisruption.unpredicted(2, second.copy(lineId = ""), emptyList(), null))
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
