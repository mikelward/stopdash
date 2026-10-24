package app.stopdash.ui

import android.content.res.Resources
import androidx.annotation.WorkerThread
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import app.stopdash.domain.Compass
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.stopdash.R
import app.stopdash.StopdashDebugLog
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Coordinates
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.DoubledTrains
import app.stopdash.domain.LegEnd
import app.stopdash.domain.LineStatus
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RideStopList
import app.stopdash.domain.StepFree
import app.stopdash.domain.lineStopNames
import app.stopdash.domain.rideStopList
import app.stopdash.domain.withRouteStops
import app.stopdash.domain.TflException
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.ReplanOrigin
import app.stopdash.domain.RouteDisruption
import app.stopdash.RouteDisruptionAlert
import app.stopdash.domain.DepartureLabels
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.StopGroup
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.domain.RouteFocus
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.routeAnchor
import app.stopdash.domain.OffPlan
import app.stopdash.domain.Staleness
import app.stopdash.domain.cleanStopName
import app.stopdash.domain.LineRef
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.UsageEvent
import app.stopdash.telemetry.ReportScreen
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.toJavaDuration

/**
 * A trip on the way (SPEC *On the way*): the next step over the route, leg by leg, the leg the rider
 * is on marked, with **End trip**. Renders from the tracker's [trip] and [progress] alone; the caller
 * refreshes them about every 30 s while it's shown. [failed] says the last refresh couldn't reach TfL,
 * so the step isn't passed off as current. Its live details (due, stops left, get off soon) show
 * only while [current]: back after a while away, they wait for the next answer. Arrived ([trip]
 * null), it says so, and Done closes it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun OnTheWayScreen(
    trip: ActiveTrip?,
    progress: TripProgress?,
    failed: Boolean,
    now: Instant,
    onEnd: () -> Unit,
    onBack: () -> Unit,
    current: Boolean = true,
    // When the step's last answer was had ([ActiveTripTracker.answeredAt]): while it's no longer
    // [current], its time stays beside "Checking…" (principle 2); null holds it back.
    asOf: Instant? = null,
    // The trip couldn't be saved on the device, so a restart may lose it or bring it back out of date: said, not hidden.
    notKept: Boolean = false,
    // End trip couldn't forget the trip on the device, so it's still on the way.
    endFailed: Boolean = false,
    // Notifications are off for the app, so "get off soon" can't alert: said here, not left to be
    // found out at the stop (SPEC principle 2).
    alertsOff: Boolean = false,
    // Android refused to follow the trip with the app closed ([app.stopdash.OnTheWayService.refused]):
    // it's followed only while the app is open, and reopening it tries again.
    appOpenOnly: Boolean = false,
    // Every train at the next boarding stop that takes the rider on ([rememberNextTrains]): shown
    // while they walk, change or wait for it; null while riding.
    nextTrains: NextTrains? = null,
    // The rider says they're at a step ([OnTheWay.Step], [ActiveTripTracker.goTo]): Next, or a step
    // tapped. Null leaves both out.
    onGoTo: ((from: OnTheWay.Step, to: OnTheWay.Step) -> Unit)? = null,
    // What's wrong on the route ahead ([ActiveTripTracker.routeDisruptions]), worst first: what the
    // route disruption alert says, here in full, so tapping it finds where and how (maintainer, 2026-10-01).
    disruptions: List<RouteDisruption.Signal> = emptyList(),
    // [disruptions] as cards, each thing known once ([RouteDisruption.cards]), worked out off the main
    // thread with them ([ActiveTripTracker.KnownDisruptions.cards]): drawn here, never worked out.
    cards: List<RouteDisruption.Signal> = disruptions,
    // While something is known wrong ahead, the station still ahead nearest the rider
    // ([ActiveTripTracker.replanFrom]), and the trip list from there to where they chose to go
    // ([onPlanAgain], maintainer 2026-10-02). Null leaves it out.
    replanFrom: ReplanOrigin.Stop? = null,
    onPlanAgain: ((ReplanOrigin.Stop) -> Unit)? = null,
    // The rider went past their change because the stop or the line on from it is closed (maintainer,
    // 2026-10-08): that one is avoided ([avoid], [AvoidedLines]), as a route card's Avoid does (sticky until
    // cleared), and the trip planned again from [replanFrom] as [onPlanAgain] does. Null leaves the buttons out.
    onClosed: ((avoid: String, from: ReplanOrigin.Stop) -> Unit)? = null,
    // Whether what's already avoided has been read: until then the buttons wait, so avoiding one more never
    // stands in for the whole stored set (Codex, #695).
    closedEnabled: Boolean = true,
    // The rider read [disruptions] and keeps going ([ActiveTripTracker.dismissDisruptions], maintainer
    // 2026-10-03), as shown. Null leaves Keep going out.
    onDismissDisruptions: ((List<RouteDisruption.Signal>) -> Unit)? = null,
    // The coming stations' other notices ([ActiveTripTracker.stationNotes]): a lift or an escalator out,
    // an exit shut. Shown, never alerted (maintainer, 2026-10-04); worked out off the main thread.
    notes: List<RouteDisruption.StationNote> = emptyList(),
    // The rider dismissed a note with its × ([ActiveTripTracker.dismissNote]). Null offers none.
    onDismissNote: ((RouteDisruption.StationNote) -> Unit)? = null,
    // The rider reroutes onto a branch on the board that leaves the plan ([ActiveTripTracker.take], maintainer
    // 2026-10-05). Null offers none.
    onTake: ((TripLeg, OffPlan.Branch) -> Unit)? = null,
    // The lines' statuses the trip's last check found ([ActiveTripTracker.lineChecks]), the board's among
    // them: a train tapped on the board opens its route's page with its line's status (maintainer, 2026-10-14).
    lineChecks: RouteDisruption.LinesChecked? = null,
    // The service alerts the rider dismissed on the near-me list, which a board train's route page and its line's
    // page honor as every line page does (maintainer, 2026-10-08), and how they dismiss one. Null offers no dismiss.
    dismissed: Set<DismissedAlert> = emptySet(),
    lineDismissal: LineAlertDismissal? = null,
) {
    val back = rememberBackFollow()
    RootBack(back, onBack = onBack)
    // A train tapped on the board: its route's page over this one, every train on it to come, as a row on the
    // main screen opens (maintainer, 2026-10-14), its line's page a "View line" away. Kept by the row's identity
    // ([detailKey]) and the route tapped on its card, so a rotation keeps it open, and found again in each board
    // as it refreshes. Only the rider closes it, or the trip ending: a refresh never does (maintainer, 2026-10-22).
    var boardRoute by rememberSaveable { mutableStateOf<String?>(null) }
    // Its line, so the page is up from the tap, headed by the line's pill, while its row is found.
    var boardLineId by rememberSaveable { mutableStateOf("") }
    var boardLineName by rememberSaveable { mutableStateOf("") }
    var boardMode by rememberSaveable { mutableStateOf("") }
    var boardDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var boardBranch by rememberSaveable { mutableStateOf<String?>(null) }
    // The way its trains run, so the page can move to another platform's row the same way once its own is
    // gone ([boardRouteOf]).
    var boardWay by rememberSaveable { mutableStateOf("") }
    // And the ride it was tapped for, kept whole and saved with the page ([TappedRideSaver]): its line's page
    // keeps the ride's stretch on the map whatever the trip does since (Codex, #627).
    var boardRide by rememberSaveable(stateSaver = TappedRideSaver) { mutableStateOf<TripLeg?>(null) }
    // The stop whose board it was tapped on: a board from another stop (the rider has boarded, so the next ride's is
    // up, or none is) doesn't list it, so the page holds as it was last drawn rather than look there. The stop, not
    // the ride: a ride the trip changes (a branch taken) boards from the same board.
    var boardFrom by rememberSaveable { mutableStateOf<String?>(null) }
    val routeLineOpen = rememberSaveable(boardRoute) { mutableStateOf(false) }
    val closeRoute = { boardRoute = null; boardRide = null; boardFrom = null }
    // The board's row for it, its line's status as the trip's last check found it marked on it, worked out on
    // the worker; the last answer stands in while a refreshed board's is, so a refresh never blinks the page. One
    // worked out for another status than the check's now is said as checking until its own is in, so a line
    // newly disrupted never reads clean meanwhile (Codex, #844).
    val board = nextTrains?.takeIf { trip != null }
    val boardOwn = board?.takeIf { it.ride.fromId == boardFrom }
    val boardStatus = boardLineStatus(lineChecks, boardLineId, now)
    val routeSlot = remember { mutableStateOf<Worked<Inputs, BoardRoute?>?>(null) }
    val routeTopology = LocalRouteTopology.current
    // The row the page was last drawn with, kept with its opening: what it stands on once the board has no train
    // on the route, and what it holds as once the board is another ride's.
    val routeDrawn = remember(boardRoute) { BoardRouteDrawn() }
    val route = rememberWorked(
        routeSlot,
        Inputs(boardRoute, boardOwn, boardStatus.status, dismissed, boardDestination, boardBranch, boardWay, routeTopology),
        keep = { held, wanted -> held.parts[0] != null && held.parts[0] == wanted.parts[0] },
    ) {
        boardRoute?.let { key ->
            boardOwn?.let {
                val focus = boardDestination?.let { destination -> RouteFocus(destination, boardBranch) }
                boardRouteOf(it, key, boardStatus.status, dismissed, focus, boardWay, routeTopology, routeDrawn.row)
            }
        }
    }
    SideEffect {
        if (route?.row != null && !route.gone) {
            routeDrawn.row = route.row
            routeDrawn.siblings = route.siblings
        }
    }
    // The trip over, the page goes with it: there's no ride left for it to be about. Nor once it has nothing to draw:
    // the row it was last drawn with isn't kept across a rotation, so after one, a board from another stop, or one
    // listing no train on its route, leaves nothing to stand it on, and it closes rather than stay blank, as the main
    // screen's does (Codex on #1105). Not while the board is still loading: the row isn't gone, just not in yet.
    val nothingToDraw = routeDrawn.row == null && (boardOwn == null || route != null && route.row == null && !boardOwn.pending)
    LaunchedEffect(boardRoute, trip == null, nothingToDraw) {
        if (boardRoute != null && (trip == null || nothingToDraw)) closeRoute()
    }
    // For the usage stats, the board's route page while it's open over the trip, its line's page over that,
    // else the trip.
    ReportScreen(
        when {
            boardRoute == null -> UsageEvent.Screen.ON_THE_WAY
            routeLineOpen.value -> UsageEvent.Screen.LINE
            else -> UsageEvent.Screen.ROUTE
        },
    )
    if (boardRoute != null && trip != null) {
        val statusPending = route != null && !sameAlert(route.status, boardStatus.status)
        // Its row not in this board (still loading, or another stop's): as last drawn; out of date (D4) once the
        // board is another stop's, its times no longer read.
        val routeHeld = route?.row == null && routeDrawn.row != null
        CompositionLocalProvider(LocalDismissLineAlert provides lineDismissal) {
            BoardRoutePage(
                route?.row ?: routeDrawn.row,
                if (routeHeld) routeDrawn.siblings else route?.siblings.orEmpty(),
                boardLineId,
                boardLineName,
                boardMode,
                boardStale = boardOwn == null || boardOwn.stale,
                gone = route?.gone == true,
                boardStatus,
                statusPending,
                boardRide,
                boardDestination?.let { RouteFocus(it, boardBranch) },
                routeLineOpen,
                lineDismissal,
                now,
                onClose = closeRoute,
            )
        }
    }
    // A dismiss from a train's route or line page that couldn't be written: a line page says so while it's open,
    // and this screen once the pages over it are closed, as every host of a line page does (Codex, #696).
    val snackbars = remember { SnackbarHostState() }
    val dismissFailedMessage = stringResource(R.string.dismiss_write_failed)
    val dismissFailed = lineDismissal?.failed == true
    val linePagesOpen = lineDismissal?.pagesOpen?.intValue ?: 0
    // Nor while a train's route page is open over the trip: a dismiss there is said once it's closed.
    val routeClosed = boardRoute == null
    // Only while the page is on top, so a glimpse of it under a back gesture spends no flag.
    OnTopEffect(dismissFailed, linePagesOpen == 0 && routeClosed) {
        if (dismissFailed && linePagesOpen == 0 && routeClosed) {
            // Cleared, then shown, so a second failure while this one shows is said too.
            lineDismissal?.onFailureShown?.invoke()
            snackbars.showSnackbar(dismissFailedMessage)
        }
    }
    // A ride saved from a train's route page ("Add favorite ride…"): said here once the page has closed, as a trip
    // says it, on a scope of its own since clearing it re-keys the effect.
    val rideAdded by RideAdds.added.collectAsStateWithLifecycle()
    val rideAddedMessage = rideAdded?.let { rideAddedText(it) }
    val rideAddedScope = rememberCoroutineScope()
    OnTopEffect(rideAddedMessage, routeClosed) {
        if (rideAddedMessage != null && routeClosed) {
            RideAdds.shown()
            rideAddedScope.launch { snackbars.showSnackbar(rideAddedMessage) }
        }
    }
    val destination = trip?.destinationName
    Scaffold(
        modifier = Modifier.followsBack(back),
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                // The app's name: where the trip goes, and when, lead the card below (maintainer, 2026-10-03).
                title = { Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                // A trip gone wrong is reported from where it's seen (maintainer, 2026-09-29).
                actions = { AppMenuOverflow() },
            )
        },
        bottomBar = {
            // Above the system navigation bar: the app draws edge to edge. The buttons grow with the
            // text size (at least 48dp high) and wrap onto a second line when they don't fit.
            val bar = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
            if (trip == null) {
                Row(bar, horizontalArrangement = Arrangement.End) {
                    Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.on_the_way_done)) }
                }
            } else {
                FlowRow(bar, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // End trip on its own at the start, away from Back and Next, which step through the legs.
                    OutlinedButton(onClick = onEnd, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.on_the_way_end)) }
                    if (onGoTo != null) {
                        // A ride is two steps, boarding it and getting off it (maintainer, 2026-09-29).
                        val at = OnTheWay.stepOf(trip)
                        val back = OnTheWay.stepBefore(trip)
                        val forward = OnTheWay.stepAfter(trip)
                        // Takes the rest of its line, so Back and Next sit at its end, on the first line or,
                        // when the text is too large for one, on their own.
                        FlowRow(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { back?.let { onGoTo(at, it) } },
                                enabled = back != null && OnTheWay.canGoTo(trip, back, now),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("onTheWayGoBack"),
                            ) { Text(stringResource(R.string.on_the_way_go_back)) }
                            // Off where the move would arrive at once (the last leg, or before a closing walk
                            // of no length): arriving forgets the trip, which Back couldn't undo, and End
                            // trip is the way out there ([OnTheWay.canGoTo]). Off rather than gone, so the
                            // buttons stay where they are (maintainer, 2026-09-28).
                            Button(
                                onClick = { forward?.let { onGoTo(at, it) } },
                                enabled = forward != null && OnTheWay.canGoTo(trip, forward, now),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("onTheWayGoNext"),
                            ) { Text(stringResource(R.string.on_the_way_go_next)) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        // What follows the current leg ([OnTheWay.etaTail] goes over the route's legs), worked out on the
        // worker, never in composition (AGENTS.md *Main thread*; Codex, #526): it's the route and the leg
        // alone, so it stands until either changes. The arrival is timed from it and the live progress at
        // once ([OnTheWay.etaFrom]); until it's in, none shows.
        val tailSlot = remember { mutableStateOf<Worked<Inputs, OnTheWay.EtaTail?>?>(null) }
        val etaTail = rememberWorked(tailSlot, Inputs(trip?.route, trip?.legIndex)) { trip?.let(OnTheWay::etaTail) }
        val ridingBranches = rememberRidingBranches(trip, progress, now)
        // The ride at hand, whose stops Show stops opens (maintainer, 2026-10-18): the one the rider is on or waiting
        // for, or walking to. On it, as the line it's followed on runs it ([OnTheWay.ridingOn]); still to come, as
        // planned, with the rider where it boards.
        val stopsLeg = trip?.let { t ->
            when {
                t.leg?.isWalk == false -> t.legIndex
                t.leg?.isWalk == true && t.route.legs.getOrNull(t.legIndex + 1)?.isWalk == false -> t.legIndex + 1
                else -> null
            }
        }
        val stopsAtHand = stopsLeg != null && stopsLeg == trip?.legIndex
        val stopsOn = stopsLeg?.let { i -> trip?.let { t -> (if (stopsAtHand) OnTheWay.ridingOn(t) else null) ?: t.route.legs[i] } }
            ?.takeIf { !it.isWalk }
        // Closed again for another ride, or another trip (its start), whatever leg it's at; open through the walk to
        // a ride and onto it, which is one leg (Codex, #968). Saved as the ride it's open for, so a screen restored
        // after the trip moved on doesn't open the next ride's stops.
        val stopsFor = "$stopsLeg@${trip?.startedAt}"
        var stopsOpenFor by rememberSaveable { mutableStateOf<String?>(null) }
        val stopsOpen = stopsLeg != null && stopsOpenFor == stopsFor
        val stopsProgress = progress.takeIf { stopsAtHand }
        // Seen on board, or the rider said so: a train that only left with no sign of them on it leaves them where it
        // boards, as the step does (Codex, #968).
        val stopsBoarded = stopsAtHand && trip?.onBoardSeen == true
        // The level the ride gets off at, worked out with its list off the main thread ([rememberRideStops]); an older
        // trip that kept no step-free level counts as asking none.
        val stopsLevel = trip?.stepFree ?: StepFree.DEFAULT
        val rideStops = rememberRideStops(stopsOn, stopsLevel, stopsProgress, stopsBoarded, stopsOpen, stale = !current)
        val stopsRail = stopsOn?.let { railColorFor(it) }
        val openStop = LocalOpenRouteStop.current
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).testTag("onTheWay"),
        ) {
            // Time left and when they get there (maintainer, 2026-10-01). From an answer too old to stand
            // behind ([current]), the step's own answer's alone, beside "Checking…", as its own time is.
            val stale = !current && fromTfl(progress)
            // The board's next train only for the ride the rider is still to board: another ride's board
            // times nothing here. Not while stale: the answered train's time gone by doesn't say they missed
            // it, so the arrival goes with it rather than move to another train (Codex, #611).
            val nextDue = nextTrains?.takeIf { !stale && it.ride == (progress as? TripProgress.Waiting)?.leg ?: (progress as? TripProgress.Lost)?.leg }?.nextDue
            val eta = trip?.let { t -> etaTail?.let { OnTheWay.etaFrom(t, progress, now, it, nextDue) } }
            // The card leads with the whole trip, then the step at hand (maintainer, 2026-10-03). The next
            // ride's trains sit right under it, the board the rider is heading for, before the route, and
            // close enough to read as the card's own (4dp, as the board's rows; maintainer, 2026-10-03).
            item(key = "next") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The phone's compass only on a walk with a way to point, and not behind the route page a
                    // train tapped opens over the trip (SPEC *On the way*; Codex, #775): battery.
                    val heading = LocalHeadingSource.current.heading(boardRoute == null && (progress as? TripProgress.Walking)?.bearing != null)
                    NextStep(destination, eta?.takeIf { !stale || asOf != null }, progress, now, current, asOf = asOf, heading = heading, pointing = true)
                    // Why the ride now runs only to a change: none of the plan's trains was listed, so the trip
                    // took a branch by itself, said loudly until the rider is past it (maintainer, 2026-10-06).
                    trip?.takeIf { it.branchTakenLeg >= 0 && it.legIndex <= it.branchTakenLeg }?.let {
                        WarningText(
                            stringResource(R.string.on_the_way_trains_none_direct, it.branchTakenTo),
                            it.branchTakenFork.takeIf { fork -> fork.isNotBlank() }?.let { fork -> stringResource(R.string.on_the_way_off_plan_change, fork) },
                        )
                    }
                    // On board the ride the board is for (its train taken to have left with them, say): the
                    // board's branches don't say which forks are behind them, so the ride's own stand in (Codex, #586).
                    val aboardBoard = nextTrains != null && trip != null && (trip.boarded || trip.onBoardSeen) && sameRide(nextTrains.ride, trip.leg)
                    if (nextTrains != null && trip != null) {
                        NextTrainsSection(nextTrains, now, onTake, offPlan = !aboardBoard) { row, focus ->
                            boardRide = listOfNotNull(nextTrains.ride, trip.leg).firstOrNull { it.lineId == row.lineId }
                            boardFrom = nextTrains.ride.fromId
                            boardLineId = row.lineId
                            boardLineName = row.lineName
                            boardMode = row.mode
                            boardDestination = focus?.destination
                            boardBranch = focus?.branch
                            boardWay = row.direction
                            boardRoute = row.detailKey()
                        }
                    }
                    // On board, the board's gone (or isn't theirs to change from): the branches still ahead, for a
                    // train that changed its branch on the way or that TfL labels wrongly (maintainer, 2026-10-05).
                    // Only from a current answer: from an old one, the train may have passed a fork since (Codex, #586).
                    if ((nextTrains == null || aboardBoard) && trip != null && current) ridingBranches.ride?.let { OtherRoutes(ridingBranches.rows, it, now, onTake) }
                }
            }
            if (trip != null) {
                // Only while it's still ahead of the trip as shown: worked out by the last check, it can lag
                // a step the trip has since taken, and a stop now behind the rider is never offered (Codex on #479).
                // Or the stop the rider was seen past theirs at, off the plan's way (maintainer, 2026-10-06).
                val planFrom = replanFrom?.takeIf { it.id == trip.pastAtId || it.id in ReplanOrigin.stopsAhead(trip, ReplanOrigin.rideAhead(trip, progress)) }
                val planAgain = planFrom?.takeIf { onPlanAgain != null }
                // Each thing known once, as the alert has it: two legs on one line read as one ([cards]).
                // Registered by count, not walked here: each card is read only as it's drawn (Codex on #519).
                items(cards, key = { signal -> "disruption/${signal.key}" }) { signal ->
                    // Gone past the change because the stop, or the line on from it, is closed: plan again without
                    // it, under the card that says so.
                    val missed = signal as? RouteDisruption.Signal.Missed
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DisruptionCard(signal, trip.route.legs.getOrNull(signal.legIndex)?.let { RouteDisruption.rideAt(trip, signal.legIndex, it) })
                        if (planAgain != null && onClosed != null && missed != null) {
                            if (missed.stopAvoid.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { onClosed(missed.stopAvoid, planAgain) },
                                    enabled = closedEnabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onTheWayStopClosed"),
                                ) {
                                    Text(stringResource(R.string.on_the_way_stop_closed, missed.stopName))
                                }
                            }
                            if (missed.onwardAvoid.isNotEmpty()) {
                                val lineWord = rememberLineWord()
                                OutlinedButton(
                                    onClick = { onClosed(missed.onwardAvoid, planAgain) },
                                    enabled = closedEnabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onTheWayLineClosed"),
                                ) {
                                    Text(stringResource(R.string.on_the_way_line_closed, missed.onwardLabel?.text(lineWord).orEmpty()))
                                }
                            }
                        }
                    }
                }
                // Only under a card: a branch the trip took by itself is said under the step, and neither a new
                // plan (the Planner offers the same train) nor Keep going answers it (Codex, #633).
                if (cards.isNotEmpty() && (planAgain != null || onDismissDisruptions != null)) {
                    // Plan again, or Keep going as planned (maintainer, 2026-10-03), side by side
                    // under the cards they answer; Keep going alone sits at the end.
                    item(key = "planAgain") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            if (planAgain != null && onPlanAgain != null) {
                                OutlinedButton(
                                    onClick = { onPlanAgain(planAgain) },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("onTheWayPlanAgain"),
                                ) {
                                    Text(stringResource(R.string.on_the_way_plan_again, planAgain.name))
                                }
                            }
                            if (onDismissDisruptions != null) {
                                OutlinedButton(
                                    onClick = { onDismissDisruptions(disruptions) },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("onTheWayKeepGoing"),
                                ) {
                                    Text(stringResource(R.string.on_the_way_keep_going))
                                }
                            }
                        }
                    }
                }
            }
            if (trip != null) {
                // Each coming station's notice that neither closes nor moves it, after anything that may stop
                // the trip, quieter than it: worth knowing on the way, no reason to change plans.
                items(notes, key = { note -> "note/${note.legIndex}/${note.stopId}" }) { note ->
                    StationNoteCard(note, onDismissNote?.let { dismiss -> { dismiss(note) } })
                }
            }
            if (endFailed && trip != null) {
                item(key = "endFailed") {
                    Text(
                        stringResource(R.string.on_the_way_end_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (notKept && trip != null) {
                item(key = "notKept") {
                    Text(
                        stringResource(R.string.on_the_way_not_kept),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (appOpenOnly && trip != null) {
                item(key = "appOpenOnly") {
                    Text(
                        stringResource(R.string.on_the_way_app_open_only),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (failed) {
                item(key = "failed") {
                    Text(
                        stringResource(R.string.on_the_way_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (alertsOff && trip != null) {
                item(key = "alertsOff") {
                    Text(
                        stringResource(R.string.on_the_way_alerts_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trip != null) {
                val steps = OnTheWay.steps(trip)
                val at = OnTheWay.stepOf(trip)
                val doneCount = OnTheWay.stepsDone(trip)
                // One row per leg (maintainer, 2026-10-03): a ride's getting-off step is still a step, for
                // Back, Next and the card above, but not a row of its own. On board, the ride's row is the
                // rider's; it's done once they're past getting off it.
                steps.forEachIndexed { index, step ->
                    if (step.onBoard) return@forEachIndexed
                    val leg = trip.route.legs[step.leg]
                    val last = if (leg.isWalk) index else index + 1
                    val onTap = onGoTo?.takeIf { OnTheWay.canGoTo(trip, step, now) }?.let { go -> { go(at, step) } }
                    item(key = "leg${step.leg}") {
                        LegLine(leg, trip.route.rides, current = at.leg == step.leg, done = last < doneCount, onTap = onTap)
                    }
                    // The ride at hand's stops, a tap away under its row, each a row of this list.
                    if (step.leg == stopsLeg && stopsOn != null && stopsRail != null) {
                        item(key = "showStops${step.leg}") { ShowStopsButton(stopsOpen) { stopsOpenFor = if (stopsOpen) null else stopsFor } }
                        val stops = rideStops?.list
                        val failure = rideStops?.failure
                        if (stops != null && stops.names.size >= 2) {
                            items(stops.names.size, key = { "stop${step.leg}/$it" }) { i ->
                                // A stop tapped opens its details, as a route page's does (maintainer, 2026-10-23); one the
                                // Planner gave no id opens none.
                                val id = stops.ids.getOrNull(i)?.takeIf { it.isNotBlank() }
                                val head = rideStops?.head
                                val onOpen = openStop?.takeIf { id != null && head != null }?.let { open -> { open(rideStopOpen(head!!, id!!, stops.names[i], i)) } }
                                RideStopRow(stops.names, i, stops.here, stopsRail, joinGap = 12.dp, onOpen = onOpen)
                            }
                        }
                        if (failure != null) {
                            // The line's route couldn't be read: said with why, under what could be listed.
                            item(key = "stopsFailed${step.leg}") {
                                RideStopsNote(stringResource(routeStopsFailureMessage(failure)), Modifier.testTag("onTheWayRideStopsFailed"))
                            }
                        } else if (stops != null && stops.names.size < 2) {
                            // Neither the Planner nor the line's route could list them: said, not left blank.
                            item(key = "stopsUnavailable${step.leg}") {
                                RideStopsNote(stringResource(R.string.route_stops_unavailable), Modifier.testTag("onTheWayRideStopsUnavailable"))
                            }
                        } else if (stops == null && stopsOpen) {
                            // Still being worked out (the line's route read, at worst): said, once it's taken long enough
                            // to see, so a list in at once never flashes it (Codex, #968). Its line is held from the
                            // first frame, unseen and unread, so the legs below don't move when it shows.
                            item(key = "stopsLoading${step.leg}") {
                                var shown by remember { mutableStateOf(false) }
                                LaunchedEffect(Unit) {
                                    delay(LOADING_NOTE_DELAY_MILLIS)
                                    shown = true
                                }
                                RideStopsNote(
                                    stringResource(R.string.route_stops_loading),
                                    if (shown) Modifier.testTag("onTheWayRideStopsLoading") else Modifier.alpha(0f).clearAndSetSemantics {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The trains at the boarding stop of the rider's next ride that take them where they get off
 * (maintainer, 2026-09-28): every line of the ride's mode, not just the Planner's, each a way to the
 * same stop ([OnTheWay.boardTrains]). [pending] while a line's route is still loading and
 * [unresolved] when a train couldn't be checked, so a short or empty list is never passed off as the
 * whole answer; [stale] when the last board is too old to stand behind (D4), when its rows are drawn
 * as marked guesses under "Checking…"; [failed] when the last update couldn't reach TfL, said beside whatever is shown.
 * A train due before [readyAt] (the rider still walking or changing) is grayed: listed, but not one
 * they can catch.
 */
