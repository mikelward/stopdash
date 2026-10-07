package app.stopdash.domain

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stop ids and codes only. */
class RailAwareTflClientTest {
    private val now = Instant.parse("2026-09-24T08:00:00Z")
    private fun departure(line: String, mode: String) =
        Departure(line, line, "", "Example", null, now.plusSeconds(60), mode)

    private val tfl = object : TflClient {
        val asked = mutableListOf<String>()
        override suspend fun arrivals(stopId: String): List<Departure> {
            asked += stopId
            return listOf(departure("overground-example", "overground"))
        }
        override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
        override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
    }

    private class Board(var key: Boolean = true, var fail: Boolean = false) : RailBoardSource {
        val asked = mutableListOf<String>()
        override val available get() = key
        override suspend fun departures(crs: String): List<Departure> {
            asked += crs
            if (fail) throw TflException.Unreachable("HTTP 401", null)
            return listOf(Departure("great-example", "Great Example", "", "Far", "Platform 1", Instant.EPOCH, "national-rail"))
        }
    }

    private val codes = RailStationCodes(mapOf("EXAMPLE" to "EXA"))

    @Test
    fun `a board's trains with no time come apart from the arrivals, kept with the board`() = runTest {
        val canceled = UntimedTrain(Departure("great-example", "Great Example", "", "Far", null, now, "national-rail"), canceled = true)
        var untimed = listOf(canceled)
        val board = object : RailBoardSource {
            override val available = true
            override suspend fun departures(crs: String) = error("the whole board is asked for")
            override suspend fun board(crs: String) =
                RailBoard(listOf(Departure("great-example", "Great Example", "", "Far", "Platform 1", now.plusSeconds(300), "national-rail")), untimed)
        }
        val boards = ArrivalsCache()
        val client = RailAwareTflClient(tfl, board, { codes }, boards = boards, clock = { now })
        // Never among the arrivals, which a trip or a countdown times; only through [untimed].
        assertEquals(listOf("overground-example", "great-example"), client.arrivals("910GEXAMPLE").map { it.lineId })
        assertEquals(listOf(canceled), client.untimed("910GEXAMPLE"))
        // Kept with the board: read from it within its age, they come back with it.
        untimed = emptyList()
        client.arrivals("910GEXAMPLE")
        assertEquals(listOf(canceled), client.untimed("910GEXAMPLE"))
        // A stop with no board has none, and neither has one whose board isn't asked for.
        assertEquals(emptyList<UntimedTrain>(), client.untimed("940GZZLUEXA"))
        client.arrivals("910GEXAMPLE", railBoard = false)
        assertEquals(emptyList<UntimedTrain>(), client.untimed("910GEXAMPLE"))
        // A fresh board with none leaves none.
        boards.clear()
        client.arrivals("910GEXAMPLE")
        assertEquals(emptyList<UntimedTrain>(), client.untimed("910GEXAMPLE"))
    }

