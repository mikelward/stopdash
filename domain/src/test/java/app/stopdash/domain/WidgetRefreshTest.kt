package app.stopdash.domain

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure widget-refresh rebuild (SPEC D5): re-fetch arrivals for exactly the persisted stops,
 * stamp the fresh ones [now], keep an aged last-good for a stop that fails, and treat a cycle
 * where nothing fetched fresh as a no-op (null → nothing saved). No Android, no network — the
 * fetch is an injected lambda.
 */
class WidgetRefreshTest {
    private val t0: Instant = Instant.parse("2026-09-20T08:00:00Z")
    private val t1: Instant = Instant.parse("2026-09-20T08:01:00Z")

    private fun departure(dest: String) = Departure(
        lineId = "victoria",
        lineName = "Victoria",
        direction = "inbound",
        destination = dest,
        platform = null,
        expectedArrival = t1.plusSeconds(120),
        mode = "tube",
    )

    private fun stop(id: String, deps: List<Departure>, fetchedAt: Instant = t0) =
        StopArrivals(stopId = id, stopName = "Stop $id", departures = deps, fetchedAt = fetchedAt)

    private fun snapshot(vararg stops: StopArrivals) =
        DeparturesSnapshot(stops = stops.toList(), fetchedAt = stops.maxOf { it.fetchedAt })

