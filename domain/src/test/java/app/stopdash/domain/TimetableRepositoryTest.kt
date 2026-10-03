package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimetableRepositoryTest {
    private val key = EmptyTimes.Key("940GZZLUKSX", "victoria")
    private val timetable = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(java.time.DayOfWeek.MONDAY), listOf(600))))

    private class FakeSource(var answer: () -> StopTimetable) : TimetableSource {
        val asked = mutableListOf<Pair<String, String>>()

        override suspend fun timetable(lineId: String, stopId: String): StopTimetable {
            asked += lineId to stopId
            return answer()
        }
    }

    // Tuesday 2026-10-06, 12:00 London.
    private var now = Instant.parse("2026-10-06T11:00:00Z")

    @Test
    fun `fetches a key once a service day, and once while in flight`() = runTest {
        val source = FakeSource { timetable }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = TimetableRepository(source, TestScope(dispatcher), clock = { now }, io = dispatcher)
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(1, source.asked.size)
        assertEquals("victoria" to "940GZZLUKSX", source.asked.single())
        assertEquals(EmptyTimes.Lookup.Found(timetable), repository.lookups.value[key])
        // Later the same day: kept.
        now = now.plus(Duration.ofHours(10))
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(1, source.asked.size)
        // The next service day starts at 04:00 London: asked again.
        now = Instant.parse("2026-10-07T03:30:00Z")
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(2, source.asked.size)
    }

    @Test
    fun `a fetch asked before 4am and answered after is the old day's, and asked again`() = runTest {
        // Tuesday 03:59 London (BST): the answer lands at 04:01, in Wednesday's service day.
        now = Instant.parse("2026-10-07T02:59:00Z")
        val source = FakeSource { now = Instant.parse("2026-10-07T03:01:00Z"); timetable }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = TimetableRepository(source, TestScope(dispatcher), clock = { now }, io = dispatcher)
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(1, source.asked.size)
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(2, source.asked.size)
    }

    @Test
    fun `a failed fetch reads as failed, and is asked again only after a while`() = runTest {
        val source = FakeSource { throw TflException.Offline(null) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val warnings = mutableListOf<String>()
        val repository = TimetableRepository(source, TestScope(dispatcher), clock = { now }, io = dispatcher, warn = { warnings += it })
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(EmptyTimes.Lookup.Failed, repository.lookups.value[key])
        assertEquals(listOf("timetable failed for line victoria at stop 940GZZLUKSX: offline"), warnings)
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(1, source.asked.size)
        // Past the retry wait, a success replaces the failure.
        now = now.plus(TimetableRepository.RETRY_AFTER)
        source.answer = { timetable }
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(2, source.asked.size)
        assertEquals(EmptyTimes.Lookup.Found(timetable), repository.lookups.value[key])
    }

    @Test
    fun `a failed timetable asked for again reads as loading until it answers, and a quiet "?" stays`() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        val source = FakeSource { throw TflException.Offline(null) }
        val gated = object : TimetableSource {
            override suspend fun timetable(lineId: String, stopId: String): StopTimetable {
                gate?.await()
                return source.timetable(lineId, stopId)
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = TimetableRepository(gated, TestScope(dispatcher), clock = { now }, io = dispatcher)
        val quiet = EmptyTimes.quietBoard(key.stopId, key.lineId, "tube")
        fun watchBoth() {
            repository.watch("status") { EmptyTimes.Board(listOf(key)) }
            repository.watch("quiet") { quiet }
        }
        watchBoth()
        advanceUntilIdle()
        assertEquals(EmptyTimes.Mark.UNKNOWN, repository.marks.value["status"]?.mark)
        assertEquals(EmptyTimes.Mark.UNKNOWN, repository.marks.value["quiet"]?.mark)
        // Past the retry wait, the fetch is held open: the status row's mark is loading again, and the
        // quiet row's "?" stays rather than going off and on.
        now = now.plus(TimetableRepository.RETRY_AFTER)
        gate = CompletableDeferred()
        watchBoth()
        advanceUntilIdle()
        assertEquals(EmptyTimes.Mark.LOADING, repository.marks.value["status"]?.mark)
        assertEquals(EmptyTimes.Mark.UNKNOWN, repository.marks.value["quiet"]?.mark)
        // It fails again: "?" again.
        gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, source.asked.size)
        assertEquals(EmptyTimes.Mark.UNKNOWN, repository.marks.value["status"]?.mark)
    }

    @Test
    fun `a request returns at once and fetches off the caller's thread`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val io = executor.asCoroutineDispatcher()
            val fetchedOn = CompletableDeferred<Thread>()
            val source = object : TimetableSource {
                override suspend fun timetable(lineId: String, stopId: String): StopTimetable {
                    fetchedOn.complete(Thread.currentThread())
                    return timetable
                }
            }
            // The clock is read where the keys due are picked: that scan runs off the caller's thread too.
            val scannedOn = CompletableDeferred<Thread>()
            val clock = { scannedOn.complete(Thread.currentThread()); now }
            val repository = TimetableRepository(source, TestScope(StandardTestDispatcher(testScheduler)), clock = clock, io = io)
            val caller = Thread.currentThread()
            val builtOn = CompletableDeferred<Thread>()
            repository.watch("board") { builtOn.complete(Thread.currentThread()); EmptyTimes.Board(listOf(key)) }
            // The board's recipe, the scan for what's due, and the fetch all run off the caller's thread.
            assertNotEquals(caller, builtOn.await())
            assertNotEquals(caller, scannedOn.await())
            val thread = fetchedOn.await()
            assertNotEquals(caller, thread)
            assertTrue(thread.isAlive)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `boards watched together are found off the caller's thread, each watched`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val io = executor.asCoroutineDispatcher()
            val source = object : TimetableSource {
                override suspend fun timetable(lineId: String, stopId: String): StopTimetable = timetable
            }
            val repository = TimetableRepository(source, TestScope(StandardTestDispatcher(testScheduler)), clock = { now }, io = io)
            val caller = Thread.currentThread()
            val foundOn = CompletableDeferred<Thread>()
            repository.watchEach {
                foundOn.complete(Thread.currentThread())
                mapOf("a" to EmptyTimes.Board(listOf(key)), "b" to EmptyTimes.Board(listOf(key), notRunning = true))
            }
            assertNotEquals(caller, foundOn.await())
            val marks = repository.marks.first { it.keys.containsAll(listOf("a", "b")) }
            assertEquals(EmptyTimes.Mark.NONE, marks.getValue("b").mark)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `no timetable at all is kept for the day, not asked again every few minutes`() = runTest {
        val source = FakeSource { throw TflException.NotFound(null) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = TimetableRepository(source, TestScope(dispatcher), clock = { now }, io = dispatcher)
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        // An empty timetable: it can't settle the mark, so the board reads "?".
        assertEquals(EmptyTimes.Lookup.Found(StopTimetable(emptyList())), repository.lookups.value[key])
        now = now.plus(TimetableRepository.RETRY_AFTER).plus(Duration.ofMinutes(1))
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(1, source.asked.size)
    }


    @Test
    fun `a board's mark is published as its timetables land, and a not-running one is a dash at once`() = runTest {
        // Tuesday 04:30 London, in Tuesday's service day (Monday's runs to 04:00): the stand-in
        // timetable runs Mondays and Tuesdays from 06:00.
        now = Instant.parse("2026-10-06T03:30:00Z")
        val days = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY), listOf(6 * 60))))
        val source = FakeSource { days }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = TimetableRepository(source, TestScope(dispatcher), clock = { now }, io = dispatcher)
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(EmptyTimes.Mark.NONE, repository.marks.value["board"]?.mark)
        // Asked again at 05:45, the 06:00 is due: the mark moves with the clock, no new fetch.
        now = Instant.parse("2026-10-06T04:45:00Z")
        repository.watch("board") { EmptyTimes.Board(listOf(key)) }
        advanceUntilIdle()
        assertEquals(EmptyTimes.Mark.UNKNOWN, repository.marks.value["board"]?.mark)
        // Stamped with when it was worked out, so a board back on screen can tell it from a leftover.
        assertEquals(now, repository.marks.value["board"]?.at)
        assertEquals(1, source.asked.size)
        // A board its status says isn't running needs no timetable: a dash, and nothing fetched.
        repository.watch("suspended") { EmptyTimes.Board(listOf(EmptyTimes.Key("940GZZLUVIC", "victoria")), notRunning = true) }
        advanceUntilIdle()
        assertEquals(EmptyTimes.Mark.NONE, repository.marks.value["suspended"]?.mark)
        assertEquals(1, source.asked.size)
    }

    @Test
    fun `yesterday's timetables and boards long off screen are forgotten`() {
        runTest {
            val source = FakeSource { timetable }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = TimetableRepository(source, TestScope(dispatcher), clock = { now }, io = dispatcher)
            repository.watch("old") { EmptyTimes.Board(listOf(key)) }
            advanceUntilIdle()
            assertEquals(setOf(key), repository.lookups.value.keys)
            assertEquals(setOf("old"), repository.marks.value.keys)
            // The next day, another board is shown: the old one's timetable and mark go.
            now = now.plus(Duration.ofDays(1))
            val other = EmptyTimes.Key("940GZZLUVIC", "victoria")
            source.answer = { throw TflException.Offline(null) }
            repository.watch("new") { EmptyTimes.Board(listOf(other)) }
            advanceUntilIdle()
            assertEquals(setOf(other), repository.lookups.value.keys)
            assertEquals(setOf("new"), repository.marks.value.keys)
        }
    }

    @Test
    fun `the service day ends at 4am London time, clock change or not`() {
        fun day(at: String) = TimetableRepository.serviceDay(Instant.parse(at)).toString()
        // An ordinary night: 03:59 is still yesterday's, 04:00 today's.
        assertEquals("2026-10-05", day("2026-10-06T02:59:00Z"))
        assertEquals("2026-10-06", day("2026-10-06T03:00:00Z"))
        // Spring (2027-03-28): 04:30 BST is 03:30Z, and already the new day.
        assertEquals("2027-03-28", day("2027-03-28T03:30:00Z"))
        assertEquals("2027-03-27", day("2027-03-28T02:59:00Z"))
        // Autumn (2026-10-25): 03:30 GMT is 03:30Z, still the old day; 04:00 GMT is the new one.
        assertEquals("2026-10-24", day("2026-10-25T03:30:00Z"))
        assertEquals("2026-10-25", day("2026-10-25T04:00:00Z"))
    }
}
