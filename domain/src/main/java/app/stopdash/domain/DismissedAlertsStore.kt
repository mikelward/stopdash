package app.stopdash.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Reads and writes the persisted **dismissed alerts** — the stop-closure cards the user has tapped
 * away, hidden until their content changes ([DepartureRows.withoutDismissed]). A seam (interface)
 * so a ViewModel depends on the capability, not on DataStore, and a JVM test can supply a fake
 * without Android. The concrete DataStore-backed implementation lives in the `data` layer.
 *
 * [dismissed] is a cold [Flow] the caller collects: it emits the current set at once and again on
 * every change, so a card disappears the moment the user dismisses it. Unlike the starred store
 * there is **no "unavailable" state** — a set this build can't read (a newer schema, a corrupt
 * file) reads back **empty**, which fails *safe* for a dismissal: the worst case is an
 * already-dismissed card reappearing, never a warning hidden by a set we couldn't verify (SPEC
 * principle 2). [dismiss] is suspending and meant to run off the main thread; it is best-effort.
 */
interface DismissedAlertsStore {
    /** The current dismissed set, re-emitted on every change; empty when nothing is stored or the
     *  stored set is unreadable (fails safe — a dismissed card returns rather than a warning hides). */
    fun dismissed(): Flow<Set<DismissedAlert>>

    /**
     * The dismissals the widget and the watch apply to what the widget has stored: [dismissed],
     * plus the ended line dismissals kept only for the widget's old copy of their alert
     * ([Dismissals.ended]). Those aren't in [dismissed], so a screen showing what it fetched never
     * hides a recurrence with one.
     */
    fun dismissals(): Flow<Dismissals> = dismissed().map { Dismissals(it) }

    /** Record [alert] as dismissed ([Dismissed.dismiss]: add-only; [reconcile] prunes obsolete ones). */
    suspend fun dismiss(alert: DismissedAlert)

    /**
     * Prune the stored set against the notices still shown ([Dismissed.reconcile]): for a place in
     * [checkedPlaces] (queried this cycle, disruption lookup succeeded), drop any dismissal whose
     * signature is no longer in [live] — its notice resolved. A place not in [checkedPlaces] is left
     * alone, so a dismissal never lapses because its place wasn't looked at. Best-effort; a no-op
     * write when the set is unchanged.
     */
    suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>)

    /**
     * How many dismissals this store has recorded so far, each counted as it's made: read before a
     * check asks anything and handed back to [reconcile] as `since`, it tells a dismissal newer than
     * the check's verdict, never let go of on it, from an older one.
     */
    fun mark(): Long = 0L

    /**
     * Of [alerts], the ones not dismissed again since [mark] `since`: what a check that read its set
     * then may still let go of, in memory as in the store. Asked as the in-memory set is updated, so a
     * tap that adds an alert back (counted before it's added) is never taken away by an older prune.
     */
    fun stillSeen(alerts: Set<DismissedAlert>, since: Long): Set<DismissedAlert> = alerts

    /**
     * Of [alerts], the ones dismissed again since [mark] `since` and written: what a check that let
     * them go of should take back. Waits for any such dismissal still being written, so one whose
     * write fails is never taken back as made.
     */
    suspend fun dismissedAgain(alerts: Set<DismissedAlert>, since: Long): Set<DismissedAlert> =
        alerts - stillSeen(alerts, since)

    /**
     * [reconcile], letting go only of dismissals in [seen], the set the check settled in memory, and
     * not made again since [mark] `since`, read before the check asked anything: one made since (of a
     * notice a newer check found back), even of the same alert again, isn't written away by the
     * older check's verdict.
     */
    suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: Long) =
        reconcile(live, checkedPlaces)

    companion object {
        /** A store that persists nothing and always reads an empty set — the default for tests and a
         *  build with no wired DataStore, so the app runs identically minus dismissing. */
        val NONE: DismissedAlertsStore = object : DismissedAlertsStore {
            override fun dismissed(): Flow<Set<DismissedAlert>> = flowOf(emptySet())
            override suspend fun dismiss(alert: DismissedAlert) {}
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {}
        }
    }
}
