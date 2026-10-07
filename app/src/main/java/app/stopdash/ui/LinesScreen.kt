package app.stopdash.ui

import app.stopdash.domain.StopDistance
import app.stopdash.domain.StopLinks
import app.stopdash.domain.StopAccess
import app.stopdash.domain.stopAccessFor
import app.stopdash.domain.NearStation
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.minimumInteractiveComponentSize
import app.stopdash.domain.NearestStops
import app.stopdash.domain.Coordinates
import app.stopdash.domain.StationMatch
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
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.runtime.key
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
import androidx.annotation.WorkerThread
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.StopQualifier
import app.stopdash.domain.lineStopCue
import app.stopdash.domain.StopCue
import app.stopdash.domain.StepFreeLevel
import app.stopdash.domain.StepFreeAccess
import app.stopdash.domain.TflException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import app.stopdash.domain.UsageEvent
import app.stopdash.telemetry.ReportScreen
import app.stopdash.telemetry.UsageEvents
import app.stopdash.domain.LineRef
import app.stopdash.domain.RouteFocus

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
    // Favorite: the stop as a favorite place, at its position where the index has one; null offers none.
    onFavorite: ((LineStopRef, Coordinates?) -> Unit)? = null,
    // Show on map: the stop at its published position in the phone's maps app; null offers none.
    onShowOnMap: ((LineStopRef, Coordinates) -> Unit)? = null,
    // The stop's live departures, from a model the caller keeps alive and refreshing while the page is
    // up ([StopDepartures]); null draws none.
    // With it, the lines to declare served there (the line it was opened from, else the station's own), so
    // a suspended one's status is asked for even with nothing due there.
    // And the stop's other ids ([StopLinks.ownIds]), asked for too.
    departures: @Composable (LineStopRef, List<LineRef>, List<String>) -> StopDepartures? = { _, _, _ -> null },
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
        // How far [position] is from the rider's last fix, as a label; null with either unknown.
        fun distanceTo(position: Coordinates?): String? =
            if (position == null || here == null || system == null) {
                null
            } else {
                StopDistance.label(NearestStops.distanceMeters(here.latitude, here.longitude, position.latitude, position.longitude), system)
            }
        CompositionLocalProvider(
            LocalDismissLineAlert provides dismissal,
            // A station tapped on the map opens its details (SPEC *Finding a line*).
            LocalOpenLineMapStop provides { id, name, position ->
                val at = position?.let { Coordinates(it.first, it.second) }
                onStop(LineStopRef(id, name, distanceTo(at), position = at))
            },
        ) {
            OneLinePage(row, line.id, line.name, line.mode, onClose = { onOpen(null) })
        }
        // Each stop page's own saved state (its scroll), by [LineStopRef.pageKey], in the caller's [saveable]
        // so it outlives this overlay leaving composition under From, Settings or Licenses (Codex on #667):
        // Back to a station opened before returns it as it was left, not at its top.
        if (stop != null) {
            // Back to the station this one was opened from, else to the line; the page left forgets its scroll,
            // so opening it again starts at its top.
            val back = {
                saveable.removeState(stop.pageKey)
                onStop(stop.previous)
            }
            // Its lines and the stations beside it, from the bundled index, worked out off the main thread.
            LaunchedEffect(stop.id) { viewModel.stopLinks(stop.id) }
            // Under From and To: a bus stop's letter and the way its buses go, from its stop area's poles,
            // else a station's fare zone, each one cached request. A bus stop asks for no zone (it has
            // none), a station for no poles (it has no letters), and a pier or cable car station for
            // neither (Codex on #676).
            val routes = LocalRouteStops.current
            val worker = LocalWorker.current
            var pole by remember(stop.id) { mutableStateOf<StopQualifier?>(null) }
            var zone by remember(stop.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(stop.id, routes) {
                val repository = routes ?: return@LaunchedEffect
                // By the stop's own modes, not the line's: a station opened from another's details can be
                // of another mode (a tube station beside a pier) (Codex on #676).
                when (stop.cue ?: lineStopCue(line.mode)) {
                    StopCue.POLE -> pole = withContext(worker) { stopPole(repository, line.id, stop.id) }
                    StopCue.ZONE -> zone = withContext(worker) { stopZone(repository, stop.id) }
                    StopCue.NONE -> Unit
                }
            }
            val heldLinks by viewModel.links.collectAsStateWithLifecycle()
            val links = heldLinks?.takeIf { it.stopId == stop.id }?.links
            // A station's step-free access, under its zone: from TfL's bundled table, for the line it was
            // opened from where that line calls here, else the level all its lines meet; and, where only a
            // lift makes it step-free, TfL's lift outages, so it says so while a lift it needs is out.
            // Read, not worked out: a station opened from another's was classed as it was built (Codex on #678).
            val isStation = (stop.cue ?: lineStopCue(line.mode)) == StopCue.ZONE
            val stepFreeTable = LocalStepFree.current
            var byLift by remember(stop.id) { mutableStateOf(false) }
            val lifts = rememberLiftsOut(byLift)
            var access by remember(stop.id) { mutableStateOf<StopAccess?>(null) }
            // Across every id TfL lists the station under ([StopLinks.ownIds]: St Pancras's table entry is under
            // its high-speed id) and every line through it, so it waits for its links, from the bundled index
            // (Codex on #678). Keyed by identity ([Inputs]): composition never compares the sets (Codex on #678).
            LaunchedEffect(Inputs(stop.id, stepFreeTable, lifts.ids, isStation, links)) {
                // A lift newly out may take the access away: what's shown is taken down at once, the line
                // held blank, rather than overstated until the table is worked out again (Codex on #678).
                access = heldWhileReworked(access, onlyLiftsBack = lifts.cleared != null)
                val table = stepFreeTable ?: return@LaunchedEffect
                if (!isStation) return@LaunchedEffect
                val station = links ?: return@LaunchedEffect
                val lineId = line.id.takeIf { stop.onLine }
                val worked = stopAccessOn(worker, table, lifts.ids, stop.id, station, lineId, line.mode)
                byLift = worked.byLift
                access = worked
            }
            // A stop's details are a window of their own over the line's page (itself one, [TripLinesPage]),
            // which stays up beneath them with its place, its folds and its work, so Back returns to its map
            // just as it was, the station just opened in view (Codex on #659). Its distance was worked out
            // once, at the tap, and is kept with the stop as its label, so the title is whole from the first
            // frame and never changes under From and To (Codex on #659).
            Dialog(
                // Back steps to the station this one was opened from, if any, else to the line.
                onDismissRequest = back,
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                // A dialog's window has none of the app's text size nor its pinch (SPEC *Display size*): both
                // applied again here (Codex on #659).
                FontSizePinchWindow {
                    // Every board waits on the stop's links (the bundled index, quick), "Loading departures…"
                    // meanwhile: the links sit above it, so a board up first (a stop's kept model, opened again)
                    // would be pushed down as they came in. Every line through it is declared, so a suspended one
                    // with nothing due shows its status (Codex on #664); the line it was opened from only decides
                    // which part leads. A stop the index doesn't hold (a bus stop) declares that line alone.
                    val board = links?.let { departures(stop, if (it.lines.isEmpty() && stop.onLine) listOf(line) else it.lines, it.ownIds) }
                    // A fresh page per stop: a station opened from another's chips starts at its top, its title and
                    // From and To in view (Codex on #664); Back to one opened before finds it as it was left.
                    key(stop.pageKey) {
                        saveable.SaveableStateProvider(stop.pageKey) {
                            // A row's route page open over the stop, by its route's key, kept with the stop's page so
                            // Back to this stop, or a rotation, finds it still open.
                            var routeKey by rememberSaveable { mutableStateOf<String?>(null) }
                            var routeDestination by rememberSaveable { mutableStateOf<String?>(null) }
                            var routeBranch by rememberSaveable { mutableStateOf<String?>(null) }
                            // Its line's page ("View line") open over the route page, kept with the route it's for.
                            val routeLineOpen = rememberSaveable(routeKey) { mutableStateOf(false) }
                            // The stop's details, the route page a row opens over them, or its line's page over that.
                            ReportScreen(
                                when {
                                    routeKey == null -> UsageEvent.Screen.LINE_STOP
                                    routeLineOpen.value -> UsageEvent.Screen.LINE
                                    else -> UsageEvent.Screen.ROUTE
                                },
                            )
                            val view = rememberStopBoard(board, line.id.takeIf { stop.onLine })
                            // The row gone from the board (its last train left), or the board failed (a restore whose
                            // reload couldn't be made): the page closes, rather than reopening if a later refresh or a
                            // Try again brought the same route back. Kept only while the board is still worked out.
                            val boardFailed = board?.state is DeparturesUiState.Error
                            LaunchedEffect(routeKey, view, boardFailed) {
                                val key = routeKey ?: return@LaunchedEffect
                                if (boardFailed || (view != null && key !in view.rowsByKey)) routeKey = null
                            }
                            LineStopPage(
                                name = stop.name,
                                distance = stop.distance,
                                lineName = line.name.takeIf { stop.onLine },
                                departures = board,
                                // Its board waiting on its links: "Loading departures…" meanwhile, never a blank (Codex on #664).
                                boardPending = board == null,
                                view = view,
                                pole = pole,
                                zone = zone,
                                cueSlot = true,
                                access = access,
                                accessSlot = isStation,
                                onOpenRoute = { row, focus ->
                                    UsageEvents.log(UsageEvent.Tapped(UsageEvent.Tap.STOP_ROW))
                                    routeKey = row.detailKey()
                                    routeDestination = focus?.destination
                                    routeBranch = focus?.branch
                                },
                                links = links,
                                // A line's pill opens that line's page in place of this one; Back from it returns
                                // to the search, as from any line opened there.
                                onOpenLine = { picked ->
                                    stop.pageKeys().forEach(saveable::removeState)
                                    onStop(null)
                                    if (picked.id != line.id) onOpen(picked)
                                },
                                // A station beside this one opens its details in their place, Back returning here.
                                onOpenStation = { station ->
                                    // The station the trail lets go of forgets its scroll too (Codex on #667).
                                    stop.evictedByOpening()?.let(saveable::removeState)
                                    onStop(
                                        LineStopRef(
                                            station.id,
                                            station.name,
                                            distanceTo(station.position),
                                            onLine = line.id in station.lineIds,
                                            position = station.position,
                                            cue = station.cue,
                                        )
                                            .openedFrom(stop),
                                    )
                                },
                                // A station under several ids opens its interchange ([StopLinks.openId]): From and To wait
                                // for the links, so a tap during the lookup can't open one id alone (Codex on #664).
                                actionsReady = links != null,
                                onFrom = { onFrom(stop.copy(fromId = links?.openId)) },
                                onTo = onTo?.let { to -> { to(stop.copy(fromId = links?.openId)) } },
                                // Where it is: the index's, else where the map placed it (a bus stop), so no lookup
                                // is needed for a stop already placed (Codex on #670).
                                onFavorite = onFavorite?.let { favorite -> { favorite(stop, links?.position ?: stop.position) } },
                                // On the map at the same position: the index's, else the map's, so it waits on the
                                // links rather than open the map's point a moment before the index answers (Codex on
                                // #672). The pin stays put meanwhile, greyed, so the star beside it never moves.
                                onShowOnMap = onShowOnMap?.let { show ->
                                    { links?.let { it.position ?: stop.position }?.let { at -> show(stop, at) } }
                                },
                                mapReady = links?.let { it.position ?: stop.position } != null,
                                onBack = back,
                            )
                            routeKey?.let { key ->
                                StopRoutePage(view, key, routeDestination?.let { RouteFocus(it, routeBranch) }, routeLineOpen, onBack = { routeKey = null })
                            }
                        }
                    }
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
    // Null where that line doesn't call here (a station opened from another's details): the board then
    // shows every service, none leading.
    lineName: String? = "",
    departures: StopDepartures? = null,
    view: StopBoardView? = null,
    // A board to come, not yet started: drawn as loading until [departures] is in.
    boardPending: Boolean = false,
    // Whether From and To can be tapped yet: false while what they open is still being worked out.
    actionsReady: Boolean = true,
    // The lines through it and the stations beside it ([linksOf]); null while they're worked out.
    links: StopLinks? = null,
    onOpenLine: (LineRef) -> Unit = {},
    onOpenStation: (NearStation) -> Unit = {},
    // Save it as a favorite place; null offers none. Waits on [actionsReady], for its position.
    onFavorite: (() -> Unit)? = null,
    // Show it in the phone's maps app; null where none is offered. Greyed until [mapReady]: where it is
    // isn't known yet, or is known nowhere.
    onShowOnMap: (() -> Unit)? = null,
    mapReady: Boolean = true,
    // A row's route page, as on a station's page; null leaves the rows inert.
    onOpenRoute: ((DepartureRow, RouteFocus?) -> Unit)? = null,
    // A bus stop's letter and the way its buses go ("Stop H, towards Oxford Circus"), from its pole's
    // data; null for a station, or while it's looked up.
    pole: StopQualifier? = null,
    // A station's fare zone ("1", "2/3"); null for a bus stop, or while it's looked up.
    zone: String? = null,
    // The line under From and To is kept for [pole] or [zone] from the first frame, blank while it's
    // looked up (or if the lookup finds none), so what's under it never moves when it comes in.
    cueSlot: Boolean = false,
    // A station's step-free access ([stopAccess]); null while it's worked out, or for a bus stop.
    access: StopAccess? = null,
    // Its line, under the cue's, kept from the first frame as that one is, for a station.
    accessSlot: Boolean = false,
) {
    BackHandler(onBack = onBack)
    val zoneCue = zone?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.line_stop_zone, it) }
    val accessCue = when {
        access == null || access.level == null -> null
        access.liftOut -> stringResource(R.string.line_stop_step_free_lift_out)
        access.level == StepFreeLevel.LEVEL -> stringResource(R.string.route_stop_step_free_train)
        access.level == StepFreeLevel.NONE -> stringResource(R.string.line_stop_not_step_free)
        else -> stringResource(R.string.route_stop_step_free_platform)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {},
                actions = {
                    if (onFavorite != null) {
                        IconButton(onClick = onFavorite, enabled = actionsReady, modifier = Modifier.testTag("lineStopFavorite")) {
                            Icon(StarBorderIcon, contentDescription = stringResource(R.string.line_stop_favorite))
                        }
                    }
                    if (onShowOnMap != null) {
                        IconButton(onClick = onShowOnMap, enabled = mapReady, modifier = Modifier.testTag("lineStopMap")) {
                            Icon(Icons.Filled.Place, contentDescription = stringResource(R.string.action_show_on_map))
                        }
                    }
                    AppMenuOverflow()
                },
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
                    Button(onClick = onFrom, enabled = actionsReady) { Text(stringResource(R.string.line_stop_from)) }
                    if (onTo != null) Button(onClick = onTo, enabled = actionsReady) { Text(stringResource(R.string.line_stop_to)) }
                }
            }
            // Under From and To, as it comes in after them, so they never move; its line held from the first
            // frame, so nothing under it moves either (Codex on #675). Held with a no-break space, as an empty
            // Text is shorter than a line of text.
            val poleCue = groupHeaderSpoken(pole)?.replaceFirstChar { it.uppercase() }
            val cue = poleCue ?: zoneCue
            if (cueSlot || cue != null) {
                item(key = "cue") {
                    Box(Modifier.testTag("lineStopCueSlot")) {
                        Text(
                            cue ?: "\u00A0",
                            style = MaterialTheme.typography.titleMedium,
                            // One line, as held: a long "towards" on a narrow screen or a large font is
                            // cut short rather than growing into what's under it (Codex on #675).
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = when {
                                poleCue != null -> Modifier.testTag("lineStopPole")
                                zoneCue != null -> Modifier.testTag("lineStopZone")
                                else -> Modifier
                            },
                        )
                    }
                }
            }
            // Under the zone, held from the first frame for a station as the zone's line is, one line so a
            // long phrase can't grow into what's under it.
            if (accessSlot || accessCue != null) {
                item(key = "access") {
                    Box(Modifier.testTag("lineStopAccessSlot")) {
                        Text(
                            accessCue ?: "\u00A0",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (accessCue != null) Modifier.testTag("lineStopAccess") else Modifier,
                        )
                    }
                }
            }
            // The links come in after the first frame: under From and To, so those never move as they arrive
            // (Codex on #664). From the bundled index, they're in long before the board's arrivals.
            if (links != null && links.lines.isNotEmpty()) {
                item(key = "lines") { StopLines(links.lines, onOpenLine) }
            }
            if (links != null && links.sameHub.isNotEmpty()) {
                item(key = "sameHub") {
                    StationLinks(stringResource(R.string.line_stop_same_hub), links.sameHub, withDistance = false, onOpenStation, "lineStopSameHub", name)
                }
            }
            if (links != null && links.nearby.isNotEmpty()) {
                item(key = "nearby") {
                    StationLinks(stringResource(R.string.line_stop_nearby), links.nearby, withDistance = true, onOpenStation, "lineStopNearby", name)
                }
            }
            if (departures != null || boardPending) stopBoard(departures, view, lineName, onOpenRoute)
        }
    }
}

