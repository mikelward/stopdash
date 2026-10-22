package app.stopdash.ui

import android.content.res.Resources
import androidx.annotation.WorkerThread
import app.stopdash.R
import app.stopdash.domain.AroundDelays
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LegTimetables
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteDisruption
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TripTiming
import app.stopdash.domain.stopPlaceKey
import java.time.Duration
import java.time.Instant

/**
 * Everything a trip page draws on a tick, worked out on the page's worker ([tripFrame]), never in
 * composition (AGENTS.md *Main thread: read and dispatch only*). It carries the [state], time [now],
 * walk to the first stop ([access]), [sequences] and [rideLines] it was worked out from, and the trip
 * it's for ([tripKey]): the page is drawn against those, so a frame
 * standing in while the next is worked out is never paired with a newer clock or snapshot (as the
 * near-me list's rows, #524).
 *
 * [estimates]: every route timed, the open one kept; [cards] the list's cards; [open] the route opened
 * ([openKey]), if the plan still offers it, and [starts] the route a Start of it starts: as the Planner gave it,
 * never the copy drawn cut at the destination's hub ([ShownRoutes.plannedOf]). [shownStops]: the stops only the
 * screen can name, for the trip to check.
 * [list] and [openView] are what the list's cards and the open route draw, each with its own
 * framing ([TripFraming]), so a frame held while a route is opened or closed frames whichever is shown.
 */
internal class TripFrame(
    val tripKey: Any?,
    val state: TripViewModel.State,
    val now: Instant,
    val access: Duration,
    val sequences: Map<String, LineSequence?>,
    val rideLines: TripRideLines,
    val estimates: List<TripTiming.Estimate>?,
    val cards: List<List<TripTiming.Estimate>>?,
    val open: TripTiming.Estimate?,
    val openKey: String?,
    val shownStops: Set<String>,
    val list: TripListView?,
    val openView: TripOpenView?,
    // The rides whose timetable the estimates asked for and the trip hasn't got ([timetablesWanted]).
    val timetablesWanted: Set<LegTimetables.Key> = emptySet(),
    val starts: TripRoute? = open?.route,
)

/**
 * What frames the routes shown: the check banner ([check], see [tripCheckState]), the trains it means
 * ([misses], for the debug log) and the boarding stops whose refresh failed ([failed], see [failedStops]).
 */
internal class TripFraming(val check: TripMessage?, val misses: Set<RouteMiss>, val failed: List<String>)

/**
 * The list's cards as drawn: what frames them ([framing]), each card's own ([cards], in
 * [TripFrame.cards]' order; [byKey], by its card's key), and the order and headers they're drawn
 * under ([listed]). The order comes with the cards, so a new plan's cards never show unheaded and then
 * head and re-sort under the rider (Codex, #543).
 */
internal class TripListView(
    val framing: TripFraming,
    val cards: List<TripCardView>,
    // What each card opens, by its route's key ([openRouteOf]), from the same snapshot.
    val opens: Map<String, OpenRoute>,
    val listed: ListedCards,
) {
    val byKey: Map<String, TripCardView> = cards.associateBy { cardKey(it.card.first().route) }
}

/**
 * A card as drawn: its modes and lines for its long press, its lines' alerts ([statuses]), the walk to
 * its first stop ([walk]), its stops' closure notices ([closures]), each ride's own in its ⚠
 * ([rideClosures], by ride, [cardClosures]), its first ride's trains ([times]), and on a step-free trip
 * where each ride has lost its way on or off to a lift out, in its ⚠ too ([rideStepFree], by ride).
 */
internal class TripCardView(
    val card: List<TripTiming.Estimate>,
    val modes: List<String>,
    val lines: List<LineRef>,
    val statuses: Map<String, LineStatus>,
    val walk: Duration,
    val closures: Map<String, DepartureRow>,
    val rideClosures: List<List<DepartureRow>>,
    val times: CardTimes,
    val rideStepFree: List<String?> = emptyList(),
)

