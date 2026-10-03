package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStopsTest {
    // A synthetic bus route whose last stop TfL names differently from the arrivals' destination.
    private val bus = LineSequence(
        routes = listOf(LineRoute("Hammersmith &harr;  Victoria Station", listOf("A", "B", "C"))),
        stopNames = mapOf("A" to "Hammersmith", "B" to "Hyde Park Corner", "C" to "Victoria Bus"),
    )

    @Test
    fun `a destination spelled differently from the last stop falls back to the route's name`() {
        assertEquals(
            listOf("Hyde Park Corner", "Victoria Bus"),
            RouteStops.ahead(bus, "B", "Victoria Bus", null)?.map { it.name },
        )
        assertEquals(listOf("B", "C"), RouteStops.ahead(bus, "B", "Victoria", null)?.map { it.id })
    }

    @Test
    fun `the whole route a stop list runs on is the longest route holding it, from its first stop`() {
        val routes = LineSequence(
            routes = listOf(
                LineRoute("Short", listOf("B", "C")),
                LineRoute("Long", listOf("Z", "A", "B", "C")),
                LineRoute("Other way", listOf("C", "B", "A")),
            ),
            stopNames = mapOf("Z" to "Zeta", "A" to "Hammersmith", "B" to "Hyde Park Corner", "C" to "Victoria Bus"),
        )
        val ahead = listOf(RouteStop("B", "Hyde Park Corner"), RouteStop("C", "Victoria Bus"))
        assertEquals(listOf("Z", "A", "B", "C"), RouteStops.wholeRouteOf(routes, ahead).map { it.id })
        assertEquals("Zeta", RouteStops.wholeRouteOf(routes, ahead).first().name)
        // A list no route runs in that order, or none at all, has no whole route.
        assertEquals(emptyList<RouteStop>(), RouteStops.wholeRouteOf(routes, listOf(RouteStop("A", ""), RouteStop("C", ""))))
        assertEquals(emptyList<RouteStop>(), RouteStops.wholeRouteOf(routes, emptyList()))
    }

    @Test
    fun `two downstream stops sharing the destination's name are ambiguous, not cut at the first`() {
        val loop = LineSequence(
            routes = listOf(LineRoute("A &harr; D", listOf("A", "X1", "B", "X2", "D"))),
            stopNames = mapOf("A" to "Start", "X1" to "Market", "B" to "Middle", "X2" to "Market", "D" to "End"),
        )
        assertNull(RouteStops.ahead(loop, "A", "Market", null))
        // Past the first one, only one remains ahead: unambiguous.
        assertEquals(listOf("B", "X2"), RouteStops.ahead(loop, "B", "Market", null)?.map { it.id })
    }

    @Test
    fun `a loop that calls at the boarding stop twice is ambiguous`() {
        val loop = LineSequence(
            routes = listOf(LineRoute("A &harr; E", listOf("A", "L", "M", "L", "E"))),
            stopNames = mapOf("A" to "Start", "L" to "Loop", "M" to "Middle", "E" to "End"),
        )
        // From the first visit the train still runs the loop (L, M, L, E); from the second it
        // doesn't (L, E) — nothing on the arrival says which.
        assertNull(RouteStops.ahead(loop, "L", "End", null))
    }

    @Test
    fun `a variant matching only by its route name still counts against one matching by stop name`() {
        val variants = LineSequence(
            routes = listOf(
                LineRoute("A &harr; Victoria", listOf("A", "B", "V1")),
                LineRoute("A &harr; Victoria Station", listOf("A", "C", "V2")),
            ),
            stopNames = mapOf("A" to "Start", "B" to "Bee", "C" to "Sea", "V1" to "Victoria", "V2" to "Victoria Bus"),
        )
        assertNull(RouteStops.ahead(variants, "A", "Victoria", null))
    }

    // A synthetic bus route whose blind reads a place ("Northtown") that names neither its last
    // stop nor the route — the common shape for London buses, which left most without a list.
    private val labeledBus = LineSequence(
        routes = listOf(LineRoute("Southgate &harr;  Corner Stand", listOf("S", "P", "Q", "N", "E"))),
        stopNames = mapOf(
            "S" to "Southgate", "P" to "Park Road", "Q" to "Queens Avenue",
            "N" to "Northtown High Road", "E" to "Corner Stand",
        ),
    )

    @Test
    fun `a bus whose destination names no stop or route runs to the route's end`() {
        assertEquals(
            listOf("P", "Q", "N", "E"),
            RouteStops.ahead(labeledBus, "P", "Northtown", null, bus = true)?.map { it.id },
        )
    }

    @Test
    fun `rail keeps the strict rule - an unmatched destination has no list`() {
        assertEquals(
            RouteStops.Resolution.NoMatch,
            RouteStops.resolve(labeledBus, "P", "Northtown", null, bus = false),
        )
    }

    @Test
    fun `a bus short-working that names a stop still ends there`() {
        assertEquals(
            listOf("P", "Q"),
            RouteStops.ahead(labeledBus, "P", "Queens Avenue", null, bus = true)?.map { it.id },
        )
    }

    @Test
    fun `a bus with two variants diverging ahead stays ambiguous`() {
        val variants = LineSequence(
            routes = listOf(
                LineRoute("A &harr; X", listOf("A", "B", "X")),
                LineRoute("A &harr; Y", listOf("A", "C", "Y")),
            ),
            stopNames = mapOf("A" to "Start", "B" to "Bee", "C" to "Sea", "X" to "Ex", "Y" to "Why"),
        )
        assertEquals(RouteStops.Resolution.Ambiguous(2), RouteStops.resolve(variants, "A", "Town", null, bus = true))
        // Variants that only differ *behind* the stop agree from here on: one path.
        val behind = LineSequence(
            routes = listOf(
                LineRoute("A &harr; X", listOf("A", "B", "X")),
                LineRoute("G &harr; X", listOf("G", "B", "X")),
            ),
            stopNames = mapOf("A" to "Start", "G" to "Garage", "B" to "Bee", "X" to "Ex"),
        )
        assertEquals(listOf("B", "X"), RouteStops.ahead(behind, "B", "Town", null, bus = true)?.map { it.id })
    }

    @Test
    fun `a stop off every route, or at a route's end, is reported as such`() {
        assertEquals(RouteStops.Resolution.NotOnRoute, RouteStops.resolve(labeledBus, "Z", "Northtown", null, bus = true))
        assertEquals(RouteStops.Resolution.NoMatch, RouteStops.resolve(labeledBus, "E", "Northtown", null, bus = true))
        assertEquals(RouteStops.Resolution.NoDestination, RouteStops.resolve(labeledBus, "P", "", null, bus = true))
    }

    @Test
    fun `a bus arriving at its stand ends there rather than matching no route`() {
        // The stand is only ever a route's last stop: the buses TfL lists there are arriving to end.
        assertEquals(RouteStops.Resolution.EndsHere, RouteStops.resolve(labeledBus, "E", "Corner Stand", null, bus = true))
        assertEquals(
            RouteStops.Resolution.EndsHere,
            RouteStops.resolve(labeledBus, "E", "Town Centre", null, bus = true, destinationId = "E"),
        )
        // TfL's id names another stop: a name alike doesn't overrule it.
        assertEquals(
            RouteStops.Resolution.NoMatch,
            RouteStops.resolve(labeledBus, "E", "Corner Stand", null, bus = true, destinationId = "ELSEWHERE"),
        )
    }

    @Test
    fun `a bus curtailed at this stop ends here, not run on to its route's end`() {
        assertEquals(RouteStops.Resolution.EndsHere, RouteStops.resolve(labeledBus, "Q", "Queens Avenue", null, bus = true))
        assertTrue(RouteStops.candidatePaths(labeledBus, "Q", "Queens Avenue", null, bus = true).isEmpty())
        // Ending at another pole of the stop's area is ending here too.
        val withAreas = labeledBus.copy(stopAreas = mapOf("Q" to "G-QUEENS", "Q2" to "G-QUEENS"))
        assertEquals(
            RouteStops.Resolution.EndsHere,
            RouteStops.resolve(withAreas, "Q", "Queens Avenue", null, bus = true, destinationId = "Q2"),
        )
        assertEquals(
            RouteStops.Resolution.EndsHere,
            RouteStops.resolve(withAreas, "Q", "Queens Avenue", null, bus = true, destinationId = "G-QUEENS"),
        )
    }

    @Test
    fun `a train turned short at the station it's listed at ends here`() {
        val rail = LineSequence(
            routes = listOf(LineRoute("North ↔ South", listOf("N", "M", "S")), LineRoute("South ↔ North", listOf("S", "M", "N"))),
            stopNames = mapOf("N" to "North", "M" to "Middle", "S" to "South"),
        )
        assertEquals(RouteStops.Resolution.EndsHere, RouteStops.resolve(rail, "M", "Middle", null))
        assertEquals(RouteStops.Resolution.EndsHere, RouteStops.resolve(rail, "M", "Middle", null, destinationId = "M"))
        // A loop calling here again still runs to its second call, unless TfL's id says it ends here.
        val loop = LineSequence(listOf(LineRoute("Loop", listOf("M", "N", "S", "M"))), rail.stopNames)
        assertEquals(listOf("M", "N", "S", "M"), RouteStops.ahead(loop, "M", "Middle", null)?.map { it.id })
        assertEquals(RouteStops.Resolution.EndsHere, RouteStops.resolve(loop, "M", "Middle", null, destinationId = "M"))
        assertTrue(RouteStops.candidatePaths(loop, "M", "Middle", null, destinationId = "M").isEmpty())
        // The id holds with no destination named, too.
        assertEquals(RouteStops.Resolution.EndsHere, RouteStops.resolve(rail, "M", "", null, destinationId = "M"))
    }

    @Test
    fun `an unresolved list is logged with its reason, a resolved one is not`() {
        val warnings = mutableListOf<String>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = labeledBus
            },
            warn = { warnings += it },
        )
        repository.reportUnresolved("43", "P", RouteStops.Resolution.Found(emptyList()))
        repository.reportUnresolved("43", "P", RouteStops.Resolution.Ambiguous(2))
        repository.reportUnresolved("43", "P", RouteStops.Resolution.NoMatch)
        repository.reportUnresolved("43", "P", RouteStops.Resolution.EndsHere)
        assertEquals(
            listOf(
                "route stops unavailable for line 43 at stop P: 2 possible paths",
                "route stops unavailable for line 43 at stop P: destination matches no route",
                "route stops unavailable for line 43 at stop P: ends at this stop",
            ),
            warnings,
        )
    }

    @Test
    fun `misses and an unplaced journey are logged with their ids and reasons`() {
        val warnings = mutableListOf<String>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = labeledBus
            },
            warn = { warnings += it },
        )
        repository.reportMisses(
            listOf(
                RouteMiss("43", "P", RouteStops.Resolution.NoMatch),
                RouteMiss("", "P", RouteStops.Resolution.NoLine),
            ),
        )
        repository.reportMisses(emptyList())
        repository.reportUnplaced("43")
        assertEquals(
            listOf(
                "route stops unavailable for line 43 at stop P: destination matches no route",
                "route stops unavailable for line (none) at stop P: no line id",
                "journey not placed on line 43: no single boarding stop before the far end",
            ),
            warnings,
        )
    }

    @Test
    fun `a loop's inner and outer rail go opposite ways round`() {
        // A square loop about the origin, both ways round from N ending at End: the outer rail runs
        // clockwise (by E), the inner rail anticlockwise (by W).
        val loop = LineSequence(
            routes = listOf(
                LineRoute("N ↔ End", listOf("N", "E", "S", "W", "END")),
                LineRoute("N ↔ End", listOf("N", "W", "S", "E", "END")),
            ),
            stopNames = mapOf("N" to "N", "E" to "E", "S" to "S", "W" to "W", "END" to "End"),
            stopPositions = mapOf(
                "N" to (51.01 to 0.0), "E" to (51.0 to 0.01), "S" to (50.99 to 0.0), "W" to (51.0 to -0.01), "END" to (51.0 to 0.0),
            ),
        )
        assertEquals(RouteStops.Resolution.Ambiguous(2), RouteStops.resolve(loop, "N", "End", null))
        assertEquals("E", RouteStops.ahead(loop, "N", "End", null, bound = RouteStops.Bound.OUTER_RAIL)!![1].id)
        assertEquals("W", RouteStops.ahead(loop, "N", "End", null, bound = RouteStops.Bound.INNER_RAIL)!![1].id)
        assertEquals(RouteStops.Bound.INNER_RAIL, RouteStops.boundOf("Inner Rail - Platform 1"))
        assertEquals(RouteStops.Bound.OUTER_RAIL, RouteStops.boundOf("Outer Rail - Platform 2"))
        // A platform that faces neither way keeps both: never guessed down to one.
        assertEquals(RouteStops.Resolution.Ambiguous(2), RouteStops.resolve(loop, "N", "End", null, bound = RouteStops.Bound.NORTH))
    }

    @Test
    fun `with a platform that faces no way, the train's direction picks its routes`() {
        // One line both ways through Mid: outbound runs on to Far, inbound back to Home.
        val line = LineSequence(
            routes = listOf(
                LineRoute("Home ↔ Far", listOf("HOME", "MID", "FAR"), direction = "outbound"),
                LineRoute("Far ↔ Home", listOf("FAR", "MID", "HOME"), direction = "inbound"),
            ),
            stopNames = mapOf("HOME" to "Home", "MID" to "Mid", "FAR" to "Far"),
        )
        val far = setOf("FAR")
        // "Check Front of Train" at "Platform 1": either way, so it can't be said.
        assertEquals(null, RouteStops.reaches(line, "MID", "Check Front of Train", null, far))
        assertEquals(true, RouteStops.reaches(line, "MID", "Check Front of Train", null, far, direction = "outbound"))
        assertEquals(false, RouteStops.reaches(line, "MID", "Check Front of Train", null, far, direction = "inbound"))
        // A direction no route was fetched for can't narrow, nor can a blank one.
        assertEquals(null, RouteStops.reaches(line, "MID", "Check Front of Train", null, far, direction = "sideways"))
        // A route cached before its direction was kept stays a candidate.
        val unknown = line.copy(routes = line.routes.map { it.copy(direction = "") })
        assertEquals(null, RouteStops.reaches(unknown, "MID", "Check Front of Train", null, far, direction = "outbound"))
    }

    @Test
    fun `a platform's compass wins over the train's direction`() {
        // On a loop TfL's direction can name the other way round; the platform decides.
        val loop = LineSequence(
            routes = listOf(
                LineRoute("N ↔ End", listOf("N", "E", "S", "W", "END"), direction = "outbound"),
                LineRoute("N ↔ End", listOf("N", "W", "S", "E", "END"), direction = "inbound"),
            ),
            stopNames = mapOf("N" to "N", "E" to "E", "S" to "S", "W" to "W", "END" to "End"),
            stopPositions = mapOf(
                "N" to (51.01 to 0.0), "E" to (51.0 to 0.01), "S" to (50.99 to 0.0), "W" to (51.0 to -0.01), "END" to (51.0 to 0.0),
            ),
        )
        assertEquals(
            "E",
            RouteStops.ahead(loop, "N", "End", null, bound = RouteStops.Bound.OUTER_RAIL, direction = "inbound")!![1].id,
        )
        // No compass: the direction picks.
        assertEquals("W", RouteStops.ahead(loop, "N", "End", null, direction = "inbound")!![1].id)
    }

    @Test
    fun `route names parse to their far end`() {
        assertEquals("Edgware", RouteStops.terminusOf("Morden  &harr;  Edgware  via Bank"))
        assertEquals("Archway", RouteStops.terminusOf("Victoria Bus Station &harr;  Archway Station"))
    }

    @Test
    fun `a blank direction fetches both, a known one only its own`() {
        assertEquals(listOf("inbound", "outbound"), RouteStops.directionsFor(""))
        assertEquals(listOf("outbound"), RouteStops.directionsFor("outbound"))
    }

    @Test
    fun `the repository caches per line and direction, and reports failures`() = runTest {
        val calls = mutableListOf<String>()
        var fail = false
        val warnings = mutableListOf<String>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    calls += "$lineId/$direction"
                    if (fail) throw TflException.Offline(null)
                    return bus
                }
            },
            warn = { warnings += it },
            clock = { Instant.parse("2026-09-26T08:00:00Z") },
        )
        assertNull(repository.cached("14", "inbound"))
        repository.load("14", "inbound")
        repository.load("14", "inbound")
        assertEquals(listOf("14/inbound"), calls)
        assertEquals(bus, repository.cached("14", "inbound"))
        // A blank direction needs both halves; only the missing one is fetched.
        assertNull(repository.cached("14", ""))
        repository.load("14", "")
        assertEquals(listOf("14/inbound", "14/outbound"), calls)

        fail = true
        val thrown = try {
            repository.load("22", "inbound")
            null
        } catch (e: TflException.Offline) {
            e
        }
        assertTrue("a failed fetch propagates its reason", thrown != null)
        assertNull("and caches nothing", repository.cached("22", "inbound"))
        assertEquals(
            listOf(
                "route sequence fetched for line 14 inbound in 0 ms",
                "route sequence fetched for line 14 outbound in 0 ms",
                "route sequence fetch failed for line 22 inbound after 0 ms: Offline",
            ),
            warnings,
        )
    }

    @Test
    fun `route fetches are bounded so live requests keep free slots`() = runTest {
        var inFlight = 0
        var most = 0
        val gate = CompletableDeferred<Unit>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    inFlight++
                    most = maxOf(most, inFlight)
                    gate.await()
                    inFlight--
                    return bus
                }
            },
        )
        val loads = (1..5).map { line -> async { repository.load("$line", "") } }
        runCurrent()
        assertEquals(RouteStopsRepository.MAX_CONCURRENT_FETCHES, inFlight)
        gate.complete(Unit)
        loads.forEach { it.await() }
        assertEquals(RouteStopsRepository.MAX_CONCURRENT_FETCHES, most)
    }

    @Test
    fun `two loads of one line at once share its requests`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    calls += "$lineId/$direction"
                    gate.await()
                    return bus
                }
            },
        )
        // A trip loading the line while its page, opened meanwhile, loads it too.
        val trip = async { repository.load("14", "") }
        val page = async { repository.load("14", "") }
        runCurrent()
        gate.complete(Unit)
        assertEquals(trip.await(), page.await())
        assertEquals(listOf("14/inbound", "14/outbound"), calls)
    }

    @Test
    fun `a load joined by another still finishes when the first is canceled`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    calls += "$lineId/$direction"
                    gate.await()
                    return bus
                }
            },
        )
        val first = async { repository.load("14", "inbound") }
        val second = async { repository.load("14", "inbound") }
        runCurrent()
        // The screen that started the request leaves; the one waiting on it asks again itself.
        first.cancel()
        runCurrent()
        gate.complete(Unit)
        assertEquals(bus.routes, second.await().routes)
        assertEquals(listOf("14/inbound", "14/inbound"), calls)
    }

    @Test
    fun `a line's two directions are fetched at once, not in turn`() = runTest {
        val started = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    started += direction
                    gate.await()
                    return bus
                }
            },
        )
        val load = async { repository.load("14", "") }
        runCurrent()
        // Both requests are out before either answers.
        assertEquals(listOf("inbound", "outbound"), started)
        gate.complete(Unit)
        load.await()
    }

    private class MemoryStore : RouteStopsStore {
        var contents = RouteStopsStore.Contents()
        var saves = 0
        override fun load() = contents
        override fun save(contents: RouteStopsStore.Contents) {
            this.contents = contents
            saves++
        }
    }

    private val pole = StopLocation("490000001A", "Hill", 51.5, -0.12, listOf(LineRef("43", "43", "bus")), stopLetter = "A")

    private class CountingSource(private val sequence: LineSequence, private val poles: List<StopLocation>) :
        RouteSequenceSource, StopAreaSource {
        val calls = mutableListOf<String>()
        override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
            calls += "$lineId/$direction"
            return sequence
        }
        override suspend fun stopAreaPoles(areaId: String): List<StopLocation> {
            calls += areaId
            return poles
        }
    }

    @Test
    fun `a route and a stop area fetched by one process are reused by the next for a day`() = runTest {
        val store = MemoryStore()
        var now = Instant.parse("2026-09-24T08:00:00Z")
        val io = StandardTestDispatcher(testScheduler)
        val first = CountingSource(bus, listOf(pole))
        val before = RouteStopsRepository(first, store = store, clock = { now }, io = io)
        before.load("14", "inbound")
        before.loadPoles("490G00000001")
        assertEquals(listOf("14/inbound", "490G00000001"), first.calls)

        // A new process: nothing in memory, the store read once on warm-up, so the first frame
        // has the stops and neither is fetched again.
        now = now.plus(Duration.ofHours(23))
        val second = CountingSource(bus, listOf(pole))
        val after = RouteStopsRepository(second, store = store, clock = { now }, io = io)
        assertNull(after.cached("14", "inbound"))
        after.warm()
        assertEquals(bus, after.cached("14", "inbound"))
        assertEquals(listOf(pole), after.cachedPoles("490G00000001"))
        after.load("14", "inbound")
        after.loadPoles("490G00000001")
        assertEquals(emptyList<String>(), second.calls)

        // A day on, both have expired: not offered to a first frame, fetched again.
        now = now.plus(Duration.ofHours(1))
        assertNull(after.cached("14", "inbound"))
        assertNull(after.cachedPoles("490G00000001"))
        after.load("14", "inbound")
        after.loadPoles("490G00000001")
        assertEquals(listOf("14/inbound", "490G00000001"), second.calls)
    }

    @Test
    fun `entries a day old are dropped from the store when it is read`() = runTest {
        val at = Instant.parse("2026-09-24T08:00:00Z")
        val store = MemoryStore().apply {
            contents = RouteStopsStore.Contents(
                sequences = mapOf(
                    "14/inbound" to RouteStopsStore.Timed(at, bus),
                    "22/inbound" to RouteStopsStore.Timed(at.plus(Duration.ofHours(2)), bus),
                ),
                poles = mapOf("490G00000001" to RouteStopsStore.Timed(at, listOf(pole))),
            )
        }
        val repository = RouteStopsRepository(
            CountingSource(bus, listOf(pole)),
            store = store,
            clock = { at.plus(Duration.ofHours(25)) },
            io = StandardTestDispatcher(testScheduler),
        )
        repository.warm()
        assertEquals(setOf("22/inbound"), store.contents.sequences.keys)
        assertEquals(emptySet<String>(), store.contents.poles.keys)
        assertNull(repository.cached("14", "inbound"))
        assertEquals(bus, repository.cached("22", "inbound"))
    }

    @Test
    fun `an entry stamped in the future, the clock having moved back, is fetched again`() = runTest {
        val now = Instant.parse("2026-09-24T08:00:00Z")
        val store = MemoryStore().apply {
            contents = RouteStopsStore.Contents(sequences = mapOf("14/inbound" to RouteStopsStore.Timed(now.plusSeconds(60), bus)))
        }
        val source = CountingSource(bus, emptyList())
        val repository = RouteStopsRepository(source, store = store, clock = { now }, io = StandardTestDispatcher(testScheduler))
        repository.load("14", "inbound")
        assertEquals(listOf("14/inbound"), source.calls)
        assertEquals(now, store.contents.sequences.getValue("14/inbound").at)
    }

    private fun at(minutes: Long) = Instant.parse("2026-09-26T08:00:00Z").plus(Duration.ofMinutes(minutes))

    @Test
    fun `a planned leg shows the stops of its own branch`() {
        // Two branches from A to one terminus Z, by B or by C: the Planner rides by C.
        val forked = LineSequence(
            routes = listOf(LineRoute("A ↔ Z via B", listOf("A", "B", "Z")), LineRoute("A ↔ Z via C", listOf("A", "C", "Z"))),
            stopNames = mapOf("A" to "A", "B" to "B", "C" to "C", "Z" to "Z"),
        )
        val leg = TripLeg("tube", "fork", "fork", "A", "A", "Z", "Z", at(5), at(15), path = listOf("C", "Z"), headings = listOf("Z"))
        val stops = RouteStops.forLeg(forked, leg) as RouteStops.Resolution.Found
        assertEquals(listOf("A", "C", "Z"), stops.stops.map { it.id })
        // No planned path: the terminus alone can't say which branch.
        assertTrue(RouteStops.forLeg(forked, leg.copy(path = emptyList(), toId = "Q")) !is RouteStops.Resolution.Found)
    }

    @Test
    fun `a planned leg lists its line on to the terminus, past where the rider gets off`() {
        // Both branches pass B; the Planner's train, heading to Y, rides on by C.
        val line = LineSequence(
            routes = listOf(LineRoute("A ↔ Y", listOf("A", "B", "C", "Y")), LineRoute("A ↔ Z", listOf("A", "B", "D", "Z"))),
            stopNames = mapOf("A" to "A", "B" to "B", "C" to "C", "D" to "D", "Y" to "Y", "Z" to "Z"),
        )
        val leg = TripLeg("tube", "line", "line", "A", "A", "B", "B", at(5), at(9), path = listOf("B"), headings = listOf("Y"))
        val stops = RouteStops.forLeg(line, leg) as RouteStops.Resolution.Found
        assertEquals(listOf("A", "B", "C", "Y"), stops.stops.map { it.id })
        // A terminus no route reaches past where the rider gets off: no list, rather than a guessed one.
        assertTrue(RouteStops.forLeg(line, leg.copy(headings = listOf("Q"))) !is RouteStops.Resolution.Found)
    }

    @Test
    fun `a planned leg follows its whole planned path where branches share their first stop`() {
        // Both branches leave A by H, then split by B or by C to rejoin at Z, as the Northern line does.
        val forked = LineSequence(
            routes = listOf(LineRoute("A ↔ Z via B", listOf("A", "H", "B", "Z")), LineRoute("A ↔ Z via C", listOf("A", "H", "C", "Z"))),
            stopNames = mapOf("A" to "A", "H" to "H", "B" to "B", "C" to "C", "Z" to "Z"),
        )
        val leg = TripLeg("tube", "fork", "fork", "A", "A", "Z", "Z", at(5), at(15), path = listOf("H", "C", "Z"), headings = listOf("Z"))
        val stops = RouteStops.forLeg(forked, leg) as RouteStops.Resolution.Found
        assertEquals(listOf("A", "H", "C", "Z"), stops.stops.map { it.id })
    }

    @Test
    fun `a planned leg whose route misses where the rider gets off has no list`() {
        // The line's route runs to the Planner's terminus but never calls where the leg alights (the
        // two datasets disagree): no list, rather than one without the rider's stop.
        val line = LineSequence(
            routes = listOf(LineRoute("A ↔ Z", listOf("A", "B", "Z"))),
            stopNames = mapOf("A" to "A", "B" to "B", "Z" to "Z"),
        )
        val leg = TripLeg("tube", "line", "line", "A", "A", "Q", "Q", at(5), at(9), path = listOf("Q"), headings = listOf("Z"))
        assertTrue(RouteStops.forLeg(line, leg) !is RouteStops.Resolution.Found)
    }

    @Test
    fun `a planned leg gets off at the station the Planner names by its other id`() {
        // The route calls at Z's low-level platforms (ZLL); the Planner names the station's main id
        // (Z), in the same interchange: the same place, so the rider gets off there.
        val line = LineSequence(
            routes = listOf(LineRoute("A ↔ Y", listOf("A", "B", "ZLL", "Y"))),
            stopNames = mapOf("A" to "A", "B" to "B", "ZLL" to "Z", "Z" to "Z", "Y" to "Y"),
            stopHubs = mapOf("ZLL" to "HUBZ", "Z" to "HUBZ"),
        )
        val leg = TripLeg("rail", "line", "line", "A", "A", "Z", "Z", at(5), at(9), path = listOf("B", "Z"), headings = listOf("Y"))
        val stops = RouteStops.forLeg(line, leg) as RouteStops.Resolution.Found
        assertEquals(listOf("A", "B", "Z", "Y"), stops.stops.map { it.id })
    }

    @Test
    fun `a station's line qualifier keeps Hammersmith's two stations apart`() {
        // Both are in one interchange; the District calls at the Dist&Picc station only.
        val district = LineSequence(
            routes = listOf(LineRoute("Ealing Broadway ↔ Upminster", listOf("A", "HSD", "B"))),
            stopNames = mapOf(
                "A" to "A", "B" to "B",
                "HSD" to cleanStopName("Hammersmith (Dist&Picc Line) Underground Station"),
                "HSC" to cleanStopName("Hammersmith (H&C Line) Underground Station"),
                "HSX" to "Hammersmith",
            ),
            stopHubs = mapOf("HSD" to "HUBHMS", "HSC" to "HUBHMS", "HSX" to "HUBHMS"),
        )
        // The H&C station isn't taken for the District's own.
        assertTrue(district.callingAt("HSC") === district)
        // A stop under another id that leaves the qualifier off is.
        assertEquals(listOf("A", "HSX", "B"), district.callingAt("HSX").routes.single().stopIds)
        // Ending here, by name only: a qualifier on one side is a source that left it off; on both,
        // it has to name the same line.
        assertTrue(RouteStops.endsAt(district, "HSD", "Hammersmith", ""))
        assertTrue(RouteStops.endsAt(district.copy(stopNames = district.stopNames + ("HSD" to "Hammersmith")), "HSD", "Hammersmith (Dist&Picc)", ""))
        assertFalse(RouteStops.endsAt(district, "HSD", "Hammersmith (H&C Line) Underground Station", ""))
    }

}
