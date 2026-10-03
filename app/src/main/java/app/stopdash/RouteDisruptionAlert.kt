package app.stopdash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.RouteDisruption
import app.stopdash.ui.ActiveTripTracker.DisruptionPost
import java.time.Duration
import java.time.Instant

/**
 * "Route disruption" for a trip on the way (SPEC *On the way*; maintainer, 2026-09-29): something
 * known that may stop a leg the rider hasn't finished ([RouteDisruption]), so they don't wait for a
 * train that won't come. Named for what's known ("Red: Part Suspended", "Example Road closed"), never
 * a verdict on the route; tapping it opens the trip.
 *
 * One notification for the trip, kept up to date as what's known changes, and taken down once nothing
 * is left or the trip ends. It lasts no longer than its evidence is current, nor than the trip's own
 * answers stay live, renewed by each refresh that still finds it, so once nothing follows the trip it
 * comes down by itself (SPEC D4).
 * Its own channel, apart from "Get off soon" and "Time to board", so it can be muted alone.
 */
internal object RouteDisruptionAlert {
    const val CHANNEL_ID = "route-disruption"

    private const val NOTIFICATION_ID = 4103

    private const val EXTRA_HEARD = "app.stopdash.extra.DISRUPTIONS_HEARD"

    private val VIBRATION = longArrayOf(0, 400, 200, 400)

    /** Creates the channel (idempotent), so the user can tune it in Settings before the first alert. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.route_disruption_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.route_disruption_channel_description)
            enableVibration(true)
            vibrationPattern = VIBRATION
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts [signals] (worst first) for [trip], as [how] says ([DisruptionPost]), standing until
     * [until]: whether it's up. One that's heard ([DisruptionPost.NEW]) is false (and logged, naming no
     * line or stop) when notifications are off, to be tried again. One kept up to date
     * ([DisruptionPost.KEEP]) is false once it's no longer showing, swiped away or timed out, and isn't
     * brought back. None is posted once [until] has passed by [now].
     */
    fun post(
        context: Context,
        trip: ActiveTrip,
        signals: List<RouteDisruption.Signal>,
        how: DisruptionPost,
        until: Instant,
        now: Instant,
        log: (String) -> Unit,
    ): Boolean {
        val top = signals.firstOrNull() ?: return false
        val lasts = Duration.between(now, until)
        if (lasts <= Duration.ZERO) return false
        if (how == DisruptionPost.KEEP && !showing(context)) return false
        ensureChannel(context)
        if (!GetOffSoonAlert.canNotify(context) ||
            NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            log("on the way: route disruption alert not shown, notifications off")
            return false
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(GetOffSoonAlert.EXTRA_OPEN_ON_THE_WAY, true)
        val pending = PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val (title, body) = content(context, signals)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_appbar_route_arrow)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(body?.let { NotificationCompat.BigTextStyle().bigText(it) })
            .setSubText(context.getString(R.string.on_the_way_title, trip.destinationName))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(if (top.tier == RouteDisruption.Tier.HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(VIBRATION)
            .setContentIntent(pending)
            .setAutoCancel(true)
            // Kept up to date while it's up: heard when something new is known, not on every refresh.
            .setOnlyAlertOnce(how != DisruptionPost.NEW)
            .setSilent(how != DisruptionPost.NEW)
            .setTimeoutAfter(lasts.toMillis())
            // What it says, by key, kept on the notification itself: posted and recorded at once, so
            // what's showing reads as heard after a restart however the trip was saved ([shown]).
            .addExtras(Bundle().apply { putStringArray(EXTRA_HEARD, signals.map { it.key }.toTypedArray()) })
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
            log("on the way: route disruption alert refused: ${e::class.simpleName}")
            false
        }
    }

    /** Clears a posted alert: nothing known is left, or the trip ended. */
    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    /** What the alert still showing was posted with, by key ([RouteDisruption.Signal.key]); empty when none is up. */
    fun shown(context: Context): Set<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == NOTIFICATION_ID }?.notification?.extras?.getStringArray(EXTRA_HEARD)?.toSet().orEmpty()

    // What one signal says: the line and its alert as the trip's chip has it, the stop and what happened to
    // it, or the line with no train predicted where the rider changes onto it.
    /**
     * The alert's title and body for [signals] (worst first): the worst's title, then what TfL says of
     * it (where the line is diverted or shut, which a bare "Diversion" doesn't say; maintainer,
     * 2026-10-01), then each other thing known, titled, with TfL's words for it too. Each is said once:
     * two legs on one line with the same words read as one, while two alerts of a line with the same
     * title but their own words are each said (Codex on #519), as each is heard once.
     */
    internal fun content(context: Context, signals: List<RouteDisruption.Signal>): Pair<String, String?> {
        val items = signals.map { text(context, it) to (it as? RouteDisruption.Signal.Line)?.status?.fullText?.trim()?.ifEmpty { null } }.distinct()
        val (title, detail) = items.first()
        val rest = items.drop(1).map { (heading, words) -> listOfNotNull(heading, words).joinToString("\n") }
        return title to (listOfNotNull(detail) + rest).joinToString("\n\n").ifEmpty { null }
    }

    internal fun text(context: Context, signal: RouteDisruption.Signal): String = when (signal) {
        is RouteDisruption.Signal.Line -> context.getString(R.string.route_disruption_line, signal.lineName.ifBlank { signal.lineId }, signal.status.description)
        is RouteDisruption.Signal.Stop -> context.getString(
            if (signal.closed) R.string.route_disruption_stop_closed else R.string.route_disruption_stop_moved,
            signal.stopName.ifBlank { signal.stopId },
        )
        is RouteDisruption.Signal.Unpredicted -> context.getString(
            R.string.route_disruption_unpredicted,
            signal.lineName.ifBlank { signal.lineId },
            signal.stopName.ifBlank { signal.stopId },
        )
    }

    // Whether the alert is still up: not swiped away, nor timed out.
    private fun showing(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID }
}
