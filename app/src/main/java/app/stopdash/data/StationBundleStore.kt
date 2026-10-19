package app.stopdash.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.stopdash.domain.StationBundle
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * The bundled station index, stops and station model, as the build worked them out ([StationBundle],
 * `stations/station_bundle.bin`): the one file [StationIndexStore], [BundledStopsStore] and [HubsStore]
 * read them from, once per process, and cached.
 *
 * Null where it can't be read whole: each store then goes without, as it did before its source file
 * existed (SPEC principle 2) — station search and near me on TfL's own answers, no station model.
 */
object StationBundleStore {
    private const val TAG = "StopDash.Stations"

    @Volatile
    private var read = false
    private var contents: StationBundle.Contents? = null

    /** The bundle, read once and cached. Reads the asset: call it off the main thread. */
    fun load(context: Context): StationBundle.Contents? {
        if (read) return contents
        synchronized(this) {
            if (read) return contents
            contents = try {
                val begun = SystemClock.elapsedRealtime()
                context.assets.open(StationBundle.ASSET).use { StationBundle.read(it) }.also {
                    // Static public data and a duration: nothing about the user.
                    Log.i(TAG, "station bundle read in ${SystemClock.elapsedRealtime() - begun} ms")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                // Static public data throughout: naming the failure carries nothing about the user.
                Log.w(TAG, "no station bundle; going without it", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "station bundle not read: ${e.message}; going without it", e)
                null
            } catch (e: IOException) {
                Log.w(TAG, "station bundle load failed: ${e::class.simpleName}; going without it", e)
                null
            } catch (e: RuntimeException) {
                // A bundle corrupt some other way (a decoder's own runtime exception): gone without, once,
                // rather than asked for again. Static public data, so the whole stack is logged (Codex on #1002).
                Log.w(TAG, "station bundle unreadable; going without it", e)
                null
            }
            read = true
            return contents
        }
    }
}
