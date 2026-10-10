package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyAlertSchedule
import app.stopdash.domain.JourneyAlerts
import app.stopdash.domain.PendingEnd
import app.stopdash.domain.PendingJourney
import app.stopdash.domain.SavedJourneys
import app.stopdash.domain.TimeWindow
import java.time.DayOfWeek
import java.time.LocalTime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Store mapping, toggle, versioning, and JSON round-trip. Example stations and synthetic positions. */
class DataStoreFavoriteJourneysStoreTest {
    private val journey = FavoriteJourney(
        from = JourneyEnd("940GZZLUHGT", "Highgate", 51.5, -0.12),
        to = JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras", 51.49, -0.12, areaId = "HUBKGX"),
        lineId = "northern",
        lineName = "Northern",
        mode = "tube",
    )

    private class FakeDataStore(initial: PersistedFavoriteJourneys?) : DataStore<PersistedFavoriteJourneys?> {
        private val state = MutableStateFlow(initial)
        var writes = 0
        override val data: Flow<PersistedFavoriteJourneys?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedFavoriteJourneys?) -> PersistedFavoriteJourneys?,
        ): PersistedFavoriteJourneys? = transform(state.value).also {
            writes++
            state.value = it
        }
    }

    @Test
    fun `nothing stored reads as no journeys`() = runTest {
        assertEquals(emptyList<FavoriteJourney>(), DataStoreFavoriteJourneysStore(FakeDataStore(null)).journeys().first())
    }

    @Test
    fun `toggle adds, and toggling the reverse removes`() = runTest {
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        store.toggle(journey)
        assertEquals(listOf(journey), store.journeys().first())
        store.toggle(journey.reversed())
        assertEquals(emptyList<FavoriteJourney>(), store.journeys().first())
    }

    @Test
    fun `add saves a journey once, either way round, and never removes one`() = runTest {
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        store.add(journey)
        // A second Favorite, landing before the first is read back, leaves it saved.
        store.add(journey.reversed())
        assertEquals(listOf(journey), store.journeys().first())
    }

    @Test
    fun `remove unstars a journey, either way round, and never stars one`() = runTest {
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        store.toggle(journey)
        store.remove(journey.reversed())
        assertEquals(emptyList<FavoriteJourney>(), store.journeys().first())
        // A second Remove, landing after the first's write, leaves it unstarred.
        store.remove(journey)
        assertEquals(emptyList<FavoriteJourney>(), store.journeys().first())
    }

    @Test
    fun `a newer-version file reads as unavailable and a toggle preserves it`() = runTest {
        val future = listOf(journey).toPersisted().copy(version = PersistedFavoriteJourneys.CURRENT_VERSION + 1)
        val data = FakeDataStore(future)
        val warnings = mutableListOf<String>()
        val store = DataStoreFavoriteJourneysStore(data, warn = { warnings += it })
        assertNull(store.journeys().first())
        // Its alert schedules are unreadable too, not "none set".
        assertNull(store.alertSchedules().first())
        store.toggle(journey)
        assertEquals(future, data.data.first())
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a disk read failure reads as unavailable and is logged`() = runTest {
        val failing = object : DataStore<PersistedFavoriteJourneys?> {
            override val data: Flow<PersistedFavoriteJourneys?> = flow { throw IOException("disk") }
            override suspend fun updateData(
                transform: suspend (t: PersistedFavoriteJourneys?) -> PersistedFavoriteJourneys?,
            ): PersistedFavoriteJourneys? = throw IOException("disk")
        }
        val warnings = mutableListOf<String>()
        assertNull(DataStoreFavoriteJourneysStore(failing, warn = { warnings += it }).journeys().first())
        assertEquals(listOf("favorite journeys read failed: IOException"), warnings)
    }

    @Test
    fun `the same stations starred on two lines read as one journey`() = runTest {
        val twice = listOf(journey, journey.copy(lineId = "other")).toPersisted()
        assertEquals(listOf(journey), twice.toDomain())
    }

    @Test
    fun `journeys round-trip through JSON`() = runTest {
        val out = ByteArrayOutputStream()
        FavoriteJourneysSerializer.writeTo(listOf(journey).toPersisted(), out)
        val back = FavoriteJourneysSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf(journey), back!!.toDomain())
    }

    @Test
    fun `the favorite journeys are mapped off the caller's thread`() {
        // Mapped on the store's worker, never the collector's (main) thread (AGENTS.md *Main thread: read
        // and dispatch only*): collected from a thread of its own, the stored list is read on the worker's.
        OffMainReads().use { reads ->
            val stored = PersistedFavoriteJourneys(journeys = reads.recorded(PersistedFavoriteJourney(PersistedJourneyEnd("A", "A"), PersistedJourneyEnd("B", "B"), "victoria")))
            reads.fromCaller { DataStoreFavoriteJourneysStore(reads.dataStore<PersistedFavoriteJourneys?>(stored), compute = reads.worker).journeys().first() }
            assertTrue(reads.reads.isNotEmpty())
            assertEquals(setOf(OffMainReads.WORKER), reads.reads.toSet())
        }
    }

    @Test
    fun `a favorite journey is saved off the caller's thread`() {
        // An edit maps and searches the whole stored list, and DataStore runs it in the caller's context:
        // a tap's main thread hops to the worker first (AGENTS.md *Main thread*).
        OffMainReads().use { reads ->
            val stored = PersistedFavoriteJourneys(journeys = reads.recorded(PersistedFavoriteJourney(PersistedJourneyEnd("A", "A"), PersistedJourneyEnd("B", "B"), "victoria")))
            reads.fromCaller { DataStoreFavoriteJourneysStore(reads.dataStore<PersistedFavoriteJourneys?>(stored), compute = reads.worker).add(journey) }
            assertTrue(reads.reads.isNotEmpty())
            assertEquals(setOf(OffMainReads.WORKER), reads.reads.toSet())
        }
    }

    private val fromHome = PendingJourney(PendingEnd.Place("h", "Home"), PendingEnd.Station("940GZZLUWLO", "Waterloo"))

    @Test
    fun `a grayed journey is saved once, either way round, and removed`() = runTest {
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        assertEquals(emptyList<PendingJourney>(), store.pendingJourneys().first())
        store.addPending(fromHome)
        store.addPending(PendingJourney(fromHome.to, fromHome.from))
        assertEquals(listOf(fromHome), store.pendingJourneys().first())
        // It's no favorite journey: the near-me list, widget and alerts never see it.
        assertEquals(emptyList<FavoriteJourney>(), store.journeys().first())
        store.removePending(fromHome)
        store.removePending(fromHome)
        assertEquals(emptyList<PendingJourney>(), store.pendingJourneys().first())
    }

    @Test
    fun `a journey replaces its grayed copy in one write`() = runTest {
        val grayed = PendingJourney(PendingEnd.Station("HUBKGX", "King's Cross St. Pancras"), PendingEnd.Station("940GZZLUHGT", "Highgate"))
        val data = FakeDataStore(null)
        val store = DataStoreFavoriteJourneysStore(data)
        store.addPending(grayed)
        store.addPending(fromHome)
        val before = data.writes
        store.addReplacingPending(journey, grayed)
        assertEquals(before + 1, data.writes)
        assertEquals(listOf(journey), store.journeys().first())
        // Only that pair's gray copy goes.
        assertEquals(listOf(fromHome), store.pendingJourneys().first())
    }

    @Test
    fun `both lists come from one read, so the swap reaches a reader in one emission`() = runTest {
        val grayed = PendingJourney(PendingEnd.Station("HUBKGX", "King's Cross St. Pancras"), PendingEnd.Station("940GZZLUHGT", "Highgate"))
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        store.addPending(grayed)
        assertEquals(SavedJourneys(emptyList(), listOf(grayed)), store.savedJourneys().first())
        store.addReplacingPending(journey, grayed)
        assertEquals(SavedJourneys(listOf(journey), emptyList()), store.savedJourneys().first())
    }

    @Test
    fun `a disk read failure leaves both lists unreadable`() = runTest {
        val failing = object : DataStore<PersistedFavoriteJourneys?> {
            override val data: Flow<PersistedFavoriteJourneys?> = flow { throw IOException("disk") }
            override suspend fun updateData(
                transform: suspend (t: PersistedFavoriteJourneys?) -> PersistedFavoriteJourneys?,
            ): PersistedFavoriteJourneys? = throw IOException("disk")
        }
        val warnings = mutableListOf<String>()
        assertEquals(SavedJourneys(null, null), DataStoreFavoriteJourneysStore(failing, warn = { warnings += it }).savedJourneys().first())
        assertEquals(listOf("favorite journeys read failed: IOException"), warnings)
    }

    @Test
    fun `editing the journeys or their alerts keeps the grayed ones, and the reverse`() = runTest {
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        store.addPending(fromHome)
        store.add(journey)
        val key = JourneyAlerts.directionKey(journey, journey.from.stopId)
        store.updateAlertSchedule(key) { JourneyAlertSchedule.DEFAULT }
        assertEquals(listOf(fromHome), store.pendingJourneys().first())
        store.removePending(fromHome)
        assertEquals(listOf(journey), store.journeys().first())
        assertEquals(mapOf(key to JourneyAlertSchedule.DEFAULT), store.alertSchedules().first())
    }

    @Test
    fun `grayed journeys survive the JSON round trip, and one of an unknown kind is dropped`() = runTest {
        val stations = PendingJourney(PendingEnd.Station("HUBKGX", "King's Cross St. Pancras"), PendingEnd.Station("940GZZLUCWR", "Canada Water"))
        val stored = PersistedFavoriteJourneys(
            pending = listOf(
                PersistedPendingJourney(PersistedPendingEnd("place", "h", "Home"), PersistedPendingEnd("station", "940GZZLUWLO", "Waterloo")),
                PersistedPendingJourney(PersistedPendingEnd("station", "HUBKGX", "King's Cross St. Pancras"), PersistedPendingEnd("station", "940GZZLUCWR", "Canada Water")),
                PersistedPendingJourney(PersistedPendingEnd("bus-route", "x", "X"), PersistedPendingEnd("station", "940GZZLUWLO", "Waterloo")),
            ),
        )
        val out = ByteArrayOutputStream()
        FavoriteJourneysSerializer.writeTo(stored, out)
        val back = FavoriteJourneysSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf(fromHome, stations), back?.pendingToDomain())
    }

    @Test
    fun `a newer-version file's grayed journeys read as unavailable and an add preserves it`() = runTest {
        val newer = PersistedFavoriteJourneys(version = PersistedFavoriteJourneys.CURRENT_VERSION + 1)
        val data = FakeDataStore(newer)
        val store = DataStoreFavoriteJourneysStore(data)
        assertNull(store.pendingJourneys().first())
        store.addPending(fromHome)
        assertEquals(newer, data.data.first())
    }

    @Test
    fun `alert schedules are saved per direction, and go when their journey does`() = runTest {
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        store.add(journey)
        val out = JourneyAlerts.directionKey(journey, journey.from.stopId)
        val back = JourneyAlerts.directionKey(journey, journey.to.stopId)
        val evenings = JourneyAlertSchedule(days = setOf(DayOfWeek.SATURDAY), windows = listOf(TimeWindow(LocalTime.of(16, 30), LocalTime.of(18, 0))))
        store.updateAlertSchedule(out) { JourneyAlertSchedule.DEFAULT }
        store.updateAlertSchedule(back) { evenings }
        assertEquals(mapOf(out to JourneyAlertSchedule.DEFAULT, back to evenings), store.alertSchedules().first())

        store.updateAlertSchedule(back) { null }
        assertEquals(mapOf(out to JourneyAlertSchedule.DEFAULT), store.alertSchedules().first())

        // Two changes in a row each build on the last: neither is lost.
        store.updateAlertSchedule(out) { it?.copy(days = it.days - DayOfWeek.MONDAY) }
        store.updateAlertSchedule(out) { it?.copy(days = it.days - DayOfWeek.TUESDAY) }
        assertEquals(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), store.alertSchedules().first()?.get(out)?.days)
        store.updateAlertSchedule(out) { JourneyAlertSchedule.DEFAULT }

        // Removed, then saved again: its alerts start off.
        store.remove(journey)
        store.add(journey)
        assertEquals(emptyMap<String, JourneyAlertSchedule>(), store.alertSchedules().first())
    }

    @Test
    fun `a schedule for a journey not saved isn't kept`() = runTest {
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        store.updateAlertSchedule(JourneyAlerts.directionKey(journey, journey.from.stopId)) { JourneyAlertSchedule.DEFAULT }
        assertEquals(emptyMap<String, JourneyAlertSchedule>(), store.alertSchedules().first())
    }

    @Test
    fun `alert schedules survive the JSON round trip`() = runTest {
        val key = JourneyAlerts.directionKey(journey, journey.from.stopId)
        val out = ByteArrayOutputStream()
        FavoriteJourneysSerializer.writeTo(listOf(journey).toPersisted(mapOf(key to JourneyAlertSchedule.DEFAULT)), out)
        val read = FavoriteJourneysSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(mapOf(key to JourneyAlertSchedule.DEFAULT), read?.alertsToDomain())
        assertEquals(listOf(journey), read?.toDomain())
    }

    @Test
    fun `a stored schedule is read back as the app saves them, bounded and in order`() = runTest {
        // A file from elsewhere (a restore, another build) with repeats, out of order and too many windows.
        val key = JourneyAlerts.directionKey(journey, journey.from.stopId)
        val hours = (6..12).map { TimeWindow(LocalTime.of(it, 0), LocalTime.of(it + 1, 0)) }
        val messy = JourneyAlertSchedule(windows = (hours.reversed() + hours.first()))
        val read = listOf(journey).toPersisted(mapOf(key to messy)).alertsToDomain()!!.getValue(key).windows
        assertEquals(hours.take(JourneyAlertSchedule.MAX_WINDOWS), read)
    }

    @Test
    fun `a stored schedule that could never fire reads as off, and a window that never opens is dropped`() = runTest {
        val key = JourneyAlerts.directionKey(journey, journey.from.stopId)
        val backwards = TimeWindow(LocalTime.of(10, 0), LocalTime.of(8, 0))
        val good = TimeWindow(LocalTime.of(16, 0), LocalTime.of(18, 0))
        val partly = listOf(journey).toPersisted(mapOf(key to JourneyAlertSchedule(windows = listOf(backwards, good)))).alertsToDomain()!!
        assertEquals(listOf(good), partly.getValue(key).windows)
        val never = listOf(journey).toPersisted(mapOf(key to JourneyAlertSchedule(windows = listOf(backwards)))).alertsToDomain()!!
        assertEquals(emptyMap<String, JourneyAlertSchedule>(), never)
        val noDays = listOf(journey).toPersisted(mapOf(key to JourneyAlertSchedule(days = emptySet()))).alertsToDomain()!!
        assertEquals(emptyMap<String, JourneyAlertSchedule>(), noDays)
    }

    @Test
    fun `a write waits while the journey alert check holds the settings lock`() = runTest {
        val lock = kotlinx.coroutines.sync.Mutex()
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null), compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), writes = lock)
        lock.lock()
        val write = backgroundScope.launch { store.add(journey) }
        testScheduler.advanceUntilIdle()
        // Held from the check's last read through its posts: the edit can't land in between (Codex on #700).
        assertTrue(write.isActive)
        assertEquals(emptyList<FavoriteJourney>(), store.journeys().first())
        lock.unlock()
        write.join()
        assertEquals(listOf(journey), store.journeys().first())
    }
}
