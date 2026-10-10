package app.stopdash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.ChipLabel
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.SearchEntry
import app.stopdash.domain.StationMatch
import app.stopdash.domain.TripDestination
import app.stopdash.domain.abbreviateStationName
import java.util.Locale

/**
 * "Find a station" (SPEC *Finding stops*): a name field in the app bar and TfL's matches below it.
 * UI-only — the query, the matches and the search itself live in [StationSearchViewModel] — so it
 * renders in a screenshot test with no network. A tap on a match opens that station's departures
 * ([onOpenStation]); Back (the arrow or the system back) closes the search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSearchScreen(
    state: StationSearchViewModel.State,
    onQueryChange: (String) -> Unit,
    onOpenStation: (StationMatch) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    // Off in the screenshot test, where a focused field would add a blinking cursor.
    autoFocus: Boolean = true,
    // The field's placeholder: "To station or stop" when picking a To… destination.
    hint: String? = null,
    // Set only by a To… picker: offers the user's saved favorite places at the top of the pre-query
    // list, each routed to as a coordinate (SPEC D9). Null elsewhere (a plain station browse has no
    // trip to route), which hides the section.
    onOpenPlace: ((TripDestination.Place) -> Unit)? = null,
    // Told of a geocoded place picked here, from the results or from Recent, before it's routed to
    // ([onOpenPlace]), so it's remembered with the recent stops. A saved place's chip isn't one.
    onPlacePicked: (PlaceHit) -> Unit = {},
    // Re-reads the saved places, for the Retry shown when their read failed. Null hides the retry.
    onRetryPlaces: (() -> Unit)? = null,
    // Set where the search picks where a trip starts (From…): "Here", behind the crosshair, heads the
    // list before anything is typed, and taps back to the rider's position (maintainer, 2026-09-28).
    // Null leaves it out, as the To… destination search does.
    onPickHere: (() -> Unit)? = null,
    // Set with [onPickHere] on From…: the saved places follow "Here" as chips, and a tap starts the
    // trip from that place's coordinate rather than routing to it. Null leaves them out.
    onStartFromPlace: ((TripDestination.Place) -> Unit)? = null,
    // Set by the favorite-journey picker: the saved places as chips, each picked as an end of the journey
    // (maintainer, 2026-10-10). Chips only: a typed address isn't offered, unlike [onOpenPlace].
    onPickPlace: ((FavoritePlace, String) -> Unit)? = null,
    // A long press on a place chip opens the saved places to edit them, as on the near-me list's
    // chips (SPEC *Routing from the near-me list*). Null offers none.
    onEditPlaces: (() -> Unit)? = null,
    // Set by a To… picker: the bar becomes "From" over "To" (maintainer, 2026-09-28), the From row
    // naming where the trip starts — [fromStation], or "Here" when null — and a tap changes it.
    // Null keeps the plain search bar.
    fromStation: String? = null,
    onChangeFrom: (() -> Unit)? = null,
    // A long press on a Recent row asks to take it off the list ([onAskRemoveRecent], null offering no
    // long press); the question is [StationSearchViewModel.State.pendingRemoval], so it outlives a
    // rotation, and Remove calls [onRemoveRecent], Cancel [onCancelRemoveRecent].
    onAskRemoveRecent: ((SearchEntry) -> Unit)? = null,
    onCancelRemoveRecent: () -> Unit = {},
    onRemoveRecent: (SearchEntry) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    // The field keeps its own value, cursor included. Bound to the query as a plain String it came
    // back from a picked station (which recomposes this screen afresh) with the cursor before the
    // first letter, so typing on inserted there; now it arrives after the text, and while the
    // screen stays up the cursor sits wherever the user put it. Only the query itself is typed
    // into this field, so seeding from it once is enough to stay in step.
    var field by remember { mutableStateOf(queryFieldValue(state.query)) }
    val onFieldChange = { value: TextFieldValue ->
        val edited = value.text != field.text
        field = value
        // A tap or drag that only moves the cursor isn't a new query to search.
        if (edited) onQueryChange(value.text)
    }
    val placeholder = hint ?: stringResource(R.string.station_search_hint)
    val endsLabelWidth = rememberTripEndsLabelWidth()
    // Under the From/To bar, the place chips start where the To field does, so they read as quick
    // picks for it (maintainer, 2026-09-28); elsewhere at the screen's 16dp margin.
    val chipsStart = if (onChangeFrom != null) tripEndsFieldStart(endsLabelWidth, LocalDensity.current) else 16.dp
    // The app's overflow at the From row's end, as on the trip page; none where the activity gives no menu.
    val appMenu: (@Composable () -> Unit)? = if (LocalAppMenu.current != null) { { AppMenuOverflow() } } else null
    Scaffold(
        topBar = {
            if (onChangeFrom != null) {
                TripEndsBar(
                    fromStation = fromStation,
                    onChangeFrom = onChangeFrom,
                    onBack = onBack,
                    labelWidth = endsLabelWidth,
                    actions = appMenu,
                ) { modifier ->
                    BasicTextField(
                        value = field,
                        onValueChange = onFieldChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = modifier.focusRequester(focus).testTag("stationSearchField"),
                        decorationBox = { inner ->
                            EndField {
                                // The placeholder under the (empty) text, so the cursor starts at its first letter.
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    if (field.text.isEmpty()) {
                                        Text(
                                            placeholder,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    inner()
                                }
                            }
                        },
                    )
                }
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = {
                        TextField(
                            value = field,
                            onValueChange = onFieldChange,
                            placeholder = { Text(placeholder) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("stationSearchField"),
                        )
                    },
                    actions = { AppMenuOverflow() },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Keeps the previous matches in view while the next search runs, so typing doesn't blank
            // the list on every letter; the bar says a newer answer is on its way.
            if (state.searching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Box(modifier = Modifier.height(4.dp))
            }
            when (val result = state.result) {
                StationSearchViewModel.Result.Idle -> when {
                    // Before anything is typed, the user's own stops, to pick without typing. Nothing
                    // until they're read, so the prompt doesn't flash up and then give way.
                    // "Here" needs nothing read, so a From… search lists it at once and the rest follow.
                    state.query.isBlank() && !state.yoursRead && onPickHere == null -> Unit
                    state.query.isBlank() && (
                        onPickHere != null || state.favorites.isNotEmpty() ||
                            state.recent.any { onOpenPlace != null || it is SearchEntry.Stop } ||
                            ((onOpenPlace != null || onPickPlace != null) && (state.favoritePlaces.isNotEmpty() || state.favoritePlacesFailed))
                        ) ->
                        YourStopsList(
                            favoritePlaces = state.favoritePlaces,
                            favoritePlacesFailed = state.favoritePlacesFailed,
                            favorites = state.favorites,
                            recent = state.recent,
                            onOpenStation = onOpenStation,
                            onOpenPlace = onOpenPlace,
                            onPlacePicked = onPlacePicked,
                            onRetryPlaces = onRetryPlaces,
                            onPickHere = onPickHere,
                            onStartFromPlace = onStartFromPlace,
                            onPickPlace = onPickPlace,
                            onEditPlaces = onEditPlaces,
                            onAskRemoveRecent = onAskRemoveRecent,
                            onCancelRemoveRecent = onCancelRemoveRecent,
                            onRemoveRecent = onRemoveRecent,
                            pendingRemoval = state.pendingRemoval,
                            removeRecentFailed = state.removeRecentFailed,
                            chipsStart = chipsStart,
                            chipsTop = if (onChangeFrom != null) 0.dp else 8.dp,
                        )
                    else -> Message(stringResource(R.string.station_search_prompt))
                }
                StationSearchViewModel.Result.NoMatches -> Message(stringResource(R.string.station_search_no_matches))
                is StationSearchViewModel.Result.Failed -> Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(errorMessage(result.kind)), textAlign = TextAlign.Center)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.route_stops_retry)) }
                }
                is StationSearchViewModel.Result.Matches -> {
                    // A new query's matches start at the top, best match first, not where the last
                    // query's list was scrolled to.
                    val listState = rememberSaveable(state.query, saver = LazyListState.Saver) { LazyListState() }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().scrollEdgeCue(listState, scrollCueColors(MaterialTheme.colorScheme.background))
                            .testTag("stationSearchMatches"),
                        state = listState,
                    ) {
                        // Stops and geocoded places in the order the search listed them: ranked by how well
                        // each name matches, and only ever added to as answers arrive, so a row doesn't move
                        // under a finger (maintainer, 2026-09-28). A place is tagged Place/Postcode and routed
                        // to as a coordinate (SPEC D9); only a To… picker sets onOpenPlace, so only it lists places.
                        val entries = if (onOpenPlace != null) result.entries else result.entries.filterIsInstance<SearchEntry.Stop>()
                        items(
                            entries,
                            key = { it.key },
                        ) { entry ->
                            when (entry) {
                                is SearchEntry.Stop -> MatchRow(entry.match, onClick = { onOpenStation(entry.match) })
                                is SearchEntry.Place -> PlaceHitRow(
                                    entry.hit,
                                    onClick = { openPlaceHit(entry.hit, onPlacePicked, onOpenPlace) },
                                )
                            }
                            HorizontalDivider()
                        }
                        // More is on its way and will land here, below what's listed: say so where it
                        // will appear, not only in the bar above.
                        if (state.searching) {
                            item(key = "loading-more") {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp).testTag(SEARCH_LOADING_MORE_TAG))
                                }
                            }
                        }
                        // The bundled stations matched but TfL's search (bus stops) failed: say so under
                        // the matches rather than show them as the whole answer.
                        result.remoteFailure?.let { kind ->
                            item(key = "remote-failure") {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(stringResource(R.string.station_search_bus_stops_missing), textAlign = TextAlign.Center)
                                    Text(
                                        stringResource(errorMessage(kind)),
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = onRetry) { Text(stringResource(R.string.route_stops_retry)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The search field's value for [query] on arrival: the text, with the cursor after it. */
internal fun queryFieldValue(query: String): TextFieldValue = TextFieldValue(query, TextRange(query.length))

