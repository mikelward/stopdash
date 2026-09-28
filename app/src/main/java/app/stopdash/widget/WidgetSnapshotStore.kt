package app.stopdash.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.stopdash.data.DataStoreSnapshotStore
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.Terminating
import app.stopdash.domain.WidgetJourneysReport
import kotlinx.coroutines.CancellationException

/**
 * Feeds the widget without changing the in-app view. [save] writes the app's last-good
 * snapshot to the shared [DataStoreSnapshotStore] (the file the widget reads) and pokes the
 * widget to re-render; [load] returns null so the in-app `MainViewModel` does **not** restore
 * it. That asymmetry is deliberate: the in-app nearby set is location-derived, and restoring a
 * previous location's stops under a newly-resolved set would mislead — the reason `MainActivity`
 * used `SnapshotStore.NONE`. This keeps that in-app behavior while still giving the widget data.
 * Interim until Phase 2's user-chosen watched stops make the persisted set meaningful to
 * restore in-app too.
 */
class WidgetSnapshotStore(context: Context) : SnapshotStore {
    private val appContext = context.applicationContext
    // Wire the sanitized warn sink so a discarded corrupt snapshot file is logged rather than
    // silently dropped. from() keeps the first caller's sink, so the widget's provideGlance
    // passes the same one — whichever initializes the singleton first, corruption is traced.
    private val delegate = DataStoreSnapshotStore.from(appContext, warn = ::logWidgetSnapshotWarning)

    override suspend fun load(): DeparturesSnapshot? = null

    override suspend fun saveKeepingJourneys(snapshot: DeparturesSnapshot) {
        delegate.saveKeepingJourneys(snapshot)
        pokeWidget()
    }

    override suspend fun updateWidgetJourneys(report: WidgetJourneysReport, origins: List<StopArrivals>) {
        delegate.updateWidgetJourneys(report, origins)
        pokeWidget()
    }

    override suspend fun save(snapshot: DeparturesSnapshot) {
        // The primary operation: persist the last-good snapshot. Its failure propagates to the
        // caller, which reports it as a real save failure.
        delegate.save(snapshot)
        pokeWidget()
    }

    /**
     * The compare-and-set save the background refresh uses: persist only if the stored stop set
     * still matches [expectedStopIds], and poke the widget only when the write was applied.
     * Returns false when a newer in-app snapshot (a different set — e.g. the user relocated while
     * the worker was fetching) is already stored; that write already poked the widget, so this
     * discarded stale result changes nothing and must not redraw over it (Codex P1 on #56).
     */
    override suspend fun saveIfStopsMatch(
        snapshot: DeparturesSnapshot,
        expectedStopIds: List<String>,
    ): Boolean {
        val applied = delegate.saveIfStopsMatch(snapshot, expectedStopIds)
        if (applied) pokeWidget()
        return applied
    }

    override suspend fun updateNearer(nearer: Map<String, Terminating.Nearer>) {
        if (nearer.isEmpty()) return
        // The widget hides by the stored places, so re-render once they're updated.
        delegate.updateNearer(nearer)
        pokeWidget()
    }

    override suspend fun pruneStops(departedStopIds: Collection<String>) {
        if (departedStopIds.isEmpty()) return
        // Drop the departed stops from the shared file, then re-render so the widget shows the
        // reduced set at once (a stop left the nearby set). The poke is secondary and best-effort,
        // as on the save path — the removal is already committed.
        delegate.pruneStops(departedStopIds)
        pokeWidget()
    }

    /**
     * Pokes the widget to re-render with the now-persisted snapshot. The redraw is secondary and
     * best-effort — the snapshot is already committed, so a redraw failure must NOT surface as a
     * save failure (the caller would log "snapshot save failed" for a save that in fact
     * succeeded). Log it separately, sanitized, and swallow; the widget re-renders on the next
     * successful save or host refresh. The updateAll re-runs provideGlance, which arms the
     * one-shot staleness-boundary redraw for the app-closed case (SPEC D4) — so that scheduling
     * lives on the render path, not here. Cancellation is rethrown so structured concurrency
     * isn't broken.
     */
    private suspend fun pokeWidget() {
        try {
            StopDashWidget().updateAll(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget redraw after snapshot save failed: ${e::class.simpleName}")
        }
    }
}
