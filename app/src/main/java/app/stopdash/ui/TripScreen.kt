package app.stopdash.ui

import app.stopdash.domain.DismissedAlert
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import kotlin.coroutines.cancellation.CancellationException
import app.stopdash.domain.TflException
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.R
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.RouteFocus
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.Headway
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.RouteStops
import app.stopdash.domain.Staleness
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.toKotlinDuration

/**
 * The live trains [leg] can use (SPEC *Trips with a change*): its line's upcoming trains at its
 * boarding stop whose route calls at its alighting stop ([DirectTrips.filter], so a train for another
 * branch never counts). Null when StopDash can't vouch for them: no arrivals yet, or stale ones (D4).
 * A train whose route is still loading, or can't be followed, is left out rather than guessed.
 */
internal fun legTrains(
    state: TripViewModel.State,
    leg: TripLeg,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): List<Departure>? {
    if (leg.isWalk) return null
    val stop = state.live[leg.fromId] ?: return null
    if (Staleness.isStale(Duration.between(stop.fetchedAt, now).toKotlinDuration())) return null
    val calling = legFilter(leg, stop, now, sequences)?.stops?.firstOrNull()?.departures.orEmpty()
    // On a loop or a reconverging line both ways can reach the alighting stop: only a train leaving
    // for the leg's next stop takes the Planner's path (and run time).
    return calling.filter { leavesAlongLeg(it, leg, sequences) != false }
}

/**
 * Whether [train] leaves [leg]'s boarding stop for the leg's next stop on its line's route
 * ([sequences]): null when that can't be told (no route, a next stop the route doesn't list, or a
 * path that doesn't resolve).
 */
internal fun leavesAlongLeg(train: Departure, leg: TripLeg, sequences: Map<String, LineSequence?>): Boolean? {
    val next = leg.path.firstOrNull() ?: return null
    val sequence = sequences[leg.lineId]?.callingAt(leg.fromId) ?: return null
    if (sequence.routes.none { route -> route.stopIds.any { isStop(sequence, it, next) } }) return null
    // A bus blind names an area more often than a stop: a bus runs to its route's end (RouteStops.resolve).
    val bus = leg.mode.equals("bus", ignoreCase = true)
    val ahead = RouteStops.ahead(sequence, leg.fromId, train.destination, train.branch, leg.lineId, bus, RouteStops.boundOf(train.platform), train.direction) ?: return null
    return ahead.getOrNull(1)?.let { isStop(sequence, it.id, next) } == true
}

/**
 * Whether [leg]'s times aren't in yet: its boarding stop's arrivals not fetched, or, at a bus stop
 * pair, its route not loaded to say which side the bus uses, or its poles still being looked up.
 */
internal fun legLoading(state: TripViewModel.State, leg: TripLeg, sequences: Map<String, LineSequence?>): Boolean =
    state.live[leg.fromId] == null ||
        (leg.fromArea.isNotEmpty() && (leg.lineId !in sequences || (state.refreshing && leg.fromArea !in state.areaPoles)))

/**
 * [state] with each route's legs [onPoles] — where the trip fetches the chosen pole: one of its
 * pair's looked-up poles ([TripViewModel.State.areaPoles]), read as "Loading" until its arrivals are
 * in. After a failed lookup the trip never fetches it, so the Planner's pole stands (the lookup is
 * asked again on the next refresh).
 */
internal fun onPoles(state: TripViewModel.State, sequences: Map<String, LineSequence?>): TripViewModel.State {
    val routes = state.routes ?: return state
    fun fetched(leg: TripLeg, pole: String) = pole in state.live || pole in state.areaPoles[leg.fromArea].orEmpty()
    val placed = routes.map { route ->
        TripRoute(route.legs.map { leg -> onPoles(leg, sequences).takeIf { it.fromId == leg.fromId || fetched(leg, it.fromId) } ?: leg })
    }
    return if (placed == routes) state else state.copy(routes = placed)
}

/**
 * [leg] boarding and alighting at the poles its bus uses. The Planner names a bus stop by its pair
 * ([TripLeg.fromArea], a road's two poles) and one pole of it, which can be the other side of the
 * road: its buses there run the other way. Of the pair's poles on the line's route ([sequences]),
 * the one the route leaves by way of the leg's next stop, and the first pole of the alighting pair
 * after it — or, where it gets off at a stop in no pair, the first stop of that name. Unchanged for
 * a leg named by no pair, before its route loads (or when it failed), or where the route gives no
 * single answer.
 */
internal fun onPoles(leg: TripLeg, sequences: Map<String, LineSequence?>): TripLeg {
    if (leg.isWalk || (leg.fromArea.isEmpty() && leg.toArea.isEmpty())) return leg
    val sequence = sequences[leg.lineId] ?: return leg
    fun boards(id: String) = id == leg.fromId || (leg.fromArea.isNotEmpty() && sequence.stopAreas[id] == leg.fromArea)
    fun alights(id: String) = id == leg.toId || (leg.toArea.isNotEmpty() && sequence.stopAreas[id] == leg.toArea)
    // A stop in no pair (a bus station's stands, "Archway Station") by its name, only where the route
    // doesn't call at the stop itself: the Planner can name a stand the line doesn't use, and the
    // route's own stand is the only tie between them.
    fun named(id: String) = leg.toArea.isEmpty() && sequence.stopNames[id]?.equals(leg.toName, ignoreCase = true) == true
    val next = leg.path.firstOrNull()
    val ends = sequence.routes.flatMap { route ->
        route.stopIds.indices.filter { boards(route.stopIds[it]) }.flatMap { i ->
            val on = route.stopIds.subList(i + 1, route.stopIds.size)
            // Every stop of the name, so two along the route are no single answer.
            val offs = listOfNotNull(on.indexOfFirst(::alights).takeIf { it >= 0 })
                .ifEmpty { on.indices.filter { named(on[it]) } }
            // The way the Planner rides: by its next stop, before or at where it gets off.
            offs.filter { off -> next == null || on.subList(0, off + 1).any { isStop(sequence, it, next) } }
                .map { off -> route.stopIds[i] to on[off] }
        }
    }.distinct()
    val (from, to) = ends.singleOrNull() ?: return leg
    return if (from == leg.fromId && to == leg.toId) leg else leg.copy(fromId = from, toId = to)
}

// Whether the route's stop [id] is the Planner's [stop]: the stop itself, or, for a bus, the stop
// pair ("490G…") the Planner names its path by, which holds both of a road's poles.
private fun isStop(sequence: LineSequence, id: String, stop: String): Boolean =
    id == stop || sequence.stopAreas[id] == stop

// [leg]'s line's upcoming trains at [stop] judged on their routes; null when the line has none.
private fun legFilter(
    leg: TripLeg,
    stop: TripViewModel.StopLive,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): DirectTrips.Result? {
    val line = Countdown.upcoming(stop.departures.filter { it.lineId == leg.lineId }, now)
    if (line.isEmpty()) return null
    return DirectTrips.filter(
        listOf(StopArrivals(leg.fromId, leg.fromName, line, stop.fetchedAt)),
        listOf(DirectTrips.End(leg.toId, leg.toName)),
        sequences,
    )
}

/**
 * While [leg]'s line's route is still loading (absent from [sequences]), its live trains at the
 * boarding stop as the main screen shows them, so the row isn't bare meanwhile (plain only when
 * [uncheckedPending] can't doubt it, grayed until checked otherwise): those heading for the
 * Planner's terminus and the rest of their direction, or, at a bus pole (one direction by nature),
 * every one. Where TfL gives no direction (National Rail), only those heading for the terminus; at a
 * station none heading there, none (its snapshot may just lack the leg's direction). Shown only: nothing times the route until the route check vouches for a
 * train. Empty once the route has loaded (or failed), or when the arrivals are stale (D4).
 */
