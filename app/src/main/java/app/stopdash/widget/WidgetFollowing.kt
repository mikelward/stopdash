package app.stopdash.widget

import android.content.Context
import app.stopdash.JourneyAlertLocation
import app.stopdash.data.AndroidLocationProvider
import app.stopdash.data.DataStoreAlertsBehindStore
import app.stopdash.data.DataStoreDismissedAlertsStore
import app.stopdash.data.DataStoreNearbySetStore
import app.stopdash.data.HiddenModesSetting
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.AlertBehind
import app.stopdash.domain.Dismissals
import app.stopdash.domain.EmptyNearbyMemo
import app.stopdash.domain.LocationFix
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.StopFinder
import app.stopdash.domain.TflException
import app.stopdash.domain.WidgetFollow
import app.stopdash.logLocationWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The widget's refresh following the rider (SPEC D1, [WidgetFollow]): with location allowed all the
 * time, the phone's last known position re-picks the nearby stops before their arrivals are fetched.
 * Asks Android for no fix, so it costs no radio; the stops are looked up through the app's own nearby
 * cache, so a rider who stays put asks TfL nothing more — nor, for a while, one where none were found.
 */
internal object WidgetFollowing {
    /** A lookup here that found no stops, so a phone left beyond any route doesn't ask TfL each cycle. */
    private val noStops = EmptyNearbyMemo()

    /** Where the phone last was, by each provider, when following is allowed: swappable in tests. */
    @Volatile
    internal var lastKnown: (Context) -> List<LocationFix> = { context ->
        if (!JourneyAlertLocation.allowedAllTheTime(context)) {
            emptyList()
        } else {
            AndroidLocationProvider(context, warn = ::logLocationWarning, remembers = false).lastKnownFixes()
        }
    }

    /**
     * Where the rider is, followed from [prior]: its position, and the move to follow with its new nearby
     * set already stored for the widget and the watch, or no move where the stops shown are the ones
     * there. Null to refresh [prior] as it is: following not allowed, no recent and accurate enough
     * position, or the lookup failed. Never throws but for cancellation; a failure is logged, never with
     * a coordinate.
     */
    suspend fun follow(
        context: Context,
        prior: DeparturesSnapshot,
        finder: StopFinder,
        nearbySet: DataStoreNearbySetStore = DataStoreNearbySetStore.from(context.applicationContext, warn = ::logWidgetSnapshotWarning),
        now: Instant = Instant.now(),
    ): WidgetFollow.Followed? {
        // Before anything can return: an expired empty lookup's position goes even when there's no fix.
        noStops.forgetExpired()
        try {
            val fix = WidgetFollow.best(withContext(Dispatchers.IO) { lastKnown(context) }) ?: return null
            val at = fix.coordinates
            val stored = nearbySet.read()
            // An unreadable set can't be compared with, so it isn't replaced from here.
            if (stored.failed) return null
            val found = if (noStops.knownEmpty(at)) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    finder.nearbyStops(at.latitude, at.longitude, NearbySelection.OUTER_RADIUS_METERS)
                }.also { noStops.record(at, it) }
            }
            val hidden = HiddenModesSetting.loaded()
            // What the widget applies before it draws, so the line choices are worked out over the rows it draws.
            val dismissals = dismissals(context)
            val verdicts = alertVerdicts(context)
            val followed = withContext(Dispatchers.Default) {
                WidgetFollow.followed(prior, stored.stopIds, found, at, hidden, now, dismissals, verdicts)
            }
            // Where the stored layout already stands, the refresh still works its line choices out here.
            val moved = followed.moved ?: return followed
            // The app resolved a set meanwhile, from a fresher fix of its own: that one stands.
            if (!nearbySet.replaceIf(stored.stopIds, moved.nearby)) return null
            // Redrawn at once: the widget draws only the stored set's stops, so the old place's rows go
            // now, not only once the new place's arrivals are fetched and saved, which a stopped worker
            // may never reach (Codex on #711).
            // A failed redraw leaves only the drawing behind: the set is stored, so the move goes on and the
            // refresh's save draws it, rather than refreshing the old place under the new set (Codex on #711).
            try {
                redrawWidgets(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWidgetSnapshotWarning("widget redraw after a follow failed: ${e::class.simpleName}")
            }
            logWidgetSnapshotWarning("widget followed the rider: ${moved.nearby.size} stops, ${moved.placeholders.size} new")
            return followed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // TflException.message is the coarse kind, never the position asked about (SPEC *Privacy*).
            logWidgetSnapshotWarning("widget couldn't follow the rider: ${(e as? TflException)?.message ?: e::class.simpleName}")
            return null
        }
    }

    /**
     * The user's dismissals as the widget reads them to draw, the ended ones it still hides included. An
     * unreadable set counts as none, as the widget's drawing counts it.
     */
    private suspend fun dismissals(context: Context): Dismissals = try {
        DataStoreDismissedAlertsStore.from(context.applicationContext, warn = ::logWidgetSnapshotWarning).dismissals().first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("widget follow dismissed read failed: ${e::class.simpleName}")
        Dismissals.NONE
    }

    /** The app's verdicts that an alert lies behind a stop, as the widget reads them to draw; unreadable, none. */
    private suspend fun alertVerdicts(context: Context): Set<AlertBehind> = try {
        DataStoreAlertsBehindStore.from(context.applicationContext, warn = ::logWidgetSnapshotWarning).verdicts().first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("widget follow alerts-behind read failed: ${e::class.simpleName}")
        emptySet()
    }
}
