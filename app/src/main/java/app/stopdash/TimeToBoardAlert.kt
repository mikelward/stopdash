package app.stopdash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripProgress
import app.stopdash.ui.ActiveTripTracker
import app.stopdash.ui.ActiveTripTracker.BoardPost
import java.time.Duration
import java.time.Instant

/**
 * "Time to board" for a trip on the way (SPEC *On the way*; maintainer, 2026-09-27): the train the
 * rider waits for is about to arrive. Its own channel, apart from "Get off soon", so either can be
 * muted alone; high importance with sound and vibration, as that one is. Its header counts down to
 * the train. It lasts only as long as the answer it counts from is live ([ActiveTripTracker.CURRENT_FOR]
 * from when that answer was had, the moment every other surface ages it from), each fresh answer
 * posting it again silently, so a countdown no longer stood behind comes down by itself, even with
 * the app gone (SPEC D4); and never past two minutes after the train was due.
 * Posted by the trip's tracker, once for each train; taken down once the rider is on board, another
 * train is followed, a refresh fails, or the trip ends. Tapping it opens the trip.
 */
internal object TimeToBoardAlert {
    const val CHANNEL_ID = "time-to-board"

    private const val NOTIFICATION_ID = 4102

    // How long past the train's time an alert stays: long enough for one running a little late.
    private val AFTER_DUE: Duration = Duration.ofMinutes(2)

    private val VIBRATION = longArrayOf(0, 400, 200, 400)

    /** Creates the channel (idempotent), so the user can tune it in Settings before the first alert. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.time_to_board_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.time_to_board_channel_description)
            enableVibration(true)
            vibrationPattern = VIBRATION
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts that [waiting]'s train on [trip] is about to arrive, as [how] says ([BoardPost]), from the
     * answer had at [answeredAt]: whether it's up. A first one ([BoardPost.NEW]) is false (and logged,
     * with no stop or place) when notifications are off, to be tried again; one already past its time
     * by [AFTER_DUE] isn't posted, and counts as said: the train has gone. One kept up to date
     * ([BoardPost.KEEP]) is false once it's no longer showing, swiped away or timed out, and isn't
     * brought back; nor is one this answer can't keep up, which the tracker then takes down. Any whose
     * answer is no longer live by [now] isn't posted.
     */
    fun post(
        context: Context,
        trip: ActiveTrip,
        waiting: TripProgress.Waiting,
        how: BoardPost,
        answeredAt: Instant,
        now: Instant,
        log: (String) -> Unit,
    ): Boolean {
        val due = waiting.due ?: return how == BoardPost.NEW
        val gone = due.plus(AFTER_DUE)
        if (!gone.isAfter(now)) {
            if (how == BoardPost.NEW) log("on the way: board alert not shown, past its train")
            return how == BoardPost.NEW
        }
        // Until the answer it counts from stops being live, or the train has gone, whichever is first:
        // the answer's age runs from when it was had, not from now, which a slow request can make later.
        val lasts = Duration.between(now, minOf(gone, answeredAt.plus(ActiveTripTracker.CURRENT_FOR)))
        if (lasts <= Duration.ZERO) return false
        if (how == BoardPost.KEEP && !showing(context, now)) return false
        ensureChannel(context)
        if (!GetOffSoonAlert.canNotify(context) ||
            NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            log("on the way: board alert not shown, notifications off")
            return false
        }
        val title = context.getString(R.string.on_the_way_board, waiting.lineName, waiting.leg.fromName)
        // Said in the trip's own notification where it's up, so it's the only one (maintainer, 2026-10-09):
        // its chip counts the minutes the header did, and the step below says when the train's due. A new
        // one a more pressing alert would hide waits for a later refresh, to be heard when it shows.
        if (how == BoardPost.NEW && OnTheWayNotification.carried(context) && TripAlerts.outranked(context, TripAlerts.Kind.BOARD, now)) {
            log("on the way: board alert held, a more pressing alert is showing")
            return false
        }
        if (TripAlerts.offer(context, TripAlerts.Alert(TripAlerts.Kind.BOARD, title, null, now.plus(lasts), sound = how == BoardPost.NEW))) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return true
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(GetOffSoonAlert.EXTRA_OPEN_ON_THE_WAY, true)
        val pending = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_route_arrow)
            .setContentTitle(title)
            .setSubText(context.getString(R.string.on_the_way_title, trip.destinationName))
            // Counts down to the train in the header, live, rather than a time that goes stale.
            .setWhen(due.toEpochMilli())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(VIBRATION)
            .setContentIntent(pending)
            .setAutoCancel(true)
            // Posted again while still up: heard once, not twice.
            .setOnlyAlertOnce(true)
            .setSilent(how != BoardPost.NEW)
            .setTimeoutAfter(lasts.toMillis())
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
            log("on the way: board alert refused: ${e::class.simpleName}")
            false
        }
    }

    /** Clears a posted alert: the rider boarded, another train is followed, a refresh failed, or the trip ended. */
    fun cancel(context: Context) {
        TripAlerts.clear(TripAlerts.Kind.BOARD)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    // Whether the alert is still up: not swiped away, nor timed out; said in the trip's notification, while that's up.
    private fun showing(context: Context, now: Instant): Boolean =
        (TripAlerts.standing(TripAlerts.Kind.BOARD, now) && OnTheWayNotification.carried(context)) ||
            context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID }
}
