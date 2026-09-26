package app.stopdash.data

import app.stopdash.domain.Departure
import app.stopdash.domain.VehicleCall
import app.stopdash.domain.branchOf
import app.stopdash.domain.cleanStopName
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One prediction from TfL's `/StopPoint/{id}/Arrivals` response. Only the fields
 * stopdash maps are declared; the client's `Json { ignoreUnknownKeys = true }`
 * drops the rest (TfL returns ~20 per prediction), so the DTO stays small and
 * tolerant of fields TfL adds later.
 *
 * [expectedArrival] is kept as the raw ISO-8601 string and parsed in [toDeparture]
 * rather than via a custom `Instant` serializer — one obvious place, no serializer
 * wiring.
 */
@Serializable
data class TflArrivalDto(
    val lineId: String = "",
    val lineName: String = "",
    val direction: String? = null,
    val platformName: String? = null,
    val destinationName: String? = null,
    val destinationNaptanId: String? = null,
    val towards: String? = null,
    val modeName: String = "",
    val expectedArrival: String = "",
    val vehicleId: String? = null,
    // Where the prediction is for: the stop's id and name, read by [toVehicleCall] (a stop's own
    // arrivals already know which stop they're for).
    val naptanId: String? = null,
    val stationName: String? = null,
)

/**
 * Maps a raw prediction to the domain [Departure]. The destination is the **terminus
 * only**: `destinationName` when TfL gives one, else the part of `towards` before " via "
 * (TfL omits destinationName on some services but usually gives `towards`). Anything past a
 * comma is dropped — a London bus `towards` like "Pimlico, Grosvenor Road" has a noise tail
 * — and the station-type suffix is trimmed ([cleanStopName]) the same way stop names are, so
 * "Brixton Underground Station" reads "Brixton" (SPEC *Concise copy*). The "via" branch is
 * carried separately in [Departure.branch] ([branchOf]), so "Battersea Power Station via
 * Charing Cross" becomes destination "Battersea Power" + branch "Charing X". A blank
 * platform becomes null (buses have none).
 * TfL's `direction` (`inbound`/`outbound`, absent on some services) is retained
 * as the grouping key for per-direction rows (SPEC D8) — normalized to "" when
 * absent or blank, never null, so the grouping key is uniform.
 */
fun TflArrivalDto.toDeparture(): Departure {
    val terminus = destinationName?.ifBlank { null }
        ?: towards?.ifBlank { null }?.let { t ->
            val i = t.indexOf(" via ", ignoreCase = true)
            if (i >= 0) t.substring(0, i) else t
        }
        ?: ""
    return Departure(
        lineId = lineId,
        lineName = lineName,
        direction = direction?.trim().orEmpty(),
        destination = cleanStopName(terminus.substringBefore(",").trim()),
        platform = platformName?.ifBlank { null },
        expectedArrival = Instant.parse(expectedArrival),
        mode = modeName,
        branch = branchOf(towards),
        destinationId = destinationNaptanId?.trim().orEmpty(),
        vehicleId = vehicleIdOf(vehicleId),
    )
}

/** A call on a train's own arrivals (`/Vehicle/{id}/Arrivals`): the stop it's for and when. */
fun TflArrivalDto.toVehicleCall(): VehicleCall = VehicleCall(
    stopId = naptanId?.trim().orEmpty(),
    stopName = cleanStopName(stationName.orEmpty()),
    platform = platformName?.ifBlank { null },
    expected = Instant.parse(expectedArrival),
)

// TfL's vehicle id, trimmed; blank when it gives none, or its all-zero placeholder (a train it hasn't
// identified), which is no particular train.
private fun vehicleIdOf(raw: String?): String = raw?.trim()?.takeIf { id -> id.any { it != '0' } }.orEmpty()
