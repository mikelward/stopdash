package app.stopdash.domain

/** Whether a To… place result is a named place/landmark/address, a street, or a postcode — shown as its tag. */
enum class PlaceKind { PLACE, STREET, POSTCODE }

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

    // The box the bundled place index covers (`scripts/build_place_index.py`'s WEST, EAST, SOUTH,
    // NORTH), so TfL's places outside London leave out what the bundle already lists.
    const val BUNDLED_WEST = -0.51
    const val BUNDLED_EAST = 0.34
    const val BUNDLED_SOUTH = 51.28
    const val BUNDLED_NORTH = 51.70

    /**
     * The [candidates] TfL found that lie outside the bundled index's box: what "Search outside London"
     * lists (maintainer, 2026-10-11). One inside it is a place the bundle already offers (or, for an
     * exact house number, its street), so listing it again under "Outside London" would mislabel it.
     */
    @androidx.annotation.WorkerThread
    fun outsideBundle(candidates: List<PlaceCandidate>): List<PlaceCandidate> = candidates.filterNot {
        it.coordinate.latitude in BUNDLED_SOUTH..BUNDLED_NORTH && it.coordinate.longitude in BUNDLED_WEST..BUNDLED_EAST
    }

    // A house number, optionally a letter or a range ("50", "12a", "10-12", "10 – 12"), then a word:
    // an address.
    private const val NUMBER = """\d+[a-z]?(?:\s*[-‐‑‒–—]\s*\d+[a-z]?)?,?\s+"""
    private val ADDRESS = Regex("""^$NUMBER\p{L}.*""", RegexOption.IGNORE_CASE)
    private val HOUSE_NUMBER = Regex("""^$NUMBER""", RegexOption.IGNORE_CASE)

    /**
     * Whether [query] reads as a street address ("50 Euston Road"). The bundled index holds streets
     * and places but no house numbers, so its street ([withoutHouseNumber]) is what's searched.
     */
    fun looksLikeAddress(query: String): Boolean = ADDRESS.matches(query.trim())

    /** [query] without the house number an address starts with ("50 Euston Road" → "Euston Road"). */
    fun withoutHouseNumber(query: String): String {
        val trimmed = query.trim()
        return if (looksLikeAddress(trimmed)) trimmed.replaceFirst(HOUSE_NUMBER, "") else trimmed
    }

    /**
     * The geocoded [candidates] as the To… list should show them. TfL's Journey Planner geocoder
     * returns place candidates in a **noisy** order — a weak partial ("Ace & Tate") can outrank the
     * obvious landmark ("Tate Modern") — so for a **place-name** query we re-rank by our own name
     * matcher ([StationMatcher]: Prefix beats Anchored beats Substring beats Fuzzy) and drop candidates
     * whose name doesn't match the query at all, cutting the geocoder's noise (maintainer, 2026-09-27;
     * a TODO tracks trying a better geocoder). A **postcode** query is kept in TfL's own order instead:
     * the resolved location's name needn't contain the digits, so name-matching would wrongly drop it.
     */
    @androidx.annotation.WorkerThread
    fun rank(query: String, candidates: List<PlaceCandidate>, kind: PlaceKind, limit: Int = DEFAULT_LIMIT, near: Coordinates? = null): List<PlaceHit> {
        val q = query.trim()
        if (q.isEmpty() || candidates.isEmpty()) return emptyList()
        val ordered = when (kind) {
            PlaceKind.POSTCODE -> candidates
            PlaceKind.PLACE, PlaceKind.STREET -> candidates
                .mapNotNull { candidate -> StationMatcher.tier(q, candidate.name)?.let { it to candidate } }
                // Equal matches nearer where the trip starts ([near]) first, as the bundled places rank.
                .sortedWith(compareBy({ it.first.ordinal }, { it.second.name.length }, { distanceFrom(near, it.second.coordinate) }, { it.second.name }))
                .map { it.second }
        }
        return ordered
            .map { PlaceHit(it.name, it.coordinate, kind) }
            .distinctBy { it.name to it.coordinate }
            .take(limit)
    }

    private fun distanceFrom(near: Coordinates?, at: Coordinates): Double =
        if (near == null) 0.0 else NearestStops.distanceMeters(near.latitude, near.longitude, at.latitude, at.longitude)

    /**
     * The bundled index's matches for [query] ([PlaceIndex.search], already ranked) as the To… list
     * shows them: a place, a street or a postcode, each with its coordinate; only postcodes when
     * [postcodesOnly].
     */
    @androidx.annotation.WorkerThread
    fun fromIndex(
        index: PlaceIndex,
        query: String,
        limit: Int = DEFAULT_LIMIT,
        checkpoint: () -> Unit = {},
        postcodesOnly: Boolean = false,
        // Where the trip starts: the nearer of equal matches first.
        near: Coordinates? = null,
    ): List<PlaceHit> =
        index.search(query, limit, checkpoint, if (postcodesOnly) PlaceIndex.Kind.POSTCODE else null, near).map { entry ->
            val kind = when (entry.kind) {
                PlaceIndex.Kind.PLACE -> PlaceKind.PLACE
                PlaceIndex.Kind.STREET -> PlaceKind.STREET
                PlaceIndex.Kind.POSTCODE -> PlaceKind.POSTCODE
            }
            PlaceHit(entry.name, entry.coordinate, kind)
        }
}