/** The lines through a stop, each a pill opening that line's page, wrapped onto as many rows as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopLines(lines: List<LineRef>, onOpenLine: (LineRef) -> Unit) {
    // Each pill sits in a 48 dp touch target, which spaces the rows already.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("lineStopLines"),
    ) {
        lines.forEach { line ->
            val label = stringResource(R.string.line_stop_open_line, line.name)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(onClickLabel = label) { onOpenLine(line) }
                    .testTag("lineStopLine:${line.id}"),
            ) {
                LinePill(line.name, line.id, line.mode)
            }
        }
    }
}

/**
 * The stations a stop's details link to under [title]: its interchange's others, or those a short walk
 * away with how far ([withDistance]), each a chip opening its details.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationLinks(
    title: String,
    stations: List<NearStation>,
    withDistance: Boolean,
    onOpen: (NearStation) -> Unit,
    tag: String,
    // The stop's own name: a station named the same takes its mode after it ("Balham (National Rail)").
    ownName: String,
) {
    val system = LocalDistanceSystem.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(tag)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stations.forEach { station ->
                val meters = station.meters
                val mode = station.modes.firstOrNull()
                val named = if (station.name == ownName && mode != null) {
                    stringResource(R.string.line_stop_title_distance, station.name, modeName(mode))
                } else {
                    station.name
                }
                val text = if (withDistance && meters != null && system != null) {
                    stringResource(R.string.line_stop_title_distance, named, StopDistance.label(meters, system))
                } else {
                    named
                }
                SuggestionChip(onClick = { onOpen(station) }, label = { Text(text) })
            }
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
data class LineStopRef(
    val id: String,
    val name: String,
    val distance: String? = null,
    // Whether the line the page was opened from calls here: a stop on its map does; a station opened from
    // another's details may not, and then its board leads with no line (SPEC *Finding a line*).
    val onLine: Boolean = true,
    // What From and To open, where not [id] ([StopLinks.openId]): set as they're tapped, never saved.
    val fromId: String? = null,
    // The station whose details opened this one, which Back returns to; null opened from the line's map.
    val previous: LineStopRef? = null,
    // How many stations were opened before this one, counting those [previous] no longer keeps: set once,
    // as it opens, so its [pageKey] holds as older stations drop off the trail (Codex on #667).
    val depth: Int = 0,
    // Where it is, as the map or the index placed it when it was opened; null where neither did.
    val position: Coordinates? = null,
    // What its details look up under From and To, where it was opened from another's details (classed by
    // its own modes as the index built it, [NearStation.cue]); null for a stop on the line's map, which is
    // classed by the line's mode.
    val cue: StopCue? = null,
) {
    /**
     * This stop opened from [from]'s details, so Back returns there. The trail keeps the last
     * [MAX_TRAIL] stops before this one: a rider hopping station to station a long way still gets back
     * through the recent ones, and the saved state stays small.
     */
    fun openedFrom(from: LineStopRef): LineStopRef = copy(previous = from.trimmed(MAX_TRAIL - 1), depth = from.depth + 1)

    /** The key its page's saved state is kept under: its place in the trail, which trimming never moves. */
    val pageKey: String
        get() = "lineStop:$depth:$id"

    /**
     * The page key of the station the trail lets go of when a station is opened from this one: the
     * one [MAX_TRAIL] back, which [openedFrom] trims off; null while the trail is shorter.
     */
    fun evictedByOpening(): String? {
        var at: LineStopRef = this
        repeat(MAX_TRAIL - 1) { at = at.previous ?: return null }
        return at.previous?.pageKey
    }

    /** This page's key and those of the stations kept before it. */
    fun pageKeys(): List<String> = generateSequence(this) { it.previous }.map { it.pageKey }.toList()

    private fun trimmed(keep: Int): LineStopRef =
        copy(fromId = null, previous = if (keep <= 0) null else previous?.trimmed(keep - 1))

    companion object {
        /** How many stations before this one Back steps through. */
        const val MAX_TRAIL = 10
    }
}