    @Test
    fun `a trip's board carries calling points, sharing the plain board with a list's`() = runTest {
        val calling = listOf(CallingPortion(setOf("910GFAR"), complete = true))
        fun train(service: String, inSeconds: Long, callingAt: List<CallingPortion>? = null) =
            Departure("great-example", "Great Example", "", "Far", null, now.plusSeconds(inSeconds), "national-rail", callingAt = callingAt, railServiceId = service)
        val asked = mutableListOf<String>()
        var detailsFail = false
        val board = object : RailBoardSource {
            override val available = true
            override suspend fun departures(crs: String) = error("the whole board is asked for")
            override suspend fun board(crs: String): RailBoard {
                asked += "plain"
                return RailBoard(listOf(train("1", 300), train("2", 600)))
            }
            override suspend fun boardWithDetails(crs: String): RailBoard {
                asked += "details"
                if (detailsFail) throw TflException.Unreachable("HTTP 500", null)
                // Fewer trains: only the first.
                return RailBoard(listOf(train("1", 300, calling)))
            }
        }
        val warned = mutableListOf<String>()
        val boards = ArrivalsCache()
        val list = RailAwareTflClient(tfl, board, { codes }, boards = boards, clock = { now })
        val trip = RailAwareTflClient(tfl, board, { codes }, warn = { warned += it }, boardAtEveryStop = true, boards = boards, clock = { now }, callingPoints = true)
        list.arrivals("910GEXAMPLE")
        // The list's plain board serves the trip too: only the details are asked for (Codex, #650).
        val rail = trip.arrivals("910GEXAMPLE").filter { it.mode == "national-rail" }
        assertEquals(listOf(calling, null), rail.map { it.callingAt })
        assertEquals(listOf("plain", "details"), asked)
        // Both kept.
        trip.arrivals("910GEXAMPLE")
        assertEquals(listOf("plain", "details"), asked)
        // A plain board fetched afresh beside calling points kept from earlier: the stop is as old as
        // the calling points (Codex, #650). The list's board from [now] has expired by then; the trip's
        // calling points, from 30 s later, haven't.
        boards.clear()
        var at = now
        val stamped = RailAwareTflClient(tfl, board, { codes }, boardAtEveryStop = true, boards = boards, clock = { at }, callingPoints = true)
        list.arrivals("910GEXAMPLE")
        at = now.plusSeconds(30)
        stamped.arrivals("910GEXAMPLE")
        at = now.plusSeconds(55)
        stamped.arrivals("910GEXAMPLE")
        assertEquals(now.plusSeconds(30), stamped.fetchedAt("910GEXAMPLE"))
        // The details failing leaves the plain board, said so in the log.
        boards.clear()
        detailsFail = true
        assertTrue(trip.arrivals("910GEXAMPLE").filter { it.mode == "national-rail" }.all { it.callingAt == null })
        assertTrue(warned.any { it.startsWith("national rail calling points failed for stop 910GEXAMPLE") })
    }

    @Test
    fun `calling points pair with the plain board's trains by service`() {
        val calling = listOf(CallingPortion(setOf("910GFAR"), complete = true))
        fun train(service: String, callingAt: List<CallingPortion>? = null) =
            Departure("great-example", "Great Example", "", "Far", null, now, "national-rail", callingAt = callingAt, railServiceId = service)
        val merged = withCallingPoints(listOf(train("1"), train("2"), train("")), listOf(train("2", calling), train("", calling)))
        assertEquals(listOf(null, calling, null), merged.map { it.callingAt })
    }

    @Test
    fun `a rail station's National Rail departures join TfL's`() = runTest {
        val board = Board()
        val client = RailAwareTflClient(tfl, board, { codes })
        val lines = client.arrivals("910GEXAMPLE").map { it.lineId }
        assertEquals(listOf("overground-example", "great-example"), lines)
        assertEquals(listOf("EXA"), board.asked)
    }

    @Test
    fun `a stop not wanting the board asks TfL alone and has no National Rail feed`() = runTest {
        val board = Board()
        val kept = ArrivalsCache()
        val client = CachingTflClient(RailAwareTflClient(tfl, board, { codes }), kept)
        assertTrue(client.hasRailBoard("910GEXAMPLE"))
        assertFalse("not a station with a board", client.hasRailBoard("940GZZLUEXA"))
        board.key = false
        assertFalse("no key", client.hasRailBoard("910GEXAMPLE"))
        board.key = true
        client.arrivals("910GEXAMPLE")
        assertEquals(RailFeed.LIVE, client.railFeed("910GEXAMPLE"))
        assertEquals(listOf("overground-example"), client.arrivals("910GEXAMPLE", railBoard = false).map { it.lineId })
        assertEquals("the board asked once, for the first fetch only", listOf("EXA"), board.asked)
        assertEquals(null, client.railFeed("910GEXAMPLE"))
        assertEquals(null, client.fetchedAt("910GEXAMPLE"))
        // Never kept for another screen: a station a board could join isn't shareable, with or
        // without the board, so no other list or trip takes these for its full arrivals.
        assertEquals(null, kept.get("910GEXAMPLE", Instant.now(), client.arrivalsSource()))
        // Wanting it again asks again.
        assertEquals(2, client.arrivals("910GEXAMPLE", railBoard = true).size)
        assertEquals(listOf("EXA", "EXA"), board.asked)
    }