// A geocoded place tapped, in the results or under Recent: remembered, then routed to as a coordinate
// (SPEC D9). Only a To… picker routes to one.
private fun openPlaceHit(hit: PlaceHit, onPicked: (PlaceHit) -> Unit, onOpenPlace: ((TripDestination.Place) -> Unit)?) {
    val route = onOpenPlace ?: return
    onPicked(hit)
    route(TripDestination.Place(hit.coordinate, hit.name))
}

/**
 * Before anything is typed: the user's saved [favoritePlaces] (a To… picker only — [onOpenPlace] set),
 * then their recent picks, stops and the places a To… search picked among them, then their starred
 * stops not picked lately, each under its heading. Places lead so a rider routing home taps once
 * without typing (maintainer, 2026-09-27).
 */
@Composable
private fun YourStopsList(
    favoritePlaces: List<FavoritePlace>,
    favoritePlacesFailed: Boolean,
    favorites: List<StationMatch>,
    recent: List<SearchEntry>,
    onOpenStation: (StationMatch) -> Unit,
    onOpenPlace: ((TripDestination.Place) -> Unit)?,
    onPlacePicked: (PlaceHit) -> Unit,
    onRetryPlaces: (() -> Unit)?,
    onPickHere: (() -> Unit)? = null,
    onStartFromPlace: ((TripDestination.Place) -> Unit)? = null,
    onPickPlace: ((FavoritePlace, String) -> Unit)? = null,
    onEditPlaces: (() -> Unit)? = null,
    onAskRemoveRecent: ((SearchEntry) -> Unit)? = null,
    onCancelRemoveRecent: () -> Unit = {},
    onRemoveRecent: (SearchEntry) -> Unit = {},
    pendingRemoval: SearchEntry? = null,
    removeRecentFailed: Boolean = false,
    // Where the chips start: the screen's margin, or the To field's edge under the From/To bar.
    chipsStart: Dp = 16.dp,
    // Space above the chips: none right under the From/To bar, so they sit close to the To field
    // they serve.
    chipsTop: Dp = 8.dp,
) {
    val listState = rememberLazyListState()
    pendingRemoval?.let { entry ->
        RemoveRecentDialog(
            name = when (entry) {
                is SearchEntry.Stop -> abbreviateStationName(entry.match.name)
                is SearchEntry.Place -> entry.hit.name
            },
            onRemove = { onRemoveRecent(entry) },
            onDismiss = onCancelRemoveRecent,
        )
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("stationSearchYours")
            .scrollEdgeCue(listState, scrollCueColors(MaterialTheme.colorScheme.background)),
        state = listState,
    ) {
        // One row of chips at the top: "Here" first on From…, then the saved places, on To… and on
        // From… (maintainer, 2026-09-28, 2026-10-04). Places lead so a rider routing home, or setting
        // out from home, taps once without typing.
        val onPlace = onOpenPlace ?: onStartFromPlace
        val places = if (onPlace != null || onPickPlace != null) favoritePlaces else emptyList()
        if (onPickHere != null || places.isNotEmpty()) {
            item(key = "chips") {
                FavoriteChips(
                    places = places,
                    onRouteTo = { onPlace?.invoke(it) },
                    onPickPlace = onPickPlace,
                    modifier = Modifier.padding(top = chipsTop, bottom = 8.dp),
                    contentPadding = PaddingValues(start = chipsStart, end = 16.dp),
                    onEditPlaces = onEditPlaces,
                    onHere = onPickHere,
                    labelOverride = ChipLabel.BOTH,
                    actionDescription = when {
                        onOpenPlace != null -> R.string.favorite_place_route_description
                        onStartFromPlace != null -> R.string.favorite_place_start_description
                        else -> R.string.favorite_place_pick_description
                    },
                )
            }
        }
        if ((onPlace != null || onPickPlace != null) && favoritePlaces.isEmpty() && favoritePlacesFailed) {
            // Read failed (not genuinely empty): say so honestly with a Retry, rather than hide the
            // places as "none" (SPEC principle 2). Station search below stays usable.
            item(key = "places-error") {
                PlacesError(onRetryPlaces)
                HorizontalDivider()
            }
        }
        // A place is routed to only where a trip can go to one, so elsewhere Recent lists stops alone.
        val recentShown = if (onOpenPlace != null) recent else recent.filterIsInstance<SearchEntry.Stop>()
        listOf(
            R.string.station_search_recent to recentShown,
            R.string.station_search_starred to favorites.map(SearchEntry::Stop),
        ).forEach { (heading, entries) ->
            if (entries.isEmpty()) return@forEach
            item(key = "heading-$heading") { SectionHeading(stringResource(heading)) }
            if (heading == R.string.station_search_recent && removeRecentFailed) {
                // The removal wasn't saved, so the row is back: say so rather than leave it unexplained.
                item(key = "remove-recent-failed") {
                    Text(
                        stringResource(R.string.station_search_remove_recent_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            // Only Recent's rows come off with a long press: a starred stop is removed where it was starred.
            val onLongPress = onAskRemoveRecent.takeIf { heading == R.string.station_search_recent }
            items(entries, key = { "$heading-${it.key}" }) { entry ->
                val onLongClick = onLongPress?.let { ask -> { ask(entry) } }
                when (entry) {
                    is SearchEntry.Stop -> MatchRow(entry.match, onClick = { onOpenStation(entry.match) }, onLongClick = onLongClick)
                    is SearchEntry.Place -> PlaceHitRow(
                        entry.hit,
                        onClick = { openPlaceHit(entry.hit, onPlacePicked, onOpenPlace) },
                        onLongClick = onLongClick,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}


/** Asks before a long-pressed Recent row ([name]) comes off the list: Remove or Cancel. */
@Composable
private fun RemoveRecentDialog(name: String, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.station_search_remove_recent_title)) },
        text = { Text(name) },
        confirmButton = {
            TextButton(onClick = onRemove, modifier = Modifier.testTag("removeRecent")) {
                Text(stringResource(R.string.station_search_remove_recent_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.station_search_remove_recent_cancel)) }
        },
    )
}

/** A row's tap, and its long press where it has one ([onLongClick]). */
private fun Modifier.rowClicks(onClick: () -> Unit, onLongClick: (() -> Unit)?, longClickLabel: String): Modifier =
    if (onLongClick == null) {
        clickable(onClick = onClick)
    } else {
        combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel)
    }

/** A section heading over one group of the pre-query list ("Places", "Recent", "Starred"). */
@Composable
internal fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** The Places section when the saved places couldn't be read: an honest line with a Retry, rather
 *  than hiding the section as if there were none (SPEC principle 2). */
@Composable
private fun PlacesError(onRetry: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.station_search_places_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.route_stops_retry)) }
        }
    }
}

