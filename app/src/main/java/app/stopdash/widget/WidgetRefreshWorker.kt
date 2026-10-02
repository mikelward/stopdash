package app.stopdash.widget

import app.stopdash.data.KtorDarwinClient
import app.stopdash.data.RailStationCodesStore
import app.stopdash.data.RejectedApiKey
import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.CachingTflClient
import app.stopdash.domain.RailAwareTflClient
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import app.stopdash.MainActivity
import app.stopdash.data.DataStoreAlertsBehindStore
import app.stopdash.data.DataStoreAppSettings
import app.stopdash.data.DataStoreDismissedAlertsStore
import app.stopdash.data.DataStoreSnapshotStore
import app.stopdash.data.HiddenModesSetting
import app.stopdash.data.KtorTflClient
import app.stopdash.data.LineAlertDirections
import app.stopdash.data.SharedTflRateLimiter
import app.stopdash.data.SharedTflRequestPool
import app.stopdash.data.logAppSettingsWarning
import app.stopdash.data.WatchRefreshOutcome
import app.stopdash.domain.AlertsBehind
import app.stopdash.domain.AlertsBehindStore
import app.stopdash.domain.AppSettings
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.lineAlertKey
import app.stopdash.domain.TflException
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.TflClient
import app.stopdash.domain.WidgetRefresh
import app.stopdash.ui.ARRIVALS_REUSE
import app.stopdash.ui.LINE_STATUS_REUSE
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Unique-work name so each scheduled refresh REPLACEs the previous one — at most one pending. */
internal const val WIDGET_REFRESH_WORK = "stopdash-widget-refresh"

/** The cadence of the opt-in "live widget" refresh (SPEC D5). One minute matches the app's own
 *  auto-refresh (D6) and TfL's ~30 s prediction cadence, at a tiny fraction of the rate budget. */
internal const val WIDGET_REFRESH_INTERVAL_MILLIS = 60_000L

/** Ceiling on a one-shot settings read (see [liveWidgetRefreshNow]). Normal reads are instant;
 *  this only bites on a persistent storage error (Codex P2 on #56). */
internal const val SETTINGS_READ_BOUND_MILLIS = 2_000L

/**
 * Reads the live-refresh setting once, bounded — for the two *user-facing* one-shot callers that
 * can't wait indefinitely: the startup sync (blocks the restore + error notice) and the widget
 * render path (blocks `provideContent`). Returns the current value, or **null** when it can't be
 * read within [timeoutMillis].
 *
 * [AppSettings.liveWidgetRefresh] retries a transient `IOException` forever so the long-lived in-app
 * collector stays alive and recovers rather than reading a synthetic "off" (Codex P2 on #56) — but
 * that same infinite retry means a one-shot `.first()` never completes on a *persistent* storage
 * error, hanging its caller. This is the single bounded seam those two read through, so neither
 * hangs and neither re-derives its own bound (Codex P2 on #56, ×2). A null is "couldn't read",
 * never "off" — each caller decides what that means for it, never treating it as a toggle-off.
 * The background worker deliberately reads plainly instead: WorkManager already bounds its
 * execution, so an unbounded read there stalls one background tick rather than anything the user
 * sees, and bounding it would race the test's virtual clock against a real DataStore read.
 */
internal suspend fun AppSettings.liveWidgetRefreshNow(
    timeoutMillis: Long = SETTINGS_READ_BOUND_MILLIS,
): Boolean? = withTimeoutOrNull(timeoutMillis) { liveWidgetRefresh().first() }

/**
 * Enqueues one refresh tick ~a minute out, REPLACE so only one is ever pending. The worker
 * re-enqueues the next tick itself, so a single call starts a self-sustaining ~1/min chain that
 * runs until [cancelWidgetRefresh]. A deferrable [OneTimeWorkRequest], not a periodic one:
 * WorkManager's periodic floor is 15 min, far coarser than the minute this needs, and it is not
 * a foreground service — so the OS runs it ~1/min while the device is active and defers it in Doze
 * rather than guaranteeing the exact minute. It is **not** screen-state-gated: with the app closed
 * there is no live component to hear screen on/off, so it can still run screen-off while charging.
 * A true screen-on-only scope (and the exact minute with the screen off) is the deferred
 * foreground-service option (mechanism A, SPEC D5, *Widget follow-ups*).
 */
