package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.DismissedAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The store wrapper's mapping and dismiss rule over a fake in-memory [DataStore], so no Android
 * file or Context is needed (JSON serialization is covered by [DismissedAlertsSerializerTest]).
 * Mirrors [DataStoreStarredRowsStoreTest], minus the "unavailable" state: a set this build can't
 * read fails **safe** to empty (a dismissed card reappears), never hiding a warning.
 */
class DataStoreDismissedAlertsStoreTest {
    private val closure = DismissedAlert("HUBKGX", "No step-free access")
    private val busStop = DismissedAlert("490G000A", "Bus Stop Closed")

    private class FakeDataStore(initial: PersistedDismissedAlerts?) : DataStore<PersistedDismissedAlerts?> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<PersistedDismissedAlerts?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedDismissedAlerts?) -> PersistedDismissedAlerts?,
        ): PersistedDismissedAlerts? = transform(state.value).also { state.value = it }
    }

    @Test
    fun `dismissed reads an empty set when nothing is stored`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        assertEquals(emptySet<DismissedAlert>(), store.dismissed().first())
    }

    @Test
    fun `dismiss records an alert`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        store.dismiss(closure)
        assertEquals(setOf(closure), store.dismissed().first())
    }

    @Test
    fun `dismissing a concurrent notice keeps the other card's dismissal`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        val other = DismissedAlert("HUBKGX", "Northern line not stopping here")
        store.dismiss(closure)
        // A dismiss only adds, so dismissing a second concurrent notice retains the first.
        store.dismiss(other)
        assertEquals(setOf(closure, other), store.dismissed().first())
    }

    @Test
    fun `reconcile drops a resolved notice's dismissal and keeps a live one`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        store.dismiss(closure)
        store.dismiss(busStop)
        // Both places were checked; only the bus-stop notice is still in the feed, so the resolved
        // HUBKGX dismissal is pruned — a later same-text closure there would show, not be suppressed.
        store.reconcile(live = setOf(busStop), checkedPlaces = setOf("HUBKGX", "490G000A"))
        assertEquals(setOf(busStop), store.dismissed().first())
    }

    @Test
    fun `reconcile keeps a dismissal for a place it did not check`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        store.dismiss(closure)
        store.dismiss(busStop)
        // Only the bus stop was checked this cycle and its notice is gone, so it's pruned; HUBKGX
        // wasn't queried, so its dismissal is kept despite nothing there being in `live`
        // (persist-until-change across nearby sets).
        store.reconcile(live = emptySet(), checkedPlaces = setOf("490G000A"))
        assertEquals(setOf(closure), store.dismissed().first())
    }

    @Test
    fun `a newer-version set reads as empty, failing safe rather than hiding a card`() = runTest {
        val future = setOf(closure).toPersisted().copy(version = PersistedDismissedAlerts.CURRENT_VERSION + 1)
        val store = DataStoreDismissedAlertsStore(FakeDataStore(future))
        assertEquals(emptySet<DismissedAlert>(), store.dismissed().first())
    }

    @Test
    fun `a dismiss on top of an unreadable set starts a fresh readable one`() = runTest {
        val future = setOf(closure).toPersisted().copy(version = PersistedDismissedAlerts.CURRENT_VERSION + 1)
        val store = DataStoreDismissedAlertsStore(FakeDataStore(future))
        store.dismiss(busStop)
        // The unreadable set read as empty, so the new dismiss is the whole set now — safe (at worst
        // the old dismissals reappear as cards), never a lost warning.
        assertEquals(setOf(busStop), store.dismissed().first())
    }

    @Test
    fun `a line dismissal a refresh saw end is kept for the widget only, for one staleness window`() = runTest {
        val start = java.time.Instant.parse("2026-09-18T08:00:00Z")
        var now = start
        val severe = app.stopdash.domain.LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null), clock = { now })
        val dismissal = DismissedAlert.ofLineStatus(severe)
        val victoria = setOf(app.stopdash.domain.lineAlertKey("victoria"), "HUBKGX")
        store.dismiss(dismissal)
        store.dismiss(closure)
        // A refresh learned both ended. The line one is kept for the widget's stored copy, which
        // may still hold it, but isn't one the app's own screens apply: what they fetch is newer
        // than the end, so the same alert there is a recurrence. The widget carries no closures.
        store.reconcile(live = emptySet(), checkedPlaces = victoria)
        assertEquals(app.stopdash.domain.Dismissals(emptySet(), mapOf(dismissal to start)), store.dismissals().first())
        assertEquals(emptySet<DismissedAlert>(), store.dismissed().first())
        // The clock goes back: an end time now in the future isn't applied, as it would hide a
        // recurrence fetched since. Nor is it an ordinary dismissal.
        now = start.minusSeconds(3_600)
        assertEquals(app.stopdash.domain.Dismissals.NONE, store.dismissals().first())
        // Once every check it could hide is stale, it goes.
        now = start.plusSeconds(3_600)
        store.reconcile(live = setOf(dismissal), checkedPlaces = victoria)
        assertEquals(app.stopdash.domain.Dismissals.NONE, store.dismissals().first())
    }

    @Test
    fun `dismissing an ended alert again makes it an ordinary dismissal`() = runTest {
        val start = java.time.Instant.parse("2026-09-18T08:00:00Z")
        var now = start
        val severe = app.stopdash.domain.LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null), clock = { now })
        val dismissal = DismissedAlert.ofLineStatus(severe)
        val victoria = setOf(app.stopdash.domain.lineAlertKey("victoria"))
        store.dismiss(dismissal)
        now = start.plusSeconds(60)
        store.reconcile(live = emptySet(), checkedPlaces = victoria)
        // It recurs, and the user dismisses the recurrence.
        store.dismiss(dismissal)
        now = start.plusSeconds(3_600)
        store.reconcile(live = setOf(dismissal), checkedPlaces = victoria)
        assertEquals(setOf(dismissal), store.dismissed().first())
    }
}
