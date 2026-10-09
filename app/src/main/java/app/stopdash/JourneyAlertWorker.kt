package app.stopdash

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import app.stopdash.data.DataStoreFavoriteJourneysStore
import app.stopdash.data.FavoriteJourneysWrites
import app.stopdash.data.DataStoreSnapshotStore
import app.stopdash.data.KtorTflClient
import app.stopdash.data.RejectedApiKey
import app.stopdash.data.SharedTflRateLimiter
import app.stopdash.data.SharedTflRequestPool
import app.stopdash.data.logNetworkWarning
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.FavoriteJourneysStore
import app.stopdash.domain.JourneyAlertAction
import app.stopdash.domain.JourneyAlertLog
import app.stopdash.domain.JourneyAlertResult
import app.stopdash.domain.JourneyAlertSchedule
import app.stopdash.domain.JourneyAlerts
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusBatch
import app.stopdash.domain.Workers
import app.stopdash.ui.WidgetJourneysWrites
import app.stopdash.widget.readRefreshKeys
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Unique-work name for the next journey alert check: at most one is ever pending. */
internal const val JOURNEY_ALERTS_WORK = "stopdash-journey-alerts"

// A check at the close of the window the next one runs in, needing no network: that one may be held
// for a network past the close, and taking an alert down, or forgetting its swipe, needs none (Codex
// on #700).
internal const val JOURNEY_ALERTS_CLOSE_WORK = "stopdash-journey-alerts-close"

internal fun logJourneyAlertWarning(message: String) = StopdashDebugLog.warning("journey alerts: %s", message)

/**
 * Schedules the background check behind journey alerts (SPEC *Journeys → Alerts*): one deferrable
 * [OneTimeWorkRequest] at a time, timed from the saved schedules ([JourneyAlerts.nextCheck]), every
 * quarter hour inside a window and none outside one. Nothing runs while no direction is watched, or
 * while notifications are off, since every alert would be invisible (the run would cost battery and
 * TfL requests for nothing).
 */
internal object JourneyAlertChecks {
    // Before each try at scheduling: at once, then after a few seconds, a minute, and five.
    private val SYNC_RETRIES: List<Long> = listOf(0L, 5_000L, 60_000L, 300_000L)

    private const val RECOLLECT_AFTER_MILLIS = 60_000L

    /**
     * Follows the saved journeys and schedules in [scope] for the process's life, rescheduling (or
     * canceling) the check on every change, so an edit in Settings takes effect at once and an app
     * start re-arms it. Unreadable stores leave what's scheduled alone.
     */
    fun start(
        context: Context,
        scope: CoroutineScope,
        store: FavoriteJourneysStore = DataStoreFavoriteJourneysStore.from(context, warn = ::logJourneyAlertWarning),
        // Where the stored lists are combined and compared, work that grows with what's saved, never
        // on [scope]'s main thread (AGENTS.md *Main thread*).
        compute: CoroutineDispatcher = Workers.compute,
    ) {
        val app = context.applicationContext
        scope.launch {
            // The stores' flows end after a read failure (they say so with null first); collected again a
            // minute later, so a passing disk error doesn't leave the check unarmed for the process's life
            // (Codex on #700). A healthy store's flow never ends.
            while (true) {
                combine(store.journeys(), store.alertSchedules()) { journeys, schedules -> journeys to schedules }
                    .distinctUntilChanged()
                    .flowOn(compute)
                    .collectLatest { (journeys, schedules) ->
                        // At once while a window is open: a direction just turned on, or an app start
                        // mid-window, shouldn't wait a quarter hour for its first check. A failure (WorkManager's
                        // database unavailable, say) is tried again, a little later each time, since nothing else
                        // re-arms an unchanged schedule (Codex on #700); a newer schedule replaces the attempts.
                        // Past the last, it keeps trying at the last's pace: a healthy flow emits nothing more,
                        // so giving up would leave the schedule unarmed for the process's life (Codex on #700).
                        var attempt = 0
                        while (true) {
                            delay(SYNC_RETRIES.getOrElse(attempt) { SYNC_RETRIES.last() })
                            try {
                                sync(app, journeys, schedules, checkNowIfActive = true)
                                break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logJourneyAlertWarning("scheduling failed (try ${attempt + 1}): ${e::class.simpleName}")
                            }
                            attempt++
                        }
                    }
                delay(RECOLLECT_AFTER_MILLIS)
            }
        }
    }

    /**
     * What a post for a journey was made for: its open directions (their origins' stop ids), whose lines
     * it speaks of, when it times out (the first of their windows to close), and the journey's saved
     * line: one removed and saved again on another line is a different journey to the check that asked
     * about the old one (Codex on #700).
     */
    data class Watch(val directions: Set<String>, val until: Instant?, val lineId: String = "") {
        /** How an alert made for this names it ([JourneyAlertResult.scope]). */
        val scope: String? get() = JourneyAlertResult.scope(directions, until)
    }

    // What the latest sync found watched, each journey as a [Watch], and a count of syncs, for
    // [applying]. In memory: the check runs in this process, as WorkManager runs it.
    @Volatile
    private var lastWatched: Map<String, Watch> = emptyMap()

    // The settings the latest sync was made from, so a close check can tell whether it read the same
    // ones as everything else (Codex on #700).
    @Volatile
    private var lastSettings: Pair<List<FavoriteJourney>, Map<String, JourneyAlertSchedule>>? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()

    /** Where syncs have got to, to pass to [applying] later. */
    fun syncs(): Long = generation.get()

    /** Each journey watched at [at], with what a post for it then would be made for. */
    @WorkerThread
    internal fun watches(journeys: List<FavoriteJourney>, schedules: Map<String, JourneyAlertSchedule>, at: java.time.ZonedDateTime): Map<String, Watch> {
        val directions = JourneyAlerts.directionsOf(journeys, schedules, at)
        return journeys.filter { directions[it.key].orEmpty().isNotEmpty() }
            .associate { it.key to Watch(directions.getValue(it.key), JourneyAlerts.watchedUntil(it, schedules, at)?.toInstant(), it.lineId) }
    }

