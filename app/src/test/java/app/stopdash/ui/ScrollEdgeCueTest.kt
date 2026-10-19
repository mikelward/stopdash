package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scroll-edge cue: an edge gets its fade and chevron only while the list scrolls on past it. White
 * rows under a black edge, a blue plate and a red glyph, so each part of the cue is told apart by
 * color.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h480dp-160dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollEdgeCueTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var state: LazyListState
    private val clicked = mutableListOf<Int>()

    private fun show(rows: Int, pause: TapPause? = null) {
        composeRule.setContent {
            state = rememberLazyListState()
            Box(Modifier.size(200.dp).then(if (pause != null) Modifier.ignoresPausedTaps(pause) else Modifier)) {
                LazyColumn(
                    modifier = Modifier.testTag("list").size(200.dp).scrollEdgeCue(state, ScrollCueColors(edge = Color.Black, plate = Color.Blue, glyph = Color.Red)),
                    state = state,
                ) {
                    items(rows) { row -> Box(Modifier.fillMaxWidth().height(50.dp).background(Color.White).clickable { clicked += row }) }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // The color at the top and bottom rows of the list, in the middle of its width.
    private fun edges(): Pair<Color, Color> {
        val pixels = composeRule.onNodeWithTag("list").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, 0] to pixels[pixels.width / 2, pixels.height - 1]
    }

    // At 160dpi a dp is a pixel. The chevron's plate is centered 18dp in from the edge (4dp inset plus
    // its 14dp radius); its glyph spans the middle 12dp, so 10dp left of center is plate alone.
    private fun plate(top: Boolean): Color {
        val pixels = composeRule.onNodeWithTag("list").captureToImage().toPixelMap()
        val y = if (top) 18 else pixels.height - 1 - 18
        return pixels[pixels.width / 2 - 10, y]
    }

    // The arrow's point: the down arrow's tip sits 4dp below the plate's center, the up arrow's above.
    private fun glyph(top: Boolean): Color {
        val pixels = composeRule.onNodeWithTag("list").captureToImage().toPixelMap()
        val y = if (top) 18 - 3 else pixels.height - 1 - 18 + 3
        return pixels[pixels.width / 2, y]
    }

    @Test
    fun `a list with more below cues only its bottom edge`() {
        show(rows = 10)
        val (top, bottom) = edges()
        assertEquals(Color.White, top)
        assertNotEquals(Color.White, bottom)
        assertEquals(Color.White, plate(top = true))
        assertEquals(Color.Blue, plate(top = false))
        assertTrue(glyph(top = false).red > 0.5f && glyph(top = false).green < 0.5f)
    }

    @Test
    fun `scrolled to the end, only the top edge is cued`() {
        show(rows = 10)
        composeRule.runOnIdle { runBlocking { state.scrollToItem(9) } }
        composeRule.waitForIdle()
        val (top, bottom) = edges()
        assertNotEquals(Color.White, top)
        assertEquals(Color.White, bottom)
        assertEquals(Color.Blue, plate(top = true))
        assertEquals(Color.White, plate(top = false))
        assertTrue(glyph(top = true).red > 0.5f && glyph(top = true).green < 0.5f)
    }

    @Test
    fun `a list that fits shows no cue`() {
        show(rows = 4)
        val (top, bottom) = edges()
        assertEquals(Color.White, top)
        assertEquals(Color.White, bottom)
        assertEquals(Color.White, plate(top = true))
        assertEquals(Color.White, plate(top = false))
    }

    // At 160dpi a dp is a pixel: the list is 200px tall, its chevrons centered 18px in from each edge.
    private fun tap(x: Float, y: Float) {
        composeRule.onNodeWithTag("list").performTouchInput { click(Offset(x, y)) }
        composeRule.waitForIdle()
    }

    @Test
    fun `a tap on the bottom chevron scrolls down a page and presses no row`() {
        show(rows = 20)
        tap(100f, 182f)
        composeRule.runOnIdle {
            // 200px down: four 50px rows.
            assertEquals(4, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertEquals(emptyList<Int>(), clicked)
        }
    }

    @Test
    fun `a tap on the chevron while taps are paused pages nothing`() {
        val pause = TapPause()
        pause.hold()
        show(rows = 20, pause = pause)
        tap(100f, 182f)
        composeRule.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(emptyList<Int>(), clicked)
        }
    }

    @Test
    fun `a pinch that starts on the chevron pages nothing`() {
        show(rows = 20)
        composeRule.onNodeWithTag("list").performTouchInput {
            down(0, Offset(100f, 182f))
            down(1, Offset(100f, 100f))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
    }

    @Test
    fun `a pinch with its second finger outside the list pages nothing`() {
        composeRule.setContent {
            state = rememberLazyListState()
            // The app's pinch host around the screen, as the theme puts it, and a bar beside the list
            // whose touches never reach the list.
            Column(
                Modifier.testTag("screen").size(200.dp, 250.dp)
                    .pinchFontSize(enabled = { true }, scale = { 1f }, onStart = {}, onPreview = {}, onSettled = {}),
            ) {
                Box(Modifier.fillMaxWidth().height(50.dp).clickable {})
                LazyColumn(
                    modifier = Modifier.size(200.dp).scrollEdgeCue(state, ScrollCueColors(edge = Color.Black, plate = Color.Blue, glyph = Color.Red)),
                    state = state,
                ) {
                    items(20) { Box(Modifier.fillMaxWidth().height(50.dp).background(Color.White)) }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen").performTouchInput {
            // The bottom chevron, 18px above the list's foot, under the 50px bar.
            down(0, Offset(100f, 232f))
            down(1, Offset(100f, 25f))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
        // And the next tap on the chevron, a gesture of its own, still pages.
        composeRule.onNodeWithTag("screen").performTouchInput { click(Offset(100f, 232f)) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(4, state.firstVisibleItemIndex) }
    }

    @Test
    fun `the chevron's target reaches the very edge`() {
        show(rows = 20)
        tap(100f, 199f)
        composeRule.runOnIdle { assertEquals(4, state.firstVisibleItemIndex) }
    }

    @Test
    fun `a tap on the top chevron scrolls up a page`() {
        show(rows = 20)
        composeRule.runOnIdle { runBlocking { state.scrollToItem(10) } }
        composeRule.waitForIdle()
        tap(100f, 18f)
        composeRule.runOnIdle {
            assertEquals(6, state.firstVisibleItemIndex)
            assertEquals(emptyList<Int>(), clicked)
        }
    }

    @Test
    fun `a tap beside the chevron, or where none shows, goes to the row`() {
        show(rows = 20)
        // Beside the bottom chevron's target, and just inside it from the edge's far side.
        tap(20f, 182f)
        tap(100f, 150f)
        // Where the top chevron would be, at the top of the list.
        tap(100f, 18f)
        composeRule.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(listOf(3, 3, 0), clicked)
        }
    }

    @Test
    fun `a drag that starts on the chevron still scrolls the list`() {
        show(rows = 20)
        composeRule.onNodeWithTag("list").performTouchInput { swipe(Offset(100f, 182f), Offset(100f, 82f)) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(state.firstVisibleItemIndex >= 1)
            assertEquals(emptyList<Int>(), clicked)
        }
    }
}
