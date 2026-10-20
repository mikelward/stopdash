package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Following a National Rail train by its stations' boards (SPEC *On the way*): a southbound Thameslink
 * train picked at St Pancras for Brighton, through Farringdon, City Thameslink, Blackfriars and London
 * Bridge. Station codes and TfL ids as National Rail and TfL give them; times synthetic.
 */
class RailFollowTest {
    private val t0 = Instant.parse("2026-10-15T16:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    private val ids = mapOf(
        "STP" to "910GSTPXBOX", "ZFD" to "910GFRNDNLT", "CTK" to "910GCTMSLNK",
        "BFR" to "910GBLFR", "LBG" to "910GLNDNBDC", "BTN" to "910GBRGHTN",
    )
    // Every TfL id of a station code, as the bundled codes give them: one each here.
    private val idsOf: (String) -> Set<String> = { setOfNotNull(ids[it]) }
    private val names = mapOf(
        "STP" to "London St Pancras International", "ZFD" to "Farringdon", "CTK" to "City Thameslink",
        "BFR" to "London Blackfriars", "LBG" to "London Bridge", "BTN" to "Brighton",
    )
    private val stops = listOf("STP" to 0L, "ZFD" to 3L, "CTK" to 5L, "BFR" to 7L, "LBG" to 11L, "BTN" to 70L)

    private fun point(crs: String, minutes: Long, late: Long? = 0) =
        RailPoint(crs, names.getValue(crs), at(minutes), late?.let { at(minutes + it) })

    // The train as the board at [crs] lists it, [late] minutes behind its schedule, gone from each stop before.
    private fun run(crs: String, late: Long? = 0, destination: String = "BTN", starts: Long = 0): RailRun {
        val here = stops.indexOfFirst { it.first == crs }
        val due = stops[here].second + starts
        return RailRun(
            atCrs = crs,
            atName = names.getValue(crs),
            lineId = "thameslink",
            scheduled = at(due),
            expected = late?.let { at(due + it) },
            platform = "Platform A",
            destinationCrs = destination,
            previous = stops.take(here).map { (code, minutes) -> point(code, minutes + starts).copy(left = true) },
            next = stops.drop(here + 1).map { (code, minutes) -> point(code, minutes + starts, late) },
        )
    }

    private val id = RailFollow.idOf("STP", at(0), "BTN", "thameslink")
    private val key = RailFollow.keyOf(id)!!

    @Test
    fun `a train's id says where it was picked, when it was due to leave there and where it ends`() {
        assertEquals(RailFollow.Key("STP", at(0), "BTN", "thameslink"), key)
        // A TfL vehicle's id is no National Rail train's.
        assertNull(RailFollow.keyOf("162"))
        assertNull(RailFollow.keyOf("rail:STP/soon/BTN/thameslink"))
        assertNull(RailFollow.keyOf("rail:STP/1760544000/BTN"))
        assertNull(RailFollow.keyOf("rail:STP/1760544000"))
    }

    @Test
    fun `it's found at the station it was picked at, and later by when it called there`() {
        val next = run("STP", starts = 15)
        assertEquals(run("STP"), RailFollow.find(listOf(next, run("STP")), key))
        // At London Bridge, by its departure from St Pancras among the stops it called at.
        assertEquals(run("LBG"), RailFollow.find(listOf(run("LBG", starts = 15), run("LBG")), key))
        // Not one to another terminus.
        assertNull(RailFollow.find(listOf(run("STP", destination = "BDM")), key))
    }

    @Test
    fun `a train due at its station a minute either side is another train, however near (Codex, #883)`() {
        // The one picked gone from the board: the next along, two minutes off, isn't taken for it.
        assertNull(RailFollow.find(listOf(run("STP", starts = 2)), key))
        // Later on, by when it called there: the other's call is nearer than a long dwell, still not it.
        assertNull(RailFollow.find(listOf(run("LBG", starts = 1), run("LBG", starts = -2)), key))
        // With the one picked listed beside it, that one, wherever the other's time falls.
        assertEquals(run("LBG"), RailFollow.find(listOf(run("LBG", starts = 1), run("LBG")), key))
    }

    @Test
    fun `another line's train due at its station then, to its terminus, isn't it (Codex, #883)`() {
        val southern = run("STP").copy(lineId = "southern")
        assertNull(RailFollow.find(listOf(southern), key))
        assertEquals(run("LBG"), RailFollow.find(listOf(run("LBG").copy(lineId = "southern"), run("LBG")), key))
    }

    @Test
    fun `two trains fitting its time are neither, never a guess`() {
        assertNull(RailFollow.find(listOf(run("STP"), run("STP").copy(platform = "Platform B")), key))
    }

