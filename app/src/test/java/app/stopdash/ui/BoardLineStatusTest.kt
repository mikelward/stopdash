package app.stopdash.ui

import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteDisruption
import app.stopdash.domain.SteadyClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** A board line's status on the way ([boardLineStatus]), as its page shows it. */
class BoardLineStatusTest {
    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")
    private val delays = LineStatus("jubilee", 9, "Minor Delays")
    private val checked = RouteDisruption.LinesChecked(setOf("jubilee", "dlr"), mapOf("jubilee" to delays), SteadyClock.stamp(now))

    // A check out asking about [lines], which the trip follows now, as the tracker says once one goes out.
    private fun asking(vararg lines: String) = checked.copy(asking = setOf(*lines), following = setOf(*lines))

    @Test
    fun `checking only while a check under way asks about the line`() {
        // A check out asking about it, none in yet for it.
        val out = boardLineStatus(asking("jubilee", "elizabeth"), "elizabeth", now)
        assertTrue(out.checking)
        assertFalse(out.unknown)
        assertNull(out.status)
        // No check asks about it: the board failed, or moved on to the next ride. Never "Checking…" for
        // good, but couldn't check (Codex, #627).
        val dropped = boardLineStatus(checked, "elizabeth", now)
        assertFalse(dropped.checking)
        assertTrue(dropped.unknown)
        // Nor before any check has started.
        val none = boardLineStatus(null, "jubilee", now)
        assertFalse(none.checking)
        assertTrue(none.unknown)
        // Gone stale while the next check asks again: checking, its status kept beside that.
        val again = boardLineStatus(asking("jubilee"), "jubilee", now.plus(Duration.ofHours(1)))
        assertTrue(again.checking)
        assertEquals(delays, again.status)
        // A current answer stands while the next check is out.
        assertFalse(boardLineStatus(asking("jubilee"), "jubilee", now).checking)
        // A check under way that no longer asks about it (the board moved on): its current status is no
        // longer followed, so it's kept beside "Couldn't check", never shown as current (Codex, #627).
        val unfollowed = boardLineStatus(asking("elizabeth"), "jubilee", now)
        assertTrue(unfollowed.unknown)
        assertFalse(unfollowed.checking)
        assertEquals(delays, unfollowed.status)
        // No check out, but the trip no longer follows it (its board moved on, or failed): not current from
        // then, whenever the next check goes out, its status kept beside "Couldn't check" (Codex, #627).
        val moved = boardLineStatus(checked.copy(following = setOf("dlr")), "jubilee", now)
        assertTrue(moved.unknown)
        assertFalse(moved.checking)
        assertEquals(delays, moved.status)
        // The last check left it out, and the next one asks again: checking while it's out (Codex, #627),
        // couldn't check once none asks.
        val retry = boardLineStatus(asking("dlr"), "dlr", now)
        assertTrue(retry.checking)
        assertFalse(retry.unknown)
        val left = boardLineStatus(checked, "dlr", now)
        assertTrue(left.unknown)
        assertFalse(left.checking)
    }

    @Test
    fun `a line answered has its status, current`() {
        val found = boardLineStatus(checked, "jubilee", now)
        assertEquals(delays, found.status)
        assertFalse(found.unknown)
        assertFalse(found.checking)
    }

    @Test
    fun `a line left out, failed, stale or with no id couldn't be checked`() {
        // TfL left it out of an answer.
        assertTrue(boardLineStatus(checked, "dlr", now).unknown)
        // The request failed.
        assertTrue(boardLineStatus(RouteDisruption.LinesChecked(setOf("jubilee"), emptyMap(), null), "jubilee", now).unknown)
        // Gone stale, its status is kept beside the doubt.
        val stale = boardLineStatus(checked, "jubilee", now.plus(Duration.ofHours(1)))
        assertTrue(stale.unknown)
        assertEquals(delays, stale.status)
        // No id to ask about.
        val blank = boardLineStatus(checked, "", now)
        assertTrue(blank.unknown)
        assertFalse(blank.checking)
    }
}
