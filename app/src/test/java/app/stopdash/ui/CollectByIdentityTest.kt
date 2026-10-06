package app.stopdash.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A flow's value taken in by identity ([collectByIdentityWithLifecycle]): the main thread never compares a
 * new value with the last by its contents (AGENTS.md *Main thread: read and dispatch only*; Codex, #627).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class CollectByIdentityTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Counts every comparison by contents Compose's state makes: one is a walk of whatever the value
    // holds. The flow's own, where it's written, is the one that's meant to happen.
    private var compared = 0

    private inner class Probe(val id: Int) {
        override fun equals(other: Any?): Boolean {
            if (Thread.currentThread().stackTrace.any { it.className.startsWith("androidx.compose.runtime") }) compared++
            return this === other
        }

        override fun hashCode(): Int = id
    }

    @Test
    fun `a new value is shown without being compared with the last`() {
        val flow = MutableStateFlow(Probe(1))
        composeRule.setContent {
            val probe by flow.collectByIdentityWithLifecycle()
            Text("probe ${probe.id}")
        }
        composeRule.onNodeWithText("probe 1").assertExists()
        flow.value = Probe(2)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("probe 2").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("compared by contents on the main thread", 0, compared)
    }
}
