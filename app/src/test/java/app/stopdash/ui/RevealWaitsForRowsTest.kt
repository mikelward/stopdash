package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.stopdash.domain.Departure
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a cold load the snapshot arrives before its rows are worked out ([LocalWorker]), and a spinner
 * stands in meanwhile. The reveal, and the tap pause with it, waits for the rows themselves: played
 * on that spinner, it would be over by the time the rows took its place, in one frame, under a finger
 * (Codex, #714).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class RevealWaitsForRowsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val kingsCross = StopArrivals(
        stopId = "940GZZLUKSX",
        stopName = "King's Cross St. Pancras",
        departures = listOf(Departure("victoria", "Victoria", "southbound", "Brixton", null, now.plusSeconds(240), "tube")),
        fetchedAt = now,
    )

    @Test
    fun `taps stay paused while the rows come in, however long they took to work out`() {
        // Held until released by hand, so the rows are worked out only when the test says.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val pause = TapPause { composeRule.mainClock.currentTime }
        var state by mutableStateOf<DeparturesUiState>(DeparturesUiState.Loading)
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held, LocalTapPause provides pause) {
                    MainScreen(state = state, now = now, onRefresh = {}, listKey = "kings-cross")
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        state = DeparturesUiState.Loaded(stops = listOf(kingsCross), fetchedAt = now)
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        // The snapshot is in, but not its rows, for longer than any reveal takes.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(0)

        // The rows are in: they come in now, and taps wait for them.
        repeat(5) {
            scheduler.advanceUntilIdle()
            composeRule.mainClock.advanceTimeByFrame()
        }
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(1)
        assertTrue(pause.ignoresTaps())

        composeRule.mainClock.advanceTimeBy(REVEAL_MILLIS + 100L)
        assertFalse(pause.ignoresTaps())
    }
}
