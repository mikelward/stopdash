package app.stopdash.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.HeadedCard
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.headedCards
import app.stopdash.domain.remainingAfter
import app.stopdash.domain.Headway
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.AlertStart
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.RideLines
import app.stopdash.domain.RouteFocus
import app.stopdash.domain.RouteLabel
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.RouteStops
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.Staleness
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.cleanDisruptionBody
import app.stopdash.domain.StopGroup
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.TflException
import app.stopdash.domain.TripClosures
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import app.stopdash.domain.riderLineName
import app.stopdash.domain.WalkingSpeed
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import app.stopdash.domain.PlacedStand
import app.stopdash.domain.alightingKey
import app.stopdash.domain.boardingKey
import app.stopdash.domain.busesSettled
import app.stopdash.domain.endPole
import app.stopdash.domain.isStop
import app.stopdash.domain.onPoles
import app.stopdash.domain.placedOnPoles
import app.stopdash.domain.placedStands
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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
    if (Staleness.isStale(stop.fetchedAt, now)) return null
    // On a loop or a reconverging line both ways can reach the alighting stop: only a train leaving
    // for the leg's next stop takes the Planner's path (and run time).
    return legFilter(leg, stop, now, sequences)?.leaving.orEmpty()
}

/**
 * The live trains that can take [leg]'s ride: its own line's ([legTrains]) and those of every other
 * line riding the same stretch between the same stops ([RideLines.timed]), which share its time on
 * board, and is checked as running. Null when none can be vouched for.
 */
internal fun rideTrains(
    state: TripViewModel.State,
    leg: TripLeg,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    lines: Map<TripLeg, RideLines>,
): List<Departure>? {
    val found = timingLines(state, leg, now, lines).map { legTrains(state, it, now, sequences) }
    return if (found.all { it == null }) null else found.flatMap { it.orEmpty() }.distinct()
}

/** Whether another line's own stops are checked open, as the trip holds its checks ([TripClosures.opens]). */
internal fun lineStopsOpen(state: TripViewModel.State, now: Instant): (TripLeg) -> Boolean =
    TripClosures.opens(state.closures, state.closuresUnknown, now)

/**
 * The lines whose trains may time [leg]'s ride: one list for [rideTrains] and [ridePredicted], so
 * the reason a withheld arrival gives always describes the trains that timed it.
 */
private fun timingLines(state: TripViewModel.State, leg: TripLeg, now: Instant, lines: Map<TripLeg, RideLines>): List<TripLeg> =
    // Another line times the ride only once checked as running, from stops checked open
    // ([RideLines.vouched]): a suspended line's predictions, one never checked, or one from a closed
    // stop mustn't make a route read as live. The Planner's own is held to the same once another line
    // keeps the ride usable, as its status no longer ranks the route.
    // Nor from a pole whose refresh failed: its held predictions would pass for current. The
    // Planner's line keeps its own rule, its held trains timing the ride as they age, though only
    // trains from a stop that refreshed show the ride running every few minutes ([rideRefreshed]).
    (lines[leg] ?: RideLines.only(leg)).timedRunning(rideStatuses(state), lineStopsOpen(state, now))
        .filter { it == leg || state.live[it.fromId]?.failed != true }

/**
 * Whether [leg]'s ride has arrivals from a refresh that succeeded: its own pole's last refresh didn't
 * fail, or another line that times it ([timingLines]) boards at a stop whose last one succeeded. The
 * ride isn't judged at the Planner's pole alone, so a failure there doesn't withhold a ride another
 * line keeps timing from fresh arrivals (Codex on #309).
 */
internal fun rideCurrent(state: TripViewModel.State, leg: TripLeg, now: Instant, lines: Map<TripLeg, RideLines>): Boolean =
    state.live[leg.fromId]?.failed != true ||
        timingLines(state, leg, now, lines).any { it != leg && state.live[it.fromId]?.failed == false }

/**
 * The trains of [leg]'s ride ([rideTrains]) from boarding stops whose last refresh succeeded: those
 * that may show it running every few minutes ([TripTiming.frequentAt]). The Planner line's trains held
 * from a pole that failed are left out, as they may have stopped running since.
 */
internal fun rideRefreshed(
    state: TripViewModel.State,
    leg: TripLeg,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    lines: Map<TripLeg, RideLines>,
): List<Departure> =
    timingLines(state, leg, now, lines).filter { state.live[it.fromId]?.failed != true }
        .flatMap { legTrains(state, it, now, sequences).orEmpty() }.distinct()

/**
 * The line statuses a ride's lines are judged by ([RideLines]): every status held, less those whose
 * latest check failed. A status kept from before a failed check is the last one known, not a current
 * one, so it mustn't let a line stand in for another or be offered as a way to go (Codex on #382),
 * as its own line page can't vouch for it either.
 */
internal fun rideStatuses(state: TripViewModel.State): Map<String, LineStatus> = state.statuses - state.statusFailedLines

/**
 * Whether [leg]'s own line, the Planner's, may still give its ride's times: a walk, or a ride whose
 * line is [RideLines.vouched]. One another line stands in for, not itself checked as running, has no
 * times of its own to give, timetabled or live: only a train of a line that takes the ride times it
 * then (Codex on #382).
 */
internal fun plannerVouched(state: TripViewModel.State, leg: TripLeg, now: Instant, lines: Map<TripLeg, RideLines>): Boolean =
    leg.isWalk || (lines[leg] ?: RideLines.only(leg)).vouched(leg, rideStatuses(state), lineStopsOpen(state, now))

/**
 * Whether a line other than [leg]'s own may take and time its ride ([RideLines.othersTime]: checked
 * as running, from stops checked open), so the route is ranked by it rather than by the Planner's
 * line alone (Codex on #309). Its trains' freshness isn't asked: that times the route, it doesn't
 * decide whether the ride can be taken.
 */
internal fun otherLineRuns(state: TripViewModel.State, leg: TripLeg, now: Instant, lines: Map<TripLeg, RideLines>): Boolean =
    !leg.isWalk && (lines[leg] ?: RideLines.only(leg)).othersTime(rideStatuses(state), lineStopsOpen(state, now))

/**
 * How many trains [leg]'s timing lines ([rideTrains]) have predicted at their boarding stops, before
 * any route check: for the reason a withheld arrival gives ([TripTiming.Withheld]), so trains a
 * route couldn't vouch for read apart from none predicted. Stale arrivals (D4) count none.
 */
internal fun ridePredicted(state: TripViewModel.State, leg: TripLeg, now: Instant, lines: Map<TripLeg, RideLines>): Int {
    if (leg.isWalk) return 0
    return timingLines(state, leg, now, lines).sumOf { line ->
        val stop = state.live[line.fromId] ?: return@sumOf 0
        if (Staleness.isStale(stop.fetchedAt, now)) return@sumOf 0
        Countdown.upcoming(stop.departures.filter { it.lineId == line.lineId }, now).size
    }
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
    val ahead = RouteStops.ahead(sequence, leg.fromId, train.destination, train.branch, leg.lineId, bus, RouteStops.boundOf(train.platform), train.direction, via = train.via) ?: return null
    return ahead.getOrNull(1)?.let { isStop(sequence, it.id, next) } == true
}

/**
 * Whether [leg]'s times aren't in yet: its boarding stop's arrivals not fetched, or, at a bus stop
 * pair, its route not loaded to say which side the bus uses, or its poles still being looked up; at
 * a bus station's stand, its route not loaded to say which stand the bus uses, or the one it says
 * ([onPoles]) not yet fetched, the Planner's standing in meanwhile (Codex, #398).
 */
internal fun legLoading(state: TripViewModel.State, leg: TripLeg, sequences: Map<String, LineSequence?>): Boolean = when {
    state.live[leg.fromId] == null -> true
    leg.fromArea.isNotEmpty() -> leg.lineId !in sequences || (state.refreshing && leg.fromArea !in state.areaPoles)
    leg.isBus -> leg.lineId !in sequences || onPoles(leg, sequences).fromId.let { it != leg.fromId && state.live[it] == null }
    else -> false
}

/**
 * Whether [route] can be started on the way: not while its origin is being found again
 * ([originUnconfirmed], the route may change with the new fix), nor while a bus leg isn't yet at
 * the poles or stands its bus uses ([busesSettled]). A started trip keeps the route as it was, so
 * it's started only once that's settled.
 */
internal fun canStart(route: TripRoute, sequences: Map<String, LineSequence?>, originUnconfirmed: Boolean): Boolean =
    !originUnconfirmed && busesSettled(route, sequences)

/** Whether an open route can be followed on the way at all ([canFollow]), and can start now ([canStart]). */
internal data class StartCheck(val canFollow: Boolean, val canStart: Boolean)

/** A [StartCheck] with the very inputs it was worked out for, compared by identity so a read never walks them. */
private class CheckedStart(
    val route: TripRoute,
    val sequences: Map<String, LineSequence?>,
    val originUnconfirmed: Boolean,
    val check: StartCheck,
)

/**
 * [OnTheWay.canFollow] and [canStart] for [route], worked out on the screen's worker ([LocalWorker])
 * whenever its inputs change: each walks the route, and a bus route's check its line routes' stops,
 * too much for composition (AGENTS.md *Main thread: read and dispatch only*). Null until worked out
 * for these very inputs, and with no route: an answer for inputs since changed (a relocation begun,
 * another route opened) is never read, so Start waits rather than starting on a stale yes.
 */
@Composable
internal fun rememberStartCheck(
    route: TripRoute?,
    sequences: Map<String, LineSequence?>,
    originUnconfirmed: Boolean,
    follow: (TripRoute) -> Boolean = OnTheWay::canFollow,
    start: (TripRoute, Map<String, LineSequence?>, Boolean) -> Boolean = ::canStart,
): StartCheck? {
    val worker = LocalWorker.current
    // Keyed by identity, as the guard below reads: a route rebuilt equal on a refresh is checked again,
    // never left waiting on an answer the guard won't read.
    val checked by produceState<CheckedStart?>(null, ByIdentity(route), ByIdentity(sequences), originUnconfirmed, worker) {
        if (route == null) return@produceState
        val check = withContext(worker) {
            val canFollow = follow(route)
            StartCheck(canFollow, canFollow && start(route, sequences, originUnconfirmed))
        }
        value = CheckedStart(route, sequences, originUnconfirmed, check)
    }
    return checked?.takeIf { it.route === route && it.sequences === sequences && it.originUnconfirmed == originUnconfirmed }?.check
}

/**
 * [state] with each route's legs [onPoles] — where the trip fetches the chosen pole: one of its
 * pair's looked-up poles ([TripViewModel.State.areaPoles]), read as "Loading" until its arrivals are
 * in. After a failed lookup the trip never fetches it, so the Planner's pole stands (the lookup is
 * asked again on the next refresh).
 */
internal fun onPoles(state: TripViewModel.State, sequences: Map<String, LineSequence?>): TripViewModel.State = placed(state, sequences).first

/**
 * [onPoles], with where each leg boards and gets off as one number from the same pass over the legs
 * ([placementOf] for the routes it returns): the frame's guard against a held frame standing in once a
 * leg moves, published with the placed routes it's of, never after them (Codex, #529).
 */
internal fun placed(state: TripViewModel.State, sequences: Map<String, LineSequence?>): Pair<TripViewModel.State, Int> {
    val routes = state.routes ?: return state to 1
    fun fetched(leg: TripLeg, pole: String) = pole in state.live || pole in state.areaPoles[leg.fromArea].orEmpty()
    var placement = 1
    val placed = routes.map { route ->
        TripRoute(
            route.legs.map { leg ->
                (onPoles(leg, sequences).takeIf { it.fromId == leg.fromId || fetched(leg, it.fromId) } ?: leg)
                    .also { placement = placeLeg(placement, it) }
            },
        )
    }
    return (if (placed == routes) state else state.copy(routes = placed)) to placement
}

// [placement] with [leg]'s boarding and alighting stops folded in.
private fun placeLeg(placement: Int, leg: TripLeg): Int = (placement * 31 + leg.fromId.hashCode()) * 31 + leg.toId.hashCode()

/**
 * Judges the live trains of each leg [state]'s routes and their other lines ([rideLines]) ride
 * ([TripVerdicts.warm]); true when any was judged anew. Slow on a main-line railway: on a worker only.
 */
@WorkerThread
internal fun warmVerdicts(
    state: TripViewModel.State,
    sequences: Map<String, LineSequence?>,
    rideLines: Map<TripLeg, RideLines>,
    // False once the warm-up is superseded: it then stops, leaving the newer one's verdicts in place.
    active: () -> Boolean = { true },
): Boolean {
    val legs = (state.routes.orEmpty().flatMap { it.legs } + rideLines.values.flatMap { it.legs }).filterNot { it.isWalk }.distinct()
    if (!active()) return false
    TripVerdicts.makeRoom(legs, active)
    var warmed = false
    for (leg in legs) {
        val route = sequences[leg.lineId] ?: continue
        val trains = state.live[leg.fromId]?.departures?.filter { it.lineId == leg.lineId }.orEmpty()
        if (!active()) return false
        if (TripVerdicts.warm(leg, route, trains, active)) warmed = true
    }
    return warmed
}

// The routes as loaded, renewed whenever more trains are judged on them: a key the page's derived
// values are remembered under, so they're worked out again with the new verdicts.
private class JudgedRoutes(routes: Map<String, LineSequence?>) : Map<String, LineSequence?> by routes

// A leg's upcoming trains judged on its line's route ([legFilter]): the [result] as [DirectTrips.filter]
// gives it, and of the trains it keeps, those [leaving] along the leg ([leavesAlongLeg]).
private class LegJudgement(val result: DirectTrips.Result, val leaving: List<Departure>)

private val CHECKING = LegJudgement(DirectTrips.Result(emptyList(), pending = true, unresolved = false), emptyList())

// [leg]'s line's upcoming trains at [stop] judged on their routes; null when the line has none. Read
// from [TripVerdicts], worked out off the main thread: while a train hasn't been judged, the leg reads
// as its route still loading ([DirectTrips.Result.pending]).
private fun legFilter(
    leg: TripLeg,
    stop: TripViewModel.StopLive,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): LegJudgement? {
    val line = Countdown.upcoming(stop.departures.filter { it.lineId == leg.lineId }, now)
    if (line.isEmpty()) return null
    // The origin itself is no destination: From and To the same station is no trip.
    if (leg.fromId == leg.toId) return LegJudgement(DirectTrips.Result(emptyList(), pending = false, unresolved = false), emptyList())
    // No line to follow: they may well call there, so never a silent "no".
    if (leg.lineId.isBlank()) {
        val miss = RouteMiss(leg.lineId, leg.fromId, RouteStops.Resolution.NoLine, leg.headings.firstOrNull().orEmpty())
        return LegJudgement(DirectTrips.Result(emptyList(), pending = false, unresolved = true, misses = setOf(miss)), emptyList())
    }
    if (leg.lineId !in sequences) return CHECKING
    // A failed route can't tell: logged by its fetch.
    val route = sequences[leg.lineId] ?: return LegJudgement(DirectTrips.Result(emptyList(), pending = false, unresolved = true), emptyList())
    val judged = line.map { train -> train to (TripVerdicts.get(leg, route, train) ?: return CHECKING) }
    var unresolved = false
    val misses = LinkedHashSet<RouteMiss>()
    val kept = judged.filter { (_, verdict) ->
        when (val reach = verdict.reach) {
            DirectTrips.Verdict.Reaches -> true
            DirectTrips.Verdict.Misses -> false
            is DirectTrips.Verdict.Unknown -> {
                unresolved = true
                reach.miss?.let { misses += it }
                false
            }
        }
    }
    val stops = if (kept.isEmpty()) emptyList() else listOf(StopArrivals(leg.fromId, leg.fromName, kept.map { it.first }, stop.fetchedAt))
    return LegJudgement(
        DirectTrips.Result(stops, pending = false, unresolved = unresolved, misses = misses),
        kept.filter { (_, verdict) -> verdict.leaves != false }.map { it.first },
    )
}

/**
 * Whether [leg]'s line's trains at its boarding stop are judged on its route: its route loaded (or
 * failed) and each upcoming train worked out ([TripVerdicts]). Until then the leg reads as loading.
 */
private fun legChecked(leg: TripLeg, stop: TripViewModel.StopLive, now: Instant, sequences: Map<String, LineSequence?>): Boolean =
    leg.lineId in sequences && legFilter(leg, stop, now, sequences) !== CHECKING

/**
 * While [leg]'s line's route is still loading (absent from [sequences]), or its trains are still
 * being judged on it ([TripVerdicts]), its live trains at the
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
    if (leg.isWalk) return emptyList()
    val stop = state.live[leg.fromId] ?: return emptyList()
    if (legChecked(leg, stop, now, sequences)) return emptyList()
    // A bus stop the Planner named by its pair: which side the bus uses isn't known until its route
    // is, and the other side's buses run the other way.
    if (leg.fromArea.isNotEmpty()) return emptyList()
    if (Staleness.isStale(stop.fetchedAt, now)) return emptyList()
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

/** Why a trip's live trains may not all have been checked ([tripCheckState]). */
internal enum class TripMessage { CHECKING, INCOMPLETE }

