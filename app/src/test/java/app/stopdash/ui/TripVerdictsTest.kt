package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A trip leg's trains are judged on their line's route ahead, off the main thread, and only read as
 * the page renders ([TripVerdicts]). Synthetic lines and stops only.
 */
class TripVerdictsTest {
    private val now = Instant.parse("2026-10-03T08:00:00Z")

    private val names = listOf("Aston", "Beck", "Mead", "Cole", "Dale", "Ely").associateWith { it }

    // Blue's other branch leaves at Beck for Ely: its trains reach Beck but never Cole.
    private fun blue() = LineSequence(
        listOf(
            LineRoute("to Dale", listOf("Aston", "Beck", "Mead", "Cole", "Dale")),
            LineRoute("to Ely", listOf("Aston", "Beck", "Ely")),
        ),
        names,
    )

    private val leg = TripLeg(
        "tube", "blue", "Blue", "Aston", "Aston", "Cole", "Cole", now, now.plus(Duration.ofMinutes(9)),
        path = listOf("Beck", "Mead", "Cole"),
    )

    private fun train(destination: String, inMinutes: Long) = Departure(
        lineId = "blue", lineName = "Blue", direction = "outbound", destination = destination, platform = null,
        expectedArrival = now.plus(Duration.ofMinutes(inMinutes)), mode = "tube",
    )

    @Test
    fun `a train is judged only once warmed, and its verdict holds for later predictions of the service`() {
        val route = blue()
        val toDale = train("Dale", 2)
        assertNull(TripVerdicts.get(leg, route, toDale))

        assertTrue(TripVerdicts.warm(leg, route, listOf(toDale, train("Ely", 4))))
        val verdict = TripVerdicts.get(leg, route, toDale)!!
        assertEquals(DirectTrips.Verdict.Reaches, verdict.reach)
        assertEquals(true, verdict.leaves)
        assertEquals(DirectTrips.Verdict.Misses, TripVerdicts.get(leg, route, train("Ely", 4))?.reach)

        // The same service predicted later, on another vehicle: already judged.
        val later = toDale.copy(expectedArrival = now.plus(Duration.ofMinutes(1)), vehicleId = "123")
        assertEquals(DirectTrips.Verdict.Reaches, TripVerdicts.get(leg, route, later)?.reach)
        assertFalse(TripVerdicts.warm(leg, route, listOf(later)))
    }

    @Test
    fun `a new load of the line's route is judged afresh`() {
        val toDale = train("Dale", 2)
        TripVerdicts.warm(leg, blue(), listOf(toDale))
        assertNull(TripVerdicts.get(leg, blue(), toDale))
    }

    @Test
    fun `a superseded warm-up never puts an older route's verdicts in place of a newer one's`() {
        val toDale = train("Dale", 2)
        val newer = blue()
        TripVerdicts.warm(leg, newer, listOf(toDale))
        // The warm-up for the route before, canceled but finishing late.
        assertFalse(TripVerdicts.warm(leg, blue(), listOf(toDale)) { false })
        assertEquals(DirectTrips.Verdict.Reaches, TripVerdicts.get(leg, newer, toDale)?.reach)
    }

    @Test
    fun `a warm-up with the cache full keeps every leg it judged`() {
        val route = blue()
        val toDale = train("Dale", 2)
        // Full to the limit with other legs' verdicts.
        repeat(TripVerdicts.MAX_LEGS) { TripVerdicts.warm(leg.copy(toId = "Full $it"), route, listOf(toDale)) }
        val beck = leg.copy(toId = "Beck", toName = "Beck", path = listOf("Beck"))
        val state = TripViewModel.State(
            routes = listOf(TripRoute(listOf(leg)), TripRoute(listOf(beck))),
            plannedAt = now,
            live = mapOf("Aston" to TripViewModel.StopLive(listOf(toDale), now)),
        )
        assertTrue(warmVerdicts(state, mapOf("blue" to route), emptyMap()))
        assertEquals(DirectTrips.Verdict.Reaches, TripVerdicts.get(leg, route, toDale)?.reach)
        assertEquals(DirectTrips.Verdict.Reaches, TripVerdicts.get(beck, route, toDale)?.reach)
    }

    @Test
    fun `a superseded warm-up never clears the verdicts to make room`() {
        val route = blue()
        val toDale = train("Dale", 2)
        repeat(TripVerdicts.MAX_LEGS) { TripVerdicts.warm(leg.copy(toId = "Room $it"), route, listOf(toDale)) }
        TripVerdicts.warm(leg, route, listOf(toDale))
        TripVerdicts.makeRoom(listOf(leg.copy(toId = "Another")), active = { false })
        assertEquals(DirectTrips.Verdict.Reaches, TripVerdicts.get(leg, route, toDale)?.reach)
    }

    @Test
    fun `the page reads verdicts, never works them out, and an unjudged train reads as still checking`() {
        val route = blue()
        val state = TripViewModel.State(
            routes = listOf(TripRoute(listOf(leg))),
            plannedAt = now,
            live = mapOf("Aston" to TripViewModel.StopLive(listOf(train("Dale", 2), train("Ely", 4)), now)),
        )
        val sequences = mapOf("blue" to route)

        assertEquals(emptyList<Departure>(), legTrains(state, leg, now, sequences))
        val shown = tripEstimates(state, now, Duration.ZERO, sequences).orEmpty()
        assertEquals(TripMessage.CHECKING, tripCheckState(state, shown, now, sequences))

        assertTrue(warmVerdicts(state, sequences, emptyMap()))
        assertEquals(listOf("Dale"), legTrains(state, leg, now, sequences)?.map { it.destination })
        assertNull(tripCheckState(state, tripEstimates(state, now, Duration.ZERO, sequences).orEmpty(), now, sequences))
    }
}
