package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a shown trip refreshes on the rider's fixes as they come (SPEC *On the way*): synthetic stops only. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripFixesTest {
    private val t0 = Instant.parse("2026-10-01T08:00:00Z")

    // A ten-minute walk to a placed bus stop: a fix is wanted while the rider walks, to see them there.
    private val walk = TripLeg(TripLeg.WALKING, "", "", "A", "Stop A", "B", "Stop B", t0, t0.plusSeconds(600))
    private val bus = TripLeg(
        "bus", "1", "1", "B", "Stop B", "C", "Stop C", t0.plusSeconds(660), t0.plusSeconds(1200),
        fromAt = Coordinates(51.5, -0.12),
    )
    private val trip = ActiveTrip(TripRoute(listOf(walk, bus)), "Stop C", startedAt = t0)

    private fun fix(accuracy: Float = 10f, ageMillis: Long = 1_000) =
        LocationFix(Coordinates(51.501, -0.12), isFallback = false, accuracyMeters = accuracy, ageMillis = ageMillis)

    private fun TestScope.fixes() = TripFixes { currentTime }

    @Test
    fun `each fix reaches a collector though a refresh clears it first`() = runTest {
        // A walk's distance follows every fix; the refresh loop takes one from [TripFixes.latest] and
        // clears it, perhaps before that collector has looked (Codex, #542).
        val fixes = TripFixes(elapsed = { 0L })
        val seen = mutableListOf<Long>()
        val collecting = launch { fixes.each.collect { seen += it.seq } }
        runCurrent()
        fixes.offer(fix())
        fixes.clear()
        runCurrent()
        assertEquals(listOf(1L), seen)
        assertNull(fixes.latest.value)
        collecting.cancel()
        // None is held for a collector that comes later: a position is used and dropped.
        fixes.offer(fix())
        val late = mutableListOf<Long>()
        val after = launch { fixes.each.collect { late += it.seq } }
        runCurrent()
        assertTrue(late.isEmpty())
        after.cancel()
    }

    @Test
    fun `a fix brings the next refresh sooner, but never inside the gap`() = runTest {
        val fixes = fixes()
        val woke = async { awaitRefresh(fixes.latest, Duration.ofSeconds(30), Duration.ofSeconds(10)) }
        // One during the gap is passed over: it would be that much older by the refresh.
        advanceTimeBy(5_000)
        fixes.offer(fix(accuracy = 20f))
        advanceTimeBy(7_000)
        runCurrent()
        assertFalse(woke.isCompleted)
        // The first after it wakes the refresh at once.
        fixes.offer(fix(accuracy = 12f))
        runCurrent()
        assertEquals(12f, woke.await()?.fix?.accuracyMeters)
        assertEquals(12_000L, currentTime)
    }

    @Test
    fun `with no fix, the timer still refreshes`() = runTest {
        assertNull(awaitRefresh(fixes().latest, Duration.ofSeconds(30), Duration.ofSeconds(10)))
        assertEquals(30_000L, currentTime)
    }

    @Test
    fun `location is watched only while the trip wants a fix, and only fixes of use to it are kept`() = runTest {
        val kept = MutableStateFlow<ActiveTrip?>(trip)
        val fixes = fixes()
        val updates = MutableSharedFlow<LocationFix>()
        var watching = 0
        val watched = flow {
            watching++
            updates.collect { emit(it) }
        }.onCompletion { watching-- }
        val job = launch { watchTripFixes(kept, fixes, { watched }, now = { t0.plusMillis(currentTime) }) }
        runCurrent()
        assertEquals("the rider is walking to a placed stop", 1, watching)
        updates.emit(fix())
        runCurrent()
        assertEquals(1L, fixes.latest.value?.seq)
        // Too vague to see them at the stop: it would only wake a refresh that can't use it.
        updates.emit(fix(accuracy = 500f))
        runCurrent()
        assertEquals(1L, fixes.latest.value?.seq)
        // Past the walk's time no fix is wanted, so location is no longer asked for (battery), and the
        // last position isn't held on to (Codex, #458).
        advanceTimeBy(611_000)
        assertEquals(0, watching)
        assertNull(fixes.latest.value)
        // Nor with the trip gone.
        kept.value = null
        advanceTimeBy(20_000)
        assertEquals(0, watching)
        job.cancel()
    }

    @Test
    fun `a refresh woken by a fix acts on it, aged to now, and takes its own otherwise`() = runTest {
        val fixes = fixes()
        val taken = fix(accuracy = 5f)
        var takes = 0
        val take: suspend (ActiveTrip?) -> LocationFix? = { takes++; taken }
        fixes.offer(fix(ageMillis = 1_000))
        val seen = fixes.latest.value
        advanceTimeBy(2_000)
        val used = refreshFix(seen, fixes, trip, t0, take = take)
        assertEquals(3_000L, used?.ageMillis)
        assertEquals(0, takes)
        // Used, it's dropped: a position isn't kept past the refresh it was for (Codex, #458).
        assertNull(fixes.latest.value)
        // Woken by the timer: one taken, as before.
        assertSame(taken, refreshFix(null, fixes, trip, t0, take = take))
        // One grown too old to settle anything by the time it's acted on: one taken instead.
        advanceTimeBy(10_000)
        assertSame(taken, refreshFix(seen, fixes, trip, t0, take = take))
        assertEquals(2, takes)
    }

    @Test
    fun `the service uses a fix the open app saw, though it may not take its own`() = runTest {
        // Started before precise location was allowed: it runs without the location type (Codex, #458).
        val fixes = fixes()
        var takes = 0
        val take: suspend (ActiveTrip?) -> LocationFix? = { takes++; fix() }
        fixes.offer(fix(accuracy = 12f))
        val used = refreshFix(fixes.latest.value, fixes, trip, t0, mayTake = false, take)
        assertEquals(12f, used?.accuracyMeters)
        assertNull("and drops it", fixes.latest.value)
        // Woken by the timer, it takes none of its own.
        assertNull(refreshFix(null, fixes, trip, t0, mayTake = false, take))
        assertEquals(0, takes)
        // Allowed, it takes one as before.
        assertEquals(10f, refreshFix(null, fixes, trip, t0, mayTake = true, take)?.accuracyMeters)
        assertEquals(1, takes)
    }

    @Test
    fun `a fix passed over by a timer's refresh is dropped too`() = runTest {
        val fixes = fixes()
        fixes.offer(fix())
        refreshFix(null, fixes, trip, t0) { null }
        assertNull(fixes.latest.value)
    }

    @Test
    fun `location unavailable while a fix is wanted is asked for again, so one allowed mid-trip is used`() = runTest {
        val kept = MutableStateFlow<ActiveTrip?>(trip)
        val fixes = fixes()
        var asks = 0
        // Precise location off at first (the stream ends at once), then allowed.
        val updates = {
            asks++
            if (asks == 1) emptyFlow() else flowOf(fix())
        }
        val job = launch { watchTripFixes(kept, fixes, updates, now = { t0.plusMillis(currentTime) }) }
        runCurrent()
        assertEquals(1, asks)
        assertNull(fixes.latest.value)
        advanceTimeBy(10_001)
        assertEquals(2, asks)
        assertEquals(1L, fixes.latest.value?.seq)
        // Left the app: no longer watched, and the position goes with it (Codex, #458).
        job.cancel()
        runCurrent()
        assertNull(fixes.latest.value)
    }

    @Test
    fun `a fix's age is counted from when it came`() = runTest {
        val fixes = fixes()
        fixes.offer(fix(ageMillis = 500))
        advanceTimeBy(4_000)
        assertEquals(4_500L, fixes.aged(fixes.latest.value!!).ageMillis)
        assertTrue(fixes.latest.value!!.seq > 0)
    }
}
