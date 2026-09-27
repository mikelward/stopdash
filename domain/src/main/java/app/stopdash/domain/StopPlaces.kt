package app.stopdash.domain

/**
 * One place a rider thinks of as one stop: a TfL interchange, or the stops TfL keeps apart that
 * stand for the same place — a station and the bus stops at its door, or a road's two poles of the
 * same name that TfL files under different stop areas (SPEC *Places*). [id] is stable for the same
 * members: the TfL hub where one is known, else the stop area, else the smallest member id, so a
 * place keys the same across refreshes.
 */
data class StopPlace(val id: String, val name: String)

/**
 * A stop as [StopPlaces] groups it: its id and name as TfL gives them, its position where known,
 * and its TfL interchange ([hub], `topMostParentId`) and stop area ([area], a bus stop pair) where
 * known. Static network data — no user data.
 */
data class PlaceStop(
    val id: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val hub: String = "",
    val area: String = "",
)

/**
 * Groups stops into [StopPlace]s. TfL's own interchanges (hubs) and stop pairs miss places a rider
 * reads as one — two routes' poles at the same station, under different stop areas and no hub — so
 * a place is **synthesized** too: stops of the same name ([sameName]) within [RADIUS_METERS] of each
 * other are one place. Worked out from the stops at hand (a plan's legs, a line's route), so no stop
 * list has to be preprocessed: a place only ever needs comparing among stops already fetched.
 */
object StopPlaces {
    /** How near two same-named stops must be to be one place; the search's fold radius. */
    const val RADIUS_METERS = StationIndex.FOLD_RADIUS_METERS

    /**
     * Each of [stops]' ids to its place. Joined, transitively: stops of one TfL hub, stops of one
     * stop area, and same-named stops ([sameName]) both positioned within [RADIUS_METERS]. A stop
     * given more than once (from two sources) counts as one, its known fields merged.
     */
    fun group(stops: List<PlaceStop>): Map<String, StopPlace> {
        val byId = LinkedHashMap<String, PlaceStop>()
        for (stop in stops) {
            if (stop.id.isBlank()) continue
            val held = byId[stop.id]
            byId[stop.id] = if (held == null) stop else held.copy(
                name = held.name.ifBlank { stop.name },
                latitude = held.latitude ?: stop.latitude,
                longitude = held.longitude ?: stop.longitude,
                hub = held.hub.ifBlank { stop.hub },
                area = held.area.ifBlank { stop.area },
            )
        }
        val all = byId.values.toList()
        val parent = IntArray(all.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val next = parent[j]; parent[j] = r; j = next }
            return r
        }
        fun join(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        fun joinBy(key: (PlaceStop) -> String) {
            val first = HashMap<String, Int>()
            all.forEachIndexed { i, stop -> key(stop).takeIf { it.isNotBlank() }?.let { k -> first[k]?.let { join(it, i) } ?: first.put(k, i) } }
        }
        joinBy { it.hub }
        joinBy { it.area }
        // Same-named pairs only, found through an index rather than by comparing every pair: a
        // trip's stops and its lines' routes run to hundreds, and this is worked out as a screen
        // composes. [sameName] holds when one's whole name is the other's whole name or one of its
        // parts, so each stop is looked up by its whole name among wholes and among parts.
        val names = all.map { parts(it.name) }
        val byWhole = HashMap<String, MutableList<Int>>()
        val byPart = HashMap<String, MutableList<Int>>()
        all.indices.forEach { i ->
            val split = names[i]
            if (split.isEmpty() || all[i].latitude == null || all[i].longitude == null) return@forEach
            byWhole.getOrPut(split.joinToString(" ")) { ArrayList() } += i
            split.forEach { byPart.getOrPut(it) { ArrayList() } += i }
        }
        for (i in all.indices) {
            val a = all[i]
            val aLat = a.latitude ?: continue
            val aLon = a.longitude ?: continue
            val split = names[i]
            if (split.isEmpty()) continue
            val whole = split.joinToString(" ")
            val candidates = byWhole[whole].orEmpty() + byPart[whole].orEmpty() + split.flatMap { byWhole[it].orEmpty() }
            for (j in candidates) {
                if (j <= i) continue
                val bLat = all[j].latitude ?: continue
                val bLon = all[j].longitude ?: continue
                if (NearestStops.distanceMeters(aLat, aLon, bLat, bLon) <= RADIUS_METERS) join(i, j)
            }
        }
        val members = all.indices.groupBy(::root)
        val places = members.mapValues { (_, indices) ->
            val group = indices.map { all[it] }
            val id = group.map { it.hub }.filter { it.isNotBlank() }.minOrNull()
                ?: group.map { it.area }.filter { it.isNotBlank() }.minOrNull()
                ?: group.minOf { it.id }
            // The plainest name stands for the place: "Archway" over "Archway / Holloway Road".
            val name = group.map { cleanStopName(it.name) }.filter { it.isNotBlank() }.minByOrNull { it.length }.orEmpty()
            StopPlace(id, name)
        }
        return all.indices.associate { all[it].id to places.getValue(root(it)) }
    }

    /**
     * Whether two stop names name one place: equal once cleaned ([cleanStopName]: "Archway Station"
     * and "Archway"), or one is a whole part of the other's "/"-joined name ("Archway" and "Archway /
     * Holloway Road"). Case, accents and punctuation don't count.
     */
    fun sameName(a: String, b: String): Boolean {
        val x = parts(a)
        val y = parts(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val wholeX = x.joinToString(" ")
        val wholeY = y.joinToString(" ")
        return wholeX == wholeY || wholeX in y || wholeY in x
    }

    private fun parts(name: String): List<String> =
        cleanStopName(name).split('/').map { StationMatcher.normalize(cleanStopName(it)).lowercase() }.filter { it.isNotEmpty() }
}
