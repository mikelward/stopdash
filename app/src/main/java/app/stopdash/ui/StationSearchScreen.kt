package app.stopdash.ui

import android.content.res.Resources
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.key
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
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.PlaceArt
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
    // What [fromStation]'s place is, when it names a place rather than a station: its name leads with
    // its own icon ([fromIcon], a saved place's) or this one's.
    fromPlace: PlaceArt? = null,
    fromIcon: String? = null,
    // With [onChangeFrom]: the stop the trip goes via, picked before where it goes (a view's Via, SPEC *Going via a
    // stop*), as a Via row between From and To. Null shows none.
    via: TripEndsVia? = null,
    // With [onChangeFrom]: *Via…* in the overflow, over the app's own items, to pick a stop the trip goes via before
    // where it goes, as the trip's page offers it (SPEC *Going via a stop*). Null offers none.
    onPickVia: (() -> Unit)? = null,
    // A Recent row's menu asks to take it off the list ([onAskRemoveRecent], null offering no such
    // item); the question is [StationSearchViewModel.State.pendingRemoval], so it outlives a
    // rotation, and Remove calls [onRemoveRecent], Cancel [onCancelRemoveRecent].
    onAskRemoveRecent: ((SearchEntry) -> Unit)? = null,
    onCancelRemoveRecent: () -> Unit = {},
    onRemoveRecent: (SearchEntry) -> Unit = {},
    // "Search outside London", and its Retry: asks TfL for the typed place
    // ([StationSearchViewModel.searchOutside]). Shown only where a place can be routed to or started from.
    onSearchOutside: () -> Unit = {},
    // A long press on any row, a result or one of the user's own, opens its menu ([SearchRowMenu]):
    // Show on map, Remove from Recent (a Recent row: [onAskRemoveRecent]), and Add to or Remove from
    // favorite places (maintainer, 2026-10-12). Null leaves that item out.
    // The activity's by default ([LocalSearchRowHost]); a removed place reads the places again ([onRetryPlaces]).
    onShowOnMap: ((String, Coordinates) -> Unit)? = LocalSearchRowHost.current?.showOnMap,
    onAddPlace: ((StationMatch, String?) -> Unit)? = LocalSearchRowHost.current?.addPlace,
    onRemovePlace: ((FavoritePlace) -> Unit)? = LocalSearchRowHost.current?.let { host ->
        { place -> host.removePlace(place, { onRetryPlaces?.invoke() }) }
    },
    // Where a row carrying no position is (a bundled station), for its menu: the view model's
    // [StationSearchViewModel.positionOf]. Null uses only the row's own.
    positionOf: (suspend (SearchEntry) -> Coordinates?)? = null,
) {
    val back = rememberBackFollow()
    PredictiveBack(back, onBack = onBack)
    val rowActions = SearchRowActions(onShowOnMap, onAskRemoveRecent, onAddPlace, onRemovePlace)
    // The row whose menu is up. Not kept across a rotation, which closes it.
    var menuFor by remember { mutableStateOf<SearchEntry?>(null) }
    // A long press opens the row's menu; where the screen offers no action it does nothing, rather
    // than counting as a tap that opens the row (and adds it to Recent).
    val onLongPress: (SearchEntry) -> Unit = { entry -> if (rowActions.any) menuFor = entry }
    menuFor?.let { entry ->
        SearchRowMenu(
            entry = entry,
            recent = state.recent,
            savedPlaces = state.favoritePlaces,
            actions = rowActions,
            onDismiss = { menuFor = null },
            // Known once read, and read whole: before a read lands (the first, or one after a place was
            // saved), or when it failed, a row may be saved already, so neither favorite item is offered.
            placesKnown = state.yoursRead && !state.yoursReading && !state.favoritePlacesFailed,
            positionOf = positionOf,
        )
    }
    // Asked from a row's menu, a typed result's or a Recent row's, so asked here over either list.
    state.pendingRemoval?.let { entry ->
        RemoveRecentDialog(
            name = when (entry) {
                is SearchEntry.Stop -> abbreviateStationName(entry.match.name)
                is SearchEntry.Place -> entry.hit.name
            },
            onRemove = { onRemoveRecent(entry) },
            onDismiss = onCancelRemoveRecent,
        )
    }
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
    // The keyboard's Search key (maintainer, 2026-10-13): once the typed query's answer is all in, the
    // row it is sure of ([StationSearchViewModel.Result.Matches.searchKey]) opens as a tap on it would;
    // otherwise, or while TfL's places outside London are being looked up or listed below, the key only
    // closes the keyboard and leaves the list to choose from. Reads what the search worked out, never
    // the rows themselves. A keyboard can send its last edit and the action together, before this
    // screen recomposes with the edit's state: the state is the field's own only while its query is
    // the field's text (the same string, handed straight through), so otherwise it opens nothing
    // rather than the last query's row (Codex on #828).
    val onSearchKey = {
        keyboard?.hide()
        val onPlace = onOpenPlace ?: onStartFromPlace
        val pick = (state.result as? StationSearchViewModel.Result.Matches)?.searchKey
            ?.takeIf { state.query == field.text }
            ?.takeIf { !state.searching && state.outside !is OutsideLondon.Searching && state.outside !is OutsideLondon.Found }
        when (pick) {
            is SearchEntry.Stop -> onOpenStation(pick.match)
            is SearchEntry.Place -> if (onPlace != null) openPlaceHit(pick.hit, onPlacePicked, onPlace)
            null -> Unit
        }
    }
    val placeholder = hint ?: stringResource(R.string.station_search_hint)
    val endsLabelWidth = rememberTripEndsLabelWidth()
    // Under the From/To bar, the place chips start where the To field does, so they read as quick
    // picks for it (maintainer, 2026-09-28); elsewhere at the screen's 16dp margin.
    val chipsStart = if (onChangeFrom != null) tripEndsFieldStart(endsLabelWidth, LocalDensity.current) else 16.dp
    // The app's overflow at the From row's end, as on the trip page; none where the activity gives no menu.
    val appMenu: (@Composable () -> Unit)? = if (LocalAppMenu.current != null) {
        {
            AppMenuOverflow { close ->
                if (onPickVia != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trip_via_pick)) },
                        onClick = {
                            close()
                            onPickVia()
                        },
                        modifier = Modifier.testTag("searchPickVia"),
                    )
                }
            }
        }
    } else null
    Scaffold(
        modifier = Modifier.followsBack(back),
        topBar = {
            if (onChangeFrom != null) {
                TripEndsBar(
                    fromStation = fromStation,
                    onChangeFrom = onChangeFrom,
                    onBack = onBack,
                    labelWidth = endsLabelWidth,
                    fromPlace = fromPlace,
                    fromIcon = fromIcon,
                    actions = appMenu,
                    via = via,
                ) { modifier ->
                    BasicTextField(
                        value = field,
                        onValueChange = onFieldChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchKey() }),
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
                            keyboardActions = KeyboardActions(onSearch = { onSearchKey() }),
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
                            state.recent.any { onOpenPlace != null || onStartFromPlace != null || it is SearchEntry.Stop } ||
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
                            onLongPress = onLongPress,
                            removeRecentFailed = state.removeRecentFailed,
                            areas = state.yourAreas,
                            chipsStart = chipsStart,
                            chipsTop = if (onChangeFrom != null) 8.dp else 16.dp,
                        )
                    else -> Message(stringResource(R.string.station_search_prompt))
                }
                StationSearchViewModel.Result.NoMatches -> {
                    val onPlace = onOpenPlace ?: onStartFromPlace
                    if (onPlace == null || state.outside == OutsideLondon.Hidden) {
                        Message(stringResource(R.string.station_search_no_matches))
                    } else {
                        // Nothing in London, but the place may be outside it: offer TfL's search below.
                        LazyColumn(modifier = Modifier.fillMaxSize().testTag("stationSearchMatches")) {
                            // Said until TfL lists places outside London, which then are the matches.
                            if (state.outside !is OutsideLondon.Found) {
                                item(key = "no-matches") { Message(stringResource(R.string.station_search_no_matches)) }
                            }
                            outsideLondon(state.outside, onSearchOutside, onLongPress = { onLongPress(SearchEntry.Place(it)) }) { openPlaceHit(it, onPlacePicked, onPlace) }
                        }
                    }
                }
                is StationSearchViewModel.Result.Matches -> {
                    // A new query's matches start at the top, best match first, not where the last
                    // query's list was scrolled to. Keyed on the query the rows answer, not the one typed:
                    // a state made at the letter kept the last query's top row in view when the answer
                    // landed, and where that row still matched lower down, the list scrolled to it, past
                    // the new best match. A key group rather than rememberSaveable's inputs, which a restore
                    // doesn't check: the saved scroll belongs to its query, so after process death one
                    // query's can't land on another's rows (Codex, #825).
                    val listState = key(result.query) { rememberSaveable(saver = LazyListState.Saver) { LazyListState() } }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().scrollEdgeCue(listState, scrollCueColors(MaterialTheme.colorScheme.background))
                            .testTag("stationSearchMatches"),
                        state = listState,
                    ) {
                        // Stops and geocoded places in the order the search listed them: ranked by how well
                        // each name matches, and only ever added to as answers arrive, so a row doesn't move
                        // under a finger (maintainer, 2026-09-28). A place is tagged Place/Postcode and routed
                        // to as a coordinate (SPEC D9), or on From… started from; a search that can do neither
                        // lists stops alone.
                        val onPlace = onOpenPlace ?: onStartFromPlace
                        val entries = if (onPlace != null) result.entries else result.entries.filterIsInstance<SearchEntry.Stop>()
                        // Only places matched, and this search lists none (the favorite-journey picker): say so.
                        if (entries.isEmpty() && !state.searching) {
                            item(key = "no-stop-matches") { Message(stringResource(R.string.station_search_no_matches)) }
                        }
                        items(
                            entries,
                            key = { it.key },
                        ) { entry ->
                            when (entry) {
                                is SearchEntry.Stop -> MatchRow(
                                    entry.match,
                                    onClick = { onOpenStation(entry.match) },
                                    onLongClick = { onLongPress(entry) },
                                    area = result.areas[entry.match.key],
                                )
                                is SearchEntry.Place -> PlaceHitRow(
                                    entry.hit,
                                    onClick = { openPlaceHit(entry.hit, onPlacePicked, onPlace) },
                                    onLongClick = { onLongPress(entry) },
                                    area = result.areas[entry.key],
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
                        // TfL's places outside London, below everything bundled, so no row above moves.
                        if (onPlace != null) {
                            outsideLondon(state.outside, onSearchOutside, onLongPress = { onLongPress(SearchEntry.Place(it)) }) { openPlaceHit(it, onPlacePicked, onPlace) }
                        }
                    }
                }
            }
        }
    }
}