data class NextTrains(
    val ride: TripLeg,
    val trains: List<Departure>,
    val pending: Boolean = false,
    val unresolved: Boolean = false,
    val stale: Boolean = false,
    val failed: Boolean = false,
    val readyAt: Instant? = null,
    // When the board was read: its rows' age, as the departures board ages a stop's.
    val fetchedAt: Instant? = null,
    // The boarding pole's letter, "towards" and bearing ([ActiveTripTracker.NextBoard.pole]), so the
    // board is headed as the main view heads that stop ("Stop D", "➔ Archway", "Southbound"), never by
    // where one of its buses goes; blank for none.
    val stopLetter: String = "",
    val towards: String = "",
    val bearing: String = "",
    // The boarding stop pair's other poles read with it ([ActiveTripTracker.NextBoard.others]), each
    // under its own header: another of the ride's lines boards there, across the road. None for a
    // station, or with no other pole read.
    val others: List<PoleTrains> = emptyList(),
    // When the soonest train the rider can catch is due, of every pole's ([OnTheWay.nextDue]): what the
    // trip's time falls back on once the train followed has gone by. Null with none, or a board too old.
    val nextDue: Instant? = null,
    // [trains] and [others] as the board draws them, a header per pole ([nextTrainsGroups]): worked out
    // with them on the worker, so the section only draws them.
    val groups: List<StopGroup> = emptyList(),
    // Each of [groups] as its card draws it ([stopCard]), worked out with them.
    val cards: List<StopCard> = emptyList(),
    // The board's trains that leave the plan ([OffPlan], maintainer 2026-10-05), a row per heading,
    // worked out on the worker with the rest: grayed under the plan's own, each one the rider can take.
    val offPlan: List<OffPlanRow> = emptyList(),
    // Whether any of [offPlan] lists a train, and where they all turn off when that's one stop (null
    // otherwise): worked out with [groups] on the worker ([withGroups]), so the section only reads them.
    val offPlanListed: Boolean = false,
    val offPlanFork: String? = null,
    // The plan's own row's name where none of its trains is listed: the terminus the Planner's train runs
    // to ("High Barnet"), as the platform's boards say it, else where the rider gets off. Worked out with them.
    val plannedHeading: String = "",
) {
    /**
     * Whether no pole has a train listed: the board's own ([trains]) nor any of [others], nor any that
     * leaves the plan ([offPlan]), which a failed refresh keeps showing as it keeps the rest (Codex, #583).
     */
    val none: Boolean get() = trains.isEmpty() && others.all { it.trains.isEmpty() } && offPlan.isEmpty()
}

