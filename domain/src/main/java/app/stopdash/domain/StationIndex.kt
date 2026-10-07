package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * One station or interchange in the bundled index (SPEC *Finding stops → Find a station*): its
 * TfL [id], cleaned [name], the [modes] it serves, and the interchange it belongs to ([hubId],
 * blank for none — and for a hub itself). A station also carries its public position and its
 * [lines] by mode (null/empty on an index built before they were), for [FartherStations]. Public
 * TfL facts, never user data.
 */
data class IndexedStation(
    val id: String,
    val name: String,
    val modes: List<String> = emptyList(),
    val hubId: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    // Mode → line ids: tube lines, National Rail services, Overground lines, and so on.
    val lines: Map<String, List<String>> = emptyMap(),
    // National Rail service → the ends of the routes this station is on (station ids), where TfL
    // gave route data: one service runs to different places from different stations.
    val routeEnds: Map<String, List<String>> = emptyMap(),
    // The platforms TfL lists under the station ("9100LIVSTLL1", "9400ZZLUKSX3"), for [StationIndex.stationOf].
    val platforms: List<String> = emptyList(),
)

/**
 * The bundled list of London's stations and interchanges — every tube, DLR, Overground, Elizabeth
 * line, tram, rail and pier stop, not the ~20,000 bus stops — searched on the device as the user
 * types, so an abbreviation or a station code ("kx", "kgx") finds its station with no request and
 * no wait. TfL's own search still covers what the index doesn't (bus stops); [rank] orders both.
 */
