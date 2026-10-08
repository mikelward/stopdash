package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Where a stop's details lead (SPEC *Finding a line*): the [lines] through it, each opening that line's
 * page, the other stations of its interchange ([sameHub]: the rail station beside a tube one, say), and
 * the stations nearest it outside that interchange ([nearby]), nearest first. All from the bundled
 * station index: public TfL facts, no request.
 */
class StopLinks(
    val lines: List<LineRef>,
    val sameHub: List<NearStation>,
    val nearby: List<NearStation>,
    // The other ids TfL lists this same station under ([sameStation]): its board asks for them too.
    val ownIds: List<String> = emptyList(),
    // What From and To open for a station under several ids in an interchange: that interchange, so none of
    // its ids' stops is left out, as a search for it opens (Codex on #664). Null to open the stop itself.
    val openId: String? = null,
    // Where the station is, from the index; null where it isn't placed.
    val position: Coordinates? = null,
    // The lines the index lists under each of the station's own ids (this one and [ownIds]), so what is
    // read under one id is read only for the lines that id serves (Codex on #678).
    val linesById: Map<String, Set<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = lines.isEmpty() && sameHub.isEmpty() && nearby.isEmpty()

    companion object {
        val NONE = StopLinks(emptyList(), emptyList(), emptyList())
    }
}

/**
 * A station a stop's details link to: its id, its cleaned [name], [meters] from the stop where both are
 * placed, its [lineIds], and where it is ([position], for its own distance from the rider once opened).
 */
data class NearStation(
    val id: String,
    val name: String,
    val meters: Double?,
    val lineIds: Set<String> = emptySet(),
    val position: Coordinates? = null,
    // Its modes ("national-rail"), to tell it from a member of the same name (Balham's tube and rail).
    val modes: List<String> = emptyList(),
    // The interchange it's in, blank for none: what From opens for a station under several ids, as a search
    // for it does, so none of its ids' stops is left out.
    val hubId: String = "",
    // What its details look up under From and To, by its own modes ([stopCueOf]), worked out here, on
    // the worker, so the details never walk its modes (Codex on #678).
    val cue: StopCue = StopCue.NONE,
)

/**
 * Whether [a] and [b] are one station TfL lists under two ids (St Pancras's Southeastern platforms apart from
 * its Thameslink and Midland ones; Weybridge's two records): the same name and modes, and either the same
 * interchange or, in none, within [SAME_STATION_METERS] of each other (Codex on #664). Bethnal Green's tube
 * and Overground share a name but not a mode, so stay two.
 */
private fun sameStation(a: IndexedStation, b: IndexedStation): Boolean {
    if (a.modes != b.modes || cleanStopName(a.name) != cleanStopName(b.name)) return false
    if (a.hubId.isNotBlank() || b.hubId.isNotBlank()) return a.hubId == b.hubId
    val aLat = a.latitude ?: return false
    val aLon = a.longitude ?: return false
    val bLat = b.latitude ?: return false
    val bLon = b.longitude ?: return false
    return NearestStops.distanceMeters(aLat, aLon, bLat, bLon) <= SAME_STATION_METERS
}

/**
 * [stations] with each station listed under several ids ([sameStation] by name and mode, the list already
 * near one place) as one chip, at the first's place, with every id's lines: opened, its details find the
 * other ids as [StopLinks.ownIds].
 */
private fun merged(stations: List<NearStation>): List<NearStation> =
    stations.groupBy { it.name to it.modes }.values.map { same ->
        if (same.size == 1) same.first() else same.first().copy(lineIds = same.flatMapTo(LinkedHashSet()) { it.lineIds })
    }

/**
 * The other ids TfL lists the station [id] under ([sameStation]: Weybridge's two records), for a page that
 * opens it to ask for them too. Empty for an interchange, a stop the index doesn't hold, or a station
 * under one id. Walks every station: on a worker only.
 */
@WorkerThread
fun StationIndex.sameStationIds(id: String): List<String> {
    val ownId = station(id)?.id ?: stationOf(id) ?: return emptyList()
    if (ownId.startsWith("HUB", ignoreCase = true)) return emptyList()
    val own = stations.firstOrNull { it.id == ownId } ?: return emptyList()
    return stations.filter { it.id != ownId && it.id != id && sameStation(own, it) }.map { it.id }
}

