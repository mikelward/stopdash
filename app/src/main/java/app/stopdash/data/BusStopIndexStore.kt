package app.stopdash.data

import android.content.Context
import app.stopdash.domain.BusStopIndex
import app.stopdash.domain.Workers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
}