class StationIndex(
    val stations: List<IndexedStation>,
    // The user's own stops by last use ([YourStops.own]): each leads its tier in this order, and so
    // does the interchange it folds into, so a stop picked lately ranks above an equally good one
    // picked before it, and both above a stranger.
    own: List<String> = emptyList(),
    // Each line's name as TfL spells it ("Hammersmith & City"), by id, for a line the index carries
    // by id alone. Empty in an older index.
    val lineNames: Map<String, String> = emptyMap(),
) {
    /**
     * The stations matching [query], best first ([rank]), at most [limit]. A station whose
     * interchange also matches is left out: the interchange's page already holds it, and listing
     * both reads as the same place twice ("King's Cross St. Pancras" hub and tube station).
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<StationMatch> {
        val scored = stations.mapNotNull { station ->
            StationMatcher.tier(query, station.name, station.id, station.hubId)?.let { station to it }
        }
        val matchedIds = scored.mapTo(HashSet()) { it.first.id }
        return scored
            .filter { (station, _) -> station.hubId.isBlank() || station.hubId !in matchedIds }
            .sortedWith(
                compareBy({ it.second }, { leadOf(it.first.id) }, { !it.first.isHub }, { it.first.name.length }, { it.first.name }),
            )
            .take(limit)
            .map { (station, _) -> StationMatch(station.id, station.name, station.modes) }
    }

    /**
     * Every line of the stations within [meters] of any of [places], by id, each once with its mode and name
     * ([lineNames], else its id): the lines a rider at one of them can take with no bus (the index holds no
     * bus stops). A station with no position never counts. In the stations' order. Walks every station for
     * every place: on a worker only.
     */
    @WorkerThread
    fun linesNear(places: Collection<Coordinates>, meters: Int): List<LineRef> {
        if (places.isEmpty()) return emptyList()
        val lines = LinkedHashMap<String, LineRef>()
        for (station in stations) {
            val lat = station.latitude ?: continue
            val lon = station.longitude ?: continue
            if (places.none { NearestStops.distanceMeters(it.latitude, it.longitude, lat, lon) <= meters }) continue
            for ((mode, ids) in station.lines) {
                ids.forEach { id -> if (id.isNotBlank()) lines.getOrPut(id) { LineRef(id, lineNames[id] ?: id, mode) } }
            }
        }
        return lines.values.toList()
    }

    /**
     * The index's matches and TfL's (bus stops, or a station newer than the bundled list) in one
     * list, each placed by how well its own name matches — so a bus stop
     * named for the query still sorts among the stations, and one TfL found by a rule this matcher
     * doesn't share goes last rather than being dropped. Deduplicated by id, and a station the index
     * knows belongs to an interchange that either source matched stays folded into it, as in [search].
     */
    fun rank(query: String, local: List<StationMatch>, remote: List<StationMatch>, limit: Int = DEFAULT_LIMIT): List<StationMatch> {
        // A bundled station has no position of its own; TfL's copy of it lends one, for the fold below.
        val placed = remote.filter { it.latitude != null && it.longitude != null }.associateBy { it.id }
        val candidates = (local.map { match -> placed[match.id]?.let { match.copy(latitude = it.latitude, longitude = it.longitude) } ?: match } + remote)
            .distinctBy { it.id }
        val matchedIds = candidates.mapTo(HashSet()) { it.id }
        // One ranking over both sources, by the same rules as [search] — tier, the user's own stops
        // first, then interchanges, shorter name, then name — so a TfL bus stop that matches as well
        // as a bundled station sorts among them rather than after all of them. Source order only breaks an exact tie.
        // A match the matcher can't place (TfL found it by a rule this one doesn't share) goes last.
        return candidates
            .filter { hubOf[it.id]?.let { hub -> hub in matchedIds } != true }
            .withIndex()
            .sortedWith(
                compareBy(
                    { StationMatcher.tier(query, it.value.name, it.value.id, hubOf[it.value.id].orEmpty())?.ordinal ?: StationMatchTier.entries.size },
                    { leadOf(it.value.id) },
                    { !it.value.id.startsWith("HUB", ignoreCase = true) },
                    { it.value.name.length },
                    { it.value.name },
                    { it.index },
                ),
            )
            .map { it.value }
            .let(::foldNeighbors)
            .take(limit)
    }

    // Each indexed station's interchange, for folding TfL's matches the way [search] folds its own.
    private val hubOf: Map<String, String> =
        stations.filter { it.hubId.isNotBlank() }.associate { it.id to it.hubId }

    // Each own stop's place in [own], and its interchange's (the earliest of its members').
    private val leads: Map<String, Int> = buildMap {
        own.forEachIndexed { i, id ->
            putIfAbsent(id, i)
            hubOf[id]?.let { putIfAbsent(it, i) }
        }
    }

    private fun leadOf(id: String): Int = leads[id] ?: Int.MAX_VALUE

    private val byId: Map<String, IndexedStation> by lazy { stations.associateBy { it.id } }

    // Each listed platform's station. One listed under two stations says nothing about which a
    // train leaves, so it's neither's.
    private val platformStation: Map<String, String> by lazy {
        val owners = HashMap<String, MutableSet<String>>()
        stations.forEach { station -> station.platforms.forEach { owners.getOrPut(it) { HashSet() } += station.id } }
        owners.mapNotNull { (platform, ids) -> ids.singleOrNull()?.let { platform to it } }.toMap()
    }

    /**
     * The station a train leaves or reaches at [platform] (SPEC *Trips with a change*): the Journey
     * Planner can name a rail leg's end by its platform alone ("9100LIVSTLL1", no station id), and
     * the live feed and National Rail's boards know only the station. The one TfL lists it under
     * ([IndexedStation.platforms]), whatever code it carries (St Pancras's "9100STPXBOX1" is under
     * "910GSTPX"); else the listed station its id names, as NaPTAN names a station's own access area
     * by the station's code ("9100STPXBOX" is at "910GSTPXBOX", "9400ZZLUKSX" at "940GZZLUKSX"),
     * which TfL's listings leave out. Null for any other id, or a station the index doesn't hold.
     */
    fun stationOf(platform: String): String? {
        platformStation[platform]?.let { return it }
        val station = when {
            platform.startsWith(RAIL_PLATFORM) -> RAIL_STATION + platform.removePrefix(RAIL_PLATFORM)
            platform.startsWith(METRO_PLATFORM) -> METRO_STATION + platform.removePrefix(METRO_PLATFORM)
            else -> return null
        }
        return station.takeIf { it in byId }
    }

    /** The listed station with [id], as a match, or null for one the list doesn't hold. */
    fun station(id: String): StationMatch? = byId[id]?.let { StationMatch(it.id, it.name, it.modes) }

    /**
     * The bundled station's own public position, when the index has one. Used to anchor a favorite
     * place from a bundled match even when TfL's search (which normally lends the position back) is
     * unreachable (SPEC D9). Search/rank leave [StationMatch] positionless; this fills it by id.
     */
    fun positionOf(id: String): Coordinates? {
        val station = byId[id]
        val lat = station?.latitude
        val lon = station?.longitude
        if (lat != null && lon != null) return Coordinates(lat, lon)
        // A hub record carries no position of its own; derive a center from its positioned members,
        // so a bundled interchange (King's Cross St. Pancras) is still selectable offline.
        val members = stations.filter { it.hubId == id && it.latitude != null && it.longitude != null }
        if (members.isEmpty()) return null
        return Coordinates(members.mapNotNull { it.latitude }.average(), members.mapNotNull { it.longitude }.average())
    }

    /**
     * Where the stop [id] is: a listed station's own point ([positionOf]), or that of the station a
     * platform is listed under ([stationOf]). Null for a stop the index doesn't place.
     */
    fun placeOf(id: String): Coordinates? = positionOf(id) ?: stationOf(id)?.let(::positionOf)

    /**
     * The interchange the stop [id] belongs to: a listed station's ([IndexedStation.hubId]), or that
     * of the station a platform is listed under, or [id] itself when it names an interchange. Null
     * for a stop in none, or one the index doesn't hold.
     */
    fun interchangeOf(id: String): String? {
        val station = id.takeIf { it in byId } ?: stationOf(id) ?: return null
        return hubOf[station] ?: station.takeIf { byId[it]?.isHub == true }
    }

    /**
     * This index with [yours] added (SPEC *Finding stops → Find a station*): the user's starred,
     * opened and lately shown stops that the bundled list lacks — bus stops, mostly — so they match
     * as the user types instead of after TfL's search, and the user's own stops lead their tier.
     */
    fun withYours(yours: YourStops): StationIndex {
        if (yours.all.isEmpty()) return this
        val indexed = stations.mapTo(HashSet()) { it.id }
        val extra = yours.all.filter { it.id !in indexed }.map { IndexedStation(it.id, it.name, it.modes) }
        return StationIndex(stations + extra, yours.own, lineNames)
    }

    companion object {
        const val DEFAULT_LIMIT = 20

        // NaPTAN's prefixes: a rail access area and its station, a metro one and its station.
        private const val RAIL_PLATFORM = "9100"
        private const val RAIL_STATION = "910G"
        private const val METRO_PLATFORM = "9400"
        private const val METRO_STATION = "940G"

        /**
         * How near two same-named results must be to read as one place: a station and the bus stop
         * areas around it ("Archway", once per stand) are a street or two apart, well inside this;
         * two "Church Street"s in different boroughs are miles apart and both stay.
         */
        const val FOLD_RADIUS_METERS = 250.0

        /**
         * [ranked] with each result dropped when a better-ranked one of the same name ([matchStopName]) lies
         * within [FOLD_RADIUS_METERS] of it — TfL lists a place's bus stop areas one by one, so
         * "Archway" came back once per stand. The kept result opens the whole place: a From… page
         * lists the stops around it.
         * A result without a position is never folded, since nothing says it's the same place. The
         * kept result takes on the folded ones' modes, so a station with buses at its door reads
         * "Tube · Bus" rather than "Tube".
         */
        fun foldNeighbors(ranked: List<StationMatch>): List<StationMatch> {
            val kept = ArrayList<StationMatch>(ranked.size)
            for (match in ranked) {
                val lat = match.latitude
                val lon = match.longitude
                val name = StationMatcher.normalize(matchStopName(match.name))
                val into = if (lat == null || lon == null) {
                    -1
                } else {
                    kept.indexOfFirst { other ->
                        val otherLat = other.latitude
                        val otherLon = other.longitude
                        otherLat != null && otherLon != null &&
                            StationMatcher.normalize(matchStopName(other.name)) == name &&
                            !conflictingQualifiers(match.name, other.name) &&
                            NearestStops.distanceMeters(lat, lon, otherLat, otherLon) <= FOLD_RADIUS_METERS
                    }
                }
                if (into < 0) {
                    kept += match
                } else {
                    kept[into] = kept[into].let { it.copy(modes = (it.modes + match.modes).distinct()) }
                }
            }
            return kept
        }
        val EMPTY = StationIndex(emptyList())
    }
}

private val IndexedStation.isHub: Boolean get() = id.startsWith("HUB", ignoreCase = true)
