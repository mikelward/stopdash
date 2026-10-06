package app.stopdash.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import java.time.Duration
import java.time.Instant

/**
 * The trip on the way as a Wear OS ongoing activity (dev-docs/wear-os.md *Trip on the way*): the
 * watch's own silent ongoing notification, which the system shows as an icon on the watch face and
 * at the top of the launcher, one tap from the trip in the watch app. Posted by the watch from the
 * trip the phone sent, since the phone's ongoing notification isn't bridged; nothing new leaves the
 * phone for it.
 *
 * It says only the step the rider is at, in the phone's words, never a countdown: it isn't redrawn
 * between the phone's updates, so it must hold nothing that goes out of date. It goes with the trip,
 * and by itself once the trip would read out of date ([WatchTripState.STALE_AFTER] without an
 * update): it can't say so the way the app does, so it stands only while the trip is current.
 */
object WatchTripOngoing {
    private const val TAG = "StopDash.Watch"
    const val CHANNEL_ID = "on-the-way"
    const val ID = 4100

    /** On the intent a tap opens the app with: show the trip, scrolled back to it if the app was open. */
    const val EXTRA_OPEN_TRIP = "app.stopdash.wear.OPEN_TRIP"

    /** What the ongoing activity says ([text]), and how long it stays without another update ([lasts]). */
    internal data class Content(val text: String, val lasts: Duration)

    /**
     * What [held] shows as at [now] and [elapsedNow]; null when there's no trip, or it already reads
     * out of date. [Content.lasts] runs to where it would, by whichever of the watch's time held and
     * the phone's stamp gets there first, as [WatchTripState.shown] marks it.
     */
    internal fun content(held: HeldTrip?, now: Instant, elapsedNow: Long): Content? {
        held ?: return null
        val shown = WatchTripState.shown(held, now, elapsedNow) ?: return null
        if (shown.stale) return null
        val trip = shown.trip
        val text = trip.steps.getOrNull(trip.current)?.text?.takeIf { it.isNotBlank() } ?: trip.title
        val sinceArrival = Duration.ofMillis(elapsedNow - held.arrivedElapsed)
        // A stamp a little ahead of the watch's clock counts as just sent.
        val stamped = Duration.between(Instant.ofEpochMilli(trip.sentAt), now).coerceAtLeast(Duration.ZERO)
        return Content(text, WatchTripState.STALE_AFTER.minus(maxOf(sinceArrival, stamped)))
    }

    /**
     * Posts, updates or takes off the ongoing activity for the trip held now. Called after each
     * change to the trip, from the Data Layer's listener and the app's own lookup; a notification
     * call is all it does, so any thread will do.
     */
    fun update(context: Context, held: HeldTrip? = WatchTripState.trip.value) {
        val content = content(held, Instant.now(), SystemClock.elapsedRealtime())
        val manager = NotificationManagerCompat.from(context)
        if (content == null) {
            manager.cancel(ID)
            return
        }
        if (!canPost(context)) {
            Log.i(TAG, "trip ongoing activity not posted: notifications not allowed")
            return
        }
        try {
            manager.notify(ID, build(context, content))
        } catch (e: SecurityException) {
            Log.w(TAG, "trip ongoing activity refused: ${e::class.simpleName}")
        }
    }

    /** Whether the watch lets the app post it: the permission granted and its channel not turned off. */
    fun canPost(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    internal fun build(context: Context, content: Content): Notification {
        ensureChannel(context)
        val open = Intent(context, WatchHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_TRIP, true)
        val touch = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = context.getString(R.string.watch_trip)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_trip_ongoing)
            .setContentTitle(title)
            .setContentText(content.text)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setContentIntent(touch)
            // Each update from the phone pushes this back, so it lasts while the phone follows the trip.
            .setTimeoutAfter(content.lasts.toMillis().coerceAtLeast(1))
        OngoingActivity.Builder(context, ID, builder)
            .setStaticIcon(R.drawable.ic_trip_ongoing)
            .setTouchIntent(touch)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setTitle(title)
            .setStatus(Status.Builder().addTemplate(content.text).build())
            .build()
            .apply(context)
        return builder.build()
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.watch_trip), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.watch_trip_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
