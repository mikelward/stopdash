package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.JourneySegment
import app.stopdash.domain.JourneyTrains
import app.stopdash.domain.Journeys
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.SiblingPoles
import app.stopdash.domain.Staleness
import app.stopdash.domain.StarredJourney
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopLocation
import app.stopdash.domain.WidgetJourneyCheck
import app.stopdash.domain.WidgetJourneys
import java.time.Instant

/**
 * The journey cards and everything the screen reads off them, worked out together by
 * [journeyCardsOf] on a worker ([rememberComputed]): matching each journey's trains to its far end
 * walks whole line routes for every departure at its origin and neighboring poles, and the screen
 * asks again on every tick.
 */
internal data class JourneyCardsShown(
    val cards: List<JourneyCard> = emptyList(),
    // The near journeys' cards, atop the list, and the far ones', at its foot once revealed.
    val near: List<JourneyCard> = emptyList(),
    val far: List<JourneyCard> = emptyList(),
    // Each card by its journey's key, for a journey's own view.
    val byKey: Map<String, JourneyCard> = emptyMap(),
    // The departures the cards left out as unchecked, for the debug log.
    val misses: Set<RouteMiss> = emptySet(),
    // The far ends to check for a closure.
    val destinations: List<StopRef> = emptyList(),
    // What each near journey's card found for the widget, per boarding stop, and the boarding keys of
    // each journey whose neighboring poles are settled ([WidgetJourneys]).
    val widgetChecks: List<WidgetJourneyCheck> = emptyList(),
    val widgetBoarding: Map<String, Set<String>> = emptyMap(),
    // The rows the cards show, so a near-me row they cover isn't repeated and a tap on one opens.
    val rowsShown: List<DepartureRow> = emptyList(),
    // Those rows by route identity, for a route page opened from a card to follow.
    val rowsByDetailKey: Map<String, DepartureRow> = emptyMap(),
    // The near journeys, pinned on the widget (a far one opened in the app doesn't join it), and the
    // direction each is shown in, so a flip reaches the widget before its route places the new origin.
    val widgetKeys: Set<String> = emptySet(),
    val shownFrom: Map<String, String> = emptyMap(),
)

/**
 * The journey cards: the trains or buses from each journey's origin that call at its far end, on any
 * line, the origin's closure notice if it has one, or why they can't be shown yet (SPEC principle 1);
 * and what the screen, the widget and the log read off them ([JourneyCardsShown]).
 * [farJourneyMeters] says which journeys are far.
 */
