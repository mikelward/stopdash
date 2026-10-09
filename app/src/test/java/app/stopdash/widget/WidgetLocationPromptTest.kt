package app.stopdash.widget

import app.stopdash.ThreadRecorder
import app.stopdash.widget.WidgetLocationPrompt.Drawn
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLocationPromptTest {
    @Test
    fun `the record is read and the widget redrawn off the caller's thread`() {
        // Called as the activity reads the grant, on the main thread: the read and the redraw run on the worker.
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            val threads = ThreadRecorder()
            runBlocking(caller) {
                WidgetLocationPrompt.redrawIfOutdated(
                    allowed = true,
                    drawn = { threads.note(); Drawn.ASKS_FOR_LOCATION },
                    redraw = { threads.note() },
                    io = worker,
                )
            }
            assertEquals(listOf("test-worker", "test-worker"), threads.threads())
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `an empty widget is redrawn when the grant no longer matches what it says, either way`() {
        assertTrue(WidgetLocationPrompt.outdated(Drawn.ASKS_FOR_LOCATION, allowed = true))
        assertTrue(WidgetLocationPrompt.outdated(Drawn.OPEN_THE_APP, allowed = false))
        assertFalse(WidgetLocationPrompt.outdated(Drawn.ASKS_FOR_LOCATION, allowed = false))
        assertFalse(WidgetLocationPrompt.outdated(Drawn.OPEN_THE_APP, allowed = true))
    }

    @Test
    fun `a widget showing departures isn't redrawn for a grant change`() = runBlocking {
        var redraws = 0
        WidgetLocationPrompt.redrawIfOutdated(allowed = false, drawn = { Drawn.NOT_EMPTY }, redraw = { redraws++ })
        WidgetLocationPrompt.redrawIfOutdated(allowed = true, drawn = { Drawn.NOT_EMPTY }, redraw = { redraws++ })
        assertEquals(0, redraws)
    }

    @Test
    fun `a failed redraw puts back what the widget said, so the next read tries again`() = runBlocking {
        var recorded = Drawn.ASKS_FOR_LOCATION
        val warnings = mutableListOf<String>()
        WidgetLocationPrompt.redrawIfOutdated(
            allowed = true,
            drawn = { recorded },
            redraw = { recorded = Drawn.OPEN_THE_APP; throw IllegalStateException("launcher") },
            restore = { recorded = it },
            warn = { warnings += it },
        )
        assertEquals(Drawn.ASKS_FOR_LOCATION, recorded)
        assertEquals(1, warnings.size)
    }
}
