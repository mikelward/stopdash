package app.stopdash.ui

import app.stopdash.domain.DepartureRow
import app.stopdash.domain.LineStatus
import app.stopdash.domain.Staleness
import app.stopdash.domain.StopArrivals
import java.time.Instant

/**
 * What `MainScreen` renders — a snapshot, never the network. The screen only ever
 * reads one of these; the fetch happens off the render path in the ViewModel and
 * swaps a new value in when it completes (SPEC staleness contract / D4). Rendering
 * from a value like this is what lets the screen appear at once with a stamp or a
 * placeholder rather than blocking the first frame on a request.
 */
sealed interface DeparturesUiState {
    /** No snapshot yet — show a placeholder, not a blank screen (SPEC principle 5). */
    data object Loading : DeparturesUiState

    /**
     * The last-good snapshot: the raw [stops], each stamped with its own fetch age. The
     * screen groups them into rows against the *current* clock, not fetch time, so a
     * departed service leaves the list and the countdowns stay honest as time advances
     * between fetches (SPEC D4). Once a stop's fetch is [Staleness]-stale the screen
     * withholds *that stop's* countdowns rather than show numbers that are probably wrong,
     * per stop — a stop that failed to refresh goes to "—" while a fresh one beside it
     * stays live. [fetchedAt] is the freshest stop's age, for the whole-screen "updated N
     * ago" stamp; per-row staleness reads each stop's own age.
     *
     * A snapshot with nothing upcoming is a real state — distinct from [Error] — and the
     * screen says so. [partialRefresh] is true when some stops refreshed but at least one
     * couldn't and was kept at its older age: the list mixes fresh and aged stops, so the
     * screen flags it rather than passing a mixed-age list off as one fresh whole (SPEC
     * principle 2). [refreshFailure] is set when a later refresh got *nothing* fresh and
     * this whole aged snapshot was kept — the screen shows the failure explicitly rather
     * than passing stale rows off as fresh, and it clears on the next refresh that gets
     * anything.
     *
     * [lineStatuses] carries the disruptions found for the shown lines (keyed by line id,
     * only lines with an alert — disrupted now, or with planned work to come), so a delayed or
     * suspended line's rows are marked rather than shown as trustworthy (SPEC *Disruptions* /
     * D3), and a line with work still to come can note it. [disruptionUnknown] is true when the
     * status lookup itself failed while arrivals succeeded: the disruption state of some of
     * these departures was never checked, so the screen says so rather than pass them off as
     * verified-clean (SPEC *Disruptions*).
     *
     * [determinedLineIds] is the set of line ids TfL actually returned a status for (good or
     * disrupted). It is what tells a *specific* line's absent [lineStatuses] entry apart —
     * "checked, good service" (id present here) from "never checked" (id absent) — so a
     * per-line surface (the route detail) can say "couldn't check" for only the lines that
     * weren't checked, even when [disruptionUnknown] is set by a *different* line (a blank
     * line id, or one TfL omitted). Empty when the whole lookup failed or wasn't made.
     *
     * [stopsDisruptionUnknown] is the second, independent uncertainty axis: stop ids whose
     * *stop-level* disruption lookup (a closure, a moved stop) failed this cycle, while the
     * line-status lookup is separate and may have succeeded. A row on such a stop is never
     * "clean" even when its line was determined — a closure was never checked — so a per-stop
     * surface consults this too, not just [determinedLineIds] (SPEC principle 1). Empty when
     * every stop's disruption lookup returned.
     */
    data class Loaded(
        val stops: List<StopArrivals>,
        val fetchedAt: Instant,
        val partialRefresh: Boolean = false,
        // With [partialRefresh]: the stops that couldn't be refreshed, by id, nearest first, each with
        // its own reason, so the banner names them rather than "some stops". Per stop so every path
        // that merges or trims the set (a reveal, a relocation, an opened farther card) just unions or
        // filters by id, and the one reason the banner gives is derived in one place
        // ([partialReason]). Empty when none can be named, and the banner falls back.
        val partialStops: Map<String, FailedStop> = emptyMap(),
        // With [partialRefresh]: the list is also incomplete in a way [partialStops] can't name — a
        // stop still pending after a relocation, or one with no name — so it stays partial even
        // once every named stop is accounted for (an opened farther card showing one fresh).
        val partialUnnamed: Boolean = false,
        val refreshFailure: Error.Kind? = null,
        val lineStatuses: Map<String, LineStatus> = emptyMap(),
        val disruptionUnknown: Boolean = false,
        val determinedLineIds: Set<String> = emptySet(),
        val stopsDisruptionUnknown: Set<String> = emptySet(),
        // Stops the last fetch asked for but has nothing to show: their arrivals failed with no
        // earlier result to keep. Tells a stop that couldn't be fetched apart from one still loading
        // (a favorite journey's origin says "Couldn't check trains", not "Checking trains…").
        val unavailableStopIds: Set<String> = emptySet(),
        // A cold load still out: the stops not back yet, each shown as a collapsed "Loading" card
        // where it will land, while [stops] holds the ones that are. Never persisted (SPEC D4).
        val pendingStops: List<StopRef> = emptyList(),
        // A cold load shown before its line-status checks have all run (every stop may be in): the
        // stamp says it's still checking rather than a banner saying it couldn't.
        val statusPending: Boolean = false,
        // With [statusPending]: a check already back left something shown unchecked — a failed
        // request, a line TfL gave no status for, a stop's closure check, a departure with no line —
        // and the rest of the load won't ask again, so the banner says it couldn't check rather
        // than the stamp saying it's still checking.
        val checkFailed: Boolean = false,
        // A cold load's stops shown with their departures while their own closure check is still out:
        // each heading shows a spinner where a closure notice would be, until it's back. Never
        // persisted, like [pendingStops].
        val closurePending: Set<String> = emptySet(),
        // The stops of an opened farther card whose own cold load is still out, landed or not: its
        // card keeps saying "Loading" until they all settle, rather than a dash for one back empty.
        val openedLoadingStopIds: Set<String> = emptySet(),
        // With [statusPending]: the shown lines whose status check is still out, so a row on one reads
        // "checking" while a row whose check is already back undetermined reads "couldn't check".
        // Worked out with the state, so the screen only looks rows up in it.
        val pendingLineIds: Set<String> = emptySet(),
        // When each of [determinedLineIds] was answered, by the steady clock: a line's check ages on its
        // own, so a stop's arrivals kept from before don't make its line's fresh check old (Codex, #623).
        val lineCheckedAt: Map<String, Instant> = emptyMap(),
    ) : DeparturesUiState {
        /**
         * The one reason the banner gives: the one every named stop failed with, else null — a stop
         * whose cause is unknown (a restored snapshot) or different means no single reason is true
         * of them all, so the banner names the stops without one (SPEC principle 2).
         */
        val partialReason: Error.Kind?
            get() = partialStops.values.mapTo(HashSet()) { it.reason }.singleOrNull()

        /**
         * Some of what's shown is still to be checked for disruptions — a line's status, or a stop's
         * own closure check ([closurePending]) — and nothing checked so far failed: the stamp says
         * "Checking…" rather than a banner (SPEC *Freshness → Cold load*). It says so for a closure
         * check too, since a list of one place may show no heading for its spinner.
         */
        val checkingDisruptions: Boolean
            get() = statusPending && (disruptionUnknown || closurePending.isNotEmpty()) && !checkFailed

        /**
         * Whether [row]'s own disruption check is still out: its stop's closure check, or its line's
         * status on a cold load with neither back failed. Per row, so another stop's failed check
         * doesn't make this one read "couldn't check" before its own is back.
         */
        fun checkingDisruptionsFor(row: DepartureRow): Boolean =
            row.stopId in closurePending || (checkingLineFor(row) && row.stopId !in stopsDisruptionUnknown)

        /**
         * Whether [row]'s line's own status went unchecked: a blank id, or one TfL gave no status for.
         * Its stop's closure check aside, as its line's page has it ("View line", Codex, #623).
         */
        fun lineUncheckedFor(row: DepartureRow): Boolean = row.lineId.isBlank() || row.lineId !in determinedLineIds

        /**
         * Whether [row]'s line's own status can't be stood behind at [now]: unchecked ([lineUncheckedFor]), or
         * its check gone stale, aged by its own stamp ([lineCheckedAt]) where it has one, else as old as the
         * row (SPEC D4).
         */
        fun lineDoubtedFor(row: DepartureRow, now: Instant): Boolean =
            lineUncheckedFor(row) || Staleness.isStale(lineCheckedAt[row.lineId] ?: row.fetchedAt, now)

        /** Whether [row]'s line's own status check is still out on a cold load, its stop's aside. */
        fun checkingLineFor(row: DepartureRow): Boolean = statusPending && row.lineId in pendingLineIds
    }

    /** A stop that couldn't be refreshed: its [name], and [reason] if known. */
    data class FailedStop(val name: String, val reason: Error.Kind? = null)

    /**
     * The fetch failed with no snapshot to fall back on, shown honestly rather than as
     * an empty or stale list (SPEC principles 1–2). Once persistence lands (Phase 1's
     * snapshot item) a failure keeps showing the aged last-good data instead.
     */
    data class Error(val kind: Kind) : DeparturesUiState {
        // NETWORK: online but the request didn't complete; SERVER: TfL answered with an error;
        // KEY_REJECTED: TfL refused the user's own key (SPEC D7), which only clearing it mends.
        enum class Kind { OFFLINE, RATE_LIMITED, NETWORK, SERVER, KEY_REJECTED }
    }
}