    // The train as the board at [crs] lists it, held: not yet gone from [from] or any stop after it, [late]
    // minutes behind its schedule at each.
    private fun held(crs: String, from: String, late: Long): RailRun {
        val run = run(crs, late)
        val since = run.previous.indexOfFirst { it.crs == from }
        return run.copy(previous = run.previous.mapIndexed { i, p -> if (i < since) p else p.copy(left = false, expected = p.scheduled?.plus(Duration.ofMinutes(late))) })
    }

    // [run] as its board lists it still to leave [crs], a stop before the board's, due there at [minutes].
    private fun notLeft(run: RailRun, crs: String, minutes: Long?) =
        run.copy(previous = run.previous.map { if (it.crs == crs) it.copy(expected = minutes?.let(::at), left = false) else it })

    @Test
    fun `its calls are its station while still to leave, then each stop on with an estimate`() {
        val calls = RailFollow.calls(run("STP", late = 2), "STP", idsOf)
        assertEquals(listOf("910GSTPXBOX", "910GFRNDNLT", "910GCTMSLNK", "910GBLFR", "910GLNDNBDC", "910GBRGHTN"), calls!!.map { it.stopId })
        assertEquals(at(2), calls.first().expected)
        assertEquals("Platform A", calls.first().platform)
        assertEquals(at(13), calls[4].expected)
        assertEquals("London Bridge", calls[4].stopName)
        // A station with no TfL id is named, for the trip to match by name.
        assertEquals("", RailFollow.calls(run("STP"), "STP", { emptySet() })!!.first().stopId)
        // One TfL lists under several ids (St Pancras's three) names none of them, and is at each.
        val stPancras = setOf("910GSTPX", "910GSTPXBOX", "910GSTPADOM")
        val there = RailFollow.calls(run("STP"), "STP", stopIdsFor = { if (it == "STP") stPancras else idsOf(it) })!!.first()
        assertEquals("", there.stopId)
        assertEquals(stPancras, there.stationIds)
    }

    @Test
    fun `each call's station is placed by the model where it comes in, none without one`() {
        val stPancras = setOf("910GSTPX", "910GSTPXBOX", "910GSTPADOM")
        val ids: (String) -> Set<String> = { if (it == "STP") stPancras else idsOf(it) }
        val placed = RailFollow.calls(run("STP"), "STP", ids) { records -> Resolved(null, "station:" + records.min(), "HUBKGX", poles = records) }!!
        // St Pancras's three records, as one placed station with all three kept.
        assertEquals("station:910GSTPADOM", placed.first().place?.station)
        assertEquals(stPancras, placed.first().place?.poles)
        assertEquals("station:910GFRNDNLT", placed[1].place?.station)
        // A station with no TfL id has nothing to place.
        assertNull(RailFollow.calls(run("STP"), "STP", { emptySet() }) { Resolved(null, "x", "x") }!!.first().place)
        // Without a model, unplaced.
        assertTrue(RailFollow.calls(run("STP"), "STP", ids)!!.all { it.place == null })
    }

    @Test
    fun `its last call ends its run where the board gives every stop it has left (Codex, #883)`() {
        // Through Brighton, an estimate at each: the last is where it ends.
        assertEquals(listOf(false, false, false, false, false, true), RailFollow.calls(run("STP"), "STP", idsOf)!!.map { it.endsRun })
        // Cut short at City Thameslink, its calls past there canceled: it ends there now.
        val cut = run("STP").let { it.copy(next = it.next.take(2)) }
        assertEquals(listOf("910GSTPXBOX", "910GFRNDNLT", "910GCTMSLNK"), RailFollow.calls(cut, "STP", idsOf)!!.map { it.stopId })
        assertTrue(RailFollow.calls(cut, "STP", idsOf)!!.last().endsRun)
        // With no estimate at a stop on, what's past it isn't known, so nothing ends its run.
        val delayed = run("STP").let { it.copy(next = it.next.mapIndexed { i, p -> if (i == 2) p.copy(expected = null) else p }) }
        assertTrue(RailFollow.calls(delayed, "STP", idsOf)!!.none { it.endsRun })
        // Canceled at City Thameslink and every stop after: it ends at Farringdon, which it hasn't left.
        val endsBefore = notLeft(run("CTK").copy(expected = null, canceled = true, next = emptyList()), "ZFD", 4)
        assertEquals(listOf(true), RailFollow.calls(endsBefore, "STP", idsOf)!!.map { it.endsRun })
        // Canceled there with no stops after given, where it ends isn't known.
        assertEquals(listOf(false), RailFollow.calls(endsBefore.copy(throughTerminus = false), "STP", idsOf)!!.map { it.endsRun })
        // Gone from every stop before, and canceled there and on: no call left, its run ended; not knowing where it
        // ends, nothing is known.
        val ended = run("CTK").copy(expected = null, canceled = true, next = emptyList())
        assertEquals(emptyList<VehicleCall>(), RailFollow.calls(ended, "STP", idsOf))
        assertNull(RailFollow.calls(ended.copy(throughTerminus = false), "STP", idsOf))
    }

