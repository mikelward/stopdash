package app.stopdash.domain

/**
 * The stops "Find a station" knows without asking TfL (SPEC *Finding stops → Find a station*):
 * the user's [recent] picks from the search, most recent first, and their [favorites] not picked
 * lately (the ends of favorite journeys, and the stops holding a starred row), both listed before
 * anything is typed, the recent first ([recentPicks], with the places a To… search picked among
 * them); and [known], the
 * stops the app has lately shown near the user. All of them match as the user types, alongside the
 * bundled stations, so a starred bus stop is found at once rather than after TfL's search. Read
 * from the device and kept there: nothing here is sent anywhere or logged.
 */
data class YourStops(
    val favorites: List<StationMatch> = emptyList(),
    val recent: List<StationMatch> = emptyList(),
    val known: List<StationMatch> = emptyList(),
    // Starred stops the device couldn't name — starred before places were recorded, and not shown
    // lately. [namedFrom] names the stations among them from the bundled list.
    val unnamedStarred: List<String> = emptyList(),
    // What's listed under Recent, most recent first: [recent]'s stops and the geocoded places picked
    // among them, in the order they were picked. Only the stops match as the user types.
    val recentPicks: List<SearchEntry> = recent.map(SearchEntry::Stop),
) {
    /** Every stop here once, the user's own first. */
    val all: List<StationMatch> get() = (recent + favorites + known).distinctBy { it.id }

    /**
     * These lists with each [unnamedStarred] stop that [index] holds (a station, not a bus stop)
     * added to the favorites, so a star from before places were recorded still lists by name.
     */
    fun namedFrom(index: StationIndex): YourStops {
        if (unnamedStarred.isEmpty()) return this
        val have = (favorites + recent).mapTo(HashSet()) { it.id }
        val named = unnamedStarred.mapNotNull(index::station).filter { it.id !in have }.sortedBy { it.name }
        return copy(favorites = favorites + named, unnamedStarred = emptyList())
    }

    /**
     * The stops the user chose — picked or starred — by last use: the recent, most recent first, then
     * the favorites not picked lately. Each leads its tier in a search, in this order.
     */
    val own: List<String> get() = (recent + favorites).map { it.id }.distinct()

    companion object {
        val EMPTY = YourStops()

        /**
         * Gathers the lists from what the device holds: the [recent] picks, stops and places, most
         * recent first; then
         * [journeys]' ends in their saved order and the [starred] rows' stops by name, as the
         * favorites, less any picked lately; and every [known] stop. Names are cleaned the way the
         * rest of the app shows them.
         */
        fun of(
            journeys: List<FavoriteJourney>,
            starred: List<StationMatch>,
            recent: List<SearchEntry>,
            known: List<StationMatch>,
            unnamedStarred: List<String> = emptyList(),
        ): YourStops {
            val journeyEnds = journeys.flatMap { journey ->
                val modes = listOf(journey.mode).filter { it.isNotBlank() }
                listOf(journey.from, journey.to).map { StationMatch(it.areaId.ifBlank { it.stopId }, it.name, modes) }
            }
            val picks = recent.mapNotNull { pick ->
                when (pick) {
                    is SearchEntry.Stop -> pick.match.copy(name = cleanStopName(pick.match.name))
                        .takeIf { it.name.isNotBlank() }?.let(SearchEntry::Stop)
                    is SearchEntry.Place -> pick.takeIf { it.hit.name.isNotBlank() }
                }
            }.distinctBy { it.key }
            val picked = picks.filterIsInstance<SearchEntry.Stop>().map { it.match }
            val pickedIds = picked.mapTo(HashSet()) { it.id }
            return YourStops(
                favorites = (journeyEnds + starred.sortedBy { cleanStopName(it.name) }).cleaned().filter { it.id !in pickedIds },
                recent = picked,
                known = known.cleaned(),
                unnamedStarred = unnamedStarred,
                recentPicks = picks,
            )
        }

        private fun List<StationMatch>.cleaned(): List<StationMatch> =
            map { it.copy(name = cleanStopName(it.name)) }.filter { it.name.isNotBlank() }.distinctBy { it.id }
    }
}

/**
 * The place to open from the search for a stop the app has shown: its stop area or station (TfL's
 * `stationNaptan`, [clusterId]) when it has one, so a bus stop's poles list as one place whose page
 * holds them all; else the stop itself. [clusterId] falls back to the name when TfL gives none, so
 * a cluster id equal to [name] isn't an id.
 */
fun stopPlace(stopId: String, name: String, clusterId: String, modes: List<String>): StationMatch {
    val id = clusterId.takeIf { it.isNotBlank() && it != name } ?: stopId
    return StationMatch(id, name, modes.filter { it.isNotBlank() }.distinct())
}

/** The recently opened stations, newest first: pure, so the rule is JVM-tested apart from storage. */
object RecentStations {
    /** How many opens are remembered — enough for the places a rider looks up this week. */
    const val MAX = 8

    /** [current] with [opened] moved (or added) to the front, capped at [max]. */
    fun add(current: List<StationMatch>, opened: StationMatch, max: Int = MAX): List<StationMatch> =
        (listOf(opened) + current.filter { it.id != opened.id }).take(max)

    /**
     * [current] with [picked], a stop or a geocoded place, moved (or added) to the front, capped at
     * [max]: one list, so a place picked last reads above a stop picked before it. A place is the same
     * pick again by its name and coordinate ([SearchEntry.key]).
     */
    fun add(current: List<SearchEntry>, picked: SearchEntry, max: Int = MAX): List<SearchEntry> =
        (listOf(picked) + current.filter { it.key != picked.key }).take(max)
}
