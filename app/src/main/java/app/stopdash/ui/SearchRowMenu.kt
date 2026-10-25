package app.stopdash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlaceIcon
import app.stopdash.domain.FavoritePlaces
import app.stopdash.domain.Pick
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.PlaceStart
import app.stopdash.domain.SearchEntry
import app.stopdash.domain.StationMatch
import app.stopdash.domain.abbreviateStationName
import kotlinx.coroutines.withContext

/**
 * What a search row's long press can do (maintainer, 2026-10-12), each null where the screen offers
 * none: show the row on the map, take it off Recent (asked as before: [onAskRemoveRecent]), add a favorite
 * ride from it ([onAddRide]), and add it to the favorite places, or remove the one saved at its position.
 */
internal class SearchRowActions(
    val onShowOnMap: ((String, Coordinates) -> Unit)? = null,
    val onAskRemoveRecent: ((SearchEntry) -> Unit)? = null,
    // The pick, and a searched place's type's icon for the editor to start on ([placeIconOf]).
    val onAddPlace: ((StationMatch, String?) -> Unit)? = null,
    val onRemovePlace: ((FavoritePlace) -> Unit)? = null,
    // A stop's favorite rides: Settings' Add, From set to the stop, asking To… (maintainer, 2026-10-24).
    val onAddRide: ((StationMatch) -> Unit)? = null,
) {
    val any: Boolean get() = onShowOnMap != null || onAskRemoveRecent != null || onAddPlace != null || onRemovePlace != null || onAddRide != null
}

/**
 * What the activity does for a search row's menu, wherever the search is shown: the row on the map,
 * the favorite-place editor filled in with it ([addPlace]), and the removal of a saved place
 * ([removePlace], which calls back once it is written so the search can read its places again).
 */
internal class SearchRowHost(
    val showOnMap: (String, Coordinates) -> Unit,
    val addPlace: (StationMatch, String?) -> Unit,
    val removePlace: (FavoritePlace, onRemoved: () -> Unit) -> Unit,
    // The favorite rides' Add, From set to the stop ([SearchRowActions.onAddRide]).
    val addRide: ((StationMatch) -> Unit)? = null,
)

/** The activity's [SearchRowHost]; none (no such items) outside it, as in a screenshot test. */
internal val LocalSearchRowHost = staticCompositionLocalOf<SearchRowHost?> { null }

/** Where [entry] is, when known: a stop's published position (not every stop carries one), or a place's. */
internal fun SearchEntry.coordinate(): Coordinates? = when (this) {
    is SearchEntry.Stop -> {
        val latitude = match.latitude
        val longitude = match.longitude
        if (latitude != null && longitude != null) Coordinates(latitude, longitude) else null
    }
    is SearchEntry.Place -> hit.coordinate
}

/**
 * [this] as a pick for the favorite-place editor: a stop as itself, at [at] where it carries no position
 * of its own (the editor would otherwise look it up again), and a place at its position.
 */
internal fun SearchEntry.asPlacePick(at: Coordinates? = null): StationMatch = when (this) {
    is SearchEntry.Stop -> if (match.latitude == null && at != null) match.copy(latitude = at.latitude, longitude = at.longitude) else match
    is SearchEntry.Place -> StationMatch(PlaceStart.id(hit.coordinate), hit.name, latitude = hit.coordinate.latitude, longitude = hit.coordinate.longitude, level = Pick.Level.PLACE)
}

/** The icon a saved [this] starts with: a searched place's type's, none for a stop, a street or a postcode. */
internal fun SearchEntry.placeIconOf(): String? =
    (this as? SearchEntry.Place)?.hit?.takeIf { it.kind == PlaceKind.PLACE }?.let { FavoritePlaceIcon.forType(it.type) }

/**
 * The menu a long press on a search row opens, titled with its [entry]'s name. Whether the row is on
 * Recent ([recent]) and which saved place sits at its position ([savedPlaces]) are worked out on
 * [LocalWorker] (AGENTS.md *Main thread: read and dispatch only*); the menu is shown once they are.
 * Each action closes the menu ([onDismiss]).
 */
@Composable
internal fun SearchRowMenu(
    entry: SearchEntry,
    recent: List<SearchEntry>,
    savedPlaces: List<FavoritePlace>,
    actions: SearchRowActions,
    onDismiss: () -> Unit,
    // False until the saved places have been read, or when they couldn't be: neither favorite item is
    // offered, since the row may be saved already and adding it would save it twice.
    placesKnown: Boolean = true,
    // Where a row carrying no position is (a bundled station), looked up off the main thread. Null
    // uses only the row's own.
    positionOf: (suspend (SearchEntry) -> Coordinates?)? = null,
) {
    val worker = LocalWorker.current
    // All at once, so the actions come up together and none moves under a finger reaching for another.
    val facts by produceState<RowFacts?>(null, entry, recent, savedPlaces) {
        val coordinate = entry.coordinate() ?: positionOf?.invoke(entry)
        value = withContext(worker) {
            RowFacts(
                coordinate = coordinate,
                inRecent = recent.any { it.key == entry.key },
                saved = coordinate?.let { FavoritePlaces.at(savedPlaces, it) },
            )
        }
    }
    val name = when (entry) {
        is SearchEntry.Stop -> entry.match.name
        is SearchEntry.Place -> entry.hit.name
    }
    // Up only once its items are known, so it appears whole rather than growing under a finger reaching
    // for Cancel; working them out takes the worker a moment.
    val known = facts ?: return
    val coordinate = known.coordinate
    // A window of its own: drawn over a glimpse of the page it's over, it would cover the page being swiped.
    if (!LocalPageOnTop.current) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (entry) {
                    is SearchEntry.Stop -> abbreviateStationName(name)
                    is SearchEntry.Place -> name
                },
            )
        },
        text = {
            Column {
                val map = actions.onShowOnMap
                if (map != null && coordinate != null) {
                    MenuItem(stringResource(R.string.action_show_on_map), "rowMenuMap") { onDismiss(); map(name, coordinate) }
                }
                val remove = actions.onAskRemoveRecent
                if (remove != null && known.inRecent) {
                    MenuItem(stringResource(R.string.station_search_remove_recent_action), "rowMenuRemoveRecent") { onDismiss(); remove(entry) }
                }
                val unsave = actions.onRemovePlace
                val place = known.saved
                val add = actions.onAddPlace
                // Nothing about favorites where whether the row is one couldn't be read.
                // A ride from a stop (not a place: a ride's ends are stops or saved places), asked To… next.
                val ride = actions.onAddRide
                if (ride != null && entry is SearchEntry.Stop) {
                    MenuItem(stringResource(R.string.add_favorite_ride), "rowMenuAddRide") { onDismiss(); ride(entry.match) }
                }
                if (placesKnown && unsave != null && place != null) {
                    MenuItem(stringResource(R.string.station_search_remove_favorite), "rowMenuRemovePlace") { onDismiss(); unsave(place) }
                } else if (placesKnown && add != null) {
                    MenuItem(stringResource(R.string.line_stop_favorite), "rowMenuAddPlace") { onDismiss(); add(entry.asPlacePick(coordinate), entry.placeIconOf()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.station_search_remove_recent_cancel)) }
        },
    )
}

private class RowFacts(val coordinate: Coordinates?, val inRecent: Boolean, val saved: FavoritePlace?)

@Composable
private fun MenuItem(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag(tag),
    )
}