// Saved as "v4", then eight strings a stop, this one first, then the station it was opened from, and so on
// back: its id, name, distance, whether it's on the line, its depth, its position (blank where none), and
// its cue (blank for the line's). Saves from before (v3's modes, read as none; v2's seven a stop; #667's
// five; and a single stop's three or four) still restore.
internal val LineStopRefSaver: Saver<LineStopRef?, ArrayList<String>> = Saver(
    save = { stop ->
        stop?.let {
            val out = arrayListOf(LINE_STOP_SAVE_V4)
            var at: LineStopRef? = it
            while (at != null) {
                out += listOf(
                    at.id, at.name, at.distance.orEmpty(), if (at.onLine) "1" else "0", at.depth.toString(),
                    at.position?.latitude?.toString().orEmpty(), at.position?.longitude?.toString().orEmpty(),
                    at.cue?.name.orEmpty(),
                )
                at = at.previous
            }
            out
        }
    },
    restore = { saved ->
        val version = saved.firstOrNull()
        when {
            version == LINE_STOP_SAVE_V4 || version == LINE_STOP_SAVE_V3 || version == LINE_STOP_SAVE_V2 -> {
                val size = if (version == LINE_STOP_SAVE_V2) 7 else 8
                val fields = saved.drop(1)
                if (fields.isEmpty() || fields.size % size != 0) {
                    null
                } else {
                    fields.chunked(size).foldRight(null as LineStopRef?) { stop, before ->
                        val lat = stop[5].toDoubleOrNull()
                        val lon = stop[6].toDoubleOrNull()
                        LineStopRef(
                            stop[0], stop[1], stop[2].ifEmpty { null }, onLine = stop[3] != "0", previous = before,
                            depth = stop[4].toIntOrNull() ?: 0,
                            position = if (lat != null && lon != null) Coordinates(lat, lon) else null,
                            cue = if (version == LINE_STOP_SAVE_V4) StopCue.entries.firstOrNull { it.name == stop[7] } else savedModesCue(stop.getOrNull(7).orEmpty()),
                        )
                    }
                }
            }
            // Saves from before onLine, and from before the trail.
            saved.size == 3 -> LineStopRef(saved[0], saved[1], saved[2].ifEmpty { null })
            saved.size == 4 -> LineStopRef(saved[0], saved[1], saved[2].ifEmpty { null }, onLine = saved[3] != "0")
            saved.isEmpty() || saved.size % 5 != 0 -> null
            else -> saved.chunked(5).foldRight(null as LineStopRef?) { (id, name, distance, onLine, depth), before ->
                LineStopRef(id, name, distance.ifEmpty { null }, onLine = onLine != "0", previous = before, depth = depth.toIntOrNull() ?: 0)
            }
        }
    },
)

