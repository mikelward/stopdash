package app.stopdash

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Registers again each of the app's notification channels that exists and that something besides its own
 * use may need to keep current, so a name or description an update changed reaches Android's settings at
 * the next app start rather than at the channel's next use (Codex on #840): the ride alerts' (used only
 * when an alert posts) and the trip's "Disruption ahead" (only when a trip starts or a disruption posts).
 * On a channel that exists Android takes only the name and description, keeping what the rider set; one
 * the rider never had isn't made. Notification-manager calls, so the hop first (AGENTS.md *Main thread*).
 */
internal suspend fun refreshNotificationChannels(
    context: Context,
    io: CoroutineDispatcher = Dispatchers.IO,
    // Each channel's refresh; a test passes its own to see where they run.
    refreshes: List<(Context) -> Unit> = CHANNEL_REFRESHES,
) = withContext(io) {
    refreshes.forEach { it(context) }
}

private val CHANNEL_REFRESHES: List<(Context) -> Unit> = listOf(JourneyAlertNotification::refreshChannel, RouteDisruptionAlert::refreshChannel)
