package app.stopdash.wear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import app.stopdash.data.WatchRefreshOutcome
import app.stopdash.data.WatchRefreshReply
import app.stopdash.data.WatchSyncContract
import com.google.android.gms.wearable.Wearable
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

/** Where the watch's last refresh request stands (dev-docs/wear-os.md *Refresh*). */
sealed interface RefreshState {
    data object Idle : RefreshState

    /** Asked at [since] as request [id]; no answer yet. */
    data class Pending(val since: Instant, val id: Long, val acked: Boolean = false) : RefreshState

    /** The phone answered [outcome] at [at]. */
    data class Answered(val outcome: WatchRefreshOutcome, val at: Instant) : RefreshState

    /**
     * No phone answered request [id] by [at]: it's out of reach, and the watch keeps its last
     * snapshot. A late answer to [id] still counts: the phone was only slow.
     */
    data class OutOfReach(val at: Instant, val id: Long) : RefreshState
}

/** A short line the watch shows about a refresh, until [until]. */
data class RefreshNotice(val kind: Kind, val until: Instant) {
    enum class Kind { REFRESHING, RATE_LIMITED, UNREACHABLE, KEY_REJECTED, PHONE_OUT_OF_REACH }
}

/** The Refresh line's label for [notice]: "Refresh" with none, else what the notice says. */
fun refreshLabel(notice: RefreshNotice.Kind?): Int = when (notice) {
    null -> R.string.watch_refresh
    RefreshNotice.Kind.REFRESHING -> R.string.watch_refreshing
    RefreshNotice.Kind.RATE_LIMITED -> R.string.watch_refresh_rate_limited
    RefreshNotice.Kind.UNREACHABLE -> R.string.watch_refresh_unreachable
    RefreshNotice.Kind.KEY_REJECTED -> R.string.watch_refresh_key_rejected
    RefreshNotice.Kind.PHONE_OUT_OF_REACH -> R.string.watch_phone_out_of_reach
}

/** The refresh rules, apart from the Data Layer so they're testable. */
object RefreshPolicy {
    /** At most one request this often, however often the user taps: never a polling loop. */
    val DEBOUNCE: Duration = Duration.ofSeconds(30)

    /** How long to wait for the phone's answer before saying it's out of reach. */
    // Longer than the phone's worst ordinary case: up to 20 s queued for the shared rate limit,
    // then the fetch itself. A slower answer still replaces the out-of-reach notice ([answer]).
    val TIMEOUT: Duration = Duration.ofSeconds(30)

    /**
     * How long to wait once the phone has acknowledged the request: it's in reach, but its work may
     * queue (WorkManager without expedited quota, or behind another refresh) before it answers.
     */
    val ACKED_TIMEOUT: Duration = Duration.ofMinutes(2)

    /** When [pending] stops waiting and reads as out of reach. */
    fun deadline(pending: RefreshState.Pending): Instant = pending.since.plus(if (pending.acked) ACKED_TIMEOUT else TIMEOUT)

    /** [state] once the phone acknowledged request [id], or null if that isn't the one waiting. */
    fun acknowledged(state: RefreshState, id: Long): RefreshState.Pending? =
        (state as? RefreshState.Pending)?.takeIf { it.id == id && !it.acked }?.copy(acked = true)

    /** How long a failure stays on screen over the last snapshot, which keeps its own age stamp. */
    val NOTICE_FOR: Duration = Duration.ofSeconds(60)

    fun shouldSend(lastSent: Instant?, now: Instant): Boolean =
        lastSent == null || now < lastSent || Duration.between(lastSent, now) >= DEBOUNCE

    /**
     * What to show for [state] from [now] on, in order, each until its own `until`: a waiting
     * request is "Refreshing…" to its timeout, then out of reach. The timeout is written in advance,
     * so a tile timeline shows it on time even if this process is gone by then. A successful or
     * debounced refresh needs no line: the snapshot it brings speaks for itself, and a partial one
     * is marked on its own.
     */
    fun notices(state: RefreshState, now: Instant): List<RefreshNotice> {
        val notices = when (state) {
            RefreshState.Idle -> emptyList()
            is RefreshState.Pending -> {
                val timeout = deadline(state)
                listOf(
                    RefreshNotice(RefreshNotice.Kind.REFRESHING, timeout),
                    RefreshNotice(RefreshNotice.Kind.PHONE_OUT_OF_REACH, timeout.plus(NOTICE_FOR)),
                )
            }
            is RefreshState.OutOfReach -> listOf(RefreshNotice(RefreshNotice.Kind.PHONE_OUT_OF_REACH, state.at.plus(NOTICE_FOR)))
            is RefreshState.Answered -> listOfNotNull(
                when (state.outcome) {
                    WatchRefreshOutcome.RATE_LIMITED -> RefreshNotice(RefreshNotice.Kind.RATE_LIMITED, state.at.plus(NOTICE_FOR))
                    WatchRefreshOutcome.UNREACHABLE -> RefreshNotice(RefreshNotice.Kind.UNREACHABLE, state.at.plus(NOTICE_FOR))
                    WatchRefreshOutcome.KEY_REJECTED -> RefreshNotice(RefreshNotice.Kind.KEY_REJECTED, state.at.plus(NOTICE_FOR))
                    else -> null
                },
            )
        }
        return notices.filter { now < it.until }
    }

