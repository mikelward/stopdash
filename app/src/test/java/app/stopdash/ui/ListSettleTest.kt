package app.stopdash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A list's tap targets that move on their own slide there, and taps wait until they stop ([ListSettle]):
 * a re-sort, an item coming in among the rest, a banner above pushing the list, a card's rows trading
 * places. The user's own scroll moves everything too, and never pauses a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class ListSettleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pause = TapPause { composeRule.mainClock.currentTime }
    private var items by mutableStateOf(listOf("A", "B", "C", "D", "E"))
    private var banner by mutableStateOf(false)
    private var shown by mutableStateOf(true)
    private val state = LazyListState()

    private fun show(rowsOf: (String) -> List<String> = { emptyList() }) {
        composeRule.setContent {
            CompositionLocalProvider(LocalTapPause provides pause) {
                Column(Modifier.fillMaxSize()) {
                    if (banner) Text("Banner", Modifier.fillMaxWidth().height(48.dp))
                    if (shown) {
                        SettlingLazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("list"),
                            state = state,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(items, key = { it }) { item ->
                                Column {
                                    Text(item, Modifier.fillMaxWidth().height(ITEM_HEIGHT))
                                    // Each item's rows, keyed as a stop card's are.
                                    rowsOf(item).forEach { row ->
                                        key(row) { SlidesInPlace { Text(row, Modifier.fillMaxWidth().height(ROW_HEIGHT)) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    private fun top(text: String) = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun change(block: () -> Unit) {
        block()
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `a re-sort slides the items to their new places, taps paused until they stop`() {
        show()
        val aWas = top("A")
        val eWas = top("E")

        change { items = listOf("E", "B", "C", "D", "A") }
        // Drawn where it was until it slides: never where it's going first.
        assertEquals(aWas, top("A"))
        // Paused on every frame either is on its way, and only on those.
        assertPausedWhileMoving(mapOf("A" to (aWas to eWas), "E" to (eWas to aWas)))
        assertEquals(aWas, top("E"))
        assertEquals(eWas, top("A"))
    }

    @Test
    fun `a re-sort that interrupts a slide keeps taps paused until the items stop`() {
        // A second refresh lands mid-slide: the list finishes on its own spring, not the slide it was
        // given, and taps stay paused until that has stopped too (Codex, #818).
        show()
        val aWas = top("A")
        val eWas = top("E")
        change { items = listOf("E", "B", "C", "D", "A") }
        repeat(8) { composeRule.mainClock.advanceTimeByFrame() }
        assertTrue(top("A") > aWas + 0.5.dp && top("A") < eWas - 0.5.dp)
        change { items = listOf("A", "B", "C", "D", "E") }
        assertPausedWhileMoving(mapOf("A" to (eWas to aWas), "E" to (aWas to eWas)), frames = 80)
    }

    @Test
    fun `an item coming in among the rest pauses taps while the rest make room`() {
        show()
        val cWas = top("C")
        val dWas = top("D")
        change { items = listOf("A", "B", "X", "C", "D", "E") }
        assertPausedWhileMoving(mapOf("C" to (cWas to dWas)))
    }

    @Test
    fun `an item going pauses taps while the rest close up`() {
        show()
        val bWas = top("B")
        val cWas = top("C")
        change { items = listOf("A", "C", "D", "E") }
        assertPausedWhileMoving(mapOf("C" to (cWas to bWas)))
    }

    @Test
    fun `the first item going pauses taps while the rest close up together`() {
        // A dismissed card at the top: everything under it moves up by the same amount, as a scroll
        // would, but on its own.
        show()
        val aWas = top("A")
        val bWas = top("B")
        change { items = listOf("B", "C", "D", "E") }
        assertPausedWhileMoving(mapOf("B" to (bWas to aWas)))
    }

    @Test
    fun `an item coming in below the rest, where nothing slides, pauses taps`() {
        // Nothing has to make room, so no slide: it appears where a finger may already be heading.
        show()
        change { items = listOf("A", "B", "C", "D", "E", "F") }
        assertTrue(pause.ignoresTaps())
        composeRule.mainClock.advanceTimeBy(TAP_PAUSE_FLOOR_MILLIS + 50L)
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `a change that moves nothing pauses no taps`() {
        var label by mutableStateOf("")
        items = listOf("A", "B")
        composeRule.setContent {
            CompositionLocalProvider(LocalTapPause provides pause) {
                SettlingLazyColumn(Modifier.fillMaxSize(), state, PaddingValues(16.dp), Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it }) { Text("$it$label", Modifier.fillMaxWidth().height(ITEM_HEIGHT)) }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        // A refresh that updates the items where they are.
        change { label = " 2 min" }
        repeat(10) {
            assertFalse("frame $it", pause.ignoresTaps())
            composeRule.mainClock.advanceTimeByFrame()
        }
    }

    @Test
    fun `a scroll never pauses taps`() {
        items = (1..40).map { "Item $it" }
        show()
        // Dragged a little each frame, as a finger scrolls the list.
        repeat(20) {
            composeRule.runOnIdle { runBlocking { state.scrollBy(37f) } }
            composeRule.mainClock.advanceTimeByFrame()
            assertFalse("frame $it", pause.ignoresTaps())
        }
        assertTrue("it scrolled", state.firstVisibleItemIndex > 3)
    }

    @Test
    fun `a fling never pauses taps`() {
        items = (1..80).map { "Item $it" }
        lateinit var fling: () -> Unit
        composeRule.setContent {
            CompositionLocalProvider(LocalTapPause provides pause) {
                val scope = rememberCoroutineScope()
                fling = { scope.launch { state.animateScrollBy(4_000f) } }
                SettlingLazyColumn(Modifier.fillMaxSize(), state, PaddingValues(16.dp), Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it }) { Text(it, Modifier.fillMaxWidth().height(ITEM_HEIGHT)) }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { fling() }
        var frames = 0
        do {
            composeRule.mainClock.advanceTimeByFrame()
            assertFalse("frame $frames", pause.ignoresTaps())
            frames++
        } while (state.isScrollInProgress && frames < 200)
        assertTrue("it scrolled", state.firstVisibleItemIndex > 20)
        repeat(5) {
            composeRule.mainClock.advanceTimeByFrame()
            assertFalse(pause.ignoresTaps())
        }
    }

    @Test
    fun `a banner above pushing the list pauses taps`() {
        show()
        change { banner = true }
        assertTrue(pause.ignoresTaps())
        composeRule.mainClock.advanceTimeBy(TAP_PAUSE_FLOOR_MILLIS + 50L)
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `rows trading places in a card slide there, taps paused until they stop`() {
        var rows by mutableStateOf(listOf("Brixton", "Walthamstow"))
        show { if (it == "A") rows else emptyList() }
        val brixtonWas = top("Brixton")
        val walthamstowWas = top("Walthamstow")

        change { rows = listOf("Walthamstow", "Brixton") }
        assertTrue(pause.ignoresTaps())
        // Drawn where it was, on its way: never where it's going first.
        assertEquals(brixtonWas, top("Brixton"))

        composeRule.mainClock.advanceTimeBy(CARD_MOVE_MILLIS / 2)
        assertTrue(top("Brixton") > brixtonWas && top("Brixton") < walthamstowWas)
        assertTrue(pause.ignoresTaps())

        composeRule.mainClock.advanceTimeBy(CARD_MOVE_MILLIS)
        assertFalse(pause.ignoresTaps())
        assertEquals(walthamstowWas, top("Brixton"))
        assertEquals(brixtonWas, top("Walthamstow"))
    }

    @Test
    fun `rows sliding together outside a list share one hold`() {
        // A trip's board: a card outside any list, three of its rows moving at once.
        var rows by mutableStateOf(listOf("Brixton", "Walthamstow", "Morden"))
        composeRule.setContent {
            CompositionLocalProvider(LocalTapPause provides pause) {
                SlideGroup {
                    Column {
                        rows.forEach { row -> key(row) { SlidesInPlace { Text(row, Modifier.fillMaxWidth().height(ROW_HEIGHT)) } } }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        change { rows = rows.reversed() }
        assertTrue(pause.ignoresTaps())
        assertEquals(1, pause.held)
        composeRule.mainClock.advanceTimeBy(CARD_MOVE_MILLIS + 100L)
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `a card outside a list that grows or shrinks pauses taps`() {
        // A trip's board: a row coming in at the foot of a card moves nothing in it, but pushes down
        // whatever is laid out under the card.
        var rows by mutableStateOf(listOf("Brixton", "Walthamstow"))
        composeRule.setContent {
            CompositionLocalProvider(LocalTapPause provides pause) {
                Column {
                    SlideGroup {
                        Column {
                            rows.forEach { row -> key(row) { SlidesInPlace { Text(row, Modifier.fillMaxWidth().height(ROW_HEIGHT)) } } }
                        }
                    }
                    Text("Under the card", Modifier.fillMaxWidth().height(ROW_HEIGHT))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        change { rows = rows + "Morden" }
        assertTrue(pause.ignoresTaps())
        composeRule.mainClock.advanceTimeBy(TAP_PAUSE_FLOOR_MILLIS + 50L)
        assertFalse(pause.ignoresTaps())

        change { rows = rows.dropLast(1) }
        assertTrue(pause.ignoresTaps())
        composeRule.mainClock.advanceTimeBy(TAP_PAUSE_FLOOR_MILLIS + 50L)
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `taps resume at once when the list leaves mid-slide`() {
        show()
        change { items = listOf("E", "B", "C", "D", "A") }
        composeRule.mainClock.advanceTimeBy(CARD_MOVE_MILLIS / 2)
        assertTrue(pause.ignoresTaps())
        change { shown = false }
        assertFalse(pause.ignoresTaps())
    }

    /**
     * Steps frame by frame through a slide, each of [moves] going from its first place to its second:
     * taps are paused on every frame one of them is between the two, at least one frame is, and taps
     * resume once all have arrived.
     */
    private fun assertPausedWhileMoving(moves: Map<String, Pair<Dp, Dp>>, frames: Int = 40) {
        var between = 0
        repeat(frames) { frame ->
            val moving = moves.any { (text, places) ->
                val (from, to) = places
                val at = top(text)
                at > minOf(from, to) + 0.5.dp && at < maxOf(from, to) - 0.5.dp
            }
            if (moving) {
                between++
                assertTrue("frame $frame", pause.ignoresTaps())
            }
            composeRule.mainClock.advanceTimeByFrame()
        }
        assertTrue("it slid", between > 3)
        moves.forEach { (text, places) -> assertEquals(text, places.second, top(text)) }
        assertFalse("arrived, so taps resume", pause.ignoresTaps())
    }

    private companion object {
        val ITEM_HEIGHT: Dp = 48.dp
        val ROW_HEIGHT: Dp = 40.dp
    }
}
