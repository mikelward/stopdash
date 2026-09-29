package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and lines only. */
class TripClosuresTest {
    private val now = Instant.parse("2026-09-26T08:00:00Z")

    private fun leg(lineId: String, from: String, to: String, mode: String = "tube", fromArea: String = "", toArea: String = "") = TripLeg(
        mode = mode,
        lineId = lineId,
        lineName = lineId,
        fromId = from,
        fromName = from,
        toId = to,
        toName = to,
        departure = now,
        arrival = now.plus(Duration.ofMinutes(10)),
        fromArea = fromArea,
        toArea = toArea,
    )

    private fun walk(from: String, to: String) = leg("", from, to, mode = TripLeg.WALKING)

    // From here (no stop) on foot to A, red to B, a walk to D, blue to C, then on foot to E.
    private val route = TripRoute(listOf(walk("", "A"), leg("red", "A", "B"), walk("B", "D"), leg("blue", "D", "C"), walk("C", "E")))

    private val closed = listOf(StopDisruption("Station closed due to strike action"))

    // Every stop [route] is checked at, checked with nothing to report, as the trip holds them.
    private val allChecked = listOf("A", "B", "D", "C", "E").associateWith { emptyList<StopDisruption>() }

    @Test
    fun `a route is checked where each ride boards and gets off, and where it ends`() {
        assertEquals(listOf("A", "B", "D", "C", "E"), TripClosures.ends(route).map { it.id })
        // A walk to a place ends at no stop: nothing to check there.
        val toPlace = TripRoute(listOf(leg("red", "A", "B"), walk("B", "")))
        assertEquals(listOf("A", "B"), TripClosures.ends(toPlace).map { it.id })
        // A change at one stop: once for each line that stops there.
        val change = TripRoute(listOf(leg("red", "A", "B"), leg("blue", "B", "C")))
        assertEquals(listOf("A" to "red", "B" to "red", "B" to "blue", "C" to "blue"), TripClosures.ends(change).map { it.id to it.lineId })
        // A walk from one stop to another is checked at both, before a ride or as the whole route; a
        // walk's stop a ride also uses is that ride's, placed by its line.
        val walkFirst = TripRoute(listOf(walk("F", "A"), leg("red", "A", "B")))
        assertEquals(listOf("F" to "", "A" to "red", "B" to "red"), TripClosures.ends(walkFirst).map { it.id to it.lineId })
        assertEquals(listOf("F", "E"), TripClosures.ends(TripRoute(listOf(walk("F", "E")))).map { it.id })
        assertEquals(TripClosures.Standing.CLOSED, TripClosures.standing(walkFirst, mapOf("F" to closed, "A" to emptyList(), "B" to emptyList()), emptySet(), now))
        // The Planner walks to the pair's pole P1, and the bus is placed at P2 across the road: P1
        // is the ride's stop, not the walk's, so its closure doesn't close the route.
        val placed = TripRoute(listOf(walk("F", "P1"), leg("25", "P2", "Q1", mode = "bus", fromArea = "490GP", toArea = "490GQ")))
        assertEquals(listOf("F", "P2", "Q1"), TripClosures.ends(placed).map { it.id })
        val checks = mapOf("F" to emptyList(), "P1" to closed, "P2" to emptyList(), "Q1" to emptyList<StopDisruption>())
        assertEquals(TripClosures.Standing.OPEN, TripClosures.standing(placed, checks, emptySet(), now))
    }

    @Test
    fun `a stop it boards or gets off at that says it's closed closes the route`() {
        assertEquals(TripClosures.Standing.CLOSED, TripClosures.standing(route, allChecked + ("A" to closed), emptySet(), now))
        assertEquals(TripClosures.Standing.CLOSED, TripClosures.standing(route, allChecked + ("C" to closed), emptySet(), now))
        assertEquals(TripClosures.Standing.CLOSED, TripClosures.standing(route, allChecked + ("E" to closed), emptySet(), now))
        // A closed stop it doesn't use leaves it open.
        assertEquals(TripClosures.Standing.OPEN, TripClosures.standing(route, allChecked + ("F" to closed), emptySet(), now))
    }