internal suspend fun scheduleWidgetRefresh(context: Context) {
    // await() surfaces a WorkManager DB-write failure to the caller (all of which wrap this in a
    // try/catch) instead of discarding the Operation, which would leave the setting on with no
    // tick enqueued and nothing logged (Codex P2 on #56).
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        WIDGET_REFRESH_WORK,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInitialDelay(WIDGET_REFRESH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            // Only wake when there's a network path — offline, the tick defers instead of
            // building a client and failing every persisted stop each minute (Codex P2 on #56).
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build(),
    ).await()
}

/** Cancels the pending refresh tick — called when the setting is turned off, and when the last
 *  widget is removed ([StopDashWidget.onDelete]). Awaited for the same reason as the enqueue. */
internal suspend fun cancelWidgetRefresh(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WIDGET_REFRESH_WORK).await()
}

/**
 * Schedules the next tick only when no tick is already enqueued or running — the idempotent
 * entry point used by everything except the worker's own end-of-cycle reschedule. Startup sync
 * and the enable toggle re-run on activity recreation (a rotation re-runs the startup
 * `LaunchedEffect`), and an unconditional [scheduleWidgetRefresh] there REPLACEs — resetting a
 * pending tick's delay and cancelling a currently-running worker, so repeated config changes could
 * postpone refresh indefinitely or abort an in-flight fetch (Codex P2 on #56). Retaining existing
 * pending/running work keeps the chain alive across recreation. The worker's own reschedule must
 * NOT use this: it is itself RUNNING when it enqueues the next tick, so an "already running" guard
 * would see itself and kill the chain — it keeps the REPLACE [scheduleWidgetRefresh]. */
private suspend fun scheduleWidgetRefreshIfNotPending(context: Context) {
    val alreadyPending = WorkManager.getInstance(context.applicationContext)
        .getWorkInfosForUniqueWorkFlow(WIDGET_REFRESH_WORK).first()
        .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    if (!alreadyPending) scheduleWidgetRefresh(context)
}

/** True when at least one [StopDashWidget] is installed on a host. A refresh cycle with none
 *  installed would fetch every persisted stop each minute with no surface to update, so the
 *  chain must neither run nor reschedule without one (Codex P1 on #56). */
private suspend fun anyWidgetInstalled(context: Context): Boolean =
    GlanceAppWidgetManager(context.applicationContext)
        .getGlanceIds(StopDashWidget::class.java).isNotEmpty()

/**
 * Restarts the live-refresh chain when a widget render shows a widget now exists (an add, or a
 * host rebind) and the setting is on — but only when no tick is already pending, so an active
 * chain isn't pushed back on every render. Armed from [StopDashWidget.provideGlance] (the render
 * path, like the staleness redraw): the worker retires the chain when the last widget is removed,
 * so this is what resumes it after one is re-added (Codex P1 on #56).
 */
suspend fun resumeWidgetRefreshIfEnabled(
    context: Context,
    settings: AppSettings =
        DataStoreAppSettings.from(context.applicationContext, warn = ::logAppSettingsWarning),
) {
    // Bounded one-shot read (see liveWidgetRefreshNow). This runs on the widget render path —
    // provideGlance awaits it before provideContent — so an unbounded read would hang the frame.
    // Resume is best-effort scheduler recovery, so on a slow/failing read (null) skip it and let
    // the snapshot paint; the next render retries (SPEC jank-free UI / principle 5). null is never
    // read as "off", which would wrongly retire the chain.
    if (settings.liveWidgetRefreshNow() != true) return
    scheduleWidgetRefreshIfNotPending(context)
}

