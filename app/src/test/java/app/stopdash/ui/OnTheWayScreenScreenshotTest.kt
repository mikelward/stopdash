package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A trip on the way (SPEC *On the way*): waiting for the train, on it, told to get off, and arrived.
 * A trip between well-known stations; the times are made up. No user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnTheWayScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")
    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

    private val mildmay = TripLeg("overground", "mildmay", "Mildmay", "910GHGHI", "Highbury & Islington", "910GSTFD", "Stratford", at(4), at(20))
    private val walk = TripLeg(TripLeg.WALKING, "", "", "910GSTFD", "Stratford", "940GZZLUSTD", "Stratford", at(20), at(24))
    private val jubilee = TripLeg("tube", "jubilee", "Jubilee", "940GZZLUSTD", "Stratford", "940GZZLUCYF", "Canary Wharf", at(26), at(33))
    private val trip = ActiveTrip(TripRoute(listOf(mildmay, walk, jubilee)), "Canary Wharf", startedAt = now, vehicleId = "EXAMPLE")

    private fun show(
        trip: ActiveTrip?,
        progress: TripProgress?,
        failed: Boolean = false,
        current: Boolean = true,
        notKept: Boolean = false,
        endFailed: Boolean = false,
        onEnd: () -> Unit = {},
        onBack: () -> Unit = {},
        alertsOff: Boolean = false,
    ) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                OnTheWayScreen(trip, progress, failed, now, onEnd, onBack, current = current, notKept = notKept, endFailed = endFailed, alertsOff = alertsOff)
            }
        }
    }

    @Test
    fun on_the_way_says_when_get_off_alerts_are_off() {
        show(trip, TripProgress.Waiting(mildmay, at(4)), alertsOff = true)
        composeRule.onNodeWithText("Get-off alerts are off").assertIsDisplayed()
    }

    @Test
    fun the_main_view_pins_the_trip_on_the_way_and_opens_it() {
        var opened = false
        val state = OnTheWayBannerState(trip.copy(boarded = true), TripProgress.Riding(mildmay, "Hackney Central", 4, at(16), getOffSoon = false), now) {
            opened = true
        }
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    MainScreen(DeparturesUiState.Loaded(emptyList(), now), now, {})
                }
            }
        }
        composeRule.onNodeWithText("To Canary Wharf").assertIsDisplayed()
        composeRule.onNodeWithText("Get off at Stratford").assertIsDisplayed()
        captureSnapshot("on-the-way-banner.png")
        composeRule.onNodeWithText("Get off at Stratford").performClick()
        assertTrue(opened)
    }

    @Test
    fun the_pinned_card_doesnt_show_an_old_answer_as_live() {
        // Last brought up to date ten minutes ago: its "next stop" is no longer known.
        val state = OnTheWayBannerState(trip.copy(boarded = true), TripProgress.Riding(mildmay, "Stratford", 1, at(1), getOffSoon = true), at(-10)) {}
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    MainScreen(DeparturesUiState.Loaded(emptyList(), now), now, {})
                }
            }
        }
        composeRule.onNodeWithText("Get off at Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("Updating…").assertIsDisplayed()
    }

    @Test
    fun the_location_gate_pins_the_trip_too() {
        // Location denied, so the near-me list never comes up: the trip is still a tap away.
        var opened = false
        val state = OnTheWayBannerState(trip, TripProgress.Waiting(mildmay, at(4)), now) { opened = true }
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    LocationGate(NearbyStopsViewModel.State.PermissionRequired, onAllow = {}, onRetry = {}, onOpenSettings = {})
                }
            }
        }
        composeRule.onNodeWithText("To Canary Wharf").performClick()
        assertTrue(opened)
    }

    @Test
    fun the_location_gate_card_reads_the_clock_it_is_given() {
        // Updated just now by the clock the gate is handed: live, not "Updating…".
        val state = OnTheWayBannerState(trip, TripProgress.Waiting(mildmay, at(4)), now) {}
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    LocationGate(NearbyStopsViewModel.State.PermissionRequired, onAllow = {}, onRetry = {}, onOpenSettings = {}, now = now)
                }
            }
        }
        composeRule.onNodeWithText("Due in 4 min").assertIsDisplayed()
    }

    @Test
    fun a_station_page_leaves_the_trip_card_to_the_near_me_list() {
        val state = OnTheWayBannerState(trip, TripProgress.Waiting(mildmay, at(4)), now) {}
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    MainScreen(DeparturesUiState.Loaded(emptyList(), now), now, {}, stationTitle = "Highbury & Islington")
                }
            }
        }
        composeRule.onNodeWithText("To Canary Wharf").assertDoesNotExist()
    }

    @Test
    fun on_the_way_waiting_for_the_train() {
        show(trip, TripProgress.Waiting(mildmay, at(4)))
        composeRule.onNodeWithText("Board Mildmay at Highbury & Islington").assertIsDisplayed()
        composeRule.onNodeWithText("Due in 4 min").assertIsDisplayed()
        captureSnapshot("on-the-way-waiting.png")
    }

    @Test
    fun on_the_way_on_the_train() {
        show(trip.copy(boarded = true), TripProgress.Riding(mildmay, "Hackney Central", 4, at(16), getOffSoon = false))
        composeRule.onNodeWithText("Get off at Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("4 stops · next Hackney Central").assertIsDisplayed()
        captureSnapshot("on-the-way-riding.png")
    }

    @Test
    fun on_the_way_get_off_soon() {
        show(trip.copy(boarded = true), TripProgress.Riding(mildmay, "Stratford", 1, at(1), getOffSoon = true))
        composeRule.onNodeWithText("Get off at Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("Next stop").assertIsDisplayed()
        captureSnapshot("on-the-way-get-off.png")
    }

    @Test
    fun on_the_way_a_change_shows_its_time() {
        // A change between two rides with no walk of its own, as the route shows it: "N min to change".
        show(trip.copy(legIndex = 2, vehicleId = ""), TripProgress.Changing(jubilee, at(3)))
        composeRule.onNodeWithText("Change to Jubilee at Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("3 min to change").assertIsDisplayed()
    }

    @Test
    fun on_the_way_stops_left_unknown_names_the_next_stop() {
        // A bus beyond its predictions: on it, its stops left not counted.
        show(trip.copy(boarded = true), TripProgress.Riding(mildmay, "Hackney Central", null, null, getOffSoon = false))
        composeRule.onNodeWithText("Next: Hackney Central").assertIsDisplayed()
    }

    @Test
    fun on_the_way_an_old_answer_isnt_shown_as_live() {
        // Back after a while away: the last answer said get off next, but that's no longer known.
        show(trip.copy(boarded = true), TripProgress.Riding(mildmay, "Stratford", 1, at(1), getOffSoon = true), current = false)
        composeRule.onNodeWithText("Get off at Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("Updating…").assertIsDisplayed()
        composeRule.onNodeWithText("Next stop").assertDoesNotExist()
    }

    @Test
    fun on_the_way_says_when_the_trip_couldnt_be_saved() {
        show(trip, TripProgress.Waiting(mildmay, at(4)), notKept = true)
        composeRule.onNodeWithText("Couldn't save the trip on this phone").assertIsDisplayed()
    }

    @Test
    fun on_the_way_says_when_the_trip_couldnt_be_ended() {
        show(trip, TripProgress.Waiting(mildmay, at(4)), endFailed = true)
        composeRule.onNodeWithText("Couldn't end the trip. Try again.").assertIsDisplayed()
    }

    @Test
    fun on_the_way_a_failed_update_says_so() {
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(3)), failed = true)
        composeRule.onNodeWithText("Walk to Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't update just now").assertIsDisplayed()
    }

    @Test
    fun on_the_way_end_trip_ends_it() {
        var ended = false
        show(trip, TripProgress.Waiting(mildmay, null), onEnd = { ended = true })
        composeRule.onNodeWithText("Finding your train…").assertIsDisplayed()
        composeRule.onNodeWithText("End trip").performClick()
        composeRule.runOnIdle { assertTrue(ended) }
    }

    @Test
    fun on_the_way_arrived() {
        var closed = false
        show(null, TripProgress.Arrived, onBack = { closed = true })
        composeRule.onNodeWithText("You've arrived").assertIsDisplayed()
        captureSnapshot("on-the-way-arrived.png")
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertTrue(closed) }
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
