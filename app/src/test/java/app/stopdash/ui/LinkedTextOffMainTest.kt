package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.LinkAnnotation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue

/**
 * Disruption text's links ([LinkedText]) are found on the worker ([LocalWorker]), never in composition
 * (AGENTS.md *Main thread: read and dispatch only*): the text shows plain at once, and its links come
 * when the worker has run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class LinkedTextOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun the_text_shows_at_once_and_its_links_come_from_the_worker() {
        // National Rail's reason is often its page alone (a public National Rail URL, no user data).
        val reason = "https://www.nationalrail.co.uk/service-disruptions/example/"
        // The worker's tasks queue here and run on their own thread only when the test says so, so it
        // can look at the first frame and then record where the link scan ran.
        val queued = LinkedBlockingQueue<Runnable>()
        val worker = Executor { queued.add(it) }.asCoroutineDispatcher()
        val ranOn = mutableSetOf<Thread>()
        fun runQueued() {
            val thread = Thread {
                while (true) {
                    val task = queued.poll() ?: break
                    synchronized(ranOn) { ranOn += Thread.currentThread() }
                    task.run()
                }
            }
            thread.start()
            thread.join()
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) { LinkedText(reason) }
        }
        composeRule.waitForIdle()
        fun shown() = composeRule.onNodeWithText(reason).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(reason, shown().text)
        assertTrue("no links before the worker runs", shown().getLinkAnnotations(0, reason.length).isEmpty())

        val main = Thread.currentThread()
        repeat(5) {
            runQueued()
            composeRule.waitForIdle()
        }
        val link = shown().getLinkAnnotations(0, reason.length).single()
        assertEquals(reason, (link.item as LinkAnnotation.Url).url)
        assertEquals(0 until reason.length, link.start until link.end)
        // The links can only come from the worker's tasks, and none of those ran on the main thread.
        assertTrue("the worker ran", ranOn.isNotEmpty())
        assertFalse("the scan ran off the main thread", main in ranOn)
    }

    @Test
    fun a_changed_text_shows_at_once_without_the_old_ones_links() {
        val first = "https://www.nationalrail.co.uk/service-disruptions/first/"
        val second = "Minor delays: see https://www.nationalrail.co.uk/service-disruptions/second/"
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val text = mutableStateOf(first)
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) { LinkedText(text.value) }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()

        text.value = second
        composeRule.waitForIdle()
        val shown = composeRule.onNodeWithText(second).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(second, shown.text)
        assertTrue("no links before the worker runs", shown.getLinkAnnotations(0, second.length).isEmpty())
    }
}
