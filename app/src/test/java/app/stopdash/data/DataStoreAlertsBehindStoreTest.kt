package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.AlertBehind
import app.stopdash.domain.AlertPlacement
import app.stopdash.domain.AlertsBehind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's verdicts on alerts behind a stop, as stored for the widget and the watch, over a fake
 * in-memory [DataStore] as [DataStoreDismissedAlertsStoreTest] does: kept a day from when last reached,
 * and anything unreadable reads as none, so every alert flags.
 */
class DataStoreAlertsBehindStoreTest {
    private val t0: Instant = Instant.parse("2026-10-02T08:00:00Z")
    private val verdict = AlertBehind("99", "abc1234", "490GEXAMPLE1", "inbound")

    // The list at the verdict's stop, its alert on the rows, nothing weighed there.
    private val shown = setOf("490GEXAMPLE1")
    private val onRows = setOf("99" to "abc1234")
    private val atStop = AlertPlacement(emptySet(), emptySet(), shown, onRows)

    private class FakeDataStore(initial: PersistedAlertsBehind?) : DataStore<PersistedAlertsBehind?> {
        val state = MutableStateFlow(initial)
        var writes = 0
        override val data: Flow<PersistedAlertsBehind?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedAlertsBehind?) -> PersistedAlertsBehind?,
        ): PersistedAlertsBehind? = transform(state.value).also {
            if (it != state.value) writes++
            state.value = it
        }
    }

    @Test
    fun `a recorded verdict is read back until a day after it was last reached`() = runTest {
        var now = t0
        val data = FakeDataStore(null)
        val store = DataStoreAlertsBehindStore(data) { now }
        assertEquals(emptySet<AlertBehind>(), store.verdicts().first())
        store.record(AlertPlacement(setOf(verdict), setOf(verdict), shown, onRows))
        assertEquals(setOf(verdict), store.verdicts().first())
        // Reached again within the hour: no write.
        now = t0.plus(Duration.ofMinutes(30))
        store.record(AlertPlacement(setOf(verdict), setOf(verdict), shown, onRows))
        assertEquals(1, data.writes)
        // A day after it was last reached, it no longer stands.
        now = t0.plus(AlertsBehind.MAX_AGE)
        assertEquals(emptySet<AlertBehind>(), store.verdicts().first())
    }

    @Test
    fun `a verdict the app weighs and no longer finds behind is dropped`() = runTest {
        val store = DataStoreAlertsBehindStore(FakeDataStore(null)) { t0 }
        store.record(AlertPlacement(setOf(verdict), setOf(verdict), shown, onRows))
        store.record(AlertPlacement(emptySet(), setOf(verdict), shown, onRows))
        assertEquals(emptySet<AlertBehind>(), store.verdicts().first())
    }

    @Test
    fun `a placement that weighed nothing still prunes the lapsed verdicts from the file`() = runTest {
        val data = FakeDataStore(mapOf(verdict to t0).toPersisted())
        DataStoreAlertsBehindStore(data) { t0.plus(AlertsBehind.MAX_AGE) }.record(atStop)
        assertEquals(emptyList<PersistedAlertBehind>(), data.state.value?.verdicts)
        // And one still standing is left alone, unwritten.
        val held = FakeDataStore(mapOf(verdict to t0).toPersisted())
        DataStoreAlertsBehindStore(held) { t0.plusSeconds(60) }.record(atStop)
        assertEquals(0, held.writes)
    }

    @Test
    fun `a verdict at a stop the list no longer shows leaves the file`() = runTest {
        val data = FakeDataStore(mapOf(verdict to t0).toPersisted())
        DataStoreAlertsBehindStore(data) { t0.plusSeconds(60) }.record(AlertPlacement(emptySet(), emptySet(), setOf("490GEXAMPLE2"), onRows))
        assertEquals(emptyList<PersistedAlertBehind>(), data.state.value?.verdicts)
    }

    @Test
    fun `an empty placement writes nothing new, and a newer version reads as none`() = runTest {
        val data = FakeDataStore(null)
        DataStoreAlertsBehindStore(data) { t0 }.record(AlertPlacement.NONE)
        assertNull(data.state.value)
        val newer = FakeDataStore(PersistedAlertsBehind(version = 2, verdicts = listOf(PersistedAlertBehind("99", "abc1234", "490GEXAMPLE1", "inbound", t0.toEpochMilli()))))
        assertEquals(emptySet<AlertBehind>(), DataStoreAlertsBehindStore(newer) { t0 }.verdicts().first())
    }

    @Test
    fun `a stored set round-trips through the file`() = runTest {
        val stored = mapOf(verdict to t0).toPersisted()
        val bytes = ByteArrayOutputStream().also { AlertsBehindSerializer.writeTo(stored, it) }.toByteArray()
        val back = AlertsBehindSerializer.readFrom(ByteArrayInputStream(bytes))
        assertEquals(stored, back)
        assertEquals(mapOf(verdict to t0), back?.toDomain())
        // An empty file is nothing saved yet.
        assertSame(null, AlertsBehindSerializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `the verdicts are mapped off the caller's thread`() {
        // Mapped on the store's worker, never the collector's (main) thread (AGENTS.md *Main thread: read
        // and dispatch only*): collected from a thread of its own, the stored list is read on the worker's.
        OffMainReads().use { reads ->
            val stored = PersistedAlertsBehind(verdicts = reads.recorded(PersistedAlertBehind("victoria", "fingerprint", "940GZZLUKSX", atMillis = 0)))
            reads.fromCaller { DataStoreAlertsBehindStore(reads.dataStore<PersistedAlertsBehind?>(stored), compute = reads.worker).verdicts().first() }
            assertTrue(reads.reads.isNotEmpty())
            assertEquals(setOf(OffMainReads.WORKER), reads.reads.toSet())
        }
    }
}
