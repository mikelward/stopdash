package app.stopdash.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.stopdash.StopDashAppRoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With the system's animations slowed (developer options' animator duration scale), the row-by-row
 * reveal runs longer, and taps stay paused until it ends rather than for the usual 300 ms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TapPauseSlowedUiTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = AnimationScale(5f))

    @Test
    fun `a tap stays ignored while a slowed reveal is still running, then reaches the rows`() {
        val screen = LoadingThenRows(composeRule)
        val spot = screen.arrive()

        // Past the usual 300 ms, but the reveal has 5 × 300 to run.
        composeRule.mainClock.advanceTimeBy(3L * REVEAL_MILLIS)
        screen.tap(spot)
        assertEquals(emptyList<Int>(), screen.tapped)

        composeRule.mainClock.advanceTimeBy(3L * REVEAL_MILLIS)
        screen.tap(spot)
        assertEquals(1, screen.tapped.size)
    }
}

/**
 * With animations off, the rows are shown at once, but taps stay paused for the floor, so a
 * tap already on its way to the button still opens no row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TapPauseAnimationsOffUiTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = AnimationScale(0f))

    @Test
    fun `a tap is ignored for the floor, then reaches the rows`() {
        val screen = LoadingThenRows(composeRule)
        val spot = screen.arrive()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("update").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(TAP_PAUSE_FLOOR_MILLIS / 2)
        screen.tap(spot)
        assertEquals(emptyList<Int>(), screen.tapped)

        composeRule.mainClock.advanceTimeBy(TAP_PAUSE_FLOOR_MILLIS)
        screen.tap(spot)
        assertEquals(1, screen.tapped.size)
    }

    @Test
    fun `a stall before the rows first show uses none of the floor`() {
        var now = 0L
        val screen = LoadingThenRows(composeRule) { now }
        val spot = screen.arrive()
        // The main thread stalls for a second before the rows' first frame.
        now += 1_000
        composeRule.mainClock.advanceTimeByFrame()
        screen.tap(spot)
        assertEquals(emptyList<Int>(), screen.tapped)

        now += TAP_PAUSE_FLOOR_MILLIS
        screen.tap(spot)
        assertEquals(1, screen.tapped.size)
    }
}

private class AnimationScale(override val scaleFactor: Float) : MotionDurationScale

/** The loading screen, with its Update available button, giving way to rows that record each tap. */
private class LoadingThenRows(
    private val composeRule: ComposeContentTestRule,
    clock: (() -> Long)? = null,
) {
    private var phase by mutableIntStateOf(0)
    val tapped = mutableListOf<Int>()

    init {
        composeRule.setContent {
            StopDashAppRoot(tapPause = TapPause(clock ?: { composeRule.mainClock.currentTime })) {
                RevealsAfterLoading(state = phase, loading = { it == 0 }, modifier = Modifier.fillMaxSize()) { shown ->
                    Shown(shown)
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Shown(shown: Int) {
        if (shown == 0) {
            Box(Modifier.fillMaxSize()) {
                UpdateAvailableButton(onClick = {}, modifier = Modifier.align(Alignment.BottomCenter).testTag("update"))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(60) { i ->
                    Text("Row $i", Modifier.fillMaxWidth().height(48.dp).pointerInput(Unit) { detectTapGestures { tapped += i } })
                }
            }
        }
    }

    /** The rows arrive, with the clock held from that frame on; returns where the button was. */
    fun arrive(): Offset {
        val spot = composeRule.onNodeWithTag("update").fetchSemanticsNode().boundsInRoot.center
        composeRule.mainClock.autoAdvance = false
        phase = 1
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        return spot
    }

    fun tap(at: Offset) {
        composeRule.onRoot().performTouchInput { click(at) }
    }
}
