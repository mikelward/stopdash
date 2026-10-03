package app.stopdash.domain

/**
 * One end-to-end route pattern of a line, from TfL's Route/Sequence: its ordered [stops]
 * (naptanIds, first→last), the [branch] its `towards` names (normalized to the board label
 * — `Bank` / `Charing X` — via [normalizeBranch], null for a pattern with no "via"), and
 * the two terminus names [endA]/[endB] aligned to `stops.first()` / `stops.last()`. Endpoint
 * names are held as TfL spells them; [RouteTopology] cleans them ([cleanStopName]) for
 * matching, so one cleaning rule serves stop names, arrival destinations, and these.
 */
data class RoutePattern(
    val branch: String?,
    val stops: List<String>,
    val endA: String,
    val endB: String,
)

/**
 * How to group and label a departure's (destination, branch) on a row (see
 * [DepartureRows.destinationLines]): [mergeKey] collapses branches that are the *same
 * service from this stop onward* into one line, and [label] is the branch to show — null to
 * drop it where the branch names only which trunk the train came up *behind* this stop, not
 * a choice a rider makes here.
 */
data class BranchGrouping(val mergeKey: String, val label: String?)

/**
 * The branch topology of the lines that run more than one central trunk — the Northern
 * line's `via Bank` / `via Charing Cross`, the Central line's Hainault loop. It answers one
 * question, for a train at a stop bound for a terminus: **is its via-branch a choice ahead
 * of here, or already behind?**
 *
 * Two trains to one terminus by different trunks are the *same service* only once they have
 * physically joined — past the junction, on the single shared track — "from the perspective of
 * someone traveling away from Bank and Charing Cross the origin doesn't matter" (maintainer).
 * There the rows merge and the label drops. The branch stays, each row labeled, wherever the
 * trunks are still distinct: a trunk-only stop ahead (High Barnet → Morden picks which central
 * stations you pass; Euston → High Barnet picks whether you stop at Mornington Crescent), **and
 * at the junction and trunk stops themselves** (Camden Town, Euston, Kennington), where a Bank
 * train and a Charing Cross train reach you by different approaches/platforms and you still pick
 * one (maintainer, 2026-09-20).
 *
 * The test is an **approach-inclusive path comparison**: from the stop one before this one
 * through to the terminus, do the two branches visit the same stops? Equal ⇒ merge and drop the
 * label; differ ⇒ keep both. Including the approach stop is what keeps the branch at the junction
 * (the trunks reach it by different approaches) while still merging once past it (the approach is
 * shared). Pure and topology-only — it reads no clock and touches no decision path; it only shapes
 * how [DepartureRows.destinationLines] groups a row for display. Static app data loaded from a
 * bundled asset. [EMPTY] (an unknown line, an unwired build or test) resolves every branch as
 * unknown, so the label is kept exactly as TfL gave it and nothing merges — the safe default,
 * never a wrong merge.
 */
class RouteTopology(val patternsByLine: Map<String, List<RoutePattern>>) {
    // Endpoint names pre-cleaned once, as shown ([cleanStopName], lowercased) and in the matching
    // form ([matchStopName]) a destination falls back to, so each lookup is plain equality.
    private class Pattern(
        val branch: String?,
        val stops: List<String>,
        val endA: String,
        val endB: String,
        val exactA: String,
        val exactB: String,
    )

    private val byLine: Map<String, List<Pattern>> =
        patternsByLine.mapValues { (_, patterns) ->
            patterns.map {
                Pattern(
                    it.branch, it.stops, matchStopName(it.endA), matchStopName(it.endB),
                    cleanStopName(it.endA).lowercase(), cleanStopName(it.endB).lowercase(),
                )
            }
        }

