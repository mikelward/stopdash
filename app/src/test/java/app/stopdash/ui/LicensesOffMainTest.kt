package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.stopdash.R
import app.stopdash.ThreadRecorder
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withJson
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
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
 * The licenses dialog never scans every library in composition (AGENTS.md *Main thread: read and
 * dispatch only*): a tap opens the library it named, and a restored dialog, with only its id left,
 * is found again on the worker ([LocalWorker]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class LicensesOffMainTest {
    // Effects on a queued dispatcher, as the app's main thread is: the rule's default resumes an effect
    // unconfined, on whichever thread handed it back, so the worker's answer was written, and the
    // dialog sometimes built, on the worker ("Can't create handler inside thread licenses-worker").
    @get:Rule
    val composeRule = createComposeRule(effectContext = StandardTestDispatcher())

    /** Each pass over the libraries notes its thread; the list's rows read by index, not by a pass. */
    private val scans = ThreadRecorder()

    /** The thread of each state write during the restore. */
    private val stateWrites = ThreadRecorder()

    private val libraries: Libs = run {
        val loaded = Libs.Builder().withJson(ApplicationProvider.getApplicationContext(), R.raw.aboutlibraries).build()
        Libs(RecordingList(loaded.libraries, scans), loaded.licenses)
    }

    @Test
    fun a_tap_opens_its_library_without_a_scan_or_the_worker() {
        // Held, never run: a dialog that needed the worker would never open.
        val held = StandardTestDispatcher()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) { LicensesContent(libraries) }
        }
        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertIsDisplayed()
        assertEquals(emptyList<String>(), scans.threads())
    }

    @Test
    fun a_restored_dialog_finds_its_library_on_the_worker_thread() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "licenses-worker") }.asCoroutineDispatcher()
        worker.use {
            // Every state write the screen makes notes its thread: the worker's answer is published on the
            // main thread, never written from the worker, where it would recompose (and build the dialog) there.
            // Disposed however the test ends: the observer is process-wide.
            val writes = Snapshot.registerGlobalWriteObserver { stateWrites.note() }
            try {
                val restoration = StateRestorationTester(composeRule)
                restoration.setContent {
                    CompositionLocalProvider(LocalWorker provides worker) { LicensesContent(libraries) }
                }
                composeRule.onNodeWithText("Activity").performClick()
                restoration.emulateSavedInstanceStateRestore()
                composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTextExists("Version 1.13.0") }
            } finally {
                writes.dispose()
            }
        }
        composeRule.onNodeWithText("Version 1.13.0").assertIsDisplayed()
        assertTrue(scans.threads().isNotEmpty())
        assertEquals(setOf("licenses-worker"), scans.threads().toSet())
        assertEquals(setOf(Thread.currentThread().name.substringBefore(" @")), stateWrites.threads().toSet())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextExists(text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private class RecordingList(
        private val items: List<Library>,
        private val scans: ThreadRecorder,
    ) : AbstractList<Library>(), ImmutableList<Library> {
        override val size: Int get() = items.size
        override fun get(index: Int): Library = items[index]
        override fun iterator(): Iterator<Library> {
            scans.note()
            return items.iterator()
        }
        override fun subList(fromIndex: Int, toIndex: Int): ImmutableList<Library> =
            items.subList(fromIndex, toIndex).toImmutableList()
    }
}
