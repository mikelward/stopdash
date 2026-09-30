package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and lines only. */
class BusPlacementTest {
    private fun at(minutes: Long) = Instant.parse("2026-09-26T08:00:00Z").plus(Duration.ofMinutes(minutes))

    // Bus 1 both ways along a road: the stop pair BG's poles Bn (northbound) and Bs (southbound).
    private val road = LineSequence(
        routes = listOf(
            LineRoute("North", listOf("As", "Bn", "Xn", "Cn")),
            LineRoute("South", listOf("Cs", "Xs", "Bs", "Ad")),
        ),
        stopNames = mapOf("As" to "A", "Bn" to "B", "Xn" to "X", "Cn" to "C", "Cs" to "C", "Xs" to "X", "Bs" to "B", "Ad" to "A"),
        stopAreas = mapOf("Bn" to "BG", "Bs" to "BG", "Xn" to "XG", "Xs" to "XG", "Cn" to "CG", "Cs" to "CG"),
    )

    // The Planner rides north from BG to CG by way of XG, naming the southbound pole of each pair.
    private val plannerBus = TripLeg(
        "bus", "1", "1", "Bs", "B", "Cs", "C", at(20), at(30), path = listOf("XG", "CG"), fromArea = "BG", toArea = "CG",
    )

    // The Planner boards the northbound bus at a bus station's stand As2, which the line doesn't use
    // (its own stand there is As; neither is in a pair), and gets off at the pair CG.
    private val atStation = road.copy(stopNames = road.stopNames + ("As2" to "A"))
    private val fromStand = plannerBus.copy(fromId = "As2", fromName = "A", fromArea = "", path = listOf("BG", "XG", "CG"))

    @Test
    fun `a bus leg boards at the pole its bus uses, not the other side the Planner named`() {
        val placed = onPoles(plannerBus, mapOf("1" to road))
        assertEquals("Bn", placed.fromId)
        assertEquals("Cn", placed.toId)
        // Its route not in yet, or failed: as the Planner named it.
        assertEquals(plannerBus, onPoles(plannerBus, emptyMap()))
        assertEquals(plannerBus, onPoles(plannerBus, mapOf("1" to null)))
        // Known by its stop pairs, so its keys hold once its poles are known.
        assertEquals(boardingKey(plannerBus), boardingKey(placed))
        assertEquals(alightingKey(plannerBus), alightingKey(placed))
    }

    @Test
    fun `a bus leg is placed on its poles only once its route gives a single answer`() {
        assertTrue(placedOnPoles(plannerBus, mapOf("1" to road)))
        // Its route not in yet, or failed: another pole of the pair may be the one it uses.
        assertFalse(placedOnPoles(plannerBus, emptyMap()))
        assertFalse(placedOnPoles(plannerBus, mapOf("1" to null)))
        // Loaded, but running both ways between the pairs: no single answer, so still not placed.
        val both = road.copy(routes = road.routes + LineRoute("North again", listOf("As", "Bs", "Xn", "Cs")))
        assertEquals(plannerBus, onPoles(plannerBus, mapOf("1" to both)))
        assertFalse(placedOnPoles(plannerBus, mapOf("1" to both)))
        // A ride named by no pair needs no placing.
        val tube = TripLeg("tube", "red", "red", "A", "A", "B", "B", at(5), at(15))
        assertTrue(placedOnPoles(tube, emptyMap()))
        // Where the route ends on foot, no bus is placed there.
        val walk = TripLeg(TripLeg.WALKING, "", "", "Cs", "Cs", "E", "E", at(30), at(35), toArea = "EG")
        val walkOn = TripRoute(listOf(plannerBus, walk))
        assertEquals("E", endPole(walkOn, TripClosures.End("E", "EG"), emptyMap()))
        assertNull(endPole(walkOn, TripClosures.End("Bs", "BG", "1"), mapOf("1" to both)))
        // Placed, at the pole the bus uses rather than the one the Planner named.
        assertEquals("Bn", endPole(walkOn, TripClosures.End("Bs", "BG", "1"), mapOf("1" to road)))
        assertEquals("Cn", endPole(walkOn, TripClosures.End("Cs", "CG", "1"), mapOf("1" to road)))
    }

