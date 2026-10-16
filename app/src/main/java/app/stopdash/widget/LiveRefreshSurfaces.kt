package app.stopdash.widget

import android.content.Context
import app.stopdash.StopdashDebugLog
import app.stopdash.watch.WatchReach
import app.stopdash.watch.watchReach
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long a watch has to say whether it's in reach before the minute updates go on without the answer. */
internal const val WATCH_REACH_BOUND_MILLIS = 5_000L

/** Why the minute updates wait with the setting on: nothing for one to update. */
internal const val NO_LIVE_REFRESH_SURFACE = "no widget placed and no watch in reach"

/**
 * Why the minute updates wait with the setting on: no widget, and the watch reachable only through
 * the cloud ([WatchReach.AWAY]), which nothing reports coming back near the phone.
 */
internal const val WATCH_AWAY = "no widget placed and the watch is away"

/** Why the minute updates wait with the setting on: no stops stored to refresh yet. */
internal const val NOTHING_STORED = "nothing stored to refresh"

/**
 * How far out the next tick is while the minute updates wait ([parkLiveRefresh]). Each check is a wake
 * and two local questions (a widget placed, a watch in reach), with no fetch, so a widget placed or a
 * watch back near the phone is updated within a few minutes even if nothing said so, for a fraction
 * of the minute's cost.
 */
internal const val LIVE_REFRESH_RECHECK_MILLIS = 5 * 60_000L

/**
 * Marks a tick parked [LIVE_REFRESH_RECHECK_MILLIS] out, so a resume (a widget placed, a watch asking)
 * replaces it with a minute tick rather than keeping it ([liveRefreshResumePolicy]).
 */
internal const val LIVE_REFRESH_PARKED_TAG = "live-refresh-parked"

/**
 * What the opt-in minute updates are for (SPEC D5): a StopDash widget placed on a home screen, or a
 * watch in reach with the StopDash watch app, which every refresh reaches through the watch sync (the
 * watch never fetches on its own). The chain fetches while either is there and waits when neither is:
 * a refresh with nothing to show it would fetch every stored stop each minute for no one (Codex P1 on
 * #56).
 */
internal object LiveRefreshSurfaces {
    private val placed: (Context) -> Boolean = { placedWidgetIds(it.applicationContext).isNotEmpty() }
    private val reach: suspend (Context) -> WatchReach = { watchReach(it) }

    /** Whether a StopDash widget is placed: a system call. Swappable in tests. */
    @Volatile
    internal var widgetPlaced: (Context) -> Boolean = placed

    /** Where a watch with the StopDash watch app is: asks Play services. Swappable in tests. */
    @Volatile
    internal var watchReach: suspend (Context) -> WatchReach = reach

    // Whether the last watch question went unanswered, so a run of them is logged once, and its end.
    @Volatile
    private var unanswered = false

    // Why the minute updates are waiting, or null while they go on, so a run of waits is logged once,
    // and its end.
    @Volatile
    private var waitingFor: String? = null

    /** The minute updates wait for [why]: said once for a run of the same reason ([parkLiveRefresh]). */
    internal fun waiting(why: String) {
        if (why != waitingFor) {
            StopdashDebugLog.info("widget: live refresh waiting: %s; checking every ${LIVE_REFRESH_RECHECK_MILLIS / 60_000} min", why)
        }
        waitingFor = why
    }

    /** A tick refreshed: said when that ends a wait. */
    internal fun updating() {
        if (waitingFor != null) StopdashDebugLog.info("widget: live refresh: every minute again")
        waitingFor = null
    }

    /**
     * Why there's nothing for a refresh to update, [NO_LIVE_REFRESH_SURFACE] or [WATCH_AWAY], or null
     * when there is: a widget placed, or a watch in reach. The widget is asked first, so a phone with one
     * never asks Play services. Asked on [io], since both are calls into the system.
     *
     * A watch question that fails, or isn't answered within [boundMillis], counts as a watch there,
     * logged: the rider turned the minute updates on knowing their cost, and a wrong "there" spends
     * no more than that until the next tick asks again, while a wrong "not there" would leave the watch
     * quietly falling behind for minutes at a time (SPEC principle 2).
     * A phone without the Wearable API answers "not there", not a failure ([watchReach]).
     */
    suspend fun whyNone(
        context: Context,
        io: CoroutineDispatcher = Dispatchers.IO,
        boundMillis: Long = WATCH_REACH_BOUND_MILLIS,
    ): String? = withContext(io) {
        if (widgetPlaced(context)) return@withContext null
        val watch = try {
            withTimeoutOrNull(boundMillis) { watchReach(context) } ?: return@withContext noAnswer("timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext noAnswer("failed: ${e::class.simpleName}")
        }
        if (unanswered) StopdashDebugLog.info("widget: live refresh: watch check answers again")
        unanswered = false
        when (watch) {
            WatchReach.NEARBY -> null
            WatchReach.AWAY -> WATCH_AWAY
            WatchReach.NONE -> NO_LIVE_REFRESH_SURFACE
        }
    }

    // A watch question with no answer: counted as a watch there (null), and logged once a run.
    private fun noAnswer(why: String): String? {
        if (!unanswered) logWidgetSnapshotWarning("live refresh: watch check $why; updating anyway")
        unanswered = true
        return null
    }

    /** Puts the system questions and the log state back, after a test swapped them. */
    internal fun reset() {
        widgetPlaced = placed
        watchReach = reach
        unanswered = false
        waitingFor = null
    }
}
