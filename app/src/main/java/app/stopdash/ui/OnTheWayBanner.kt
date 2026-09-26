package app.stopdash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripProgress
import java.time.Instant

/**
 * A trip on the way, for the card pinned atop the main view: the [trip], where it stands, when that
 * was last brought up to date ([updatedAt], see [ActiveTripTracker.isCurrent]), and [onOpen].
 */
class OnTheWayBannerState(val trip: ActiveTrip, val progress: TripProgress?, val updatedAt: Instant?, val onOpen: () -> Unit)

/** The trip on the way, provided by the activity; null with none (or in a test). */
val LocalOnTheWayBanner = compositionLocalOf<OnTheWayBannerState?> { null }

/**
 * The card pinned at the top of the main view while a trip is on the way (SPEC *On the way*): where
 * it's going and the next step, from the tracker's state alone (no request of its own). A tap opens
 * the trip.
 */
@Composable
internal fun OnTheWayBanner(state: OnTheWayBannerState, now: Instant, modifier: Modifier = Modifier) {
    val current = ActiveTripTracker.isCurrent(state.updatedAt, now)
    val (title, detail) = nextStepText(state.progress, now, current)
    Card(
        colors = nextStepColors(state.progress, current),
        modifier = modifier.fillMaxWidth().testTag("onTheWayBanner").clickable(role = Role.Button, onClick = state.onOpen),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.on_the_way_title, state.trip.destinationName),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
