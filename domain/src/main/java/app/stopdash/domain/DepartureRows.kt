package app.stopdash.domain

import java.time.Instant

/**
 * Groups a stop's [Departure]s into the flat list's rows (SPEC D8): one
 * [DepartureRow] per (line, direction) at the stop, each carrying that group's
 * not-yet-departed departures soonest-first. Pure and clock-free — the caller
 * supplies [now] — so it stays JVM-testable and off any render path.
 *
 * The stop is the caller's context: the TfL client fetches per stop, so a row is
 * stamped with the [stopId]/[stopName] the caller already holds rather than the
 * domain having to carry stop identity on every [Departure].
 */
object DepartureRows {
    /**
     * Rows for one stop's [departures], soonest-first overall. Departed services are
     * dropped first (via [Countdown.upcoming]), so a (line, direction) group whose
     * every departure has gone yields no row rather than an empty one. Rows are
     * ordered by their soonest departure, ties broken by line then direction for a
     * stable order independent of input order.
     */
    fun forStop(
        stopId: String,
        stopName: String,
        departures: List<Departure>,
        now: Instant,
        lineStatuses: Map<String, LineStatus> = emptyMap(),
        // Defaults to `now` (rows "as of now", stamped as a fetch is, [SteadyClock]) so a grouping-only
        // caller need not supply it; `across` passes the stop's own fetch age so the screen can
        // withhold per stop.
        fetchedAt: Instant = SteadyClock.stamp(now),
        // The stop's cluster (TfL `stationNaptan` else display name), stamped on each row so the
        // screen groups by place rather than name (SPEC D8). Blank groups the stop alone.
        clusterId: String = "",
        // The bus pole's letter, bearing, and "towards", stamped on each timed row so a bus place
        // splits into one header per pole (SPEC D8). Blank for a station or a letter-less stop.
        stopLetter: String = "",
        bearing: String = "",
        towards: String = "",
        // Whether a rail (line, direction) splits into one row per platform ([byPlatform]). The
        // in-app list does, so each platform gets its own header; the widget, which has no
        // per-platform headers, keeps one merged row per direction.
        splitPlatforms: Boolean = true,
        // Each line's mode as the stop advertises it: a row whose predictions all leave the mode
        // blank (TfL can omit `modeName`) takes it from here, so hidden modes and line colors hold.
        lineModes: Map<String, String> = emptyMap(),
    ): List<DepartureRow> {
        // upcoming() has already dropped departed services and sorted soonest-first;
        // groupBy preserves that encounter order within each group.
        // Directions are inferred over the whole snapshot, before departed services drop, so a
        // blank train keeps its inferred row (and a star pinned to it) after the tagged ones leave.
        // The client already infers at fetch time; this covers a snapshot saved before it did.
        val live = Countdown.upcoming(inferDirections(departures), now)
        val folded = foldedUnknownPlatforms(live)
        return live.groupBy { RowKey(it.lineId, directionKeyOf(it)) }
            .flatMap { (key, directionGroup) ->
                val mode = resolvedMode(directionGroup, lineModes[key.lineId])
                byPlatform(directionGroup, splitPlatforms, folded).map { (platform, group) -> RowGroup(key, platform, group, mode) }
            }
            .map { (key, platform, group, mode) ->
                val soonest = group.first()
                DepartureRow(
                    stopId = stopId,
                    stopName = stopName,
                    clusterId = clusterId,
                    stopLetter = stopLetter,
                    bearing = bearing,
                    towards = towards,
                    lineId = key.lineId,
                    lineName = soonest.lineName,
                    direction = soonest.direction,
                    directionKey = key.directionKey,
                    platform = platform,
                    destination = soonest.destination,
                    mode = mode,
                    upcoming = group,
                    fetchedAt = fetchedAt,
                    // Marks the row only when the line is actually disrupted — a
                    // good-service (or unlooked-up) line leaves it null, so a non-null
                    // status always means "flag this" (SPEC *Disruptions* / D3).
                    // The row's own direction's alerts only: a diversion the other way round
                    // doesn't touch these buses (TfL's affected-route direction, [LineStatus.forDirection]).
                    status = lineStatuses[key.lineId]?.forDirection(soonest.direction)?.takeIf(LineStatus::disrupted),
                    plannedAlerts = lineStatuses[key.lineId]?.forDirection(soonest.direction)?.planned.orEmpty(),
                )
            }
            .sortedWith(rowOrder)
    }

    /**
     * The flat departures list across several stops (SPEC D8): every stop's rows in
     * one soonest-first list, so the next thing to leave — whichever stop it is at —
     * is at the top. Each [StopArrivals] is grouped by [forStop], then the rows are
     * merged and re-sorted by the same [rowOrder]. Ordering is location-free (D1): the
     * list works with location denied. Starred rows are pinned to the top by the
     * caller in Phase 2, not here.
     */
    fun across(
        stops: List<StopArrivals>,
        now: Instant,
        lineStatuses: Map<String, LineStatus> = emptyMap(),
        splitPlatforms: Boolean = true,
        // Keep a disrupted line's status row once its stop's arrivals are old or carried. A glance
        // surface (the widget, the watch) passes only statuses whose own checks are live and draws
        // a status row as the status alone, claiming nothing about departures, so the suspension
        // stays marked until its check expires rather than until the arrivals do. The in-app list
        // doesn't: its status row asserts "No departures", which needs current arrivals.
        statusRowsWhenStale: Boolean = false,
    ): List<DepartureRow> {
        // Planned work whose day has come shows as under way, however long ago it was fetched.
        val lineStatuses = LineStatus.asOf(lineStatuses, now)
        return stops.flatMap { stop ->
            // A service ending no farther from the rider than this stop goes nowhere for them
            // ([Terminating]). Hidden here, as the rows are built, rather than dropped from the
            // stop's data, so a new location (a new [StopArrivals.nearer]) applies at once and the
            // widget, which renders through here too, hides the same ones.
            // Directions are inferred first, over the whole stop, so a hidden terminating train
            // still lends its direction to a kept one on its platform, as in [shows].
            val shown = Terminating.drop(inferDirections(stop.departures), stop.nearer)
            // The board's trains with no time, by the same rule: a canceled train ending here goes
            // nowhere for the rider either. One canceled goes at its scheduled time ([Countdown.stillShown]).
            val untimed = Terminating.drop(stop.untimed.map { it.train }, stop.nearer).toHashSet()
                .let { kept -> stop.untimed.filter { it.train in kept && Countdown.stillShown(it, now) } }
            val timed = withUntimed(
                forStop(
                    stop.stopId, stop.stopName, shown, now, lineStatuses, stop.fetchedAt,
                    stop.clusterId, stop.stopLetter, stop.bearing, stop.towards, splitPlatforms,
                    lineModes = stop.lines.associate { it.id to it.mode },
                ),
                untimed,
            )
            // A line whose every live prediction was hidden has departures, just none that help: it
            // mustn't surface as a "No departures" status row. Counted over live predictions only,
            // like the timed rows, so an expired onward one doesn't make it look shown.
            val hiddenLines = Countdown.upcoming(stop.departures, now).mapTo(HashSet()) { it.lineId } -
                Countdown.upcoming(shown, now).mapTo(HashSet()) { it.lineId }
            // A synthesized line-status row asserts "No departures", which is only true when
            // this stop's arrivals were actually fetched AND are still current: fetched (not
            // a disruption-only stop stamped `now` with no arrivals, nor one carried from a
            // prior) and not stale (a delayed line's predictions may have merely expired, not
            // stopped). Otherwise it's suppressed and the stop falls to the screen's stale
            // empty-state prompt (SPEC principle 1). Timed rows (withheld once stale) and a
            // fresh stop-status closure still show.
            val status =
                if (statusRowsWhenStale || (stop.arrivalsFresh && !isStale(stop.fetchedAt, now))) {
                    statusRows(stop, timed, lineStatuses, hiddenLines)
                } else {
                    emptyList()
                }
            stopStatusRow(stop, now) + timed + status
        }.sortedWith(rowOrder)
    }

