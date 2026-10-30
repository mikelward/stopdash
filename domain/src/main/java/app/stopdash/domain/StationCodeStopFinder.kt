package app.stopdash.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [delegate]'s stops, each a station's record placed by its station's code: its [StopLocation.clusterId] is the
 * place the bundled station model files its TfL station code (ICS) under ([Hubs.stationPlaceOf]: one of the code's
 * records, still a TfL id a station opens by), so every record of one station is one place wherever a place is
 * keyed (maintainer, 2026-10-30): St Pancras International's high-speed
 * platforms beside its main record, Paddington's Elizabeth line beside its main station, Farringdon's rail and
 * Underground stations. The list's headings, its station pages, a ride's station, a place's distance and its
 * notices, the widget's and the watch's headings, all read the one place. A stop the model doesn't hold (a bus
 * pole), or every stop where the model is unavailable, keeps TfL's cluster. [model] may wait for the model to load
 * ([app.stopdash.data.HubsStore.ready], bounded), so a list's first lookup is placed as the next is. The relabeling
 * runs on [compute].
 */
class StationCodeStopFinder(
    private val delegate: StopFinder,
    private val model: suspend () -> Hubs?,
    private val compute: CoroutineDispatcher = Workers.compute,
) : StopFinder {
    override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>): List<StopLocation> {
        val stops = delegate.nearbyStops(latitude, longitude, radiusMeters, stopTypes)
        val hubs = model() ?: return stops
        return withContext(compute) { placed(stops, hubs::stationPlaceOf) }
    }

    companion object {
        /** [stops], each a station's record with its station's place as its cluster ([placeOf]); any other as it was. */
        fun placed(stops: List<StopLocation>, placeOf: (stopId: String) -> String?): List<StopLocation> = stops.map { stop ->
            val code = placeOf(stop.id) ?: return@map stop
            if (code == stop.clusterId) stop else stop.copy(clusterId = code)
        }
    }
}
