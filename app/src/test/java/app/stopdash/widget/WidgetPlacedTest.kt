package app.stopdash.widget

import androidx.test.core.app.ApplicationProvider
import app.stopdash.ThreadRecorder
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        // The last of a kind removed: the host is asked off the main thread whether the other kind is placed.
        receiver.onDisabled(context)
        runBlocking { withTimeout(10_000) { WidgetPresence.placed.first { it == false } } }
    }

    @Test
    fun `removing the last of one kind keeps presence while the other kind is placed`() = runBlocking {
        WidgetPresence.set(true)
        presenceAfterRemoval(ApplicationProvider.getApplicationContext(), WidgetPresence.generation()) { true }
        assertEquals(true, WidgetPresence.placed.value)
        presenceAfterRemoval(ApplicationProvider.getApplicationContext(), WidgetPresence.generation()) { false }
        assertEquals(false, WidgetPresence.placed.value)
    }

    @Test
    fun `both kinds of widget count as placed`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        org.robolectric.Shadows.shadowOf(manager).setAllowedToBindAppWidgets(true)
        manager.bindAppWidgetIdIfAllowed(8, android.content.ComponentName(context, StopDashCompactWidgetReceiver::class.java))
        assertEquals(listOf(8), placedWidgetIds(context).toList())
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

    @Test
    fun `placed widgets are found by the receiver, not by Glance's class-name map`() {
        // Glance's getGlanceIds keys on StopDashWidget's class name, which R8 renames between
        // releases; the receiver's component name is pinned by the manifest.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        org.robolectric.Shadows.shadowOf(manager).setAllowedToBindAppWidgets(true)
        assertEquals(0, placedWidgetIds(context).size)

        manager.bindAppWidgetIdIfAllowed(7, android.content.ComponentName(context, StopDashWidgetReceiver::class.java))

        assertEquals(listOf(7), placedWidgetIds(context).toList())
    }

    @Test
    fun `a redraw finds and updates the placed widgets off the caller's thread`() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            val threads = ThreadRecorder()
            val widgets = listOf(
                object : androidx.glance.GlanceId {} to StopDashWidget(),
                object : androidx.glance.GlanceId {} to StopDashCompactWidget(),
            )
            runBlocking(caller) {
                redrawWidgets(
                    ApplicationProvider.getApplicationContext(),
                    io = worker,
                    glanceIds = {
                        threads.note()
                        widgets
                    },
                    update = { _, _ -> threads.note() },
                )
            }
            // One lookup, then one update per widget, all on the worker.
            assertEquals(listOf("test-worker", "test-worker", "test-worker"), threads.threads())
        } finally {
            caller.close()
            worker.close()
        }
    }
}
