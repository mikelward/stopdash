package app.stopdash

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.stopdash.data.AndroidLocationProvider
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.LocationFix
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.TripProgress
import app.stopdash.ui.ActiveTripTracker
import app.stopdash.ui.ON_THE_WAY_REFRESH
import app.stopdash.ui.nextStepText
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Follows a trip on the way with the app closed (SPEC *On the way*): a foreground service whose
 * ongoing notification is the trip's next step, refreshed from the tracker every
 * [ON_THE_WAY_REFRESH] until the rider arrives or ends the trip, when it stops itself. Started by the
 * activity (on Start, or on opening with a trip kept), never from the background. Held back from
 * merging until the maintainer's Play foreground-service declaration.
 */
class OnTheWayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null
    private var awake: PowerManager.WakeLock? = null
    // Whether the service runs with the location type, so a fix may be taken (and is still allowed).
    private var located = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tracker = MainActivity.activeTrip(applicationContext)
        OnTheWayNotification.ensureChannel(this)
        val first = OnTheWayNotification.build(this, tracker.trip.value, tracker.progress.value, tracker.failed.value, tracker.updatedAt.value, Instant.now())
        // Location too when it's allowed: a fix just after boarding shows a rider left behind.
        val canLocate = locationAllowed()
        val withLocation = enterForeground(canLocate, warn = { StopdashDebugLog.warning("on the way: %s", it) }) { type ->
            startForeground(OnTheWayNotification.ID, first, type)
        }
        followed(withLocation != null)
        if (withLocation == null) {
            // Refused (started from the background, or the type not allowed): the activity's own
            // foreground loop still follows the trip while the app is open, and its screen says so.
            stopSelf()
            return START_NOT_STICKY
        }
        // Read again on each start (the app starts the service each time it opens with a trip), so
        // location allowed partway through a trip is used from then on, by the loop already running.
        located = withLocation
        _running.value = true
        if (following?.isActive != true) {
            awake = awake ?: OnTheWayWakeLock.acquire(this)
            following = scope.launch {
                // Shown again on each change, and on each tick, so its minutes count down and an answer
                // grown old turns to Updating… though nothing else changed.
                val shown = launch {
                    combine(tracker.trip, tracker.progress, tracker.failed, tracker.updatedAt, ticks(NOTIFICATION_TICK)) { trip, progress, failed, updatedAt, _ ->
                        if (trip != null) OnTheWayNotification.show(this@OnTheWayService, trip, progress, failed, updatedAt)
                    }.collect()
                }
                // Arrived, ended, past the cap, or failed: nothing left to follow here.
                followThenStop(
                    warn = { StopdashDebugLog.warning("on the way: %s", it) },
                    failed = ::notFollowing,
                    stop = {
                        shown.cancel()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    },
                ) {
                    followTrip(tracker.trip, tracker.starting, ON_THE_WAY_REFRESH, OnTheWayWakeLock.LIMIT, restore = tracker::restore,
                        startedFor = { Duration.between(it.startedAt, Instant.now()) },
                        keepAwake = { awake?.let(OnTheWayWakeLock::renew) }) {
                        tracker.refresh(if (located && locationAllowed()) riderIfWanted(tracker.trip.value) else null)
                    }
                }
            }
        }
        // The trip is kept on disk; a process that dies takes the service with it, and the next
        // opening of the app starts it again.
        return START_NOT_STICKY
    }

    private suspend fun riderIfWanted(trip: ActiveTrip?): LocationFix? = onTheWayFix(location, trip)

    private fun locationAllowed(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val location by lazy {
        AndroidLocationProvider(applicationContext, warn = { StopdashDebugLog.warning("on the way: %s", it) }, remembers = false)
    }

    override fun onDestroy() {
        _running.value = false
        awake?.let(OnTheWayWakeLock::release)
        awake = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        // How often the ongoing notification is shown again with nothing new: its minutes and freshness.
        private val NOTIFICATION_TICK: Duration = Duration.ofSeconds(15)

        private fun ticks(every: Duration) = flow {
            while (true) {
                emit(Unit)
                delay(every.toMillis())
            }
        }

        private val _running = MutableStateFlow(false)

        /** Whether the service is following the trip, so the activity's own loop stands down. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _refused = MutableStateFlow(false)

        /** Whether Android refused to follow the trip with the app closed: the trip's screen says so. */
        val refused: StateFlow<Boolean> = _refused.asStateFlow()

        /** Starts following the trip on the way; call from the foreground only. */
        fun start(context: Context) = promote { ContextCompat.startForegroundService(context, Intent(context, OnTheWayService::class.java)) }

        /** Runs [start], a foreground start Android may refuse: whether it went ahead. */
        internal fun promote(start: () -> Unit): Boolean {
            try {
                start()
            } catch (e: IllegalStateException) {
                StopdashDebugLog.warning("on the way: %s", "foreground service refused: ${e::class.simpleName}")
                followed(false)
                return false
            }
            followed(true)
            return true
        }

        /** Following with the app closed stopped short (it failed): the trip's screen says so, as for a refusal. */
        internal fun notFollowing() {
            _refused.value = true
        }

        /** Notes whether a foreground start went ahead, for [refused]. */
        internal fun followed(started: Boolean) {
            _refused.value = !started
        }
    }
}

