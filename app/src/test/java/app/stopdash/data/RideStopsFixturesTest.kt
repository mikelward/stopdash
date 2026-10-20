package app.stopdash.data

import app.stopdash.domain.LegEnd
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripLeg
import app.stopdash.domain.lineStopNames
import app.stopdash.domain.rideStopList
import app.stopdash.domain.withRouteStops
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ride's stops under Show stops ([rideStopList]) from recorded Planner answers and a recorded route sequence (public
 * network data only), not hand-built legs: the Planner's own paths, a bus path named by stop areas, and a ride whose
 * stops come from its line's route (Codex, #968).
 */
class RideStopsFixturesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun rides(name: String): List<TripLeg> =
        json.decodeFromString<TflJourneyResultsDto>(checkNotNull(javaClass.getResource("/fixtures/$name")).readText())
            .toRoutes(Instant.parse("2026-09-26T19:00:00Z")).first().rides

    @Test
    fun `a planned tube ride lists every stop it calls at by name, from where it boards`() {
        val leg = rides("journey_results_kennington_to_archway.json").single()
        assertTrue(leg.path.isNotEmpty())
        val list = checkNotNull(rideStopList(leg, null, boarded = false))
        assertEquals(leg.path.size + 1, list.names.size)
        assertEquals("Kennington", list.names.first())
        assertEquals("Archway", list.names.last())
        assertEquals(0, list.here)
        assertTrue(list.names.none { it.isBlank() })
    }

    @Test
    fun `a ride with no stops listed takes them from its line's route, through where it gets off`() {
        // The Planner lists no stops for this ride (a third of the recorded rides are like it); the recorded Circle line
        // route, which runs the same stops between Euston Square and Barbican, lists them.
        val leg = rides("journey_results_euston_to_st_pauls_fewest.json").first()
        assertTrue(leg.path.isEmpty())
        val circle = json.decodeFromString<TflRouteSequenceDto>(
            checkNotNull(javaClass.getResource("/fixtures/route_sequence_circle.json")).readText(),
        ).toLineSequence()
        val ride = withRouteStops(leg, LegEnd.planned(leg, StepFree.ANY), circle)
        assertTrue(ride.path.isNotEmpty())
        val list = checkNotNull(rideStopList(ride, null, boarded = false, lineStopNames(circle)))
        assertEquals(listOf("Euston Square", "King's Cross St Pancras", "Farringdon", "Barbican"), list.names)
    }

    @Test
    fun `a bus path named by stop areas alone keeps where it boards and gets off, and wants the line's names`() {
        val leg = rides("journey_results_archway_to_london_bridge_bus.json").single { it.isBus }
        assertTrue(leg.path.isNotEmpty() && leg.pathNames.all { it.isBlank() })
        val list = checkNotNull(rideStopList(leg, null, boarded = false))
        assertEquals(listOf(leg.fromName, leg.toName), list.names)
        assertTrue(list.missing)
    }
}