/**
 * A branch that leaves the plan ([OffPlan.Branch]) as the board lists it: "(Bank)", grayed, as it isn't
 * the plan's (maintainer, 2026-10-05), with the next few [trains] TfL lists for it (none where TfL
 * lists none, or labels them otherwise). [key] tells the row apart across boards, so one opened stays
 * open as times come and go.
 */
data class OffPlanRow(
    val key: String,
    val lineId: String,
    val lineName: String,
    val mode: String,
    val heading: String,
    val branch: OffPlan.Branch,
    val trains: List<Departure>,
)

/**
 * [branches] of [ride] as rows, nearest fork first: the first [MAX_OFF_PLAN_ROWS], so the card composes
 * a bounded few (Codex, #583), each with its next [MAX_OFF_PLAN_TIMES] trains.
 */
@WorkerThread
internal fun offPlanRows(ride: TripLeg, branches: List<OffPlan.Branch>): List<OffPlanRow> =
    branches.take(MAX_OFF_PLAN_ROWS).map { branch ->
        OffPlanRow(
            "${branch.lineId}|${branch.forkIndex}|${branch.label}", branch.lineId.ifBlank { ride.lineId },
            branch.lineName.ifBlank { ride.lineName }, ride.mode, branch.label, branch, branch.trains.take(MAX_OFF_PLAN_TIMES),
        )
    }

// As many branches as the card lists: a branching line's few, never a whole board.
internal const val MAX_OFF_PLAN_ROWS = 4

// As many times as a board's row shows.
private const val MAX_OFF_PLAN_TIMES = 3

/**
 * A pole of the boarding stop pair besides the ride's own ([NextTrains.others]): its trains that take
 * the rider where they get off, headed by its letter and "towards" as the main view heads it.
 */
data class PoleTrains(
    val stopId: String,
    val stopName: String,
    val trains: List<Departure>,
    val stopLetter: String = "",
    val towards: String = "",
    val bearing: String = "",
)

/**
 * [board] ([ActiveTripTracker.nextBoard]) as [NextTrains] at [now]: its trains kept to those whose
 * line's route reaches the ride's alighting stop, from the same route data the trip's cards check
 * against ([rememberLineSequences]). Null with no board.
 */
@Composable
internal fun rememberNextTrains(
    board: ActiveTripTracker.NextBoard?,
    now: Instant,
    readyAt: Instant? = null,
    // The ride ahead ([OnTheWay.upcomingRide]): its section shows at once, "Loading" until its first
    // board is in, rather than appear only once TfL answers.
    ride: TripLeg? = board?.ride,
    // The suspects last logged of what the section holds ([DoubledTrains]), compared on the worker.
    logged: LoggedSuspects = remember { LoggedSuspects() },
    // Where the ride ends when it's a piece of a branch the trip took by itself ([OffPlan.boardShortTo]):
    // its trains are named by it, not by TfL's terminus.
    shortTo: String? = null,
    // The lines' statuses the trip's last check found ([ActiveTripTracker.lineChecks]): each board row of a
    // line it found disrupted carries the list's ⚠, as every board does (SPEC *Disruptions*: a disrupted
    // line is always kept flagged). Read on the worker ([boardStatuses]).
    lineChecks: RouteDisruption.LinesChecked? = null,
    // The near-me list's dismissed alerts, which the board's rows honor as the list's do ([withDismissedMarked]).
    dismissed: Set<DismissedAlert> = emptySet(),
): NextTrains? {
    val worker = LocalWorker.current
    // The lines whose routes the board's trains are checked against, read off its departures on the
    // worker too: the last board's held meanwhile, as its routes are what a new board of the ride needs.
    var lineIds by remember { mutableStateOf(BoardLines(null, emptyList())) }
    LaunchedEffect(board?.let(::BoardKey), worker) {
        // No board (the rider seen on board): no lines, so a route load under way for the last one is
        // canceled and nothing is rechecked through the ride (Codex on #557).
        val read = board ?: run {
            lineIds = BoardLines(null, emptyList())
            return@LaunchedEffect
        }
        if (lineIds.board === read) return@LaunchedEffect
        lineIds = BoardLines(read, withContext(worker) { nextBoardLineIds(read.ride, read.boards.values.flatten()) })
    }
    val loads = rememberLineLoads(lineIds.ids, now)
    val sequences = loads.sequences
    val routes = LocalRouteStops.current
    val currentSequences by rememberUpdatedState(sequences)
    // How the board's stop cards group a branching line's trains ([stopCard]): set once for the app.
    val topology = LocalRouteTopology.current
    // The board's trains are worked out on the worker, from each instant one departs ([trainsTimeline]),
    // keyed by the board itself (a new one each read) and its routes, so composition compares no
    // departures and only picks the entry for now (AGENTS.md *Main thread: read and dispatch only*).
    // Only once its own lines are read: the last board's may leave out a line it brings back, already
    // loaded, so its routes would be missed with no new load to work the board out again (Codex on #557).
    val key = board?.takeIf { it.fetchedAt != null && lineIds.board === it && sameRide(it.ride, ride) }?.let { TrainsKey(it, loads.version, topology, shortTo, lineChecks, dismissed) }
    var held by remember { mutableStateOf<HeldTrains?>(null) }
    LaunchedEffect(key, worker) {
        key ?: return@LaunchedEffect
        if (held?.key == key) return@LaunchedEffect
        val at = now
        val timeline = withContext(worker) {
            trainsTimeline(key.board, currentSequences, at, key.topology, key.shortTo, boardStatuses(key.lineChecks, at), key.dismissed)
        }
        held = HeldTrains(key, timeline, index = 0)
        // A train its route couldn't place, logged where every trip filter logs it, so "Couldn't check
        // every line" can be explained. Later entries hold fewer trains, so the first has every miss.
        routes?.reportMisses(timeline.misses)
    }
    // No board of this ride's yet (just started, or back after a restart): its section, loading.
    if (board == null || !sameRide(board.ride, ride)) return ride?.let { NextTrains(it, emptyList(), pending = true, readyAt = readyAt) }
    // Never read: nothing to show but that the update failed.
    if (board.fetchedAt == null) return NextTrains(board.ride, emptyList(), failed = board.failed, readyAt = readyAt)
    // This board's trains, or while they're worked out the last board's of this ride, each dropped as it
    // departs as a refresh would drop it; none worked out yet for this ride: loading.
    // Whether this refresh failed is the current board's to say, at once, whatever rows show or while
    // none do yet (Codex on #557): a pole of the pair left unread is said too, as a train there went unseen.
    val failed = board.failed || board.partial
    val shown = held?.takeIf { it.key == key || sameRide(it.key.board.ride, board.ride) }
        ?: return NextTrains(board.ride, emptyList(), pending = true, failed = failed, readyAt = readyAt)
    // The entry held, or the one after it from the moment it starts: two comparisons, no search on the
    // main thread. Past both (the page back after a while), the worker finds the entry for now, and
    // meanwhile the section says it's loading rather than show a train that's gone.
    val timeline = shown.timeline
    val lapsed = timeline.endedBy(shown.index, now)
    val skipped = lapsed && timeline.endedBy(shown.index + 1, now)
    // The clock set back before the held entry began (Codex on #557): trains it had dropped may be to
    // come again, so the worker finds the entry for now, or works the board out afresh from before its
    // timeline began, and meanwhile the section says it's loading.
    val rewound = timeline.startsAfter(shown.index, now)
    // A line check the rows are marked from gone stale with no new one in (Codex, #1174): worked out afresh,
    // so a ⚠ never outlasts the check behind it. One comparison here; the worker found when.
    val expired = timeline.statusesExpired(now)
    LaunchedEffect(shown, lapsed, rewound, expired, worker) {
        if (!lapsed && !rewound && !expired) return@LaunchedEffect
        val at = now
        val rebuilt = expired || timeline.startsAfter(0, at)
        val (index, window) = withContext(worker) {
            val from = if (rebuilt) {
                trainsTimeline(
                    shown.key.board, currentSequences, at, shown.key.topology, shown.key.shortTo,
                    boardStatuses(shown.key.lineChecks, at), shown.key.dismissed,
                )
            } else {
                timeline
            }
            from.indexAt(at).let { it to from.around(it) }
        }
        if (held !== shown) return@LaunchedEffect
        held = HeldTrains(shown.key, window, index)
        // Worked out afresh from before, it may hold trains the first timeline had already dropped, so
        // its misses are logged too (Codex on #557).
        if (rebuilt) routes?.reportMisses(window.misses)
    }
    if (skipped || rewound) return NextTrains(board.ride, emptyList(), pending = true, failed = failed, readyAt = readyAt)
    // The next entry's rows came with the held one's, so a departure never waits on the worker.
    val nextIndex = if (lapsed) shown.index + 1 else shown.index
    val next = timeline.entry(nextIndex)
        ?: return NextTrains(board.ride, emptyList(), pending = true, failed = failed, readyAt = readyAt)
    // A train the section holds twice, or two at one platform under a minute apart, logged as each entry
    // comes on screen, beside what the board read ([ActiveTripTracker]), so more trains on the screen than
    // at the platform can be traced to TfL's answer or to the app. Found on the worker with the entry's
    // rows ([TrainsTimeline.around]); only read here, and logged once for the same suspects.
    LaunchedEffect(timeline, nextIndex, worker) {
        val found = timeline.doubled(nextIndex)
        // Whether they're new is a comparison of sets, so it's the worker's too (Codex on #719).
        val fresh = withContext(worker) { logged.changedTo(found?.key.orEmpty()) }
        if (found != null && fresh) {
            val age = shown.key.board.fetchedAt?.let { SteadyClock.age(it, now).seconds }
            StopdashDebugLog.warning("on the way: trip board holds %s (stop cards and branch rows, shown or collapsed), board %s s old", found.text, age)
        }
    }
    val stale = Staleness.isStale(next.fetchedAt ?: now, now)
    // The current board's, not the held one's, and only once its own lines are read: before, the routes
    // may be the last board's, and a line it brings back, already loaded, bumps no version to work it
    // out again (Codex on #557). None until the worker has it.
    val linesRead = lineIds.board === board
    val nextDue = rememberNextDue(board, sequences, if (linesRead) loads.version else LINES_UNREAD, readyAt, now)
        .takeIf { linesRead && !stale }
    return next.copy(stale = stale, readyAt = readyAt, nextDue = nextDue, failed = failed)
}

/**
 * Whether [a] and [b] are the same ride, as a board of it goes: the same line, stops and times, by its
 * fields alone, never its path (a [TripLeg]'s equality walks it), so composition compares a few
 * values, not a route (Codex on #557).
 */
internal fun sameRide(a: TripLeg?, b: TripLeg?): Boolean = a === b || (
    a != null && b != null && a.mode == b.mode && a.lineId == b.lineId && a.fromId == b.fromId && a.toId == b.toId &&
        a.toArea == b.toArea && a.departure == b.departure && a.arrival == b.arrival
    )

// A routes version for [rememberNextDue] before a board's own lines are read: never a real one, so
// the answer is worked out again once they are.
private const val LINES_UNREAD = -1

// A board by identity (a new one each read), so a key compares no departures.
private class BoardKey(val board: ActiveTripTracker.NextBoard) {
    override fun equals(other: Any?): Boolean = other is BoardKey && other.board === board
    override fun hashCode(): Int = System.identityHashCode(board)
}

/**
 * The lines whose routes a board of [ride] needs: those read off its [departures] ([OnTheWay.boardLineIds]),
 * and the ride's own line, whose route finds the branches off it ([OffPlan.branches]) even when TfL lists
 * none of its trains (Codex, #583). Not a bus's, which has none.
 */
@WorkerThread
internal fun nextBoardLineIds(ride: TripLeg, departures: List<Departure>): List<String> {
    val listed = OnTheWay.boardLineIds(ride, departures)
    if (ride.isBus || ride.isWalk || ride.lineId.isBlank() || ride.lineId in listed) return listed
    return (listed + ride.lineId).sorted()
}

// The line ids a board needs ([nextBoardLineIds]); none before the first board.
private class BoardLines(val board: ActiveTripTracker.NextBoard?, val ids: List<String>)

// What a board's trains are worked out from: the board by identity (a new one each read), so the key
// compares no departures, its routes' [LineLoads.version], and the route topology its cards are grouped
// under, by identity: replaced once its patterns load, the board's rows are worked out again (Codex, #588).
// [shortTo], where the trains are named by the ride's end ([OffPlan.boardShortTo]). The trip's line checks by
// identity (a new one each check) and the dismissed alerts, which mark the rows' ⚠s.
private class TrainsKey(
    val board: ActiveTripTracker.NextBoard,
    val routes: Int,
    val topology: RouteTopology,
    val shortTo: String? = null,
    val lineChecks: RouteDisruption.LinesChecked? = null,
    val dismissed: Set<DismissedAlert> = emptySet(),
) {
    override fun equals(other: Any?): Boolean =
        other is TrainsKey && other.board === board && other.routes == routes && other.topology === topology &&
            other.shortTo == shortTo && other.lineChecks === lineChecks && other.dismissed === dismissed
    override fun hashCode(): Int =
        (((System.identityHashCode(board) * 31 + routes) * 31 + System.identityHashCode(topology)) * 31 + shortTo.hashCode()) * 31 +
            System.identityHashCode(lineChecks)
}

/**
 * The [statuses] a board's rows are marked with, and when the first of the checks behind them goes stale
 * ([until], wall clock; null with none), when they're worked out again.
 */
internal class BoardStatuses(val statuses: Map<String, LineStatus>, val until: Instant?) {
    companion object {
        val NONE = BoardStatuses(emptyMap(), null)
    }
}

/**
 * The statuses of [checks] a board's rows are marked with at [at]: each line's while that check stands behind
 * it ([boardLineStatus]: asked, still followed, and current), none otherwise, so a row never carries a verdict
 * the check can't stand behind and a line newly disrupted is marked from the check that finds it.
 */
@WorkerThread
internal fun boardStatuses(checks: RouteDisruption.LinesChecked?, at: Instant): BoardStatuses {
    checks ?: return BoardStatuses.NONE
    val kept = checks.statuses.keys.mapNotNull { id ->
        boardLineStatus(checks, id, at).takeIf { !it.unknown && !it.checking }?.status?.let { id to it }
    }.toMap()
    val until = kept.keys.mapNotNull { id ->
        checks.atOf(id)?.let { at.plus(Staleness.remainingUntilStale(Staleness.age(it, at)).toJavaDuration()) }
    }.minOrNull()
    return BoardStatuses(kept, until)
}

// A board's timeline and the entry of it in force when last looked up ([index]).
private class HeldTrains(val key: TrainsKey, val timeline: TrainsTimeline, val index: Int)