    /**
     * The country of the mobile network the phone's data SIM is registered on ([JourneyAlerts.registeredCountry]),
     * or null when it can't say (Wi-Fi only, no SIM, airplane mode, or only a nearby cell's country). Needs no
     * permission and makes no request; only ever compared with the UK on the device ([JourneyAlerts.abroad]), never kept, logged or sent.
     */
    internal fun networkCountry(context: Context): String? = try {
        // The SIM carrying mobile data now, not the default (voice) one, nor the default data one where
        // Android has switched data to another: a traveler keeping a UK SIM for calls and a local eSIM for
        // data is abroad (Codex on #712). None carrying data is no answer.
        val data = android.telephony.SubscriptionManager.getActiveDataSubscriptionId()
        context.getSystemService(android.telephony.TelephonyManager::class.java)
            ?.takeIf { data != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            ?.createForSubscriptionId(data)
            ?.let { phone ->
                JourneyAlerts.registeredCountry(
                    phone.networkCountryIso,
                    simReady = phone.simState == android.telephony.TelephonyManager.SIM_STATE_READY,
                    networkOperator = phone.networkOperator,
                )
            }
    } catch (e: RuntimeException) {
        // Some builds throw rather than answer with no network; unknown, so alerts fire as before.
        logJourneyAlertWarning("network country unreadable: ${e::class.simpleName}")
        null
    }

    /**
     * Whether a journey is watched at [at] for directions other than those the check [asked] about: a
     * direction opened since, while the check waited, which only a check run at once will cover.
     */
    internal fun unaskedDirections(
        asked: Map<String, Set<String>>,
        journeys: List<FavoriteJourney>,
        schedules: Map<String, JourneyAlertSchedule>,
        at: java.time.ZonedDateTime,
    ): Boolean {
        val watched = watches(journeys, schedules, at)
        return watched.any { (key, watch) -> asked[key] != watch.directions }
    }

    /**
     * The statuses [request] returns for [lineIds], asked one group at a time ([LineStatusBatch]): a group
     * that fails leaves only its lines unanswered, keeping what the others returned (Codex on #700). Null
     * when nothing was answered; failures logged by count only (SPEC *Privacy*).
     */
    internal suspend fun answered(lineIds: Set<String>, request: suspend (List<String>) -> List<LineStatus>): List<LineStatus>? {
        val results = LineStatusBatch.request(lineIds, request)
        results.failure?.let { logJourneyAlertWarning("line status failed for ${results.failed.size} line(s): ${it::class.simpleName}") }
        if (results.unknown.isNotEmpty()) logJourneyAlertWarning("TfL doesn't know ${results.unknown.size} line(s)")
        return if (results.anyAnswered) results.answers.flatMap { it.value } else null
    }

    private fun zoneTag(zone: java.time.ZoneId): String = "journey-alerts-zone:${zone.id}"

    private const val CLOCK_TAG = "journey-alerts-clock:"

    // The wall-clock minute the elapsed-time count started from. Steady while the clock is left alone
    // (a minute or two of network time correction aside); moved by setting it, and by a reboot, after
    // which a resync is harmless. Swappable in tests.
    @Volatile
    internal var clockMinute: () -> Long = { (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000 }

    // Whether alerts could show at the last sync, to notice them coming back on (or going off) in
    // Android's settings, which changes neither store.
    @Volatile
    private var lastCanAlert: Boolean? = null

    /**
     * Reads the stores afresh and syncs from them. Nothing changes while they can't be read, and that
     * throws [java.io.IOException], so the caller can retry or log it rather than take it for success.
     */
    suspend fun resync(
        context: Context,
        store: FavoriteJourneysStore = DataStoreFavoriteJourneysStore.from(context, warn = ::logJourneyAlertWarning),
        io: CoroutineDispatcher = Dispatchers.IO,
        now: Instant = Instant.now(),
        checkNowIfActive: Boolean = false,
    ) = withContext(io) {
        // Read under the lock: a snapshot read outside it could be overtaken by a newer edit's sync and then
        // applied over it, re-timing (or canceling) the check from settings already replaced (Codex on #700).
        syncLock.withLock {
            // Unreadable is a failure, not "nothing to do": the stores say so with null rather than throwing,
            // and a caller that retries (a clock change) must see it to retry (Codex on #700).
            val journeys = store.journeys().first() ?: throw java.io.IOException("journeys unreadable")
            val schedules = store.alertSchedules().first() ?: throw java.io.IOException("schedules unreadable")
            syncLocked(context, journeys, schedules, now, checkNowIfActive)
        }
    }

    /**
     * Called each time the app comes to the foreground, so whatever re-arming a check needs happens
     * wherever the rider returns (Codex on #700): when alerts have come back on (or gone off) in
     * Android's settings since the last sync, or when no check is pending at all (a read or schedule
     * that failed earlier), it syncs again from the stores, checking at once if a window is open. A
     * pending check is left alone, so using the app mid-window never pushes it back, unless a clock
     * change left it stale ([JourneyAlertState]). Off the main
     * thread, the hop first.
     */
    suspend fun onForeground(
        context: Context,
        store: FavoriteJourneysStore = DataStoreFavoriteJourneysStore.from(context, warn = ::logJourneyAlertWarning),
        io: CoroutineDispatcher = Dispatchers.IO,
        now: () -> Instant = Instant::now,
    ): Unit = withContext(io) {
        val can = JourneyAlertNotification.canAlert(context)
        val flipped = lastCanAlert != null && lastCanAlert != can
        val waiting = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get()
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        val pending = waiting.isNotEmpty()
        // A pending check timed for an old clock: one whose resync failed after a clock change (the
        // stale mark), or one timed in another time zone, read off the check itself so it holds even
        // when nothing else could be written (Codex on #700).
        val zoneTag = zoneTag(java.time.ZoneId.systemDefault())
        val clock = clockMinute()
        val stale = JourneyAlertState.stale(context) || waiting.any { work ->
            zoneTag !in work.tags || work.tags.none { tag -> tag.removePrefix(CLOCK_TAG).toLongOrNull()?.let { abs(it - clock) <= 2 } == true }
        }
        if (!flipped && !stale && (pending || !can)) return@withContext
        resync(context, store, io, now(), checkNowIfActive = can)
    }

    /**
     * Arms the next check for [journeys] and [schedules] as of [now], or cancels it when nothing is
     * watched or notifications are off; and takes down any alert whose direction is no longer watched
     * now, since no check may come to clear it. With [checkNowIfActive], a window open now is checked
     * at once rather than a [JourneyAlerts.CHECK_INTERVAL] on. Null inputs (unreadable) change nothing.
     */
    suspend fun sync(
        context: Context,
        journeys: List<FavoriteJourney>?,
        schedules: Map<String, JourneyAlertSchedule>?,
        now: Instant = Instant.now(),
        checkNowIfActive: Boolean = false,
        // The hop first (AGENTS.md *Main thread*): its callers start on the main thread, and it reads the
        // announced alerts from disk and asks the notification manager what's up.
        io: CoroutineDispatcher = Dispatchers.IO,
    ): Unit = withContext(io) { syncLock.withLock { syncLocked(context, journeys, schedules, now, checkNowIfActive) } }

    /**
     * Carries out a check's decisions with no sync able to run meanwhile, then times the next check
     * from [journeys] and [schedules], the settings the check read — unless a sync has run since
     * [since] (a [syncs] reading): that one timed it from newer settings (Codex on #700).
     *
     * [apply] is handed `unchanged(key, watch)`: whether [key]'s journey is still watched just as
     * [watch] (null: not watched) says, which is so if no sync has run since [since] or the latest
     * found it the same way — the same directions, whose lines the alert speaks of, and the same
     * close, its timeout. A decision on a journey the rider changed since is skipped, post or clear
     * alike, and holding off the syncs while deciding means no clean-up can land between the check
     * and its post: the race is closed by ordering, not by taking posts back down (Codex on #700).
     */
    internal suspend fun <T> applying(
        since: Long,
        context: Context,
        journeys: List<FavoriteJourney>,
        schedules: Map<String, JourneyAlertSchedule>,
        now: () -> Instant = { Instant.now() },
        io: CoroutineDispatcher = Dispatchers.IO,
        closeOnly: Boolean = false,
        // A direction watched now that the check didn't ask about (its window opened while the request was
        // out): the next check runs at once rather than a quarter hour on (Codex on #700).
        checkNow: () -> Boolean = { false },
        // The settings as stored now, read under the lock: an edit saved after the check read its own but
        // not yet synced by the store's watcher (the process may stop first) still counts as a change, so
        // the check decides nothing from settings it overtook (Codex on #700).
        latest: (suspend () -> Pair<List<FavoriteJourney>?, Map<String, JourneyAlertSchedule>?>)? = null,
        // The settings' write lock, held from [latest]'s read through [apply]: an edit saved after that read
        // would otherwise still be overtaken by the check's posts (Codex on #700). Order: this lock inside
        // the sync lock; the store's writers take only this one.
        settingsWrites: Mutex? = null,
        apply: suspend (unchanged: (String, Watch?) -> Boolean) -> T,
    ): T = withContext(io) {
        syncLock.withLock { settingsWrites.lockedIfAny {
            val stored = latest?.invoke()
            // Synced as the store's watcher would sync an edit: a window open now is checked at once, since
            // this sync takes the re-arm over from the check's own (Codex on #700).
            if (stored != null && stored != (journeys to schedules)) {
                syncLocked(context, stored.first, stored.second, now(), checkNowIfActive = true)
            }
            val unchanged = { key: String, watch: Watch? -> generation.get() == since || lastWatched[key] == watch }
            val result = apply(unchanged)
            if (generation.get() == since) syncLocked(context, journeys, schedules, now(), checkNowIfActive = !closeOnly && checkNow(), closeOnly = closeOnly)
            result
        } }
    }

    private suspend fun <R> Mutex?.lockedIfAny(block: suspend () -> R): R = if (this == null) block() else withLock { block() }

    // One sync at a time, so a sync from newer settings is never overtaken by one from older ones.
    private val syncLock = Mutex()

    private suspend fun syncLocked(
        context: Context,
        journeys: List<FavoriteJourney>?,
        schedules: Map<String, JourneyAlertSchedule>?,
        now: Instant,
        checkNowIfActive: Boolean,
        // From the close check: it times only the next close check, never the main one, which may be
        // running or waiting for a network as it is (Codex on #700).
        closeOnly: Boolean = false,
    ) {
        if (journeys == null || schedules == null) return
        // A close check that read settings other than the latest sync's (an edit not yet synced) syncs in
        // full instead: the change is a change, whoever reads it first, and the main check is re-timed from
        // it rather than left to re-arm from what it read before (Codex on #700).
        val settings = journeys to schedules
        val closeOnly = closeOnly && settings == lastSettings
        lastSettings = settings
        val at = JourneyAlerts.at(now)
        val canAlert = JourneyAlertNotification.canAlert(context)
        lastCanAlert = canAlert
        // While alerts can't show, nothing counts as announced: Android has taken them down, and one
        // still under way is posted again once they can show (Codex on #700).
        val openNow = if (canAlert) watches(journeys, schedules, at) else emptyMap()
        val watchedNow = openNow.keys
        // What a check deciding after this sync compares its own reading against ([applying]).
        lastWatched = openNow
        // A close check's sync, from the same settings the latest sync had, times nothing a running check
        // would: it doesn't count as a change, so that check still re-arms itself after (Codex on #700).
        if (!closeOnly) generation.incrementAndGet()
        JourneyAlertNotification.clearExcept(context, openNow.mapValues { (_, watch) -> watch.scope })
        val work = WorkManager.getInstance(context)
        if (closeOnly) {
            armClose(work, if (canAlert) JourneyAlerts.closesAfter(journeys, schedules, at) else null, now)
            return
        }
        val next = JourneyAlerts.nextCheck(journeys, schedules, at)
        if (next == null || !canAlert) {
            // Said either way: a report with no alert to explain needs to show whether one was ever armed.
            logJourneyAlertWarning(JourneyAlertLog.notScheduled(watched = next != null))
            work.cancelUniqueWork(JOURNEY_ALERTS_WORK).await()
            work.cancelUniqueWork(JOURNEY_ALERTS_CLOSE_WORK).await()
            JourneyAlertState.clearStale(context)
            return
        }
        val due = if (checkNowIfActive && watchedNow.isNotEmpty()) now else next.toInstant()
        val delay = Duration.between(now, due).coerceAtLeast(Duration.ZERO)
        // Whether that check will ask TfL anything: only when a window is open then.
        val asks = JourneyAlerts.active(journeys, schedules, JourneyAlerts.at(due)).isNotEmpty()
        work.enqueueUniqueWork(
            JOURNEY_ALERTS_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<JourneyAlertWorker>()
                // The zone it was timed in, so a return to the app can tell a check left behind by a
                // time-zone change without anything else having been written.
                .addTag(zoneTag(at.zone))
                // And the wall clock it was timed by, as the moment the device's elapsed-time count read
                // zero: setting the clock moves that moment, so a return to the app tells a manual clock
                // change from the pending check itself, too (Codex on #700).
                .addTag(CLOCK_TAG + clockMinute())
                // When it's due, so it can log how late it ran: Android defers it in Doze.
                .setInputData(androidx.work.workDataOf(DUE to due.toEpochMilli()))
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                // Offline, a check inside a window waits for a network rather than fail and say nothing; one
                // that only closes a window runs anyway, since taking an alert down needs none (Codex on #700).
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (asks) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build())
                .build(),
        ).await()
        logJourneyAlertWarning(JourneyAlertLog.scheduled(now, due, asks, at.zone))
        // The first close from now on, among windows open now or when the check is due: one closing
        // before or at that check while another stays open must not wait for the later close (Codex on #700).
        val closes = if (asks) listOfNotNull(JourneyAlerts.closesAfter(journeys, schedules, at), JourneyAlerts.closesAfter(journeys, schedules, JourneyAlerts.at(due))).minOrNull() else null
        armClose(work, closes, now)
        // Timed from the clock as it reads now, so whatever a clock change left stale is put right.
        JourneyAlertState.clearStale(context)
    }

    // The close check at [closes], or none. It asks TfL nothing ([CLOSE_ONLY]): it only takes down what
    // the closing window showed and forgets its swipe, so it needs no network and adds no request.
    private suspend fun armClose(work: WorkManager, closes: java.time.ZonedDateTime?, now: Instant) {
        if (closes == null) {
            work.cancelUniqueWork(JOURNEY_ALERTS_CLOSE_WORK).await()
            return
        }
        work.enqueueUniqueWork(
            JOURNEY_ALERTS_CLOSE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<JourneyAlertWorker>()
                .setInputData(androidx.work.workDataOf(CLOSE_ONLY to true, DUE to closes.toInstant().toEpochMilli()))
                .setInitialDelay(Duration.between(now, closes.toInstant()).coerceAtLeast(Duration.ZERO).toMillis(), TimeUnit.MILLISECONDS)
                .build(),
        ).await()
        logJourneyAlertWarning(JourneyAlertLog.closeScheduled(now, closes.toInstant(), closes.zone))
    }

    /** Input marking a close check: clean-up only, no request (Codex on #700). */
    internal const val CLOSE_ONLY = "close_only"

    /** Input: when a check was due, in epoch millis, for its log line. */
    internal const val DUE = "due"
}

/**
 * Small facts about journey alerts kept across process deaths, in their own preferences file: a check
 * left timed for an old clock ([stale]), and a notification prompt Android won't show again
 * ([promptGone]). Disk reads: called off the main thread.
 */
internal object JourneyAlertState {
    private const val FILE = "journey-alerts-state"
    private const val STALE = "stale"
    private const val PROMPT_GONE = "prompt_gone"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Committed, not applied: a receiver may finish, and the process go, straight after. */
    fun markStale(context: Context) {
        // Not fatal: a time-zone change is still read off the pending check itself (its zone tag).
        if (!prefs(context).edit().putBoolean(STALE, true).commit()) logJourneyAlertWarning("marking the check stale wasn't written")
    }