    /**
     * Split [row]'s upcoming departures into per-destination lines for rendering, soonest group
     * first, with each line's countdowns capped to the next [maxTimes] so a card or widget shows
     * a bounded few *per destination*. A non-branching row yields a single group.
     *
     * **Group first, cap within a group** — [maxTimes] bounds the countdowns on each line, not
     * the flat list before grouping. Capping first would drop a divergent destination whose
     * soonest train is beyond the first [maxTimes] overall (three imminent Morden trains then a
     * Battersea): the Battersea line would vanish though it's a valid service (SPEC D8). Every
     * group therefore survives; only the times *within* each are bounded.
     *
     * Groups come back soonest-first: [row]'s upcoming is soonest-first and `groupBy` keeps
     * first-encounter order, so the soonest departure's group leads and the rest follow by their
     * own soonest.
     *
     * **The via-branch is grouped by [topology], not by its raw string** (SPEC D8). One terminus
     * reached by two trunks (Edgware via Bank and via Charing Cross) is split into two labeled
     * lines *only where the trunk is still a choice ahead of this stop*; where the trunks have
     * already met (or not yet split) the two are the same service from here, so they merge into
     * one line and the now-meaningless branch label is dropped — a countdown still never sits
     * under the wrong destination or branch, and a rider isn't shown a distinction that makes no
     * difference from where they're standing. With [RouteTopology.EMPTY] (the default) every
     * branch keeps its raw label and nothing merges, the pre-topology behavior. Shared by the
     * in-app card and the widget so the two surfaces group a branching row identically and
     * neither can drift from the other.
     */
    fun destinationLines(
        row: DepartureRow,
        maxTimes: Int,
        topology: RouteTopology = RouteTopology.EMPTY,
    ): List<DestinationGroup> {
        fun keyOf(departure: Departure) =
            departure.destination to topology.grouping(row.lineId, row.stopId, departure.destination, departure.branch).mergeKey
        // A train with no time joins its destination's line among the timed trains, and only that:
        // one with none there isn't drawn on its own (TODO, *Show delays and cancellations honestly*).
        val untimed = row.untimed.groupBy { keyOf(it.train) }
        return row.upcoming
            .map { it to topology.grouping(row.lineId, row.stopId, it.destination, it.branch) }
            .groupBy { (departure, grouping) -> departure.destination to grouping.mergeKey }
            .map { (key, entries) ->
                // The group's times and its trains with no time in one order, capped together, so a
                // canceled train shows in its place among the next few rather than past them.
                val times = entries.map { it.first }
                val shown = Countdown.entries(times, untimed[key].orEmpty()).take(maxTimes)
                // All entries in a group share a merge key (the same forward path from this stop),
                // so they also share the branch label the group resolved to.
                DestinationGroup(
                    key.first,
                    entries.first().second.label,
                    shown.mapNotNull { (it as? Countdown.Entry.Timed)?.departure },
                    shown.mapNotNull { (it as? Countdown.Entry.Untimed)?.train },
                )
            }
    }

    /**
     * [rows] (one stop's timed rows, soonest first) each with the trains of its line and way that [untimed]
     * holds: those its row's direction key names ([directionKeyOf], a platform or a destination), else the
     * soonest row of the line with a timed train to the same destination. One with neither isn't drawn
     * (TODO, *Show delays and cancellations honestly*).
     */
    internal fun withUntimed(rows: List<DepartureRow>, untimed: List<UntimedTrain>): List<DepartureRow> {
        if (untimed.isEmpty()) return rows
        val joined = untimed.groupBy { train ->
            rows.indexOfFirst { it.lineId == train.train.lineId && it.directionKey == directionKeyOf(train.train) }
                .takeIf { it >= 0 }
                ?: rows.indexOfFirst { row -> row.lineId == train.train.lineId && row.upcoming.any { it.destination == train.train.destination } }
        }
        return rows.mapIndexed { i, row ->
            joined[i]?.let { row.copy(untimed = it.sortedBy { train -> train.train.expectedArrival }) } ?: row
        }
    }

    /**
     * Collapse the "near me now" rows so a **(line, direction)** appears once — from the
     * **nearest** stop serving it — instead of once per adjacent stop it passes (SPEC
     * *Finding stops → Near me now*): a bus route stopping three times within the radius
     * should read as one service, not three. Both directions survive (they are distinct
     * `(lineId, directionKey)`), and nothing is dropped by a count cap — the result is
     * distance-shaped, so a mode with only one nearby stop (the lone Tube among many bus
     * stops) is never crowded out.
     *
     * Distance is the nearby flow's input, not a row's business — [across] stays
     * location-free (D1) — so it arrives as [stopDistanceMeters] (`stopId` → meters). A
     * stop missing from the map sorts last, and equal distances break by `stopId`, so the
     * kept stop never depends on input order.
     *
     * **Stop-level status rows are folded per place.** One disruption is reported by TfL against
     * several stop points, so the near-me set otherwise shows the identical card once per point: a
     * hub-wide lift outage against both St Pancras International and King's Cross St. Pancras, or a
     * closed bus stop reported against each pole of one junction (a bus stop reported both ways). The
     * notice is kept once — on the **nearest** point — keyed on `(place, normalized text)`, where
     * `place` is the coarsest identity that still holds: the interchange ([DepartureRow.hubId],
     * `HUBKGX`) when there is one, else a **real StopArea** cluster ([DepartureRow.clusterId] when it
     * is TfL's `stationNaptan`, not the display-name fallback — the poles of a junction and the
     * platforms of a station share it), else the stop alone. Folding at that identity is what
     * collapses both the interchange and the bus-junction cases while keeping two genuinely distinct
     * places apart: an identical place-less notice ("Station closed") at two unrelated stops, or a
     * generic "Bus Stop Closed" at two same-named stops that only share a name-fallback cluster,
     * never collapses — each keeps its card (SPEC principle 1 — no warning dropped). A place with two
     * different notices keeps a card for each. The kept card titles itself by the interchange, else
     * the stop.
     * Line rows (timed and line-status) are deduped separately by their cross-stop (line,
     * direction) key. Survivors are re-sorted by [rowOrder].
     *
     * The dedupe identity is **cross-stop**: line + TfL's `direction`, *not* the row's
     * [DepartureRow.directionKey], which for a timed row can be the stop-local platform
     * ([directionKeyOf]) — the same line and direction at adjacent stops would then get
     * different keys and both survive, defeating the collapse (Codex). A row whose
     * `direction` is blank has no cross-stop identity and stays stop-specific (see
     * [dedupeKeyOf]); a status row keeps its cross-stop-stable sentinel key. Every row of the
     * *nearest* stop for a key is kept — not just one — so a single stop with two platforms
     * of one service (distinct rows at that stop) keeps both, while a farther stop's
     * duplicate is dropped.
     */
    fun nearbyDeduped(
        rows: List<DepartureRow>,
        stopDistanceMeters: Map<String, Double>,
        // The alerts the user has dismissed, which the screen hides after this fold: a dismissed line
        // warning no longer gives its pole a header of its own, so the rule keying places by header
        // has to see the list as it will be shown.
        dismissed: Set<DismissedAlert> = emptySet(),
    ): List<DepartureRow> {
        fun distanceOf(stopId: String): Double = stopDistanceMeters[stopId] ?: Double.MAX_VALUE
        val (stopStatus, lineRows) = rows.partition { it.stopDisruption != null }
        // The nearest stop serving each cross-stop (line, direction) key.
        val nearestStopByKey = HashMap<RowKey, String>()
        for (row in lineRows) {
            val key = dedupeKeyOf(row)
            val incumbent = nearestStopByKey[key]
            if (incumbent == null || isCloserStop(row.stopId, incumbent, ::distanceOf)) {
                nearestStopByKey[key] = row.stopId
            }
        }
        keepDirectionsTogether(lineRows, nearestStopByKey, stopStatus.mapTo(HashSet()) { it.stopId }, dismissed, ::distanceOf)
        // Keep every row from the nearest stop for its key, so two platforms of one service
        // at a single stop both survive; a farther stop's same-service row is dropped.
        val kept = lineRows.filter { nearestStopByKey[dedupeKeyOf(it)] == it.stopId }
        return (foldedStopStatus(stopStatus, ::distanceOf) + kept).sortedWith(rowOrder)
    }

