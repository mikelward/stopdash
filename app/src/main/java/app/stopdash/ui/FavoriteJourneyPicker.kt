package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.stopdash.R
import app.stopdash.domain.StationMatch

/**
 * Adding a favorite journey from Settings (SPEC *Journeys*): the station search picks where it starts,
 * then, with that standing in the From row, where it ends (maintainer, 2026-10-07). The From row
 * changes the start; Back from the To search drops back to it, and from the From search leaves.
 * UI-only: the search lives in [state], and the pair goes up through [onPickTo].
 */
@Composable
fun FavoriteJourneyPicker(
    state: StationSearchViewModel.State,
    // The start picked, null while it's being picked.
    from: StationMatch?,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onPickFrom: (StationMatch) -> Unit,
    onPickTo: (from: StationMatch, to: StationMatch) -> Unit,
    // Back to picking the start: the From row, or Back from the To search.
    onChangeFrom: () -> Unit,
    onBack: () -> Unit,
    autoFocus: Boolean = true,
) {
    if (from == null) {
        StationSearchScreen(
            state = state,
            onQueryChange = onQueryChange,
            onOpenStation = onPickFrom,
            onRetry = onRetry,
            onBack = onBack,
            autoFocus = autoFocus,
            hint = stringResource(R.string.journey_pick_from_hint),
        )
    } else {
        StationSearchScreen(
            state = state,
            onQueryChange = onQueryChange,
            onOpenStation = { onPickTo(from, it) },
            onRetry = onRetry,
            onBack = onChangeFrom,
            autoFocus = autoFocus,
            hint = stringResource(R.string.station_search_hint),
            fromStation = from.name,
            onChangeFrom = onChangeFrom,
        )
    }
}
