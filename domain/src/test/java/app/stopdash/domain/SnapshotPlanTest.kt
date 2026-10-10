package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotPlanTest {
    private val t0: Instant = Instant.parse("2026-09-20T08:00:00Z")

    private fun stop(id: String) = StopArrivals(stopId = id, stopName = "Stop $id", departures = emptyList(), fetchedAt = t0)

    @Test
    fun `keeps nearby stops and journey origins, classing an origin that isn't nearby as journey-only`() {
        val laid = SnapshotPlan.laidOut(
            stops = listOf(stop("A"), stop("B"), stop("C"), stop("D")),
            nearIds = setOf("A", "B", "E"),
            journeyIds = setOf("B", "C"),
            distances = mapOf("A" to 200.0, "B" to 100.0, "C" to 50.0),
        )
        // D is neither nearby nor an origin; C is an origin only; E was asked for and has no row.
        assertEquals(listOf("A", "B", "C"), laid.stops.map { it.stopId })
        assertEquals(setOf("C"), laid.journeyOnlyStopIds)
        assertEquals(setOf("E"), laid.missingStopIds)
        // Nearest first, the nearby stops only: C is nearer but isn't one the rider is near.
        assertEquals(listOf("B", "A"), laid.nearestFirst)
    }
}
