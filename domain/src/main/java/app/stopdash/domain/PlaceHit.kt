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

    // A house number, optionally a letter or a range ("50", "12a", "10-12"), then a word: an address.
    private val ADDRESS = Regex("""^\d+[a-z]?(?:-\d+[a-z]?)?,?\s+\p{L}.*""", RegexOption.IGNORE_CASE)

    /**
     * Whether [query] reads as a street address ("50 Euston Road"). The bundled index holds streets
     * and places, not house numbers, so its matches for one are loose ("350 Euston Road" for "50");
     * such a query still goes to TfL's geocoder, which can place the number.
     */
    fun looksLikeAddress(query: String): Boolean = ADDRESS.matches(query.trim())

    /**
     * Whether the bundled index's [hits] answer [query] well enough to leave TfL's geocoder unasked:
     * one of them matches as a prefix or a substring, not just letters in order ("foobar" finding
     * "Football Force Academy" is noise, not an answer), and the query isn't an address with a house
     * number, which the index can match only loosely. At most a few hits, so cheap.
     */
    fun answers(query: String, hits: List<PlaceHit>): Boolean =
        !looksLikeAddress(query) && hits.any { hit ->
            StationMatcher.tier(query, hit.name)?.let { it <= StationMatchTier.Substring } == true
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
    fun rank(query: String, candidates: List<PlaceCandidate>, kind: PlaceKind, limit: Int = DEFAULT_LIMIT): List<PlaceHit> {
        val q = query.trim()
        if (q.isEmpty() || candidates.isEmpty()) return emptyList()
        val ordered = when (kind) {
            PlaceKind.POSTCODE -> candidates
            PlaceKind.PLACE, PlaceKind.STREET -> candidates
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
     * The bundled index's matches for [query] ([PlaceIndex.search], already ranked) as the To… list
     * shows them: a place, a street or a postcode, each with its coordinate.
     */
    @androidx.annotation.WorkerThread
    fun fromIndex(index: PlaceIndex, query: String, limit: Int = DEFAULT_LIMIT, checkpoint: () -> Unit = {}): List<PlaceHit> =
        index.search(query, limit, checkpoint).map { entry ->
            val kind = when (entry.kind) {
                PlaceIndex.Kind.PLACE -> PlaceKind.PLACE
                PlaceIndex.Kind.STREET -> PlaceKind.STREET
                PlaceIndex.Kind.POSTCODE -> PlaceKind.POSTCODE
            }
            PlaceHit(entry.name, entry.coordinate, kind)
        }
}
