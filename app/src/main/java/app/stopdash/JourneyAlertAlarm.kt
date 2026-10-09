package app.stopdash

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import app.stopdash.domain.JourneyAlertLog
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * The inexact alarms that start each journey alert check on time (SPEC *Journeys → Alerts*). A deferred
 * WorkManager job alone was held half an hour and more while the app was off screen, phone in use and
 * battery "Optimized". Two alarms, neither needing a permission, each firing a broadcast that swaps the
 * pending delayed job for the same check expedited: one with a 10-minute window ([AlarmManager.setWindow];
 * Android 12+'s shortest), so it's at most that late while the phone is awake, where a plain inexact alarm
 * set hours ahead may come up to an hour late (Codex on #716); and one allowed while idle
 * ([AlarmManager.setAndAllowWhileIdle]), which Doze delivers, at most once per ~9 minutes. Whichever comes
 * first starts the check; the other then finds it started and does nothing. Each arming replaces both.
 */
internal object JourneyAlertAlarm {
    internal const val EXTRA_DUE = "due"
    internal const val EXTRA_ASKS = "asks"

    /** The shortest window Android 12+ honors for an alarm with one: a shorter one is widened to it. */
    internal val WINDOW: Duration = Duration.ofMinutes(10)

    private const val WINDOWED = 0
    private const val WHILE_IDLE = 1

    private fun intent(context: Context, code: Int, due: Instant? = null, asks: Boolean = false): PendingIntent {
        val intent = Intent(context, JourneyAlertAlarmReceiver::class.java)
        if (due != null) intent.putExtra(EXTRA_DUE, due.toEpochMilli()).putExtra(EXTRA_ASKS, asks)
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Arms the alarms [delay] from now for the check due at [due]. Off the main thread: it's an IPC. */
    fun arm(context: Context, due: Instant, asks: Boolean, delay: Duration) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (alarms == null) {
            logJourneyAlertWarning("no alarm service: check left deferred")
            return
        }
        // Elapsed time, so a clock set doesn't move it; the clock receiver re-times the check anyway.
        val at = SystemClock.elapsedRealtime() + delay.toMillis()
        alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, WINDOW.toMillis(), intent(context, WINDOWED, due, asks))
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent(context, WHILE_IDLE, due, asks))
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(intent(context, WINDOWED))
        alarms.cancel(intent(context, WHILE_IDLE))
    }

    /**
     * What the alarm for [due] does: swaps the check still waiting for that moment for the same check
     * expedited, so it runs now. A check re-timed since (a newer sync), already started by the other alarm,
     * running or already run is left alone. Logs how late the alarm fired.
     */
    // Blocking reads: called off the main thread (the receiver's IO scope).
    // Under the schedule's own lock: a sync re-timing the check between the read and the swap would otherwise
    // be overwritten by this one's older request, its new alarms cancelled with it (Codex on #716).
    suspend fun fire(context: Context, due: Instant, asks: Boolean, now: Instant = Instant.now()): Unit =
        JourneyAlertChecks.syncLock.withLock { fireLocked(context, due, asks, now) }

    private suspend fun fireLocked(context: Context, due: Instant, asks: Boolean, now: Instant) {
        val work = WorkManager.getInstance(context)
        val waiting = work.getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get()
            // Still delayed: one with none is the other alarm's, already started.
            .any { it.state == WorkInfo.State.ENQUEUED && JourneyAlertChecks.dueTag(due) in it.tags && it.initialDelayMillis > 0L }
        val late = Duration.between(due, now).coerceAtLeast(Duration.ZERO).seconds
        if (!waiting) {
            logJourneyAlertWarning(JourneyAlertLog.alarm(late, started = false))
            return
        }
        work.enqueueUniqueWork(
            JOURNEY_ALERTS_WORK,
            ExistingWorkPolicy.REPLACE,
            JourneyAlertChecks.checkRequest(due, asks, ZoneId.systemDefault(), Duration.ZERO),
        ).await()
        // Its sibling has nothing left to do: taken down, so it doesn't wake the phone just to find that out
        // (the check may yet wait for a network, so its own re-arm can be a while coming; Codex on #716).
        cancel(context)
        logJourneyAlertWarning(JourneyAlertLog.alarm(late, started = true))
    }
}

/** Hears [JourneyAlertAlarm]'s alarm and starts the check it was for. */
class JourneyAlertAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val due = intent.getLongExtra(JourneyAlertAlarm.EXTRA_DUE, 0L).takeIf { it > 0L } ?: return
        val asks = intent.getBooleanExtra(JourneyAlertAlarm.EXTRA_ASKS, true)
        val pending: PendingResult? = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                JourneyAlertAlarm.fire(app, Instant.ofEpochMilli(due), asks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The delayed job is still pending, so the check still runs, only later.
                logJourneyAlertWarning("alarm couldn't start the check: ${e::class.simpleName}")
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        // Where the work runs; a receiver has no scope of its own.
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
