package app.stopdash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.TripDestination

/**
 * The saved favorite places as one row of chips atop the near-me list (SPEC D9 → *Routing from the
 * near-me list*): a tap plans a trip there, as the same place does in Settings or the To… picker.
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
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("favoriteChips"),
        contentPadding = contentPadding,
        horizontalArrangement = if (centered) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(8.dp),
    ) {
        items(places, key = { it.id }) { place ->
            val name = favoriteRouteName(place)
            // TalkBack hears the action, not just the name, as on the Settings row.
            val description = stringResource(R.string.favorite_place_route_description, name)
            AssistChip(
                onClick = { onRouteTo(TripDestination.Place(place.coordinate, name)) },
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    Icon(
                        imageVector = if (place.kind == FavoriteKind.HOME) Icons.Outlined.Home else Icons.Outlined.Place,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                modifier = Modifier
                    .testTag("favoriteChip-${place.id}")
                    .semantics { contentDescription = description },
            )
        }
    }
}

/** The name a favorite is known by: its label, or its resolved place name when the label is blank
 *  (as the Settings route-to and the To… picker name it). */
internal fun favoriteRouteName(place: FavoritePlace): String = place.label.ifBlank { place.placeName.orEmpty() }