    @Test
    fun `a bus leg to a stop in no pair gets off at the route's stop of that name`() {
        // The Planner rides north to a bus station's stand Ds, which only the southbound route uses;
        // the northbound route's own stand there is Dn. Neither is in a pair.
        val station = road.copy(
            routes = listOf(LineRoute("North", listOf("As", "Bn", "Xn", "Dn")), LineRoute("South", listOf("Ds", "Xs", "Bs", "Ad"))),
            stopNames = road.stopNames + mapOf("Dn" to "D", "Ds" to "D"),
        )
        val toStation = plannerBus.copy(toId = "Ds", toName = "D", toArea = "", path = listOf("XG", "Ds"))
        val placed = onPoles(toStation, mapOf("1" to station))
        assertEquals("Bn", placed.fromId)
        assertEquals("Dn", placed.toId)
        // Known by the stand the Planner named, so its keys hold once its stops are known.
        assertEquals("Ds", placed.plannedToId)
        assertEquals(alightingKey(toStation), alightingKey(placed))
        // A route calling at the Planner's own stop gets off there, not at an earlier stop of its name.
        val twice = station.copy(routes = listOf(LineRoute("North", listOf("As", "Bn", "Xn", "Dn", "Ds"))))
        assertEquals("Ds", onPoles(toStation, mapOf("1" to twice)).toId)
        // Two stops of the name along the route, and not the Planner's own: no single answer, as named.
        val loop = station.copy(routes = listOf(LineRoute("North", listOf("As", "Bn", "Xn", "Dn", "Yn", "Dx"))))
        assertEquals(toStation, onPoles(toStation, mapOf("1" to loop.copy(stopNames = loop.stopNames + ("Dx" to "D")))))
        // A stop of another name is no match: as the Planner named it.
        assertEquals(toStation.copy(toName = "E"), onPoles(toStation.copy(toName = "E"), mapOf("1" to station)))
    }

    @Test
    fun `a bus leg from a stand the line doesn't use boards at the route's stand of that name`() {
        val placed = onPoles(fromStand, mapOf("1" to atStation))
        assertEquals("As", placed.fromId)
        assertEquals("Cn", placed.toId)
        assertTrue(placedOnPoles(fromStand, mapOf("1" to atStation)))
        assertEquals("As", endPole(TripRoute(listOf(fromStand)), TripClosures.End("As2", lineId = "1"), mapOf("1" to atStation)))
        // Handed to the trip to fetch; a pair's poles are looked up by the trip itself.
        assertEquals(setOf(PlacedStand("1", "As2", "As")), placedStands(listOf(TripRoute(listOf(fromStand))), mapOf("1" to atStation)))
        assertEquals(emptySet<PlacedStand>(), placedStands(listOf(TripRoute(listOf(plannerBus))), mapOf("1" to road)))
        // A route calling at the Planner's own stand boards there.
        val calls = atStation.copy(routes = listOf(LineRoute("North", listOf("As2", "As", "Bn", "Xn", "Cn"))))
        assertEquals("As2", onPoles(fromStand, mapOf("1" to calls)).fromId)
        assertEquals(emptySet<PlacedStand>(), placedStands(listOf(TripRoute(listOf(fromStand))), mapOf("1" to calls)))
        // Two stands of the name, neither the Planner's: no single answer, so not placed.
        val two = atStation.copy(
            routes = listOf(LineRoute("North", listOf("As", "Bn", "Xn", "Cn")), LineRoute("Short", listOf("As3", "Bn", "Xn", "Cn"))),
            stopNames = atStation.stopNames + ("As3" to "A"),
        )
        assertEquals(fromStand, onPoles(fromStand, mapOf("1" to two)))
        assertFalse(placedOnPoles(fromStand, mapOf("1" to two)))
        // A stand of another name is no match.
        assertFalse(placedOnPoles(fromStand.copy(fromName = "E"), mapOf("1" to atStation)))
    }

    @Test
    fun `a moved leg is known by the stop the Planner named`() {
        // A moved leg keeps the stand the Planner named, however often it's placed, so its keys hold.
        val placed = onPoles(fromStand, mapOf("1" to atStation))
        assertEquals("As2", placed.plannedFromId)
        assertEquals(placed, onPoles(placed, mapOf("1" to atStation)))
        assertEquals(boardingKey(fromStand), boardingKey(placed))
        // Not by the stop's name: two stands of one bus station, or two blank names, keep their own.
        assertNotEquals(boardingKey(fromStand), boardingKey(fromStand.copy(fromId = "As3")))
        val blank = fromStand.copy(fromName = "")
        assertNotEquals(boardingKey(blank), boardingKey(blank.copy(fromId = "As3")))
        val offAt = fromStand.copy(toArea = "", toId = "Cs", toName = "C")
        assertNotEquals(alightingKey(offAt), alightingKey(offAt.copy(toId = "Cx")))
    }

    @Test
    fun `a moved stand is where the route says it is`() {
        val placed = onPoles(fromStand.copy(fromAt = Coordinates(51.5, -0.1)), mapOf("1" to atStation.copy(stopPositions = mapOf("As" to (51.6 to -0.2)))))
        assertEquals("As", placed.fromId)
        // The trip walks the rider to the stand the bus uses, not the one the Planner named.
        assertEquals(Coordinates(51.6, -0.2), placed.fromAt)
        // Where the route gives no position, the Planner's stands in.
        assertEquals(Coordinates(51.5, -0.1), onPoles(fromStand.copy(fromAt = Coordinates(51.5, -0.1)), mapOf("1" to atStation)).fromAt)
    }

