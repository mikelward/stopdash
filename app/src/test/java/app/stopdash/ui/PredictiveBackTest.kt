package app.stopdash.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A back swipe's page follows it, and only the finished swipe goes back (SPEC *Back*). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class PredictiveBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var dispatcher: OnBackPressedDispatcher
    private val backs = ArrayList<String>()

    private fun show(enabled: Boolean = true, inside: @Composable () -> Unit = {}, under: @Composable () -> Unit = {}) {
        composeRule.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            under()
            PredictiveBackPage(onBack = { backs += "page" }, enabled = enabled) {
                Box(Modifier.fillMaxSize().testTag(PAGE))
                inside()
            }
        }
        composeRule.waitForIdle()
    }

    private fun page(): DpRect = composeRule.onNodeWithTag(PAGE).getBoundsInRoot()

    private fun swipe(vararg progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, edge)) }
        progress.forEach { at ->
            composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(BackEventCompat(at * 300f, 400f, at, edge)) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a back swipe shrinks the page toward the edge it comes from`() {
        show()
        val whole = page()
        swipe(0.25f, 0.5f)
        val following = page()
        // Halfway, the page is 95% of its size, shifted right, away from the left edge swiped from.
        assertEquals(whole.width.value * 0.95f, following.width.value, 0.5f)
        assertTrue(following.left > whole.left + (whole.width * 0.025f))
        assertEquals(emptyList<String>(), backs)
    }

    @Test
    fun `from the right edge it shifts left`() {
        show()
        val whole = page()
        swipe(0.5f, edge = BackEventCompat.EDGE_RIGHT)
        assertTrue(page().right < whole.right - (whole.width * 0.025f))
    }

    @Test
    fun `a canceled swipe eases the page back and doesn't go back`() {
        show()
        val whole = page()
        swipe(0.6f)
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()
        assertEquals(whole, page())
        assertEquals(emptyList<String>(), backs)
    }

    @Test
    fun `a finished swipe goes back once`() {
        show()
        val whole = page()
        swipe(0.4f, 0.8f)
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(listOf("page"), backs)
        // A page its Back left in place stands whole again.
        assertEquals(whole, page())
    }

    @Test
    fun `a Back with no gesture goes back at once`() {
        show()
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        assertEquals(listOf("page"), backs)
    }

    @Test
    fun `an inner page's own Back goes first`() {
        show(inside = { BackHandler { backs += "inner" } })
        swipe(0.5f)
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        assertEquals(listOf("inner"), backs)
    }

    @Test
    fun `disabled, Back goes to the page under it`() {
        show(enabled = false, under = { BackHandler { backs += "under" } })
        val whole = page()
        swipe(0.5f)
        assertEquals(whole, page())
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        assertEquals(listOf("under"), backs)
    }

    private companion object {
        const val PAGE = "page"
    }
}

private operator fun androidx.compose.ui.unit.Dp.times(factor: Float) = (value * factor).dp