/**
 * The open route as drawn: what frames it ([framing]), its stops' closure notices ([closures]), its
 * summary's alerts ([statuses]) and each ride's rows ([rides], by leg index; null for a walk), so an
 * open ride draws whole in the frame that opens it, never its stop count first and its rows after.
 */
internal class TripOpenView(
    val framing: TripFraming,
    val closures: Map<String, DepartureRow>,
    // Each of [closures]' cards ([tripClosureCards]), by every stop it covers.
    val closureCards: Map<String, TripClosureCard> = emptyMap(),
    val statuses: Map<String, LineStatus>,
    val rides: List<RideLegView?>,
)

/** [TripFrame] for [state] at [now]: off the main thread, on the page's worker. */
@WorkerThread
internal fun tripFrame(
    // The trip it's for ([TripScreen]'s `tripKey`).
    tripKey: Any?,
    state: TripViewModel.State,
    now: Instant,
    access: Duration,
    sequences: Map<String, LineSequence?>,
    excluded: Set<String>,
    originUnconfirmed: Boolean,
    rideLines: TripRideLines,
    plannedLegs: Set<TripLeg>,
    openKey: String?,
    dismissed: Set<DismissedAlert>,
    // The lines whose route data is loading ([LineLoads.loading]), a retry included.
    loading: Set<String>,
    routeStops: RouteStopsRepository?,
    // The routes as planned and placed ([onPoles]), before trains through a change are added: what a
    // tapped card opens is worked out from them ([openRouteOf]).
    poled: TripViewModel.State,
    // How step-free the trip must be: where a train through a change gets off ([openRoutesOf]).
    stepFree: StepFree,
    // The order the last frame drew its cards in ([CardOrder]): the same order comes back as that very
    // object, so the screen tells a re-sort apart by identity.
    previousOrder: CardOrder? = null,
    // How an open ride's stop cards group a branching line's trains ([stopCard]).
    topology: RouteTopology = RouteTopology.EMPTY,
    // The routes as drawn ([poled] among them, [ShownRoutes.state]): a tapped card opens from those as planned
    // ([ShownRoutes.of]), and a Start starts the route as planned ([ShownRoutes.plannedOf]).
    shown: ShownRoutes? = null,
    // What a card's step-free notices are written with ([rideStepFree]).
    resources: Resources,
): TripFrame {
    // On a step-free trip, the routes a lift out leaves with no step-free way on or off a ride, among them the trains
    // through a change made since [shown] was worked out ([ShownRoutes.notStepFreeOf]).
    val notStepFree = shown?.notStepFreeOf(state.routes.orEmpty()).orEmpty()
    val timed = tripEstimates(state, now, access, sequences, excluded, originUnconfirmed, rideLines, keep = openKey, planned = plannedLegs, notStepFree = notStepFree)
    // Those whose trains through a change are predicted ([TripTiming.withoutUnvouchedLegs]).
    // By identity: an estimate's equality would go over its whole route.
    val vouched = timed.orEmpty().filterTo(java.util.Collections.newSetFromMap(java.util.IdentityHashMap())) {
        TripTiming.withoutUnvouchedLegs(listOf(it), plannedLegs).isNotEmpty()
    }
    // The open route stays, its arrival withheld while its train through a change isn't predicted.
    val estimates = timed?.filter { routeKey(it.route) == openKey || it in vouched }
    val shownStops = estimates.orEmpty().flatMapTo(HashSet()) { shownStops(it.route, sequences, rideLines) }
    // A route with more changes than another getting there no later is left off the list
    // ([TripTiming.withoutSlowerChanges]); an open one stays open.
    // The list's cards leave out a route timed only as the one open, past the cap ([TripViewModel.bestOf]):
    // closing it while this frame stands in shows the list it would show (Codex, #529).
    // Nor a Direct row's route ([TripViewModel.openDirect]): it's there only to be open, and the soonest
    // few are counted without it, so it never takes a card's place.
    // Capped by the delays in the statuses shown, as the estimates were ([tripEstimates]; Codex, #703).
    val listed = TripViewModel.bestOf(state.shownRoutes(excluded).orEmpty(), direct = state.directKeys, delayed = AroundDelays.delayed(state.statuses.values))
        .mapTo(HashSet(), ::routeKey)
    // Nor one kept only as the one open, its train through a change not predicted: closed, it's off the
    // list as it would be (Codex, #529).
    // A route found via a stop towards the destination is listed whatever, by its key ([towardKeys]).
    val toward = towardKeys(state.routes.orEmpty())
    val cards = estimates?.let { all ->
        tripCards(TripTiming.withoutSlowerChanges(all.filter { routeKey(it.route) in listed && it in vouched }) { routeKey(it.route) in toward })
    }
    val open = estimates?.firstOrNull { routeKey(it.route) == openKey }
    val hubOf: (String) -> String? = { routeStops?.hubOf(it) }
    // Each bus leg's alerts placed on its ride ([legStatuses]), by where the ride gets off as its lines
    // were worked out with ([TripRideLines.linesOf]), for the open route's rows and the line pages they
    // open, which the page builds as it draws.
    val rides = estimates.orEmpty().flatMap { rideLines.endsOf(it.route) }.flatMap(rideLines::linesOf).distinct()
    val state = state.copy(legStatuses = legStatuses(state.statuses, rides, sequences))
    // The list even with a route open: closed again, the list shows at once from the frame in hand.
    val list = cards?.let { listView(it, state, now, access, sequences, rideLines, dismissed, loading, hubOf, openRoutesOf(it.flatten().map { e -> e.route }, shown?.of ?: poled, stepFree, sequences, excluded, rideLines::endsOf), previousOrder, notStepFree, resources) }
    // A pole's letter, sign and bearing, from its stop area's poles where they're cached (no IO).
    val poleOf: (String, String) -> StopLocation? = { id, area ->
        area.ifBlank { null }?.let { routeStops?.cachedPoles(it) }?.firstOrNull { it.id == id }
    }
    val openView = open?.let { openView(it, state, now, sequences, rideLines, dismissed, loading, originUnconfirmed, hubOf, topology, poleOf) }
    val starts = open?.route?.let { route -> shown?.plannedOf(route) ?: route }
    return TripFrame(tripKey, state, now, access, sequences, rideLines, estimates, cards, open, openKey, shownStops, list, openView, timetablesWanted(timed.orEmpty(), state, now), starts)
}

