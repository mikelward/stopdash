package app.stopdash

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripProgress
import app.stopdash.ui.stopsText
import java.time.Duration
import java.time.Instant

/**
 * "Get off soon" for a trip on the way (SPEC *On the way*): its own channel, high importance with
 * sound and vibration, so a rider with the phone in a pocket feels it. Tapping it opens the trip.
 * Posted by the trip's tracker, once per leg; it times out soon after the stop, so a stale alert
 * doesn't linger (SPEC D4).
 */
internal object GetOffSoonAlert {
    const val CHANNEL_ID = "get-off-soon"

    /** Set on the alert's intent: the activity opens the trip on the way. */
    const val EXTRA_OPEN_ON_THE_WAY = "app.stopdash.extra.OPEN_ON_THE_WAY"

    private const val NOTIFICATION_ID = 4101

    // How long past the stop an alert stays, and how long one stays with no time to the stop.
    private val AFTER_STOP: Duration = Duration.ofMinutes(5)
    private val NO_TIME: Duration = Duration.ofMinutes(15)

    private val VIBRATION = longArrayOf(0, 400, 200, 400)

    /** Creates the channel (idempotent), so the user can tune it in Settings before the first alert. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.get_off_soon_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.get_off_soon_channel_description)
            enableVibration(true)
            vibrationPattern = VIBRATION
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Whether the app may post notifications at all. */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Whether an alert can reach the rider: notifications allowed, and this channel not silenced. */
    fun canAlert(context: Context): Boolean =
        canNotify(context) &&
            NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    /**
     * Alerts that [riding]'s stop on [trip] is next; false (and logged, with no stop or place) when
     * notifications are off, which the trip's screen says. One already over five minutes past its
     * stop isn't posted, and counts as said: there's nothing left to tell.
     */
    fun post(context: Context, trip: ActiveTrip, riding: TripProgress.Riding, now: Instant, log: (String) -> Unit): Boolean {
        // Until five minutes after the stop, however late it's posted; past that, too late to help.
        val lasts = riding.getOffAt?.let { Duration.between(now, it.plus(AFTER_STOP)) } ?: NO_TIME
        if (lasts <= Duration.ZERO) {
            log("on the way: get-off alert not shown, past its stop")
            return true
        }
        ensureChannel(context)
        if (!canAlert(context)) {
            log("on the way: get-off alert not shown, notifications off")
            return false
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_ON_THE_WAY, true)
        val pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when (val left = riding.stopsLeft) {
            null -> riding.nextStop?.let { context.getString(R.string.on_the_way_next_is, it) }
            0, 1 -> context.getString(R.string.on_the_way_next_stop)
            else -> stopsText(context.resources, left, riding.nextStop, null)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_appbar_route_arrow)
            .setContentTitle(context.getString(R.string.get_off_soon_title, riding.leg.toName))
            .setContentText(text)
            .setSubText(context.getString(R.string.on_the_way_title, trip.destinationName))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(VIBRATION)
            .setContentIntent(pending)
            .setAutoCancel(true)
            // Posted again after the app died before it was marked said: heard once, not twice.
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(lasts.toMillis())
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            // Said with no stop or place: the stops left, and what they're counted from.
            val left = riding.stopsLeft?.let { if (it == 1) "1 stop left" else "$it stops left" } ?: "stops not counted"
            // From where the rider was seen, else the train followed's calls, timed or not (Codex, #566).
            val by = if (riding.byPosition) "by where seen" else "by train"
            log("on the way: get-off alert shown, $left, $by")
            true
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
            log("on the way: get-off alert refused: ${e::class.simpleName}")
            false
        }
    }

    /** Clears a posted alert: the trip ended or moved on. */
    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}
