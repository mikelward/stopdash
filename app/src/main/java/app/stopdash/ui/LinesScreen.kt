package app.stopdash.ui

import app.stopdash.domain.StopDistance
import app.stopdash.domain.NearestStops
import app.stopdash.domain.Coordinates
import androidx.compose.runtime.Immutable
import androidx.compose.material3.Button
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import app.stopdash.R
import app.stopdash.domain.LineRef

/**
 * Opens *Lines…* (SPEC *Finding a line*), for the overflows that offer it under From… and To…: the
 * near-me list's and the location screen's. Null (a test, a preview) offers no item.
 */
val LocalOpenLines = compositionLocalOf<(() -> Unit)?> { null }

/** The overflow's *Lines…* item, where the activity offers it ([LocalOpenLines]). */
@Composable
internal fun LinesMenuItem(close: () -> Unit) {
    val openLines = LocalOpenLines.current ?: return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_lines)) },
        onClick = {
            close()
            openLines()
        },
    )
}

/**
 * *Lines…* (SPEC *Finding a line*): the search ([LineSearchScreen]) and, once a line is picked, its
 * page ([OneLinePage]): its status and its map. A station tapped on that map opens its details
 * ([LineStopPage]): its name, how far it is from [here], and From and To. The picked line, [open], and
 * stop, [stop], are held by the caller ([onOpen], [onStop] set them), so a rotation, a return to the app,
 * or Back from an overlay shown above this one keeps the page up; Back from the stop returns to the
 * line, from the line to the search, and from the search closes it ([onBack]).
 */
@Composable
internal fun LinesOverlay(
    viewModel: LinesViewModel,
    open: LineRef?,
    onOpen: (LineRef?) -> Unit,
    onBack: () -> Unit,
    // The search's own saved state (its list's scroll), held by the caller above any overlay that can
    // cover this one (Licenses), so it outlives this leaving composition (Codex on #652).
    saveable: SaveableStateHolder = rememberSaveableStateHolder(),
    stop: LineStopRef? = null,
    onStop: (LineStopRef?) -> Unit = {},
    // The rider's last fix, for the stop's distance; null shows none.
    here: Coordinates? = null,
    // From: the stop's own page, as From… opens a station's. To: a trip there from here, as To… plans
    // one; null where To… isn't offered (no near-me stops to start from).
    onFrom: (LineStopRef) -> Unit = {},
    onTo: ((LineStopRef) -> Unit)? = null,
    // The stop's live departures, from a model the caller keeps alive and refreshing while the page is
    // up ([StopDepartures]); null draws none.
    // The line it was opened from goes with it, so its status is asked for even with nothing due there.
    departures: @Composable (LineStopRef, LineRef) -> StopDepartures? = { _, _ -> null },
) {
    // Each time the overlay comes up: TfL's list is asked for again if its day is up, or after a failure.
    LaunchedEffect(Unit) { viewModel.reopened() }
    // [saveable] keeps the search's scroll while a line's page is over it, so Back returns to the line
    // picked, not to the top of the list.
    val line = open
    if (line != null) {
        LaunchedEffect(line.id) { viewModel.open(line) }
        // Its status asked as the page comes up and every [LinesViewModel.checkEvery] while it's on screen,
        // a return to the app included, so it never shows an answer past its age as current (SPEC D4).
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(line.id, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    viewModel.check(line)
                    delay(viewModel.checkEvery.toMillis())
                }
            }
        }
        val check by viewModel.check.collectAsStateWithLifecycle()
        val held = check?.takeIf { it.lineId == line.id }
        val slot = remember { mutableStateOf<Worked<Inputs, TripRow>?>(null) }
        val status = held?.status
        val unknown = held?.unknown == true
        // No answer up and none failed (asking, or the last one taken down at its age): "Checking…", never
        // a clean line claimed for want of one (SPEC D4); the next tick asks.
        val checking = held == null || held.checking || (held.status == null && !held.unknown)
        val dismissed by viewModel.lineDismissed.collectAsStateWithLifecycle()
        val row = rememberWorked(slot, Inputs(line.id, line.name, line.mode, status, dismissed, unknown, checking), keep = ::sameVerdict) {
            // No stop of the rider's: the map draws the whole line, none of it marked as theirs.
            lineRow(line.mode, line.id, line.name, "", "", status, dismissed = dismissed, ride = null, unknown = unknown, checking = checking)
        }
        // Its alert dismissible here as on any line's page (SPEC *Disruptions*), into the shared store.
        val dismissFailed by viewModel.dismissWriteFailed.collectAsStateWithLifecycle()
        val pagesOpen = remember { mutableIntStateOf(0) }
        val dismissal = LineAlertDismissal(viewModel::dismiss, dismissFailed, viewModel::dismissWriteFailureShown, pagesOpen)
        val system = LocalDistanceSystem.current
        CompositionLocalProvider(
            LocalDismissLineAlert provides dismissal,
            // A station tapped on the map opens its details (SPEC *Finding a line*).
            LocalOpenLineMapStop provides { id, name, position ->
                val distance = if (position == null || here == null || system == null) {
                    null
                } else {
                    StopDistance.label(NearestStops.distanceMeters(here.latitude, here.longitude, position.first, position.second), system)
                }
                onStop(LineStopRef(id, name, distance))
            },
        ) {
            OneLinePage(row, line.id, line.name, line.mode, onClose = { onOpen(null) })
        }
        if (stop != null) {
            // A stop's details are a window of their own over the line's page (itself one, [TripLinesPage]),
            // which stays up beneath them with its place, its folds and its work, so Back returns to its map
            // just as it was, the station just opened in view (Codex on #659). Its distance was worked out
            // once, at the tap, and is kept with the stop as its label, so the title is whole from the first
            // frame and never changes under From and To (Codex on #659).
            Dialog(
                onDismissRequest = { onStop(null) },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                // A dialog's window has none of the app's text size nor its pinch (SPEC *Display size*): both
                // applied again here (Codex on #659).
                FontSizePinchWindow {
                    val board = departures(stop, line)
                    LineStopPage(
                        name = stop.name,
                        distance = stop.distance,
                        lineName = line.name,
                        departures = board,
                        view = rememberStopBoard(board, line.id),
                        onFrom = { onFrom(stop) },
                        onTo = onTo?.let { to -> { to(stop) } },
                        onBack = { onStop(null) },
                    )
                }
            }
        }
    } else {
        val state by viewModel.state.collectAsStateWithLifecycle()
        saveable.SaveableStateProvider(LinesViewModel.SEARCH_STATE_KEY) {
            LineSearchScreen(
                state = state,
                onQueryChange = viewModel::setQuery,
                onOpenLine = onOpen,
                onRetry = viewModel::retry,
                onBack = onBack,
            )
        }
    }
}

