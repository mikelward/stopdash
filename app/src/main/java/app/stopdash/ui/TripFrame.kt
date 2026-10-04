package app.stopdash.ui

import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.HeadedCard
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RideLines
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripTiming
import app.stopdash.domain.headedCards
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
 * The list's cards as drawn: what frames them ([framing]), the status note ([status], see
 * [statusNote]) and what it names ([uncheckedNames]), each card's header ([headed]) and each card's
 * own ([cards], in [TripFrame.cards]' order).
 */
internal class TripListView(
    val framing: TripFraming,
    val status: Boolean?,
    val names: List<String>,
    val headed: List<HeadedCard>,
    val cards: List<TripCardView>,
)

/**
 * A card as drawn: its modes and lines for its long press, its lines' alerts ([statuses]), the walk to
 * its first stop ([walk]), its stops' closure notices ([closures]) and its first ride's trains ([times]).
 */
internal class TripCardView(
    val card: List<TripTiming.Estimate>,
    val modes: List<String>,
    val lines: List<LineRef>,
    val statuses: Map<String, LineStatus>,
    val walk: Duration,
    val closures: Map<String, DepartureRow>,
    val times: CardTimes,
)

/**
 * The open route as drawn: what frames it ([framing]), its stops' closure notices ([closures]), its summary's alerts ([statuses]),
 * its status note ([status]) and what that names ([names]), and whether it can start ([canStart]).
 */
internal class TripOpenView(
    val framing: TripFraming,
    val closures: Map<String, DepartureRow>,
    val statuses: Map<String, LineStatus>,
    val status: Boolean?,
    val names: List<String>,
    val canStart: Boolean,
)

/** [TripFrame] for [state] at [now]: off the main thread, on the page's worker. */
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
): TripFrame {
    val estimates = tripEstimates(state, now, access, sequences, excluded, originUnconfirmed, rideLines, keep = openKey, planned = plannedLegs)
        // The open route stays, its arrival withheld while its train through a change isn't predicted.
        ?.filter { routeKey(it.route) == openKey || TripTiming.withoutUnvouchedLegs(listOf(it), plannedLegs).isNotEmpty() }
    val shownStops = estimates.orEmpty().flatMapTo(HashSet()) { shownStops(it.route, sequences, rideLines) }
    // A route with more changes than another getting there no later is left off the list
    // ([TripTiming.withoutSlowerChanges]); an open one stays open.
    // The list's cards leave out a route timed only as the one open, past the cap ([TripViewModel.bestOf]):
    // closing it while this frame stands in shows the list it would show (Codex, #529).
    val listed = TripViewModel.bestOf(state.shownRoutes(excluded).orEmpty()).mapTo(HashSet(), ::routeKey)
    val cards = estimates?.let { all -> tripCards(TripTiming.withoutSlowerChanges(all.filter { routeKey(it.route) in listed })) }
    val open = estimates?.firstOrNull { routeKey(it.route) == openKey }
    val hubOf: (String) -> String? = { routeStops?.hubOf(it) }
    // The list even with a route open: closed again, the list shows at once from the frame in hand.
    val list = cards?.let { listView(it, state, now, access, sequences, rideLines, dismissed, loading, hubOf) }
    val openView = open?.let { openView(it, state, now, sequences, rideLines, dismissed, loading, originUnconfirmed, hubOf) }
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
): TripListView {
    // Lines not yet checked rank as unchecked, and say so: checking while a check runs, and only a
    // finished check says it couldn't. A route revealed since the last refresh (a mode shown again)
    // counts by its own lines. So does another line shown with its trains grayed
    // ([RideLines.unchecked]): once the check is over it isn't still "checking", it couldn't be.
    val rides = cards.flatMap { card -> card.flatMap { it.route.rides } }
    val otherLines = RideLines.unchecked(rides.mapNotNull { rideLines[it] }, rideStatuses(state))
    // Only the routes shown: a hidden mode's line or failed stop isn't these routes'.
    val shownLines = rides.mapTo(HashSet()) { it.lineId }
    val status = statusNote(
        state,
        // A Planner line still unchecked says so even where another line keeps its route usable.
        state.statusUnknown.any { it in shownLines } || shownLines.any { it !in state.statuses } || otherLines.isNotEmpty() ||
            cards.any { card -> card.any { it.unchecked || otherLineStopsUnchecked(it.route, state, rideLines) } },
        cards.any { card -> card.any { routeClosuresFailed(it.route, state, sequences, rideLines) } },
        cards.any { card -> card.any { routeStatusFailed(it.route, rideLines, state) } },
        awaitingRoutes(cards.flatten(), sequences, loading),
    )
    val names = if (status == false) uncheckedNames(cards.flatten(), state, now, sequences, rideLines) else emptyList()
    // Which card gets there soonest, which rides fewest and which walks least, over each, then the
    // rest under "Other" (maintainer, 2026-09-30).
    val headed = headedCards(cards.map { it.first() })
    val views = cards.map { card ->
        TripCardView(
            card = card,
            modes = cardModes(card),
            lines = cardLines(card, rideLines),
            // Each line's alerts for the way the card rides it, less those dismissed.
            statuses = shownStatuses(cardStatuses(card, rideLines, state, now, sequences), dismissed),
            walk = walkToStart(card.first().route, access),
            // Every route's on the card: another line's ride may use another pole of the pair.
            closures = card.fold(emptyMap()) { found, estimate -> found + routeClosures(estimate.route, state, now, dismissed, sequences, rideLines, hubOf) },
            times = cardTimes(card, state, now, access, sequences, rideLines),
        )
    }
    // The list takes every route's warnings.
    return TripListView(framing(cards.flatten(), state, now, sequences, rideLines), status, names, headed, views)
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
): TripOpenView {
    val otherLines = RideLines.unchecked(estimate.route.rides.mapNotNull { rideLines[it] }, rideStatuses(state))
    // A Planner line still unchecked says so even where another line keeps the route ranked usable.
    val plannerUnchecked = estimate.route.rides.any { it.lineId in state.statusUnknown || it.lineId !in state.statuses }
    val status = statusNote(
        state,
        estimate.unchecked || plannerUnchecked || otherLines.isNotEmpty() || otherLineStopsUnchecked(estimate.route, state, rideLines),
        routeClosuresFailed(estimate.route, state, sequences, rideLines),
        routeStatusFailed(estimate.route, rideLines, state),
        awaitingRoutes(listOf(estimate), sequences, loading),
    )
    return TripOpenView(
        // With a route open, only its own legs' warnings frame it.
        framing = framing(listOf(estimate), state, now, sequences, rideLines),
        closures = routeClosures(estimate.route, state, now, dismissed, sequences, rideLines, hubOf),
        statuses = shownStatuses(cardStatuses(listOf(estimate), rideLines, state, now, sequences), dismissed),
        status = status,
        names = if (status == false) uncheckedNames(listOf(estimate), state, now, sequences, rideLines) else emptyList(),
        canStart = canStart(estimate.route, sequences, originUnconfirmed),
    )
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