internal fun pendingTrains(
    state: TripViewModel.State,
    leg: TripLeg,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): List<Departure> {
    if (leg.isWalk || leg.lineId in sequences) return emptyList()
    // A bus stop the Planner named by its pair: which side the bus uses isn't known until its route
    // is, and the other side's buses run the other way.
    if (leg.fromArea.isNotEmpty()) return emptyList()
    val stop = state.live[leg.fromId] ?: return emptyList()
    if (Staleness.isStale(Duration.between(stop.fetchedAt, now).toKotlinDuration())) return emptyList()
    // A train with no destination couldn't be labeled but by the Planner's terminus, which the live
    // feed never said it runs to: left out until the route check vouches for it.
    val line = Countdown.upcoming(stop.departures.filter { it.lineId == leg.lineId && it.destination.isNotBlank() }, now)
    val toward = line.filter { train -> towardTerminus(train, leg) }
    // With no train heading for the terminus, only a bus pole (which serves one direction by nature)
    // says which way its trains run; a station's snapshot may simply lack the leg's direction.
    val bus = leg.mode.equals("bus", ignoreCase = true)
    val directions = toward.ifEmpty { if (bus) line else emptyList() }.mapTo(HashSet()) { it.direction }
    // A blank direction (National Rail's boards give none) says nothing about the way a train runs:
    // only the trains heading for the Planner's terminus, never the rest of the board.
    if ("" in directions) return toward
    if (directions.size != 1 && toward.isEmpty()) return emptyList()
    return line.filter { it.direction in directions }
}

// Whether [train] shows the Planner's terminus for [leg], allowing for a place's parenthetical either
// name carries ("Stratford" for a board's "Stratford (London)"), never a longer name ("Stratford
// International" is another station).
private fun towardTerminus(train: Departure, leg: TripLeg): Boolean {
    val destination = train.destination.withoutQualifier()
    return leg.headings.any { it.withoutQualifier().equals(destination, ignoreCase = true) }
}

private val QUALIFIER = Regex("""\s*\([^()]*\)\s*$""")

private fun String.withoutQualifier(): String = replace(QUALIFIER, "").trim()

/**
 * Of the [pendingTrains], those that may not call where [leg] gets off, shown grayed and read as still
 * being checked until the route check vouches for them; only a bus to the terminus on no named branch
 * shows plain meanwhile (maintainer, 2026-09-26). A train to another terminus or on a named branch
 * ("via Bank") may take another way; and a rail service to the same terminus may still run fast past
 * the rider's stop, which neither its destination nor its branch tells.
 */
internal fun uncheckedPending(pending: List<Departure>, leg: TripLeg): Set<Departure> =
    pending.filterTo(HashSet()) { train ->
        !leg.mode.equals("bus", ignoreCase = true) || !train.branch.isNullOrBlank() || !towardTerminus(train, leg)
    }

/** A leg card's trains while its route is checked: the [pendingTrains] less those [uncheckedPending]. */
internal fun pendingCardTrains(
    state: TripViewModel.State,
    leg: TripLeg,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): List<Departure> = pendingTrains(state, leg, now, sequences).let { pending -> pending - uncheckedPending(pending, leg) }

/**
 * Whether some listed route's live trains couldn't be checked against where the rider gets off:
 * [TripMessage.CHECKING] while a line's route loads, [TripMessage.INCOMPLETE] once one failed or a
 * train's path couldn't be followed; null when every train was checked. Such a leg falls back to the
 * Planner's time, and this says why, rather than pass the fallback off as "no live train".
 */
internal fun tripCheckState(
    state: TripViewModel.State,
    estimates: List<TripTiming.Estimate>,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): TripMessage? {
    val results = legChecks(state, estimates, now, sequences)
    return when {
        results.any { it.unresolved } -> TripMessage.INCOMPLETE
        results.any { it.pending } -> TripMessage.CHECKING
        else -> null
    }
}

/**
 * The trains [tripCheckState] found it couldn't check, by line, stop and reason: the banner says only
 * "Some routes couldn't be checked", so these are what the debug log records (SPEC principle 2).
 */
internal fun tripMisses(
    state: TripViewModel.State,
    estimates: List<TripTiming.Estimate>,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): Set<RouteMiss> = legChecks(state, estimates, now, sequences).flatMapTo(LinkedHashSet()) { it.misses }

// Each ridden leg's live trains judged on their route: legs with fresh arrivals and trains only.
private fun legChecks(
    state: TripViewModel.State,
    estimates: List<TripTiming.Estimate>,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): List<DirectTrips.Result> =
    estimates.flatMap { it.route.rides }.distinct().mapNotNull { leg ->
        val stop = state.live[leg.fromId] ?: return@mapNotNull null
        if (Staleness.isStale(Duration.between(stop.fetchedAt, now).toKotlinDuration())) return@mapNotNull null
        legFilter(leg, stop, now, sequences)
    }

/**
 * The trains a first-leg row times, at most [cap]: the soonest the rider can reach, after as many of
 * those leaving too soon (grayed) as fit, so the train a route is timed from is never cut off.
 */
internal fun shownTrains(
    trains: List<Departure>,
    reachable: Instant,
    cap: Int = SHOWN_TRAINS,
    usable: (Departure) -> Boolean = { true },
): List<Pair<Departure, Boolean>> {
    val sorted = trains.sortedBy { it.expectedArrival }
    fun catchable(d: Departure) = usable(d) && !d.expectedArrival.isBefore(reachable)
    val first = sorted.indexOfFirst(::catchable)
    val shown = if (first < 0) {
        sorted.take(cap)
    } else {
        val before = sorted.subList(0, first).takeLast(cap - 1)
        before + sorted.subList(first, sorted.size).take(cap - before.size)
    }
    return shown.map { it to catchable(it) }
}

/**
 * Each of [state]'s routes timed from [now], best first; null until there is a plan. A route riding
 * a [hidden] mode is left out, as the list leaves out its departures. While the rider's position
 * isn't confirmed ([originUnconfirmed]: a re-locate in flight, failed, or approximate), no route is
 * presented as live-confirmed: each reads "est." at best.
 */
internal fun tripEstimates(
    state: TripViewModel.State,
    now: Instant,
    access: Duration,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
    originUnconfirmed: Boolean = false,
): List<TripTiming.Estimate>? {
    val routes = state.routes
        ?.filterNot { route -> route.rides.any { HiddenModes.isHidden(it.mode, it.lineId, hidden) } }
        ?.let(TripViewModel::bestOf) ?: return null
    val notRunning = TripTiming.notRunning(state.statuses.values)
    // A line with no status known (left out of TfL's answer, or a failed check) can't be vouched
    // for as running.
    // A route shown only now (its mode shown again) waits for its lines' status like a new plan's.
    val unknown = state.statusUnknown +
        routes.flatMap { route -> route.rides.map { it.lineId } }.filterNot { it in state.statuses }
    val estimates = routes.map { route ->
        TripTiming.estimate(
            route, now, access, { index -> legTrains(state, route.legs[index], now, sequences) }, notRunning, unknown,
            current = { index -> state.live[route.legs[index].fromId]?.failed != true },
        )
            .let { if (originUnconfirmed && it.basis == TripTiming.Basis.LIVE) it.copy(basis = TripTiming.Basis.ESTIMATED) else it }
    }
    // Journeys the Planner times differently but rides alike are one route here (one key): each is
    // timed, since a later timetable slot can still be caught when an earlier one can't, and the
    // best stands for the route.
    return TripTiming.rank(estimates).distinctBy { routeKey(it.route) }
}


