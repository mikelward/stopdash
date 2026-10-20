package app.stopdash.domain

import java.time.Instant

/**
 * A stop a train or bus will call at, as TfL predicts it: the stop, its cleaned name, the platform
 * (null for a bus) and when it's expected. Public network data, no user data. A National Rail train's
 * call is at a station, by its code ([RailFollow]): [stationIds] are every TfL id of that station, so a
 * call there is a call at each, where TfL lists one station under several (St Pancras's three) and
 * [stopId] is blank as naming one would be a guess. Empty for TfL's own calls, which name the stop.
 * [endsRun] marks the train's last call where it's known to be: no call follows, so a stop missing from
 * the calls before it is one the train won't call at (a National Rail train's, whose board gives its
 * stops through where it now ends, Codex, #883). Never for TfL's, a prediction window.
 */
data class VehicleCall(
    val stopId: String,
    val stopName: String,
    val platform: String?,
    val expected: Instant,
    val stationIds: Set<String> = emptySet(),
    val endsRun: Boolean = false,
)

/**
 * One train's predicted calls ahead of it (SPEC *On the way*), from TfL's `/Vehicle/{id}/Arrivals`,
 * soonest first, so a trip can follow the train the rider is on without asking each stop along the
 * way. [vehicleId] is TfL's ([Departure.vehicleId]), unique only within a line, so the answer is kept
 * to [lineId].
 *
 * A prediction window, not the route: TfL predicts only so far ahead (about half an hour), so a stop
 * further on is missing until the train nears it, and neither the list's end nor an empty list says
 * the train has finished its run, unless its last call says so ([VehicleCall.endsRun]). A caller
 * confirms a stop is passed from a later refresh, not from its absence.
 */
interface VehicleSource {
    suspend fun vehicleCalls(vehicleId: String, lineId: String): List<VehicleCall>

    /**
     * [vehicleId]'s calls, asked to check whether it's the rider's train rather than to follow it: as
     * little as one read, where following looks further for a train a board leaves out ([RailVehicles]).
     * Not found there, it's not known this time, a failed lookup. TfL's are the same request.
     */
    suspend fun candidateCalls(vehicleId: String, lineId: String): List<VehicleCall> = vehicleCalls(vehicleId, lineId)

    /**
     * When the calls last answered for [vehicleId] were read, where a source answers from a read older
     * than the request (a National Rail board shared from a read before: [RailVehicles]), so a trip counts
     * them as old as they are; null where every answer is read when asked, as TfL's are. Stamped in the
     * steady frame ([SteadyClock.stamp]), so the clock set back doesn't make an old read new (Codex, #883).
     */
    fun readAt(vehicleId: String): Instant? = null
}