/**
 * The keys of the routes the plan found via a bus stop towards the destination ([TripRoute.towardStop]),
 * listed whatever another route beats them by ([TripTiming.withoutSlowerChanges]). By key, not by the
 * mark alone: one timetable variant stands for all a key's in [tripEstimates], and the one standing in
 * can be a copy the Planner's own requests offered at another departure, unmarked (Codex, #870).
 */
@WorkerThread
internal fun towardKeys(routes: List<TripRoute>): Set<String> = routes.filter { it.towardStop }.mapTo(HashSet(), ::routeKey)

private fun listView(
    cards: List<List<TripTiming.Estimate>>,
    state: TripViewModel.State,
    now: Instant,
    access: Duration,
    sequences: Map<String, LineSequence?>,
    rideLines: TripRideLines,
    dismissed: Set<DismissedAlert>,
    loading: Set<String>,
    hubOf: (String) -> String?,
    // What each card opens ([openRoutesOf]).
    opens: Map<String, OpenRoute>,
    previousOrder: CardOrder?,
    // The routes, by key, a lift out leaves with no step-free way on or off a ride ([ShownRoutes.notStepFree]).
    notStepFree: Map<String, List<RouteDisruption.Signal.NotStepFree>>,
    // What the step-free notices are written with.
    resources: Resources,
): TripListView {
    // Said as On the way says it, the stop by name.
    val notStepFreeText = { lost: RouteDisruption.Signal.NotStepFree ->
        resources.getString(R.string.route_disruption_not_step_free, lost.stopName.ifBlank { lost.stopId })
    }
    // The row over the cards is the list's own work ([rememberTripRow]), kept apart so it outlives an open route.
    val views = cards.map { card ->
        // Every route's on the card: another line's ride may use another pole of the pair.
        val closures = card.fold(emptyMap<String, DepartureRow>()) { found, estimate -> found + routeClosures(estimate.route, state, now, dismissed, sequences, rideLines, hubOf) }
        TripCardView(
            card = card,
            modes = cardModes(card),
            lines = cardLines(card, rideLines),
            // Each line's alerts for the way the card rides it, less those dismissed.
            statuses = shownStatuses(cardStatuses(card, rideLines, state, now, sequences), dismissed),
            walk = walkToStart(card.first().route, access),
            closures = closures,
            rideClosures = card.first().route.rides.indices.map { cardClosures(card, it, closures, rideLines) },
            times = cardTimes(card, state, now, access, sequences, rideLines),
            // Every route's on the card: another line's ride may have lost its way on where this one's hasn't.
            rideStepFree = rideStepFree(card.map { it.route }, notStepFree, notStepFreeText),
        )
    }
    // The list takes every route's warnings.
    return TripListView(framing(cards.flatten(), state, now, sequences, rideLines), views, opens, listedCards(cards, previousOrder))
}

