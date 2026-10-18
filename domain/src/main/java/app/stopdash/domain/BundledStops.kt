package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.io.BufferedReader
import kotlin.math.cos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The stops TfL's nearby search answers with, bundled (SPEC *Finding stops → Near me now*): every
 * bus and tram stop, station and pier in and around London, with what the near-me list reads from
 * that search — name, position, cluster, interchange, pole letter, compass, "towards" and lines.
 * Built by `scripts/build_stops.py`, so the list is found the moment there's a fix rather
 * than after a TfL search that takes 10–25 s in central London when TfL hasn't cached it.
 *
 * Covers [bounds] only: a search reaching past them ([covers] false) is TfL's to answer.
 */
class BundledStops private constructor(
    private val stops: List<StopLocation>,
    private val types: ByteArray,
    /** South, west, north, east, in degrees. */
    private val bounds: DoubleArray,
    /** Records that couldn't be read and were left out, so the caller can say so. */
    val skippedLines: Int,
) {
    val size: Int get() = stops.size

    /**
     * Whether a search of [radiusMeters] around ([latitude], [longitude]) lies wholly inside the
     * bundle's area, so no stop it should find can be missing for being outside it.
     */
    fun covers(latitude: Double, longitude: Double, radiusMeters: Int): Boolean {
        if (stops.isEmpty()) return false
        val dLat = radiusMeters / METERS_PER_DEGREE
        val dLon = radiusMeters / (METERS_PER_DEGREE * cos(Math.toRadians(latitude)))
        return latitude - dLat >= bounds[0] && longitude - dLon >= bounds[1] &&
            latitude + dLat <= bounds[2] && longitude + dLon <= bounds[3]
    }

    /** Whether every one of [stopTypes] is a kind the bundle holds, so none is missing for its type. */
    fun holds(stopTypes: List<String>): Boolean = stopTypes.all { it in TYPE_CODES }

    /** The stops of [stopTypes] within [radiusMeters] of ([latitude], [longitude]), nearest first. */
    @WorkerThread
    fun nearby(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>): List<StopLocation> {
        val wanted = BooleanArray(TYPE_NAMES.size) { TYPE_NAMES[it] in stopTypes }
        // A box around the circle first, so the distance is worked out for the few stops in it.
        val dLat = radiusMeters / METERS_PER_DEGREE
        val dLon = radiusMeters / (METERS_PER_DEGREE * cos(Math.toRadians(latitude)))
        val found = ArrayList<Pair<Double, StopLocation>>()
        for (i in stops.indices) {
            if (!wanted[types[i].toInt()]) continue
            val stop = stops[i]
            if (stop.latitude < latitude - dLat || stop.latitude > latitude + dLat) continue
            if (stop.longitude < longitude - dLon || stop.longitude > longitude + dLon) continue
            val meters = NearestStops.distanceMeters(latitude, longitude, stop.latitude, stop.longitude)
            if (meters <= radiusMeters) found += meters to stop
        }
        found.sortBy { it.first }
        return found.map { it.second }
    }

    /**
     * Where the stop [id] is, or for a stop area ("490G…", a road's two poles) the middle of its
     * stops: a trip's destination stop placed on the device ([TowardStops]). Null for one the bundle
     * doesn't hold. The map is built at the first call, over every stop, so off the main thread.
     */
    @WorkerThread
    fun positionOf(id: String): Coordinates? = positions[id]

    private val positions: Map<String, Coordinates> by lazy {
        val found = HashMap<String, Coordinates>(stops.size * 2)
        val areas = HashMap<String, MutableList<StopLocation>>()
        for (stop in stops) {
            if (stop.latitude == 0.0 && stop.longitude == 0.0) continue
            found[stop.id] = Coordinates(stop.latitude, stop.longitude)
            // A cluster that is only the stop's name (no area) places nothing.
            if (stop.clusterId.isNotBlank() && stop.clusterId != stop.name && ' ' !in stop.clusterId) {
                areas.getOrPut(stop.clusterId) { ArrayList() } += stop
            }
        }
        areas.forEach { (area, members) ->
            found.putIfAbsent(area, Coordinates(members.map { it.latitude }.average(), members.map { it.longitude }.average()))
        }
        found
    }

    /**
     * The bus stops among them: the single stops a bus route serves, for the stop search
     * ([BusStopIndex.of]). Only those placed inside [bounds], so a stop the file mis-places (a
     * zeroed position, say) can't be saved as a favorite far outside London; it isn't listed at all.
     */
    @WorkerThread
    fun busStops(): List<StopLocation> = stops.filterIndexed { i, stop ->
        types[i].toInt() == BUS_TYPE && stop.lines.any { it.mode == "bus" } &&
            stop.latitude in bounds[0]..bounds[2] && stop.longitude in bounds[1]..bounds[3]
    }

    companion object {
        const val FORMAT_VERSION = 2

        // The versions read: v2 adds four membership columns (the station model, not read yet) and a
        // content revision to v1, so a v1 file, as the APK carried before, reads the same as ever.
        private val READ_VERSIONS = 1..FORMAT_VERSION

        private const val METERS_PER_DEGREE = 111_320.0
        private const val STOP_FIELDS = 16

        // The file's type codes and the NaPTAN stop types they stand for (StopFinder's defaults).
        private val TYPE_NAMES = listOf("NaptanMetroStation", "NaptanRailStation", "NaptanPublicBusCoachTram", "NaptanFerryPort")
        private val TYPE_CODES = mapOf("NaptanMetroStation" to "M", "NaptanRailStation" to "R", "NaptanPublicBusCoachTram" to "B", "NaptanFerryPort" to "F")
        private val CODE_INDEX = mapOf("M" to 0, "R" to 1, "B" to 2, "F" to 3)
        private const val BUS_TYPE = 2

        val EMPTY = BundledStops(emptyList(), ByteArray(0), DoubleArray(4), 0)

        /**
         * Reads the file (see `scripts/build_stops.py`). Throws [IllegalArgumentException] on
         * a header for another format version or one without its bounds, since its records can't be
         * trusted to mean the same; a single unreadable record is left out and counted in
         * [skippedLines]. Each stop is read as the app reads TfL's own answer: its name cleaned, each
         * line named as a rider knows it, its cluster its station's id or else its name.
         */
        @WorkerThread
        fun read(reader: BufferedReader): BundledStops {
            val header = reader.readLine() ?: return EMPTY
            val headerFields = header.split('\t')
            require(READ_VERSIONS.any { headerFields[0] == "# stopdash stops v$it" }) {
                "stops: unexpected header for format $FORMAT_VERSION"
            }
            val bounds = headerFields.drop(1).firstOrNull { it.startsWith("bounds=") }
                ?.removePrefix("bounds=")?.split(',')?.mapNotNull { it.toDoubleOrNull() }
            require(bounds != null && bounds.size == 4 && bounds[0] < bounds[2] && bounds[1] < bounds[3]) {
                "stops: no bounds in the header"
            }
            val lines = HashMap<String, LineRef>()
            val stops = ArrayList<StopLocation>(25_000)
            val types = java.io.ByteArrayOutputStream(25_000)
            var skipped = 0
            while (true) {
                val record = reader.readLine() ?: break
                if (record.isEmpty()) continue
                val fields = record.split('\t')
                when (fields[0]) {
                    "L" -> if (fields.size == 4 && fields[1].isNotBlank()) {
                        lines[fields[1]] = LineRef(fields[1], riderLineName(fields[3], fields[2]), fields[2])
                    } else {
                        skipped++
                    }
                    "S" -> {
                        val stop = stopOf(fields, lines)
                        val type = fields.getOrNull(2)?.let(CODE_INDEX::get)
                        if (stop == null || type == null) {
                            skipped++
                        } else {
                            stops += stop
                            types.write(type)
                        }
                    }
                    else -> skipped++
                }
            }
            return BundledStops(stops, types.toByteArray(), bounds.toDoubleArray(), skipped)
        }

        // A stop record, as the app reads TfL's answer for it (toStopLocationOrNull); null when it
        // lacks what identifies or places it, or names a line the file doesn't.
        private fun stopOf(record: List<String>, lines: Map<String, LineRef>): StopLocation? {
            // Trailing empty fields may have been trimmed (a pier with no lines ends in tabs).
            if (record.size !in 6..STOP_FIELDS) return null
            val fields = record + List(STOP_FIELDS - record.size) { "" }
            val id = fields[1]
            val name = cleanStopName(fields[3])
            val lat = fields[4].toDoubleOrNull()
            val lon = fields[5].toDoubleOrNull()
            if (id.isBlank() || name.isBlank() || lat == null || lon == null) return null
            val stopLines = fields[11].split(',').filter { it.isNotEmpty() }.map { lines[it] ?: return null }
            return StopLocation(
                id = id,
                name = name,
                latitude = lat,
                longitude = lon,
                lines = stopLines,
                clusterId = fields[6].ifBlank { name },
                interchangeId = fields[7],
                // Read as TfL's own answer is (StopPole): an arrow in the letter is a bearing.
                stopLetter = StopPole.letter(fields[8]),
                bearing = StopPole.bearing(fields[8], fields[9]),
                towards = fields[10].trim(),
            )
        }
    }
}