/**
 * Whether some listed route's live trains couldn't be checked against where the rider gets off:
 * [TripMessage.CHECKING] while any line's route loads, then [TripMessage.INCOMPLETE] if one failed or a
 * train's path couldn't be followed; null when every train was checked. Such a leg falls back to the
 * Planner's time, and this says why, rather than pass the fallback off as "no live train".
 */
internal fun tripCheckState(
    state: TripViewModel.State,
    estimates: List<TripTiming.Estimate>,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    lines: Map<TripLeg, RideLines> = emptyMap(),
): TripMessage? {
    val results = legChecks(state, estimates, now, sequences, lines)
    return when {
        // Still checking comes first: one line failing says nothing yet of the others, whose trains
        // can still change the cards (Codex, #543).
        results.any { it.pending } -> TripMessage.CHECKING
        results.any { it.unresolved } -> TripMessage.INCOMPLETE
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
    lines: Map<TripLeg, RideLines> = emptyMap(),
): Set<RouteMiss> = legChecks(state, estimates, now, sequences, lines).flatMapTo(LinkedHashSet()) { it.misses }

// Each ridden leg's live trains, and each of its other lines' ([lines]), judged on their route: legs
// with fresh arrivals and trains only.
private fun legChecks(
    state: TripViewModel.State,
    estimates: List<TripTiming.Estimate>,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    lines: Map<TripLeg, RideLines>,
): List<DirectTrips.Result> =
    estimates.flatMap { it.route.rides }.distinct().flatMap { lines[it]?.legs ?: listOf(it) }.distinct().mapNotNull { leg ->
        val stop = state.live[leg.fromId] ?: return@mapNotNull null
        if (Staleness.isStale(stop.fetchedAt, now)) return@mapNotNull null
        legFilter(leg, stop, now, sequences)?.result
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
    lines: Map<TripLeg, RideLines> = rideLines(state.routes.orEmpty(), state, sequences, hidden),
    // The open route's key: timed past the cap while the plan offers it ([TripViewModel.bestOf]).
    keep: String? = null,
    // Every leg the Planner planned, when some routes ride one it didn't (a train through a change):
    // such a leg is timed only by a live train ([TripTiming.estimate]'s timetabled). Null: all are.
    planned: Set<TripLeg>? = null,
): List<TripTiming.Estimate>? {
    val routes = state.shownRoutes(hidden)?.let { TripViewModel.bestOf(it, keep) } ?: return null
    val notRunning = TripTiming.notRunning(state.statuses.values)
    // A line with no status known (left out of TfL's answer, or a failed check) can't be vouched
    // for as running.
    // A route shown only now (its mode shown again) waits for its lines' status like a new plan's.
    val unknown = state.statusUnknown +
        routes.flatMap { route -> route.rides.map { it.lineId } }.filterNot { it in state.statuses }
    val estimates = routes.map { route ->
        TripTiming.estimate(
            route, now, access, { index -> rideTrains(state, route.legs[index], now, sequences, lines) }, notRunning, unknown,
            current = { index -> rideCurrent(state, route.legs[index], now, lines) },
            refreshed = { index -> rideRefreshed(state, route.legs[index], now, sequences, lines) },
            predicted = { index -> ridePredicted(state, route.legs[index], now, lines) },
            // Nor one whose Planner line another line stands in for, unless it's running ([plannerVouched]).
            timetabled = { index -> (planned == null || route.legs[index] in planned) && plannerVouched(state, route.legs[index], now, lines) },
            // A stop it boards or gets off at closed ranks it below every usable route, as a line
            // not running does; one not yet checked, with the unchecked (SPEC *Trips with a change*).
            // A bus stop is judged at its pole once its line's route gives one ([endPole]).
            stops = TripClosures.standing(route, state.closures, state.closuresUnknown, now) { end -> endPole(route, end, sequences) },
            // A ride another running line can take and time isn't sunk by its Planner line's status.
            otherLine = { index -> otherLineRuns(state, route.legs[index], now, lines) },
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

// Which card a route shares: its first ride's mode and ends (by stop pair for a bus, or by the stop
// the Planner named, as [onPoles] places it), and the lines after it with where each gets off, since
// the card names those stops ([RideStops]) for every route on it; a route with no ride keeps its own.
internal fun cardKey(route: TripRoute): String {
    val first = route.rides.firstOrNull() ?: return routeKey(route)
    val to = alightingKey(first)
    val after = route.rides.drop(1).joinToString("|") { "${it.mode}:${it.lineId}>${alightingKey(it)}" }
    return "${first.mode}:${boardingKey(first)}>$to|$after"
}

/**
 * The lines whose route data a trip loads: [settled] while a first plan's answers are still landing,
 * else [timedLineIds] and the other lines at those rides' boarding stops ([rideLineIds]). A re-plan keeps the last plan until it lands whole, so that plan's lines load
 * at once, even on a screen shown again with nothing settled yet.
 */
internal fun sequenceLineIds(state: TripViewModel.State, hidden: Set<String>, settled: List<String>, keep: Collection<String> = emptyList()): List<String> {
    if (state.planning && state.plannedAt == null) return settled
    val shown = state.shownRoutes(hidden).orEmpty()
    // And every other line at a timed ride's boarding stop, to tell whether it serves the ride's
    // stops too ([rideLines]): a route each, loaded once a day like the rest.
    val timed = TripViewModel.bestOf(shown.filterNot { route -> route.rides.any { HiddenModes.isHidden(it.mode, it.lineId, hidden) } }, keep)
    return (timedLineIds(shown, hidden, keep) + rideLineIds(timed, state, hidden)).distinct()
}

/**
 * The lines of the routes a trip times: not riding a [hidden] mode, and within the cap, or the open
 * route ([keep]) past it ([TripViewModel.bestOf]).
 */
internal fun timedLineIds(routes: List<TripRoute>, hidden: Set<String>, keep: Collection<String> = emptyList()): List<String> =
    TripViewModel.bestOf(routes.filterNot { route -> route.rides.any { HiddenModes.isHidden(it.mode, it.lineId, hidden) } }, keep)
        .flatMap { route -> route.rides.map { it.lineId } }.distinct()

/**
 * Whether the [open] route, on a settled plan (not [TripViewModel.State.planning], its routes in),
 * is gone for good ([OpenRoute.routeIn]): the plan no longer offers it (a new plan without it, or its
 * mode hidden), whether as planned or by the route with the change its train through is made from, or
 * a line it rides itself is hidden (a train through a change can run on a line that route doesn't). Only the plan and the rider's own choices
 * decide: a train through a change that isn't predicted now leaves it open (maintainer, 2026-09-29).
 */
internal fun openRouteGone(state: TripViewModel.State, hidden: Set<String>, open: OpenRoute): Boolean {
    val route = open.routeIn(state.shownRoutes(hidden).orEmpty()) ?: return true
    return route.rides.any { HiddenModes.isHidden(it.mode, it.lineId, hidden) }
}

/** A route's identity across refreshes and re-ranking: its lines and stops in order. */
internal fun routeKey(route: TripRoute): String =
    // A bus leg by its stop pairs, or by the stop the Planner named, so its key holds once its poles
    // or stands are worked out ([onPoles]).
    route.legs.joinToString("|") { leg -> "${leg.mode}:${leg.lineId}:${boardingKey(leg)}:${alightingKey(leg)}" }

// How many of a first leg's trains its row times.
private const val SHOWN_TRAINS = 3

internal val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("mm")
internal val LONDON: ZoneId = ZoneId.of("Europe/London")

/**
 * When the rider last pulled on the routes to [destKey] ([TripViewModel.pullRefresh]), for the screen
 * to [TripViewModel.carryPull] into a trip that takes the pulled one's place. Kept past a new nearest
 * stop, a trip model of its own, and past a configuration change, which keeps the fix a pull from
 * here is waiting on (Codex, PR #373).
 */
@Composable
internal fun rememberLastPull(destKey: String): MutableState<Instant?> =
    rememberSaveable(destKey) { mutableStateOf(null) }

/**
 * A trip with a change (SPEC *Trips with a change*): the routes best first, every route alike — its
 * line pills, ⚠ on a disrupted leg, and duration · arrival, over its first leg's live trains — and,
 * once one is tapped, that route leg by leg in the list's own header and route cards. Renders from
 * [state] alone; the caller refreshes it on the list's foreground tick. Its trains are checked
 * against each line's route from [routeStops], which a caller must give: with none, no route would
 * load and every train would stay "checking".
 */
/**
 * [statuses] as of [now]'s day in London ([LineStatus.asOf]): planned work whose day has come shows as
 * under way, however long ago it was fetched (Codex, PR #337), as a kept status outlives the day it was
 * sorted on. Sorted on that day already ([sortedOn], the earliest any was), they're shown as they are.
 * Sorted on an earlier day, they're brought up to it on [LocalWorker], never in composition (AGENTS.md
 * *Main thread*; Codex on #519), as it goes through every alert under way; until that's in, none is
 * shown, so each line reads as still being checked rather than as sorted on a day gone by (Codex on #519).
 */
@Composable
internal fun rememberStatusesAsOf(statuses: Map<String, LineStatus>, sortedOn: LocalDate?, now: Instant): Map<String, LineStatus> {
    val today = now.atZone(AlertStart.ZONE).toLocalDate()
    val slot = remember { mutableStateOf<Worked<Inputs, Map<String, LineStatus>>?>(null) }
    val current = sortedOn == null || !sortedOn.isBefore(today)
    val worked = rememberWorked(slot, Inputs(statuses, if (current) null else today)) {
        if (current) statuses else LineStatus.asOf(statuses, now)
    }
    return if (current) statuses else worked ?: emptyMap()
}

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
    // A line row's long-press "Hide ‹mode›", as on the list (null: no menu), and the Undo it then
    // offers, showing the group or line just hidden again (null: no Undo).
    onHideMode: ((String) -> Unit)? = null,
    onUnhideMode: ((String) -> Unit)? = null,
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
    // Start an open route on the way (SPEC *On the way*); null offers no Start.
    onStart: ((TripRoute) -> Unit)? = null,
    // With a trip already on the way, open it beside Start, whose tap then asks before this route
    // takes its place ([onReplaceTrip]; null offers only the open).
    onOpenTrip: (() -> Unit)? = null,
    onReplaceTrip: ((TripRoute) -> Unit)? = null,
    // Why each timed route's arrival is withheld (null: it shows), by route key, for the debug log
    // ([TripViewModel.noteWithheld]).
    onWithheld: (Map<String, TripTiming.Withheld?>) -> Unit = {},
    // The rider's walking speed atop the routes, and a pick of another; null shows no picker.
    walkingSpeed: WalkingSpeed = WalkingSpeed.AVERAGE,
    onWalkingSpeedChange: ((WalkingSpeed) -> Unit)? = null,
    // A pick that didn't save, said once as a mode hidden from here is, then acknowledged.
    walkingSpeedWriteFailed: Boolean = false,
    onWalkingSpeedWriteFailureShown: () -> Unit = {},
    // The longest walk the routes may take, under the speed, and a pick of another; null shows no picker.
    maxWalk: MaxWalk = MaxWalk.DEFAULT,
    onMaxWalkChange: ((MaxWalk) -> Unit)? = null,
    maxWalkWriteFailed: Boolean = false,
    onMaxWalkWriteFailureShown: () -> Unit = {},
    // How step-free the routes must be, and a pick of another; null shows no picker.
    stepFree: StepFree = StepFree.DEFAULT,
    onStepFreeChange: ((StepFree) -> Unit)? = null,
    stepFreeWriteFailed: Boolean = false,
    onStepFreeWriteFailureShown: () -> Unit = {},
    // Which kinds of transport the routes may ride, and a change of them; null shows no chips.
    tripModes: TripModes = TripModes.DEFAULT,
    onTripModesChange: ((TripModes) -> Unit)? = null,
    tripModesWriteFailed: Boolean = false,
    onTripModesWriteFailureShown: () -> Unit = {},
    // Whether the walking speed, max walk, step-free level and trip modes have been read from storage:
    // until then their pickers show no value and respond to nothing, so a pick can't be saved over a
    // choice not yet read.
    planOptionsLoaded: Boolean = true,
    // The From/To bar in place of [title] over the routes (maintainer, 2026-09-28): where the trip
    // starts and where it goes, each a tap to change. Null (a test) shows the title.
    ends: TripEnds? = null,
    // A pull on the routes (maintainer, 2026-09-29): plan again and fetch every stop afresh
    // ([TripViewModel.pullRefresh]); null offers no pull. [pullRefreshing] holds its indicator.
    pullRefreshing: Boolean = false,
    onPullRefresh: (() -> Unit)? = null,
    // The stops the routes shown are judged at that only the screen can name ([shownStops]), for the
    // trip to check too ([TripViewModel.checkShownStops]).
    onShownStops: (Set<String>) -> Unit = {},
    // The stands buses board at in place of the one the Planner named ([placedStands]), for the trip
    // to fetch too ([TripViewModel.boardAt]).
    onPlacedStands: (Set<PlacedStand>) -> Unit = {},
    // The lines the rider avoids ([AvoidedLines]; SPEC *Trips with a change → Avoiding a line*): routes
    // riding one are left out, each a chip atop the routes that a tap stops avoiding
    // ([onStopAvoiding]); a card's long press offers to avoid each line it rides ([onAvoidLine]).
    // Null offers neither. A change that didn't save is said once, then acknowledged.
    avoidedLines: Set<String> = emptySet(),
    onAvoidLine: ((String) -> Unit)? = null,
    onStopAvoiding: ((String) -> Unit)? = null,
    avoidedLinesWriteFailed: Boolean = false,
    onAvoidedLinesWriteFailureShown: () -> Unit = {},
    // Which journey this is (its origin and destination): another, a new nearest stop's included,
    // starts the list afresh, never under the last journey's order, disruptions or banner (Codex, #543),
    // and what the page worked out for another never stands in for it, though the screen stays put as a
    // re-locate moves the trip to another model.
    journey: Any? = null,
) {
    val statuses = rememberStatusesAsOf(state.statuses, state.statusesSortedOn, now)
    val state = remember(state, statuses) { state.copy(statuses = statuses) }
    CompositionLocalProvider(LocalRouteStops provides routeStops, LocalTripJourney provides journey) {
        TripContent(
            title, state, now, access, onBack, onRetry, locationBanner, relocating, onRelocate,
            hiddenModes, onShowAllModes, onHideMode, onUnhideMode, hiddenModesWriteFailed, onHiddenModesWriteFailureShown, menu, openRoute,
            TripAlerts(dismissed, onDismissAlert, dismissWriteFailed, onDismissWriteFailureShown),
            onStart,
            onOpenTrip,
            onReplaceTrip,
            onWithheld,
            walkingSpeed,
            onWalkingSpeedChange,
            walkingSpeedWriteFailed,
            onWalkingSpeedWriteFailureShown,
            maxWalk,
            onMaxWalkChange,
            maxWalkWriteFailed,
            onMaxWalkWriteFailureShown,
            stepFree,
            onStepFreeChange,
            stepFreeWriteFailed,
            onStepFreeWriteFailureShown,
            tripModes,
            onTripModesChange,
            tripModesWriteFailed,
            onTripModesWriteFailureShown,
            planOptionsLoaded,
            ends,
            pullRefreshing,
            onPullRefresh,
            onShownStops,
            onPlacedStands,
            avoided = TripAvoided(avoidedLines, onAvoidLine, onStopAvoiding, avoidedLinesWriteFailed, onAvoidedLinesWriteFailureShown),
            tripKey = journey,
        )
    }
}

/**
 * Whether the trip page's last frame, worked out for [held], may stand in for [wanted] while its own is
 * worked out (Codex, #529): only while what changed is the data and the clock (a refresh, a route
 * loaded, a tick), never what the rider chose or is told (the trip, the walk, the routes hidden or
 * avoided, whether the origin is confirmed, the alerts dismissed, the planning options: walking speed,
 * longest walk, step-free level, modes), nor once something the frame vouched for has failed (a plan,
 * a status or closure check, a stop's refresh) or the trip is planned again, and only for [FRAME_HOLD] (a
 * tick), never frozen at an old time past it. [held] and [wanted] are the frame's [Inputs], in the page's order.
 */
private fun mayStandIn(held: Inputs, wanted: Inputs): Boolean {
    val heldAt = held.parts[FRAME_TIME] as Instant
    val wantedAt = wanted.parts[FRAME_TIME] as Instant
    // A route opened waits for its own frame: the list's, standing in, would draw the list again, the tap
    // seeming to do nothing (Codex, #529). Closed, the list's frame in hand shows at once.
    val opened = wanted.parts[FRAME_OPEN] != null && !Inputs.same(held.parts[FRAME_OPEN], wanted.parts[FRAME_OPEN])
    return !opened && FRAME_CHOICES.all { Inputs.same(held.parts[it], wanted.parts[it]) } && Duration.between(heldAt, wantedAt).abs() <= FRAME_HOLD
}

// Where the frame's inputs (see the page's `Inputs`) hold its time, and what the rider chose or is told:
// the trip, the walk, the routes left out, whether the origin is unconfirmed, the alerts dismissed, the
// planning options, what has failed, the plan, and where its legs board and get off.
private const val FRAME_TIME = 2
// And the route open, if any ([TripFrame.openKey]).
private const val FRAME_OPEN = 9
private val FRAME_CHOICES = intArrayOf(0, 3, 5, 6, 10, 13, 14, 15, 16, 17, 18, 20)

/** Where [state]'s routes' legs board and get off, as one number: a leg moved, another number ([placed]'s). */
@WorkerThread
internal fun placementOf(state: TripViewModel.State): Int =
    state.routes.orEmpty().fold(1) { hash, route -> route.legs.fold(hash, ::placeLeg) }

/**
 * How long the page's last frame may stand in: one tick (10 s, `tickingNow`) and its drift, so the next
 * tick draws the last frame while its own is worked out, a moment, never a blank page for it. Its
 * countdowns, staleness, departures gone, connections still caught and notices in force are then at most
 * a tick behind, as the tick itself already is; a worker later than that and the page waits for its own
 * (Codex, #529). Not cut at each such boundary instead: a tick crossing one, as most would with a board
 * of trains, would draw the page without a frame until its own is in, a flash each time.
 */
private val FRAME_HOLD: Duration = Duration.ofSeconds(15)

/**
 * A trip page's two ends, for its From/To bar: the start ([fromStation], or "Here" when null) and the
 * destination ([toName]). A tap on From opens the From… search, and one on To the To… search; the
 * other end is kept either way.
 */
internal class TripEnds(
    val fromStation: String?,
    val toName: String,
    val onChangeFrom: () -> Unit,
    val onChangeTo: () -> Unit,
)

/** The lines a trip avoids, and what a long press or a chip does with them (see [TripScreen]). */
private class TripAvoided(
    val lines: Set<String> = emptySet(),
    val onAvoid: ((String) -> Unit)? = null,
    val onStopAvoiding: ((String) -> Unit)? = null,
    val writeFailed: Boolean = false,
    val onWriteFailureShown: () -> Unit = {},
)

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
    // The tick the page is worked out for; what it draws is the frame's own ([TripFrame.now]).
    tickNow: Instant,
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
    onUnhideMode: ((String) -> Unit)? = null,
    hiddenModesWriteFailed: Boolean = false,
    onHiddenModesWriteFailureShown: () -> Unit = {},
    menu: AppMenuActions? = null,
    openRoute: MutableState<String?>? = null,
    alerts: TripAlerts = TripAlerts(emptySet(), null, false) {},
    onStart: ((TripRoute) -> Unit)? = null,
    onOpenTrip: (() -> Unit)? = null,
    onReplaceTrip: ((TripRoute) -> Unit)? = null,
    onWithheld: (Map<String, TripTiming.Withheld?>) -> Unit = {},
    walkingSpeed: WalkingSpeed = WalkingSpeed.AVERAGE,
    onWalkingSpeedChange: ((WalkingSpeed) -> Unit)? = null,
    walkingSpeedWriteFailed: Boolean = false,
    onWalkingSpeedWriteFailureShown: () -> Unit = {},
    maxWalk: MaxWalk = MaxWalk.DEFAULT,
    onMaxWalkChange: ((MaxWalk) -> Unit)? = null,
    maxWalkWriteFailed: Boolean = false,
    onMaxWalkWriteFailureShown: () -> Unit = {},
    stepFree: StepFree = StepFree.DEFAULT,
    onStepFreeChange: ((StepFree) -> Unit)? = null,
    stepFreeWriteFailed: Boolean = false,
    onStepFreeWriteFailureShown: () -> Unit = {},
    tripModes: TripModes = TripModes.DEFAULT,
    onTripModesChange: ((TripModes) -> Unit)? = null,
    tripModesWriteFailed: Boolean = false,
    onTripModesWriteFailureShown: () -> Unit = {},
    planOptionsLoaded: Boolean = true,
    ends: TripEnds? = null,
    pullRefreshing: Boolean = false,
    onPullRefresh: (() -> Unit)? = null,
    onShownStops: (Set<String>) -> Unit = {},
    onPlacedStands: (Set<PlacedStand>) -> Unit = {},
    avoided: TripAvoided = TripAvoided(),
    tripKey: Any? = null,
) {
    // What the routes leave out: the hidden modes and lines, and the lines avoided ([AvoidedLines]).
    // Only the hidden ones are the "hidden" banner's: an avoided line is said by its own chip.
    val excluded = remember(hiddenModes, avoided.lines) { AvoidedLines.excluded(hiddenModes, avoided.lines) }
    // The open route, kept twice: by the trip when it's given one ([openRoute]), which outlasts the
    // screen leaving composition (an overlay) and, saved by the trip, the process too; and saved with
    // the screen, for a trip that holds none. Read from the trip first; set in both.
    val savedOpenKey = rememberSaveable { mutableStateOf<String?>(null) }
    val heldOpenKey = remember(openRoute) {
        openRoute?.also { if (it.value == null) it.value = savedOpenKey.value } ?: savedOpenKey
    }
    // Saved as text ([OpenRoute.encode]), read back whole.
    val openRef = remember(heldOpenKey.value) { OpenRoute.parse(heldOpenKey.value) }
    fun setOpen(open: OpenRoute?) {
        val saved = open?.encode()
        heldOpenKey.value = saved
        savedOpenKey.value = saved
    }
    // Only the timed routes' lines: a hidden mode's routes, and those past the cap (but the open one,
    // and its train through a change), load no route data. While a plan's answers are still landing,
    // the last settled plan's lines stand, so a passing top six never starts loads a later answer
    // would make pointless.
    val settledLines = remember { arrayOf(emptyList<String>()) }
    val lineIds = remember(planned.routes, excluded, planned.planning, planned.live, planned.areaPoles, openRef) {
        (sequenceLineIds(planned, excluded, settledLines[0], openRef?.keys.orEmpty()) + listOfNotNull(openRef?.ride?.lineId))
            .distinct().also { settledLines[0] = it }
    }
    val loads = rememberLineLoads(lineIds, tickNow)
    val routeSequences = loads.sequences
    // Each bus leg at the poles its bus uses, once its route says which (the Planner's may be the
    // other side of the road); everything below reads the trip this way, with the routes a train
    // running through a change offers without it ([withThroughRoutes]).
    // And where each leg boards and gets off, as one number from the same pass ([placed]), published with
    // the placed routes: a held frame never stands in once a leg moves, whatever moved it (a stop pair
    // placed, a stand fetched, a route refreshed; Codex, #529), compared as a number, never by walking
    // the routes again.
    val (poled, placement) = remember(planned, routeSequences) { placed(planned, routeSequences) }
    // A bus station's stand a bus boards at in place of the Planner's is placed only once the trip
    // has fetched it ([onPoles]), so it's handed to the trip to fetch ([placedStands]).
    val stands = remember(planned, routeSequences, excluded, openRef) {
        placedStands(TripViewModel.bestOf(planned.shownRoutes(excluded).orEmpty(), openRef?.keys.orEmpty()), routeSequences)
    }
    LaunchedEffect(stands) { onPlacedStands(stands) }
    // The open route as the plan offers it now ([OpenRoute.routeIn]): its walks at the current pace,
    // and a train through a change whether or not one is predicted. Null while no plan offers it.
    val opened = remember(poled, excluded, openRef) { openRef?.routeIn(poled.shownRoutes(excluded).orEmpty()) }
    val openKey = opened?.let(::routeKey)
    val liveState = remember(poled, routeSequences, excluded, opened) { withThroughRoutes(poled, routeSequences, excluded, opened) }
    // Every leg the Planner planned: a leg it didn't (a train through a change) needs a live train.
    val plannedLegs = remember(poled) { poled.routes.orEmpty().flatMapTo(HashSet()) { it.legs } }
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
    // Each ride's lines ([rideLines]): worked out once per refresh and route load, not on every tick.
    val liveRideLines = remember(liveState, routeSequences, excluded) { rideLines(liveState.routes.orEmpty(), liveState, routeSequences, excluded) }
    // Each leg's live trains judged on its line's route on the worker ([TripVerdicts]), on every
    // refresh and route load; a train not judged yet reads as its route still loading. Once a warm-up
    // ends, the same routes come anew ([JudgedRoutes]) so what reads the verdicts reads them again.
    val worker = LocalWorker.current
    var judged by remember { mutableIntStateOf(0) }
    LaunchedEffect(liveState, routeSequences, liveRideLines) {
        withContext(worker) { warmVerdicts(liveState, routeSequences, liveRideLines) { isActive } }
        // Read again however the verdicts came: a warm-up superseded mid-way may have written the last
        // of them after this composition looked, leaving this one nothing new to judge.
        judged++
    }
    val liveSequences: Map<String, LineSequence?> = remember(routeSequences, judged) { JudgedRoutes(routeSequences) }
    // The lines loading key the frame by their version ([LineLoads.loadingVersion]), never their contents;
    // the frame reads the live set on the worker.
    val routeStops = LocalRouteStops.current
    // Everything the page draws on a tick ([tripFrame]), worked out on the worker, never in composition
    // (AGENTS.md *Main thread*): the estimates, the cards and the open route, and what frames them. The
    // last frame stands in while the next is worked out; the page is drawn against the frame's own state,
    // time, routes and ride lines, never newer ones it doesn't hold yet (as the near-me list's, #524).
    val frameSlot = remember { mutableStateOf<Worked<Inputs, TripFrame>?>(null) }
    // The order the last frame drew its cards in, so the next hands it back when nothing moved ([listedCards]).
    val previousOrder = frameSlot.value?.value?.list?.listed?.keys
    val frame = rememberWorked(
        frameSlot,
        Inputs(tripKey, liveState, tickNow, access, liveSequences, excluded, originUnconfirmed, liveRideLines, plannedLegs, openKey, alerts.dismissed, loads.loadingVersion, routeStops,
            // The planning options: the frame a plan under the old ones was worked out for never stands in.
            walkingSpeed, maxWalk, stepFree, tripModes,
            // What has failed since ([TripViewModel.State.failures]): a frame from before a failed refresh
            // never stands in once it fails (Codex, #529).
            liveState.failures,
            // The plan, one list until it's planned again: a new plan's frame waits for the worker, so a
            // route it dropped never lingers (#535).
            planned.routes,
            // The routes as planned and placed, which a tapped card opens from ([TripListView.opens]).
            poled,
            placement),
        keep = ::mayStandIn,
    ) {
        tripFrame(tripKey, liveState, tickNow, access, liveSequences, excluded, originUnconfirmed, liveRideLines, plannedLegs, openKey, alerts.dismissed, loads.loading, routeStops, poled, previousOrder)
    }
    // What the page draws against: the frame's own state, time, routes and ride lines.
    val state = frame?.state ?: liveState
    val now = frame?.now ?: tickNow
    // Try again judged by the live state, not the frame's: a frame held from before a retry began still
    // draws its button, and a second tap mustn't plan again behind the first (Codex, #529).
    val planningNow by rememberUpdatedState(liveState.planning)
    val retryNow by rememberUpdatedState(onRetry)
    val onRetry = remember { { if (!planningNow) retryNow() } }
    val sequences = frame?.sequences ?: liveSequences
    val rideLines = frame?.rideLines ?: liveRideLines
    val estimates = frame?.estimates
    // A withheld arrival leaves its reason in the debug log: a side effect, off composition.
    LaunchedEffect(estimates) {
        estimates?.let { list -> onWithheld(list.associate { routeKey(it.route) to it.withheld }) }
    }
    // The stops only the screen can name, handed to the trip to check ([shownStops]).
    val shownStops = frame?.shownStops
    LaunchedEffect(shownStops) { shownStops?.let(onShownStops) }
    // The list's cards; an open route is looked up among every way timed, so it stays open whichever
    // way its card shows.
    val cards = frame?.cards
    // Only the route the frame opened, if it's still the one open: a frame worked out before a route was
    // opened or closed doesn't stand in for that.
    val open = frame?.takeIf { it.openKey == openKey }?.open
    // The open route stays open while the plan offers it ([openRouteGone]), past the cap too
    // ([TripViewModel.bestOf]); once a settled plan doesn't — a new plan without it (another walking
    // speed, a re-plan), or its mode hidden — it's closed for good, so it never reopens unbidden
    // should it come back. A plan still landing ([TripViewModel.State.planning],
    // or no routes yet) keeps it, since the route may be in the part still to come.
    LaunchedEffect(openRef, poled.routes, poled.planning, excluded) {
        if (openRef != null && poled.routes != null && !poled.planning && openRouteGone(poled, excluded, openRef)) setOpen(null)
    }
    // A route asked for closes on Back even before its frame is in, never leaving the trip (Codex, #529).
    // The on-screen arrow too.
    val back = { if (openRef != null) setOpen(null) else onBack() }
    BackHandler(onBack = back)
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
    val detailLegs = remember(open, estimates, rideLines) {
        // Every line of each ride ([RideLines]): a row of one the Planner didn't name opens its page too.
        (open?.let { listOf(it) } ?: estimates.orEmpty()).flatMap { it.route.rides }.distinct()
            .flatMap { rideLines[it]?.legs ?: listOf(it) }.distinct()
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
        // Another line not yet checked as running has its no-trains row on the open route though its
        // trains are in ([rideLegRows]): that row opens its page too, as a list card's rows still do.
        val ride = rideLines.values.firstOrNull { detailLeg in it.legs } ?: RideLines.only(detailLeg)
        val statusRow = withDismissedMarked(legStatusRow(state, detailLeg, now), alerts.dismissed)
        val rows = when {
            live.isEmpty() -> listOf(statusRow)
            // First, so a live row keyed alike (a departure with no direction, destination or
            // platform) can't open the countdown the open route withholds.
            !ride.vouched(detailLeg, rideStatuses(state), lineStopsOpen(state, now)) -> listOf(statusRow) + DepartureRows.withoutDismissed(live, alerts.dismissed)
            else -> DepartureRows.withoutDismissed(live, alerts.dismissed)
        }
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
    // Not while the page waits for its frame (a re-locate, a new walk): the page is judged gone only
    // against a frame that lacks it.
    LaunchedEffect(detailKey, detailRow == null, frame == null) {
        if (detailKey != null && detailRow == null && frame != null) detailKey = null
    }
    if (detailRow != null) {
        RouteDetailScreen(
            // With its stop's notice in force, dismissed or not, so the page never calls a closed or
            // moved stop clean.
            row = detailRow.copy(stopDisruption = stopNotice(state.closures[detailRow.stopId], now)),
            isStarred = false,
            starrable = false,
            // Vouched clean, as the list's page is, only once both checks are known and current: the
            // line's status (and its own last check didn't fail, nor is it as old as a stale countdown)
            // and its stop's own closure check, the stop judged as the route's ranking judges it
            // ([legStopUnchecked]).
            disruptionUnknown = detailRow.lineId.isBlank() || detailRow.lineId in state.statusFailedLines || detailRow.lineId !in state.statuses ||
                !checkCurrent(state.statusesAt[detailRow.lineId], now) ||
                detailRow.stopId !in state.closures || detailLeg?.let { legStopUnchecked(it, state, now, sequences) } != false,
            // Stale too once its stop's last refresh failed: the held arrivals no longer stand as
            // current, and this page doesn't carry the route's failure banner.
            stale = Staleness.isStale(detailRow.fetchedAt, now) ||
                state.live[detailRow.stopId]?.failed == true,
            now = now,
            onToggleStar = {},
            onBack = { detailKey = null },
            focus = detailDestination?.let { RouteFocus(it, detailBranch) },
            // A leg with no train to follow still shows its line's stops, from the leg's own route.
            onDismissAlert = alerts.onDismiss?.takeIf { detailRow.status != null }?.let { dismiss -> { dismiss(detailRow) } },
            // A planned alert dismissed on its own, as on the list's page: a row standing for just it.
            onDismissPlanned = alerts.onDismiss?.let { dismiss ->
                { planned -> dismiss(detailRow.copy(status = null, stopDisruption = null, plannedAlerts = listOf(planned))) }
            },
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
    // A hide from a card offers Undo for a moment, as on the list.
    val hideMode = rememberHideWithUndo(onHideMode, onUnhideMode, snackbarHostState)
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
    // A walking speed picked here that didn't save: the trip is planned at it, but it won't last.
    val walkingSpeedWriteFailedMessage = stringResource(R.string.settings_walking_speed_write_failed)
    LaunchedEffect(walkingSpeedWriteFailed) {
        if (walkingSpeedWriteFailed) {
            onWalkingSpeedWriteFailureShown()
            snackbarHostState.showSnackbar(walkingSpeedWriteFailedMessage)
        }
    }
    // A walk limit picked here that didn't save, likewise.
    val maxWalkWriteFailedMessage = stringResource(R.string.max_walk_write_failed)
    LaunchedEffect(maxWalkWriteFailed) {
        if (maxWalkWriteFailed) {
            onMaxWalkWriteFailureShown()
            snackbarHostState.showSnackbar(maxWalkWriteFailedMessage)
        }
    }
    // A step-free level picked here that didn't save, likewise.
    val stepFreeWriteFailedMessage = stringResource(R.string.step_free_write_failed)
    LaunchedEffect(stepFreeWriteFailed) {
        if (stepFreeWriteFailed) {
            onStepFreeWriteFailureShown()
            snackbarHostState.showSnackbar(stepFreeWriteFailedMessage)
        }
    }
    // A line avoided here, or no longer, that didn't save, likewise.
    val avoidedWriteFailedMessage = stringResource(R.string.hidden_modes_write_failed)
    LaunchedEffect(avoided.writeFailed) {
        if (avoided.writeFailed) {
            avoided.onWriteFailureShown()
            snackbarHostState.showSnackbar(avoidedWriteFailedMessage)
        }
    }
    // A mode turned on or off here that didn't save, likewise.
    val tripModesWriteFailedMessage = stringResource(R.string.trip_modes_write_failed)
    LaunchedEffect(tripModesWriteFailed) {
        if (tripModesWriteFailed) {
            onTripModesWriteFailureShown()
            snackbarHostState.showSnackbar(tripModesWriteFailedMessage)
        }
    }
    // The key of the route whose Start is asking to take the trip on the way's place, until answered:
    // saved, so a rotation keeps the question, and asked only while that route is the one open
    // ([openKey], already its key, so nothing is rebuilt here or on the tap).
    var confirmReplace by rememberSaveable { mutableStateOf<String?>(null) }
    // The question is about the trip on the way when it was asked: once that trip ends here (arrived,
    // ended elsewhere), it goes, so a trip started next never reopens it. Not on a restored screen's
    // first frame, where no trip is known yet; a plain Start below clears it for that case.
    val replaceAvailable = onReplaceTrip != null
    var hadReplace by remember { mutableStateOf(replaceAvailable) }
    LaunchedEffect(replaceAvailable) {
        if (hadReplace && !replaceAvailable) confirmReplace = null
        hadReplace = replaceAvailable
    }
    // Whether the open route can be followed, and start now, for Start and Replace alike: not known
    // (null) until worked out for what's shown now, when Start shows but waits.
    val startCheck = rememberStartCheck(open?.route, sequences, originUnconfirmed)
    val openCanStart = startCheck?.canStart == true
    // Only a route known not to be followable drops Start: one not worked out yet keeps its place.
    val cantFollow = startCheck?.canFollow == false
    val replacement = open?.route?.takeIf { onReplaceTrip != null && confirmReplace != null && confirmReplace == openKey }
    if (replacement != null) {
        AlertDialog(
            onDismissRequest = { confirmReplace = null },
            title = { Text(stringResource(R.string.on_the_way_replace_title)) },
            confirmButton = {
                // As Start: not until the route can be followed, which a restored screen may not
                // know yet (its buses' stands still loading).
                TextButton(
                    onClick = {
                        confirmReplace = null
                        onReplaceTrip?.invoke(replacement)
                    },
                    enabled = openCanStart,
                ) { Text(stringResource(R.string.on_the_way_replace)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // An open route starts on the way from here: followed by its train to the destination. Above
        // the system navigation bar, as the app draws edge to edge.
        bottomBar = {
            if (open != null && onStart != null && onOpenTrip != null) {
                // One trip at a time: the one on the way opens, or this route takes its place once the
                // rider says so, since ending it can't be undone. A route that can't be followed only opens.
                val replace = onReplaceTrip?.takeIf { !cantFollow }
                // At least 56dp, growing as one for a label that wraps (a large font, a narrow phone),
                // so neither is clipped and both stay the same height.
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val openModifier = Modifier.heightIn(min = 56.dp).fillMaxHeight().let { if (replace != null) it.weight(1f) else it.fillMaxWidth() }
                    if (replace == null) {
                        Button(onClick = onOpenTrip, modifier = openModifier) {
                            Text(stringResource(R.string.on_the_way_open_current))
                        }
                    } else {
                        OutlinedButton(onClick = onOpenTrip, modifier = openModifier) {
                            Text(stringResource(R.string.on_the_way_open_current))
                        }
                        Button(
                            onClick = { confirmReplace = openKey },
                            enabled = openCanStart,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight(),
                        ) {
                            Text(stringResource(R.string.on_the_way_start))
                        }
                    }
                }
            } else if (open != null && onStart != null && cantFollow) {
                Text(
                    stringResource(R.string.on_the_way_cant_follow_rail),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (open != null && onStart != null) {
                Button(
                    onClick = {
                        confirmReplace = null
                        onStart(open.route)
                    },
                    enabled = openCanStart,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
                ) {
                    Text(stringResource(R.string.on_the_way_start))
                }
            }
        },
        topBar = {
            val overflow: @Composable () -> Unit = {
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
            }
            // Over the routes, where the trip starts and where it goes, each a tap to change
            // (maintainer, 2026-09-28); over one route opened, its title, as Back returns to the routes.
            if (ends != null && open == null) {
                TripEndsBar(
                    fromStation = ends.fromStation,
                    onChangeFrom = ends.onChangeFrom,
                    onBack = back,
                    labelWidth = rememberTripEndsLabelWidth(),
                    actions = if (menu != null) overflow else null,
                    readToLabel = false,
                ) { modifier -> ToField(ends.toName, ends.onChangeTo, modifier) }
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = { overflow() },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // With a route open, only its own legs' warnings frame it; the list takes every route's
            // ([TripFraming]): whichever is shown, from the frame in hand.
            val framing = if (open != null) frame?.openView?.framing else frame?.list?.framing
            val check = framing?.check
            // The plan the list shows: its options. Another (a cached one switched to included) starts
            // the list afresh, never under the last one's order, disruptions or banner (Codex, #543).
            val planKey = listOf(LocalTripJourney.current, walkingSpeed, maxWalk, stepFree, tripModes)
            // The list's own check, every card's: it gates the list kept behind an open route (its row
            // and its reveal), so a route restored open never lets the list show on a check of that route
            // alone (Codex, #543). The frame works the list out even with a route open, on the worker
            // (AGENTS.md *Main thread*), so it's the list's own; until a frame with the list is in, the list
            // counts as still checking, so its gate waits rather than open on a guess.
            val listCheck = frame?.list.let { if (it == null) TripMessage.CHECKING else it.framing.check }
            // Which trains the banner means, logged once per distinct set, off composition.
            val misses = framing?.misses.orEmpty()
            LaunchedEffect(routeStops, misses) { routeStops?.reportMisses(misses) }
            // The search's choices head the routes only: an opened route is the one chosen, so its page
            // shows its own legs, not the pickers and chips that choose among routes (maintainer,
            // 2026-10-04). By the route held open, not only the one found: while a new plan runs none is
            // found, and the choices mustn't flash back above it (Codex, #545).
            if (open == null && openRef == null) {
                // The walking speed first: a pick plans again.
                if (onWalkingSpeedChange != null) {
                    WalkingSpeedPicker(walkingSpeed, onWalkingSpeedChange, enabled = planOptionsLoaded)
                }
                // The walk limit sits under it: the two decide together which walks the routes can take.
                if (onMaxWalkChange != null) {
                    MaxWalkPicker(maxWalk, onMaxWalkChange, enabled = planOptionsLoaded)
                }
                // Step-free under them: it too changes which routes the Planner offers.
                if (onStepFreeChange != null) {
                    StepFreePicker(stepFree, onStepFreeChange, enabled = planOptionsLoaded)
                }
                // The kinds of transport the routes may ride, last: chips, a tap each, rather than a menu.
                if (onTripModesChange != null) {
                    TripModeChips(tripModes, onTripModesChange, enabled = planOptionsLoaded)
                }
                // The lines avoided, under the modes: each a chip a tap stops avoiding.
                avoided.onStopAvoiding?.let { AvoidedLineChips(avoided.lines, it) }
            }
            // Settled here, once, so the list's reveal waits on the banner it would otherwise slide under (Codex, #543).
            // Per surface, so a return from an open route never brings its banner back to the list, and per
            // plan, so one plan's failure never stands over another's placeholder, and per routes left out, so a
            // failure of a route hidden or avoided never stands over those that remain (Codex, #543).
            val incomplete = key(open != null, cards == null, planKey, excluded) { settled(check == TripMessage.INCOMPLETE, at = false) }
            TripBanners(framing?.failed.orEmpty(), check, incomplete, locationBanner, onRelocate, hiddenModes, onShowAllModes)
            // Hold still (SPEC *Engineering quality bar*): the list appears once, after its plan, its live
            // refresh and its routes' checks have landed, rather than settle under the rider's
            // thumb as each lands (maintainer, 2026-10-04). Never longer than [REVEAL_CAP_MILLIS].
            // Not saved: a rotation or a recreated process loses the list's work, so the list waits for
            // it again rather than show raw cards that settle under the rider (Codex, #543).
            val revealedState = remember(planKey) { mutableStateOf(false) }
            // The row's work goes with the list's, and also with what's left out of it: a mode hidden or a
            // line avoided never leaves its pill over the cards that remain (Codex, #543). The cards' order
            // stays, so they slide rather than jump.
            // Started afresh with a plan that is, so one plan's row never stands over another's (Codex, #543).
            val rowWork = remember(cards == null, planKey, excluded) { mutableStateOf<Worked<Inputs, TripRow>?>(null) }
            // The cards' order and headers, worked out with the cards in the page's frame ([TripListView.listed]):
            // a new plan's cards never show unheaded and then head and re-sort under the rider (Codex, #543).
            val headed = frame?.list?.listed
            // Worked out behind the placeholder too, and drawn at once until the list shows: it's never
            // seen changing before then (Codex, #543).
            // From the live state, not the frame's: a status that changed while the frame is still being
            // worked out turns the row to "Checking…", never leaves "None" standing over it.
            val row = cards?.let {
                rememberTripRow(it, liveRideLines, liveState, tickNow, liveSequences, alerts.dismissed, loads.loading, listCheck == TripMessage.CHECKING, rowWork, hold = revealedState.value)
            }
            rememberRevealed(
                revealedState,
                cards != null,
                // A plan in hand, though its frame is still being worked out (a line avoided, a mode
                // hidden): the list shown stays shown rather than wait out its reveal again.
                liveState.routes != null,
                // The plan and refresh as they are now, not as the frame last drew them: a frame still
                // being worked out never holds the list back past the moment both have landed.
                liveState,
                listCheck,
                // Everything the list draws is in: its banner, its cards' order, its disruptions, and every
                // route loaded (a line with no trains predicted can't make the check wait on it, Codex, #543).
                // The banner shown is an open route's, not the list's: the list's own settles at once on return.
                settledAround = (open != null || incomplete == (check == TripMessage.INCOMPLETE)) && headed != null &&
                    row?.checking == false && loads.loading.isEmpty(),
            )
            val revealed = revealedState.value
            Box(Modifier.fillMaxSize()) {
                when {
                    // The plan in, its routes still being worked out ([tripFrame]): checking, as the list's
                    // own wait says, rather than the placeholder that waits for a plan.
                    cards == null && frame == null && state.routes != null && state.planError == null -> RoutesChecking()
                    cards == null -> TripPlaceholder(state, onRetry)
                    open == null && !revealed -> RoutesChecking()
                    // Both from the frame, which worked out the open route ([TripFrame.openView]) and the list's cards ([TripFrame.list]).
                    open != null && frame?.openView != null -> RouteLegs(open, frame.openView, rideLines, state, now, frame.access, sequences, onRetry, alerts.dismissed, alerts.onDismiss, hideMode, ::openDetail, loads.loading, check == TripMessage.CHECKING)
                    else -> {
                        val routes = @Composable {
                            RouteList(
                                frame?.list ?: TripListView(TripFraming(null, emptySet(), emptyList()), emptyList(), emptyMap(), ListedCards(emptyList(), emptyList(), CardOrder(emptyList(), emptyList()))), rideLines, state, now, onRetry,
                                // What the card opens, worked out with the frame it's drawn from: a train through a
                                // change keeps the planned route it's made from, though newer arrivals no longer
                                // list it, and the tap only reads (Codex, #529).
                                onOpen = { setOpen(frame?.list?.opens?.get(routeKey(it.route)) ?: OpenRoute(routeKey(it.route))) },
                                onHideMode = hideMode,
                                onAvoidLine = avoided.onAvoid,
                                row = row ?: TripRow.CHECKING,
                            )
                        }
                        // Pulled down, the routes are planned again and every stop fetched afresh, from
                        // the same ends at the same pace (maintainer, 2026-09-29).
                        if (onPullRefresh == null) {
                            routes()
                        } else {
                            PullToRefreshBox(pullRefreshing, onPullRefresh, Modifier.fillMaxSize().testTag("tripRoutesPull")) { routes() }
                        }
                    }
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
/**
 * The boarding stops of [estimates]' rides whose last refresh failed, by name: every line of each ride
 * ([RideLines]), so another line's trains held from a pole that failed never read as fresh.
 */
internal fun failedStops(estimates: List<TripTiming.Estimate>, state: TripViewModel.State, rideLines: Map<TripLeg, RideLines>): List<String> =
    estimates.flatMap { it.route.rides }
        .flatMap { ride -> rideLines[ride]?.legs ?: listOf(ride) }
        .filter { state.live[it.fromId]?.failed == true }
        .map { it.fromName }
        .distinct()

@Composable
private fun TripBanners(
    // The shown routes' boarding stops whose last refresh failed ([failedStops]), worked out with the frame.
    failed: List<String>,
    check: TripMessage?,
    // The "couldn't be checked" banner, settled by the caller ([settled]).
    incomplete: Boolean,
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
    if (failed.isNotEmpty()) {
        val which = if (failed.size == 1) failed[0] else stringResource(R.string.partial_refresh_more, failed[0], failed.size - 1)
        Banner(stringResource(R.string.partial_refresh_no_reason, which))
    }
    // A route still loading says so in the disruptions row over the routes, which holds its place
    // ([DisruptionsRow]), rather than in a banner that came and went over the list (maintainer,
    // 2026-10-04). One that couldn't be checked says so here, once that has held ([settled]).
    if (incomplete) Banner(stringResource(R.string.journey_incomplete))
    if (hiddenModes.isNotEmpty()) {
        ActionBanner(
            text = stringResource(R.string.modes_hidden, hiddenGroupsLabel(hiddenModes)),
            actionLabel = stringResource(R.string.modes_show_all),
            onAction = onShowAllModes,
        )
    }
}

/** The longest a trip's list is held back while what it shows lands ([rememberRevealed]). */
internal const val REVEAL_CAP_MILLIS = 8_000L

/**
 * Whether a trip's list is shown yet: once [hasCards], with no plan landing, no live refresh running
 * and no line's route still loading ([check]); or [REVEAL_CAP_MILLIS] after the cards
 * came, whatever is still out, so a check that never answers can't hide the routes. Once shown it
 * stays shown, refreshes and re-plans included: from then on a card moves only when what it says
 * changes. Timed on frames, as a screenshot test drives them.
 */
@Composable
private fun rememberRevealed(
    revealedState: MutableState<Boolean>,
    hasCards: Boolean,
    hasPlan: Boolean,
    state: TripViewModel.State,
    check: TripMessage?,
    settledAround: Boolean,
) {
    var revealed by revealedState
    val ready = hasCards && !state.refreshing && !state.planning && check != TripMessage.CHECKING && settledAround
    // Keyed on the holder too: each plan's holder gets its own cap timer (Codex, #543).
    LaunchedEffect(revealedState, hasCards, hasPlan, ready) {
        // No plan means one starting afresh, a process recreated included (its plans are memory-only),
        // so a restored reveal mustn't let the new plan's cards show as they land (Codex, #543).
        if (!hasPlan) revealed = false
        if (revealed || !hasCards) return@LaunchedEffect
        if (!ready) {
            val start = withFrameMillis { it }
            while (withFrameMillis { it } - start < REVEAL_CAP_MILLIS) Unit
        }
        revealed = true
    }
}

/** In place of a trip's list while what it shows lands ([rememberRevealed]). */
@Composable
private fun RoutesChecking() {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.trip_checking), style = MaterialTheme.typography.bodyLarge)
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
    // The list as the page's frame worked it out ([TripListView]): its cards, each with what it draws.
    view: TripListView,
    // Each ride's lines ([rideLines]), shown together on its pill, their trains together.
    rideLines: Map<TripLeg, RideLines>,
    state: TripViewModel.State,
    // The frame's own time ([TripFrame.now]), which its cards were worked out for.
    now: Instant,
    onRetry: () -> Unit,
    onOpen: (TripTiming.Estimate) -> Unit,
    onHideMode: ((String) -> Unit)?,
    // A card's long press also offers to avoid each line it rides ([AvoidedLines]); null offers not.
    onAvoidLine: ((String) -> Unit)? = null,
    // The row over the cards ([DisruptionsRow]), worked out on the worker and held as one ([rememberTripRow]).
    row: TripRow,
) {
    val cards = view.cards
    // Which card gets there soonest, which rides fewest and which walks least, over each, then
    // the rest under "Other" (maintainer, 2026-09-30): worked out with the cards in the page's frame
    // ([TripListView.listed]), so a card never shows without its header, nor under another's.
    val listed = view.listed.cards
    val order = view.listed.order
    // Cards re-sort as their times move (maintainer, 2026-10-04), sliding to their new places rather
    // than jumping, and a tap on a card while it moves is dropped: it could land on the card that just
    // moved under the finger. Each card watches its own place ([rememberSliding]), whatever moved it.
    val density = LocalDensity.current
    // Remembered, so the keys below hold the same measure from one composition to the next.
    val pillWidth = rememberPillWidth()
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
        if (cards.isNotEmpty()) item(key = "status") { DisruptionsRow(row) }
        if (cards.isEmpty()) {
            item(key = "none") { Text(stringResource(R.string.trip_no_routes), style = MaterialTheme.typography.bodyLarge) }
        }
        // Routes sharing every stop but differing in their first line are one card: one header, a
        // row per ride, and the first ride's times for every line together. The card is one choice
        // (maintainer, 2026-09-27): tapping it opens the best of its routes, and a long press
        // anywhere offers to hide each group any of its legs rides.
        // Each card under its own header, both from the frame.
        items(listed.size, key = { cardKey(listed[order[it].index].first().route) }) { position ->
            val header = order[position].header
            val listedCard = listed[order[position].index]
            // What the card draws, from the frame ([TripCardView]): its alerts, its walk, its stops' notices
            // and its first ride's trains, worked out on the worker against the frame's time, with the order.
            val shown = view.byKey[cardKey(listedCard.first().route)]
            Column(
                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(CARD_MOVE_MILLIS.toInt())),
            ) {
                if (shown != null) {
                    val card = shown.card
                    var menuOpen by remember { mutableStateOf(false) }
                    val onLongPress = if (onHideMode != null && shown.modes.isNotEmpty()) ({ menuOpen = true }) else null
                    val moreLabel = stringResource(R.string.more_actions)
                    CardHeader(header)
                    Box {
                        // Each row takes the card's tap and long press itself: a clickable card would merge
                        // its rows into one, and a screen reader would lose the rows' own times.
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            // The header, then a row per ride saying where it gets off (maintainer,
                            // 2026-09-27): one tap target, so a card changing at Highgate reads apart from
                            // one changing at Archway.
                            val sliding = rememberSliding()
                            Column(
                                modifier = Modifier
                                    .sliding(sliding)
                                    .combinedClickable(
                                        enabled = !sliding.moving,
                                        onLongClickLabel = onLongPress?.let { moreLabel },
                                        onLongClick = onLongPress,
                                        onClick = { onOpen(card.first()) },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CardHeader(card, rideLines, shown.statuses, shown.walk)
                                // The card's pill column: its widest pill ([pillSlotWidthPx]), so its rows' stop names
                                // start in one place, a cut pill's row too. Per card, not across the list (maintainer,
                                // 2026-10-04): a list-wide width fell back to each card's own, then to a lone pill's,
                                // every time a line's status or route landed while the page loaded, and the rows
                                // shuffled sideways for the first few seconds. Worked out on the worker apart from the
                                // frame, so a font scale changing (a pinch) measures again without the frame (Codex,
                                // #530); the last width holds while a new one is worked out, since what loads rarely
                                // changes a card's pills.
                                val widthSlot = remember { mutableStateOf<Worked<Inputs, Int?>?>(null) }
                                val columnPx = rememberWorked(widthSlot, Inputs(card, rideLines, pillWidth), keep = { _, _ -> true }) {
                                    pillSlotWidthPx(listOf(card), rideLines, pillWidth.measure())
                                }
                                RideStops(card, rideLines, shown.statuses, shown.closures, shown.times, now, shown.walk, columnPx?.let { with(density) { it.toDp() } })
                            }
                        }
                        if (onHideMode != null) {
                            HideModeMenu(
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                modes = shown.modes,
                                onHideMode = onHideMode,
                                lines = shown.lines,
                                onAvoidLine = onAvoidLine,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The journey the trip screen shows ([TripScreen]'s `journey`), read where the list's work is kept. */
internal val LocalTripJourney = staticCompositionLocalOf<Any?> { null }

/**
 * A card's header ([RouteLabelHeader]), grown and shrunk over a slide's time ([CARD_MOVE_MILLIS]) as it
 * comes and goes. The card's own place in the list may not move, so its placement animation alone
 * would leave the card jumping by the header's height (Codex, #543). One shown with the card isn't
 * animated in; one going keeps its last labels until it has shrunk away.
 */
@Composable
internal fun CardHeader(header: List<RouteLabel>) {
    // The labels drawn: the header's own, or its last while it shrinks away. A value the content reads,
    // so a header changing to other labels redraws them.
    val last = remember { arrayOf(header) }
    if (header.isNotEmpty()) last[0] = header
    val shown = last[0]
    AnimatedVisibility(
        visible = header.isNotEmpty(),
        enter = expandVertically(tween(CARD_MOVE_MILLIS.toInt()), expandFrom = Alignment.Top),
        exit = shrinkVertically(tween(CARD_MOVE_MILLIS.toInt()), shrinkTowards = Alignment.Top),
    ) {
        // Its gap to the card goes with it, so nothing jumps by the spacing either. A header changing to
        // a longer or shorter one (a line more when it wraps) grows or shrinks the same way (Codex, #543).
        Box(Modifier.animateContentSize(tween(CARD_MOVE_MILLIS.toInt())).padding(bottom = 4.dp)) { RouteLabelHeader(shown) }
    }
}

/**
 * The bold header over a card: "Fastest", "Simplest", "Least walking", several of them ("Fastest ·
 * Simplest"), or "Other" ([headedCards]), read as a heading.
 */
@Composable
private fun RouteLabelHeader(labels: List<RouteLabel>) {
    val names = labels.map { label ->
        stringResource(
            when (label) {
                RouteLabel.FASTEST -> R.string.trip_label_fastest
                RouteLabel.SIMPLEST -> R.string.trip_label_simplest
                RouteLabel.LEAST_WALKING -> R.string.trip_label_least_walking
                RouteLabel.OTHER -> R.string.trip_label_other
            },
        )
    }
    Text(
        text = names.joinToString(stringResource(R.string.trip_label_separator)),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() }.testTag("routeLabel"),
    )
}

/**
 * The lines [card] shows for its ride [index], one pill for them all: every line of each of its
 * routes' ride there ([RideLines]), the best route's own first. None ranks above another; each is a
 * way between the same two stops.
 */
internal fun cardRideLines(card: List<TripTiming.Estimate>, index: Int, rideLines: Map<TripLeg, RideLines>): List<TripLeg> =
    card.mapNotNull { it.route.rides.getOrNull(index) }.flatMap { rideLines[it]?.legs ?: listOf(it) }.distinctBy { it.lineId }

/** The lines of [card]'s ride [index] as its pill draws them ([cardRideLines]). */
internal fun cardRidePill(card: List<TripTiming.Estimate>, index: Int, rideLines: Map<TripLeg, RideLines>): List<LineRef> =
    cardRideLines(card, index, rideLines).map { LineRef(it.lineId, it.lineName, it.mode) }

/**
 * The width, in pixels, of the widest pill among [cards]' ride rows, [widthPx] giving a pill's
 * ([sharedPillWidthPx]); null for none. Each row of a card has its pill column that wide, so the card's
 * stop names line up (maintainer, 2026-10-04: a cut pill's row stood out to the right). Every lone
 * pill is one width, so only each distinct cut pill is measured besides. Measures text: on a worker only.
 */
internal fun pillSlotWidthPx(cards: List<List<TripTiming.Estimate>>, rideLines: Map<TripLeg, RideLines>, widthPx: (List<LineRef>) -> Int): Int? =
    cards.asSequence()
        .flatMap { card -> card.first().route.rides.indices.asSequence().map { cardRidePill(card, it, rideLines) } }
        .distinctBy { pill -> if (pill.size == 1) emptyList() else cutPillCodes(pill) }
        .maxOfOrNull(widthPx)

/**
 * How to measure a pill's width ([sharedPillWidthPx]) at the screen's density and font scale, in the
 * pills' label style. Compared by those values, so a key holding it changes only when they do.
 */
internal data class PillWidth(
    val density: Density,
    val style: TextStyle,
    val fontFamilyResolver: FontFamily.Resolver,
    val layoutDirection: LayoutDirection,
) {
    /**
     * A pill's width in pixels. Each call has its own [TextMeasurer], so the list's and each card's
     * workers never share one's cache. Measures text: on a worker only.
     */
    fun measure(): (List<LineRef>) -> Int {
        val measurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, cacheSize = 0)
        return { pill -> sharedPillWidthPx(pill, density) { code -> measurer.measure(code, style, maxLines = 1).size.width } }
    }
}

/** The screen's [PillWidth], the same from one composition to the next while nothing it measures by changes. */
@Composable
private fun rememberPillWidth(): PillWidth {
    val density = LocalDensity.current
    val style = pillLabelStyle()
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    return remember(Density(density.density, density.fontScale), style, resolver, direction) {
        PillWidth(Density(density.density, density.fontScale), style, resolver, direction)
    }
}

/** Every line any route on a trip's card rides, in the order they're ridden, for its "Hide ‹line›" items. */
internal fun cardLines(card: List<TripTiming.Estimate>, rideLines: Map<TripLeg, RideLines> = emptyMap()): List<LineRef> =
    card.asSequence()
        // Every line a ride's pill names ([RideLines]), so each can be hidden from where it shows.
        .flatMap { estimate -> estimate.route.rides.asSequence().flatMap { ride -> (rideLines[ride]?.legs ?: listOf(ride)).asSequence() } }
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
private fun RouteSummary(
    card: List<TripTiming.Estimate>,
    rideLines: Map<TripLeg, RideLines>,
    statuses: Map<String, LineStatus>,
    modifier: Modifier = Modifier,
) {
    val estimate = card.first()
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val rides = estimate.route.rides
        if (rides.isEmpty()) {
            // All walking (two stops close together): no line to show, and no live row below.
            Text(stringResource(R.string.trip_walk_only), style = MaterialTheme.typography.titleMedium)
        }
        rides.forEachIndexed { index, _ ->
            val lines = cardRideLines(card, index, rideLines)
            SharedLinePill(
                lines.map { LineRef(it.lineId, it.lineName, it.mode) },
                lines.map { riderLineName(it.lineName, it.mode) }.reduce { a, b -> stringResource(R.string.trip_lines_either, a, b) },
            )
        }
        // One ⚠ just before the arrival, where every screen puts it before the times (maintainer,
        // 2026-09-28), reading out each disrupted line by name when there's more than one.
        val warning = linesWarning(rides.indices.flatMap { cardRideLines(card, it, rideLines) }.distinctBy { it.lineId }, statuses)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            // A FlowRow keeps a weighted item on the pills' line when its min intrinsic width fits,
            // which for text is its longest word: "Arrival unknown" stayed beside the pills and was
            // cut to "Arrival". The whole line's width moves it below them instead, ⚠ and all.
            modifier = Modifier.weight(1f).width(IntrinsicSize.Max).padding(start = 12.dp),
        ) {
            // 8dp before the time, as the main screen spaces its ⚠.
            if (warning != null) {
                DisruptionWarningGlyph(warning, Modifier.padding(end = 8.dp))
            } else {
                // Work still to come on one of its lines: the muted calendar, as on the main screen's rows.
                linesPlanned(rides.indices.flatMap { cardRideLines(card, it, rideLines) }, statuses)
                    ?.let { PlannedAlertGlyph(it, Modifier.padding(end = 8.dp)) }
            }
            Text(
                text = arrivalText(estimate),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
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
 * [card]'s first-ride trains ([CardTimes]): each route's usable trains along its first ride, on
 * every line serving its two stops ([RideLines], [legTrains]), or while its route is checked the line's trains as the main screen shows them
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
    rideLines: Map<TripLeg, RideLines> = emptyMap(),
): CardTimes {
    val trains = ArrayList<Departure>()
    val usable = HashSet<Departure>()
    val checking = HashSet<Departure>()
    var loading = false
    // Every line of each route's first ride ([RideLines]): each is a way between the same two stops.
    val lines = card.flatMap { estimate ->
        estimate.route.rides.firstOrNull()?.let { ride -> (rideLines[ride]?.legs ?: listOf(ride)).map { it to ride } }.orEmpty()
    }.distinctBy { it.first }
    lines.forEach { (leg, ride) ->
        val live = legTrains(state, leg, now, sequences)
        // A line not yet checked as running ([RideLines.vouched]) shows its trains as still being
        // checked, never as catchable: it may be suspended. That's the Planner's own too, once
        // another line is what keeps the ride usable.
        if (!(rideLines[ride] ?: RideLines.only(ride)).vouched(leg, rideStatuses(state), lineStopsOpen(state, now))) {
            trains += live.orEmpty()
            checking += live.orEmpty()
            return@forEach
        }
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
    // Each later ride's trains, every one of its lines checked as running ([RideLines.running]) along
    // its route ([legTrains]); none while stale or unchecked: a suspended line's leftover predictions
    // would make the connection read as more frequent than it is.
    val headways = card.first().route.rides.drop(1).map { ride ->
        linesHeadway((rideLines[ride] ?: RideLines.only(ride)).running(rideStatuses(state), lineStopsOpen(state, now)), state, now, sequences)
    }
    return CardTimes(shown, checking, reachable, loading, headways)
}

/**
 * How often [lines] run together between their stops ([Headway]), from their live trains along the
 * ride ([legTrains]): the card's figure for a later ride, and each line's own on the open route's.
 * Null when none of their trains can be vouched for, or too few are known.
 */
internal fun linesHeadway(
    lines: List<TripLeg>,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): Headway.Range? {
    val found = lines.map { legTrains(state, it, now, sequences) }
    return if (found.all { it == null }) null else Headway.of(found.flatMap { it.orEmpty() }.distinct().map { it.expectedArrival })
}

/**
 * How long the walk to [route]'s first ride takes: to its first stop ([access]) and any walk before
 * the ride. Whole minutes.
 */
internal fun walkToStart(route: TripRoute, access: Duration): Duration =
    route.legs.takeWhile { it.isWalk }.fold(access) { total, walk -> total.plus(walk.run) }

/**
 * Under a card's header, the [walk] to the first ride's stop when there is one (maintainer,
 * 2026-09-27: it says why a train too soon to reach is grayed), then a row per ride of its best
 * route: the ride's line pill (the first ride's lines as one cut pill) and the stop it gets off at,
 * the first ride's also with [times], every line's trains together, and each later one's with how
 * often it runs; a stop's name is cut before the times are. Walks between rides are left out; the
 * route's own page has them.
 */
@Composable
private fun RideStops(
    card: List<TripTiming.Estimate>,
    rideLines: Map<TripLeg, RideLines>,
    statuses: Map<String, LineStatus>,
    // The route's stops with a notice in force ([routeClosures]): a ride's ⚠ carries its own.
    closures: Map<String, DepartureRow>,
    times: CardTimes,
    now: Instant,
    walk: Duration,
    // Every row's pill column width, worked out on the worker; null until it is, when a lone pill's
    // ([linePillWidthPx]), the width of most pills.
    slotWidth: Dp? = null,
) {
    val rides = card.first().route.rides
    val density = LocalDensity.current
    val columnWidth = slotWidth ?: with(density) { linePillWidthPx(density).toDp() }
    // A row's pill column: at least [columnWidth], so every row's stop starts in one place.
    @Composable
    fun PillColumn(alignment: Alignment, pill: @Composable () -> Unit) {
        Box(contentAlignment = alignment, modifier = Modifier.widthIn(min = columnWidth), content = { pill() })
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("rideStops")) {
        val start = rides.firstOrNull()
        val minutes = walk.toMinutes().toInt()
        if (start != null && minutes > 0) {
            val description = stringResource(R.string.trip_walk_first, start.fromName, minutes)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("walkToStart").clearAndSetSemantics { contentDescription = description },
            ) {
                // The walker takes the pills' room, so the stop starts where a ride's does beside its pill.
                PillColumn(Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_walk),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                ShortenedName(start.fromName, MaterialTheme.typography.bodyLarge, Modifier.weight(1f).padding(start = 8.dp).testTag("rideStopName"))
                // In the times column, to set against the first train's; in parentheses, as how long
                // the walk takes, not a time.
                Text(
                    text = stringResource(R.string.trip_walk_minutes, minutes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        rides.forEachIndexed { index, ride ->
            val lines = cardRideLines(card, index, rideLines)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // In a column as wide as the list's widest pill, so the stop starts where every row's does.
                PillColumn(Alignment.CenterStart) {
                    SharedLinePill(
                        lines.map { LineRef(it.lineId, it.lineName, it.mode) },
                        lines.map { riderLineName(it.lineName, it.mode) }.reduce { a, b -> stringResource(R.string.trip_lines_either, a, b) },
                    )
                }
                // Shortened as the main screen's destinations are, before any "…" (SPEC destination-label).
                ShortenedName(ride.toName, MaterialTheme.typography.bodyLarge, Modifier.weight(1f).padding(start = 8.dp).testTag("rideStopName"))
                // A disrupted line's ⚠ just before the times, as on the main screen's rows (maintainer,
                // 2026-09-28), so the stops line up down the card; for a cut pill, any of its lines.
                // And a closure or moved stop where the ride boards or gets off, each by its stop.
                val stopNotices = cardClosures(card, index, closures, rideLines).map { closure ->
                    val notice = cleanDisruptionBody(closure.stopDisruption.orEmpty(), stopName = closure.stopName, hubName = closure.hubName, aliases = closure.placeAliases)
                    stringResource(R.string.trip_line_status, closure.hubName.ifBlank { closure.stopName }, notice)
                }
                val warning = (listOfNotNull(linesWarning(lines, statuses)) + stopNotices).takeIf { it.isNotEmpty() }?.joinToString("; ")
                // Work still to come, when nothing is disrupted now: the muted calendar in the ⚠'s place.
                val planned = if (warning == null) linesPlanned(lines, statuses) else null
                warning?.let { DisruptionWarningGlyph(it, Modifier.padding(start = 12.dp)) }
                planned?.let { PlannedAlertGlyph(it, Modifier.padding(start = 12.dp)) }
                // 8dp after a ⚠ or calendar, as the main screen spaces it from the times; else the usual 12dp.
                val timesGap = if (warning != null || planned != null) 8.dp else 12.dp
                if (index > 0) {
                    times.headways.getOrNull(index - 1)?.let { headway -> HeadwayLabel(headway, Modifier.padding(start = timesGap)) }
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
                            .padding(start = timesGap)
                            .testTag("firstRideTimes")
                            .then(if (times.shown.isEmpty()) Modifier else Modifier.semantics { contentDescription = description }),
                    )
                }
            }
        }
    }
}

/** How often a line runs ([Headway]), in a later ride's times column: "↻ 2–4 min", heard as "Every 2 to 4 min". */
@Composable
internal fun HeadwayLabel(headway: Headway.Range, modifier: Modifier = Modifier) {
    val even = headway.min == headway.max
    // ↻ saves width (maintainer, 2026-09-27); TalkBack reads it as "Every".
    val description = if (even) {
        stringResource(R.string.trip_headway_description, headway.min)
    } else {
        stringResource(R.string.trip_headway_range_description, headway.min, headway.max)
    }
    Text(
        text = if (even) {
            stringResource(R.string.trip_headway, headway.min)
        } else {
            stringResource(R.string.trip_headway_range, headway.min, headway.max)
        },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier.semantics { contentDescription = description },
    )
}

/** The soonest work still to come on any of [lines] ([LineStatus.planned]), for a card's calendar; null when none. */
internal fun linesPlanned(lines: List<TripLeg>, statuses: Map<String, LineStatus>): PlannedAlert? =
    lines.flatMap { statuses[it.lineId]?.planned.orEmpty() }.minByOrNull { it.startsOn }

/** The ⚠'s wording for [lines]' disruptions in [statuses]; beside a cut pill, each by its line. Null when none is disrupted. */
@Composable
private fun linesWarning(lines: List<TripLeg>, statuses: Map<String, LineStatus>): String? {
    val disrupted = lines.mapNotNull { line -> statuses[line.lineId]?.takeIf { it.disrupted }?.let { line to it } }
    return disrupted.singleOrNull()?.second?.description
        ?: disrupted.map { (line, status) -> stringResource(R.string.trip_line_status, line.lineName, status.description) }
            .takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/**
 * A list card's top row: the best route's duration · arrival, alone when the [walk] to its first
 * stop has a row of its own below ([RideStops]); otherwise after where the trip starts, "From
 * ‹stop›". Its lines are the ride rows below, each with its ⚠. A walk-only route reads as the open
 * route's summary does ([RouteSummary]).
 */
@Composable
private fun CardHeader(card: List<TripTiming.Estimate>, rideLines: Map<TripLeg, RideLines>, statuses: Map<String, LineStatus>, walk: Duration) {
    val estimate = card.first()
    val first = estimate.route.rides.firstOrNull() ?: return RouteSummary(card, rideLines, statuses)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (walk.toMinutes() < 1) {
            // "From ‹stop›" around a stand-in for the stop, so the words around it are drawn whole and
            // only the name shortens and elides.
            val from = stringResource(R.string.trip_from, "\u0000")
            val style = MaterialTheme.typography.titleMedium
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                from.substringBefore('\u0000').takeIf { it.isNotEmpty() }?.let { Text(it, style = style, maxLines = 1, softWrap = false) }
                ShortenedName(first.fromName, style, Modifier.weight(1f, fill = false))
                from.substringAfter('\u0000', "").takeIf { it.isNotEmpty() }?.let { Text(it, style = style, maxLines = 1, softWrap = false) }
            }
        }
        // As large as the first ride's times (maintainer, 2026-09-27): when the trip gets there matters
        // as much as when it leaves.
        Text(
            text = arrivalText(estimate),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.testTag("tripArrival"),
        )
    }
}

@Composable
private fun arrivalText(estimate: TripTiming.Estimate): String {
    val arrival = estimate.arrival ?: return stringResource(R.string.trip_arrival_unknown)
    val minutes = (estimate.duration ?: Duration.ZERO).toMinutes().toInt()
    val clock = CLOCK.format(arrival.atZone(LONDON))
    val slack = estimate.slack
    val end = slack?.let { arrivalEnd(arrival, it) }
    return when {
        // A longer wait could miss a connection nothing else times: no latest to give, so say it may be later.
        slack == null -> stringResource(R.string.trip_duration_arrival_open, minutes, clock)
        // A wait for a frequent line could make it a few minutes later: say how many, once they matter.
        end != null -> stringResource(R.string.trip_duration_arrival_range, minutes, latestMinutes(estimate.duration, slack), clock, end)
        estimate.basis == TripTiming.Basis.LIVE -> stringResource(R.string.trip_duration_arrival, minutes, clock)
        else -> stringResource(R.string.trip_duration_arrival_estimated, minutes, clock)
    }
}

/**
 * The end of an [arrival]'s range when its [slack] is enough to show ([SHOWN_SLACK_MINUTES]):
 * minutes alone within the same hour ("11:26–34", to save width), else the clock (also across a clock
 * change, where the hour repeats); null for no range.
 */
internal fun arrivalEnd(arrival: Instant, slack: Duration): String? {
    if (slack.toMinutes() < SHOWN_SLACK_MINUTES) return null
    val latest = arrival.plus(slack).atZone(LONDON)
    val start = arrival.atZone(LONDON)
    // Across the autumn clock change 01:58 BST + 8 min is 01:06 GMT: the same hour on the clock
    // face, so the offset must match too, or "01:58–06" would run backward.
    val sameHour = latest.hour == start.hour && latest.toLocalDate() == start.toLocalDate() && latest.offset == start.offset
    return if (sameHour) MINUTE.format(latest) else CLOCK.format(latest)
}

/**
 * Whole minutes to the latest end of a range: the [duration] and [slack] added before rounding down, so
 * 16m30s plus 1m40s reads 18 like the clock it sits beside, not 16 + 1.
 */
internal fun latestMinutes(duration: Duration?, slack: Duration): Int =
    (duration ?: Duration.ZERO).plus(slack).toMinutes().toInt()

/**
 * The fewest minutes of [TripTiming.Estimate.slack] an arrival shows as a range: any whole minute
 * (maintainer, 2026-09-30), since an estimate ranks on its latest ([TripTiming.rank]) and a single
 * time would hide why it sorted where it did.
 */
internal const val SHOWN_SLACK_MINUTES = 1

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
    // The open route as the page's frame worked it out ([TripOpenView]): its notices, alerts and status note.
    view: TripOpenView,
    // Each ride's lines ([rideLines]): a departure row for every one of them.
    rideLines: Map<TripLeg, RideLines>,
    state: TripViewModel.State,
    now: Instant,
    access: Duration,
    sequences: Map<String, LineSequence?>,
    onRetry: () -> Unit,
    // The alerts dismissed (as on the list): their ⚠ doesn't show on a leg, nor their closure card.
    dismissed: Set<DismissedAlert>,
    // A closure card's ×: hides it as the list's does (null: no ×).
    onDismissAlert: ((DepartureRow) -> Unit)?,
    // A leg's row long-pressed: "Hide ‹mode›", as on the list (null: no menu).
    onHideMode: ((String) -> Unit)?,
    // A leg's row tapped: opens its line's page.
    onOpenDetail: (TripLeg, DepartureRow, RouteFocus?) -> Unit,
    // The lines whose route data is loading ([LineLoads.loading]), a retry included.
    loading: Set<String> = emptySet(),
    // Whether a line's route is still loading ([tripCheckState]), as the list's.
    routesChecking: Boolean = false,
) {
    // The row over the route's legs, as the list's ([rememberTripRow]).
    // One list per estimate, not one per composition: the row's worker is keyed by it.
    val asCards = remember(estimate) { listOf(listOf(estimate)) }
    // Its own work per route and journey: another route opened in its place, a new journey's included,
    // never shows this one's row while its own is worked out (Codex, #543).
    val rowWork = remember(LocalTripJourney.current, routeKey(estimate.route)) { mutableStateOf<Worked<Inputs, TripRow>?>(null) }
    val row = rememberTripRow(asCards, rideLines, state, now, sequences, dismissed, loading, routesChecking, rowWork, firstAtOnce = true)
    // The route's stops' closure notices, worked out with the frame ([TripOpenView]).
    val closures = view.closures
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize().testTag("tripLegs"),
    ) {
        // A re-plan that failed says so over the open route too, with its Retry, as the list does.
        state.planError?.let { error -> item(key = "error") { PlanFailure(error, state.planning, onRetry) } }
        if (state.planError == null && state.planIncomplete) item(key = "incomplete") { PlanIncomplete(state.planning, onRetry) }
        item(key = "summary") { RouteSummary(listOf(estimate), rideLines, view.statuses, Modifier.padding(vertical = 8.dp)) }
        item(key = "status") { DisruptionsRow(row) }
        val firstStop = estimate.route.legs.firstOrNull()?.fromName
        if (access > Duration.ZERO && firstStop != null) {
            item(key = "access") { WalkLink(stringResource(R.string.trip_walk_first, firstStop, access.toMinutes().toInt())) }
        }
        // Each stop's closure card where the route reaches it (SPEC *Trips with a change*), once:
        // where a ride boards, above its departures; where it gets off, below its stops; a walk's
        // stops either side of it. A ride's other lines ([rideLines]) board and get off at their own
        // poles, so each of those counts too.
        val carded = HashSet<String>()
        fun closureCard(id: String) {
            val closure = closures[id]?.takeIf { carded.add(id) } ?: return
            item(key = "closure|$id") { StopClosureCard(closure, onDismissAlert?.let { dismiss -> { dismiss(closure) } }) }
        }
        // Only the rider's next ride counts down; they aren't at a later one's stop yet.
        val nextRide = estimate.route.legs.indexOfFirst { !it.isWalk }
        estimate.route.legs.forEachIndexed { index, leg ->
            // A change on foot, as the trip decides them ([TripViewModel.State.changesOnFoot]), between two rides.
            if (leg in state.changesOnFoot && index > 0 && !estimate.route.legs[index - 1].isWalk &&
                estimate.route.legs.getOrNull(index + 1)?.isWalk == false
            ) {
                // A change on foot onto the next ride is its change, not "walk to" where the
                // rider already is: the ride's own card below names the stop or platform. Its time
                // still decides which train is in reach, so it shows as the change's (maintainer, 2026-10-03).
                closureCard(leg.fromId)
                val change = leg.run.plus(leg.changeAfter)
                if (change > Duration.ZERO) {
                    item(key = "leg$index") { WalkLink(stringResource(R.string.trip_change, change.toMinutes().toInt())) }
                }
                closureCard(leg.toId)
            } else if (leg.isWalk) {
                closureCard(leg.fromId)
                item(key = "leg$index") { WalkLink(stringResource(R.string.trip_walk, leg.toName, leg.run.toMinutes().toInt())) }
                closureCard(leg.toId)
            } else {
                // The next ride's trains that leave before the rider gets to its stop are grayed, as the
                // list's first-leg row grays them.
                val ready = TripTiming.readyAt(estimate, access, index)
                val ride = rideLines[leg] ?: RideLines.only(leg)
                val shown = ride.legs
                (listOf(leg) + shown).forEach { closureCard(it.fromId) }
                item(key = "leg$index") {
                    RideLeg(leg, ride, index == 0, index == nextRide, state, now, sequences, dismissed, onOpenDetail, onHideMode, ready)
                }
                (listOf(leg) + shown).forEach { closureCard(it.toId) }
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
 * lines, or one that failed, runs again or is about to (a plan still landing), false once the check
 * failed or left them unchecked, null when every line was checked. [closuresFailed]: a closure check failed for a stop
 * the routes shown use (by default any failed stop; the screens pass their routes' own, [routeClosuresFailed]).
 * [statusFailed]: a status request failed for a line the routes shown ride (by default any; the
 * screens pass their routes' own, [routeStatusFailed]). [loading]: a ride's stops wait on its line's
 * route to place its bus ([awaitingRoutes]), so they're still being checked, not failing to be.
 */
internal fun statusNote(
    state: TripViewModel.State,
    unchecked: Boolean,
    closuresFailed: Boolean = state.closuresFailed.isNotEmpty(),
    statusFailed: Boolean = state.statusFailed,
    loading: Boolean = false,
): Boolean? = when {
    // A plan still landing checks its lines when it settles: checking, not failed. So is a check that
    // failed and is being asked again: its last answer can't be vouched for until the retry lands.
    state.refreshing || state.planning || loading -> if (unchecked || statusFailed || closuresFailed) true else null
    // A closure check that failed keeps its last notices, but can't vouch for them as current.
    statusFailed || closuresFailed || unchecked -> false
    else -> null
}

/**
 * Whether the latest status request failed for a line [route] rides, or one shown at a stop it boards
 * at ([rideLines]): a request that failed for another route's line isn't this route's, as another
 * route's failed stop isn't ([routeClosuresFailed]).
 */
internal fun routeStatusFailed(route: TripRoute, rideLines: Map<TripLeg, RideLines>, state: TripViewModel.State): Boolean =
    route.rides.any { ride ->
        ride.lineId in state.statusFailedLines || rideLines[ride]?.legs.orEmpty().any { it.lineId in state.statusFailedLines }
    }

/**
 * Whether the latest closure check failed for a stop [route] is judged at, as its ranking judges it
 * ([TripClosures.reads]): one it boards or gets off at ([TripClosures.ends]), at the stop its ride
 * is placed on once its line's route ([sequences]) places it, else the stop the Planner named or any
 * pole of its pair. Another route's failure isn't this one's, nor is the other pole of a placed
 * bus's pair. So is a pole another line shown on a ride boards or gets off at ([otherLineStops]).
 */
internal fun routeClosuresFailed(
    route: TripRoute,
    state: TripViewModel.State,
    sequences: Map<String, LineSequence?>,
    rideLines: Map<TripLeg, RideLines> = emptyMap(),
): Boolean =
    state.closuresFailed.isNotEmpty() && (
        TripClosures.ends(route).any { end ->
            TripClosures.reads(end, state.areaPoles, endPole(route, end, sequences)).any { it in state.closuresFailed }
        } || otherLineStops(route, rideLines).any { it in state.closuresFailed }
        )

/**
 * The poles the other lines [route]'s rides show ([rideLines]) board and get off at: shown for the
 * rider to take, so their closures count, and so does a check of them that failed or hasn't come.
 */
internal fun otherLineStops(route: TripRoute, rideLines: Map<TripLeg, RideLines>): Set<String> =
    route.rides.flatMap { ride -> rideLines[ride]?.legs.orEmpty().filter { it != ride } }
        .flatMapTo(HashSet()) { listOf(it.fromId, it.toId) }

/**
 * The stops [route] is judged at that only the screen can name: where each ride is placed by its
 * line's route ([endPole]; a bus station's stand the route puts the bus at in place of the one the
 * Planner named, say), and the poles another line a ride shows uses ([otherLineStops]). The trip asks
 * only about the Planner's stops and their pairs' poles by itself, so the screen hands it these
 * ([TripViewModel.checkShownStops]); one it already asks about is left to it.
 */
internal fun shownStops(route: TripRoute, sequences: Map<String, LineSequence?>, rideLines: Map<TripLeg, RideLines>): Set<String> =
    TripClosures.ends(route).mapNotNullTo(HashSet()) { endPole(route, it, sequences) } + otherLineStops(route, rideLines)

/** Whether a pole another line [route]'s rides show uses has no check held yet ([otherLineStops]). */
internal fun otherLineStopsUnchecked(route: TripRoute, state: TripViewModel.State, rideLines: Map<TripLeg, RideLines>): Boolean =
    otherLineStops(route, rideLines).any { it !in state.closures }

/**
 * Whether a ride of [estimates]' routes waits on its line's route to be placed on its poles
 * ([placedOnPoles]): not loaded yet ([sequences]), or failed and being loaded again ([loading]).
 * Until it is, its stops can't be judged, so the trip is still checking them. One whose route failed
 * to load, and isn't being loaded again, can't be placed, and says so ([uncheckedNames]).
 */
internal fun awaitingRoutes(
    estimates: List<TripTiming.Estimate>,
    sequences: Map<String, LineSequence?>,
    loading: Set<String> = emptySet(),
): Boolean =
    estimates.any { estimate ->
        estimate.route.rides.any { (it.lineId !in sequences || it.lineId in loading) && !placedOnPoles(it, sequences) }
    }

/**
 * What the "couldn't check" note names for [estimates]' routes ([statusNote]), as their cards name
 * them (maintainer, 2026-09-30: say what couldn't be checked, not only that something couldn't).
 * First each line whose status isn't known or whose latest check failed, the Planner's and the other
 * lines a ride shows ([rideLines]); then each stop a route is judged at that no current check vouches
 * for — none held yet, its latest failed, or its bus not yet placed on a pole there ([endPole]) — and
 * each pole another line shows boards or gets off at with none ([otherLineStops]). Each once, in the
 * routes' order.
 */
internal fun uncheckedNames(
    estimates: List<TripTiming.Estimate>,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    rideLines: Map<TripLeg, RideLines>,
): List<String> = unchecked(estimates, state, now, sequences, rideLines).let { (lines, stops) -> lines.map { it.lineName } + stops }
    .filter { it.isNotBlank() }.distinct()

/**
 * What [uncheckedNames] names, apart: the lines (each once, as its pill names it) and the stops, so the
 * disruptions row can draw the lines as their pills (maintainer, 2026-10-04: "Unknown: ‹pills›").
 */
internal fun unchecked(
    estimates: List<TripTiming.Estimate>,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    rideLines: Map<TripLeg, RideLines>,
): Pair<List<TripLeg>, List<String>> {
    val statuses = rideStatuses(state)
    val lines = LinkedHashMap<String, TripLeg>()
    val stops = LinkedHashSet<String>()
    for (route in estimates.map { it.route }) {
        val others = route.rides.flatMap { ride -> rideLines[ride]?.legs.orEmpty().filter { it != ride } }
        route.rides.filter { it.lineId in state.statusUnknown || it.lineId !in state.statuses || it.lineId in state.statusFailedLines }
            .forEach { lines.putIfAbsent(it.lineId, it) }
        others.filter { it.lineId !in statuses }.forEach { lines.putIfAbsent(it.lineId, it) }
        // Named as the route names a stop, else as the other line does ([routeClosures]).
        val names = (others + route.legs).flatMap { listOf(it.fromId to it.fromName, it.toId to it.toName) }.toMap()
        for (end in TripClosures.ends(route)) {
            val used = endPole(route, end, sequences)
            val unchecked = TripClosures.judge(end, state.closures, state.closuresUnknown, now, used) == TripClosures.Standing.UNCHECKED
            if (unchecked || TripClosures.reads(end, state.areaPoles, used).any { it in state.closuresFailed }) names[end.id]?.let(stops::add)
        }
        otherLineStops(route, rideLines).filter { it !in state.closures || it in state.closuresFailed }.forEach { id -> names[id]?.let(stops::add) }
    }
    // A ride the Planner gave no line names nothing a pill could show: it's left to the plain "Unknown".
    return lines.values.filter { it.lineId.isNotBlank() }.map(::pillNamed) to stops.filter { it.isNotBlank() }
}

/**
 * [leg] with a name its pill can show and read out: a line the Planner gave an id but no name goes by
 * its id, as the route-disruption alert names it, never a blank pill (Codex, #543).
 */
internal fun pillNamed(leg: TripLeg): TripLeg = if (leg.lineName.isBlank()) leg.copy(lineName = leg.lineId) else leg

/**
 * The closure notices along [route] (a closure, a moved stop) as the list's closure cards show them,
 * each by its stop: every stop it boards or gets off at ([TripClosures.ends]) with a notice in force
 * at [now], less those the rider [dismissed]. Named as the route names the stop, and placed as the
 * list places it, so a dismissal on either screen holds on the other ([stopPlaceKey]): in its
 * interchange and stop area, from the line's route ([sequences]), else a bus stop's pair. A stop
 * no ride calls at (one the route only walks to or from) takes its interchange from the bundled
 * index ([hubOf]). Each other line a ride shows ([rideLines]) adds where it boards and gets off,
 * at its own poles, after the route's own: its rows are the rider's to take too.
 */
internal fun routeClosures(
    route: TripRoute,
    state: TripViewModel.State,
    now: Instant,
    dismissed: Set<DismissedAlert>,
    sequences: Map<String, LineSequence?> = emptyMap(),
    rideLines: Map<TripLeg, RideLines> = emptyMap(),
    hubOf: (String) -> String? = { null },
): Map<String, DepartureRow> {
    val others = route.rides.flatMap { ride -> rideLines[ride]?.legs.orEmpty().filter { it != ride } }
    // Named as the route names a stop, else as the other line does.
    val names = (others + route.legs).flatMap { listOf(it.fromId to it.fromName, it.toId to it.toName) }.toMap()
    val rides = route.rides + others
    val ends = TripClosures.ends(route) + others.flatMap { listOf(TripClosures.End(it.fromId, lineId = it.lineId), TripClosures.End(it.toId, lineId = it.lineId)) }
    val rows = ends.distinctBy { it.id }.flatMap { end ->
        val notices = state.closures[end.id]?.takeIf { it.isNotEmpty() } ?: return@flatMap emptyList()
        // The route data of a line that calls there, any ride's that has loaded (at a change, one may
        // have failed while the other came in); none for a stop only a walk uses.
        val sequence = rides.filter { it.fromId == end.id || it.toId == end.id }.firstNotNullOfOrNull { sequences[it.lineId] }
        // Only an interchange ("HUB…") is a hub: a stop with none has its stop area as its top parent.
        val hub = (sequence?.stopHubs?.get(end.id) ?: hubOf(end.id))?.takeIf { it.startsWith(HUB_PREFIX) }.orEmpty()
        val area = sequence?.stopAreas?.get(end.id) ?: end.area
        val stop = StopArrivals(end.id, names[end.id].orEmpty(), emptyList(), SteadyClock.stamp(now), disruptions = notices, clusterId = area, hubId = hub)
        DepartureRows.across(listOf(stop), now).filter { it.stopDisruption != null }
    }
    // One card per notice at a place, folded as the list folds them (two poles of a stop area can
    // each carry the same area-wide notice), kept where the route first reaches it, less what the
    // rider dismissed. A stop's card carries every notice in force there, joined, as the list's does.
    return DepartureRows.withoutDismissed(DepartureRows.stopStatusFolded(rows), dismissed).associateBy { it.stopId }
}

private const val HUB_PREFIX = "HUB"

/**
 * Whether [leg]'s boarding stop can't be vouched for on its line's page: judged as the route's
 * ranking judges it ([TripClosures.judge]), at the stop its ride is placed on ([endPole]) and never
 * before, and not once a check bearing on it just failed ([TripClosures.reads]) or is as old as a
 * stale countdown ([checkCurrent]), whatever it last knew, closed included: the page claims only
 * what's current, though the ranking still goes by it.
 */
internal fun legStopUnchecked(leg: TripLeg, state: TripViewModel.State, now: Instant, sequences: Map<String, LineSequence?>): Boolean {
    val end = TripClosures.End(leg.fromId, leg.fromArea, leg.lineId)
    val used = endPole(TripRoute(listOf(leg)), end, sequences)
    if (TripClosures.reads(end, state.areaPoles, used).any { it in state.closuresFailed || !checkCurrent(state.closuresAt[it], now) }) return true
    return TripClosures.judge(end, state.closures, state.closuresUnknown, now, used) == TripClosures.Standing.UNCHECKED
}

/**
 * Whether a check stamped [at] still stands as current at [now]: made, and younger than the shared
 * staleness threshold, as a countdown shown from it would be ([Staleness]). Aged by the steady clock
 * it's stamped by ([SteadyClock]), so setting the device's clock doesn't change it. One stamped more
 * than [Staleness.CLOCK_SKEW] after [now] isn't: the screen's clock ticks every 10 s, so a check
 * landing between ticks is dated after it, but further ahead than that its age can't be told.
 */
internal fun checkCurrent(at: Instant?, now: Instant): Boolean =
    !Staleness.isStale(at ?: return false, now)

/** A stop's [notices] in force at [now], as one text, or null when none is. */
internal fun stopNotice(notices: List<StopDisruption>?, now: Instant): String? =
    notices.orEmpty().filter { it.isActiveAt(now) }.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.description }

/**
 * The closure notices a list card's ride [index] of [rides] carries in its ⚠: before the first ride,
 * where the route starts on foot from a stop ([starts]); where it boards, unless the ride before got
 * off there; where it gets off; and after the last ride, where the route ends ([ends], a final walk's
 * stop). Each line the ride shows ([lines]) boards and gets off at its own poles, so each counts.
 * From [closures] ([routeClosures]), in that order.
 */
internal fun rideClosures(
    rides: List<TripLeg>,
    index: Int,
    ends: String?,
    closures: Map<String, DepartureRow>,
    starts: String? = null,
    lines: (TripLeg) -> List<TripLeg> = { emptyList() },
): List<DepartureRow> {
    fun shown(ride: TripLeg) = (listOf(ride) + lines(ride)).distinct()
    val ride = rides[index]
    val first = starts.takeIf { index == 0 }
    val arrived = if (index == 0) emptySet() else shown(rides[index - 1]).mapTo(HashSet()) { it.toId }
    val boards = shown(ride).map { it.fromId }.filter { it !in arrived }
    val alights = shown(ride).map { it.toId }
    val last = ends.takeIf { index == rides.lastIndex }
    return (listOfNotNull(first) + boards + alights + listOfNotNull(last)).distinct().mapNotNull { closures[it] }
}

/**
 * The closure notices a [card]'s ride [index] carries in its ⚠: every route's on the card
 * ([rideClosures]), and every other line a ride shows ([rideLines]), since the lines sharing a card
 * go between the same stop pairs but each may use its own pole. Each stop once, and each notice
 * once at a place, as the list folds them.
 */
internal fun cardClosures(
    card: List<TripTiming.Estimate>,
    index: Int,
    closures: Map<String, DepartureRow>,
    rideLines: Map<TripLeg, RideLines> = emptyMap(),
): List<DepartureRow> =
    card.flatMap { estimate ->
        val rides = estimate.route.rides
        if (index > rides.lastIndex) return@flatMap emptyList()
        val legs = estimate.route.legs
        rideClosures(rides, index, legs.lastOrNull()?.takeIf { it.isWalk }?.toId, closures, legs.firstOrNull()?.takeIf { it.isWalk }?.fromId) {
            rideLines[it]?.legs.orEmpty()
        }
    }.distinctBy { it.stopId }.let(DepartureRows::stopStatusFolded)

/** How long a change to the disruptions row holds before it's drawn, unless it's to "Checking…" ([settled]). */
/** The check over every card of the list kept behind an open route, as worked out ([tripCheckState]); null when it's clear. */
private class ListCheck(val message: TripMessage?)

/** How long a card takes to slide to its new place when the list re-sorts, taps held throughout. */
internal const val CARD_MOVE_MILLIS = 300L

/**
 * The trip list's cards as they are drawn: [cards] in their own order, [order] placing each under its
 * header ([headedCards]), and [keys], the cards' routes ([cardKey]) in the order drawn. [keys] is the
 * last answer's own list when nothing moved, so a re-sort shows on the main thread as a new list, told
 * apart by identity, never by comparing the two.
 */
internal class ListedCards(
    val cards: List<List<TripTiming.Estimate>>,
    val order: List<HeadedCard>,
    val keys: CardOrder,
)

/**
 * The cards' routes in drawn order ([cardKey]) and the header over each: a header coming or going moves
 * the cards under it as surely as a re-sort (Codex, #543). Equal only to itself, so comparing two never
 * walks the list.
 */
internal class CardOrder(val keys: List<String>, val headers: List<List<RouteLabel>>)

@WorkerThread
internal fun listedCards(cards: List<List<TripTiming.Estimate>>, previous: CardOrder?): ListedCards {
    val order = headedCards(cards.map { it.first() })
    val keys = order.map { cardKey(cards[it.index].first().route) }
    val headers = order.map { it.header }
    val same = previous != null && keys == previous.keys && headers == previous.headers
    return ListedCards(cards, order, if (same) previous else CardOrder(keys, headers))
}

/**
 * Whether a tap target is moving on screen (maintainer, 2026-10-04): a card sliding to a new order,
 * pushed by something coming or going above it, by its own header growing, or by a scroll. Read from
 * where the target is placed ([sliding]), so no cause has to be named (Codex, #543). True from the
 * first frame it moves until it has held still for a frame.
 */
internal class Sliding {
    var moving by mutableStateOf(false)
        internal set

    // Plain fields, written as the target is placed: no recomposition per frame while it moves, only
    // when [moving] itself flips.
    internal var y = Float.NaN
    internal var moved = false

    internal fun placedAt(y: Float) {
        if (!this.y.isNaN() && y != this.y) {
            moved = true
            if (!moving) moving = true
        }
        this.y = y
    }
}

/** A [Sliding] that clears once its target has held still for a whole frame. */
@Composable
internal fun rememberSliding(): Sliding {
    val sliding = remember { Sliding() }
    LaunchedEffect(sliding.moving) {
        if (!sliding.moving) return@LaunchedEffect
        // Timed on frames, not delay(): the frame clock is the one a test drives.
        do {
            sliding.moved = false
            withFrameMillis { }
            withFrameMillis { }
        } while (sliding.moved)
        sliding.moving = false
    }
    return sliding
}

/** Reports where this is placed on screen to [sliding]. */
internal fun Modifier.sliding(sliding: Sliding): Modifier = onPlaced { sliding.placedAt(it.positionInRoot().y) }

internal const val NOTE_SETTLE_MILLIS = 1_500L

/**
 * [value], but a change to it drawn only once it has held for [NOTE_SETTLE_MILLIS], unless [at] says
 * this one shows at once: so a value that flips back within the moment is never drawn. [initial] is what
 * shows first, the first [value] unless given. Compared by
 */
@Composable
private fun <T> settled(value: T, at: Boolean, initial: T = value): T {
    var shown by remember { mutableStateOf(initial) }
    LaunchedEffect(value) {
        // Timed on frames, not delay(): the frame clock is the one a screenshot test drives.
        if (!at && shown != value) {
            val start = withFrameMillis { it }
            while (withFrameMillis { it } - start < NOTE_SETTLE_MILLIS) Unit
        }
        shown = value
    }
    return if (at) value else shown
}

/**
 * What the disruptions row over a trip's routes draws ([DisruptionsRow]), worked out whole on the
 * worker ([tripRow]): the disrupted [lines] (each once, as a card's pill names it) and the stops with
 * a closure notice in force ([stops], joined); then [checking] while a check runs or a line's route
 * loads, else [unknown] when one couldn't check, naming [unknownLines] (as pills) and [unknownStops].
 * Compared by identity: the worker hands back the last one when nothing in it changed ([sameAs]), so
 * holding the row never walks its lists on the main thread (Codex, #543).
 */
internal class TripRow(
    val checking: Boolean,
    val lines: List<TripLeg> = emptyList(),
    val stops: String = "",
    val unknown: Boolean = false,
    val unknownLines: List<TripLeg> = emptyList(),
    val unknownStops: String = "",
) {
    /** Whether [other] draws the same: on the worker only, as it walks both. */
    @WorkerThread
    fun sameAs(other: TripRow?): Boolean = other != null && checking == other.checking && unknown == other.unknown &&
        stops == other.stops && unknownStops == other.unknownStops && lines == other.lines && unknownLines == other.unknownLines

    companion object {
        /** "Checking…" alone: before the first row is worked out, or once the last is too old to show. */
        val CHECKING = TripRow(checking = true)
    }
}

/**
 * The disruptions row for [cards] ([TripRow]): each card's disrupted lines as its ⚠ judges them
 * ([cardStatuses] along its rides, less what was [dismissed]) and its closure notices
 * ([routeClosures]), then the check's word ([statusNote]): checking while a check runs or a line's
 * route loads ([loading], [routesChecking]: the "Checking routes…" banner this row replaced,
 * maintainer, 2026-10-04), else what couldn't be checked ([unchecked]). Judges trains along rides: on
 * a worker only.
 */
@WorkerThread
internal fun tripRow(
    cards: List<List<TripTiming.Estimate>>,
    rideLines: Map<TripLeg, RideLines>,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    dismissed: Set<DismissedAlert>,
    loading: Set<String> = emptySet(),
    routesChecking: Boolean = false,
    hubOf: (String) -> String? = { null },
): TripRow {
    val lines = LinkedHashMap<String, TripLeg>()
    val stops = LinkedHashSet<String>()
    for (card in cards) {
        val statuses = shownStatuses(cardStatuses(card, rideLines, state, now, sequences), dismissed)
        card.first().route.rides.indices.flatMap { cardRideLines(card, it, rideLines) }
            .filter { statuses[it.lineId]?.disrupted == true && it.lineId.isNotBlank() }
            .forEach { lines.putIfAbsent(it.lineId, pillNamed(it)) }
        card.forEach { estimate -> routeClosures(estimate.route, state, now, dismissed, sequences, rideLines, hubOf).values.mapTo(stops) { it.stopName } }
    }
    // Lines not yet checked rank as unchecked, and say so: checking while a check runs, and only a
    // finished check says it couldn't. A route revealed since the last refresh (a mode shown again)
    // counts by its own lines; so does another line shown with its trains grayed ([RideLines.unchecked]):
    // once the check is over it isn't still "checking", it couldn't be.
    val estimates = cards.flatten()
    val rides = estimates.flatMap { it.route.rides }
    val otherLines = RideLines.unchecked(rides.mapNotNull { rideLines[it] }, rideStatuses(state))
    // Only the routes shown: a hidden mode's line or failed stop isn't these routes'.
    val shownLines = rides.mapTo(HashSet()) { it.lineId }
    val note = statusNote(
        state,
        // A Planner line still unchecked says so even where another line keeps its route usable.
        state.statusUnknown.any { it in shownLines } || shownLines.any { it !in state.statuses } || otherLines.isNotEmpty() ||
            estimates.any { it.unchecked || otherLineStopsUnchecked(it.route, state, rideLines) },
        estimates.any { routeClosuresFailed(it.route, state, sequences, rideLines) },
        estimates.any { routeStatusFailed(it.route, rideLines, state) },
        awaitingRoutes(estimates, sequences, loading),
    )
    val disrupted = lines.values.toList()
    val closed = stops.joinToString(", ")
    if (note == true || routesChecking) return TripRow(checking = true, lines = disrupted, stops = closed)
    if (note == null) return TripRow(checking = false, lines = disrupted, stops = closed)
    val (unknownLines, unknownStops) = unchecked(estimates, state, now, sequences, rideLines)
    return TripRow(false, disrupted, closed, unknown = true, unknownLines = unknownLines, unknownStops = unknownStops.joinToString(", "))
}

/**
 * The disruptions row for [cards] ([tripRow]), worked out on the worker and held as one value
 * (maintainer, 2026-10-04): a change to it is drawn only once it has held for [NOTE_SETTLE_MILLIS]
 * ([settled]), unless it's to "Checking…", which shows at once. A trip's loads land one after another
 * (the plan, the statuses and closures, each line's route), and a moment between two of them read as
 * checked, or as couldn't check, so the row blinked and the cards under it jumped. The last row stands
 * while the worker works out the next, as a card's last times do; if that takes longer than the hold,
 * the row says "Checking…" alone rather than show what older cards said (Codex, #543).
 */
@Composable
private fun rememberTripRow(
    cards: List<List<TripTiming.Estimate>>,
    rideLines: Map<TripLeg, RideLines>,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    dismissed: Set<DismissedAlert>,
    loading: Set<String>,
    routesChecking: Boolean,
    // Where the row is kept; a caller that outlives this composable passes its own.
    work: MutableState<Worked<Inputs, TripRow>?> = remember { mutableStateOf(null) },
    // Whether a change waits out its moment ([settled]); not while nobody can see the row yet.
    hold: Boolean = true,
    // Whether the first row worked out shows at once: for a row of its own (an open route's), whose
    // cards are already settled. Not for the list's, kept by the caller, whose first answer after a
    // reset can still be for the cards before it.
    firstAtOnce: Boolean = false,
): TripRow {
    val routeStops = LocalRouteStops.current
    val key = Inputs(cards, rideLines, state, now, sequences, dismissed, loading, routesChecking, routeStops)
    val previous = work.value?.value
    val latest = rememberWorked(work, key, keep = { _, _ -> true }) {
        tripRow(cards, rideLines, state, now, sequences, dismissed, loading, routesChecking) { routeStops?.hubOf(it) }
            .let { row -> if (previous != null && row.sameAs(previous)) previous else row }
    }
    val stale = work.value?.key != key
    // Behind the cards for longer than the hold: "Checking…" alone until the worker catches up.
    // A row kept from before (a return from an open route) is never stuck at first: it stands its hold
    // like any other. With none kept, the row says "Checking…" anyway.
    val stuck = settled(stale, at = !stale, initial = stale && latest == null)
    val target = if (latest == null || stuck) TripRow.CHECKING else latest
    // The first row worked out where none was kept ([firstAtOnce]) shows at once: the "Checking…"
    // before it stood only for no work done yet, not a check running, so holding it would say
    // "Checking…" over a settled trip (Codex, #543). A row kept from before still stands its hold.
    val worked = remember(work) { booleanArrayOf(work.value != null) }
    val first = firstAtOnce && latest != null && !stuck && !worked[0]
    if (latest != null && !stuck) worked[0] = true
    return settled(target, at = target.checking || !hold || first)
}

/**
 * The row over a trip's routes, always there so nothing under it moves as the checks land
 * (maintainer, 2026-10-04): "Disruptions:", then each disrupted line's pill and each closed stop,
 * then the check's word: "Checking…" while it runs, "Unknown:" and what it couldn't check (in red,
 * lines as their pills) when it couldn't, else "None" when there's nothing to show.
 */
@Composable
private fun DisruptionsRow(row: TripRow) {
    val style = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // The row is at least a pill tall, whatever it says, so "Checking…" or "None" turning into a pill
    // never pushes the cards down (Codex, #543): a pill nobody sees sets the height.
    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.testTag("tripDisruptions")) {
        LinePill("", "", "bus", Modifier.alpha(0f).clearAndSetSemantics {}.padding(vertical = 4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(stringResource(R.string.trip_disruptions_label), style = style, color = muted)
            row.lines.forEach { LinePill(it.lineName, it.lineId, it.mode) }
            if (row.stops.isNotEmpty()) Text(row.stops, style = style)
            when {
                row.checking -> Text(stringResource(R.string.trip_disruptions_checking), style = style, color = muted)
                // What couldn't be checked, after "Unknown:", as the disrupted are drawn (maintainer, 2026-10-04).
                row.unknown -> {
                    val error = MaterialTheme.colorScheme.error
                    if (row.unknownLines.isEmpty() && row.unknownStops.isEmpty()) {
                        Text(stringResource(R.string.trip_disruptions_unknown), style = style, color = error)
                    } else {
                        Text(stringResource(R.string.trip_disruptions_unknown_label), style = style, color = error)
                        row.unknownLines.forEach { LinePill(it.lineName, it.lineId, it.mode) }
                        if (row.unknownStops.isNotEmpty()) Text(row.unknownStops, style = style, color = error)
                    }
                }
                row.lines.isEmpty() && row.stops.isEmpty() -> Text(stringResource(R.string.trip_disruptions_none), style = style, color = muted)
            }
        }
    }
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
        fetchedAt = state.live[leg.fromId]?.fetchedAt ?: SteadyClock.stamp(now),
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
 * [leg]'s stops ([legStops]) from the line's route in [LocalRouteStops], loaded and matched off the
 * main thread ([LocalWorker]); a failed load says why and loads again on [retry], as a row's stop
 * list does ([rememberRouteStops]).
 */
@Composable
internal fun rememberLegRouteStops(leg: TripLeg, retry: Int): RouteStopsUi {
    val repository = LocalRouteStops.current ?: return RouteStopsUi.Hidden
    val worker = LocalWorker.current
    return key(repository, leg, worker) {
        val state by produceState<RouteStopsUi>(RouteStopsUi.Loading, retry) {
            if (value !is RouteStopsUi.Loading && value !is RouteStopsUi.Failed) return@produceState
            value = RouteStopsUi.Loading
            value = try {
                withContext(worker) { legStops(leg, repository.load(leg.lineId, "")) }
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
        // Keyed by, and reading, this composition's value: the delegate read when the effect runs
        // could already be the next state, which then logs twice.
        val shown = state
        LaunchedEffect(shown) {
            // The Planner's terminus for the leg, never its toName: that's where the rider gets off.
            (shown as? RouteStopsUi.Unavailable)?.let {
                repository.reportUnresolved(leg.lineId, leg.fromId, it.reason, leg.headings.firstOrNull().orEmpty())
            }
        }
        shown
    }
}

/**
 * [state]'s line statuses as [card]'s rides travel them ([LineStatus.alongRides]), by the trains seen
 * along each ride of every line on it ([legTrains]). Display only, like [shownStatuses], which goes
 * after it: a direction's own alerts are what a dismissal made from its row names.
 */
internal fun cardStatuses(
    card: List<TripTiming.Estimate>,
    rideLines: Map<TripLeg, RideLines>,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): Map<String, LineStatus> {
    if (state.statuses.values.none { it.byDirection.isNotEmpty() }) return state.statuses
    val legs = card.flatMap { it.route.rides }.flatMap { rideLines[it]?.legs ?: listOf(it) }.distinct()
    val rides = legs.groupBy { it.lineId }.mapValues { (_, byLine) -> byLine.map { legTrains(state, it, now, sequences).orEmpty() } }
    return LineStatus.alongRides(state.statuses, rides)
}

/**
 * The line statuses a trip's cards warn of: [statuses] less the alerts the rider dismissed, as the
 * list shows them. Display only — a dismissed line still ranks and counts as checked.
 */
internal fun shownStatuses(statuses: Map<String, LineStatus>, dismissed: Set<DismissedAlert>): Map<String, LineStatus> {
    if (dismissed.isEmpty()) return statuses
    // Each alert goes on its own (Codex, PR #337): a dismissed disruption leaves the line's planned
    // work showing, and a dismissed planned alert leaves the rest.
    return statuses.mapNotNull { (line, status) ->
        val planned = status.planned.filter { DismissedAlert.ofPlanned(status.lineId, it) !in dismissed }
        val shown = status.remainingAfter(dismissed)?.copy(planned = planned)
            ?: LineStatus(status.lineId, LineStatus.GOOD_SERVICE, GOOD_SERVICE_LABEL, planned = planned)
        // A line left with nothing once its alerts are dismissed goes; one that had nothing stays.
        (line to shown).takeIf { shown.hasAlerts || !status.hasAlerts }
    }.toMap()
}

private const val GOOD_SERVICE_LABEL = "Good Service"

/**
 * [row] with its line alert marked dismissed ([DepartureRow.statusDismissed]) if it's in [dismissed],
 * or showing the other direction's alert when only one way's was ([remainingAfter]).
 */
internal fun withDismissedMarked(row: DepartureRow, dismissed: Set<DismissedAlert>): DepartureRow {
    // Its dismissed planned alerts go too, as on the list's rows (Codex, PR #337).
    val planned = with(DepartureRows) { row.withoutDismissedPlanned(dismissed) }
    val status = planned.status ?: return planned
    return when (val shown = status.remainingAfter(dismissed)) {
        status -> planned
        null -> planned.copy(status = null, statusDismissed = true)
        else -> planned.copy(status = shown)
    }
}

/**
 * The key a leg's [row] opens its page by: the main screen's, but a bus leg's by its stop pair rather
 * than its pole, or by the stand the Planner named where its stand has moved ([boardingKey]), so the
 * page stays open when the trip works out which side of the road, or which stand, its bus uses
 * ([onPoles]) and the row moves there.
 */
internal fun tripDetailKey(leg: TripLeg, row: DepartureRow): String =
    row.copy(stopId = leg.fromArea.ifEmpty { leg.plannedFromId.ifEmpty { row.stopId } }).detailKey()

/**
 * Which planned leg a line page belongs to, stable across refreshes and re-plans (never its times):
 * its line, where it boards and gets off, and its path. Two routes boarding the same line at the same
 * stop but getting off elsewhere are different legs, and a page shows the one tapped. Only what
 * [onPoles] never changes: a bus leg's poles can move to the other side of the road once its route
 * loads, so an end is its stop pair where it has one, and a moved stand the one the Planner named
 * ([boardingKey], [alightingKey]).
 */
internal fun tripLegKey(leg: TripLeg): String = listOf(
    leg.lineId,
    boardingKey(leg),
    alightingKey(leg),
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
    fetchedAt = state.live[leg.fromId]?.fetchedAt ?: SteadyClock.stamp(now),
    // Only a disruption, as a row's status always is: a good service is no alert.
    status = state.statuses[leg.lineId]?.takeIf { it.disrupted },
    // Its page lists the line's work still to come, as a list row's does (Codex, PR #337).
    plannedAlerts = state.statuses[leg.lineId]?.planned.orEmpty(),
)

/**
 * An open ride's rows by line: each of [ride]'s lines checked as running ([RideLines.vouched]). One
 * not yet checked, or not running, shows no countdowns, as a train whose route isn't checked shows
 * none ([pendingCardTrains]): its row says "–", with its status chip or the page's status note saying
 * why, never a countdown the card wouldn't offer.
 */
internal fun rideLegRows(
    ride: RideLines,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    dismissed: Set<DismissedAlert>,
): Map<TripLeg, List<DepartureRow>> = ride.legs.associateWith { line ->
    if (!ride.vouched(line, rideStatuses(state), lineStopsOpen(state, now))) emptyList()
    else DepartureRows.withoutDismissed(legRows(state, line, now, sequences), dismissed)
}

/**
 * Where a ride's [quiet] lines (no live rows) go on its open route: under the [groups] of the stop
 * each boards at (the first, where platforms or directions split it), so none reads as boarding at
 * another pole or shows twice; the rest by stop, each under a header of its own.
 */
internal fun placeQuiet(groups: List<StopGroup>, quiet: List<TripLeg>): Pair<Map<StopGroup, List<TripLeg>>, List<List<TripLeg>>> {
    // A stop split by platform or direction is several groups: each line goes under the first only.
    val home = quiet.associateWith { line -> groups.firstOrNull { group -> group.rows.any { it.stopId == line.fromId } } }
    val placed = groups.associateWith { group -> quiet.filter { home[it] == group } }
    val taken = home.filterValues { it != null }.keys
    return placed to quiet.filterNot { it in taken }.groupBy { it.fromId }.values.toList()
}

/**
 * A ride line with no live rows: tapped, its line's page all the same (its service alert and its
 * stops); long-pressed, "Hide ‹mode›" — the main screen's route row, as a line with no trains shows
 * there. Its times read "Loading" while they aren't in yet, "–" once none can be shown.
 */
@Composable
private fun NoTrainsRow(
    line: TripLeg,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    dismissed: Set<DismissedAlert>,
    onOpenDetail: (TripLeg, DepartureRow, RouteFocus?) -> Unit,
    onHideMode: ((String) -> Unit)?,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        // A dismissed alert leaves the row (it's how the leg opens its stops), without its chip.
        val statusRow = withDismissedMarked(legStatusRow(state, line, now), dismissed)
        RouteRow(
            row = statusRow,
            isStarred = false,
            starrable = false,
            onToggleStar = {},
            onOpenDetail = { row, focus -> onOpenDetail(line, row, focus) },
            onHideMode = onHideMode,
        ) {
            LinePill(line.lineName, line.lineId, line.mode)
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                statusRow.status?.let { DisruptionChip(it.description) }
                    // Work still to come, noted as on the list's rows (Codex, PR #337).
                    ?: statusRow.plannedAlerts.firstOrNull()?.let { PlannedAlertGlyph(it) }
            }
            Text(
                if (legLoading(state, line, sequences)) stringResource(R.string.trip_times_loading) else "–",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun RideLeg(
    leg: TripLeg,
    // Every line between the leg's two stops ([RideLines]), the Planner's first: a row for each.
    ride: RideLines,
    first: Boolean,
    // The rider's next ride, whose rows count down; a later one's say how often each line runs.
    countsDown: Boolean,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    dismissed: Set<DismissedAlert>,
    onOpenDetail: (TripLeg, DepartureRow, RouteFocus?) -> Unit,
    onHideMode: ((String) -> Unit)?,
    // When the rider can board ([TripTiming.readyAt]): a time before it is grayed; null grays none.
    grayBefore: Instant?,
) {
    // Each line's rows; a line with none still gets a row of its own below, so every line the pill
    // names is on the page.
    val lines = ride.legs
    val byLine = remember(ride, state, now, sequences, dismissed) { rideLegRows(ride, state, now, sequences, dismissed) }
    // A later ride's rows say how often their line runs instead of counting down, as the list's card
    // does (maintainer, 2026-09-29): the rider isn't there yet, so its next few trains say nothing
    // they can use. Each line's own figure, from the trains the card's comes from ([linesHeadway]),
    // so a ride on one line reads the same on both; none where too few are known.
    val headways = remember(countsDown, lines, state, now, sequences) {
        if (countsDown) null else lines.associate { it.lineId to linesHeadway(listOf(it), state, now, sequences) }
    }
    val groups = remember(byLine) { StopGrouping.groupByStop(byLine.values.flatten()) }
    val quiet = lines.filter { byLine[it].isNullOrEmpty() }
    // A row opens its own line's page: the leg as that line rides it.
    fun legOf(row: DepartureRow) = lines.firstOrNull { it.lineId == row.lineId } ?: leg
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A quiet line sits under the group of the stop it boards at, so it never reads as boarding at
        // another pole (the other side of the road); one at a stop no group shows gets that stop's own
        // header.
        val (placed, unplaced) = placeQuiet(groups, quiet)
        groups.forEachIndexed { index, group ->
            StopGroupHeader(group.stopName, group.qualifier, distanceLabel = null, firstOnScreen = first && index == 0)
            StopGroupCard(
                group,
                now,
                starred = emptySet(),
                onToggleStar = {},
                starringAvailable = false,
                onOpenDetail = { row, focus -> onOpenDetail(legOf(row), row, focus) },
                onHideMode = onHideMode,
                grayBefore = grayBefore,
                timesInstead = if (headways == null) null else { { row -> headways[row.lineId]?.let { HeadwayLabel(it) } } },
            )
            placed[group].orEmpty().forEach { line -> NoTrainsRow(line, state, now, sequences, dismissed, onOpenDetail, onHideMode) }
        }
        unplaced.forEachIndexed { index, atStop ->
            StopGroupHeader(atStop.first().fromName, qualifier = null, distanceLabel = null, firstOnScreen = first && groups.isEmpty() && index == 0)
            atStop.forEach { line -> NoTrainsRow(line, state, now, sequences, dismissed, onOpenDetail, onHideMode) }
        }
        // "N stops to B" only where every line of the ride takes that many: one reaching B another
        // way ([RideLines.timed] leaves it out) would read as the Planner's count. Its own page lists its stops.
        rideStops(lines)?.let { stops ->
            Text(
                text = pluralStringResource(R.plurals.trip_stops_to, stops, stops, leg.toName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** How many stops every one of a ride's [lines] takes to its getting-off stop, or null where they differ. */
internal fun rideStops(lines: List<TripLeg>): Int? = lines.map { it.stops }.distinct().singleOrNull()
