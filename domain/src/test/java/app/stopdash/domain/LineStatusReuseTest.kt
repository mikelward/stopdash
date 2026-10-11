package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a line's last status check stands, by the rule the app and the widget share. */
class LineStatusReuseTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")
    private val reuse: Duration = Duration.ofSeconds(90)

    @Test
    fun `a check stands until it is as old as the window`() {
        assertTrue(LineStatusReuse.stands(now, now, reuse))
        assertTrue(LineStatusReuse.stands(now.minusSeconds(89), now, reuse))
        assertFalse(LineStatusReuse.stands(now.minusSeconds(90), now, reuse))
        assertFalse("no window, no reuse", LineStatusReuse.stands(now, now, Duration.ZERO))
    }

    @Test
    fun `a check from the future or awaiting its alerts' directions is asked again`() {
        assertFalse(LineStatusReuse.stands(now.plusSeconds(5), now, reuse))
        assertFalse(LineStatusReuse.stands(now.minusSeconds(10), now, reuse, awaitingDirections = true))
    }
}
