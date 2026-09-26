package app.stopdash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.StationMatch
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
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    TextField(
                        value = field,
                        onValueChange = { value ->
                            val edited = value.text != field.text
                            field = value
                            // A tap or drag that only moves the cursor isn't a new query to search.
                            if (edited) onQueryChange(value.text)
                        },
                        placeholder = { Text(hint ?: stringResource(R.string.station_search_hint)) },
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
            )
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
                    state.query.isBlank() && !state.yoursRead -> Unit
                    state.query.isBlank() && (state.favorites.isNotEmpty() || state.recent.isNotEmpty()) ->
                        YourStopsList(state.favorites, state.recent, onOpenStation)
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
                    val listState = rememberLazyListState()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().scrollEdgeCue(listState, scrollCueColors(MaterialTheme.colorScheme.background)),
                        state = listState,
                    ) {
                        items(result.matches, key = { it.id }) { match ->
                            MatchRow(match, onClick = { onOpenStation(match) })
                            HorizontalDivider()
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

/** The user's recent picks, most recent first, then their starred stops not picked lately, each under its heading. */
@Composable
private fun YourStopsList(favorites: List<StationMatch>, recent: List<StationMatch>, onOpenStation: (StationMatch) -> Unit) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("stationSearchYours")
            .scrollEdgeCue(listState, scrollCueColors(MaterialTheme.colorScheme.background)),
        state = listState,
    ) {
        listOf(R.string.station_search_recent to recent, R.string.station_search_starred to favorites).forEach { (heading, stops) ->
            if (stops.isEmpty()) return@forEach
            item(key = "heading-$heading") {
                Text(
                    stringResource(heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(stops, key = { "$heading-${it.id}" }) { match ->
                MatchRow(match, onClick = { onOpenStation(match) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MatchRow(match: StationMatch, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(match.name, style = MaterialTheme.typography.bodyLarge)
        val modes = modesLabel(match.modes)
        if (modes.isNotEmpty()) {
            Text(modes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Message(text: String) {
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

/** A hiding group's display name ("tube" → "Tube & DLR"), else its mode's name. */
internal fun groupName(group: ModeGroups.Group): String = GROUP_NAMES[group.key] ?: modeName(group.key)

/**
 * A group's name as it reads mid-sentence ("Hide all train services"): the common nouns lowercase,
 * the Tube and the DLR, being names, as they are.
 */
internal fun groupNameInSentence(group: ModeGroups.Group): String =
    GROUP_NAMES_IN_SENTENCE[group.key] ?: groupName(group)

private val GROUP_NAMES_IN_SENTENCE = mapOf(
    "tube" to "Tube & DLR",
    "train" to "train",
    "bus" to "bus",
    "tram" to "tram",
    "boat" to "boat",
    "coach" to "coach",
)

/** The hidden groups' names, in menu order ("Train, Bus"), for the banner and empty states. */
internal fun hiddenGroupsLabel(hidden: Set<String>): String =
    ModeGroups.hiddenGroups(hidden).joinToString(", ") { groupName(it) }

private val GROUP_NAMES = mapOf(
    "tube" to "Tube & DLR",
    "train" to "Train",
    "bus" to "Bus",
    "tram" to "Tram",
    "boat" to "Boat",
    "coach" to "Coach",
)

/** A mode's display name ("national-rail" → "National Rail"), or its id tidied for one we don't know. */
internal fun modeName(mode: String): String =
    KNOWN_MODE_NAMES[mode.lowercase(Locale.ROOT)] ?: mode.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

private val KNOWN_MODE_NAMES = mapOf(
    "tube" to "Tube",
    "bus" to "Bus",
    "dlr" to "DLR",
    "overground" to "Overground",
    "elizabeth-line" to "Elizabeth line",
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
                    if (onLocate != null) {
                        IconButton(onClick = onLocate) {
                            Icon(CrosshairIcon, contentDescription = stringResource(R.string.locate_here))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
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
