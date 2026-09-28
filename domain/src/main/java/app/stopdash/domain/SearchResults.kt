package app.stopdash.domain

/** One row of the To… search results: a stop, or a geocoded place (SPEC *Find a station*). */
sealed interface SearchEntry {
    data class Stop(val match: StationMatch) : SearchEntry

    data class Place(val hit: PlaceHit) : SearchEntry
}

object SearchResults {
    /**
     * The [stops] and geocoded [places] for [query] as one list, ranked by how well each name
     * matches, so a place the query starts ("Tate Britain" for "tate") sits above a stop that only
     * contains it ("… Estate") rather than after every stop (maintainer, 2026-09-28). Each list keeps
     * its own order, both already ranked; a place goes before the first stop that matches worse than
     * it does, and a stop wins a tie. A postcode's places, whose names needn't contain the query,
     * follow the stops, as a match the matcher can't place does.
     */
    fun merge(query: String, stops: List<StationMatch>, places: List<PlaceHit>): List<SearchEntry> {
        val unplaced = StationMatchTier.entries.size
        fun tierOf(stop: StationMatch) = StationMatcher.tier(query, stop.name, stop.id)?.ordinal ?: unplaced
        fun tierOf(place: PlaceHit) =
            if (place.kind == PlaceKind.POSTCODE) unplaced else StationMatcher.tier(query, place.name)?.ordinal ?: unplaced
        val merged = ArrayList<SearchEntry>(stops.size + places.size)
        var p = 0
        for (stop in stops) {
            val stopTier = tierOf(stop)
            while (p < places.size && tierOf(places[p]) < stopTier) merged += SearchEntry.Place(places[p++])
            merged += SearchEntry.Stop(stop)
        }
        while (p < places.size) merged += SearchEntry.Place(places[p++])
        return merged
    }
}
