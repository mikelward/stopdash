package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.ui.theme.StopDashTheme
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [rememberComputed]: the UI's one way to derive what it shows, off the main thread (AGENTS.md *Main
 * thread: read and dispatch only*), showing what it has at once and holding a late change behind
 * "Tap to see" (SPEC *Engineering quality bar*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class ComputedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `an answer in time replaces the placeholder`() {
        composeRule.setContent {
            val shown = rememberComputed("A", placeholder = "placeholder") { "worked out" }
            Text(shown.value)
        }
        composeRule.onNodeWithText("worked out").assertExists()
    }

    @Test
    fun `a late change waits behind Tap to see, and a tap shows it`() {
        var input by mutableStateOf("first")
        composeRule.setContent {
            // Every answer counts as late: only the first, over the placeholder, goes up unasked.
            CompositionLocalProvider(LocalComputeBudgetMillis provides -1L) {
                StopDashTheme {
                    Column {
                        val shown = rememberComputed(input, placeholder = "placeholder", resetOnChange = false) { "$input answer" }
                        Text(shown.value)
                        UpdateChip(shown)
                    }
                }
            }
        }
        composeRule.onNodeWithText("first answer").assertExists()
        composeRule.onNodeWithText("Tap to see").assertDoesNotExist()

        input = "second"
        composeRule.waitForIdle()
        // The rider's page doesn't move under them.
        composeRule.onNodeWithText("first answer").assertExists()
        composeRule.onNodeWithText("Tap to see").performClick()
        composeRule.onNodeWithText("second answer").assertExists()
        composeRule.onNodeWithText("Tap to see").assertDoesNotExist()
    }

    @Test
    fun `a late answer that shows the same needs no tap`() {
        var input by mutableStateOf("first")
        composeRule.setContent {
            CompositionLocalProvider(LocalComputeBudgetMillis provides -1L) {
                StopDashTheme {
                    Column {
                        val shown = rememberComputed(input, placeholder = "placeholder", resetOnChange = false) { "same answer" }
                        Text(shown.value)
                        UpdateChip(shown)
                    }
                }
            }
        }
        input = "second"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("same answer").assertExists()
        composeRule.onNodeWithText("Tap to see").assertDoesNotExist()
    }

    @Test
    fun `new keys put the placeholder back, never the old answer`() {
        var input by mutableStateOf("first")
        val gate = CompletableDeferred<Unit>()
        composeRule.setContent {
            val shown = rememberComputed(input, placeholder = "placeholder") {
                if (input == "second") gate.await()
                "$input answer"
            }
            Text(shown.value)
        }
        composeRule.onNodeWithText("first answer").assertExists()
        input = "second"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("placeholder").assertExists()
        gate.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("second answer").assertExists()
    }

    @Test
    fun `a page reopened shows its last answer in its first frame`() {
        val memo = listOf("ComputedTest", "reopened")
        var open by mutableStateOf(true)
        val never = CompletableDeferred<String>()
        var calls = 0
        composeRule.setContent {
            if (open) {
                val shown = rememberComputed("A", placeholder = "placeholder", memo = memo) {
                    // The first visit works it out; the second never finishes, so only the memo shows.
                    if (calls++ == 0) "remembered" else never.await()
                }
                Text(shown.value)
            }
        }
        composeRule.onNodeWithText("remembered").assertExists()
        open = false
        composeRule.waitForIdle()
        open = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("remembered").assertExists()
        assertEquals(2, calls)
    }

    @Test
    fun `a reopened page's remembered answer gives way to its new one, however late`() {
        val memo = listOf("ComputedTest", "late")
        var open by mutableStateOf(true)
        var answer = "remembered"
        composeRule.setContent {
            // Every answer counts as late: none could replace a value the rider is reading unasked.
            CompositionLocalProvider(LocalComputeBudgetMillis provides -1L) {
                if (open) {
                    val shown = rememberComputed("A", placeholder = "placeholder", memo = memo) { answer }
                    Text(shown.value)
                }
            }
        }
        composeRule.onNodeWithText("remembered").assertExists()
        open = false
        composeRule.waitForIdle()
        answer = "current"
        open = true
        composeRule.waitForIdle()
        // The memo's answer is only a stand-in for this visit's, so no tap is needed to replace it.
        composeRule.onNodeWithText("current").assertExists()
    }

    @Test
    fun `the work runs on the worker, not the main thread`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            var ranOn: String? = null
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    val shown = rememberComputed("A", placeholder = "placeholder") {
                        ranOn = Thread.currentThread().name
                        "worked out"
                    }
                    Text(shown.value)
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { ranOn != null }
            composeRule.waitForIdle()
            // Debug coroutines append " @coroutine#n" to the name; the thread is what matters.
            assertEquals("test-worker", ranOn?.substringBefore(" @"))
        } finally {
            worker.close()
        }
    }
}
