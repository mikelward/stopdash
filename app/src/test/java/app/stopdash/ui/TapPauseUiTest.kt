package app.stopdash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
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
 * A loading screen fades through to what replaces it, and a tap during that fade is ignored, so one
 * aimed at its Update available button can't open the departure row that took the button's place
 * (SPEC *Update indicator*). Driven through [StopDashAppRoot], as the app composes it, with the tap
 * pause's own clock ([now]) apart from the frame clock, so a test can show the pause follows the fade
 * rather than the time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TapPauseUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    // 0 is the loading screen; any other number is the list, a different snapshot of it each.
    private var phase by mutableIntStateOf(0)
    private val tapped = mutableListOf<Int>()
    private var opened = 0
    private var now = 0L

    private fun show(clickableRows: Boolean = false) {
        composeRule.setContent {
            StopDashAppRoot(tapPause = TapPause { now }) {
                FadesThroughFromLoading(state = phase, loading = { it == 0 }, modifier = Modifier.fillMaxSize()) { shown ->
                    if (shown == 0) {
                        Box(Modifier.fillMaxSize()) {
                            UpdateAvailableButton(
                                onClick = { opened++ },
                                modifier = Modifier.align(Alignment.BottomCenter).testTag("update"),
                            )
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                            items(60) { i ->
                                val row = Modifier.fillMaxWidth().height(48.dp)
                                Text(
                                    "Row $i",
                                    if (clickableRows) row.clickable { tapped += i } else row.pointerInput(Unit) { detectTapGestures { tapped += i } },
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun buttonSpot(): Offset = composeRule.onNodeWithTag("update").fetchSemanticsNode().boundsInRoot.center

    /** The departures arrive: one frame later the fade has started, and the clock is held there. */
    private fun arrive(next: Int = 1) {
        composeRule.mainClock.autoAdvance = false
        phase = next
        // The clock is held, so nothing else would tell Compose the state changed before the frame.
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
    }

    /**
     * Runs the fade to its end: it starts on the frame after the departures arrive, and lets go of the
     * pause on the frame after its last. The pause's own clock moves on as far, past its floor.
     */
    private fun finishFade() {
        composeRule.mainClock.advanceTimeBy(FADE_THROUGH_MILLIS + 2 * FRAME_MILLIS)
        now += FADE_THROUGH_MILLIS + 2 * FRAME_MILLIS
    }

    private fun tap(at: Offset) {
        composeRule.onRoot().performTouchInput { click(at) }
    }

    @Test
    fun `the button opens the listing while it shows`() {
        show()
        tap(buttonSpot())
        assertEquals(1, opened)
    }

    @Test
    fun `a tap meant for the button opens no row while the rows fade in`() {
        show()
        val spot = buttonSpot()
        arrive()
        tap(spot)
        assertEquals(emptyList<Int>(), tapped)
        assertEquals(0, opened)
    }

    @Test
    fun `a clickable row ignores it too`() {
        show(clickableRows = true)
        val spot = buttonSpot()
        arrive()
        tap(spot)
        assertEquals(emptyList<Int>(), tapped)
    }

    @Test
    fun `a tap anywhere else during the fade is ignored as well`() {
        show()
        arrive()
        // The middle of the screen, which the growing list covers from its first frame (a corner
        // isn't until it has grown).
        composeRule.onRoot().performTouchInput { click(center) }
        assertEquals(emptyList<Int>(), tapped)
    }

    @Test
    fun `the loading screen fades out while the rows fade in, then is gone`() {
        show()
        arrive()
        composeRule.onNodeWithTag("update").assertExists()
        composeRule.onNodeWithTag("list").assertExists()
        finishFade()
        composeRule.onNodeWithTag("update").assertDoesNotExist()
    }

    @Test
    fun `taps reach the rows once the fade is done`() {
        show()
        val spot = buttonSpot()
        arrive()
        finishFade()
        tap(spot)
        assertEquals(1, tapped.size)
        tap(Offset(10f, 10f))
        assertEquals(listOf(tapped.first(), 0), tapped)
    }

    @Test
    fun `taps stay ignored while the fade has yet to finish, however long it has been`() {
        show()
        val spot = buttonSpot()
        arrive()
        // A second on the pause's clock, but no frame has drawn the fade any further.
        now += 1_000
        tap(spot)
        assertEquals(emptyList<Int>(), tapped)
        finishFade()
        tap(spot)
        assertEquals(1, tapped.size)
    }

    @Test
    fun `loading again partway through the fade frees taps at once`() {
        show()
        val spot = buttonSpot()
        arrive()
        arrive(next = 0)
        tap(spot)
        assertEquals(1, opened)
        assertEquals(emptyList<Int>(), tapped)
    }

    @Test
    fun `a new snapshot of the list is no handover, so taps go straight through`() {
        phase = 1
        show()
        arrive(next = 2)
        tap(Offset(10f, 10f))
        assertEquals(listOf(0), tapped)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}
