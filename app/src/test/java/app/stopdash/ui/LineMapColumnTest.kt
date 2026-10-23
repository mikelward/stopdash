package app.stopdash.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.stopdash.domain.LineMap
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class LineMapColumnTest {
    @get:Rule
    val composeRule = createComposeRule()

    // A made-up line of [count] stations, every one shown, as the All chip draws a whole line.
    private fun whole(count: Int): LineMapUi.Ready {
        val ids = (1..count).map { "S$it" }
        val map = LineMap.of(LineSequence(listOf(LineRoute("S1 - S$count", ids, "outbound")), ids.associateWith { "Station ${it.drop(1)}" }))!!
        return LineMapUi.Ready(map, map.folded(emptySet(), all = true), map.foldable())
    }

    private fun drawnRows() = composeRule.onAllNodesWithText("Station ", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun a_long_map_in_a_page_column_draws_its_first_rows_at_once_and_the_rest_a_batch_a_frame_after() {
        // Drawn in a route page's column, not a lazy list: a whole long line's every row in one frame would stall it.
        val ui = mutableStateOf(whole(LINE_MAP_FIRST_ROWS + 3 * LINE_MAP_ROWS_PER_FRAME))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LineMapColumn(LineMapSectionState(ui.value, atFirst = true, open = {}, toggleAll = {}, retry = {}), Color.Black)
        }
        assertEquals(LINE_MAP_FIRST_ROWS, drawnRows())
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(LINE_MAP_FIRST_ROWS + 3 * LINE_MAP_ROWS_PER_FRAME, drawnRows())

        // A shorter map (the train's route alone) all at once; grown again, in batches again.
        ui.value = whole(10)
        composeRule.waitForIdle()
        assertEquals(10, drawnRows())
        composeRule.mainClock.autoAdvance = false
        ui.value = whole(LINE_MAP_FIRST_ROWS + 2 * LINE_MAP_ROWS_PER_FRAME)
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(LINE_MAP_FIRST_ROWS, drawnRows())
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(LINE_MAP_FIRST_ROWS + 2 * LINE_MAP_ROWS_PER_FRAME, drawnRows())
    }
}
