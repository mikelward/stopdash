package app.stopdash.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-renders the tile and every complication when the watch's clock is set (dev-docs/wear-os.md
 * *Staleness on the watch*). The system steps their timelines by the wall clock, and each was built
 * from the envelope as the clock read it then, so after the clock is set back it would keep showing
 * an entry built before, its countdowns and its age as they were, until the clock caught up with it
 * (Codex, PR #371). A re-render reads the envelope as the clock reads it now
 * ([WatchEnvelopeStore.current]). The app's ticker re-reads it at each wake on its own.
 */
class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIME_CHANGED) WatchSurfaces.requestUpdate(context)
    }
}
