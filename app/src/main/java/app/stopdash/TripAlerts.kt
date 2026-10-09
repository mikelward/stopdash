package app.stopdash

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A trip's alerts ("Get off soon", "Time to board", "Route disruption") said by the trip's own ongoing
 * notification while it's up, rather than as notifications of their own (maintainer, 2026-10-09): one
 * notification for the trip, so a second never crowds the Live Update's chip off the status bar. Each
 * is posted on its own channel the moment it's first heard, so it sounds and vibrates as it always did
 * and is muted as it always was; then quietly kept up to date. In memory: a process that dies takes the
 * trip's notification with it.
 */
internal object TripAlerts {
    /** Each alert, most pressing first: the one shown when more than one stands. */
    enum class Kind(val channelId: String) {
        GET_OFF(GetOffSoonAlert.CHANNEL_ID),
        BOARD(TimeToBoardAlert.CHANNEL_ID),
        DISRUPTION(RouteDisruptionAlert.CHANNEL_ID),
    }

    /**
     * What an alert says ([title], and [text] where it has more than the step), until when ([until]),
     * and whether it's still to be heard ([sound]). [keys] are a route disruption's signals ([RouteDisruptionAlert.shown]).
     */
    data class Alert(
        val kind: Kind,
        val title: String,
        val text: String?,
        val until: Instant,
        val sound: Boolean,
        val keys: Set<String> = emptySet(),
    )

    private val _alerts = MutableStateFlow<Map<Kind, Alert>>(emptyMap())

    /** The alerts standing, by kind: the trip's notification is shown again as they change. */
    val alerts: StateFlow<Map<Kind, Alert>> = _alerts.asStateFlow()

    /**
     * Has the trip's notification say [alert], if it's up for [context] to say it in ([carried]): whether
     * it will. A new one replaces the last of its kind; one already heard stays quiet unless [Alert.sound].
     */
    fun offer(context: Context, alert: Alert): Boolean {
        if (!OnTheWayNotification.carried(context)) return false
        // One still to be heard stays so though kept up to date before it's shown.
        _alerts.update { it + (alert.kind to alert.copy(sound = alert.sound || it[alert.kind]?.sound == true)) }
        return true
    }

    /**
     * Whether a new [kind] alert would be hidden at [now] behind a more pressing one standing (its channel
     * not muted): such an alert isn't taken yet, so it's never counted as heard unshown; its tracker
     * offers it again on the next refresh (Codex on #723).
     */
    fun outranked(context: Context, kind: Kind, now: Instant): Boolean = outranked(_alerts.value, kind, now) { channelOn(context, it) }

    internal fun outranked(alerts: Map<Kind, Alert>, kind: Kind, now: Instant, channelOn: (String) -> Boolean): Boolean =
        top(alerts.filterKeys { it.ordinal < kind.ordinal }, now, channelOn) != null

    /** Whether [kind]'s alert is being said with [title]: posted again for the same thing, it's kept quiet. */
    fun saying(kind: Kind, title: String): Boolean = _alerts.value[kind]?.title == title

    /** No longer says [kind]'s alert. */
    fun clear(kind: Kind) = _alerts.update { it - kind }

    /** Says nothing: the trip ended. */
    fun clearAll() {
        _alerts.value = emptyMap()
    }

    /** [kind]'s alert, heard: kept quiet from now on, unless offered again as new. */
    fun heard(kind: Kind, alert: Alert) = _alerts.update { current ->
        if (current[kind] == alert) current + (kind to alert.copy(sound = false)) else current
    }

    /** Whether [kind]'s alert is still being said at [now]. */
    fun standing(kind: Kind, now: Instant): Boolean = _alerts.value[kind]?.until?.isAfter(now) == true

    /** The keys of the route disruption the trip's notification is saying, if it is. */
    fun disruptionKeys(now: Instant): Set<String> = _alerts.value[Kind.DISRUPTION]?.takeIf { it.until.isAfter(now) }?.keys.orEmpty()

    /** The alert the trip's notification says at [now]: the most pressing standing one whose channel isn't muted. */
    fun top(context: Context, now: Instant): Alert? = top(_alerts.value, now) { channelOn(context, it) }

    internal fun top(alerts: Map<Kind, Alert>, now: Instant, channelOn: (String) -> Boolean): Alert? =
        Kind.entries.firstNotNullOfOrNull { kind -> alerts[kind]?.takeIf { it.until.isAfter(now) && channelOn(kind.channelId) } }

    private fun channelOn(context: Context, channelId: String): Boolean =
        NotificationManagerCompat.from(context).getNotificationChannel(channelId)?.importance.let { it != null && it != NotificationManager.IMPORTANCE_NONE }
}
