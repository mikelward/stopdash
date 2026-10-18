package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * One station or interchange in the bundled index (SPEC *Finding stops → Find a station*): its
 * TfL [id], cleaned [name], the [modes] it serves, and the interchange it belongs to ([interchangeId],
 * blank for none — and for a hub itself). A station also carries its public position and its
 * [lines] by mode (null/empty on an index built before they were), for [FartherStations]. Public
 * TfL facts, never user data.
 */
data class IndexedStation(
    val id: String,
    val name: String,
    val modes: List<String> = emptyList(),
    val interchangeId: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    // Mode → line ids: tube lines, National Rail services, Overground lines, and so on.
    val lines: Map<String, List<String>> = emptyMap(),
    // National Rail service → the ends of the routes this station is on (station ids), where TfL
    // gave route data: one service runs to different places from different stations.
    val routeEnds: Map<String, List<String>> = emptyMap(),
    // Line → the far ends (station ids) of a line this station is the terminus of: the end of every
    // route it's on there. Read by [Turnback], to relabel a train TfL lists as arriving here.
    val terminusEnds: Map<String, List<String>> = emptyMap(),
    // The platforms TfL lists under the station ("9100LIVSTLL1", "9400ZZLUKSX3"), for [StationIndex.stationOf].
    val platforms: List<String> = emptyList(),
    // The station model's membership ([Hubs]; dev-docs/station-model.md): TfL's ICS code, the station
    // this record belongs to ("1001276:rail") and its hub. Blank in an index built before them, and
    // on an interchange's own row.
    val icsCode: String = "",
    val station: String = "",
    val modelHub: String = "",
)

/**
 * The bundled list of London's stations and interchanges — every tube, DLR, Overground, Elizabeth
 * line, tram, rail and pier stop, not the ~20,000 bus stops — searched on the device as the user
 * types, so an abbreviation or a station code ("kx", "kgx") finds its station with no request and
 * no wait. TfL's own search still covers what the index doesn't (bus stops); [rank] orders both.
 */