    fun stale(context: Context): Boolean = prefs(context).getBoolean(STALE, false)

    fun clearStale(context: Context) {
        if (stale(context)) prefs(context).edit().remove(STALE).apply()
    }

    fun promptGone(context: Context): Boolean = prefs(context).getBoolean(PROMPT_GONE, false)

    fun setPromptGone(context: Context, gone: Boolean) {
        prefs(context).edit().putBoolean(PROMPT_GONE, gone).apply()
    }

    /** How soon after asking a refusal comes back with no prompt shown: under any person's reaction time. */
    const val AT_ONCE_MILLIS = 300L

    /**
     * Records how the notification prompt just ended, and returns whether Android has stopped offering
     * it. Android reports a swiped-away prompt exactly as a refusal, and leaves it available, so a
     * refusal counts as final only when Android had been offering a rationale and now stops
     * ([rationaleBefore] true, [rationaleAfter] false): the second "Don't allow". A prompt that ends
     * with no rationale before or after is taken as swiped away, however often, and Allow asks again
     * (Codex on #700); Android offers no public way to tell that apart from a refusal it made for good
     * before this install knew, which is left to Allow showing nothing until Settings are opened.
     * Such a prompt, with no rationale either side, says nothing new, so a refusal already found final
     * stands: once it is, Android answers every later ask that way at once (Codex on #700). Except that it
     * answers at once ([answeredAtOnce], within [AT_ONCE_MILLIS] of asking): faster than anyone can swipe a
     * prompt away, so none was shown, which is how a refusal made for good before this install recorded
     * one (an upgrade) is found (Codex on #700).
     */
    fun recordPrompt(context: Context, granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, answeredAtOnce: Boolean = false): Boolean {
        val gone = when {
            granted -> false
            rationaleBefore && !rationaleAfter -> true
            rationaleAfter -> false
            answeredAtOnce -> true
            else -> promptGone(context)
        }
        setPromptGone(context, gone)
        return gone
    }
}

/**
 * One journey alert check: the directions whose window is open now, their lines' status in one
 * batched request, and each journey's notification posted, renewed or taken down
 * ([JourneyAlerts.actions]); then the next check is armed. A failed request claims nothing: what's
 * shown stays until its own timeout or the next check that answers.
 */
class JourneyAlertWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        check()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A storage or scheduling failure: retried, so the chain isn't lost until the app next opens.
        logJourneyAlertWarning("check failed: ${e::class.simpleName}")
        Result.retry()
    }

    private suspend fun check() {
        val context = applicationContext
        // How late Android ran it (Doze defers it): a check armed before this was recorded doesn't say.
        val late = inputData.getLong(JourneyAlertChecks.DUE, 0L).takeIf { it > 0L }?.let { Duration.ofMillis(System.currentTimeMillis() - it) }
        val store = DataStoreFavoriteJourneysStore.from(context, warn = ::logJourneyAlertWarning)
        val journeys = store.journeys().first() ?: throw java.io.IOException("journeys unreadable")
        val schedules = store.alertSchedules().first() ?: throw java.io.IOException("schedules unreadable")
        val now = Instant.now()
        val at = JourneyAlerts.at(now)
        val active = JourneyAlerts.active(journeys, schedules, at)
        // A close check asks nothing: it clears what closed, and leaves the rest to the main check.
        val closeOnly = inputData.getBoolean(JourneyAlertChecks.CLOSE_ONLY, false)
        val asking = !closeOnly && active.isNotEmpty() && JourneyAlertNotification.canAlert(context)
        // Alerts are held back abroad (maintainer, 2026-10-09): TfL isn't asked, and what's up comes down.
        val abroad = asking && JourneyAlerts.abroad(JourneyAlertChecks.networkCountry(context))
        // Coarse by design (no country named): it's what says why alerts went quiet (SPEC principle 2).
        if (abroad) logJourneyAlertWarning("abroad: no alerts this check")
        val answer = if (!asking || abroad) null else results(context, active, schedules, now)
        val answers = answer?.results.orEmpty()
        // Decided as of when the answer came back, not when it was asked for: a request that outlived its
        // window posts nothing, and an alert's timeout runs from now (Codex on #700).
        val answeredAt = Instant.now()
        val atAnswer = JourneyAlerts.at(answeredAt)
        // And as the rider's settings stand then: a direction turned off, or a journey removed, while
        // the request was out is no longer watched, so nothing is posted for it (Codex on #700).
        // Read first: any sync after it may have cleaned up what's about to be posted.
        val syncsBefore = JourneyAlertChecks.syncs()
        val journeysNow = store.journeys().first() ?: throw java.io.IOException("journeys unreadable")
        val schedulesNow = store.alertSchedules().first() ?: throw java.io.IOException("schedules unreadable")
        val canAlertNow = JourneyAlertNotification.canAlert(context)
        // Abroad nothing counts as watched, so any alert up is taken down. Read again as the answer is: a
        // phone that crossed a border while TfL was asked posts nothing, and takes down what's up (Codex on #712).
        val abroadNow = abroad || (answer != null && JourneyAlerts.abroad(JourneyAlertChecks.networkCountry(context)))
        if (abroadNow && !abroad) logJourneyAlertWarning("abroad: no alerts this check")
        val stillActive = if (canAlertNow && !abroadNow) JourneyAlerts.active(journeysNow, schedulesNow, atAnswer) else emptyList()
        // The journeys only held back, their windows still open: their swipes are kept for the rider's return.
        val heldBack = if (abroadNow) JourneyAlerts.active(journeysNow, schedulesNow, atAnswer).mapTo(HashSet()) { it.key } else emptySet()
        // What each journey is watched for as these settings stand, as a sync would find it.
        val watchesNow = if (canAlertNow) JourneyAlertChecks.watches(journeysNow, schedulesNow, atAnswer) else emptyMap()
        // An answer about lines chosen for one direction says nothing about another: a journey whose
        // open directions changed while the request was out (one window closing as the way back's
        // opens) is left for the next check (Codex on #700).
        val asked = JourneyAlerts.directionsOf(active, schedules, at)
        // Same directions, same lines: the answer holds. Its window's close is taken from the settings as
        // they stand now, so an edit made while the request was out doesn't leave the alert saying the old
        // one, which the next sync would then clear as out of date (Codex on #700).
        val results = answers.filter { asked[it.key] == watchesNow[it.key]?.directions }.map { it.copy(until = watchesNow[it.key]?.until) }
        // Read after the request, so a swipe made while it was in flight counts (Codex on #700).
        // What each alert up says, from the alert itself where its record was never written (Codex on #700).
        val announced = JourneyAlerts.shown(AnnouncedJourneyAlerts.read(context), JourneyAlertNotification.saying(context))
        val dismissed = AnnouncedJourneyAlerts.read(context, AnnouncedJourneyAlerts.DISMISSED)
        val showing = JourneyAlertNotification.showing(context)
        val actions = JourneyAlerts.actions(results, stillActive.mapTo(HashSet()) { it.key }, announced, showing, dismissed)
        var moved: Set<String> = emptySet()
        var done: List<JourneyAlertAction> = emptyList()
        JourneyAlertChecks.applying(
            syncsBefore, context, journeysNow, schedulesNow, closeOnly = closeOnly,
            // As the settings stand when it runs, at the last moment before the re-arm: a direction can open while
            // the check waits for the locks, after the answer came back (Codex on #700).
            checkNow = {
                moved.isNotEmpty() ||
                    (canAlertNow && JourneyAlertChecks.unaskedDirections(asked, journeysNow, schedulesNow, JourneyAlerts.at(Instant.now())))
            },
            latest = { store.journeys().first() to store.alertSchedules().first() },
            settingsWrites = FavoriteJourneysWrites.lock,
        ) { unchanged ->
            // Under the pin writers' own lock too: a pin changed between the read below and the post would
            // otherwise still be posted for (Codex on #700). Its writers never take the sync lock, so this order
            // can't deadlock.
            WidgetJourneysWrites.lock.withLock {
                // And the same lines, read here, just before posting: pins moved while the request was out (a
                // route changed in the app) make the answer about lines this journey may no longer have, so it
                // waits for a check run at once (Codex on #700).
                if (answer != null) {
                    val linesNow = JourneyAlerts.linesFor(stillActive, schedulesNow, atAnswer, JourneyAlerts.pinnedLines(snapshot(context)))
                    moved = JourneyAlerts.moved(answer.lines, linesNow)
                }
                done = actions.filter { action ->
                    // The rider changed this journey's alerts since the settings were read: its decision was
                    // made from old ones, so it's left to the check that change armed.
                    if (!unchanged(action.key, watchesNow[action.key])) return@filter false
                    if (action.key in moved) return@filter false
                    when (action) {
                        is JourneyAlertAction.Post -> {
                            val journey = stillActive.first { it.key == action.key }
                            val says = action.result.fingerprint
                            fun swiped() = AnnouncedJourneyAlerts.read(context, AnnouncedJourneyAlerts.DISMISSED)[action.key] == says
                            // A swipe of what this would renew, recorded since the state was read, holds: checked
                            // before posting, and again after, taking down a copy that raced the swipe.
                            // The clock read as it's posted: Android counts the timeout from the post, and an
                            // answer that only arrives once its window has closed posts nothing (Codex on #700).
                            val postedAt = Instant.now()
                            val until = watchesNow[action.key]?.until
                            if (swiped() || (until != null && !until.isAfter(postedAt))) {
                                false
                            } else {
                                val posted = JourneyAlertNotification.post(context, journey, action.result, until = until, now = postedAt)
                                when {
                                    !posted -> false
                                    swiped() -> true.also { JourneyAlertNotification.cancelIfSaying(context, action.key, says) }
                                    else -> true
                                }
                            }
                        }
                        is JourneyAlertAction.Clear -> true.also { JourneyAlertNotification.cancel(context, action.key) }
                    }
                }
                AnnouncedJourneyAlerts.recordDone(context, done)
                // Applied to the swipes as they stand now, so one landing during this check isn't overwritten.
                AnnouncedJourneyAlerts.updateDismissed(context) { JourneyAlerts.dismissedAfter(it, done, heldBack) }
            }
        }
        // Every check says what it saw and did, not only a failing one: a missed alert has to be explainable
        // from a bug report (SPEC principle 2). A post Android refused also logs "alert refused".
        val skipped = when {
            closeOnly -> "asks nothing"
            active.isEmpty() -> "no window open"
            !asking -> "notifications off"
            abroad -> "abroad, held back"
            else -> null
        }
        logJourneyAlertWarning(
            JourneyAlertLog.check(
                late, closeOnly, active.size, skipped,
                answer?.lines?.values?.flatten()?.toSet().orEmpty(), answer?.statuses, done, actions.size - done.size,
            ),
        )
    }

    // The widget's pins and line names, or null when unreadable: then each journey is checked on its own
    // line and those its shown alert names.
    private suspend fun snapshot(context: Context): DeparturesSnapshot? = try {
        DataStoreSnapshotStore.from(context, warn = ::logJourneyAlertWarning).load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logJourneyAlertWarning("snapshot unreadable: ${e::class.simpleName}")
        null
    }

    // What TfL says of [active]'s lines now (none when it couldn't be asked), and the lines asked about.
    private class Answer(val results: List<JourneyAlertResult>, val lines: Map<String, Set<String>>, val statuses: Map<String, LineStatus>? = null)

    private suspend fun results(context: Context, active: List<FavoriteJourney>, schedules: Map<String, JourneyAlertSchedule>, now: Instant): Answer {
        val snapshot = snapshot(context)
        val at = JourneyAlerts.at(now)
        val lines = JourneyAlerts.linesFor(active, schedules, at, JourneyAlerts.pinnedLines(snapshot))
        // Without the pins, a line the shown alert names that isn't among those can't be placed: it may
        // be a pin of this direction's. Counted as unanswered, so the alert is left as it is rather than
        // cleared by lines going missing from the request; with the pins read, a line outside them
        // (another direction's, one whose window has closed) is no longer this alert's (Codex on #700).
        val inconclusive = if (snapshot != null) {
            emptyMap()
        } else {
            JourneyAlerts.unasked(active, lines, JourneyAlerts.shown(AnnouncedJourneyAlerts.read(context), JourneyAlertNotification.saying(context)))
        }
        val statuses = lineStatuses(context, lines.values.flatten().toSet()) ?: return Answer(emptyList(), lines)
        val current = LineStatus.asOf(statuses.associateBy { it.lineId }, now)
        return Answer(JourneyAlerts.results(
            active,
            lines,
            current,
            JourneyAlerts.lineNames(snapshot),
            inconclusive,
            JourneyAlerts.directionsOf(active, schedules, at),
            // The window each is found in, so a swipe holds only for it.
            active.mapNotNull { journey -> JourneyAlerts.watchedUntil(journey, schedules, at)?.let { journey.key to it.toInstant() } }.toMap(),
        ), lines, current)
    }

    // The lines' statuses with the rider's key, through the shared rate budget ([JourneyAlertChecks.answered]).
    private suspend fun lineStatuses(context: Context, lineIds: Set<String>): List<LineStatus>? {
        val keys = readRefreshKeys(context) ?: return null
        val http = KtorTflClient.defaultHttpClient(warn = ::logNetworkWarning)
        return try {
            val client = KtorTflClient(
                http,
                appKey = { keys.tfl },
                rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                requestPool = SharedTflRequestPool.pool,
                keyAnswered = RejectedApiKey.SHARED::record,
                warn = ::logJourneyAlertWarning,
            )
            JourneyAlertChecks.answered(lineIds) { chunk -> client.lineStatuses(chunk) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logJourneyAlertWarning("line status failed for ${lineIds.size} line(s): ${e::class.simpleName}")
            null
        } finally {
            http.close()
        }
    }
}

