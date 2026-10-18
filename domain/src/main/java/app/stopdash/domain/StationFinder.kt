package app.stopdash.domain

/**
 * A station, hub or stop that matched a typed name (SPEC *Finding stops → Find a station*): the
 * id to look it up by ([StationFinder.stationStops]), its cleaned display name, and the modes it
 * serves (for the result row's secondary line). Public TfL facts, never the rider's location: the
 * [latitude]/[longitude] are TfL's own placing of the station, when its search gives one, used
 * only to fold same-named neighbors into one result ([StationIndex.rank]) and never saved.
 */
data class StationMatch(
    val id: String,
    val name: String,
    val modes: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    // Set on one of an interchange's station names listed as its own row ([StationIndex.hubNames]):
    // the modes that name stands for, Underground first. [id] is still the interchange's, so the row
    // opens the interchange; the list tells it which modes lead.
    val lead: List<String> = emptyList(),
    // The level the row is a pick at (dev-docs/station-model.md *Saved data*), where the row says so
    // itself: a bundled station or interchange, a bundled bus stop, a place. Null where only the model
    // can tell (an interchange's station name, a TfL search answer, a stop the app has shown), read
    // then through [Hubs.picked]. Saved with the pick; nothing reads it yet.
    val level: Pick.Level? = null,
    // What the row shows and says for [modes], worked out once, where the match is first made (off the
    // main thread). Constructor parameters, not body properties, so a copy carries them rather than
    // working them out again: a click handler that copies a match ("Add favorite" on a positionless
    // stop) does no list work. A copy that changes [modes] goes through [withModes] instead.
    val modeIcons: List<ModeIcon> = ModeIcon.of(modes),
    val otherModesLabel: String? = ModeIcon.others(modes).takeIf { it.isNotEmpty() }?.let { modesLabel(it) },
    val modesSpoken: String = modesLabel(modes),
) {
    /**
     * This row's identity in a list: the [id], or for an interchange's station name, the id and
     * name, so "King's Cross" and "St Pancras International" are two rows beside the interchange's own.
     */
    val key: String get() = if (lead.isEmpty()) id else "$id|$name"

    /** This match with [modes] instead, its icons and labels worked out for them: never `copy(modes = …)`. */
    fun withModes(modes: List<String>): StationMatch = StationMatch(id, name, modes, latitude, longitude, lead, level)
}

/**
 * Finds a station by name and resolves the stops that carry its departures — the in-app "Find a
 * station" action (SPEC *Finding stops*). Kept apart from [TflClient] like [StopFinder]: this is an
 * on-demand, user-typed search whose query leaves the device, while departures refresh is a
 * background path that must not reach it.
 */
interface StationFinder {
    /**
     * Stations and stops whose name matches [query], best match first (TfL `/StopPoint/Search`).
     * Throws on a transport/decode failure, mapped to a domain [TflException].
     */
    suspend fun searchStations(query: String): List<StationMatch>

    /**
     * The stops under station/hub [id] that carry departures — a hub's stations, a station itself,
     * a bus stop area's poles — with their served lines, from TfL `/StopPoint/{id}`. Empty when the
     * id resolves to nothing stopdash shows departures for. Throws like [searchStations].
     */
    suspend fun stationStops(id: String): List<StopLocation>
}