/**
 * [estimates] (best first, [tripEstimates]) as the list's cards, best first.
 *
 * Routes whose first ride goes between the same two stops by the same mode, then rides the same
 * lines, **share a card** (the 43 or the 134 to Highgate station, then the Northern line): one
 * header with the first ride's lines as a cut pill, and a row per line, best first.
 *
 * Ways riding the same lines in turn but changing elsewhere are cards of their own: each card names
 * where its rides get off, so they read apart (maintainer, 2026-09-27).
 */
internal fun tripCards(estimates: List<TripTiming.Estimate>): List<List<TripTiming.Estimate>> =
    // Within a card, one route per first-ride line: the best.
    estimates.groupBy { cardKey(it.route) }.values
        .map { group -> group.distinctBy { it.route.rides.firstOrNull()?.lineId } }
        .sortedBy { card -> estimates.indexOf(card.first()) }

// Which card a route shares: its first ride's mode and ends (by stop pair for a bus, or by name at a
// stop in no pair, as [onPoles] places it), and the lines after it with where each gets off, since
// the card names those stops ([RideStops]) for every route on it; a route with no ride keeps its own.
internal fun cardKey(route: TripRoute): String {
    val first = route.rides.firstOrNull() ?: return routeKey(route)
    fun offAt(leg: TripLeg) = leg.toArea.ifEmpty { if (leg.fromArea.isNotEmpty()) leg.toName else leg.toId }
    val to = offAt(first)
    val after = route.rides.drop(1).joinToString("|") { "${it.mode}:${it.lineId}>${offAt(it)}" }
    return "${first.mode}:${first.fromArea.ifEmpty { first.fromId }}>$to|$after"
}

/**
 * The lines whose route data a trip loads: [settled] while a first plan's answers are still landing,
 * else [timedLineIds]. A re-plan keeps the last plan until it lands whole, so that plan's lines load
 * at once, even on a screen shown again with nothing settled yet.
 */
internal fun sequenceLineIds(state: TripViewModel.State, hidden: Set<String>, settled: List<String>): List<String> =
    if (state.planning && state.plannedAt == null) settled else timedLineIds(state.routes.orEmpty(), hidden)

/** The lines of the routes a trip times: not riding a [hidden] mode, and within the cap ([TripViewModel.bestOf]). */
internal fun timedLineIds(routes: List<TripRoute>, hidden: Set<String>): List<String> =
    TripViewModel.bestOf(routes.filterNot { route -> route.rides.any { HiddenModes.isHidden(it.mode, it.lineId, hidden) } })
        .flatMap { route -> route.rides.map { it.lineId } }.distinct()

/** A route's identity across refreshes and re-ranking: its lines and stops in order. */
internal fun routeKey(route: TripRoute): String =
    // A bus leg by its stop pairs, so its key holds once its poles are worked out ([onPoles]); one
    // getting off at a stop in no pair by that stop's name, which is how its stop is worked out.
    route.legs.joinToString("|") { leg ->
        val to = leg.toArea.ifEmpty { if (leg.fromArea.isNotEmpty()) leg.toName else leg.toId }
        "${leg.mode}:${leg.lineId}:${leg.fromArea.ifEmpty { leg.fromId }}:$to"
    }

// How many of a first leg's trains its row times.
private const val SHOWN_TRAINS = 3

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val LONDON: ZoneId = ZoneId.of("Europe/London")

/**
 * A trip with a change (SPEC *Trips with a change*): the routes best first, every route alike — its
 * line pills, ⚠ on a disrupted leg, and duration · arrival, over its first leg's live trains — and,
 * once one is tapped, that route leg by leg in the list's own header and route cards. Renders from
 * [state] alone; the caller refreshes it on the list's foreground tick. Its trains are checked
 * against each line's route from [routeStops], which a caller must give: with none, no route would
 * load and every train would stay "checking".
 */
@Composable
internal fun TripScreen(
    title: String,
    state: TripViewModel.State,
    now: Instant,
    // The rider's walk to the trip's first stop (zero from a From… station).
    access: Duration,
    routeStops: RouteStopsRepository,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    locationBanner: LocationBanner? = null,
    relocating: Boolean = false,
    onRelocate: () -> Unit = {},
    hiddenModes: Set<String> = emptySet(),
    onShowAllModes: () -> Unit = {},
    // A line row's long-press "Hide ‹mode›", as on the list (null: no menu).
    onHideMode: ((String) -> Unit)? = null,
    // A change of hidden modes that didn't save, said once as the list says it, then acknowledged.
    hiddenModesWriteFailed: Boolean = false,
    onHiddenModesWriteFailureShown: () -> Unit = {},
    // The app's own overflow: the update dot, the bug report and About, as on the list. Null (a
    // test) shows no overflow.
    menu: AppMenuActions? = null,
    // The route open on screen, held by the trip ([TripViewModel.openRoute]) so it survives the
    // screen leaving composition; null holds it in the screen.
    openRoute: MutableState<String?>? = null,
    // The service alerts the user dismissed (SPEC *Disruptions*), shared with the list: a leg's line
    // page offers the same × ([onDismissAlert]; null offers none) and honors what's dismissed. A
    // dismiss that didn't persist ([dismissWriteFailed]) is said once, then acknowledged.
    dismissed: Set<DismissedAlert> = emptySet(),
    onDismissAlert: ((DepartureRow) -> Unit)? = null,
    dismissWriteFailed: Boolean = false,
    onDismissWriteFailureShown: () -> Unit = {},
) {
    CompositionLocalProvider(LocalRouteStops provides routeStops) {
        TripContent(
            title, state, now, access, onBack, onRetry, locationBanner, relocating, onRelocate,
            hiddenModes, onShowAllModes, onHideMode, hiddenModesWriteFailed, onHiddenModesWriteFailureShown, menu, openRoute,
            TripAlerts(dismissed, onDismissAlert, dismissWriteFailed, onDismissWriteFailureShown),
        )
    }
}

