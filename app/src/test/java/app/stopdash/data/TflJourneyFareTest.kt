package app.stopdash.data

import app.stopdash.domain.TripFare
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A route's fare, from recorded Journey Planner answers between well-known stations. */
class TflJourneyFareTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-10-09T10:00:00Z")

    private fun routes(fixture: String) =
        json.decodeFromString<TflJourneyResultsDto>(checkNotNull(javaClass.getResource("/fixtures/$fixture")).readText()).toRoutes(now)

    @Test
    fun `a tube route carries the Planner's fare and its level`() {
        val routes = routes("journey_results_kings_cross_to_waterloo.json")
        assertTrue("the fixture's routes were read", routes.isNotEmpty())
        routes.forEach { route ->
            assertEquals(TripFare(pence = 300, level = TripFare.Level.OFF_PEAK, pinkReader = false), route.fare)
        }
    }

    @Test
    fun `the pink reader warning rides with the routes it's given for, and only those`() {
        val routes = routes("journey_results_waterloo_to_gatwick.json")
        assertEquals(3, routes.size)
        val fares = routes.map { checkNotNull(it.fare) }
        assertEquals(listOf(1350, 1070, 1070), fares.map { it.pence })
        assertEquals(listOf(false, true, true), fares.map { it.pinkReader })
    }

    @Test
    fun `a journey the Planner didn't price has no fare`() {
        val dto = TflJourneyFareDto(totalCost = null)
        assertNull(dto.toFareOrNull())
        assertNull(TflJourneyDto(legs = emptyList(), fare = null).fare?.toFareOrNull())
    }

    @Test
    fun `a fare of nothing is no fare`() {
        assertNull(TflJourneyFareDto(totalCost = 0).toFareOrNull())
    }

    @Test
    fun `a level only where every part is charged at it`() {
        fun level(vararg levels: String) = TflJourneyFareDto(totalCost = 450, fares = levels.map { TflFareDto(it) }).toFareOrNull()?.level
        assertEquals(TripFare.Level.PEAK, level("Peak"))
        assertEquals(TripFare.Level.OFF_PEAK, level("Off Peak", "off-peak"))
        // A bus part has none, so the route as a whole has none to name.
        assertNull(level("Off Peak", ""))
        assertNull(level("Peak", "Off Peak"))
        assertNull(level())
    }

    @Test
    fun `a bus fare has no level and no reader`() {
        val fare = checkNotNull(TflJourneyFareDto(totalCost = 175, fares = listOf(TflFareDto(""))).toFareOrNull())
        assertEquals(175, fare.pence)
        assertNull(fare.level)
        assertFalse(fare.pinkReader)
    }
}