/**
 * A [StopFinder] that answers from the bundled stops ([BundledStops]) when they cover the search,
 * and asks [delegate] (TfL, through its cache) when they don't: outside the bundle's area, for a
 * stop type it doesn't hold, or when the bundle couldn't be read. The search over the bundle runs
 * on [compute], never the caller's thread.
 */
class BundledStopFinder(
    private val delegate: StopFinder,
    private val bundled: suspend () -> BundledStops,
    private val compute: CoroutineDispatcher = Workers.compute,
    private val warn: (String) -> Unit = {},
) : StopFinder {
    override suspend fun nearbyStops(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        stopTypes: List<String>,
    ): List<StopLocation> {
        val stops = try {
            bundled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Static public data: naming the failure carries nothing about the user.
            warn("bundled stops unavailable: ${e::class.simpleName}; asking TfL")
            BundledStops.EMPTY
        }
        // No coordinate in the log (SPEC Privacy): only that TfL was asked.
        if (stops.size == 0) return delegate.nearbyStops(latitude, longitude, radiusMeters, stopTypes)
        if (!stops.holds(stopTypes) || !stops.covers(latitude, longitude, radiusMeters)) {
            warn("nearby: outside the bundled stops; asking TfL")
            return delegate.nearbyStops(latitude, longitude, radiusMeters, stopTypes)
        }
        return withContext(compute) { stops.nearby(latitude, longitude, radiusMeters, stopTypes) }
    }
}

