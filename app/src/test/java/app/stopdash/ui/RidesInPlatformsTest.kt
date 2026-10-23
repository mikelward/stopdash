package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRef
import app.stopdash.domain.RideGrouping
import app.stopdash.domain.RouteFocus
import app.stopdash.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Grouped by platform with Group favorite rides By station, a ride's trains stay in their platform's group ([shownRowsOf]):
 * tapped there, a row opens its route page as its ride's own card would, with the ride's far end ([openAsRide]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class RidesInPlatformsTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")
    private val stopId = "940GZZLUKSX"

    private fun train(line: String, name: String, to: String, platform: String, minutes: Long) =
        Departure(line, name, "outbound", to, platform, now.plusSeconds(minutes * 60), "tube")

    @Test
    fun `a ride's train kept in its platform opens with the ride's far end, any other row as usual`() {
        val stop = StopArrivals(
            stopId, "King's Cross St. Pancras",
            listOf(train("victoria", "Victoria", "Brixton", "Southbound - Platform 5", 3), train("piccadilly", "Piccadilly", "Cockfosters", "Eastbound - Platform 6", 4)),
            fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("piccadilly", "Piccadilly", "tube")),
        )
        val built = listRowsOf(
            DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = now, determinedLineIds = setOf("victoria", "piccadilly")),
            now,
            stopDistanceMeters = mapOf(stopId to 100.0),
            dismissed = emptySet(),
            hiddenModes = emptySet(),
            alertSequences = emptyMap(),
            journeyDestinationStops = emptyList(),
        )
        val victoria = built.forCards.single { it.lineId == "victoria" }
        val journey = FavoriteJourney(JourneyEnd(stopId, "King's Cross St. Pancras"), JourneyEnd("940GZZLUWRR", "Warren Street"), "victoria", mode = "tube")
        val card = JourneyCard(journey, JourneyCardState.Trains(listOf(victoria)), boardingIds = listOf(stopId), destinationIds = setOf("940GZZLUWRR"))
        val shown = shownRowsOf(
            built, built.marked.rows, listOf(victoria), starred = emptySet(), stopDistanceMeters = mapOf(stopId to 100.0),
            cards = listOf(card), journeys = listOf(journey), rideGrouping = RideGrouping.STATION,
        )
        assertEquals(setOf(journey.key), shown.ridesInPlatforms)
        val listedVictoria = shown.rows.single { it.lineId == "victoria" }
        val listedPiccadilly = shown.rows.single { it.lineId == "piccadilly" }
        assertEquals(setOf("940GZZLUWRR"), shown.rideEnds[listedVictoria])
        assertTrue(listedPiccadilly !in shown.rideEnds.keys)

        val opened = mutableListOf<Pair<String, Set<String>?>>()
        val open = openAsRide(shown.rideEnds, { row, _ -> opened += row.lineId to null }, { row, _, ends -> opened += row.lineId to ends })
        open(listedVictoria, null as RouteFocus?)
        open(listedPiccadilly, null)
        assertEquals(listOf("victoria" to setOf("940GZZLUWRR"), "piccadilly" to null), opened)
    }

    @Test
    fun `with no ride kept in a platform, a tap opens as usual, the same callback`() {
        val plain: (DepartureRow, RouteFocus?) -> Unit = { _, _ -> }
        assertTrue(openAsRide(emptyMap(), plain) { _, _, _ -> } === plain)
    }
}