    /**
     * How much farther a place's stop may be than a direction's nearest for the route's directions to
     * stay together there (maintainer, 2026-09-25).
     */
    const val TOGETHER_SLACK_METERS = 50.0

    /**
     * Where a line's directions would come from two places — northbound at one stop pair, southbound
     * at another, because each direction takes its own nearest pole — move them to one place that
     * serves every direction, when that place's stop for each is within [TOGETHER_SLACK_METERS] of
     * the direction's nearest (maintainer, 2026-09-25): two equally near stop pairs otherwise split a
     * route across two headers on a few meters' difference. Of several such places, the one whose
     * farthest stop is nearest. A place is the header the list will group the stop under
     * ([StopGrouping.clusterKeyOf]: a road's pole pair shares one stop area, while a stop showing a
     * line warning the user hasn't dismissed heads its own), not the interchange-wide identity [stopPlaceKey] folds notices
     * by, and only timed rows with a line and TfL direction take part. A direction never moves onto a
     * stop with a notice in force ([noticed]: closed, or moved) — TfL can still list times at a
     * closed pole — so a direction's open nearest stop keeps it. Updates [nearestStopByKey].
     */
    private fun keepDirectionsTogether(
        lineRows: List<DepartureRow>,
        nearestStopByKey: HashMap<RowKey, String>,
        noticed: Set<String>,
        dismissed: Set<DismissedAlert>,
        distanceOf: (String) -> Double,
    ) {
        val timed = lineRows.filter { it.upcoming.isNotEmpty() && it.lineId.isNotBlank() && it.direction.isNotBlank() }
        // The header each stop will sit under, worked out by the same code the screen runs: the rows
        // this fold keeps (warning rows aren't moved here), less the alerts the screen then hides
        // ([withoutDismissed]), grouped by [StopGrouping]'s own rule. Only warnings decide it; the
        // screen's later steps drop timed rows alone ([withoutShownAbove]).
        val kept = lineRows.filter { nearestStopByKey[dedupeKeyOf(it)] == it.stopId }
        val warned = StopGrouping.warnedStopsOf(withoutDismissed(kept, dismissed))
        fun headerPlaceOf(row: DepartureRow) = StopGrouping.clusterKeyOf(row, warned)
        for ((_, rows) in timed.groupBy { it.lineId }) {
            val keys = rows.mapTo(LinkedHashSet()) { dedupeKeyOf(it) }
            if (keys.size < 2) continue
            // Already one place: nothing to move.
            val places = keys.mapTo(HashSet()) { key -> headerPlaceOf(rows.first { it.stopId == nearestStopByKey[key] }) }
            if (places.size < 2) continue
            // Each place's nearest stop for each of the line's directions.
            val byPlace = HashMap<String, HashMap<RowKey, String>>()
            for (row in rows) {
                val poles = byPlace.getOrPut(headerPlaceOf(row)) { HashMap() }
                val key = dedupeKeyOf(row)
                val incumbent = poles[key]
                if (incumbent == null || isCloserStop(row.stopId, incumbent, distanceOf)) poles[key] = row.stopId
            }
            val choice = byPlace.values
                .filter { poles -> poles.keys.containsAll(keys) }
                .filter { poles -> keys.none { key -> poles.getValue(key).let { it != nearestStopByKey[key] && it in noticed } } }
                .filter { poles ->
                    keys.all { key -> distanceOf(poles.getValue(key)) - distanceOf(nearestStopByKey.getValue(key)) <= TOGETHER_SLACK_METERS }
                }
                .minByOrNull { poles -> keys.maxOf { distanceOf(poles.getValue(it)) } }
                ?: continue
            for (key in keys) nearestStopByKey[key] = choice.getValue(key)
        }
    }

    /**
     * [rows] with each stop disruption notice kept once **per place** ([nearbyDeduped]'s fold), and
     * every other row as it was, in order. For a list without distances — a searched station's page,
     * the watched list — where a hub-wide notice (a lift out at King's Cross St. Pancras) would
     * otherwise repeat once per member stop; the card kept is the first member's, with the whole
     * group's dismissal windows. The line rows aren't deduped: a station's page shows every stop.
     */
    fun stopStatusFolded(rows: List<DepartureRow>): List<DepartureRow> {
        val groups = rows.filter { it.stopDisruption != null }.groupBy { stopPlaceKey(it) to it.stopDisruption }
        val emitted = HashSet<Pair<String, String?>>()
        return rows.mapNotNull { row ->
            if (row.stopDisruption == null) return@mapNotNull row
            val key = stopPlaceKey(row) to row.stopDisruption
            if (!emitted.add(key)) return@mapNotNull null
            val windows = foldedWindows(groups.getValue(key))
            if (windows == row.stopDisruptionWindows) row else row.copy(stopDisruptionWindows = windows)
        }
    }

    /** [stopStatus] folded to one card per (place, notice), on the member nearest by [distanceOf]. */
    private fun foldedStopStatus(stopStatus: List<DepartureRow>, distanceOf: (String) -> Double): List<DepartureRow> {
        // Fold each disruption notice to one card **per place**, on the nearest member: TfL reports
        // one notice against several stop points — a hub-wide lift outage against every member of an
        // interchange, or a closed bus stop against each pole of one junction. Keying on the coarsest
        // place identity that still holds — the hub ([DepartureRow.hubId]) when there is one, else the
        // cluster ([DepartureRow.clusterId]), else the stop's own id — plus the normalized notice text is
        // what folds both cases while keeping two genuinely distinct places apart: two unrelated stops
        // with an identical place-less notice ("Station closed") have different names/clusters, so they
        // never collapse and each keeps its own card (SPEC *Disruptions*, principle 1 — no warning
        // dropped). A place with two different notices keeps a card for each.
        val statusByPlaceNotice = LinkedHashMap<Pair<String, String>, MutableList<DepartureRow>>()
        for (row in stopStatus) {
            val text = row.stopDisruption ?: continue
            // The place identity, coarsest that still holds: the interchange (`hubNaptanCode`) when
            // there is one, else a real StopArea cluster (`stationNaptan`), else the stop alone.
            // Folding at the StopArea is what collapses a closed bus stop reported once per pole
            // (both ways) that shares no hub, while two genuinely distinct places keep their own
            // cards even with identical place-less text (SPEC *Disruptions*, principle 1). The
            // notice keyed on is the **normalized** body, not the name-stripped one — the strip is
            // per-member (see stopStatusRow), so keying on it would split a shared name-led notice
            // across a hub's differently-named members (Codex). The place key ([stopPlaceKey]) folds
            // on the interchange, else a real StopArea, else the stop — never the display-name
            // fallback, which would collapse two unrelated same-named stops (Codex, principle 1).
            // Shared with the dismissal key so fold and dismiss agree on what "one place" is.
            statusByPlaceNotice.getOrPut(stopPlaceKey(row) to text) { mutableListOf() }.add(row)
        }
        return statusByPlaceNotice.values.map { group ->
            val nearest = group.minWith(compareBy({ distanceOf(it.stopId) }, { it.stopId }))
            // The folded card's dismissal windows are the whole group's, not the nearest member's:
            // members can carry slightly different windows for one notice, and which member is
            // nearest changes as the user moves — that alone must not undo a dismissal (Codex).
            val windows = foldedWindows(group)
            if (windows == nearest.stopDisruptionWindows) nearest else nearest.copy(stopDisruptionWindows = windows)
        }
    }

