package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and lines only. */
class TripRouteTest {
    private val t = Instant.parse("2026-09-27T12:00:00Z")

    private fun ride(line: String, from: String, to: String, path: List<String> = listOf(to), toArea: String = "") = TripLeg(
        mode = "tube", lineId = line, lineName = line, fromId = from, fromName = from, toId = to, toName = to,
        departure = t, arrival = t, path = path, toArea = toArea,
    )

    private fun walk(from: String, to: String) = TripLeg(
        mode = TripLeg.WALKING, lineId = "", lineName = "", fromId = from, fromName = from, toId = to, toName = to,
        departure = t, arrival = t,
    )

    private val destination = setOf("C", "E")

    @Test
    fun `a route that rides through the destination and on passes it`() {
        // On past C to F, and a bus back to E: the rider would have got off at C.
        val route = TripRoute(listOf(ride("red", "A", "F", listOf("B", "C", "F")), ride("bus", "F", "E")))
        assertTrue(route.passesThrough(destination))
    }

    @Test
    fun `a route that changes at the destination and goes on passes it`() {
        val route = TripRoute(listOf(ride("red", "A", "C", listOf("B", "C")), ride("blue", "C", "E", listOf("E"))))
        assertTrue(route.passesThrough(destination))
    }

    @Test
    fun `a route that ends at the destination doesn't pass it`() {
        assertFalse(TripRoute(listOf(ride("red", "A", "C", listOf("B", "C")))).passesThrough(destination))
        // Whatever id TfL names its last call by, and a walk after it.
        assertFalse(TripRoute(listOf(ride("red", "A", "C", listOf("B", "E")), walk("C", "E"))).passesThrough(destination))
        // Nor one that starts there.
        assertFalse(TripRoute(listOf(ride("red", "C", "G", listOf("G")))).passesThrough(destination))
    }

    @Test
    fun `a route that walks to the destination and rides on passes it`() {
        // Walked from A to C, then away to F and a bus back to E: the rider was there after the walk.
        val route = TripRoute(listOf(walk("A", "C"), ride("red", "C", "F", listOf("F")), ride("bus", "F", "E")))
        assertTrue(route.passesThrough(destination))
        // A walk at the start that stops short of it is only the way to the first ride.
        assertFalse(TripRoute(listOf(walk("A", "B"), ride("red", "B", "C", listOf("C")))).passesThrough(destination))
    }

    @Test
    fun `an end named by an id the destination doesn't know is reached, not passed`() {
        // TfL names this bus's end only by a pole, P1, and the path's last stop is C. P1 may be C
        // under another name or a stop past it: which can't be told, so a walk on from there is
        // still arriving, and the route is kept rather than wrongly dropped.
        assertFalse(TripRoute(listOf(ride("bus", "A", "P1", listOf("B", "C")), walk("P1", "E"))).passesThrough(destination))
        // The same with the path running through P1 itself, as it can: P1 is still the end.
        assertFalse(TripRoute(listOf(ride("bus", "A", "P1", listOf("B", "C", "P1")), walk("P1", "E"))).passesThrough(destination))
        // A ride on from there has left C either way.
        assertTrue(TripRoute(listOf(ride("bus", "A", "P1", listOf("B", "C")), ride("red", "P1", "E"))).passesThrough(destination))
        // Where the path does end at the destination, that end is an arrival, not a call.
        assertFalse(TripRoute(listOf(ride("bus", "A", "C", listOf("B", "C")), walk("C", "E"))).passesThrough(destination))
    }

    @Test
    fun `a path ending in the destination's poles and stop pair only arrives`() {
        // As TfL encodes a bus end: the pole across the road, then the pair, before the pole it
        // names as the end. All three are the destination.
        val complex = setOf("C", "PX", "PS", "G")
        val route = TripRoute(listOf(ride("bus", "A", "PS", listOf("B", "PX", "G"), toArea = "G")))
        assertFalse(route.passesThrough(complex))
        // Leaving them for a call somewhere else is passing through.
        assertTrue(TripRoute(listOf(ride("bus", "A", "H", listOf("B", "PX", "G", "F", "H")))).passesThrough(complex))
        // An end the destination doesn't know may be one of them by another name: kept, unless a
        // ride goes on from there.
        assertFalse(TripRoute(listOf(ride("bus", "A", "F", listOf("B", "PX", "G", "F")))).passesThrough(complex))
        assertTrue(TripRoute(listOf(ride("bus", "A", "F", listOf("B", "PX", "G", "F")), ride("red", "F", "C"))).passesThrough(complex))
    }