    /** What to show for [state] at [now], or null: the first of [notices]. */
    fun notice(state: RefreshState, now: Instant): RefreshNotice? = notices(state, now).firstOrNull()

    /**
     * The state after [reply] arrives at [now] in [state], or null to ignore it: only an answer to
     * the latest request counts, so a late answer to an earlier one can't end a newer wait (whose
     * own timeout still stands). The latest's answer counts even after its timeout: a phone slowed
     * by the shared rate limit is not out of reach, and its answer replaces that notice.
     */
    fun answer(state: RefreshState, reply: WatchRefreshReply, now: Instant): RefreshState? {
        val latest = when (state) {
            is RefreshState.Pending -> state.id
            is RefreshState.OutOfReach -> state.id
            else -> return null
        }
        return if (latest == reply.requestId) RefreshState.Answered(reply.outcome, now) else null
    }

    /**
     * The state a new process resumes for the last request [id], sent at [since], as the old process
     * would have it at [now]: its [answer] if one came (so a failure's notice outlives the process
     * for its full minute), else still waiting, or timed out. A process killed while waiting still
     * takes the phone's answer, and a restart never forgets a request that went unanswered.
     */
    fun resumed(
        id: Long,
        since: Instant,
        now: Instant,
        answer: RefreshState.Answered? = null,
        acked: Boolean = false,
    ): RefreshState {
        answer?.let { return it }
        val pending = RefreshState.Pending(since, id, acked)
        val timeout = deadline(pending)
        return if (now < timeout && now >= since) pending else RefreshState.OutOfReach(timeout, id)
    }
}

/**
 * The watch's refresh requests: one message to the phone, debounced, answered by a
 * [WatchRefreshReply] ([onReply]) or timed out as out of reach. The request carries only a random id.
 * Every change asks the tile to re-render, so its Refresh line follows.
 */
object WatchRefresh {
    private const val TAG = "StopDash.Watch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val state: StateFlow<RefreshState> = _state.asStateFlow()

    private var lastSent: Instant? = null
    private var resumed = false

    // The last request and its answer, kept on disk, so a process killed while waiting, or before a
    // failure's notice has shown its minute, resumes it (RefreshPolicy.resumed): the answer, the
    // timeout, or the failure still reaches the screen.
    private const val PREFS = "watch_refresh"
    private const val KEY_ID = "pending_id"
    private const val KEY_SINCE = "pending_since"
    private const val KEY_ANSWER = "answer"
    private const val KEY_ANSWER_ID = "answer_id"
    private const val KEY_ANSWERED_AT = "answered_at"
    private const val KEY_ACKED = "acked"

