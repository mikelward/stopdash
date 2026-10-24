package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.RouteStop
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A stop tapped on a route page's list opens its details, from a list's route page and a trip's alike. A trip's
 * ([RouteDetailScreen]'s `ofTrip`) offers neither Go nor the ride there to star, as On the way's Show stops doesn't,
 * since either would stand in for the trip itself. Well-known stations; no user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class RouteDetailOpenStopTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-18T08:00:00Z")
    private val row = DepartureRows.across(
        listOf(
            StopArrivals(
                "940GZZLUEUS", "Euston",
                listOf(Departure("victoria", "Victoria", "inbound", "Walthamstow Central", "Northbound - Platform 6", now.plusSeconds(180), "tube")),
                fetchedAt = now,
            ),
        ),
        now,
    ).first { it.upcoming.isNotEmpty() }
    private val stops = RouteStopsUi.Loaded(
        listOf(RouteStop("940GZZLUEUS", "Euston"), RouteStop("940GZZLUKSX", "King's Cross St. Pancras"), RouteStop("940GZZLUHAI", "Highbury & Islington")),
    )

    private fun tapKingsCross(ofTrip: Boolean): RouteStopOpen {
        val opened = mutableListOf<RouteStopOpen>()
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row, isStarred = false, starrable = false, disruptionUnknown = false, stale = false, now = now,
                    onToggleStar = {}, onBack = {}, routeStops = stops, onOpenStop = { opened += it }, ofTrip = ofTrip,
                )
            }
        }
        composeRule.onNodeWithText("King's Cross St. Pancras").performClick()
        return composeRule.runOnIdle { opened.single() }
    }

    @Test
    fun a_lists_route_page_opens_a_stop_with_go_and_its_ride() {
        val kingsCross = tapKingsCross(ofTrip = false)
        assertEquals("940GZZLUKSX", kingsCross.stopId)
        assertNotNull(kingsCross.go)
        assertNotNull(kingsCross.journey)
    }

    @Test
    fun a_trips_route_page_opens_a_stop_with_neither() {
        val kingsCross = tapKingsCross(ofTrip = true)
        assertEquals("940GZZLUKSX", kingsCross.stopId)
        assertEquals("Euston", kingsCross.fromName)
        assertEquals(null, kingsCross.go)
        assertEquals(null, kingsCross.journey)
    }
}