    @Test
    fun `a bus leg's stop pair counts as its stop`() {
        val route = TripRoute(listOf(ride("bus", "A", "P1", listOf("P1"), toArea = "C"), ride("red", "P1", "E")))
        assertTrue(route.passesThrough(destination))
    }

    @Test
    fun `a detour goes only when a route getting off where it passed arrives no later`() {
        fun ride(line: String, from: String, to: String, path: List<String>, arrives: Long) =
            ride(line, from, to, path).copy(arrival = t.plusSeconds(arrives * 60))
        // Through C to F and a bus back to E, at 40; straight to C at 30, which beats it.
        val detour = TripRoute(listOf(ride("red", "A", "F", listOf("B", "C", "F"), 30), ride("bus", "F", "E", listOf("E"), 40)))
        val direct = TripRoute(listOf(ride("red", "A", "C", listOf("B", "C"), 30)))
        assertEquals(setOf("C"), detour.passedAt(destination))
        assertEquals(listOf(direct), withoutDetours(listOf(detour, direct), destination))
        // A later one getting off at C doesn't beat it, and nothing getting off elsewhere does.
        val late = TripRoute(listOf(ride("red", "A", "C", listOf("B", "C"), 45)))
        assertEquals(listOf(detour, late), withoutDetours(listOf(detour, late), destination))
        val elsewhere = TripRoute(listOf(ride("red", "A", "E", listOf("B", "E"), 20)))
        assertEquals(listOf(detour, elsewhere), withoutDetours(listOf(detour, elsewhere), destination))
        // Alone, it stays: it may be the only way the plan found.
        assertEquals(listOf(detour), withoutDetours(listOf(detour), destination))
    }

    @Test
    fun `every destination stop a detour rides on from counts`() {
        fun ride(line: String, from: String, to: String, path: List<String>, arrives: Long) =
            ride(line, from, to, path).copy(arrival = t.plusSeconds(arrives * 60))
        // Through G (a stop nothing else gets off at), out to X, then through C and out to F, and
        // back to E: a route straight to C beats it, though G alone wouldn't.
        val complex = setOf("C", "E", "G")
        val detour = TripRoute(
            listOf(ride("bus", "A", "X", listOf("G", "X"), 20), ride("red", "X", "F", listOf("C", "F"), 30), ride("bus", "F", "E", listOf("E"), 40)),
        )
        assertEquals(setOf("G", "C"), detour.passedAt(complex))
        val direct = TripRoute(listOf(ride("red", "A", "C", listOf("C"), 30)))
        assertEquals(listOf(direct), withoutDetours(listOf(detour, direct), complex))
    }

    @Test
    fun `a pole and its sibling are one stop to get off at`() {
        fun ride(line: String, from: String, to: String, path: List<String>, arrives: Long) =
            ride(line, from, to, path).copy(arrival = t.plusSeconds(arrives * 60))
        // Area G's poles PX and PS: the detour calls at PX and rides on, the direct bus ends at PS.
        val complex = setOf("PX", "PS", "G", "E")
        val stopOf = mapOf("PX" to "G", "PS" to "G", "G" to "G")
        val detour = TripRoute(listOf(ride("bus", "A", "F", listOf("PX", "F"), 20), ride("red", "F", "E", listOf("E"), 40)))
        val direct = TripRoute(listOf(ride("bus", "A", "PS", listOf("PS"), 30)))
        assertEquals(listOf(detour, direct), withoutDetours(listOf(detour, direct), complex))
        assertEquals(listOf(direct), withoutDetours(listOf(detour, direct), complex, stopOf))
    }

    @Test
    fun `a ride through one destination stop to another passed the first`() {
        fun ride(line: String, from: String, to: String, path: List<String>, arrives: Long) =
            ride(line, from, to, path).copy(arrival = t.plusSeconds(arrives * 60))
        // Calls at C and ends at E, both the destination and nothing between: the rider could have
        // got off at C, which a route ending there reaches sooner.
        val stopOf = mapOf("C" to "C", "E" to "E")
        val through = TripRoute(listOf(ride("red", "A", "E", listOf("B", "C", "E"), 40)))
        val direct = TripRoute(listOf(ride("blue", "A", "C", listOf("C"), 30)))
        assertEquals(setOf("C"), through.passedAt(destination, stopOf))
        assertEquals(listOf(direct), withoutDetours(listOf(through, direct), destination, stopOf))
        // A pole and its pair at the end are one stop, and an id with no stop is another name for
        // the end: arriving, not passing.
        val poles = mapOf("PX" to "G", "G" to "G", "PS" to "G")
        assertEquals(emptySet<String>(), TripRoute(listOf(ride("bus", "A", "PS", listOf("B", "PX", "G"), 20))).passedAt(setOf("PX", "G", "PS"), poles))
        assertEquals(emptySet<String>(), through.passedAt(destination))
    }

