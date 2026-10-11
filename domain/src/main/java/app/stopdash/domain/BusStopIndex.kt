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
) {
    val size: Int get() = ids.size

    /**
     * The stops [query] finds, best match first, at most [limit]: by tier ([StationMatchTier]), then
     * the shorter name, then the name; a name's stops together, in TfL id order. [checkpoint] runs
     * every [CHECKPOINT_EVERY] names, so a caller can stop a scan nobody will read: it throws to stop.
     */
    @WorkerThread
    fun search(query: String, limit: Int = DEFAULT_LIMIT, checkpoint: () -> Unit = {}): List<StationMatch> {
        val q = StationMatcher.queryForm(query)
        if (q.length < MIN_QUERY_LENGTH || limit <= 0) return emptyList()
        val hits = ArrayList<Long>()
        for (i in names.indices) {
            if (i % CHECKPOINT_EVERY == 0) checkpoint()
            val tier = StationMatcher.nameTierOf(q, forms[i]) ?: continue
            // Packed so a plain sort orders them: tier, then name length, then the name's place in
            // the (alphabetical) name list.
            hits.add((tier.ordinal.toLong() shl 48) or (minOf(names[i].length, 0xFFFF).toLong() shl 32) or i.toLong())
        }
        hits.sort()
        val found = ArrayList<StationMatch>()
        for (hit in hits) {
            val name = names[(hit and 0xFFFFFFFFL).toInt()]
            for (stop in stopsByName[(hit and 0xFFFFFFFFL).toInt()]) {
                if (found.size >= limit) return found
                found += StationMatch(ids[stop], name, BUS, latitudes[stop], longitudes[stop])
            }
        }
        return found
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
            )
        }
    }
}
