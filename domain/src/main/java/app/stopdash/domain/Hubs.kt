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

    companion object {
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
            for (record in index.stations) {
                if (record.id.startsWith("HUB", ignoreCase = true)) continue
                if (record.station.isBlank() || record.modelHub.isBlank()) {
                    unavailable("a station record has no membership")
                    return null
                }
                addStation(record.id, record.station, record.modelHub, record.latitude, record.longitude,
                    stationOfRecord, hubOfStation, stationPoints)
            }
            val stopOfPole = HashMap<String, String>()
            val hubOfStop = HashMap<String, String>()
            val stopPoints = HashMap<String, MutableList<Coordinates>>()
            val areaStops = HashMap<String, MutableSet<String>>()
            for ((stop, member) in stops.memberships()) {
                when {
                    member.type == 'B' -> {
                        if (member.level.isBlank() || member.hub.isBlank()) {
                            unavailable("a bus stop has no membership")
                            return null
                        }
                        stopOfPole[stop.id] = member.level
                        hubOfStop[member.level] = member.hub
                        val at = Coordinates(stop.latitude, stop.longitude)
                        stopPoints.getOrPut(member.level) { ArrayList() } += at
                        // A pole's stop area, unless the area is a station record (a forecourt's poles).
                        if (member.cluster.isNotBlank() && member.cluster != stop.id) {
                            areaStops.getOrPut(member.cluster) { LinkedHashSet() } += member.level
                        }
                    }
                    // A station record the index leaves out: its station and hub are in stops.tsv.
                    member.level.isNotBlank() && member.hub.isNotBlank() -> addStation(
                        stop.id, member.level, member.hub, stop.latitude, stop.longitude,
                        stationOfRecord, hubOfStation, stationPoints,
                    )
                }
            }
            areaStops.keys.removeAll { it in stationOfRecord }
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
