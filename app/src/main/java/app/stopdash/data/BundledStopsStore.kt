package app.stopdash.data

import android.content.Context
import app.stopdash.domain.BundledStops
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bundled stops ([BundledStops], SPEC *Finding stops → Near me now*), built by
 * `scripts/build_stops.py` and shipped in the build's station bundle ([StationBundleStore]). Read
 * once per process, off the main thread.
 *
 * Reads as [BundledStops.EMPTY] where the bundle can't be read: the near-me list then says it couldn't load
 * the stops rather than send the rider's position to TfL ([app.stopdash.domain.BundledStopFinder]), and a
 * lookup around a place asks TfL (SPEC principle 2).
 */
object BundledStopsStore {
    /** The bundled stops, read once and cached, on [io]: safe to ask for from anywhere. */
    suspend fun load(context: Context, io: CoroutineDispatcher = Dispatchers.IO): BundledStops =
        withContext(io) { StationBundleStore.load(context)?.stops ?: BundledStops.EMPTY }
}
