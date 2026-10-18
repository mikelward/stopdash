package app.stopdash.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.stopdash.domain.Hubs
import app.stopdash.domain.PendingEnd
import app.stopdash.domain.StationMatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The station model ([Hubs], dev-docs/station-model.md), loaded once per process on a worker as the
 * app starts, from the bundled station index and stops it already reads, and published as [state]:
 * [Hubs.State.Loading] until then, then [Hubs.State.Ready] or [Hubs.State.Unavailable], once. A
 * station page reads it ([ready]) for the records its station is.
 */
object HubsStore {
    private const val TAG = "StopDash.Stations"

    private val mutableState = MutableStateFlow<Hubs.State>(Hubs.State.Loading)
    val state: StateFlow<Hubs.State> = mutableState.asStateFlow()

    private val started = AtomicBoolean(false)

    // How long [ready] waits for a load still under way: the model is built once, as the app starts, so
    // this bounds only a slow build, after which the caller goes without it.
    private const val READY_WAIT_MILLIS = 3_000L

    /**
     * The model once loaded, waiting for a load under way at most [READY_WAIT_MILLIS]; null when it
     * couldn't be built, or isn't built in time, for the caller to do without it as before.
     */
    suspend fun ready(): Hubs? =
        (withTimeoutOrNull(READY_WAIT_MILLIS) { state.first { it !is Hubs.State.Loading } } as? Hubs.State.Ready)?.hubs

    /**
     * [match] as the model now reads a pick of it ([Hubs.resolved]): an interchange's station name
     * saved before phase 2 at that station. Itself while the model is still loading or unavailable,
     * opening as it was saved. Called where a store reads its file, off the main thread; unmarked, as
     * [Hubs.resolved] is.
     */
    fun resolvedNow(match: StationMatch): StationMatch = readyNow()?.resolved(match) ?: match

    /** A pending ride's end, as [resolvedNow] reads a search row. */
    fun resolvedNow(end: PendingEnd.Station): PendingEnd.Station = readyNow()?.resolved(end) ?: end

    private fun readyNow(): Hubs? = (state.value as? Hubs.State.Ready)?.hubs

    /** Starts the load on [scope] once per process: files on [io], the model built on [compute]. */
    fun warm(
        context: Context,
        scope: CoroutineScope,
        io: CoroutineDispatcher = Dispatchers.IO,
        compute: CoroutineDispatcher = Dispatchers.Default,
    ) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            mutableState.value = try {
                val stops = BundledStopsStore.load(appContext, io)
                val index = withContext(io) { StationIndexStore.load(appContext) }
                withContext(compute) {
                    val begun = SystemClock.elapsedRealtime()
                    val hubs = Hubs.of(index, stops) { Log.w(TAG, "station model unavailable: $it") }
                    // Static public data and a duration: nothing about the user.
                    if (hubs != null) Log.i(TAG, "station model built in ${SystemClock.elapsedRealtime() - begun} ms")
                    hubs?.let { Hubs.State.Ready(it) } ?: Hubs.State.Unavailable
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The bundled files are public data and the model holds nothing about the user, so the
                // whole exception, message and stack, can be logged.
                Log.w(TAG, "station model load failed", e)
                Hubs.State.Unavailable
            }
        }
    }
}
