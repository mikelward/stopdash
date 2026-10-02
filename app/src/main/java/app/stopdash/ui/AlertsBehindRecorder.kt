package app.stopdash.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.stopdash.domain.AlertBehind
import app.stopdash.domain.AlertPlacement
import app.stopdash.domain.AlertsBehindStore
import kotlinx.coroutines.CancellationException

/**
 * Keeps the list's verdicts that a bus alert lies wholly behind a stop ([AlertBehind]) for the widget
 * and the watch, which have no routes to reach them ([app.stopdash.domain.DeparturesSnapshot.withAlertsBehind]).
 * A write that fails is logged (sanitized) and dropped: those surfaces then flag the alert, as they
 * did before any was placed, the safe way (SPEC principle 2).
 */
class AlertsBehindRecorder(private val store: AlertsBehindStore, private val warn: (String) -> Unit) {
    suspend fun record(placement: AlertPlacement) {
        try {
            store.record(placement)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("alerts-behind write failed for ${placement.weighed.size} alert(s): ${e::class.simpleName}")
        }
    }
}

/** Where the near-me list keeps its verdicts; null keeps none (a test, a station page). */
val LocalAlertsBehind = staticCompositionLocalOf<AlertsBehindRecorder?> { null }