@WorkerThread
internal fun journeyCardsOf(
    loaded: DeparturesUiState.Loaded?,
    now: Instant,
    cardJourneys: List<StarredJourney>,
    journeySegments: Map<String, JourneySegment?>,
    journeySequences: Map<String, LineSequence?>,
    dismissed: Set<DismissedAlert>,
    journeyAreas: Map<String, String>,
    journeyPoles: Map<String, List<StopLocation>?>,
    journeySiblings: Map<String, SiblingPoles>,
    journeyDestinationStops: List<StopArrivals>,
    journeyDestinationIds: Map<String, Set<String>>,
    journeyDestinationsUnknown: Set<String>,
    alertSequences: Map<String, LineSequence?>,
    farJourneyMeters: Map<String, Double>,
): JourneyCardsShown {
    // The widget follows the same rule as the list: only near journeys are pinned there.
    val journeyKeys = cardJourneys.filter { it.key !in farJourneyMeters }.mapTo(HashSet()) { it.key }
    val ld = loaded
    // Dismissals apply here as on the list, so an alert dismissed anywhere is gone from the card,
    // and an alert behind the origin flags it no more than the list.
    val across = DepartureRows.withAlertsBehind(
        DepartureRows.withoutDismissed(ld?.let { DepartureRows.across(it.stops, now, it.lineStatuses) }.orEmpty(), dismissed),
        alertSequences,
    )
    val cards = cardJourneys.map { journey ->
        val segment = journeySegments[journey.key]
        val originId = segment?.originId ?: journey.from.stopId
        val origin = ld?.stops?.firstOrNull { it.stopId == originId }
        // Every boarding stop's closure notice: the origin's, and each neighboring pole's.
        val boardingStops = listOf(originId) + journeySiblings[journey.key]?.poles.orEmpty().map { it.id }
        // The far-end stops the card's departures reach (another line may use another pole).
        var reached = emptySet<String>()
        // The departures its check left out as unchecked, for the debug log.
        var misses = emptySet<RouteMiss>()
        // The stop being fetched: known before the route is in for a station (see journeyOrigins).
        val fetchedId = segment?.originId ?: journey.from.stopId.takeUnless { journey.bus }
        val state = when {
            // Asked for and not come back: the fetch failed with nothing earlier to show.
            origin == null && fetchedId != null && fetchedId in ld?.unavailableStopIds.orEmpty() ->
                JourneyCardState.NotChecked()
            journey.lineId !in journeySequences -> JourneyCardState.Checking
            journeySequences[journey.lineId] == null -> JourneyCardState.RouteFailed
            // The route can't place the stops this way round (no single way-back stop).
            segment == null -> JourneyCardState.NotChecked()
            origin == null -> JourneyCardState.Checking
            // A bus origin's neighboring poles still being looked up, or their lines' routes loading.
            journey.key in journeyAreas && journey.key !in journeyPoles -> JourneyCardState.Checking
            journeySiblings[journey.key]?.pendingLines.orEmpty().isNotEmpty() -> JourneyCardState.Checking
            else -> {
                val siblings = journeySiblings[journey.key]?.poles.orEmpty()
                val siblingStops = siblings.map { pole -> ld?.stops?.firstOrNull { it.stopId == pole.id } }
                // The origin's trains, then each neighboring pole's (matched to the far end the same way).
                val parts = listOf(Journeys.trains(segment, across, journeySequences, journey)) +
                    siblings.map { pole -> Journeys.trains(JourneySegment(pole.id, emptySet()), across, journeySequences, journey) }
                val trains = JourneyTrains(
                    rows = parts.flatMap { it.rows },
                    pending = parts.any { it.pending },
                    unresolved = parts.any { it.unresolved },
                    routeFailed = parts.any { it.routeFailed },
                    misses = parts.flatMapTo(LinkedHashSet()) { it.misses },
                )
                misses = trains.misses
                // Trains on another branch, offered with where to change when no direct one is due.
                val changes = Journeys.changesWithoutDirect(trains.rows, parts.flatMap { it.changes })
                reached = parts.flatMapTo(HashSet()) { it.reachedIds }
                // A neighboring pole whose fetch failed, whose lookup did, or whose line's route
                // did, may have had a bus.
                val polesFailed = journey.key in journeyPoles && journeyPoles[journey.key] == null ||
                    journeySiblings[journey.key]?.failedLines.orEmpty().isNotEmpty()
                val siblingsMissed = siblings.zip(siblingStops).any { (pole, stop) ->
                    stop == null && pole.id in ld?.unavailableStopIds.orEmpty()
                } || polesFailed
                // "No trains" is only a claim a fresh, current fetch can make: an origin whose last
                // refresh failed (kept aged) or has gone stale says it couldn't check instead —
                // and so does one with a departure whose path couldn't be resolved, since it may
                // well call at the far end. A line whose route is still loading says checking.
                // The same holds for each neighboring pole.
                val current = (listOf(origin) + siblingStops.filterNotNull()).all { stop ->
                    stop.arrivalsFresh && !Staleness.isStale(stop.fetchedAt, now)
                }
                // A line still loading holds the whole card at "checking", so a first line's trains
                // aren't shown as if they were all; one that couldn't be checked is said so beneath
                // the rest.
                when {
                    trains.pending -> JourneyCardState.Checking
                    // A neighboring pole asked for and not in yet.
                    siblings.zip(siblingStops).any { (pole, stop) -> stop == null && pole.id !in ld?.unavailableStopIds.orEmpty() } ->
                        JourneyCardState.Checking
                    trains.rows.isNotEmpty() || changes.isNotEmpty() ->
                        JourneyCardState.Trains(
                            trains.rows,
                            // With only trains to change from, "no direct trains" is a claim
                            // only a fresh, current fetch can make too.
                            incomplete = trains.unresolved || siblingsMissed || trains.rows.isEmpty() && !current,
                            retry = trains.routeFailed || polesFailed,
                            changes = changes,
                        )
                    // A route that failed to load is the one gap a retry can close.
                    trains.routeFailed -> JourneyCardState.RouteFailed
                    !current || trains.unresolved || siblingsMissed -> JourneyCardState.NotChecked(retry = polesFailed)
                    else -> JourneyCardState.Trains(emptyList())
                }
            }
        }
        // A complete check judged every departure at the origin: the widget drops any it now rejects.
        // Per boarding stop: the origin, then each neighboring pole (pinned separately on the widget).
        val boardingIds = listOfNotNull(segment?.originId) + journeySiblings[journey.key]?.poles.orEmpty().map { it.id }
        val checked =
            if (state is JourneyCardState.Trains && !state.incomplete) {
                boardingIds.associateWith { id ->
                    across.filter { it.stopId == id && it.lineId.isNotBlank() }
                        .flatMapTo(HashSet()) { row -> row.upcoming.map(JourneyCall::of) }
                }
            } else {
                emptyMap()
            }
        // And the far end's (closed or moved), from its own check, for every stop the card's departures
        // reach there: a journey can't end as shown. One card per notice, however many poles carry it.
        val destinationIds = journeyDestinationIds[journey.key].orEmpty() + reached
        val destinationClosures = DepartureRows.withoutDismissed(
            DepartureRows.across(journeyDestinationStops.filter { it.stopId in destinationIds }, now),
            dismissed,
        ).filter { it.stopDisruption != null }.groupBy { it.stopDisruption }.values.toList()
        val closures = boardingStops.mapNotNull { id ->
            across.firstOrNull { it.stopId == id && it.stopDisruption != null }?.let(::listOf)
        } + destinationClosures
        // A destination whose check failed with nothing known: the card says so, not "open".
        val destinationUnchecked = destinationIds.any { it in journeyDestinationsUnknown }
        JourneyCard(journey, state, closures, checked, boardingIds, destinationIds, destinationUnchecked, misses)
    }
    // The far ends to check for a closure.
    val destinations = cards.flatMap { card -> card.destinationIds.map { id -> StopRef(id, card.journey.to.name) } }.distinctBy { it.id }
    // One check per boarding stop: the origin under the journey's key, a neighboring pole under its
    // [WidgetJourneys.poleKey], each pinned on the widget from its own stop.
    val widgetChecks = cards.filter { it.journey.key in journeyKeys }.flatMap { card ->
        val key = card.journey.key
        val rows = (card.state as? JourneyCardState.Trains)?.rows.orEmpty()
        card.boardingIds.mapIndexed { i, id ->
            WidgetJourneyCheck(
                if (i == 0) key else WidgetJourneys.poleKey(key, id),
                id,
                rows.filter { it.stopId == id }.flatMapTo(HashSet()) { row -> row.upcoming.map(JourneyCall::of) },
                card.checked[id].orEmpty(),
                card.journey.from.stopId,
            )
        }
    }
    // The boarding keys of each journey whose neighboring poles are settled (none to look up, or
    // looked up and judged), so a pole that no longer qualifies loses its widget pin.
    val widgetBoarding = cards.filter { card ->
        val key = card.journey.key
        card.boardingIds.isNotEmpty() &&
            (key !in journeyAreas || journeyPoles[key] != null && journeySiblings[key]?.settled == true)
    }.associate { card ->
        val key = card.journey.key
        key to card.boardingIds.mapIndexedTo(HashSet()) { i, id -> if (i == 0) key else WidgetJourneys.poleKey(key, id) }
    }
    val rowsShown = cards.flatMap { (it.state as? JourneyCardState.Trains)?.shownRows.orEmpty() }
    return JourneyCardsShown(
        cards = cards,
        near = cards.filter { it.journey.key !in farJourneyMeters },
        far = cards.filter { it.journey.key in farJourneyMeters },
        byKey = cards.associateBy { it.journey.key },
        misses = cards.flatMapTo(LinkedHashSet()) { it.misses },
        destinations = destinations,
        widgetChecks = widgetChecks,
        widgetBoarding = widgetBoarding,
        rowsShown = rowsShown,
        rowsByDetailKey = byDetailKey(rowsShown),
        widgetKeys = journeyKeys,
        shownFrom = cardJourneys.filter { it.key in journeyKeys }.associate { it.key to it.from.stopId },
    )
}
