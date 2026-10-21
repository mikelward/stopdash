package app.stopdash.ui

import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic stops and lines only. */
class RidePageTest {
    // Bus 1 both ways along a road: the stop pairs BG and CG, each a pole per side.
    private val road = LineSequence(
        routes = listOf(
            LineRoute("North", listOf("As", "Bn", "Xn", "Cn")),
            LineRoute("South", listOf("Cs", "Xs", "Bs", "Ad")),
        ),
        stopNames = mapOf("As" to "A", "Bn" to "B", "Xn" to "X", "Cn" to "C", "Cs" to "C", "Xs" to "X", "Bs" to "B", "Ad" to "A"),
        stopAreas = mapOf("Bn" to "BG", "Bs" to "BG", "Xn" to "XG", "Xs" to "XG", "Cn" to "CG", "Cs" to "CG"),
    )

    private fun repository(answer: (String) -> LineSequence) = RouteStopsRepository(
        object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence = answer(direction)
        },
    )

    // A ride saved southbound, from C to B, now shown northbound: its far end is the pole it was saved at.
    private val ride = TripLeg("bus", "1", "1", "Bn", "B", "Cs", "C", Instant.EPOCH, Instant.EPOCH, fromArea = "BG", toArea = "CG")

    @Test
    fun `a ride's far end is placed on the pole its bus gets off at, before its timetable is asked for`() = runBlocking {
        val routes = repository { direction -> if (direction == "outbound") road else LineSequence(emptyList(), emptyMap()) }
        assertEquals("Cn", placedRide(ride, routes).toId)
        assertEquals("Bn", placedRide(ride, routes).fromId)
    }

    @Test
    fun `a ride whose route can't be had keeps its saved stops`() = runBlocking {
        assertEquals(ride, placedRide(ride, null))
        assertEquals(ride, placedRide(ride, repository { throw TflException.Offline(null) }))
    }

    @Test
    fun `a timetabled train on or after a day its line's planned work starts is withheld`() {
        // 05:41 BST on Tuesday 2026-10-20.
        val train = Instant.parse("2026-10-20T04:41:00Z")
        assertEquals(train, departureBefore(train, emptyList()))
        assertEquals(train, departureBefore(train, listOf(java.time.LocalDate.of(2026, 10, 21))))
        assertEquals(null, departureBefore(train, listOf(java.time.LocalDate.of(2026, 10, 20))))
        assertEquals(null, departureBefore(train, listOf(java.time.LocalDate.of(2026, 10, 19))))
    }
}
