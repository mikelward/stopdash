package app.stopdash.data

import app.stopdash.domain.RailFollow
import app.stopdash.domain.RailPoint
import app.stopdash.domain.TFL_RUN_OPERATORS
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A board with calling points read for following a train (SPEC *On the way*): a hand-written St Pancras
 * board in Darwin's documented shape, filtered to Brighton.
 */
class DarwinRunsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun board() = json.decodeFromString<DarwinBoardDto>(
        checkNotNull(javaClass.getResource("/fixtures/darwin_board_details_st_pancras.json")).readText(),
    )

    // A UK clock time on the board's day, British Summer Time.
    private fun bst(time: String): Instant = OffsetDateTime.parse("2026-10-15T$time:00+01:00").toInstant()

    @Test
    fun `each train comes with when it's due to leave, its estimate and its platform`() {
        val runs = board().toRuns()
        val first = runs.first()
        assertEquals("STP", first.atCrs)
        assertEquals("London St Pancras International", first.atName)
        assertEquals("thameslink", first.lineId)
        assertEquals(bst("17:12"), first.scheduled)
        assertEquals(bst("17:14"), first.expected)
        assertEquals("Platform B", first.platform)
        assertEquals("BTN", first.destinationCrs)
        // "Delayed" with no estimate: when it leaves isn't known, though it isn't canceled.
        assertEquals(bst("17:27"), runs[1].scheduled)
        assertNull(runs[1].expected)
        assertEquals(false, runs[1].canceled)
        assertEquals(false, first.canceled)
    }

    @Test
    fun `a train canceled at the board's station is said to be`() {
        val dto = board()
        val canceled = dto.copy(trainServices = dto.trainServices!!.map { if (it.serviceID == "11") it.copy(isCancelled = true, etd = "Cancelled") else it })
        val run = canceled.toRuns().single { it.scheduled == bst("17:27") }
        assertNull(run.expected)
        assertTrue(run.canceled)
    }

    @Test
    fun `its stops before by their schedules, and after with their estimates, less a canceled one`() {
        val first = board().toRuns().first()
        assertEquals(listOf("BDM", "LUT", "SAC"), first.previous.map { it.crs })
        // Gone from Luton, by when it was there.
        assertEquals(RailPoint("LUT", "Luton", bst("16:38"), bst("16:40"), left = true), first.previous[1])
        assertEquals(listOf("ZFD", "CTK", "BFR", "LBG", "GTW", "BTN"), first.next.map { it.crs })
        assertEquals(RailPoint("ZFD", "Farringdon", bst("17:15"), bst("17:17")), first.next[0])
        // "On time" is its schedule; a word with no time ("Delayed") no estimate.
        assertEquals(bst("17:19"), first.next[2].expected)
        assertNull(first.next[4].expected)
    }

    @Test
    fun `a train cut short gives the stops it still calls at, the last ending its run (Codex, #883)`() {
        val dto = board()
        val first = dto.trainServices!!.first()
        // Darwin cancels each of its calls past City Thameslink: it ends there now.
        val cut = first.copy(
            subsequentCallingPoints = first.subsequentCallingPoints!!.map { list ->
                list.copy(callingPoint = list.callingPoint!!.map { if (it.crs in setOf("BFR", "LBG", "GTW", "BTN")) it.copy(isCancelled = true) else it })
            },
        )
        val run = dto.copy(trainServices = listOf(cut)).toRuns().single()
        assertEquals(listOf("ZFD", "CTK"), run.next.map { it.crs })
        assertTrue(run.throughTerminus)
        // So its calls are every one it has left: a stop past City Thameslink is one it won't call at.
        assertEquals(listOf(false, false, true), RailFollow.calls(run, "STP") { emptySet() }!!.map { it.endsRun })
        // Running through, "Delayed" at Gatwick leaves what's past it unknown: none ends its run.
        assertTrue(RailFollow.calls(board().toRuns().first(), "STP") { emptySet() }!!.none { it.endsRun })
        // Canceled here with no stops after given, where it ends isn't known.
        val bare = first.copy(isCancelled = true, etd = "Cancelled", subsequentCallingPoints = null)
        assertEquals(false, dto.copy(trainServices = listOf(bare)).toRuns().single().throughTerminus)
    }

    @Test
    fun `a train held at the station more than six hours keeps its day, and is found again by it (Codex, #883)`() {
        val dto = board()
        val first = dto.trainServices!!.first()
        // Every time on its timetable 6h42m earlier, due here at 10:30, still to leave at 17:14 on a board made at 17:00.
        fun early(time: String?) = time?.let { LocalTime.parse(it).minusHours(6).minusMinutes(42).toString() }
        val held = first.copy(
            std = early(first.std),
            previousCallingPoints = first.previousCallingPoints!!.map { list ->
                list.copy(callingPoint = list.callingPoint!!.map { it.copy(st = early(it.st), at = if (it.at == "On time") it.at else early(it.at)) })
            },
            subsequentCallingPoints = first.subsequentCallingPoints!!.map { list ->
                list.copy(callingPoint = list.callingPoint!!.map { it.copy(st = early(it.st), et = if (it.et == "On time") "17:21" else it.et) })
            },
        )
        val board = dto.copy(trainServices = listOf(held))
        val run = board.toRuns().single()
        assertEquals(bst("10:30"), run.scheduled)
        assertEquals(bst("17:14"), run.expected)
        assertEquals(listOf(bst("09:38"), bst("09:56"), bst("10:10")), run.previous.map { it.scheduled })
        // Its stops on are due that morning too, each estimate the one still to come.
        assertEquals(RailPoint("ZFD", "Farringdon", bst("10:33"), bst("17:17")), run.next.first())
        assertEquals(bst("11:48"), run.next.last().scheduled)
        // Picked before it was held, by its schedule here, it's still that train; and the board's id for it agrees.
        val picked = RailFollow.keyOf(RailFollow.idOf("STP", bst("10:30"), "BTN", run.lineId))!!
        assertEquals(run, RailFollow.find(board.toRuns(), picked))
        assertEquals(picked, RailFollow.keyOf(board.toBoard().departures.single().vehicleId))
    }

    @Test
    fun `a station Darwin has no code for is none to follow a train to or ask a board by (Codex, #883)`() {
        val dto = board()
        val first = dto.trainServices!!.first()
        // To a terminus Darwin writes "???": no id, passed over as a train that divides is, and said to be.
        val unknown = first.copy(destination = first.destination!!.map { it.copy(crs = "???") })
        val board = dto.copy(trainServices = listOf(unknown) + dto.trainServices!!.drop(1))
        val warnings = mutableListOf<String>()
        assertEquals("", board.toBoard(warn = { warnings += it }).departures.first().vehicleId)
        assertTrue(warnings.any { "terminus with no known code can't be followed" in it })
        assertEquals(dto.toRuns().drop(1).map { it.scheduled }, board.toRuns().map { it.scheduled })
        // A stop on its way with no code is passed over, the train read whole otherwise.
        val stop = first.copy(
            previousCallingPoints = first.previousCallingPoints!!.map { list ->
                list.copy(callingPoint = list.callingPoint!!.map { if (it.crs == "LUT") it.copy(crs = "???") else it })
            },
            subsequentCallingPoints = first.subsequentCallingPoints!!.map { list ->
                list.copy(callingPoint = list.callingPoint!!.map { if (it.crs == "CTK") it.copy(crs = "???") else it })
            },
        )
        val run = dto.copy(trainServices = listOf(stop)).toRuns().single()
        assertEquals(listOf("BDM", "SAC"), run.previous.map { it.crs })
        assertEquals(listOf("ZFD", "BFR", "LBG", "GTW", "BTN"), run.next.map { it.crs })
        // Passed over, it's a stop the run's calls can't give, so they never say they're every one it has left: a
        // trip's stop could be that one. Cut short at City Thameslink, with Farringdon "???" before it...
        val cut = first.copy(
            subsequentCallingPoints = first.subsequentCallingPoints!!.map { list ->
                list.copy(
                    callingPoint = list.callingPoint!!.map {
                        when (it.crs) {
                            "ZFD" -> it.copy(crs = "???")
                            "BFR", "LBG", "GTW", "BTN" -> it.copy(isCancelled = true)
                            else -> it
                        }
                    },
                )
            },
        )
        val short = dto.copy(trainServices = listOf(cut)).toRuns().single()
        assertEquals(listOf("CTK"), short.next.map { it.crs })
        assertEquals(false, short.throughTerminus)
        assertTrue(RailFollow.calls(short, "STP") { emptySet() }!!.none { it.endsRun })
        // ...or with St Albans, which it hasn't left, "???" before the board.
        val held = cut.copy(
            subsequentCallingPoints = first.subsequentCallingPoints!!.map { list ->
                list.copy(callingPoint = list.callingPoint!!.map { if (it.crs in setOf("BFR", "LBG", "GTW", "BTN")) it.copy(isCancelled = true) else it })
            },
            previousCallingPoints = first.previousCallingPoints!!.map { list ->
                list.copy(callingPoint = list.callingPoint!!.map { if (it.crs == "SAC") it.copy(crs = "???", at = null, et = "16:58") else it })
            },
        )
        val before = dto.copy(trainServices = listOf(held)).toRuns().single()
        assertEquals(false, before.throughTerminus)
        assertTrue(RailFollow.calls(before, "BDM") { emptySet() }!!.none { it.endsRun })
        // One it was canceled at is no call to give: the run still ends where its calls do.
        val canceled = cut.copy(
            subsequentCallingPoints = first.subsequentCallingPoints!!.map { list ->
                list.copy(
                    callingPoint = list.callingPoint!!.map {
                        when (it.crs) {
                            "ZFD" -> it.copy(crs = "???", isCancelled = true)
                            "BFR", "LBG", "GTW", "BTN" -> it.copy(isCancelled = true)
                            else -> it
                        }
                    },
                )
            },
        )
        assertTrue(dto.copy(trainServices = listOf(canceled)).toRuns().single().throughTerminus)
        // A board naming its own station "???" names none.
        val nameless = dto.copy(crs = "???")
        assertTrue(runCatching { nameless.toRuns() }.exceptionOrNull() is IllegalStateException)
        assertTrue(nameless.toBoard().departures.all { it.vehicleId.isEmpty() })
    }

    @Test
    fun `a time Darwin marks as uncertain is still that time, never one it can't read (Codex, #883)`() {
        val dto = board()
        val first = dto.trainServices!!.first()
        val starred = first.copy(
            etd = "17:14*",
            previousCallingPoints = first.previousCallingPoints!!.map { list ->
                list.copy(callingPoint = list.callingPoint!!.map { if (it.crs == "LUT") it.copy(at = "16:40*") else it })
            },
            subsequentCallingPoints = first.subsequentCallingPoints!!.map { list ->
                list.copy(callingPoint = list.callingPoint!!.map { if (it.crs == "ZFD") it.copy(et = "17:17*") else it })
            },
        )
        val run = dto.copy(trainServices = listOf(starred)).toRuns().single()
        assertEquals(bst("17:14"), run.expected)
        assertEquals(bst("16:40"), run.previous.single { it.crs == "LUT" }.expected)
        assertEquals(bst("17:17"), run.next.single { it.crs == "ZFD" }.expected)
        // And on the board itself, counted down by it.
        assertEquals(bst("17:14"), dto.copy(trainServices = listOf(starred)).toBoard().departures.single().expectedArrival)
    }

    @Test
    fun `a train that set out more than six hours before the board, or the day before, is read whole (Codex, #883)`() {
        val dto = board()
        val first = dto.trainServices!!.first()
        fun from(st: String, at: String) = dto.copy(
            trainServices = listOf(
                first.copy(
                    previousCallingPoints = first.previousCallingPoints!!.map { list ->
                        list.copy(callingPoint = list.callingPoint!!.map { if (it.crs == "BDM") it.copy(st = st, at = at) else it })
                    },
                ),
            ),
        ).toRuns().single().previous.first()
        // Bedford at 08:20, nearly nine hours before the board: still a train, never a board that can't be read.
        assertEquals(RailPoint("BDM", "Bedford", bst("08:20"), bst("08:22"), left = true), from("08:20", "08:22"))
        // At 22:10, later in the day than Luton after it: the evening before.
        val evening = OffsetDateTime.parse("2026-10-14T22:10:00+01:00").toInstant()
        assertEquals(RailPoint("BDM", "Bedford", evening, evening.plusSeconds(120), left = true), from("22:10", "22:12"))
    }

    @Test
    fun `a stop before with an estimate, not when it was there, is one it hasn't left (Codex, #883)`() {
        val dto = board()
        val first = dto.trainServices!!.first()
        val held = dto.copy(
            trainServices = listOf(
                first.copy(
                    previousCallingPoints = first.previousCallingPoints!!.map { list ->
                        list.copy(
                            callingPoint = list.callingPoint!!.map {
                                when (it.crs) {
                                    "SAC" -> it.copy(at = null, et = "16:58")
                                    "LUT" -> it.copy(at = "No report")
                                    else -> it
                                }
                            },
                        )
                    },
                ),
            ),
        )
        val run = held.toRuns().single()
        // Still to leave St Albans, due there at 16:58, so still due there first, though St Pancras's board lists it.
        assertEquals(RailPoint("SAC", "St Albans City", bst("16:52"), bst("16:58"), left = false), run.previous[2])
        // Gone from Luton with no time reported there: gone all the same.
        assertEquals(RailPoint("LUT", "Luton", bst("16:38"), null, left = true), run.previous[1])
        assertEquals(listOf("SAC", "STP"), RailFollow.calls(run, "LUT") { setOf(it) }!!.take(2).map { it.stopId })
    }

    @Test
    fun `each portion that joins it is kept apart, never one route through both (Codex, #883)`() {
        val dto = board()
        val first = dto.trainServices!!.first()
        val joined = dto.copy(
            trainServices = listOf(
                // Joining at St Albans from Harpenden, neither portion there yet.
                first.copy(
                    previousCallingPoints = first.previousCallingPoints!!.map { list ->
                        list.copy(callingPoint = list.callingPoint!!.map { if (it.crs == "SAC") it.copy(at = null, et = "16:55") else it })
                    } + DarwinCallingPointsDto(
                        callingPoint = listOf(DarwinCallingPointDto(locationName = "Harpenden", crs = "HPD", st = "16:45", et = "16:46")),
                    ),
                ),
            ),
        )
        val run = joined.toRuns().single()
        assertEquals(listOf("BDM", "LUT", "SAC"), run.previous.map { it.crs })
        assertEquals(listOf(listOf(RailPoint("HPD", "Harpenden", bst("16:45"), bst("16:46")))), run.joining)
        // Picked on the joining portion, its calls are that portion's, then the first one's on, never Luton.
        assertEquals(listOf("HPD", "SAC", "STP"), RailFollow.calls(run, "HPD") { setOf(it) }!!.take(3).map { it.stopId })
    }

    @Test
    fun `a stop it was canceled at is no stop it called at, so it's never found again by it (Codex, #883)`() {
        val dto = board()
        val first = dto.trainServices!!.first()
        val skipped = dto.copy(
            trainServices = listOf(
                first.copy(
                    previousCallingPoints = first.previousCallingPoints!!.map { list ->
                        list.copy(callingPoint = list.callingPoint!!.map { if (it.crs == "LUT") it.copy(isCancelled = true) else it })
                    },
                ),
            ),
        )
        val run = skipped.toRuns().single()
        assertEquals(listOf("BDM", "SAC"), run.previous.map { it.crs })
        // Picked at Luton, it isn't this train, which didn't call there.
        assertNull(RailFollow.find(listOf(run), RailFollow.Key("LUT", bst("16:38"), "BTN", run.lineId)))
        // As listed, calling at Luton, it is.
        val calling = board().toRuns().first()
        assertEquals(calling, RailFollow.find(listOf(calling), RailFollow.Key("LUT", bst("16:38"), "BTN", calling.lineId)))
    }

    @Test
    fun `stops on a portion it doesn't run on as one train are no stops it called at (Codex, #883)`() {
        val dto = board()
        val first = dto.trainServices!!.first()
        val joined = dto.copy(
            trainServices = listOf(
                first.copy(previousCallingPoints = first.previousCallingPoints!!.map { it.copy(assocIsCancelled = true) }),
            ),
        )
        val run = joined.toRuns().single()
        assertTrue(run.previous.isEmpty())
        assertNull(RailFollow.find(listOf(run), RailFollow.Key("LUT", bst("16:38"), "BTN", run.lineId)))
    }

    @Test
    fun `a train that divides has no one terminus to follow it to, and isn't one`() {
        // The 17:30 runs to Brighton and Horsham.
        assertEquals(listOf(bst("17:12"), bst("17:27")), board().toRuns().map { it.scheduled })
    }

    @Test
    fun `a board's trains carry the id a trip follows each by, but not a dividing one's`() {
        val departures = board().toBoard().departures
        assertEquals(RailFollow.idOf("STP", bst("17:12"), "BTN", "thameslink"), departures[0].vehicleId)
        assertEquals(departures[0].lineId, RailFollow.keyOf(departures[0].vehicleId)?.lineId)
        assertEquals("", departures.single { it.railServiceId == "12" }.vehicleId)
        // Each found again by its id among the board's runs.
        val key = RailFollow.keyOf(departures[0].vehicleId)!!
        assertEquals(bst("17:12"), RailFollow.find(board().toRuns(), key)?.scheduled)
    }

    @Test
    fun `a board with a train it can't read whole fails, never reads as that train gone (Codex, #883)`() {
        val dto = board()
        val first = dto.trainServices!!.first()
        val broken = listOf(
            first.copy(std = "soon"),
            first.copy(operator = null),
            first.copy(destination = emptyList()),
            first.copy(
                previousCallingPoints = first.previousCallingPoints!!.map { list ->
                    list.copy(callingPoint = list.callingPoint!!.map { if (it.crs == "LUT") it.copy(st = null) else it })
                },
            ),
            first.copy(
                subsequentCallingPoints = first.subsequentCallingPoints!!.map { list ->
                    list.copy(callingPoint = list.callingPoint!!.map { if (it.crs == "ZFD") it.copy(crs = null) else it })
                },
            ),
            // Its stops after stopping short of Brighton, none canceled: read short, not a train cut short.
            first.copy(
                subsequentCallingPoints = first.subsequentCallingPoints!!.map { list -> list.copy(callingPoint = list.callingPoint!!.dropLast(1)) },
            ),
        )
        for (service in broken + first.copy(subsequentCallingPoints = null) + first.copy(subsequentCallingPoints = emptyList())) {
            val read = runCatching { dto.copy(trainServices = listOf(service) + dto.trainServices!!.drop(1)).toRuns() }
            assertTrue("read a board with $service", read.exceptionOrNull() is IllegalStateException)
        }
        // Canceled here, it may well call nowhere on: still a train the board read.
        val canceled = first.copy(isCancelled = true, etd = "Cancelled", subsequentCallingPoints = null)
        assertTrue(dto.copy(trainServices = listOf(canceled)).toRuns().single().canceled)
        // Left out by what they are, not unread: a dividing train (the fixture's 17:30) and a TfL-run one.
        val tflRun = first.copy(operatorCode = TFL_RUN_OPERATORS.first())
        assertEquals(listOf(bst("17:27")), dto.copy(trainServices = listOf(tflRun) + dto.trainServices!!.drop(1)).toRuns().map { it.scheduled })
    }

    @Test
    fun `a board Darwin has suppressed is a failure, never one its trains have left (Codex, #883)`() {
        val suppressed = board().copy(areServicesAvailable = false, trainServices = emptyList())
        assertTrue(runCatching { suppressed.toRuns() }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { suppressed.toBoard() }.exceptionOrNull() is IllegalStateException)
        // Read from Darwin's JSON, as it says so.
        val read = json.decodeFromString<DarwinBoardDto>("""{"crs":"STP","areServicesAvailable":false}""")
        assertTrue(runCatching { read.toRuns() }.exceptionOrNull() is IllegalStateException)
        // A board that doesn't say is available, as every one before it was read.
        assertTrue(json.decodeFromString<DarwinBoardDto>("""{"crs":"STP"}""").toRuns().isEmpty())
    }

    @Test
    fun `a board naming no station gives no train an id, and can't be followed by`() {
        val unnamed = board().copy(crs = null)
        assertTrue(unnamed.toBoard().departures.all { it.vehicleId.isEmpty() })
        try {
            unnamed.toRuns()
            throw AssertionError("read runs off a board naming no station")
        } catch (e: IllegalStateException) {
            // A failure the client reports, never runs placed nowhere.
        }
    }
}
