package app.stopdash.widget

import androidx.test.core.app.ApplicationProvider
import app.stopdash.ThreadRecorder
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetPlacedTest {
    @Test
    fun `whether a widget is placed is read off the caller's thread`() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            val threads = ThreadRecorder()
            val placed = runBlocking(caller) {
                widgetPlaced(ApplicationProvider.getApplicationContext(), io = worker) { threads.note(); true }
            }
            assertEquals(true, placed)
            assertEquals(listOf("test-worker"), threads.threads())
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `the receiver says when the first widget is placed and the last removed`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = StopDashWidgetReceiver()
        receiver.onEnabled(context)
        assertEquals(true, WidgetPresence.placed.value)
        receiver.onDisabled(context)
        assertEquals(false, WidgetPresence.placed.value)
    }

    @Test
    fun `a host that can't be asked leaves presence unknown, and says so`() = runBlocking {
        val warnings = mutableListOf<String>()
        val placed = widgetPlaced(ApplicationProvider.getApplicationContext(), warn = { warnings += it }) {
            throw IllegalStateException("no host")
        }
        assertEquals(null, placed)
        assertEquals(listOf("widget presence read failed: IllegalStateException"), warnings)
    }

    @Test
    fun `a host answer begun before a receiver event doesn't overwrite it`() {
        val since = WidgetPresence.generation()
        // The last widget removed while the host was being asked.
        WidgetPresence.set(false)
        assertFalse(WidgetPresence.setIfUnchanged(true, since))
        assertEquals(false, WidgetPresence.placed.value)
        // With nothing newer, the host's answer is kept.
        assertTrue(WidgetPresence.setIfUnchanged(true, WidgetPresence.generation()))
        assertEquals(true, WidgetPresence.placed.value)
    }

    @Test
    fun `a hold saved by another process isn't trusted`() {
        assertTrue(WidgetPresence.heldHere(WidgetPresence.process))
        assertFalse(WidgetPresence.heldHere(0L))
        assertFalse(WidgetPresence.heldHere(WidgetPresence.process + 1))
    }

    @Test
    fun `a card that's gone stops holding at once`() {
        assertTrue(WidgetPresence.holds(WidgetPresence.process, gone = false))
        assertFalse(WidgetPresence.holds(WidgetPresence.process, gone = true))
        assertFalse(WidgetPresence.holds(0L, gone = false))
    }
}
