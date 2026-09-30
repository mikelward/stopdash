package app.stopdash.wear

import app.stopdash.data.WatchRefreshOutcome
import app.stopdash.data.WatchRefreshReply
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {
    private val t0 = Instant.parse("2026-09-24T08:00:00Z")

    @Test
    fun `requests are debounced, so taps can't become a polling loop`() {
        assertTrue(RefreshPolicy.shouldSend(null, t0))
        assertFalse(RefreshPolicy.shouldSend(t0, t0.plusSeconds(10)))
        assertTrue(RefreshPolicy.shouldSend(t0, t0.plusSeconds(30)))
        // A clock that went back never blocks a request for good.
        assertTrue(RefreshPolicy.shouldSend(t0, t0.minusSeconds(5)))
    }

    @Test
    fun `a failure shows for a while, a success shows nothing`() {
        val failed = RefreshState.Answered(WatchRefreshOutcome.RATE_LIMITED, t0)
        assertEquals(RefreshNotice.Kind.RATE_LIMITED, RefreshPolicy.notice(failed, t0.plusSeconds(10))?.kind)
        assertNull("gone after a minute", RefreshPolicy.notice(failed, t0.plusSeconds(61)))
        assertEquals(
            RefreshNotice.Kind.UNREACHABLE,
            RefreshPolicy.notice(RefreshState.Answered(WatchRefreshOutcome.UNREACHABLE, t0), t0)?.kind,
        )
        // A refused key says so, not "unreachable": it's cleared on the phone (SPEC D7).
        assertEquals(
            RefreshNotice.Kind.KEY_REJECTED,
            RefreshPolicy.notice(RefreshState.Answered(WatchRefreshOutcome.KEY_REJECTED, t0), t0)?.kind,
        )
        for (ok in listOf(WatchRefreshOutcome.REFRESHED, WatchRefreshOutcome.PARTLY_REFRESHED, WatchRefreshOutcome.DEBOUNCED)) {
            assertNull(RefreshPolicy.notice(RefreshState.Answered(ok, t0), t0))
        }
        assertNull(RefreshPolicy.notice(RefreshState.Idle, t0))
    }

    @Test
    fun `a pending request says so until the timeout, and no answer says the phone is out of reach`() {
        assertEquals(RefreshNotice.Kind.REFRESHING, RefreshPolicy.notice(RefreshState.Pending(t0, 1L), t0.plusSeconds(5))?.kind)
        // Past the timeout the same state reads as out of reach, then nothing once that has shown.
        assertEquals(
            RefreshNotice.Kind.PHONE_OUT_OF_REACH,
            RefreshPolicy.notice(RefreshState.Pending(t0, 1L), t0.plusSeconds(31))?.kind,
        )
        assertNull(RefreshPolicy.notice(RefreshState.Pending(t0, 1L), t0.plusSeconds(91)))
        assertEquals(
            RefreshNotice.Kind.PHONE_OUT_OF_REACH,
            RefreshPolicy.notice(RefreshState.OutOfReach(t0, 1L), t0.plusSeconds(30))?.kind,
        )
    }

    @Test
    fun `a restarted process resumes an unanswered request as waiting or timed out`() {
        assertEquals(RefreshState.Pending(t0, 3L), RefreshPolicy.resumed(3L, t0, t0.plusSeconds(10)))
        assertEquals(
            RefreshState.OutOfReach(t0.plus(RefreshPolicy.TIMEOUT), 3L),
            RefreshPolicy.resumed(3L, t0, t0.plusSeconds(90)),
        )
        // An answer that came before the restart resumes as answered, its notice running on.
        val failed = RefreshState.Answered(WatchRefreshOutcome.RATE_LIMITED, t0.plusSeconds(10))
        assertEquals(failed, RefreshPolicy.resumed(3L, t0, t0.plusSeconds(20), answer = failed))
        assertEquals(
            RefreshNotice.Kind.RATE_LIMITED,
            RefreshPolicy.notice(RefreshPolicy.resumed(3L, t0, t0.plusSeconds(20), answer = failed), t0.plusSeconds(20))?.kind,
        )
        // Either way the phone's answer to it still lands.
        val reply = WatchRefreshReply(3L, WatchRefreshOutcome.UNREACHABLE)
        assertEquals(
            RefreshState.Answered(WatchRefreshOutcome.UNREACHABLE, t0.plusSeconds(90)),
            RefreshPolicy.answer(RefreshPolicy.resumed(3L, t0, t0.plusSeconds(90)), reply, t0.plusSeconds(90)),
        )
    }

    @Test
    fun `an acknowledged request waits longer before saying the phone is out of reach`() {
        val pending = RefreshState.Pending(t0, 4L)
        val acked = RefreshPolicy.acknowledged(pending, 4L)!!
        assertNull("another request's ack", RefreshPolicy.acknowledged(pending, 5L))
        assertEquals(RefreshNotice.Kind.REFRESHING, RefreshPolicy.notice(acked, t0.plusSeconds(90))?.kind)
        assertEquals(
            RefreshNotice.Kind.PHONE_OUT_OF_REACH,
            RefreshPolicy.notice(acked, t0.plus(RefreshPolicy.ACKED_TIMEOUT))?.kind,
        )
        assertEquals(acked, RefreshPolicy.resumed(4L, t0, t0.plusSeconds(90), acked = true))
    }

    @Test
    fun `only an answer to the pending request settles it`() {
        val pending = RefreshState.Pending(t0, 2L)
        assertEquals(
            RefreshState.Answered(WatchRefreshOutcome.REFRESHED, t0.plusSeconds(1)),
            RefreshPolicy.answer(pending, WatchRefreshReply(2L, WatchRefreshOutcome.REFRESHED), t0.plusSeconds(1)),
        )
        // A late answer to an earlier, timed-out request leaves the newer wait (and its timeout) alone.
        assertNull(RefreshPolicy.answer(pending, WatchRefreshReply(1L, WatchRefreshOutcome.REFRESHED), t0.plusSeconds(1)))
        // A slow phone's answer to the latest request replaces "out of reach"; an older one doesn't.
        assertEquals(
            RefreshState.Answered(WatchRefreshOutcome.REFRESHED, t0.plusSeconds(40)),
            RefreshPolicy.answer(RefreshState.OutOfReach(t0, 2L), WatchRefreshReply(2L, WatchRefreshOutcome.REFRESHED), t0.plusSeconds(40)),
        )
        assertNull(RefreshPolicy.answer(RefreshState.OutOfReach(t0, 2L), WatchRefreshReply(1L, WatchRefreshOutcome.REFRESHED), t0))
        assertNull(RefreshPolicy.answer(RefreshState.Idle, WatchRefreshReply(2L, WatchRefreshOutcome.REFRESHED), t0))
    }
}
