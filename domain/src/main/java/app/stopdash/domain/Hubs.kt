package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * The station model (dev-docs/station-model.md): which station, stop and hub any TfL id belongs to,
 * read from the bundle, never worked out here. The weekly bundle refresh groups them
 * (`scripts/build_station_index.py`, `scripts/stop_membership.py`); this only looks them up.
 *
 * Three levels: a **stand** (a platform or a pole) belongs to one **station** (the records sharing
 * TfL's ICS code and a mode family: St Pancras's three records are one) or, for buses, one **stop**
 * (both directions of its road); each station and stop belongs to one **hub** (a TfL interchange, or
 * a lone station with its own bus stops, or a junction of bus stops). Each level's id is the
 * bundle's: a station `1001276:rail`, a stop its TfL area or anchor pole, a hub its interchange
 * (`HUBKGX`), its one station's id or its anchor stop's.
 *
 * Not read by any screen yet: phase 2 points station identity at it, phase 3 hubs.
 */
class Hubs private constructor(
    private val stationOfRecord: Map<String, String>,
    private val hubOfStation: Map<String, String>,
    private val stationPoints: Map<String, List<Coordinates>>,
    private val stopOfPole: Map<String, String>,
    private val hubOfStop: Map<String, String>,
    private val stopPoints: Map<String, List<Coordinates>>,
    private val stopsOfArea: Map<String, Set<String>>,
    private val hubMembers: Map<String, Members>,
    private val platformStation: (String) -> String?,
    // For [picked] and [idsOf]: each station's records (index order, then stops.tsv's) and their
    // platforms, each stop's poles (by id) and the station owning it, each pole's name, each record's,
    // the bus poles filed under a station record (their `cluster` is the record), and the interchanges'
    // member rows.
    private val recordsOfStation: Map<String, List<String>>,
    private val platformsOfRecord: Map<String, List<String>>,
    private val polesOfStop: Map<String, List<String>>,
    private val wholeAreasOfStop: Map<String, List<String>>,
    private val ownedStops: Map<String, List<String>>,
    private val poleName: Map<String, String>,
    private val recordName: Map<String, String>,
    private val polesOfRecord: Map<String, List<String>>,
    private val memberRows: () -> Map<String, List<StationIndex.MemberRow>>,
    /** The bundle's content revision both files carry. */
    val revision: String,
) {
    /** What an id resolves to at one level. */
    sealed interface Membership {
        /** The level's id ([Hubs]): a station, a stop or a hub. */
        data class Of(val id: String) : Membership

        /**
         * A TfL stop area the bundle split into several stops (its poles carry two names, or span
         * more than 250 m): its id names those poles, never one stop of them.
         */
        data class Split(val ids: Set<String>) : Membership

        /** An id the bundle doesn't hold: matched by its exact id, never grouped with anything. */
        data object Unbundled : Membership
    }

    /** One loaded model, or why there's none: callers handle each, never a half-loaded model. */
    sealed interface State {
        data object Loading : State

        /** The bundle couldn't be read or lacks its membership; callers match exact ids only. */
        data object Unavailable : State

        data class Ready(val hubs: Hubs) : State
    }

    /** A level of the model. Ids can repeat across levels: a junction is named after its anchor stop. */
    enum class Level { STATION, STOP, HUB }

    /** A hub's stations and the bus stops in it, by their level ids. */
    data class Members(val stations: Set<String>, val stops: Set<String>)

    /**
     * The station [id] belongs to: a station record's, a platform's (the record TfL lists it under,
     * else the station its id names: `9400ZZLUKSX3` at `940GZZLUKSX`), or a station's own id.
     */
    fun stationOf(id: String): Membership {
        stationOfRecord[id]?.let { return Membership.Of(it) }
        if (id in hubOfStation) return Membership.Of(id)
        platformStation(id)?.let { record -> stationOfRecord[record]?.let { return Membership.Of(it) } }
        // The prefix step again, over records the index leaves out but stops.tsv holds.
        namedRecord(id)?.let { record -> stationOfRecord[record]?.let { return Membership.Of(it) } }
        return Membership.Unbundled
    }

    // The station record a platform's id names, as NaPTAN names an access area by its station's code.
    private fun namedRecord(platform: String): String? = when {
        platform.startsWith("9100") -> "910G" + platform.removePrefix("9100")
        platform.startsWith("9400") -> "940G" + platform.removePrefix("9400")
        else -> null
    }

    /** The bus stop [id] belongs to: a pole's, a stop's own id, or a TfL stop area's (whole, or [Membership.Split]). */
    fun stopOf(id: String): Membership {
        stopOfPole[id]?.let { return Membership.Of(it) }
        if (id in hubOfStop) return Membership.Of(id)
        stopsOfArea[id]?.let { stops -> return if (stops.size == 1) Membership.Of(stops.single()) else Membership.Split(stops) }
        return Membership.Unbundled
    }

    /**
     * The hub [id] belongs to, whatever level it names: a record, platform or station's; a pole, area
     * or stop's; or a hub's own id. A split area's poles can lie in several hubs ([Membership.Split]).
     */
    fun hubOf(id: String): Membership {
        if (id in hubMembers) return Membership.Of(id)
        when (val station = stationOf(id)) {
            is Membership.Of -> return hubOfStation[station.id]?.let { Membership.Of(it) } ?: Membership.Unbundled
            else -> Unit
        }
        return when (val stop = stopOf(id)) {
            is Membership.Of -> hubOfStop[stop.id]?.let { Membership.Of(it) } ?: Membership.Unbundled
            is Membership.Split -> stop.ids.mapNotNullTo(LinkedHashSet()) { hubOfStop[it] }.let { hubs ->
                if (hubs.size == 1) Membership.Of(hubs.single()) else Membership.Split(hubs)
            }
            Membership.Unbundled -> Membership.Unbundled
        }
    }

    /**
     * Every TfL record of the station [id] belongs to (a record's, a platform's or the station's own
     * id), the lead record first: St Pancras's main, low-level and domestic records together. Empty
     * for an id the bundle doesn't hold as a station's, a hub's included.
     */
    fun recordsOf(id: String): List<String> = when (val station = stationOf(id)) {
        is Membership.Of -> recordsOfStation[station.id].orEmpty()
        else -> emptyList()
    }

    /** Whether [a] and [b] are one station; an id the bundle doesn't hold is only itself. */
    fun sameStation(a: String, b: String): Boolean = same(stationOf(a), stationOf(b), a, b)

    /** Whether [a] and [b] are one bus stop; an id the bundle doesn't hold is only itself. */
    fun sameStop(a: String, b: String): Boolean = same(stopOf(a), stopOf(b), a, b)

    /** Whether [a] and [b] are in one hub; an id the bundle doesn't hold is only itself. */
    fun sameHub(a: String, b: String): Boolean = same(hubOf(a), hubOf(b), a, b)

    private fun same(x: Membership, y: Membership, a: String, b: String): Boolean =
        if (x is Membership.Of && y is Membership.Of) x.id == y.id else a.equals(b, ignoreCase = true)

    /** A hub's stations and bus stops, or null for an id that isn't a hub's. */
    fun membersOf(hub: String): Members? = hubMembers[hub]

    /**
     * Where a pick at [level] is aimed from (*Each level's place*): a station at the middle of its
     * records, a stop of its poles, a hub of every record of its stations or, with none, every pole of
     * its stops. Never how near it is: a level's distance is its nearest record's or pole's. The level
     * is asked for, not inferred, since a junction hub and its anchor stop share an id. Null for an id
     * the model doesn't hold at that level.
     */
    @WorkerThread
    fun placeOf(id: String, level: Level): Coordinates? = when (level) {
        Level.STATION -> stationPoints[id]?.let(::middle)
        Level.STOP -> stopPoints[id]?.let(::middle)
        Level.HUB -> hubMembers[id]?.let { members ->
            val records = members.stations.flatMap { stationPoints[it].orEmpty() }
            middle(records.ifEmpty { members.stops.flatMap { stopPoints[it].orEmpty() } })
        }
    }

    private fun middle(points: List<Coordinates>): Coordinates? =
        if (points.isEmpty()) null else Coordinates(points.map { it.latitude }.average(), points.map { it.longitude }.average())

    /**
     * What a pick saved before phase 2, with no level, was a pick of (*Saved data*): read from the
     * entry's own fields alone, the saved [id] and [name], and where the store kept them the starred
     * pole ([stopId]) or the row's [modes].
     * - An interchange's id with one of its member rows' names is what that row stands for: the row's
     *   station, by its lead record, or the hub for a row folded from two stations' names (King's
     *   Cross takes in the Underground's). Any other name is the hub.
     * - A station record that bus poles are filed under (their stop area is the record) is the bus
     *   stop when the entry carries a bus pole (one filed there or since moved), a bus-only mode, or a name of those poles' and
     *   not the record's; else the station, with [Pick.extraStopId] the same-named stop's lowest-id
     *   pole when the entry kept only an id and a name whose names match.
     * - A stop area is the stop whose poles carry the saved name, the one with the lowest-id such pole
     *   where two do, else the stop of its lowest-id pole: a guess for an area split since, but the
     *   same on every build. A stop is picked by a pole (*A stop is picked by a pole*).
     * - An id the bundle doesn't hold keeps its id, at the level its shape or a bus-only mode names.
     */
    @WorkerThread
    fun picked(id: String, name: String, stopId: String? = null, modes: List<String> = emptyList()): Pick {
        hubMembers[id]?.takeIf { id.startsWith("HUB", ignoreCase = true) }?.let { return pickedInterchange(id, name) }
        polesOfRecord[id]?.let { poles -> return pickedRecord(id, name, poles, stopId, modes) }
        if (stationOf(id) is Membership.Of) return Pick(id, name, Pick.Level.STATION)
        return when (val stop = stopOf(id)) {
            is Membership.Of -> Pick(if (id in stopOfPole) id else anchorPole(stop.id) ?: id, name, Pick.Level.STOP)
            is Membership.Split -> Pick(poleNamed(stop.ids.flatMap { polesOfStop[it].orEmpty() }, name) ?: id, name, Pick.Level.STOP)
            Membership.Unbundled -> Pick(id, name, unbundledLevel(id, modes))
        }
    }

    // A bus pole's or area's id, or an entry saved as a bus stop, is a stop; an interchange's a hub.
    private fun unbundledLevel(id: String, modes: List<String>): Pick.Level = when {
        BUS_ID.matches(id) || modes == listOf("bus") -> Pick.Level.STOP
        id.startsWith("HUB", ignoreCase = true) -> Pick.Level.HUB
        else -> Pick.Level.STATION
    }

    private fun pickedInterchange(id: String, name: String): Pick {
        val saved = compared(StationIndex.memberName(name))
        val row = memberRows()[id]?.firstOrNull { compared(it.match.name) == saved } ?: return Pick(id, name, Pick.Level.HUB)
        val stations = row.records.mapNotNullTo(LinkedHashSet()) { (stationOf(it) as? Membership.Of)?.id }
        return if (stations.size == 1) Pick(row.records.first(), name, Pick.Level.STATION) else Pick(id, name, Pick.Level.HUB)
    }

    private fun pickedRecord(id: String, name: String, poles: List<String>, stopId: String?, modes: List<String>): Pick {
        // The starred pole is the stop, wherever a later bundle files it: the rider's own record wins.
        if (stopId != null && (stopId in poles || (stopId != id && BUS_ID.matches(stopId)))) return Pick(stopId, name, Pick.Level.STOP)
        val saved = compared(cleanStopName(name))
        val own = compared(cleanStopName(recordName[id].orEmpty()))
        val named = poles.filter { compared(cleanStopName(poleName[it].orEmpty())) == saved }
        if (modes == listOf("bus")) return Pick(named.firstOrNull() ?: poles.first(), name, Pick.Level.STOP)
        if (named.isNotEmpty() && saved != own) return Pick(named.first(), name, Pick.Level.STOP)
        val extra = named.firstOrNull()?.takeIf { stopId == null && modes.isEmpty() && saved == own }
        return Pick(id, name, Pick.Level.STATION, extraStopId = extra)
    }

    // The pole carrying [name] with the lowest id, else the lowest-id pole: [poles] are sorted.
    private fun poleNamed(poles: List<String>, name: String): String? {
        val saved = compared(cleanStopName(name))
        val sorted = poles.sorted()
        return sorted.firstOrNull { compared(cleanStopName(poleName[it].orEmpty())) == saved } ?: sorted.firstOrNull()
    }

    private fun anchorPole(stop: String): String? = polesOfStop[stop]?.firstOrNull()

    private fun compared(name: String): String = StationMatcher.normalize(name).lowercase()

    /**
     * Every TfL id [pick] can be named by, each to the station or stop it belongs to: the shape a trip's
     * arrival map takes (*Every existing rule*: `destinationIds`). A station's records, their platforms
     * and the bus stops it owns; a stop's poles and its whole stop areas (a split area names several
     * stops, so it maps to none); a hub's every station and stop. [Pick.extraStopId]'s stop joins a
     * station's. An id the bundle doesn't hold maps to itself, and a place has none. An id outside the
     * map is the caller's to place through [stationOf], as a platform added since the bundle was built.
     */
    @WorkerThread
    fun idsOf(pick: Pick): Map<String, String> {
        val ids = LinkedHashMap<String, String>()
        when (pick.level) {
            Pick.Level.STATION -> when (val station = stationOf(pick.id)) {
                is Membership.Of -> addStation(ids, station.id)
                else -> ids[pick.id] = pick.id
            }
            Pick.Level.STOP -> when (val stop = stopOf(pick.id)) {
                is Membership.Of -> addStop(ids, stop.id)
                is Membership.Split -> stop.ids.forEach { addStop(ids, it) }
                Membership.Unbundled -> ids[pick.id] = pick.id
            }
            Pick.Level.HUB -> when (val hub = hubOf(pick.id)) {
                is Membership.Of -> hubMembers[hub.id]?.let { members ->
                    members.stations.forEach { addStation(ids, it) }
                    members.stops.forEach { addStop(ids, it) }
                }
                else -> ids[pick.id] = pick.id
            }
            Pick.Level.PLACE -> return emptyMap()
        }
        // Kept as the pick's own id is: a pole a later bundle no longer holds is still the stop saved.
        pick.extraStopId?.let { extra ->
            when (val stop = stopOf(extra)) {
                is Membership.Of -> addStop(ids, stop.id)
                is Membership.Split -> stop.ids.forEach { addStop(ids, it) }
                Membership.Unbundled -> ids[extra] = extra
            }
        }
        return ids
    }

    private fun addStation(ids: MutableMap<String, String>, station: String) {
        recordsOfStation[station].orEmpty().forEach { record ->
            ids[record] = station
            platformsOfRecord[record].orEmpty().forEach { ids[it] = station }
        }
        ownedStops[station].orEmpty().forEach { addStop(ids, it) }
    }

    private fun addStop(ids: MutableMap<String, String>, stop: String) {
        ids[stop] = stop
        polesOfStop[stop].orEmpty().forEach { ids[it] = stop }
        wholeAreasOfStop[stop].orEmpty().forEach { ids[it] = stop }
    }

    companion object {
        // A NaPTAN bus pole's or area's id, London's (`490…`, `490G…`) or another region's (`037…`):
        // stations' are `9…` (`910G…`, `940G…`).
        private val BUS_ID = Regex("^[0-8]\\d{2}.+")

        /**
         * The model from the bundle's station [index] and [stops], or null where it can't be trusted
         * whole, with [unavailable] told why: either file built before the model, either file with a
         * record it couldn't read, a record or pole without its membership, or the two files from
         * different builds (their revisions differ).
         * Walks every stop: on a worker only.
         */
        @WorkerThread
        fun of(index: StationIndex, stops: BundledStops, unavailable: (String) -> Unit = {}): Hubs? {
            if (index.revision.isBlank() || stops.revision.isBlank()) {
                unavailable("bundle built before the station model")
                return null
            }
            // Either reader leaves out a record it can't read; a model over what's left would call
            // the missing ids unbundled, so neither file may have lost one.
            if (stops.skippedLines > 0 || index.skippedEntries > 0) {
                unavailable("bundle has unreadable records")
                return null
            }
            if (index.revision != stops.revision) {
                unavailable("station index and stops are from different builds")
                return null
            }
            val stationOfRecord = HashMap<String, String>()
            val hubOfStation = HashMap<String, String>()
            val stationPoints = HashMap<String, MutableList<Coordinates>>()
            val platformsOfRecord = HashMap<String, List<String>>()
            val recordName = HashMap<String, String>()
            for (record in index.stations) {
                if (record.id.startsWith("HUB", ignoreCase = true)) continue
                if (record.station.isBlank() || record.modelHub.isBlank()) {
                    unavailable("a station record has no membership")
                    return null
                }
                addStation(record.id, record.station, record.modelHub, record.latitude, record.longitude,
                    stationOfRecord, hubOfStation, stationPoints)
                if (record.platforms.isNotEmpty()) platformsOfRecord[record.id] = record.platforms
                recordName[record.id] = record.name
            }
            val stopOfPole = HashMap<String, String>()
            val hubOfStop = HashMap<String, String>()
            val stopPoints = HashMap<String, MutableList<Coordinates>>()
            val areaStops = HashMap<String, MutableSet<String>>()
            val polesOfStop = HashMap<String, MutableList<String>>()
            val ownedStops = HashMap<String, MutableList<String>>()
            val poleName = HashMap<String, String>()
            val clusterPoles = HashMap<String, MutableList<String>>()
            for ((stop, member) in stops.memberships()) {
                when {
                    member.type == 'B' -> {
                        if (member.level.isBlank() || member.hub.isBlank()) {
                            unavailable("a bus stop has no membership")
                            return null
                        }
                        stopOfPole[stop.id] = member.level
                        if (member.level !in hubOfStop && member.owner.isNotBlank()) {
                            ownedStops.getOrPut(member.owner) { ArrayList() } += member.level
                        }
                        hubOfStop[member.level] = member.hub
                        val at = Coordinates(stop.latitude, stop.longitude)
                        stopPoints.getOrPut(member.level) { ArrayList() } += at
                        polesOfStop.getOrPut(member.level) { ArrayList() } += stop.id
                        poleName[stop.id] = stop.name
                        // A pole's stop area, unless the area is a station record (a forecourt's poles).
                        if (member.cluster.isNotBlank() && member.cluster != stop.id) {
                            areaStops.getOrPut(member.cluster) { LinkedHashSet() } += member.level
                            clusterPoles.getOrPut(member.cluster) { ArrayList() } += stop.id
                        }
                    }
                    // A station record the index leaves out: its station and hub are in stops.tsv.
                    member.level.isNotBlank() && member.hub.isNotBlank() -> {
                        addStation(
                            stop.id, member.level, member.hub, stop.latitude, stop.longitude,
                            stationOfRecord, hubOfStation, stationPoints,
                        )
                        recordName.putIfAbsent(stop.id, stop.name)
                    }
                }
            }
            areaStops.keys.removeAll { it in stationOfRecord }
            val wholeAreasOfStop = HashMap<String, MutableList<String>>()
            areaStops.forEach { (area, inStops) -> if (inStops.size == 1) wholeAreasOfStop.getOrPut(inStops.single()) { ArrayList() } += area }
            val recordsOfStation = HashMap<String, MutableList<String>>()
            stationOfRecord.forEach { (record, station) -> recordsOfStation.getOrPut(station) { ArrayList() } += record }
            // Index order first, as search's rows list them, so a station's lead record is its first.
            val order = index.stations.withIndex().associate { (i, it) -> it.id to i }
            recordsOfStation.values.forEach { records -> records.sortWith(compareBy({ order[it] ?: Int.MAX_VALUE }, { it })) }
            val hubMembers = HashMap<String, Pair<MutableSet<String>, MutableSet<String>>>()
            hubOfStation.forEach { (station, hub) -> hubMembers.getOrPut(hub) { LinkedHashSet<String>() to LinkedHashSet() }.first += station }
            hubOfStop.forEach { (stop, hub) -> hubMembers.getOrPut(hub) { LinkedHashSet<String>() to LinkedHashSet() }.second += stop }
            return Hubs(
                stationOfRecord = stationOfRecord,
                hubOfStation = hubOfStation,
                stationPoints = stationPoints,
                stopOfPole = stopOfPole,
                hubOfStop = hubOfStop,
                stopPoints = stopPoints,
                stopsOfArea = areaStops,
                hubMembers = hubMembers.mapValues { (_, m) -> Members(m.first, m.second) },
                platformStation = index::stationOf,
                recordsOfStation = recordsOfStation,
                platformsOfRecord = platformsOfRecord,
                polesOfStop = polesOfStop.mapValues { (_, poles) -> poles.sorted() },
                wholeAreasOfStop = wholeAreasOfStop,
                ownedStops = ownedStops,
                poleName = poleName,
                recordName = recordName,
                // Bus poles filed under a station record: the pre-phase-2 picks [picked] reads with care.
                polesOfRecord = clusterPoles.filterKeys { it in stationOfRecord }.mapValues { (_, poles) -> poles.sorted() },
                memberRows = { index.memberRows },
                revision = index.revision,
            )
        }

        private fun addStation(
            id: String,
            station: String,
            hub: String,
            latitude: Double?,
            longitude: Double?,
            stationOfRecord: MutableMap<String, String>,
            hubOfStation: MutableMap<String, String>,
            stationPoints: MutableMap<String, MutableList<Coordinates>>,
        ) {
            stationOfRecord[id] = station
            hubOfStation[station] = hub
            if (latitude != null && longitude != null) {
                stationPoints.getOrPut(station) { ArrayList() } += Coordinates(latitude, longitude)
            }
        }
    }
}