/**
 * What each journey's alert last said (a [JourneyAlertResult.fingerprint] by journey key), so the
 * same disruption isn't announced again after the rider swipes it away. On the device only, never
 * logged; cleared with the alert.
 */
internal object AnnouncedJourneyAlerts {
    const val ANNOUNCED = "journey-alerts-announced"

    /** What the rider swiped away, by journey key, so a timeout isn't mistaken for a swipe. */
    const val DISMISSED = "journey-alerts-dismissed"

    fun read(context: Context, file: String = ANNOUNCED): Map<String, String> =
        context.getSharedPreferences(file, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun write(context: Context, values: Map<String, String>, file: String = ANNOUNCED) {
        context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().apply {
            clear()
            values.forEach { (k, v) -> putString(k, v) }
        }.apply()
    }

    /**
     * Records what [done] posted and cleared, applied to the record as it stands now (with what each alert
     * up says), not as the check first read it: a sync since may have forgotten a journey turned off or
     * removed, and writing the old copy back would keep what it said (Codex on #700). Under the sync lock.
     */
    fun recordDone(context: Context, done: List<JourneyAlertAction>) {
        write(context, JourneyAlerts.announcedAfter(JourneyAlerts.shown(read(context), JourneyAlertNotification.saying(context)), done))
    }

    // Serializes the swipe record's read-modify-writes: the dismiss receiver and a check can overlap.
    private val dismissedLock = Any()

    /**
     * Records that the rider swiped away [key]'s alert saying [fingerprint], while its journey still has an
     * alert on record: a swipe heard only after the alert was cleared (its direction turned off, the journey
     * removed) is dropped, not written back after the cleanup forgot it, where nothing would remove it
     * again (Codex on #700). The cleanup forgets the record before the swipes, and both take this lock, so
     * a swipe either lands before the cleanup trims the swipes or finds the record already gone. A journey
     * still [watched] now, as its saved settings stand, takes the swipe too, read under the same lock: an
     * alert posted by a check that died before recording it still has its swipe kept (Codex on #700).
     */
    fun dismiss(context: Context, key: String, fingerprint: String, watched: () -> Boolean = { false }) =
        updateDismissed(context) { held -> if (key in read(context) || watched()) held + (key to fingerprint) else held }

    /** Replaces the swipe record with [change] applied to it as it stands, atomically within the process. */
    fun updateDismissed(context: Context, change: (Map<String, String>) -> Map<String, String>) {
        synchronized(dismissedLock) {
            val held = read(context, DISMISSED)
            val next = change(held)
            if (next != held) write(context, next, DISMISSED)
        }
    }
}

/**
 * Re-schedules the journey alert check when the device's clock is set or its time zone changes: a
 * window is a time of day where the rider is, so a check armed for the old clock could miss it
 * (Codex on #700).
 */
class JourneyAlertClockReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val pending: PendingResult? = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                resync(app)
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        // Where the resync runs; a receiver has no scope of its own.
        private val scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        /** Re-reads the saved journeys and schedules and re-arms the check from them; a failure is logged. */
        internal suspend fun resync(
            context: Context,
            store: FavoriteJourneysStore = DataStoreFavoriteJourneysStore.from(context, warn = ::logJourneyAlertWarning),
            io: CoroutineDispatcher = Dispatchers.IO,
            retryAfterMillis: Long = 2_000L,
            now: () -> Instant = { Instant.now() },
        ) {
            // Marked stale first: if the resync below fails, the next return to the app syncs again
            // even with a check still pending, timed for the old clock (Codex on #700). A successful
            // sync clears it.
            try {
                withContext(io) { JourneyAlertState.markStale(context) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logJourneyAlertWarning("marking the check stale failed: ${e::class.simpleName}")
            }
            for (attempt in 1..2) {
                try {
                    // Checking at once if a window is open in the new reckoning: the re-timed close
                    // usually takes the shown alert down, and a disruption still under way shouldn't
                    // wait a quarter hour to come back (Codex on #700).
                    JourneyAlertChecks.resync(context, store, io, now(), checkNowIfActive = true)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logJourneyAlertWarning("resync after a clock change failed (try $attempt of 2): ${e::class.simpleName}")
                    if (attempt == 1) delay(retryAfterMillis)
                }
            }
        }
    }
}