private const val LINE_STOP_SAVE_V2 = "v2"
private const val LINE_STOP_SAVE_V3 = "v3"
private const val LINE_STOP_SAVE_V4 = "v4"

/**
 * The cue for a v3 save's modes (comma-joined; blank for a stop on the line's map, read as none), so a
 * restore after the update keeps classing a station opened from another's as it was (Codex on #678).
 * A save holds at most [LineStopRef.MAX_TRAIL] stations past the first, each of a mode or three: bounded,
 * so read on the restore.
 */
private fun savedModesCue(saved: String): StopCue? {
    val cues = saved.split(',').filter { it.isNotEmpty() }.map(::lineStopCue)
    return when {
        cues.isEmpty() -> null
        StopCue.ZONE in cues -> StopCue.ZONE
        StopCue.POLE in cues -> StopCue.POLE
        else -> StopCue.NONE
    }
}

/** A stop to add as a favorite place, saved as its id, name and position (blank where it has none). */
internal val StationMatchSaver: Saver<StationMatch?, ArrayList<String>> = Saver(
    save = { match -> match?.let { arrayListOf(it.id, it.name, it.latitude?.toString().orEmpty(), it.longitude?.toString().orEmpty()) } },
    restore = { saved ->
        if (saved.size == 4) StationMatch(saved[0], saved[1], latitude = saved[2].toDoubleOrNull(), longitude = saved[3].toDoubleOrNull()) else null
    },
)

