package app.stopdash.domain

/** One row of the To… search results: a stop, or a geocoded place (SPEC *Find a station*). */
sealed interface SearchEntry {
    /** Names the row across updates: a stop by its id, a place by its name and coordinate. */
    val key: String

    data class Stop(val match: StationMatch) : SearchEntry {
        override val key: String get() = match.key
    }

    data class Place(val hit: PlaceHit) : SearchEntry {
        override val key: String get() = "place-${hit.name}@${hit.coordinate.latitude},${hit.coordinate.longitude}"
    }
}

object SearchResults {
    private const val BUS_MODE = "bus"

    /**
     * The [stops] and geocoded [places] for [query] as one list, ranked by how well each name
     * matches, so a place the query starts ("Tate Britain" for "tate") sits above a stop that only
     * contains it ("… Estate") rather than after every stop (maintainer, 2026-09-28). Each list keeps
     * its own order, both already ranked; a place goes before the first stop that matches worse than
     * it does (a worse tier, or the same tier with a name less exactly the query's), and a stop wins
     * a tie. A stop that [leads] its tier (the user's own, an interchange) goes ahead of any place in it,
     * however exactly the place is named, as the stops' own ranking puts it first. A [PlaceHit.notable]
     * landmark wins the tie against a bus stop, though not a station: "st pau" lists St Paul's tube,
     * then St Paul's Cathedral, then the St Paul's bus stops (maintainer, 2026-10-13). A postcode's
     * places, whose names needn't contain the query, follow the stops, as a match the matcher can't
     * place does.
     */
    fun merge(
        query: String,
        stops: List<StationMatch>,
        places: List<PlaceHit>,
        leads: (StationMatch) -> Boolean = { false },
        // How well a stop matches, as the stops' ranking placed it ([StationIndex.tier]); by its own name
        // and id without the index.
        tier: (StationMatch) -> StationMatchTier? = { StationMatcher.tier(query, it.name, it.id.takeIf { _ -> it.lead.isEmpty() }.orEmpty()) },
    ): List<SearchEntry> {
        val unplaced = StationMatchTier.entries.size
        // How well a name matches, as both lists rank it: its tier, then whether it's exactly the query,
        // then whether the query starts it (maintainer, 2026-10-12), packed so a smaller number is better.
        fun scoreOf(tier: Int, name: String) =
            tier * 4 + (if (StationMatcher.isExact(query, name)) 0 else 2) + (if (StationMatcher.startsName(query, name)) 0 else 1)
        // An interchange's station name by its name alone, as the index ranks it ([StationIndex.search]).
        fun scoreOf(stop: StationMatch): Int {
            val stopTier = tier(stop)?.ordinal ?: unplaced
            return if (leads(stop)) stopTier * 4 else scoreOf(stopTier, stop.name)
        }
        fun scoreOf(place: PlaceHit) =
            if (place.kind == PlaceKind.POSTCODE) unplaced * 4 + 3 else scoreOf(StationMatcher.tier(query, place.name)?.ordinal ?: unplaced, place.name)
        // A stop only buses call at: a landmark matched as well goes above it.
        fun busOnly(stop: StationMatch) = stop.modes.isNotEmpty() && stop.modes.all { it.equals(BUS_MODE, ignoreCase = true) }
        val merged = ArrayList<SearchEntry>(stops.size + places.size)
        var p = 0
        for (stop in stops) {
            val stopScore = scoreOf(stop)
            val yieldsToLandmark = busOnly(stop) && !leads(stop)
            while (p < places.size) {
                val placeScore = scoreOf(places[p])
                if (placeScore < stopScore || (placeScore == stopScore && places[p].notable && yieldsToLandmark)) {
                    merged += SearchEntry.Place(places[p++])
                } else {
                    break
                }
            }
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
        // Which stops lead their tier ([merge]).
        leads: (StationMatch) -> Boolean = { false },
        // How well a stop matches ([merge]).
        tier: ((StationMatch) -> StationMatchTier?)? = null,
    ): List<SearchEntry> {
        val incoming = if (tier == null) merge(query, stops, places, leads) else merge(query, stops, places, leads, tier)
        val fresher = (everyStop.orEmpty().map(SearchEntry::Stop) + incoming).associateBy { it.key }
        val kept = if (everyStop != null) shown.filter { it !is SearchEntry.Stop || it.key in fresher } else shown
        val listed = kept.mapTo(HashSet()) { it.key }
        return kept.map { fresher[it.key] ?: it } + incoming.filter { it.key !in listed }
    }
}
