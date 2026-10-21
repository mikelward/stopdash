package app.stopdash.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import app.stopdash.domain.Hubs
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.Journeys
import app.stopdash.domain.PlaceStopsFinder
import app.stopdash.domain.PlaceStops
import app.stopdash.domain.StopFinder
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Looks up the stops around a favorite journey's end (SPEC *Journeys*): one TfL `/StopPoint` request by
 * the end's published position (TfL's, saved with the journey; never the rider's), through [finder], a
 * cache of its own a day long, on [io]. Throws a [TflException] on failure after logging it, sanitized
 * to the end's public stop id ([warn]).
 */
class JourneyEndStops(
    private val finder: StopFinder,
    private val warn: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun around(end: JourneyEnd, radiusMeters: Int): List<StopLocation> {
        val lat = end.latitude ?: return emptyList()
        val lon = end.longitude ?: return emptyList()
        return withContext(io) {
            try {
                finder.nearbyStops(lat, lon, radiusMeters)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                warn("journey end stops lookup failed for ${end.stopId}: ${e::class.simpleName}")
                throw e
            }
        }
    }
}

/** The near-me list's journey end lookups; null (none wired: a test, a preview) looks nothing up. */
val LocalJourneyEndStops = staticCompositionLocalOf<JourneyEndStops?> { null }

/**
 * The station model a journey's ends' hubs are read from ([Journeys.stopsInHub]): null while it loads,
 * where it's unavailable, or with none wired (a test, a preview), when each end is its own stop and stop
 * area alone.
 */
val LocalJourneyHubs = compositionLocalOf<Hubs?> { null }

/**
 * The rider's walk in meters, by their max walk and pace ([PlaceStops.walkMeters]), which sizes the lookup
 * around a journey's ends ([journeyEndRadius]); which of the stops found count is their hubs' alone
 * ([LocalJourneyHubs]). Null until both are read, when nothing is looked up or judged on a default
 * (Codex, #691).
 */
val LocalJourneyWalkMeters = compositionLocalOf<Int?> { PlaceStops.WALK_METERS }

/** How far around a journey's end to look up for [walkMeters]: the walk or the boarding radius, plus a cached lookup's reuse. */
internal fun journeyEndRadius(walkMeters: Int): Int =
    PlaceStopsFinder.lookupMeters(maxOf(walkMeters, Journeys.BOARDING_RADIUS_METERS.toInt()))
