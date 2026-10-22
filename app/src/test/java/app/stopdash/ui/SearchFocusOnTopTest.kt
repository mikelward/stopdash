package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A search drawn under a back swipe (Settings or Licenses opened over it, `dev-docs/navigation3.md`) leaves its
 * field alone: focused, it would raise the keyboard over the screen being swiped, swipe let go of or not. It
 * takes focus once it's on top, as it always has on opening (Codex on #1136).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class SearchFocusOnTopTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var onTop by mutableStateOf(false)
    private var shown by mutableStateOf(false)

    /** [search] composed once the window is up, as a page drawn under a swipe is, so a focus it asks for takes. */
    private fun show(search: @Composable () -> Unit) {
        composeRule.setContent {
            StopDashTheme { CompositionLocalProvider(LocalPageOnTop provides onTop) { if (shown) search() } }
        }
        composeRule.waitForIdle()
        shown = true
        composeRule.waitForIdle()
    }

    private fun fieldFocusedOnlyOnTop() {
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        onTop = true
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `the line search takes focus only on top`() {
        show {
            LineSearchScreen(state = LinesViewModel.State(recent = emptyList()), onQueryChange = {}, onOpenLine = {}, onRetry = {}, onBack = {})
        }
        fieldFocusedOnlyOnTop()
    }

    @Test
    fun `the station search takes focus only on top`() {
        show {
            StationSearchScreen(state = StationSearchViewModel.State(yoursRead = true), onQueryChange = {}, onOpenStation = {}, onBack = {})
        }
        fieldFocusedOnlyOnTop()
    }
}
