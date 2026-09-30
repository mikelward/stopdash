package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic stops and lines only. */
class FinalStopTest {
    private fun at(minutes: Long): Instant = Instant.parse("2026-09-30T15:30:00Z").plus(Duration.ofMinutes(minutes))

    private fun ride(mode: String, line: String, from: String, to: String, departs: Long, arrives: Long, toArea: String = "") = TripLeg(
        mode = mode, lineId = line, lineName = line, fromId = from, fromName = from, toId = to, toName = to,
        departure = at(departs), arrival = at(arrives), path = listOf(to), toArea = toArea,
    )

    // A walk; to a place when [to] is blank, as TfL routes to a coordinate.
    private fun walk(from: String, to: String, departs: Long, arrives: Long) = TripLeg(
        mode = TripLeg.WALKING, lineId = "", lineName = "", fromId = from, fromName = from, toId = to,
        toName = to.ifEmpty { "Home" }, departure = at(departs), arrival = at(arrives),
    )

    // The fastest to the place: a train, then a bus to the stop pair P (pole P1), then a walk on.
    private val fastest = TripRoute(
        listOf(
            walk("", "T1", 0, 10),
            ride("tube", "red", "T1", "T2", 12, 20),
            walk("T2", "Q1", 20, 23),
            ride("bus", "7", "Q1", "P1", 25, 30, toArea = "P"),
            walk("P1", "", 30, 34),
        ),
    )

    // The Planner's fewest changes to the place: the train and a long walk, arriving later.
    private val longWalk = TripRoute(listOf(walk("", "T1", 0, 10), ride("tube", "red", "T1", "T2", 12, 20), walk("T2", "", 20, 38)))

    @Test
    fun `plans via where the fastest route gets off its last ride, by the stop pair`() {
        assertEquals("P", FinalStop.of(listOf(longWalk, fastest))?.stopId)
        // By the stop itself where it has no pair (a station).
        val byTrain = TripRoute(fastest.legs.dropLast(2) + ride("tube", "blue", "Q1", "S1", 25, 30) + walk("S1", "", 30, 34))
        assertEquals("S1", FinalStop.of(listOf(byTrain))?.stopId)
    }

    @Test
    fun `asks nothing more when the fastest route rides once`() {
        // Arriving first, the train and a short walk: nothing rides fewer times and still rides.
        val oneRide = TripRoute(listOf(walk("", "T1", 0, 10), ride("tube", "red", "T1", "T2", 12, 20), walk("T2", "", 20, 25)))
        assertNull(FinalStop.of(listOf(fastest, oneRide)))
        assertNull(FinalStop.of(emptyList()))
    }

    @Test
    fun `asks nothing more when the fastest route ends on its ride or at no named stop`() {
        // No walk on: a trip to a stop, which the Planner's fewest changes already plans to.
        assertNull(FinalStop.of(listOf(TripRoute(fastest.legs.dropLast(1)))))
        val unnamed = TripRoute(fastest.legs.dropLast(2) + ride("bus", "7", "Q1", "", 25, 30) + walk("", "", 30, 34))
        assertNull(FinalStop.of(listOf(unnamed)))
    }

    @Test
    fun `keeps the Planner's routes via the stop that ride fewer times, as it gave them`() {
        val stop = checkNotNull(FinalStop.of(listOf(fastest)))
        // The one bus the whole way from a stop a short walk off, and the Planner's own walk on.
        val direct = TripRoute(listOf(walk("", "B1", 0, 5), ride("bus", "7", "B1", "P1", 8, 33, toArea = "P"), walk("P1", "", 33, 37)))
        // Riding as often as the fastest adds nothing; nor does a walk the whole way.
        val twice = TripRoute(listOf(ride("tube", "red", "T1", "T2", 12, 20), ride("bus", "7", "T2", "P1", 22, 31, toArea = "P"), walk("P1", "", 31, 35)))
        val walking = TripRoute(listOf(walk("", "P1", 0, 40), walk("P1", "", 40, 44)))
        assertEquals(listOf(direct), stop.fewerRides(listOf(direct, twice, walking)))
    }
}
