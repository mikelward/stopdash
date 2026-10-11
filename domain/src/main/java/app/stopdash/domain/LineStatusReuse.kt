package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * Whether a line's last status check still stands, the rule every surface shares (the app's list,
 * the widget, the watch), so one line is never asked about twice within the window while another
 * screen's answer stands (SPEC D3).
 */
object LineStatusReuse {
    /**
     * A check made at [checkedAt] (a [SteadyClock] stamp) still stands at [now] when it's younger than
     * [reuse] — never one from the future, which a clock moved back would make look recent — and wasn't
     * made while a lookup of which way its alerts apply was running ([awaitingDirections]): reused, it
     * would show them both ways for the whole window, so it's asked again. A line TfL left out counts
     * as checked too, with nothing awaiting.
     */
    fun stands(checkedAt: Instant, now: Instant, reuse: Duration, awaitingDirections: Boolean = false): Boolean {
        val age = SteadyClock.age(checkedAt, now)
        return !age.isNegative && age < reuse && !awaitingDirections
    }
}