    /**
     * The dismissal windows of one folded (place, notice) card: every member row's entries, distinct
     * and sorted, so the card's identity doesn't depend on which member is nearest.
     */
    private fun foldedWindows(group: List<DepartureRow>): String =
        group.flatMap { it.stopDisruptionWindows.split(WINDOW_ENTRY_SEPARATOR) }
            .filter { it.isNotEmpty() }.distinct().sorted().joinToString(WINDOW_ENTRY_SEPARATOR)

    /**
     * Every dismissal identity a stop-closure card built from [rows] can carry: each row's own, plus
     * each (place, notice) group's folded one ([nearbyDeduped]). Reconciliation keeps a dismissal
     * that matches any of these, so a folded near-me card's dismissal isn't pruned for being
     * absent from the unfolded rows (Codex). Extra identities only retain, never hide, a card.
     */
    fun liveStopClosureAlerts(rows: List<DepartureRow>): Set<DismissedAlert> =
        rows.filter { it.stopDisruption != null }
            .groupBy { stopPlaceKey(it) to it.stopDisruption }
            .values
            .flatMapTo(mutableSetOf()) { group ->
                group.map { DismissedAlert.ofStopClosure(it) } +
                    DismissedAlert.ofStopClosure(group.first().copy(stopDisruptionWindows = foldedWindows(group)))
            }

    /**
     * Reorder the "near me now" rows **closest stop first** — the nearest stop's services at
     * the top, down to the farthest (SPEC *Finding stops → Near me now*). Distance is the sort;
     * rows at the **same stop** (several lines at one stop, or both directions of one line — all
     * identical distance) can't be ordered by distance, so they break the tie **soonest-first**,
     * which never moves a near stop below a far one. Two *distinct* stops that compute an equal
     * distance are kept grouped by stop id (before arrival time), so soonest-first stays a
     * strictly same-stop tiebreak and their rows never interleave. A stop missing from
     * [stopDistanceMeters] sorts last (`Double.MAX_VALUE`).
     *
     * **A line-status alert rides with its stop and gets no special order** (maintainer,
     * 2026-09-22): it is neither hoisted above a closer stop nor lifted within its own stop's
     * section — a no-countdown alert row sorts *after* the stop's timed departures (`Instant.MAX`),
     * so the alert simply trails its stop's services rather than leading them for being an alert.
     * (Stop *closures* still lead the distance sort — they render as standalone cards ahead of the
     * groups today, SPEC *Disruptions*; making a closure a normal stop section is a `TODO.md`
     * follow-up.) **Starred rows are lifted afterward by [pinStarred]**, whose sort is stable so this
     * closest-first order carries through within each band. Applied only on the near-me path
     * (distances present); the location-free watched list keeps its soonest-first [across] order
     * (D1 — distance ranking is for *finding* stops, not the watched list). Distance arrives as
     * [stopDistanceMeters] (`stopId` → meters), the nearby flow's input.
     *
     * This is the current near-me display experiment (closest-first, soonest same-stop tiebreak),
     * to be judged on a device against the earlier two-band lean — see `TODO.md`.
     */
    fun byStopDistance(
        rows: List<DepartureRow>,
        stopDistanceMeters: Map<String, Double>,
    ): List<DepartureRow> {
        fun distanceOf(stopId: String): Double = stopDistanceMeters[stopId] ?: Double.MAX_VALUE
        return rows.sortedWith(
            // Stop notices first (0), then everything else — a line-status alert included — by
            // distance (1), so an alert never hoists its stop above a closer one. The notices' order
            // here doesn't place them on screen: the list draws each with its own place, at the
            // place's distance (SPEC *Disruptions*).
            compareBy<DepartureRow> { if (it.stopDisruption != null) 0 else 1 }
                .thenBy { distanceOf(it.stopId) }
                // Group two *distinct* stops that compute an equal distance (e.g. StopPoints
                // sharing coordinates) by stop identity BEFORE arrival time, so their rows don't
                // interleave (A, B, A) — soonest-first stays a strictly same-stop tiebreak.
                .thenBy { it.stopId }
                // A no-countdown line-status alert sorts to MAX, so it trails its stop's timed
                // departures rather than leading them — the alert rides with the stop, with no
                // special order for being an alert (maintainer, 2026-09-22).
                .thenBy { it.upcoming.firstOrNull()?.expectedArrival ?: Instant.MAX }
                .thenBy { it.lineName }
                .thenBy { it.direction }
                .thenBy { it.directionKey }
                .thenBy { it.platform }
                .thenBy { it.stopName },
        )
    }

    /**
     * [rows] with fresh ones ahead of stale ones, as every glance surface orders its cap: [stale]
     * says whether a row's stop is past its boundary. A status-only row ([isStatusOnly]) ranks as
     * fresh whatever its stop's age, since it has no countdown to withhold and only a live check
     * reaches it, so a current suspension is never pushed below the cap by fresher departures.
     * Stable: the prior order carries through within each band.
     */
    fun freshFirst(rows: List<DepartureRow>, stale: (DepartureRow) -> Boolean): List<DepartureRow> =
        rows.sortedBy { if (!it.isStatusOnly && stale(it)) 1 else 0 }

    /**
     * Reorder [rows] so the user's **starred** services sit at the top — ranking only, not
     * membership (SPEC D8): a star pins its row above the unstarred ones, it does not add or
     * remove anything. Applied after [across]/[nearbyDeduped], so the rows are already in
     * soonest-first order and this only lifts the starred ones.
     *
     * **With [warningsLead] (the default), warning rows stay on top of everything**, above even a
     * starred service: on the location-free watched list a stop-closure or no-departure line-status
     * row is a warning the user must see, and pinning a starred service above it would push a closure
     * down the list (SPEC principle 2 — never hide a warning). So a starred *service that is itself
     * currently a warning row* (a starred line gone suspended, now a no-prediction status row) stays
     * in the warning band too — its star re-pins it the moment it has departures again.
     *
     * **On the near-me list the caller passes `warningsLead = false`**, so an unstarred alert is not
     * hoisted — it stays at its stop's distance ([byStopDistance]) rather than jumping a closer stop
     * (maintainer, 2026-09-22). A stop closure still leads there because the screen renders closures
     * as standalone cards ahead of the groups; only a line-status alert's stop is left in place. A
     * starred alert still lifts with the starred band.
     *
     * The sort is stable, so within each band the prior order (soonest- or closest-first) carries
     * through unchanged; an empty [starred] set returns [rows] as-is.
     */
    fun pinStarred(
        rows: List<DepartureRow>,
        starred: Set<StarredRow>,
        warningsLead: Boolean = true,
    ): List<DepartureRow> {
        if (starred.isEmpty()) return rows
        return rows.sortedBy { row ->
            when {
                // Warnings (closures, no-prediction status) lead only where warnings are meant to —
                // the watched list. On the near-me list (warningsLead=false) an unstarred alert is
                // not hoisted; a starred alert still lifts with the starred band.
                warningsLead && (row.stopDisruption != null || row.upcoming.isEmpty()) -> 0
                StarredRow.of(row) in starred -> 1
                else -> 2
            }
        }
    }

    /**
     * [rows] without any timed row [shown] above them already covers in full: the same stop and line,
     * with every one of its departures among the shown row's — a journey card's row repeated in the
     * near-me list below it. A row the card shows only in part (some of its trains don't reach the
     * journey's far end) stays, as does a status-only row or a closure, so nothing is lost.
     */
    fun withoutShownAbove(rows: List<DepartureRow>, shown: List<DepartureRow>): List<DepartureRow> {
        if (shown.isEmpty()) return rows
        val byStopLine = shown.groupBy { it.stopId to it.lineId }
        return rows.filterNot { row ->
            row.upcoming.isNotEmpty() && row.stopDisruption == null &&
                byStopLine[row.stopId to row.lineId].orEmpty().any { it.upcoming.containsAll(row.upcoming) }
        }
    }

