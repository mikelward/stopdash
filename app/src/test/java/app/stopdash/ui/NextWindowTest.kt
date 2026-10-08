package app.stopdash.ui

import app.stopdash.domain.JourneyAlertSchedule
import app.stopdash.domain.TimeWindow
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window "Add time" suggests is never one already set, which saving would silently drop. */
class NextWindowTest {
    private fun hour(start: Int, end: Int) = TimeWindow(LocalTime.of(start, 0), LocalTime.of(end, 0))

    @Test
    fun a_default_window_not_yet_set_comes_first() {
        val schedule = JourneyAlertSchedule(windows = JourneyAlertSchedule.DEFAULT_WINDOWS.take(1))
        assertEquals(JourneyAlertSchedule.DEFAULT_WINDOWS[1], nextWindow(schedule))
    }

    @Test
    fun otherwise_the_hour_after_the_last() {
        val schedule = JourneyAlertSchedule(windows = JourneyAlertSchedule.DEFAULT_WINDOWS + hour(19, 20))
        assertEquals(hour(20, 21), nextWindow(schedule))
    }

    @Test
    fun a_last_window_at_the_end_of_the_day_isnt_proposed_again() {
        val schedule = JourneyAlertSchedule(windows = JourneyAlertSchedule.DEFAULT_WINDOWS + hour(22, 23))
        val next = nextWindow(schedule)
        assertTrue(next.valid)
        assertTrue(next !in schedule.windows)
    }

    @Test
    fun the_dialog_model_is_worked_out_off_the_callers_thread() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "compute") }
        try {
            var ranOn: String? = null
            val compute = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    pool.execute { ranOn = Thread.currentThread().name; block.run() }
                }
            }
            val schedule = JourneyAlertSchedule(windows = JourneyAlertSchedule.DEFAULT_WINDOWS)
            val edit = kotlinx.coroutines.runBlocking { windowEdit("${hour(8, 10).start}-${hour(8, 10).end}", schedule, compute) }
            assertEquals("compute", ranOn)
            assertEquals(hour(8, 10), edit.old)
            assertEquals(setOf(JourneyAlertSchedule.DEFAULT_WINDOWS[1]), edit.taken)
            // A new window: none edited, and it isn't one already set.
            val added = kotlinx.coroutines.runBlocking { windowEdit("", schedule, compute) }
            assertEquals(null, added.old)
            assertTrue(added.initial !in schedule.windows)
        } finally {
            pool.shutdown()
        }
    }
}