/**
 * Hears a journey alert swiped away (its delete intent; not sent for a timeout or a cancel), so the
 * same disruption isn't posted again, while one that only timed out is (Codex on #700).
 */
class JourneyAlertDismissReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT) ?: return
        val pending: PendingResult? = goAsync()
        // Off the main thread: the first read of the preferences file is disk work.
        writes.execute {
            try {
                val app = context.applicationContext
                AnnouncedJourneyAlerts.dismiss(app, key, fingerprint) { watchedNow(app, key) }
                // A check may have renewed it in the moment before this swipe was recorded.
                JourneyAlertNotification.cancelIfSaying(context.applicationContext, key, fingerprint)
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        /**
         * Whether [key]'s journey is watched now, as its saved settings stand; false when they can't be read,
         * so a swipe is never kept for a journey that may have gone. On the receiver's own thread, and
         * bounded, since it holds the swipe record's lock while it reads.
         */
        internal fun watchedNow(context: Context, key: String): Boolean = try {
            runBlocking {
                withTimeoutOrNull(2_000L) { readWatched(context, key) } ?: false.also { logJourneyAlertWarning("swipe: settings read timed out") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logJourneyAlertWarning("swipe: settings unreadable: ${e::class.simpleName}")
            false
        }

        private suspend fun readWatched(context: Context, key: String): Boolean = withContext(Dispatchers.IO) {
            val store = DataStoreFavoriteJourneysStore.from(context, warn = ::logJourneyAlertWarning)
            val journeys = store.journeys().first()
            val schedules = store.alertSchedules().first()
            journeys != null && schedules != null &&
                key in JourneyAlertChecks.watches(journeys, schedules, JourneyAlerts.at(Instant.now()))
        }

        // Where the swipe is written; a test runs it in place.
        @Volatile
        internal var writes: java.util.concurrent.Executor = java.util.concurrent.Executor { Thread(it, "journey-alert-dismiss").start() }

        const val EXTRA_KEY = "app.stopdash.extra.JOURNEY_KEY"
        const val EXTRA_FINGERPRINT = "app.stopdash.extra.ALERT_FINGERPRINT"
    }
}

/**
 * The silent notification for a watched journey (SPEC *Journeys → Alerts*): one per journey, tagged
 * with its key, titled the way the open window travels ("Euston ➔ Waterloo"), naming each disrupted
 * line and TfL's words for it. Its own low-importance channel: no sound, vibration or heads-up. It
 * times out unless a later check renews it, so it never outlives the check behind it (SPEC D4).
 */
internal object JourneyAlertNotification {
    const val CHANNEL_ID = "journey-alerts"

    private const val NOTIFICATION_ID = 4110

    // Two missed checks and it goes: a dead worker never leaves an old alert up.
    private val LASTS: Duration = JourneyAlerts.CHECK_INTERVAL.multipliedBy(2).plusMinutes(5)

    /**
     * Whether an alert can reach the rider: notifications allowed for the app and this channel not
     * turned off in Android's settings. While it can't, no check is scheduled, since every alert
     * would be dropped.
     */
    fun canAlert(context: Context): Boolean =
        GetOffSoonAlert.canNotify(context) &&
            NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    /** What stands between the rider and seeing an alert, worst first; [Gate.OPEN] when nothing does. */
    enum class Gate { NEEDS_PERMISSION, APP_OFF, CHANNEL_OFF, OPEN }

    /**
     * Makes sure the channel exists (so the rider can tune it) and says what, if anything, keeps an
     * alert from showing. Both are notification-manager calls, so the hop is first (AGENTS.md *Main
     * thread*); the caller returns to the main thread only to open the permission prompt or a settings page.
     */
    suspend fun gate(context: Context, io: CoroutineDispatcher = Dispatchers.IO): Gate = withContext(io) {
        if (NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID) == null) ensureChannel(context)
        when {
            // Denied for good earlier: Android won't show the prompt, so only the app's settings can turn
            // them on. Remembered across restarts (Codex on #700).
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                if (JourneyAlertState.promptGone(context)) Gate.APP_OFF else Gate.NEEDS_PERMISSION
            !NotificationManagerCompat.from(context).areNotificationsEnabled() -> Gate.APP_OFF
            !canAlert(context) -> Gate.CHANNEL_OFF
            else -> Gate.OPEN
        }
    }

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.journey_alerts_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.journey_alerts_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts [result] for [journey], oriented as its window travels; false when it couldn't be shown. It
     * times out at [until] (its window's close) if sooner than two missed checks, so it comes down with
     * the window even when no check runs then (Codex on #700).
     */
    fun post(context: Context, journey: FavoriteJourney, result: JourneyAlertResult, until: Instant? = null, now: Instant = Instant.now()): Boolean {
        if (result.alerts.isEmpty()) return false
        val lasts = listOfNotNull(LASTS, until?.let { Duration.between(now, it) }).min()
        if (lasts <= Duration.ZERO) return false
        // Made if missing; never remade over a channel the rider has changed in Android's settings.
        if (NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID) == null) ensureChannel(context)
        if (!canAlert(context)) return false
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val heading = result.alerts.joinToString("\n") { context.getString(R.string.route_disruption_line, it.lineName, it.description) }
        val body = result.alerts.joinToString("\n\n") { alert ->
            listOfNotNull(context.getString(R.string.route_disruption_line, alert.lineName, alert.description), alert.fullText?.trim()?.ifEmpty { null })
                .joinToString("\n")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_appbar_route_arrow)
            .setContentTitle(context.getString(R.string.journey_title, journey.from.name, journey.to.name))
            .setContentText(heading)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(lasts.toMillis())
            // What it says, so a swipe that raced a renewal can find and take down the renewed copy.
            .addExtras(android.os.Bundle().apply { putString(JourneyAlertDismissReceiver.EXTRA_FINGERPRINT, result.fingerprint) })
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    // One per text, so the swipe of an earlier copy, sent as a changed one replaces it,
                    // reports what that copy said, not the new text (Codex on #700).
                    (result.key + "\n" + result.fingerprint).hashCode(),
                    Intent(context, JourneyAlertDismissReceiver::class.java)
                        .putExtra(JourneyAlertDismissReceiver.EXTRA_KEY, result.key)
                        .putExtra(JourneyAlertDismissReceiver.EXTRA_FINGERPRINT, result.fingerprint),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(result.key, NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
            logJourneyAlertWarning("alert refused: ${e::class.simpleName}")
            false
        }
    }

    fun cancel(context: Context, key: String) = NotificationManagerCompat.from(context).cancel(key, NOTIFICATION_ID)

    /** The journey keys whose alert is up now. */
    /**
     * Takes down [key]'s alert if it still says [fingerprint]: one a check renewed just as the rider
     * swiped the copy before it (Codex on #700). One saying something new stays.
     */
    fun cancelIfSaying(context: Context, key: String, fingerprint: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { it.id == NOTIFICATION_ID && it.tag == key }
            .filter { it.notification.extras.getString(JourneyAlertDismissReceiver.EXTRA_FINGERPRINT) == fingerprint }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /** The journey keys of the alerts up, each with what it says (its fingerprint), if it records it. */
    fun saying(context: Context): Map<String, String?> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id == NOTIFICATION_ID && it.tag != null }
            .associate { it.tag to it.notification.extras.getString(JourneyAlertDismissReceiver.EXTRA_FINGERPRINT) }

    fun showing(context: Context): Set<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id == NOTIFICATION_ID && it.tag != null }
            .mapTo(HashSet()) { it.tag }

    /** Takes down every alert but [keys]', and forgets what they said. */
    fun clearExcept(context: Context, watched: Map<String, String?>) {
        // An alert recorded as found for other directions, or another window, than its journey is watched
        // for now goes too: what it says may be about a direction just turned off (Codex on #700).
        fun holds(key: String, said: String?) = key in watched && (said == null || JourneyAlertResult.scopeIn(said) == watched[key])
        val announced = AnnouncedJourneyAlerts.read(context)
        // What each alert says: the alert up's own, over a record that may be missing or older (the
        // process died between posting and saving), as every check decision reads it. One up that
        // records nothing is taken down rather than guessed at (Codex on #700).
        val up = saying(context)
        val shown = JourneyAlerts.shown(announced, up)
        val keep = watched.keys.filterTo(HashSet()) { key ->
            holds(key, shown[key]) && (key !in up || up[key] != null)
        }
        val gone = up.keys - keep
        gone.forEach { cancel(context, it) }
        if (announced.keys.any { it !in keep }) AnnouncedJourneyAlerts.write(context, announced.filterKeys { it in keep })
        // Under the swipe record's lock, so a swipe landing meanwhile isn't overwritten (Codex on #700).
        AnnouncedJourneyAlerts.updateDismissed(context) { held -> held.filter { (key, said) -> key in keep && holds(key, said) } }
    }
}
