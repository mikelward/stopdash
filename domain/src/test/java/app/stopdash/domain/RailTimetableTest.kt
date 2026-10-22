package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RailTimetableTest {
    private val at = Instant.parse("2026-10-23T09:00:00Z")

    private fun train(minutes: Long, line: String = "thameslink", ends: Set<String> = setOf("BDM"), id: String = "s$minutes") =
        RailTimetable.Train(at.plusSeconds(minutes * 60), line, ends, serviceId = id)

    @Test
    fun `a way's trains are its line's that end where it goes, every one of the line's without one`() {
        val timetable = RailTimetable(
            listOf(
                train(2),
                train(5, ends = setOf("LTN")),
                train(9, line = "east-midlands-railway", ends = setOf("BDM")),
                // A train dividing for two places ends at each.
                train(14, ends = setOf("BDM", "LTN")),
            ),
        )
        assertEquals(listOf("s2", "s14"), timetable.along("thameslink", "BDM").trains.map { it.serviceId })
        assertEquals(listOf("s5", "s14"), timetable.along("thameslink", "LTN").trains.map { it.serviceId })
        assertEquals(listOf("s2", "s5", "s14"), timetable.along("thameslink", null).trains.map { it.serviceId })
    }

    @Test
    fun `two parts join as one, a train they both list once, as complete as each and as old as the oldest`() {
        val first = RailTimetable(listOf(train(110), train(119)), age = Duration.ofSeconds(10))
        // Its second part starts where the first ends, so the train due then is on both.
        val second = RailTimetable(listOf(train(119), train(150)), complete = false, age = Duration.ofSeconds(40))
        val joined = RailTimetable.joined(listOf(second, first))
        assertEquals(listOf("s110", "s119", "s150"), joined.trains.map { it.serviceId })
        assertFalse(joined.complete)
        assertEquals(Duration.ofSeconds(40), joined.age)
        // With no service ids, the same time, line and end is the same train; another time isn't.
        val unnamed = RailTimetable.joined(listOf(RailTimetable(listOf(train(5, id = ""))), RailTimetable(listOf(train(5, id = ""), train(6, id = "")))))
        assertEquals(2, unnamed.trains.size)
        assertTrue(unnamed.complete)
    }

    private class Source(private val timetable: RailTimetable?, private val failure: TflException? = null) : RailBoardSource {
        val asked = mutableListOf<Pair<String, String?>>()
        override val available = true
        override suspend fun departures(crs: String): List<Departure> = emptyList()
        override suspend fun timetable(crs: String, towards: String?): RailTimetable? {
            asked += crs to towards
            failure?.let { throw it }
            return timetable
        }
    }

    // Stop ids end in TIPLOCs: St Pancras's Thameslink platforms, Bedford, Luton.
    private val codes = RailStationCodes(mapOf("STPXBOX" to "STP", "BEDFDM" to "BDM", "LUTOJ" to "LTN"))

    @Test
    fun `a timetable is asked by its station's code, toward where it goes, and kept to its line and end`() {
        val source = Source(RailTimetable(listOf(train(2), train(5, ends = setOf("LTN")), train(9, line = "east-midlands-railway"))))
        val timetables = RailTimetables(source, { codes })
        val way = runBlocking { timetables.fetch("910GSTPXBOX", "thameslink", "910GBEDFDM", ends = true) }!!
        assertEquals(listOf("STP" to "BDM"), source.asked)
        assertEquals(listOf("s2"), way.trains.map { it.serviceId })
        // A ride's far end: every train of the line Darwin lists as calling there, wherever it ends.
        val ride = runBlocking { timetables.fetch("910GSTPXBOX", "thameslink", "910GLUTOJ", ends = false) }!!
        assertEquals("STP" to "LTN", source.asked.last())
        assertEquals(listOf("s2", "s5"), ride.trains.map { it.serviceId })
        // Every way: nothing to ask toward.
        runBlocking { timetables.fetch("910GSTPXBOX", "thameslink", null, ends = false) }
        assertEquals("STP" to null, source.asked.last())
    }

    @Test
    fun `a station or end with no code has no timetable to ask for`() {
        val source = Source(RailTimetable(emptyList()))
        val timetables = RailTimetables(source, { codes })
        assertNull(runBlocking { timetables.fetch("910GNOWHERE", "thameslink", null, ends = false) })
        assertNull(runBlocking { timetables.fetch("910GSTPXBOX", "thameslink", "910GNOWHERE", ends = true) })
        assertEquals(emptyList<Pair<String, String?>>(), source.asked)
    }

    @Test
    fun `a failure is reported by its station and kind, never where it was asked toward, and still fails`() {
        val warned = mutableListOf<String>()
        val timetables = RailTimetables(Source(null, TflException.Unreachable("HTTP 502", null)), { codes }, warned::add)
        val failure = runCatching { runBlocking { timetables.fetch("910GSTPXBOX", "thameslink", "910GLUTOJ", ends = false) } }.exceptionOrNull()
        assertTrue(failure is TflException.Unreachable)
        assertEquals(listOf("national rail timetable failed: HTTP 502 for station STP"), warned)
        // A source with nothing to give (no key) fails too, never reads as a timetable with no trains.
        val none = RailTimetables(Source(null), { codes }, warned::add)
        assertTrue(runCatching { runBlocking { none.fetch("910GSTPXBOX", "thameslink", null, ends = false) } }.exceptionOrNull() is TflException)
        assertTrue(warned.none { "LTN" in it })
    }
}
