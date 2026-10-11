package app.stopdash.data

import android.content.Context
import android.util.Log
import app.stopdash.domain.BundledStops
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the bundled stops ([BundledStops], SPEC *Finding stops → Near me now*), built by
 * `scripts/build_stops.py`. Read once per process, off the main thread, and cached.
 *
 * Fails safe to [BundledStops.EMPTY]: a missing, corrupt or other-format asset leaves the near-me
 * list on TfL's nearby search, as before the bundle existed, rather than breaking it (SPEC
 * principle 2).
 */
object BundledStopsStore {
    private const val ASSET = "stations/stops.tsv"
    private const val TAG = "StopDash.Stations"

    @Volatile
    private var cached: BundledStops? = null

    /** The bundled stops, read once and cached, on [io]: safe to ask for from anywhere. */
    suspend fun load(context: Context, io: CoroutineDispatcher = Dispatchers.IO): BundledStops {
        cached?.let { return it }
        return withContext(io) { read(context) }
    }

    private fun read(context: Context): BundledStops {
        synchronized(this) {
            cached?.let { return it }
            val stops = try {
                context.assets.open(ASSET).bufferedReader().use { BundledStops.read(it) }.also {
                    // Static public data: naming a count carries nothing about the user.
                    if (it.skippedLines > 0) Log.w(TAG, "bundled stops: ${it.skippedLines} unreadable lines left out")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "no bundled stops; asking TfL")
                BundledStops.EMPTY
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "bundled stops not read: ${e.message}")
                BundledStops.EMPTY
            } catch (e: java.io.IOException) {
                Log.w(TAG, "bundled stops load failed: ${e::class.simpleName}")
                BundledStops.EMPTY
            }
            cached = stops
            return stops
        }
    }
}
