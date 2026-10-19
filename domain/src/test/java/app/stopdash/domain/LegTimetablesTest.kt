package app.stopdash.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LegTimetablesTest {
    private val key = LegTimetables.Key("northern", "940GZZLUEUS", "940GZZLUKSX")
    private val timetable = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(DayOfWeek.TUESDAY), listOf(482, 485))))

    private class FakeSource(var answer: suspend () -> StopTimetable) : LegTimetableSource {
        val asked = mutableListOf<Triple<String, String, String>>()

        override suspend fun legTimetable(lineId: String, fromId: String, toId: String): StopTimetable {
            asked += Triple(lineId, fromId, toId)
            return answer()
        }
    }

    // Tuesday 2026-10-06, 12:00 London.
    private var now = Instant.parse("2026-10-06T11:00:00Z")

    @Test
    fun `fetches a ride once a service day`() = runTest {
        val source = FakeSource { timetable }
        val timetables = LegTimetables(source, clock = { now })
        timetables.fetch(listOf(key, key))
        assertEquals(listOf(Triple("northern", "940GZZLUEUS", "940GZZLUKSX")), source.asked)
        assertEquals(mapOf(key to timetable), timetables.known().timetables)
        // Read with the service day they're for: Tuesday's.
        assertEquals(java.time.LocalDate.of(2026, 10, 6), timetables.known().day)
        // Later the same day: kept, nothing new.
        now = now.plus(Duration.ofHours(10))
        timetables.fetch(listOf(key))
        assertEquals(1, source.asked.size)
        // The next service day starts at 04:00 London: dropped, and asked again.
        now = Instant.parse("2026-10-07T03:30:00Z")
        assertEquals(emptyMap<LegTimetables.Key, StopTimetable?>(), timetables.known().timetables)
        timetables.fetch(listOf(key))
        assertEquals(2, source.asked.size)
    }

    @Test
    fun `a ride being fetched is waited for, not asked for twice`() = runTest {
        val answer = CompletableDeferred<StopTimetable>()
        val source = FakeSource { answer.await() }
        val timetables = LegTimetables(source, clock = { now })
        val first = async { timetables.fetch(listOf(key)) }
        runCurrent()
        // A second caller waits for the first's answer and then has it too.
        val second = async { timetables.fetch(listOf(key)); timetables.known().timetables }
        runCurrent()
        assertFalse(second.isCompleted)
        answer.complete(timetable)
        first.await()
        assertEquals(mapOf(key to timetable), second.await())
        assertEquals(1, source.asked.size)
    }

    @Test
    fun `a ride whose first caller gave up is fetched by the one waiting`() = runTest {
        val answer = CompletableDeferred<StopTimetable>()
        val source = FakeSource { answer.await() }
        val timetables = LegTimetables(source, clock = { now })
        val first = async { timetables.fetch(listOf(key)) }
        runCurrent()
        val second = async { timetables.fetch(listOf(key)) }
        runCurrent()
        first.cancel()
        runCurrent()
        answer.complete(timetable)
        second.await()
        assertEquals(2, source.asked.size)
        assertEquals(mapOf(key to timetable), timetables.known().timetables)
    }

    @Test
    fun `a failed fetch is asked again after a while, a missing timetable not that day`() = runTest {
        val warnings = mutableListOf<String>()
        val source = FakeSource { throw TflException.Offline(null) }
        val timetables = LegTimetables(source, clock = { now }, warn = { warnings += it })
        timetables.fetch(listOf(key))
        assertTrue(timetables.failed(listOf(key)))
        assertEquals(TimetableRepository.RETRY_AFTER, timetables.retryDueIn(listOf(key)))
        // Its retry comes due from when it failed, whoever asks after.
        now = now.plus(Duration.ofMinutes(2))
        assertEquals(TimetableRepository.RETRY_AFTER.minusMinutes(2), timetables.retryDueIn(listOf(key)))
        now = now.minus(Duration.ofMinutes(2))
        timetables.fetch(listOf(key))
        assertEquals(1, source.asked.size)
        assertEquals(emptyMap<LegTimetables.Key, StopTimetable?>(), timetables.known().timetables)
        assertEquals(listOf("timetable failed for line northern from stop 940GZZLUEUS: Offline (offline)"), warnings)
        now = now.plus(TimetableRepository.RETRY_AFTER)
        source.answer = { throw TflException.NotFound(null) }
        timetables.fetch(listOf(key))
        assertEquals(2, source.asked.size)
        assertFalse(timetables.failed(listOf(key)))
        assertEquals(null, timetables.retryDueIn(listOf(key)))
        // TfL has none: answered as none, so a trip stops asking, and not asked again today.
        assertEquals(mapOf<LegTimetables.Key, StopTimetable?>(key to null), timetables.known().timetables)
        now = now.plus(TimetableRepository.RETRY_AFTER)
        timetables.fetch(listOf(key))
        assertEquals(2, source.asked.size)
    }

    @Test
    fun `a fetch that fails across 4am is no backoff for the new day`() = runTest {
        // 03:59 London when asked, 04:01 when it fails.
        now = Instant.parse("2026-10-07T02:59:00Z")
        val source = FakeSource {
            now = Instant.parse("2026-10-07T03:01:00Z")
            throw TflException.Offline(null)
        }
        val timetables = LegTimetables(source, clock = { now })
        timetables.fetch(listOf(key))
        // The day before's failure: today's first ask is due at once.
        assertEquals(null, timetables.retryDueIn(listOf(key)))
    }

    @Test
    fun `a fetch asked before 4am and answered after doesn't stand for the new day`() = runTest {
        val answer = CompletableDeferred<StopTimetable>()
        var calls = 0
        val source = FakeSource { if (++calls == 1) answer.await() else timetable }
        // 03:59 London: the old service day.
        now = Instant.parse("2026-10-07T02:59:00Z")
        val timetables = LegTimetables(source, clock = { now })
        val old = async { timetables.fetch(listOf(key)) }
        runCurrent()
        // 04:01: a new day's caller waits for it, then fetches the new day's.
        now = Instant.parse("2026-10-07T03:01:00Z")
        val new = async { timetables.fetch(listOf(key)) }
        runCurrent()
        answer.complete(timetable)
        old.await()
        new.await()
        assertEquals(2, source.asked.size)
        assertEquals(mapOf(key to timetable), timetables.known().timetables)
    }
}