/**
 * A board's trains as [rememberNextTrains] shows them, from each instant on ([starts], ascending):
 * an entry per instant the list shown changes, each with the trains still to come then. Only the
 * entries around the one in force carry their rows ([groups], [around]): the next one's are ready the
 * moment it starts, and a busy board's later entries aren't all grouped ahead of time (Codex on #557).
 */
internal class TrainsTimeline(
    private val starts: List<Instant>,
    private val entries: List<NextTrains>,
    val misses: Set<RouteMiss>,
    // How a stop card groups a branching line's trains ([stopCardLines]).
    private val topology: RouteTopology = RouteTopology.EMPTY,
    private val rows: Map<Int, NextTrains> = emptyMap(),
    // The lines' statuses and dismissed alerts the rows are marked with ([nextTrainsGroups]).
    private val statuses: BoardStatuses = BoardStatuses.NONE,
    private val dismissed: Set<DismissedAlert> = emptySet(),
    // What each entry with rows holds that may count a train twice ([DoubledTrains]), found with them.
    private val doubledAt: Map<Int, DoubledTrains.Found?> = emptyMap(),
) {
    /** What entry [index] (or the last) holds that may count a train twice, if its rows are worked out. */
    fun doubled(index: Int): DoubledTrains.Found? = doubledAt[index.coerceAtMost(entries.lastIndex)]

    /** Entry [index] (or the last, once past it) with its rows, or null if they aren't worked out. */
    fun entry(index: Int): NextTrains? {
        val at = index.coerceAtMost(entries.lastIndex)
        return rows[at]
    }

    /** Whether a line check the rows are marked from has gone stale by [now] ([BoardStatuses.until]). */
    fun statusesExpired(now: Instant): Boolean = statuses.until?.let { !now.isBefore(it) } == true

    /** Whether entry [index] starts after [now]: the clock set back before it. */
    fun startsAfter(index: Int, now: Instant): Boolean = now.isBefore(starts[index.coerceAtMost(starts.lastIndex)])

    /** Whether entry [index] has given way to a later one by [now] (the last never does). */
    fun endedBy(index: Int, now: Instant): Boolean = index + 1 < starts.size && !now.isBefore(starts[index + 1])

    /** The index of the entry in force at [now]: the last to start by then, else the first. */
    @WorkerThread
    fun indexAt(now: Instant): Int {
        val found = starts.binarySearch(now)
        return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
    }

    /** This timeline with the rows of entry [index] and the one after it, the only ones kept. */
    @WorkerThread
    fun around(index: Int): TrainsTimeline {
        val window = (index..(index + 1).coerceAtMost(entries.lastIndex)).associateWith { i ->
            rows[i] ?: entries[i].withGroups(starts[i], topology, statuses.statuses, dismissed)
        }
        val doubled = window.mapValues { (i, entry) -> if (i in doubledAt) doubledAt[i] else DoubledTrains.find(entry.heldTrains(), starts[i]) }
        return TrainsTimeline(starts, entries, misses, topology, window, statuses, dismissed, doubled)
    }

    /** The entry in force at [now] ([indexAt]), with its rows. */
    @WorkerThread
    fun at(now: Instant): NextTrains {
        val index = indexAt(now)
        return entries[index].withGroups(starts[index], topology, statuses.statuses, dismissed)
    }
}

/**
 * [rememberNextTrains]' work, off the main thread: [board]'s trains as [nextTrainsAt] keeps them, from
 * [at] and again from each instant the list shown changes (a train kept, loading or unplaced goes).
 * Each pole's trains are routed once ([OnTheWay.placeTrains]), and each entry taken from them, so a
 * busy board isn't routed again for every departure (Codex on #557).
 */
@WorkerThread
internal fun trainsTimeline(
    board: ActiveTripTracker.NextBoard,
    sequences: Map<String, LineSequence?>,
    at: Instant,
    topology: RouteTopology = RouteTopology.EMPTY,
    // Where the ride ends, when its trains are named by it ([OffPlan.boardShortTo]); null for TfL's terminus.
    shortTo: String? = null,
    // The lines' statuses the rows are marked with ([boardStatuses]), and the alerts dismissed on the list.
    statuses: BoardStatuses = BoardStatuses.NONE,
    dismissed: Set<DismissedAlert> = emptySet(),
): TrainsTimeline {
    val fetchedAt = checkNotNull(board.fetchedAt) { "a board never read has no trains" }
    val own = OnTheWay.placeTrains(board.ride, board.departures, fetchedAt, sequences, at)
    val others = board.others.map { other ->
        other to OnTheWay.placeTrains(otherRide(board, other), other.departures, fetchedAt, sequences, at)
    }
    // The ride's own pole's trains that leave the plan, placed once and dropped as each leaves.
    val offPlan = OffPlan.branches(board.ride, board.departures, sequences, at)
    val instants = (listOf(at) + own.changes + others.flatMap { it.second.changes } + offPlan.flatMap { branch -> branch.trains.map { it.expectedArrival } })
        .filter { !it.isBefore(at) }.distinct().sorted()
    val entries = instants.map { instant ->
        nextTrainsAt(board, own.trainsAt(instant), others.map { (other, placed) -> other to placed.trainsAt(instant) }, shortTo)
            .copy(offPlan = offPlanRows(board.ride, offPlan.map { branch -> branch.copy(trains = branch.trains.filter { !Countdown.hasDeparted(it, instant) }) }))
    }
    // Misses are reported once per board, so only the first instant's are gathered.
    return TrainsTimeline(instants, entries, own.missesAt(at) + others.flatMap { it.second.missesAt(at) }, topology, statuses = statuses, dismissed = dismissed).around(0)
}

/** [board]'s ride as it boards at [other], another pole of the pair: only another line's way to the same stop. */
private fun otherRide(board: ActiveTripTracker.NextBoard, other: ActiveTripTracker.PoleBoard): TripLeg =
    board.ride.copy(fromId = other.pole.id, fromName = other.pole.name.ifBlank { board.ride.fromName })

/**
 * [board] ([ActiveTripTracker.nextBoard]) as [NextTrains] at an instant, from what its poles' routes
 * kept then ([found] for its own, [others] for the pair's others): trains whose line's route reaches
 * the ride's alighting stop, each other pole's as the ride boards there. Its rows are left for
 * [TrainsTimeline.around], and [NextTrains.stale], [NextTrains.readyAt] and [NextTrains.nextDue] for
 * the caller, who knows them as it draws.
 */
@WorkerThread
internal fun nextTrainsAt(
    board: ActiveTripTracker.NextBoard,
    found: OnTheWay.BoardTrains,
    others: List<Pair<ActiveTripTracker.PoleBoard, OnTheWay.BoardTrains>>,
    shortTo: String? = null,
): NextTrains = NextTrains(
    board.ride, found.trains.map { it.namedTo(shortTo) },
    pending = found.pending || others.any { it.second.pending },
    unresolved = found.unresolved || others.any { it.second.unresolved },
    // A pole of the pair left unread is said too: a train there went unseen.
    failed = board.failed || board.partial,
    fetchedAt = board.fetchedAt, stopLetter = board.pole?.stopLetter.orEmpty(), towards = board.pole?.towards.orEmpty(),
    bearing = board.pole?.bearing.orEmpty(),
    others = others.map { (other, trains) ->
        PoleTrains(other.pole.id, other.pole.name.ifBlank { board.ride.fromName }, trains.trains.map { it.namedTo(shortTo) }, other.pole.stopLetter, other.pole.towards, other.pole.bearing)
    },
)

/**
 * This train named by where the ride takes the rider, in brackets ("(Camden Town)"), not by TfL's
 * terminus and branch: on a ride the trip cut short at a fork by itself, every train on its board takes
 * the rider there, and TfL's label is the one thing about it the trip couldn't trust (an Edgware train
 * as High Barnet via Bank; maintainer, 2026-10-09). Unchanged with [to] null. Only once its train is
 * placed: its route is resolved from its own label.
 */
internal fun Departure.namedTo(to: String?): Departure =
    if (to == null) this else copy(destination = "($to)", branch = null, via = "", destinationId = "")

/**
 * The suspects ([DoubledTrains.Found.key]) a trip's section last logged, so the same ones read again aren't
 * logged again. [changedTo] compares sets, so it's called on the worker only; [onCompare] sees each key, for a test.
 */
internal class LoggedSuspects(private val onCompare: (Set<String>) -> Unit = {}) {
    private var last: Set<String> = emptySet()

    /** Whether [key] differs from the last one, which it then replaces. */
    @WorkerThread
    @Synchronized
    fun changedTo(key: Set<String>): Boolean {
        onCompare(key)
        val changed = key != last
        last = key
        return changed
    }
}

/**
 * Every train the section holds a time for, as many times as it holds it ([DoubledTrains]): its stop cards'
 * and its rows of branches off the plan ([offPlan]), so one on both is counted twice. What the section
 * holds, not what's in view: Other routes may be collapsed, and the log says so rather than track every way
 * the section can hide a row (Codex on #719).
 */
@WorkerThread
internal fun NextTrains.heldTrains(): List<Departure> =
    cards.flatMap { card -> card.lines.flatten().flatMap { it.times } } + offPlan.flatMap { it.trains }

/** These trains with their [NextTrains.groups] worked out at [now] ([nextTrainsGroups]), and each one's card ([NextTrains.cards]). */
@WorkerThread
internal fun NextTrains.withGroups(
    now: Instant,
    topology: RouteTopology = RouteTopology.EMPTY,
    statuses: Map<String, LineStatus> = emptyMap(),
    dismissed: Set<DismissedAlert> = emptySet(),
): NextTrains {
    val groups = nextTrainsGroups(this, now, statuses, dismissed)
    // The branches with trains listed, and the one stop they all turn off at, if they share it.
    val listed = offPlan.filter { it.trains.isNotEmpty() }
    val fork = listed.map { it.branch.forkName }.distinct().singleOrNull()?.takeIf { it.isNotBlank() }
    val heading = ride.headings.firstOrNull()?.let { DepartureLabels.destinationLabel(it, "") ?: it } ?: ride.toName
    return copy(groups = groups, cards = groups.map { stopCard(it, topology, ride.toId) }, offPlanListed = listed.isNotEmpty(), offPlanFork = fork, plannedHeading = heading)
}

/**
 * [next]'s trains as the board draws them at [now]: the ride's own pole first, then the pair's others, each its own
 * header ("Stop N"). A row of a line [statuses] has disrupted carries the list's ⚠, less what was [dismissed].
 */
@WorkerThread
internal fun nextTrainsGroups(
    next: NextTrains,
    now: Instant,
    statuses: Map<String, LineStatus> = emptyMap(),
    dismissed: Set<DismissedAlert> = emptySet(),
): List<StopGroup> {
    val fetchedAt = next.fetchedAt ?: SteadyClock.stamp(now)
    val shown = LineStatus.asOf(statuses, now)
    return StopGrouping.groupByStop(
        (
            DepartureRows.forStop(
                next.ride.fromId, next.ride.fromName, next.trains, now, shown, fetchedAt = fetchedAt,
                stopLetter = next.stopLetter, towards = next.towards, bearing = next.bearing,
            ) + next.others.flatMap { pole ->
                DepartureRows.forStop(
                    pole.stopId, pole.stopName, pole.trains, now, shown, fetchedAt = fetchedAt,
                    stopLetter = pole.stopLetter, towards = pole.towards, bearing = pole.bearing,
                )
            }
            ).map { withDismissedMarked(it, dismissed) },
    )
}

/**
 * When the soonest train [board] lists that takes the rider where they get off and that they can
 * catch is due ([OnTheWay.nextDue]): every pole's, its trains kept as [rememberNextTrains] keeps them,
 * from [sequences]. Worked out on the page's worker ([LocalWorker]), never the main thread, keyed by
 * the board itself rather than its trains, so composition compares no departures (AGENTS.md *Main
 * thread: read and dispatch only*; Codex, PR #520). An answer is only ever this board's: null while
 * a new board's is worked out, never the last board's train (Codex, PR #520). It holds until its
 * train is due, since no sooner one can turn up on the same board, and is worked out again then.
 */
@Composable
internal fun rememberNextDue(
    board: ActiveTripTracker.NextBoard,
    sequences: Map<String, LineSequence?>,
    // The routes' [LineLoads.version]: a route loaded, or replaced in place by a retry, works it out again.
    routesVersion: Int,
    readyAt: Instant?,
    now: Instant,
): Instant? {
    val worker = LocalWorker.current
    val key = DueKey(board, readyAt, routesVersion)
    val routes by rememberUpdatedState(sequences)
    var held by remember { mutableStateOf<HeldDue?>(null) }
    val mine = held?.takeIf { it.key == key }
    val expired = mine?.due?.let { now.isAfter(it) } == true
    LaunchedEffect(key, expired, worker) {
        if (mine != null && !expired) return@LaunchedEffect
        val at = now
        held = HeldDue(key, withContext(worker) { nextDueOf(board, routes, readyAt, at) })
    }
    return mine?.due?.takeIf { !now.isAfter(it) }
}

// What a fallback train is worked out from: the board by identity (a new one each read), so the key
// compares no departures.
private class DueKey(val board: ActiveTripTracker.NextBoard, val readyAt: Instant?, val routes: Int) {
    override fun equals(other: Any?): Boolean = other is DueKey && other.board === board && other.readyAt == readyAt && other.routes == routes
    override fun hashCode(): Int = (System.identityHashCode(board) * 31 + (readyAt?.hashCode() ?: 0)) * 31 + routes
}

private class HeldDue(val key: DueKey, val due: Instant?)

/** [rememberNextDue]'s work, off the main thread: [board]'s poles' trains as the board keeps them. */
@WorkerThread
internal fun nextDueOf(board: ActiveTripTracker.NextBoard, sequences: Map<String, LineSequence?>, readyAt: Instant?, now: Instant): Instant? {
    val fetchedAt = board.fetchedAt ?: return null
    val trains = OnTheWay.boardTrains(board.ride, board.departures, fetchedAt, sequences, now).trains +
        board.others.flatMap { other ->
            val ride = board.ride.copy(fromId = other.pole.id, fromName = other.pole.name.ifBlank { board.ride.fromName })
            OnTheWay.boardTrains(ride, other.departures, fetchedAt, sequences, now).trains
        }
    return OnTheWay.nextDue(trains, readyAt, now)
}

/**
 * The next ride's trains ([NextTrains]) under that ride's own row, drawn as the departures board
 * draws a stop (maintainer, 2026-09-28): its platform header over the board's own card, a row per
 * line and terminus with its next few times, so they read as the board rather than as another step.
 * A time due before the rider can be there is grayed. A row tapped opens its route's page, with the route
 * tapped on its card ([onOpenRoute], maintainer 2026-10-14); null leaves the rows with nothing to open.
 */
