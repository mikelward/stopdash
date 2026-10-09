package app.stopdash.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether the precise-location request has been shown at least once, kept so an existing
 * coarse-only install is asked for precise exactly once, and how it was last answered. Read and written on [io]: the first read
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

    // As [marked], for [lastRefused]: null until this process records an answer.
    @Volatile private var refused: Boolean? = null

    /**
     * Whether the rider's last answer to the location request was a refusal. Only that, with Android
     * withholding its rationale, means it won't prompt again: a one-time grant that has since expired
     * also withholds the rationale, and there Android does prompt again (Codex on #732).
     *
     * An install from before the answer was kept has only "asked once" to go on, and that can't tell
     * the two apart, so it's read as a refusal, as it was before: Settings works either way, where an
     * Allow tap Android refuses unseen does nothing. Any grant seen afterwards clears it ([grantSeen]).
     */
    suspend fun lastRefused(): Boolean = refused ?: withContext(io) {
        val prefs = prefs()
        if (prefs.contains(KEY_REFUSED)) prefs.getBoolean(KEY_REFUSED, false) else prefs.getBoolean(KEY, false)
    }

    /** Records the rider's answer: at once for [lastRefused], then kept on [io]. */
    suspend fun answered(granted: Boolean) {
        refused = !granted
        withContext(io) { prefs().edit().putBoolean(KEY_REFUSED, !granted).apply() }
    }

    /**
     * Records that this version asked and the answer said nothing either way (a first refusal, a
     * dismissed prompt): kept as not refused where nothing is recorded yet, so it isn't read later as
     * the "asked before" of an install from before the answer was kept (Codex on #732). A refusal
     * already recorded stands.
     */
    suspend fun askedWithoutRefusal() {
        if (refused == null) withContext(io) {
            val prefs = prefs()
            if (!prefs.contains(KEY_REFUSED)) prefs.edit().putBoolean(KEY_REFUSED, false).apply()
        }
    }

    /**
     * Clears a remembered refusal once a grant is seen, however it was given (Settings included), so a
     * grant Android later resets isn't read as the refusal before it (Codex on #732). Written only when
     * one is remembered.
     */
    suspend fun grantSeen() {
        if (lastRefused()) answered(granted = true)
    }

    companion object {
        private const val KEY = "precise_prompted"
        private const val KEY_REFUSED = "location_last_refused"

        @Volatile private var instance: PrecisePrompted? = null

        /** This process's one, so a mark outlives the activity that made it (a rotation). */
        fun of(context: Context): PrecisePrompted = instance ?: synchronized(this) {
            instance ?: PrecisePrompted({
                context.applicationContext.getSharedPreferences("stopdash.location", Context.MODE_PRIVATE)
            }).also { instance = it }
        }
    }
}
