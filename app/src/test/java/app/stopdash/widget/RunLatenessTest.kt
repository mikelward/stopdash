package app.stopdash.widget

import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How late a background job ran ([RunLateness]), on uptime while it and the wall clock agree, and
 * on both, saying so, when the clock was set or the device restarted in between (Codex).
 */
class RunLatenessTest {
    private val minute = 60_000L

    // Scheduled at uptime 1 h and wall time 10 h, due a minute later.
    private val input = workDataOf(*RunLateness.dueIn(minute, uptimeMillis = 60 * minute, wallMillis = 600 * minute))

    @Test
    fun `measured on uptime while the clocks agree`() {
        val late = RunLateness.of(input, uptimeMillis = 71 * minute, wallMillis = 611 * minute)!!
        assertTrue(late.clocksAgree)
        assertEquals("600 s", late.describe())
        assertTrue(late.atLeast(10 * minute))
        assertFalse(late.atLeast(11 * minute))
    }

    @Test
    fun `a wall clock set forward while it waited isn't taken for lateness, and says so`() {
        // Ran on time by uptime; the wall clock was moved on 3 h meanwhile.
        val late = RunLateness.of(input, uptimeMillis = 61 * minute, wallMillis = 781 * minute)!!
        assertFalse(late.clocksAgree)
        assertEquals("0 s by uptime, 10800 s by the wall clock (the clock was set or the device restarted)", late.describe())
    }

    @Test
    fun `a restart, which uptime starts again from, gives both figures too`() {
        // Uptime 5 min into a new boot, 4 h later by the wall clock.
        val late = RunLateness.of(input, uptimeMillis = 5 * minute, wallMillis = 841 * minute)!!
        assertFalse(late.clocksAgree)
        assertTrue("4 h late by the wall clock counts", late.atLeast(2 * minute))
    }

    @Test
    fun `one scheduled without a due time, as an older build did, can't be told`() {
        assertNull(RunLateness.of(workDataOf("due" to 1L), uptimeMillis = minute, wallMillis = minute))
    }
}
