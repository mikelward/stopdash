package app.stopdash.ui

import android.content.res.Resources
import androidx.annotation.WorkerThread
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.LineSequence
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.StopDistance
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
import app.stopdash.domain.OffPlan
import app.stopdash.domain.Staleness
import app.stopdash.domain.cleanStopName
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.withContext

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
    // The rider read [disruptions] and keeps going ([ActiveTripTracker.dismissDisruptions], maintainer
    // 2026-10-03), as shown. Null leaves Keep going out.
    onDismissDisruptions: ((List<RouteDisruption.Signal>) -> Unit)? = null,
    // The coming stations' other notices ([ActiveTripTracker.stationNotes]): a lift or an escalator out,
    // an exit shut. Shown, never alerted (maintainer, 2026-10-04); worked out off the main thread.
    notes: List<RouteDisruption.StationNote> = emptyList(),
    // The rider reroutes onto a branch on the board that leaves the plan ([ActiveTripTracker.take], maintainer
    // 2026-10-05). Null offers none.
    onTake: ((TripLeg, OffPlan.Branch) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val destination = trip?.destinationName
    Scaffold(
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
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).testTag("onTheWay"),
        ) {
            // Time left and when they get there (maintainer, 2026-10-01). Not from an answer too old to
            // stand behind ([current]): it waits, as the step's own times do.
            // The board's next train only for the ride the rider is still to board: another ride's board
            // times nothing here.
            val nextDue = nextTrains?.takeIf { it.ride == (progress as? TripProgress.Waiting)?.leg ?: (progress as? TripProgress.Lost)?.leg }?.nextDue
            val eta = trip?.let { t -> etaTail?.let { OnTheWay.etaFrom(t, progress, now, it, nextDue) } }
            val stale = !current && fromTfl(progress)
            // The card leads with the whole trip, then the step at hand (maintainer, 2026-10-03). The next
            // ride's trains sit right under it, the board the rider is heading for, before the route, and
            // close enough to read as the card's own (4dp, as the board's rows; maintainer, 2026-10-03).
            item(key = "next") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NextStep(destination, eta?.takeIf { !stale }, progress, now, current)
                    if (nextTrains != null && trip != null) NextTrainsSection(nextTrains, now, onTake)
                }
            }
            if (trip != null) {
                // Each thing known once, as the alert has it: two legs on one line read as one ([cards]).
                // Registered by count, not walked here: each card is read only as it's drawn (Codex on #519).
                items(cards, key = { signal -> "disruption/${signal.key}" }) { signal ->
                    DisruptionCard(signal, trip.route.legs.getOrNull(signal.legIndex)?.let { RouteDisruption.rideAt(trip, signal.legIndex, it) })
                }
                // Only while it's still ahead of the trip as shown: worked out by the last check, it can lag
                // a step the trip has since taken, and a stop now behind the rider is never offered (Codex on #479).
                val planFrom = replanFrom?.takeIf { it.id in ReplanOrigin.stopsAhead(trip, ReplanOrigin.rideAhead(trip, progress)) }
                val planAgain = planFrom?.takeIf { onPlanAgain != null }
                if (disruptions.isNotEmpty() && (planAgain != null || onDismissDisruptions != null)) {
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
                items(notes, key = { note -> "note/${note.legIndex}/${note.stopId}" }) { note -> StationNoteCard(note) }
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
 * whole answer; [stale] when the last board is too old to stand behind (D4), when its times give way
 * to "Updating…"; [failed] when the last update couldn't reach TfL, said beside whatever is shown.
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
    // The board's trains that leave the plan ([OffPlan], maintainer 2026-10-05), a row per heading,
    // worked out on the worker with the rest: grayed under the plan's own, each one the rider can take.
    val offPlan: List<OffPlanRow> = emptyList(),
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
            "${branch.forkIndex}|${branch.label}", ride.lineId, ride.lineName, ride.mode, branch.label, branch,
            branch.trains.take(MAX_OFF_PLAN_TIMES),
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
    // The board's trains are worked out on the worker, from each instant one departs ([trainsTimeline]),
    // keyed by the board itself (a new one each read) and its routes, so composition compares no
    // departures and only picks the entry for now (AGENTS.md *Main thread: read and dispatch only*).
    // Only once its own lines are read: the last board's may leave out a line it brings back, already
    // loaded, so its routes would be missed with no new load to work the board out again (Codex on #557).
    val key = board?.takeIf { it.fetchedAt != null && lineIds.board === it && sameRide(it.ride, ride) }?.let { TrainsKey(it, loads.version) }
    var held by remember { mutableStateOf<HeldTrains?>(null) }
    LaunchedEffect(key, worker) {
        key ?: return@LaunchedEffect
        if (held?.key == key) return@LaunchedEffect
        val at = now
        val timeline = withContext(worker) { trainsTimeline(key.board, currentSequences, at) }
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
    LaunchedEffect(shown, lapsed, rewound, worker) {
        if (!lapsed && !rewound) return@LaunchedEffect
        val at = now
        val rebuilt = timeline.startsAfter(0, at)
        val (index, window) = withContext(worker) {
            val from = if (rebuilt) trainsTimeline(shown.key.board, currentSequences, at) else timeline
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
    val next = timeline.entry(if (lapsed) shown.index + 1 else shown.index)
        ?: return NextTrains(board.ride, emptyList(), pending = true, failed = failed, readyAt = readyAt)
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
// compares no departures, and its routes' [LineLoads.version].
private class TrainsKey(val board: ActiveTripTracker.NextBoard, val routes: Int) {
    override fun equals(other: Any?): Boolean = other is TrainsKey && other.board === board && other.routes == routes
    override fun hashCode(): Int = System.identityHashCode(board) * 31 + routes
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
    private val groups: Map<Int, List<StopGroup>> = emptyMap(),
) {
    /** Entry [index] (or the last, once past it) with its rows, or null if they aren't worked out. */
    fun entry(index: Int): NextTrains? {
        val at = index.coerceAtMost(entries.lastIndex)
        return groups[at]?.let { entries[at].copy(groups = it) }
    }

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
            groups[i] ?: nextTrainsGroups(entries[i], starts[i])
        }
        return TrainsTimeline(starts, entries, misses, window)
    }

    /** The entry in force at [now] ([indexAt]), with its rows. */
    @WorkerThread
    fun at(now: Instant): NextTrains {
        val index = indexAt(now)
        return entries[index].withGroups(starts[index])
    }
}

/**
 * [rememberNextTrains]' work, off the main thread: [board]'s trains as [nextTrainsAt] keeps them, from
 * [at] and again from each instant the list shown changes (a train kept, loading or unplaced goes).
 * Each pole's trains are routed once ([OnTheWay.placeTrains]), and each entry taken from them, so a
 * busy board isn't routed again for every departure (Codex on #557).
 */
@WorkerThread
internal fun trainsTimeline(board: ActiveTripTracker.NextBoard, sequences: Map<String, LineSequence?>, at: Instant): TrainsTimeline {
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
        nextTrainsAt(board, own.trainsAt(instant), others.map { (other, placed) -> other to placed.trainsAt(instant) })
            .copy(offPlan = offPlanRows(board.ride, offPlan.map { branch -> branch.copy(trains = branch.trains.filter { !Countdown.hasDeparted(it, instant) }) }))
    }
    // Misses are reported once per board, so only the first instant's are gathered.
    return TrainsTimeline(instants, entries, own.missesAt(at) + others.flatMap { it.second.missesAt(at) }).around(0)
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
): NextTrains = NextTrains(
    board.ride, found.trains,
    pending = found.pending || others.any { it.second.pending },
    unresolved = found.unresolved || others.any { it.second.unresolved },
    // A pole of the pair left unread is said too: a train there went unseen.
    failed = board.failed || board.partial,
    fetchedAt = board.fetchedAt, stopLetter = board.pole?.stopLetter.orEmpty(), towards = board.pole?.towards.orEmpty(),
    bearing = board.pole?.bearing.orEmpty(),
    others = others.map { (other, trains) ->
        PoleTrains(other.pole.id, other.pole.name.ifBlank { board.ride.fromName }, trains.trains, other.pole.stopLetter, other.pole.towards, other.pole.bearing)
    },
)

/** These trains with their [NextTrains.groups] worked out at [now] ([nextTrainsGroups]). */
@WorkerThread
internal fun NextTrains.withGroups(now: Instant): NextTrains = copy(groups = nextTrainsGroups(this, now))

/** [next]'s trains as the board draws them at [now]: the ride's own pole first, then the pair's others, each its own header ("Stop N"). */
@WorkerThread
internal fun nextTrainsGroups(next: NextTrains, now: Instant): List<StopGroup> {
    val fetchedAt = next.fetchedAt ?: SteadyClock.stamp(now)
    return StopGrouping.groupByStop(
        DepartureRows.forStop(
            next.ride.fromId, next.ride.fromName, next.trains, now, fetchedAt = fetchedAt,
            stopLetter = next.stopLetter, towards = next.towards, bearing = next.bearing,
        ) + next.others.flatMap { pole ->
            DepartureRows.forStop(
                pole.stopId, pole.stopName, pole.trains, now, fetchedAt = fetchedAt,
                stopLetter = pole.stopLetter, towards = pole.towards, bearing = pole.bearing,
            )
        },
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
 * A time due before the rider can be there is grayed. Nothing to open from here: a row takes no tap
 * and announces no action.
 */
@Composable
private fun NextTrainsSection(
    next: NextTrains,
    now: Instant,
    // The rider takes a train that leaves the plan ([OffPlanCard]); null offers none.
    onTake: ((TripLeg, OffPlan.Branch) -> Unit)? = null,
) {
    val groups = next.groups
    Column(Modifier.fillMaxWidth().testTag("onTheWayTrains"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A failed update is said whatever else shows: the rows may be the last good board's.
        if (next.failed) NoteText(stringResource(R.string.on_the_way_failed))
        if (next.stale) {
            if (!next.failed) NoteText(stringResource(R.string.on_the_way_updating))
            return@Column
        }
        if (next.failed && next.none) return@Column
        groups.forEach { group ->
            StopGroupHeader(group.stopName, group.qualifier, distanceLabel = null, firstOnScreen = false)
            StopGroupCard(
                group,
                now,
                starred = emptySet(),
                onToggleStar = {},
                starringAvailable = false,
                onOpenDetail = null,
                grayBefore = next.readyAt,
            )
        }
        // The trains that leave the plan, under the plan's own (maintainer, 2026-10-05).
        if (next.offPlan.isNotEmpty()) OffPlanCard(next.offPlan, next.ride, now, onTake)
        // What the rows may be missing, said rather than left to be taken as the whole answer.
        when {
            next.pending && groups.isEmpty() -> NoteText(stringResource(R.string.on_the_way_trains_loading))
            next.pending -> NoteText(stringResource(R.string.on_the_way_trains_checking))
            next.unresolved -> NoteText(stringResource(R.string.on_the_way_trains_unchecked))
            groups.isEmpty() -> NoteText(stringResource(R.string.on_the_way_trains_none, next.ride.toName))
        }
    }
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
) {
    // The row opened, by its key: it stays open as its times come and go, and closes once it's gone.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val gray = MaterialTheme.colorScheme.outline
    val takeLabel = stringResource(R.string.on_the_way_take_this)
    // As a board's card caps its pills: half the card's inner width, so a long name can't starve the times.
    val pillMax = (LocalConfiguration.current.screenWidthDp.dp - 64.dp) * 0.5f
    OutlinedCard(Modifier.fillMaxWidth().testTag("onTheWayOffPlan")) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
) {
    val detail = nextStepText(progress, now, current).second
    val at = stepTime(progress, current, now)
    // Timed, the step's time stands in for its own words, which say the same; a ride's stops stay beside
    // it, as a walk's distance left does, where a fix has placed the rider (maintainer, 2026-10-03).
    // Not until the rider's distance units are known (null while their choice loads): never a moment in
    // the wrong ones (Codex, PR #521).
    val system = LocalDistanceSystem.current
    val walking = progress as? TripProgress.Walking
    val walkLeft = walking?.metersLeft
    val words = if (walkLeft != null && system != null) {
        // Estimated from a fix taken before the walk began, it says so until a fix on the walk places the rider.
        StopDistance.label(walkLeft, system).let { if (walking?.estimated == true) stringResource(R.string.on_the_way_walk_left_estimated, it) else it }
    } else if (at == null) {
        detail
    } else if (progress is TripProgress.Riding) {
        LocalConfiguration.current // Read again on a configuration change (locale, font scale).
        rideStopsLeft(LocalContext.current.resources, progress)
    } else {
        ""
    }
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
            if (words.isNotEmpty() || stepAt.isNotEmpty()) CardRow(listOf(words), listOf(stepAt))
        }
    }
}

// A row of the card: the first of [start]'s forms (longest first) that fits on the left, and [ends]'
// first form (a time) whole at the right. Where both don't fit, the time drops to its last form first
// (an estimate's "est."; maintainer, 2026-10-04); where they still don't (a narrow window, large text) the text yields,
// shortened before it's cut with a single "…" ([ShortenedText]); the time keeps its one line wherever
// it fits beside the text's last stub (its first word and the next letter, then "…"), and wraps only past that
// (maintainer, 2026-10-03; Codex, PR #518).
@Composable
private fun CardRow(start: List<String>, ends: List<String>, endTag: String? = null) {
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
        val stub = start.last().takeIf { it.isNotEmpty() }?.let { text ->
            val word = text.indexOf(' ').takeIf { it in 1 until text.length - 1 }?.let { text.take(it + 2) } ?: text.take(1)
            "$word…"
        }
        val stubWidth = remember(stub, startStyle, fontScale) { stub?.let { measurer.measure(it, startStyle, maxLines = 1).size.width } ?: 0 }
        // All the row but the text's stub and the gap: the time's single line where that fits, else it wraps in it.
        // A few px of slack: the stub measured alone can still elide to "…" laid out in exactly its width.
        val slack = if (stub != null) with(density) { gap.roundToPx() + 4.dp.roundToPx() } else 0
        val endMax = with(density) { (constraints.maxWidth - stubWidth - slack).coerceAtLeast(0).toDp() }
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            if (stub != null) {
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
private fun stepTimeText(at: Instant, now: Instant): String =
    stringResource(R.string.on_the_way_step_time, CLOCK.format(at.atZone(LONDON)), Countdown.minutes(at, now).toInt())

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
    listOf(destination, DestinationAbbreviations.abbreviate(destination), DestinationAbbreviations.floor(destination))
        .map { resources.getString(R.string.on_the_way_title, it) }

/** What the rider does next, as a title and a detail line — the trip's screen and its banner alike. */
@Composable
internal fun nextStepText(progress: TripProgress?, now: Instant, current: Boolean = true): Pair<String, String> {
    LocalConfiguration.current // Read again on a configuration change (locale, font scale).
    return nextStepText(LocalContext.current.resources, progress, now, current)
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
): Pair<String, String> {
    // A train's time or stops from an answer too old to stand behind ([current]): the step stays,
    // its details wait for the next answer.
    // Nor "Get off at": the stop being next is what that answer said, and it no longer stands, so the
    // step is the ride until the next one says (Codex, PR #456).
    // Seen on board or not: once told to get off, it doesn't go back to boarding.
    if (fromTfl(progress) && !current) {
        val title = (progress as? TripProgress.Riding)?.takeIf { it.getOffSoon }?.let { resources.getString(R.string.on_the_way_ride_to, place(it.leg.toName)) }
            ?: nextStepText(resources, progress, now, place = place).first
        return title to resources.getString(R.string.on_the_way_updating)
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
            (signal as? RouteDisruption.Signal.Line)?.status?.fullText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
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
 * the station, then TfL's words. Muted, as a medium alert's card, and never sounded.
 */
@Composable
private fun StationNoteCard(note: RouteDisruption.StationNote) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(colors = colors, modifier = Modifier.fillMaxWidth().testTag("onTheWayStationNote")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(note.stopName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(note.text, style = MaterialTheme.typography.bodyMedium)
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
