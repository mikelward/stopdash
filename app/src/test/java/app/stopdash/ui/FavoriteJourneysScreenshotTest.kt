package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.JourneyAlertSchedule
import app.stopdash.domain.JourneyAlerts
import app.stopdash.domain.JourneyEnd
import java.time.DayOfWeek
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.StationMatch
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The favorite journeys in Settings (SPEC *Journeys*): the list with Remove and Add, none, and a file
 * this build can't read; and a stop page's row offering the journey there. Public TfL interchanges only, never anyone's stops (SPEC *Privacy*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoriteJourneysScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val victoriaLine = FavoriteJourney(
        JourneyEnd("940GZZLUVIC", "Victoria"),
        JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras"),
        "victoria",
        lineName = "Victoria",
        mode = "tube",
    )
    private val northern = FavoriteJourney(
        JourneyEnd("940GZZLUEUS", "Euston"),
        JourneyEnd("940GZZLUWLO", "Waterloo"),
        "northern",
        lineName = "Northern",
        mode = "tube",
    )

    private fun show(state: FavoriteJourneysUi, onRemove: (FavoriteJourney) -> Unit = {}) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(state = state, onBack = {}, onRemove = onRemove)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun the_list_names_each_journey_and_removes_the_one_tapped() {
        val removed = mutableListOf<FavoriteJourney>()
        show(FavoriteJourneysUi(listOf(victoriaLine, northern)), onRemove = { removed += it })
        // Saved both ways, so a two-way arrow (maintainer, 2026-10-08).
        composeRule.onNodeWithText("Victoria ⇄ King's Cross St. Pancras").assertIsDisplayed()
        composeRule.onNodeWithText("Northern").assertIsDisplayed()
        captureSnapshot("favorite-journeys-list.png")
        composeRule.onNodeWithContentDescription("Remove Euston to Waterloo").performClick()
        assertEquals(listOf(northern), removed)
    }

    @Test
    fun none_says_how_to_add_one() {
        show(FavoriteJourneysUi(emptyList()))
        composeRule.onNodeWithText("Tap Add", substring = true).assertIsDisplayed()
        captureSnapshot("favorite-journeys-empty.png")
    }

    @Test
    fun add_starts_picking_one() {
        var adds = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(state = FavoriteJourneysUi(emptyList()), onBack = {}, onRemove = {}, onAdd = { adds++ })
            }
        }
        composeRule.onNodeWithTag("addJourney").assertIsDisplayed().performClick()
        assertEquals(1, adds)
    }

    @Test
    fun a_stops_page_offers_the_journey_there_until_saved_then_its_removal() {
        var saved by mutableStateOf<Boolean?>(null)
        var toggles = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StationJourneyRow(StationJourneyState(victoriaLine, saved, failed = false) { toggles++ })
            }
        }
        composeRule.onNodeWithText("Victoria ➔ King's Cross St. Pancras").assertIsDisplayed()
        // Not read yet: the button waits rather than guess which way a tap goes.
        composeRule.onNodeWithTag("stationJourneyToggle").assertIsNotEnabled()
        saved = false
        composeRule.onNodeWithText("Favourite").assertIsDisplayed().performClick()
        assertEquals(1, toggles)
        saved = true
        composeRule.onNodeWithText("Remove favourite").assertIsDisplayed()
    }

    @Test
    fun a_stops_page_says_when_the_journey_didnt_save() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StationJourneyRow(StationJourneyState(victoriaLine, saved = false, failed = true) {})
            }
        }
        composeRule.onNodeWithText("Couldn't save that change").assertIsDisplayed()
    }

    @Test
    fun a_stops_page_still_loading_offers_the_journey_there() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalStationJourney provides StationJourneyState(victoriaLine, saved = false, failed = false) {}) {
                    StationPlaceholderScreen(
                        title = "King's Cross St. Pancras",
                        state = StationStopsViewModel.State.Loading,
                        onRetry = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Victoria ➔ King's Cross St. Pancras").assertIsDisplayed()
        composeRule.onNodeWithText("Favourite").assertIsDisplayed()
    }

    @Test
    fun a_stops_page_says_when_the_favorites_cant_be_read_with_retry() {
        var retries = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StationJourneyRow(StationJourneyState(victoriaLine, saved = null, failed = false, unavailable = true, onRetry = { retries++ }) {})
            }
        }
        composeRule.onNodeWithText("Can't read your favourite journeys.").assertIsDisplayed()
        composeRule.onNodeWithTag("stationJourneyRetry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun a_tapped_route_stop_survives_being_saved_and_restored() {
        val scope = SaverScope { true }
        val withJourney = RouteStopOpen("940GZZLUKSX", "King's Cross St. Pancras", victoriaLine.copy(from = JourneyEnd("940GZZLUVIC", "Victoria", 51.5, -0.12, "HUBVIC")))
        val bare = RouteStopOpen("940GZZLUKSX", "King's Cross St. Pancras", null, hubId = "HUBKGX")
        for (open in listOf(withJourney, bare)) {
            val saved = with(RouteStopOpenSaver) { scope.save(open) }!!
            assertEquals(open, RouteStopOpenSaver.restore(saved))
        }
    }

    @Test
    fun a_route_stop_is_the_open_station_by_its_own_id_or_its_hubs() {
        val kingsCross = RouteStopOpen("940GZZLUKSX", "King's Cross St. Pancras", null, hubId = "HUBKGX")
        assertEquals(true, kingsCross.isOpen("940GZZLUKSX"))
        // A station page opened from search by its interchange.
        assertEquals(true, kingsCross.isOpen("HUBKGX"))
        assertEquals(false, kingsCross.isOpen("940GZZLUEUS"))
        assertEquals(false, kingsCross.isOpen(null))
        assertEquals(false, RouteStopOpen("940GZZLUEUS", "Euston", null).isOpen("HUBKGX"))
    }

    @Test
    fun an_unreadable_file_is_said_not_shown_as_none_with_retry() {
        var retries = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(state = FavoriteJourneysUi(journeys = null), onBack = {}, onRemove = {}, onRetry = { retries++ })
            }
        }
        composeRule.onNodeWithText("Can't read your favourite journeys.").assertIsDisplayed()
        composeRule.onNodeWithTag("retryJourneys").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun a_pair_with_no_direct_line_says_so_until_dismissed() {
        var dismissed = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(
                    state = FavoriteJourneysUi(listOf(victoriaLine), adding = JourneyAddNote.NoDirectLine("King's Cross St. Pancras", "Canada Water")),
                    onBack = {},
                    onRemove = {},
                    onDismissAddNote = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithText("No direct line from King's Cross St. Pancras to Canada Water").assertIsDisplayed()
        captureSnapshot("favorite-journeys-no-direct-line.png")
        composeRule.onNodeWithTag("dismissJourneyAddNote").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun a_pair_being_added_says_so_with_nothing_to_dismiss() {
        show(FavoriteJourneysUi(emptyList(), adding = JourneyAddNote.Adding("Euston", "Waterloo")))
        composeRule.onNodeWithText("Adding Euston ⇄ Waterloo…").assertIsDisplayed()
        composeRule.onNodeWithTag("dismissJourneyAddNote").assertDoesNotExist()
    }

    @Test
    fun picking_a_pair_asks_from_then_to_with_the_start_in_the_from_row() {
        var from by mutableStateOf<StationMatch?>(null)
        var picked: Pair<StationMatch, StationMatch>? = null
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneyPicker(
                    state = StationSearchViewModel.State(yoursRead = true),
                    from = from,
                    onQueryChange = {},
                    onRetry = {},
                    onPickFrom = { from = it },
                    onPickTo = { a, b -> picked = a to b },
                    onChangeFrom = { from = null },
                    onBack = {},
                    autoFocus = false,
                )
            }
        }
        composeRule.onNodeWithText("From station or stop").assertIsDisplayed()
        from = StationMatch("940GZZLUEUS", "Euston")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Euston").assertIsDisplayed()
        captureSnapshot("favorite-journeys-pick-to.png")
        // Back from To drops back to picking From.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("From station or stop").assertIsDisplayed()
        assertEquals(null, picked)
    }

    @Test
    fun an_alert_change_that_wasnt_saved_is_said_on_the_list_too() {
        var dismissed = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(
                    state = FavoriteJourneysUi(listOf(victoriaLine), alertWriteFailed = true),
                    onBack = {},
                    onRemove = {},
                    onDismissAlertWriteError = { dismissed = true },
                )
            }
        }
        composeRule.waitForIdle()
        // Left the Alerts screen before the save failed: said where the rider now is (Codex on #700).
        composeRule.onNodeWithText("Couldn't save that change").assertIsDisplayed()
        composeRule.onNodeWithTag("dismissJourneyAlertWriteError").performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun a_late_alert_save_error_doesnt_move_the_list() {
        var failed by mutableStateOf(false)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(state = FavoriteJourneysUi(listOf(victoriaLine), alertWriteFailed = failed), onBack = {}, onRemove = {})
            }
        }
        composeRule.waitForIdle()
        val before = composeRule.onNodeWithTag("remove-${victoriaLine.key}").getUnclippedBoundsInRoot()
        failed = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dismissJourneyAlertWriteError").assertIsDisplayed()
        // Said over the screen, not in its flow (Codex on #700).
        assertEquals(before, composeRule.onNodeWithTag("remove-${victoriaLine.key}").getUnclippedBoundsInRoot())
    }

    @Test
    fun a_row_keeps_its_height_when_its_alert_summary_changes() {
        var summaries by mutableStateOf(mapOf(northern.key to emptyList<String>()))
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(state = FavoriteJourneysUi(listOf(northern, victoriaLine), alertSummaries = summaries), onBack = {}, onRemove = {})
            }
        }
        composeRule.waitForIdle()
        val below = composeRule.onNodeWithTag("remove-${victoriaLine.key}").getUnclippedBoundsInRoot()
        // A save finishing after the rider came back turns both directions on: the row below stays put (Codex on #700).
        summaries = mapOf(northern.key to listOf("Alerts to Waterloo: Mon–Fri 08:00–10:00", "Alerts to Euston: Mon–Fri 16:00–18:00"))
        composeRule.waitForIdle()
        assertEquals(below, composeRule.onNodeWithTag("remove-${victoriaLine.key}").getUnclippedBoundsInRoot())
    }

    @Test
    fun a_failed_removal_says_so_until_dismissed() {
        var dismissed = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(
                    state = FavoriteJourneysUi(listOf(victoriaLine), writeFailed = true),
                    onBack = {},
                    onRemove = {},
                    onDismissWriteError = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithText("Couldn't remove that journey").assertIsDisplayed()
        composeRule.onNodeWithTag("dismissJourneyWriteError").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun each_journey_says_its_alerts_and_a_tap_opens_them() {
        val alerts = mapOf(
            JourneyAlerts.directionKey(northern, northern.from.stopId) to JourneyAlertSchedule(windows = JourneyAlertSchedule.DEFAULT_WINDOWS.take(1)),
            JourneyAlerts.directionKey(northern, northern.to.stopId) to JourneyAlertSchedule(windows = JourneyAlertSchedule.DEFAULT_WINDOWS.drop(1)),
        )
        val opened = mutableListOf<FavoriteJourney>()
        val summaries = journeyAlertSummaries(composeRule.activity, listOf(northern, victoriaLine), alerts)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(
                    state = FavoriteJourneysUi(listOf(northern, victoriaLine), alertSummaries = summaries),
                    onBack = {},
                    onRemove = {},
                    onOpenAlerts = { opened += it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Alerts to Waterloo: Mon–Fri 08:00–10:00").assertIsDisplayed()
        composeRule.onNodeWithText("Alerts to Euston: Mon–Fri 16:00–18:00").assertIsDisplayed()
        composeRule.onNodeWithText("Alerts off").assertIsDisplayed()
        captureSnapshot("favorite-journeys-alerts.png")
        composeRule.onNodeWithTag("openAlerts-${northern.key}").performClick()
        assertEquals(listOf(northern), opened)
    }

    @Test
    fun a_journeys_alerts_show_each_direction_and_each_change_builds_on_the_last() {
        val out = JourneyAlerts.directionKey(northern, northern.from.stopId)
        val back = JourneyAlerts.directionKey(northern, northern.to.stopId)
        // Stands in for the store: each change applied to what's held now.
        var stored by mutableStateOf(mapOf(out to JourneyAlertSchedule(windows = JourneyAlertSchedule.DEFAULT_WINDOWS.take(1))))
        val update = { key: String, change: (JourneyAlertSchedule?) -> JourneyAlertSchedule? ->
            val next = change(stored[key])
            stored = if (next == null) stored - key else stored + (key to next)
        }
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                JourneyAlertsScreen(state = JourneyAlertsUi(northern, stored), onBack = {}, onUpdate = update)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Euston ⇄ Waterloo").assertIsDisplayed()
        composeRule.onNodeWithText("Euston ➔ Waterloo").assertIsDisplayed()
        composeRule.onNodeWithText("Waterloo ➔ Euston").assertIsDisplayed()
        composeRule.onNodeWithText("08:00–10:00").assertIsDisplayed()
        captureSnapshot("journey-alerts.png")

        // The way back, off, turns on at its evening default.
        composeRule.onNodeWithTag("alertsSwitch-$back").performClick()
        assertEquals(JourneyAlertSchedule(windows = JourneyAlertSchedule.DEFAULT_WINDOWS.drop(1)), stored[back])
        // Two days off the way out, one after the other: both stick.
        composeRule.onNodeWithTag("alertDay-$out-MONDAY").performClick()
        composeRule.onNodeWithTag("alertDay-$out-TUESDAY").performClick()
        assertEquals(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), stored[out]?.days)
        // Its only window can't be removed (the switch turns it off), nor its last day.
        composeRule.onNodeWithContentDescription("Remove 08:00–10:00").assertDoesNotExist()
        listOf("WEDNESDAY", "THURSDAY", "FRIDAY").forEach { composeRule.onNodeWithTag("alertDay-$out-$it").performClick() }
        assertEquals(setOf(DayOfWeek.FRIDAY), stored[out]?.days)
        // At most a few windows: no Add past the cap.
        update(out) { it?.copy(windows = List(JourneyAlertSchedule.MAX_WINDOWS) { i -> JourneyAlertSchedule.DEFAULT_WINDOWS[0].let { w -> w.copy(start = w.start.plusHours(i * 3L), end = w.end.plusHours(i * 3L)) } }) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("addTime-$out").assertDoesNotExist()
        // With two windows, either can be removed.
        update(out) { it?.copy(windows = JourneyAlertSchedule.DEFAULT_WINDOWS) }
        composeRule.onNodeWithContentDescription("Remove 08:00–10:00").performClick()
        assertEquals(JourneyAlertSchedule.DEFAULT_WINDOWS.drop(1), stored[out]?.windows)
        // And the way out off.
        composeRule.onNodeWithTag("alertsSwitch-$out").performClick()
        assertEquals(null, stored[out])
    }

    @Test
    fun a_journeys_alerts_say_a_change_that_wasnt_saved_until_dismissed() {
        var dismissed = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                JourneyAlertsScreen(
                    state = JourneyAlertsUi(northern, emptyMap(), writeFailed = true),
                    onBack = {},
                    onUpdate = { _, _ -> },
                    onDismissWriteError = { dismissed = true },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Couldn't save that change").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun a_failed_save_doesnt_move_the_controls() {
        val out = JourneyAlerts.directionKey(northern, northern.from.stopId)
        var failed by mutableStateOf(false)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                JourneyAlertsScreen(state = JourneyAlertsUi(northern, emptyMap(), writeFailed = failed), onBack = {}, onUpdate = { _, _ -> })
            }
        }
        composeRule.waitForIdle()
        val before = composeRule.onNodeWithTag("alertsSwitch-$out").getUnclippedBoundsInRoot()
        failed = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("journeyAlertsWriteFailed").assertIsDisplayed()
        // Said over the screen, not in its flow (Codex on #700).
        assertEquals(before, composeRule.onNodeWithTag("alertsSwitch-$out").getUnclippedBoundsInRoot())
    }

    @Test
    fun a_journeys_alerts_still_loading_dont_call_the_schedules_unreadable() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                JourneyAlertsScreen(state = JourneyAlertsUi(northern, null, loading = true), onBack = {}, onUpdate = { _, _ -> })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Alerts").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Can't read", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun a_journeys_alerts_say_when_notifications_are_off() {
        var allowed = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                JourneyAlertsScreen(
                    state = JourneyAlertsUi(northern, emptyMap(), notificationsOff = true),
                    onBack = {},
                    onUpdate = { _, _ -> },
                    onAllowNotifications = { allowed = true },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("journeyAlertsNotificationsOff").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(true, allowed)
    }

    @Test
    fun the_list_asks_for_location_all_the_time_at_its_top_once_alerts_are_on() {
        var asked = 0
        var declined = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(
                    state = FavoriteJourneysUi(listOf(northern, victoriaLine), askLocation = true),
                    onBack = {},
                    onRemove = {},
                    onAllowLocation = { asked++ },
                    onDeclineLocation = { declined++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Alerts only in London?").assertIsDisplayed()
        captureSnapshot("favorite-journeys-location.png")
        composeRule.onNodeWithTag("allowJourneyAlertsLocation").performClick()
        assertEquals(1, asked)
        composeRule.onNodeWithTag("declineJourneyAlertsLocation").performClick()
        assertEquals(1, declined)
    }

    @Test
    fun the_location_disclosure_says_it_runs_while_closed_and_continues_or_not() {
        var continued = 0
        var dismissed = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                LocationRationaleDialog(onContinue = { continued++ }, onDismiss = { dismissed++ })
            }
        }
        composeRule.waitForIdle()
        // Google Play's disclosure: location, used while the app is closed, kept on the phone.
        composeRule.onNodeWithText("Allow all the time").assertIsDisplayed()
        composeRule.onNodeWithText("even while it's closed", substring = true).assertIsDisplayed()
        if (capturing()) {
            composeRule.onNode(isDialog()).captureRoboImage(filePath = "src/test/snapshots/images/location-disclosure.png")
        }
        composeRule.onNodeWithTag("continueJourneyAlertsLocation").performClick()
        assertEquals(1, continued)
        composeRule.onNodeWithText("Not now").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun the_list_asks_nothing_while_location_is_allowed_or_no_alert_is_on() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(state = FavoriteJourneysUi(listOf(northern)), onBack = {}, onRemove = {})
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("journeyAlertsLocation").fetchSemanticsNodes().size)
    }

    @Test
    fun a_journeys_alerts_ask_for_location_all_the_time_at_their_foot() {
        var asked = 0
        val out = JourneyAlerts.directionKey(northern, northern.from.stopId)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                JourneyAlertsScreen(
                    state = JourneyAlertsUi(northern, mapOf(out to JourneyAlerts.defaultsFor(northern).getValue(out)), askLocation = true),
                    onBack = {},
                    onUpdate = { _, _ -> },
                    onAllowLocation = { asked++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("allowJourneyAlertsLocation").performScrollTo().performClick()
        assertEquals(1, asked)
        captureSnapshot("journey-alerts-location.png")
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        if (!capturing()) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
