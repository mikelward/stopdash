package app.stopdash.data

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import app.stopdash.domain.LocationTimings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The bundled table of how long Tube trains took to arrive from where TfL said they were
 * (`stations/location_timings.json`, built from recordings by `scripts/arrivals_recorder.py table`), read
 * once per process on first use, off the main thread ([current] is called on the arrivals decode dispatcher).
 * Fails safe to [LocationTimings.EMPTY]: a missing, corrupt or newer-format asset means TfL's own times, never
 * a crash or a guess.
 */
object LocationTimingsStore {
    private const val ASSET = "stations/location_timings.json"
    private const val CURRENT_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var context: Context? = null

    @Volatile
    private var cached: LocationTimings? = null

    /** Where [current] reads the asset from: the application's context, given once at start. */
    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    /** The table, read on first use; empty before [attach] (a test's client) or where it can't be read. */
    @WorkerThread
    fun current(): LocationTimings {
        cached?.let { return it }
        val from = context ?: return LocationTimings.EMPTY
        return synchronized(this) {
            cached ?: read(from).also { cached = it }
        }
    }

    private fun read(context: Context): LocationTimings =
        try {
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }) { Log.w("StopDash.Timings", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Static asset, no user data in the failure.
            Log.w("StopDash.Timings", "location timing table load failed: ${e::class.simpleName}")
            LocationTimings.EMPTY
        }

    internal fun parse(text: String, warn: (String) -> Unit = {}): LocationTimings {
        val file = json.decodeFromString<TableFile>(text)
        if (file.version != CURRENT_VERSION) {
            warn("location timing table version ${file.version} != $CURRENT_VERSION, ignoring")
            return LocationTimings.EMPTY
        }
        val steps = HashMap<String, Map<Int, Int>>()
        for ((stop, lines) in file.stops) {
            for ((line, platforms) in lines) {
                for ((platform, states) in platforms) {
                    for ((state, pairs) in states) {
                        val byStep = pairs.filter { it.size == 2 }.associate { it[0] to it[1] }
                        if (byStep.isNotEmpty()) steps[LocationTimings.key(stop, line, platform, state)] = byStep
                    }
                }
            }
        }
        return LocationTimings(steps)
    }

    @Serializable
    private class TableFile(
        val version: Int,
        // Stop, line, platform number, location: each [step, seconds to arrival].
        val stops: Map<String, Map<String, Map<String, Map<String, List<List<Int>>>>>> = emptyMap(),
    )
}
