package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.ConsumeForegroundReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A page composed while it isn't on top ([LocalPageOnTop]), as a page under a back gesture is, starts nothing for
 * the rider, and does what it held back once it's on top: a gesture let go of leaves no trace on the page it showed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class PageOnTopTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an effect waits for its page to be on top, and runs again each time it comes back`() {
        var onTop by mutableStateOf(false)
        var runs = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalPageOnTop provides onTop) {
                OnTopEffect(Unit) { runs++ }
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, runs)
        onTop = true
        composeRule.waitForIdle()
        assertEquals(1, runs)
        onTop = false
        composeRule.waitForIdle()
        assertEquals(1, runs)
        onTop = true
        composeRule.waitForIdle()
        assertEquals(2, runs)
    }

    @Test
    fun `what runs while started starts only on top, and stops as the page leaves the top`() {
        var onTop by mutableStateOf(false)
        var shown by mutableStateOf(true)
        val calls = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalPageOnTop provides onTop) {
                if (shown) StartedWhileOnTop(onStart = { calls += "start" }, onStop = { calls += "stop" })
            }
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls)
        onTop = true
        composeRule.waitForIdle()
        assertEquals(listOf("start"), calls)
        onTop = false
        composeRule.waitForIdle()
        assertEquals(listOf("start", "stop"), calls)
        // Leaving composition while not on top stops nothing more: nothing was started.
        shown = false
        composeRule.waitForIdle()
        assertEquals(listOf("start", "stop"), calls)
    }

    @Test
    fun `a foreground return owed is left for the page the rider is on`() {
        var onTop by mutableStateOf(false)
        var pending by mutableStateOf(true)
        var relocates = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalPageOnTop provides onTop) {
                ConsumeForegroundReturn(
                    pending = pending,
                    isBusy = { false },
                    onConsumed = { pending = false },
                    onRelocate = { relocates++ },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, relocates)
        assertTrue("the latch is left as it was", pending)
        onTop = true
        composeRule.waitForIdle()
        assertEquals(1, relocates)
        assertEquals(false, pending)
    }

    @Test
    fun `a page tells its model nothing while it isn't on top, and the latest once it is`() {
        var onTop by mutableStateOf(false)
        var reported by mutableStateOf(Reported(listOf("a")))
        val told = mutableListOf<List<String>>()
        val owner = Any()
        composeRule.setContent {
            CompositionLocalProvider(LocalPageOnTop provides onTop) {
                ReportChanges(reported, owner) { told += it }
            }
        }
        composeRule.waitForIdle()
        reported = Reported(listOf("b"))
        composeRule.waitForIdle()
        assertEquals(emptyList<List<String>>(), told)
        onTop = true
        composeRule.waitForIdle()
        assertEquals(listOf(listOf("b")), told)
        // Back on top with nothing new: not told again.
        onTop = false
        composeRule.waitForIdle()
        onTop = true
        composeRule.waitForIdle()
        assertEquals(listOf(listOf("b")), told)
    }

    @Test
    fun `a window a page had open shows only while the page is on top`() {
        var onTop by mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalPageOnTop provides onTop) {
                AboutDialog(onOpenLicenses = {}, onDismiss = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(isDialog()).assertDoesNotExist()
        onTop = true
        composeRule.waitForIdle()
        composeRule.onNode(isDialog()).assertExists()
    }
}