/**
 * Where a card's [routes] have lost a way on or off ([ShownRoutes.notStepFree], by key, each by its leg's index among its
 * route's legs) said by each of the card's rides in turn, as it draws them: every route's on the card, one notice a
 * station ([RouteDisruption.Signal.NotStepFree.key]), each by [text], a ride's joined as its ⚠ joins its notices, null
 * for a ride with none; empty where none is lost. Written here, on the worker, so the card only reads it.
 */
@WorkerThread
internal fun rideStepFree(
    routes: List<TripRoute>,
    notStepFree: Map<String, List<RouteDisruption.Signal.NotStepFree>>,
    text: (RouteDisruption.Signal.NotStepFree) -> String,
): List<String?> {
    val rides = routes.firstOrNull()?.rides?.size ?: return emptyList()
    if (notStepFree.isEmpty()) return emptyList()
    // Each ride's, by its place among the card's rides, a station once.
    val byRide = HashMap<Int, LinkedHashMap<String, RouteDisruption.Signal.NotStepFree>>()
    for (route in routes) {
        val lost = notStepFree[routeKey(route)] ?: continue
        val rideOf = route.legs.withIndex().filter { !it.value.isWalk }.withIndex().associate { (ride, leg) -> leg.index to ride }
        for (signal in lost) {
            val ride = rideOf[signal.legIndex]?.takeIf { it < rides } ?: continue
            byRide.getOrPut(ride) { LinkedHashMap() }.putIfAbsent(signal.key, signal)
        }
    }
    if (byRide.isEmpty()) return emptyList()
    return (0 until rides).map { ride -> byRide[ride]?.values?.joinToString("; ", transform = text) }
}

private fun openView(
    estimate: TripTiming.Estimate,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    rideLines: TripRideLines,
    dismissed: Set<DismissedAlert>,
    loading: Set<String>,
    originUnconfirmed: Boolean,
    hubOf: (String) -> String?,
    topology: RouteTopology,
    poleOf: (String, String) -> StopLocation?,
): TripOpenView {
    val rows = routeClosureRows(estimate.route, state, now, sequences, rideLines, hubOf, poleOf)
    val closures = foldedClosures(rows, dismissed)
    return TripOpenView(
        // With a route open, only its own legs' warnings frame it.
        framing = framing(listOf(estimate), state, now, sequences, rideLines),
        closures = closures,
        closureCards = tripClosureCards(closures, rows),
        statuses = shownStatuses(cardStatuses(listOf(estimate), rideLines, state, now, sequences), dismissed),
        rides = openRides(estimate.route, state, now, sequences, rideLines, dismissed, topology),
    )
}

/**
 * One closure card on the open route: the [row] it's drawn and dismissed by, and its title
 * ([closureTitleOf]).
 */
internal class TripClosureCard(val row: DepartureRow, val title: String)