/**
 * Puts the service in the foreground through [start], with the location type too when [canLocate]:
 * true when it runs with location, false without, null when it was refused (started from the
 * background). Location refused as it starts (permission revoked since the check, Android 14+) falls
 * back to following without it rather than crashing.
 */
internal fun enterForeground(canLocate: Boolean, warn: (String) -> Unit, start: (type: Int) -> Unit): Boolean? {
    val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    if (canLocate) {
        try {
            start(special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            return true
        } catch (e: SecurityException) {
            warn("location refused as the service started: ${e::class.simpleName}")
        } catch (e: IllegalStateException) {
            warn("foreground service refused: ${e::class.simpleName}")
            return null
        }
    }
    return try {
        start(special)
        false
    } catch (e: IllegalStateException) {
        warn("foreground service refused: ${e::class.simpleName}")
        null
    } catch (e: SecurityException) {
        warn("foreground service refused: ${e::class.simpleName}")
        null
    }
}

/**
 * Runs [follow], then [stop]s the service however it ends: done, cancelled, or failed. A failure is
 * logged ([warn], its kind only) rather than left to keep a stale notification up and the app's own
 * loop stood down.
 */
internal suspend fun followThenStop(warn: (String) -> Unit, stop: () -> Unit, failed: () -> Unit = {}, follow: suspend () -> Unit) {
    try {
        follow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        warn("following failed: ${e::class.simpleName}")
        failed()
    } finally {
        stop()
    }
}

/**
 * Refreshes the trip every [every] until it's gone (arrived or ended), or until [cap] after it
 * started, once no start is in flight ([starting]). A trip gone mid-wait ends it
 * at once, so the ongoing notification never outlives the trip by a wait.
 */
internal suspend fun followTrip(
    trip: StateFlow<ActiveTrip?>,
    starting: StateFlow<Int>,
    every: Duration,
    cap: Duration,
    restore: suspend () -> Boolean = { true },
    startedFor: (ActiveTrip) -> Duration = { Duration.ZERO },
    keepAwake: () -> Unit = {},
    refresh: suspend () -> Unit,
) {
    // Started from the Start tap, the trip may not be kept yet: a start in flight is waited for.
    starting.first { it == 0 }
    // At most [cap]: a trip never ended or arrived isn't followed in the background (or kept awake
    // for) indefinitely; the app's own loop still follows it whenever it's open.
    // A kept trip that couldn't be read (a storage error) isn't taken for none: read again each
    // [every], as the app's own loop does, until it can be, for at most [OnTheWayWakeLock.READ_FOR]: its
    // age, which the cap counts from, isn't known until it's read.
    // [keepAwake] before each read as well as each refresh: reads failing for longer than one hold of
    // the wake lock mustn't let the phone sleep through the next.
    val read = withTimeoutOrNull(minOf(cap, OnTheWayWakeLock.READ_FOR).toMillis()) {
        while (true) {
            keepAwake()
            if (restore()) break
            delay(every.toMillis())
        }
    }
    val following = trip.value ?: return
    if (read == null) return
    // The cap counts from when the trip started ([startedFor]), not from this start of the service:
    // one restarted after the app died, or reopened, doesn't get four more hours.
    val left = cap.minus(startedFor(following))
    if (left.isNegative || left.isZero) return
    withTimeoutOrNull(left.toMillis()) {
        while (trip.value != null) {
            keepAwake()
            refresh()
            withTimeoutOrNull(every.toMillis()) { trip.first { it == null } }
        }
    }
}

/**
 * Keeps the CPU awake while a trip is followed with the screen off: a foreground service alone doesn't,
 * and a sleeping CPU would stall the refresh (and the get-off alert) for as long as it sleeps. Partial
 * (screen stays off), held only for the trip: renewed for [HOLD] on each refresh, so a stalled loop
 * lets go within minutes, and the trip is followed for at most [LIMIT], so a trip never left can't
 * hold it.
 */
internal object OnTheWayWakeLock {
    const val TAG = "stopdash:on-the-way"
    val LIMIT: Duration = Duration.ofHours(4)

    /**
     * How long a kept trip that can't be read is read again for: its age isn't known until it's read,
     * so this is short, not [LIMIT] again. The app's own loop still reads it whenever the app is open.
     */
    val READ_FOR: Duration = Duration.ofMinutes(10)
    private val HOLD: Duration = Duration.ofMinutes(2)

    fun acquire(context: Context): PowerManager.WakeLock =
        context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
            setReferenceCounted(false)
            acquire(HOLD.toMillis())
        }

    /** Held for another [HOLD], from a refresh. */
    fun renew(lock: PowerManager.WakeLock) = lock.acquire(HOLD.toMillis())

    fun release(lock: PowerManager.WakeLock) {
        if (lock.isHeld) lock.release()
    }
}