    /**
     * Hide the **service alerts the user has dismissed** (SPEC *Disruptions*). A stop-status row is
     * removed when its [DismissedAlert.ofStopClosure] identity — place key + the current notice text
     * and window — is in [dismissed]. A timed row whose **line status** matches a
     * [DismissedAlert.ofLineStatus] identity keeps its departures but drops the status (so no ⚠) and
     * is marked [DepartureRow.statusDismissed], so the detail never reads it as a clean line; a
     * no-prediction status row exists only to carry that alert, so it is removed outright. A row
     * carrying the line-wide status (no direction) whose other direction still has an undismissed
     * alert shows that alert instead, as the widget and watch do. Because each signature is the alert's *current* content, a reworded, re-dated or
     * escalated alert no longer matches its old dismissal and shows again, so a dismiss clears what
     * you've read without ever hiding a changed one. Empty [dismissed] returns [rows] unchanged.
     */
    fun withoutDismissed(rows: List<DepartureRow>, dismissed: Set<DismissedAlert>): List<DepartureRow> {
        if (dismissed.isEmpty()) return rows
        return rows.mapNotNull { row ->
            val status = row.status
            when {
                row.stopDisruption != null -> row.takeUnless { DismissedAlert.ofStopClosure(it) in dismissed }
                status != null -> when (val shown = status.remainingAfter(dismissed)) {
                    status -> row.withoutDismissedPlanned(dismissed)
                    // A no-prediction row goes with its alert, unless planned work it carries is still
                    // undismissed: that stays reachable (Codex, PR #337).
                    null -> row.copy(status = null, statusDismissed = true).withoutDismissedPlanned(dismissed)
                        .takeIf { it.upcoming.isNotEmpty() || it.plannedAlerts.isNotEmpty() }
                    else -> row.copy(status = shown).withoutDismissedPlanned(dismissed)
                }
                else -> row.withoutDismissedPlanned(dismissed)
            }
        }
    }

    // [this] less the planned alerts the user dismissed: each is its own alert, dismissed on its own.
    fun DepartureRow.withoutDismissedPlanned(dismissed: Set<DismissedAlert>): DepartureRow {
        if (plannedAlerts.isEmpty()) return this
        val kept = plannedAlerts.filterNot { DismissedAlert.ofPlanned(lineId, it) in dismissed }
        return if (kept.size == plannedAlerts.size) this else copy(plannedAlerts = kept)
    }

    /**
     * The line-status alerts live among [lineStatuses] — the identities a refresh reconciles line
     * dismissals against (see [Dismissed.reconcile], scoped to the lines actually checked).
     *
     * Given [now], each status as rows draw it then ([LineStatus.asOf]) counts too: planned work
     * whose day has come is shown, and so dismissed, as a disruption, and a reused status still
     * holds it as planned — pruning that dismissal would bring the ⚠ straight back (Codex, PR #337).
     * So does each part closure still under way behind the alert shown ([LineStatus.closures]): one
     * dismissed while it showed is still the rider's dismissal once a milder alert TfL ranks above it
     * shows, and a trip's "route disruption" names it again where it's placed on a ride (Codex, PR #446).
     */
    fun liveLineStatusAlerts(lineStatuses: Map<String, LineStatus>, now: Instant? = null): Set<DismissedAlert> =
        // Each direction's alert counts too: a row shows its own direction's, so that is what a
        // rider dismisses, and pruning it here would bring it straight back.
        (lineStatuses.values + now?.let { LineStatus.asOf(lineStatuses, it).values }.orEmpty())
            .flatMap { it.allStatuses }.flatMapTo(mutableSetOf()) { status ->
            // A planned alert is one too, so its dismissal isn't pruned while it's still coming.
            listOfNotNull(DismissedAlert.ofLineStatus(status).takeIf { status.disrupted }) +
                status.planned.map { DismissedAlert.ofPlanned(status.lineId, it) } +
                status.closures.map { DismissedAlert.ofLineStatus(status.naming(it)) }
        }

    /**
     * The cross-stop dedupe identity for [nearbyDeduped]: line + direction-of-travel.
     *
     * A timed row deduplicates across stops **only with a real cross-stop identity** — both a
     * `lineId` and TfL's own `direction`. When `direction` is blank there is no reliable
     * cross-stop discriminator: the platform is stop-local, and per `SPEC.md` a `destination`
     * cannot reconstruct a direction (opposite one-way stops can share a destination). A blank
     * `lineId` is the same problem for the line itself — TfL omits it on some predictions, and
     * `lineName` alone can name a different route — so merging on it would drop an unrelated
     * service at an adjacent stop. In either case the row stays **stop-specific** — keyed on its
     * own stop + stop-scoped [directionKey] — and is never merged across stops. That over-shows
     * a same-direction
     * service at adjacent stops in the direction-less case, which is the safe trade
     * (SPEC principle 1) against ever collapsing two opposite directions into one row and
     * mislabeling a countdown (maintainer, 2026-09-19). Earlier revisions also keyed on
     * `destination` when `direction` was blank; that produced a run of edge-case collisions
     * (blank direction + shared destination at opposite stops), so the whole `destination`
     * fallback is dropped here rather than special-cased further.
     *
     * A status row (no countdown) keeps its sentinel [DepartureRow.directionKey], which is
     * cross-stop stable and keeps it from colliding with a timed row of the same line.
     */
    private fun dedupeKeyOf(row: DepartureRow): RowKey {
        if (row.upcoming.isEmpty()) return RowKey(row.lineId, row.directionKey)
        // Dedupe across stops only with a real cross-stop identity — both a line and TfL's
        // direction. A blank `lineId` (TfL omits it on some predictions, and `lineName` alone
        // can name a different route) or a blank `direction` has no cross-stop discriminator, so
        // the row stays stop-specific — keyed on its own stop — and is never merged across stops,
        // rather than colliding with an unrelated route (blank line) or the opposite direction
        // (blank direction) at an adjacent stop and silently dropping the farther one (Codex).
        if (row.lineId.isBlank() || row.direction.isBlank()) {
            return RowKey(row.lineId, "\u0000${row.stopId}:${row.directionKey}")
        }
        return RowKey(row.lineId, row.direction)
    }

    /** True when [stopId] is nearer than [incumbent]; equal distances break by stopId. */
    private fun isCloserStop(
        stopId: String,
        incumbent: String,
        distanceOf: (String) -> Double,
    ): Boolean {
        val here = distanceOf(stopId)
        val there = distanceOf(incumbent)
        return here < there || (here == there && stopId < incumbent)
    }