/** The shared alert dismissals a trip's line page works with (see [TripScreen]). */
private class TripAlerts(
    val dismissed: Set<DismissedAlert>,
    val onDismiss: ((DepartureRow) -> Unit)?,
    val writeFailed: Boolean,
    val onWriteFailureShown: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripContent(
    title: String,
    planned: TripViewModel.State,
    now: Instant,
    access: Duration,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    // The list's low-confidence location banner, with "Try again" re-locating ([onRelocate]); while
    // it shows, or a re-locate is in flight ([relocating]), no route reads as live-confirmed.
    locationBanner: LocationBanner? = null,
    relocating: Boolean = false,
    onRelocate: () -> Unit = {},
    // Modes the rider hid: routes riding them are left out, with the list's "Show all".
    hiddenModes: Set<String> = emptySet(),
    onShowAllModes: () -> Unit = {},
    onHideMode: ((String) -> Unit)? = null,
    hiddenModesWriteFailed: Boolean = false,
    onHiddenModesWriteFailureShown: () -> Unit = {},
    menu: AppMenuActions? = null,
    openRoute: MutableState<String?>? = null,
    alerts: TripAlerts = TripAlerts(emptySet(), null, false) {},
) {
    // Only the timed routes' lines: a hidden mode's routes, and those past the cap, load no route data.
    // While a plan's answers are still landing, the last settled plan's lines stand, so a passing
    // top six never starts loads a later answer would make pointless.
    val settledLines = remember { arrayOf(emptyList<String>()) }
    val lineIds = remember(planned.routes, hiddenModes, planned.planning) {
        sequenceLineIds(planned, hiddenModes, settledLines[0]).also { settledLines[0] = it }
    }
    val sequences = rememberLineSequences(lineIds, now)
    // Each bus leg at the poles its bus uses, once its route says which (the Planner's may be the
    // other side of the road); everything below reads the trip this way.
    val state = remember(planned, sequences) { onPoles(planned, sequences) }
    val originUnconfirmed = relocating || locationBanner != null
    var showAbout by rememberSaveable { mutableStateOf(false) }
    if (showAbout && menu != null) {
        AboutDialog(
            onOpenLicenses = {
                showAbout = false
                menu.onOpenLicenses()
            },
            onDismiss = { showAbout = false },
        )
    }
    val estimates = remember(state, now, access, sequences, hiddenModes, originUnconfirmed) {
        tripEstimates(state, now, access, sequences, hiddenModes, originUnconfirmed)
    }
    // The list's cards; an open route is looked up among every way timed, so it stays open whichever
    // way its card shows.
    val cards = remember(estimates) { estimates?.let(::tripCards) }
    // The open route, kept twice: by the trip when it's given one ([openRoute]), which outlasts the
    // screen leaving composition (an overlay) and, saved by the trip, the process too; and saved with
    // the screen, for a trip that holds none. Read from the trip first; set in both.
    val savedOpenKey = rememberSaveable { mutableStateOf<String?>(null) }
    val heldOpenKey = remember(openRoute) {
        openRoute?.also { if (it.value == null) it.value = savedOpenKey.value } ?: savedOpenKey
    }
    val openKey = heldOpenKey.value
    fun setOpenKey(key: String?) {
        heldOpenKey.value = key
        savedOpenKey.value = key
    }
    val open = estimates?.firstOrNull { routeKey(it.route) == openKey }
    BackHandler { if (open != null) setOpenKey(null) else onBack() }
    // A leg's row tapped on an open route: its line's page, as a row on the main screen opens it,
    // with the line's full service alert and its stops (SPEC *Trips with a change*). Found again among
    // the open route's rows on every refresh, so it stays live; gone with them, it closes.
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Which leg the page belongs to ([tripLegKey]): two of the list's routes can board the same line
    // at the same stop and get off at different places, so the tapped one is remembered, not guessed.
    var detailLegKey by rememberSaveable { mutableStateOf<String?>(null) }
    var detailDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var detailBranch by rememberSaveable { mutableStateOf<String?>(null) }
    // The legs a line page opens from: the open route's, else every route the list shows (a card's
    // line row opens its page too, as a row on the main screen does).
    val detailLegs = remember(open, estimates) {
        (open?.let { listOf(it) } ?: estimates.orEmpty()).flatMap { it.route.rides }.distinct()
    }
    val detailLeg = detailLegs.firstOrNull { tripLegKey(it) == detailLegKey }
    val detailRow = if (detailKey == null || detailLeg == null) {
        null
    } else {
        // A leg's no-trains row only while it has no live rows, as the list's status row: once trains
        // come, the page opened from it closes (below) rather than stay on it with no times.
        // Dismissed alerts apply as on the list's page: a timed row keeps its times, marked dismissed.
        // A no-trains row is kept too, marked the same: unlike the list's, it's how the leg opens its
        // line's stops, not only its alert.
        // Every train a list card's row shows, those still being checked too, so a page opened from
        // one finds the row it was opened from (the open route's rows are a subset, keyed the same).
        val live = legRows(state, detailLeg, now, sequences, withUnchecked = true)
        val rows = if (live.isEmpty()) listOf(withDismissedMarked(legStatusRow(state, detailLeg, now), alerts.dismissed))
        else DepartureRows.withoutDismissed(live, alerts.dismissed)
        rows.firstOrNull { tripDetailKey(detailLeg, it) == detailKey }
    }
    // A line row tapped, on the open route or a list card: its line's page, for that leg's row.
    fun openDetail(leg: TripLeg, row: DepartureRow, focus: RouteFocus?) {
        detailLegKey = tripLegKey(leg)
        detailKey = tripDetailKey(leg, row)
        detailDestination = focus?.destination
        detailBranch = focus?.branch
    }
    // Its row gone (its last train passed, or the route closed): the page stays closed rather than
    // reopening by itself should a later refresh bring the same row back, as the list's does.
    LaunchedEffect(detailKey, detailRow == null) {
        if (detailKey != null && detailRow == null) detailKey = null
    }
    if (detailRow != null) {
        RouteDetailScreen(
            row = detailRow,
            isStarred = false,
            starrable = false,
            // Never vouched clean: a trip checks its lines' status but not its boarding stops' own
            // disruptions (a closure, a moved stop), so the page never claims "no disruptions".
            disruptionUnknown = true,
            // Stale too once its stop's last refresh failed: the held arrivals no longer stand as
            // current, and this page doesn't carry the route's failure banner.
            stale = Staleness.isStale(Duration.between(detailRow.fetchedAt, now).toKotlinDuration()) ||
                state.live[detailRow.stopId]?.failed == true,
            now = now,
            onToggleStar = {},
            onBack = { detailKey = null },
            focus = detailDestination?.let { RouteFocus(it, detailBranch) },
            // A leg with no train to follow still shows its line's stops, from the leg's own route.
            onDismissAlert = alerts.onDismiss?.takeIf { detailRow.status != null }?.let { dismiss -> { dismiss(detailRow) } },
            loadRouteStops = if (detailRow.upcoming.isEmpty()) {
                detailLeg?.let { leg -> { retry: Int -> rememberLegRouteStops(leg, retry) } }
            } else {
                null
            },
        )
        return
    }
    // A dismiss on a leg's page that didn't persist, said back on the route (as the list says it back
    // on the list), then acknowledged so it isn't said again.
    val snackbarHostState = remember { SnackbarHostState() }
    val dismissWriteFailedMessage = stringResource(R.string.dismiss_write_failed)
    LaunchedEffect(alerts.writeFailed) {
        if (alerts.writeFailed) {
            alerts.onWriteFailureShown()
            snackbarHostState.showSnackbar(dismissWriteFailedMessage)
        }
    }
    // A mode hidden (or shown) from here that didn't save: it's hidden for now but won't last, so
    // say so, as the list does, then acknowledge it so it isn't said again.
    val hiddenModesWriteFailedMessage = stringResource(R.string.hidden_modes_write_failed)
    LaunchedEffect(hiddenModesWriteFailed) {
        if (hiddenModesWriteFailed) {
            onHiddenModesWriteFailureShown()
            snackbarHostState.showSnackbar(hiddenModesWriteFailedMessage)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (open != null) setOpenKey(null) else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (menu != null) {
                        AppOverflowMenu(menu.updateAvailable, menu.onOpenAppListing) { close ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_send_bug_report)) },
                                onClick = {
                                    close()
                                    menu.onSendBugReport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_about)) },
                                onClick = {
                                    close()
                                    showAbout = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // With a route open, only its own legs' warnings frame it; the list takes every route's.
            val shown = open?.let { listOf(it) } ?: cards?.flatten()
            val check = remember(state, shown, now, sequences) { shown?.let { tripCheckState(state, it, now, sequences) } }
            // Which trains the banner means, logged once per distinct set, off composition.
            val misses = remember(state, shown, now, sequences) { shown?.let { tripMisses(state, it, now, sequences) }.orEmpty() }
            val routeStops = LocalRouteStops.current
            LaunchedEffect(routeStops, misses) { routeStops?.reportMisses(misses) }
            TripBanners(shown, state, check, locationBanner, onRelocate, hiddenModes, onShowAllModes)
            Box(Modifier.fillMaxSize()) {
                when {
                    cards == null -> TripPlaceholder(state, onRetry)
                    open != null -> RouteLegs(open, state, now, access, sequences, onRetry, alerts.dismissed, onHideMode, ::openDetail)
                    else -> RouteList(
                        cards, state, now, access, sequences, onRetry, alerts.dismissed,
                        onOpen = { setOpenKey(routeKey(it.route)) },
                        onHideMode = onHideMode,
                    )
                }
            }
        }
    }
}

