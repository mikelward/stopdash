package app.stopdash.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.ChipLabel
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoriteShortcuts
import app.stopdash.domain.TripDestination
import java.time.DayOfWeek

/**
 * The saved favorite places as one row of chips atop the near-me list and the From…/To… searches
 * (SPEC D9 → *Routing from the near-me list*): a tap plans a trip there, as the same place does in Settings or the To… picker, and
 * a long press opens the places' own screen to edit them ([onEditPlaces]; null offers none).
 * The caller has already left out the places the rider is at ([app.stopdash.domain.FavoriteShortcuts]);
 * the row scrolls sideways when the chips don't fit, and scrolls away with the list.
 */
@Composable
internal fun FavoriteChips(
    places: List<FavoritePlace>,
    onRouteTo: (TripDestination.Place) -> Unit,
    modifier: Modifier = Modifier,
    // 16dp at the screen's edges, as the list's cards sit; inside the list, which is already inset,
    // none.
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    // Centers the chips when they fit (the empty state, whose text is centered); start-aligned in the list.
    centered: Boolean = false,
    onEditPlaces: (() -> Unit)? = null,
    // The From… search leads the row with "Here", the rider's own position, behind the crosshair
    // (maintainer, 2026-09-28). Null leaves it out, as everywhere else.
    onHere: (() -> Unit)? = null,
    // Overrides each place's own choice of what its chip shows: the To… and From… searches have room
    // for the icon and the name (SPEC *Routing from the near-me list*). Null follows the place.
    labelOverride: ChipLabel? = null,
) {
    val editLabel = stringResource(R.string.favorite_places_edit_action)
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("favoriteChips"),
        contentPadding = contentPadding,
        horizontalArrangement = if (centered) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(8.dp),
    ) {
        if (onHere != null) {
            item(key = "here") {
                PlaceChip(
                    onClick = onHere,
                    onLongClick = null,
                    onLongClickLabel = editLabel,
                    leadingIcon = { Icon(CrosshairIcon, contentDescription = null, modifier = Modifier.size(CHIP_ICON_SIZE)) },
                    label = { Text(stringResource(R.string.from_here), maxLines = 1) },
                    modifier = Modifier.testTag("stationSearchHere"),
                )
            }
        }
        items(places, key = { it.id }) { place ->
            val name = favoriteRouteName(place)
            // An icon this build can't draw falls back to the name.
            val shows = if (hasPlaceIcon(place.icon)) labelOverride ?: place.chipLabel else ChipLabel.NAME
            // TalkBack hears the action, not just the name, as on the Settings row.
            val description = stringResource(R.string.favorite_place_route_description, name)
            PlaceChip(
                onClick = { onRouteTo(TripDestination.Place(place.coordinate, name)) },
                onLongClick = onEditPlaces,
                onLongClickLabel = editLabel,
                // The place's own icon, its name, or both, as the rider chose (maintainer, 2026-09-28);
                // never a generic icon, which said nothing the name didn't. An icon-only chip carries
                // the icon as its label, so it sits centered; TalkBack still hears the name.
                leadingIcon = if (shows == ChipLabel.BOTH) {
                    { PlaceIcon(place.icon, Modifier.size(CHIP_ICON_SIZE)) }
                } else {
                    null
                },
                label = {
                    if (shows == ChipLabel.ICON) {
                        PlaceIcon(place.icon, Modifier.size(CHIP_ICON_SIZE))
                    } else {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                modifier = Modifier
                    .testTag("favoriteChip-${place.id}")
                    .semantics { contentDescription = description },
            )
        }
    }
}

/**
 * An outlined chip drawn to Material's assist-chip measures (at least 32dp tall, 8dp corners, a 1dp
 * outline, 16dp end padding, 8dp start padding before an icon and 16dp without one, 8dp between icon
 * and label), built here because Material's own chip takes no long press. The icon takes the label's
 * color rather than an accent. Its touch target is 48dp tall, as Material's is.
 */
@Composable
private fun PlaceChip(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String,
    leadingIcon: (@Composable () -> Unit)?,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            // At least 32dp, growing with the text at a large font scale rather than clipping it.
            .heightIn(min = 32.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClick?.let { onLongClickLabel },
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                Row(
                    modifier = Modifier.padding(start = if (leadingIcon != null) 8.dp else 16.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    leadingIcon?.invoke()
                    label()
                }
            }
        }
    }
}

// Material's chip icon size.
private val CHIP_ICON_SIZE = 18.dp

/** The name a favorite is known by: its label, or its resolved place name when the label is blank
 *  (as the Settings route-to and the To… picker name it). */
internal fun favoriteRouteName(place: FavoritePlace): String = place.label.ifBlank { place.placeName.orEmpty() }

/**
 * The saved [places] to offer as chips where the shown set was found from [location]: less those the
 * rider is already at, hidden within 200 m on an accurate fix and back past 250 m
 * ([FavoriteShortcuts]), and those off [today]. Position and confidence come from the model's
 * [riderFix] for this very location (a coarse set confirmed in place stands at the precise fix, and an
 * approximate-only grant is rough even with no banner, Codex), and any [banner] (a stale or failed fix)
 * makes it rough too. The previous answer, [hiddenPlaceIds], is read unobserved and handed back to
 * [onHiddenPlaceIds] after the frame, so the band holds a place's state from one fix to the next
 * without this frame recomposing on its own write. Null [places] (not read yet) offers none and leaves
 * the memory as it was. Shared by the near-me list and its "no stops nearby" state.
 */
@Composable
internal fun rememberShownPlaces(
    places: List<FavoritePlace>?,
    location: Coordinates,
    riderFix: NearbyStopsViewModel.RiderFix?,
    banner: LocationBanner?,
    hiddenPlaceIds: Set<String>,
    onHiddenPlaceIds: (Set<String>) -> Unit,
    today: DayOfWeek?,
): List<FavoritePlace> {
    val rider = riderFix?.takeIf { it.from == location }
    val riderAt = rider?.at ?: location
    val riderAccurate = banner == null && rider?.accurate == true
    val latestHiddenPlaceIds by rememberUpdatedState(hiddenPlaceIds)
    val hiddenPlaceIdsNow = remember(places, riderAt, riderAccurate) {
        places?.let {
            FavoriteShortcuts.hiddenIds(
                it,
                riderAt,
                precise = riderAccurate,
                hiddenBefore = Snapshot.withoutReadObservation { latestHiddenPlaceIds },
            )
        }
    }
    SideEffect { if (hiddenPlaceIdsNow != null && hiddenPlaceIdsNow != hiddenPlaceIds) onHiddenPlaceIds(hiddenPlaceIdsNow) }
    return remember(places, hiddenPlaceIdsNow, riderAccurate, today) {
        if (places == null || hiddenPlaceIdsNow == null) emptyList()
        else FavoriteShortcuts.shown(places, hiddenPlaceIdsNow, precise = riderAccurate, today = today)
    }
}