    @Test
    fun `one whose run ended before the board it's found on is no longer known, never an empty list (Codex, #883)`() = runTest {
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        var now = at(-5)
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        // City Thameslink's board: gone from every stop before, canceled there and at each after.
        boards.at = mapOf("CTK" to listOf(run("CTK").copy(expected = null, canceled = true, next = emptyList())))
        now = at(4)
        try {
            vehicles.vehicleCalls(id, "thameslink")
            fail("an ended run answered with calls, or none")
        } catch (e: TflException.NotFound) {
            // As TfL says of a train that has finished: on board the trip says it couldn't update, never that
            // the rider got off.
        }
        // Canceled there with its stops after unknown: nothing known, a failed update.
        boards.at = mapOf("CTK" to listOf(run("CTK").copy(expected = null, canceled = true, next = emptyList(), throughTerminus = false)))
        now = at(5)
        try {
            vehicles.vehicleCalls(id, "thameslink")
            fail("calls claimed with nothing known")
        } catch (e: TflException.Unreachable) {
            // The trip keeps the train and says it couldn't update.
        }
    }

    @Test
    fun `no estimate ends its calls, a time past it being unknown`() {
        val run = run("STP").let { it.copy(next = it.next.mapIndexed { i, p -> if (i == 2) p.copy(expected = null) else p }) }
        assertEquals(listOf("910GSTPXBOX", "910GFRNDNLT", "910GCTMSLNK"), RailFollow.calls(run, "STP", idsOf)?.map { it.stopId })
        // With no time at its own station (canceled, or "Delayed"), nothing is claimed: its later calls
        // alone would say it has gone on past (Codex, #883).
        assertNull(RailFollow.calls(run("STP", late = null).copy(next = run("STP").next), "STP", idsOf))
    }

    @Test
    fun `a later board's calls start where it says the train hasn't left, from where it was picked (Codex, #883)`() {
        // City Thameslink's board lists it gone from St Pancras, not yet from Farringdon: due there first.
        val calls = RailFollow.calls(notLeft(run("CTK"), "ZFD", 4), "STP", idsOf)!!
        assertEquals(listOf("910GFRNDNLT", "910GCTMSLNK", "910GBLFR"), calls.take(3).map { it.stopId })
        assertEquals(at(4), calls.first().expected)
        // Not yet at St Pancras either: due there, then Farringdon.
        val both = notLeft(notLeft(run("CTK"), "ZFD", 4), "STP", 1)
        assertEquals(listOf("910GSTPXBOX", "910GFRNDNLT", "910GCTMSLNK"), RailFollow.calls(both, "STP", idsOf)!!.take(3).map { it.stopId })
        // A stop before the one it was picked at is no part of the ride, left or not.
        assertEquals("910GCTMSLNK", RailFollow.calls(notLeft(run("CTK"), "STP", 1), "ZFD", idsOf)!!.first().stopId)
        // "Delayed" at a stop it hasn't left: nothing past it is known.
        assertNull(RailFollow.calls(notLeft(run("CTK"), "ZFD", null), "STP", idsOf))
    }

