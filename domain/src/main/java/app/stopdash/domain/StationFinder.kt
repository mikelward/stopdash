package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A station, hub or stop that matched a typed name (SPEC *Finding stops → Find a station*): the
 * id to look it up by ([StationFinder.stationStops]), its cleaned display name, and the modes it
 * serves (for the result row's secondary line). Public TfL facts, never the rider's location: the
 * [latitude]/[longitude] are TfL's own placing of the station, where the bundled data gives one, used
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
    // can tell (an interchange's station name, a stop the app has shown), read
    // then through [Hubs.picked]. Saved with the pick; nothing reads it yet.
    val level: Pick.Level? = null,
    // What the row shows for [modes], worked out once, where the match is first made (off the main
    // thread). Constructor parameters, not body properties, so a copy carries them rather than working
    // them out again: a click handler that copies a match ("Add favorite" on a positionless stop) does
    // no list work. A copy that changes [modes] goes through [withModes] instead. What a screen reader
    // hears for the modes is said by the row, in the app's language (`modesSpoken` in the app).
    val modeIcons: List<ModeIcon> = ModeIcon.of(modes),
    // The modes no icon stands for, which the row names in the app's language (`modesSpoken` in the app).
    val otherModes: List<String> = ModeIcon.others(modes),
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
 * Resolves the stops that carry a picked station's departures — the in-app "Find a station" action
 * (SPEC *Finding stops*), whose search itself runs on the device ([StationIndex]). Kept apart from
 * [TflClient] like [StopFinder]: this is an on-demand lookup of what the rider picked, while
 * departures refresh is a background path that must not reach it.
 */
interface StationFinder {
    /**
     * The stops under station/hub [id] that carry departures — a hub's stations, a station itself,
     * a bus stop area's poles — with their served lines, from TfL `/StopPoint/{id}`. Empty when the
     * id resolves to nothing stopdash shows departures for. Throws on a transport/decode failure,
     * mapped to a domain [TflException].
     */
    suspend fun stationStops(id: String): List<StopLocation>

    /**
     * [stationStops] for each of [ids], together: TfL answers up to 20 ids in one `/StopPoint/{ids}`
     * request, so a bus station's many poles cost a request or two, not one each. Every id or none:
     * throws like [stationStops] if any request fails. By default one [stationStops] per id.
     */
    suspend fun stationStops(ids: List<String>): List<StopLocation> = ids.flatMap { stationStops(it) }
}

/**
 * The stops of every stand the station or bus stop [id] names, as the station model holds it ([Hubs.lookupIdsOf]):
 * St Pancras's every record, a bus stop's poles either side of the road. [id] alone without a model, or for an id
 * it doesn't hold. Every lookup or none: throws like [StationFinder.stationStops] if any fails, so a part is
 * never passed off as the whole. On a worker: the model's lookup walks its stop.
 */
@WorkerThread
suspend fun StationFinder.everyStop(id: String, hubs: Hubs?): List<StopLocation> =
    stationStops(hubs?.lookupIdsOf(id).orEmpty().ifEmpty { listOf(id) }).distinctBy { it.id }
