package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ways off a trip's plan, on a synthetic forked line: from A through B to C, where it splits for X
 * (the rider's way, through D) and for Y (through E); and a short branch off at B, to W.
 */
class OffPlanTest {
    private val t0 = Instant.parse("2026-10-05T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    private val line = LineSequence(
        routes = listOf(
            LineRoute("A ↔ X via D", listOf("A", "B", "C", "D", "X")),
            LineRoute("A ↔ Y via E", listOf("A", "B", "C", "E", "Y")),
            LineRoute("A ↔ W", listOf("A", "B", "W")),
        ),
        stopNames = mapOf("A" to "A", "B" to "B", "C" to "C", "D" to "D", "X" to "X", "E" to "E", "Y" to "Y", "W" to "W"),
    )
    private val sequences = mapOf("red" to line)

    // The ride the plan takes: A to X, four stops, ten minutes.
    private val ride = TripLeg(
        "tube", "red", "Red", "A", "A", "X", "X", at(5), at(15),
        path = listOf("B", "C", "D", "X"), pathNames = listOf("B", "C", "D", "X"), changeAfter = Duration.ofMinutes(2),
    )
    private val walkOn = TripLeg(TripLeg.WALKING, "", "", "X", "X", "Z", "Z", at(17), at(20))
    private val trip = ActiveTrip(TripRoute(listOf(ride, walkOn)), "Z", startedAt = t0)

    private fun train(vehicle: String, to: String, minutes: Long, line: String = "red", branch: String? = null) =
        Departure(line, line.replaceFirstChar { it.uppercase() }, "outbound", to, null, at(minutes), "tube", branch = branch, vehicleId = vehicle)

    private val loop get() = OffPlan.branches(ride, emptyList(), sequences, at(0)).single { it.label == "E" }

    @Test
    fun `the branches off the ride come from the line's route, nearest fork first`() {
        val branches = OffPlan.branches(ride, emptyList(), sequences, at(0))
        // Named by their routes' "via", else by where they end; the plan's own way isn't one.
        assertEquals(listOf("W" to 0, "E" to 1), branches.map { it.label to it.forkIndex })
        assertEquals("C", branches.last().forkName)
        // Listed whatever TfL's board says, an empty one included: its labels can be wrong.
        assertTrue(branches.all { it.trains.isEmpty() })
        // No route loaded: nothing guessed.
        assertTrue(OffPlan.branches(ride, emptyList(), emptyMap(), at(0)).isEmpty())
    }

    @Test
    fun `a way that comes back to where the rider gets off isn't a branch off the ride`() {
        // Leaves the ride's path after B, and comes back to X: it still takes the rider there.
        val loopBack = line.copy(
            routes = line.routes + LineRoute("A ↔ X via Long", listOf("A", "B", "Q", "X")),
            stopNames = line.stopNames + ("Q" to "Q") + ("P" to "P"),
        )
        assertEquals(listOf("W", "E"), OffPlan.branches(ride, emptyList(), mapOf("red" to loopBack), at(0)).map { it.label })
    }

    @Test
    fun `route patterns that run on alike past the fork are one branch, named for what lies past it`() {
        // A pattern to Y with no "via", and one named for a stop the rider passes before the fork: both run
        // on from C through E to Y, one branch, called where it ends (Codex, #583).
        val patterns = line.copy(
            routes = listOf(
                LineRoute("A ↔ X via D", listOf("A", "B", "C", "D", "X")),
                LineRoute("Q ↔ Y", listOf("Q", "A", "B", "C", "E", "Y")),
                LineRoute("P ↔ Y via B", listOf("P", "A", "B", "C", "E", "Y")),
            ),
            stopNames = line.stopNames + ("Q" to "Q") + ("P" to "P"),
        )
        val branches = OffPlan.branches(ride, listOf(train("2", "Y", 3)), mapOf("red" to patterns), at(0))
        assertEquals(listOf("Y"), branches.map { it.label })
        // A train on that way is the branch's, whatever TfL calls it.
        assertEquals(listOf("2"), branches.single().trains.map { it.vehicleId })
    }

    @Test
    fun `a branch carries the trains TfL lists for it`() {
        val board = listOf(
            train("1", "X", 11), train("2", "Y", 3), train("3", "Y", 7), train("4", "Y", 2, line = "blue"),
        )
        val branches = OffPlan.branches(ride, board, sequences, at(0))
        assertEquals(listOf("2", "3"), branches.single { it.label == "E" }.trains.map { it.vehicleId })
        assertTrue(branches.single { it.label == "W" }.trains.isEmpty())
        // Gone by then: not listed.
        assertEquals(listOf("3"), OffPlan.branches(ride, board, sequences, at(4)).single { it.label == "E" }.trains.map { it.vehicleId })
    }

    @Test
    fun `another line from the platform that turns off the ride is a branch of its own`() {
        // Blue runs A, B, C with the ride, then off to Q; green runs on to X, the ride's own way, so its
        // trains are the board's, not a branch's (maintainer, 2026-10-05: the Circle beside the District).
        val blue = LineSequence(listOf(LineRoute("A ↔ Q", listOf("A", "B", "C", "Q"))), mapOf("A" to "A", "B" to "B", "C" to "C", "Q" to "Q"))
        val green = LineSequence(listOf(LineRoute("A ↔ X", listOf("A", "B", "C", "D", "X"))), line.stopNames)
        val board = listOf(train("2", "Q", 3, line = "blue"), train("3", "X", 4, line = "green"), train("4", "Q", 6, line = "blue"))
        val branches = OffPlan.branches(ride, board, sequences + ("blue" to blue) + ("green" to green), at(0))
        // The ride's own line's first, then the other's.
        assertEquals(listOf("W" to "", "E" to "", "Q" to "blue"), branches.map { it.label to it.lineId })
        val q = branches.last()
        assertEquals(1, q.forkIndex)
        assertEquals("C", q.forkName)
        assertEquals("Blue", q.lineName)
        assertEquals(listOf("2", "4"), q.trains.map { it.vehicleId })
        // Not without its route loaded.
        assertEquals(listOf("W", "E"), OffPlan.branches(ride, board, sequences, at(0)).map { it.label })
    }

    @Test
    fun `taking another line's branch rides that line to where it turns off`() {
        val blue = LineSequence(listOf(LineRoute("A ↔ Q", listOf("A", "B", "C", "Q"))), mapOf("A" to "A", "B" to "B", "C" to "C", "Q" to "Q"))
        val board = listOf(train("2", "Q", 3, line = "blue"))
        val q = OffPlan.branches(ride, board, sequences + ("blue" to blue), at(0)).single { it.otherLine }
        val taken = OffPlan.take(trip, 0, q, at(1))!!
        val (first, rest) = taken.route.legs
        assertEquals("blue" to "C", first.lineId to first.toId)
        // The rest of the ride stays on the ride's own line.
        assertEquals("red" to "C", rest.lineId to rest.fromId)
        // Not once on board a train of the ride's own.
        assertNull(OffPlan.take(trip.copy(boarded = true), 0, q, at(1)))
    }

    @Test
    fun `taking a branch reroutes the trip, a change where it turns off onto the rest of the ride`() {
        val taken = OffPlan.take(trip.copy(onFootChanges = setOf(1)), 0, loop, at(1))!!
        val (first, rest, walk) = taken.route.legs
        assertEquals("C", first.toId)
        assertEquals(listOf("B", "C"), first.path)
        assertEquals(Duration.ZERO, first.changeAfter)
        assertEquals("C", rest.fromId)
        assertEquals(listOf("D", "X"), rest.path)
        assertEquals(Duration.ofMinutes(2), rest.changeAfter)
        // The Planner's ten minutes shared by stops: two of four.
        assertEquals(at(10), first.arrival)
        assertEquals(at(10), rest.departure)
        assertEquals(walkOn, walk)
        // Nothing followed specially: the ride to C picks its train as any ride does.
        assertEquals("", taken.vehicleId)
        assertEquals(0, taken.legIndex)
        assertEquals(at(1), taken.legStartedAt)
        // What was kept by leg moves up with the split.
        assertEquals(setOf(2), taken.onFootChanges)
        // Any train to C is the rider's for the first part, whatever TfL calls its way.
        val (_, progress) = OnTheWay.advance(
            OnTheWay.follow(taken, train("1", "X", 3)).copy(boarded = true),
            listOf(VehicleCall("B", "B", null, at(6)), VehicleCall("C", "C", null, at(8))), at(5),
        )
        progress as TripProgress.Riding
        assertEquals("C", progress.leg.toId)
    }

    @Test
    fun `waiting, a train followed is let go, so the next to reach the fork is looked for`() {
        val waiting = OnTheWay.follow(trip, train("1", "X", 5))
        val taken = OffPlan.take(waiting, 0, loop, at(2))!!
        assertEquals("", taken.vehicleId)
        assertFalse(taken.boarded)
    }

    @Test
    fun `on board, the rider stays on their train, now ridden to the fork`() {
        // Their train changed its branch on the way, or TfL labels it wrongly (maintainer, 2026-10-05).
        val riding = OnTheWay.follow(trip, train("1", "X", 5)).copy(boarded = true, boardedAt = at(5), dueOffAt = at(15), warnedLeg = 0)
        val taken = OffPlan.take(riding, 0, loop, at(6))!!
        assertEquals("1", taken.vehicleId)
        assertTrue(taken.boarded)
        assertEquals(at(5), taken.boardedAt)
        // The old end's "get off soon" and due time are done with.
        assertEquals(-1, taken.warnedLeg)
        assertNull(taken.dueOffAt)
        assertEquals("C", taken.leg?.toId)
        // Its calls now end the ride at C.
        val (_, progress) = OnTheWay.advance(taken, listOf(VehicleCall("C", "C", null, at(9))), at(8))
        progress as TripProgress.Riding
        assertTrue(progress.getOffSoon)
        assertEquals("C", progress.leg.toId)
    }

    @Test
    fun `a fork already behind a rider seen on board isn't taken`() {
        val seen = trip.copy(boarded = true, onBoardSeen = true, seenAlongStop = 2)
        assertNull(OffPlan.take(seen, 0, loop, at(8)))
        // Seen short of it, it is.
        assertEquals("C", OffPlan.take(seen.copy(seenAlongStop = 1), 0, loop, at(8))?.leg?.toId)
    }

    @Test
    fun `taken on the walk to the ride, the walk goes on`() {
        val walk = TripLeg(TripLeg.WALKING, "", "", "S", "S", "A", "A", at(0), at(5))
        val walking = ActiveTrip(TripRoute(listOf(walk, ride)), "X", startedAt = t0)
        val taken = OffPlan.take(walking, 1, loop, at(3))!!
        assertEquals(0, taken.legIndex)
        assertEquals(t0, taken.legStartedAt)
        assertEquals(listOf("A", "C", "X"), taken.route.legs.map { it.toId })
        // Not a ride already behind the rider.
        assertNull(OffPlan.take(walking.copy(legIndex = 2), 1, loop, at(3)))
    }

    @Test
    fun `a branch taken by itself is still said through a later split of its ride`() {
        // The ride the trip took a branch for by itself, split again: said on to the fork, its second part (Codex, #630).
        val said = trip.copy(branchTakenLeg = 0, branchTakenTo = "Z", branchTakenFork = "X")
        assertEquals(1, OffPlan.take(said, 0, loop, at(1))?.branchTakenLeg)
        // A later ride's mark moves up with it; an earlier one's stays, as does none.
        val walk = TripLeg(TripLeg.WALKING, "", "", "S", "S", "A", "A", at(0), at(5))
        val walking = ActiveTrip(TripRoute(listOf(walk, ride)), "X", startedAt = t0)
        assertEquals(0, OffPlan.take(walking.copy(branchTakenLeg = 0), 1, loop, at(3))?.branchTakenLeg)
        assertEquals(-1, OffPlan.take(trip, 0, loop, at(1))?.branchTakenLeg)
    }

    @Test
    fun `a branch taken by itself is marked, and a second keeps the first mark`() {
        val first = OffPlan.takenBySelf(trip, OffPlan.take(trip, 0, loop, at(1))!!, 0, loop)
        assertEquals(Triple(0, "X", "C"), Triple(first.branchTakenLeg, first.branchTakenTo, first.branchTakenFork))
        // Split again before C: still what the rider was going to, said on through the ride to C (Codex, #630).
        val again = OffPlan.take(first, 0, loop.copy(forkIndex = 0, forkName = "B"), at(2))!!
        val said = OffPlan.takenBySelf(first, again, 0, loop.copy(forkIndex = 0, forkName = "B"))
        assertEquals(Triple(1, "X", "C"), Triple(said.branchTakenLeg, said.branchTakenTo, said.branchTakenFork))
        // Heard once for that ride: its key, kept with what's heard, shifted with the split (Codex, #633).
        val heard = RouteDisruption.noneDirect(first, null)!!.key
        val split = OffPlan.take(first.copy(disruptionsHeard = setOf(heard)), 0, loop.copy(forkIndex = 0, forkName = "B"), at(2))!!
        assertTrue(RouteDisruption.noneDirect(split, null)!!.key in split.disruptionsHeard)
    }

    @Test
    fun `what was heard on the ride holds for both its parts, a later leg's moves up`() {
        val heard = setOf("line/0/a/b", "stop/1/Z/k/s", "unpredicted/0/red/A")
        val taken = OffPlan.take(trip.copy(disruptionsHeard = heard, disruptionsDismissed = setOf("line/1/a/b")), 0, loop, at(1))!!
        assertEquals(
            setOf("line/0/a/b", "line/1/a/b", "stop/2/Z/k/s", "unpredicted/0/red/A", "unpredicted/1/red/A"),
            taken.disruptionsHeard,
        )
        assertEquals(setOf("line/2/a/b"), taken.disruptionsDismissed)
    }
}