    @Test
    fun `leaving the board out without a key keeps the station's No key`() = runTest {
        val board = Board(key = false)
        val client = RailAwareTflClient(tfl, board, { codes })
        assertEquals(listOf("overground-example"), client.arrivals("910GEXAMPLE", railBoard = false).map { it.lineId })
        assertEquals(RailFeed.NO_KEY, client.railFeed("910GEXAMPLE"))
        assertTrue(board.asked.isEmpty())
    }

    @Test
    fun `a twin wanting the board takes it at once from an owner that stopped wanting it`() = runTest {
        val board = Board()
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        val client = RailAwareTflClient(tfl, board, { shared }, elapsedMillis = { 0L })
        fun List<Departure>.rail() = count { it.mode == "national-rail" }
        assertEquals(1, client.arrivals("910GTWINA").rail())
        // National Rail hidden: the near-me stop leaves the board out; a favorite journey's origin,
        // its twin, still shows it, well within the owner's hold.
        assertEquals(0, client.arrivals("910GTWINA", railBoard = false).rail())
        assertEquals(1, client.arrivals("910GTWINB", railBoard = true).rail())
        assertEquals(listOf("TWN", "TWN"), board.asked)
    }

    @Test
    fun `no key, no code, or not a rail station asks TfL alone`() = runTest {
        val board = Board(key = false)
        RailAwareTflClient(tfl, board, { codes }).arrivals("910GEXAMPLE")
        board.key = true
        RailAwareTflClient(tfl, board, { codes }).arrivals("910GUNKNOWN")
        RailAwareTflClient(tfl, board, { codes }).arrivals("940GZZLUEXA")
        assertTrue(board.asked.isEmpty())
    }

    @Test
    fun `a failed board is logged and the stop keeps its TfL departures`() = runTest {
        val warnings = mutableListOf<String>()
        val client = RailAwareTflClient(tfl, Board(fail = true), { codes }, warn = { warnings += it })
        assertEquals(listOf("overground-example"), client.arrivals("910GEXAMPLE").map { it.lineId })
        assertEquals(listOf("national rail board failed for stop 910GEXAMPLE: HTTP 401"), warnings)
    }

    @Test
    fun `two stops sharing a station code show its board under one, which keeps it while it asks`() = runTest {
        val board = Board()
        var clock = 0L
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        val client = RailAwareTflClient(tfl, board, { shared }, elapsedMillis = { clock })
        fun List<Departure>.rail() = count { it.mode == "national-rail" }
        assertEquals(1, client.arrivals("910GTWINA").rail())
        assertEquals("the twin gets TfL's rows only, and asks for no board", 0, client.arrivals("910GTWINB").rail())
        assertEquals(listOf("TWN"), board.asked)
        // A later refresh: the owner asks again and keeps it; the board is fetched afresh.
        clock += 60_000L
        assertEquals(1, client.arrivals("910GTWINA").rail())
        assertEquals(0, client.arrivals("910GTWINB").rail())
        assertEquals(listOf("TWN", "TWN"), board.asked)
        // Once the owner has stopped asking, the twin takes over.
        clock += RailAwareTflClient.OWNER_IDLE_MILLIS
        assertEquals(1, client.arrivals("910GTWINB").rail())
    }

    @Test
    fun `an owner whose own TfL fetch fails lets its twin show the board`() = runTest {
        val board = Board()
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        val failing = object : TflClient by tfl {
            override suspend fun arrivals(stopId: String): List<Departure> =
                if (stopId == "910GTWINA") throw TflException.Unreachable("HTTP 500", null) else tfl.arrivals(stopId)
        }
        val client = RailAwareTflClient(failing, board, { shared }, elapsedMillis = { 0L })
        assertTrue(runCatching { client.arrivals("910GTWINA") }.isFailure)
        assertEquals(1, client.arrivals("910GTWINB").count { it.mode == "national-rail" })
    }