    @Test
    fun `a refreshed stop keeps its nearer places, so the widget hides the same services`() = runTest {
        val nearer = Terminating.Nearer(ids = setOf("940GZZLUBXN"))
        val prior = snapshot(stop("A", emptyList()).copy(nearer = nearer))
        val endsHere = departure("Brixton").copy(destinationId = "940GZZLUBXN")
        val onward = departure("Walthamstow Central").copy(destinationId = "940GZZLUWWL")
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1) { listOf(endsHere, onward) }!!
        assertEquals(nearer, refreshed.stops.single().nearer)
        assertEquals(listOf("Walthamstow Central"), DepartureRows.across(refreshed.stops, t1).map { it.destination })
    }

    @Test
    fun `a stop fetched moments ago is carried over, not fetched again`() = runTest {
        val recent = stop("A", listOf(departure("Brixton")), fetchedAt = t1.minusSeconds(10))
        val prior = snapshot(recent, stop("B", listOf(departure("Walthamstow"))))
        val fetched = mutableListOf<String>()
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1, reuse = java.time.Duration.ofSeconds(30)) { id ->
            fetched += id
            listOf(departure("Fresh $id"))
        }
        assertEquals(listOf("B"), fetched)
        assertEquals(recent, refreshed!!.stops.first { it.stopId == "A" })
        assertEquals(t1, refreshed.stops.first { it.stopId == "B" }.fetchedAt)
    }

    @Test
    fun `the widget's journeys ride along with a refresh`() = runTest {
        val prior = snapshot(stop("B", listOf(departure("Walthamstow")))).copy(
            journeys = listOf(WidgetJourney("B", setOf(JourneyCall("victoria", "Fresh B", null)))),
            journeyOnlyStopIds = setOf("B"),
        )
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1, reuse = java.time.Duration.ZERO) { id ->
            listOf(departure("Fresh $id"))
        }!!
        assertEquals(prior.journeys, refreshed.journeys)
        assertEquals(prior.journeyOnlyStopIds, refreshed.journeyOnlyStopIds)
    }

    @Test
    fun `a cycle where every stop is recent fetches nothing and saves nothing`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton")), fetchedAt = t1.minusSeconds(5)))
        var calls = 0
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1, reuse = java.time.Duration.ofSeconds(30)) {
            calls++
            emptyList()
        }
        assertEquals(0, calls)
        assertNull(refreshed)
    }

    @Test
    fun `a recent stop that isn't fresh is fetched again`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton")), fetchedAt = t1.minusSeconds(5)).copy(arrivalsFresh = false))
        var calls = 0
        WidgetRefresh.refreshedArrivals(prior, t1, reuse = java.time.Duration.ofSeconds(30)) {
            calls++
            emptyList()
        }
        assertEquals(1, calls)
    }

    @Test
    fun `every stop fetching fresh stamps them now`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))), stop("B", listOf(departure("Walthamstow"))))
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1) { id ->
            listOf(departure("Fresh $id"))
        }
        assertEquals(t1, refreshed!!.fetchedAt)
        assertTrue(refreshed.stops.all { it.fetchedAt == t1 && it.arrivalsFresh })
        assertEquals(listOf("Fresh A"), refreshed.stops.first { it.stopId == "A" }.departures.map { it.destination })
    }

    @Test
    fun `a stop whose fetch fails keeps its aged last-good`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))), stop("B", listOf(departure("Walthamstow"))))
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1) { id ->
            if (id == "B") null else listOf(departure("Fresh $id"))
        }
        val a = refreshed!!.stops.first { it.stopId == "A" }
        val b = refreshed.stops.first { it.stopId == "B" }
        assertEquals(t1, a.fetchedAt) // A refreshed
        assertTrue(a.arrivalsFresh)
        assertEquals(t0, b.fetchedAt) // B kept at its old age, so per-row staleness withholds it
        assertFalse(b.arrivalsFresh) // and marked not-fresh so it can't read as fresh in-window
        assertEquals(listOf("Walthamstow"), b.departures.map { it.destination })
        assertEquals(t1, refreshed.fetchedAt) // whole-screen stamp is the freshest stop
    }

    @Test
    fun `a cycle where no stop fetches fresh is a no-op`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))))
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1) { null }
        assertNull(refreshed)
    }

    @Test
    fun `with no arrivals fresh, the lines are still checked and their statuses stored alone`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))))
        val asked = mutableListOf<Set<String>>()
        val suspended = LineStatus("victoria", 20, "Suspended")
        val outcome = WidgetRefresh.refresh(prior, { t1 }, fetchStatuses = { ids -> asked += ids; listOf(suspended) }) { null }
        // The arrivals outage doesn't hold the suspension back: its check is stored, stamped when
        // TfL answered, and nothing else is.
        assertEquals(listOf(setOf("victoria")), asked)
        assertEquals(WidgetRefresh.Outcome.Statuses(mapOf("victoria" to LineStatusCheck(suspended, t1))), outcome)
    }

    @Test
    fun `an answer that leaves every line out is stored too, as no verdict`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))))
        val outcome = WidgetRefresh.refresh(prior, { t1 }, fetchStatuses = { emptyList() }) { null }
        val check = (outcome as WidgetRefresh.Outcome.Statuses).checks.getValue("victoria")
        assertFalse(check.known)
        assertEquals(t1, check.checkedAt)
    }

    @Test
    fun `with neither arrivals nor a status answer, nothing changes`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))))
        assertEquals(WidgetRefresh.Outcome.Unchanged, WidgetRefresh.refresh(prior, { t1 }, fetchStatuses = { null }) { null })
        // Nor when every line was checked moments ago: nothing is asked, so nothing is stored.
        val checked = prior.copy(lineStatuses = mapOf("victoria" to LineStatusCheck(LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service"), t1.minusSeconds(10))))
        var asked = false
        val outcome = WidgetRefresh.refresh(checked, { t1 }, statusReuse = java.time.Duration.ofSeconds(90), fetchStatuses = { asked = true; emptyList() }) { null }
        assertEquals(WidgetRefresh.Outcome.Unchanged, outcome)
        assertFalse(asked)
    }

    @Test
    fun `fresh arrivals are saved with their lines' statuses`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))))
        val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")
        val outcome = WidgetRefresh.refresh(prior, { t1 }, fetchStatuses = { listOf(good) }) { listOf(departure("Fresh")) }
        val saved = (outcome as WidgetRefresh.Outcome.Save).snapshot
        assertEquals(listOf("Fresh"), saved.stops.single().departures.map { it.destination })
        assertEquals(LineStatusCheck(good, t1), saved.lineStatuses["victoria"])
    }

    @Test
    fun `an empty snapshot has nothing to refresh`() = runTest {
        val refreshed = WidgetRefresh.refreshedArrivals(DeparturesSnapshot(emptyList(), t0), t1) {
            listOf(departure("x"))
        }
        assertNull(refreshed)
    }

    @Test
    fun `fetches every stop at once rather than one after another`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))), stop("B", listOf(departure("Walthamstow"))))
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val result = async {
            WidgetRefresh.refreshedArrivals(prior, t1) { id ->
                started += id
                gate.await()
                listOf(departure("Fresh $id"))
            }
        }
        testScheduler.runCurrent()
        // Both fetches are in flight before either answers; a sequential loop would have started one.
        assertEquals(listOf("A", "B"), started)
        gate.complete(Unit)
        val refreshed = result.await()!!
        assertEquals(listOf("A", "B"), refreshed.stops.map { it.stopId })
        assertTrue(refreshed.stops.all { it.arrivalsFresh })
    }

    @Test
    fun `a stop another screen just fetched is taken at its own fetch time, not asked for`() = runTest {
        val prior = snapshot(stop("A", emptyList()), stop("B", emptyList()))
        val shared = ArrivalsCache()
        val sharedAt = t1.minusSeconds(10)
        shared.put("A", listOf(departure("Brixton")), sharedAt, RailFeed.NO_KEY)
        val asked = mutableListOf<String>()
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1, shared = shared) { id ->
            asked += id
            listOf(departure("Walthamstow Central"))
        }!!
        assertEquals(listOf("B"), asked)
        val a = refreshed.stops.single { it.stopId == "A" }
        assertEquals(sharedAt, a.fetchedAt)
        assertEquals(listOf("Brixton"), a.departures.map { it.destination })
        // With the National Rail feed that fetch found.
        assertEquals(RailFeed.NO_KEY, a.railFeed)
        assertEquals(t1, refreshed.stops.single { it.stopId == "B" }.fetchedAt)
    }

    @Test
    fun `a newer fetch by another screen replaces a stop the widget fetched moments ago`() = runTest {
        // The widget fetched A 20 s ago, within its reuse window; the app has fetched it since.
        val prior = snapshot(stop("A", listOf(departure("Brixton")), fetchedAt = t1.minusSeconds(20)))
        val shared = ArrivalsCache()
        shared.put("A", listOf(departure("Walthamstow Central")), t1.minusSeconds(5))
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1, reuse = java.time.Duration.ofSeconds(50), shared = shared) {
            error("not asked for")
        }!!
        assertEquals(t1.minusSeconds(5), refreshed.stops.single().fetchedAt)
        assertEquals(listOf("Walthamstow Central"), refreshed.stops.single().departures.map { it.destination })
    }

    @Test
    fun `a station row from before a National Rail key was added is fetched again, however recent`() = runTest {
        val prior = snapshot(stop("A", emptyList(), fetchedAt = t1.minusSeconds(10)).copy(railFeed = RailFeed.NO_KEY))
        val asked = mutableListOf<String>()
        val refreshed = WidgetRefresh.refreshedArrivals(
            prior, t1, reuse = java.time.Duration.ofSeconds(50), source = true, railFeed = { RailFeed.LIVE },
        ) { id ->
            asked += id
            listOf(departure("Brixton"))
        }
        assertEquals(listOf("A"), asked)
        assertEquals(t1, refreshed!!.stops.single().fetchedAt)
        // The row now says where its National Rail times stand after the fetch.
        assertEquals(RailFeed.LIVE, refreshed.stops.single().railFeed)
        // Without a change of key, the recent row is carried over as before.
        assertNull(WidgetRefresh.refreshedArrivals(prior, t1, reuse = java.time.Duration.ofSeconds(50), source = false) { error("not asked for") })
    }
}