    /**
     * A stop-level status row for a stop with its own disruption(s) — a closure or moved
     * stop — so it isn't shown as if its departures were catchable (SPEC *Disruptions*).
     * One row per stop, its descriptions joined; the stop's timed rows are kept (marked,
     * not suppressed), since TfL's closure data is coarse and often absent, so hiding
     * departments on it would risk dropping valid ones. Empty when the stop is clear.
     *
     * Each description is [normalizeDisruptionText]-ed — its `\n` escapes turned into real line
     * breaks — but the leading place name is **not** stripped here: that depends on the stop's own
     * name, and stripping before the near-me fold ([nearbyDeduped], which keys on this text) would
     * give two members of one hub that share a name-led notice different text — one member's name
     * matches and strips, the other's does not — and split the shared notice into two cards (Codex).
     * The name is stripped later, per the shown row, by [cleanDisruptionBody] at display time.
     */
    private fun stopStatusRow(stop: StopArrivals, now: Instant): List<DepartureRow> {
        // Only notices in effect at [now]: TfL lists a scheduled closure hours before it starts,
        // and one that isn't in force yet (or has ended) would contradict the live departures.
        // Deduplicated by normalized text, since one notice can be listed under several windows.
        val inWindow = stop.disruptions.filter { it.isActiveAt(now) }
        val active = inWindow.distinctBy { normalizeDisruptionText(it.description) }
        if (active.isEmpty()) return emptyList()
        return listOf(
            DepartureRow(
                stopId = stop.stopId,
                stopName = stop.stopName,
                clusterId = stop.clusterId,
                hubId = stop.hubId,
                hubName = stop.hubName,
                placeAliases = stop.placeAliases,
                // A bus pole's letter, bearing and heading, so a notice about one pole ("Bus Stop
                // Closed" at Stop E) can sit with that pole rather than read as the whole junction's.
                stopLetter = stop.stopLetter,
                bearing = stop.bearing,
                towards = stop.towards,
                lineId = "",
                lineName = "",
                direction = "",
                directionKey = STOP_STATUS_DIRECTION_KEY,
                destination = "",
                mode = "",
                upcoming = emptyList(),
                fetchedAt = stop.fetchedAt,
                status = null,
                stopDisruption = active.joinToString("\n\n") {
                    normalizeDisruptionText(it.description)
                },
                stopDisruptionWindows = stopDisruptionWindows(
                    stop.disruptions, active.mapTo(HashSet()) { normalizeDisruptionText(it.description) }, now,
                ),
            ),
        )
    }

    /**
     * The dismissal-identity form of the shown notices' windows: per shown (normalized) notice text, the **span**
     * of that text's windows overlapping the one in force at [now] — every window TfL lists for it,
     * future ones included, merged where they overlap or touch — as `text@from..to` (an open bound
     * blank). Bound to its text, so two notices swapping windows still reads as a change; sorted so
     * fetch order can't change it; blank when none is dated, so an undated notice keeps a text-only
     * identity. The span, not the window active right now, is what holds as time passes with TfL's
     * data unchanged: a shorter overlapping window ending, or a later one starting, leaves it the
     * same, while an extension or a move changes it (Codex).
     */
    private fun stopDisruptionWindows(all: List<StopDisruption>, shown: Set<String>, now: Instant): String =
        // Keyed on the normalized text, the same form the card shows and dismisses on, so a
        // formatting-only difference (escaped vs real line breaks) is not a new notice (Codex).
        all.groupBy { normalizeDisruptionText(it.description) }
            .filterKeys { it in shown }
            .mapNotNull { (text, same) ->
                if (same.all { it.validFrom == null && it.validTo == null }) return@mapNotNull null
                val span = spanAt(same.map { (it.validFrom ?: Instant.MIN) to (it.validTo ?: Instant.MAX) }, now)
                    ?: return@mapNotNull null
                fun bound(i: Instant) = if (i == Instant.MIN || i == Instant.MAX) "" else i.toString()
                "$text@${bound(span.first)}..${bound(span.second)}"
            }
            .sorted().joinToString(WINDOW_ENTRY_SEPARATOR)

    // Between [DepartureRow.stopDisruptionWindows] entries: a record separator, since the notice text
    // in each entry can itself hold line breaks.
    private const val WINDOW_ENTRY_SEPARATOR = "\u001E"

    /** The merged run of overlapping or touching [windows] that contains [now], or null if none does. */
    private fun spanAt(windows: List<Pair<Instant, Instant>>, now: Instant): Pair<Instant, Instant>? {
        var current: Pair<Instant, Instant>? = null
        for (w in windows.sortedBy { it.first }) {
            val c = current
            current = when {
                c == null -> w
                !w.first.isAfter(c.second) -> c.first to maxOf(c.second, w.second)
                !now.isBefore(c.first) && now.isBefore(c.second) -> return c
                else -> w
            }
        }
        return current?.takeIf { !now.isBefore(it.first) && now.isBefore(it.second) }
    }

    /**
     * Status rows for a stop's disrupted lines that have **no prediction rows** — a
     * suspended line often returns zero arrivals, so without this it would vanish from
     * the list rather than surface as suspended (SPEC *Departures*, the quietly-wrong
     * failure the model exists to avoid). Only the stop's declared [StopArrivals.lines]
     * can name such a line, since the predictions don't. A line that *does* have
     * prediction rows is already marked on them (its [DepartureRow.status]) and gets no
     * separate status row; a good-service line gets none either.
     */
    private fun statusRows(
        stop: StopArrivals,
        timed: List<DepartureRow>,
        lineStatuses: Map<String, LineStatus>,
        hiddenLines: Set<String> = emptySet(),
    ): List<DepartureRow> {
        val timedLineIds = timed.mapTo(mutableSetOf()) { it.lineId }
        return stop.lines
            .filter { it.id !in timedLineIds && it.id !in hiddenLines }
            .mapNotNull { line ->
                val status = lineStatuses[line.id]?.takeIf(LineStatus::disrupted)
                    ?: return@mapNotNull null
                DepartureRow(
                    stopId = stop.stopId,
                    stopName = stop.stopName,
                    clusterId = stop.clusterId,
                    // Carry the pole letter/bearing/towards so a suspended bus line groups under its
                    // own pole's header, not a separate bare group — at a multi-pole place the
                    // warning must say which pole it belongs to (Codex P2, PR #118).
                    stopLetter = stop.stopLetter,
                    bearing = stop.bearing,
                    towards = stop.towards,
                    lineId = line.id,
                    lineName = line.name,
                    direction = "",
                    directionKey = STATUS_DIRECTION_KEY,
                    destination = "",
                    mode = line.mode,
                    upcoming = emptyList(),
                    fetchedAt = stop.fetchedAt,
                    status = status,
                    // Its page lists work still to come beside the disruption now, as a timed row's does.
                    plannedAlerts = status.planned,
                    railFeed = stop.railFeed.takeIf { line.mode.equals(NATIONAL_RAIL_MODE, ignoreCase = true) },
                )
            }
    }

    /**
     * Rows ordered by **rank** first — stop-status rows (a whole stop disrupted), then
     * line-status rows (a line disrupted with no countdown), then timed rows — since a
     * disruption is the most important thing to see and has no departure time to sort by.
     * Within timed rows: soonest departure, ties broken by line, direction, then the
     * resolved direction key. A total, input-order-independent order shared by [forStop]
     * and [across] so a stop's rows sort the same alone or merged; stop is the final
     * tie-break so status rows for the same line/stop-status across stops stay stable.
     */
    private val rowOrder: Comparator<DepartureRow> =
        compareBy<DepartureRow> { rank(it) }
            .thenBy { it.upcoming.firstOrNull()?.expectedArrival ?: Instant.MIN }
            .thenBy { it.lineName }
            .thenBy { it.direction }
            .thenBy { it.directionKey }
            .thenBy { it.platform }
            .thenBy { it.stopName }

    /** 0 = stop-status row, 1 = line-status row (no countdown), 2 = timed row. */
    private fun rank(row: DepartureRow): Int = when {
        row.stopDisruption != null -> 0
        row.upcoming.isEmpty() -> 1
        else -> 2
    }

    /** Whether a stop fetched at [fetchedAt] is past the shared staleness bound at [now]. */
    private fun isStale(fetchedAt: Instant, now: Instant): Boolean = Staleness.isStale(fetchedAt, now)

