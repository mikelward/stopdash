package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.Departure
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.WidgetJourney
import app.stopdash.domain.WidgetJourneyCheck
import app.stopdash.domain.WidgetJourneysReport
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.Terminating
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store wrapper's mapping (persisted ↔ domain), over a fake in-memory [DataStore] so no
 * Android file or Context is needed — the JSON serialization itself is covered by
 * [SnapshotSerializerTest].
 */
class DataStoreSnapshotStoreTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private class FakeDataStore(initial: PersistedSnapshot?) : DataStore<PersistedSnapshot?> {
        val state = MutableStateFlow(initial)
        // Run once as the next write takes the lock, before its transform: a writer that got there first.
        var beforeNextWrite: (suspend () -> Unit)? = null
        override val data: Flow<PersistedSnapshot?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedSnapshot?) -> PersistedSnapshot?,
        ): PersistedSnapshot? {
            beforeNextWrite?.let { beforeNextWrite = null; it() }
            return transform(state.value).also { state.value = it }
        }
    }

    /** A report pinning [pin] as a check at its origin confirms it. */
    private fun pinning(pin: WidgetJourney) =
        WidgetJourneysReport(setOf(pin.key), listOf(WidgetJourneyCheck(pin.key, pin.originId, pin.calls)))

    private fun snapshot() = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                stopId = "940GZZLUOXC",
                stopName = "Oxford Circus",
                departures = listOf(
                    Departure("victoria", "Victoria", "inbound", "Brixton", null, now.plusSeconds(180), "tube"),
                ),
                fetchedAt = now,
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `dismissing a line status marks the stored check only while it holds exactly that alert`() = runTest {
        val severe = LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now))))
        // A different status is a different alert: left marked.
        store.dismissLineStatus(DismissedAlert.ofLineStatus(severe.copy(severity = 3, description = "Part Suspended")))
        assertEquals(false, store.load()!!.lineStatuses.getValue("victoria").dismissed)
        // So is the same label with a reworded reason, though the stored check drops the reason.
        store.dismissLineStatus(DismissedAlert.ofLineStatus(severe.copy(fullText = "Earlier signal failure.")))
        assertEquals(false, store.load()!!.lineStatuses.getValue("victoria").dismissed)
        store.dismissLineStatus(DismissedAlert.ofLineStatus(severe))
        val dismissed = store.load()!!.lineStatuses.getValue("victoria")
        assertTrue(dismissed.dismissed)
        assertEquals(now, dismissed.checkedAt)
    }

    @Test
    fun `a newer save of the same alert, from a writer that missed the dismissal, keeps it dismissed`() = runTest {
        val severe = LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now))))
        store.dismissLineStatus(DismissedAlert.ofLineStatus(severe))
        // The app's next save was built before the dismissal reached it: a newer, unmarked check.
        store.saveKeepingJourneys(
            snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now.plusSeconds(30)))),
        )
        val kept = store.load()!!.lineStatuses.getValue("victoria")
        assertEquals(now.plusSeconds(30), kept.checkedAt)
        assertTrue(kept.dismissed)
    }

    @Test
    fun `a check fetched before the dismissal, saved after it, comes out dismissed`() = runTest {
        // The widget's worker read the dismissed set, then fetched a status the store didn't hold
        // yet; the user dismisses it from the app before the worker saves.
        val severe = LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        var clock = now
        val store = DataStoreSnapshotStore(FakeDataStore(null), clock = { clock })
        store.save(snapshot())
        store.dismissLineStatus(DismissedAlert.ofLineStatus(severe))
        val fetched = snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now)))
        clock = now.plusSeconds(20)
        assertTrue(store.saveIfStopsMatch(fetched, listOf("940GZZLUOXC")))
        assertTrue(store.load()!!.lineStatuses.getValue("victoria").dismissed)
        // A replacing save keeps it too.
        store.save(fetched)
        assertTrue(store.load()!!.lineStatuses.getValue("victoria").dismissed)
        // A reworded alert on that line is a new one: shown.
        val reworded = LineStatus("victoria", 6, "Severe Delays", "Earlier signal failure.")
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(reworded, now))))
        assertFalse(store.load()!!.lineStatuses.getValue("victoria").dismissed)
    }

    @Test
    fun `a dismissal stops being replayed once its window has passed`() = runTest {
        val severe = LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        var clock = now
        val store = DataStoreSnapshotStore(FakeDataStore(null), clock = { clock })
        store.save(snapshot())
        store.dismissLineStatus(DismissedAlert.ofLineStatus(severe))
        clock = now.plus(RECENT_DISMISSAL_WINDOW)
        val later = snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(severe, clock)))
        store.saveKeepingJourneys(later)
        // By now every writer reads the dismissal from the dismissed set itself.
        assertFalse(store.load()!!.lineStatuses.getValue("victoria").dismissed)
    }

    @Test
    fun `a dismissal stops being replayed once its line is checked holding another alert`() = runTest {
        val severe = LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")
        var clock = now
        val store = DataStoreSnapshotStore(FakeDataStore(null), clock = { clock })
        store.save(snapshot())
        store.dismissLineStatus(DismissedAlert.ofLineStatus(severe))
        // A racing writer's check from before the dismissal proves nothing: still replayed.
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(good, now.minusSeconds(10)))))
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now))))
        assertTrue(store.load()!!.lineStatuses.getValue("victoria").dismissed)
        // Checked since, the disruption has cleared.
        clock = now.plusSeconds(60)
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(good, clock))))
        // So the same alert recurring is shown.
        clock = now.plusSeconds(120)
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(severe, clock))))
        assertFalse(store.load()!!.lineStatuses.getValue("victoria").dismissed)
    }

    @Test
    fun `a dismissal made before anything is stored reaches the first save`() = runTest {
        val severe = LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val store = DataStoreSnapshotStore(FakeDataStore(null), clock = { now })
        store.dismissLineStatus(DismissedAlert.ofLineStatus(severe))
        // Nothing is stored for it, so the widget still reads as having no snapshot.
        assertNull(store.load())
        // The first save, built before the dismissal, lands after it.
        store.saveKeepingJourneys(snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now))))
        assertTrue(store.load()!!.lineStatuses.getValue("victoria").dismissed)
    }

    @Test
    fun `a first save already waiting when the dismissal lands still comes out dismissed`() = runTest {
        // The save was called first, but the dismissal's write reached the store ahead of it.
        val severe = LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing, clock = { now })
        backing.beforeNextWrite = { store.dismissLineStatus(DismissedAlert.ofLineStatus(severe)) }
        store.saveKeepingJourneys(snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now))))
        assertTrue(store.load()!!.lineStatuses.getValue("victoria").dismissed)
    }

    @Test
    fun `load returns null when nothing is stored`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        assertNull(store.load())
    }

    @Test
    fun `save then load returns the same snapshot`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        store.save(snapshot())
        assertEquals(snapshot(), store.load())
    }

    @Test
    fun `the widget's journeys and journey-only stops round-trip`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val withJourneys = snapshot().copy(
            journeys = listOf(WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "940GZZLUOXC|940GZZLUBXN")),
            journeyOnlyStopIds = setOf("940GZZLUOXC"),
        )
        store.save(withJourneys)
        assertEquals(withJourneys, store.load())
    }

    @Test
    fun `an unstarred journey is dropped from the stored snapshot, with its journey-only stop`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val kept = WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "keep", "940GZZLUOXC")
        val gone = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "gone")
        val base = snapshot()
        val origin = base.stops.first().copy(stopId = "490000009Z")
        store.save(
            base.copy(
                stops = base.stops + origin,
                journeys = listOf(kept, gone),
                journeyOnlyStopIds = setOf("490000009Z"),
            ),
        )
        store.updateWidgetJourneys(WidgetJourneysReport(setOf("keep"), emptyList()), emptyList())
        val after = store.load()!!
        assertEquals(listOf(kept), after.journeys)
        // A flipped journey (now shown from another stop) is dropped too.
        store.updateWidgetJourneys(WidgetJourneysReport(setOf("keep"), emptyList(), mapOf("keep" to "940GZZLUKSX")), emptyList())
        assertTrue(store.load()!!.journeys.isEmpty())
        assertTrue(after.journeyOnlyStopIds.isEmpty())
        assertEquals(base.stops.map { it.stopId }, after.stops.map { it.stopId })
    }

    @Test
    fun `a version-1 snapshot still loads`() = runTest {
        val v1 = snapshot().toPersisted().copy(version = 1)
        assertEquals(snapshot(), DataStoreSnapshotStore(FakeDataStore(v1)).load())
    }

    @Test
    fun `a stored future-version snapshot loads as null`() = runTest {
        val future = snapshot().toPersisted().copy(version = PersistedSnapshot.CURRENT_VERSION + 1)
        val store = DataStoreSnapshotStore(FakeDataStore(future))
        assertNull(store.load())
    }

    /** A different-id snapshot, standing in for "the user relocated" — a new stop set entirely. */
    private fun relocated() = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                stopId = "940GZZLUKSX",
                stopName = "King's Cross",
                departures = listOf(
                    Departure("northern", "Northern", "southbound", "Morden", null, now.plusSeconds(120), "tube"),
                ),
                fetchedAt = now,
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `saveIfStopsMatch applies and writes when the stored stop set matches`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().toPersisted()))
        val refreshed = snapshot().copy(fetchedAt = now.plusSeconds(60))
        val applied = store.saveIfStopsMatch(refreshed, listOf("940GZZLUOXC"))
        assertEquals(true, applied)
        assertEquals(refreshed, store.load())
    }

    @Test
    fun `updateWidgetJourneys applies a complete check and leaves the stops`() = runTest {
        val brixton = JourneyCall("victoria", "Brixton", null)
        val old = WidgetJourney("940GZZLUOXC", setOf(brixton), "k")
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().copy(journeys = listOf(old)).toPersisted()))
        val walthamstow = JourneyCall("victoria", "Walthamstow Central", null)
        val check = WidgetJourneyCheck("k", "940GZZLUOXC", setOf(walthamstow), setOf(brixton, walthamstow))
        // A journey with nothing confirmed isn't pinned.
        val none = WidgetJourneyCheck("other", "940GZZLUOXC", emptySet())
        store.updateWidgetJourneys(WidgetJourneysReport(setOf("k", "other"), listOf(check, none)), emptyList())
        val after = store.load()!!
        assertEquals(listOf(old.copy(calls = setOf(walthamstow))), after.journeys)
        assertEquals(snapshot().stops, after.stops)
    }

    @Test
    fun `saveKeepingJourneys keeps the stored journeys and their journey-only stops`() = runTest {
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        val stored = snapshot().copy(stops = snapshot().stops + origin, journeys = listOf(pin), journeyOnlyStopIds = setOf("490000009Z"))
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        store.saveKeepingJourneys(snapshot())
        val after = store.load()!!
        assertEquals(listOf(pin), after.journeys)
        assertEquals(setOf("490000009Z"), after.journeyOnlyStopIds)
        assertTrue(after.stops.any { it.stopId == "490000009Z" })
    }

    @Test
    fun `a pruned stop a pinned journey starts from stays, as journey-only`() = runTest {
        val pin = WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "k")
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().copy(journeys = listOf(pin)).toPersisted()))
        store.pruneStops(listOf("940GZZLUOXC"))
        val after = store.load()!!
        assertEquals(snapshot().stops, after.stops)
        assertEquals(setOf("940GZZLUOXC"), after.journeyOnlyStopIds)
    }

    @Test
    fun `updateWidgetJourneys adds a supplied origin the store doesn't hold`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().toPersisted()))
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        store.updateWidgetJourneys(pinning(pin), listOf(origin))
        val after = store.load()!!
        assertEquals(listOf(pin), after.journeys)
        assertEquals(setOf("490000009Z"), after.journeyOnlyStopIds)
        assertTrue(origin in after.stops)
    }

    @Test
    fun `updateWidgetJourneys refreshes a stored origin only with newer arrivals`() = runTest {
        val stored = snapshot().stops.first()
        val pin = WidgetJourney(stored.stopId, setOf(JourneyCall("b1", "Hill", null)), "k")
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().toPersisted()))
        val older = stored.copy(fetchedAt = stored.fetchedAt.minusSeconds(60), departures = emptyList())
        store.updateWidgetJourneys(pinning(pin), listOf(older))
        assertEquals(listOf(stored), store.load()!!.stops)
        val newer = stored.copy(fetchedAt = stored.fetchedAt.plusSeconds(60), departures = emptyList())
        store.updateWidgetJourneys(pinning(pin), listOf(newer))
        val after = store.load()!!
        assertEquals(listOf(newer), after.stops)
        assertEquals(newer.fetchedAt, after.fetchedAt)
        // Still a nearby stop, as stored.
        assertEquals(emptySet<String>(), after.journeyOnlyStopIds)
    }

    @Test
    fun `updateWidgetJourneys starts a snapshot when nothing is stored`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        store.updateWidgetJourneys(pinning(pin), listOf(origin))
        val after = store.load()!!
        assertEquals(listOf(pin), after.journeys)
        assertEquals(listOf(origin), after.stops)
        assertEquals(setOf("490000009Z"), after.journeyOnlyStopIds)
        assertEquals(origin.fetchedAt, after.fetchedAt)
        // No pin, nothing stored: still nothing.
        val empty = DataStoreSnapshotStore(FakeDataStore(null))
        empty.updateWidgetJourneys(WidgetJourneysReport(emptySet(), emptyList()), emptyList())
        assertNull(empty.load())
    }

    @Test
    fun `journey writes over a version-1 snapshot write version 2`() = runTest {
        val v1 = snapshot().toPersisted().copy(version = 1)
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        val replaced = FakeDataStore(v1)
        DataStoreSnapshotStore(replaced).updateWidgetJourneys(pinning(pin), listOf(origin))
        assertEquals(PersistedSnapshot.CURRENT_VERSION, replaced.state.value!!.version)
        val pinned = snapshot().copy(journeys = listOf(pin.copy(originId = "940GZZLUOXC"))).toPersisted().copy(version = 1)
        val retained = FakeDataStore(pinned)
        DataStoreSnapshotStore(retained).updateWidgetJourneys(WidgetJourneysReport(emptySet(), emptyList()), emptyList())
        assertEquals(PersistedSnapshot.CURRENT_VERSION, retained.state.value!!.version)
        val pruned = FakeDataStore(pinned)
        DataStoreSnapshotStore(pruned).pruneStops(listOf("940GZZLUOXC"))
        assertEquals(PersistedSnapshot.CURRENT_VERSION, pruned.state.value!!.version)
        assertEquals(listOf("940GZZLUOXC"), pruned.state.value!!.journeyOnlyStopIds)
    }

    @Test
    fun `saveKeepingJourneys keeps an origin saved as nearby`() = runTest {
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        // Saved when the origin was nearby: not marked journey-only.
        val stored = snapshot().copy(stops = snapshot().stops + origin, journeys = listOf(pin))
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        store.saveKeepingJourneys(snapshot())
        val after = store.load()!!
        assertTrue(after.stops.any { it.stopId == "490000009Z" })
        assertEquals(setOf("490000009Z"), after.journeyOnlyStopIds)
    }

    @Test
    fun `saveKeepingJourneys stamps from a carried origin that's newer`() = runTest {
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        val origin = snapshot().stops.first().copy(stopId = "490000009Z", fetchedAt = now.plusSeconds(60))
        val stored = snapshot().copy(stops = snapshot().stops + origin, journeys = listOf(pin), journeyOnlyStopIds = setOf("490000009Z"))
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        store.saveKeepingJourneys(snapshot())
        assertEquals(now.plusSeconds(60), store.load()!!.fetchedAt)
    }

    @Test
    fun `saveKeepingJourneys keeps a stop's newer stored arrivals, and never writes the pins`() = runTest {
        val journeys = listOf(WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "k"))
        val newer = snapshot().let { s ->
            s.copy(stops = s.stops.map { it.copy(fetchedAt = now.plusSeconds(120)) }, fetchedAt = now.plusSeconds(120), journeys = journeys)
        }
        val store = DataStoreSnapshotStore(FakeDataStore(newer.toPersisted()))
        // The app's older copy, saved with pins of its own.
        store.saveKeepingJourneys(snapshot().copy(journeys = emptyList()))
        val after = store.load()!!
        assertEquals(newer.stops, after.stops)
        assertEquals(journeys, after.journeys)
    }

    @Test
    fun `saveKeepingJourneys leaves out a journey-only stop no pin starts from`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().toPersisted()))
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        // Fetched later than the nearby stop: it mustn't stamp the snapshot once it's left out.
        val later = origin.copy(fetchedAt = now.plusSeconds(60))
        store.saveKeepingJourneys(
            snapshot().copy(stops = snapshot().stops + later, fetchedAt = later.fetchedAt, journeyOnlyStopIds = setOf("490000009Z")),
        )
        val after = store.load()!!
        assertEquals(snapshot().stops, after.stops)
        assertTrue(after.journeyOnlyStopIds.isEmpty())
        assertEquals(now, after.fetchedAt)
    }

    @Test
    fun `saveKeepingJourneys keeps a same-age stop the store marks as failed`() = runTest {
        val failed = snapshot().let { s -> s.copy(stops = s.stops.map { it.copy(arrivalsFresh = false) }) }
        val store = DataStoreSnapshotStore(FakeDataStore(failed.toPersisted()))
        store.saveKeepingJourneys(snapshot())
        assertEquals(failed.stops, store.load()!!.stops)
    }

    @Test
    fun `saveIfStopsMatch keeps the stored widget journeys`() = runTest {
        val journeys = listOf(WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "k"))
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().copy(journeys = journeys).toPersisted()))
        // A worker that loaded before the journey was pinned writes back no journeys.
        val refreshed = snapshot().copy(fetchedAt = now.plusSeconds(60))
        assertEquals(true, store.saveIfStopsMatch(refreshed, listOf("940GZZLUOXC")))
        assertEquals(refreshed.copy(journeys = journeys), store.load())
    }

    @Test
    fun `updateNearer sets the stored stops' places and leaves the rest`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().toPersisted()))
        val nearer = Terminating.Nearer(ids = setOf("940GZZLUKSX"), names = setOf("king's cross"))
        store.updateNearer(mapOf("940GZZLUOXC" to nearer, "940GZZLUGONE" to Terminating.Nearer(ids = setOf("x"))))
        val expected = snapshot().let { s -> s.copy(stops = s.stops.map { it.copy(nearer = nearer) }) }
        assertEquals(expected, store.load())
    }

    @Test
    fun `saveIfStopsMatch keeps each stop's stored nearer places`() = runTest {
        // The app has since saved the stop with the rider's new nearer places; a worker that loaded
        // the old ones writes them back, and they must not win.
        val current = Terminating.Nearer(ids = setOf("940GZZLUKSX"))
        val stale = Terminating.Nearer(ids = setOf("940GZZLUBND"))
        val withNearer = { n: Terminating.Nearer, s: DeparturesSnapshot -> s.copy(stops = s.stops.map { it.copy(nearer = n) }) }
        val store = DataStoreSnapshotStore(FakeDataStore(withNearer(current, snapshot()).toPersisted()))
        val refreshed = withNearer(stale, snapshot().copy(fetchedAt = now.plusSeconds(60)))
        assertEquals(true, store.saveIfStopsMatch(refreshed, listOf("940GZZLUOXC")))
        assertEquals(withNearer(current, refreshed), store.load())
    }

    @Test
    fun `saveIfStopsMatch discards and keeps the stored snapshot when the set differs`() = runTest {
        // The store now holds a different (relocated) set than the one the caller worked from.
        val store = DataStoreSnapshotStore(FakeDataStore(relocated().toPersisted()))
        val staleResult = snapshot().copy(fetchedAt = now.plusSeconds(60))
        val applied = store.saveIfStopsMatch(staleResult, listOf("940GZZLUOXC"))
        assertEquals(false, applied)
        // The newer relocated snapshot is untouched — the stale result was dropped.
        assertEquals(relocated(), store.load())
    }

    @Test
    fun `saveIfStopsMatch discards when nothing is stored`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val applied = store.saveIfStopsMatch(snapshot(), listOf("940GZZLUOXC"))
        assertEquals(false, applied)
        assertNull(store.load())
    }

    /** Two stops at different ages — Oxford Circus is the freshest, King's Cross is a minute older. */
    private fun twoStopSnapshot() = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                stopId = "940GZZLUOXC",
                stopName = "Oxford Circus",
                departures = listOf(
                    Departure("victoria", "Victoria", "inbound", "Brixton", null, now.plusSeconds(180), "tube"),
                ),
                fetchedAt = now,
            ),
            StopArrivals(
                stopId = "940GZZLUKSX",
                stopName = "King's Cross",
                departures = listOf(
                    Departure("northern", "Northern", "southbound", "Morden", null, now.plusSeconds(120), "tube"),
                ),
                fetchedAt = now.minusSeconds(60),
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `pruneStops removes the departed stop and re-derives the stamp from the rest`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(twoStopSnapshot().toPersisted()))
        store.pruneStops(listOf("940GZZLUOXC"))
        val loaded = store.load()!!
        assertEquals(listOf("940GZZLUKSX"), loaded.stops.map { it.stopId })
        // The whole-snapshot stamp drops to the freshest remaining stop — Oxford Circus was the
        // newest, so removing it ages the snapshot's stamp to King's Cross's.
        assertEquals(now.minusSeconds(60), loaded.fetchedAt)
    }

    @Test
    fun `pruneStops leaves the snapshot untouched when no id is present`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().toPersisted()))
        store.pruneStops(listOf("940GZZLUKSX")) // not in the stored single-stop set
        assertEquals(snapshot(), store.load())
    }

    @Test
    fun `pruneStops is a no-op when nothing is stored`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        store.pruneStops(listOf("940GZZLUOXC"))
        assertNull(store.load())
    }

    @Test
    fun `missing stops round-trip, and a stop the store holds isn't missing`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        store.save(snapshot().copy(missingStopIds = setOf("940GZZLUKSX")))
        assertEquals(setOf("940GZZLUKSX"), store.load()!!.missingStopIds)
        // A later save that names the held stop as missing (a narrower fetch) keeps its copy instead.
        store.saveKeepingJourneys(snapshot().copy(missingStopIds = setOf("940GZZLUOXC", "940GZZLUKSX")))
        assertEquals(setOf("940GZZLUKSX"), store.load()!!.missingStopIds)
    }

    @Test
    fun `a departed stop is no longer missing`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        store.save(snapshot().copy(missingStopIds = setOf("940GZZLUKSX", "940GZZLUBND")))
        store.pruneStops(listOf("940GZZLUKSX"))
        assertEquals(setOf("940GZZLUBND"), store.load()!!.missingStopIds)
    }

    @Test
    fun `an older worker result can't clear the app's newer missing stops`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        // The app stored the same stops, now with one it couldn't get.
        store.save(snapshot().copy(missingStopIds = setOf("940GZZLUKSX")))
        // A worker that loaded before that write saves its result over the same stop set.
        val applied = store.saveIfStopsMatch(snapshot(), snapshot().stops.map { it.stopId })
        assertTrue(applied)
        assertEquals(setOf("940GZZLUKSX"), store.load()!!.missingStopIds)
    }

    @Test
    fun `a pinned origin that's also nearby, carried from its stored copy, is unrefreshed, not missing or journey-only`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        val pin = WidgetJourney("940GZZLUKSX", setOf(JourneyCall("victoria", "Brixton", null)), key = "j")
        val origin = StopArrivals("940GZZLUKSX", "King's Cross St. Pancras", emptyList(), now.minusSeconds(120))
        store.save(snapshot().copy(stops = snapshot().stops + origin, journeys = listOf(pin)))
        // After a restart the origin is nearby, but its fetch failed with nothing in memory.
        store.saveKeepingJourneys(snapshot().copy(missingStopIds = setOf("940GZZLUKSX")))
        val after = store.load()!!
        // Its refresh failed, so the widget flags it rather than showing it as live.
        assertFalse(after.stops.single { it.stopId == "940GZZLUKSX" }.arrivalsFresh)
        assertEquals(emptySet<String>(), after.missingStopIds)
        assertTrue("940GZZLUKSX" !in after.journeyOnlyStopIds)
    }

    private fun check(severity: Int, at: Instant) =
        LineStatusCheck(LineStatus("victoria", severity, if (severity == LineStatus.GOOD_SERVICE) "Good Service" else "Severe Delays"), at)

    @Test
    fun `saveIfStopsMatch keeps the app's newer line check over the worker's older one`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        // The app checked at +60 s: good service.
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to check(LineStatus.GOOD_SERVICE, now.plusSeconds(60)))))
        // A worker that loaded earlier comes back with its older "severe" verdict.
        val worker = snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now)))
        assertTrue(store.saveIfStopsMatch(worker, listOf("940GZZLUOXC")))
        assertEquals(LineStatus.GOOD_SERVICE, store.load()!!.lineStatuses.getValue("victoria").status.severity)
        // And a newer worker verdict replaces the app's.
        val newer = snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now.plusSeconds(120))))
        assertTrue(store.saveIfStopsMatch(newer, listOf("940GZZLUOXC")))
        assertEquals(6, store.load()!!.lineStatuses.getValue("victoria").status.severity)
    }

    @Test
    fun `saveKeepingJourneys keeps each line's newest check`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now.plusSeconds(60)))))
        store.saveKeepingJourneys(snapshot().copy(lineStatuses = mapOf("victoria" to check(LineStatus.GOOD_SERVICE, now))))
        assertEquals(now.plusSeconds(60), store.load()!!.lineStatuses.getValue("victoria").checkedAt)
    }

    @Test
    fun `pruning a stop drops the checks for lines only it showed`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        val other = StopArrivals(
            "940GZZLUKSX", "King's Cross St. Pancras",
            listOf(Departure("northern", "Northern", "inbound", "Morden", null, now.plusSeconds(60), "tube")), now,
        )
        store.save(
            snapshot().copy(
                stops = snapshot().stops + other,
                lineStatuses = mapOf(
                    "victoria" to check(6, now),
                    "northern" to check(6, now).let { it.copy(status = it.status.copy(lineId = "northern")) },
                ),
            ),
        )
        store.pruneStops(listOf("940GZZLUKSX"))
        assertEquals(setOf("victoria"), store.load()!!.lineStatuses.keys)
    }

    @Test
    fun `a journey report keeps the stored line checks`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now))))
        val pin = WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), key = "j1")
        store.updateWidgetJourneys(pinning(pin), emptyList())
        assertEquals(setOf("victoria"), store.load()!!.lineStatuses.keys)
    }

    @Test
    fun `a worker's newer no-verdict check replaces the stored disruption`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing, clock = { now.plusSeconds(200) })
        store.save(snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now))))
        val worker = snapshot().copy(lineStatuses = mapOf("victoria" to LineStatusCheck.noVerdict("victoria", now.plusSeconds(120))))
        assertTrue(store.saveIfStopsMatch(worker, listOf("940GZZLUOXC")))
        val stored = store.load()!!
        assertFalse(stored.lineStatuses.getValue("victoria").known)
        assertTrue(stored.liveLineStatuses(now.plusSeconds(130)).isEmpty())
    }
}