class StationIndex(
    val stations: List<IndexedStation>,
    // The user's own stops by last use, by [StationMatch.key] ([YourStops.own]): each leads its tier in
    // this order, and so does the interchange it folds into, so a stop picked lately ranks above an
    // equally good one picked before it, and both above a stranger. An interchange's station name
    // leads as itself, apart from the interchange's own row.
    own: List<String> = emptyList(),
    // Each line's name as TfL spells it ("Hammersmith & City"), by id, for a line the index carries
    // by id alone. Empty in an older index.
    val lineNames: Map<String, String> = emptyMap(),
    // The bundle's content revision, shared with stops.tsv ([BundledStops.revision]); blank in an
    // index built before it.
    val revision: String = "",
    // Entries the file held that couldn't be read (no id or name) and were left out, so the station
    // model ([Hubs]) can refuse an index it can't vouch for whole.
    val skippedEntries: Int = 0,
) {
    /**
     * The stations matching [query], best first ([rank]), at most [limit]. A station whose
     * interchange also matches is left out: the interchange's page already holds it, and listing
     * both reads as the same place twice ("King's Cross St. Pancras" hub and tube station).
     * [near], where the trip starts, puts the nearer of same-named stations first, as [rank] does.
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT, near: Coordinates? = null): List<StationMatch> {
        val scored = stations.mapNotNull { station ->
            StationMatcher.tier(query, station.name, station.id, station.interchangeId)?.let {
                val at = if (station.latitude != null && station.longitude != null) Coordinates(station.latitude, station.longitude) else null
                Scored(StationMatch(station.id, station.name, station.modes, level = levelOf(station.id, station.modes)), station.interchangeId, it, query, distanceFrom(near, at), station)
            }
        } + hubNames.mapNotNull { name ->
            // By its name alone: a station code ("kgx") is the interchange's, and finds that row.
            StationMatcher.tier(query, name.name)?.let {
                Scored(name, "", it, query, if (near == null) 0.0 else distanceFrom(near, positionOf(name.id)))
            }
        }
        val matchedIds = scored.mapTo(HashSet()) { it.match.id }
        return scored
            .filter { it.interchangeId.isBlank() || it.interchangeId !in matchedIds }
            .sortedWith(
                compareBy(
                    { it.tier }, { leadOf(it.match) }, { !it.match.id.startsWith("HUB", ignoreCase = true) },
                    // As in [rank]: the exact name, one the query starts, then a station before a bus stop
                    // (one of the user's, joined by withYours), then the nearer to where the trip starts.
                    { !it.exact }, { !it.startsName },
                    { it.match.modes.isNotEmpty() && it.match.modes.all { mode -> mode == "bus" } },
                    { it.distance },
                    { it.match.name },
                ),
            )
            .let { foldTwins(it, query) }
            .take(limit)
            .map { it.match }
    }

    /**
     * [ranked] with a station TfL lists under two ids ([sameStation]: Reading's two records at one spot) as
     * one row, at the better-ranked one's place, under the user's own twin or else the one with the most
     * modes, and with every twin's modes, so search shows Reading once with its Elizabeth line, whichever record matched ("RDNG4AB" finds
     * only the leaner one). Opened, its page asks the other ids too ([sameStationIds]).
     */
    private fun foldTwins(ranked: List<Scored>, query: String): List<Scored> {
        val kept = ArrayList<Scored>(ranked.size)
        val leads = HashSet<String>()
        for (scored in ranked) {
            val station = scored.station
            val group = station?.let { twins[it.id] }
            if (group == null) {
                kept += scored
                continue
            }
            // One lead per group, whichever twin matched: the user's own twin if they have one (a recent or
            // starred stop keeps its key, so it isn't listed again under another), then the most modes,
            // then the lowest id.
            val lead = group.minWith(
                compareBy<IndexedStation> { leadOf(StationMatch(it.id, it.name, it.modes)) }
                    .thenByDescending { it.modes.size }
                    .thenBy { it.id },
            )
            if (!leads.add(lead.id)) continue
            val modes = (scored.match.modes + group.flatMap { it.modes }).distinct()
            kept += Scored(StationMatch(lead.id, lead.name, modes, level = levelOf(lead.id, modes)), scored.interchangeId, scored.tier, query, scored.distance, lead)
        }
        return kept
    }

    /**
     * Each station TfL lists under several ids ([sameStation]), by each id, to the whole group, itself
     * included; a station under one id is absent. Built on first read from the whole index: a worker's work.
     */
    @get:WorkerThread
    private val twins: Map<String, List<IndexedStation>> by lazy {
        val byName = stations.filter { it.interchangeId.isBlank() && !it.isHub }.groupBy { cleanStopName(it.name) }
        buildMap {
            for (same in byName.values) {
                if (same.size < 2) continue
                for (station in same) {
                    val group = same.filter { it === station || sameStation(it, station) }
                    if (group.size > 1) put(station.id, group)
                }
            }
        }
    }

    private class Scored(
        val match: StationMatch,
        val interchangeId: String,
        val tier: StationMatchTier,
        query: String,
        val distance: Double,
        val station: IndexedStation? = null,
    ) {
        val exact = StationMatcher.isExact(query, match.name)
        val startsName = StationMatcher.startsName(query, match.name)
    }

    /**
     * Each interchange's station names, as rows of their own beside the interchange's (maintainer,
     * 2026-10-09), so a rider finds King's Cross & St Pancras by either name: "King's Cross" (Tube ·
     * National Rail) and "St Pancras International" (National Rail). A member's name is cleaned of
     * what tells its records apart rather than places ("London …", "… LL", a line in brackets), and
     * members of one name are one row; a member named for two others ("King's Cross St. Pancras",
     * the tube station) joins the first; a name the interchange itself goes by is left to its row
     * (West Hampstead lists "West Hampstead Thameslink" only). An interchange whose members come to
     * one name lists none:
     * Paddington, Waterloo and Victoria stay one row each. Each row's modes put the Underground
     * first ([MODE_ORDER]), and are its [StationMatch.lead].
     */
    // Built on first read from the whole index: a worker's work.
    @get:WorkerThread
    val hubNames: List<StationMatch> by lazy { memberRows.values.flatMap { rows -> rows.map { it.match } } }

    /** One of an interchange's station-name rows ([hubNames]) and the records it was made from, in index order. */
    data class MemberRow(val match: StationMatch, val records: List<String>)

    /**
     * Each interchange's [hubNames] rows by its id, with the records behind each, so the station model
     * can tell what a row stood for ([Hubs.picked]): St Pancras's row its own station, the King's Cross
     * row, which takes in the Underground's name, two stations.
     */
    // Built on first read from the whole index: a worker's work.
    @get:WorkerThread
    val memberRows: Map<String, List<MemberRow>> by lazy {
        stations.filter { it.interchangeId.isNotBlank() && it.interchangeId in byId }
            .groupBy { it.interchangeId }
            .mapValues { (hub, members) -> rowsIn(hub, members) }
            .filterValues { it.isNotEmpty() }
    }

    private class Group(val display: String) {
        val modes = mutableListOf<String>()
        val records = mutableListOf<String>()
    }

    private fun rowsIn(hub: String, members: List<IndexedStation>): List<MemberRow> {
        // Display name, modes and records, by the name as compared, in the members' order.
        val groups = LinkedHashMap<String, Group>()
        // "London" is the place's own name where the interchange says so (London Bridge).
        val keepLondon = byId.getValue(hub).name.startsWith("London ")
        for (member in members) {
            val display = memberName(member.name, keepLondon)
            if (display.isBlank()) continue
            val group = groups.getOrPut(StationMatcher.normalize(display).lowercase()) { Group(display) }
            group.modes += member.modes
            group.records += member.id
        }
        for (joint in groups.keys.toList()) {
            val base = groups.keys.firstOrNull { base ->
                base != joint && joint.startsWith("$base ") &&
                    groups.keys.any { other -> other != base && other != joint && other.startsWith(joint.removePrefix("$base ")) }
            } ?: continue
            groups.remove(joint)?.let { folded ->
                groups.getValue(base).modes += folded.modes
                groups.getValue(base).records += folded.records
            }
        }
        if (groups.size < 2) return emptyList()
        // A name the interchange already goes by is its own row ("West Hampstead"): not a second one.
        val hubName = StationMatcher.normalize(memberName(byId.getValue(hub).name, keepLondon)).lowercase()
        groups.remove(hubName)
        return groups.values.map { group ->
            val ordered = group.modes.filter { it.isNotBlank() }.distinct()
                .sortedBy { MODE_ORDER.indexOf(it).let { i -> if (i < 0) MODE_ORDER.size else i } }
            MemberRow(StationMatch(hub, group.display, ordered, lead = ordered), group.records)
        }
    }

    /**
     * Every line of the stations within [meters] of any of [places], by id, each once with its mode and name
     * ([lineNames], else its id, as riders say it: [riderLineName]): the lines a rider at one of them can take with no bus (the index holds no
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
                ids.forEach { id -> if (id.isNotBlank()) lines.getOrPut(id) { LineRef(id, riderLineName(lineNames[id] ?: id, mode), mode) } }
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
    fun rank(query: String, local: List<StationMatch>, remote: List<StationMatch>, limit: Int = DEFAULT_LIMIT, near: Coordinates? = null): List<StationMatch> {
        // A bundled station has no position of its own; TfL's copy of it lends one, for the fold below.
        val placed = remote.filter { it.latitude != null && it.longitude != null }.associateBy { it.id }
        // An interchange's station name stays unplaced, so it never folds into the interchange's own row
        // of the same place, nor anything into it: a row once listed stays where it is.
        val candidates = (local.map { match -> placed[match.id]?.takeIf { match.lead.isEmpty() }?.let { match.copy(latitude = it.latitude, longitude = it.longitude) } ?: match } + remote)
            .distinctBy { it.key }
        val matchedIds = candidates.mapTo(HashSet()) { it.id }
        // One ranking over both sources, by the same rules as [search] — tier, the user's own stops
        // first, then interchanges, the exact name, a name the query starts, a station before a bus
        // stop, then the nearer —
        // so a TfL bus stop that matches as well
        // as a bundled station sorts among them rather than after all of them. Source order only breaks an exact tie.
        // A match the matcher can't place (TfL found it by a rule this one doesn't share) goes last.
        // Which names are exactly what's typed, punctuation and case aside, so "St. Paul's" is "St Paul's".
        val exact = candidates.associate { it.key to StationMatcher.isExact(query, it.name) }
        val starts = candidates.associate { it.key to StationMatcher.startsName(query, it.name) }
        // Each one's distance from where the trip starts, its own position or the index's: none (all
        // equal) without a start.
        val distances = if (near == null) emptyMap() else candidates.associate { match ->
            val at = if (match.latitude != null && match.longitude != null) Coordinates(match.latitude, match.longitude) else positionOf(match.id)
            match.key to distanceFrom(near, at)
        }
        return candidates
            .filter { hubOf[it.id]?.let { hub -> hub in matchedIds } != true }
            .withIndex()
            .sortedWith(
                compareBy(
                    { tierOf(query, it.value)?.ordinal ?: StationMatchTier.entries.size },
                    { leadOf(it.value) },
                    { !it.value.id.startsWith("HUB", ignoreCase = true) },
                    // The exact name first, then the better known: a station before a bus stop, so "St Paul's"
                    // means the station. A name's length is no measure of a match (maintainer, 2026-10-12).
                    { exact[it.value.key] != true },
                    // Then a name the query starts, not just one of its parts ("st pa", St Pancras International).
                    { starts[it.value.key] != true },
                    { it.value.modes.isNotEmpty() && it.value.modes.all { mode -> mode == "bus" } },
                    // Then the nearer, from where the trip starts (maintainer, 2026-10-12).
                    { distances[it.value.key] ?: 0.0 },
                    { it.value.name },
                    { it.index },
                ),
            )
            .map { it.value }
            .let(::foldNeighbors)
            .take(limit)
    }

    // How far [at] is from [near], in meters; 0 without a start, and past every known stop without a
    // position, so a stop that can't be placed follows those that can.
    private fun distanceFrom(near: Coordinates?, at: Coordinates?): Double = when {
        near == null -> 0.0
        at == null -> Double.MAX_VALUE
        else -> NearestStops.distanceMeters(near.latitude, near.longitude, at.latitude, at.longitude)
    }

    // An interchange's station name matches by its name alone, as in [search].
    private fun tierOf(query: String, match: StationMatch): StationMatchTier? = tier(query, match)

    /**
     * How well [match] matches [query], as [rank] places it: a station TfL lists twice by any of its
     * twins' ids, so the row [search] folds them into keeps a code only the other twin carries
     * ("rdng4ab" for Reading). An interchange's station name matches by its name alone. A worker's work.
     */
    @WorkerThread
    fun tier(query: String, match: StationMatch): StationMatchTier? {
        if (match.lead.isNotEmpty()) return StationMatcher.tier(query, match.name)
        val ids = twins[match.id]?.map { it.id } ?: listOf(match.id)
        return ids.mapNotNull { StationMatcher.tier(query, match.name, it, hubOf[it].orEmpty()) }.minOrNull()
    }

    // Each indexed station's interchange, for folding TfL's matches the way [search] folds its own.
    private val hubOf: Map<String, String> =
        stations.filter { it.interchangeId.isNotBlank() }.associate { it.id to it.interchangeId }

    // Each own stop's place in [own], by key, and its interchange's (the earliest of its members').
    // A station name's key isn't a member's id, so it lends the interchange's row nothing.
    private val leads: Map<String, Int> = buildMap {
        own.forEachIndexed { i, key ->
            putIfAbsent(key, i)
            hubOf[key]?.let { putIfAbsent(it, i) }
        }
    }

    private fun leadOf(match: StationMatch): Int = leads[match.key] ?: Int.MAX_VALUE

    /**
     * Whether [match] leads its tier ahead of how well its name matches: one of the user's own stops,
     * or an interchange, as [search] and [rank] order them. [SearchResults.merge] keeps a place behind it.
     */
    fun leads(match: StationMatch): Boolean = leadOf(match) != Int.MAX_VALUE || match.id.startsWith("HUB", ignoreCase = true)

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

    /**
     * The far ends of each line [stopId] is the terminus of ([IndexedStation.terminusEnds]), named,
     * for [Turnback]; empty for a stop that ends no line, or that the index doesn't know. Read where
     * arrivals are decoded, off the main thread, like [Turnback.relabel] it feeds.
     */
    @WorkerThread
    fun terminusEnds(stopId: String): Map<String, List<Turnback.End>> =
        byId[stopId]?.terminusEnds.orEmpty().mapValues { (_, ends) ->
            ends.map { id -> Turnback.End(id, byId[id]?.name.orEmpty().ifBlank { id }) }
        }

    /** The listed station with [id], as a match, or null for one the list doesn't hold. */
    fun station(id: String): StationMatch? = byId[id]?.let { StationMatch(it.id, it.name, it.modes, level = levelOf(it.id, it.modes)) }

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
        val members = stations.filter { it.interchangeId == id && it.latitude != null && it.longitude != null }
        if (members.isEmpty()) return null
        return Coordinates(members.mapNotNull { it.latitude }.average(), members.mapNotNull { it.longitude }.average())
    }

    /**
     * Where the stop [id] is: a listed station's own point ([positionOf]), or that of the station a
     * platform is listed under ([stationOf]). Null for a stop the index doesn't place.
     */
    fun placeOf(id: String): Coordinates? = positionOf(id) ?: stationOf(id)?.let(::positionOf)

    /**
     * The interchange the stop [id] belongs to: a listed station's ([IndexedStation.interchangeId]), or that
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
        /**
         * The level a bundled record's own row is a pick at: an interchange's is the hub, a record
         * serving buses alone a bus stop, any other a station.
         */
        fun levelOf(id: String, modes: List<String>): Pick.Level = when {
            id.startsWith("HUB", ignoreCase = true) -> Pick.Level.HUB
            modes.isNotEmpty() && modes.all { it == "bus" } -> Pick.Level.STOP
            else -> Pick.Level.STATION
        }

        const val DEFAULT_LIMIT = 20

        // The order a station name's modes lead in: the Underground first, then the rest of TfL's
        // metro-like services, then National Rail and the rarer modes.
        private val MODE_ORDER = listOf(
            "tube", "elizabeth-line", "overground", "dlr", "tram", "national-rail", "international-rail", "river-bus", "cable-car", "bus",
        )

        /**
         * An interchange member's name as a rider says it: [cleanStopName], less what only tells
         * TfL's records apart — a leading "London" (unless [keepLondon]: London Bridge), a trailing
         * "LL" (St Pancras's low level), anything in brackets ("Paddington (H&C Line)", "Abbey Wood
         * (London)"), a tram stop's or pier's type.
         */
        internal fun memberName(raw: String, keepLondon: Boolean = false): String =
            matchStopName(raw)
                .replace(RECORD_SUFFIX, "")
                .replace(QUALIFIER, "")
                .replace(LOW_LEVEL, "")
                .let { if (keepLondon) it else it.replace(LONDON, "") }
                .trim()

        // What [matchStopName] leaves that only tells TfL's records of one station apart: a tram stop's
        // or pier's type ("Beckenham Junction Tram Stop"), a place in brackets ("Abbey Wood (London)").
        private val RECORD_SUFFIX = Regex("""\s+(Tram Stop|Pier)$""", RegexOption.IGNORE_CASE)
        private val QUALIFIER = Regex("""\s*\([^)]*\)$""")
        private val LOW_LEVEL = Regex("""\s+LL$""")
        private val LONDON = Regex("""^London\s+""")

        // NaPTAN's prefixes: a rail access area and its station, a metro one and its station.
        private const val RAIL_PLATFORM = "9100"
        private const val RAIL_STATION = "910G"
        private const val METRO_PLATFORM = "9400"
        private const val METRO_STATION = "940G"

        /**
         * [ranked] with each result dropped into a better-ranked one that is the same place by name
         * ([StopPlace.sameNamed]: TfL lists a place's bus stop areas one by one, so "Archway" came back
         * once per stand) — the same name, or a bus stop named for the station up to its cross street. The
         * kept result opens the whole place: a From… page lists the stops around it.
         * A result without a position is never folded, since nothing says it's the same place. The kept
         * result takes on the folded ones' modes, so a station with buses at its door reads "Tube · Bus"
         * rather than "Tube".
         */
        fun foldNeighbors(ranked: List<StationMatch>): List<StationMatch> {
            val kept = ArrayList<StationMatch>(ranked.size)
            for (match in ranked) {
                val into = kept.indexOfFirst { other -> folds(match, other) }
                if (into < 0) {
                    kept += match
                } else {
                    kept[into] = kept[into].let { it.withModes((it.modes + match.modes).distinct()) }
                }
            }
            return kept
        }

        /**
         * Whether [match] folds into the better-ranked [into]: the same place by name ([StopPlace.sameNamed]),
         * and either the same name, or [match] a bus stop named for [into], a station, up to its cross street.
         * Never a station into a bus stop's row of another name, which would read as the station while
         * carrying the bus stop's id, and never two bus stops named apart, which a rider picks one by one.
         */
        private fun folds(match: StationMatch, into: StationMatch): Boolean {
            if (match.latitude == null || match.longitude == null) return false
            val a = StopPlace.Stop(match.name, match.latitude, match.longitude)
            val b = StopPlace.Stop(into.name, into.latitude, into.longitude)
            if (!StopPlace.sameNamed(a, b)) return false
            return wholeName(match.name) == wholeName(into.name) || (busOnly(match) && !busOnly(into))
        }

        private fun wholeName(name: String): String = StationMatcher.normalize(matchStopName(name)).lowercase()

        private fun busOnly(match: StationMatch): Boolean = match.modes.isNotEmpty() && match.modes.all { it == "bus" }

        val EMPTY = StationIndex(emptyList())
    }
}

private val IndexedStation.isHub: Boolean get() = id.startsWith("HUB", ignoreCase = true)
