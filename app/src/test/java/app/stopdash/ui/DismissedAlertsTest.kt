package app.stopdash.ui

import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.LineAlert
import app.stopdash.domain.LineStatus
import java.time.Instant
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
    private val marks = mutableListOf<Long>()
    private val store = object : DismissedAlertsStore {
        override fun dismissed(): Flow<Set<DismissedAlert>> = flowOf(emptySet())
        override suspend fun dismiss(alert: DismissedAlert) = Unit
        override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = error("the check's set is passed")
        override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: Long) {
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
            val read = mutableListOf<String>()
            val status = LineStatus("99", 3, "Part Suspended", shown.fullText, underWay = Watched(listOf(shown, behind), read))
            val ended = DismissedAlert.ofLineStatus(LineStatus("99", 6, "Diversion", "Bus stop 'Gamma Road' will not be served."))
            val gone = mutableListOf<Set<DismissedAlert>>()
            runBlocking(caller) {
                reconcileLineDismissals(setOf(identity(behind), ended), mapOf("99" to status), setOf("99"), now, store, worker, {}, "test", since = 7, pruned = { gone += it })
            }
            // The alert under way behind the one shown stays dismissed; the one that ended goes.
            assertEquals(listOf(setOf(ended)), gone)
            // The store lets go of only what the check saw, so one dismissed since stays stored too.
            assertEquals(listOf(setOf(identity(behind), ended)), written)
            assertEquals(listOf(7L), marks)
            assertTrue(read.isNotEmpty())
            assertEquals(setOf("worker"), read.toSet())
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
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: Long) {
                // The other screen's tap lands as the check writes.
                again = setOf(ended)
            }
            override fun stillSeen(alerts: Set<DismissedAlert>, since: Long) = alerts - again
        }
        val status = LineStatus("99", 3, "Part Suspended", shown.fullText)
        val pruned = mutableListOf<Set<DismissedAlert>>()
        val restored = mutableListOf<Set<DismissedAlert>>()
        runBlocking {
            reconcileLineDismissals(
                setOf(ended), mapOf("99" to status), setOf("99"), now, tapped, kotlinx.coroutines.Dispatchers.Unconfined, {}, "test", since = 1,
                pruned = { pruned += it }, restored = { restored += it },
            )
        }
        assertEquals(listOf(setOf(ended)), pruned)
        assertEquals(listOf(setOf(ended)), restored)
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
        val read = mutableListOf<String>()
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
        assertTrue(read.isNotEmpty())
        assertEquals(setOf("worker"), read.toSet())
    }
}

// [items], noting the thread of each read in [read].
private class ReadSet<T>(private val items: Set<T>, private val read: MutableList<String>) : AbstractSet<T>() {
    private fun seen() { synchronized(read) { read += Thread.currentThread().name.substringBefore(" @") } }

    override val size: Int get() = items.size.also { seen() }

    override fun iterator(): Iterator<T> = items.iterator().also { seen() }
}