/**
 * A stop tapped on a line's map (SPEC *Finding a line*): its full name, how far it is ([distance],
 * where known), and **From** and **To**, which do what From… and To… do with this stop picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LineStopPage(
    name: String,
    distance: String?,
    onFrom: () -> Unit,
    onTo: (() -> Unit)?,
    onBack: () -> Unit,
    // The line it was opened from, whose departures lead its board, then the rest ([stopBoard]).
    lineName: String = "",
    departures: StopDepartures? = null,
    view: StopBoardView? = null,
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
                title = {},
                actions = { AppMenuOverflow() },
            )
        },
    ) { padding ->
        // Scrolls, so a long name at a large text size never pushes From and To out of reach (Codex on #659).
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .scrollEdgeCue(listState, scrollCueColors(MaterialTheme.colorScheme.background))
                .testTag("lineStopPage"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "title") {
                Text(
                    if (distance != null) stringResource(R.string.line_stop_title_distance, name, distance) else name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag("lineStopTitle"),
                )
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onFrom) { Text(stringResource(R.string.line_stop_from)) }
                    if (onTo != null) Button(onClick = onTo) { Text(stringResource(R.string.line_stop_to)) }
                }
            }
            if (departures != null) stopBoard(departures, view, lineName)
        }
    }
}

/** TfL's lines couldn't be loaded and none are kept: said, with Retry. */
@Composable
private fun CatalogFailed(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.lines_search_failed), textAlign = TextAlign.Center)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.route_stops_retry)) }
    }
}

