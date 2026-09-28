package app.stopdash.widget

import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineStatus
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The widget applies dismissals as it draws, so a change to them has to redraw it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetDismissalRedrawTest {
    private val severe = DismissedAlert.ofLineStatus(LineStatus("victoria", 6, "Severe Delays"))

    @Test
    fun `the first set and each change redraw, a repeat doesn't`() = runTest {
        val dismissed = MutableStateFlow<Set<DismissedAlert>>(setOf(severe))
        var redraws = 0
        val job = launch { WidgetDismissalRedraw.redrawOnChange({ dismissed }) { redraws++ } }
        runCurrent()
        // The first set too: a dismissal saved just before the process died may never have been drawn.
        assertEquals(1, redraws)
        // A dismissal a refresh has since forgotten redraws, so the mark comes back.
        dismissed.value = emptySet()
        runCurrent()
        assertEquals(2, redraws)
        job.cancel()
    }

    @Test
    fun `a failed redraw is tried again`() = runTest {
        val dismissed = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        var attempts = 0
        var drawn: Set<DismissedAlert>? = null
        val job = launch {
            WidgetDismissalRedraw.redrawOnChange({ dismissed }, retryMs = 1_000) {
                attempts++
                if (attempts == 2) throw IllegalStateException("no host")
                drawn = dismissed.value
            }
        }
        runCurrent()
        dismissed.value = setOf(severe)
        runCurrent()
        // The set the failed redraw was for isn't counted as drawn: it's tried again after the wait.
        assertEquals(emptySet<DismissedAlert>(), drawn)
        advanceTimeBy(1_001)
        assertEquals(3, attempts)
        assertEquals(setOf(severe), drawn)
        job.cancel()
    }

    @Test
    fun `a redraw that keeps failing backs off further each time`() = runTest {
        val dismissed = MutableStateFlow<Set<DismissedAlert>>(setOf(severe))
        var attempts = 0
        val job = launch {
            WidgetDismissalRedraw.redrawOnChange({ dismissed }, retryMs = 1_000, maxRetryMs = 4_000) {
                attempts++
                throw IllegalStateException("no host")
            }
        }
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1_001)
        assertEquals(2, attempts)
        // Then 2 s, not 1 s again.
        advanceTimeBy(1_500)
        assertEquals(2, attempts)
        advanceTimeBy(600)
        assertEquals(3, attempts)
        job.cancel()
    }

    @Test
    fun `a failed read restarts the collection, and a set already drawn isn't redrawn`() = runTest {
        val live = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        var reads = 0
        val source: () -> Flow<Set<DismissedAlert>> = {
            reads++
            if (reads == 1) flow { emit(emptySet()); throw IOException("disk") } else live
        }
        var redraws = 0
        val job = launch { WidgetDismissalRedraw.redrawOnChange(source, retryMs = 1_000) { redraws++ } }
        runCurrent()
        // The first set, then once for the failed read.
        assertEquals(2, redraws)
        advanceTimeBy(1_001)
        assertEquals(2, reads)
        // The restart draws what it reads, since the last draw followed a failure.
        assertEquals(3, redraws)
        // A dismissal after the failure still reaches the widget.
        live.value = setOf(severe)
        runCurrent()
        assertEquals(4, redraws)
        job.cancel()
    }

    @Test
    fun `a read that keeps failing redraws once, so the widget stops hiding what it can't vouch for`() = runTest {
        var reads = 0
        val source: () -> Flow<Set<DismissedAlert>> = {
            reads++
            if (reads == 1) flow { emit(setOf(severe)); throw IOException("disk") } else flow { throw IOException("disk") }
        }
        var redraws = 0
        val job = launch { WidgetDismissalRedraw.redrawOnChange(source, retryMs = 1_000) { redraws++ } }
        runCurrent()
        // The dismissal is drawn, then the read fails: redrawn, and the widget reads none.
        assertEquals(2, redraws)
        advanceTimeBy(10_000)
        assertTrue(reads > 2)
        // Further failures don't redraw again.
        assertEquals(2, redraws)
        job.cancel()
    }
}
