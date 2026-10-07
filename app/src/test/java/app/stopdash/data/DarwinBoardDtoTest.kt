package app.stopdash.data

import app.stopdash.domain.CallingPortion
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
    fun `a board with details gives each train its calling points and service id`() {
        fun point(crs: String, canceled: Boolean = false) = DarwinCallingPointDto(crs, crs, canceled)
        val example = board()
        val details = example.copy(
            trainServices = listOf(
                // Guildford's: Clapham Junction (both of its stops), Woking skipped today, then Guildford.
                example.trainServices!![0].copy(
                    subsequentCallingPoints = listOf(DarwinCallingPointsDto(listOf(point("CLJ"), point("WOK", canceled = true), point("GLD")))),
                ),
                // Reading's, with a stop no TfL id names.
                example.trainServices[1].copy(subsequentCallingPoints = listOf(DarwinCallingPointsDto(listOf(point("CLJ"), point("XXX"))))),
            ),
        )
        val ids = mapOf("CLJ" to setOf("910GCLPHMJC", "910GCLPHMJW"), "WOK" to setOf("910GWOKING"), "GLD" to setOf("910GGUILDFD"))
        val departures = details.toBoard(stopIdsFor = { ids[it].orEmpty() }).departures
        assertEquals(listOf(CallingPortion(setOf("910GCLPHMJC", "910GCLPHMJW", "910GGUILDFD"), complete = true)), departures[0].callingAt)
        assertEquals(listOf(CallingPortion(setOf("910GCLPHMJC", "910GCLPHMJW"), complete = false)), departures[1].callingAt)
        assertEquals(listOf("1", "2"), departures.map { it.railServiceId })
        // A board asked for without details has none, but still its service ids to pair by.
        val plain = example.toBoard().departures
        assertTrue(plain.all { it.callingAt == null })
        assertEquals("1", plain[0].railServiceId)
    }

    @Test
    fun `a portion that won't run today is no way to reach its stops`() {
        fun point(crs: String) = DarwinCallingPointDto(crs, crs)
        val ids = mapOf("WOK" to setOf("910GWOKING"), "GLD" to setOf("910GGUILDFD"), "BSK" to setOf("910GBSNGSTK"))
        val portions = callingPortions(
            listOf(
                DarwinCallingPointsDto(listOf(point("WOK"), point("GLD"))),
                DarwinCallingPointsDto(listOf(point("BSK")), assocIsCancelled = true),
            ),
        ) { ids[it].orEmpty() }
        assertEquals(listOf(CallingPortion(setOf("910GWOKING", "910GGUILDFD"), complete = true)), portions)
        // No portion that can be ridden: it reaches none of its stops, a sure miss, never left to the route.
        val none = callingPortions(
            listOf(
                DarwinCallingPointsDto(listOf(point("BSK")), assocIsCancelled = true),
                DarwinCallingPointsDto(listOf(point("WOK")), serviceChangeRequired = true),
            ),
        ) { ids[it].orEmpty() }
        assertEquals(listOf(CallingPortion(emptySet(), complete = true)), none)
        assertEquals(false, app.stopdash.domain.DirectTrips.calling(none, setOf("910GBSNGSTK")))
        // No calling points at all is still no answer.
        assertEquals(emptyList<CallingPortion>(), callingPortions(emptyList()) { ids[it].orEmpty() })
    }

    @Test
    fun `a portion reached only by changing service is no way to reach its stops`() {
        fun point(crs: String) = DarwinCallingPointDto(crs, crs)
        val ids = mapOf("WOK" to setOf("910GWOKING"), "BSK" to setOf("910GBSNGSTK"))
        val portions = callingPortions(
            listOf(
                DarwinCallingPointsDto(listOf(point("WOK"))),
                DarwinCallingPointsDto(listOf(point("BSK")), serviceChangeRequired = true),
            ),
        ) { ids[it].orEmpty() }
        assertEquals(listOf(CallingPortion(setOf("910GWOKING"), complete = true)), portions)
    }

    @Test
    fun `a board read from Darwin's JSON keeps the portion flags`() {
        val list = json.decodeFromString(
            DarwinCallingPointsDto.serializer(),
            """{"callingPoint":[{"crs":"BSK"}],"serviceChangeRequired":true,"assocIsCancelled":false}""",
        )
        assertEquals(false, list.rideable)
    }

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
    fun `a canceled train, one delayed with no estimate, and a TfL-run service have no time to count down`() {
        val destinations = board().toDepartures().map { it.destination }
        assertTrue("Woking" !in destinations)
        assertTrue(destinations.none { it.startsWith("Windsor") })
        assertTrue("Somewhere" !in destinations)
        // The canceled and the delayed train come apart from the timed ones, at their scheduled 23:50
        // and 23:55 (UK time, BST), never as departures; the TfL-run one not at all.
        val untimed = board().toBoard().untimed
        assertEquals(listOf("Woking" to false, "Windsor & Eton Riverside" to true), untimed.map { it.train.destination to it.canceled })
        assertEquals(listOf(Instant.parse("2026-09-24T22:50:00Z"), Instant.parse("2026-09-24T22:55:00Z")), untimed.map { it.train.expectedArrival })
        assertTrue(untimed.all { it.train.lineId == "south-western-railway" && it.train.mode == "national-rail" })
        // Canceled by its estimate alone, too.
        val canceledByEtd = board().trainServices!!.first().copy(etd = "Cancelled")
        assertEquals(listOf(true), board().copy(trainServices = listOf(canceledByEtd)).toBoard().untimed.map { it.canceled })
    }

    @Test
    fun `a train's via comes in as the stations it runs by, but not a dividing train's`() {
        val service = board().trainServices!!.first()
        val via = service.copy(destination = listOf(DarwinLocationDto("Guildford", "GLD", via = "via Woking")))
        val dividing = service.copy(
            destination = listOf(DarwinLocationDto("Guildford", "GLD", via = "via Woking"), DarwinLocationDto("Reading", "RDG")),
        )
        val read = board().copy(trainServices = listOf(via, dividing, service)).toDepartures()
        assertEquals(listOf("Woking", "", ""), read.map { it.via })
        // The via narrows the route; it isn't the shown branch.
        assertTrue(read.all { it.branch == null })
    }

    @Test
    fun `a train's terminus comes in by TfL's id for its code, but not a dividing train's`() {
        val service = board().trainServices!!.first()
        val one = service.copy(destination = listOf(DarwinLocationDto("Guildford", "GLD")))
        val dividing = service.copy(destination = listOf(DarwinLocationDto("Guildford", "GLD"), DarwinLocationDto("Reading", "RDG")))
        val unknown = service.copy(destination = listOf(DarwinLocationDto("Elsewhere", "ZZZ")))
        val ids = mapOf("GLD" to "910GGUILDFD", "RDG" to "910GRDNGSTN")
        val read = board().copy(trainServices = listOf(one, dividing, unknown)).toDepartures(stopIdFor = ids::get)
        assertEquals(listOf("910GGUILDFD", "", ""), read.map { it.destinationId })
        // With no codes to read it by, as before.
        assertTrue(board().toDepartures().all { it.destinationId.isEmpty() })
    }

    @Test
    fun `a canceled or delayed train with no schedule to place it is left out and reported`() {
        val delayed = board().trainServices!!.first().copy(etd = "Delayed", std = null)
        val warnings = mutableListOf<String>()
        val read = board().copy(trainServices = board().trainServices!! + delayed).toBoard { warnings += it }
        assertEquals(2, read.untimed.size)
        assertEquals(3, read.departures.size)
        assertEquals(listOf("national rail board: 1 canceled or delayed train(s) with unreadable schedules left out"), warnings)
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
