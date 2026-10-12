package app.stopdash.data

import android.content.Context
import app.stopdash.domain.BusStopIndex
import app.stopdash.domain.Workers
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * London's bus stops for the stop search ([BusStopIndex]), from the bundled stops the near-me list
 * reads ([BundledStopsStore], `assets/stations/stops.tsv`): one file, read once per process, and the
 * index built from it once, off the main thread, and cached.
 *
 * Fails safe as that store does: stops it couldn't read leave an empty index, so the search finds
 * stations and places only rather than breaking (SPEC principle 2); the store logs why.
 */
object BusStopIndexStore {
    @Volatile
    private var cached: BusStopIndex? = null
    private val building = Mutex()

    /** The index, built once and cached, the file read on [io] and the index built on [compute]. */
    suspend fun load(
        context: Context,
        io: CoroutineDispatcher = Dispatchers.IO,
        compute: CoroutineDispatcher = Workers.compute,
    ): BusStopIndex {
        cached?.let { return it }
        return building.withLock {
            cached ?: run {
                val stops = BundledStopsStore.load(context, io)
                withContext(compute) { BusStopIndex.of(stops.busStops()) }.also { cached = it }
            }
        }
    }

    /**
     * Reads the stops and builds the index in the background as the app starts, so neither the
     * first near-me lookup ([BundledStopsStore]) nor the first search waits on the read. Returns at
     * once; [io] does the read, and a later [load] finds it done (or joins it, under the same lock).
     */
    fun warm(
        context: Context,
        scope: CoroutineScope,
        io: CoroutineDispatcher = Dispatchers.IO,
        compute: CoroutineDispatcher = Workers.compute,
    ): Job {
        val appContext = context.applicationContext
        return scope.launch(io) {
            try {
                load(appContext, io, compute)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Static public data: naming the failure carries nothing about the user. The first
                // lookup or search reads it again, and fails safe there as it always did.
                Log.w("StopDash.Stations", "bundled stops warm-up failed: ${e::class.simpleName}")
            }
        }
    }
}