/** A geocoded place in the To… results: its name, and a Place/Postcode tag in the right column (where
 *  a stop shows its modes) so a place reads apart from a stop, on one line at [MatchRow]'s density. */
@Composable
private fun PlaceHitRow(place: PlaceHit, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val tag = stringResource(
        when (place.kind) {
            PlaceKind.POSTCODE -> R.string.station_search_kind_postcode
            PlaceKind.PLACE -> R.string.station_search_kind_place
        },
    )
    val removeLabel = stringResource(R.string.station_search_remove_recent_action)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .rowClicks(onClick, onLongClick, removeLabel)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            place.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            tag,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun MatchRow(match: StationMatch, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    // One line per result (name, then its modes on the right) so more fit on screen. The name takes
    // priority — it fills the row (pushing the modes to the right edge) and gets every pixel the modes
    // don't need, so a long name like "King's Cross & St Pancras International" shows as much as fits
    // and ellipsizes only what's left. The modes are bounded and single-line, so a many-mode station
    // (Stratford's five modes) can't grow to squeeze the name to nothing or wrap at large text scales.
    val modes = modesLabel(match.modes)
    // The clickable merges its children, so TalkBack reads one label for the whole row: give it the
    // FULL station name (not the visual "Intl" abbreviation) plus the modes. A per-child description
    // would be read alone and drop the modes; the visual modes text can ellipsize, but this doesn't.
    val spoken = if (modes.isEmpty()) match.name else "${match.name}, $modes"
    val removeLabel = stringResource(R.string.station_search_remove_recent_action)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .rowClicks(onClick, onLongClick, removeLabel)
            // 8dp padding keeps the denser look; the min height holds the row at Android's 48dp tap
            // target, which a one-mode row's ~40dp would otherwise miss (more so at large text scales).
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Bound the modes to a fraction of the row rather than a fixed dp, so the cap scales with the
        // window: on a narrow (320dp) screen a 200dp cap would leave the name too little, but 45% keeps
        // the name the majority at any width. It's a max, not a fixed width — a short mode list uses
        // only its intrinsic width, so the name still gets everything the modes don't need.
        val modesMax = maxWidth * 0.45f
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Display-only shortening (International → Intl); the full name still backs matching and,
                // via [spoken] above, the screen-reader label.
                abbreviateStationName(match.name),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (modes.isNotEmpty()) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    modes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = modesMax),
                )
            }
        }
    }
}

