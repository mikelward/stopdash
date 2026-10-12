package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * London's bus stops, from the bundled stops ([BundledStops.busStops], `assets/stations/stops.tsv`),
 * so the stop search finds a bus stop on the device as the rider types, with no TfL request (SPEC
 * *Finding stops → Find a station*).
 *
 * About 21,000 stops under about 10,000 names: the names are split into their matched forms
 * ([StationMatcher.namesOf]) once, as the file is read, so a search only compares strings. A search
 * answers each stop as TfL's own stop search would, a [StationMatch] with TfL's id, its cleaned
 * name, the bus mode and its position, so the stations' ranking ([StationIndex.rank]) merges and
 * folds them exactly as it did TfL's answers: the same-named stops either side of a road become one
 * row.
 */
class BusStopIndex private constructor(
    private val ids: Array<String>,
    private val latitudes: DoubleArray,
    private val longitudes: DoubleArray,
    // Each distinct name, its matched forms, and the stops (by position in [ids]) that carry it.
    private val names: Array<String>,
    private val forms: Array<List<String>>,
    private val stopsByName: Array<IntArray>,
    // Each stop area (a stop's `clusterId`, "490G…") at the middle of its stops: a starred bus stop is
    // saved under its area, not its pole.
    private val areaPositions: Map<String, Coordinates> = emptyMap(),
) {
    val size: Int get() = ids.size

    /** Where the stop or stop area with [id] is, or null when it's neither of these. */
    @WorkerThread
    fun positionOf(id: String): Coordinates? = byId[id]?.let { Coordinates(latitudes[it], longitudes[it]) } ?: areaPositions[id]

    private val byId: Map<String, Int> by lazy { ids.withIndex().associate { it.value to it.index } }

    /**
     * The stops [query] finds, best match first, at most [limit]: by tier ([StationMatchTier]), then
     * the exact name, a name the query starts rather than one of its parts, then nearest [near] (where the trip starts), then the name and TfL id order, as
     * the stations rank (maintainer, 2026-10-12). [checkpoint] runs
     * every [CHECKPOINT_EVERY] names, so a caller can stop a scan nobody will read: it throws to stop.
     */
    @WorkerThread
    fun search(query: String, limit: Int = DEFAULT_LIMIT, checkpoint: () -> Unit = {}, near: Coordinates? = null): List<StationMatch> {
        val q = StationMatcher.queryForm(query)
        if (q.length < MIN_QUERY_LENGTH || limit <= 0) return emptyList()
        // Each matching stop, with what orders it: tier, whether its name is exactly the query, its
        // distance from [near] (none without one), its name's place in the (alphabetical) names.
        class Hit(val tier: Int, val inexact: Boolean, val partOnly: Boolean, val distance: Double, val name: Int, val stop: Int)
        val hits = ArrayList<Hit>()
        for (i in names.indices) {
            if (i % CHECKPOINT_EVERY == 0) checkpoint()
            val tier = StationMatcher.nameTierOf(q, forms[i]) ?: continue
            val inexact = !StationMatcher.isExact(query, names[i])
            val partOnly = !StationMatcher.startsName(query, names[i])
            for (stop in stopsByName[i]) {
                val distance = if (near == null) 0.0 else NearestStops.distanceMeters(near.latitude, near.longitude, latitudes[stop], longitudes[stop])
                hits += Hit(tier.ordinal, inexact, partOnly, distance, i, stop)
            }
        }
        // A stable sort, so a name's stops keep TfL id order.
        hits.sortWith(compareBy<Hit>({ it.tier }, { it.inexact }, { it.partOnly }, { it.distance }, { it.name }))
        return hits.take(limit).map { StationMatch(ids[it.stop], names[it.name], BUS, latitudes[it.stop], longitudes[it.stop]) }
    }

    companion object {
        /** Enough to fill the search's list after nearby twins fold into one row. */
        const val DEFAULT_LIMIT = 40
        /** The shortest query searched, as the stop search's own. */
        const val MIN_QUERY_LENGTH = 2
        // A scan's names between checks for a superseded search.
        const val CHECKPOINT_EVERY = 2048
        private val BUS = listOf("bus")

        val EMPTY = BusStopIndex(emptyArray(), DoubleArray(0), DoubleArray(0), emptyArray(), emptyArray(), emptyArray())

        /**
         * The index of [stops] (bus stops, as [BundledStops.busStops] lists them): grouped by name,
         * the names in order, each name's stops in TfL id order, and each name split into its matched
         * forms once, here.
         */
        @WorkerThread
        fun of(stops: List<StopLocation>): BusStopIndex {
            val byName = sortedMapOf<String, MutableList<Int>>()
            stops.forEachIndexed { i, stop -> if (stop.name.isNotBlank()) byName.getOrPut(stop.name) { mutableListOf() } += i }
            val names = byName.keys.toTypedArray()
            return BusStopIndex(
                Array(stops.size) { stops[it].id },
                DoubleArray(stops.size) { stops[it].latitude },
                DoubleArray(stops.size) { stops[it].longitude },
                names,
                Array(names.size) { StationMatcher.namesOf(names[it]) },
                byName.values.map { group -> group.sortedBy { stops[it].id }.toIntArray() }.toTypedArray(),
                stops.filter { it.clusterId.isNotBlank() }.groupBy { it.clusterId }.mapValues { (_, members) ->
                    Coordinates(members.map { it.latitude }.average(), members.map { it.longitude }.average())
                },
            )
        }
    }
}
