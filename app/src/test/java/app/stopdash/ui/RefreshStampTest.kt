package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import app.stopdash.domain.Departure
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A refresh under way is said by the stamp, "Checking…" where the age was, not by a spinner held over
 * the top of the list, which covered its banners and disruptions (maintainer, 2026-10-13). A pull still
 * refreshes, its indicator gone once the user lets go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class RefreshStampTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    // The rows are worked out only when the test says, so none is pending when the screen is read.
    private val scheduler = TestCoroutineScheduler()
    private val worker = StandardTestDispatcher(scheduler)
    private var refreshing by mutableStateOf(false)
    private var pulls = 0

    private fun kingsCross(fetchedAt: Instant) = StopArrivals(
        stopId = "940GZZLUKSX",
        stopName = "King's Cross St. Pancras",
        departures = listOf(Departure("victoria", "Victoria", "southbound", "Brixton", null, now.plusSeconds(240), "tube")),
        fetchedAt = fetchedAt,
    )

    private fun show(state: DeparturesUiState) {
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides worker) {
                    MainScreen(
                        state = state,
                        now = now,
                        onRefresh = {},
                        onPullRefresh = { pulls++ },
                        refreshing = refreshing,
                        listKey = "kings-cross",
                    )
                }
            }
        }
        repeat(5) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
    }

    private val spinners = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

    @Test
    fun `a refresh says Checking in the stamp, with no spinner over the list`() {
        refreshing = true
        show(DeparturesUiState.Loaded(stops = listOf(kingsCross(now)), fetchedAt = now))
        composeRule.onNodeWithText("Brixton").assertExists()
        composeRule.onNodeWithText("Checking…").assertExists()
        composeRule.onAllNodesWithText("Just now").assertCountEquals(0)
        composeRule.onAllNodes(spinners).assertCountEquals(0)

        refreshing = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Just now").assertExists()
        composeRule.onAllNodesWithText("Checking…").assertCountEquals(0)
    }

    @Test
    fun `a refresh of stale rows says Checking, not Tap to refresh`() {
        // The refresh the stale rows need is the one already running.
        refreshing = true
        val old = now.minusSeconds(3_600)
        show(DeparturesUiState.Loaded(stops = listOf(kingsCross(old)), fetchedAt = old))
        composeRule.onNodeWithText("Checking…").assertExists()
        composeRule.onAllNodesWithText("Tap to refresh").assertCountEquals(0)

        refreshing = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tap to refresh").assertExists()
    }

    @Test
    fun `a pull during a refresh starts nothing over it`() {
        // Asking again would cancel the running refresh's fetch and location fix to start them over.
        refreshing = true
        show(DeparturesUiState.Loaded(stops = listOf(kingsCross(now)), fetchedAt = now))
        composeRule.onRoot().performTouchInput { swipeDown(startY = centerY / 2, endY = bottom) }
        composeRule.waitForIdle()
        assertEquals(0, pulls)

        refreshing = false
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput { swipeDown(startY = centerY / 2, endY = bottom) }
        composeRule.waitForIdle()
        assertEquals(1, pulls)
    }

    @Test
    fun `a pull still refreshes, and lets go of its indicator`() {
        show(DeparturesUiState.Loaded(stops = listOf(kingsCross(now)), fetchedAt = now))
        composeRule.onRoot().performTouchInput { swipeDown(startY = centerY / 2, endY = bottom) }
        composeRule.waitForIdle()
        assertEquals(1, pulls)
        // The refresh it asked for runs: still no spinner, the stamp says so instead.
        refreshing = true
        composeRule.waitForIdle()
        composeRule.onAllNodes(spinners).assertCountEquals(0)
        composeRule.onNodeWithText("Checking…").assertExists()
    }
}