/**
 * [StopLinks] for the stop [id]: a listed station, a platform listed under one ([StationIndex.stationOf]),
 * or an interchange (its members' lines, its members as [StopLinks.sameHub]). Nearby takes at most
 * [nearbyLimit] stations within [nearbyMeters], leaving out the stop's own interchange. [StopLinks.NONE] for a stop the index doesn't hold (a bus stop). Walks every station:
 * on a worker only.
 */
@WorkerThread
fun StationIndex.linksOf(id: String, nearbyMeters: Double = NEARBY_LINK_METERS, nearbyLimit: Int = NEARBY_LINK_LIMIT): StopLinks {
    val ownId = station(id)?.id ?: stationOf(id) ?: return StopLinks.NONE
    val own = stations.first { it.id == ownId }
    val isHub = ownId.startsWith("HUB", ignoreCase = true)
    val hub = if (isHub) ownId else own.hubId
    val members = if (hub.isBlank()) emptyList() else stations.filter { it.hubId == hub }
    val here = placeOf(ownId)
    fun metersTo(station: IndexedStation): Double? {
        val lat = station.latitude ?: return null
        val lon = station.longitude ?: return null
        return here?.let { NearestStops.distanceMeters(it.latitude, it.longitude, lat, lon) }
    }
    fun near(station: IndexedStation) = NearStation(
        station.id,
        cleanStopName(station.name),
        metersTo(station),
        station.lines.values.flatten().filterTo(LinkedHashSet()) { it.isNotBlank() },
        station.latitude?.let { lat -> station.longitude?.let { lon -> Coordinates(lat, lon) } },
        station.modes,
        hubId = station.hubId,
        cue = stopCueOf(station.modes),
    )

    // The other ids TfL lists this same station under, counted as this stop: its lines, and its board's ids.
    val ownGroup = if (isHub) emptyList() else stations.filter { it.id != ownId && sameStation(own, it) }
    val lines = LinkedHashMap<String, LineRef>()
    for (station in if (isHub) members else listOf(own) + ownGroup) {
        for ((mode, ids) in station.lines) {
            ids.forEach { line -> if (line.isNotBlank()) lines.getOrPut(line) { LineRef(line, riderLineName(lineNames[line] ?: line, mode), mode) } }
        }
    }
    val ownIds = ownGroup.mapTo(HashSet()) { it.id } + ownId
    // A member named as this one is still its own station (Balham rail beside Balham tube): kept, its mode
    // telling them apart on the page; one under several ids is one chip asking for all of them (Codex on #664).
    val sameHub = merged(members.filter { it.id !in ownIds }.map(::near))
    val inHub = members.mapTo(HashSet()) { it.id } + ownIds
    val nearby = if (here == null) {
        emptyList()
    } else {
        stations.asSequence()
            .filter { it.id !in inHub && !it.id.startsWith("HUB", ignoreCase = true) }
            .mapNotNull { station -> metersTo(station)?.takeIf { it <= nearbyMeters }?.let { station to it } }
            .sortedBy { it.second }
            .map { near(it.first) }
            // Left out by id, never by name: Bethnal Green's Overground and tube stations share one but are
            // two stations 460 m apart (Codex on #664); the page tells a same-named one by its mode.
            .toList()
            .let(::merged)
            .take(nearbyLimit)
    }
    val openId = own.hubId.takeIf { ownGroup.isNotEmpty() && it.isNotBlank() }
    val linesById = if (isHub) {
        emptyMap()
    } else {
        (listOf(own) + ownGroup).associate { station -> station.id to station.lines.values.flatten().filterTo(HashSet()) { it.isNotBlank() } }
    }
    return StopLinks(lines.values.toList(), sameHub, nearby, ownGroup.map { it.id }, openId, here, linesById)
}

/** How far a station can be and still count as near a stop's details: a short walk. */
const val NEARBY_LINK_METERS = 800.0

/** At most this many nearby stations listed on a stop's details. */
const val NEARBY_LINK_LIMIT = 5

/** How close two records of one name and mode, in no interchange, are to count as one station. */
const val SAME_STATION_METERS = 250.0