@Composable
private fun NextTrainsSection(
    next: NextTrains,
    now: Instant,
    // The rider takes a train that leaves the plan ([OffPlanCard]); null offers none.
    onTake: ((TripLeg, OffPlan.Branch) -> Unit)? = null,
    // Whether the board offers its branches off the plan: not once the rider is on board its ride.
    offPlan: Boolean = true,
    onOpenRoute: ((DepartureRow, RouteFocus?) -> Unit)? = null,
) {
    val groups = next.groups
    Column(Modifier.fillMaxWidth().testTag("onTheWayTrains"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A failed update is said whatever else shows: the rows may be the last good board's.
        if (next.failed) NoteText(stringResource(R.string.on_the_way_failed))
        // Too old to stand behind (D4): the last board's rows stay, drawn as the boards draw an old one
        // (each line's soonest a dimmed, marked guess), while it's checked, as the card above keeps its
        // time beside "Checking…" (maintainer, 2026-10-06).
        if (next.stale && !next.failed) NoteText(stringResource(R.string.on_the_way_checking))
        if (next.failed && next.none) return@Column
        next.cards.forEach { card ->
            val group = card.group
            StopGroupHeader(group.stopName, group.qualifier, distanceLabel = null, firstOnScreen = false)
            StopGroupCard(
                card,
                now,
                starred = emptySet(),
                onToggleStar = {},
                starringAvailable = false,
                onOpenDetail = onOpenRoute,
                grayBefore = next.readyAt,
            )
        }
        // The trains that leave the plan, under the plan's own (maintainer, 2026-10-05).
        // The branches off the plan, behind Other routes so they don't crowd the board (maintainer, 2026-10-05).
        // Not from a board too old to stand behind: a branch taken off the plan is chosen by its times.
        // With none of the plan's own listed, they're the board's only live trains: shown open, under the
        // board's "None going to …", rather than an empty board over a button (maintainer, 2026-10-06).
        // Not while a train couldn't be checked: it may be one of the plan's (Codex, #630).
        val branchesOnly = offPlan && !next.stale && groups.isEmpty() && !next.pending && !next.unresolved && next.offPlanListed
        if (branchesOnly) {
            // Said loudly: the plan's train isn't running as planned (the Planner's timetable can promise
            // one TfL's live board doesn't list, with no alert to say so), and where to change when every
            // listed train turns off at the same place (maintainer, 2026-10-06).
            WarningText(
                stringResource(R.string.on_the_way_trains_none_direct, next.ride.toName),
                next.offPlanFork?.let { stringResource(R.string.on_the_way_off_plan_change, it) },
            )
            OffPlanCard(next.offPlan, next.ride, now, onTake, planned = next.plannedHeading.ifEmpty { next.ride.toName })
            return@Column
        }
        if (offPlan && !next.stale) OtherRoutes(next.offPlan, next.ride, now, onTake)
        // What the rows may be missing, said rather than left to be taken as the whole answer; an old
        // board's "Checking…" above says it already.
        if (!next.stale) when {
            next.pending && groups.isEmpty() -> NoteText(stringResource(R.string.on_the_way_trains_loading))
            next.pending -> NoteText(stringResource(R.string.on_the_way_trains_checking))
            next.unresolved -> NoteText(stringResource(R.string.on_the_way_trains_unchecked))
            groups.isEmpty() -> NoteText(stringResource(R.string.on_the_way_trains_none, next.ride.toName))
        }
    }
}

/**
 * The board's row for a route tapped on it ([boardRouteOf]), null when the board no longer lists it, and the
 * line [status] it was worked out with. [siblings]: the board's rows, which the page lists the route's trains on
 * other platforms from ([app.stopdash.domain.otherPlatforms]).
 */
internal class BoardRoute(
    val row: DepartureRow?,
    val status: LineStatus?,
    val siblings: List<DepartureRow> = emptyList(),
    // [row] stands for the route with no train, the board listing none on it at any platform ([boardRouteOf]).
    val gone: Boolean = false,
)

/**
 * What a board train's route page was last drawn with ([BoardRoute.row], [BoardRoute.siblings]): written after each
 * composition that found the row, read once it's gone. Read on the worker too, so each write is published to it.
 */
private class BoardRouteDrawn {
    @Volatile var row: DepartureRow? = null
    @Volatile var siblings: List<DepartureRow> = emptyList()
}

/**
 * The row of [next]'s board opened by [key] ([detailKey]), as the main screen's route page has it: its line's
 * [status] as the trip's last check found it, this way's alone and only where it's disrupted, with its work to
 * come ([DepartureRows.forStop]'s rule), then the near-me list's [dismissed] alerts marked or left out
 * ([withDismissedMarked]). The page is the route's, not the platform's (maintainer, 2026-10-15): with that row
 * gone, the board's row the same [way] with the [focus] route's soonest train on another platform
 * ([routeAnchor]). Once the board lists the route at no platform, [drawn] (the row the page was last drawn with)
 * stands for it with no train ([BoardRoute.gone]), its alerts and age as of this board, so a refresh never closes
 * the page (maintainer, 2026-10-22); its row null with none drawn, or while the board is still loading.
 */
@WorkerThread
internal fun boardRouteOf(
    next: NextTrains,
    key: String,
    status: LineStatus?,
    dismissed: Set<DismissedAlert>,
    focus: RouteFocus? = null,
    way: String = "",
    topology: RouteTopology = RouteTopology.EMPTY,
    drawn: DepartureRow? = null,
): BoardRoute {
    val rows = next.groups.flatMap { it.rows }
    val found = rows.firstOrNull { it.detailKey() == key }
        ?: focus?.let { f -> key.split('|').takeIf { it.size >= 2 }?.let { parts -> routeAnchor(rows, parts[0], parts[1], way, f, topology) } }
    val row = found
        // Its dismissal marked afresh below, for the alerts as of now.
        ?: drawn?.takeIf { !next.pending }?.copy(
            upcoming = emptyList(), untimed = emptyList(), fetchedAt = next.fetchedAt ?: drawn.fetchedAt, statusDismissed = false,
        )
        ?: return BoardRoute(null, status)
    val routeStatus = status?.forDirection(row.direction)
    return BoardRoute(
        withDismissedMarked(row.copy(status = routeStatus?.takeIf(LineStatus::disrupted), plannedAlerts = routeStatus?.planned.orEmpty()), dismissed),
        status,
        rows,
        gone = found == null,
    )
}

/**
 * A train's route tapped on the board ([row]), on the main screen's route page ([RouteDetailScreen]): every
 * train on it to come, from any of the board's platforms ([siblings]), its stops, and its line's status as the trip's last check found it ([status],
 * [boardLineStatus]), the line's own page a "View line" away ([lineOpen]), its map keeping the ride it was
 * tapped for ([ride]). Drawn in a window of its own over the trip, as a line page is, so the trip under it keeps
 * its place. Its alerts dismiss into the near-me list's store ([dismissal]), as every line page's do. Up from the
 * tap: until its [row] is found, headed by the line's pill alone ([BoardRoutePending]). [statusPending] while the
 * row was worked out for another status than the check's now: checking, never clean, until its own is in.
 * [boardStale] when the board is too old to stand behind, or is no longer this ride's; [gone] when [row] stands for
 * the route with no train ([BoardRoute.gone]).
 */
@Composable
private fun BoardRoutePage(
    row: DepartureRow?,
    siblings: List<DepartureRow>,
    lineId: String,
    lineName: String,
    mode: String,
    boardStale: Boolean,
    gone: Boolean,
    status: BoardLineStatus,
    statusPending: Boolean,
    ride: TripLeg?,
    focus: RouteFocus?,
    lineOpen: MutableState<Boolean>,
    dismissal: LineAlertDismissal?,
    now: Instant,
    onClose: () -> Unit,
) {
    // A window of its own: drawn over a glimpse of the page it's over, it would cover the page being swiped.
    if (!LocalPageOnTop.current) return
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().testTag("onTheWayRoutePage")) {
            if (row == null) {
                BoardRoutePending(lineId, lineName, mode, onClose)
            } else {
                val checking = status.checking || statusPending
                // "Add favorite ride…" from its menu, as on a trip's route page (maintainer, 2026-10-24): the page asks
                // "To…", Back only stops it, and a pick saves the ride and closes to On the way, which says how it went.
                var picking by rememberSaveable(lineId) { mutableStateOf(false) }
                val addRouteRide = LocalAddRouteRide.current
                RouteDetailScreen(
                    lineOpen = lineOpen,
                    row = row,
                    // Nothing on the trip stars a row, as on a trip's own route pages.
                    isStarred = false,
                    starrable = false,
                    // A stop tapped on its list opens its details over the page, as on the near-me list's.
                    onOpenStop = LocalOpenRouteStop.current,
                    ofTrip = true,
                    // The line's check alone vouches for it here: the trip's own check tells of its stops, on the
                    // trip under this page.
                    disruptionUnknown = status.unknown,
                    disruptionChecking = checking,
                    // Too old to stand behind as the board says it, or by its own age (D4): its times held back.
                    stale = boardStale || Staleness.isStale(row.fetchedAt, now),
                    now = now,
                    onToggleStar = {},
                    onBack = { if (picking) picking = false else onClose() },
                    picking = picking,
                    onPickTo = addRouteRide?.let { add ->
                        { loaded, stop ->
                            add(row, loaded, stop)
                            picking = false
                            onClose()
                        }
                    },
                    onStartPicking = { picking = true },
                    focus = focus,
                    onDismissAlert = dismissal?.let { d -> row.status?.let { s -> { d.dismiss(s) } } },
                    onDismissPlanned = dismissal?.dismissPlanned?.let { d -> { planned -> d(row.lineId, planned) } },
                    ride = ride,
                    lineUnknown = status.unknown,
                    lineChecking = checking,
                    siblings = siblings,
                    // It had trains, so the page never says TfL gave it none.
                    rowGone = gone,
                )
            }
        }
    }
}

/**
 * A board train's route page while its row is found ([BoardRoutePage]): its bar as the page has it, Back and the
 * line's pill, over nothing yet, so the tap is answered at once and nothing moves when the rest comes in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardRoutePending(lineId: String, lineName: String, mode: String, onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("onTheWayRoutePending"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { LinePill(lineName = lineName, lineId = lineId, mode = mode) },
            )
        },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) }
}

/**
 * The ride a board's train was tapped for, saved whole with the page (Codex, #627): once the trip has
 * ended, nothing live is left to find it again in. Its ends are public stops, kept on the device like the
 * trip itself. Saving and restoring hand over a fixed set of references, the ride's lists as they are (a
 * parcel writes any list as one), so neither walks a route on the main thread (Codex, #627).
 */
internal val TappedRideSaver = Saver<TripLeg?, ArrayList<Any?>>(
    save = { leg ->
        leg?.let {
            arrayListOf(
                it.mode, it.lineId, it.lineName, it.fromId, it.fromName, it.toId, it.toName, it.departure, it.arrival,
                it.path, it.pathNames, it.changeAfter, it.headings, it.fromArea, it.toArea,
                it.fromAt?.latitude, it.fromAt?.longitude, it.toAt?.latitude, it.toAt?.longitude, it.plannedFromId, it.plannedToId,
            )
        }
    },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        fun strings(i: Int) = saved[i] as List<String>
        fun at(i: Int) = (saved[i] as Double?)?.let { lat -> (saved[i + 1] as Double?)?.let { lon -> Coordinates(lat, lon) } }
        TripLeg(
            saved[0] as String, saved[1] as String, saved[2] as String, saved[3] as String, saved[4] as String,
            saved[5] as String, saved[6] as String, saved[7] as Instant, saved[8] as Instant,
            strings(9), strings(10), saved[11] as Duration, strings(12), saved[13] as String, saved[14] as String,
            fromAt = at(15), toAt = at(17), plannedFromId = saved[19] as String, plannedToId = saved[20] as String,
        )
    },
)

/** A line's status as the trip's last check found it ([boardLineStatus]). */
internal class BoardLineStatus(val status: LineStatus?, val unknown: Boolean, val checking: Boolean)

/**
 * [lineId]'s status as the trip's last check found it ([checks]; the board's lines are asked with the
 * trip's on each refresh), by one rule (Codex, #627): its status while that check is current and the trip
 * still follows the line ([RouteDisruption.LinesChecked.following], kept up as the trip or its board moves
 * on). Else in doubt, its status kept beside that (SPEC D4): [BoardLineStatus.checking] while a check under
 * way asks about it ([RouteDisruption.LinesChecked.asking]), a line the last check got no status for
 * included (its request failed, or TfL left it out); else [BoardLineStatus.unknown], since none will (the
 * board failed, or moved on to the next ride). A line with no id can't be asked about.
 */
internal fun boardLineStatus(checks: RouteDisruption.LinesChecked?, lineId: String, now: Instant): BoardLineStatus {
    if (lineId.isBlank()) return BoardLineStatus(null, unknown = true, checking = false)
    val status = checks?.takeIf { lineId in it.asked }?.statuses?.get(lineId)
    val asking = checks?.asking?.contains(lineId) == true
    val followed = checks != null && lineId in checks.following
    if (status != null && followed && checkCurrent(checks?.atOf(lineId), now)) return BoardLineStatus(status, unknown = false, checking = false)
    return BoardLineStatus(status, unknown = !asking, checking = asking)
}

/**
 * **Other routes** (maintainer, 2026-10-05): a button that shows the branches off the plan ([OffPlanCard])
 * under it, and hides them again, so they're there when wanted without crowding the board. Nothing when
 * there are none.
 */
@Composable
private fun OtherRoutes(rows: List<OffPlanRow>, ride: TripLeg, now: Instant, onTake: ((TripLeg, OffPlan.Branch) -> Unit)?) {
    if (rows.isEmpty() || onTake == null) return
    var shown by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = { shown = !shown },
            modifier = Modifier.heightIn(min = 48.dp).testTag("onTheWayOtherRoutes"),
        ) { Text(stringResource(R.string.on_the_way_other_routes)) }
        if (shown) OffPlanCard(rows, ride, now, onTake)
    }
}

/** The ride the rider is on board and its branches still ahead ([rememberRidingBranches]); none while not riding. */
internal class RidingBranches(val ride: TripLeg?, val rows: List<OffPlanRow>)

/**
 * While the rider is on board [trip]'s ride ([TripProgress.Riding]), or on board a train the trip can no
 * longer place on it ([TripProgress.Lost]: one that changed its branch on the way, maintainer
 * 2026-10-05), its branches whose fork is still ahead of them ([OffPlan.branches]), as rows: none passed,
 * as far as the stops left say. Worked out on the page's worker from the ride's line's route, kept a day
 * and shared with the board's; nothing on a bus, or a ride taken on another of its lines.
 */
@Composable
internal fun rememberRidingBranches(trip: ActiveTrip?, progress: TripProgress?, now: Instant): RidingBranches {
    val riding = progress as? TripProgress.Riding
    // Lost, only once this answer's calls placed the train: with none, where it is now isn't known (Codex, #586).
    val leg = riding?.leg ?: (progress as? TripProgress.Lost)?.takeIf { it.placed }?.leg?.takeIf { trip?.boarded == true || trip?.onBoardSeen == true }
    val ride = trip?.leg?.takeIf { leg != null && sameRide(it, leg) && !it.isWalk && !it.isBus && trip.vehicleLeg == null }
    val loads = rememberLineLoads(listOfNotNull(ride?.lineId), now)
    // Read inside the effect, which keys on the routes' version: held by reference, so no recomposition
    // compares a route or a ride's path on the main thread (Codex, #586).
    val sequencesHeld = remember { mutableStateOf(loads.sequences, referentialEqualityPolicy()) }
    sequencesHeld.value = loads.sequences
    val rideHeld = remember { mutableStateOf(ride, referentialEqualityPolicy()) }
    rideHeld.value = ride
    // The next stop's index on the ride's path: a fork before it is behind them. Lost, the last known before
    // their train was (Codex, #586). Unknown, none is ruled out.
    val next = riding?.stopsLeft?.let { left -> ride?.let { it.path.size - left } } ?: (progress as? TripProgress.Lost)?.ahead
    val worker = LocalWorker.current
    var held by remember { mutableStateOf(RidingBranches(null, emptyList())) }
    LaunchedEffect(ride?.fromId, ride?.toId, ride?.departure, ride?.lineId, loads.version, next, worker) {
        val on = rideHeld.value ?: run {
            held = RidingBranches(null, emptyList())
            return@LaunchedEffect
        }
        val seq = sequencesHeld.value[on.lineId] ?: return@LaunchedEffect
        val at = now
        held = RidingBranches(
            on,
            withContext(worker) {
                offPlanRows(on, OffPlan.branches(on, emptyList(), mapOf(on.lineId to seq), at).filter { next == null || it.forkIndex >= next })
            },
        )
    }
    return held.takeIf { ride != null && sameRide(it.ride, ride) } ?: RidingBranches(null, emptyList())
}

