package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Starts a trip on the way (SPEC *On the way*), for a screen several layers down (a trip's open route)
 * without each layer passing it on: [start] a route to a destination, the rider at its first stop by
 * the given time. While one is already on the way ([active]), [open] opens it instead: one trip at a
 * time. Provided by the activity ([LocalOnTheWay]); null (a test) offers no Start.
 */
class OnTheWayActions(
    val active: Boolean = false,
    val open: () -> Unit = {},
    val start: (route: TripRoute, destinationName: String, readyAt: Instant) -> Unit,
)

val LocalOnTheWay = compositionLocalOf<OnTheWayActions?> { null }

/** How often a trip on the way asks after its train while the app is in the foreground. */
val ON_THE_WAY_REFRESH: Duration = Duration.ofSeconds(30)

/**
 * Follows [tracker]'s trip while the app is in the foreground: the kept trip read once, then a
 * refresh every [ON_THE_WAY_REFRESH] while one is on the way — unless [serviceFollowing], when the
 * trip's foreground service does it, app open or closed (SPEC *On the way*). Nothing at all with no
 * trip.
 */
@Composable
internal fun FollowActiveTrip(tracker: ActiveTripTracker, serviceFollowing: StateFlow<Boolean>) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Read again every [ON_THE_WAY_REFRESH] while in the foreground if it couldn't be read.
    LaunchedEffect(tracker, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (!tracker.restore()) delay(ON_THE_WAY_REFRESH.toMillis())
        }
    }
    val trip by tracker.trip.collectAsStateWithLifecycle()
    val service by serviceFollowing.collectAsStateWithLifecycle()
    if (trip != null && !service) {
        LaunchedEffect(tracker, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    tracker.refresh()
                    delay(ON_THE_WAY_REFRESH.toMillis())
                }
            }
        }
    }
}
