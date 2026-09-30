package app.stopdash.data

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A hand-written board in Darwin's documented shape, for a public example station. */
class DarwinBoardDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun board() = json.decodeFromString<DarwinBoardDto>(
        checkNotNull(javaClass.getResource("/fixtures/darwin_board_example.json")).readText(),
    )

    @Test
    fun `a board's departures carry their expected times, platforms and operators`() {
        val departures = board().toDepartures()
        assertEquals(listOf("Guildford", "Reading", "Portsmouth Harbour & Southampton Central"), departures.map { it.destination })
        // On time at its scheduled 23:45; the delayed one at its 23:52 estimate (UK time, BST).
        assertEquals(Instant.parse("2026-09-24T22:45:00Z"), departures[0].expectedArrival)
        assertEquals(Instant.parse("2026-09-24T22:52:00Z"), departures[1].expectedArrival)
        // After midnight, on the next day.
        assertEquals(Instant.parse("2026-09-24T23:12:00Z"), departures[2].expectedArrival)
        assertEquals(listOf("Platform 4", "Platform 12", "Platform 1"), departures.map { it.platform })
        assertTrue(departures.all { it.mode == "national-rail" && it.lineName == "South Western Railway" })
        assertEquals("south-western-railway", departures[0].lineId)
    }

    @Test
    fun `a cancelled train, one delayed with no estimate, and a TfL-run service are left out`() {
        val destinations = board().toDepartures().map { it.destination }
        assertTrue("Woking" !in destinations)
        assertTrue(destinations.none { it.startsWith("Windsor") })
        assertTrue("Somewhere" !in destinations)
    }

    @Test
    fun `a board's West Midlands Trains line comes in as London Northwestern`() {
        // The board's "LNR & WMR" names both brands, and TfL's parent-company name neither; every
        // train it runs from London is branded London Northwestern Railway, as its LNR pill reads.
        val both = board().trainServices!!.first().copy(operator = "LNR & WMR", operatorCode = "LM")
        val parent = both.copy(operator = "West Midlands Trains")
        val departures = board().copy(trainServices = listOf(both, parent)).toDepartures()
        assertEquals(listOf("London Northwestern Railway", "London Northwestern Railway"), departures.map { it.lineName })
        assertEquals("west-midlands-trains", departures.first().lineId)
    }

    @Test
    fun `a London Underground train on a shared platform is left to TfL's feed`() {
        // Where tube trains share National Rail platforms (the District at Richmond), a board lists
        // them as "London Underground" under its operator code LT (London Transport). TfL's own feed
        // carries them as the real line, so the board's copy is left out rather than shown as a
        // "London Underground" line TfL has no status or route for.
        val tube = board().trainServices!!.first().copy(
            operator = "London Underground",
            operatorCode = "LT",
            destination = listOf(DarwinLocationDto(locationName = "Upminster Underground")),
        )
        val destinations = board().copy(trainServices = board().trainServices!! + tube).toDepartures().map { it.destination }
        assertTrue(destinations.none { it.startsWith("Upminster") })
    }

    @Test
    fun `a train with an unreadable time is left out and reported, and all unreadable is a failure`() {
        val odd = board().trainServices!!.first().copy(etd = "soon-ish")
        val warnings = mutableListOf<String>()
        val departures = board().copy(trainServices = board().trainServices!! + odd).toDepartures { warnings += it }
        assertEquals(3, departures.size)
        assertEquals(listOf("national rail board: 1 train(s) with unreadable times left out"), warnings)
        val allOdd = board().copy(trainServices = listOf(odd))
        assertTrue(runCatching { allOdd.toDepartures() }.exceptionOrNull() is IllegalStateException)
        // Still a failure beside trains rightly left out (a cancelled one here).
        val cancelled = board().trainServices!!.first().copy(isCancelled = true)
        val oddAndCancelled = board().copy(trainServices = listOf(odd, cancelled))
        assertTrue(runCatching { oddAndCancelled.toDepartures() }.exceptionOrNull() is IllegalStateException)
        // So is a train with no time at all: "On time" with no schedule, or no estimate given.
        val unscheduled = board().trainServices!!.first().copy(etd = "On time", std = null)
        val noEstimate = board().trainServices!!.first().copy(etd = null)
        val untimed = board().copy(trainServices = listOf(unscheduled, noEstimate, cancelled))
        assertTrue(runCatching { untimed.toDepartures() }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `a repeated hour when the clocks go back reads the occurrence nearest the board`() {
        val uk = java.time.ZoneId.of("Europe/London")
        // After the clocks went back (01:20 GMT, the second 01:20), 01:30 is ten minutes away in GMT.
        val afterRollback = Instant.parse("2026-10-25T01:20:00Z").atZone(uk)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), instantNear(afterRollback, java.time.LocalTime.of(1, 30)))
        // Before it (01:20 BST, the first 01:20), 01:30 is ten minutes away in BST.
        val beforeRollback = Instant.parse("2026-10-25T00:20:00Z").atZone(uk)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), instantNear(beforeRollback, java.time.LocalTime.of(1, 30)))
    }

    @Test
    fun `a board with trains but no readable time it was made at fails rather than guess or go empty`() {
        for (generatedAt in listOf(null, "not a time")) {
            val failed = runCatching { board().copy(generatedAt = generatedAt).toDepartures() }.exceptionOrNull()
            assertTrue(failed is IllegalStateException)
        }
        // A board with no trains at all has nothing to date.
        assertTrue(board().copy(generatedAt = null, trainServices = emptyList()).toDepartures().isEmpty())
    }
}