internal val LineRefSaver: Saver<LineRef?, ArrayList<String>> = Saver(
    save = { line -> line?.let { arrayListOf(it.id, it.name, it.mode) } },
    restore = { saved -> if (saved.size == 3) LineRef(saved[0], saved[1], saved[2]) else null },
)

/**
 * A bus stop's letter and the way its buses go, from its pole's data: its stop area, as [lineId]'s route
 * data places it, and that area's poles, both from the route pages' day-long cache (one request per
 * area). The route is loaded if it isn't held yet (a restored page, before the line's map has asked for
 * it): joined with the map's own request, never a second one. Its letter and "towards", else "towards"
 * alone, else its compass bearing, as the near-me list heads its poles. Null for a stop no route places
 * in an area, a pole with none of them, or where a lookup failed (logged by the repository), as the stop's
 * details are whole without it.
 */
@WorkerThread
internal suspend fun stopPole(repository: RouteStopsRepository, lineId: String, stopId: String): StopQualifier? {
    val pole = try {
        val route = repository.cached(lineId, "") ?: repository.load(lineId, "")
        val area = route.stopAreas[stopId] ?: return null
        repository.loadPoles(area).firstOrNull { it.id == stopId } ?: return null
    } catch (e: CancellationException) {
        throw e
    } catch (e: TflException) {
        return null
    }
    return when {
        pole.stopLetter.isNotBlank() -> StopQualifier.BusStop(pole.stopLetter, pole.towards.ifBlank { null })
        pole.towards.isNotBlank() -> StopQualifier.Towards(pole.towards)
        pole.bearing.isNotBlank() -> StopQualifier.BusBearing(pole.bearing)
        else -> null
    }
}