    @Test
    fun `of two twins refreshed together, the one whose TfL fetch works shows the board`() = runTest {
        val board = Board()
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        // TWINA starts first and fails; TWINB's fetch works.
        val failing = object : TflClient by tfl {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == "910GTWINA") {
                    delay(20)
                    throw TflException.Unreachable("HTTP 500", null)
                }
                delay(10)
                return tfl.arrivals(stopId)
            }
        }
        val client = RailAwareTflClient(failing, board, { shared }, elapsedMillis = { 0L })
        suspend fun refresh(): Int = coroutineScope {
            val a = async { runCatching { client.arrivals("910GTWINA") } }
            val b = async { client.arrivals("910GTWINB") }
            assertTrue(a.await().isFailure)
            b.await().count { it.mode == "national-rail" }
        }
        assertEquals(1, refresh())
        assertEquals(1, refresh())
    }

    @Test
    fun `a twin that finishes first takes the board over when the standing owner's fetch fails`() = runTest {
        val board = Board()
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        var ownerFails = false
        val tflClient = object : TflClient by tfl {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == "910GTWINA") {
                    delay(20)
                    if (ownerFails) throw TflException.Unreachable("HTTP 500", null)
                } else {
                    delay(10)
                }
                return tfl.arrivals(stopId)
            }
        }
        val client = RailAwareTflClient(tflClient, board, { shared }, elapsedMillis = { 0L })
        suspend fun refresh(): Pair<Int?, Int> = coroutineScope {
            val a = async { runCatching { client.arrivals("910GTWINA") } }
            val b = async { client.arrivals("910GTWINB") }
            a.await().getOrNull()?.count { it.mode == "national-rail" } to b.await().count { it.mode == "national-rail" }
        }
        // TWINA becomes the owner.
        assertEquals(1, client.arrivals("910GTWINA").count { it.mode == "national-rail" })
        // Both refreshed together, the owner fetching slower: it keeps the board while its fetch works.
        assertEquals(1 to 0, refresh())
        // Its fetch fails: the twin, done first, waits to hear so and shows the board.
        ownerFails = true
        assertEquals(null to 1, refresh())
    }

    @Test
    fun `an idle owner asking again keeps its board, fetched once, when its twin asks too`() = runTest {
        val board = Board()
        var clock = 0L
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        // The owner's TfL fetch is the slower of the two.
        val slowOwner = object : TflClient by tfl {
            override suspend fun arrivals(stopId: String): List<Departure> {
                delay(if (stopId == "910GTWINA") 20 else 10)
                return tfl.arrivals(stopId)
            }
        }
        val client = RailAwareTflClient(slowOwner, board, { shared }, elapsedMillis = { clock })
        assertEquals(1, client.arrivals("910GTWINA").count { it.mode == "national-rail" })
        clock += RailAwareTflClient.OWNER_IDLE_MILLIS
        val (a, b) = coroutineScope {
            val a = async { client.arrivals("910GTWINA") }
            val b = async { client.arrivals("910GTWINB") }
            a.await().count { it.mode == "national-rail" } to b.await().count { it.mode == "national-rail" }
        }
        assertEquals(1 to 0, a to b)
        assertEquals("one board request per refresh", listOf("TWN", "TWN"), board.asked)
    }

    @Test
    fun `a twin taking over from a failed owner shows the board the owner already asked for`() = runTest {
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        // The owner fails either after its twin's fetch is in, or before.
        for (ownerDelay in listOf(20L, 5L)) {
            val board = Board()
            var ownerFails = false
            val tflClient = object : TflClient by tfl {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    if (stopId == "910GTWINA") {
                        delay(ownerDelay)
                        if (ownerFails) throw TflException.Unreachable("HTTP 500", null)
                    } else {
                        delay(10)
                    }
                    return tfl.arrivals(stopId)
                }
            }
            val client = RailAwareTflClient(tflClient, board, { shared }, elapsedMillis = { 0L })
            assertEquals(1, client.arrivals("910GTWINA").count { it.mode == "national-rail" })
            ownerFails = true
            val b = coroutineScope {
                val a = async { runCatching { client.arrivals("910GTWINA") } }
                val b = async { client.arrivals("910GTWINB") }
                assertTrue(a.await().isFailure)
                b.await().count { it.mode == "national-rail" }
            }
            assertEquals("owner delay $ownerDelay", 1, b)
            assertEquals("owner delay $ownerDelay: one request per refresh", listOf("TWN", "TWN"), board.asked)
        }
    }

    @Test
    fun `each stop says why it has no National Rail times`() = runTest {
        val board = Board(key = false)
        val client = RailAwareTflClient(tfl, board, { codes })
        client.arrivals("910GEXAMPLE")
        assertEquals("no key: a key would give times", RailFeed.NO_KEY, client.railFeed("910GEXAMPLE"))
        client.arrivals("910GUNKNOWN")
        assertEquals("no code: a key would give nothing", null, client.railFeed("910GUNKNOWN"))
        client.arrivals("940GZZLUEXA")
        assertEquals(null, client.railFeed("940GZZLUEXA"))
        board.key = true
        board.fail = true
        client.arrivals("910GEXAMPLE")
        assertEquals("the board failed", RailFeed.UNAVAILABLE, client.railFeed("910GEXAMPLE"))
        board.fail = false
        client.arrivals("910GEXAMPLE")
        assertEquals("the board came back", RailFeed.LIVE, client.railFeed("910GEXAMPLE"))
    }

    @Test
    fun `an operator's name makes TfL's line id`() {
        assertEquals("great-northern", railLineId("Great Northern"))
        assertEquals("c2c", railLineId("c2c"))
        assertEquals("london-north-eastern-railway", railLineId("London North Eastern Railway"))
        // By operator code where the name doesn't slug to TfL's id: West Midlands Trains' brands,
        // and Northern, whose name would take the tube's Northern line id.
        assertEquals("west-midlands-trains", railLineId("London Northwestern Railway", "LM"))
        assertEquals("northern-rail", railLineId("Northern", "NT"))
        // An operator TfL has no line for keeps its slug.
        assertEquals("caledonian-sleeper", railLineId("Caledonian Sleeper", "CS"))
    }

    @Test
    fun `a station's arrivals are another client's to reuse only without National Rail times`() {
        val board = Board()
        val client = RailAwareTflClient(tfl, board, { codes })
        // Its board goes under whichever of its stops this client picked.
        assertEquals(false, client.shareable("910GEXAMPLE"))
        assertEquals(true, client.shareable("940GZZLUEXA"))
        assertEquals(true, client.arrivalsSource())
        board.key = false
        assertEquals(true, client.shareable("910GEXAMPLE"))
        // Its arrivals now come without National Rail times: another source.
        assertEquals(false, client.arrivalsSource())
    }

    @Test
    fun `a trip's client gives every stop of a station its board, asked for once`() = runTest {
        val board = Board()
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        val cache = ArrivalsCache()
        val client = RailAwareTflClient(tfl, board, { shared }, boardAtEveryStop = true, boards = cache, clock = { now })
        fun List<Departure>.rail() = count { it.mode == "national-rail" }
        // A train boarding at either stop is timed from the station's board.
        assertEquals(1, client.arrivals("910GTWINA").rail())
        assertEquals(1, client.arrivals("910GTWINB").rail())
        assertEquals(RailFeed.LIVE, client.railFeed("910GTWINB"))
        assertEquals(listOf("TWN"), board.asked)
        // Still never another client's to reuse by stop: a list's client shows it under one only.
        assertEquals(false, client.shareable("910GTWINB"))
    }

    @Test
    fun `a trip's stops of one station asking at once share one board request and its outcome`() = runTest {
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        for (fails in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val board = object : RailBoardSource {
                val asked = mutableListOf<String>()
                override val available = true
                override suspend fun departures(crs: String): List<Departure> {
                    asked += crs
                    gate.await()
                    if (fails) throw TflException.Unreachable("HTTP 503", null)
                    return listOf(departure("great-example", "national-rail"))
                }
            }
            val client = RailAwareTflClient(tfl, board, { shared }, boardAtEveryStop = true, boards = ArrivalsCache(), clock = { now })
            val (a, b) = coroutineScope {
                val a = async { client.arrivals("910GTWINA") }
                val b = async { client.arrivals("910GTWINB") }
                // Both are waiting on the board before it answers.
                delay(1)
                gate.complete(Unit)
                a.await() to b.await()
            }
            assertEquals(listOf("TWN"), board.asked)
            val expected = if (fails) 0 else 1
            assertEquals(expected, a.count { it.mode == "national-rail" })
            assertEquals(expected, b.count { it.mode == "national-rail" })
            val feed = if (fails) RailFeed.UNAVAILABLE else RailFeed.LIVE
            assertEquals(feed, client.railFeed("910GTWINA"))
            assertEquals(feed, client.railFeed("910GTWINB"))
        }
    }

    @Test
    fun `two screens' clients asking for one station at once share one board request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val board = object : RailBoardSource {
            val asked = mutableListOf<String>()
            override val available = true
            override suspend fun departures(crs: String): List<Departure> {
                asked += crs
                gate.await()
                return listOf(departure("great-example", "national-rail"))
            }
        }
        val cache = ArrivalsCache()
        val list = RailAwareTflClient(tfl, board, { codes }, boards = cache, clock = { now })
        val trip = RailAwareTflClient(tfl, board, { codes }, boardAtEveryStop = true, boards = cache, clock = { now })
        val (fromList, fromTrip) = coroutineScope {
            val a = async { list.arrivals("910GEXAMPLE") }
            val b = async { trip.arrivals("910GEXAMPLE") }
            delay(1)
            gate.complete(Unit)
            a.await() to b.await()
        }
        assertEquals(listOf("EXA"), board.asked)
        assertEquals(1, fromList.count { it.mode == "national-rail" })
        assertEquals(1, fromTrip.count { it.mode == "national-rail" })
    }

    @Test
    fun `a board fetched for one screen is every screen's until it's a minute old`() = runTest {
        val board = Board()
        val cache = ArrivalsCache()
        var at = now
        val list = RailAwareTflClient(tfl, board, { codes }, boards = cache, clock = { at })
        val trip = RailAwareTflClient(tfl, board, { codes }, boardAtEveryStop = true, boards = cache, clock = { at })
        val listAsked = at
        list.arrivals("910GEXAMPLE")
        assertEquals("fetched as the stop was asked for: no older part", listAsked, list.stampOf("910GEXAMPLE", listAsked))
        at = at.plusSeconds(20)
        assertEquals(listOf("overground-example", "great-example"), trip.arrivals("910GEXAMPLE").map { it.lineId })
        assertEquals("the trip reads the list's board", listOf("EXA"), board.asked)
        // At its own age: the trip's arrivals there are as old as their oldest part.
        assertEquals(listAsked, trip.fetchedAt("910GEXAMPLE"))
        assertEquals(listAsked, trip.stampOf("910GEXAMPLE", at))
        // Past the arrivals' own time to live, it's asked for afresh.
        at = at.plus(ArrivalsCache.TTL)
        trip.arrivals("910GEXAMPLE")
        assertEquals(listOf("EXA", "EXA"), board.asked)
        // A pull to refresh clears it with every stop's arrivals.
        cache.clear()
        list.arrivals("910GEXAMPLE")
        assertEquals(listOf("EXA", "EXA", "EXA"), board.asked)
    }

    @Test
    fun `a failed board is never kept`() = runTest {
        val board = Board(fail = true)
        val cache = ArrivalsCache()
        val client = RailAwareTflClient(tfl, board, { codes }, boardAtEveryStop = true, boards = cache, clock = { now })
        assertEquals(listOf("overground-example"), client.arrivals("910GEXAMPLE").map { it.lineId })
        assertEquals(RailFeed.UNAVAILABLE, client.railFeed("910GEXAMPLE"))
        board.fail = false
        assertEquals(listOf("overground-example", "great-example"), client.arrivals("910GEXAMPLE").map { it.lineId })
        assertEquals(listOf("EXA", "EXA"), board.asked)
    }

    @Test
    fun `a trip's stop whose TfL fetch fails fails, as any stop does`() {
        val failing = object : TflClient by tfl {
            override suspend fun arrivals(stopId: String): List<Departure> = throw TflException.Unreachable("HTTP 503", null)
        }
        val client = RailAwareTflClient(failing, Board(), { codes }, boardAtEveryStop = true, boards = ArrivalsCache(), clock = { now })
        assertThrows(TflException.Unreachable::class.java) { runBlocking { client.arrivals("910GEXAMPLE") } }
    }
}
