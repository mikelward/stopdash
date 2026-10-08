package app.stopdash.ui

import app.stopdash.ThreadRecorder
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.LineAlert
import app.stopdash.domain.LineStatus
import java.time.Instant
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settling dismissals against what a check found live ([reconcileLineDismissals]). Made-up words. */
class DismissedAlertsTest {
    private val now = Instant.parse("2026-10-03T08:00:00Z")
    private val shown = LineAlert(3, "Part Suspended", "No buses between Alpha Road and Beta Road.")
    private val behind = LineAlert(6, "Diversion", "Bus stop 'Alpha Road' will not be served.")

    // What each write was told the check saw.
    private val written = mutableListOf<Set<DismissedAlert>>()
    private val marks = mutableListOf<app.stopdash.domain.DismissalMarks>()
    private val store = object : DismissedAlertsStore {
        override fun dismissed(): Flow<Set<DismissedAlert>> = flowOf(emptySet())
        override suspend fun dismiss(alert: DismissedAlert) = Unit
        override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = error("the check's set is passed")
        override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) {
            written += seen
            marks += since
        }
    }

    private fun identity(alert: LineAlert) = DismissedAlert.ofLineStatus(LineStatus("99", alert.severity, alert.description, alert.fullText))

    @Test
    fun `only the dismissals of alerts that ended are let go of, worked out on the worker`() {
        // From the trip planner's line check, called on the main thread: every alert under way is gone
        // through on the worker (Codex on #519), and what's let go of is named, so a dismissal made
        // meanwhile stays.
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            val read = ThreadRecorder()
            val status = LineStatus("99", 3, "Part Suspended", shown.fullText, underWay = Watched(listOf(shown, behind), read))
            val ended = DismissedAlert.ofLineStatus(LineStatus("99", 6, "Diversion", "Bus stop 'Gamma Road' will not be served."))
            val gone = mutableListOf<Set<DismissedAlert>>()
            runBlocking(caller) {
                reconcileLineDismissals(setOf(identity(behind), ended), mapOf("99" to status), setOf("99"), now, store, worker, {}, "test", since = app.stopdash.domain.DismissalMarks(7), pruned = { gone += it })
            }
            // The alert under way behind the one shown stays dismissed; the one that ended goes.
            assertEquals(listOf(setOf(ended)), gone)
            // The store lets go of only what the check saw, so one dismissed since stays stored too.
            assertEquals(listOf(setOf(identity(behind), ended)), written)
            assertEquals(listOf(app.stopdash.domain.DismissalMarks(7)), marks)
            assertTrue(read.threads().isNotEmpty())
            assertEquals(setOf("worker"), read.threads().toSet())
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `one dismissed again on another screen while a check pruned is taken back once the store is written`() {
        // The other screen's tap left the store as it was (it still held the alert), so this screen's
        // set heard nothing: asked after the store's write, it's dismissed again since the mark.
        val ended = DismissedAlert.ofLineStatus(LineStatus("99", 6, "Diversion", "Bus stop 'Gamma Road' will not be served."))
        var again = emptySet<DismissedAlert>()
        val tapped = object : DismissedAlertsStore {
            override fun dismissed(): Flow<Set<DismissedAlert>> = flowOf(emptySet())
            override suspend fun dismiss(alert: DismissedAlert) = Unit
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = error("the check's set is passed")
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) {
                // The other screen's tap lands as the check writes.
                again = setOf(ended)
            }
            override fun stillSeen(alerts: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) = alerts - again
        }
        val status = LineStatus("99", 3, "Part Suspended", shown.fullText)
        val pruned = mutableListOf<Set<DismissedAlert>>()
        val restored = mutableListOf<Set<DismissedAlert>>()
        runBlocking {
            reconcileLineDismissals(
                setOf(ended), mapOf("99" to status), setOf("99"), now, tapped, kotlinx.coroutines.Dispatchers.Unconfined, {}, "test", since = app.stopdash.domain.DismissalMarks(1),
                pruned = { pruned += it }, restored = { restored += it },
            )
        }
        assertEquals(listOf(setOf(ended)), pruned)
        assertEquals(listOf(setOf(ended)), restored)
    }

    @Test
    fun `a line alert's signature is built on the worker, never the caller's thread`() {
        // Its signature joins TfL's text, which grows with the alert (Codex, #603): built after the hop.
        val status = LineStatus("99", shown.severity, shown.description, shown.fullText)
        val built = ThreadRecorder()
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        val into = kotlinx.coroutines.flow.MutableStateFlow<Set<DismissedAlert>>(emptySet())
        try {
            runBlocking(caller) {
                dismissAlertOf(store, worker, kotlinx.coroutines.flow.MutableStateFlow(false), {}, into) {
                    built.note()
                    DismissedAlert.ofLineStatus(status)
                }
            }
        } finally {
            caller.close()
            worker.close()
        }
        assertEquals(listOf("worker"), built.threads())
        assertEquals(setOf(DismissedAlert.ofLineStatus(status)), into.value)
    }

    @Test
    fun `a dismissal the store already holds still takes in memory, worked out on the worker`() {
        // A check let go of the alert in memory, its write not yet in, so the store still holds it and
        // the tap changes nothing there to follow: the caller's set is told directly. Adding copies
        // the whole set, so on the worker, never the caller's (the main) thread.
        val row = app.stopdash.domain.DepartureRow(
            stopId = "490000001A", stopName = "Example Road", lineId = "", lineName = "", direction = "", directionKey = "",
            destination = "", mode = "bus", upcoming = emptyList(), fetchedAt = now, stopDisruption = "Bus Stop Closed",
        )
        val alert = DismissedAlert.ofStopClosure(row)
        val other = DismissedAlert("HUBKGX", "No step-free access")
        val read = ThreadRecorder()
        val into = kotlinx.coroutines.flow.MutableStateFlow<Set<DismissedAlert>>(ReadSet(setOf(other), read))
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            runBlocking(caller) {
                dismissAlert(store, row, worker, kotlinx.coroutines.flow.MutableStateFlow(false), {}, into)
            }
        } finally {
            caller.close()
            worker.close()
        }
        assertEquals(setOf(other, alert), into.value)
        assertTrue(read.threads().isNotEmpty())
        assertEquals(setOf("worker"), read.threads().toSet())
    }

    @Test
    fun `a dismissal whose write fails is taken back out of the caller's set`() {
        // Added as it's counted, before its write: a write that fails (no other dismissal of it in) takes
        // it out again, so the card stays shown, and the screen says the dismiss didn't take.
        val row = app.stopdash.domain.DepartureRow(
            stopId = "490000001A", stopName = "Example Road", lineId = "", lineName = "", direction = "", directionKey = "",
            destination = "", mode = "bus", upcoming = emptyList(), fetchedAt = now, stopDisruption = "Bus Stop Closed",
        )
        val other = DismissedAlert("HUBKGX", "No step-free access")
        val into = kotlinx.coroutines.flow.MutableStateFlow(setOf(other))
        val failed = kotlinx.coroutines.flow.MutableStateFlow(false)
        var added = false
        val failing = object : DismissedAlertsStore {
            override fun dismissed(): Flow<Set<DismissedAlert>> = flowOf(emptySet())
            override suspend fun dismiss(alert: DismissedAlert) = Unit
            override suspend fun dismiss(alert: DismissedAlert, counted: () -> Unit, notWritten: () -> Unit) {
                counted()
                added = alert in into.value
                notWritten()
                throw java.io.IOException("disk full")
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = Unit
        }
        runBlocking { dismissAlert(failing, row, kotlinx.coroutines.Dispatchers.Unconfined, failed, {}, into) }
        assertTrue(added)
        assertEquals(setOf(other), into.value)
        assertTrue(failed.value)
    }

    @Test
    fun `two taps of an alert whose writes both fail leave it shown`() = kotlinx.coroutines.test.runTest {
        // Both added as counted; the earlier fails while the later is still out, so it stays; then the
        // later fails too, and it's taken back out: neither dismissal was written.
        val row = app.stopdash.domain.DepartureRow(
            stopId = "490000001A", stopName = "Example Road", lineId = "", lineName = "", direction = "", directionKey = "",
            destination = "", mode = "bus", upcoming = emptyList(), fetchedAt = now, stopDisruption = "Bus Stop Closed",
        )
        val alert = DismissedAlert.ofStopClosure(row)
        val gates = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Unit>>()
        val data = object : androidx.datastore.core.DataStore<app.stopdash.data.PersistedDismissedAlerts?> {
            override val data: Flow<app.stopdash.data.PersistedDismissedAlerts?> = flowOf(null)
            override suspend fun updateData(
                transform: suspend (t: app.stopdash.data.PersistedDismissedAlerts?) -> app.stopdash.data.PersistedDismissedAlerts?,
            ): app.stopdash.data.PersistedDismissedAlerts? {
                kotlinx.coroutines.CompletableDeferred<Unit>().also { gates.addLast(it) }.await()
                throw java.io.IOException("disk full")
            }
        }
        val store = app.stopdash.data.DataStoreDismissedAlertsStore(data)
        val into = kotlinx.coroutines.flow.MutableStateFlow(emptySet<DismissedAlert>())
        val failed = kotlinx.coroutines.flow.MutableStateFlow(false)
        val io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val first = launch { dismissAlert(store, row, io, failed, {}, into) }
        val second = launch { dismissAlert(store, row, io, failed, {}, into) }
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(alert), into.value)
        gates.removeFirst().complete(Unit)
        testScheduler.advanceUntilIdle()
        first.join()
        assertEquals(setOf(alert), into.value)
        gates.removeFirst().complete(Unit)
        second.join()
        assertEquals(emptySet<DismissedAlert>(), into.value)
        assertTrue(failed.value)
    }
}

// [items], noting the thread of each read in [read].
private class ReadSet<T>(private val items: Set<T>, private val read: ThreadRecorder) : AbstractSet<T>() {
    private fun seen() { read.note() }

    override val size: Int get() = items.size.also { seen() }

    override fun iterator(): Iterator<T> = items.iterator().also { seen() }
}
