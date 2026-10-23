package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A TAP on a departure/route card opens the full-screen route detail (SPEC D8 / `TODO.md`) — a
 * deliberate no-op before this. A long-press opens "Add favorite…" ([StarLongPressTest]); this pins the
 * tap wiring and that the page closes on back. Its back arrow (the list has none) says it's open. Because the page REPLACES the departures screen, its
 * banner isn't composed while the page is open, so these assertions read the page's own text
 * directly. The page's contents are covered by [RouteDetailScreenScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteDetailTapTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val stop = StopArrivals(
        stopId = "940GZZLUKSX",
        stopName = "King's Cross St. Pancras",
        departures = listOf(
            Departure("victoria", "Victoria", "southbound", "Brixton", null, now.plusSeconds(120), "tube"),
        ),
        fetchedAt = now,
    )

    private fun loaded() = DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = now)

    @Test
    fun `tapping a card opens the route detail`() {
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = loaded(), now = now, onRefresh = {})
            }
        }

        // The near-me list has no back arrow, so the page's appearing means the page opened.
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `tapping a card's second route opens that route, not the soonest train`() {
        val branching = StopArrivals(
            stopId = "940GZZLUKNG",
            stopName = "Kennington",
            departures = listOf(
                Departure("northern", "Northern", "northbound", "Edgware", null, now.plusSeconds(60), "tube"),
                Departure("northern", "Northern", "northbound", "High Barnet", null, now.plusSeconds(240), "tube"),
            ),
            fetchedAt = now,
        )
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loaded(stops = listOf(branching), fetchedAt = now),
                    now = now,
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("High Barnet").performTouchInput { click() }
        // The page is open and names only the tapped route.
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("High Barnet").assertIsDisplayed()
        composeRule.onNodeWithText("Edgware", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a checked good-service line shows no disruptions even when another line is unknown`() {
        // disruptionUnknown is set by some OTHER line, but this row's line was determined (good
        // service): its detail must not claim "couldn't check" — that's the per-line fix (Codex).
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loaded(
                        stops = listOf(stop),
                        fetchedAt = now,
                        disruptionUnknown = true,
                        determinedLineIds = setOf("victoria"),
                    ),
                    now = now,
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithText("No disruptions reported").assertIsDisplayed()
    }

    @Test
    fun `a determined line whose stop disruption failed says couldnt check`() {
        // The line WAS determined (good service), but this stop's own disruption lookup (a
        // closure/move) failed — the two axes are independent. The detail must not claim the row
        // is clean when a stop-level disruption was never checked (SPEC principle 1, Codex on #100).
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loaded(
                        stops = listOf(stop),
                        fetchedAt = now,
                        disruptionUnknown = true,
                        determinedLineIds = setOf("victoria"),
                        stopsDisruptionUnknown = setOf("940GZZLUKSX"),
                    ),
                    now = now,
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithText("Couldn't check for disruptions").assertIsDisplayed()
    }

    @Test
    fun `an unchecked line's detail says couldnt check`() {
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loaded(
                        stops = listOf(stop),
                        fetchedAt = now,
                        disruptionUnknown = true,
                        determinedLineIds = emptySet(),
                    ),
                    now = now,
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithText("Couldn't check for disruptions").assertIsDisplayed()
    }

    @Test
    fun `the detail stays open when its row leaves the list, and follows it back`() {
        // The open route's last departure passes, or TfL leaves it out of a fetch: a refresh never closes a page the
        // rider opened (maintainer, 2026-10-22). It stays on its route with no live times until a train comes.
        val served = stop.copy(lines = listOf(LineRef("victoria", "Victoria", "tube")))
        val present = DeparturesUiState.Loaded(stops = listOf(served), fetchedAt = now)
        val gone = DeparturesUiState.Loaded(stops = listOf(served.copy(departures = emptyList())), fetchedAt = now)
        var current by mutableStateOf<DeparturesUiState>(present)
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = current, now = now, onRefresh = {})
            }
        }

        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()

        // The row leaves the list: the page stays, on its route with no train, not the list.
        composeRule.runOnUiThread { current = gone }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria line").assertIsDisplayed()
        composeRule.onNodeWithText("2 min").assertDoesNotExist()

        // A train comes again on a later refresh: the page shows it, as from a tap on its row.
        composeRule.runOnUiThread { current = present }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `the detail stays open while a new set of stops loads afresh`() {
        // A fix that picks a different set of nearby stops gives the list a model of its own, which starts with
        // nothing loaded: the page waited for nothing and closed, on most refreshes (maintainer, 2026-10-22).
        var current by mutableStateOf<DeparturesUiState>(loaded())
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = current, now = now, onRefresh = {})
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()

        composeRule.runOnUiThread { current = DeparturesUiState.Loading }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()

        // The new set's stops come in one by one: the page's own isn't judged gone while it's still loading.
        composeRule.runOnUiThread {
            current = DeparturesUiState.Loaded(
                stops = emptyList(),
                fetchedAt = now,
                pendingStops = listOf(StopRef(stop.stopId, stop.stopName)),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Status may be out of date").assertDoesNotExist()

        composeRule.runOnUiThread { current = loaded() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `the detail holds as out of date once its stop has left the list, and is live again when it's back`() {
        // The rider moved on, so the stop is no longer fetched: the page stays as it was, saying it may be out of
        // date, rather than closing or claiming its times are current.
        val elsewhere = StopArrivals(
            stopId = "940GZZLUEUS",
            stopName = "Euston",
            departures = listOf(Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(180), "tube")),
            fetchedAt = now,
        )
        var current by mutableStateOf<DeparturesUiState>(loaded())
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = current, now = now, onRefresh = {})
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()

        composeRule.runOnUiThread { current = DeparturesUiState.Loaded(stops = listOf(elsewhere), fetchedAt = now) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Status may be out of date").assertIsDisplayed()
        composeRule.onNodeWithText("Walthamstow Central").assertDoesNotExist()

        composeRule.runOnUiThread { current = loaded() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Status may be out of date").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `pressing back closes the detail`() {
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = loaded(), now = now, onRefresh = {})
            }
        }

        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        // The app bar's back arrow returns to the list.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }
}
