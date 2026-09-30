package app.stopdash

import android.content.Context
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.ProcessExits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Records why this app's recent processes ended, through androidlog's shared [ProcessExits]:
 * the package's install and update times, then the last few exits oldest first, as pinned lines
 * the ring can't push out before a report is shared.
 *
 * An uncaught exception is the only death the app sees from the inside, and the file sink's
 * crash handler already keeps it. An ANR, a native crash, a low-memory kill or an OEM's standby
 * kill leave nothing, so the next run's log just restarts, as it does after a clean exit. That
 * is the case for a widget or on-the-way card that stopped updating with nothing to say why.
 *
 * Coarse fields only: the reason, the system's importance, the exit status and the time. The
 * platform's free-text description is left out (`TODO.md`, `docs/PRIVACY.md`): it is written by
 * the system and can name another app.
 */
internal fun logRecentProcessExits(context: Context, log: DebugLog = StopdashDebugLog) {
    ProcessExits.logRecent(context, log)
}

/**
 * Runs [collect] on [dispatcher] in [scope], since the query is two binder calls and must stay off
 * `Application.onCreate`'s thread. A failure is logged rather than lost: a missing section would
 * read like a query that was never wired up.
 */
internal fun logRecentProcessExitsInBackground(
    scope: CoroutineScope,
    log: DebugLog = StopdashDebugLog,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    collect: () -> Unit,
): Job = scope.launch(dispatcher) {
    try {
        collect()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // ProcessExits contains its own query failures, so this is what it didn't expect.
        log.failure(e, "processExits collection failed")
    }
}
