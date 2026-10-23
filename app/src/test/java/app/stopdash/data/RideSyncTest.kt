package app.stopdash.data

import app.stopdash.domain.Sync
import app.stopdash.domain.SyncLedger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import androidx.datastore.core.DataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Favorite rides as sync entries, and back. Major stations and synthetic positions only. */
class RideSyncTest {
    private fun end(id: String, name: String) = PersistedJourneyEnd(id, name, 51.5, -0.12)

    private val oxfordCircusKingsCross = PersistedFavoriteJourney(end("940GZZLUOXC", "Oxford Circus"), end("940GZZLUKSX", "King's Cross St. Pancras"), "victoria", "Victoria", "tube")
    private val eustonWaterloo = PersistedFavoriteJourney(end("940GZZLUEUS", "Euston"), end("940GZZLUWLO", "Waterloo"), "northern", "Northern", "tube")
    private val victoriaGreenPark = PersistedFavoriteJourney(end("940GZZLUVIC", "Victoria"), end("940GZZLUGPK", "Green Park"), "victoria", "Victoria", "tube")
    private val mornings = PersistedAlertSchedule(listOf("MONDAY"), listOf(PersistedTimeWindow("07:00", "09:00")))
    private val grayed = PersistedPendingJourney(PersistedPendingEnd("place", "home-id", "Home"), PersistedPendingEnd("station", "940GZZLUEUS", "Euston"))

    private val stored = PersistedFavoriteJourneys(
        journeys = listOf(oxfordCircusKingsCross, eustonWaterloo),
        alerts = mapOf(
            "940GZZLUKSX|940GZZLUOXC>940GZZLUOXC" to mornings,
            // A ride not saved: its alert time can't be found, and isn't synced.
            "940GZZLUAAA|940GZZLUBBB>940GZZLUAAA" to mornings,
        ),
        pending = listOf(grayed),
    )

    @Test
    fun `rides are entries by segment, alert times by direction, grayed rides by their ends`() {
        val entries = checkNotNull(RideSync.entriesOf(stored))
        assertEquals(setOf("940GZZLUKSX|940GZZLUOXC", "940GZZLUEUS|940GZZLUWLO"), entries.getValue(RideSync.RIDES).keys)
        assertEquals(setOf("940GZZLUKSX|940GZZLUOXC>940GZZLUOXC"), entries.getValue(RideSync.ALERTS).keys)
        assertEquals(setOf("place:home-id|station:940GZZLUEUS"), entries.getValue(RideSync.GRAYED).keys)
    }

    @Test
    fun `a ride saved either way round is one entry`() {
        val reversed = oxfordCircusKingsCross.copy(from = oxfordCircusKingsCross.to, to = oxfordCircusKingsCross.from)
        val entries = checkNotNull(RideSync.entriesOf(PersistedFavoriteJourneys(journeys = listOf(oxfordCircusKingsCross, reversed))))
        assertEquals(1, entries.getValue(RideSync.RIDES).size)
    }

    @Test
    fun `nothing stored is no entries, and a newer build's file isn't synced`() {
        assertEquals(RideSync.KINDS.associateWith { emptyMap<String, String>() }, RideSync.entriesOf(null))
        assertNull(RideSync.entriesOf(PersistedFavoriteJourneys(version = PersistedFavoriteJourneys.CURRENT_VERSION + 1)))
    }

    @Test
    fun `applying a store's own entries leaves it as it was`() {
        val entries = checkNotNull(RideSync.entriesOf(stored))
        val applied = RideSync.applied(stored, entries)
        assertEquals(stored.journeys, applied.journeys)
        assertEquals(stored.pending, applied.pending)
        assertEquals(entries, RideSync.entriesOf(applied))
    }

    @Test
    fun `a merged view keeps rides in place, adds new ones at the end, and drops removed ones with their alert times`() {
        val entries = checkNotNull(RideSync.entriesOf(stored))
        val renamed = eustonWaterloo.copy(hiddenLines = listOf("line:89=89"))
        val merged = mapOf(
            // Oxford Circus to King's Cross removed on the other phone; Euston to Waterloo changed there; Victoria to Green Park added.
            RideSync.RIDES to mapOf(
                "940GZZLUEUS|940GZZLUWLO" to json(PersistedFavoriteJourney.serializer(), renamed),
                "940GZZLUGPK|940GZZLUVIC" to json(PersistedFavoriteJourney.serializer(), victoriaGreenPark),
            ),
            RideSync.ALERTS to entries.getValue(RideSync.ALERTS),
            RideSync.GRAYED to emptyMap(),
        )
        val applied = RideSync.applied(stored, merged)
        assertEquals(listOf(renamed, victoriaGreenPark), applied.journeys)
        // The removed ride's alert times go with it.
        assertEquals(emptyMap<String, PersistedAlertSchedule>(), applied.alerts)
        assertEquals(emptyList<PersistedPendingJourney>(), applied.pending)
    }

