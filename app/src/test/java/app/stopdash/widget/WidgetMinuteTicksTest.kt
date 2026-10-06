package app.stopdash.widget

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The widget's once-a-minute redraw while the app is open or a trip is followed. */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetMinuteTicksTest {
    private val start = Instant.parse("2026-10-06T08:00:20Z")

    @Test
    fun `it redraws just after each minute while held, and stops when the hold ends`() = runTest {
        val held = MutableStateFlow(0)
        var redraws = 0
        val ticks = launch { minuteTicks(held, { start.plusMillis(testScheduler.currentTime) }) { redraws++ } }
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        assertEquals("nothing holds it: no redraw", 0, redraws)

        held.value = 1
        runCurrent()
        val first = start.plusMillis(testScheduler.currentTime)
        // The first comes just after the next whole minute, not a minute from now.
        advanceTimeBy(untilNextMinute(first) + 1)
        assertEquals(1, redraws)
        advanceTimeBy(60_000L)
        assertEquals(2, redraws)

        // A second holder doesn't double the redraws.
        held.value = 2
        runCurrent()
        advanceTimeBy(60_000L)
        assertEquals(3, redraws)

        held.value = 0
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        assertEquals(3, redraws)
        ticks.cancel()
    }

    @Test
    fun `the wait runs to just past the next whole minute`() {
        assertEquals(40_050L, untilNextMinute(Instant.parse("2026-10-06T08:00:20Z")))
        assertEquals(60_050L, untilNextMinute(Instant.parse("2026-10-06T08:01:00Z")))
    }

    @Test
    fun `it wakes where a countdown changes, not only on the clock's minute`() = runTest {
        val held = MutableStateFlow(1)
        var redraws = 0
        // A change 10 s from the start, well before the next whole minute (40 s away).
        val change = start.plusSeconds(10)
        val ticks = launch {
            minuteTicks(held, { start.plusMillis(testScheduler.currentTime) }, { change.takeIf { it > start.plusMillis(testScheduler.currentTime) } }) { redraws++ }
        }
        runCurrent()
        advanceTimeBy(10_001L)
        assertEquals(1, redraws)
        ticks.cancel()
    }

    @Test
    fun `the next change is a departure, or just after a whole minute before one`() {
        fun train(due: String) = app.stopdash.domain.Departure("victoria", "Victoria", "inbound", "Brixton", null, Instant.parse(due), "tube")
        // Due at 08:02:01 with 91 s left: its count goes from "2 min" to "1 min" just after 08:01:01.
        assertEquals(Instant.parse("2026-10-06T08:01:01.001Z"), nextCountdownChange(listOf(train("2026-10-06T08:02:01Z")), Instant.parse("2026-10-06T08:00:30Z")))
        // With 31 s left, the next change is the departure itself (Codex on #612).
        assertEquals(Instant.parse("2026-10-06T08:02:01Z"), nextCountdownChange(listOf(train("2026-10-06T08:02:01Z")), Instant.parse("2026-10-06T08:01:30Z")))
        assertEquals(null, nextCountdownChange(listOf(train("2026-10-06T08:00:00Z")), Instant.parse("2026-10-06T08:01:30Z")))
    }
}