/**
 * The open route's closure cards, by the stop each is drawn at ([closures], already one per notice
 * at a place): each titled by every pole the route reaches that carries its notice ([rows], before
 * they were folded), so a notice on two poles of one stop is headed by that stop, one on a single
 * pole by its letter.
 */
internal fun tripClosureCards(closures: Map<String, DepartureRow>, rows: List<DepartureRow>): Map<String, TripClosureCard> {
    val byNotice = rows.groupBy { stopPlaceKey(it) to it.stopDisruption }
    return closures.mapValues { (_, row) ->
        TripClosureCard(row, closureTitleOf(byNotice[stopPlaceKey(row) to row.stopDisruption] ?: listOf(row)))
    }
}

/**
 * Each of [route]'s rides as the open route draws it ([rideLegView]); null for a walk. Only the rider's
 * next ride counts down: they aren't at a later one's stop yet.
 */
private fun openRides(
    route: TripRoute,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    rideLines: TripRideLines,
    dismissed: Set<DismissedAlert>,
    topology: RouteTopology,
): List<RideLegView?> {
    val legs = route.legs
    val nextRide = legs.indexOfFirst { !it.isWalk }
    // Each leg's lines, by where [route] gets it off.
    val ridden = rideLines.legsOf(route)
    val ended = rideLines.legEndsOf(route)
    return legs.mapIndexed { index, leg ->
        // A walk gets off nowhere: no end, no view.
        val end = ended[index] ?: return@mapIndexed null
        rideLegView(ridden[index], end, state, now, sequences, dismissed, countsDown = index == nextRide, topology = topology)
    }
}

private fun framing(
    shown: List<TripTiming.Estimate>,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    rideLines: TripRideLines,
): TripFraming = TripFraming(
    tripCheckState(state, shown, now, sequences, rideLines),
    tripMisses(state, shown, now, sequences, rideLines),
    failedStops(shown, state, rideLines),
)

/**
 * The rides of [estimates] timed past their live predictions ([TripTiming.Estimate.scheduleAsked]) on a
 * line running a good service ([rideSchedule]) whose timetable [state] hasn't got for [now]'s service
 * day: the trip fetches them ([TripViewModel.wantTimetables]).
 */
@WorkerThread
internal fun timetablesWanted(estimates: List<TripTiming.Estimate>, state: TripViewModel.State, now: Instant): Set<LegTimetables.Key> {
    val held = if (timetablesCurrent(state, now)) state.timetables.keys else emptySet()
    return estimates.flatMapTo(HashSet()) { estimate ->
        estimate.scheduleAsked.mapNotNull { index -> estimate.route.legs.getOrNull(index) }
            .filter { leg -> goodService(state, leg.lineId, now) }
            .map(::legTimetableKey)
            .filterNot { it in held }
    }
}

// Whether [state]'s timetables are for [now]'s service day: a trip left open past 04:00 holds the day before's.
private fun timetablesCurrent(state: TripViewModel.State, now: Instant): Boolean =
    state.timetablesDay == LegTimetables.serviceDay(now)

/** The ride [leg]'s timetable is fetched for ([LegTimetables]). */
internal fun legTimetableKey(leg: TripLeg): LegTimetables.Key = LegTimetables.Key(leg.lineId, leg.fromId, leg.toId)

/**
 * Whether [lineId] runs a good service at [now], by a status the trip can stand behind
 * ([tripLineInDoubt]: not one whose latest check failed or went stale): only then does a trip go by its
 * timetable ([rideSchedule]), which knows nothing of delays (maintainer, 2026-10-19).
 */
internal fun goodService(state: TripViewModel.State, lineId: String, now: Instant): Boolean =
    !tripLineInDoubt(state, lineId, now) && state.statuses[lineId]?.severity == LineStatus.GOOD_SERVICE

/**
 * [leg]'s timetable for [TripTiming.estimate]'s schedule: fetched for [now]'s service day, on a line
 * running a good service.
 */
internal fun rideSchedule(state: TripViewModel.State, leg: TripLeg, now: Instant) =
    state.timetables[legTimetableKey(leg)]?.takeIf { goodService(state, leg.lineId, now) && timetablesCurrent(state, now) }
