package app.stopdash.domain

/**
 * Finds stops near a point — the in-app "near me now" search (SPEC *Finding stops*, D1), and
 * the widget's follow when *Automatic watch and widget location* is on — kept separate from [TflClient] on
 * purpose: departures refresh is a location-free path, while finding stops sends the user's
 * location off the device. Separating the two keeps the refresh client unable to reach a
 * location-sending call, and lets a surface depend on only the capability it uses.
 */
interface StopFinder {
    /**
     * Stops near ([latitude], [longitude]) within [radiusMeters], from TfL's `/StopPoint`
     * by coordinates. [stopTypes] is the NaPTAN stop-type filter (tube/rail/bus by
     * default). This is the one call that sends the user's location off the device, and
     * only on an explicit in-app action — never a background one (SPEC D1 / *Privacy*).
     * Returns stops **unranked**: distance ordering and taking the nearest few are the
     * caller's job ([NearestStops]), the way [TflClient.arrivals] leaves sorting to the
     * caller. Throws on a transport/decode failure, mapped to a domain [TflException].
     */
    suspend fun nearbyStops(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        stopTypes: List<String> = DEFAULT_NEARBY_STOP_TYPES,
    ): List<StopLocation>

    companion object {
        /**
         * The default NaPTAN stop types for [nearbyStops]: tube/DLR/Overground metro
         * stations, national-rail stations, bus/coach/tram stops, and river-bus piers
         * (`NaptanFerryPort`) — the modes stopdash shows departures for (river bus is in
         * TfL's arrivals coverage, SPEC). The port level, not a berth/entrance. Callers can
         * narrow it (e.g. a rail-only search).
         */
        val DEFAULT_NEARBY_STOP_TYPES: List<String> = listOf(
            "NaptanMetroStation",
            "NaptanRailStation",
            "NaptanPublicBusCoachTram",
            "NaptanFerryPort",
        )
    }
}
