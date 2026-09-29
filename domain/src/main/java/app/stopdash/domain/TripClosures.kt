package app.stopdash.domain

import java.time.Instant

/**
 * The stops a trip checks for closures (SPEC *Trips with a change*), and how a route stands by them:
 * every stop a leg boards or gets off at, walks included, so where the route ends is checked as the
 * list checks a starred journey's far end (*Alerts for the journey shown*).
 */
object TripClosures {
    /**
     * One stop a route's check covers: its [id], for a bus stop the pair ([area], "490G…") it's a
     * pole of, and the line of the ride that stops there ([lineId], blank for a stop only a walk uses).
     */
    data class End(val id: String, val area: String = "", val lineId: String = "")

    /**
     * The stops [route] boards or gets off at, in order: each ride's two ends (so both ends of a walk
     * between stations), and a walk's end with no ride beside it: where the route starts on foot
     * from a stop, where it ends at one, or both ends of a route that only walks. A walk's end beside
     * a ride is that ride's stop, judged where its bus is placed, which can be the pair's other pole
     * from the one the Planner walks to. A walk from or to a place (a coordinate) has no stop there.
     * A stop two rides share (a change between buses at one pole) comes once per line, since each
     * ride's bus is placed on its pole by its own line's route.
     */
    fun ends(route: TripRoute): List<End> {
        val legs = route.legs
        val all = legs.flatMapIndexed { i, leg ->
            if (!leg.isWalk) {
                listOf(End(leg.fromId, leg.fromArea, leg.lineId), End(leg.toId, leg.toArea, leg.lineId))
            } else {
                listOfNotNull(
                    End(leg.fromId, leg.fromArea).takeIf { legs.getOrNull(i - 1)?.isWalk != false },
                    End(leg.toId, leg.toArea).takeIf { legs.getOrNull(i + 1)?.isWalk != false },
                )
            }
        }.filter { it.id.isNotBlank() }
        val ridden = all.filter { it.lineId.isNotEmpty() }.mapTo(HashSet()) { it.id }
        return all.filterNot { it.lineId.isEmpty() && it.id in ridden }.distinctBy { it.id to it.lineId }
    }

    /** How a route stands by its stops' checks, as [TripTiming.rank] tiers it. */
    enum class Standing { OPEN, UNCHECKED, CLOSED }

    /**
     * [route]'s standing by its stops' checks at [now]: CLOSED if any stop it boards or gets off at
     * is ([judge]), else UNCHECKED if any is, else OPEN.
     */
    fun standing(
        route: TripRoute,
        closures: Map<String, List<StopDisruption>>,
        unknown: Set<String>,
        now: Instant,
        pole: (End) -> String? = { it.id },
    ): Standing {
        var unchecked = false
        for (end in ends(route)) {
            when (judge(end, closures, unknown, now, pole(end))) {
                Standing.CLOSED -> return Standing.CLOSED
                Standing.UNCHECKED -> unchecked = true
                Standing.OPEN -> Unit
            }
        }
        return if (unchecked) Standing.UNCHECKED else Standing.OPEN
    }

    /**
     * How one stop a route boards or gets off at stands by its checks at [now], judged where the
     * rider really is: the stop its ride is placed on ([used]) by its line's route. A bus stop in a
     * pair ([End.area], a road's two poles) is the pole its bus uses, which the Planner can name the
     * wrong side of; a bus station's stand can be another of the same name. Until the ride is placed
     * (null) it's never vouched for, whichever poles were looked up and checked, since TfL's list of
     * a pair's poles can miss the one the bus uses. The trip's ranking and a leg's line page both
     * judge a stop this way, so they never disagree.
     *
     * CLOSED when the stop has a notice in force that says it's closed ([ClosedNotice]): a moved stop
     * or a lift out leaves it open, as only the notice's own words can close it. UNCHECKED when the
     * ride isn't placed, or the stop has no check of its own in [closures] (whichever stop the trip
     * asked about: a pole of a pair, a stand a bus was placed on, or one a route rewritten to its
     * placement names as if it were the Planner's), or [unknown] names it. Else OPEN.
     */
    /**
     * Whether a line's leg boards and gets off at stops checked open, each by a check of its own
     * ([judge]): another line shown on a ride uses its own poles, so one from a closed stop, or one
     * not yet checked, isn't passed off as a way to go ([RideLines.checked]).
     */
    fun opens(closures: Map<String, List<StopDisruption>>, unknown: Set<String>, now: Instant): (TripLeg) -> Boolean = { leg ->
        listOf(leg.fromId, leg.toId).all { judge(End(it, lineId = leg.lineId), closures, unknown, now) == Standing.OPEN }
    }

    fun judge(
        end: End,
        closures: Map<String, List<StopDisruption>>,
        unknown: Set<String>,
        now: Instant,
        used: String? = end.id,
    ): Standing {
        val at = used ?: return Standing.UNCHECKED
        if (closedAt(closures[at].orEmpty(), now)) return Standing.CLOSED
        return if (at !in unknown && at in closures) Standing.OPEN else Standing.UNCHECKED
    }

    /**
     * The stops whose checks bear on [end]: the stop its ride is placed on ([used]), or, until it's
     * placed, the stop the Planner named and, for a pair, every pole of it ([poles]), any of which
     * the ride may turn out to use. A check of any of these that failed is this stop's; a failure
     * anywhere else, the other pole of a placed bus's pair included, isn't.
     */
    fun reads(end: End, poles: Map<String, List<String>> = emptyMap(), used: String? = end.id): List<String> =
        if (used != null) listOf(used) else listOf(end.area, end.id).filter { it.isNotEmpty() } + poles[end.area].orEmpty()

    /** Whether any of a stop's [notices] in force at [now] says it's closed. */
    fun closedAt(notices: List<StopDisruption>, now: Instant): Boolean =
        notices.any { it.isActiveAt(now) && ClosedNotice.saysClosed(normalizeDisruptionText(it.description)) }
}
