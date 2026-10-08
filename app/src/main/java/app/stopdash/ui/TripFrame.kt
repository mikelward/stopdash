package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.AroundDelays
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RideLines
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripTiming
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
 * ([openKey]), if the plan still offers it. [shownStops]: the stops only the screen can name, for the trip to check.
 * [list] and [openView] are what the list's cards and the open route draw, each with its own
 * framing ([TripFraming]), so a frame held while a route is opened or closed frames whichever is shown.
 */
internal class TripFrame(
    val tripKey: Any?,
    val state: TripViewModel.State,
    val now: Instant,
    val access: Duration,
    val sequences: Map<String, LineSequence?>,
    val rideLines: Map<TripLeg, RideLines>,
    val estimates: List<TripTiming.Estimate>?,
    val cards: List<List<TripTiming.Estimate>>?,
    val open: TripTiming.Estimate?,
    val openKey: String?,
    val shownStops: Set<String>,
    val list: TripListView?,
    val openView: TripOpenView?,
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
 * ([rideClosures], by ride, [cardClosures]) and its first ride's trains ([times]).
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
)

/**
 * The open route as drawn: what frames it ([framing]), its stops' closure notices ([closures]), its
 * summary's alerts ([statuses]) and each ride's rows ([rides], by leg index; null for a walk), so an
 * open ride draws whole in the frame that opens it, never its stop count first and its rows after.
 */
internal class TripOpenView(
    val framing: TripFraming,
    val closures: Map<String, DepartureRow>,
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
    rideLines: Map<TripLeg, RideLines>,
    plannedLegs: Set<TripLeg>,
    openKey: String?,
    dismissed: Set<DismissedAlert>,
    // The lines whose route data is loading ([LineLoads.loading]), a retry included.
    loading: Set<String>,
    routeStops: RouteStopsRepository?,
    // The routes as planned and placed ([onPoles]), before trains through a change are added: what a
    // tapped card opens is worked out from them ([openRouteOf]).
    poled: TripViewModel.State,
    // The order the last frame drew its cards in ([CardOrder]): the same order comes back as that very
    // object, so the screen tells a re-sort apart by identity.
    previousOrder: CardOrder? = null,
    // How an open ride's stop cards group a branching line's trains ([stopCard]).
    topology: RouteTopology = RouteTopology.EMPTY,
): TripFrame {
    val timed = tripEstimates(state, now, access, sequences, excluded, originUnconfirmed, rideLines, keep = openKey, planned = plannedLegs)
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
    val cards = estimates?.let { all -> tripCards(TripTiming.withoutSlowerChanges(all.filter { routeKey(it.route) in listed && it in vouched })) }
    val open = estimates?.firstOrNull { routeKey(it.route) == openKey }
    val hubOf: (String) -> String? = { routeStops?.hubOf(it) }
    // The list even with a route open: closed again, the list shows at once from the frame in hand.
    val list = cards?.let { listView(it, state, now, access, sequences, rideLines, dismissed, loading, hubOf, openRoutesOf(it.flatten().map { e -> e.route }, poled, sequences, excluded), previousOrder) }
    val openView = open?.let { openView(it, state, now, sequences, rideLines, dismissed, loading, originUnconfirmed, hubOf, topology) }
    return TripFrame(tripKey, state, now, access, sequences, rideLines, estimates, cards, open, openKey, shownStops, list, openView)
}

private fun listView(
    cards: List<List<TripTiming.Estimate>>,
    state: TripViewModel.State,
    now: Instant,
    access: Duration,
    sequences: Map<String, LineSequence?>,
    rideLines: Map<TripLeg, RideLines>,
    dismissed: Set<DismissedAlert>,
    loading: Set<String>,
    hubOf: (String) -> String?,
    // What each card opens ([openRoutesOf]).
    opens: Map<String, OpenRoute>,
    previousOrder: CardOrder?,
): TripListView {
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
        )
    }
    // The list takes every route's warnings.
    return TripListView(framing(cards.flatten(), state, now, sequences, rideLines), views, opens, listedCards(cards, previousOrder))
}

private fun openView(
    estimate: TripTiming.Estimate,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    rideLines: Map<TripLeg, RideLines>,
    dismissed: Set<DismissedAlert>,
    loading: Set<String>,
    originUnconfirmed: Boolean,
    hubOf: (String) -> String?,
    topology: RouteTopology,
): TripOpenView {
    return TripOpenView(
        // With a route open, only its own legs' warnings frame it.
        framing = framing(listOf(estimate), state, now, sequences, rideLines),
        closures = routeClosures(estimate.route, state, now, dismissed, sequences, rideLines, hubOf),
        statuses = shownStatuses(cardStatuses(listOf(estimate), rideLines, state, now, sequences), dismissed),
        rides = openRides(estimate.route.legs, state, now, sequences, rideLines, dismissed, topology),
    )
}

/**
 * Each of [legs]' rides as the open route draws it ([rideLegView]); null for a walk. Only the rider's
 * next ride counts down: they aren't at a later one's stop yet.
 */
private fun openRides(
    legs: List<TripLeg>,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    rideLines: Map<TripLeg, RideLines>,
    dismissed: Set<DismissedAlert>,
    topology: RouteTopology,
): List<RideLegView?> {
    val nextRide = legs.indexOfFirst { !it.isWalk }
    return legs.mapIndexed { index, leg ->
        if (leg.isWalk) null else rideLegView(rideLines[leg] ?: RideLines.only(leg), state, now, sequences, dismissed, countsDown = index == nextRide, topology = topology)
    }
}

private fun framing(
    shown: List<TripTiming.Estimate>,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    rideLines: Map<TripLeg, RideLines>,
): TripFraming = TripFraming(
    tripCheckState(state, shown, now, sequences, rideLines),
    tripMisses(state, shown, now, sequences, rideLines),
    failedStops(shown, state, rideLines),
)
