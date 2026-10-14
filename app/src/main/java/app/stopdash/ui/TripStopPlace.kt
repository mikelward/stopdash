package app.stopdash.ui

import app.stopdash.domain.BusStopIndex
import app.stopdash.domain.Coordinates
import app.stopdash.domain.StationIndex
import app.stopdash.domain.Workers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the stop or station [id] a trip goes to is, as the place its *Direct* section looks around:
 * a station is a place too (SPEC *Direct to a place*; maintainer, 2026-10-14). From the bundled lists,
 * so nothing is asked: a station's own point, or an interchange's middle ([StationIndex.placeOf]), else
 * a bus stop's or its stop area's ([BusStopIndex.positionOf]). Null when neither places it, said in
 * [warn] without the stop, since where a trip goes is the rider's (SPEC *Privacy*). Both loaders fail
 * safe to an empty list. Searched on [compute], the hop first, the station list read on [io]
 * (AGENTS.md *Main thread*).
 */
internal suspend fun tripStopPlace(
    id: String,
    stations: () -> StationIndex,
    busStops: suspend () -> BusStopIndex,
    warn: (String) -> Unit = {},
    io: CoroutineDispatcher = Dispatchers.IO,
    compute: CoroutineDispatcher = Workers.compute,
): Coordinates? = withContext(compute) {
    val index = withContext(io) { stations() }
    index.placeOf(id) ?: busStops().positionOf(id)
        ?: null.also { warn("trip direct: the destination isn't in the bundled lists, so no Direct section") }
}
