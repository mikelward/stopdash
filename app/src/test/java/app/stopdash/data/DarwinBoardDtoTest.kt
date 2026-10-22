package app.stopdash.data

import app.stopdash.domain.CallingPortion
import app.stopdash.domain.SuppressedRailBoard
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a board of the trains calling somewhere lists every one for an hour, or through the last it could list`() {
        // Recorded: St Pancras's trains toward Brighton, made at 17:00 UK time, three of the ten asked for.
        val board = json.decodeFromString<DarwinBoardDto>(
            checkNotNull(javaClass.getResource("/fixtures/darwin_board_details_st_pancras.json")).readText(),
        )
        assertEquals(Instant.parse("2026-10-15T17:00:00Z"), board.coversUntil(10, java.time.Duration.ofMinutes(60)))
        // As many as it could list: through the last one's schedule, past which more may have been cut off.
        val first = board.trainServices!!.first()
        val full = board.copy(trainServices = List(10) { i -> first.copy(std = "17:%02d".format(10 + 2 * i), serviceID = "$i") })
        assertEquals(Instant.parse("2026-10-15T16:28:00Z"), full.coversUntil(10, java.time.Duration.ofMinutes(60)))
        // A schedule it can't read, or no time it was made at: it can't say.
        val unreadable = full.copy(trainServices = full.trainServices!!.dropLast(1) + first.copy(std = "soon"))
        assertEquals(null, unreadable.coversUntil(10, java.time.Duration.ofMinutes(60)))
        val warned = mutableListOf<String>()
        assertEquals(null, board.copy(generatedAt = "later").coversUntil(10, java.time.Duration.ofMinutes(60), warned::add))
        assertEquals(1, warned.size)
    }

    @Test
    fun `a board of the trains calling somewhere says nothing of those it leaves out once it drops one it couldn't read`() {
        val board = json.decodeFromString<DarwinBoardDto>(
            checkNotNull(javaClass.getResource("/fixtures/darwin_board_details_st_pancras.json")).readText(),
        )
        val hour = java.time.Duration.ofMinutes(60)
        assertEquals(Instant.parse("2026-10-15T17:00:00Z"), board.coversUntilRead(board.toBoard(), 10, hour))
        // One train's estimate garbled: left out of the trains read, though another board may read it (Codex, #1138).
        val garbled = board.copy(trainServices = board.trainServices!!.dropLast(1) + board.trainServices!!.last().copy(etd = "garbled"))
        val read = garbled.toBoard()
        assertEquals(board.toBoard().departures.size - 1, read.departures.size)
        assertEquals(null, garbled.coversUntilRead(read, 10, hour))
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
    fun `a board as a timetable lists each train by its schedule, with what National Rail says of it`() {
        // Made at 23:40 BST: Guildford on time, Reading late, Woking delayed, Windsor canceled, the TfL-run one left out,
        // and past midnight one dividing for Portsmouth Harbour and Southampton.
        val timetable = board().toTimetable(rows = 150)
        assertEquals(listOf("1", "2", "3", "4", "6"), timetable.trains.map { it.serviceId })
        assertEquals(Instant.parse("2026-09-24T22:45:00Z"), timetable.trains[0].scheduled)
        assertEquals(null, timetable.trains[0].expected)
        assertEquals(Instant.parse("2026-09-24T22:52:00Z"), timetable.trains[1].expected)
        assertTrue(timetable.trains[2].delayed && timetable.trains[2].expected == null && !timetable.trains[2].canceled)
        assertTrue(timetable.trains[3].canceled && !timetable.trains[3].delayed)
        assertEquals(Instant.parse("2026-09-24T23:10:00Z"), timetable.trains[4].scheduled)
        assertEquals(setOf("PMH", "SOU"), timetable.trains[4].ends)
        assertTrue(timetable.trains.all { it.lineId == "south-western-railway" })
        assertTrue(timetable.complete)
        // As many trains as it was asked for: there may be more it couldn't list.
        assertFalse(board().toTimetable(rows = 6).complete)
    }

    @Test
    fun `a timetable's train with an unreadable time is left out and said, and all unreadable fails it`() {
        val odd = board().trainServices!!.first().copy(etd = "soon-ish", serviceID = "odd")
        val unscheduled = board().trainServices!!.first().copy(std = "later", serviceID = "unscheduled")
        val warnings = mutableListOf<String>()
        val timetable = board().copy(trainServices = board().trainServices!! + odd + unscheduled).toTimetable(150) { warnings += it }
        assertEquals(listOf("1", "2", "3", "4", "6"), timetable.trains.map { it.serviceId })
        assertFalse(timetable.complete)
        assertEquals(listOf("national rail timetable: 2 train(s) with unreadable times left out"), warnings)
        assertTrue(runCatching { board().copy(trainServices = listOf(odd)).toTimetable(150) }.exceptionOrNull() is IllegalStateException)
        // A suppressed board fails, never reads as a timetable with no trains.
        assertTrue(runCatching { board().copy(areServicesAvailable = false).toTimetable(150) }.exceptionOrNull() is SuppressedRailBoard)
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
    fun `a train with a time but no readable schedule is listed, unfollowable, and said to be`() {
        // Reading's 23:48, expected 23:52, with its schedule missing: shown at its estimate, but with no
        // schedule to know it by, no trip can follow it, which the log says (maintainer, 2026-10-16).
        val odd = board().trainServices!![1].copy(std = null)
        val warnings = mutableListOf<String>()
        val departures = board().copy(trainServices = listOf(odd)).toDepartures { warnings += it }
        assertEquals("", departures.single().vehicleId)
        assertEquals(listOf("national rail board: 1 train(s) with no readable schedule can't be followed"), warnings)
        // A board whose trains can all be followed, or that divide, says nothing of it.
        val none = mutableListOf<String>()
        board().toDepartures { none += it }
        assertTrue(none.none { "can't be followed" in it })
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
    fun `a stop before another is read at or before it, in the repeated hour too`() {
        // 01:30 before the second 01:40 (GMT): the GMT reading, the later of the two at or before it.
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), instantAtOrBefore(Instant.parse("2026-10-25T01:40:00Z"), java.time.LocalTime.of(1, 30)))
        // Before the first 01:40 (BST), only the BST one is.
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), instantAtOrBefore(Instant.parse("2026-10-25T00:40:00Z"), java.time.LocalTime.of(1, 30)))
        // A clock time later in the day than the stop after it is the day before's, however long ago.
        assertEquals(Instant.parse("2026-10-14T21:10:00Z"), instantAtOrBefore(Instant.parse("2026-10-15T16:00:00Z"), java.time.LocalTime.of(22, 10)))
        assertEquals(Instant.parse("2026-10-15T07:20:00Z"), instantAtOrBefore(Instant.parse("2026-10-15T16:00:00Z"), java.time.LocalTime.of(8, 20)))
    }

    @Test
    fun `a schedule is read up to 18 hours back and less than six on, and a stop after on or after the one before`() {
        val uk = java.time.ZoneId.of("Europe/London")
        val board = Instant.parse("2026-10-15T16:00:00Z").atZone(uk)
        // A train due at 10:30 that morning, held past six hours, is that morning's, never tomorrow's (Codex, #883)...
        assertEquals(Instant.parse("2026-10-15T09:30:00Z"), scheduleNear(board, java.time.LocalTime.of(10, 30)))
        // ...as an estimate at that time is read on, as it always was.
        assertEquals(Instant.parse("2026-10-16T09:30:00Z"), instantNear(board, java.time.LocalTime.of(10, 30)))
        // Past midnight both ways: a schedule just after it is the next day, one just before it the day before.
        assertEquals(Instant.parse("2026-10-15T23:10:00Z"), scheduleNear(Instant.parse("2026-10-15T22:40:00Z").atZone(uk), java.time.LocalTime.of(0, 10)))
        assertEquals(Instant.parse("2026-10-15T22:55:00Z"), scheduleNear(Instant.parse("2026-10-15T23:05:00Z").atZone(uk), java.time.LocalTime.of(23, 55)))
        // In the repeated hour, the reading nearest the board, as for an estimate.
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), scheduleNear(Instant.parse("2026-10-25T00:20:00Z").atZone(uk), java.time.LocalTime.of(1, 30)))
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), scheduleNear(Instant.parse("2026-10-25T01:20:00Z").atZone(uk), java.time.LocalTime.of(1, 30)))
        // A stop after: the first reading no earlier than the one before, the next day's across midnight.
        assertEquals(Instant.parse("2026-10-15T09:33:00Z"), instantAtOrAfter(Instant.parse("2026-10-15T09:30:00Z"), java.time.LocalTime.of(10, 33)))
        assertEquals(Instant.parse("2026-10-15T23:05:00Z"), instantAtOrAfter(Instant.parse("2026-10-15T22:55:00Z"), java.time.LocalTime.of(0, 5)))
        // After the first 01:20 (BST), 01:30 is the BST one; after the second (GMT), the GMT one.
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), instantAtOrAfter(Instant.parse("2026-10-25T00:20:00Z"), java.time.LocalTime.of(1, 30)))
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), instantAtOrAfter(Instant.parse("2026-10-25T01:20:00Z"), java.time.LocalTime.of(1, 30)))
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

    @Test
    fun `a board's station messages come as plain text, even with no trains`() {
        val board = json.decodeFromString<DarwinBoardDto>(
            checkNotNull(javaClass.getResource("/fixtures/darwin_board_messages_waterloo.json")).readText(),
        ).toBoard()
        assertTrue(board.departures.isEmpty())
        // Said once, its link kept beside its words, the blank one dropped.
        assertEquals(
            listOf(
                "Disruption between Clapham Junction and Woking is expected until 10:00. More details can be found in " +
                    "Latest Travel News (https://www.nationalrail.co.uk/service-disruptions/example-20261017/).",
            ),
            board.messages,
        )
        // A board with trains carries them too; one with none has none.
        // ("Value" as well as "value", either case the service might write.)
        val withTrains = board().copy(nrccMessages = listOf(JsonObject(mapOf("Value" to JsonPrimitive("<p>Trains are delayed.</p>"))))).toBoard()
        assertTrue(withTrains.departures.isNotEmpty())
        assertEquals(listOf("Trains are delayed."), withTrains.messages)
        assertTrue(board().toBoard().messages.isEmpty())
    }

    @Test
    fun `a station message in a shape it doesn't know costs only that message`() {
        val odd = json.decodeFromString<DarwinBoardDto>("""{"generatedAt": "2026-10-17T08:00:00+01:00", "nrccMessages": [{"text": "x"}, 3, "<b>Plain</b> words"]}""")
        assertEquals(listOf("Plain words"), odd.toBoard().messages)
    }

    @Test
    fun `a suppressed board fails with its station messages, which often say why (Codex, #938)`() {
        val suppressed = json.decodeFromString<DarwinBoardDto>(
            checkNotNull(javaClass.getResource("/fixtures/darwin_board_messages_waterloo.json")).readText(),
        ).copy(areServicesAvailable = false)
        val failure = runCatching { suppressed.toBoard() }.exceptionOrNull()
        assertTrue(failure is SuppressedRailBoard)
        assertEquals(1, (failure as SuppressedRailBoard).messages.size)
        assertTrue(failure.messages.single().startsWith("Disruption between Clapham Junction and Woking"))
    }
}
