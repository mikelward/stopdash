package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import app.stopdash.ThreadRecorder
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Disruptions summary's line picker never walks the stored choice on the main thread (AGENTS.md
 * *Main thread: read and dispatch only*): its chips are worked out on [LocalWorker], and until they're
 * in, every chip is already drawn where it will stay, none selected (Codex, #642).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class SummaryPickerOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Each read of the choice notes its thread. */
    private val reads = ThreadRecorder()

    private val chosen: Set<String> = RecordingSet(setOf("victoria"), reads)

    @androidx.compose.runtime.Composable
    private fun page() {
        DisruptionsSummaryPage(
            show = true, onShowChange = {}, showLoaded = true, showWriteFailed = false, onDismissShowError = {},
            networks = chosen, onToggleLine = {}, networksLoaded = true, networksWriteFailed = false,
            onDismissNetworksError = {}, onBack = {},
        )
    }

    @Test
    fun the_chips_are_drawn_at_once_without_the_worker() {
        // Held, never run: every chip is there anyway, none selected or tappable (one shown off that is
        // on would turn off), and the choice never read.
        val held = StandardTestDispatcher()
        composeRule.setContent { CompositionLocalProvider(LocalWorker provides held) { page() } }
        composeRule.onNodeWithTag("summaryLine-victoria").assertIsNotSelected().assertIsNotEnabled()
        composeRule.onNodeWithTag("summaryLine-tram").performScrollTo().assertIsNotSelected()
        assertEquals(emptyList<String>(), reads.threads())
    }

    @Test
    fun the_choice_is_read_on_the_worker_thread() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "picker-worker") }.asCoroutineDispatcher()
        worker.use {
            composeRule.setContent { CompositionLocalProvider(LocalWorker provides worker) { page() } }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasTestTag("summaryLine-victoria") and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.onNodeWithTag("summaryLine-victoria").assertIsSelected().assertIsEnabled()
        assertTrue(reads.threads().isNotEmpty())
        assertEquals(setOf("picker-worker"), reads.threads().toSet())
    }

    private class RecordingSet(private val items: Set<String>, private val reads: ThreadRecorder) : AbstractSet<String>() {
        private fun note() {
            reads.note()
        }
        override val size: Int get() = items.size.also { note() }
        override fun contains(element: String): Boolean = items.contains(element).also { note() }
        override fun iterator(): Iterator<String> = items.iterator().also { note() }
    }
}
