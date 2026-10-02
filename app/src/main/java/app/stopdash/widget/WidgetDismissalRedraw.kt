package app.stopdash.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.stopdash.data.DataStoreAlertsBehindStore
import app.stopdash.data.DataStoreDismissedAlertsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Redraws the widget whenever the user's dismissed alerts change, for the life of the process.
 * The widget applies them when it draws ([app.stopdash.domain.DeparturesSnapshot.withDismissals]),
 * so a dismissal from any screen, or one a refresh forgets, shows on it at once. Nothing is
 * stored with the snapshot, so there is nothing else to update. The same for the app's verdicts that
 * a bus alert lies behind a stop ([app.stopdash.domain.DeparturesSnapshot.withAlertsBehind]).
 */
object WidgetDismissalRedraw {
    /** The first wait before re-reading a failed dismissed set; doubled each time, to [MAX_RETRY_MS]. */
    private const val RETRY_MS = 500L

    /** The ceiling on that wait: a store that keeps failing is retried quietly, never given up on. */
    private const val MAX_RETRY_MS = 30_000L

    /** Stands for "nothing drawn yet", so even a first set equal to nothing is drawn. */
    private val NOT_DRAWN = Any()

    /** Stands for "drawn after a failed read", so the next set read is drawn whatever it is. */
    private val READ_FAILED = Any()

    fun start(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        val store = DataStoreDismissedAlertsStore.from(appContext, warn = ::logWidgetSnapshotWarning)
        scope.launch(Dispatchers.IO) {
            redrawOnChange({ store.dismissals() }) { StopDashWidget().updateAll(appContext) }
        }
        val behind = DataStoreAlertsBehindStore.from(appContext, warn = ::logWidgetSnapshotWarning)
        scope.launch(Dispatchers.IO) {
            redrawOnChange({ behind.verdicts() }) { StopDashWidget().updateAll(appContext) }
        }
    }

    /**
     * Calls [redraw] for the first set [dismissed] gives and for each change after it. The first
     * one too: a dismissal saved just before the process died may never have reached the widget.
     * A failed read or a failed redraw restarts the collection with capped backoff, for as long as
     * the caller runs, since a dead collector would leave every later dismissal on the widget. A
     * set counts as drawn only once its redraw succeeded, so a failed one is tried again on the
     * restart; one already drawn isn't redrawn. A failed read also redraws, once per run of
     * failures: the widget counts a set it can't read as none, so the mark shows rather than a
     * warning staying hidden on what it last drew (SPEC principle 2). The next set read is then
     * drawn again, whatever it is.
     */
    internal suspend fun <T> redrawOnChange(
        dismissed: () -> Flow<T>,
        retryMs: Long = RETRY_MS,
        maxRetryMs: Long = MAX_RETRY_MS,
        redraw: suspend () -> Unit,
    ) {
        var drawn: Any? = NOT_DRAWN
        var backoff = retryMs
        while (true) {
            var redrawing = false
            try {
                dismissed().collect { set ->
                    // Reset only once the widget shows this set: a redraw that keeps failing on a
                    // re-read of the same set must keep backing off, not retry twice a second.
                    if (set != drawn) {
                        redrawing = true
                        redraw()
                        redrawing = false
                        drawn = set
                    }
                    backoff = retryMs
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (redrawing) {
                    logWidgetSnapshotWarning("widget dismissal redraw failed, retrying: ${e::class.simpleName}")
                } else {
                    logWidgetSnapshotWarning("widget dismissed read failed, retrying: ${e::class.simpleName}")
                    if (drawn !== READ_FAILED) {
                        try {
                            redraw()
                            drawn = READ_FAILED
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logWidgetSnapshotWarning("widget dismissal redraw failed, retrying: ${e::class.simpleName}")
                        }
                    }
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(maxRetryMs)
            }
        }
    }
}
