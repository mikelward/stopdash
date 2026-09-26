package app.stopdash.domain

import java.time.Instant

/**
 * A stop a train or bus will call at, as TfL predicts it: the stop, its cleaned name, the platform
 * (null for a bus) and when it's expected. Public network data, no user data.
 */
data class VehicleCall(val stopId: String, val stopName: String, val platform: String?, val expected: Instant)

/**
 * One train's predicted calls ahead of it (SPEC *On the way*), from TfL's `/Vehicle/{id}/Arrivals`,
 * soonest first, so a trip can follow the train the rider is on without asking each stop along the
 * way. [vehicleId] is TfL's ([Departure.vehicleId]), unique only within a line, so the answer is kept
 * to [lineId].
 *
 * A prediction window, not the route: TfL predicts only so far ahead (about half an hour), so a stop
 * further on is missing until the train nears it, and neither the list's end nor an empty list says
 * the train has finished its run. A caller confirms a stop is passed from a later refresh, not from
 * its absence.
 */
interface VehicleSource {
    suspend fun vehicleCalls(vehicleId: String, lineId: String): List<VehicleCall>
}
