package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app bar's crosshairs (SPEC *Finding stops*): in place of a refresh button, it re-locates on
 * the near-me list (the refresh action, which re-locates there) and, on a From… station page, runs
 * the page's own `onLocate` — back to the near-me list — rather than refreshing the station.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainScreenLocateTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    @Test
    fun crosshairs_nearMe_relocates() {
        var refreshes = 0
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = DeparturesUiState.Loading, now = now, onRefresh = { refreshes++ })
            }
        }

        composeRule.onNodeWithContentDescription("Refresh").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Use my location").performClick()
        composeRule.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun crosshairs_fromStation_returnsToNearMe() {
        var refreshes = 0
        var located = 0
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loading,
                    now = now,
                    onRefresh = { refreshes++ },
                    onLocate = { located++ },
                    stationTitle = "Example Station",
                    onCloseStation = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Use my location").performClick()
        composeRule.runOnIdle {
            assertEquals(1, located)
            assertEquals(0, refreshes)
        }
    }

    @Test
    fun crosshairs_stationPlaceholder_returnsToNearMe() {
        var located = 0
        composeRule.setContent {
            StopDashTheme {
                StationPlaceholderScreen(
                    title = "Example Station",
                    state = StationStopsViewModel.State.NoStops,
                    onRetry = {},
                    onBack = {},
                    onLocate = { located++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Use my location").performClick()
        composeRule.runOnIdle { assertEquals(1, located) }
    }

    @Test
    fun crosshairs_withoutLocationGrant_areNotOffered() {
        // Without a grant every crosshair could only lead back to the location gate (SPEC *Without
        // location*), so none shows, on a station's departures or its placeholder alike.
        var page by androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalLocationAllowed provides false) {
                    if (page) {
                        MainScreen(
                            state = DeparturesUiState.Loading,
                            now = now,
                            onRefresh = {},
                            onLocate = {},
                            stationTitle = "Example Station",
                            onCloseStation = {},
                        )
                    } else {
                        StationPlaceholderScreen(
                            title = "Example Station",
                            state = StationStopsViewModel.State.NoStops,
                            onRetry = {},
                            onBack = {},
                            onLocate = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("Use my location").assertDoesNotExist()
        composeRule.runOnIdle { page = false }
        composeRule.onNodeWithContentDescription("Use my location").assertDoesNotExist()
    }
}