/**
 * A station's fare zone ("1", "2/3"), from its own TfL record, through the route pages' repository (one
 * request a station a day, kept in memory). Null where the lookup failed (logged by the repository), as
 * the stop's details are whole without it; blank where TfL gives none.
 */
@WorkerThread
internal suspend fun stopZone(repository: RouteStopsRepository, stopId: String): String? =
    try {
        repository.loadZone(stopId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TflException) {
        null
    }



/**
 * What a station's details keep showing while its access is worked out again: the last answer where
 * the change can only have added access back ([onlyLiftsBack], lifts returning: the old one errs
 * toward "not step-free"), or where no lift could change it; else none, so a lift newly out never
 * leaves "step-free" up meanwhile (Codex on #678).
 */
internal fun heldWhileReworked(held: StopAccess?, onlyLiftsBack: Boolean): StopAccess? =
    if (held == null || onlyLiftsBack || !held.byLift) held else null

/** [stopAccess] for [stopId] and its [links] (the other ids TfL lists it under, the lines through it), on [worker]. */
internal suspend fun stopAccessOn(
    worker: CoroutineDispatcher,
    table: StepFreeAccess,
    out: Set<String>,
    stopId: String,
    links: StopLinks,
    lineId: String?,
    mode: String,
): StopAccess = withContext(worker) { stopAccessFor(table, out, stopId, links, lineId, mode) }

