package app.stopdash.widget

import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What an empty widget last said about location: "Allow location in StopDash", or the usual
 * "Open StopDash" prompt. A grant given or taken away can come with nothing new to store (no stops
 * found, a failed lookup, an empty nearby set stored again), so nothing else would redraw it: the
 * app redraws whenever the grant it sees no longer matches what the empty widget says (Codex on
 * #732). Kept on disk because the grant usually changes in another process (Settings, which ends
 * the app's process on a revoke, or a fresh start). A widget showing departures records nothing.
 */
internal object WidgetLocationPrompt {
    private const val FILE = "widget_location_prompt"
    private const val DRAWN = "drawn"

    /** What an empty widget was last drawn saying. */
    internal enum class Drawn { NOT_EMPTY, ASKS_FOR_LOCATION, OPEN_THE_APP }

    /** Records what the widget just drew; written only when it changes. */
    suspend fun record(context: Context, drawn: Drawn, io: CoroutineDispatcher = Dispatchers.IO) {
        withContext(io) {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            if (prefs.getString(DRAWN, null) != drawn.name) prefs.edit().putString(DRAWN, drawn.name).apply()
        }
    }

    /** Redraws the widget if its empty-state prompt no longer matches [allowed]. */
    suspend fun redrawIfOutdated(context: Context, allowed: Boolean, io: CoroutineDispatcher = Dispatchers.IO) {
        val prefs = { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
        redrawIfOutdated(
            allowed = allowed,
            drawn = {
                val name = prefs().getString(DRAWN, null)
                Drawn.entries.firstOrNull { it.name == name } ?: Drawn.NOT_EMPTY
            },
            redraw = { redrawWidgets(context) },
            restore = { prefs().edit().putString(DRAWN, it.name).apply() },
            warn = ::logWidgetSnapshotWarning,
            io = io,
        )
    }

    /**
     * The redraw is best effort, since the widget is a secondary surface: a failure is logged. The
     * drawing records what it said before the update finishes, so a failed one puts back what was
     * there, and the next grant read tries again (Codex on #732).
     */
    internal suspend fun redrawIfOutdated(
        allowed: Boolean,
        drawn: () -> Drawn,
        redraw: suspend () -> Unit,
        restore: (Drawn) -> Unit = {},
        warn: (String) -> Unit = {},
        io: CoroutineDispatcher = Dispatchers.IO,
    ) {
        withContext(io) {
            val before = drawn()
            if (!outdated(before, allowed)) return@withContext
            try {
                redraw()
            } catch (e: CancellationException) {
                restore(before)
                throw e
            } catch (e: Exception) {
                restore(before)
                warn("redraw after a location grant change failed: ${e::class.simpleName}")
            }
        }
    }

    /** Whether an empty widget drawn saying [drawn] is wrong now that location is [allowed] or not. */
    internal fun outdated(drawn: Drawn, allowed: Boolean): Boolean = when (drawn) {
        Drawn.NOT_EMPTY -> false
        Drawn.ASKS_FOR_LOCATION -> allowed
        Drawn.OPEN_THE_APP -> !allowed
    }
}
