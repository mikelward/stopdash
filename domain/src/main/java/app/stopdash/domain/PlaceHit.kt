package app.stopdash.domain

/** Whether a geocoded To… result is a named place/landmark/address, or a postcode — shown as its tag. */
enum class PlaceKind { PLACE, POSTCODE }

/**
 * A geocoded destination the To… search offers alongside stops (SPEC *Find a station*): a display
 * [name], its [coordinate] (the trip plans to it with a final walk leg, SPEC D9), and its [kind] for
 * the row's right-column tag. Distinct from a [StationMatch] (a stop id): a place carries a coordinate
 * and routes as a [TripDestination.Place].
 */
data class PlaceHit(val name: String, val coordinate: Coordinates, val kind: PlaceKind)

object PlaceHits {
    /** How many geocoded places are kept after re-ranking, so they don't crowd out the stops. */
    const val DEFAULT_LIMIT = 8

    /**
     * The geocoded [candidates] as the To… list should show them. TfL's Journey Planner geocoder
     * returns place candidates in a **noisy** order — a weak partial ("Ace & Tate") can outrank the
     * obvious landmark ("Tate Modern") — so for a **place-name** query we re-rank by our own name
     * matcher ([StationMatcher]: Prefix beats Anchored beats Substring beats Fuzzy) and drop candidates
     * whose name doesn't match the query at all, cutting the geocoder's noise (maintainer, 2026-09-27;
     * a TODO tracks trying a better geocoder). A **postcode** query is kept in TfL's own order instead:
     * the resolved location's name needn't contain the digits, so name-matching would wrongly drop it.
     */
    fun rank(query: String, candidates: List<PlaceCandidate>, kind: PlaceKind, limit: Int = DEFAULT_LIMIT): List<PlaceHit> {
        val q = query.trim()
        if (q.isEmpty() || candidates.isEmpty()) return emptyList()
        val ordered = when (kind) {
            PlaceKind.POSTCODE -> candidates
            PlaceKind.PLACE -> candidates
                .mapNotNull { candidate -> StationMatcher.tier(q, candidate.name)?.let { it to candidate } }
                .sortedWith(compareBy({ it.first.ordinal }, { it.second.name.length }, { it.second.name }))
                .map { it.second }
        }
        return ordered
            .map { PlaceHit(it.name, it.coordinate, kind) }
            .distinctBy { it.name to it.coordinate }
            .take(limit)
    }
}
