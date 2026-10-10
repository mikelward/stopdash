package app.stopdash.ui

import android.content.res.Resources
import app.stopdash.domain.StopDistance
import app.stopdash.domain.StopLinks
import app.stopdash.domain.StopAccess
import app.stopdash.domain.stopAccessFor
import app.stopdash.domain.NearStation
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.minimumInteractiveComponentSize
import app.stopdash.domain.NearestStops
import app.stopdash.domain.Coordinates
import app.stopdash.domain.StationMatch
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.MutableState
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
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.platform.LocalResources
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
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.StopQualifier
import app.stopdash.domain.lineStopCue
import app.stopdash.domain.StationFacility
import app.stopdash.domain.StationFacts
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

/** A line page's work in Lines…: its row ([row]) and its map ([map]), kept while the page is away. */
@Stable
internal class LinePageWork(internal val map: LineMapWork = LineMapWork()) {
    internal val row: MutableState<Worked<Inputs, TripRow>?> = mutableStateOf(null)
}

/**
 * The [LinePageWork] for the line open in Lines…, kept by the caller so the page outlives the overlay
 * leaving composition, and replaced only for another line, whose page starts afresh.
 */
internal class LinePageWorkHolder private constructor(private var lineId: String?, private var work: LinePageWork) {
    constructor() : this(null, LinePageWork())

    /** Forgets the page's work, so the line opened again starts afresh, at its top. */
    fun clear() {
        lineId = null
        work = LinePageWork()
    }

    fun workFor(lineId: String): LinePageWork {
        if (lineId != this.lineId) {
            work = LinePageWork()
            this.lineId = lineId
        }
        return work
    }