    /**
     * Picks up where a killed process left off: its unanswered request, and the debounce with it.
     * Every entry point calls it first; it reads the disk once per process. Off the main thread.
     */
    fun resume(context: Context) {
        val appContext = context.applicationContext
        val waiting = synchronized(this) {
            if (resumed) return
            resumed = true
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_ID)) return
            val since = Instant.ofEpochMilli(prefs.getLong(KEY_SINCE, 0L))
            lastSent = since
            // An answer from a newer phone build this one can't read resumes as no answer at all.
            // An answer counts only for the request it was to (its own id alongside).
            val id = prefs.getLong(KEY_ID, 0L)
            val answer = WatchRefreshOutcome.entries.firstOrNull { it.name == prefs.getString(KEY_ANSWER, null) }
                ?.takeIf { prefs.contains(KEY_ANSWER_ID) && prefs.getLong(KEY_ANSWER_ID, 0L) == id }
                ?.let { RefreshState.Answered(it, Instant.ofEpochMilli(prefs.getLong(KEY_ANSWERED_AT, 0L))) }
            val state = RefreshPolicy.resumed(id, since, Instant.now(), answer, acked = prefs.getBoolean(KEY_ACKED, false))
            if (!_state.compareAndSet(RefreshState.Idle, state)) return
            state as? RefreshState.Pending
        }
        waiting?.let { timeOut(appContext, it) }
    }

    /** Asks the phone for a refresh, unless one was asked within [RefreshPolicy.DEBOUNCE]. */
    fun request(context: Context) {
        val appContext = context.applicationContext
        resume(appContext)
        val now = Instant.now()
        // State and disk change together, under the same lock as an answer's, so an answer to an
        // older request can never be written against this one.
        val pending = synchronized(this) {
            if (!RefreshPolicy.shouldSend(lastSent, now)) return
            lastSent = now
            RefreshState.Pending(now, Random.nextLong()).also { pending ->
                _state.value = pending
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putLong(KEY_ID, pending.id)
                    putLong(KEY_SINCE, now.toEpochMilli())
                    remove(KEY_ANSWER)
                    remove(KEY_ANSWER_ID)
                    remove(KEY_ANSWERED_AT)
                    remove(KEY_ACKED)
                }
            }
        }
        StopDashTileService.requestUpdate(appContext)
        scope.launch { send(appContext, pending.id) }
        timeOut(appContext, pending)
    }

    /** At [pending]'s deadline, if it's still waiting, the phone is out of reach. */
    private fun timeOut(context: Context, pending: RefreshState.Pending) {
        scope.launch {
            val left = Duration.between(Instant.now(), RefreshPolicy.deadline(pending))
            delay(left.toMillis().coerceAtLeast(0L))
            if (_state.compareAndSet(pending, RefreshState.OutOfReach(Instant.now(), pending.id))) {
                StopDashTileService.requestUpdate(context)
            }
        }
    }

    /**
     * The phone has request [id] and is working on it: it's in reach, so the wait stretches to
     * [RefreshPolicy.ACKED_TIMEOUT] (kept on disk too) while its refresh queues.
     */
    fun onAck(context: Context, id: Long) {
        val appContext = context.applicationContext
        resume(appContext)
        val acked = synchronized(this) {
            val acked = RefreshPolicy.acknowledged(_state.value, id) ?: return
            _state.value = acked
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_ID, 0L) == id) prefs.edit { putBoolean(KEY_ACKED, true) }
            acked
        }
        // The earlier timeout no longer matches the state, so it won't fire; this one replaces it.
        timeOut(appContext, acked)
        StopDashTileService.requestUpdate(appContext)
    }

    /** The phone's answer; one to a request no longer pending is ignored. */
    fun onReply(context: Context, reply: WatchRefreshReply) {
        resume(context)
        synchronized(this) {
            val answered = RefreshPolicy.answer(_state.value, reply, Instant.now()) as? RefreshState.Answered ?: return
            _state.value = answered
            // Kept with its request, and tagged with its id, so a restart shows it too; the lock
            // keeps a newer request from slipping in between the check above and this write.
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(KEY_ANSWER, answered.outcome.name)
                putLong(KEY_ANSWER_ID, reply.requestId)
                putLong(KEY_ANSWERED_AT, answered.at.toEpochMilli())
            }
        }
        StopDashTileService.requestUpdate(context.applicationContext)
    }

    private suspend fun send(context: Context, id: Long) {
        val sent = try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            nodes.count { node ->
                try {
                    Wearable.getMessageClient(context).sendMessage(node.id, WatchSyncContract.REFRESH_PATH, WatchRefreshReply.encodeRequest(id)).await()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "refresh request failed: ${e::class.simpleName}")
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "refresh request failed: ${e::class.simpleName}")
            0
        }
        // Nothing to send to: out of reach now, rather than after the timeout. Nothing went out, so
        // nothing is owed an answer: the request is forgotten (on disk too, so a restart doesn't
        // resume a wait for it) and the debounce released, so a phone back in reach is asked at once.
        if (sent == 0) {
            val changed = synchronized(this) {
                val current = _state.value
                if (current !is RefreshState.Pending || current.id != id) return@synchronized false
                _state.value = RefreshState.OutOfReach(Instant.now(), id)
                lastSent = null
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (prefs.getLong(KEY_ID, 0L) == id) {
                    prefs.edit {
                        remove(KEY_ID)
                        remove(KEY_SINCE)
                    }
                }
                true
            }
            if (changed) StopDashTileService.requestUpdate(context)
        }
    }
}
