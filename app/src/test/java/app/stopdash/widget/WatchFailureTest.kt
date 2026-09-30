package app.stopdash.widget

import app.stopdash.data.WatchRefreshOutcome
import app.stopdash.domain.TflException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Why a stop's fetch failed, as a watch's refresh reports it (dev-docs/wear-os.md *Refresh*). */
class WatchFailureTest {

    @Test
    fun `each failure maps to what the watch says`() {
        assertEquals(WatchRefreshOutcome.Failure.RATE_LIMITED, watchFailureOf(TflException.RateLimited(null)))
        // A refused key is its own case: the watch says to clear it, not that TfL is down (SPEC D7).
        assertEquals(WatchRefreshOutcome.Failure.KEY_REJECTED, watchFailureOf(TflException.KeyRejected(null)))
        assertEquals(WatchRefreshOutcome.Failure.UNREACHABLE, watchFailureOf(TflException.Unreachable("HTTP 503", null)))
        assertEquals(WatchRefreshOutcome.Failure.UNREACHABLE, watchFailureOf(TflException.Offline(null)))
        assertEquals(WatchRefreshOutcome.Failure.UNREACHABLE, watchFailureOf(IllegalStateException("unexpected")))
    }
}
