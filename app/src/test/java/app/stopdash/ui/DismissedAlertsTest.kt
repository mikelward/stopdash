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

    private val store = object : DismissedAlertsStore {
        override fun dismissed(): Flow<Set<DismissedAlert>> = flowOf(emptySet())
        override suspend fun dismiss(alert: DismissedAlert) = Unit
        override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = Unit
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
                reconcileLineDismissals(setOf(identity(behind), ended), mapOf("99" to status), setOf("99"), now, store, worker, {}, "test") { gone += it }
            }
            // The alert under way behind the one shown stays dismissed; the one that ended goes.
            assertEquals(listOf(setOf(ended)), gone)
            assertTrue(read.isNotEmpty())
            assertEquals(setOf("worker"), read.toSet())
        } finally {
            caller.close()
            worker.close()
        }
    }
}