/** The search field's value for [query] on arrival: the text, with the cursor after it. */
internal fun queryFieldValue(query: String): TextFieldValue = TextFieldValue(query, TextRange(query.length))

// A place tapped, in the results or under Recent: remembered, then routed to as a coordinate (SPEC D9)
// by To…, or started from by From… — whichever [onOpenPlace] is; a search doing neither ignores it.
private fun openPlaceHit(hit: PlaceHit, onPicked: (PlaceHit) -> Unit, onOpenPlace: ((TripDestination.Place) -> Unit)?) {
    val route = onOpenPlace ?: return
    onPicked(hit)
    route(TripDestination.Place(hit.coordinate, hit.name, hit.art))
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
    onLongPress: (SearchEntry) -> Unit = {},
    removeRecentFailed: Boolean = false,
    // Where the chips start: the screen's margin, or the To field's edge under the From/To bar.
    chipsStart: Dp = 16.dp,
    // Space above the chips' outline: the least, 8dp, right under the From/To bar, so they sit close
    // to the To field they serve. Their touch target reaches 8dp past the outline into it.
    chipsTop: Dp = 16.dp,
    // Each row's postcode district, by [SearchEntry.key] ([StationSearchViewModel.State.yourAreas]).
    areas: Map<String, String> = emptyMap(),
) {
    val listState = rememberLazyListState()
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
                    modifier = Modifier.padding(top = chipsTop, bottom = 16.dp),
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
        // A place is listed only where it can be routed to (To…) or started from (From…); elsewhere
        // Recent lists stops alone.
        val recentShown = if (onOpenPlace != null || onStartFromPlace != null) recent else recent.filterIsInstance<SearchEntry.Stop>()
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
            items(entries, key = { "$heading-${it.key}" }) { entry ->
                val onLongClick = { onLongPress(entry) }
                when (entry) {
                    is SearchEntry.Stop -> MatchRow(entry.match, onClick = { onOpenStation(entry.match) }, onLongClick = onLongClick, area = areas[entry.match.key])
                    is SearchEntry.Place -> PlaceHitRow(
                        entry.hit,
                        onClick = { openPlaceHit(entry.hit, onPlacePicked, onOpenPlace ?: onStartFromPlace) },
                        onLongClick = onLongClick,
                        area = areas[entry.key],
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

/**
 * Places outside London, from TfL (maintainer, 2026-10-11), below everything bundled so no row above
 * moves: "Search outside London" until asked, then what TfL found under an "Outside London" heading, a
 * row each, or why there's nothing, with a Retry.
 */
internal fun LazyListScope.outsideLondon(
    outside: OutsideLondon,
    onSearch: () -> Unit,
    // A row's long press: its menu, as any other search row's. Null gives none.
    onLongPress: ((PlaceHit) -> Unit)? = null,
    onPick: (PlaceHit) -> Unit,
) {
    when (outside) {
        OutsideLondon.Hidden -> Unit
        OutsideLondon.Offered -> item(key = "outside-offer") { OutsideOfferRow(onSearch) }
        else -> {
            item(key = "outside-heading") { SectionHeading(stringResource(R.string.station_search_outside_heading)) }
            when (outside) {
                is OutsideLondon.Found -> items(outside.places, key = { "outside-${it.name}-${it.coordinate}" }) { hit ->
                    PlaceHitRow(hit, onClick = { onPick(hit) }, onLongClick = onLongPress?.let { press -> { press(hit) } })
                    HorizontalDivider()
                }
                else -> item(key = "outside-status") { OutsideStatusRow(outside, onSearch) }
            }
        }
    }
}

/** [outsideLondon] for a screen that isn't a lazy list (the favorite place editor). */
@Composable
internal fun OutsideLondonRows(
    outside: OutsideLondon,
    onSearch: () -> Unit,
    onPick: (PlaceHit) -> Unit,
) {
    when (outside) {
        OutsideLondon.Hidden -> Unit
        OutsideLondon.Offered -> OutsideOfferRow(onSearch)
        else -> Column(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.station_search_outside_heading))
            if (outside is OutsideLondon.Found) {
                outside.places.forEach { hit ->
                    PlaceHitRow(hit, onClick = { onPick(hit) })
                    HorizontalDivider()
                }
            } else {
                OutsideStatusRow(outside, onSearch)
            }
        }
    }
}

@Composable
private fun OutsideOfferRow(onSearch: () -> Unit) {
    Text(
        stringResource(R.string.station_search_outside_search),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onSearch)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(SEARCH_OUTSIDE_TAG),
    )
    HorizontalDivider()
}

// Searching, nothing found, or failed with a Retry; nothing for the other states.
@Composable
private fun OutsideStatusRow(outside: OutsideLondon, onSearch: () -> Unit) {
    when (outside) {
        OutsideLondon.Searching -> Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        OutsideLondon.NoMatches -> Message(stringResource(R.string.station_search_outside_none))
        OutsideLondon.Failed -> Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.station_search_outside_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSearch) { Text(stringResource(R.string.route_stops_retry)) }
        }
        else -> Unit
    }
}