/** The trip's ongoing notification: its next step, on a quiet channel of its own. */
internal object OnTheWayNotification {
    const val CHANNEL_ID = "on-the-way"
    const val ID = 4100

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.on_the_way), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.on_the_way_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * The notification for [trip] at [progress] at [now]; [failed] says it isn't current, and a train's
     * time or stops from no recent answer ([updatedAt], as on the trip's screen) say it's updating.
     */
    fun build(context: Context, trip: ActiveTrip?, progress: TripProgress?, failed: Boolean, updatedAt: Instant?, now: Instant): Notification {
        val (title, detail) = nextStepText(context.resources, progress, now, current = ActiveTripTracker.isCurrent(updatedAt, now))
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(GetOffSoonAlert.EXTRA_OPEN_ON_THE_WAY, true)
        val pending = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_appbar_route_arrow)
            .setContentTitle(title)
            .setContentText(if (failed) context.getString(R.string.on_the_way_failed) else detail)
            .setSubText(trip?.let { context.getString(R.string.on_the_way_title, it.destinationName) })
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .build()
    }

    fun show(context: Context, trip: ActiveTrip, progress: TripProgress?, failed: Boolean, updatedAt: Instant?) {
        if (!GetOffSoonAlert.canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(ID, build(context, trip, progress, failed, updatedAt, Instant.now()))
        } catch (e: SecurityException) {
            StopdashDebugLog.warning("on the way: %s", "ongoing notification refused: ${e::class.simpleName}")
        }
    }
}

/**
 * A precise fix for a trip on the way, only while one could tell anything ([OnTheWay.wantsFix]): on a
 * walk to a boarding stop, while waiting there, in the minutes after the train leaves it, and as it
 * nears where the rider gets off. Never logged or kept, only compared with stops' public positions.
 * The service's loop and the app's own both take it.
 */
internal suspend fun onTheWayFix(location: AndroidLocationProvider, trip: ActiveTrip?): LocationFix? {
    val now = Instant.now()
    if (trip == null || !OnTheWay.wantsFix(trip, now)) return null
    // GPS/fused, waited for: a quick coarse fix could never settle it, so it isn't asked for. How
    // sure it must be depends on what it's for ([OnTheWay.sureEnoughFor]).
    val sure = OnTheWay.sureEnoughFor(trip, now)
    val fix = location.preciseFix(sureEnough = sure) ?: return null
    return OnTheWay.usableFix(fix, trip, now)
}
