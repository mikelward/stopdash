package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.DismissalMarks
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.SteadyClock
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

        // Run once ahead of the next update, as a write queued before it would be.
        var ahead: (suspend () -> Unit)? = null

        // Run once just after the next update is written, as a write that lands before its caller resumes.
        var behind: (suspend () -> Unit)? = null

        override suspend fun updateData(
            transform: suspend (t: PersistedDismissedAlerts?) -> PersistedDismissedAlerts?,
        ): PersistedDismissedAlerts? {
            ahead?.let { ahead = null; it() }
            val written = transform(state.value).also { state.value = it }
            behind?.let { behind = null; it() }
            return written
        }
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
    fun `reconcile keeps a dismissal made after the check saw the set`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        store.dismiss(busStop)
        // The check settled what was dismissed then; the HUBKGX closure was dismissed since, of a
        // notice a newer check found back, so the older check's write keeps it.
        val since = store.mark()
        val seen = store.dismissed().first()
        store.dismiss(closure)
        store.reconcile(live = emptySet(), checkedPlaces = setOf("HUBKGX", "490G000A"), seen = seen, since = DismissalMarks(since))
        assertEquals(setOf(closure), store.dismissed().first())
    }

    @Test
    fun `reconcile keeps an alert dismissed again after the check saw it`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        store.dismiss(closure)
        // The check saw it dismissed and found its notice ended, so let go of it in memory; before its
        // write lands, the rider dismisses the same alert again (a newer check found it back).
        val since = store.mark()
        val seen = store.dismissed().first()
        store.dismiss(closure)
        store.reconcile(live = emptySet(), checkedPlaces = setOf("HUBKGX"), seen = seen, since = DismissalMarks(since))
        assertEquals(setOf(closure), store.dismissed().first())
        // Not dismissed again since a check read the set: its verdict lets go of it.
        val later = store.mark()
        store.reconcile(live = emptySet(), checkedPlaces = setOf("HUBKGX"), seen = store.dismissed().first(), since = DismissalMarks(later))
        assertEquals(emptySet<DismissedAlert>(), store.dismissed().first())
    }

    @Test
    fun `reconcile keeps an alert dismissed again by a write queued just ahead of it`() = runTest {
        val data = FakeDataStore(null)
        val store = DataStoreDismissedAlertsStore(data)
        store.dismiss(closure)
        val since = store.mark()
        val seen = store.dismissed().first()
        // The same alert dismissed again once the check's write is asked for, its write landing first.
        data.ahead = { store.dismiss(closure) }
        store.reconcile(live = emptySet(), checkedPlaces = setOf("HUBKGX"), seen = seen, since = DismissalMarks(since))
        assertEquals(setOf(closure), store.dismissed().first())
    }

    @Test
    fun `only alerts not dismissed again since the mark are still the check's to let go of`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        store.dismiss(closure)
        store.dismiss(busStop)
        val since = store.mark()
        store.dismiss(closure)
        assertEquals(setOf(busStop), store.stillSeen(setOf(closure, busStop), DismissalMarks(since)))
    }

    @Test
    fun `each place is still the check's to let go of by its own answer's mark`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        val older = store.mark()
        store.dismiss(closure)
        store.dismiss(busStop)
        val newer = store.mark()
        // The interchange's answer was asked before its closure was dismissed, the bus stop's after:
        // only the bus stop's dismissal is the check's to let go of.
        val marks = DismissalMarks(newer, mapOf("HUBKGX" to older))
        assertEquals(setOf(busStop), store.stillSeen(setOf(closure, busStop), marks))
        assertEquals(setOf(closure), store.dismissedAgain(setOf(closure, busStop), marks))
    }

    @Test
    fun `a check's prune never lands between a tap's count and its add`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        val shown = MutableStateFlow(emptySet<DismissedAlert>())
        val pruned = java.util.concurrent.CountDownLatch(1)
        var check: Thread? = null
        var heldOff = false
        store.dismiss(closure, notWritten = {}, counted = {
            // A check that started after the tap was counted prunes it on another thread meanwhile: it
            // waits for the tap's add, never landing before it to have the add undo its prune.
            val since = DismissalMarks(store.mark())
            val thread = Thread {
                store.prune(setOf(closure), since) { still -> shown.update { it - still } }
                pruned.countDown()
            }.also { check = it; it.start() }
            // Until the check has either pruned (landing before the add) or is held off by the store's
            // lock: no elapsed time decides it.
            while (pruned.count > 0 && thread.state != Thread.State.BLOCKED) Thread.onSpinWait()
            heldOff = pruned.count > 0
            shown.update { it + closure }
        })
        check?.join()
        assertTrue(heldOff)
        // The check is newer than the tap, so its prune, after the add, takes the alert away.
        assertEquals(emptySet<DismissedAlert>(), shown.value)
    }

    @Test
    fun `a dismissal whose write fails isn't counted as made`() = runTest {
        val data = FakeDataStore(null)
        val store = DataStoreDismissedAlertsStore(data)
        store.dismiss(closure)
        val since = store.mark()
        // Dismissed again, but the write fails: the check that read the set before still lets it go.
        data.ahead = { throw java.io.IOException("disk full") }
        try {
            store.dismiss(closure)
        } catch (_: java.io.IOException) {
            // The caller says the dismiss failed; asserted below that it isn't kept as one.
        }
        assertEquals(setOf(closure), store.stillSeen(setOf(closure), DismissalMarks(since)))
    }

    @Test
    fun `two failed dismissals of an alert at once leave neither counted`() = runTest {
        // Two dismissals of the same alert out at once, the earlier one's write failing first, then the
        // later one's: neither is a dismissal a check must keep.
        val gates = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Unit>>()
        var failing = false
        val state = MutableStateFlow<PersistedDismissedAlerts?>(null)
        val data = object : DataStore<PersistedDismissedAlerts?> {
            override val data: Flow<PersistedDismissedAlerts?> = state
            override suspend fun updateData(
                transform: suspend (t: PersistedDismissedAlerts?) -> PersistedDismissedAlerts?,
            ): PersistedDismissedAlerts? {
                if (failing) {
                    val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { gates.addLast(it) }
                    gate.await()
                    throw java.io.IOException("disk full")
                }
                return transform(state.value).also { state.value = it }
            }
        }
        val store = DataStoreDismissedAlertsStore(data)
        store.dismiss(closure)
        val since = store.mark()
        failing = true
        val first = async { runCatching { store.dismiss(closure) } }
        val second = async { runCatching { store.dismiss(closure) } }
        testScheduler.advanceUntilIdle()
        assertEquals(2, gates.size)
        // The earlier one fails first, while the later one is still out; then the later one.
        gates.removeFirst().complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(first.await().isFailure)
        gates.removeFirst().complete(Unit)
        assertTrue(second.await().isFailure)
        assertEquals(setOf(closure), store.stillSeen(setOf(closure), DismissalMarks(since)))
    }

    @Test
    fun `a failed dismissal is taken out of every caller's set only when no other one of it is in`() = runTest {
        val gates = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Boolean>>()
        val state = MutableStateFlow<PersistedDismissedAlerts?>(null)
        val data = object : DataStore<PersistedDismissedAlerts?> {
            override val data: Flow<PersistedDismissedAlerts?> = state
            override suspend fun updateData(
                transform: suspend (t: PersistedDismissedAlerts?) -> PersistedDismissedAlerts?,
            ): PersistedDismissedAlerts? {
                val gate = kotlinx.coroutines.CompletableDeferred<Boolean>().also { gates.addLast(it) }
                if (!gate.await()) throw java.io.IOException("disk full")
                return transform(state.value).also { state.value = it }
            }
        }
        val store = DataStoreDismissedAlertsStore(data)
        var told = 0
        var toldFirst = 0
        var toldSecond = 0
        // Two taps of the alert at once (on two screens, say): the earlier one's write fails while the
        // later one's is still out, so the later one keeps it; then the later one's fails too, and both
        // callers are told.
        val first = async { runCatching { store.dismiss(closure, counted = {}, notWritten = { toldFirst++ }) } }
        val second = async { runCatching { store.dismiss(closure, counted = {}, notWritten = { toldSecond++ }) } }
        testScheduler.advanceUntilIdle()
        gates.removeFirst().complete(false)
        assertTrue(first.await().isFailure)
        assertEquals(0 to 0, toldFirst to toldSecond)
        gates.removeFirst().complete(false)
        assertTrue(second.await().isFailure)
        assertEquals(1 to 1, toldFirst to toldSecond)
        // One written, then another failing: the written one keeps it.
        val written = async { store.dismiss(closure, counted = {}, notWritten = { told++ }) }
        testScheduler.advanceUntilIdle()
        gates.removeFirst().complete(true)
        written.await()
        val failing = async { runCatching { store.dismiss(closure, counted = {}, notWritten = { told++ }) } }
        testScheduler.advanceUntilIdle()
        gates.removeFirst().complete(false)
        assertTrue(failing.await().isFailure)
        assertEquals(0, told)
    }

    @Test
    fun `a dismissal whose write fails after its update ran keeps the one written before it`() = runTest {
        val data = FakeDataStore(null)
        val store = DataStoreDismissedAlertsStore(data)
        val since = store.mark()
        store.dismiss(closure)
        // Dismissed again: its update runs, then the file write fails. The first is still stored, so no
        // caller takes it back out, and a check that read the set before it still keeps it.
        data.behind = { throw java.io.IOException("disk full") }
        var told = 0
        val failed = runCatching { store.dismiss(closure, counted = {}, notWritten = { told++ }) }
        assertTrue(failed.isFailure)
        assertEquals(0, told)
        assertEquals(emptySet<DismissedAlert>(), store.stillSeen(setOf(closure), DismissalMarks(since)))
        assertEquals(setOf(closure), store.dismissedAgain(setOf(closure), DismissalMarks(since)))
    }

    @Test
    fun `a later dismissal failing after an earlier one is written keeps the earlier one`() = runTest {
        // Two taps of the alert, counted in one order but updated in the other: the later one's update
        // runs and its write fails only after the earlier one's is in. The earlier one stays written.
        val updates = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Unit>>()
        val writes = HashMap<Int, kotlinx.coroutines.CompletableDeferred<Boolean>>()
        var calls = 0
        val state = MutableStateFlow<PersistedDismissedAlerts?>(null)
        val data = object : DataStore<PersistedDismissedAlerts?> {
            override val data: Flow<PersistedDismissedAlerts?> = state
            override suspend fun updateData(
                transform: suspend (t: PersistedDismissedAlerts?) -> PersistedDismissedAlerts?,
            ): PersistedDismissedAlerts? {
                val call = calls++
                kotlinx.coroutines.CompletableDeferred<Unit>().also { updates.addLast(it) }.await()
                val next = transform(state.value)
                val write = kotlinx.coroutines.CompletableDeferred<Boolean>().also { writes[call] = it }
                if (!write.await()) throw java.io.IOException("disk full")
                return next.also { state.value = it }
            }
        }
        val store = DataStoreDismissedAlertsStore(data)
        val since = store.mark()
        var told = 0
        val earlier = async { runCatching { store.dismiss(closure, counted = {}, notWritten = { told++ }) } }
        val later = async { runCatching { store.dismiss(closure, counted = {}, notWritten = { told++ }) } }
        testScheduler.advanceUntilIdle()
        val (earlierUpdate, laterUpdate) = updates.removeFirst() to updates.removeFirst()
        laterUpdate.complete(Unit)
        testScheduler.advanceUntilIdle()
        earlierUpdate.complete(Unit)
        testScheduler.advanceUntilIdle()
        writes.getValue(0).complete(true)
        assertTrue(earlier.await().isSuccess)
        writes.getValue(1).complete(false)
        assertTrue(later.await().isFailure)
        assertEquals(0, told)
        assertEquals(setOf(closure), store.dismissedAgain(setOf(closure), DismissalMarks(since)))
    }

    @Test
    fun `a check's settle waits for a dismissal its mark covers still being written`() = runTest {
        // The tap is counted, the check reads its mark past it and settles before the tap's write is
        // in: the settle waits, so the tap's write can't come after it and store the alert again.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var first = true
        val state = MutableStateFlow<PersistedDismissedAlerts?>(null)
        val data = object : DataStore<PersistedDismissedAlerts?> {
            override val data: Flow<PersistedDismissedAlerts?> = state
            override suspend fun updateData(
                transform: suspend (t: PersistedDismissedAlerts?) -> PersistedDismissedAlerts?,
            ): PersistedDismissedAlerts? {
                if (first) {
                    first = false
                    gate.await()
                }
                return transform(state.value).also { state.value = it }
            }
        }
        val store = DataStoreDismissedAlertsStore(data)
        val tap = async { store.dismiss(closure) }
        testScheduler.advanceUntilIdle()
        val since = DismissalMarks(store.mark())
        val check = async { store.reconcile(live = emptySet(), checkedPlaces = setOf("HUBKGX"), seen = setOf(closure), since = since) }
        testScheduler.advanceUntilIdle()
        assertFalse(check.isCompleted)
        gate.complete(Unit)
        tap.await()
        check.await()
        // The check, newer than the tap, lets go of it once both are in.
        assertEquals(emptySet<DismissedAlert>(), store.dismissed().first())
    }

    @Test
    fun `a failed dismissal of an alert stored before the process started isn't rolled back`() = runTest {
        // Dismissed in an earlier run, so this store wrote none of it; tapped again, its write fails. The
        // stored set still holds it, so no caller takes it back out.
        val data = FakeDataStore(setOf(closure).toPersisted())
        val store = DataStoreDismissedAlertsStore(data)
        data.ahead = { throw java.io.IOException("disk full") }
        var told = 0
        assertTrue(runCatching { store.dismiss(closure, counted = {}, notWritten = { told++ }) }.isFailure)
        assertEquals(0, told)
        // One the stored set doesn't hold is rolled back.
        data.ahead = { throw java.io.IOException("disk full") }
        assertTrue(runCatching { store.dismiss(busStop, counted = {}, notWritten = { told++ }) }.isFailure)
        assertEquals(1, told)
    }

    @Test
    fun `an alert dismissed again is taken back only once its write is in`() = runTest {
        // A check asks, once its own write is in, which alerts it let go of were dismissed again: one
        // still being written is waited for, and taken back only if it's written.
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var holding = false
        val state = MutableStateFlow<PersistedDismissedAlerts?>(null)
        val data = object : DataStore<PersistedDismissedAlerts?> {
            override val data: Flow<PersistedDismissedAlerts?> = state
            override suspend fun updateData(
                transform: suspend (t: PersistedDismissedAlerts?) -> PersistedDismissedAlerts?,
            ): PersistedDismissedAlerts? {
                if (holding && !gate.await()) throw java.io.IOException("disk full")
                return transform(state.value).also { state.value = it }
            }
        }
        val store = DataStoreDismissedAlertsStore(data)
        store.dismiss(closure)
        val since = store.mark()
        holding = true
        val tap = async { runCatching { store.dismiss(closure) } }
        val asked = async { store.dismissedAgain(setOf(closure), DismissalMarks(since)) }
        testScheduler.advanceUntilIdle()
        // Still being written: not answered yet.
        assertFalse(asked.isCompleted)
        gate.complete(false)
        assertTrue(tap.await().isFailure)
        // Its write failed, so it isn't taken back.
        assertEquals(emptySet<DismissedAlert>(), asked.await())
    }

    @Test
    fun `an alert dismissed again and written is taken back`() = runTest {
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        store.dismiss(closure)
        val since = store.mark()
        store.dismiss(closure)
        assertEquals(setOf(closure), store.dismissedAgain(setOf(closure), DismissalMarks(since)))
        assertEquals(emptySet<DismissedAlert>(), store.dismissedAgain(setOf(closure), DismissalMarks(store.mark())))
    }

    @Test
    fun `an alert a newer check let go of isn't taken back by an older one`() = runTest {
        // An older check marks, the alert is dismissed again, then a newer check (marked after) lets
        // go of it in the store: the older check, asking last, doesn't take it back.
        val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
        store.dismiss(closure)
        val older = store.mark()
        store.dismiss(closure)
        val newer = store.mark()
        store.reconcile(live = emptySet(), checkedPlaces = setOf("HUBKGX"), seen = setOf(closure), since = DismissalMarks(newer))
        assertEquals(emptySet<DismissedAlert>(), store.dismissed().first())
        assertEquals(emptySet<DismissedAlert>(), store.dismissedAgain(setOf(closure), DismissalMarks(older)))
    }

    @Test
    fun `a dismissal written just after a check let go of the alert stays counted`() = runTest {
        // The check's write lets go of the alert; before the check resumes, the rider dismisses it again
        // and that write lands. The check forgets only the count it cleared, so the new one stands.
        val data = FakeDataStore(null)
        val store = DataStoreDismissedAlertsStore(data)
        store.dismiss(closure)
        val since = store.mark()
        data.behind = { store.dismiss(closure) }
        store.reconcile(live = emptySet(), checkedPlaces = setOf("HUBKGX"), seen = setOf(closure), since = DismissalMarks(since))
        assertEquals(setOf(closure), store.dismissed().first())
        assertEquals(emptySet<DismissedAlert>(), store.stillSeen(setOf(closure), DismissalMarks(since)))
        assertEquals(setOf(closure), store.dismissedAgain(setOf(closure), DismissalMarks(since)))
    }

    @Test
    fun `a check that lets go of an alert just written isn't undone as its dismissal resumes`() = runTest {
        // The dismissal's write commits; before its caller resumes, a newer check (marked after it) settles,
        // letting go of the alert once the dismissal is in. An older check then finds nothing dismissed
        // again to take back.
        val data = FakeDataStore(null)
        val store = DataStoreDismissedAlertsStore(data)
        store.dismiss(closure)
        val older = store.mark()
        var check: kotlinx.coroutines.Deferred<Unit>? = null
        data.behind = {
            val since = DismissalMarks(store.mark())
            check = async { store.reconcile(live = emptySet(), checkedPlaces = setOf("HUBKGX"), seen = setOf(closure), since = since) }
        }
        store.dismiss(closure)
        checkNotNull(check).await()
        assertEquals(emptySet<DismissedAlert>(), store.dismissed().first())
        assertEquals(emptySet<DismissedAlert>(), store.dismissedAgain(setOf(closure), DismissalMarks(older)))
    }

    @Test
    fun `an alert dismissed from many threads at once keeps its latest count`() {
        // Each count is taken in turn but recorded as it lands: the highest stays, so a check marked
        // before the last dismissal never takes it for one it saw.
        repeat(50) {
            val store = DataStoreDismissedAlertsStore(FakeDataStore(null))
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            try {
                val start = java.util.concurrent.CountDownLatch(1)
                val done = (1..8).map {
                    pool.submit {
                        start.await()
                        kotlinx.coroutines.runBlocking { store.dismiss(closure) }
                    }
                }
                start.countDown()
                done.forEach { it.get() }
            } finally {
                pool.shutdown()
            }
            kotlinx.coroutines.runBlocking {
                store.reconcile(live = emptySet(), checkedPlaces = setOf("HUBKGX"), seen = setOf(closure), since = DismissalMarks(store.mark() - 1))
                assertEquals(setOf(closure), store.dismissed().first())
            }
        }
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

    @Test
    fun `the dismissals are mapped off the caller's thread`() {
        // Mapped on the store's worker, never the collector's (main) thread (AGENTS.md *Main thread: read
        // and dispatch only*): collected from a thread of its own, the stored list is read on the worker's.
        OffMainReads().use { reads ->
            val stored = PersistedDismissedAlerts(alerts = reads.recorded(PersistedDismissedAlert("line:victoria", "signature")))
            reads.fromCaller { DataStoreDismissedAlertsStore(reads.dataStore<PersistedDismissedAlerts?>(stored), compute = reads.worker).dismissed().first() }
            assertTrue(reads.reads.isNotEmpty())
            assertEquals(setOf(OffMainReads.WORKER), reads.reads.toSet())
        }
    }
}
