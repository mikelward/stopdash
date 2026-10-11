package app.stopdash.domain

/** Whether a geocoded To… result is a named place/landmark/address, or a postcode — shown as its tag. */
enum class PlaceKind { PLACE, POSTCODE }

/**
 * A geocoded destination the To… search offers alongside stops (SPEC *Find a station*): a display
 * [name], its [coordinate] (the trip plans to it with a final walk leg, SPEC D9), and its [kind] for
 * the row's right-column tag. Distinct from a [StationMatch] (a stop id): a place carries a coordinate
 * and routes as a [TripDestination.Place].
 *
 * A Google Places suggestion ([PlaceSuggestion]) carries its [placeId] instead of a coordinate, which
 * only a details lookup on tap supplies ([PlaceAutocomplete.locate]), and the [detail] line Google gives
 * to tell same-named places apart ("Bankside, London"). Exactly one of [coordinate] and [placeId] says
 * where it is: [coordinate] once known, else [placeId] to look it up by.
 */
data class PlaceHit(
    val name: String,
    val coordinate: Coordinates?,
    val kind: PlaceKind,
    val placeId: String? = null,
    val detail: String? = null,
) {
    init {
        require(coordinate != null || placeId != null) { "a place needs a coordinate or a place id" }
    }

    /** Whether tapping it needs a lookup first: a suggestion whose coordinate isn't known yet. */
    val needsLookup: Boolean get() = coordinate == null
}

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

    /**
     * Google's [suggestions] for a query as the To… list should show them: in **Google's own order**,
     * not re-ranked or filtered by [StationMatcher] as TfL's candidates are. Google already ranks by
     * prefix, typo tolerance, popularity and nearness, and a good suggestion needn't contain the query
     * ("tate modren" finds Tate Modern; an address's main text may drop a word typed), so our matcher
     * would only drop right answers. Stations are dropped: the stop rows already list them from TfL with
     * the ids their departures and routes need, so a Google copy would be a worse duplicate that routes
     * by coordinate.
     */
    fun fromSuggestions(suggestions: List<PlaceSuggestion>, limit: Int = DEFAULT_LIMIT): List<PlaceHit> =
        suggestions
            .filterNot { s -> s.types.any { it in TRANSIT_TYPES } }
            .distinctBy { it.placeId }
            .take(limit)
            .map { PlaceHit(it.name, coordinate = null, kind = PlaceKind.PLACE, placeId = it.placeId, detail = it.detail) }

    // Google place types of a station or stop TfL's own search already lists.
    private val TRANSIT_TYPES = setOf(
        "transit_station", "train_station", "subway_station", "bus_station", "bus_stop", "light_rail_station", "ferry_terminal",
    )
}