/** A place in the search results: its name, and in the right column (where a stop shows its modes) its
 *  postcode district and an icon for its type, a street or a postcode ([RowTag]), so a place reads apart
 *  from a stop, on one line at [MatchRow]'s density. */
@Composable
internal fun PlaceHitRow(place: PlaceHit, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, area: String? = null) {
    val kind = stringResource(placeTypeLabel(place))
    // The place's postcode district leads its icon ("EC4M · [museum]"), as a stop's leads its modes, so
    // a place and the stops beside it read as the same spot. A screen reader hears the words.
    val spoken = listOfNotNull(place.name, area, kind).joinToString(", ")
    val menuLabel = stringResource(R.string.station_search_row_menu_action)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .rowClicks(onClick, onLongClick, menuLabel)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = spoken },
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
        RowTag(area, icon = placeIcon(place))
    }
}

@Composable
internal fun MatchRow(match: StationMatch, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, area: String? = null) {
    // One line per result (name, then its modes on the right) so more fit on screen. The name takes
    // priority — it fills the row (pushing the modes to the right edge) and gets every pixel the modes
    // don't need, so a long name like "King's Cross & St Pancras International" shows as much as fits
    // and ellipsizes only what's left. The modes are bounded and single-line, so a many-mode station
    // (Stratford's five modes) can't grow to squeeze the name to nothing or wrap at large text scales.
    // The stop's postcode district leads its modes ("EC4M · Bus"), so same-named stops read apart.
    // Read from the match, which worked the label out off the main thread (AGENTS *Main thread*).
    val modes = when {
        area == null -> match.modesSpoken
        match.modesSpoken.isEmpty() -> area
        else -> "$area · ${match.modesSpoken}"
    }
    // The clickable merges its children, so TalkBack reads one label for the whole row: give it the
    // FULL station name (not the visual "Intl" abbreviation) plus the modes. A per-child description
    // would be read alone and drop the modes; the visual modes text can ellipsize, but this doesn't.
    val spoken = if (modes.isEmpty()) match.name else "${match.name}, $modes"
    val menuLabel = stringResource(R.string.station_search_row_menu_action)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .rowClicks(onClick, onLongClick, menuLabel)
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
                // The district, then a mode's icon each (maintainer, 2026-10-13), precomputed on the match;
                // [spoken] says the words. A mode with no icon is named, which only a mode new to TfL is.
                RowTag(
                    area,
                    Modifier.widthIn(max = modesMax),
                    modes = match.modeIcons,
                    extra = match.otherModesLabel,
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


/** A hiding group's display name ("tube" → "Underground"), else its mode's name. */
internal fun groupName(group: ModeGroups.Group, resources: Resources): String =
    GROUP_NAMES[group.key]?.let { resources.getString(it) } ?: modeName(group.key)

/**
 * What a hidden item is called where it's named alone (the Undo snackbar, Settings' Hidden list): a
 * hidden line by its own label ("Northern line"), a group by [groupName].
 */
internal fun hiddenItemName(group: ModeGroups.Group, resources: Resources): String =
    if (HiddenModes.isLineKey(group.key)) HiddenModes.hiddenLineLabels(setOf(group.key)).single() else groupName(group, resources)

/**
 * A group's name as it reads mid-sentence ("Hide all bus services"): the common nouns lowercase,
 * the Underground, the Overground and National Rail, being names, as they are.
 */
internal fun groupNameInSentence(group: ModeGroups.Group, resources: Resources): String =
    GROUP_NAMES_IN_SENTENCE[group.key]?.let { resources.getString(it) } ?: groupName(group, resources)

private val GROUP_NAMES_IN_SENTENCE = mapOf(
    "bus" to R.string.mode_group_bus_in_sentence,
    "tram" to R.string.mode_group_tram_in_sentence,
    "boat" to R.string.mode_group_boat_in_sentence,
)

/** The hidden groups' names, in menu order ("Train, Bus"), for the banner and empty states. */
internal fun hiddenGroupsLabel(hidden: Set<String>, resources: Resources): String {
    // Hidden lines follow the groups: one by its name ("Northern line"), more by their count, so the
    // banner stays one line however many are hidden.
    val lines = HiddenModes.hiddenLineLabels(hidden)
    val linesPart = when (lines.size) {
        0 -> emptyList()
        1 -> lines
        else -> listOf(resources.getQuantityString(R.plurals.hidden_lines_count, lines.size, lines.size))
    }
    return (ModeGroups.hiddenGroups(hidden).map { groupName(it, resources) } + linesPart).joinToString(", ")
}

/**
 * What a group holds that its name doesn't say, under it in the list's menu (maintainer,
 * 2026-10-08), kept short; null for a group whose name says it all.
 */
internal fun groupSubtitle(group: ModeGroups.Group, resources: Resources): String? =
    GROUP_SUBTITLES[group.key]?.let { resources.getString(it) }

private val GROUP_SUBTITLES = mapOf(
    "tube" to R.string.mode_group_tube_subtitle,
    "rail" to R.string.mode_group_rail_subtitle,
)

private val GROUP_NAMES = mapOf(
    "tube" to R.string.mode_group_tube,
    "overground" to R.string.mode_group_overground,
    "rail" to R.string.mode_group_rail,
    "bus" to R.string.mode_group_bus,
    "tram" to R.string.mode_group_tram,
    "boat" to R.string.mode_group_boat,
)

/** A mode's display name ("national-rail" → "National Rail"), or its id tidied for one we don't know. */
internal fun modeName(mode: String): String = app.stopdash.domain.modeName(mode)

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
    val back = rememberBackFollow()
    PredictiveBack(back, onBack = onBack)
    Scaffold(
        modifier = Modifier.followsBack(back),
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
internal const val SEARCH_OUTSIDE_TAG = "searchOutsideLondon"
