package app.stopdash.domain

import java.time.Instant

/**
 * Merges one seed stop's refresh into its prior snapshot, so a partial refresh keeps what
 * it can rather than dropping a stop wholesale (SPEC D4 / principle 2 — the
 * drop-on-partial-refresh class the whole-list snapshot suffered). Arrivals and disruption
 * merge **independently**: a stop whose arrivals failed keeps its aged departures instead
 * of vanishing, and one whose arrivals failed but disruption succeeded still surfaces its
 * fresh closure.
 *
 * Pure and clock-injected (the caller passes [now]), so the merge is JVM-testable without
 * the ViewModel or a dispatcher — the tricky per-stop bookkeeping lives here, not in the
 * coroutine.
 */
object Snapshot {
    /**
     * The stop's merged [StopArrivals], or null only when the stop is **unknown** — its
     * arrivals failed this refresh with nothing to fall back on (no prior, no fresh
     * disruption) — so the caller can surface that failure rather than show the stop as a
     * fabricated "no departures". A stop whose arrivals *succeeded* (even returning none) is
     * kept as a determinate known-empty entry.
     *
     * [freshDepartures]/[freshDisruptions] are null when that request **failed** this
     * refresh, or the list (possibly empty) when it **succeeded**. The null-vs-empty
     * distinction is load-bearing, and arrivals and disruptions treat a failure
     * differently:
     * - **Arrivals** fall back to [prior]'s on failure (aged, to be withheld once stale),
     *   and a successful *empty* replaces them (the stop genuinely has none now). Countdowns
     *   degrade gracefully — the screen withholds a stale one — so keeping them aged is safe.
     * - **Disruptions** do *not* fall back on failure — a failed fetch drops the notice. A
     *   closure is a point-in-time claim with no stale-safe rendering (a stop-status row has
     *   no countdown to withhold, and the screen shows it unconditionally), so a retained one
     *   would assert a possibly-reopened station beside a fresh arrivals stamp. The caller's
     *   `disruptionUnknown` banner carries the uncertainty instead; only a successful fetch
     *   (fresh, even empty) sets the disruptions.
     *
     * [lines] comes from the seed either way — declared lines are static per stop, so a
     * kept stop carries them for the status-row logic: a stop whose arrivals were fetched
     * but returned none (a suspended line returns none) is a known-empty stop, kept, so its
     * suspended line can surface as a status row (SPEC *Departures*). Declared lines do not
     * on their own make an *unknown* (arrivals-failed) stop worth keeping — knowledge does.
     *
     * The result's `fetchedAt` tracks the **departures'** age, which is what staleness
     * withholds: [now] when arrivals refreshed; else the prior fetch time (the departures
     * shown are that old); else — no prior and arrivals failed, so the only content is a
     * disruption fetched just now — [now]. So [now] is a fetch stamp, the steady clock's
     * ([SteadyClock.stamp]), like the prior's.
     */
    fun mergeStop(
        stopId: String,
        stopName: String,
        clusterId: String,
        lines: List<LineRef>,
        freshDepartures: List<Departure>?,
        freshDisruptions: List<StopDisruption>?,
        prior: StopArrivals?,
        now: Instant,
        // The stop's interchange (TfL `hubNaptanCode`) and its resolved display name, carried onto
        // the merged stop so a folded near-me disruption alert can title itself by the interchange
        // (SPEC *Disruptions*). Both default blank — a stop in no hub, or a caller that doesn't
        // enrich hub names — in which case the alert titles by the stop's own name.
        hubId: String = "",
        hubName: String = "",
        // The interchange's member-station spellings, resolved alongside [hubName], for the display
        // strip's alias set (SPEC *Disruptions*). Empty for a stop in no hub or a caller that
        // doesn't enrich hubs; not persisted, like the disruption.
        placeAliases: List<String> = emptyList(),
        // The bus pole's letter, bearing, and "towards", for the per-pole bus header (SPEC D8).
        // Default blank — a station, a letter-less bus stop, or a watched stop whose seed carries none.
        stopLetter: String = "",
        bearing: String = "",
        towards: String = "",
        // The stop's [Terminating.Nearer] places, saved with it for the widget's refresh.
        nearer: Terminating.Nearer = Terminating.Nearer(),
        // Why the fresh arrivals carry no National Rail times ([TflClient.railFeed]); kept from
        // [prior] when the arrivals are.
        freshRailFeed: RailFeed? = null,
    ): StopArrivals? {
        val departures = freshDepartures ?: prior?.departures ?: emptyList()
        // A failed disruption fetch drops the notice (no `?: prior`), rather than aging a
        // point-in-time closure the screen can't render as stale — see the KDoc.
        val disruptions = freshDisruptions ?: emptyList()
        // Keep the stop when we have KNOWLEDGE of it, drop it only when its arrivals FAILED
        // with nothing to fall back on. The distinguishing axis is "were the arrivals
        // fetched", not "is there content to show": a stop whose arrivals succeeded but
        // returned none is a determinate *known-empty* stop (kept, so it renders the honest
        // empty state and can't be mistaken for a failure), while a stop whose arrivals
        // failed with no prior is unknown (dropped, so the caller surfaces the offline /
        // rate-limited error instead of a fabricated "no departures"). A fresh closure or a
        // prior snapshot is knowledge too. Declared lines alone are not — a suspended-line
        // status row still needs the arrivals to have been fetched (see the KDoc).
        val known = freshDepartures != null || prior != null || disruptions.isNotEmpty()
        if (!known) return null
        val fetchedAt = when {
            freshDepartures != null -> now
            prior != null -> prior.fetchedAt
            else -> now
        }
        return StopArrivals(
            stopId = stopId,
            stopName = stopName,
            clusterId = clusterId,
            departures = departures,
            lines = lines,
            disruptions = disruptions,
            fetchedAt = fetchedAt,
            hubId = hubId,
            hubName = hubName,
            placeAliases = placeAliases,
            stopLetter = stopLetter,
            bearing = bearing,
            towards = towards,
            nearer = nearer,
            // Only a successful arrivals fetch this refresh lets a status row claim "No
            // departures"; a kept-prior or disruption-only stop has no fetched arrivals.
            arrivalsFresh = freshDepartures != null,
            railFeed = if (freshDepartures != null) freshRailFeed else prior?.railFeed,
        )
    }
}