    companion object {
        /**
         * Saves what the rider sees of the line's page, its folds and scroll, so a rotation or the process
         * coming back keeps them (Codex on #679); the map itself is worked out again.
         */
        val Saver: Saver<LinePageWorkHolder, Any> = listSaver(
            save = { holder ->
                val map = holder.work.map
                listOf(
                    holder.lineId.orEmpty(),
                    with(OpenedFoldsSaver) { save(map.opened.value) } ?: arrayListOf<String>(),
                    map.all.value,
                    // A restored position not yet applied is saved as it was, not the short list's top.
                    map.pendingScroll?.first ?: map.list.firstVisibleItemIndex,
                    map.pendingScroll?.second ?: map.list.firstVisibleItemScrollOffset,
                )
            },
            restore = { saved ->
                val lineId = (saved.getOrNull(0) as? String)?.takeIf { it.isNotEmpty() }
                @Suppress("UNCHECKED_CAST")
                val folds = (saved.getOrNull(1) as? ArrayList<String>)?.takeIf { it.isNotEmpty() }?.let(OpenedFoldsSaver::restore)
                val index = saved.getOrNull(3) as? Int ?: 0
                val offset = saved.getOrNull(4) as? Int ?: 0
                // The list starts at its top and moves to where it was once the map is laid out again.
                val map = LineMapWork(
                    opened = folds,
                    all = saved.getOrNull(2) as? Boolean ?: false,
                    pendingScroll = (index to offset).takeIf { index > 0 || offset > 0 },
                )
                LinePageWorkHolder(lineId, LinePageWork(map))
            },
        )
    }
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
    // Back from the first stop opened: in place of returning to the line's page, where Lines… was opened on the
    // stop from another page's map; null returns to the line.
    onStopClosed: (() -> Unit)? = null,
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
    // Departures: the departures at the stop and around it, on a page of their own that starts nothing.
    onDepartures: (LineStopRef) -> Unit = {},
    // The line page's worked-out row and map, held by the caller above any overlay that takes this one's
    // place (From's station page), so the page comes back as it was left (Codex on #659).
    lineWork: LinePageWorkHolder = remember { LinePageWorkHolder() },
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
        val work = lineWork.workFor(line.id)
        val slot = work.row
        val status = held?.status
        val unknown = held?.unknown == true
        // No answer up and none failed (asking, or the last one taken down at its age): "Checking…", never
        // a clean line claimed for want of one (SPEC D4); the next tick asks.
        val checking = held == null || held.checking || (held.status == null && !held.unknown)
        val dismissed by viewModel.lineDismissed.collectAsStateWithLifecycle()
        val dismissals by viewModel.dismissed.collectByIdentityWithLifecycle()
        val aheadUnknown = held?.aheadUnknown == true
        val row = rememberWorked(slot, Inputs(line.id, line.name, line.mode, dismissals, aheadUnknown, status, dismissed, unknown, checking), keep = ::sameVerdict) {
            // No stop of the rider's: the map draws the whole line, none of it marked as theirs.
            lineRow(
                line.mode, line.id, line.name, "", "", status, dismissed = dismissed, ride = null, unknown = unknown, checking = checking,
                planned = plannedShown(line.id, status, dismissals), aheadUnknown = aheadUnknown,
            )
        }
        // Its alert dismissible here as on any line's page (SPEC *Disruptions*), into the shared store.
        val dismissFailed by viewModel.dismissWriteFailed.collectAsStateWithLifecycle()
        val pagesOpen = remember { mutableIntStateOf(0) }
        val dismissal = LineAlertDismissal(viewModel::dismiss, dismissFailed, viewModel::dismissWriteFailureShown, pagesOpen, viewModel::dismissPlanned)
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
            // Its map, folds and scroll in [work], held by the caller: back from From, as it was left.
            CompositionLocalProvider(LocalLineMapWork provides work.map) {
                OneLinePage(
                    row,
                    line.id,
                    line.name,
                    line.mode,
                    onClose = {
                        // Closed, the line opened again starts afresh, at its top.
                        lineWork.clear()
                        onOpen(null)
                    },
                )
            }
        }
        // Each stop page's own saved state (its scroll), by [LineStopRef.pageKey], in the caller's [saveable]
        // so it outlives this overlay leaving composition under From, Settings or Licenses (Codex on #667):
        // Back to a station opened before returns it as it was left, not at its top.
        if (stop != null) {
            LineStopWindow(
                stop = stop,
                line = line,
                saveable = saveable,
                links = viewModel.links.collectAsStateWithLifecycle().value?.takeIf { it.stopId == stop.id }?.links,
                loadLinks = viewModel::stopLinks,
                onStop = onStop,
                onStopClosed = onStopClosed,
                distanceTo = ::distanceTo,
                // A line's pill opens that line's page in place of this one; Back from it returns to the search,
                // as from any line opened there.
                onPickLine = { picked ->
                    // The trail Lines… lets go of forgets its pages, as before (at most [LineStopRef.MAX_TRAIL]).
                    stop.pageKeys().forEach(saveable::removeState)
                    if (picked.id != line.id) onOpen(picked)
                },
                onFrom = onFrom,
                onTo = onTo,
                onFavorite = onFavorite,
                onShowOnMap = onShowOnMap,
                onDepartures = onDepartures,
            )
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
 * A stop's details over the page it was opened from (SPEC *Finding a line*), a window of their own: Lines…'s
 * line map, or a route page's stop list ([RouteStopWindow]). Back steps through the stations opened from
 * one another ([LineStopRef.previous]), then to [onStopClosed], else to no stop ([onStop] null). Its lines and
 * the stations beside it ([links]) are the caller's, asked for by [loadLinks]; null while they're worked out.
 */
@Composable
internal fun LineStopWindow(
    stop: LineStopRef,
    line: LineRef,
    saveable: SaveableStateHolder,
    links: StopLinks?,
    loadLinks: (String) -> Unit,
    onStop: (LineStopRef?) -> Unit,
    onStopClosed: (() -> Unit)?,
    distanceTo: (Coordinates?) -> String?,
    onPickLine: (LineRef) -> Unit,
    onFrom: (LineStopRef) -> Unit,
    onTo: ((LineStopRef) -> Unit)?,
    onFavorite: ((LineStopRef, Coordinates?) -> Unit)?,
    onShowOnMap: ((LineStopRef, Coordinates) -> Unit)?,
    // The departures at the stop and around it, on a page of their own ([LineStopRef.fromId] its interchange).
    onDepartures: (LineStopRef) -> Unit,
    // Opened from a route page's stop list: that stop's page in its route mode ([RouteStopMode]); a station
    // opened from it, beside it, is a stop page like any other.
    route: RouteStopMode? = null,
    // A line's pills open its page; false where none can be opened from here (they're shown, not tapped). [onPickLine]
    // forgets the stops' saved pages as it sees fit.
    linesOpenable: Boolean = true,
) {
    // Back to the station this one was opened from, else to the line, or to the page whose map it was
    // tapped on ([onStopClosed]); the page left forgets its scroll, so opening it again starts at its top.
    val back = {
        saveable.removeState(stop.pageKey)
        val closed = onStopClosed
        if (stop.previous == null && closed != null) closed() else onStop(stop.previous)
    }
    // Its lines and the stations beside it, from the bundled index, worked out off the main thread.
    LaunchedEffect(stop.id) { loadLinks(stop.id) }
    // Under From and To: a bus stop's letter and the way its buses go, from its stop area's poles,
    // else a station's fare zone and facilities, each one cached request. A bus stop asks for no zone (it has
    // none), a station for no poles (it has no letters), and a pier or cable car station for
    // neither (Codex on #676).
    val routes = LocalRouteStops.current
    val worker = LocalWorker.current
    var pole by remember(stop.id) { mutableStateOf<StopQualifier?>(null) }
    var facts by remember(stop.id) { mutableStateOf<StationDetails?>(null) }
    // The app's strings as the configuration now has them: a language change words the facilities
    // again (the station's record is cached, so that asks TfL nothing).
    val resources = LocalResources.current
    LaunchedEffect(stop.id, routes, resources) {
        val repository = routes ?: return@LaunchedEffect
        // By the stop's own modes, not the line's: a station opened from another's details can be
        // of another mode (a tube station beside a pier) (Codex on #676).
        when (stop.cue ?: lineStopCue(line.mode)) {
            StopCue.POLE -> pole = withContext(worker) { stopPole(repository, line.id, stop.id) }
            StopCue.ZONE -> facts = withContext(worker) { stationDetails(repository, resources, stop.id) }
            StopCue.NONE -> Unit
        }
    }
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
            // A fresh page per stop, its scroll kept with it in [saveable].
            key(stop.pageKey) {
                saveable.SaveableStateProvider(stop.pageKey) {
                    ReportScreen(UsageEvent.Screen.LINE_STOP)
                    LineStopPage(
                        name = stop.name,
                        distance = stop.distance,
                        pole = pole,
                        zone = facts?.zone,
                        facilities = facts?.facilities,
                        cueSlot = true,
                        access = access,
                        accessSlot = isStation,
                        links = links,
                        // A line's pill closes the stop for that line's page ([onPickLine]).
                        onOpenLine = if (linesOpenable) {
                            // The pages' saved state is the caller's to forget ([onPickLine]): Lines… drops each, the
                            // route page's details their opening's one entry.
                            { picked ->
                                onStop(null)
                                onPickLine(picked)
                            }
                        } else {
                            null
                        },
                        // A station under several ids opens its interchange ([StopLinks.openId]): From, To and
                        // Departures wait for the links, so a tap during the lookup can't open one id alone (Codex on #664).
                        actionsReady = links != null,
                        onFrom = { onFrom(stop.copy(fromId = links?.openId)) },
                        onTo = onTo?.let { to -> { to(stop.copy(fromId = links?.openId)) } },
                        onDepartures = { onDepartures(stop.copy(fromId = links?.openId)) },
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
                        route = route?.takeIf { stop.previous == null },
                        onBack = back,
                    )
                }
            }
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
    // Whether From, To and Departures can be tapped yet: false while what they open is still being worked out.
    actionsReady: Boolean = true,
    // The lines through it and around it ([StopLinks.nearbyLines]); null while they're worked out.
    links: StopLinks? = null,
    // A line's pill opens that line's page; null shows them without.
    onOpenLine: ((LineRef) -> Unit)? = {},
    // The departures at the stop and around it, on a page of their own that starts nothing; null offers none.
    onDepartures: (() -> Unit)? = null,
    // Save it as a favorite place; null offers none. Waits on [actionsReady], for its position.
    onFavorite: (() -> Unit)? = null,
    // Show it in the phone's maps app; null where none is offered. Greyed until [mapReady]: where it is
    // isn't known yet, or is known nowhere.
    onShowOnMap: (() -> Unit)? = null,
    mapReady: Boolean = true,
    // A bus stop's letter and the way its buses go ("Stop H, towards Oxford Circus"), from its pole's
    // data; null for a station, or while it's looked up.
    pole: StopQualifier? = null,
    // A station's fare zone ("1", "2/3"); null for a bus stop, or while it's looked up.
    zone: String? = null,
    // A station's facilities as one line ([facilitiesLine], made on the worker), at the page's foot; null
    // for a bus stop, while they're looked up, or where TfL names none.
    facilities: String? = null,
    // The line under From and To is kept for [pole] or [zone] from the first frame, blank while it's
    // looked up (or if the lookup finds none), so what's under it never moves when it comes in.
    cueSlot: Boolean = false,
    // A station's step-free access ([stopAccess]); null while it's worked out, or for a bus stop.
    access: StopAccess? = null,
    // Its line, under the lines here, kept for a station once they are worked out.
    accessSlot: Boolean = false,
    // Opened from a route page's stop list: headed by the departure tapped, with Go in place of From and To,
    // its star the journey there ([RouteStopMode]); null for a stop opened from a line's map.
    route: RouteStopMode? = null,
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
                    val journey = route?.journey
                    if (journey != null) {
                        // Waits on the favorites being read, so a tap never goes the wrong way.
                        val saved = journey.saved == true
                        IconButton(
                            onClick = journey.onToggle,
                            enabled = journey.saved != null && !journey.unavailable,
                            modifier = Modifier.testTag("routeStopJourney"),
                        ) {
                            Icon(
                                if (saved) Icons.Filled.Star else StarBorderIcon,
                                contentDescription = stringResource(if (saved) R.string.action_unstar_journey else R.string.station_journey_add),
                            )
                        }
                    } else if (route == null && onFavorite != null) {
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
            if (route != null) {
                item(key = "departure") { RouteStopDeparture(route) }
                // Go only where the ride can be followed: decided by the line's mode, so it's there from the first frame.
                if (route.onGo != null || onDepartures != null) {
                    item(key = "actions") {
                        StopActions {
                            route.onGo?.let { go ->
                                Button(onClick = go, enabled = route.goReady, modifier = Modifier.testTag("routeStopGo")) {
                                    Text(stringResource(R.string.route_stop_go))
                                }
                            }
                            if (onDepartures != null) DeparturesButton(onDepartures, actionsReady)
                        }
                    }
                }
            } else {
                item(key = "actions") {
                    StopActions {
                        Button(onClick = onFrom, enabled = actionsReady) { Text(stringResource(R.string.line_stop_from)) }
                        if (onTo != null) Button(onClick = onTo, enabled = actionsReady) { Text(stringResource(R.string.line_stop_to)) }
                        if (onDepartures != null) DeparturesButton(onDepartures, actionsReady)
                    }
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
            // The lines here and around it, under From and To and the cue, so those never move as they arrive
            // (Codex on #664). From the bundled index, they're in at once.
            if (links != null && links.nearbyLines.isNotEmpty()) {
                item(key = "lines") { StopLines(links.nearbyLines, onOpenLine) }
            }
            // How step-free it is and its facilities, under the lines (maintainer, 2026-10-10). They wait on the
            // lines being worked out (never long: the bundled index, or none), so the pills coming in never push
            // them down mid-read. The step-free line is held from then for a station, one line so a long phrase
            // can't grow into what's under it.
            if (links != null && (accessSlot || accessCue != null)) {
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
            // Last, so coming in never moves anything above it (SPEC *Finding a line → A station's facilities*).
            if (links != null && facilities != null) {
                item(key = "facilities") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("lineStopFacilities")) {
                        Text(stringResource(R.string.line_stop_facilities), style = MaterialTheme.typography.titleMedium)
                        Text(facilities, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * A stop's details opened from a route page's stop list (SPEC *Route detail*): the departure
 * tapped, by [line] from [fromName] towards [towards]; **Go** ([onGo]), the ride there on the way, null
 * where it can't be followed, and waiting on [goReady]; and the [journey] there for the star, null where
 * the route page has none to offer (its boarding stop).
 */
@Immutable
class RouteStopMode(
    val line: LineRef,
    val fromName: String,
    val towards: String,
    val onGo: (() -> Unit)?,
    val goReady: Boolean,
    val journey: StationJourneyState?,
)

/** The departure a route stop's details were opened from: its line's pill, where it was boarded and where it goes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteStopDeparture(route: RouteStopMode) {
    val journey = route.journey
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("routeStopDeparture")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            LinePill(route.line.name, route.line.id, route.line.mode)
            Text(
                if (route.towards.isBlank()) {
                    stringResource(R.string.route_stop_departure_from, route.fromName)
                } else {
                    stringResource(R.string.route_stop_departure, route.fromName, route.towards)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        // The star's change didn't save, or the favorites couldn't be read (with Retry): said here, under what it's for.
        if (journey != null && (journey.failed || journey.unavailable)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (journey.unavailable) R.string.favorite_journeys_unavailable else R.string.station_journey_write_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (journey.unavailable) {
                    TextButton(onClick = journey.onRetry, modifier = Modifier.testTag("routeStopJourneyRetry")) {
                        Text(stringResource(R.string.favorite_journeys_retry))
                    }
                }
            }
        }
    }
}

/** The lines through a stop, each a pill opening that line's page, wrapped onto as many rows as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopLines(lines: List<LineRef>, onOpenLine: ((LineRef) -> Unit)?) {
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
                    .then(if (onOpenLine != null) Modifier.clickable(onClickLabel = label) { onOpenLine(line) } else Modifier)
                    .testTag("lineStopLine:${line.id}"),
            ) {
                LinePill(line.name, line.id, line.mode)
            }
        }
    }
}

/**
 * The page's buttons, wrapped onto as many rows as they need: three side by side don't fit a narrow window
 * at a large text size, and none may be cut off (Codex on #736).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopActions(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("lineStopActions"),
    ) { content() }
}

/** Departures, beside the page's other actions: the departures at the stop and around it. */
@Composable
private fun DeparturesButton(onClick: () -> Unit, enabled: Boolean) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.testTag("lineStopDepartures")) {
        Text(stringResource(R.string.line_stop_departures))
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

/** A station's fare [zone] and its [facilities] as the details show them ([facilitiesLine]). */
internal data class StationDetails(val zone: String, val facilities: String?)

/**
 * A station's fare zone and facilities line ([StationDetails]), from its own TfL record, through the
 * route pages' repository (one request a station a day, kept in memory), the line put into words here on
 * the worker, so composition only reads it (AGENTS.md *Main thread*). Null where the lookup failed
 * (logged by the repository), as the stop's details are whole without them.
 */
@WorkerThread
internal suspend fun stationDetails(repository: RouteStopsRepository, resources: Resources, stopId: String): StationDetails? =
    try {
        repository.loadStationFacts(stopId).let { StationDetails(it.zone, facilitiesLine(resources, it)) }
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

/**
 * A station's facilities as one line, in [StationFacts]' order: "Toilets · Waiting room · Taxi rank", the
 * accessible toilet with TfL's note on where ("Accessible toilet (National Rail)"). Null for none. Built
 * from a list: on the worker only.
 */
@WorkerThread
internal fun facilitiesLine(resources: Resources, facts: StationFacts): String? {
    val names = facts.facilities.map { facility ->
        when (facility) {
            StationFacility.TOILETS -> resources.getString(R.string.facility_toilets)
            StationFacility.ACCESSIBLE_TOILET -> resources.getString(R.string.facility_accessible_toilet).let { name ->
                if (facts.toiletNote.isBlank()) name else resources.getString(R.string.facility_with_note, name, facts.toiletNote)
            }
            StationFacility.WAITING_ROOM -> resources.getString(R.string.facility_waiting_room)
            StationFacility.LEFT_LUGGAGE -> resources.getString(R.string.facility_left_luggage)
            StationFacility.CAR_PARK -> resources.getString(R.string.facility_car_park)
            StationFacility.CASH_MACHINE -> resources.getString(R.string.facility_cash_machine)
            StationFacility.TAXI_RANK -> resources.getString(R.string.facility_taxi_rank)
        }
    }
    return names.takeIf { it.isNotEmpty() }?.joinToString(resources.getString(R.string.facility_separator))
}
