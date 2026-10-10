package app.stopdash.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Whether a StopDash widget is placed on a home screen, read on [io], or null when the host couldn't
 * be asked (logged): asking the widget host is a call
 * into the system, and the app asks on each return to the front (AGENTS.md *Main thread*). [ids] is
 * the host's answer, injectable so a test can check where it ran.
 */
internal suspend fun widgetPlaced(
    context: Context,
    io: CoroutineDispatcher = Dispatchers.IO,
    warn: (String) -> Unit = ::logWidgetSnapshotWarning,
    ids: suspend () -> Boolean = {
        GlanceAppWidgetManager(context.applicationContext).getGlanceIds(StopDashWidget::class.java).isNotEmpty()
    },
): Boolean? = withContext(io) {
    try {
        ids()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The card is optional: a host that can't be asked leaves it unknown, so it isn't shown.
        warn("widget presence read failed: ${e::class.simpleName}")
        null
    }
}

/**
 * Whether a StopDash widget is placed, as last known in this process: null until first known. Held for
 * the process rather than by a screen, so a rotation keeps it and the near-me card doesn't leave and
 * come back. Set by the widget receiver as the first widget is placed and the last removed, so a change
 * made while StopDash was in the background is known as it comes back; and asked again of the host
 * each time it comes to the front ([widgetPlaced]), for a process started since.
 */
object WidgetPresence {
    private val _placed = MutableStateFlow<Boolean?>(null)
    val placed: StateFlow<Boolean?> = _placed.asStateFlow()

    // Counts each answer set, so a host lookup started before a receiver event can't overwrite it.
    private val lock = Any()
    private var generation = 0L

    /** The answer as it happens: the receiver, as a widget is placed or removed. */
    fun set(placed: Boolean) {
        synchronized(lock) {
            generation++
            _placed.value = placed
        }
    }

    /**
     * This process, for a hold saved across recreation: a hold saved by a process since killed names
     * another one, so it isn't trusted before this process has read everything again (Codex on #735).
     */
    val process: Long = generateSequence { Random.nextLong() }.first { it != 0L }

    /** Whether a hold saved as [savedBy] (0 for none) was taken in this process. */
    fun heldHere(savedBy: Long): Boolean = savedBy != 0L && savedBy == process

    /**
     * Whether a card saved as held by [savedBy] still holds its place: taken in this process, and not
     * [gone]. A card that's gone stops holding in the same frame, before the saved hold is cleared, so
     * whatever waited behind it takes its place at once (Codex on #735).
     */
    fun holds(savedBy: Long, gone: Boolean): Boolean = heldHere(savedBy) && !gone

    /** The current generation, taken before asking the host, for [setIfUnchanged]. */
    fun generation(): Long = synchronized(lock) { generation }

    /** The host's answer, kept only if nothing newer was set since [since]; true when kept. */
    fun setIfUnchanged(placed: Boolean, since: Long): Boolean = synchronized(lock) {
        if (generation != since) return false
        generation++
        _placed.value = placed
        true
    }
}
