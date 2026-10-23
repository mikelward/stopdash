package app.stopdash.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class SettingReadTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun a_setting_read_while_the_screen_waits_shows_at_once() {
        // The store lands while the screen holds: it shows then, without waiting on [loaded]'s own return.
        val isLoaded = MutableStateFlow(false)
        composeRule.setContent {
            Text(if (rememberSettingRead(isLoaded, { CompletableDeferred<Unit>().await() })) "read" else "holding")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("holding").assertExists()
        isLoaded.value = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("read").assertExists()
    }

    @Test
    fun a_setting_not_yet_read_holds_until_it_is() {
        // A cold start: the stored choice lands after the first frame, so the screen holds rather than draw by the
        // default and redraw (the near-me list grouped by platform, then by station).
        val read = CompletableDeferred<Unit>()
        composeRule.setContent {
            Text(if (rememberSettingRead(MutableStateFlow(false), { read.await() })) "read" else "holding")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("holding").assertExists()
        read.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("read").assertExists()
    }

    @Test
    fun a_setting_already_read_shows_on_the_first_frame() {
        // Read before the screen opened: no held frame, though the store's own wait never returns.
        val shown = mutableListOf<Boolean>()
        composeRule.setContent {
            val ready = rememberSettingRead(MutableStateFlow(true), { CompletableDeferred<Unit>().await() })
            shown += ready
            Text(ready.toString())
        }
        composeRule.waitForIdle()
        assertEquals(true, shown.first())
    }
}