    @Test
    fun `one delayed at its station with no estimate is a failed update, one canceled there no train to take`() = runTest {
        val boards = Boards(mapOf("STP" to listOf(run("STP", late = null).copy(next = run("STP").next))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { at(-5) })
        try {
            vehicles.vehicleCalls(id, "thameslink")
            fail("calls claimed for a train with no time at its station")
        } catch (e: TflException.Unreachable) {
            // Nothing known: the trip keeps the train and says it couldn't update.
        }
        boards.at = mapOf("STP" to listOf(run("STP", late = null).copy(next = run("STP").next, canceled = true)))
        val canceled = RailVehicles(tfl, boards::runs, idsOf, clock = { at(-5) })
        try {
            canceled.vehicleCalls(id, "thameslink")
            fail("calls claimed for a train canceled at its station")
        } catch (e: TflException.NotFound) {
            // It won't call here: a trip waiting for it picks another.
        }
    }

    @Test
    fun `one canceled at a station further on calls on past it, still the train to take (Codex, #883)`() = runTest {
        // City Thameslink's board lists it canceled there, not yet gone from Farringdon: Darwin cancels that one
        // call, and the train still runs on to London Bridge and Brighton.
        val skips = notLeft(run("CTK").copy(expected = null, canceled = true), "ZFD", 4)
        val calls = RailFollow.calls(skips, "STP", idsOf)!!
        assertEquals(listOf("910GFRNDNLT", "910GBLFR", "910GLNDNBDC", "910GBRGHTN"), calls.map { it.stopId })
        assertEquals(at(4), calls.first().expected)
        // Picked at St Pancras, read there, then found on City Thameslink's board with Farringdon's board leaving it
        // out: its calls on past, never a train no longer known for a waiting rider to give up.
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        var now = at(-5)
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        boards.at = mapOf("CTK" to listOf(skips))
        now = at(1)
        assertEquals(calls, vehicles.vehicleCalls(id, "thameslink"))
        // With no time at Farringdon, which it hasn't left, nothing is known: a failed update, the train kept.
        boards.at = mapOf("CTK" to listOf(notLeft(run("CTK").copy(expected = null, canceled = true), "ZFD", null)))
        now = at(2)
        try {
            vehicles.vehicleCalls(id, "thameslink")
            fail("calls claimed past a stop with no time")
        } catch (e: TflException.Unreachable) {
            // The trip keeps the train and says it couldn't update.
        }
    }

    @Test
    fun `a joined train's stops before are the portion it was picked on, never another's (Codex, #883)`() {
        fun p(crs: String, minutes: Long) = RailPoint(crs, crs, at(minutes), at(minutes))
        // Two portions join at J before London Bridge, one from A, one from B, neither gone from any stop yet.
        val joined = run("LBG").copy(previous = listOf(p("A", -20), p("J", -5)), joining = listOf(listOf(p("B", -18), p("C", -10))))
        // Picked at A: its own portion on, never B's stops.
        assertEquals(listOf("A", "J", "London Bridge"), RailFollow.calls(joined, "A", idsOf)!!.take(3).map { it.stopName })
        // Picked at B: its portion, then the first one's from where they joined.
        assertEquals(listOf("B", "C", "J", "London Bridge"), RailFollow.calls(joined, "B", idsOf)!!.take(4).map { it.stopName })
        // Found again by when it was due at B, on its own portion, and asked after along it.
        assertEquals(joined, RailFollow.find(listOf(joined), RailFollow.Key("B", at(-18), "BTN", "thameslink")))
        assertEquals(RailFollow.Asks(listOf("B", "C"), emptyList()), RailFollow.toAsk(joined, "B", at(-25)))
        assertEquals(RailFollow.Asks(listOf("J", "LBG"), listOf("A")), RailFollow.toAsk(joined, "A", at(-8)))
    }

    @Test
    fun `the boards to ask are where it's due next and the one past, then each before in turn, never its terminus`() {
        // Not yet due to leave St Pancras: there, and Farringdon for one that left a little early.
        assertEquals(RailFollow.Asks(listOf("STP", "ZFD"), emptyList()), RailFollow.toAsk(run("STP"), "STP", at(-5)))
        // Due at City Thameslink next: its board lists it wherever it is before; those before are asked in turn,
        // none left out for its estimate gone by (a train held there, Codex, #883).
        assertEquals(RailFollow.Asks(listOf("CTK", "BFR"), listOf("STP", "ZFD")), RailFollow.toAsk(run("STP"), "STP", at(3)))
        assertEquals(RailFollow.Asks(listOf("LBG"), listOf("STP", "ZFD", "CTK", "BFR")), RailFollow.toAsk(run("STP"), "STP", at(8)))
        // Due to have left them all: the last before its terminus, which no departure board lists it at.
        assertEquals(RailFollow.Asks(listOf("LBG"), listOf("STP", "ZFD", "CTK", "BFR")), RailFollow.toAsk(run("STP"), "STP", at(30)))
        assertEquals(RailFollow.Asks(listOf("LBG"), emptyList()), RailFollow.toAsk(run("LBG"), "STP", at(30)))
        // A stop with no time on the way says nothing of whether it's past it.
        val delayed = run("STP").let { it.copy(next = it.next.map { p -> if (p.crs == "CTK") p.copy(expected = null) else p }) }
        assertEquals(RailFollow.Asks(listOf("BFR", "LBG"), listOf("STP", "ZFD", "CTK")), RailFollow.toAsk(delayed, "STP", at(6)))
        // Listed at Farringdon not yet gone from St Pancras: St Pancras is among them, as its calls are (Codex, #883).
        val held = notLeft(run("ZFD", late = 3), "STP", 2)
        assertEquals(RailFollow.Asks(listOf("STP", "ZFD"), emptyList()), RailFollow.toAsk(held, "STP", at(1)))
        assertEquals(RailFollow.Asks(listOf("ZFD", "CTK"), listOf("STP")), RailFollow.toAsk(held, "STP", at(3)))
    }

    // Boards by station, and every request made, to follow the train through.
    private class Boards(var at: Map<String, List<RailRun>>) {
        val asked = mutableListOf<String>()
        var failing: TflException? = null

        // A station whose board alone fails.
        var failingAt: Map<String, TflException> = emptyMap()

        // Each station's board from so many minutes on, past the ten [at] lists from now.
        var later: Map<Int, Map<String, List<RailRun>>> = emptyMap()

        // How old each board already was, as the proxy's `Age` says.
        var age: Duration = Duration.ZERO

        suspend fun runs(crs: String, towards: String, offset: Int): RailRuns? {
            asked += "$crs>$towards" + if (offset == 0) "" else "+$offset"
            failing?.let { throw it }
            failingAt[crs]?.let { throw it }
            return RailRuns(if (offset == 0) at[crs].orEmpty() else later[offset]?.get(crs).orEmpty(), age)
        }
    }

    @Test
    fun `one picked further out than a board's ten to its terminus is asked for from near its time (Codex, #883)`() = runTest {
        // An hour before it's due, the ten trains to Brighton St Pancras's board lists all leave before it.
        val first = (0 until 10).map { run("STP", starts = -55L + 5 * it) }
        val boards = Boards(mapOf("STP" to first))
        boards.later = mapOf(45 to mapOf("STP" to listOf(run("STP", starts = -10), run("STP", starts = -5), run("STP"))))
        var now = at(-60)
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        assertEquals("910GSTPXBOX", vehicles.vehicleCalls(id, "thameslink").first().stopId)
        // From 45 minutes on, fifteen before it's due: one request, never the board that can't list it.
        assertEquals(listOf("STP>BTN+45"), boards.asked)
        // Due within the next 25 minutes, the board from now lists it among its first.
        boards.at = mapOf("STP" to listOf(run("STP")))
        now = at(-24)
        assertEquals("910GSTPXBOX", vehicles.vehicleCalls(id, "thameslink").first().stopId)
        assertEquals("STP>BTN", boards.asked.last())
    }

    @Test
    fun `a board is asked from a quarter hour at least ten minutes before the train, within Darwin's reach`() {
        assertEquals(0, RailFollow.offsetMinutes(at(24), at(0)))
        assertEquals(15, RailFollow.offsetMinutes(at(25), at(0)))
        assertEquals(15, RailFollow.offsetMinutes(at(39), at(0)))
        assertEquals(45, RailFollow.offsetMinutes(at(60), at(0)))
        // Due already, or held past it: from now.
        assertEquals(0, RailFollow.offsetMinutes(at(-5), at(0)))
        // Darwin goes no further than 119 minutes on.
        assertEquals(105, RailFollow.offsetMinutes(at(240), at(0)))
    }

    private val tfl = object : VehicleSource {
        val asked = mutableListOf<String>()
        override suspend fun vehicleCalls(vehicleId: String, lineId: String): List<VehicleCall> {
            asked += "$lineId/$vehicleId"
            return emptyList()
        }
    }

    @Test
    fun `a TfL train is still TfL's`() = runTest {
        val vehicles = RailVehicles(tfl, { _, _, _ -> fail("asked National Rail"); null }, idsOf)
        vehicles.vehicleCalls("162", "jubilee")
        assertEquals(listOf("jubilee/162"), tfl.asked)
    }

    @Test
    fun `the train is followed from board to board as it goes`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        // Picked: St Pancras's board, asked toward its terminus.
        assertEquals("910GSTPXBOX", vehicles.vehicleCalls(id, "thameslink").first().stopId)
        assertEquals(listOf("STP>BTN"), boards.asked)
        // Gone from St Pancras's: Farringdon's lists it, its calls from there on.
        now = at(1)
        boards.at = mapOf("ZFD" to listOf(run("ZFD", late = 1)))
        val calls = vehicles.vehicleCalls(id, "thameslink")
        assertEquals("910GFRNDNLT", calls.first().stopId)
        assertEquals(at(12), calls.first { it.stopId == "910GLNDNBDC" }.expected)
        // Due at Farringdon next, so its board is asked: it lists it gone from St Pancras by when it was there.
        assertEquals(listOf("STP>BTN", "ZFD>BTN"), boards.asked)
        assertEquals(at(1), vehicles.readAt(id))
        // A TfL train's are TfL's to date.
        assertNull(vehicles.readAt("162"))
    }

