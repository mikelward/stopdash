package app.stopdash.ui

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The trip list's reveal line for the debug log: how long it took and what it last waited for. */
class RevealLogTest {
    private val clock = TestTimeSource()
    private val log = RevealLog(clock.markNow())

    private fun waiting(hasPlan: Boolean = true, hasCards: Boolean = true, routesLoading: Int = 0, rowIn: Boolean = true, widthsIn: Boolean = true) =
        log.waitingFor(hasPlan, hasCards, refreshing = false, planning = false, checking = false, ordered = true, widthsIn = widthsIn, rowIn = rowIn, routesLoading = routesLoading)

    @Test
    fun `names what the list last waited for, with when its routes came`() {
        log.note(false, waiting(hasPlan = false, hasCards = false))
        clock += 2_100.milliseconds
        log.note(true, waiting(routesLoading = 3, rowIn = false))
        clock += 2_000.milliseconds
        log.note(true, waiting(routesLoading = 1))
        clock += 900.milliseconds
        log.note(true, waiting())
        assertEquals("trip list shown after 5000 ms (routes in at 2100 ms), last waited for line routes (1)", log.shown())
    }

    @Test
    fun `a list shown at its cap says what it was still waiting for`() {
        log.note(true, waiting(widthsIn = false, rowIn = false))
        clock += REVEAL_CAP_MILLIS.milliseconds
        assertEquals(
            "trip list shown after 8000 ms (routes in at 0 ms), at its cap, still waiting for its pill columns, the disruptions row",
            log.shown(),
        )
    }

    @Test
    fun `says it once`() {
        log.note(true, waiting())
        log.shown()
        assertNull(log.shown())
    }

    @Test
    fun `the plan in, its cards still being timed, is the frame's wait, not the Planner's`() {
        // A slow frame on the worker isn't charged to the Planner (Codex, #563): the routes are in when
        // the plan is, and what follows is named apart.
        log.note(false, waiting(hasPlan = false, hasCards = false))
        clock += 1_000.milliseconds
        log.note(true, waiting(hasCards = false))
        clock += 1_500.milliseconds
        log.note(true, waiting())
        assertEquals("trip list shown after 2500 ms (routes in at 1000 ms), last waited for its cards' times", log.shown())
    }
}
