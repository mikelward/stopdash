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
import app.stopdash.domain.ON_THE_WAY_MIN_GAP
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.TripFixes
import app.stopdash.domain.TripProgress
import app.stopdash.domain.awaitRefresh
import app.stopdash.domain.refreshFix
import app.stopdash.ui.ActiveTripTracker
import app.stopdash.ui.ON_THE_WAY_REFRESH
import app.stopdash.ui.fromTfl
import app.stopdash.ui.nextStepText
import app.stopdash.ui.stepTime
import app.stopdash.ui.stepTimeText
import app.stopdash.watch.WatchTripSync
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Follows a trip on the way with the app closed (SPEC *On the way*): a foreground service whose
 * ongoing notification is the trip's next step, refreshed from the tracker every
 * [ON_THE_WAY_REFRESH] until the rider arrives or ends the trip, when it stops itself. Started by the
 * activity (on Start, or on opening with a trip kept), never from the background.
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
        // Started by the app opening with the trip on: the rider is back with it, so a notification they
        // swiped away earlier shows again, as the service must post one to run at all.
        OnTheWayNotification.dismissedTrip = null
        OnTheWayNotification.ensureChannel(this)
        // Plain, on the main thread before startForeground: the Live Update's bar, chip and boarding are
        // worked out over the route and the board, so they come with the first update off it, a moment later.
        val first = OnTheWayNotification.build(this, tracker.trip.value, tracker.progress.value, tracker.failed.value, tracker.updatedAt.value, Instant.now(), tracker.answeredAt.value, live = false)
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
                // grown old turns to Checking… (or Updating…) though nothing else changed.
                val shown = launch {
                    // The trip's alerts too ([TripAlerts]): said in this notification, shown again as they change.
                    val answers = combine(tracker.updatedAt, tracker.answeredAt, tracker.nextBoard, TripAlerts.alerts) { updatedAt, answeredAt, board, _ -> Triple(updatedAt, answeredAt, board) }
                    combine(tracker.trip, tracker.progress, tracker.failed, answers, ticks(NOTIFICATION_TICK)) { trip, progress, failed, (updatedAt, answeredAt, board), _ ->
                        if (trip != null) {
                            val boarding = OnTheWayLiveUpdate.boarding(trip, progress, board, Instant.now())
                            OnTheWayNotification.show(this@OnTheWayService, trip, progress, failed, updatedAt, answeredAt, boarding)
                        }
                    }.flowOn(Dispatchers.Default).collect()
                }
                // The trip on a paired watch with the app, for as long as it's followed here.
                val onWatch = launch { WatchTripSync.follow(this@OnTheWayService, tracker) }
                // Arrived, ended, past the cap, or failed: nothing left to follow here.
                followThenStop(
                    warn = { StopdashDebugLog.warning("on the way: %s", it) },
                    failed = ::notFollowing,
                    stop = {
                        shown.cancel()
                        onWatch.cancel()
                        // Its alerts go with it: nothing left to say them in.
                        TripAlerts.clearAll()
                        // Off the watch too: a trip no longer followed isn't shown there as current.
                        WatchTripSync.clear(this@OnTheWayService)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    },
                ) {
                    // A fix seen while the trip is shown refreshes it at once ([TripFixes]).
                    val fixes = MainActivity.tripFixes
                    followTrip(tracker.trip, tracker.starting, ON_THE_WAY_REFRESH, OnTheWayWakeLock.LIMIT, restore = tracker::restore,
                        startedFor = { Duration.between(it.startedAt, Instant.now()) },
                        keepAwake = { awake?.let(OnTheWayWakeLock::renew) }, fixes = fixes.latest) { seen ->
                        // A fix the open app saw is used whether or not this service may take its own.
                        tracker.refresh(refreshFix(seen, fixes, tracker.trip.value, Instant.now(), mayTake = located && locationAllowed(), ::riderIfWanted))
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
 * started, once no start is in flight ([starting]). A trip started in place of it is refreshed at
 * once, and capped from its own start, the old one gone while it starts not taken for the end. A trip gone mid-wait ends it at once, so the ongoing notification never outlives the trip by a wait. A fix from [fixes] (the
 * trip shown, and moving) refreshes it sooner, no less than [minGap] after the last ([awaitRefresh]),
 * and is handed to that [refresh]; the timer's hands it none.
 */
internal suspend fun followTrip(
    trip: StateFlow<ActiveTrip?>,
    starting: StateFlow<Int>,
    every: Duration,
    cap: Duration,
    restore: suspend () -> Boolean = { true },
    startedFor: (ActiveTrip) -> Duration = { Duration.ZERO },
    keepAwake: () -> Unit = {},
    fixes: StateFlow<TripFixes.Seen?> = MutableStateFlow(null),
    minGap: Duration = ON_THE_WAY_MIN_GAP,
    refresh: suspend (TripFixes.Seen?) -> Unit,
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
    if (read == null) return
    var woke: TripFixes.Seen? = null
    var refreshing: Instant? = null
    // Each trip in turn: one started in place of another is followed from its own start. Replace ends
    // the trip on the way before the new one is kept, so a trip gone while a start is in flight is
    // waited through, not taken for the end.
    while (true) {
        starting.first { it == 0 }
        val following = trip.value ?: return
        // A fix taken for one trip is never handed to another's refresh: it may place the rider before
        // this trip began.
        if (following.startedAt != refreshing) woke = null
        refreshing = following.startedAt
        // The cap counts from when the trip started ([startedFor]), not from this start of the service:
        // one restarted after the app died, or reopened, doesn't get four more hours.
        val left = cap.minus(startedFor(following))
        val capped = left.isNegative || left.isZero || withTimeoutOrNull(left.toMillis()) {
            while (trip.value?.startedAt == following.startedAt) {
                keepAwake()
                refresh(woke)
                // Whichever comes first: the trip gone, a fix, the timer (null), or another trip started in
                // its place (null), whose next ride's board is read now rather than a wait later.
                woke = merge(
                    trip.filter { it == null }.map<ActiveTrip?, TripFixes.Seen?> { null },
                    flow { emit(awaitRefresh(fixes, every, minGap, trip, from = following.startedAt)) },
                ).first()
            }
        } == null
        // Past this trip's cap, the service lets go, unless another is being started or was started in its place.
        if (capped) {
            starting.first { it == 0 }
            if (trip.value?.startedAt == following.startedAt) return
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
     * stops from no recent answer ([updatedAt], as on the trip's screen) say "Checking…" beside the
     * step's last known time where it had an answer ([answeredAt]), or say it's updating where not.
     */
    fun build(
        context: Context,
        trip: ActiveTrip?,
        progress: TripProgress?,
        failed: Boolean,
        updatedAt: Instant?,
        now: Instant,
        answeredAt: Instant? = null,
        // Where to board the next ride, when it's the step ([OnTheWayLiveUpdate.boarding]).
        boarding: OnTheWayLiveUpdate.Boarding? = null,
        // False for a plain notification with no bar or chip: work over the route kept off the main thread.
        live: Boolean = true,
        // The trip's alert to say in place of the step's title ([TripAlerts]), on its own channel.
        alert: TripAlerts.Alert? = null,
    ): Notification {
        val current = ActiveTripTracker.isCurrent(updatedAt, now)
        val (title, detail) = nextStepText(context.resources, progress, now, current, asOf = answeredAt)
        // Failed with an answer of the step's own: the failure said, the last known time kept beside it, as the screen keeps it.
        val failedText = (stepTime(progress, current = true, now).takeIf { !current && fromTfl(progress) && answeredAt != null })
            ?.let { context.getString(R.string.on_the_way_failed_time, stepTimeText(context.resources, it, now)) }
            ?: context.getString(R.string.on_the_way_failed)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(GetOffSoonAlert.EXTRA_OPEN_ON_THE_WAY, true)
        val pending = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // A Live Update (Android 16): promoted to the top of the shade and the lock screen, with a chip in
        // the status bar, where Android and the rider allow it; a plain ongoing notification where not.
        // The chip and the bar say only what the text does: with no current answer, neither.
        val shown = live && !failed && (current || !fromTfl(progress))
        val stepText = if (failed) failedText else OnTheWayLiveUpdate.withBoarding(context.resources, boarding.takeIf { shown }, detail)
        // An alert takes the title, and its channel, so it's heard (once) and muted as its own notification
        // was; its text where it has more to say than the step, which otherwise stays below it.
        val builder = NotificationCompat.Builder(context, alert?.kind?.channelId ?: CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_appbar_route_arrow)
            .setContentTitle(alert?.title ?: title)
            .setContentText(alert?.text ?: stepText)
            .setSubText(trip?.let { context.getString(R.string.on_the_way_title, it.destinationName) })
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(alert?.sound != true)
            .setShowWhen(false)
            .setContentIntent(pending)
            // Dismissed, it stays dismissed for the trip, as Android asks of a Live Update ([dismissedTrip]).
            .setDeleteIntent(trip?.let { dismissIntent(context, it) })
            .setRequestPromotedOngoing(true)
        if (trip != null && shown) OnTheWayLiveUpdate.style(trip, progress, now)?.let(builder::setStyle)
        if (shown) OnTheWayLiveUpdate.chipText(context.resources, progress, now, boarding, OnTheWayLiveUpdate.distanceSystem(context))?.let(builder::setShortCriticalText)
        if (alert?.sound == true) builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        return builder.build()
    }

    /**
     * Whether the trip's notification is up, so the trip's alerts are said in it ([TripAlerts]): not
     * swiped away, and its own channel not muted. Where it isn't, each posts as a notification of its own.
     */
    fun carried(context: Context): Boolean =
        GetOffSoonAlert.canNotify(context) &&
            NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE &&
            context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == ID }

    /**
     * The trip ([ActiveTrip.startedAt]) whose notification the rider swiped away: not posted again for
     * it, as Android asks of a Live Update, until another trip starts or the app is opened on it again
     * (the service must post one to start). In memory, as a process that dies takes the service with it.
     */
    @Volatile
    internal var dismissedTrip: Instant? = null

    private fun dismissIntent(context: Context, trip: ActiveTrip): PendingIntent = PendingIntent.getBroadcast(
        context, 2,
        Intent(context, OnTheWayDismissReceiver::class.java).putExtra(OnTheWayDismissReceiver.EXTRA_STARTED_AT, trip.startedAt.toEpochMilli()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun show(context: Context, trip: ActiveTrip, progress: TripProgress?, failed: Boolean, updatedAt: Instant?, answeredAt: Instant? = null, boarding: OnTheWayLiveUpdate.Boarding? = null) {
        if (!GetOffSoonAlert.canNotify(context)) return
        if (dismissedTrip == trip.startedAt) return
        val now = Instant.now()
        val alert = TripAlerts.top(context, now)
        try {
            NotificationManagerCompat.from(context).notify(ID, build(context, trip, progress, failed, updatedAt, now, answeredAt, boarding, alert = alert))
            // Heard: kept quiet on every showing after this one.
            if (alert?.sound == true) TripAlerts.heard(alert.kind, alert)
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
    // sure it must be depends on what it's for ([OnTheWay.sureEnoughFor]); one surer still is waited
    // for where it could tell more ([OnTheWay.preferredFor]), the vaguer one kept if none comes.
    val fix = location.preciseFix(sureEnough = OnTheWay.preferredFor(trip, now)) ?: return null
    return OnTheWay.usableFix(fix, trip, now)
}

/** Hears the trip's ongoing notification swiped away (its delete intent), so it isn't posted again for that trip. */
class OnTheWayDismissReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val startedAt = intent.getLongExtra(EXTRA_STARTED_AT, -1L).takeIf { it >= 0 } ?: return
        OnTheWayNotification.dismissedTrip = Instant.ofEpochMilli(startedAt)
        StopdashDebugLog.info("on the way: %s", "ongoing notification dismissed; not posted again for this trip")
    }

    companion object {
        const val EXTRA_STARTED_AT = "app.stopdash.extra.ON_THE_WAY_STARTED_AT"
    }
}
