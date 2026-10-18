package app.stopdash.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.stopdash.domain.Hubs
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The station model ([Hubs], dev-docs/station-model.md), loaded once per process on a worker as the
 * app starts, from the bundled station index and stops it already reads, and published as [state]:
 * [Hubs.State.Loading] until then, then [Hubs.State.Ready] or [Hubs.State.Unavailable], once. Nothing
 * reads it yet (phase 2 of the design does).
 */
object HubsStore {
    private const val TAG = "StopDash.Stations"

    private val mutableState = MutableStateFlow<Hubs.State>(Hubs.State.Loading)
    val state: StateFlow<Hubs.State> = mutableState.asStateFlow()

    private val started = AtomicBoolean(false)

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
