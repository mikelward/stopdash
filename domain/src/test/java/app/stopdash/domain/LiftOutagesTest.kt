package app.stopdash.domain

import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** TfL's lift outages, asked for at most once a few minutes whichever screen wants them. */
class LiftOutagesTest {
    @After
    fun tearDown() {
        SteadyClock.source = null
    }

    private var now = Instant.parse("2026-10-02T09:00:00Z")
    private var asks = 0
    private var answer: () -> Set<String> = { setOf("HUBBAN-Lift-7") }
    private val warnings = mutableListOf<String>()
    private val outages = LiftOutages(source = { asks++; answer() }, clock = { now }, warn = { warnings += it })

    @Test
    fun `an answer is kept for a few minutes, then asked for again`() = runTest {
        assertEquals(LiftsOut(setOf("HUBBAN-Lift-7"), LiftOutages.MAX_AGE), outages.current())
        now = now.plus(LiftOutages.MAX_AGE).minusSeconds(1)
        answer = { setOf("940GZZLUECT-Lift-5") }
        assertEquals(setOf("HUBBAN-Lift-7"), outages.current().ids)
        assertEquals(1, asks)
        now = now.plusSeconds(1)
        assertEquals(setOf("940GZZLUECT-Lift-5"), outages.current().ids)
        assertEquals(2, asks)
    }

    @Test
    fun `a kept answer says how long it has left, so its asker asks again then`() = runTest {
        outages.current()
        now = now.plus(Duration.ofMinutes(4))
        assertEquals(LiftsOut(setOf("HUBBAN-Lift-7"), Duration.ofMinutes(1)), outages.current())
    }

    @Test
    fun `a failed ask keeps the last answer TfL gave, says why, and the next asks again`() = runTest {
        outages.current()
        now = now.plus(LiftOutages.MAX_AGE)
        answer = { throw TflException.Network("transport: IOException", IOException()) }
        // TfL never said the lift was working again, so it's still out.
        assertEquals(LiftsOut(setOf("HUBBAN-Lift-7"), LiftOutages.MAX_AGE), outages.current())
        assertEquals(listOf("lift outages failed: Network"), warnings)
        now = now.plus(LiftOutages.MAX_AGE)
        answer = { emptySet() }
        assertEquals(emptySet<String>(), outages.current().ids)
        assertEquals(3, asks)
    }

    @Test
    fun `a failed ask holds off the next for a few minutes, whoever asks`() = runTest {
        outages.current()
        now = now.plus(LiftOutages.MAX_AGE)
        answer = { throw TflException.RateLimited(null) }
        outages.current()
        // Another screen, or this one reopened, a minute on: TfL isn't asked again.
        now = now.plus(Duration.ofMinutes(1))
        assertEquals(LiftsOut(setOf("HUBBAN-Lift-7"), Duration.ofMinutes(4)), outages.current())
        assertEquals(2, asks)
    }

    @Test
    fun `what's known at once is TfL's last answer, through a failed ask too`() = runTest {
        assertEquals(emptySet<String>(), outages.known)
        outages.current()
        assertEquals(setOf("HUBBAN-Lift-7"), outages.known)
        now = now.plus(LiftOutages.MAX_AGE)
        answer = { throw TflException.Network("transport: IOException", IOException()) }
        outages.current()
        assertEquals(setOf("HUBBAN-Lift-7"), outages.known)
    }

    @Test
    fun `a failed first ask reads as no lifts out, to ask again in a few minutes`() = runTest {
        answer = { throw TflException.Unreachable("HTTP 503", null) }
        assertEquals(LiftsOut(emptySet(), LiftOutages.MAX_AGE), outages.current())
        assertEquals(LiftsOut(emptySet(), LiftOutages.MAX_AGE), outages.current())
        assertEquals(1, asks)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `an ask canceled on the way doesn't count, so the next asks again`() = runTest {
        val never = CompletableDeferred<Set<String>>()
        var first = true
        val outages = LiftOutages(source = { asks++; if (first) { first = false; never.await() } else answer() }, clock = { now })
        // The screen that asked leaves before TfL answers.
        val asking = launch { outages.current() }
        runCurrent()
        asking.cancelAndJoin()
        assertEquals(LiftsOut(setOf("HUBBAN-Lift-7"), LiftOutages.MAX_AGE), outages.current())
        assertEquals(2, asks)
    }

    @Test
    fun `an answer is stamped when it came, in the frame the clock is then in`() = runTest {
        val setBack = object : SteadyClock.Source {
            var by: Duration = Duration.ZERO
            override val frame = SteadyClock.Frame("device/7", 0L)
            override fun offset(): Duration = by
        }
        SteadyClock.source = setBack
        // The clock is set back an hour while TfL is answering.
        val outages = LiftOutages(
            source = {
                asks++
                now = now.minus(Duration.ofHours(1))
                setBack.by = Duration.ofHours(1)
                answer()
            },
            clock = { now },
        )
        outages.current()
        // Just after, the answer is new, so another screen isn't sent to TfL again.
        assertEquals(LiftsOut(setOf("HUBBAN-Lift-7"), LiftOutages.MAX_AGE), outages.current())
        assertEquals(1, asks)
    }

    @Test
    fun `an answer stamped after the clock was set back is asked for again`() = runTest {
        outages.current()
        now = now.minus(Duration.ofHours(1))
        outages.current()
        assertEquals(2, asks)
    }
}
