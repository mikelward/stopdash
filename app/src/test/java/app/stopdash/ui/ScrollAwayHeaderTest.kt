package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A header taller than a short window scrolls away with the list under it, rather than staying fixed
 * and leaving the list no room (a phone on its side), and comes back only with the list's top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w480dp-h320dp-160dpi")
class ScrollAwayHeaderTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var list: LazyListState
    private lateinit var away: ScrollAwayState

    private fun show(headerHeight: Int = 250, rows: Int = 30, scrollingBody: Boolean = true) {
        composeRule.setContent {
            list = rememberLazyListState()
            away = rememberScrollAwayState()
            Box(Modifier.size(width = 400.dp, height = 300.dp)) {
                ScrollAwayHeader(
                    modifier = Modifier.fillMaxSize().testTag("page"),
                    state = away,
                    header = { Box(Modifier.fillMaxWidth().height(headerHeight.dp).testTag("header")) },
                ) {
                    if (scrollingBody) {
                        LazyColumn(Modifier.fillMaxSize().testTag("body"), state = list) {
                            items(rows) { Text("Row $it", Modifier.fillMaxWidth().height(50.dp)) }
                        }
                    } else {
                        Box(Modifier.fillMaxSize().testTag("body"))
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // The body's place on screen; the header's is [ScrollAwayState.offset], as a header scrolled off is
    // clipped out of the bounds a test can read.
    private fun top(tag: String) = composeRule.onNodeWithTag(tag).getBoundsInRoot().top.value
    private fun height(tag: String) = composeRule.onNodeWithTag(tag).getBoundsInRoot().let { (it.bottom - it.top).value }

    @Test
    fun the_body_takes_what_the_header_leaves() {
        show()
        assertEquals(0f, away.offset, 0.5f)
        assertEquals(250f, top("body"), 0.5f)
        assertEquals(50f, height("body"), 0.5f)
    }

    @Test
    fun scrolling_the_list_up_takes_the_header_away_first() {
        show()
        composeRule.onNodeWithTag("page").performTouchInput { swipeUp(startY = 290f, endY = 10f) }
        composeRule.waitForIdle()
        // The header went before the list moved far: the list now has the whole window.
        assertEquals(-250f, away.offset, 0.5f)
        assertEquals(0f, top("body"), 0.5f)
        assertEquals(300f, height("body"), 0.5f)
    }

    @Test
    fun the_header_comes_back_only_with_the_lists_top() {
        show()
        repeat(3) { composeRule.onNodeWithTag("page").performTouchInput { swipeUp(startY = 290f, endY = 10f) } }
        composeRule.waitForIdle()
        assertTrue(list.firstVisibleItemIndex > 0)
        // A small pull down moves the list, not the header.
        composeRule.onNodeWithTag("page").performTouchInput { swipeDown(startY = 100f, endY = 160f) }
        composeRule.waitForIdle()
        assertEquals(-250f, away.offset, 0.5f)
        repeat(10) { composeRule.onNodeWithTag("page").performTouchInput { swipeDown(startY = 10f, endY = 290f) } }
        composeRule.waitForIdle()
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0f, away.offset, 0.5f)
    }

    @Test
    fun a_body_that_doesnt_scroll_still_lets_the_header_be_dragged_away() {
        show(headerHeight = 400, scrollingBody = false)
        composeRule.onNodeWithTag("page").performTouchInput { swipeUp(startY = 290f, endY = 10f) }
        composeRule.waitForIdle()
        assertTrue(away.offset < 0f)
        assertTrue(height("body") > 0f)
    }

    @Test
    fun a_header_that_grows_while_scrolled_away_stays_out_of_sight() {
        val extra = mutableStateOf(0)
        composeRule.setContent {
            list = rememberLazyListState()
            away = rememberScrollAwayState()
            Box(Modifier.size(width = 400.dp, height = 300.dp)) {
                ScrollAwayHeader(modifier = Modifier.fillMaxSize().testTag("page"), state = away, header = {
                    Box(Modifier.fillMaxWidth().height(250.dp))
                    Box(Modifier.fillMaxWidth().height(extra.value.dp))
                }) {
                    LazyColumn(Modifier.fillMaxSize().testTag("body"), state = list) {
                        items(30) { Text("Row $it", Modifier.fillMaxWidth().height(50.dp)) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("page").performTouchInput { swipeUp(startY = 290f, endY = 10f) }
        composeRule.waitForIdle()
        assertEquals(0f, top("body"), 0.5f)
        // A banner lands in the header while it's out of sight: the routes don't move.
        extra.value = 60
        composeRule.waitForIdle()
        assertEquals(0f, top("body"), 0.5f)
        assertEquals(-310f, away.offset, 0.5f)
    }

    @Test
    fun a_list_in_the_header_scrolls_itself_before_the_header_moves() {
        lateinit var inner: LazyListState
        composeRule.setContent {
            list = rememberLazyListState()
            inner = rememberLazyListState()
            away = rememberScrollAwayState()
            Box(Modifier.size(width = 400.dp, height = 300.dp)) {
                ScrollAwayHeader(modifier = Modifier.fillMaxSize().testTag("page"), state = away, header = {
                    LazyColumn(Modifier.fillMaxWidth().height(200.dp).testTag("inner"), state = inner) {
                        items(20) { Text("Direct $it", Modifier.fillMaxWidth().height(50.dp)) }
                    }
                }) {
                    LazyColumn(Modifier.fillMaxSize().testTag("body"), state = list) {
                        items(30) { Text("Row $it", Modifier.fillMaxWidth().height(50.dp)) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inner").performTouchInput { swipeUp(startY = 190f, endY = 110f) }
        composeRule.waitForIdle()
        // The Direct rows moved; the header, and the rows with it, stayed where the rider can read them.
        assertTrue(inner.firstVisibleItemIndex > 0 || inner.firstVisibleItemScrollOffset > 0)
        assertEquals(0f, away.offset, 0.5f)
    }

    @Test
    fun the_header_stays_scrolled_away_across_recreation() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            list = rememberLazyListState()
            away = rememberScrollAwayState()
            Box(Modifier.size(width = 400.dp, height = 300.dp)) {
                ScrollAwayHeader(modifier = Modifier.fillMaxSize().testTag("page"), state = away, header = {
                    Box(Modifier.fillMaxWidth().height(250.dp))
                }) {
                    LazyColumn(Modifier.fillMaxSize().testTag("body"), state = list) {
                        items(30) { Text("Row $it", Modifier.fillMaxWidth().height(50.dp)) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("page").performTouchInput { swipeUp(startY = 290f, endY = 10f) }
        composeRule.waitForIdle()
        assertEquals(-250f, away.offset, 0.5f)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        // The list comes back where it was, and the header doesn't stand over it.
        assertEquals(-250f, away.offset, 0.5f)
        assertEquals(0f, top("body"), 0.5f)
    }
}