/**
 * What frames every route, as the list's banners frame its stops: a location that isn't current,
 * boarding stops whose arrivals couldn't be refreshed (their times age out rather than pass as
 * live), and the modes hidden from the trip.
 */
@Composable
private fun TripBanners(
    estimates: List<TripTiming.Estimate>?,
    state: TripViewModel.State,
    check: TripMessage?,
    locationBanner: LocationBanner?,
    onRelocate: () -> Unit,
    hiddenModes: Set<String>,
    onShowAllModes: () -> Unit,
) {
    locationBanner?.let {
        ActionBanner(
            text = stringResource(
                when (it) {
                    LocationBanner.APPROXIMATE -> R.string.location_approximate
                    LocationBanner.UPDATE_FAILED -> R.string.location_update_failed
                    LocationBanner.COARSE -> R.string.location_coarse
                },
            ),
            onTryAgain = onRelocate,
        )
    }
    val failed = remember(estimates, state.live) {
        estimates.orEmpty().flatMap { it.route.rides }
            .filter { state.live[it.fromId]?.failed == true }
            .map { it.fromName }
            .distinct()
    }
    if (failed.isNotEmpty()) {
        val which = if (failed.size == 1) failed[0] else stringResource(R.string.partial_refresh_more, failed[0], failed.size - 1)
        Banner(stringResource(R.string.partial_refresh_no_reason, which))
    }
    when (check) {
        TripMessage.CHECKING -> Banner(stringResource(R.string.trip_checking))
        TripMessage.INCOMPLETE -> Banner(stringResource(R.string.journey_incomplete))
        else -> Unit
    }
    if (hiddenModes.isNotEmpty()) {
        ActionBanner(
            text = stringResource(R.string.modes_hidden, hiddenGroupsLabel(hiddenModes)),
            actionLabel = stringResource(R.string.modes_show_all),
            onAction = onShowAllModes,
        )
    }
}

@Composable
private fun TripPlaceholder(state: TripViewModel.State, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val error = state.planError
        if (error == null || state.planning) {
            Text(stringResource(R.string.trip_planning), style = MaterialTheme.typography.bodyLarge)
        } else {
            PlanFailure(error, state.planning, onRetry)
        }
    }
}

@Composable
private fun PlanFailure(error: DeparturesUiState.Error.Kind, planning: Boolean, onRetry: () -> Unit) =
    PlanNotice(stringResource(R.string.trip_plan_failed, stringResource(errorMessage(error))), planning, onRetry)

/** A plan that reached only some of a complex's stations: the routes shown may not be the best. */
@Composable
private fun PlanIncomplete(planning: Boolean, onRetry: () -> Unit) =
    PlanNotice(stringResource(R.string.trip_plan_incomplete), planning, onRetry)

@Composable
private fun PlanNotice(text: String, planning: Boolean, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, enabled = !planning) { Text(stringResource(R.string.try_again)) }
    }
}

