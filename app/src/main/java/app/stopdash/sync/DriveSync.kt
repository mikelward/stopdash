package app.stopdash.sync

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.stopdash.AppForeground
import app.stopdash.BuildConfig
import app.stopdash.StopdashDebugLog
import app.stopdash.data.DataStoreFavoriteJourneysStore
import app.stopdash.data.KtorDriveAppData
import app.stopdash.data.RideSync
import app.stopdash.data.RideSyncedStore
import app.stopdash.domain.DriveFailure
import app.stopdash.domain.Journeys
import app.stopdash.domain.SyncOutcome
import app.stopdash.domain.SyncRun
import app.stopdash.domain.WidgetJourneysReport
import app.stopdash.ui.WidgetJourneysWrites
import app.stopdash.widget.WidgetSnapshotStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sync on this phone as Settings shows it: whether it's on, and how the last sync went. */
data class DriveSyncView(val on: Boolean, val status: DriveSyncStatus)

/** How sync last went on this phone, said under its Settings row (dev-docs/drive-sync.md *What the rider sees*). */
sealed interface DriveSyncStatus {
    /** On, and not synced since StopDash started. */
    data object Waiting : DriveSyncStatus

    data object Syncing : DriveSyncStatus

    data class Synced(val at: Instant) : DriveSyncStatus

    /** Synced, but a phone on a newer StopDash wrote a file this one can't read. */
    data object NewerPhone : DriveSyncStatus

    data object Offline : DriveSyncStatus

    /** The grant is gone: the switch reads off, and turning it on asks again. */
    data object SignIn : DriveSyncStatus

    /** Asking for the grant came to nothing (no Play services, or Google refused). */
    data object SignInFailed : DriveSyncStatus

    /** Granted, but this phone's files couldn't keep the switch: sync stays off. */
    data object TurnOnFailed : DriveSyncStatus

    /** Off for now, but this phone's files couldn't keep it so: on again when StopDash restarts. */
    data object OffNotSaved : DriveSyncStatus

    data object Full : DriveSyncStatus

    /** Anything else: the next sync tries again. */
    data object Failed : DriveSyncStatus
}

/**
 * Drive sync on this phone (dev-docs/drive-sync.md): turned on and off from Settings, run in the
 * background ([DriveSyncWorker]) when the app comes to the front and a few seconds after a ride changes,
 * one sync at a time. **Debug builds only** ([BuildConfig.DRIVE_SYNC]) until the Play Data Safety form
 * says so (maintainer, 2026-10-22): a release build never offers it, never asks for the grant, and never
 * sends anything to Drive. The debug log hears how each sync went by its outcome alone: never a ride.
 */
internal object DriveSync {
    private val _view = MutableStateFlow<DriveSyncView?>(null)

    /** Null until read. */
    val view: StateFlow<DriveSyncView?> = _view

    private val running = Mutex()

    private const val WORK = "drive-sync"

    // Long enough that a run of edits (a ride added, then its alerts set) syncs once.
    private const val CHANGE_DELAY_SECONDS = 5L