    @Test
    fun `a newer build's extra fields are the same entry, and a shape this build can't read is left alone`() {
        val ours = json(PersistedFavoriteJourney.serializer(), eustonWaterloo)
        val newer = ours.removeSuffix("}") + ""","addedLater":true}"""
        assertTrue(RideSync.same(RideSync.RIDES, newer, ours))
        assertFalse(RideSync.same(RideSync.RIDES, json(PersistedFavoriteJourney.serializer(), oxfordCircusKingsCross), ours))
        val reshaped = """{"from":"Euston","to":"Waterloo","lineId":"northern"}"""
        assertFalse(RideSync.understood(RideSync.RIDES, reshaped))
        assertTrue(RideSync.understood(RideSync.RIDES, newer))
        // Applied, an unreadable value never replaces what's stored, nor is added.
        val applied = RideSync.applied(
            stored,
            mapOf(RideSync.RIDES to mapOf("940GZZLUEUS|940GZZLUWLO" to reshaped, "940GZZLUAAA|940GZZLUBBB" to reshaped)),
        )
        assertEquals(listOf(eustonWaterloo), applied.journeys)
    }

    @Test
    fun `two phones' rides end up as one list on both`() {
        val phoneA = PersistedFavoriteJourneys(journeys = listOf(oxfordCircusKingsCross))
        val phoneB = PersistedFavoriteJourneys(journeys = listOf(eustonWaterloo))
        fun recorded(stored: PersistedFavoriteJourneys, phone: String, now: Long, seen: SyncLedger? = null): SyncLedger {
            val entries = checkNotNull(RideSync.entriesOf(stored))
            return RideSync.KINDS.fold(SyncLedger.EMPTY) { ledger, kind ->
                Sync.record(ledger, kind, entries.getValue(kind), now, phone, seen?.let { Sync.latestOf(listOf(it)) })
            }
        }
        val a = recorded(phoneA, "a", 1_000)
        val b = Sync.merge(recorded(phoneB, "b", 2_000, seen = a), listOf(a))
        val onB = RideSync.applied(phoneB, RideSync.KINDS.associateWith { Sync.valuesOf(b, it) })
        val onA = RideSync.applied(phoneA, RideSync.KINDS.associateWith { Sync.valuesOf(Sync.merge(a, listOf(b)), it) })
        assertEquals(listOf(eustonWaterloo, oxfordCircusKingsCross), onB.journeys)
        assertEquals(listOf(oxfordCircusKingsCross, eustonWaterloo), onA.journeys)
    }

    @Test
    fun `the store applies a sync only while it holds what that sync compared`() = runTest {
        val data = FakeDataStore(stored)
        val store = DataStoreFavoriteJourneysStore(data)
        val expected = checkNotNull(store.syncEntries())
        val merged = expected + (RideSync.RIDES to expected.getValue(RideSync.RIDES) + ("940GZZLUGPK|940GZZLUVIC" to json(PersistedFavoriteJourney.serializer(), victoriaGreenPark)))
        assertTrue(store.applySync(expected, merged, "m1"))
        assertEquals("m1", store.syncMark())
        assertEquals(listOf(oxfordCircusKingsCross, eustonWaterloo, victoriaGreenPark), data.data.first()?.journeys)
        // A ride removed here meanwhile: the sync's view is out of date, so nothing is written.
        val stale = checkNotNull(store.syncEntries())
        data.set(data.data.first()!!.copy(journeys = listOf(oxfordCircusKingsCross)))
        assertFalse(store.applySync(stale, merged, "m2"))
        // Nothing written, nor the mark.
        assertEquals("m1", store.syncMark())
        assertEquals(listOf(oxfordCircusKingsCross), data.data.first()?.journeys)
    }

    @Test
    fun `the store says each change a sync may send, and none for an edit sync doesn't carry`() = runTest {
        val data = FakeDataStore(stored)
        val store = DataStoreFavoriteJourneysStore(data, compute = UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<Map<String, Map<String, String>>?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { store.syncChanges().toList(seen) }
        // An alert time for a ride not saved isn't synced, so writing one is no change to send.
        data.set(stored.copy(alerts = stored.alerts + ("940GZZLUCCC|940GZZLUDDD>940GZZLUCCC" to mornings)))
        data.set(stored.copy(journeys = listOf(eustonWaterloo)))
        job.cancel()
        assertEquals(listOf(RideSync.entriesOf(stored), RideSync.entriesOf(stored.copy(journeys = listOf(eustonWaterloo)))), seen)
    }

    @Test
    fun `the rider's edits keep the mark of the sync that last wrote the rides`() = runTest {
        val data = FakeDataStore(stored)
        val store = DataStoreFavoriteJourneysStore(data)
        val expected = checkNotNull(store.syncEntries())
        assertTrue(store.applySync(expected, expected, "m1"))
        store.toggle(store.journeys().first()!!.first())
        store.updateAlertSchedule("940GZZLUEUS|940GZZLUWLO>940GZZLUEUS") { null }
        assertEquals("m1", store.syncMark())
    }

    private fun <T> json(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(serializer, value)

    private class FakeDataStore(initial: PersistedFavoriteJourneys?) : DataStore<PersistedFavoriteJourneys?> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<PersistedFavoriteJourneys?> = state

        fun set(value: PersistedFavoriteJourneys?) {
            state.value = value
        }

        override suspend fun updateData(
            transform: suspend (t: PersistedFavoriteJourneys?) -> PersistedFavoriteJourneys?,
        ): PersistedFavoriteJourneys? = transform(state.value).also { state.value = it }
    }
}
