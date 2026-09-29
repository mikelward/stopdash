package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.SteadyClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // An end time ahead of the clock (here, with no steady source, the wall clock set back; on a
        // device, across a reboot) isn't applied, as it would hide a recurrence fetched since. Nor
        // is it an ordinary dismissal.
        now = start.minusSeconds(3_600)
        assertEquals(app.stopdash.domain.Dismissals.NONE, store.dismissals().first())
        // Once every check it could hide is stale, it goes.
        now = start.plusSeconds(3_600)
        store.reconcile(live = setOf(dismissal), checkedPlaces = victoria)
        assertEquals(app.stopdash.domain.Dismissals.NONE, store.dismissals().first())
    }

    // A device whose wall clock has been set back by [setBack] since the process started, in [frame].
    private class Steady(var setBack: java.time.Duration = java.time.Duration.ZERO, override val frame: SteadyClock.Frame? = SteadyClock.Frame("device/7", 0L)) : SteadyClock.Source {
        override fun offset(): java.time.Duration = setBack
    }

    @After
    fun resetSteadyClock() {
        SteadyClock.source = null
    }

    @Test
    fun `an ended dismissal still hides the widget's old check after the clock is set back`() = runTest {
        val start = java.time.Instant.parse("2026-09-18T08:00:00Z")
        val device = Steady()
        SteadyClock.source = device
        var now = start
        val severe = app.stopdash.domain.LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val backing = FakeDataStore(null)
        val store = DataStoreDismissedAlertsStore(backing, clock = { now })
        val dismissal = DismissedAlert.ofLineStatus(severe)
        // The widget's copy was checked, then the alert's end was seen.
        val old = app.stopdash.domain.LineStatusCheck(severe, SteadyClock.stamp(start.minusSeconds(30)))
        store.dismiss(dismissal)
        store.reconcile(live = emptySet(), checkedPlaces = setOf(app.stopdash.domain.lineAlertKey("victoria")))
        // A minute on, the clock is set back an hour: the old check is still live by the steady
        // clock, so the end it's weighed against is too, and it stays hidden (Codex, PR #386).
        device.setBack = java.time.Duration.ofHours(1)
        now = start.plusSeconds(60).minusSeconds(3_600)
        assertTrue(old.isLive(now))
        assertTrue(store.dismissals().first().hide(old))
        // So too for a process started since, in the same boot, whose frame reads the boot an hour earlier.
        SteadyClock.source = Steady(frame = SteadyClock.Frame("device/7", -3_600_000L))
        val moved = app.stopdash.domain.LineStatusCheck(severe, start.minusSeconds(30).minusSeconds(3_600))
        assertTrue(DataStoreDismissedAlertsStore(backing, clock = { now }).dismissals().first().hide(moved))
    }

    @Test
    fun `an ended dismissal kept across a reboot hides the old check however the clock is set after`() = runTest {
        val start = java.time.Instant.parse("2026-09-18T08:00:00Z")
        val severe = app.stopdash.domain.LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val dismissal = DismissedAlert.ofLineStatus(severe)
        // In one boot, the widget's copy was checked, then the alert's end was seen.
        val before = SteadyClock.Frame("device/7", start.minusSeconds(86_400).toEpochMilli())
        SteadyClock.source = Steady(frame = before)
        val backing = FakeDataStore(null)
        DataStoreDismissedAlertsStore(backing, clock = { start }).run {
            dismiss(dismissal)
            reconcile(live = emptySet(), checkedPlaces = setOf(app.stopdash.domain.lineAlertKey("victoria")))
        }
        val stored = PersistedSnapshot(
            lineStatuses = listOf(app.stopdash.domain.LineStatusCheck(severe, start.minusSeconds(30)).toPersisted()),
            stampFrame = before.toPersisted(),
        )
        // The phone reboots, and a read of the stored snapshot takes it into the new boot.
        val bootStart = start.plusSeconds(90).toEpochMilli()
        val adopted = stored.inFrame(SteadyClock.Frame("device/8", bootStart))
        for (setBy in listOf(3_600L, -3_600L)) {
            // The clock is then set, and a process started since reads the boot as that much later
            // or earlier: the snapshot's check moves with it, and the end must too (Codex, PR #386).
            val after = SteadyClock.Frame("device/8", bootStart + setBy * 1_000)
            SteadyClock.source = Steady(frame = after)
            val old = adopted.inFrame(after).lineStatuses.single().toDomain()
            val now = java.time.Instant.ofEpochMilli(after.originMillis).plusSeconds(120)
            assertTrue(old.isLive(now))
            val dismissals = DataStoreDismissedAlertsStore(backing, clock = { now }).dismissals().first()
            assertTrue(dismissals.hide(old))
            // A check made since the reboot that still has the alert is a recurrence: it shows.
            assertFalse(dismissals.hide(app.stopdash.domain.LineStatusCheck(severe, now)))
        }
    }

    @Test
    fun `an older build's end time, the wall clock's, is taken in as the wall clock reads it`() = runTest {
        val start = java.time.Instant.parse("2026-09-18T08:00:00Z")
        val severe = app.stopdash.domain.LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val dismissal = DismissedAlert.ofLineStatus(severe)
        val written = PersistedDismissedAlerts(alerts = listOf(PersistedDismissedAlert(dismissal.alertKey, dismissal.contentSignature, start.toEpochMilli())))
        // The clock was set back an hour since this process started: the end, a minute ago by the wall
        // clock, is a minute ago in the steady frame too.
        SteadyClock.source = Steady(setBack = java.time.Duration.ofHours(1))
        val ended = DataStoreDismissedAlertsStore(FakeDataStore(written), clock = { start.plusSeconds(60) }).dismissals().first().ended
        assertEquals(mapOf(dismissal to start.plusSeconds(3_600)), ended)
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
