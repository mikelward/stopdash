package app.stopdash.data

import android.content.Context
import android.util.Log
import app.stopdash.domain.PlaceIndex
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the bundled London place index (dev-docs/PLACES.md), built by
 * `scripts/build_place_index.py`. Read once per process, off the main thread, and cached: it holds
 * about 25 MB, so a second copy is never made while the first is still reading.
 *
 * Fails safe to [PlaceIndex.EMPTY]: a missing, corrupt or other-format asset leaves the *To…* search
 * on TfL's geocoder, as before the index existed, rather than breaking it (SPEC principle 2).
 */
object PlaceIndexStore {
    private const val ASSET = "places/place_index.tsv"
    private const val TAG = "StopDash.Places"

    @Volatile
    private var cached: PlaceIndex? = null

    /** The bundled index, read once and cached, on [io]: safe to ask for from anywhere. */
    suspend fun load(context: Context, io: CoroutineDispatcher = Dispatchers.IO): PlaceIndex {
        cached?.let { return it }
        return withContext(io) { read(context) }
    }

    private fun read(context: Context): PlaceIndex {
        synchronized(this) {
            cached?.let { return it }
            val index = try {
                context.assets.open(ASSET).bufferedReader().use { PlaceIndex.read(it) }.also {
                    // Static public data: naming a count carries nothing about the user.
                    if (it.skippedLines > 0) Log.w(TAG, "place index: ${it.skippedLines} unreadable lines left out")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "no bundled place index; places come from TfL only")
                PlaceIndex.EMPTY
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "place index not read: ${e.message}")
                PlaceIndex.EMPTY
            } catch (e: java.io.IOException) {
                Log.w(TAG, "place index load failed: ${e::class.simpleName}")
                PlaceIndex.EMPTY
            }
            cached = index
            return index
        }
    }
}
