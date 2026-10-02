package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlaceIcon
import app.stopdash.domain.TripDestination
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `LocationGate` in each state the nearby-stops search can be in before it resolves (SPEC
 * *Finding stops*): asking for permission, locating, no fix, none nearby, and a TfL
 * failure. Each renders from fixture state alone (the gate's pure `state`-in signature),
 * so the same function drives the app and these snapshots. Canned wording only — no user
 * data (SPEC *Privacy*); dynamic color off for deterministic schemes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationGateScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Obviously-synthetic fix for the Empty/Failed states, never a real position (SPEC Privacy).
    private val FIX = Coordinates(51.5, -0.12)

    @Test
    fun `permission required, light`() {
        capture("location-permission.png") {
            LocationGate(NearbyStopsViewModel.State.PermissionRequired, onAllow = {}, onRetry = {}, onOpenSettings = {})
        }
        composeRule.onNodeWithText("Departures near you").assertExists()
        composeRule.onNodeWithText("Allow location").assertExists()
    }

    @Test
    fun `permission required, dark`() {
        capture("location-permission-dark.png", dark = true) {
            LocationGate(NearbyStopsViewModel.State.PermissionRequired, onAllow = {}, onRetry = {}, onOpenSettings = {})
        }
    }

    @Test
    fun `a denied gate still offers Find a station`() {
        var opened = false
        capture("location-denied-find-station.png") {
            LocationGate(
                NearbyStopsViewModel.State.PermissionRequired,
                onAllow = {},
                onRetry = {},
                onOpenSettings = {},
                permanentlyDenied = true,
                onFindStation = { opened = true },
            )
        }
        composeRule.onNodeWithText("Find a station").performClick()
        org.junit.Assert.assertTrue(opened)
    }

    @Test
    fun `permission permanently denied offers settings`() {
        capture("location-denied.png") {
            LocationGate(
                NearbyStopsViewModel.State.PermissionRequired,
                onAllow = {},
                onRetry = {},
                onOpenSettings = {},
                permanentlyDenied = true,
            )
        }
        composeRule.onNodeWithText("Open settings").assertExists()
    }

    @Test
    fun `finding`() {
        capture("location-finding.png") {
            LocationGate(NearbyStopsViewModel.State.Locating, onAllow = {}, onRetry = {}, onOpenSettings = {})
        }
        composeRule.onNodeWithText("Finding stops near you…").assertExists()
        // About is in the overflow, not on the screen.
        composeRule.onNodeWithText("About").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("More options").assertExists()
    }

    @Test
    fun `every state's overflow offers From, Settings, a bug report and About`() {
        var state by mutableStateOf<NearbyStopsViewModel.State>(NearbyStopsViewModel.State.PermissionRequired)
        var found = 0
        var settings = 0
        var reported = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LocationGate(
                        state,
                        onAllow = {},
                        onRetry = {},
                        onOpenSettings = {},
                        onFindStation = { found++ },
                        onOpenStopDashSettings = { settings++ },
                        onSendBugReport = { reported++ },
                    )
                }
            }
        }
        val states = listOf(
            NearbyStopsViewModel.State.PermissionRequired,
            NearbyStopsViewModel.State.Locating,
            NearbyStopsViewModel.State.NoLocation,
            NearbyStopsViewModel.State.Empty(FIX),
            NearbyStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.OFFLINE, FIX),
        )
        for ((i, next) in states.withIndex()) {
            state = next
            composeRule.onNodeWithContentDescription("More options").performClick()
            composeRule.onNodeWithText("From…").performClick()
            composeRule.onNodeWithContentDescription("More options").performClick()
            composeRule.onNodeWithText("Settings").performClick()
            composeRule.onNodeWithContentDescription("More options").performClick()
            // The stuck states also show the report as a button: the menu's item is the last of the two.
            composeRule.onAllNodesWithText("Send bug report").onLast().performClick()
            composeRule.runOnIdle { assertEquals(listOf(i + 1, i + 1, i + 1), listOf(found, settings, reported)) }
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("About").performClick()
        // The About dialog, titled "About", with the menu gone.
        composeRule.onNodeWithText("About").assertExists()
        composeRule.onNodeWithText("From…").assertDoesNotExist()
    }

    @Test
    fun `the overflow's About goes to the caller's dialog when it hosts one`() {
        // Hosted above the gate, so departures replacing the gate can't close it (Codex, #470).
        var opened = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LocationGate(
                        NearbyStopsViewModel.State.Locating,
                        onAllow = {},
                        onRetry = {},
                        onOpenSettings = {},
                        onOpenAbout = { opened = true },
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("About").performClick()
        assertTrue(opened)
        // The gate opens no dialog of its own.
        composeRule.onNodeWithText("About").assertDoesNotExist()
    }

    @Test
    fun `the overflow carries the update dot and item when one is available`() {
        var opened = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LocationGate(
                        NearbyStopsViewModel.State.Empty(FIX),
                        onAllow = {},
                        onRetry = {},
                        onOpenSettings = {},
                        updateAvailable = true,
                        onOpenAppListing = { opened = true },
                    )
                }
            }
        }
        composeRule.onNodeWithTag(UPDATE_AVAILABLE_DOT_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Update available").performClick()
        assertTrue(opened)
    }

    @Test
    fun `the locating spinner offers the update when one is available`() {
        capture("location-finding-update.png") {
            LocationGate(
                NearbyStopsViewModel.State.Locating,
                onAllow = {},
                onRetry = {},
                onOpenSettings = {},
                updateAvailable = true,
            )
        }
        composeRule.onNodeWithText("Finding stops near you…").assertExists()
        composeRule.onNodeWithText("Update available").assertExists()
    }

    @Test
    fun `the locating spinner's update button is at the bottom and moves nothing else`() {
        var update by mutableStateOf(false)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LocationGate(
                        NearbyStopsViewModel.State.Locating,
                        onAllow = {},
                        onRetry = {},
                        onOpenSettings = {},
                        updateAvailable = update,
                        onFindStation = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val finding = composeRule.onNodeWithText("Finding stops near you…").getBoundsInRoot()
        val find = composeRule.onNodeWithText("Find a station").getBoundsInRoot()
        update = true
        composeRule.waitForIdle()
        // Everything else stays put: the button's slot is kept either way.
        assertEquals(finding, composeRule.onNodeWithText("Finding stops near you…").getBoundsInRoot())
        assertEquals(find, composeRule.onNodeWithText("Find a station").getBoundsInRoot())
        // The button is at the bottom, inside the gate's 24dp margin, below Find a station.
        val button = composeRule.onNodeWithText("Update available").getBoundsInRoot()
        // Its face sits up to 4dp inside its 48dp touch target, which meets the margin.
        val gap = composeRule.onRoot().getBoundsInRoot().bottom - 24.dp - button.bottom
        assertTrue("$gap", gap >= 0.dp && gap <= 4.dp)
        assertTrue(button.top > find.bottom)
    }

    @Test
    fun `the locating spinner has no update button when none is available`() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LocationGate(NearbyStopsViewModel.State.Locating, onAllow = {}, onRetry = {}, onOpenSettings = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Update available").assertDoesNotExist()
    }

    @Test
    fun `no fix`() {
        capture("location-no-fix.png") {
            LocationGate(NearbyStopsViewModel.State.NoLocation, onAllow = {}, onRetry = {}, onOpenSettings = {})
        }
        composeRule.onNodeWithText("Couldn't get your location").assertExists()
    }

    @Test
    fun `no stops nearby says StopDash shows only London's, and keeps Find a station`() {
        var found = false
        capture("location-empty.png") {
            LocationGate(
                NearbyStopsViewModel.State.Empty(FIX),
                onAllow = {},
                onRetry = {},
                onOpenSettings = {},
                onFindStation = { found = true },
            )
        }
        composeRule.onNodeWithText("No stops found nearby").assertExists()
        composeRule.onNodeWithText("Routemo only shows stops in and around London.").assertExists()
        composeRule.onNodeWithText("Find a station").performClick()
        assertTrue(found)
    }

    // Stock stand-in places on synthetic coordinates, never a real person's (SPEC *Privacy*).
    private val places = listOf(
        FavoritePlace("home", FavoriteKind.HOME, "Home", Coordinates(51.51, -0.09), icon = FavoritePlaceIcon.HOME),
        FavoritePlace("gym", FavoriteKind.CUSTOM, "Gym", Coordinates(51.52, -0.1)),
    )

    @Test
    fun `no stops nearby still offers the saved places, a tap routing to one`() {
        var routed: TripDestination.Place? = null
        var edited = false
        capture("location-empty-places.png") {
            LocationGate(
                NearbyStopsViewModel.State.Empty(FIX),
                onAllow = {},
                onRetry = {},
                onOpenSettings = {},
                places = places,
                onRouteToPlace = { routed = it },
                onEditPlaces = { edited = true },
            )
        }
        composeRule.onNodeWithText("No stops found nearby").assertExists()
        composeRule.onNodeWithContentDescription("Plan a trip to Gym").performClick()
        assertEquals("Gym", routed?.name)
        assertEquals(Coordinates(51.52, -0.1), routed?.coordinate)
        composeRule.onNodeWithContentDescription("Plan a trip to Home").performTouchInput { longClick() }
        assertTrue(edited)
    }

    @Test
    fun `a state with no position to plan from offers no place chips`() {
        var state by mutableStateOf<NearbyStopsViewModel.State>(NearbyStopsViewModel.State.Empty(FIX))
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                LocationGate(state, onAllow = {}, onRetry = {}, onOpenSettings = {}, places = places)
            }
        }
        composeRule.onNodeWithTag("favoriteChips").assertExists()
        for (next in listOf(NearbyStopsViewModel.State.NoLocation, NearbyStopsViewModel.State.Locating)) {
            state = next
            composeRule.onNodeWithTag("favoriteChips").assertDoesNotExist()
        }
    }

    @Test
    fun `no stops nearby from a coarse fix`() {
        capture("location-empty-approximate.png") {
            LocationGate(
                NearbyStopsViewModel.State.Empty(FIX),
                onAllow = {},
                onRetry = {},
                onOpenSettings = {},
                approximate = true,
            )
        }
        composeRule.onNodeWithText("Approximate location").assertExists()
    }

    @Test
    fun `lookup failed`() {
        capture("location-error.png") {
            LocationGate(
                NearbyStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.OFFLINE, FIX),
                onAllow = {},
                onRetry = {},
                onOpenSettings = {},
            )
        }
        composeRule.onNodeWithText("You're offline").assertExists()
    }

    @Test
    fun `a stuck state offers the bug report action and reports the tap`() {
        var sent = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LocationGate(
                        NearbyStopsViewModel.State.NoLocation,
                        onAllow = {},
                        onRetry = {},
                        onOpenSettings = {},
                        onSendBugReport = { sent = true },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Send bug report").performClick()
        composeRule.runOnIdle { assert(sent) }
    }

    @Test
    fun `the first-run permission prompt has no bug report action`() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LocationGate(
                        NearbyStopsViewModel.State.PermissionRequired,
                        onAllow = {},
                        onRetry = {},
                        onOpenSettings = {},
                        onSendBugReport = {},
                    )
                }
            }
        }
        // The allow prompt isn't a stuck state — no report action there.
        composeRule.onNodeWithText("Send bug report").assertDoesNotExist()
    }

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            StopDashTheme(darkTheme = dark, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.waitForIdle()
        captureSnapshot(name)
    }

    /**
     * Draws the activity window into a PNG. Measured and laid out explicitly at the device
     * size — Robolectric's window has no real surface, so an unmeasured decor view captures
     * blank. Same helper shape as `MainScreenScreenshotTest` and the sibling repos.
     */
    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 2400) {
        val recording = System.getProperty("roborazzi.test.record") == "true"
        val verifying = System.getProperty("roborazzi.test.verify") == "true"
        if (!recording && !verifying) return

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
}