/**
 * Applies the "refresh widget every minute" setting: start the chain when enabled, cancel it when
 * not. Called from the Settings toggle and once at app start (so an enabled setting resumes after
 * the process is recreated). Enabling uses the idempotent [scheduleWidgetRefreshIfNotPending] so a
 * startup re-sync on activity recreation retains an existing tick rather than resetting or aborting
 * it (Codex P2 on #56). Reboot persistence would need a boot receiver and is a deferred follow-up
 * (SPEC D5, *Widget follow-ups*).
 */
suspend fun applyWidgetRefreshSetting(context: Context, enabled: Boolean) {
    if (enabled) scheduleWidgetRefreshIfNotPending(context) else cancelWidgetRefresh(context)
}

/**
 * Re-fetches arrivals for the widget's persisted stops and saves the refreshed snapshot (which
 * pokes the widget to re-render), then schedules the next tick — the opt-in "live widget" loop
 * (SPEC D5). Reads no location (D1): it refreshes exactly the stops already in the snapshot.
 *
 * Its stops' line statuses are re-checked in the same cycle, so the widget's disruption marks stay
 * as fresh as its countdowns (SPEC D3).
 *
 * Best-effort throughout: a failed cycle keeps the last-good on the widget and still reschedules,
 * so a transient TfL error doesn't break the chain; cancellation propagates. The chain stops only
 * when the setting reads off — checked at the start (so a toggle-off retires the loop) and again
 * before rescheduling.
 */
class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        runRefreshCycle()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A transient failure reading settings, querying installed widgets, or scheduling the next
        // tick must not silently kill the chain — an uncaught throw becomes Result.failure() with no
        // retry, leaving the loop dead until an app launch or widget render happens to restart it.
        // Ask WorkManager to retry so the chain survives (Codex P2 on #56).
        logWidgetSnapshotWarning("widget refresh worker failed: ${e::class.simpleName}")
        Result.retry()
    }

    private suspend fun runRefreshCycle(): Result {
        val settings = DataStoreAppSettings.from(applicationContext, warn = ::logAppSettingsWarning)
        // A plain read, not the bounded liveWidgetRefreshNow the render path and startup sync use:
        // this is background work WorkManager already bounds by its own execution timeout, so an
        // unbounded read on a persistent storage error stalls one background tick (then WorkManager
        // retries) rather than hanging anything user-facing. If it can't be read at all the outer
        // catch turns the throw into Result.retry(); an actual retry read here never spuriously
        // times out under the test's virtual clock either.
        if (!settings.liveWidgetRefresh().first()) return Result.success() // toggled off → chain ends
        // No widget installed → nothing to refresh; retire the chain rather than fetch every
        // persisted stop each minute with no surface to update (Codex P1). A widget added later
        // resumes it via resumeWidgetRefreshIfEnabled from the render path.
        if (!anyWidgetInstalled(applicationContext)) return Result.success()
        val store = DataStoreSnapshotStore.from(applicationContext, warn = ::logWidgetSnapshotWarning)
        // No snapshot yet (before the first fetch, or after a corrupt one was discarded) → there
        // are no stops to refresh, so retire the chain rather than wake every minute doing nothing
        // (Codex P2 on #56). A later snapshot save pokes the widget (updateAll → provideGlance →
        // resumeWidgetRefreshIfEnabled), which restarts the chain. A load failure throws and the
        // outer catch turns it into Result.retry().
        // The keys first, outside the lock: a stalled read (storage errors retry forever) then
        // stalls this background tick alone, never a watch's refresh waiting on the lock.
        val keys = readRefreshKeys(applicationContext, settings)
        StoredSnapshotRefresh.lock.withLock {
            val prior = store.load() ?: return Result.success()
            StoredSnapshotRefresh.refresh(applicationContext, prior, keys)
        }
        // Reschedule the next tick unless the setting was turned off or the last widget was removed
        // during this cycle. Best-effort even after a failure above, so a transient error doesn't
        // retire the loop. Plain read, same as the top of the cycle (background, WorkManager-bounded).
        if (settings.liveWidgetRefresh().first() && anyWidgetInstalled(applicationContext)) {
            scheduleWidgetRefresh(applicationContext)
        }
        return Result.success()
    }
}