    // Its own client: Drive's answers are judged by KtorDriveAppData, and its requests aren't TfL's to time.
    private val http by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
        }
    }

    /** Reads whether sync is on, for Settings, once per process. */
    suspend fun load(context: Context) {
        if (_view.value != null) return
        val on = DriveSyncLocal.of(context).isOn()
        // Unless turned on or off while it was read.
        _view.compareAndSet(null, DriveSyncView(on, DriveSyncStatus.Waiting))
    }

    /**
     * Turns sync on, asking for the grant first: Google's screen where only it can give it (its intent
     * returned for the caller to launch, its result handed to [granted]), at once where it's given already.
     */
    suspend fun turnOn(context: Context): DriveAuthorization.Answer {
        val answer = DriveAuthorization.ask(context)
        settle(context, answer)
        return answer
    }

    /** Google's screen's result for [turnOn]. */
    suspend fun granted(context: Context, data: Intent?) = settle(context, DriveAuthorization.granted(context, data))

    /** Turns sync off on this phone, keeping what it knows, so turning it back on resumes. */
    suspend fun turnOff(context: Context) {
        val saved = saved(DriveSyncLocal.of(context), on = false)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
        // Off for this run either way ([request], [syncNow]); one that couldn't be kept says so.
        _view.value = DriveSyncView(false, if (saved) DriveSyncStatus.Waiting else DriveSyncStatus.OffNotSaved)
    }

    /**
     * Keeps the switch in [local]: false where this phone's files couldn't be written, which the caller says
     * on the switch rather than letting it escape the tap (Codex on #1151). Logged by its type and frames.
     */
    internal suspend fun saved(local: DriveSyncLocal, on: Boolean): Boolean = try {
        local.setOn(on)
        true
    } catch (e: IOException) {
        warn("couldn't turn ${if (on) "on" else "off"}: this phone's files (${e.javaClass.simpleName}${framesOf(e)})")
        false
    }

    private suspend fun settle(context: Context, answer: DriveAuthorization.Answer) {
        when (answer) {
            is DriveAuthorization.Answer.Granted -> {
                if (!saved(DriveSyncLocal.of(context), on = true)) {
                    _view.value = DriveSyncView(false, DriveSyncStatus.TurnOnFailed)
                    return
                }
                _view.value = DriveSyncView(true, DriveSyncStatus.Waiting)
                request(context)
            }
            is DriveAuthorization.Answer.NeedsScreen -> Unit
            DriveAuthorization.Answer.Failed -> _view.value = DriveSyncView(_view.value?.on == true, DriveSyncStatus.SignInFailed)
        }
    }

    /**
     * Asks for a sync each time the app comes on screen, and [CHANGE_DELAY_SECONDS] after the rides change,
     * for the process's life in [scope]. A sync that changed the rides here asks for one more, which finds
     * nothing new and sends nothing. Nothing in a release build.
     */
    fun start(context: Context, scope: CoroutineScope) {
        if (!BuildConfig.DRIVE_SYNC) return
        val app = context.applicationContext
        scope.launch { AppForeground.onScreen.filter { it }.collect { request(app) } }
        scope.launch {
            // The first is what's stored at start, which the app coming on screen already syncs. A read
            // failure ends the flow (said with null), and the next app start follows the rides again.
            DataStoreFavoriteJourneysStore.from(app, warn = ::warn).syncChanges().drop(1).filterNotNull()
                .collect { request(app, CHANGE_DELAY_SECONDS) }
        }
    }

    /**
     * Asks for a sync after [delaySeconds], on a network: the latest ask replaces one still waiting, so a
     * run of ride changes syncs once. Nothing in a release build, or while sync is off.
     */
    suspend fun request(context: Context, delaySeconds: Long = 0) {
        if (!BuildConfig.DRIVE_SYNC) return
        load(context)
        if (_view.value?.on != true) return
        val work = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, work)
    }

    /** One sync now, if sync is on: how it went, or null when it's off or couldn't start. */
    suspend fun syncNow(context: Context): SyncOutcome? = running.withLock {
        if (!BuildConfig.DRIVE_SYNC) return@withLock null
        // Once begun, a sync finishes every step, even when a newer ask replaces its job (Codex on #1151):
        // stopped before the widget's pins follow the rides, a ride removed here would keep its pins, as the
        // next sync finds nothing more changed. The newer ask waits its turn ([running]). A sync stopped
        // anyway, with its process, is settled by the next from the merge it kept ([SyncState.Pending]).
        withContext(NonCancellable) { syncOnce(context) }
    }

    private suspend fun syncOnce(context: Context): SyncOutcome? {
        val local = DriveSyncLocal.of(context)
        return try {
            // Off here, though perhaps not kept so ([turnOff]), or off on this phone's files.
            if (_view.value?.on == false || !local.isOn()) return null
            _view.value = DriveSyncView(true, DriveSyncStatus.Syncing)
            val journeys = DataStoreFavoriteJourneysStore.from(context.applicationContext, warn = ::warn)
            val drive = KtorDriveAppData(http, token = { DriveAuthorization.token(context) }, elapsed = SystemClock::elapsedRealtime, warn = ::warn)
            val state = local.state()
            // The merge is kept before the rides are written ([SyncState.Pending]), so a sync stopped between
            // the two, the process gone or the state unwritable, is settled by the next (Codex on #1151).
            val run = SyncRun(drive, RideSyncedStore(journeys), local.phone(), BuildConfig.DEBUG, SystemClock::elapsedRealtime, ::warn, keep = local::saveState)
            val (outcome, next) = run.run(state)
            if (next != state) local.saveState(next)
            report(outcome)
            say(statusOf(outcome))
            // Whether or not the upload went: rides changed here take the widget's pins with them now, since
            // the next sync finds nothing more changed (Codex on #1151).
            val changed = when (outcome) {
                is SyncOutcome.Synced -> outcome.changed
                is SyncOutcome.Failed -> outcome.changed
                else -> emptySet()
            }
            if (RideSync.RIDES in changed) followRides(context, journeys)
            outcome
        } catch (e: IOException) {
            // This phone's own files couldn't be read or written: nothing synced, said so, and tried again
            // by the next sync. By its type and frames alone.
            warn("couldn't sync: this phone's files (${e.javaClass.simpleName}${framesOf(e)})")
            say(DriveSyncStatus.Failed)
            null
        }
    }

    // How the sync went, said while sync is on: one turned off meanwhile stays off.
    private fun say(status: DriveSyncStatus) {
        _view.update { view -> if (view?.on == true) DriveSyncView(true, status) else view }
    }

    // Rides another phone changed take the widget's pins with them, as the same edits here do: a removed
    // ride's pins go (removeFavoriteJourney), and so do a hidden line's (setRideLineHidden; Codex on #1151).
    // A line shown again comes back with the card's next report, which judges its departures.
    private suspend fun followRides(context: Context, journeys: DataStoreFavoriteJourneysStore) {
        try {
            val saved = journeys.journeys().first() ?: return
            val hides = saved.filter { it.hiddenLines.isNotEmpty() }.associate { it.key to Journeys.hiddenLineIds(it) }
            WidgetJourneysWrites.lock.withLock {
                WidgetSnapshotStore(context.applicationContext)
                    .updateWidgetJourneys(WidgetJourneysReport(saved.mapTo(HashSet()) { it.key }, emptyList(), hidden = hides), emptyList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The rides are in step; only the prompt pin update is lost, and the main screen's next report makes it.
            warn("widget pins after sync failed (${e.javaClass.simpleName}${framesOf(e)})")
        }
    }

    private fun report(outcome: SyncOutcome) = when (outcome) {
        is SyncOutcome.Synced -> StopdashDebugLog.info(
            "sync: synced, %s entr(ies) removed here%s",
            outcome.removed.values.sumOf { it.size },
            if (outcome.newerPhone) ", a newer phone's file skipped" else "",
        )
        is SyncOutcome.Failed -> warn("couldn't sync: ${outcome.failure}")
        SyncOutcome.StoreUnsynced -> warn("couldn't sync: the rides file is a newer build's")
        SyncOutcome.Overtaken -> StopdashDebugLog.info("sync: overtaken by a change here; the next sync takes it in")
    }

    internal fun statusOf(outcome: SyncOutcome, now: Instant = Instant.now()): DriveSyncStatus = when (outcome) {
        is SyncOutcome.Synced -> if (outcome.newerPhone) DriveSyncStatus.NewerPhone else DriveSyncStatus.Synced(now)
        is SyncOutcome.Failed -> when (outcome.failure) {
            DriveFailure.Offline, DriveFailure.RateLimited -> DriveSyncStatus.Offline
            DriveFailure.SignInNeeded -> DriveSyncStatus.SignIn
            DriveFailure.Full -> DriveSyncStatus.Full
            else -> DriveSyncStatus.Failed
        }
        SyncOutcome.StoreUnsynced -> DriveSyncStatus.Failed
        SyncOutcome.Overtaken -> DriveSyncStatus.Waiting
    }

    private fun warn(message: String) = StopdashDebugLog.warning("sync: %s", message)
}

/** [e]'s first stack frames, for a log line that names a failure by its type and where it came from. */
internal fun framesOf(e: Throwable): String = e.stackTrace.take(3).joinToString("") { " at $it" }

/**
 * Runs one sync ([DriveSync.syncNow]) when a network is there, retrying a few times where Drive couldn't
 * be reached or the store changed meanwhile.
 */
internal class DriveSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val outcome = DriveSync.syncNow(applicationContext)
        val again = outcome == SyncOutcome.Overtaken ||
            outcome is SyncOutcome.Failed && (outcome.failure == DriveFailure.Offline || outcome.failure == DriveFailure.RateLimited)
        return if (again && runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
