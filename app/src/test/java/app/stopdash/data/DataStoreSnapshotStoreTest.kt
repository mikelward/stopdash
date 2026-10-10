package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.FoldChoice
import app.stopdash.domain.Departure
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.Staleness
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.WidgetJourney
import app.stopdash.domain.WidgetJourneyCheck
import app.stopdash.domain.WidgetJourneysReport
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.Terminating
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
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
        override val data: Flow<PersistedSnapshot?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedSnapshot?) -> PersistedSnapshot?,
        ): PersistedSnapshot? = transform(state.value).also { state.value = it }
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
    fun `saveFollowedIfUnchanged stores the followed place's order, unless the app stored another`() = runTest {
        val loaded = snapshot().copy(nearestFirst = listOf("940GZZLUOXC"))
        val backing = FakeDataStore(loaded.toPersisted())
        val store = DataStoreSnapshotStore(backing)
        val waterloo = snapshot().stops.first().copy(stopId = "940GZZLUWLO", stopName = "Waterloo", fetchedAt = now.plusSeconds(60))
        val followed = snapshot().copy(stops = listOf(waterloo), fetchedAt = now.plusSeconds(60), nearestFirst = listOf("940GZZLUWLO"), missingStopIds = setOf("940GZZLUEMB"))
        // The app stored another place meanwhile: refused, and nothing changes.
        assertFalse(store.saveFollowedIfUnchanged(followed, loaded.copy(stops = loaded.stops.map { it.copy(stopId = "940GZZLUKSX") })))
        assertEquals(listOf("940GZZLUOXC"), store.load()!!.nearestFirst)
        assertTrue(store.saveFollowedIfUnchanged(followed, loaded))
        val after = store.load()!!
        assertEquals(listOf("940GZZLUWLO"), after.stops.map { it.stopId })
        // Unlike a refresh of the same stops, the order and the missing stops are the follow's own.
        assertEquals(listOf("940GZZLUWLO"), after.nearestFirst)
        assertEquals(setOf("940GZZLUEMB"), after.missingStopIds)
    }

    @Test
    fun `a refresh saved where nothing moved stores the follow's new line choices, which saveIfStopsMatch keeps out`() = runTest {
        val loaded = snapshot().copy(nearestFirst = snapshot().stops.map { it.stopId })
        val store = DataStoreSnapshotStore(FakeDataStore(loaded.toPersisted()))
        val chosen = loaded.copy(nearbyChoices = listOf(FoldChoice("victoria", "outbound", loaded.stops.first().stopId)))
        // The plain conditional save keeps the stored choices: they're the app's.
        assertTrue(store.saveIfStopsMatch(chosen, loaded.stops.map { it.stopId }))
        assertEquals(emptyList<FoldChoice>(), store.load()!!.nearbyChoices)
        // Over the layout it loaded, the follow's save stores them.
        assertTrue(store.saveFollowedIfUnchanged(chosen, store.load()!!))
        assertEquals(chosen.nearbyChoices, store.load()!!.nearbyChoices)
    }

    @Test
    fun `a follow's choices are worked out from the rows the write keeps, fresher stored ones included`() = runTest {
        val loaded = snapshot()
        // The app refreshed the same stops meanwhile, layout unchanged: their arrivals are newer.
        val fresher = loaded.copy(stops = loaded.stops.map { it.copy(fetchedAt = now.plusSeconds(120)) })
        val store = DataStoreSnapshotStore(FakeDataStore(fresher.toPersisted()))
        var workedFrom: List<java.time.Instant>? = null
        val chosen = FoldChoice("victoria", "outbound", loaded.stops.first().stopId)
        assertTrue(
            store.saveFollowedIfUnchanged(loaded, loaded) { kept ->
                workedFrom = kept.stops.map { it.fetchedAt }
                listOf(chosen)
            },
        )
        // From the fresher rows the write kept, not the caller's older ones, and stored with them.
        assertEquals(fresher.stops.map { it.fetchedAt }, workedFrom)
        assertEquals(listOf(chosen), store.load()!!.nearbyChoices)
    }

    @Test
    fun `saveFollowedIfUnchanged keeps fresher stored arrivals but takes the follow's layout`() = runTest {
        val loaded = snapshot()
        // The app refreshed the same stops meanwhile, layout unchanged: their arrivals are newer.
        val fresher = loaded.copy(stops = loaded.stops.map { it.copy(fetchedAt = now.plusSeconds(120)) })
        val store = DataStoreSnapshotStore(FakeDataStore(fresher.toPersisted()))
        // The follow, older arrivals but each stop's nearer places from the new position.
        val followed = loaded.copy(stops = loaded.stops.map { it.copy(nearer = Terminating.Nearer(ids = setOf("940GZZLUWLO"))) })
        assertTrue(store.saveFollowedIfUnchanged(followed, loaded))
        val after = store.load()!!
        assertEquals(fresher.stops.map { it.fetchedAt }, after.stops.map { it.fetchedAt })
        assertEquals(followed.stops.map { it.nearer }, after.stops.map { it.nearer })
    }

    @Test
    fun `saveFollowedIfUnchanged gives way to the app laying the same stops out anew`() = runTest {
        val loaded = snapshot().copy(nearestFirst = listOf("940GZZLUOXC"))
        // The app moved meanwhile and stored the same stops with new nearer places.
        val relaid = loaded.copy(stops = loaded.stops.map { it.copy(nearer = Terminating.Nearer(ids = setOf(it.stopId))) })
        val store = DataStoreSnapshotStore(FakeDataStore(relaid.toPersisted()))
        val followed = loaded.copy(fetchedAt = now.plusSeconds(60))
        assertFalse(store.saveFollowedIfUnchanged(followed, loaded))
        assertEquals(relaid.stops.map { it.nearer }, store.load()!!.stops.map { it.nearer })
        // Nor does a new order.
        val reordered = DataStoreSnapshotStore(FakeDataStore(loaded.copy(nearestFirst = emptyList()).toPersisted()))
        assertFalse(reordered.saveFollowedIfUnchanged(followed, loaded))
        // Nor do the app's per-line stop choices, saved meanwhile.
        val chose = DataStoreSnapshotStore(FakeDataStore(loaded.copy(nearbyChoices = listOf(FoldChoice("victoria", "inbound", "940GZZLUOXC"))).toPersisted()))
        assertFalse(chose.saveFollowedIfUnchanged(followed, loaded))
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
    fun `saveIfStopsMatch keeps a stop the app stored newer, as every other write does`() = runTest {
        val loaded = snapshot()
        // The app refreshed the same stops meanwhile: their arrivals are newer than this caller's.
        val fresher = loaded.copy(stops = loaded.stops.map { it.copy(fetchedAt = now.plusSeconds(120)) })
        val store = DataStoreSnapshotStore(FakeDataStore(fresher.toPersisted()))
        assertTrue(store.saveIfStopsMatch(loaded, loaded.stops.map { it.stopId }))
        assertEquals(fresher.stops.map { it.fetchedAt }, store.load()!!.stops.map { it.fetchedAt })
        // A caller's newer copy still replaces an older stored one.
        val newest = loaded.copy(stops = loaded.stops.map { it.copy(fetchedAt = now.plusSeconds(300)) })
        assertTrue(store.saveIfStopsMatch(newest, loaded.stops.map { it.stopId }))
        assertEquals(newest.stops.map { it.fetchedAt }, store.load()!!.stops.map { it.fetchedAt })
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
    fun `the nearest-first order round-trips`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val ordered = twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUKSX", "940GZZLUOXC"))
        store.save(ordered)
        assertEquals(ordered, store.load())
    }

    @Test
    fun `saveIfStopsMatch keeps the app's nearest-first order`() = runTest {
        // Only the app knows where the rider is: a worker's result, with an order from an older load
        // (or none), must not replace the one the app saved since.
        val store = DataStoreSnapshotStore(FakeDataStore(twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUKSX", "940GZZLUOXC")).toPersisted()))
        val refreshed = twoStopSnapshot().copy(fetchedAt = now.plusSeconds(60), nearestFirst = listOf("940GZZLUOXC", "940GZZLUKSX"))
        assertEquals(true, store.saveIfStopsMatch(refreshed, listOf("940GZZLUOXC", "940GZZLUKSX")))
        assertEquals(listOf("940GZZLUKSX", "940GZZLUOXC"), store.load()!!.nearestFirst)
    }

    @Test
    fun `saveKeepingJourneys takes the caller's nearest-first order`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUOXC", "940GZZLUKSX")).toPersisted()))
        store.saveKeepingJourneys(twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUKSX", "940GZZLUOXC")))
        assertEquals(listOf("940GZZLUKSX", "940GZZLUOXC"), store.load()!!.nearestFirst)
    }

    @Test
    fun `updateNearestFirst stores the order for the nearby stops held`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUOXC", "940GZZLUKSX")).toPersisted()))
        store.updateNearestFirst(listOf("940GZZLUGONE", "940GZZLUKSX", "940GZZLUOXC"))
        assertEquals(listOf("940GZZLUKSX", "940GZZLUOXC"), store.load()!!.nearestFirst)
    }

    @Test
    fun `an order for another set ranks the stops it shares, the rest after`() = runTest {
        val stored = twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUOXC", "940GZZLUKSX"))
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        // A move to a set sharing only one of the stored stops, before its first save: the shared
        // stop goes first, as it's nearer now; the other keeps its place after it.
        store.updateNearestFirst(listOf("940GZZLUKSX", "940GZZLUVIC"))
        assertEquals(listOf("940GZZLUKSX", "940GZZLUOXC"), store.load()!!.nearestFirst)
        // Nothing shared: the stored order stands.
        store.updateNearestFirst(listOf("940GZZLUVIC"))
        assertEquals(listOf("940GZZLUKSX", "940GZZLUOXC"), store.load()!!.nearestFirst)
    }

    @Test
    fun `a journey-only stop the new order names is ranked`() = runTest {
        // The rider moved to a pinned journey's origin before the new set's first save.
        val stored = twoStopSnapshot().copy(
            journeyOnlyStopIds = setOf("940GZZLUOXC"),
            nearestFirst = listOf("940GZZLUKSX"),
        )
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        store.updateNearestFirst(listOf("940GZZLUOXC", "940GZZLUKSX"))
        assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), store.load()!!.nearestFirst)
    }

    private val atKsx = FoldChoice("victoria", "inbound", "940GZZLUKSX")
    private val atOxc = FoldChoice("victoria", "inbound", "940GZZLUOXC")

    @Test
    fun `the line choices round-trip with the snapshot`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        store.saveKeepingJourneys(twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUKSX", "940GZZLUOXC"), nearbyChoices = listOf(atKsx)))
        assertEquals(listOf(atKsx), store.load()!!.nearbyChoices)
    }

    @Test
    fun `updateNearestFirst stores new line choices for the stops it ranks`() = runTest {
        val stored = twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUOXC", "940GZZLUKSX"), nearbyChoices = listOf(atOxc))
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        var workedFrom: List<String>? = null
        store.updateNearestFirst(listOf("940GZZLUKSX", "940GZZLUOXC")) { held ->
            workedFrom = held.stops.map { it.stopId }
            listOf(atKsx, FoldChoice("central", "outbound", "940GZZLUGONE"))
        }
        // Worked out from the stored rows, and kept only for the stops the order ranks.
        assertEquals(stored.stops.map { it.stopId }, workedFrom)
        assertEquals(listOf(atKsx), store.load()!!.nearbyChoices)
    }

    @Test
    fun `an order with no choices clears the stored ones, even an unchanged order`() = runTest {
        val stored = twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUOXC", "940GZZLUKSX"), nearbyChoices = listOf(atOxc))
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        // Worked out for where the rider was: without rows to work them out again, they go.
        store.updateNearestFirst(listOf("940GZZLUOXC", "940GZZLUKSX"))
        assertEquals(emptyList<FoldChoice>(), store.load()!!.nearbyChoices)
        assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), store.load()!!.nearestFirst)
    }

    @Test
    fun `a pruned stop's line choices go with it`() = runTest {
        val stored = twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUOXC", "940GZZLUKSX"), nearbyChoices = listOf(atOxc, FoldChoice("northern", "outbound", "940GZZLUKSX")))
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        store.pruneStops(listOf("940GZZLUOXC"))
        assertEquals(listOf(FoldChoice("northern", "outbound", "940GZZLUKSX")), store.load()!!.nearbyChoices)
    }

    @Test
    fun `updateNearestFirst leaves a newer build's snapshot alone`() = runTest {
        val newer = twoStopSnapshot().toPersisted().copy(version = PersistedSnapshot.CURRENT_VERSION + 1)
        val backing = FakeDataStore(newer)
        DataStoreSnapshotStore(backing).updateNearestFirst(listOf("940GZZLUKSX", "940GZZLUOXC"))
        assertEquals(newer, backing.state.value)
    }

    @Test
    fun `a pruned stop leaves the nearest-first order`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(twoStopSnapshot().copy(nearestFirst = listOf("940GZZLUOXC", "940GZZLUKSX")).toPersisted()))
        store.pruneStops(listOf("940GZZLUOXC"))
        assertEquals(listOf("940GZZLUKSX"), store.load()!!.nearestFirst)
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
    fun `line checks stored alone keep the arrivals and each line's newest check`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        // Nothing stored: nothing to merge into.
        store.updateLineStatuses(mapOf("victoria" to check(6, now)))
        assertNull(store.load())
        val stored = snapshot().copy(lineStatuses = mapOf("victoria" to check(LineStatus.GOOD_SERVICE, now)))
        store.save(stored)
        // A newer check lands during an arrivals outage: its verdict is stored, the arrivals left as
        // they were; a line no stop shows isn't.
        val suspended = LineStatusCheck(LineStatus("victoria", 20, "Suspended"), now.plusSeconds(60))
        val elsewhere = LineStatusCheck(LineStatus("central", 20, "Suspended"), now.plusSeconds(60))
        store.updateLineStatuses(mapOf("victoria" to suspended, "central" to elsewhere))
        val updated = store.load()!!
        assertEquals(stored.stops, updated.stops)
        assertEquals(setOf("victoria"), updated.lineStatuses.keys)
        assertEquals(20, updated.lineStatuses.getValue("victoria").status.severity)
        // An older one doesn't replace it.
        store.updateLineStatuses(mapOf("victoria" to check(LineStatus.GOOD_SERVICE, now.plusSeconds(30))))
        assertEquals(now.plusSeconds(60), store.load()!!.lineStatuses.getValue("victoria").checkedAt)
    }

    @Test
    fun `line checks stored alone work the line choices out again over the layout the caller saw`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val seen = snapshot().copy(lineStatuses = mapOf("victoria" to check(LineStatus.GOOD_SERVICE, now)))
        store.save(seen)
        val suspended = LineStatusCheck(LineStatus("victoria", 20, "Suspended"), now.plusSeconds(60))
        var severity: Int? = null
        val chosen = FoldChoice("victoria", "outbound", snapshot().stops.first().stopId)
        store.updateLineStatuses(mapOf("victoria" to suspended), seen) { merged ->
            severity = merged.lineStatuses.getValue("victoria").status.severity
            listOf(chosen)
        }
        // Worked out from the merged checks, and stored with them.
        assertEquals(20, severity)
        assertEquals(listOf(chosen), store.load()!!.nearbyChoices)
    }

    @Test
    fun `line checks stored alone leave the choices of a layout laid out since`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val seen = snapshot().copy(lineStatuses = mapOf("victoria" to check(LineStatus.GOOD_SERVICE, now)))
        // The app laid the same stops out since, from a newer position: its own order and choices.
        val ids = snapshot().stops.map { it.stopId }
        val appChoice = FoldChoice("victoria", "outbound", ids.first())
        store.save(seen.copy(nearestFirst = ids, nearbyChoices = listOf(appChoice)))
        val suspended = LineStatusCheck(LineStatus("victoria", 20, "Suspended"), now.plusSeconds(60))
        store.updateLineStatuses(mapOf("victoria" to suspended), seen) { error("not over a layout laid out since") }
        val stored = store.load()!!
        assertEquals(20, stored.lineStatuses.getValue("victoria").status.severity)
        assertEquals(listOf(appChoice), stored.nearbyChoices)
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

    @Test
    fun `the first write after the clock is set back distrusts what it stamped ahead`() = runTest {
        // Stored before the clock went back an hour: a stop and a line check stamped at [now].
        val backing = FakeDataStore(snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now))).toPersisted())
        val back = now.minusSeconds(3600)
        val store = DataStoreSnapshotStore(backing, clock = { back })
        // Any write records it: here, only the rider's nearer places change.
        store.updateNearer(mapOf("940GZZLUOXC" to Terminating.Nearer(setOf("940GZZLUKSX"), setOf("King's Cross"))))
        val stored = store.load()!!
        val stop = stored.stops.single()
        // Stale from now on, and unrefreshed, so it stays so once the clock passes its old stamp.
        assertEquals(back.minus(java.time.Duration.ofMinutes(5)), stop.fetchedAt)
        assertFalse(stop.arrivalsFresh)
        assertEquals(stop.fetchedAt, stored.fetchedAt)
        // Its departures are still there, to be shown as stale ones are.
        assertEquals(snapshot().stops.single().departures, stop.departures)
        // The line check is gone: the line reads unchecked rather than disrupted on an unknown age.
        assertTrue(stored.lineStatuses.isEmpty())
    }

    @Test
    fun `a stamp a moment ahead of the clock is kept as it is`() = runTest {
        // Within the margin for a screen's tick or another device's clock: not a rollback.
        val stored = snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now)))
        val backing = FakeDataStore(stored.toPersisted())
        val store = DataStoreSnapshotStore(backing, clock = { now.minusSeconds(30) })
        store.updateNearer(mapOf("940GZZLUOXC" to Terminating.Nearer(emptySet(), emptySet())))
        val kept = store.load()!!
        assertEquals(stored.stops.map { it.fetchedAt to it.arrivalsFresh }, kept.stops.map { it.fetchedAt to it.arrivalsFresh })
        assertEquals(setOf("victoria"), kept.lineStatuses.keys)
    }

    @Test
    fun `a fresh fetch replaces a stop stamped from before the clock was set back`() = runTest {
        val backing = FakeDataStore(snapshot().toPersisted())
        val back = now.minusSeconds(3600)
        val store = DataStoreSnapshotStore(backing, clock = { back })
        // The app's fetch made since, stamped by the clock as it now reads: earlier than the stored stamp.
        val fresh = StopArrivals(
            "940GZZLUOXC", "Oxford Circus",
            listOf(Departure("victoria", "Victoria", "inbound", "Walthamstow Central", null, back.plusSeconds(120), "tube")),
            back,
        )
        store.saveKeepingJourneys(DeparturesSnapshot(listOf(fresh), back))
        val stop = store.load()!!.stops.single()
        assertEquals(back, stop.fetchedAt)
        assertEquals(listOf("Walthamstow Central"), stop.departures.map { it.destination })
        assertTrue(stop.arrivalsFresh)
    }

    @Test
    fun `a journey origin stamped from before the clock was set back doesn't replace a fresher stored one`() = runTest {
        val back = now.minusSeconds(3600)
        val stored = snapshot().copy(stops = snapshot().stops.map { it.copy(fetchedAt = back) }, fetchedAt = back)
        val backing = FakeDataStore(stored.toPersisted())
        val store = DataStoreSnapshotStore(backing, clock = { back.plusSeconds(10) })
        val pin = WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), key = "j1")
        // The app's copy of the origin was fetched before the clock went back: it looks newer.
        val ahead = snapshot().stops.single().copy(departures = emptyList(), fetchedAt = now)
        store.updateWidgetJourneys(pinning(pin), listOf(ahead))
        val stop = store.load()!!.stops.single()
        assertEquals(back, stop.fetchedAt)
        assertEquals(stored.stops.single().departures, stop.departures)
    }

    @Test
    fun `a worker's result carrying a stop from before the clock was set back still counts as written`() = runTest {
        val back = now.minusSeconds(3600)
        val backing = FakeDataStore(snapshot().toPersisted())
        val store = DataStoreSnapshotStore(backing, clock = { back })
        // Its fetch of the stop failed, so it carries the stored copy on, stamp and all.
        assertTrue(store.saveIfStopsMatch(snapshot().copy(stops = snapshot().stops.map { it.copy(arrivalsFresh = false) }), listOf("940GZZLUOXC")))
        assertEquals(back.minus(java.time.Duration.ofMinutes(5)), store.load()!!.stops.single().fetchedAt)
    }

    // This process's clock frame, the wall clock set by [setBack] since it started.
    private class Steady(override val frame: SteadyClock.Frame?, private val setBack: java.time.Duration = java.time.Duration.ZERO) : SteadyClock.Source {
        override fun offset(): java.time.Duration = setBack
    }

    @After
    fun resetSteadyClock() {
        SteadyClock.source = null
    }

    @Test
    fun `a snapshot read after the clock was set back is as old as it is, with no write`() = runTest {
        // Written by a process of boot 7, a stop fetched at [now].
        val written = snapshot().toPersisted().copy(stampFrame = PersistedFrame("device/7", 0L))
        // Read by a later process of the same boot, started after the clock was set back an hour.
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", -3_600_000L))
        val stop = DataStoreSnapshotStore(FakeDataStore(written)).load()!!.stops.single()
        assertEquals(now.minusSeconds(3600), stop.fetchedAt)
        // Two minutes after the fetch, as the clock now reads it, it's two minutes old...
        assertFalse(Staleness.isStale(stop.fetchedAt, now.minusSeconds(3600 - 120)))
        // ...and once the clock is back past its old stamp, an hour old: not new again (Codex, PR #371).
        assertTrue(Staleness.isStale(stop.fetchedAt, now.plusSeconds(60)))
        assertTrue(Staleness.isStale(stop.fetchedAt, now.minusSeconds(3600 - 300)))
    }

    @Test
    fun `a write records the frame its stamps are in, and moves nothing it doesn't need to`() = runTest {
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", 1_000L))
        val backing = FakeDataStore(null)
        DataStoreSnapshotStore(backing, clock = { now }).save(snapshot())
        assertEquals(PersistedFrame("device/7", 1_000L), backing.state.value!!.stampFrame)
        // As the format whose stamps, line checks' too, are the steady clock's, which an older build
        // won't misread.
        assertEquals(4, backing.state.value!!.version)
        assertEquals(now.toEpochMilli(), backing.state.value!!.stops.single().fetchedAtMillis)
        // Read back in the same process, as it was written.
        assertEquals(snapshot(), DataStoreSnapshotStore(backing, clock = { now }).load())
    }

    @Test
    fun `a stop fetched before the clock was set back in this process is kept as fresh as it is`() = runTest {
        // Fetched, and stamped by the steady clock, before the clock went back an hour: so its stamp
        // is an hour ahead of the wall clock, but this process's clock says it's a minute old.
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", 0L), setBack = java.time.Duration.ofHours(1))
        val back = now.minusSeconds(3600 - 60)
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing, clock = { back })
        store.save(snapshot())
        val stop = store.load()!!.stops.single()
        // Not restamped stale as one from before the clock was set back would be.
        assertEquals(now, stop.fetchedAt)
        assertTrue(stop.arrivalsFresh)
        assertFalse(Staleness.isStale(stop.fetchedAt, back))
    }

    @Test
    fun `a snapshot from an earlier boot is read as the wall clock's, up to the boot's start`() = runTest {
        val written = snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now))).toPersisted().copy(stampFrame = PersistedFrame("device/6", 0L))
        // This boot started ten minutes after the fetch: the stop is as old as the wall clock says.
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", now.plusSeconds(600).toEpochMilli()))
        val later = DataStoreSnapshotStore(FakeDataStore(written)).load()!!
        assertEquals(now, later.stops.single().fetchedAt)
        assertEquals(setOf("victoria"), later.lineStatuses.keys)
    }

    @Test
    fun `a snapshot from before a reboot the clock was set back across stays stale`() = runTest {
        val written = snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now))).toPersisted().copy(stampFrame = PersistedFrame("device/6", 0L))
        // This boot started, by the clock as set back, an hour before the fetch: it can't be that new.
        val bootStart = now.minusSeconds(3600)
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", bootStart.toEpochMilli()))
        val read = DataStoreSnapshotStore(FakeDataStore(written)).load()!!
        val stop = read.stops.single()
        assertEquals(bootStart.minus(java.time.Duration.ofMinutes(5)), stop.fetchedAt)
        assertFalse(stop.arrivalsFresh)
        assertTrue(read.lineStatuses.isEmpty())
        // Stale from the first read, and still once the clock has caught up with the old stamp, with
        // nothing written in between (Codex, PR #371).
        assertTrue(Staleness.isStale(stop.fetchedAt, bootStart.plusSeconds(60)))
        assertTrue(Staleness.isStale(stop.fetchedAt, now.plusSeconds(60)))
    }

    @Test
    fun `a snapshot restored from another device is never taken for this boot's`() = runTest {
        // Written by another install whose boot count happens to match this one's.
        val written = snapshot().toPersisted().copy(stampFrame = PersistedFrame("other/7", 0L))
        val bootStart = now.minusSeconds(1800)
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", bootStart.toEpochMilli()))
        val stop = DataStoreSnapshotStore(FakeDataStore(written)).load()!!.stops.single()
        // Not moved by the two devices' uptimes: read as from an earlier boot, so a stamp later than
        // this boot's start is stale (Codex, PR #371).
        assertEquals(bootStart.minus(java.time.Duration.ofMinutes(5)), stop.fetchedAt)
        assertFalse(stop.arrivalsFresh)
    }

    @Test
    fun `an older build's snapshot is read as the wall clock's and written as the steady clock's`() = runTest {
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", 0L))
        val backing = FakeDataStore(snapshot().toPersisted().copy(version = 2))
        val store = DataStoreSnapshotStore(backing, clock = { now })
        assertEquals(now, store.load()!!.stops.single().fetchedAt)
        store.updateNearer(mapOf("940GZZLUOXC" to Terminating.Nearer(emptySet(), emptySet())))
        assertEquals(PersistedSnapshot.CURRENT_VERSION, backing.state.value!!.version)
        assertEquals(PersistedFrame("device/7", 0L), backing.state.value!!.stampFrame)
    }

    @Test
    fun `an older build's snapshot stamped ahead stays stale once the clock catches up`() = runTest {
        // Written by an older build, with no frame: a stop fetched at [now], and a line checked then.
        val backing = FakeDataStore(snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now))).toPersisted().copy(version = 2))
        // First read by this build after the clock was set back an hour: stale while it's ahead.
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", 0L))
        val back = now.minusSeconds(3600)
        assertTrue(Staleness.isStale(DataStoreSnapshotStore(backing, clock = { back }).load()!!.stops.single().fetchedAt, back))
        // A later read, once the clock is back past the old stamp, with nothing written in between
        // but that first read's rewrite: still stale (Codex, PR #371).
        val caughtUp = now.plusSeconds(60)
        val later = DataStoreSnapshotStore(backing, clock = { caughtUp }).load()!!
        val stop = later.stops.single()
        assertEquals(back.minus(java.time.Duration.ofMinutes(5)), stop.fetchedAt)
        assertFalse(stop.arrivalsFresh)
        assertTrue(Staleness.isStale(stop.fetchedAt, caughtUp))
        assertTrue(later.lineStatuses.isEmpty())
        assertEquals(PersistedSnapshot.CURRENT_VERSION, backing.state.value!!.version)
    }

    @Test
    fun `a snapshot written where the boot couldn't be told stays stale once the clock catches up`() = runTest {
        // This build's format, but with no frame: the device couldn't tell its boot.
        SteadyClock.source = Steady(null)
        val backing = FakeDataStore(null)
        DataStoreSnapshotStore(backing, clock = { now }).save(snapshot())
        assertNull(backing.state.value!!.stampFrame)
        // Read by a later process after the clock was set back an hour, then once it's caught up.
        val back = now.minusSeconds(3600)
        assertTrue(Staleness.isStale(DataStoreSnapshotStore(backing, clock = { back }).load()!!.stops.single().fetchedAt, back))
        val caughtUp = now.plusSeconds(60)
        val stop = DataStoreSnapshotStore(backing, clock = { caughtUp }).load()!!.stops.single()
        // Still stale, from the first read's rewrite (Codex, PR #371).
        assertEquals(back.minus(java.time.Duration.ofMinutes(5)), stop.fetchedAt)
        assertTrue(Staleness.isStale(stop.fetchedAt, caughtUp))
    }

    @Test
    fun `an older build's snapshot that can't be rewritten stays distrusted, and the failure is logged`() = runTest {
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", 0L))
        val failing = object : DataStore<PersistedSnapshot?> {
            override val data: Flow<PersistedSnapshot?> = MutableStateFlow(snapshot().toPersisted().copy(version = 2))
            override suspend fun updateData(transform: suspend (t: PersistedSnapshot?) -> PersistedSnapshot?): PersistedSnapshot? =
                throw java.io.IOException("disk full")
        }
        val warned = mutableListOf<String>()
        // First read after the clock was set back an hour, then again once it's caught up.
        var clock = now.minusSeconds(3600)
        val store = DataStoreSnapshotStore(failing, clock = { clock }, warn = warned::add)
        assertTrue(Staleness.isStale(store.load()!!.stops.single().fetchedAt, clock))
        clock = now.plusSeconds(60)
        val stop = store.load()!!.stops.single()
        // Read as the failed write would have left it, so still stale (Codex, PR #371).
        assertFalse(stop.arrivalsFresh)
        assertTrue(Staleness.isStale(stop.fetchedAt, clock))
        assertEquals(listOf("snapshot rewrite failed: IOException"), warned)
    }

    @Test
    fun `a snapshot from before a reboot, found stale, stays so once the clock is set forward`() = runTest {
        val backing = FakeDataStore(snapshot().toPersisted().copy(stampFrame = PersistedFrame("device/6", 0L)))
        // First read in boot 7, which started, as the clock then read, half an hour before the stamp.
        val bootStart = now.minusSeconds(1800)
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", bootStart.toEpochMilli()))
        assertEquals(bootStart.minus(java.time.Duration.ofMinutes(5)), DataStoreSnapshotStore(backing, clock = { bootStart.plusSeconds(300) }).load()!!.stops.single().fetchedAt)
        // A later process of that boot, the clock set forward so the boot started, as it now reads,
        // at the old stamp: the stop, three minutes on, is still stale, as that first read found it
        // (Codex, PR #371).
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", now.toEpochMilli()))
        val later = now.plusSeconds(180)
        assertTrue(Staleness.isStale(DataStoreSnapshotStore(backing, clock = { later }).load()!!.stops.single().fetchedAt, later))
    }

    @Test
    fun `a line check made before the clock was set back is read at its real age`() = runTest {
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", 0L))
        val backing = FakeDataStore(null)
        DataStoreSnapshotStore(backing, clock = { now }).save(snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now))))
        // Read just after the clock was set back an hour, by a process of the same boot: the check,
        // stamped by the steady clock, is as new as it is, not dropped as one from the future.
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", -3_600_000L))
        val back = now.minusSeconds(3600)
        assertTrue(DataStoreSnapshotStore(backing, clock = { back }).load()!!.lineStatuses.getValue("victoria").isLive(back))
        // Once the clock has caught up with its old stamp, it's an hour old: not live again.
        val caughtUp = now.plusSeconds(60)
        assertFalse(DataStoreSnapshotStore(backing, clock = { caughtUp }).load()!!.lineStatuses.getValue("victoria").isLive(caughtUp))
    }

    @Test
    fun `a line check stamped ahead is dropped for good at the first read`() = runTest {
        // Written where the boot couldn't be told, an hour ahead of the clock that reads it: from before
        // the clock was set back, so its age can't be told.
        val written = snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now.plusSeconds(3600)))).toPersisted()
        val backing = FakeDataStore(written)
        assertTrue(DataStoreSnapshotStore(backing, clock = { now }).load()!!.lineStatuses.isEmpty())
        // Nor does it come back into trust once the clock has caught up (Codex, PR #371).
        assertTrue(DataStoreSnapshotStore(backing, clock = { now.plusSeconds(3660) }).load()!!.lineStatuses.isEmpty())
    }

    @Test
    fun `an older build's line checks, stamped by the wall clock, are taken into the steady frame`() = runTest {
        // Version 3 stamped fetches by the steady clock but line checks by the wall clock.
        val written = snapshot().copy(lineStatuses = mapOf("victoria" to check(6, now))).toPersisted()
            .copy(version = 3, stampFrame = PersistedFrame("device/7", 0L))
        // This process's clock was set back an hour since it started: the check, a minute old by the
        // wall clock, is a minute old in the steady frame too.
        SteadyClock.source = Steady(SteadyClock.Frame("device/7", 0L), setBack = java.time.Duration.ofHours(1))
        val backing = FakeDataStore(written)
        val read = DataStoreSnapshotStore(backing, clock = { now.plusSeconds(60) }).load()!!.lineStatuses.getValue("victoria")
        assertEquals(now.plus(java.time.Duration.ofHours(1)), read.checkedAt)
        assertTrue(read.isLive(now.plusSeconds(60)))
        // Written back as the current format, so it isn't taken in again.
        assertEquals(4, backing.state.value!!.version)
        assertEquals(now.plus(java.time.Duration.ofHours(1)).toEpochMilli(), backing.state.value!!.lineStatuses.single().checkedAtMillis)
    }
}
