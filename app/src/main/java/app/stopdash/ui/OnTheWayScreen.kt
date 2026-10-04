package app.stopdash.ui

import android.content.res.Resources
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
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DestinationAbbreviations
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
    // While something is known wrong ahead, the station still ahead nearest the rider
    // ([ActiveTripTracker.replanFrom]), and the trip list from there to where they chose to go
    // ([onPlanAgain], maintainer 2026-10-02). Null leaves it out.
    replanFrom: ReplanOrigin.Stop? = null,
    onPlanAgain: ((ReplanOrigin.Stop) -> Unit)? = null,
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
            val eta = trip?.let { OnTheWay.eta(it, progress, now, nextDue) }
            val stale = !current && fromTfl(progress)
            // The card leads with the whole trip, then the step at hand (maintainer, 2026-10-03). The next
            // ride's trains sit right under it, the board the rider is heading for, before the route, and
            // close enough to read as the card's own (4dp, as the board's rows; maintainer, 2026-10-03).
            item(key = "next") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NextStep(destination, eta?.takeIf { !stale }, progress, now, current)
                    if (nextTrains != null && trip != null) NextTrainsSection(nextTrains, now)
                }
            }
            if (trip != null) {
                // Each thing known once, as the alert has it: two legs on one line read as one.
                disruptions.distinctBy { DisruptionKey.of(it) }.forEach { signal ->
                    item(key = "disruption/${signal.key}") { DisruptionCard(signal, trip.route.legs.getOrNull(signal.legIndex)?.let { RouteDisruption.rideAt(trip, signal.legIndex, it) }) }
                }
                // Only while it's still ahead of the trip as shown: worked out by the last check, it can lag
                // a step the trip has since taken, and a stop now behind the rider is never offered (Codex on #479).
                val planFrom = replanFrom?.takeIf { it.id in ReplanOrigin.stopsAhead(trip, ReplanOrigin.rideAhead(trip, progress)) }
                if (disruptions.isNotEmpty() && planFrom != null && onPlanAgain != null) {
                    item(key = "planAgain") {
                        OutlinedButton(
                            onClick = { onPlanAgain(planFrom) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onTheWayPlanAgain"),
                        ) {
                            Text(stringResource(R.string.on_the_way_plan_again, planFrom.name))
                        }
                    }
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
) {
    /** Whether no pole has a train listed: the board's own ([trains]) nor any of [others]. */
    val none: Boolean get() = trains.isEmpty() && others.all { it.trains.isEmpty() }
}

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
    val lineIds = board?.let { OnTheWay.boardLineIds(it.ride, it.boards.values.flatten()) }.orEmpty()
    val loads = rememberLineLoads(lineIds, now)
    val sequences = loads.sequences
    // No board of this ride's yet (just started, or back after a restart): its section, loading.
    if (board == null || board.ride != ride) return ride?.let { NextTrains(it, emptyList(), pending = true, readyAt = readyAt) }
    // Never read: nothing to show but that the update failed.
    val fetchedAt = board.fetchedAt ?: return NextTrains(board.ride, emptyList(), failed = board.failed, readyAt = readyAt)
    val found = OnTheWay.boardTrains(board.ride, board.departures, fetchedAt, sequences, now)
    // Each other pole's, as the ride boards there: only another line's way to the same stop shows, not
    // a line's way back across the road.
    val others = board.others.map { other ->
        val ride = board.ride.copy(fromId = other.pole.id, fromName = other.pole.name.ifBlank { board.ride.fromName })
        other to OnTheWay.boardTrains(ride, other.departures, fetchedAt, sequences, now)
    }
    val misses = found.misses + others.flatMap { it.second.misses }
    // A train its route couldn't place, logged where every trip filter logs it, so "Couldn't check
    // every line" can be explained.
    val routes = LocalRouteStops.current
    LaunchedEffect(routes, misses) { routes?.reportMisses(misses) }
    val stale = Staleness.isStale(fetchedAt, now)
    val nextDue = rememberNextDue(board, sequences, loads.version, readyAt, now).takeIf { !stale }
    return NextTrains(
        board.ride, found.trains,
        pending = found.pending || others.any { it.second.pending },
        unresolved = found.unresolved || others.any { it.second.unresolved },
        stale = stale,
        // A pole of the pair left unread is said too: a train there went unseen.
        failed = board.failed || board.partial,
        readyAt = readyAt, fetchedAt = fetchedAt, stopLetter = board.pole?.stopLetter.orEmpty(), towards = board.pole?.towards.orEmpty(),
        bearing = board.pole?.bearing.orEmpty(),
        others = others.map { (other, trains) ->
            PoleTrains(other.pole.id, other.pole.name.ifBlank { board.ride.fromName }, trains.trains, other.pole.stopLetter, other.pole.towards, other.pole.bearing)
        },
        nextDue = nextDue,
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
private fun NextTrainsSection(next: NextTrains, now: Instant) {
    val groups = remember(next, now) {
        val fetchedAt = next.fetchedAt ?: SteadyClock.stamp(now)
        // The ride's own pole first, then the pair's others, each its own header ("Stop N").
        StopGrouping.groupByStop(
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
        // What the rows may be missing, said rather than left to be taken as the whole answer.
        when {
            next.pending && groups.isEmpty() -> NoteText(stringResource(R.string.on_the_way_trains_loading))
            next.pending -> NoteText(stringResource(R.string.on_the_way_trains_checking))
            next.unresolved -> NoteText(stringResource(R.string.on_the_way_trains_unchecked))
            groups.isEmpty() -> NoteText(stringResource(R.string.on_the_way_trains_none, next.ride.toName))
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
    val walkLeft = (progress as? TripProgress.Walking)?.metersLeft
    val words = if (walkLeft != null && system != null) {
        StopDistance.label(walkLeft, system)
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

// What makes two signals the same thing to the rider: the alert's own words ([RouteDisruptionAlert.text]).
private object DisruptionKey {
    fun of(signal: RouteDisruption.Signal): Any = when (signal) {
        is RouteDisruption.Signal.Line -> Triple(signal.lineId, signal.status.description, signal.status.fullText)
        is RouteDisruption.Signal.Stop -> Pair(signal.stopId, signal.closed)
        is RouteDisruption.Signal.Unpredicted -> Pair(signal.lineId, signal.stopId)
    }
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