@Composable
internal fun Message(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A match's modes as a rider reads them, in TfL's order: "Tube · Bus". TfL's mode ids are
 * lower-case and hyphenated; the known ones get their proper names, any other is spaced and
 * capitalized rather than hidden.
 */
internal fun modesLabel(modes: List<String>): String =
    modes.filter { it.isNotBlank() }.distinct().joinToString(" · ") { mode ->
        modeName(mode)
    }

/** A hiding group's display name ("tube" → "Underground"), else its mode's name. */
internal fun groupName(group: ModeGroups.Group): String = GROUP_NAMES[group.key] ?: modeName(group.key)

/**
 * What a hidden item is called where it's named alone (the Undo snackbar, Settings' Hidden list): a
 * hidden line by its own label ("Northern line"), a group by [groupName].
 */
internal fun hiddenItemName(group: ModeGroups.Group): String =
    if (HiddenModes.isLineKey(group.key)) HiddenModes.hiddenLineLabels(setOf(group.key)).single() else groupName(group)

/**
 * A group's name as it reads mid-sentence ("Hide all bus services"): the common nouns lowercase,
 * the Underground, the Overground and National Rail, being names, as they are.
 */
internal fun groupNameInSentence(group: ModeGroups.Group): String =
    GROUP_NAMES_IN_SENTENCE[group.key] ?: groupName(group)

private val GROUP_NAMES_IN_SENTENCE = mapOf(
    "tube" to "Underground",
    "overground" to "Overground",
    "rail" to "National Rail",
    "bus" to "bus",
    "tram" to "tram",
    "boat" to "boat",
)

/** The hidden groups' names, in menu order ("Train, Bus"), for the banner and empty states. */
internal fun hiddenGroupsLabel(hidden: Set<String>): String {
    // Hidden lines follow the groups: one by its name ("Northern line"), more by their count, so the
    // banner stays one line however many are hidden.
    val lines = HiddenModes.hiddenLineLabels(hidden)
    val linesPart = when (lines.size) {
        0 -> emptyList()
        1 -> lines
        else -> listOf("${lines.size} lines")
    }
    return (ModeGroups.hiddenGroups(hidden).map { groupName(it) } + linesPart).joinToString(", ")
}

/**
 * What a group holds that its name doesn't say, under it in the list's menu (maintainer,
 * 2026-10-08), kept short; null for a group whose name says it all.
 */
internal fun groupSubtitle(group: ModeGroups.Group): String? = GROUP_SUBTITLES[group.key]

private val GROUP_SUBTITLES = mapOf(
    "tube" to "Tube, DLR, Elizabeth",
    "rail" to "Thameslink, Southern, …",
)

private val GROUP_NAMES = mapOf(
    "tube" to "Underground",
    "overground" to "Overground",
    "rail" to "National Rail",
    "bus" to "Bus",
    "tram" to "Tram",
    "boat" to "Boat",
)

/** A mode's display name ("national-rail" → "National Rail"), or its id tidied for one we don't know. */
internal fun modeName(mode: String): String =
    KNOWN_MODE_NAMES[mode.lowercase(Locale.ROOT)] ?: mode.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

private val KNOWN_MODE_NAMES = mapOf(
    "tube" to "Tube",
    "bus" to "Bus",
    "dlr" to "DLR",
    "overground" to "Overground",
    "elizabeth-line" to "Elizabeth",
    "national-rail" to "National Rail",
    "tram" to "Tram",
    "river-bus" to "River Bus",
    "cable-car" to "Cable car",
)

/**
 * The station page while its stops are still being looked up, or when that failed or found none:
 * the station's name in the app bar with a back arrow, so the page appears at once (AGENTS jank
 * rule) and an error says what went wrong with a way to retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationPlaceholderScreen(
    title: String,
    state: StationStopsViewModel.State,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    // The crosshairs, as on the page this stands in for (SPEC *Finding stops*): from a From…
    // station, back to the near-me list, so a station TfL can't place isn't a dead end. Null hides it.
    onLocate: (() -> Unit)? = null,
    // A look at a stop's departures: Back to the stop, in To…'s place, as the page this stands in for
    // shows it (MainScreen's stationBrowse), so it's there from the first frame (Codex on #736).
    onBrowseBack: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(title, maxLines = 1) },
                actions = {
                    if (onLocate != null && LocalLocationAllowed.current) {
                        IconButton(onClick = onLocate) {
                            Icon(CrosshairIcon, contentDescription = stringResource(R.string.locate_here))
                        }
                    }
                    if (onBrowseBack != null) {
                        TextButton(onClick = onBrowseBack, modifier = Modifier.testTag("stationBrowseBack")) {
                            Text(stringResource(R.string.station_browse_back))
                        }
                    }
                    AppMenuOverflow()
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when (state) {
                    is StationStopsViewModel.State.Failed -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(errorMessage(state.kind)), textAlign = TextAlign.Center)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.route_stops_retry)) }
                    }
                    StationStopsViewModel.State.NoStops -> Message(stringResource(R.string.station_no_departures))
                    else -> CircularProgressIndicator()
                }
            }
        }
    }
}

/** The spinner under the listed matches while more answers are on their way. */
internal const val SEARCH_LOADING_MORE_TAG = "searchLoadingMore"