/**
 * The branches that leave the plan ([NextTrains.offPlan]): a row per branch, its pill, its name in
 * brackets and any times TfL lists for it, all gray, as none is the plan's (maintainer, 2026-10-05). A
 * tap opens **Take this one** under the row, with where the rider would change, so a stray tap changes
 * nothing; taking it reroutes the trip that way ([OffPlan.take]).
 */
@Composable
private fun OffPlanCard(
    rows: List<OffPlanRow>,
    ride: TripLeg,
    now: Instant,
    onTake: ((TripLeg, OffPlan.Branch) -> Unit)?,
    // Led by the plan's own row, named [planned], its times a dash: none of its trains listed (maintainer,
    // 2026-10-06). Null leaves it out.
    planned: String? = null,
) {
    // The row opened, by its key: it stays open as its times come and go, and closes once it's gone.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val gray = MaterialTheme.colorScheme.outline
    val takeLabel = stringResource(R.string.on_the_way_take_this)
    // As a board's card caps its pills: half the card's inner width, so a long name can't starve the times.
    val pillMax = (LocalConfiguration.current.screenWidthDp.dp - 64.dp) * 0.5f
    OutlinedCard(Modifier.fillMaxWidth().testTag("onTheWayOffPlan")) {
        Column {
            if (planned != null) {
                val none = stringResource(R.string.on_the_way_trains_none, planned)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("onTheWayPlannedRow"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinePill(ride.lineName, ride.lineId, ride.mode, Modifier.widthIn(max = pillMax))
                    Text(
                        planned,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    // No time to give, as a board's row with none lists a dash, read out as what it means.
                    Text(
                        "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { contentDescription = none },
                    )
                }
            }
            rows.forEachIndexed { index, row ->
                if (index > 0 || planned != null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val expanded = onTake != null && open == row.key
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (onTake != null) {
                                Modifier.clickable(onClickLabel = takeLabel) { open = if (expanded) null else row.key }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinePill(row.lineName, row.lineId, row.mode, Modifier.widthIn(max = pillMax))
                        Text(
                            stringResource(R.string.on_the_way_off_plan_heading, row.heading),
                            style = MaterialTheme.typography.titleMedium,
                            color = gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        // A row's few times, no more ([MAX_OFF_PLAN_TIMES]); none where TfL lists none.
                        if (row.trains.isNotEmpty()) {
                            // Grayed whole already; a doubtful train is also said to be so ([doubtfulTimes]).
                            val spoken = doubtfulTimes(Countdown.parts(row.trains, now)).second
                            Text(
                                stringResource(
                                    R.string.on_the_way_off_plan_times,
                                    row.trains.joinToString(" · ") { Countdown.minutes(it.expectedArrival, now).toString() },
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = gray,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.semantics { spoken?.let { contentDescription = it } },
                            )
                        }
                    }
                    if (expanded && onTake != null) {
                        Text(
                            stringResource(R.string.on_the_way_off_plan_change, row.branch.forkName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                open = null
                                onTake(ride, row.branch)
                            },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("onTheWayTakeThis"),
                        ) { Text(takeLabel) }
                    }
                }
            }
        }
    }
}

/** A board's warning, in the error color: none of the plan's trains listed, with [detail] on what to do. */
@Composable
private fun WarningText(text: String, detail: String?) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("onTheWayTrainsWarning")) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

// A note on the board: what its rows may be missing, or what's known of them.
@Composable
private fun NoteText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * [eta] as its screen says it at [now], longest first: the minutes left, counted as the boards count,
 * and the clock time, then, for an estimate, the same without its "est.", the first thing the card
 * drops when the row is short (maintainer, 2026-10-04).
 */
@Composable
internal fun etaForms(eta: OnTheWay.Eta, now: Instant): List<String> {
    LocalConfiguration.current // Read again on a configuration change (locale, font scale).
    val resources = LocalContext.current.resources
    val full = etaText(resources, eta, now)
    if (eta.live) return listOf(full)
    val minutes = Countdown.minutes(eta.arrival, now).toInt()
    return listOf(full, resources.getString(R.string.on_the_way_step_time, CLOCK.format(eta.arrival.atZone(LONDON)), minutes))
}

/** [etaText] from [resources]. */
internal fun etaText(resources: Resources, eta: OnTheWay.Eta, now: Instant): String {
    val minutes = Countdown.minutes(eta.arrival, now).toInt()
    val clock = CLOCK.format(eta.arrival.atZone(LONDON))
    return resources.getString(if (eta.live) R.string.on_the_way_trip_eta else R.string.on_the_way_trip_eta_estimated, clock, minutes)
}

/**
 * The card at the top: the whole trip, where to and, beside it, when ([eta], held back while the answer
 * it's from is too old to stand behind), then what the rider does next, from [progress], large, over a
 * row with a ride's stops left and, beside them, when that step's done where it's known. Only the step
 * is large, so it reads first; the times sit at the end, the minutes before the clock, with no labels
 * (maintainer, 2026-10-03).
 */
@Composable
internal fun NextStep(
    destination: String?,
    eta: OnTheWay.Eta?,
    progress: TripProgress?,
    now: Instant,
    current: Boolean,
    // The card's own: the trip screen's, or the banner atop another screen ([OnTheWayBanner]), the same card.
    modifier: Modifier = Modifier,
    tag: String = "onTheWayNext",
    // The place's and the step's forms ([destinationTitleForms], [stepTitleForms]) where they were worked
    // out on a worker ([OnTheWayBanner]); null works them out here.
    destinationForms: List<String>? = null,
    titleForms: List<String>? = null,
    // When the step's last answer was had ([ActiveTripTracker.answeredAt]): while it's too old to stand
    // behind ([current]), its time still shows, its stops "Checking…"; null holds them back.
    asOf: Instant? = null,
    // Which way the phone faces ([LocalHeadingSource]), for the walk's arrow; null draws none, its compass
    // point still named (the banner atop another screen, which doesn't follow the compass).
    heading: Float? = null,
    // Whether this card points the way (the trip's screen): its walk row then keeps the arrow's slot
    // whether or not there's a heading yet, so the text never moves when one comes or goes.
    pointing: Boolean = false,
) {
    val detail = nextStepText(progress, now, current, asOf).second
    // From an answer too old to stand behind, its time stays while its stops are checked (maintainer, 2026-10-06).
    val checking = !current && fromTfl(progress) && asOf != null
    val at = stepTime(progress, current || checking, now)
    // Timed, the step's time stands in for its own words, which say the same; a ride's stops stay beside
    // it, as a walk's distance left does, where a fix has placed the rider (maintainer, 2026-10-03).
    // Not until the rider's distance units are known (null while their choice loads): never a moment in
    // the wrong ones (Codex, PR #521).
    val system = LocalDistanceSystem.current
    val walking = progress as? TripProgress.Walking
    val walkLeft = walking?.metersLeft
    val point = walking?.point
    val walkLabel = if (walkLeft != null && system != null) {
        // Estimated from a fix taken before the walk began, it says so until a fix on the walk places the rider.
        distanceText(walkLeft, system, LocalResources.current).let { if (walking?.estimated == true) stringResource(R.string.on_the_way_walk_left_estimated, it) else it }
    } else {
        null
    }
    // The way to the walk's end beside how far it is, as a compass point ("450 m NE"; maintainer, 2026-10-11).
    val walkSpoken = walkLabel?.let { label -> point?.let { stringResource(R.string.on_the_way_walk_left_spoken, label, stringResource(compassWord(it))) } }
    val words = if (walkLabel != null) {
        point?.let { stringResource(R.string.on_the_way_walk_left_direction, walkLabel, stringResource(compassLetters(it))) } ?: walkLabel
    } else if (at == null) {
        detail
    } else if (checking) {
        stringResource(R.string.on_the_way_checking)
    } else if (progress is TripProgress.Riding) {
        LocalConfiguration.current // Read again on a configuration change (locale, font scale).
        rideStopsLeft(LocalContext.current.resources, progress)
    } else {
        ""
    }
    // A ride's stops left beside its next stop, the stop in the longest of its forms that fits and cut with "…"
    // where none does, the count held still (maintainer, 2026-10-07). Untimed too, where the step's words are the stops:
    // current, or counted from where the rider was seen, which no answer going stale touches (Codex, #655).
    val stopsSplit = (progress as? TripProgress.Riding)
        ?.takeIf { !checking && (at != null || ((current || !fromTfl(it)) && (it.seen || it.getOffSoon))) }
        ?.let { rideStopsSplit(LocalContext.current.resources, it) }
    val stepAt = at?.let { stepTimeText(it, now) }.orEmpty()
    Card(colors = nextStepColors(progress, current), modifier = modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (destination != null) {
                CardRow(
                    destinationForms ?: destinationTitleForms(destination),
                    eta?.let { etaForms(it, now) }.orEmpty(),
                    endTag = "onTheWayEta",
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = LocalContentColor.current.copy(alpha = 0.25f))
            }
            ShortenedLines(
                titleForms ?: stepTitleForms(progress, now, current),
                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (stopsSplit != null) {
                CardRow(listOf(stopsSplit.count), listOf(stepAt), tail = stopsSplit)
            } else if (walkLabel != null && (walkSpoken != null || pointing)) {
                // The arrow turns with the phone, pointing where the letters say; a screen reader hears the
                // direction spelled out, never the letters or a number of degrees. On the trip's screen its slot
                // is held from the first frame, the heading coming a moment later, so the text holds still
                // (AGENTS.md *hold still*; Codex, #775).
                val spoken = walkSpoken?.let { listOf(it, stepAt).filter { part -> part.isNotEmpty() }.joinToString(", ") }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier),
                ) {
                    val bearing = walking?.bearing
                    if (pointing) {
                        Box(Modifier.size(24.dp)) {
                            if (heading != null && bearing != null) WalkArrow(Compass.relative(bearing, heading.toDouble()).toFloat())
                        }
                    } else if (heading != null && bearing != null) {
                        Box(Modifier.size(24.dp)) { WalkArrow(Compass.relative(bearing, heading.toDouble()).toFloat()) }
                    }
                    Box(Modifier.weight(1f)) { CardRow(listOf(words), listOf(stepAt)) }
                }
            } else if (words.isNotEmpty() || stepAt.isNotEmpty()) {
                CardRow(listOf(words), listOf(stepAt))
            }
        }
    }
}

// An arrow turned [turn] degrees clockwise from straight up the screen, the way to the walk's end: a
// 16dp glyph in a 24dp slot, the height of the card's row text, so the row keeps its height.
@Composable
private fun WalkArrow(turn: Float) {
    val color = LocalContentColor.current
    Canvas(Modifier.fillMaxSize().padding(4.dp).graphicsLayer { rotationZ = turn }.testTag("onTheWayWalkArrow")) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w / 2, 0f)
            lineTo(w * 0.85f, h)
            lineTo(w / 2, h * 0.75f)
            lineTo(w * 0.15f, h)
            close()
        }
        drawPath(path, color)
    }
}

/** A compass point spelled out, as a screen reader hears the walk's direction. */
private fun compassWord(point: Compass.Point): Int = when (point) {
    Compass.Point.N -> R.string.compass_n
    Compass.Point.NE -> R.string.compass_ne
    Compass.Point.E -> R.string.compass_e
    Compass.Point.SE -> R.string.compass_se
    Compass.Point.S -> R.string.compass_s
    Compass.Point.SW -> R.string.compass_sw
    Compass.Point.W -> R.string.compass_w
    Compass.Point.NW -> R.string.compass_nw
}

/** A compass point in letters, as a walk's row and its Live Update chip write the way ("NE"). */
internal fun compassLetters(point: Compass.Point): Int = when (point) {
    Compass.Point.N -> R.string.compass_n_letters
    Compass.Point.NE -> R.string.compass_ne_letters
    Compass.Point.E -> R.string.compass_e_letters
    Compass.Point.SE -> R.string.compass_se_letters
    Compass.Point.S -> R.string.compass_s_letters
    Compass.Point.SW -> R.string.compass_sw_letters
    Compass.Point.W -> R.string.compass_w_letters
    Compass.Point.NW -> R.string.compass_nw_letters
}

