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
 * Fails safe to [BundledStops.EMPTY]: a bundle that can't be read leaves the near-me list on TfL's
 * nearby search, as before the stops were bundled, rather than breaking it (SPEC principle 2).
 */
object BundledStopsStore {
    /** The bundled stops, read once and cached, on [io]: safe to ask for from anywhere. */
    suspend fun load(context: Context, io: CoroutineDispatcher = Dispatchers.IO): BundledStops =
        withContext(io) { StationBundleStore.load(context)?.stops ?: BundledStops.EMPTY }
}