/**
 * Refreshes of the stored snapshot, whoever asks (the widget's cycle, a watch), one at a time under
 * [lock]: each reads the snapshot inside it, after the last one saved, so overlapping callers reuse
 * the stops just fetched rather than fetch them twice (the shared TfL budget, and the radio). A
 * refresh that saved nothing (every stop failed) leaves nothing to reuse, so its outcome answers
 * the next caller for a short while instead, as long as the stored arrivals are still the ones it
 * started from ([sameArrivals]): newer ones (the app refreshed, or the stops changed) are refreshed
 * on their own terms, while a line check stored since changes nothing the outcome answers for. Nor
 * once the TfL key has changed: a key cleared or pasted since is TfL's to answer (SPEC D7).
 */
internal object StoredSnapshotRefresh {
    val lock = Mutex()

    /**
     * A refresh of [input] that saved nothing, ended [at] with [outcome], sent with [tflKey]. Holds
     * the key only in memory, to compare; never logged, so deliberately not a data class.
     */
    internal class Failed(val at: Instant, val outcome: WatchRefreshOutcome, val input: DeparturesSnapshot, val tflKey: String?) {
        /**
         * Whether this answers a refresh of [prior] sent with [tflKey] at [now]: the same arrivals,
         * within the reuse window, and the same key. A key changed since (a rejected one cleared, a
         * key pasted over a rate limit) is asked of TfL, not answered from the old key's failure.
         */
        fun answers(prior: DeparturesSnapshot, tflKey: String?, now: Instant): Boolean =
            tflKey == this.tflKey &&
                sameArrivals(input, prior) &&
                WatchRefreshOutcome.answersAgain(outcome, Duration.between(at, now), ARRIVALS_REUSE)
    }

    /** The last refresh, if it saved nothing; guarded by [lock]. */
    private var lastFailed: Failed? = null

    /** How a refresh went: its [outcome], and whether it [saved] (so the store's watchers publish). */
    data class Result(val outcome: WatchRefreshOutcome, val saved: Boolean)