/** The bundled stops couldn't be read, or don't hold a stop type asked for: [OnDeviceStopFinder] has no answer. */
class BundledStopsUnavailableException(message: String) : Exception(message)

/**
 * A [StopFinder] that never sends the position anywhere: the widget's follow, the one use of location in
 * the background (SPEC D1, *Privacy*). It answers from [cache] (the nearby lookups the app already made,
 * a TfL answer near the area's edge among them) or else from the bundled stops ([BundledStops]), and never
 * asks TfL. A search reaching past the bundle's area gets the bundled stops within it; the bundle is TfL's
 * network with a margin, so past it there are few stops to miss. That answer isn't cached, so the in-app
 * near-me list, which may ask TfL, doesn't reuse it. Throws [BundledStopsUnavailableException] when the
 * bundle can't be read or doesn't hold a type asked for, so a caller keeps what it had rather than reading
 * "no stops". The search runs on [compute], never the caller's thread.
 */
class OnDeviceStopFinder(
    private val bundled: suspend () -> BundledStops,
    private val cache: NearbyStopsCache? = null,
    private val compute: CoroutineDispatcher = Workers.compute,
    private val warn: (String) -> Unit = {},
) : StopFinder {
    override suspend fun nearbyStops(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        stopTypes: List<String>,
    ): List<StopLocation> {
        // The cache's file is read and written blocking, as for [CachingStopFinder]: call this off the main thread.
        cache?.lookup(latitude, longitude, radiusMeters, stopTypes)?.let { return it }
        val stops = try {
            bundled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BundledStopsUnavailableException("bundled stops unavailable: ${e::class.simpleName}")
        }
        if (stops.size == 0) throw BundledStopsUnavailableException("no bundled stops")
        if (!stops.holds(stopTypes)) throw BundledStopsUnavailableException("a stop type the bundle doesn't hold")
        val found = withContext(compute) { stops.nearby(latitude, longitude, radiusMeters, stopTypes) }
        if (stops.covers(latitude, longitude, radiusMeters)) {
            cache?.put(latitude, longitude, radiusMeters, stopTypes, found)
        } else {
            // No coordinate in the log (SPEC Privacy): only that the search reached past the bundle.
            warn("nearby: past the bundled stops; answered on the device")
        }
        return found
    }
}