    /**
     * The grouping for a departure on [lineId] at [stopId] bound for [destination] via
     * [branch] (both as they appear on a [Departure] — [destination] already [cleanStopName]d,
     * [branch] already normalized). Departures sharing a (destination, [BranchGrouping.mergeKey])
     * render as one line; [BranchGrouping.label] is the branch shown, or null to drop it.
     *
     * **Resolution requires an exact branch match** — the arrival's branch must name a route
     * pattern that actually serves this leg. Anything the bundled asset doesn't model that way
     * keeps TfL's raw label and merges nothing: an unknown line, a stop or terminus off every
     * pattern, or a branch no serving pattern carries (a Battersea train TfL tags "via Charing
     * Cross" against an unlabeled Battersea pattern — it keeps `(Charing X)`, the trunk it runs;
     * a rename or extension the asset predates). Incomplete or stale data degrades to "show what
     * TfL said", never a confident wrong merge (maintainer, 2026-09-20).
     *
     * **A single-branch stop drops the label**, even on that raw fallback: where every pattern
     * calling here carries the arrival's own branch, the branch names which trunk the train came
     * up behind this stop, not a choice a rider makes — so it adds nothing. King's Cross is
     * Bank-only, so a Bank-branch short-working there (Golders Green, Finchley Central — no
     * pattern's terminus, so otherwise raw) shows no `(Bank)` (maintainer, 2026-09-21).
     */
    fun grouping(lineId: String, stopId: String, destination: String, branch: String?): BranchGrouping {
        val patterns = byLine[lineId] ?: return raw(branch)
        // A single-branch stop: every pattern that calls here carries this arrival's own branch,
        // so there is no alternative trunk to choose and the label is redundant. Computed up front
        // because it also governs the raw fallback below (an unmodeled short-working), which the
        // per-leg segment test never reaches.
        val here = patterns.filter { stopId in it.stops }
        val singleBranchHere = here.isNotEmpty() && here.all { it.branch == branch }
        // The approach-inclusive segment (one stop before this stop, through to the terminus) for
        // every pattern that serves the leg, plus this departure's own (the pattern whose branch
        // matches exactly).
        val segments = ArrayList<Set<String>>(patterns.size)
        var mine: Set<String>? = null
        // A qualified destination ("Paddington (H&C)") takes the ends of that exact name where any
        // pattern calling here has one, else every end of its name ([isLineQualified]).
        val exact = cleanStopName(destination).lowercase()
        val exactly = isLineQualified(destination) && here.any { it.exactA == exact || it.exactB == exact }
        for (pattern in patterns) {
            val segment = segment(pattern, stopId, destination, exactly) ?: continue
            segments += segment
            if (pattern.branch == branch) mine = segment
        }
        // Unresolved destination (no pattern's terminus from here): keep TfL's raw label and merge
        // nothing — unless this is a single-branch stop, where the label is redundant and dropped.
        val forward = mine ?: return if (singleBranchHere) raw(branch).copy(label = null) else raw(branch)
        // A choice iff more than one distinct segment serves the leg — either the trunks diverge
        // ahead (High Barnet → Morden), or they reach this very stop by different approaches (the
        // junction: Camden Town, Euston, Kennington), where the rider still picks a trunk/platform.
        // Only past the junction, on the single shared track, do the segments match and rows merge;
        // and a single-branch stop never labels, whatever the segments say.
        return BranchGrouping(
            mergeKey = signatureOf(forward),
            label = if (!singleBranchHere && segments.toHashSet().size >= 2) branch else null,
        )
    }

    /**
     * The stop-set from the stop **one before** [stopId] (toward [destination]) through to
     * [destination] on [pattern], or null if [pattern] doesn't serve that leg. Including the
     * approach stop is what keeps the branch at a junction: two trunks reach the junction stop
     * by different approaches (into Camden Town, Bank via Euston vs Charing Cross via Mornington
     * Crescent), so their segments differ there and both stay labeled. Only once past the
     * junction — where the approach is shared too — do the segments match and the rows merge.
     */
    private fun segment(pattern: Pattern, stopId: String, destination: String, exactly: Boolean): Set<String>? {
        val i = pattern.stops.indexOf(stopId)
        if (i < 0) return null
        val name = if (exactly) cleanStopName(destination).lowercase() else matchStopName(destination)
        val (endA, endB) = if (exactly) pattern.exactA to pattern.exactB else pattern.endA to pattern.endB
        // Loosely, an end its line qualifier names as another station isn't this one ([conflictingQualifiers]).
        fun reaches(end: String, shown: String) = name == end && (exactly || !conflictingQualifiers(destination, shown))
        return when {
            // Toward the last endpoint: this stop through the end, plus the one before it.
            reaches(endB, pattern.exactB) -> pattern.stops.subList((i - 1).coerceAtLeast(0), pattern.stops.size).toHashSet()
            // Toward the first endpoint: the start through this stop, plus the one after it.
            reaches(endA, pattern.exactA) -> pattern.stops.subList(0, (i + 2).coerceAtMost(pattern.stops.size)).toHashSet()
            else -> null
        }
    }

    /** A canonical id for a segment, so equal segments share a merge key. */
    private fun signatureOf(stops: Set<String>): String = stops.sorted().joinToString(",")

    /** Keep TfL's own branch and merge nothing — the fallback when topology can't decide. */
    private fun raw(branch: String?) = BranchGrouping(mergeKey = "raw:${branch ?: ""}", label = branch)

    companion object {
        /** No topology: every branch resolves as unknown, so labels are kept and nothing merges. */
        val EMPTY = RouteTopology(emptyMap())
    }
}