    @Test
    fun `a moved stop, a lift out or a closure not yet in force leaves the route open`() {
        val moved = listOf(StopDisruption("Bus stop moved to the other side of the junction"))
        val lift = listOf(StopDisruption("Lift out of order"))
        val later = listOf(StopDisruption("Station closed", validFrom = now.plus(Duration.ofHours(2))))
        for (notices in listOf(moved, lift, later)) {
            assertEquals(TripClosures.Standing.OPEN, TripClosures.standing(route, allChecked + ("B" to notices), emptySet(), now))
        }
    }

    @Test
    fun `a stop with no check known leaves the route unchecked, below a closure`() {
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.standing(route, emptyMap(), setOf("D"), now))
        assertEquals(TripClosures.Standing.CLOSED, TripClosures.standing(route, mapOf("A" to closed), setOf("D"), now))
    }

    @Test
    fun `a pole of a pair not yet looked up is unchecked until it has a check of its own`() {
        // The bus was moved to the pair's other pole ("P2"), which nothing asked about yet.
        val bus = TripRoute(listOf(leg("25", "P2", "Q", mode = "bus", fromArea = "490G1")))
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.standing(bus, mapOf("Q" to emptyList()), setOf("490G1"), now))
        assertEquals(TripClosures.Standing.OPEN, TripClosures.standing(bus, mapOf("P2" to emptyList(), "Q" to emptyList()), setOf("490G1"), now))
        // A pole in a pair is known only by its own check, even once the pair is looked up: TfL's list
        // of its poles can miss the one the bus uses, which then was never asked about.
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.standing(bus, mapOf("Q" to emptyList()), emptySet(), now))
    }

    @Test
    fun `a closed pole of a pair can't be ruled out until the bus is placed on its own`() {
        // The Planner's pole P1 is open; its pair's other pole P2 is closed.
        val bus = TripRoute(listOf(leg("25", "P1", "Q", mode = "bus", fromArea = "490G1")))
        val closures = mapOf("P1" to emptyList(), "P2" to closed, "Q" to emptyList())
        val poles = mapOf("490G1" to listOf("P1", "P2"))
        // The line's route not in yet: P2 may be the pole the bus uses.
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.standing(bus, closures, emptySet(), now) { null })
        // Placed on P1 by its route: the other side's closure isn't this route's.
        assertEquals(TripClosures.Standing.OPEN, TripClosures.standing(bus, closures, emptySet(), now) { it.id })
        // Placed on P2, the closed side: closed.
        assertEquals(TripClosures.Standing.CLOSED, TripClosures.standing(bus, closures, emptySet(), now) { "P2" })
        // Not yet placed, the Planner's own pole closed: it may not be the one the bus uses either.
        val plannerClosed = mapOf("P1" to closed, "P2" to emptyList(), "Q" to emptyList())
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.standing(bus, plannerClosed, emptySet(), now) { null })
        // Every pole TfL listed checked open, but the bus not yet placed: TfL's list can miss the pole
        // it uses, so the route still isn't vouched for there.
        val allOpen = mapOf("P1" to emptyList(), "P2" to emptyList<StopDisruption>(), "Q" to emptyList())
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.standing(bus, allOpen, emptySet(), now) { null })
        // A stop in no pair is placed where the Planner named it.
        assertEquals(TripClosures.Standing.OPEN, TripClosures.standing(route, allChecked, emptySet(), now) { it.id })
    }

    @Test
    fun `a stand a bus is placed on in place of the Planner's goes by a check of its own`() {
        // The Planner named stand S1 of a bus station; the line's route puts the bus at S2.
        val end = TripClosures.End("S1", "", "25")
        val checked = mapOf("S1" to emptyList<StopDisruption>())
        // Only S1 was asked about: S2 isn't vouched for, though nothing names it unknown.
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.judge(end, checked, emptySet(), now, used = "S2"))
        assertEquals(TripClosures.Standing.OPEN, TripClosures.judge(end, checked + ("S2" to emptyList()), emptySet(), now, used = "S2"))
        assertEquals(TripClosures.Standing.CLOSED, TripClosures.judge(end, checked + ("S2" to closed), emptySet(), now, used = "S2"))
        // Nor once the route is rewritten to its placement, naming S2 as if the Planner had: still
        // only by a check of its own.
        val rewritten = TripClosures.End("S2", "", "25")
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.judge(rewritten, checked, emptySet(), now))
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.standing(TripRoute(listOf(leg("25", "S2", "Q", mode = "bus"))), checked + ("Q" to emptyList()), emptySet(), now))
        assertEquals(TripClosures.Standing.OPEN, TripClosures.judge(rewritten, checked + ("S2" to emptyList()), emptySet(), now))
    }

    @Test
    fun `a stop is judged alone as the route judges it`() {
        val end = TripClosures.End("P1", "490G1", "25")
        val poles = mapOf("490G1" to listOf("P1", "P2"))
        val checked = mapOf("P1" to emptyList<StopDisruption>(), "P2" to emptyList())
        // Not yet placed: never vouched for, however its poles were checked.
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.judge(end, checked, emptySet(), now, used = null))
        // Placed on P1: only P1 counts.
        assertEquals(TripClosures.Standing.OPEN, TripClosures.judge(end, checked, setOf("P2"), now, used = "P1"))
        assertEquals(TripClosures.Standing.CLOSED, TripClosures.judge(end, checked + ("P1" to closed), emptySet(), now, used = "P1"))
        // What bears on it: the placed pole alone, or until then, the pair and all of its poles.
        assertEquals(listOf("P1"), TripClosures.reads(end, poles, used = "P1"))
        assertEquals(listOf("490G1", "P1", "P1", "P2"), TripClosures.reads(end, poles, used = null))
        // A lone stop goes by its own check: held, and not named unknown.
        assertEquals(TripClosures.Standing.OPEN, TripClosures.judge(TripClosures.End("A"), mapOf("A" to emptyList()), emptySet(), now))
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.judge(TripClosures.End("A"), emptyMap(), emptySet(), now))
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.judge(TripClosures.End("A"), mapOf("A" to emptyList()), setOf("A"), now))
    }

    @Test
    fun `a pair whose poles weren't looked up is unchecked until the bus is placed`() {
        val bus = TripRoute(listOf(leg("25", "P1", "Q", mode = "bus", fromArea = "490G1")))
        // The Planner's pole checked, the pair's other poles never looked up.
        val closures = mapOf("P1" to emptyList<StopDisruption>(), "Q" to emptyList())
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.standing(bus, closures, setOf("490G1"), now) { null })
        // Placed on the Planner's pole: its own check stands.
        assertEquals(TripClosures.Standing.OPEN, TripClosures.standing(bus, closures, setOf("490G1"), now) { it.id })
        // Placed on another pole nothing asked about: not vouched for.
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.standing(bus, closures, setOf("490G1"), now) { "P2" })
    }

    @Test
    fun `a change between buses at one pole is placed by each bus's own route`() {
        // The 25 gets off at P1 and the 86 boards there; the pair's other pole P2 is closed.
        val change = TripRoute(listOf(leg("25", "O", "P1", mode = "bus", toArea = "490G1"), leg("86", "P1", "Q", mode = "bus", fromArea = "490G1")))
        val closures = mapOf("O" to emptyList(), "P1" to emptyList(), "P2" to closed, "Q" to emptyList())
        val poles = mapOf("490G1" to listOf("P1", "P2"))
        // The 25's route has placed it on P1, but the 86's hasn't loaded: it may board at P2.
        assertEquals(TripClosures.Standing.UNCHECKED, TripClosures.standing(change, closures, emptySet(), now) { if (it.lineId == "25") it.id else null })
        assertEquals(TripClosures.Standing.OPEN, TripClosures.standing(change, closures, emptySet(), now) { it.id })
    }

    @Test
    fun `another line's stops are open only once each is checked open`() {
        val green = leg("green", "P2", "Q1", mode = "bus")
        val checked = mapOf("P2" to emptyList<StopDisruption>(), "Q1" to emptyList())
        assertTrue(TripClosures.opens(checked, emptySet(), now)(green))
        // Where it boards closed, where it gets off not checked, or named unknown: not open.
        assertFalse(TripClosures.opens(checked + ("P2" to closed), emptySet(), now)(green))
        assertFalse(TripClosures.opens(checked - "Q1", emptySet(), now)(green))
        assertFalse(TripClosures.opens(checked, setOf("P2"), now)(green))
        // A moved stop leaves it open, as it leaves a route open.
        assertTrue(TripClosures.opens(checked + ("P2" to listOf(StopDisruption("Bus stop moved to the other side of the junction"))), emptySet(), now)(green))
    }
}