    /**
     * The discriminator that keeps directions apart within a line at a stop. TfL's
     * `direction` is the intended key, but it omits it on some services; when it is
     * blank, falling back to `direction` alone would merge opposite directions into
     * one row that then mislabels a countdown (SPEC principle 1 — never present a
     * departure stopdash can't stand behind). So a blank direction falls back to the
     * platform (which usually names the direction, e.g. "Northbound - Platform 1")
     * and then the destination, both unambiguous enough to keep the groups apart.
     * A present `direction` is used as-is, so two platforms of the same direction
     * still share one row.
     */
    /**
     * One (line, direction) group's departures split by **platform number** (SPEC D8), each paired
     * with the number it was split on. A direction can run from more than one platform — Camden
     * Town's southbound Northern line leaves from Platform 2 or 4 depending on which northern
     * branch the train came from, a terminus alternates platforms — and a rider needs to know which
     * platform the next train is at, so each platform gets its own row (and so its own
     * "Platform N" header).
     *
     * A prediction with no platform number rides with the group's one numbered platform when there
     * is exactly one (TfL blanks it on some predictions); when there are several it can't be placed,
     * so it forms its own row with a blank number, which then heads under the bare compass rather
     * than a platform it may not be at (SPEC principle 1). Groups keep soonest-first order.
     *
     * Buses never split here: a bus prediction's `platform` is stop-local and can read like a rail
     * one ("Platform 1"), and a bus place splits on its pole instead ([StopGrouping]). With
     * [split] off (the widget) the group stays whole, numbered only when it has one platform.
     */
    private fun byPlatform(
        group: List<Departure>,
        split: Boolean,
        folded: Map<Departure, String> = emptyMap(),
    ): List<Pair<String, List<Departure>>> {
        // Any bus prediction marks the group as a bus: TfL can omit `modeName` on some predictions,
        // so the soonest one alone could let a bus split on its stop-local platforms (Codex).
        if (group.any { it.mode == "bus" }) return listOf("" to group)
        // A folded "Platform Unknown" train ([foldedUnknownPlatforms]) joins its service's named platform.
        val numberOf = group.associateWith { folded[it] ?: PlatformDirection.platformNumber(it.platform).orEmpty() }
        val numbers = numberOf.values.filter(String::isNotEmpty).distinct()
        if (numbers.size <= 1) return listOf(numbers.singleOrNull().orEmpty() to group)
        if (!split) return listOf("" to group)
        return group.groupBy { numberOf.getValue(it) }.toList()
    }

    /**
     * Whether the widget would show [row] at [stop] at [now]: the stop serves its line (in its
     * departures or its lines), the line's mode isn't in [hiddenModes], and the terminating filter
     * hasn't removed the row's own services (its line and direction; [Terminating]). A line with no
     * predictions right now still counts: the row shows its empty form. Judged on the services
     * still to come, or once they've all gone on the ones the stop had, so a row whose services
     * were all filtered stays filtered after they leave. A star or a complication's pick never
     * adds a service the widget leaves out.
     */
    fun shows(stop: StopArrivals, row: StarredRow, hiddenModes: Set<String>, now: Instant): Boolean {
        if (stop.stopId != row.stopId) return false
        val line = stop.lines.firstOrNull { it.id == row.lineId }
        // The same inference [forStop] groups by, so a star or a pick selects the row's services
        // exactly as the widget's row holds them.
        val lineServices = inferDirections(stop.departures.filter { it.lineId == row.lineId })
        if (line == null && lineServices.isEmpty()) return false
        val services = lineServices.filter { directionKeyOf(it) == row.directionKey }
        val judged = services.filter { it.expectedArrival > now }.ifEmpty { services }
        val kept = Terminating.drop(judged, stop.nearer)
        if (judged.isNotEmpty() && kept.isEmpty()) return false
        // The mode the widget's row carries, by the same rule ([resolvedMode]): never another
        // direction's, so the two can't disagree about a row whose predictions all omit it.
        val mode = resolvedMode(kept.sortedBy { it.expectedArrival }, line?.mode)
        return !HiddenModes.isHidden(mode, row.lineId, hiddenModes)
    }

    /**
     * [departures] with a blank `direction` filled in from the same service's other predictions — the
     * same line, platform, destination and via-branch — when those agree on exactly one direction.
     * Matching the destination, not just the platform, is what makes them the same service: a
     * platform can serve a line both ways (a single-platform through station), so a lone tagged train
     * there says nothing about a blank one heading the other way. TfL leaves `direction` blank on some
     * predictions and not others for one service — at Kentish Town West the Mildmay line's Platform
     * 1 trains to Clapham Junction came back both `outbound` and blank (confirmed against live TfL
     * data, 2026-09-25) — and a blank one would otherwise key on its platform ([directionKeyOf]) and
     * split one service into two rows with the same destination. A blank platform, or TfL's
     * "Platform Unknown" — a placeholder for trains in either direction, not one physical platform,
     * so even a single tagged train there says nothing about the others — gives no evidence, and a
     * platform whose tagged trains disagree is left alone too.
     *
     * The TfL client applies this to each stop's arrivals as they're fetched, so the saved snapshot
     * — and every surface reading it, the watch included — carries the inferred directions even
     * after the tagged trains have gone.
     */
    fun inferDirections(departures: List<Departure>): List<Departure> {
        // The physical platform: its number when TfL names one, so "Westbound - Platform 1" and a bare
        // "Platform 1" are the same platform; else the raw value (a bus pole's letter). Null — no
        // evidence — for a blank platform or "Platform Unknown".
        fun platformOf(d: Departure): String? {
            val raw = d.platform?.takeIf(String::isNotBlank) ?: return null
            val number = PlatformDirection.platformNumber(raw) ?: return raw
            return number.takeUnless { it.equals("Unknown", ignoreCase = true) }
        }
        // A blank destination (TfL can omit it) can't tell a shared platform's two directions apart,
        // so it gives no evidence either.
        fun serviceOf(d: Departure) = d.destination.takeIf(String::isNotBlank)?.let { destination ->
            platformOf(d)?.let { listOf(d.lineId, it, destination, d.branch.orEmpty()) }
        }
        if (departures.none { it.direction.isBlank() && serviceOf(it) != null }) return departures
        val taggedBy = departures
            .filter { it.direction.isNotBlank() }
            .mapNotNull { d -> serviceOf(d)?.let { it to d } }
            .groupBy({ it.first }, { it.second })
        return departures.map { d ->
            if (d.direction.isNotBlank()) return@map d
            val service = serviceOf(d) ?: return@map d
            // Display names aren't unique termini: where both carry TfL's destination id, it must
            // agree too. A missing id (TfL omits it on some predictions) doesn't rule a match out.
            val inferred = taggedBy[service].orEmpty()
                .filter { it.destinationId.isBlank() || d.destinationId.isBlank() || it.destinationId == d.destinationId }
                .mapTo(HashSet()) { it.direction }
                .singleOrNull() ?: return@map d
            d.copy(direction = inferred)
        }
    }

