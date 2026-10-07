package app.stopdash.ui

import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.StepFree
import app.stopdash.domain.Departure
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.NearbyStopsCache
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.TripModes
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and lines only: never a real position (SPEC *Privacy*). */
class PlaceDirectViewModelTest {
    private val now = Instant.parse("2026-10-07T08:00:00Z")

    // "rail" runs Top → Mid → Bottom; the place is a short walk from Bottom.
    private val rail = LineSequence(
        routes = listOf(LineRoute("Top ↔ Bottom", listOf("TOP", "MID", "BOT")), LineRoute("Bottom ↔ Top", listOf("BOT", "MID", "TOP"))),
        stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOT" to "Bottom"),
    )
    private val routes = RouteStopsRepository(
        object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence = rail
        },
        clock = { now },
    )

    private fun departure(destination: String, inSeconds: Long) =
        Departure("rail", "Rail", "", destination, null, now.plusSeconds(inSeconds), "tube")

    // By default every line asked about runs a good service and no stop has a notice.
    private fun client(
        statuses: suspend (Collection<String>) -> List<LineStatus> = { ids -> ids.map { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") } },
        notices: suspend (String) -> List<StopDisruption> = { emptyList() },
        arrivals: suspend (String) -> List<Departure>,
    ) = object : TflClient {
        override suspend fun arrivals(stopId: String): List<Departure> = arrivals(stopId)
        override suspend fun lineStatuses(lineIds: Collection<String>) = statuses(lineIds)
        override suspend fun stopDisruptions(stopId: String) = notices(stopId)
    }

    private fun model(
        client: TflClient,
        ends: suspend (Int) -> List<DirectTrips.End> = { listOf(DirectTrips.End("BOT", "Bottom")) },
    ) = PlaceDirectViewModel(
        ends = ends,
        client = client,
        routes = routes,
        arrivals = ArrivalsCache(),
        clock = { now },
        io = Dispatchers.Unconfined,
        compute = Dispatchers.Unconfined,
    ).apply { setOrigin(listOf(StopRef("TOP", "Top"))) }

    // Sets the origin without the look a change starts: each test runs its looks itself.
    private fun PlaceDirectViewModel.setOrigin(stops: List<StopRef>) = setInputsQuietly(PlaceDirectViewModel.Inputs(stops))

    @Test
    fun `a line from the rider's stop that reaches the place is a row with its countdowns`() = runBlocking {
        val model = model(client { listOf(departure("Bottom", 300), departure("Top", 60), departure("Bottom", 60)) })
        model.refresh()
        val ready = model.state.value as PlaceDirectViewModel.State.Ready
        val row = ready.rows.single()
        assertEquals("TOP", row.row.fromId)
        assertEquals("1 · 5 min", row.times)
    }

    @Test
    fun `a row carries the stops near the place its trains reach, for a tap to open its ride`() = runBlocking {
        val model = model(client { listOf(departure("Bottom", 60)) }, ends = { listOf(DirectTrips.End("BOT", "Bottom", "HUBB")) })
        model.refresh()
        val row = (model.state.value as PlaceDirectViewModel.State.Ready).rows.single()
        assertEquals(setOf("BOT", "HUBB"), row.endIds)
        assertEquals("BOT,HUBB", row.endKey)
    }

    @Test
    fun `the place's stops are looked up within the rider's walk, once for each walk`() = runBlocking {
        val asked = mutableListOf<Int>()
        val model = model(client { listOf(departure("Bottom", 60)) }, ends = { walk -> asked += walk; listOf(DirectTrips.End("BOT", "Bottom")) })
        val origin = listOf(StopRef("TOP", "Top"))
        model.setInputsQuietly(PlaceDirectViewModel.Inputs(origin, walkMeters = 1_000))
        model.refresh()
        model.refresh()
        // Held while the walk stands; a longer walk asks again, for the new walk.
        model.setInputsQuietly(PlaceDirectViewModel.Inputs(origin, walkMeters = 1_500))
        model.refresh()
        // Back to the first walk: its answer is reused, not asked for again.
        model.setInputsQuietly(PlaceDirectViewModel.Inputs(origin, walkMeters = 1_000))
        model.refresh()
        assertEquals(listOf(1_000, 1_500), asked)
    }

    @Test
    fun `a failed tap is said only on the row as it reached when tapped`() = runBlocking {
        val model = model(client { listOf(departure("Bottom", 60)) }, ends = { listOf(DirectTrips.End("BOT", "Bottom", "HUBB")) })
        model.refresh()
        val shown = (model.state.value as PlaceDirectViewModel.State.Ready).rows.single()
        val failed = TripViewModel.DirectOpening(shown.row.lineId, shown.row.fromId, failed = true, endKey = shown.endKey)
        assertTrue(directOpeningOf(failed, shown))
        // Reaching another stop since: it may have a route now, so it doesn't say it failed; still planning, it does.
        val reachesMore = shown.copy(endKey = "BOT,HUBB,OTHER")
        assertFalse(directOpeningOf(failed, reachesMore))
        assertTrue(directOpeningOf(failed.copy(failed = false), reachesMore))
    }

    @Test
    fun `nothing going there is None, not a failure`() = runBlocking {
        val model = model(client { listOf(departure("Top", 60)) })
        model.refresh()
        val ready = model.state.value as PlaceDirectViewModel.State.Ready
        assertTrue(ready.rows.isEmpty())
        assertEquals(false, ready.uncertain)
    }

    @Test
    fun `the place's stops not found says it couldn't check`() = runBlocking {
        val model = model(client { emptyList() }, ends = { throw TflException.Offline(null) })
        model.refresh()
        assertEquals(PlaceDirectViewModel.State.Failed, model.state.value)
    }

    @Test
    fun `no arrivals from any stop says it couldn't check, never None`() = runBlocking {
        val model = model(client { throw TflException.Offline(null) })
        model.refresh()
        assertEquals(PlaceDirectViewModel.State.Failed, model.state.value)
    }

    @Test
    fun `a refresh that fails withholds the trains it last had`() = runBlocking {
        var clock = now
        var failing = false
        val model = PlaceDirectViewModel(
            ends = { listOf(DirectTrips.End("BOT", "Bottom")) },
            client = client { if (failing) throw TflException.Offline(null) else listOf(departure("Bottom", 600)) },
            routes = routes,
            arrivals = ArrivalsCache(),
            clock = { clock },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        ).apply { setOrigin(listOf(StopRef("TOP", "Top"), StopRef("MID", "Mid"))) }
        model.refresh()
        assertEquals(1, (model.state.value as PlaceDirectViewModel.State.Ready).rows.size)
        // Past the shared cache's age, so the stops are asked again, and the ask fails.
        clock = now.plus(ArrivalsCache.TTL).plusSeconds(1)
        failing = true
        model.refresh()
        assertEquals(PlaceDirectViewModel.State.Failed, model.state.value)
    }

    @Test
    fun `a train no Retry can check leaves none found unchecked but not failed, a route that failed to load is a failure`() = runBlocking {
        for (failure in listOf<TflException>(TflException.NotFound(null), TflException.Offline(null))) {
            val failing = RouteStopsRepository(
                object : RouteSequenceSource {
                    override suspend fun routeSequence(lineId: String, direction: String): LineSequence = throw failure
                },
                clock = { now },
            )
            val model = PlaceDirectViewModel(
                ends = { listOf(DirectTrips.End("BOT", "Bottom")) },
                client = client { listOf(departure("Bottom", 60)) },
                routes = failing,
                arrivals = ArrivalsCache(),
                clock = { now },
                io = Dispatchers.Unconfined,
                compute = Dispatchers.Unconfined,
            ).apply { setOrigin(listOf(StopRef("TOP", "Top"))) }
            model.refresh()
            val ready = model.state.value as PlaceDirectViewModel.State.Ready
            val name = failure::class.simpleName
            assertTrue(name, ready.rows.isEmpty())
            assertTrue(name, ready.uncertain)
            // TfL having no route for the line: no Retry will tell; one that failed to load may.
            assertEquals(name, failure !is TflException.NotFound, ready.retryable)
        }
    }

    @Test
    fun `a train whose destination its line's route can't place leaves none found unchecked but not failed`() = runBlocking {
        val model = model(client { listOf(departure("Nowhere on the route", 60)) })
        model.refresh()
        val ready = model.state.value as PlaceDirectViewModel.State.Ready
        assertTrue(ready.rows.isEmpty())
        assertTrue(ready.uncertain)
        assertFalse(ready.retryable)
    }

    @Test
    fun `a line's status is asked for while its route loads, not after`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val asked = mutableListOf<Collection<String>>()
        val slow = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    gate.await()
                    return rail
                }
            },
            clock = { now },
        )
        val model = PlaceDirectViewModel(
            ends = { listOf(DirectTrips.End("BOT", "Bottom")) },
            client = client(statuses = { ids -> asked += ids; ids.map { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") } }) {
                listOf(departure("Bottom", 60))
            },
            routes = slow,
            arrivals = ArrivalsCache(),
            clock = { now },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        ).apply { setOrigin(listOf(StopRef("TOP", "Top"))) }
        val look = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Unconfined) { model.refresh() }
        // The route is still loading, and the status is already asked for.
        assertEquals(listOf<Collection<String>>(listOf("rail")), asked.map { it.toList() })
        gate.complete(Unit)
        look.join()
        assertEquals(1, (model.state.value as PlaceDirectViewModel.State.Ready).rows.size)
        // And not asked again for the row once the route says it goes there.
        assertEquals(1, asked.size)
    }

    @Test
    fun `a stop that couldn't be had is said under the rows`() = runBlocking {
        val model = model(client { stopId -> if (stopId == "MID") throw TflException.Offline(null) else listOf(departure("Bottom", 60)) })
            .apply { setOrigin(listOf(StopRef("TOP", "Top"), StopRef("MID", "Mid"))) }
        model.refresh()
        val ready = model.state.value as PlaceDirectViewModel.State.Ready
        assertEquals(1, ready.rows.size)
        assertTrue(ready.uncertain)
    }

    @Test
    fun `avoiding a line takes it out at once, not at the next tick`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val model = model(client { listOf(departure("Bottom", 60)) })
            model.refresh()
            assertEquals(1, (model.state.value as PlaceDirectViewModel.State.Ready).rows.size)
            model.setInputs(PlaceDirectViewModel.Inputs(listOf(StopRef("TOP", "Top")), avoided = setOf(AvoidedLines.key("rail", "Rail"))))
            assertTrue((model.state.value as PlaceDirectViewModel.State.Ready).rows.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `turning a mode off for the trip takes its lines out at once`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val model = model(client { listOf(departure("Bottom", 60)) })
            model.refresh()
            assertEquals(1, (model.state.value as PlaceDirectViewModel.State.Ready).rows.size)
            val tube = ModeGroups.of("tube")
            model.setInputs(PlaceDirectViewModel.Inputs(listOf(StopRef("TOP", "Top")), tripModes = TripModes.DEFAULT.with(tube, ride = false)))
            assertTrue((model.state.value as PlaceDirectViewModel.State.Ready).rows.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a new origin set looks again at once, and so does a pull`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            // The line's route held already, so the look a change starts finishes before it returns.
            routes.load("rail", "")
            var trains = listOf(departure("Bottom", 60))
            val model = model(client { stopId -> if (stopId == "MID") trains else emptyList() })
            model.refresh()
            assertTrue((model.state.value as PlaceDirectViewModel.State.Ready).rows.isEmpty())
            // The rider moved on: Mid is now among the trip's stops.
            model.setInputs(PlaceDirectViewModel.Inputs(listOf(StopRef("MID", "Mid"))))
            assertEquals("MID", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().row.fromId)
            trains = listOf(departure("Bottom", 60), departure("Bottom", 240))
            model.pullRefresh()
            assertEquals("1 · 4 min", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().times)
            assertEquals(false, model.pulling.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a stop at the edge of the trip's reach stays while the fix wavers, its row with it`() = runBlocking {
        val model = model(client { stopId -> if (stopId == "MID") listOf(departure("Bottom", 60)) else emptyList() })
        val top = StopRef("TOP", "Top")
        val mid = StopRef("MID", "Mid")
        val within = PlaceDirectViewModel.Inputs(listOf(top, mid), mapOf("TOP" to 100.0, "MID" to 315.0))
        model.setInputsQuietly(within)
        model.refresh()
        assertEquals("MID", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().row.fromId)
        // The next fix puts Mid a few meters past the reach: it stays, and so does its row.
        model.setInputsQuietly(PlaceDirectViewModel.Inputs(listOf(top), mapOf("TOP" to 95.0, "MID" to 330.0)))
        model.refresh()
        assertEquals("MID", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().row.fromId)
        // Walked well away from it: it goes.
        model.setInputsQuietly(PlaceDirectViewModel.Inputs(listOf(top), mapOf("TOP" to 60.0, "MID" to 420.0)))
        model.refresh()
        assertTrue((model.state.value as PlaceDirectViewModel.State.Ready).rows.isEmpty())
    }

    @Test
    fun `a stop only a look a change overtook took isn't kept for the next`() = runBlocking {
        val top = StopRef("TOP", "Top")
        val mid = StopRef("MID", "Mid")
        lateinit var model: PlaceDirectViewModel
        var overtake = false
        model = model(
            client { stopId ->
                // A later fix leaves Mid out, just past the reach, while this look's fetch is out.
                if (overtake) model.setInputsQuietly(PlaceDirectViewModel.Inputs(listOf(top), mapOf("TOP" to 100.0, "MID" to 330.0)))
                if (stopId == "MID") listOf(departure("Bottom", 60)) else emptyList()
            },
        )
        model.setInputsQuietly(PlaceDirectViewModel.Inputs(listOf(top), mapOf("TOP" to 100.0, "MID" to 400.0)))
        model.refresh()
        // An intermediate fix takes Mid in, but a later one overtakes its look.
        overtake = true
        model.setInputsQuietly(PlaceDirectViewModel.Inputs(listOf(top, mid), mapOf("TOP" to 100.0, "MID" to 310.0)))
        model.refresh(pulled = true)
        overtake = false
        model.refresh(pulled = true)
        assertTrue((model.state.value as PlaceDirectViewModel.State.Ready).rows.isEmpty())
    }

    @Test
    fun `a stop past the reach isn't kept over a changed choice, nor without a distance`() {
        val top = StopRef("TOP", "Top")
        val mid = StopRef("MID", "Mid")
        val before = PlaceDirectViewModel.Inputs(listOf(top, mid), mapOf("MID" to 315.0))
        val held = before to listOf(top, mid)
        val wavered = PlaceDirectViewModel.Inputs(listOf(top), mapOf("MID" to 330.0), hidden = before.hidden, avoided = before.avoided, tripModes = before.tripModes)
        assertEquals(listOf(top, mid), PlaceDirectViewModel.steadyOrigin(wavered, held))
        val avoiding = PlaceDirectViewModel.Inputs(listOf(top), mapOf("MID" to 330.0), avoided = setOf("rail"))
        assertEquals(listOf(top), PlaceDirectViewModel.steadyOrigin(avoiding, held))
        val unknown = PlaceDirectViewModel.Inputs(listOf(top), emptyMap(), hidden = before.hidden, avoided = before.avoided, tripModes = before.tripModes)
        assertEquals(listOf(top), PlaceDirectViewModel.steadyOrigin(unknown, held))
    }

    @Test
    fun `rows on show keep their places as their trains come and go, and a line found later goes under them`() = runBlocking {
        fun on(line: String, inSeconds: Long) = Departure(line, line, "", "Bottom", null, now.plusSeconds(inSeconds), "bus")
        var trains = listOf(on("a", 60), on("b", 120))
        val model = model(client { trains })
        model.refresh()
        fun shown() = (model.state.value as PlaceDirectViewModel.State.Ready).rows.map { it.row.lineId }
        assertEquals(listOf("a", "b"), shown())
        // b's bus now comes first, and a line not seen before has the soonest of all: nothing moves.
        trains = listOf(on("c", 30), on("b", 45), on("a", 90))
        // Asked afresh, past the shared arrivals.
        model.refresh(pulled = true)
        assertEquals(listOf("a", "b", "c"), shown())
        // a gone: the rest close up in the order they were in.
        trains = listOf(on("c", 30), on("b", 45))
        model.refresh(pulled = true)
        assertEquals(listOf("b", "c"), shown())
        // Stood down to Checking (back after a while, a Retry): the order still holds.
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            model.retry()
        } finally {
            Dispatchers.resetMain()
        }
        trains = listOf(on("c", 30), on("b", 45), on("a", 50))
        model.refresh(pulled = true)
        assertEquals(listOf("b", "c", "a"), shown())
    }

    @Test
    fun `a look a change overtook leaves the order as shown`() = runBlocking {
        fun on(line: String, inSeconds: Long) = Departure(line, line, "", "Bottom", null, now.plusSeconds(inSeconds), "bus")
        lateinit var model: PlaceDirectViewModel
        var overtake = false
        var trains = listOf(on("a", 60), on("b", 120))
        model = model(
            client {
                // The rider's fix moves the trip's stops while this look's fetch is out.
                if (overtake) model.setInputsQuietly(PlaceDirectViewModel.Inputs(listOf(StopRef("TOP", "Top"))))
                trains
            },
        )
        model.refresh()
        fun shown() = (model.state.value as PlaceDirectViewModel.State.Ready).rows.map { it.row.lineId }
        assertEquals(listOf("a", "b"), shown())
        overtake = true
        trains = listOf(on("b", 30), on("c", 60))
        model.refresh(pulled = true)
        assertEquals(listOf("a", "b"), shown())
        overtake = false
        trains = listOf(on("d", 20), on("b", 30), on("a", 40))
        model.refresh(pulled = true)
        assertEquals(listOf("a", "b", "d"), shown())
    }

    @Test
    fun `what the section says goes to the debug log when it changes, not every look`() = runBlocking {
        val logged = mutableListOf<String>()
        var trains = listOf(departure("Bottom", 60))
        val model = PlaceDirectViewModel(
            ends = { listOf(DirectTrips.End("BOT", "Bottom")) },
            client = client { trains },
            routes = routes,
            arrivals = ArrivalsCache(),
            clock = { now },
            warn = { logged += it },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        ).apply { setOrigin(listOf(StopRef("TOP", "Top"))) }
        model.refresh()
        model.refresh()
        // Another line's train now comes first: the rows on show don't move, so nothing new is said.
        trains = listOf(Departure("other", "Other", "", "Bottom", null, now.plusSeconds(30), "tube"), departure("Bottom", 60))
        model.refresh(pulled = true)
        trains = listOf(departure("Bottom", 60), Departure("other", "Other", "", "Bottom", null, now.plusSeconds(90), "tube"))
        model.refresh(pulled = true)
        trains = emptyList()
        model.refresh(pulled = true)
        assertEquals(listOf("direct: rows rail@TOP", "direct: rows rail@TOP,other@TOP", "direct: rows none"), logged.filter { it.startsWith("direct: ") })
    }

    @Test
    fun `nothing is looked up until the rider's choices are read`() = runBlocking {
        var asked = 0
        val model = model(client { asked++; listOf(departure("Bottom", 60)) })
        model.setInputsQuietly(PlaceDirectViewModel.Inputs(listOf(StopRef("TOP", "Top")), loaded = false))
        model.refresh()
        assertEquals(PlaceDirectViewModel.State.Checking, model.state.value)
        assertEquals(0, asked)
    }

    @Test
    fun `a look whose inputs changed while it waited publishes nothing`() = runBlocking {
        lateinit var model: PlaceDirectViewModel
        model = model(
            client {
                // The rider avoids the line while this look's fetch is out.
                model.setInputsQuietly(PlaceDirectViewModel.Inputs(listOf(StopRef("TOP", "Top")), avoided = setOf("x")))
                listOf(departure("Bottom", 60))
            },
        )
        model.refresh()
        assertEquals(PlaceDirectViewModel.State.Checking, model.state.value)
    }

    @Test
    fun `countdowns are worked out after the lookups, not before`() = runBlocking {
        var clock = now
        val model = PlaceDirectViewModel(
            ends = { listOf(DirectTrips.End("BOT", "Bottom")) },
            // The fetch takes two minutes.
            client = client { clock = now.plusSeconds(120); listOf(departure("Bottom", 60), departure("Bottom", 300)) },
            routes = routes,
            arrivals = ArrivalsCache(),
            clock = { clock },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        ).apply { setOrigin(listOf(StopRef("TOP", "Top"))) }
        model.refresh()
        // The train due at one minute has gone; the one at five is three minutes off.
        assertEquals("3 min", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().times)
    }

    @Test
    fun `a changed choice clears the rows found under the old one before its own look lands`(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            var holding = false
            val model = model(client { if (holding) gate.await(); listOf(departure("Bottom", 60)) })
            model.refresh()
            assertEquals(1, (model.state.value as PlaceDirectViewModel.State.Ready).rows.size)
            // The new look's fetch is held, so only what setInputs itself does shows.
            holding = true
            // A stop not fetched yet, so the new look waits on its fetch.
            model.setInputs(PlaceDirectViewModel.Inputs(listOf(StopRef("TOP", "Top"), StopRef("MID", "Mid")), stepFree = StepFree.STATION))
            assertEquals(PlaceDirectViewModel.State.Checking, model.state.value)
            gate.complete(Unit)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a line not running a good service says so on its row`() = runBlocking {
        val model = model(client(statuses = { listOf(LineStatus("rail", 6, "Severe Delays")) }) { listOf(departure("Bottom", 60)) })
        model.refresh()
        val ready = model.state.value as PlaceDirectViewModel.State.Ready
        assertEquals("Severe Delays", ready.rows.single().disruption)
        assertEquals(false, ready.uncertain)
    }

    @Test
    fun `an alert TfL gives for the other direction isn't this row's`() = runBlocking {
        val split = LineStatus(
            "rail", 6, "Severe Delays",
            byDirection = mapOf("outbound" to LineStatus("rail", LineStatus.GOOD_SERVICE, "Good Service")),
        )
        val model = model(client(statuses = { listOf(split) }) { listOf(departure("Bottom", 60).copy(direction = "outbound")) })
        model.refresh()
        assertEquals(null, (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().disruption)
    }

    @Test
    fun `a status awaiting its directions is asked again on the next look`() = runBlocking {
        var asked = 0
        val model = model(
            client(statuses = { asked++; listOf(LineStatus("rail", 6, "Severe Delays", awaitingDirections = asked == 1)) }) {
                listOf(departure("Bottom", 60))
            },
        )
        model.refresh()
        model.refresh()
        model.refresh()
        assertEquals(2, asked)
    }

    @Test
    fun `a line TfL left out of its answer is not vouched for`() = runBlocking {
        val model = model(client(statuses = { emptyList() }) { listOf(departure("Bottom", 60)) })
        model.refresh()
        assertTrue((model.state.value as PlaceDirectViewModel.State.Ready).uncertain)
    }

    @Test
    fun `a row's trains in a disrupted direction are said, whichever comes first`() = runBlocking {
        val split = LineStatus(
            "rail", 6, "Severe Delays",
            byDirection = mapOf(
                "outbound" to LineStatus("rail", LineStatus.GOOD_SERVICE, "Good Service"),
                "inbound" to LineStatus("rail", 6, "Severe Delays"),
            ),
        )
        val model = model(
            client(statuses = { listOf(split) }) {
                listOf(departure("Bottom", 60).copy(direction = "outbound"), departure("Bottom", 120).copy(direction = "inbound"))
            },
        )
        model.refresh()
        assertEquals("Severe Delays", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().disruption)
    }

    @Test
    fun `the worst of a row's disrupted directions is said`() = runBlocking {
        val split = LineStatus(
            "rail", 9, "Minor Delays",
            byDirection = mapOf(
                "outbound" to LineStatus("rail", 9, "Minor Delays"),
                "inbound" to LineStatus("rail", 16, "Part Suspended").copy(severity = 4),
            ),
        )
        val model = model(
            client(statuses = { listOf(split) }) {
                listOf(departure("Bottom", 60).copy(direction = "outbound"), departure("Bottom", 120).copy(direction = "inbound"))
            },
        )
        model.refresh()
        assertEquals("Part Suspended", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().disruption)
    }

    @Test
    fun `a row with one stop near the place closed and the other unchecked is not vouched for`() = runBlocking {
        val model = model(
            client(
                notices = { id ->
                    when (id) {
                        "MID" -> listOf(StopDisruption("Station closed"))
                        "BOT" -> throw TflException.Offline(null)
                        else -> emptyList()
                    }
                },
            ) { listOf(departure("Bottom", 60)) },
            ends = { listOf(DirectTrips.End("MID", "Mid"), DirectTrips.End("BOT", "Bottom")) },
        )
        model.refresh()
        val ready = model.state.value as PlaceDirectViewModel.State.Ready
        assertEquals(1, ready.rows.size)
        assertTrue(ready.uncertain)
    }

    @Test
    fun `planned work that has started today is said, though fetched before it began`() = runBlocking {
        val today = now.atZone(app.stopdash.domain.AlertStart.ZONE).toLocalDate()
        val planned = LineStatus(
            "rail", LineStatus.GOOD_SERVICE, "Good Service",
            planned = listOf(app.stopdash.domain.PlannedAlert("Part Closure", "No trains Mid to Bottom", today)),
        )
        val model = model(client(statuses = { listOf(planned) }) { listOf(departure("Bottom", 60)) })
        model.refresh()
        assertEquals("Part Closure", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().disruption)
    }

    @Test
    fun `a train whose only stop near the place is closed is said, though a later one reaches an open stop`() = runBlocking {
        // The line forks after Mid: one train to Bottom A (closed), one to Bottom B (open).
        val fork = LineSequence(
            routes = listOf(LineRoute("Top ↔ Bottom A", listOf("TOP", "MID", "BOTA")), LineRoute("Top ↔ Bottom B", listOf("TOP", "MID", "BOTB"))),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "BOTB" to "Bottom B"),
        )
        val model = PlaceDirectViewModel(
            ends = { listOf(DirectTrips.End("BOTA", "Bottom A"), DirectTrips.End("BOTB", "Bottom B")) },
            client = client(notices = { id -> if (id == "BOTA") listOf(StopDisruption("Station closed")) else emptyList() }) {
                listOf(departure("Bottom A", 60), departure("Bottom B", 120))
            },
            routes = RouteStopsRepository(object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = fork
            }, clock = { now }),
            arrivals = ArrivalsCache(),
            clock = { now },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        ).apply { setOrigin(listOf(StopRef("TOP", "Top"))) }
        model.refresh()
        assertEquals("Bottom A: Station closed", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().notice)
    }

    @Test
    fun `a train whose route can't be told is logged once, not every look`() = runBlocking {
        // The line forks after Mid, only Bottom A near the place; a train with no destination may take either.
        val fork = LineSequence(
            routes = listOf(LineRoute("Top ↔ Bottom A", listOf("TOP", "MID", "BOTA")), LineRoute("Top ↔ Bottom B", listOf("TOP", "MID", "BOTB"))),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "BOTB" to "Bottom B"),
        )
        val warnings = mutableListOf<String>()
        val model = PlaceDirectViewModel(
            ends = { listOf(DirectTrips.End("BOTA", "Bottom A")) },
            client = client { listOf(departure("", 60)) },
            routes = RouteStopsRepository(object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = fork
            }, clock = { now }, warn = { warnings += it }, compute = Dispatchers.Unconfined),
            arrivals = ArrivalsCache(),
            clock = { now },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        ).apply { setOrigin(listOf(StopRef("TOP", "Top"))) }
        model.refresh()
        model.refresh()
        assertEquals(1, warnings.count { "rail" in it && "TOP" in it })
    }

    @Test
    fun `a train that may take either branch, one of them closed, isn't vouched for`() = runBlocking {
        // The line forks after Mid; a train with no destination may take either, to Bottom A (closed) or B.
        val fork = LineSequence(
            routes = listOf(LineRoute("Top ↔ Bottom A", listOf("TOP", "MID", "BOTA")), LineRoute("Top ↔ Bottom B", listOf("TOP", "MID", "BOTB"))),
            stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "BOTB" to "Bottom B"),
        )
        val model = PlaceDirectViewModel(
            ends = { listOf(DirectTrips.End("BOTA", "Bottom A"), DirectTrips.End("BOTB", "Bottom B")) },
            client = client(notices = { id -> if (id == "BOTA") listOf(StopDisruption("Station closed")) else emptyList() }) {
                listOf(departure("", 60))
            },
            routes = RouteStopsRepository(object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = fork
            }, clock = { now }),
            arrivals = ArrivalsCache(),
            clock = { now },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        ).apply { setOrigin(listOf(StopRef("TOP", "Top"))) }
        model.refresh()
        val ready = model.state.value as PlaceDirectViewModel.State.Ready
        assertEquals(1, ready.rows.size)
        assertTrue(ready.uncertain)
    }

    @Test
    fun `a closure's text is cleaned as every surface shows it`() = runBlocking {
        val notice = StopDisruption("Top Underground Station: Closed\\n    until further notice")
        val model = model(client(notices = { id -> if (id == "TOP") listOf(notice) else emptyList() }) { listOf(departure("Bottom", 60)) })
        model.refresh()
        assertEquals("Top: Closed", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().notice)
    }

    @Test
    fun `a closure at the stop it boards at is said on the row`() = runBlocking {
        val model = model(client(notices = { id -> if (id == "TOP") listOf(StopDisruption("Station closed")) else emptyList() }) { listOf(departure("Bottom", 60)) })
        model.refresh()
        assertEquals("Top: Station closed", (model.state.value as PlaceDirectViewModel.State.Ready).rows.single().notice)
    }

    @Test
    fun `a line whose status couldn't be had is not vouched for`() = runBlocking {
        val model = model(client(statuses = { throw TflException.Offline(null) }) { listOf(departure("Bottom", 60)) })
        model.refresh()
        val ready = model.state.value as PlaceDirectViewModel.State.Ready
        assertEquals(1, ready.rows.size)
        assertTrue(ready.uncertain)
    }

    @Test
    fun `rows left on show while away stand down before the next look`() = runBlocking {
        var clock = now
        val model = PlaceDirectViewModel(
            ends = { listOf(DirectTrips.End("BOT", "Bottom")) },
            client = client { listOf(departure("Bottom", 60), departure("Bottom", 240)) },
            routes = routes,
            arrivals = ArrivalsCache(),
            clock = { clock },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        ).apply { setOrigin(listOf(StopRef("TOP", "Top"))) }
        model.refresh()
        // A moment away keeps them.
        clock = now.plusSeconds(5)
        model.ageShown()
        assertEquals(1, (model.state.value as PlaceDirectViewModel.State.Ready).rows.size)
        // Longer than a tick, and they stand down until a look lands.
        clock = now.plusSeconds(120)
        model.ageShown()
        assertEquals(PlaceDirectViewModel.State.Checking, model.state.value)
    }

    @Test
    fun `moving on from every stop that had rows is Checking, not None`(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val model = model(client { stopId -> if (stopId == "MID") gate.await(); listOf(departure("Bottom", 60)) })
            model.refresh()
            assertEquals(1, (model.state.value as PlaceDirectViewModel.State.Ready).rows.size)
            // The rider moved on: Mid alone, its fetch held, so only the prune shows.
            model.setInputs(PlaceDirectViewModel.Inputs(listOf(StopRef("MID", "Mid"))))
            assertEquals(PlaceDirectViewModel.State.Checking, model.state.value)
            gate.complete(Unit)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the place's stops are looked up again once a day old`() = runBlocking {
        var clock = now
        var lookups = 0
        val model = PlaceDirectViewModel(
            ends = { lookups++; listOf(DirectTrips.End("BOT", "Bottom")) },
            client = client { listOf(departure("Bottom", 60)) },
            routes = routes,
            arrivals = ArrivalsCache(),
            clock = { clock },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        ).apply { setOrigin(listOf(StopRef("TOP", "Top"))) }
        model.refresh()
        model.refresh()
        assertEquals(1, lookups)
        clock = now.plus(NearbyStopsCache.MAX_AGE).plusSeconds(1)
        model.refresh()
        assertEquals(2, lookups)
    }

    @Test
    fun `a stop near the place only a broken lift reaches isn't step-free`() = runBlocking {
        // Bottom's platform is reached from the street (node 0) only by lift L1, to node 1.
        val table = app.stopdash.domain.StepFreeAccess(
            mapOf(
                "TOP" to mapOf("rail" to listOf(app.stopdash.domain.StepFreePlatform(app.stopdash.domain.StepFreeLevel.LEVEL))),
                "BOT" to mapOf(
                    "rail" to listOf(
                        app.stopdash.domain.StepFreePlatform(app.stopdash.domain.StepFreeLevel.LEVEL, byLift = app.stopdash.domain.LiftStop("BOTSTATION", 1)),
                    ),
                ),
            ),
            mapOf("BOTSTATION" to app.stopdash.domain.LiftMap(emptyMap(), mapOf("L1" to setOf(0, 1)))),
        )
        var out = emptySet<String>()
        val model = PlaceDirectViewModel(
            ends = { listOf(DirectTrips.End("BOT", "Bottom")) },
            client = client { listOf(departure("Bottom", 60)) },
            routes = routes,
            stepFreeAccess = { table },
            liftsOut = { out },
            arrivals = ArrivalsCache(),
            clock = { now },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        ).apply { setInputsQuietly(PlaceDirectViewModel.Inputs(listOf(StopRef("TOP", "Top")), stepFree = StepFree.STATION)) }
        model.refresh()
        assertEquals(1, (model.state.value as PlaceDirectViewModel.State.Ready).rows.size)
        out = setOf("L1")
        model.refresh()
        assertTrue((model.state.value as PlaceDirectViewModel.State.Ready).rows.isEmpty())
    }

    @Test
    fun `the look runs off the caller's thread`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "direct-worker") }.asCoroutineDispatcher()
        try {
            var readOn: String? = null
            val stops = object : AbstractList<StopRef>() {
                override val size = 1
                override fun get(index: Int): StopRef {
                    readOn = Thread.currentThread().name
                    return StopRef("TOP", "Top")
                }
            }
            val model = PlaceDirectViewModel(
                ends = { listOf(DirectTrips.End("BOT", "Bottom")) },
                client = client { listOf(departure("Bottom", 60)) },
                routes = routes,
                clock = { now },
                io = worker,
                compute = worker,
            ).apply { setOrigin(stops) }
            model.refresh()
            // Debug builds suffix the coroutine's name to the thread's.
            assertTrue(readOn.orEmpty().startsWith("direct-worker"))
            assertEquals(1, (model.state.value as PlaceDirectViewModel.State.Ready).rows.size)
        } finally {
            worker.close()
        }
    }
}