    /**
     * Refreshes [prior], the snapshot stored now, with [keys] ([readRefreshKeys]), which the caller
     * reads before taking [lock] so a stalled settings read never holds it. The caller holds [lock].
     */
    suspend fun refresh(context: Context, prior: DeparturesSnapshot, keys: RefreshKeys?): Result {
        lastFailed?.let { last ->
            if (last.answers(prior, keys?.tfl, Instant.now())) {
                // Still re-render, as a refresh with nothing fresh does, so the widget ages honestly.
                try {
                    StopDashWidget().updateAll(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logWidgetSnapshotWarning("widget re-render failed: ${e::class.simpleName}")
                }
                return Result(last.outcome, saved = false)
            }
        }
        val report = refreshStoredSnapshot(context, prior, keys)
        lastFailed = if (report.savedNothing) Failed(Instant.now(), report.outcome, prior, keys?.tfl) else null
        return Result(report.outcome, report.saved)
    }

    // Whether [a] and [b] hold the same arrivals, whatever their line checks: a failed refresh's
    // outcome answers for arrivals, so a check stored since (its own status-only write, or the
    // app's) doesn't send the next caller to fetch them all again.
    internal fun sameArrivals(a: DeparturesSnapshot, b: DeparturesSnapshot): Boolean =
        a.copy(lineStatuses = emptyMap()) == b.copy(lineStatuses = emptyMap())
}

/**
 * The user's keys for one refresh (SPEC D7, *National Rail*): the TfL app_key and the National Rail
 * key, each null when unset (keyless). Never logged, so deliberately not a data class (no toString).
 */
internal class RefreshKeys(val tfl: String?, val rail: String?)

/**
 * Reads [RefreshKeys] once for a refresh (a one-shot background run needs no live provider), within
 * [boundMillis] when given, for a caller someone is waiting on. Null, logged, when they can't be
 * read, which the refresh reports as a failure rather than hanging or crashing.
 */
internal suspend fun readRefreshKeys(
    context: Context,
    settings: AppSettings = DataStoreAppSettings.from(context, warn = ::logAppSettingsWarning),
    boundMillis: Long? = null,
): RefreshKeys? {
    suspend fun read() = RefreshKeys(settings.userApiKey().first(), settings.railApiKey().first())
    return try {
        val keys = if (boundMillis == null) read() else withTimeoutOrNull(boundMillis) { read() }
        keys ?: null.also { logWidgetSnapshotWarning("refresh keys read timed out") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("refresh keys unreadable: ${e::class.simpleName}")
        null
    }
}

/** What one refresh of the stored snapshot did: [fetched] stops fetched fresh, [reused] skipped as
 *  recent, and a [failures] entry for each of the rest; [savedNothing] when it didn't try to save,
 *  and [saved] when a save went through (so the store changed, and its watchers publish);
 *  [statusKeyRejected] when a line check was refused the user's key. */
internal data class SnapshotRefreshReport(
    val stops: Int,
    val fetched: Int,
    val reused: Int,
    val failures: List<WatchRefreshOutcome.Failure>,
    val savedNothing: Boolean = false,
    val saved: Boolean = false,
    val statusKeyRejected: Boolean = false,
) {
    val outcome: WatchRefreshOutcome get() = WatchRefreshOutcome.of(stops, fetched, reused, failures, statusKeyRejected)
}

/**
 * One bounded, location-free refresh of the stored widget snapshot [prior]: its stops' arrivals,
 * fetched with the user's keys through the shared rate budget, a stop fetched moments ago reused.
 * The result is saved only if the stored stop set still matches; with nothing fresh, the widget
 * re-renders so the unchanged snapshot ages honestly. The widget's own refresh cycle and a watch's
 * refresh request both run it. Never throws but for cancellation; a failure is logged, and a cycle
 * that failed before fetching, or whose save failed, counts every stop as unreachable; so do
 * [keys] that couldn't be read (null).
 */
internal suspend fun refreshStoredSnapshot(
    context: Context,
    prior: DeparturesSnapshot,
    keys: RefreshKeys?,
): SnapshotRefreshReport {
    val attempted = AtomicInteger()
    val succeeded = AtomicInteger()
    val failures = java.util.Collections.synchronizedList(mutableListOf<WatchRefreshOutcome.Failure>())
    // A line check TfL refused the key for: with every stop's arrivals reused, the only sign of it.
    val statusKeyRejected = AtomicBoolean(false)
    var ran = false
    // Set while the fetched result is being saved: a save that throws leaves the watch and widget on
    // the old snapshot, so the refresh failed however many stops were fetched.
    var saving = false
    var savedNothing = true
    var saved = false
    try {
        // The client reads the key provider once per request and drives both the app_key and the
        // limiter's budget from that one read (rateLimiterFor), so a widget-only process needn't
        // wait for the process-wide holder to warm and the budget always matches the key sent.
        keys ?: throw java.io.IOException("keys unreadable")
        val userKey = keys.tfl
        val railKey = keys.rail
        val http = KtorTflClient.defaultHttpClient()
        // The one-off lookups of which way a new line alert applies run here, so they finish before
        // [http] is closed; the shared cache they fill serves the app and the next widget refresh.
        // A supervisor, so a lookup that fails can't cancel the refresh; it's logged, and the
        // alert shows both ways until a later lookup lands.
        try {
            supervisorScope {
                val directionLookups = this + CoroutineExceptionHandler { _, e ->
                    logWidgetSnapshotWarning("widget alert direction lookup failed: ${e::class.simpleName}")
                }
                // Its fetches land in the shared arrivals too, so an app screen open meanwhile shows them.
                val client = CachingTflClient(RailAwareTflClient(
                    tfl = KtorTflClient(
                        http,
                        appKey = { userKey },
                        rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                        requestPool = SharedTflRequestPool.pool,
                        // The app says a refused key when it's next opened (SPEC D7).
                        keyAnswered = RejectedApiKey.SHARED::record,
                        // Where a failed alert-direction lookup is reported; it's caught inside the
                        // client, so the lookup scope's handler never sees it.
                        warn = ::logWidgetSnapshotWarning,
                        // A row carries only the alerts for the way it's going, as in the app (SPEC
                        // *Disruptions*).
                        alertDirections = LineAlertDirections.shared,
                        alertDirectionScope = directionLookups,
                    ),
                    rail = KtorDarwinClient(http, apiKey = { railKey }, warn = ::logWidgetSnapshotWarning),
                    codes = { RailStationCodesStore.load(context) },
                    warn = ::logWidgetSnapshotWarning,
                    boards = ArrivalsCache.SHARED,
                ))
                ran = true
                // A station's National Rail board is left out while National Rail is hidden, since it
                // would only fill rows the widget leaves out, but kept for a pinned journey calling on
                // a National Rail line there (SPEC *Finding stops → Hiding a mode*).
                val railBoards = WidgetRefresh.railBoards(prior, HiddenModesSetting.loaded())
                // The arrivals, then the lines' statuses, in as few requests as TfL accepts (lines
                // checked moments ago reused), so a disrupted service stays marked while its
                // countdowns are live (SPEC D3). A failed status lookup keeps the prior checks, which
                // then age out like a countdown (D4); it doesn't fail the refresh, whose arrivals are
                // still good. With no arrivals fresh, the lines are still checked, so a suspension
                // declared during an arrivals outage reaches the widget ([WidgetRefresh.refresh]).
                var answered: List<LineStatus>? = null
                // Every line in a request TfL answered, returned or not: one it left out gets a
                // no-verdict check ([WidgetRefresh.refreshedLineStatuses]), so it was checked too.
                var asked: Set<String> = emptySet()
                val outcome = WidgetRefresh.refresh(
                    prior,
                    Instant::now,
                    // Skip a stop the app fetched moments ago: same data, same shared rate budget.
                    arrivalsReuse = ARRIVALS_REUSE,
                    statusReuse = LINE_STATUS_REUSE,
                    // Or one another screen fetched since.
                    shared = ArrivalsCache.SHARED,
                    source = client.arrivalsSource(),
                    railFeed = client::railFeed,
                    fetchedAt = client::fetchedAt,
                    fetchStatuses = { lineIds ->
                        // Called once per request TfL accepts: every answered one's statuses count.
                        widgetLineStatuses(client, lineIds, onKeyRejected = { statusKeyRejected.set(true) })
                            ?.also {
                                answered = answered.orEmpty() + it
                                asked = asked + lineIds
                            }
                    },
                ) { stopId ->
                    attempted.incrementAndGet()
                    try {
                        client.arrivals(stopId, railBoards[stopId] ?: true).also { succeeded.incrementAndGet() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Sanitized: a stop id is a canned identifier, not user data, but the message
                        // stays a bare fact (SPEC *Privacy* / *Error handling*).
                        logWidgetSnapshotWarning("widget refresh arrivals failed for stop $stopId: ${e::class.simpleName}")
                        failures += watchFailureOf(e)
                        null
                    }
                }
                val dismissals = { DataStoreDismissedAlertsStore.from(context.applicationContext, warn = ::logWidgetSnapshotWarning) }
                savedNothing = outcome !is WidgetRefresh.Outcome.Save
                when (outcome) {
                    is WidgetRefresh.Outcome.Save -> {
                        // Conditional save: persist and poke the widget only if the stored stop set still
                        // matches the one this cycle loaded and fetched for. In the seconds spent fetching,
                        // the app may have persisted a different set (the user relocated in-app); an
                        // unconditional save would let this slow cycle win last and stamp the old location's
                        // departures fresh over the new set. On a discard the newer in-app snapshot is
                        // already stored and has poked the widget itself (Codex P1 on #56).
                        saving = true
                        val applied = WidgetSnapshotStore(context).saveIfStopsMatch(outcome.snapshot, prior.stops.map { it.stopId })
                        saving = false
                        saved = applied
                        if (!applied) logWidgetSnapshotWarning("widget refresh result discarded: stop set changed during fetch")
                        // A dismissed disruption TfL now reports as resolved or changed is forgotten, as the
                        // app's own refresh forgets it, so the same alert recurring while the app stays
                        // closed is shown again rather than hidden. Only once this refresh's statuses are
                        // stored; a discarded one leaves it to the app's own refresh.
                        if (applied) answered?.let { reconcileWidgetDismissals(dismissals(), it) }
                        // Places the alerts it just fetched, as the app's list does, so one that came up
                        // while the app was closed needn't flag where it lies behind a stop.
                        if (applied) placeWidgetAlerts(context, answered.orEmpty(), asked)
                    }
                    is WidgetRefresh.Outcome.Statuses -> {
                        // Only the statuses are stored, merged per line with whatever the app wrote
                        // meanwhile, for the lines the stored stops show; the arrivals stay as stored,
                        // to age honestly. The store redraws the widget, as a save does.
                        WidgetSnapshotStore(context).updateLineStatuses(outcome.checks)
                        answered?.let { reconcileWidgetDismissals(dismissals(), it) }
                        placeWidgetAlerts(context, answered.orEmpty(), asked)
                    }
                    // Nothing fresh: re-render so the unchanged snapshot ages honestly.
                    WidgetRefresh.Outcome.Unchanged -> StopDashWidget().updateAll(context)
                }
            }
        } finally {
            http.close()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("widget refresh cycle failed: ${e::class.simpleName}")
    }
    if (!ran || saving) {
        return SnapshotRefreshReport(
            // A save that threw left the store as it was (DataStore writes are atomic), so it saved
            // nothing: the outcome is cached like any all-failed one, against the unchanged snapshot.
            prior.stops.size, 0, 0, List(prior.stops.size) { WatchRefreshOutcome.Failure.UNREACHABLE }, savedNothing = true,
        )
    }
    val tried = attempted.get()
    return SnapshotRefreshReport(
        prior.stops.size, succeeded.get(), prior.stops.size - tried, failures.toList(), savedNothing, saved, statusKeyRejected.get(),
    )
}

/**
 * Why a stop's fetch failed, as a watch's refresh reports it: a rate limit the user can wait out, a
 * key TfL refused that only clearing it on the phone mends (SPEC D7), else unreachable.
 */
internal fun watchFailureOf(e: Throwable): WatchRefreshOutcome.Failure = when (e) {
    is TflException.RateLimited -> WatchRefreshOutcome.Failure.RATE_LIMITED
    is TflException.KeyRejected -> WatchRefreshOutcome.Failure.KEY_REJECTED
    else -> WatchRefreshOutcome.Failure.UNREACHABLE
}

/**
 * Prune the dismissed set against the statuses TfL just [answered] for ([Dismissed.reconcile]):
 * for each line it returned a status for, a dismissal of any other alert on that line is dropped;
 * lines it didn't answer for, and every stop closure, keep theirs. Best-effort: a failure is
 * logged and the dismissals are pruned on a later refresh (the app's or this one's).
 */
internal suspend fun reconcileWidgetDismissals(store: DismissedAlertsStore, answered: List<LineStatus>) {
    if (answered.isEmpty()) return
    try {
        store.reconcile(
            live = DepartureRows.liveLineStatusAlerts(answered.associateBy { it.lineId }, Instant.now()),
            // A line still waiting on which way its alerts apply can't match a dismissal of one
            // direction's alert yet, so it isn't counted as checked until the split lands.
            checkedPlaces = answered.filterNot { it.awaitingDirections }.mapTo(HashSet()) { lineAlertKey(it.lineId) },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("widget refresh couldn't prune dismissed alerts: ${e::class.simpleName}")
    }
}

/**
 * Places the alerts TfL just [answered] at the widget's [stops] ([AlertsBehind.placement]), as the app's
 * list does, and keeps what it finds for the widget and the watch: an alert that came up while the app
 * was closed then needn't flag where it lies wholly behind a stop. With the routes the app already holds
 * (each line's for a day, [MainActivity.routeStops]) and no request of its own: a line whose route isn't
 * held is left to the app. Only the lines [asked] about count as checked, those TfL left out included, as
 * an alert reused from an earlier check isn't known gone. At the stops stored, not the ones this cycle
 * loaded: the app may have stored another place's, or a newer check of a line, meanwhile. Best-effort: a
 * failure is logged, and the alerts flag as before.
 */
private suspend fun placeWidgetAlerts(context: Context, answered: List<LineStatus>, asked: Set<String>) {
    if (asked.isEmpty()) return
    val routes = MainActivity.routeStops(context)
    val snapshots = WidgetSnapshotStore(context)
    placeWidgetAlerts(
        stored = { snapshots.stored() },
        answered,
        asked,
        sequenceOf = { lineId ->
            routes.warm()
            routes.cached(lineId, "")
        },
        store = DataStoreAlertsBehindStore.from(context.applicationContext, warn = ::logWidgetSnapshotWarning),
    )
}

/**
 * [placeWidgetAlerts] with the [stored] snapshot, its routes ([sequenceOf], held only) and [store] given,
 * as a test gives them. [stored] is read to place the alerts, and again just before they're kept: only
 * the stops still stored then ([AlertPlacement.everyStop] false), and the lines whose stored check is
 * still this refresh's answer ([AlertPlacement.lines]), are spoken for. So the app moving to new stops,
 * or storing a newer check of a line, while the routes were read leaves its verdicts there alone (Codex,
 * PR #472).
 */
internal suspend fun placeWidgetAlerts(
    stored: suspend () -> DeparturesSnapshot?,
    answered: List<LineStatus>,
    asked: Set<String>,
    sequenceOf: suspend (String) -> LineSequence?,
    store: AlertsBehindStore,
    now: Instant = Instant.now(),
) {
    if (asked.isEmpty()) return
    try {
        val at = stored()?.stops ?: return
        val statuses = answered.filter { it.lineId in asked }.associateBy { it.lineId }
        val sequences = DepartureRows.linesWithAlertsToPlace(at, statuses, now).associateWith { sequenceOf(it) }
        val placed = AlertsBehind.placement(at, statuses, sequences, now, lines = asked)
        val still = stored() ?: return
        val current = asked.filterTo(HashSet()) { line -> still.lineStatuses[line]?.isAnswer(statuses[line]) == true }
        store.record(
            placed.copy(stops = placed.stops intersect still.stops.mapTo(HashSet()) { it.stopId }, lines = current, everyStop = false),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("widget refresh couldn't place alerts: ${e::class.simpleName}")
    }
}

// Whether this stored check is still the answer a refresh got for its line: [status] by its full words,
// each direction's too, or no verdict where TfL left the line out (a null [status]).
private fun LineStatusCheck.isAnswer(status: LineStatus?): Boolean {
    if (status == null) return !known
    val answer = LineStatusCheck(status, checkedAt)
    return known && fingerprint == answer.fingerprint && directionFingerprints == answer.directionFingerprints
}

/**
 * The widget refresh's status lookup for [lineIds]: TfL's answer, or null when the call failed, so
 * the prior checks age out ([WidgetRefresh.refreshedLineStatuses]). TfL not recognising any of the
 * lines (a National Rail-only set) is an answer, not a failure: an empty one, so each line gets a
 * no-verdict check and isn't asked about again until that check is past the reuse window, as the
 * app remembers the lines TfL doesn't know.
 */
internal suspend fun widgetLineStatuses(
    client: TflClient,
    lineIds: Set<String>,
    // Told when TfL refused the user's key, so a refresh whose arrivals were all reused still says so.
    onKeyRejected: () -> Unit = {},
): List<LineStatus>? =
    try {
        client.lineStatuses(lineIds)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TflException.NotFound) {
        logWidgetSnapshotWarning("widget refresh: TfL doesn't know ${lineIds.size} line(s); recorded as no verdict")
        emptyList()
    } catch (e: TflException.KeyRejected) {
        logWidgetSnapshotWarning("widget refresh line status failed for ${lineIds.size} line(s): key rejected")
        onKeyRejected()
        null
    } catch (e: Exception) {
        logWidgetSnapshotWarning("widget refresh line status failed for ${lineIds.size} line(s): ${e::class.simpleName}")
        null
    }
