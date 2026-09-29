package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.ui.theme.StopDashTheme
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Send bug report" is in the overflow on every screen (maintainer, 2026-09-29), so a problem is
 * reported from where it's seen and the report's screenshot shows it. Each screen here is composed
 * as the activity hosts it, under [LocalAppMenu], and its menu sends the report. Stations and times
 * are well-known or made up; no user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
// Real text measurement, so the large-text checks lay out as a device would.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppMenuOverflowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")
    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

    private var reports = 0
    private var licensesOpened = false

    private fun show(fontScale: Float? = null, content: @Composable () -> Unit) {
        val menu = AppMenuActions(
            updateAvailable = false,
            onOpenAppListing = {},
            onSendBugReport = { reports++ },
            onOpenLicenses = { licensesOpened = true },
        )
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale ?: base.fontScale)) {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalAppMenu provides menu) { content() }
                }
            }
        }
    }

    // The overflow opens, and its "Send bug report" asks for the report exactly once.
    private fun sendsABugReport() {
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Send bug report").performClick()
        composeRule.runOnIdle { assertEquals(1, reports) }
    }

    // At about the largest text offered (160% of a 200% system size), a long title wraps rather
    // than squeeze the header's actions: Back stays on one line, the overflow at its full size.
    private fun headerKeepsBackAndMenu(back: String = "Back") {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(back, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals("$back wrapped", 1, layouts.single().lineCount)
        val more = composeRule.onNodeWithContentDescription("More options").assertIsDisplayed().getUnclippedBoundsInRoot()
        // Material 3's icon button is 40dp, within its 48dp touch target.
        assertTrue("overflow squeezed", more.right - more.left >= 40.dp)
        sendsABugReport()
    }

    @Test
    fun on_the_way_sends_a_bug_report() {
        val bus = TripLeg("bus", "9", "9", "490000001A", "Stop A", "490000002B", "Stop B", at(4), at(20))
        val trip = ActiveTrip(TripRoute(listOf(bus)), "Stop B", startedAt = now, vehicleId = "EXAMPLE")
        show { OnTheWayScreen(trip, TripProgress.Waiting(bus, at(4)), failed = false, now = now, onEnd = {}, onBack = {}) }
        sendsABugReport()
    }

    @Test
    fun on_the_way_arrived_still_sends_a_bug_report() {
        show { OnTheWayScreen(trip = null, progress = TripProgress.Arrived, failed = false, now = now, onEnd = {}, onBack = {}) }
        sendsABugReport()
    }

    @Test
    fun on_the_way_about_opens_the_licenses() {
        show { OnTheWayScreen(trip = null, progress = TripProgress.Arrived, failed = false, now = now, onEnd = {}, onBack = {}) }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("About").performClick()
        composeRule.onNodeWithText("Open source licenses").performClick()
        composeRule.runOnIdle { assertTrue(licensesOpened) }
    }

    @Test
    fun a_routes_page_sends_a_bug_report() {
        val stop = StopArrivals(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = listOf(Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, at(2), "tube")),
            fetchedAt = now,
        )
        val row = DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
        show {
            RouteDetailScreen(
                row = row,
                isStarred = false,
                starrable = true,
                disruptionUnknown = false,
                stale = false,
                now = now,
                onToggleStar = {},
                onBack = {},
                routeStops = RouteStopsUi.Loading,
            )
        }
        sendsABugReport()
    }

    @Test
    fun a_stations_page_sends_a_bug_report() {
        show { MainScreen(DeparturesUiState.Loaded(emptyList(), now), now, {}, stationTitle = "Highbury & Islington") }
        sendsABugReport()
    }

    @Test
    fun a_station_still_loading_sends_a_bug_report() {
        show { StationPlaceholderScreen(title = "Highbury & Islington", state = StationStopsViewModel.State.NoStops, onRetry = {}, onBack = {}) }
        sendsABugReport()
    }

    @Test
    fun station_search_sends_a_bug_report() {
        show { StationSearchScreen(StationSearchViewModel.State(), onQueryChange = {}, onOpenStation = {}, onRetry = {}, onBack = {}, autoFocus = false) }
        sendsABugReport()
    }

    @Test
    fun the_to_search_sends_a_bug_report() {
        show {
            StationSearchScreen(
                StationSearchViewModel.State(),
                onQueryChange = {},
                onOpenStation = {},
                onRetry = {},
                onBack = {},
                autoFocus = false,
                onChangeFrom = {},
            )
        }
        sendsABugReport()
    }

    @Test
    fun settings_sends_a_bug_report() {
        show { SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {}) }
        sendsABugReport()
    }

    @Test
    fun the_licenses_send_a_bug_report() {
        show { LicensesContent(libraries = null) }
        sendsABugReport()
    }

    @Test
    fun saved_places_send_a_bug_report() {
        show {
            FavoritePlacesScreen(
                state = FavoritePlacesViewModel.State(places = FavoritePlacesSet.Loaded(emptyList()), loaded = true),
                onBack = {},
                onRouteTo = {},
                onStartAdd = { _, _ -> },
                onStartEdit = {},
                onDelete = {},
                onQueryChange = {},
                onPick = {},
                onLabelChange = {},
                onSave = {},
                onCancelEditor = {},
            )
        }
        sendsABugReport()
    }

    @Test
    fun opening_settings_menu_masks_the_keys_before_a_report_can_be_sent() {
        // A revealed key would be in the report's screenshot in plain text: the menu masks both.
        show { SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {}, userApiKey = "EXAMPLE", railApiKey = "EXAMPLE") }
        composeRule.onNodeWithTag("apiKeyReveal").performScrollTo().performClick()
        composeRule.onNodeWithTag("railKeyReveal").performScrollTo().performClick()
        assertEquals(2, composeRule.onAllNodesWithText("Hide").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("More options").performClick()
        // Masked as the menu opens, before "Send bug report" is picked.
        composeRule.onAllNodesWithText("Hide").assertCountEquals(0)
        assertEquals(2, composeRule.onAllNodesWithText("Show").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Send bug report").performClick()
        composeRule.runOnIdle { assertEquals(1, reports) }
    }

    @Test
    fun settings_keep_back_and_the_menu_at_large_text() {
        show(fontScale = 3f) { SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {}) }
        headerKeepsBackAndMenu()
    }

    @Test
    fun the_licenses_keep_back_and_the_menu_at_large_text() {
        show(fontScale = 3f) { LicensesContent(libraries = null) }
        headerKeepsBackAndMenu()
    }

    @Test
    fun saved_places_keep_back_and_the_menu_at_large_text() {
        show(fontScale = 3f) {
            FavoritePlacesScreen(
                state = FavoritePlacesViewModel.State(places = FavoritePlacesSet.Loaded(emptyList()), loaded = true),
                onBack = {},
                onRouteTo = {},
                onStartAdd = { _, _ -> },
                onStartEdit = {},
                onDelete = {},
                onQueryChange = {},
                onPick = {},
                onLabelChange = {},
                onSave = {},
                onCancelEditor = {},
            )
        }
        headerKeepsBackAndMenu()
    }

    @Test
    fun with_no_app_menu_no_overflow_is_drawn() {
        // A preview or a test outside the activity: no menu to offer, so no button that does nothing.
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                OnTheWayScreen(trip = null, progress = TripProgress.Arrived, failed = false, now = now, onEnd = {}, onBack = {})
            }
        }
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("More options").assertDoesNotExist()
    }
}