    /**
     * The "Platform Unknown" predictions in [live] to fold into their service's named-platform row,
     * each mapped to that platform's number. TfL names the platform only for the next half hour or
     * so (at Highbury & Islington the Mildmay line's later trains all came back "Platform Unknown",
     * confirmed against live TfL data, 2026-09-25), so those later trains otherwise fill a "Platform
     * Unknown" group repeating services already shown with a platform. Only a train leaving after its
     * service's [KNOWN_PLATFORM_ENOUGH]th named one folds: the row already shows that many times, so
     * it stays out of the list, while the row keeps it for the route detail's full list. A sooner
     * unknown train, or one of a service with fewer named trains, keeps its own group, so no
     * departure the rider could catch is hidden or put under a platform nobody named.
     *
     * The service's named trains must all use one platform: one alternating between platforms (a
     * terminus) gives no single row to fold into, so its unknown trains keep their own group.
     *
     * A service is the line, direction, destination and via-branch: a branch renders as its own
     * destination line (High Barnet via Bank vs via Charing X), and "Platform Unknown" can hide either
     * direction, so one with a blank direction or destination never folds.
     */
    private fun foldedUnknownPlatforms(live: List<Departure>): Map<Departure, String> {
        fun unknownPlatform(d: Departure) = PlatformDirection.platformNumber(d.platform).equals("Unknown", ignoreCase = true)
        if (live.none(::unknownPlatform)) return emptyMap()
        fun serviceOf(d: Departure) =
            listOf(d.lineId, d.direction, d.destination, d.branch.orEmpty())
                .takeIf { d.direction.isNotBlank() && d.destination.isNotBlank() }
        // Each service's last shown named train: the one its row's times end on. Only a service whose
        // named trains all use one platform qualifies — one alternating between platforms (a terminus)
        // gives no platform to fold into, and a guess could put the train in a row's shown times.
        val lastShown = live
            .filter { !it.platform.isNullOrBlank() && !unknownPlatform(it) }
            .mapNotNull { d -> serviceOf(d)?.let { it to d } }
            .groupBy({ it.first }, { it.second })
            .filterValues { named -> named.mapTo(HashSet()) { PlatformDirection.platformNumber(it.platform) }.size == 1 }
            .mapNotNull { (service, named) ->
                named.sortedBy { it.expectedArrival }.getOrNull(KNOWN_PLATFORM_ENOUGH - 1)?.let { service to (it to named) }
            }
            .toMap()
        return live.filter(::unknownPlatform).mapNotNull { d ->
            val (last, named) = serviceOf(d)?.let { lastShown[it] } ?: return@mapNotNull null
            // Display names aren't unique termini: where TfL's destination ids are given, they must
            // agree, so a same-named other terminus isn't filed under this one's platform.
            if (named.any { it.destinationId.isNotBlank() && d.destinationId.isNotBlank() && it.destinationId != d.destinationId }) {
                return@mapNotNull null
            }
            val number = PlatformDirection.platformNumber(last.platform) ?: return@mapNotNull null
            (d to number).takeIf { d.expectedArrival > last.expectedArrival }
        }.toMap()
    }

    /** Named-platform trains a service needs before its later "Platform Unknown" ones fold in: a row's times. */
    const val KNOWN_PLATFORM_ENOUGH = 3

    /** The direction key [d]'s row carries ([DepartureRow.directionKey], a [StarredRow]'s too). */
    fun directionKeyOf(d: Departure): String =
        d.direction.ifBlank { d.platform?.takeIf(String::isNotBlank) ?: d.destination }

    /**
     * A row's mode: the first its services (soonest first) name, else the stop's advertised
     * [lineMode], else blank. TfL can omit a prediction's `modeName`, so the soonest one alone
     * could let a hidden mode's row through.
     */
    private fun resolvedMode(services: List<Departure>, lineMode: String?): String =
        services.firstOrNull { it.mode.isNotBlank() }?.mode ?: lineMode.orEmpty()

    private data class RowKey(val lineId: String, val directionKey: String)

    private data class RowGroup(val key: RowKey, val platform: String, val group: List<Departure>, val mode: String)
}

/**
 * One watched stop's arrivals, as [DepartureRows.across] takes them: the stop's
 * identity ([stopId]/[stopName], the caller's context since the client fetches per
 * stop) paired with the raw [departures] TfL returned for it.
 *
 * [lines] is the stop's served lines, known independently of the predictions, so a
 * disrupted line with zero arrivals still surfaces as a status row (SPEC *Departures*).
 * Empty when the caller has no such mapping — then only prediction-derived rows are shown.
 * [disruptions] are the stop's own disruptions (a closure, a moved stop), surfaced as a
 * stop-level status row so a closed stop isn't shown as if its departures were catchable
 * (SPEC *Disruptions*). Empty when the stop is clear or wasn't checked.
 * [fetchedAt] is when *this stop's* [departures] were fetched — each stop carries its own
 * age, so a partial refresh keeps a failed stop's aged rows (at their older age) beside a
 * fresh stop's, and staleness is decided per stop rather than one screen-wide flag
 * (SPEC D4). [Snapshot.mergeStop] sets it when merging a refresh into the prior snapshot.
 * [arrivalsFresh] is whether these [departures] came from a **successful arrivals fetch in
 * this snapshot** (vs carried from a prior, or absent because the fetch failed). It gates
 * the "No departures" claim a synthesized status row makes: a disruption-only stop is
 * stamped [fetchedAt] = now yet has no fetched arrivals, so freshness alone can't stand in
 * for "we know there are no departures" — only a fetch can (SPEC principle 1).
 */
data class StopArrivals(
    val stopId: String,
    val stopName: String,
    val departures: List<Departure>,
    val fetchedAt: Instant,
    val lines: List<LineRef> = emptyList(),
    val disruptions: List<StopDisruption> = emptyList(),
    val arrivalsFresh: Boolean = true,
    // Where this National Rail station's National Rail times stand after its last arrivals fetch
    // ([TflClient.railFeed]); null when none apply. Persisted with the stop, so a surface rendering
    // from the snapshot can tell "No key" from "No data" once it has the line statuses a status
    // row needs ([statusRows]).
    val railFeed: RailFeed? = null,
    // The cluster this stop belongs to — TfL's `stationNaptan` else the display name (see
    // [StopLocation.clusterId]). Carried onto each [DepartureRow] so the screen can group rows
    // into per-place headers by cluster rather than by the name TfL spells inconsistently (SPEC
    // D8). Blank groups the stop on its own.
    val clusterId: String = "",
    // The interchange this stop belongs to (TfL `hubNaptanCode`, see [StopLocation.hubId]) and its
    // display name, resolved for a stop with a disruption. The near-me alert dedup folds a shared
    // notice by [hubId] and titles it by [hubName] (SPEC *Disruptions*). Both blank for a stop in
    // no hub or when the name lookup failed.
    val hubId: String = "",
    val hubName: String = "",
    // Every member-station spelling of this stop's interchange, resolved alongside [hubName] for a
    // stop with a disruption. Carried onto the stop-status row so the display strip can drop a
    // redundant leading name even when the notice uses a different member's spelling than [stopName]
    // (SPEC *Disruptions*). Empty for a stop in no hub or when the lookup failed; not persisted.
    val placeAliases: List<String> = emptyList(),
    // The bus pole's letter ("D"), compass bearing ("E"), and "towards" description ("Farringdon Or
    // Holborn Circus"), from the nearby lookup ([StopLocation]). Carried onto each timed
    // [DepartureRow] so a bus place splits into one header per pole — "King's Cross Station (D)
    // (towards Farringdon)" — the bus analog of a rail platform (SPEC D8). All blank for a station, a
    // letter-less bus stop, or a watched stop (whose seed carries none yet).
    val stopLetter: String = "",
    val bearing: String = "",
    val towards: String = "",
    // The places no farther from the rider than this stop ([Terminating.Nearer]): a service ending
    // at one goes nowhere for them and is dropped. Worked out when the near-me list fetched the
    // stop, and saved with it so the widget's location-free refresh drops the same ones. Empty for
    // a stop with no known distance.
    val nearer: Terminating.Nearer = Terminating.Nearer(),
    // The trains this stop's National Rail board listed with no time to count down ([UntimedTrain]:
    // canceled, or delayed with no estimate), apart from [departures] so nothing times them. Shown in
    // their rows by the in-app list ([DepartureRow.untimed]); not persisted, so a surface drawing from
    // the saved snapshot (the widget, the watch) never has them.
    val untimed: List<UntimedTrain> = emptyList(),
)

/**
 * One rendered line of a (service, stop, direction) row (see [DepartureRows.destinationLines]):
 * a single [destination] with that group's own soonest-first [times]. [branch] is the via-trunk
 * label to show (the board form, `Bank` / `Charing X`) — null for most services, and also null
 * where a branching line's trunk is not a choice from this stop, so two equivalent trunks merge
 * into one unlabeled line (see [RouteTopology]). A branching row yields several of these; the
 * soonest leads. Grouped identically for both surfaces by the shared function.
 */
data class DestinationGroup(
    val destination: String,
    val branch: String?,
    val times: List<Departure>,
    // The destination's trains with no time to count down ([DepartureRow.untimed]), drawn among
    // [times] in their place ([Countdown.entries]); empty but in the in-app list.
    val untimed: List<UntimedTrain> = emptyList(),
)