    @Test
    fun `a detour still beats one that passed where it gets off`() {
        fun ride(line: String, from: String, to: String, path: List<String>, arrives: Long) =
            ride(line, from, to, path).copy(arrival = t.plusSeconds(arrives * 60))
        val complex = setOf("C", "E", "G")
        // A passes C for E at 40; B passes G for C at 30: B goes on, being quicker to where A passed.
        val a = TripRoute(listOf(ride("red", "A", "F", listOf("C", "F"), 30), ride("bus", "F", "E", listOf("E"), 40)))
        val b = TripRoute(listOf(ride("blue", "A", "H", listOf("G", "H"), 20), ride("bus", "H", "C", listOf("C"), 30)))
        assertEquals(listOf(b), withoutDetours(listOf(a, b), complex))
        // Two detours each passing where the other gets off, together: the first listed stays.
        val a2 = TripRoute(listOf(ride("red", "A", "F", listOf("C", "F"), 20), ride("bus", "F", "G", listOf("G"), 30)))
        assertEquals(listOf(a2), withoutDetours(listOf(a2, b), complex))
    }

    @Test
    fun `where a route gets off is only an end the Planner names`() {
        fun ride(line: String, from: String, to: String, path: List<String>, arrives: Long) =
            ride(line, from, to, path).copy(arrival = t.plusSeconds(arrives * 60))
        // A bus whose path stops at C, a call, before an end at P1 it leaves out: it doesn't get off at C.
        val leftOut = TripRoute(listOf(ride("bus", "A", "P1", listOf("B", "C"), 30)))
        val detour = TripRoute(listOf(ride("red", "A", "F", listOf("C", "F"), 30), ride("bus", "F", "E", listOf("E"), 40)))
        assertEquals(setOf("P1"), leftOut.endIds())
        assertEquals(listOf(detour, leftOut), withoutDetours(listOf(detour, leftOut), destination))
    }

    @Test
    fun `a route getting off where a detour passed is timed there, not at its walk's end`() {
        fun ride(line: String, from: String, to: String, path: List<String>, arrives: Long) =
            ride(line, from, to, path).copy(arrival = t.plusSeconds(arrives * 60))
        // The detour passes C for E at 40. The other gets off at C at 20 and walks to E by 45: the
        // rider could stop at C 20 minutes in, so it beats the detour.
        val detour = TripRoute(listOf(ride("red", "A", "F", listOf("C", "F"), 30), ride("bus", "F", "E", listOf("E"), 40)))
        val offAtC = TripRoute(listOf(ride("blue", "A", "C", listOf("C"), 20), walk("C", "E").copy(arrival = t.plusSeconds(45 * 60))))
        assertEquals(listOf(offAtC), withoutDetours(listOf(detour, offAtC), destination))
    }

    @Test
    fun `merged routes keep the first answer's order and add only the second's new routes`() {
        val quick = TripRoute(listOf(ride("red", "A", "C", listOf("B", "C"))))
        val change = TripRoute(listOf(ride("red", "A", "B"), ride("blue", "B", "C")))
        val oneBus = TripRoute(listOf(ride("bus", "A", "C")))
        assertEquals(listOf(quick, change, oneBus), mergedRoutes(listOf(quick, change), listOf(oneBus, quick)))
        // The same lines between the same stops at another time are another route.
        val later = TripRoute(listOf(ride("red", "A", "C", listOf("B", "C")).copy(departure = t.plusSeconds(300), arrival = t.plusSeconds(900))))
        assertEquals(listOf(quick, later), mergedRoutes(listOf(quick), listOf(later)))
        // Either answer alone is kept whole.
        assertEquals(listOf(oneBus), mergedRoutes(emptyList(), listOf(oneBus)))
        assertEquals(listOf(quick), mergedRoutes(listOf(quick), emptyList()))
    }
}
