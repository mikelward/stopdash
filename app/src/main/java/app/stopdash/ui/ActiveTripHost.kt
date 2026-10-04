package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.LocationFix
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripFixes
import app.stopdash.domain.TripRoute
import app.stopdash.domain.awaitRefresh
import app.stopdash.domain.refreshFix
import app.stopdash.domain.watchTripFixes
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Starts a trip on the way (SPEC *On the way*), for a screen several layers down (a trip's open route)
 * without each layer passing it on: [start] a route to a destination, the rider at its first stop by
 * the given time, with where to as chosen ([ActiveTrip.destinations]). While one is already on the
 * way ([active]), [open] opens it, and [replace] starts a route in its place once the rider confirms:
 * one trip at a time. A trip list opened to plan the trip on
 * the way again ([OnTheWayScreen]'s Plan again) is given one not [active] whose [start] takes that
 * trip's place. Provided by the activity ([LocalOnTheWay]); null (a test) offers no Start.
 */
class OnTheWayActions(
    val active: Boolean = false,
    val open: () -> Unit = {},
    // Start in place of the trip on the way, ending it first; null offers no replacing.
    val replace: ((
        route: TripRoute,
        destinationName: String,
        readyAt: Instant,
        destinations: List<TripDestination>,
        destinationIds: Map<String, String>,
        destinationStopId: String,
    ) -> Unit)? = null,
    val start: (
        route: TripRoute,
        destinationName: String,
        readyAt: Instant,
        destinations: List<TripDestination>,
        destinationIds: Map<String, String>,
        destinationStopId: String,
    ) -> Unit,
)

val LocalOnTheWay = compositionLocalOf<OnTheWayActions?> { null }

/** How often a trip on the way asks after its train while the app is in the foreground. */
val ON_THE_WAY_REFRESH: Duration = Duration.ofSeconds(30)

/**
 * Follows [tracker]'s trip while the app is in the foreground: the kept trip read once, then a
 * refresh every [ON_THE_WAY_REFRESH] while one is on the way — unless [serviceFollowing], when the
 * trip's foreground service does it, app open or closed (SPEC *On the way*). While the trip is shown
 * it also watches location ([watchTripFixes]) as long as a fix could move it on, so a rider on the
 * move has it refreshed on each fix, whichever of the two follows it ([TripFixes]). Nothing at all
 * with no trip.
 */
@Composable
internal fun FollowActiveTrip(
    tracker: ActiveTripTracker,
    serviceFollowing: StateFlow<Boolean>,
    fixes: TripFixes = TripFixes(),
    // Precise fixes as they come, watched while the trip is shown and wants one.
    updates: () -> Flow<LocationFix> = { emptyFlow() },
    // A fix when the trip wants one ([app.stopdash.domain.OnTheWay.wantsFix]): a walk to a stop ends
    // once the rider is seen there, not only on its time.
    rider: suspend (ActiveTrip?) -> LocationFix? = { null },
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Read again every [ON_THE_WAY_REFRESH] while in the foreground if it couldn't be read.
    LaunchedEffect(tracker, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (!tracker.restore()) delay(ON_THE_WAY_REFRESH.toMillis())
        }
    }
    val trip by tracker.trip.collectAsStateWithLifecycle()
    val service by serviceFollowing.collectAsStateWithLifecycle()
    if (trip != null) {
        LaunchedEffect(tracker, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchTripFixes(tracker.trip, fixes, updates)
            }
        }
        // Each fix as it comes moves a walk's distance left at once ([ActiveTripTracker.onFix]), not
        // only on the refresh it may bring on, which waits out its gap and its requests. From [TripFixes.each],
        // not [TripFixes.latest], which a refresh (the service's too) may clear before it's seen (Codex, #542).
        LaunchedEffect(tracker, fixes, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fixes.each.collect { tracker.onFix(fixes.aged(it), arrivedAgoMillis = fixes.waited(it)) }
            }
        }
    }
    if (trip != null && !service) {
        LaunchedEffect(tracker, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var woke: TripFixes.Seen? = null
                while (true) {
                    tracker.refresh(refreshFix(woke, fixes, tracker.trip.value, Instant.now(), take = rider))
                    woke = awaitRefresh(fixes.latest, ON_THE_WAY_REFRESH)
                }
            }
        }
    }
}
