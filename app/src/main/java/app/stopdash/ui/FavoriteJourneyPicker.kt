package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.stopdash.R
import app.stopdash.domain.PendingEnd
import app.stopdash.domain.SearchEntry

/**
 * Adding a favorite journey from Settings (SPEC *Journeys*): the station search picks where it starts,
 * then, with that standing in the From row, where it ends (maintainer, 2026-10-07). The rider's favorite
 * places head both searches as chips (maintainer, 2026-10-10), so Home or Work can be either end. The
 * From row changes the start; Back from the To search drops back to it, and from the From search leaves.
 * UI-only: the search lives in [state], and the pair goes up through [onPickTo].
 */
@Composable
fun FavoriteJourneyPicker(
    state: StationSearchViewModel.State,
    // The start picked, null while it's being picked.
    from: PendingEnd?,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    // Reads the favorite places again after they couldn't be read, from either search's Retry.
    onRetryPlaces: () -> Unit,
    onPickFrom: (PendingEnd) -> Unit,
    onPickTo: (from: PendingEnd, to: PendingEnd) -> Unit,
    // Back to picking the start: the From row, or Back from the To search.
    onChangeFrom: () -> Unit,
    onBack: () -> Unit,
    autoFocus: Boolean = true,
    // A long press on a Recent row asks to remove it, as in From…'s own search, whose list this is
    // ([StationSearchScreen]'s removal callbacks).
    onAskRemoveRecent: ((SearchEntry) -> Unit)? = null,
    onCancelRemoveRecent: () -> Unit = {},
    onRemoveRecent: (SearchEntry) -> Unit = {},
) {
    if (from == null) {
        StationSearchScreen(
            state = state,
            onQueryChange = onQueryChange,
            onOpenStation = { onPickFrom(PendingEnd.Station(it.id, it.name)) },
            onRetry = onRetry,
            onBack = onBack,
            autoFocus = autoFocus,
            hint = stringResource(R.string.journey_pick_from_hint),
            // The journey keeps the place's id, so the place stays the one source of where it is.
            onPickPlace = { place, name -> onPickFrom(PendingEnd.Place(place.id, name)) },
            onRetryPlaces = onRetryPlaces,
            onAskRemoveRecent = onAskRemoveRecent,
            onCancelRemoveRecent = onCancelRemoveRecent,
            onRemoveRecent = onRemoveRecent,
        )
    } else {
        StationSearchScreen(
            state = state,
            onQueryChange = onQueryChange,
            onOpenStation = { onPickTo(from, PendingEnd.Station(it.id, it.name)) },
            onRetry = onRetry,
            onBack = onChangeFrom,
            autoFocus = autoFocus,
            hint = stringResource(R.string.station_search_hint),
            onPickPlace = { place, name -> onPickTo(from, PendingEnd.Place(place.id, name)) },
            onRetryPlaces = onRetryPlaces,
            fromStation = from.name,
            onChangeFrom = onChangeFrom,
            onAskRemoveRecent = onAskRemoveRecent,
            onCancelRemoveRecent = onCancelRemoveRecent,
            onRemoveRecent = onRemoveRecent,
        )
    }
}