    @Test
    fun `a train read from an already-old board is dated by the board's age, not the ask (Codex, #944)`() = runTest {
        val now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        boards.age = Duration.ofSeconds(29)
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        assertEquals(now.minusSeconds(29), vehicles.readAt(id))
        // A board that lists none is as old too: reused only for what's left of its own 20 s, so a train
        // appearing on it is seen once the board is (Codex, #944).
        val bare = Boards(mapOf("STP" to emptyList()))
        bare.age = Duration.ofSeconds(29)
        val empty = RailVehicles(tfl, bare::runs, idsOf, clock = { now })
        assertTrue(runCatching { empty.vehicleCalls(id, "thameslink") }.isFailure)
        bare.at = mapOf("STP" to listOf(run("STP")))
        assertTrue(empty.vehicleCalls(id, "thameslink").isNotEmpty())
        assertEquals(listOf("STP>BTN", "STP>BTN"), bare.asked)
    }

    @Test
    fun `one a later board lists before it has left its station is still due there (Codex, #883)`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        // Held at St Pancras past its estimate: Farringdon's board lists it, not yet gone from St Pancras by that
        // board's word. Still due there, never taken to have left.
        now = at(1)
        boards.at = mapOf("ZFD" to listOf(notLeft(run("ZFD", late = 3), "STP", 2)))
        val calls = vehicles.vehicleCalls(id, "thameslink")
        assertEquals(listOf("910GSTPXBOX", "910GFRNDNLT"), calls.take(2).map { it.stopId })
        assertEquals(at(2), calls.first().expected)
        assertEquals(listOf("STP>BTN", "ZFD>BTN"), boards.asked)
        // Gone from there by Farringdon's word: its calls from Farringdon.
        now = at(4)
        boards.at = mapOf("ZFD" to listOf(run("ZFD", late = 3)))
        assertEquals("910GFRNDNLT", vehicles.vehicleCalls(id, "thameslink").first().stopId)
    }

    @Test
    fun `a station a later board said it was held at is still asked once that board leaves it out (Codex, #883)`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        // Farringdon's board lists it, not yet gone from St Pancras.
        now = at(1)
        boards.at = mapOf("ZFD" to listOf(notLeft(run("ZFD", late = 3), "STP", 2)))
        vehicles.vehicleCalls(id, "thameslink")
        // Still held past that estimate, and now left off the boards further on, ten trains long: St Pancras, where
        // Farringdon's board said it was, is asked in turn, and still lists it.
        now = at(3)
        boards.at = mapOf("STP" to listOf(run("STP", late = 4)))
        val calls = vehicles.vehicleCalls(id, "thameslink")
        assertEquals("910GSTPXBOX", calls.first().stopId)
        assertEquals(at(4), calls.first().expected)
        assertEquals(listOf("STP>BTN", "ZFD>BTN", "ZFD>BTN", "CTK>BTN", "STP>BTN"), boards.asked)
    }

    @Test
    fun `on no board, even past them all by its estimates, it's a failed update, a few boards a refresh (Codex, #883)`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        // Past every board by its last estimates, and on none: held where a board of ten leaves it out, or on its
        // way to Brighton. Which isn't known, so it's neither.
        boards.at = emptyMap()
        // A refresh on, never left as past them all: asked after again, the last board before Brighton and one
        // station before it in turn, never every station each refresh.
        for (refresh in listOf(at(20), at(20).plusSeconds(30))) {
            now = refresh
            try {
                vehicles.vehicleCalls(id, "thameslink")
                fail("calls claimed for a train no board lists")
            } catch (e: TflException.Unreachable) {
                // A failed update: the trip keeps it as it was.
            }
        }
        assertEquals(listOf("STP>BTN", "LBG>BTN", "STP>BTN", "LBG>BTN", "ZFD>BTN"), boards.asked)
        // Listed again further on, still at St Pancras by that board's word: found there.
        now = at(21)
        boards.at = mapOf("LBG" to listOf(held("LBG", "STP", 22)))
        val calls = vehicles.vehicleCalls(id, "thameslink")
        assertEquals("910GSTPXBOX", calls.first().stopId)
        assertEquals(at(22), calls.first().expected)
    }

    @Test
    fun `a read shared by a later answer dates it`() = runTest {
        var now = at(-10)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        now = at(-10).plusSeconds(15)
        vehicles.vehicleCalls(id, "thameslink")
        assertEquals(1, boards.asked.size)
        assertEquals(at(-10), vehicles.readAt(id))
    }

    @Test
    fun `one that has left before the next board lists it is asked for there next`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        // Due to leave St Pancras now: Farringdon's board, the one it's due at next, says it has.
        now = at(0)
        boards.at = mapOf("ZFD" to listOf(run("ZFD")))
        assertEquals("910GFRNDNLT", vehicles.vehicleCalls(id, "thameslink").first().stopId)
        assertEquals(listOf("STP>BTN", "ZFD>BTN"), boards.asked)
    }

    @Test
    fun `one held at its station past its estimate is still there, its call there kept (Codex, #883)`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        // Three minutes past its estimate, still listed at St Pancras: still due there, never taken to have left.
        // Here the boards it's due at further on, ten trains long, leave it out: St Pancras's is asked in turn.
        now = at(3)
        boards.at = mapOf("STP" to listOf(run("STP", late = 4)))
        val calls = vehicles.vehicleCalls(id, "thameslink")
        assertEquals("910GSTPXBOX", calls.first().stopId)
        assertEquals(at(4), calls.first().expected)
        assertEquals(listOf("STP>BTN", "CTK>BTN", "BFR>BTN", "STP>BTN"), boards.asked)
    }

    @Test
    fun `one gone from its station and not yet listed further on is not known, never guessed`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        now = at(3)
        boards.at = emptyMap()
        try {
            vehicles.vehicleCalls(id, "thameslink")
            fail("calls without its station, guessed from times gone by")
        } catch (e: TflException.Unreachable) {
            // A failed update: the trip keeps it as it was, neither boarded nor gone.
        }
        // A few boards a refresh: the one it's due at next, the one past it, and one before them in turn.
        assertEquals(listOf("STP>BTN", "CTK>BTN", "BFR>BTN", "STP>BTN"), boards.asked)
    }

    @Test
    fun `a check whether it's the rider's train asks one board, never the search a followed one gets (Codex, #883)`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        // Never seen: the board where it was picked, as for following it.
        assertEquals("910GSTPXBOX", vehicles.candidateCalls(id, "thameslink").first().stopId)
        assertEquals(listOf("STP>BTN"), boards.asked)
        // Gone from St Pancras and on no board yet: only the board it's due at next is asked, and it's not known
        // this time, never the one past it or one before it in turn (maintainer, 2026-10-16).
        now = at(3)
        boards.at = emptyMap()
        try {
            vehicles.candidateCalls(id, "thameslink")
            fail("checked on a board that didn't list it")
        } catch (e: TflException.Unreachable) {
            // Not known this time.
        }
        assertEquals(listOf("STP>BTN", "CTK>BTN"), boards.asked)
        // Followed, it gets the search as before: the one past it and one before in turn, City Thameslink's read
        // shared within the reuse.
        boards.asked.clear()
        try {
            vehicles.vehicleCalls(id, "thameslink")
            fail("calls without its station")
        } catch (e: TflException.Unreachable) {
            // A failed update.
        }
        assertEquals(listOf("BFR>BTN", "STP>BTN"), boards.asked)
        // Listed on the board it's due at next, read afresh, that board answers the check.
        now = at(4)
        boards.asked.clear()
        boards.at = mapOf("CTK" to listOf(run("CTK")))
        assertTrue(vehicles.candidateCalls(id, "thameslink").isNotEmpty())
        assertEquals(listOf("CTK>BTN"), boards.asked)
        // A TfL train's check is TfL's.
        vehicles.candidateCalls("162", "jubilee")
        assertEquals("jubilee/162", tfl.asked.last())
    }

    @Test
    fun `one held at a station on past its estimate is found there, not further on (Codex, #883)`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        // Gone from St Pancras, held at Farringdon three minutes past its estimate there: Blackfriars', the board
        // it's due at next by its estimates, lists it not yet gone from Farringdon, so it's due to leave there next.
        now = at(6)
        boards.at = mapOf("BFR" to listOf(held("BFR", "ZFD", 4)))
        val calls = vehicles.vehicleCalls(id, "thameslink")
        assertEquals("910GFRNDNLT", calls.first().stopId)
        assertEquals(at(7), calls.first().expected)
        assertEquals(listOf("STP>BTN", "BFR>BTN"), boards.asked)
    }

    @Test
    fun `one that passed several stations between refreshes is caught up with at the board it's due at next`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        now = at(8)
        boards.at = mapOf("LBG" to listOf(run("LBG")))
        assertEquals("910GLNDNBDC", vehicles.vehicleCalls(id, "thameslink").first().stopId)
        assertEquals(listOf("STP>BTN", "LBG>BTN"), boards.asked)
    }

    @Test
    fun `the clock set back after a board was read never sends it back to stations it has passed (Codex, #883)`() = runTest {
        var now = at(-5)
        var setBack = Duration.ZERO
        SteadyClock.source = object : SteadyClock.Source {
            override val frame: SteadyClock.Frame? = null
            override fun offset(): Duration = setBack
        }
        try {
            val boards = Boards(mapOf("STP" to listOf(run("STP"))))
            val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
            vehicles.vehicleCalls(id, "thameslink")
            // Eight minutes on, past Blackfriars, with the clock set back an hour: London Bridge is still the board
            // it's due at next, never St Pancras and Farringdon, which no longer list it.
            setBack = Duration.ofHours(1)
            now = at(8).minus(setBack)
            boards.at = mapOf("LBG" to listOf(run("LBG")))
            assertEquals("910GLNDNBDC", vehicles.vehicleCalls(id, "thameslink").first().stopId)
            assertEquals(listOf("STP>BTN", "LBG>BTN"), boards.asked)
        } finally {
            SteadyClock.source = null
        }
    }

    @Test
    fun `a train never seen and gone from its station is one no longer known`() = runTest {
        // Its station's board lists the one after it, and not it.
        val vehicles = RailVehicles(tfl, Boards(mapOf("STP" to listOf(run("STP", starts = 15))))::runs, idsOf, clock = { at(0) })
        try {
            vehicles.vehicleCalls(id, "thameslink")
            fail("found a train no board lists")
        } catch (e: TflException.NotFound) {
            // As TfL says of a train it no longer knows: the trip picks another, or keeps the one it has.
        }
    }

    @Test
    fun `a train never seen, on no board that reaches past it, is unknown, not gone (Codex, #883)`() = runTest {
        // Three hours out: the board from Darwin's furthest, 105 minutes on, lists ten to Brighton, all before it.
        val pickedAt = RailFollow.idOf("STP", at(180), "BTN", "thameslink")
        val boards = Boards(emptyMap())
        boards.later = mapOf(105 to mapOf("STP" to (0 until 10).map { run("STP", starts = 110L + 5 * it) }))
        var now = at(0)
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        try {
            vehicles.vehicleCalls(pickedAt, "thameslink")
            fail("a train the board couldn't list taken for gone, or found")
        } catch (e: TflException.Unreachable) {
            // Not known: the trip keeps it and says it couldn't update.
        }
        assertEquals(listOf("STP>BTN+105"), boards.asked)
        // Past its time, on a board listing only one due before it, still held perhaps: unknown, not gone.
        val due = RailVehicles(tfl, Boards(mapOf("STP" to listOf(run("STP", starts = -5))))::runs, idsOf, clock = { at(10) })
        try {
            due.vehicleCalls(id, "thameslink")
            fail("a train taken for gone by the clock")
        } catch (e: TflException.Unreachable) {
            // Its time gone by is no word that it has left.
        }
        // A board listing one due after it reached past it: not there, it's gone.
        boards.later = mapOf(105 to mapOf("STP" to listOf(run("STP", starts = 185))))
        now = at(1)
        try {
            vehicles.vehicleCalls(pickedAt, "thameslink")
            fail("found a train no board lists")
        } catch (e: TflException.NotFound) {
            // As TfL says of a train it no longer knows.
        }
    }

    @Test
    fun `with no key it's a failed update, never a train no longer known (Codex, #883)`() = runTest {
        // A key still being read back after a restart is none yet: the train picked before is kept.
        var key: String? = null
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, { crs, towards, offset -> key?.let { boards.runs(crs, towards, offset) } }, idsOf, clock = { at(-5) })
        try {
            vehicles.vehicleCalls(id, "thameslink")
            fail("followed with no key")
        } catch (e: TflException.NotFound) {
            fail("given up for no key")
        } catch (e: TflException.Unreachable) {
            // A failed update.
        }
        try {
            vehicles.candidateCalls(id, "thameslink")
            fail("checked with no key")
        } catch (e: TflException.NotFound) {
            fail("given up for no key")
        } catch (e: TflException.Unreachable) {
            // A failed update.
        }
        // The key read back, the same train is found.
        key = "KEY"
        assertEquals("910GSTPXBOX", vehicles.vehicleCalls(id, "thameslink").first().stopId)
    }

    @Test
    fun `a board's failure is the answer's`() = runTest {
        val boards = Boards(mapOf("STP" to listOf(run("STP")))).apply { failing = TflException.Offline(null) }
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { at(0) })
        try {
            vehicles.vehicleCalls(id, "thameslink")
            fail("a failed board answered")
        } catch (e: TflException.Offline) {
            // Reported as the board's own failure, never as a train no longer known.
        }
    }

    @Test
    fun `one board failing never keeps it from the next board asked, which lists it (Codex, #883)`() = runTest {
        var now = at(-5)
        val boards = Boards(mapOf("STP" to listOf(run("STP"))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        // Due at Farringdon next, whose board fails; City Thameslink's, the one past it, lists it.
        now = at(1)
        boards.at = mapOf("CTK" to listOf(run("CTK", late = 1)))
        boards.failingAt = mapOf("ZFD" to TflException.Unreachable("board unavailable", null))
        assertEquals("910GCTMSLNK", vehicles.vehicleCalls(id, "thameslink").first().stopId)
        assertEquals(listOf("STP>BTN", "ZFD>BTN", "CTK>BTN"), boards.asked)
        // Listed on none, with a board that failed: the update's failure is that board's, never "not listed".
        now = at(2)
        boards.at = emptyMap()
        boards.failingAt = mapOf("CTK" to TflException.Offline(null))
        try {
            vehicles.vehicleCalls(id, "thameslink")
            fail("calls claimed for a train no board lists")
        } catch (e: TflException.Offline) {
            // Reported as the board's own failure.
        }
    }

    @Test
    fun `one board read answers every train it lists for a while, then is read again`() = runTest {
        var now = at(-10)
        val boards = Boards(mapOf("STP" to listOf(run("STP"), run("STP", starts = 10))))
        val vehicles = RailVehicles(tfl, boards::runs, idsOf, clock = { now })
        vehicles.vehicleCalls(id, "thameslink")
        vehicles.vehicleCalls(RailFollow.idOf("STP", at(10), "BTN", "thameslink"), "thameslink")
        assertEquals(1, boards.asked.size)
        now = at(-10).plus(RailVehicles.REUSE_FOR)
        vehicles.vehicleCalls(id, "thameslink")
        assertEquals(2, boards.asked.size)
        assertTrue(boards.asked.all { it == "STP>BTN" })
    }
}
