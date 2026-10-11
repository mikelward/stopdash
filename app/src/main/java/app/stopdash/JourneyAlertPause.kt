package app.stopdash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Journey alerts paused by the rider (SPEC *Journeys → Alerts*; maintainer, 2026-10-11): Pause on an
 * alert stops them all, for every journey, until Unpause on the near-me list's card. While paused no
 * check is scheduled and none is shown, as while Android won't show them; the saved schedules are
 * left as they are, so Unpause picks up where they stand.
 *
 * Kept on the device with the checks' own state ([JourneyAlertState]'s file, not backed up): a pause
 * is about this phone now, not a setting to carry to the next one.
 */
internal object JourneyAlertPause {
    private const val FILE = "journey-alerts-state"
    private const val PAUSED = "paused"

    private val _paused = MutableStateFlow<Boolean?>(null)

    // Pause and Unpause run one at a time: an Unpause tapped while a Pause is still taking alerts down and
    // re-arming would otherwise have the alert its check posts taken down, and its check-now re-arm replaced by
    // the paused one (Codex on #756).
    internal val changes = Mutex()

    // Held while an alert is gated and posted, and while a Pause takes alerts down, so no post lands after the
    // takedown (Codex on #756). A plain monitor: both sides are short and blocking, and posting isn't suspending.
    internal val posting = Any()

    /**
     * Whether alerts are paused, for the near-me card; null until first read ([load]). It is also the one
     * value every gate reads ([isPaused]): read from disk once, then changed only by a [set] whose commit
     * succeeded. SharedPreferences puts a value in its in-memory map before the disk write can fail, so a
     * gate reading the preference itself could act on a pause or unpause that never happened, and putting
     * the old value back afterwards can't take that back (Codex on #756). Reading this instead, nothing
     * ever sees a value that wasn't saved.
     */
    val paused: StateFlow<Boolean?> = _paused.asStateFlow()

    // Guards the first read from disk against a [set] in progress, so that read can't catch a commit's
    // value before the commit is known to have succeeded.
    private val stored = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether alerts are paused: for the checks and the posting gate, which run off the main thread. */
    @WorkerThread
    fun isPaused(
        context: Context,
        // Where the pause is kept; a test passes its own.
        store: SharedPreferences = prefs(context),
    ): Boolean = _paused.value ?: synchronized(stored) {
        _paused.value ?: store.getBoolean(PAUSED, false).also { _paused.value = it }
    }

    /** Reads whether alerts are paused into [paused], on [io]. */
    suspend fun load(context: Context, io: CoroutineDispatcher = Dispatchers.IO): Boolean = withContext(io) { isPaused(context) }

    /**
     * Pauses or unpauses alerts, on [io]; true when written. Committed, not applied: the notification's
     * receiver may finish, and its process go, straight after. [paused] changes only once the commit has
     * succeeded. A write that fails leaves the pause as it was, and puts SharedPreferences' in-memory copy
     * back as well, which a failed commit has already changed, so a restart reads what the gates read.
     */
    suspend fun set(
        context: Context,
        paused: Boolean,
        io: CoroutineDispatcher = Dispatchers.IO,
        // Where the pause is kept; a test passes its own.
        store: SharedPreferences = prefs(context),
    ): Boolean = withContext(io) {
        val written = synchronized(stored) {
            val before = _paused.value ?: store.getBoolean(PAUSED, false)
            val committed = store.edit().putBoolean(PAUSED, paused).commit()
            // The disk still holds [before], so the in-memory copy alone needs putting back: applied, not committed.
            _paused.value = if (committed) paused else before.also { store.edit { putBoolean(PAUSED, it) } }
            committed
        }
        logJourneyAlertWarning(
            when {
                written && paused -> "paused by the rider"
                written -> "unpaused by the rider"
                paused -> "pause not saved"
                else -> "unpause not saved"
            },
        )
        written
    }
}

/**
 * Unpauses alerts for the near-me card's Unpause; true when unpaused. A write that fails, or throws, leaves
 * them paused and is logged, so the caller can say so and the rider can try again (Codex on #756).
 */
internal suspend fun JourneyAlertPause.unpause(
    context: Context,
    io: CoroutineDispatcher = Dispatchers.IO,
    write: suspend () -> Boolean = { set(context, paused = false, io = io) },
): Boolean = try {
    changes.withLock { write() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logJourneyAlertWarning("unpause failed: ${e::class.simpleName}")
    false
}

/**
 * The Pause button on a journey alert: pauses every journey's alerts ([JourneyAlertPause]), takes down
 * those showing, and stops the checks, until Unpause on the near-me list.
 */
class JourneyAlertPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val pending: PendingResult? = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                pause(app)
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "app.stopdash.action.PAUSE_JOURNEY_ALERTS"

        // Where the pause runs; a receiver has no scope of its own.
        private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Pauses alerts and takes down every one showing, then re-arms the checks as paused (none); true when
         * paused. Never throws but for cancellation: each step's failure is contained and logged (Codex on
         * #756 and #757), so neither the receiver nor the menu has a throw to crash on.
         *
         * A pause that couldn't be saved (a false return or a throw) changes nothing, the alerts staying up, so
         * what shows is what holds: taking them down then would look paused while the next check posts again.
         * Once saved it holds whatever follows: an alert that couldn't be taken down, or a resync that failed,
         * is logged, and the next sync (the app's start, or a return to it) stops the checks, the pause
         * already blocking any new alert.
         */
        internal suspend fun pause(
            context: Context,
            io: CoroutineDispatcher = Dispatchers.IO,
            // Saves the pause; a test passes its own.
            save: suspend () -> Boolean = { JourneyAlertPause.set(context, paused = true, io = io) },
            // Takes down the alerts showing; a test passes its own.
            takeDown: suspend () -> Unit = {
                withContext(io) {
                    synchronized(JourneyAlertPause.posting) {
                        JourneyAlertNotification.showing(context).forEach { JourneyAlertNotification.cancel(context, it) }
                    }
                }
            },
            // Re-arms the checks as paused; a test passes its own.
            resync: suspend () -> Unit = { JourneyAlertChecks.resync(context, io = io, now = java.time.Instant.now(), checkNowIfActive = false) },
        ): Boolean = JourneyAlertPause.changes.withLock {
            if (contained("pause failed") { save() } != true) return@withLock false
            contained("taking alerts down after pausing failed") { takeDown() }
            contained("resync after pausing failed") { resync() }
            true
        }

        // Runs [step], logging and swallowing any failure but cancellation as "[what]: <type>"; null on failure.
        private suspend fun <T> contained(what: String, step: suspend () -> T): T? = try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logJourneyAlertWarning("$what: ${e::class.simpleName}")
            null
        }
    }
}