@Composable
private fun RouteList(
    cards: List<List<TripTiming.Estimate>>,
    state: TripViewModel.State,
    now: Instant,
    // The walk to the trip's first stop: the card's times gray what leaves before the rider gets there.
    access: Duration,
    sequences: Map<String, LineSequence?>,
    onRetry: () -> Unit,
    // The alerts dismissed (as on the list): their ⚠ doesn't show on a card.
    dismissed: Set<DismissedAlert>,
    onOpen: (TripTiming.Estimate) -> Unit,
    onHideMode: ((String) -> Unit)?,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().testTag("tripRoutes"),
    ) {
        state.planError?.let { error -> item(key = "error") { PlanFailure(error, state.planning, onRetry) } }
        if (state.planError == null && state.planIncomplete) item(key = "incomplete") { PlanIncomplete(state.planning, onRetry) }
        // A plan past its reuse is being planned again: its routes stay, stamped with their age.
        val plannedAt = state.plannedAt
        if (state.planning && plannedAt != null) {
            item(key = "replanning") {
                Text(
                    stringResource(R.string.trip_replanning, Duration.between(plannedAt, now).toMinutes().toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        // Lines not yet checked rank as unchecked, and say so: checking while a check runs, and
        // only a finished check says it couldn't.
        // A route revealed since the last refresh (a mode shown again) counts by its own lines.
        statusNote(state, state.statusUnknown.isNotEmpty() || cards.any { card -> card.any { it.unchecked } })?.let { checking -> item(key = "status") { StatusUnknown(checking) } }
        if (cards.isEmpty()) {
            item(key = "none") { Text(stringResource(R.string.trip_no_routes), style = MaterialTheme.typography.bodyLarge) }
        }
        // Routes sharing every stop but differing in their first line are one card: one header, a
        // row per ride, and the first ride's times for every line together. The card is one choice
        // (maintainer, 2026-09-27): tapping it opens the best of its routes, and a long press
        // anywhere offers to hide each group any of its legs rides.
        items(cards, key = { cardKey(it.first().route) }) { card ->
            val modes = remember(card) { cardModes(card) }
            var menuOpen by remember { mutableStateOf(false) }
            val onLongPress = if (onHideMode != null && modes.isNotEmpty()) ({ menuOpen = true }) else null
            val moreLabel = stringResource(R.string.more_actions)
            Box {
                // Each row takes the card's tap and long press itself: a clickable card would merge
                // its rows into one, and a screen reader would lose the rows' own times.
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    // The header, then a row per ride saying where it gets off (maintainer,
                    // 2026-09-27): one tap target, so a card changing at Highgate reads apart from
                    // one changing at Archway.
                    Column(
                        modifier = Modifier
                            .combinedClickable(
                                onLongClickLabel = onLongPress?.let { moreLabel },
                                onLongClick = onLongPress,
                                onClick = { onOpen(card.first()) },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val statuses = shownStatuses(state.statuses, dismissed)
                        CardHeader(card, statuses)
                        RideStops(card, statuses, remember(card, state, now, access, sequences) { cardTimes(card, state, now, access, sequences) }, now)
                    }
                }
                if (onHideMode != null) {
                    HideModeMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        modes = modes,
                        onHideMode = onHideMode,
                        lines = cardLines(card),
                    )
                }
            }
        }
    }
}

/** Every line any route on a trip's card rides, in the order they're ridden, for its "Hide ‹line›" items. */
internal fun cardLines(card: List<TripTiming.Estimate>): List<LineRef> =
    card.asSequence()
        .flatMap { estimate -> estimate.route.rides.asSequence() }
        .filter { it.lineId.isNotBlank() }
        .map { LineRef(it.lineId, it.lineName, it.mode) }
        .distinctBy { it.id.lowercase() }
        .toList()

/** Every mode any route on a trip's card rides, in a stable order, for its "Hide all ‹group› services" menu. */
internal fun cardModes(card: List<TripTiming.Estimate>): List<String> =
    card.asSequence()
        .flatMap { estimate -> estimate.route.rides.asSequence().map { it.mode } }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .sortedBy(::modeName)
        .toList()

/**
 * A card's top row: its lines' pills in order — the first ride's lines as one cut pill when the
 * card's routes differ only there ("43/134") — a ⚠ beside each disrupted one, then the best
 * route's duration · arrival, which drops below the pills when they leave no room beside them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteSummary(card: List<TripTiming.Estimate>, statuses: Map<String, LineStatus>, modifier: Modifier = Modifier) {
    val estimate = card.first()
    val firstLines = card.mapNotNull { it.route.rides.firstOrNull() }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val rides = estimate.route.rides
        if (rides.isEmpty()) {
            // All walking (two stops close together): no line to show, and no live row below.
            Text(stringResource(R.string.trip_walk_only), style = MaterialTheme.typography.titleSmall)
        }
        rides.forEachIndexed { index, leg ->
            // A disrupted line's ⚠ sits beside its own pill (and wraps with it), so it's clear which
            // leg it qualifies; beside a cut pill, for any of its lines.
            val lines = if (index == 0) firstLines else listOf(leg)
            val warning = linesWarning(lines, statuses)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SharedLinePill(
                    lines.map { LineRef(it.lineId, it.lineName, it.mode) },
                    lines.map { it.lineName }.reduce { a, b -> stringResource(R.string.trip_lines_either, a, b) },
                )
                if (warning != null) DisruptionWarningGlyph(warning)
            }
        }
        Text(
            text = arrivalText(estimate),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // A FlowRow keeps a weighted item on the pills' line when its min intrinsic width fits,
            // which for text is its longest word: "Arrival unknown" stayed beside the pills and was
            // cut to "Arrival". The whole line's width moves it below them instead.
            modifier = Modifier.weight(1f).width(IntrinsicSize.Max).padding(start = 12.dp),
        )
    }
}

/**
 * The first ride's trains for a card, every line on it together (maintainer, 2026-09-27): each is a
 * way to the same change, so which line doesn't matter, only when. [shown] is at most
 * [SHOWN_TRAINS] in time order, each with whether the rider can use it ([shownTrains]); [checking]
 * those whose route is still being checked; [loading] while any line's times aren't in yet (its
 * boarding stop's arrivals, or at a bus stop pair its route), when [shown] is empty.
 * [headways] is how often each later ride's line runs, by ride ([Headway]): the rider isn't there
 * yet, so countdowns would say nothing they can use; null where too few trains are known.
 */
internal data class CardTimes(
    val shown: List<Pair<Departure, Boolean>>,
    val checking: Set<Departure>,
    val reachable: Instant,
    val loading: Boolean,
    val headways: List<Headway.Range?> = emptyList(),
)

/**
 * [card]'s first-ride trains ([CardTimes]): each route's usable trains along its first ride
 * ([legTrains]), or while its route is checked the line's trains as the main screen shows them
 * ([pendingTrains]), grayed until vouched for, merged and timed from when the rider reaches the
 * stop: the walk there ([access]) and any walk before the first ride, never the train a route
 * happens to be timed from, which would gray another line's earlier train the rider can catch.
 */
internal fun cardTimes(
    card: List<TripTiming.Estimate>,
    state: TripViewModel.State,
    now: Instant,
    access: Duration,
    sequences: Map<String, LineSequence?>,
): CardTimes {
    val trains = ArrayList<Departure>()
    val usable = HashSet<Departure>()
    val checking = HashSet<Departure>()
    var loading = false
    card.forEach { estimate ->
        val leg = estimate.route.legs.firstOrNull { !it.isWalk } ?: return@forEach
        val live = legTrains(state, leg, now, sequences)
        val pending = pendingTrains(state, leg, now, sequences)
        val unchecked = uncheckedPending(pending, leg)
        trains += pending.ifEmpty { null } ?: live.orEmpty()
        usable += live.orEmpty() + pending.filterNot { it in unchecked }
        checking += unchecked
        loading = loading || legLoading(state, leg, sequences)
    }
    val legs = card.first().route.legs
    val walks = legs.takeWhile { it.isWalk }
    val reachable = walks.fold(now.plus(access)) { at, walk -> at.plus(walk.run).plus(walk.changeAfter) }
    // While any line's times are still loading, none show: the others alone would read as all of them.
    val shown = if (loading) emptyList() else shownTrains(trains.distinct(), reachable, usable = { it in usable })
    // Each later ride's own trains, those along its route ([legTrains]); none while stale or unchecked.
    val headways = card.first().route.rides.drop(1).map { ride ->
        legTrains(state, ride, now, sequences)?.let { later -> Headway.of(later.map { it.expectedArrival }) }
    }
    return CardTimes(shown, checking, reachable, loading, headways)
}

/**
 * Under a card's header, a row per ride of its best route: the ride's line pill (the first ride's
 * lines as one cut pill, as in the header) and the stop it gets off at, the first ride's also with
 * [times], every line's trains together; the stop's name is cut before the times are. Walks between
 * rides are left out; the route's own page has them.
 */
@Composable
private fun RideStops(card: List<TripTiming.Estimate>, statuses: Map<String, LineStatus>, times: CardTimes, now: Instant) {
    val rides = card.first().route.rides
    val firstLines = card.mapNotNull { it.route.rides.firstOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("rideStops")) {
        rides.forEachIndexed { index, ride ->
            val lines = if (index == 0) firstLines else listOf(ride)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SharedLinePill(
                    lines.map { LineRef(it.lineId, it.lineName, it.mode) },
                    lines.map { it.lineName }.reduce { a, b -> stringResource(R.string.trip_lines_either, a, b) },
                )
                // A disrupted line's ⚠ beside its own pill; beside a cut pill, for any of its lines.
                linesWarning(lines, statuses)?.let { DisruptionWarningGlyph(it, Modifier.padding(start = 4.dp)) }
                Text(
                    text = ride.toName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (index > 0) {
                    times.headways.getOrNull(index - 1)?.let { headway ->
                        Text(
                            text = if (headway.min == headway.max) {
                                stringResource(R.string.trip_headway, headway.min)
                            } else {
                                stringResource(R.string.trip_headway_range, headway.min, headway.max)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                if (index == 0) {
                    // Graying is lost on TalkBack: each time is read with its destination, and whether it's usable.
                    val description = times.shown.map { (train, catchable) ->
                        val time = Countdown.mergedLabel(listOf(train), now)
                        val to = train.destination.ifBlank { ride.toName }
                        stringResource(trainDescription(train, catchable, train in times.checking, times.reachable), time, to)
                    }.joinToString(", ")
                    Text(
                        text = trainTimes(times.shown, now, loading = times.loading),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .then(if (times.shown.isEmpty()) Modifier else Modifier.semantics { contentDescription = description }),
                    )
                }
            }
        }
    }
}

/** The ⚠'s wording for [lines]' disruptions in [statuses]; beside a cut pill, each by its line. Null when none is disrupted. */
@Composable
private fun linesWarning(lines: List<TripLeg>, statuses: Map<String, LineStatus>): String? {
    val disrupted = lines.mapNotNull { line -> statuses[line.lineId]?.takeIf { it.disrupted }?.let { line to it } }
    return disrupted.singleOrNull()?.second?.description
        ?: disrupted.map { (line, status) -> stringResource(R.string.trip_line_status, line.lineName, status.description) }
            .takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/**
 * A list card's top row (maintainer, 2026-09-27): where the trip starts, "From ‹stop›", and the best
 * route's duration · arrival. Its lines are the ride rows below ([RideStops]), each with its ⚠. A walk
 * only route reads as the open route's summary does ([RouteSummary]).
 */
@Composable
private fun CardHeader(card: List<TripTiming.Estimate>, statuses: Map<String, LineStatus>) {
    val estimate = card.first()
    val first = estimate.route.rides.firstOrNull() ?: return RouteSummary(card, statuses)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.trip_from, first.fromName),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = arrivalText(estimate),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun arrivalText(estimate: TripTiming.Estimate): String {
    val arrival = estimate.arrival ?: return stringResource(R.string.trip_arrival_unknown)
    val minutes = (estimate.duration ?: Duration.ZERO).toMinutes().toInt()
    val clock = CLOCK.format(arrival.atZone(LONDON))
    return if (estimate.basis == TripTiming.Basis.LIVE) {
        stringResource(R.string.trip_duration_arrival, minutes, clock)
    } else {
        stringResource(R.string.trip_duration_arrival_estimated, minutes, clock)
    }
}

/**
 * How TalkBack reads a first-leg train: plain when [catchable]; "can't catch" when it leaves before the
 * rider can board at [reachable], whether or not its route is still [checking]; "checking route" for
 * one the rider could reach whose route isn't vouched for yet; "can't catch" otherwise.
 */
@StringRes
internal fun trainDescription(train: Departure, catchable: Boolean, checking: Boolean, reachable: Instant): Int = when {
    catchable -> R.string.trip_train_description
    checking && !train.expectedArrival.isBefore(reachable) -> R.string.trip_train_checking_description
    else -> R.string.trip_train_unusable_description
}

/**
 * The forms a list of destinations may show in, longest first: each name in full, then each with the
 * standard abbreviations, then each at its floor ("Crystal P., W. Croydon") — every name shortened
 * alike, as the list shortens one destination, before anything elides.
 */
internal fun destinationsLadder(names: List<String>): List<String> = listOf(
    names.joinToString(", "),
    names.joinToString(", ") { DestinationAbbreviations.abbreviate(it) },
    names.joinToString(", ") { DestinationAbbreviations.floor(it) },
).distinct()

/**
 * "3 · 11 min" in time order, trains the rider can't use grayed; "Loading" while the boarding stop's
 * arrivals haven't been fetched yet ([loading]); "–" with none StopDash can vouch for.
 */
@Composable
private fun trainTimes(shown: List<Pair<Departure, Boolean>>, now: Instant, loading: Boolean) = buildAnnotatedString {
    if (shown.isEmpty()) {
        append(if (loading) stringResource(R.string.trip_times_loading) else "–")
        return@buildAnnotatedString
    }
    shown.forEachIndexed { i, (train, catchable) ->
        if (i > 0) append(" · ")
        val minutes = Countdown.mergedLabel(listOf(train), now).removeSuffix(" min")
        if (catchable) {
            append(minutes)
        } else {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Normal)) { append(minutes) }
        }
    }
    append(" min")
}

/** A tapped route, leg by leg: each ride's header and route card, then how far it rides; walks as a link. */
@Composable
private fun RouteLegs(
    estimate: TripTiming.Estimate,
    state: TripViewModel.State,
    now: Instant,
    access: Duration,
    sequences: Map<String, LineSequence?>,
    onRetry: () -> Unit,
    // The alerts dismissed (as on the list): their ⚠ doesn't show on a leg.
    dismissed: Set<DismissedAlert>,
    // A leg's row long-pressed: "Hide ‹mode›", as on the list (null: no menu).
    onHideMode: ((String) -> Unit)?,
    // A leg's row tapped: opens its line's page.
    onOpenDetail: (TripLeg, DepartureRow, RouteFocus?) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize().testTag("tripLegs"),
    ) {
        // A re-plan that failed says so over the open route too, with its Retry, as the list does.
        state.planError?.let { error -> item(key = "error") { PlanFailure(error, state.planning, onRetry) } }
        if (state.planError == null && state.planIncomplete) item(key = "incomplete") { PlanIncomplete(state.planning, onRetry) }
        item(key = "summary") { RouteSummary(listOf(estimate), shownStatuses(state.statuses, dismissed), Modifier.padding(vertical = 8.dp)) }
        statusNote(state, estimate.unchecked)?.let { checking -> item(key = "status") { StatusUnknown(checking) } }
        val firstStop = estimate.route.legs.firstOrNull()?.fromName
        if (access > Duration.ZERO && firstStop != null) {
            item(key = "access") { WalkLink(stringResource(R.string.trip_walk_first, access.toMinutes().toInt(), firstStop)) }
        }
        estimate.route.legs.forEachIndexed { index, leg ->
            if (leg.isWalk) {
                item(key = "leg$index") { WalkLink(stringResource(R.string.trip_walk, leg.run.toMinutes().toInt(), leg.toName)) }
            } else {
                item(key = "leg$index") { RideLeg(leg, index == 0, state, now, sequences, dismissed, onOpenDetail, onHideMode) }
                // A change the Planner allows time for after this ride (not a walk leg of its own):
                // shown, since it decides which next train is in reach.
                if (leg.changeAfter > Duration.ZERO && index < estimate.route.legs.lastIndex) {
                    item(key = "change$index") { WalkLink(stringResource(R.string.trip_change, leg.changeAfter.toMinutes().toInt())) }
                }
            }
        }
    }
}

/**
 * Whether a line shown without ⚠ may still be disrupted, and why: true while a check for [unchecked]
 * lines runs or is about to (a plan still landing), false once the check failed or left them
 * unchecked, null when every line was checked.
 */
internal fun statusNote(state: TripViewModel.State, unchecked: Boolean): Boolean? = when {
    // A plan still landing checks its lines when it settles: checking, not failed.
    state.refreshing || state.planning -> if (unchecked) true else null
    state.statusFailed || unchecked -> false
    else -> null
}

/** A line shown without ⚠ may still be disrupted: its status is being checked, or couldn't be. */
@Composable
private fun StatusUnknown(checking: Boolean) {
    Text(
        stringResource(if (checking) R.string.disruptions_checking else R.string.disruptions_unknown),
        style = MaterialTheme.typography.bodyMedium,
        color = if (checking) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun WalkLink(text: String) {
    Text(
        text = "┊  $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/**
 * A ride leg's rows as its card shows them: each destination on its own row, only the trains that
 * run along the leg's route ([legTrains]), never another branch's (maintainer, 2026-09-27): the
 * rider sees only trains they can take. Stale arrivals show none (D4). While the route is checked, the line's trains as the main
 * screen shows them, less those on a named branch that may skip the stop the rider gets off at
 * (they join once the check vouches) — unless [withUnchecked]: a list card's line row shows those
 * grayed, so the row it opens, and that page, are built from the same trains.
 */
internal fun legRows(
    state: TripViewModel.State,
    leg: TripLeg,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    withUnchecked: Boolean = false,
): List<DepartureRow> {
    val pending = if (withUnchecked) pendingTrains(state, leg, now, sequences) else pendingCardTrains(state, leg, now, sequences)
    val trains = pending
        .ifEmpty { legTrains(state, leg, now, sequences).orEmpty() }
    return DepartureRows.forStop(
        leg.fromId,
        leg.fromName,
        trains,
        now,
        lineStatuses = state.statuses,
        fetchedAt = state.live[leg.fromId]?.fetchedAt ?: now,
    )
}

/**
 * A leg's stops on its line ([fetched]) for a page with no train to follow ([RouteStops.forLeg]),
 * from the pole its bus uses ([onPoles]) where the Planner named the other side of the road.
 */
internal fun legStops(planned: TripLeg, fetched: LineSequence): RouteStopsUi {
    val leg = onPoles(planned, mapOf(planned.lineId to fetched))
    val sequence = fetched.callingAt(leg.fromId)
    return when (val resolution = RouteStops.forLeg(sequence, leg)) {
        is RouteStops.Resolution.Found -> RouteStopsUi.Loaded(
            resolution.stops,
            resolution.stops.mapNotNull { stop -> sequence.stopPositions[stop.id]?.let { stop.id to it } }.toMap(),
            sequence,
        )
        else -> RouteStopsUi.Unavailable(resolution)
    }
}

/**
 * [leg]'s stops ([legStops]) from the line's route in [LocalRouteStops]: the held copy at once, else
 * loading it off the render path; a failed load says why and loads again on [retry], as a row's stop
 * list does ([rememberRouteStops]).
 */
@Composable
internal fun rememberLegRouteStops(leg: TripLeg, retry: Int): RouteStopsUi {
    val repository = LocalRouteStops.current ?: return RouteStopsUi.Hidden
    return key(repository, leg) {
        val initial = remember { repository.cached(leg.lineId, "")?.let { legStops(leg, it) } ?: RouteStopsUi.Loading }
        val state by produceState(initial, retry) {
            if (value !is RouteStopsUi.Loading && value !is RouteStopsUi.Failed) return@produceState
            value = RouteStopsUi.Loading
            value = try {
                legStops(leg, repository.load(leg.lineId, ""))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                // TfL has no route for this line: unavailable, with no retry that could never work.
                RouteStopsUi.Unavailable(RouteStops.Resolution.UnknownLine)
            } catch (e: TflException) {
                // Already logged (sanitized) by the repository; surfaced here with its reason.
                RouteStopsUi.Failed(errorKindOf(e))
            }
        }
        // Logged once per opened leg, off composition, as a followed train's page logs it.
        LaunchedEffect(state) {
            (state as? RouteStopsUi.Unavailable)?.let { repository.reportUnresolved(leg.lineId, leg.fromId, it.reason) }
        }
        state
    }
}

/**
 * The line statuses a trip's cards warn of: [statuses] less the alerts the rider dismissed, as the
 * list shows them. Display only — a dismissed line still ranks and counts as checked.
 */
internal fun shownStatuses(statuses: Map<String, LineStatus>, dismissed: Set<DismissedAlert>): Map<String, LineStatus> =
    if (dismissed.isEmpty()) statuses else statuses.filterValues { !it.disrupted || DismissedAlert.ofLineStatus(it) !in dismissed }

/** [row] with its line alert marked dismissed ([DepartureRow.statusDismissed]) if it's in [dismissed]. */
internal fun withDismissedMarked(row: DepartureRow, dismissed: Set<DismissedAlert>): DepartureRow {
    val status = row.status ?: return row
    return if (DismissedAlert.ofLineStatus(status) in dismissed) row.copy(status = null, statusDismissed = true) else row
}

/**
 * The key a leg's [row] opens its page by: the main screen's, but a bus leg's by its stop pair rather
 * than its pole, so the page stays open when the trip works out which side of the road its bus uses
 * ([onPoles]) and the row moves to that pole.
 */
internal fun tripDetailKey(leg: TripLeg, row: DepartureRow): String =
    row.copy(stopId = leg.fromArea.ifEmpty { row.stopId }).detailKey()

/**
 * Which planned leg a line page belongs to, stable across refreshes and re-plans (never its times):
 * its line, where it boards and gets off, and its path. Two routes boarding the same line at the same
 * stop but getting off elsewhere are different legs, and a page shows the one tapped. Only what
 * [onPoles] never changes: a bus leg's poles can move to the other side of the road once its route
 * loads, so an end is its stop pair where it has one, and where it gets off is by name otherwise
 * (a bus station's stand can move to the route's own stand of that name).
 */
internal fun tripLegKey(leg: TripLeg): String = listOf(
    leg.lineId,
    leg.fromArea.ifEmpty { leg.fromId },
    leg.toArea.ifEmpty { leg.toName },
    leg.path.joinToString(","),
).joinToString("|")

/**
 * Of a leg's live [rows] (split by direction or platform), the one holding [followed] — the train its
 * row's times lead with — so its page follows that train; else the first. Null with no rows.
 */
internal fun legRowFor(rows: List<DepartureRow>, followed: Departure?): DepartureRow? =
    rows.firstOrNull { followed != null && followed in it.upcoming } ?: rows.firstOrNull()

/** The row a leg with no live trains opens to: its line at its boarding stop and its status, timing nothing. */
internal fun legStatusRow(state: TripViewModel.State, leg: TripLeg, now: Instant): DepartureRow = DepartureRow(
    stopId = leg.fromId,
    stopName = leg.fromName,
    lineId = leg.lineId,
    lineName = leg.lineName,
    direction = "",
    directionKey = "",
    destination = leg.headings.firstOrNull() ?: leg.toName,
    mode = leg.mode,
    upcoming = emptyList(),
    fetchedAt = state.live[leg.fromId]?.fetchedAt ?: now,
    // Only a disruption, as a row's status always is: a good service is no alert.
    status = state.statuses[leg.lineId]?.takeIf { it.disrupted },
)

@Composable
private fun RideLeg(
    leg: TripLeg,
    first: Boolean,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    dismissed: Set<DismissedAlert>,
    onOpenDetail: (TripLeg, DepartureRow, RouteFocus?) -> Unit,
    onHideMode: ((String) -> Unit)?,
) {
    val groups = remember(leg, state, now, sequences, dismissed) {
        StopGrouping.groupByStop(DepartureRows.withoutDismissed(legRows(state, leg, now, sequences), dismissed))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (groups.isEmpty()) {
            StopGroupHeader(leg.fromName, qualifier = null, distanceLabel = null, firstOnScreen = first)
            // Tapped, its line's page all the same: its service alert and its stops; long-pressed,
            // "Hide ‹mode›" — the main screen's route row, as a line with no trains shows there.
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                // A dismissed alert leaves the row (it's how the leg opens its stops), without its chip.
                val statusRow = withDismissedMarked(legStatusRow(state, leg, now), dismissed)
                RouteRow(
                    row = statusRow,
                    isStarred = false,
                    starrable = false,
                    onToggleStar = {},
                    onOpenDetail = { row, focus -> onOpenDetail(leg, row, focus) },
                    onHideMode = onHideMode,
                ) {
                    LinePill(leg.lineName, leg.lineId, leg.mode)
                    Box(Modifier.weight(1f).padding(start = 8.dp)) {
                        statusRow.status?.let { DisruptionChip(it.description) }
                    }
                    // "Loading" while its times aren't in yet, as the first-leg row says; "–" once none can be shown.
                    Text(
                        if (legLoading(state, leg, sequences)) stringResource(R.string.trip_times_loading) else "–",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        } else {
            groups.forEachIndexed { index, group ->
                StopGroupHeader(group.stopName, group.qualifier, distanceLabel = null, firstOnScreen = first && index == 0)
                StopGroupCard(
                    group,
                    now,
                    starred = emptySet(),
                    onToggleStar = {},
                    starringAvailable = false,
                    onOpenDetail = { row, focus -> onOpenDetail(leg, row, focus) },
                    onHideMode = onHideMode,
                )
            }
        }
        Text(
            text = pluralStringResource(R.plurals.trip_stops_to, leg.stops, leg.stops, leg.toName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
