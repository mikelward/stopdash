package app.stopdash.domain

/** One row of the To… search results: a stop, or a geocoded place (SPEC *Find a station*). */
sealed interface SearchEntry {
    /** Names the row across updates: a stop by its id, a place by its name and coordinate. */
    val key: String

    data class Stop(val match: StationMatch) : SearchEntry {
        override val key: String get() = match.id
    }

    data class Place(val hit: PlaceHit) : SearchEntry {
        override val key: String get() = "place-${hit.name}@${hit.coordinate.latitude},${hit.coordinate.longitude}"
    }
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

    /**
     * [shown] with [stops] and [places] not yet in it added below, ranked among themselves ([merge]):
     * a list that answers arriving only ever add to, so a row never moves under a finger about to tap
     * it (maintainer, 2026-09-28: append, don't reorder). A row already shown keeps its place but takes
     * the newer answer's copy of itself, which may know more (TfL's position, or modes merged from a
     * same-named stop), so a tap opens and records the fuller match.
     *
     * [everyStop]: the complete, uncapped stop ranking, so a shown stop missing from it was folded into
     * a neighbor there (two records of one station, told apart only by TfL's positions) and goes, rather
     * than staying as a duplicate row. It's the one way a row leaves the list; a row only past the
     * result cap is still in [everyStop] and stays where it is.
     */
    fun appended(
        shown: List<SearchEntry>,
        query: String,
        stops: List<StationMatch>,
        places: List<PlaceHit>,
        everyStop: List<StationMatch>? = null,
    ): List<SearchEntry> {
        val incoming = merge(query, stops, places)
        val fresher = (everyStop.orEmpty().map(SearchEntry::Stop) + incoming).associateBy { it.key }
        val kept = if (everyStop != null) shown.filter { it !is SearchEntry.Stop || it.key in fresher } else shown
        val listed = kept.mapTo(HashSet()) { it.key }
        return kept.map { fresher[it.key] ?: it } + incoming.filter { it.key !in listed }
    }
}