// A row of the card: the first of [start]'s forms (longest first) that fits on the left, and [ends]'
// first form (a time) whole at the right. Where both don't fit, the time drops to its last form first
// (an estimate's "est."; maintainer, 2026-10-04); where they still don't (a narrow window, large text) the text yields,
// shortened before it's cut with a single "…" ([ShortenedText]); the time keeps its one line wherever
// it fits beside the text's last stub (its first word and the next letter, then "…"), and wraps only past that
// (maintainer, 2026-10-03; Codex, PR #518).
@Composable
private fun CardRow(start: List<String>, ends: List<String>, endTag: String? = null, tail: RideStops? = null) {
    // One style across the row: the place reads as the time beside it does (maintainer, 2026-10-04).
    val style = MaterialTheme.typography.bodyLarge
    val startStyle = style
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val fontScale = density.fontScale
        val gap = 12.dp
        // The time's first form where it fits beside the text's longest, else its last.
        val gapPx = with(density) { gap.roundToPx() }
        val end = remember(start, ends, style, fontScale, constraints.maxWidth, gapPx) {
            val first = ends.firstOrNull().orEmpty()
            if (ends.size < 2) {
                first
            } else {
                val textWidth = start.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { measurer.measure(it, style, maxLines = 1).size.width } ?: 0
                val endWidth = measurer.measure(first, style, maxLines = 1).size.width
                if (textWidth + gapPx + endWidth <= constraints.maxWidth) first else ends.last()
            }
        }
        // The text's last stub: its first word whole (the "To" around a place) and the next letter, then
        // "…" ("To C…"), never a bare "…" (Codex, PR #518).
        // With a tail, the count before it is the stub: it never shortens, only the tail does.
        val stub = if (tail != null) tail.count else start.last().takeIf { it.isNotEmpty() }?.let { text ->
            val word = text.indexOf(' ').takeIf { it in 1 until text.length - 1 }?.let { text.take(it + 2) } ?: text.take(1)
            "$word…"
        }
        val stubWidth = remember(stub, startStyle, fontScale) { stub?.let { measurer.measure(it, startStyle, maxLines = 1).size.width } ?: 0 }
        // All the row but the text's stub and the gap: the time's single line where that fits, else it wraps in it.
        // A few px of slack: the stub measured alone can still elide to "…" laid out in exactly its width.
        val slack = if (stub != null) with(density) { gap.roundToPx() + 4.dp.roundToPx() } else 0
        val endMax = with(density) { (constraints.maxWidth - stubWidth - slack).coerceAtLeast(0).toDp() }
        // The next stop only where at least its first letter fits ([RideStops.nextStub]): never a bare "· …"
        // (large text, a narrow window); left out, the count reads alone.
        val tailFits = remember(tail, end, style, fontScale, constraints.maxWidth) {
            tail != null && run {
                val used = stubWidth + measurer.measure(tail.nextStub, startStyle, maxLines = 1).size.width +
                    measurer.measure(end, style, maxLines = 1).size.width
                // The gap before the time only where there's a time to stand apart from (Codex, #655).
                used + with(density) { ((if (end.isNotEmpty()) gap else 0.dp) + (if (tail.count.isNotEmpty()) 4.dp else 0.dp)).roundToPx() } <= constraints.maxWidth
            }
        }
        // The next stop shortened only as far as it must be: the first of its forms (as named, its words
        // shortened, its floor) that fits beside the count and the time, else the shortest, cut with "…"
        // (maintainer, 2026-10-07).
        val next = remember(tail, end, style, fontScale, constraints.maxWidth) {
            tail?.let {
                val room = constraints.maxWidth - stubWidth - measurer.measure(end, style, maxLines = 1).size.width -
                    with(density) { ((if (end.isNotEmpty()) gap else 0.dp) + (if (it.count.isNotEmpty()) 4.dp else 0.dp)).roundToPx() }
                // Three forms at most, fixed ([RideStops]): the longest that fits, measured as the row's other text is.
                fun fits(form: String) = measurer.measure(form, startStyle, maxLines = 1).size.width <= room
                when {
                    fits(it.nextFull) -> it.nextFull
                    fits(it.nextShort) -> it.nextShort
                    else -> it.nextFloor
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            // With no count to fall back on (not counted), the next stop stays, cut as it must be (Codex, #655).
            if (tail != null && !tailFits && tail.count.isNotEmpty()) {
                Text(tail.count, style = startStyle, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            } else if (tail != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    if (tail.count.isNotEmpty()) Text(tail.count, style = startStyle, maxLines = 1, softWrap = false)
                    // Abbreviated, so read in full ([RideStops.nextFull]).
                    Text(
                        next.orEmpty(),
                        style = startStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).testTag("onTheWayNextStop")
                            .then(if (next != tail.nextFull) Modifier.semantics { contentDescription = tail.nextFull } else Modifier),
                    )
                }
            } else if (stub != null) {
                ShortenedText(start, startStyle, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (end.isNotEmpty()) {
                // Read in full however it's drawn: a dropped "est." still tells a screen reader it's an
                // estimate, as a shortened place reads in full (Codex, #526).
                val full = ends.first()
                Text(
                    end,
                    style = style,
                    modifier = Modifier.widthIn(max = endMax)
                        .then(endTag?.let { Modifier.testTag(it) } ?: Modifier)
                        .then(if (end != full) Modifier.semantics { contentDescription = full } else Modifier),
                )
            }
        }
    }
}

/**
 * When the step at hand is done, where that's known and stands ([current]) and is still ahead of
 * [now]: null otherwise, as [OnTheWay.eta] drops a time already passed, so the step says its own
 * words rather than a clock time gone by.
 */
internal fun stepTime(progress: TripProgress?, current: Boolean, now: Instant): Instant? = when (progress) {
    is TripProgress.Walking -> progress.until
    is TripProgress.Changing -> progress.until
    is TripProgress.Waiting -> progress.due?.takeIf { current }
    // Seen on board, or told to get off either way (as the step says it, [nextStepText]); not while
    // the step is still to take the train, which has no time of its own.
    is TripProgress.Riding -> progress.getOffAt?.takeIf { current && (progress.seen || progress.getOffSoon) }
    else -> null
}?.takeIf { it.isAfter(now) }

/**
 * When the step at hand is done, [at], as the trip's own time is said: the minutes until it, counted
 * as the boards count, then the clock time (maintainer, 2026-10-03).
 */
@Composable
private fun stepTimeText(at: Instant, now: Instant): String {
    LocalConfiguration.current // Read again on a configuration change (locale, font scale).
    return stepTimeText(LocalContext.current.resources, at, now)
}

/** [stepTimeText] from [resources], for the notification and the watch. */
internal fun stepTimeText(resources: Resources, at: Instant, now: Instant): String =
    resources.getString(R.string.on_the_way_step_time, CLOCK.format(at.atZone(LONDON)), Countdown.minutes(at, now).toInt())

/** The next step's card colors: "get off soon" stands out, the one step with a deadline a stop away. */
@Composable
internal fun nextStepColors(progress: TripProgress?, current: Boolean = true): CardColors =
    if (progress is TripProgress.Riding && progress.getOffSoon && (current || !fromTfl(progress))) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
    }

/**
 * The step's title in the forms the card tries, longest first ([ShortenedLines]): as named, its places
 * with common words shortened, then at their floor ([DestinationAbbreviations]), so a long one shortens
 * before it's cut, as a board's place does (maintainer, 2026-10-03).
 */
@Composable
private fun stepTitleForms(progress: TripProgress?, now: Instant, current: Boolean): List<String> {
    LocalConfiguration.current // Read again on a configuration change (locale, font scale).
    return stepTitleForms(LocalContext.current.resources, progress, now, current)
}

/** [stepTitleForms] from [resources], for a worker ([OnTheWayBanner]). */
internal fun stepTitleForms(resources: Resources, progress: TripProgress?, now: Instant, current: Boolean): List<String> =
    listOf<(String) -> String>({ it }, DestinationAbbreviations::abbreviate, DestinationAbbreviations::floor)
        .map { nextStepText(resources, progress, now, current, it).first }

/** "To" the trip's [destination], shortened as a board shortens a place (its words, then its floor), the "To" kept whole. */
@Composable
private fun destinationTitleForms(destination: String): List<String> {
    LocalConfiguration.current // Read again on a configuration change (locale, font scale).
    val resources = LocalContext.current.resources
    return remember(destination, resources) { destinationTitleForms(resources, destination) }
}

/** [destinationTitleForms] from [resources], for a worker ([OnTheWayBanner]). */
internal fun destinationTitleForms(resources: Resources, destination: String): List<String> =
    DestinationAbbreviations.forms(destination).map { resources.getString(R.string.on_the_way_title, it) }

/** What the rider does next, as a title and a detail line — the trip's screen and its banner alike. */
@Composable
internal fun nextStepText(progress: TripProgress?, now: Instant, current: Boolean = true, asOf: Instant? = null): Pair<String, String> {
    LocalConfiguration.current // Read again on a configuration change (locale, font scale).
    return nextStepText(LocalContext.current.resources, progress, now, current, asOf = asOf)
}

/**
 * [nextStepText] from [resources], for the trip's ongoing notification too. [place] is applied to
 * each place the title names, so the card can shorten the places alone ([stepTitleForms]), never the
 * words around them or a line's name.
 */
internal fun nextStepText(
    resources: Resources,
    progress: TripProgress?,
    now: Instant,
    current: Boolean = true,
    place: (String) -> String = { it },
    // When the step's last answer was had ([ActiveTripTracker.answeredAt]): while [current] is false its
    // time still shows beside "Checking…"; null says "Updating…".
    asOf: Instant? = null,
): Pair<String, String> {
    // A train's time or stops from an answer too old to stand behind ([current]): the step stays, its
    // stops say "Checking…" while the last answer's time left stays beside them (maintainer, 2026-10-06),
    // or "Updating…" where the step has no answer of its own.
    // Nor "Get off at": the stop being next is what that answer said, and it no longer stands, so the
    // step is the ride until the next one says (Codex, PR #456).
    // Seen on board or not: once told to get off, it doesn't go back to boarding.
    if (fromTfl(progress) && !current) {
        val title = (progress as? TripProgress.Riding)?.takeIf { it.getOffSoon }?.let { resources.getString(R.string.on_the_way_ride_to, place(it.leg.toName)) }
            ?: nextStepText(resources, progress, now, place = place).first
        if (asOf == null) return title to resources.getString(R.string.on_the_way_updating)
        // A time gone by is dropped ([stepTime]): "Checking…" alone, never a train taken as not found.
        val at = stepTime(progress, current = true, now)
        return title to (at?.let { resources.getString(R.string.on_the_way_checking_time, stepTimeText(resources, it, now)) } ?: resources.getString(R.string.on_the_way_checking))
    }
    return when (progress) {
        // The line of the train followed, which can be another of the ride's lines than the Planner's.
        is TripProgress.Waiting -> resources.getString(R.string.on_the_way_board, progress.lineName, place(progress.leg.fromName)) to
            // As the boards count it ([Countdown.minutes]): the train due here is often on the board
            // below, and the two must never read a minute apart.
            (progress.due?.let { resources.getString(R.string.on_the_way_due, Countdown.minutes(it, now).toInt()) } ?: resources.getString(finding(progress.leg)))
        // Only taken to be on board, their train having left (maintainer, 2026-09-29): still on the
        // platform looks the same underground, so the step is still the ride, its board below, until
        // location or their word says they're on. A stop or two from getting off, it says so all the same.
        is TripProgress.Riding if !progress.seen && !progress.getOffSoon ->
            resources.getString(Vehicle.of(progress.leg).take, place(progress.leg.toName)) to ""
        // "Ride to" for the ride, "Get off at" once the stop is next, a moment to act on, as the
        // get-off-soon alert says; the step's own row stays "Ride to" (maintainer, 2026-10-01).
        is TripProgress.Riding -> resources.getString(if (progress.getOffSoon) R.string.get_off_soon_title else R.string.on_the_way_ride_to, place(progress.leg.toName)) to
            // The time left on the ride, where the stop is predicted (maintainer, 2026-09-29): counted as
            // the boards count, never estimated from the plan beyond TfL's predictions.
            rideStopsText(resources, progress, progress.getOffAt?.let { Countdown.minutes(it, now).toInt() })
        is TripProgress.Changing -> resources.getString(R.string.on_the_way_change, progress.leg.lineName, place(progress.leg.fromName)) to
            resources.getString(R.string.on_the_way_change_time, minutesUntil(now, progress.until))
        // The walk to the destination can run past its time ([OnTheWay.walksToEnd]): no "About 0 min" then.
        is TripProgress.Walking -> resources.getString(R.string.on_the_way_walk, place(progress.leg.toName)) to
            (if (progress.until.isAfter(now)) resources.getString(R.string.on_the_way_walk_time, minutesUntil(now, progress.until)) else "")
        is TripProgress.Lost -> resources.getString(Vehicle.of(progress.leg).lost) to resources.getString(finding(progress.leg))
        TripProgress.Arrived -> resources.getString(R.string.on_the_way_arrived) to ""
        null -> resources.getString(R.string.on_the_way) to resources.getString(R.string.on_the_way_finding)
    }
}

/**
 * What the rider is looking for on a leg, by its mode's own name (maintainer, 2026-09-28): a bus, a
 * coach, a tram, a boat, a cable car; a train on every rail mode, and on one this doesn't know.
 */
private enum class Vehicle(val finding: Int, val lost: Int, val take: Int) {
    TRAIN(R.string.on_the_way_finding, R.string.on_the_way_lost, R.string.on_the_way_take),
    BUS(R.string.on_the_way_finding_bus, R.string.on_the_way_lost_bus, R.string.on_the_way_take_bus),
    COACH(R.string.on_the_way_finding_coach, R.string.on_the_way_lost_coach, R.string.on_the_way_take_coach),
    TRAM(R.string.on_the_way_finding_tram, R.string.on_the_way_lost_tram, R.string.on_the_way_take_tram),
    BOAT(R.string.on_the_way_finding_boat, R.string.on_the_way_lost_boat, R.string.on_the_way_take_boat),
    CABLE_CAR(R.string.on_the_way_finding_cable_car, R.string.on_the_way_lost_cable_car, R.string.on_the_way_take_cable_car),
    ;

    companion object {
        fun of(leg: TripLeg): Vehicle = when (leg.mode.lowercase()) {
            "bus", "replacement-bus" -> BUS
            "coach" -> COACH
            "tram" -> TRAM
            "river-bus", "river-tour" -> BOAT
            "cable-car" -> CABLE_CAR
            else -> TRAIN
        }
    }
}

private fun finding(leg: TripLeg) = Vehicle.of(leg).finding

/**
 * One leg of the route: its line and ends, the leg the rider is on in bold, done legs muted. A walk
 * shows a walker in the room the [rides]' pills take, so its text lines up with theirs, as on a trip
 * card. A ride is one row, its boarding step's, though getting off it is a step of its own.
 */
@Composable
private fun LegLine(leg: TripLeg, rides: List<TripLeg>, current: Boolean, done: Boolean, onTap: (() -> Unit)? = null) {
    val color = stepColor(done)
    StepLine(
        slot = {
            if (leg.isWalk) {
                // The route's pills, unseen, give the slot its width in the same pass.
                Box(contentAlignment = Alignment.Center) {
                    rides.forEach { LinePill(it.lineName, it.lineId, it.mode, Modifier.alpha(0f).clearAndSetSemantics {}) }
                    Icon(
                        painter = painterResource(R.drawable.ic_walk),
                        contentDescription = stringResource(R.string.on_the_way_walk_leg),
                        tint = color,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                LinePill(leg.lineName, leg.lineId, leg.mode)
            }
        },
        // The walk from where the rider set off names only where it goes.
        text = if (leg.fromName.isBlank()) stringResource(R.string.on_the_way_walk, leg.toName) else stringResource(R.string.on_the_way_leg, leg.fromName, leg.toName),
        current = current,
        color = color,
        onTap = onTap,
    )
}

/**
 * One thing wrong on the route ahead ([RouteDisruption.Signal]): headed as its alert is
 * ([RouteDisruptionAlert.text]), then TfL's own words for a line's alert (where it's diverted or
 * shut, which stops), then the ride it's on as the rider takes it ([leg], [RouteDisruption.rideAt]). High in the error
 * tone, the rest muted.
 */
@Composable
private fun DisruptionCard(signal: RouteDisruption.Signal, leg: TripLeg?) {
    val context = LocalContext.current
    val colors = if (signal.tier == RouteDisruption.Tier.HIGH) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth().testTag("onTheWayDisruption")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(RouteDisruptionAlert.text(context, signal), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            (signal as? RouteDisruption.Signal.Line)?.status?.shownText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                LinkedText(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (leg != null && !leg.isWalk) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The ride as the rider takes it ([RouteDisruption.rideAt]), as the signal was found:
                    // another of the ride's lines' train followed, its line and its own stops (Codex, PR #453).
                    LinePill(leg.lineName, leg.lineId, leg.mode)
                    Text(stringResource(R.string.on_the_way_leg, leg.fromName, leg.toName), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * A coming station's notice that neither closes nor moves it ([RouteDisruption.StationNote]): headed by
 * the station, then TfL's words. Muted, as a medium alert's card, and never sounded. Its × ([onDismiss],
 * null offers none) lets it go for the trip, as a closure card's does on the list.
 */
@Composable
private fun StationNoteCard(note: RouteDisruption.StationNote, onDismiss: (() -> Unit)?) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(colors = colors, modifier = Modifier.fillMaxWidth().testTag("onTheWayStationNote")) {
        // The × sits in the top corner, 4dp in, so its 48dp target doesn't push the text down; the
        // heading leaves room for it.
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    note.stopName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = if (onDismiss != null) Modifier.padding(end = 40.dp) else Modifier,
                )
                LinkedText(note.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).testTag("onTheWayStationNoteDismiss")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.alert_dismiss))
                }
            }
        }
    }
}

// A done step reads muted; the rest as ordinary text.
@Composable
private fun stepColor(done: Boolean) = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