    @Test
    fun `a bus between two bus stations boards at the route's stand too`() {
        // The Planner names stand As2 at one bus station and Cs at the other; neither is in a pair.
        val stands = fromStand.copy(toArea = "", path = listOf("BG", "XG", "Cn"))
        val placed = onPoles(stands, mapOf("1" to atStation))
        assertEquals("As", placed.fromId)
        assertEquals("Cn", placed.toId)
        // Not vouched for at the Planner's stands until its route says where it stands, as at a stop
        // pair: the Planner can name a stand the line doesn't use.
        assertFalse(placedOnPoles(stands, emptyMap()))
        assertFalse(placedOnPoles(stands, mapOf("1" to null)))
        assertTrue(placedOnPoles(stands, mapOf("1" to atStation)))
        // A train's stations are never placed by name, so they need no route.
        assertTrue(placedOnPoles(stands.copy(mode = "tube"), emptyMap()))
        assertEquals(setOf(PlacedStand("1", "As2", "As")), placedStands(listOf(TripRoute(listOf(stands))), mapOf("1" to atStation)))
        assertEquals(boardingKey(stands), boardingKey(placed))
        assertEquals(alightingKey(stands), alightingKey(placed))
        // Settled once it's moved, not while its route says it's elsewhere.
        assertFalse(busesSettled(TripRoute(listOf(stands)), mapOf("1" to atStation)))
        assertTrue(busesSettled(TripRoute(listOf(placed)), mapOf("1" to atStation)))
        // Nor before its route says where its bus stands (loading, or failed): the Planner's stand may
        // be one the line doesn't use, and a started trip keeps it.
        assertFalse(busesSettled(TripRoute(listOf(stands)), emptyMap()))
        assertFalse(busesSettled(TripRoute(listOf(stands)), mapOf("1" to null)))
        // A route that doesn't place it (no single stand of its name) leaves it where the Planner put it.
        assertTrue(busesSettled(TripRoute(listOf(stands.copy(fromName = "E"))), mapOf("1" to atStation)))
        // Only a bus: a train's stations are never moved by name.
        val train = stands.copy(mode = "tube")
        assertEquals(train, onPoles(train, mapOf("1" to atStation)))
    }

    @Test
    fun `a route's buses are settled only once each is at the poles its route says`() {
        val tube = TripLeg("tube", "blue", "blue", "B", "B", "C", "C", at(20), at(30), path = listOf("C"))
        val bus = TripLeg("bus", "1", "1", "B", "B", "C", "C", at(20), at(30), path = listOf("C"), fromArea = "490G0000B")
        val routes = LineSequence(routes = listOf(LineRoute("B ↔ C", listOf("B", "C"))), stopNames = mapOf("B" to "B", "C" to "C"))
        // A train needs no placing.
        assertTrue(busesSettled(TripRoute(listOf(tube)), emptyMap()))
        // A bus named by its stop pair, its route not loaded (or failed): the pole may be the wrong side.
        assertFalse(busesSettled(TripRoute(listOf(bus)), emptyMap()))
        assertFalse(busesSettled(TripRoute(listOf(bus)), mapOf("1" to null)))
        assertTrue(busesSettled(TripRoute(listOf(bus)), mapOf("1" to routes)))
        // The route loaded, but its bus uses the pair's other pole, not yet looked up and placed:
        // the Planner's pole may be the wrong side of the road.
        val otherSide = LineSequence(
            routes = listOf(LineRoute("B2 ↔ C", listOf("B2", "C"))),
            stopNames = mapOf("B" to "B", "B2" to "B", "C" to "C"),
            stopAreas = mapOf("B" to "490G0000B", "B2" to "490G0000B"),
        )
        assertFalse(busesSettled(TripRoute(listOf(bus)), mapOf("1" to otherSide)))
        assertTrue(busesSettled(TripRoute(listOf(bus.copy(fromId = "B2"))), mapOf("1" to otherSide)))
        // Boarding at a stand in no pair, alighting at a roadside pair: its alighting pole matters too.
        val toPair = TripLeg("bus", "1", "1", "S", "S", "C", "C", at(20), at(30), toArea = "490G0000C")
        val farSide = LineSequence(
            routes = listOf(LineRoute("S ↔ C2", listOf("S", "C2"))),
            stopNames = mapOf("S" to "S", "C" to "C", "C2" to "C"),
            stopAreas = mapOf("C" to "490G0000C", "C2" to "490G0000C"),
        )
        assertFalse(busesSettled(TripRoute(listOf(toPair)), emptyMap()))
        assertFalse(busesSettled(TripRoute(listOf(toPair)), mapOf("1" to farSide)))
        assertTrue(busesSettled(TripRoute(listOf(toPair.copy(toId = "C2"))), mapOf("1" to farSide)))
    }
}