/**
 * The line search: a field in the app bar, the recently opened lines before anything is typed, TfL's
 * lines matching what's typed after. UI only, so it renders in a screenshot test with no network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LineSearchScreen(
    state: LinesViewModel.State,
    onQueryChange: (String) -> Unit,
    onOpenLine: (LineRef) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    // Off in the screenshot test, where a focused field would add a blinking cursor.
    autoFocus: Boolean = true,
) {
    BackHandler(onBack = onBack)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    // The field keeps its own value, cursor included, seeded from the query once (as the station search's).
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
                        onValueChange = { value: TextFieldValue ->
                            // Bounded, so what's done with the query on the main thread (comparing it, telling
                            // a blank one) stays a few characters' work, whatever is pasted (Codex on #652).
                            if (value.text.length <= LinesViewModel.MAX_QUERY) {
                                val edited = value.text != field.text
                                field = value
                                if (edited) onQueryChange(value.text)
                            }
                        },
                        placeholder = { Text(stringResource(R.string.lines_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("lineSearchField"),
                    )
                },
                actions = { AppMenuOverflow() },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val catalog = state.catalog
            // The lines loading for a typed query, or a newer query being searched: the bar says an answer is coming.
            if (state.searching || (catalog is LinesViewModel.Catalog.Loading && state.query.isNotBlank())) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Box(modifier = Modifier.height(4.dp))
            }
            when {
                state.query.isBlank() -> {
                    val recent = state.recent
                    // A list that couldn't be loaded says so before anything is typed, with Retry: no search
                    // can work without it (Codex on #652). The recent lines stay below, still openable.
                    if (catalog is LinesViewModel.Catalog.Failed) {
                        CatalogFailed(onRetry)
                        if (!recent.isNullOrEmpty()) LineList(recent, onOpenLine, heading = stringResource(R.string.station_search_recent))
                    } else when {
                        // Nothing until the recent lines are read, so the prompt doesn't flash up and give way.
                        recent == null -> Unit
                        recent.isEmpty() -> Message(stringResource(R.string.lines_search_prompt))
                        else -> LineList(recent, onOpenLine, heading = stringResource(R.string.station_search_recent))
                    }
                }
                catalog is LinesViewModel.Catalog.Failed -> CatalogFailed(onRetry)
                catalog is LinesViewModel.Catalog.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                state.matchesFor.isNotBlank() && state.matches.isEmpty() && !state.searching ->
                    Message(stringResource(R.string.lines_search_no_matches))
                else -> LineList(state.matches, onOpenLine, heading = null, listKey = state.matchesFor)
            }
        }
    }
}

@Composable
private fun LineList(lines: List<LineRef>, onOpenLine: (LineRef) -> Unit, heading: String?, listKey: String = "") {
    // A new query's matches start at the top, best match first.
    val listState = rememberSaveable(listKey, saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().scrollEdgeCue(listState, scrollCueColors(MaterialTheme.colorScheme.background))
            .testTag("lineSearchList"),
        state = listState,
    ) {
        if (heading != null) item(key = "heading") { SectionHeading(heading) }
        items(lines, key = { it.id }) { line ->
            LineRow(line, onClick = { onOpenLine(line) })
            HorizontalDivider()
        }
    }
}

// A line as listed: its pill, then its name over its mode ("Victoria" over "Tube", "299" over "Bus").
@Composable
private fun LineRow(line: LineRef, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinePill(line.name, line.id, line.mode)
        Column(modifier = Modifier.weight(1f)) {
            Text(line.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                modeName(line.mode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A picked line, saved with the screen as its three strings. */
/**
 * A stop tapped on a line's map, by its TfL id and the name the map shows, and how far it was from the
 * rider's last fix when tapped, as its label ("350 m"; null with no fix, or no published position).
 */
@Immutable
data class LineStopRef(val id: String, val name: String, val distance: String? = null)

internal val LineStopRefSaver: Saver<LineStopRef?, ArrayList<String>> = Saver(
    save = { stop -> stop?.let { arrayListOf(it.id, it.name, it.distance.orEmpty()) } },
    restore = { saved -> if (saved.size == 3) LineStopRef(saved[0], saved[1], saved[2].ifEmpty { null }) else null },
)

internal val LineRefSaver: Saver<LineRef?, ArrayList<String>> = Saver(
    save = { line -> line?.let { arrayListOf(it.id, it.name, it.mode) } },
    restore = { saved -> if (saved.size == 3) LineRef(saved[0], saved[1], saved[2]) else null },
)