// A step's row: [slot] in the pills' column, then [text], the step the rider is at in bold. Tapped, a
// step other than theirs puts them there, as if they'd got there.
@Composable
private fun StepLine(slot: @Composable () -> Unit, text: String, current: Boolean, color: androidx.compose.ui.graphics.Color, onTap: (() -> Unit)?) {
    val tap = if (onTap == null) Modifier else {
        Modifier.clickable(onClickLabel = stringResource(R.string.on_the_way_go_here), onClick = onTap)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(tap),
    ) {
        slot()
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Whole minutes from [now] to [at], rounded up and never below zero: "0 min" is due now.
internal fun minutesUntil(now: Instant, at: Instant): Int {
    val seconds = Duration.between(now, at).seconds.coerceAtLeast(0)
    return ((seconds + 59) / 60).toInt()
}

/**
 * A ride's stops left, beside its time on the card: the count alone, "Next stop" when it's next, or the
 * next stop's name when the stops can't be counted (maintainer, 2026-10-03).
 */
internal fun rideStopsLeft(resources: Resources, progress: TripProgress.Riding): String =
    when (val left = progress.stopsLeft) {
        null -> progress.nextStop?.let { resources.getString(R.string.on_the_way_next_is, it) } ?: ""
        0, 1 -> resources.getString(R.string.on_the_way_next_stop)
        else -> resources.getQuantityString(R.plurals.on_the_way_stops_left, left, left)
    }

/**
 * A ride's stops left ([count], empty where they aren't counted) and its next stop in the forms the card tries,
 * longest first: [nextFull] as named, [nextShort] its common words shortened, [nextFloor] at its shortest; and
 * [nextStub], the least of it the card shows rather than leave it out.
 */
internal data class RideStops(
    val count: String,
    val nextFull: String,
    val nextShort: String,
    val nextFloor: String,
    val nextStub: String,
)

/**
 * [progress]'s stops left and its next stop for the card's row, where the next is named and there are two or
 * more stops left, or they aren't counted: the stop in the forms [TripProgress.Riding] has ([TripProgress.Riding.nextStopShort], [TripProgress.Riding.nextStopFloor]), worked out
 * off the main thread, so this is lookups alone. Null otherwise: "Next stop", or the count alone (maintainer,
 * 2026-10-07).
 */
internal fun rideStopsSplit(resources: Resources, progress: TripProgress.Riding): RideStops? {
    val next = progress.nextStop ?: return null
    val short = progress.nextStopShort ?: return null
    val floor = progress.nextStopFloor ?: return null
    // Stops left not counted (a bus beyond its predictions): "Next: …" alone, the stop shortened as it must be
    // all the same (Codex, #655).
    if (progress.stopsLeft == null) {
        return RideStops(
            "",
            resources.getString(R.string.on_the_way_next_is, next),
            resources.getString(R.string.on_the_way_next_is, short),
            resources.getString(R.string.on_the_way_next_is, floor),
            resources.getString(R.string.on_the_way_next_is, next.take(1) + "…"),
        )
    }
    val left = progress.stopsLeft?.takeIf { it >= 2 } ?: return null
    return RideStops(
        resources.getQuantityString(R.plurals.on_the_way_stops_left, left, left),
        resources.getString(R.string.on_the_way_next_tail, next),
        resources.getString(R.string.on_the_way_next_tail, short),
        resources.getString(R.string.on_the_way_next_tail, floor),
        // The least of it worth showing: the stop's first letter, then "…".
        resources.getString(R.string.on_the_way_next_tail, next.take(1) + "…"),
    )
}

/**
 * A ride's stops left and its next stop, with [minutes] to getting off where they're said here rather
 * than on a time row of their own.
 */
internal fun rideStopsText(resources: Resources, progress: TripProgress.Riding, minutes: Int?): String =
    when (val left = progress.stopsLeft) {
        null -> progress.nextStop?.let { resources.getString(R.string.on_the_way_next_is, it) } ?: ""
        0, 1 -> minutes?.let { resources.getString(R.string.on_the_way_next_stop_timed, it) } ?: resources.getString(R.string.on_the_way_next_stop)
        else -> stopsText(resources, left, progress.nextStop, minutes)
    }

/**
 * [left] stops to where the rider gets off, [minutes] from it when its time is predicted, and the
 * [next] stop when its name is known; one not named is left out rather than guessed (Codex, PR #449).
 */
internal fun stopsText(resources: Resources, left: Int, next: String?, minutes: Int?): String = when {
    next != null && minutes != null -> resources.getQuantityString(R.plurals.on_the_way_stops_timed, left, left, next, minutes)
    next != null -> resources.getQuantityString(R.plurals.on_the_way_stops, left, left, next)
    minutes != null -> resources.getQuantityString(R.plurals.on_the_way_stops_left_timed, left, left, minutes)
    else -> resources.getQuantityString(R.plurals.on_the_way_stops_left, left, left)
}

/**
 * Whether [progress]'s details stand on an answer of TfL's (a train's time or calls), so they wait while
 * that answer is too old to stand behind. Stops counted from where the rider was seen don't: they show
 * from the first frame after a restart, as they were (Codex, PR #449).
 */
internal fun fromTfl(progress: TripProgress?): Boolean =
    (progress is TripProgress.Riding && !progress.byPosition) || (progress is TripProgress.Waiting && progress.due != null)

/**
 * [on]'s stops ([rideStopList]) for the open list, worked out on [LocalWorker] in one step with any names the Planner
 * left out, from the line's route through the route cache the trip's other checks share (at most two TfL requests per
 * line a day, or none, and only when a stop is unlisted or unnamed). So only a finished list is shown: never one that grows once names
 * land, nor one named from another line's route (Codex, #968). The last list stands in while a refresh's is worked
 * out for the same ride, so the open rows never blink. A route that couldn't be read says why ([StopsShown.failure])
 * and isn't asked for again until the list is opened again, so an outage or a rate limit costs no request each
 * refresh (Codex, #968). Null while closed, or until the ride's first list is in.
 */
@Composable
private fun rememberRideStops(on: TripLeg?, stepFree: StepFree, progress: TripProgress?, boarded: Boolean, open: Boolean, stale: Boolean): StopsShown? {
    val repository = LocalRouteStops.current
    val worker = LocalWorker.current
    var shown by remember { mutableStateOf<Pair<TripLeg, StopsShown>?>(null) }
    // Each line's route the open list has needed (its stops unlisted or unnamed), as read: read once per line while the
    // list is open, each in its own job, so neither the progress refreshes that move the dot nor the followed line
    // changing cancels a read under way, and a line's read isn't asked for again, failed or not; reopening the list
    // asks again. Held by a view model, so a read under way, and a failure, outlive a recreation, and its maps are keyed
    // snapshot state, so nothing on the main thread copies or walks them (Codex, #968).
    val reads: RideStopsReads = viewModel(key = "ride-stops")
    fun readRoute(line: String) {
        // This open list's own, so a read finishing after it closes writes nowhere shown.
        val session = reads.session
        if (repository == null || line in session.done || line in session.reading) return
        session.failed[line]?.let {
            session.done[line] = RouteRead(line, null, emptyMap(), it)
            return
        }
        session.reading[line] = reads.viewModelScope.launch(session.job) {
            val read = try {
                val sequence = repository.load(line, "")
                RouteRead(line, sequence, withContext(worker) { lineStopNames(sequence) }, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                // TfL has no route for the line (already logged, sanitized, by the repository): the list shows what it
                // can, or says it's unavailable.
                RouteRead(line, null, emptyMap(), null)
            } catch (e: TflException) {
                // Said under the list, with why (offline, rate-limited, TfL unreachable), not as no route.
                val kind = errorKindOf(e)
                session.failed[line] = kind
                RouteRead(line, null, emptyMap(), kind)
            }
            session.reading.remove(line)
            session.done[line] = read
        }
    }
    val routeRead = on?.lineId?.let { reads.session.done[it] }
    // A held list's ride and one found, off the main thread, to run the same stops as it: a copy whose path is
    // another list of the same names. Kept as the pair, so it vouches for that held list alone (Codex, #968).
    var samePath by remember { mutableStateOf<Pair<TripLeg, TripLeg>?>(null) }
    // By identity: a ride's, a progress's or a route's equality walks its stops, which composition doesn't.
    LaunchedEffect(ByIdentity(on), stepFree, ByIdentity(progress), boarded, open, stale, ByIdentity(routeRead), repository, worker) {
        if (on == null || !open) {
            shown = null
            reads.close(worker)
            return@LaunchedEffect
        }
        val held = shown?.first
        if (held != null && held !== on && !sameStops(held, on) && sameRide(held, on)) {
            if (withContext(worker) { held.path == on.path && held.pathNames == on.pathNames }) samePath = held to on
        }
        // The line's route as read for this ride, when there's one to read; asked for and waited on (the last list, or
        // "Loading stops", stays meanwhile) when it isn't in yet.
        val readable = repository != null && on.lineId.isNotBlank()
        val read = routeRead
        // Whether this ride's list needed the route: only then is a failure to read it this list's to say (Codex, #968).
        var needed = false
        // A ride the Planner listed no stops for (a third of the recorded plans' rides) takes them from its line's
        // route, from where it boards to where it gets off (Codex, #968).
        if (on.path.isEmpty() && readable) {
            if (read == null) {
                readRoute(on.lineId)
                return@LaunchedEffect
            }
            needed = true
        }
        val sequence = read?.sequence?.takeIf { needed }
        // Where it gets off is [on]'s own end ([LegEnd.planned]), the ride as the trip now has it: once the end moves into
        // the destination's hub ([OnTheWay.arrivingEnd]), or another line takes it to another station there, the list
        // ends there too. Worked out on the worker too.
        val ride = sequence?.let { seq -> withContext(worker) { withRouteStops(on, LegEnd.planned(on, stepFree), seq) } } ?: on
        var list = withContext(worker) { rideStopList(ride, progress, boarded, if (needed) read!!.names else emptyMap(), stale) }
        if (list?.missing == true && !needed) {
            if (readable && read == null) {
                readRoute(on.lineId)
                return@LaunchedEffect
            }
            needed = true
            list = withContext(worker) { rideStopList(ride, progress, boarded, read?.names.orEmpty(), stale) }
        }
        // What a tapped stop's details are headed by, worked out here so the tap only reads it.
        val head = withContext(worker) { rideStopHead(ride, list) }
        // None to show: the screen says the list is unavailable, or why it couldn't be read, rather than leave the
        // opened space blank.
        shown = on to StopsShown(list ?: RideStopList(emptyList(), null), read?.failure?.takeIf { needed }, head)
    }
    // A list held from an earlier answer drops its stop at once, not when it's worked out again, where the answer now is
    // stale (a TfL-placed stop) or places no one (Codex, #968).
    // Held only while the ride runs the same stops: a train found taking another branch between the same two stops is
    // still the same ride by [sameRide], but not the same list (Codex, #968).
    return shown?.takeIf { open && on != null && sameRide(it.first, on) && (sameStops(it.first, on) || samePath?.let { (a, b) -> a === it.first && b === on } == true) }
        ?.second?.let { StopsShown(it.list.shownStops(stale, progress, boarded), it.failure, it.head) }
}

/**
 * Show stops' route reads, held above the screen's composition so a read under way and a failure outlive a recreation
 * (Codex, #968): [session] is the open list's, swapped for a fresh one when it closes.
 */
internal class RideStopsReads : ViewModel() {
    var session by mutableStateOf(Session(SupervisorJob(viewModelScope.coroutineContext[Job])))
        private set

    /** The list closed: a fresh session, the old one's reads canceled as one, off the main thread, never walked. */
    fun close(worker: CoroutineDispatcher) {
        val old = session
        if (old.reading.isEmpty() && old.done.isEmpty() && old.failed.isEmpty()) return
        session = Session(SupervisorJob(viewModelScope.coroutineContext[Job]))
        viewModelScope.launch(worker) { old.job.cancel() }
    }

    /** One open list's reads: finished ones and failures by line, those under way, and their parent job. */
    class Session(val job: Job) {
        internal val done = mutableStateMapOf<String, RouteRead>()
        internal val failed = mutableStateMapOf<String, DeparturesUiState.Error.Kind>()
        internal val reading = HashMap<String, Job>()
    }
}

/** [lineId]'s route as read for Show stops, with its stops' [names] ([lineStopNames]); [failure] where it couldn't be. */
internal class RouteRead(val lineId: String, val sequence: LineSequence?, val names: Map<String, String>, val failure: DeparturesUiState.Error.Kind?)

/** A ride's stops as Show stops lists them, and why its line's route couldn't be read, where it couldn't. */
private class StopsShown(val list: RideStopList, val failure: DeparturesUiState.Error.Kind?, val head: RideStopHead)

// Whether [a] and [b] run the same stops, by their lists' identity alone: a refresh copies a ride, keeping its lists.
private fun sameStops(a: TripLeg, b: TripLeg): Boolean = a.path === b.path && a.pathNames === b.pathNames

/**
 * What the details of a stop tapped under Show stops are headed by ([rideStopOpen]): the ride's [line], where it's
 * boarded ([fromName]) and, where the Planner names one place, where it heads ([towards]); and each listed stop's
 * position where the Planner gave it (its ends'), beside [RideStopList.ids].
 */
internal class RideStopHead(val line: LineRef, val fromName: String, val towards: String, val positions: List<Coordinates?>)

/** [ride]'s [RideStopHead] for its listed [stops]. Cleans the heading's name: off the main thread. */
@WorkerThread
internal fun rideStopHead(ride: TripLeg, stops: RideStopList?): RideStopHead = RideStopHead(
    line = LineRef(ride.lineId, ride.lineName.ifBlank { ride.lineId }, ride.mode),
    fromName = ride.fromName,
    towards = ride.headings.singleOrNull()?.let { DepartureLabels.destinationLabel(it, "") }.orEmpty(),
    positions = stops?.ids.orEmpty().mapIndexed { i, id -> if (i == 0) ride.fromAt else ride.toAt?.takeIf { id == ride.toId } },
)

/**
 * The details a stop tapped under Show stops opens, as a route page's stop's do: its departures, lines and the stations
 * around it, headed by [head]. No Go nor journey to star: the trip is already on its way. [index] is the stop's place in
 * the list, where it boards first. Reads only what's worked out already: a tap's work.
 */
internal fun rideStopOpen(head: RideStopHead, id: String, name: String, index: Int): RouteStopOpen = RouteStopOpen(
    stationId = id,
    name = name,
    journey = null,
    stopId = id,
    line = head.line,
    fromName = head.fromName,
    towards = head.towards,
    position = head.positions.getOrNull(index),
    boarding = index == 0,
    ridden = index > 0,
)

/** A line under Show stops in place of the ride's stops: why they aren't there yet, or at all. */
@Composable
private fun RideStopsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.pullUp(12.dp).padding(start = 12.dp),
    )
}

/**
 * Under the ride the rider is on or heading for, **Show stops** (maintainer, 2026-10-18): opens its stops, each a
 * row of the screen's list ([RideStopRow]) so a long bus route composes only what's on screen (Codex, #968).
 * Collapsed by default, so the route's legs stay one row each.
 */
@Composable
private fun ShowStopsButton(open: Boolean, onToggle: () -> Unit) {
    // Tucked under the leg's row it opens, into the list's gap, with 8dp of its own room below trimmed: a 40dp touch
    // target, so it reaches neither the leg's row above it nor what's below (maintainer, 2026-10-18, over a 48dp one
    // overlapping the row's tap area; Codex, #968).
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        TextButton(
            onClick = onToggle,
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.pullUp(12.dp, below = 8.dp).height(40.dp).testTag("onTheWayShowStops"),
        ) {
            // 24dp, so its middle sits over the rail of the stops it opens ([RideStopRow]).
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(stringResource(if (open) R.string.on_the_way_hide_stops else R.string.on_the_way_show_stops), modifier = Modifier.padding(start = 8.dp))
        }
    }
}
