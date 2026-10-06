package app.stopdash.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether the precise-location request has been shown at least once, kept so an existing
 * coarse-only install is asked for precise exactly once. Read and written on [io]: the first read
 * of the preferences file is a disk read, which the main thread did at every start (maintainer bug
 * report, 2026-10-05).
 */
internal class PrecisePrompted(
    private val prefs: () -> SharedPreferences,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // Set by [mark] before its write, so a check that comes first already sees it.
    @Volatile private var marked = false

    suspend fun get(): Boolean = marked || withContext(io) { prefs().getBoolean(KEY, false) }

    /** Marks it shown: at once for [get], then kept on [io]. */
    suspend fun mark() {
        marked = true
        withContext(io) { prefs().edit().putBoolean(KEY, true).apply() }
    }

    companion object {
        private const val KEY = "precise_prompted"

        @Volatile private var instance: PrecisePrompted? = null

        /** This process's one, so a mark outlives the activity that made it (a rotation). */
        fun of(context: Context): PrecisePrompted = instance ?: synchronized(this) {
            instance ?: PrecisePrompted({
                context.applicationContext.getSharedPreferences("stopdash.location", Context.MODE_PRIVATE)
            }).also { instance = it }
        }
    }
}
