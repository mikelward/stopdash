package app.stopdash.domain

import java.time.Instant

/**
 * "Route disruption" for a trip on the way (SPEC *On the way*; maintainer, 2026-09-29): what's known
 * that may stop a leg the rider hasn't finished, so they don't wait for a train that won't come.
 *
 * A **coming leg** is the one the rider is on and every one after it. A ride's line counts until they
 * get off it, so the one being ridden counts too; its boarding stop until they board; where it gets
 * off until they do. A walk's end counts where no ride meets it (two stations a change walks between,
 * or the stop the route ends at), as the trip screen's closure checks go ([TripClosures.ends]).
 *
 * Named for what's known, never a verdict that the route is dead. Only what *Disruptions* would warn
 * of on the trip right now is a signal, by the same rules, so the alert and the screen never
 * disagree: planned work only from its day ([LineStatus.asOf]); a stop's notice only in its window
 * ([StopDisruption.isActiveAt]); a line's alert only for the direction the leg's trains go, where
 * that's known ([LineStatus.forDirection]), one with no direction known counting both ways; and
 * nothing the rider has dismissed, which comes back as it does there (escalated, reworded, or
 * planned work starting).
 *
 * Only a fresh answer is evidence: the caller passes the lines and stops whose checks succeeded and
 * are current, so a failed or stale one is unknown, never a signal, and a TfL outage can't read as a
 * line suspended.
 */
object RouteDisruption {
    /** How sure a signal is that a coming leg may not run as planned (tiers agreed 2026-09-29). */
    enum class Tier { MEDIUM, HIGH }

    /** One thing known that may stop the coming leg [legIndex]. */
    sealed interface Signal {
        val legIndex: Int
        val tier: Tier

        /**
         * Its identity: the leg and the alert as fully as a dismissal knows it ([DismissedAlert]), so it's
         * heard once, and again once anything a dismissal would come back for changes (an escalation, a
         * new reason, a new window), as on the trip's screen.
         */
        val key: String

        /** The line [lineId] (named [lineName]) the leg rides has an alert: [status], as shown on the trip. */
        data class Line(
            override val legIndex: Int,
            val lineId: String,
            val lineName: String,
            val status: LineStatus,
            override val tier: Tier,
        ) : Signal {
            override val key: String get() = DismissedAlert.ofLineStatus(status).let { "line/$legIndex/${it.alertKey}/${it.contentSignature}" }
        }

        /**
         * The stop [stopId] (named [stopName]) the leg still has to reach is [closed], else moved, by the
         * notices its closure card shows, whose dismissal identity is [notice] ([DismissedAlert.ofStopClosure]).
         */
        data class Stop(
            override val legIndex: Int,
            val stopId: String,
            val stopName: String,
            val closed: Boolean,
            val notice: DismissedAlert = DismissedAlert(stopId, ""),
        ) : Signal {
            override val tier: Tier get() = Tier.HIGH
            override val key: String get() = "stop/$legIndex/$stopId/${notice.alertKey}/${notice.contentSignature}"
        }
    }

    /**
     * What's known ([signals], worst first), and until when it stands ([until]): no later than its
     * evidence goes stale, null when nothing is known.
     */
    data class Found(val signals: List<Signal>, val until: Instant?) {
        companion object {
            val NONE = Found(emptyList(), null)
        }
    }

    /**
     * Where a stop sits, for matching a dismissal of its notice as the trip's closure card does
     * ([stopPlaceKey]): its stop [area] and interchange [hub] ("HUB…"), blank where not known.
     */
    data class StopPlace(val area: String = "", val hub: String = "")

    /**
     * TfL `statusSeverity` values that are a signal, and how sure: a line not running at all is
     * [Tier.HIGH] (as a trip's ranking counts it, [TripTiming.NOT_RUNNING_SEVERITIES]); severe delays,
     * a part suspension or a part closure is [Tier.MEDIUM]. A part closure would be High where TfL
     * places it on the leg's own stretch, but the affected stops aren't kept, so every one is Medium.
     * Anything else (minor delays, a reduced service) never alerts.
     */
    fun tierOf(severity: Int): Tier? = when (severity) {
        in TripTiming.NOT_RUNNING_SEVERITIES -> Tier.HIGH
        PART_SUSPENDED, PART_CLOSURE, SEVERE_DELAYS, PART_CLOSED -> Tier.MEDIUM
        else -> null
    }

    /** The coming legs' lines: the ones whose status the alert needs, in route order. */
    fun comingLines(trip: ActiveTrip): List<String> =
        comingRides(trip).map { it.value.lineId }.filter { it.isNotBlank() }.distinct()

    /**
     * The stops the trip still has to reach, each with the coming leg that reaches it first: a coming
     * ride's two ends (less where the rider has already boarded the one they're on), and a coming
     * walk's end where no ride meets it. Mirrors [TripClosures.ends], over what's left.
     */
    fun comingStops(trip: ActiveTrip, progress: TripProgress?): List<IndexedValue<TripClosures.End>> {
        if (progress == TripProgress.Arrived) return emptyList()
        val legs = trip.route.legs
        // On board the leg they're on, by the trip's word or the progress's: a boarded train that's
        // lost ([TripProgress.Lost]) has still left the stop behind.
        val boarded = progress is TripProgress.Riding || trip.boarded || trip.onBoardSeen
        val all = (trip.legIndex until legs.size).flatMap { i ->
            val leg = legs[i]
            if (!leg.isWalk) {
                listOfNotNull(
                    IndexedValue(i, TripClosures.End(leg.fromId, leg.fromArea, leg.lineId)).takeUnless { i == trip.legIndex && boarded },
                    IndexedValue(i, TripClosures.End(leg.toId, leg.toArea, leg.lineId)),
                )
            } else {
                listOfNotNull(
                    // A walk's start is where the rider already is, once they're on it.
                    IndexedValue(i, TripClosures.End(leg.fromId, leg.fromArea)).takeIf { i > trip.legIndex && legs[i - 1].isWalk },
                    IndexedValue(i, TripClosures.End(leg.toId, leg.toArea)).takeIf { legs.getOrNull(i + 1)?.isWalk != false },
                )
            }
        }.filter { it.value.id.isNotBlank() }
        val ridden = all.filter { it.value.lineId.isNotEmpty() }.mapTo(HashSet()) { it.value.id }
        return all.filterNot { it.value.lineId.isEmpty() && it.value.id in ridden }.distinctBy { it.value.id to it.value.lineId }
    }

    /**
     * What's known at [now] that may stop [trip]'s coming legs, worst first, then in route order.
     *
     * [statuses] are the coming lines' statuses from a check that succeeded and is current, a line
     * whose check failed left out; [directions] the direction (TfL's `inbound`/`outbound`) a coming
     * leg's trains are seen going, by leg, where one is known. [closures] are the coming stops'
     * notices, likewise only from current checks; [places] where each sits ([StopPlace]), for the
     * dismissals in [dismissed].
     */
    fun signals(
        trip: ActiveTrip,
        progress: TripProgress?,
        statuses: Map<String, LineStatus>,
        directions: Map<Int, String>,
        closures: Map<String, List<StopDisruption>>,
        places: Map<String, StopPlace>,
        dismissed: Set<DismissedAlert>,
        now: Instant,
    ): List<Signal> {
        if (progress == TripProgress.Arrived) return emptyList()
        val shown = LineStatus.asOf(statuses, now)
        val lines = comingRides(trip).mapNotNull { (i, leg) ->
            val status = shown[leg.lineId] ?: return@mapNotNull null
            val scoped = directions[i]?.let(status::forDirection) ?: status
            val left = scoped.remainingAfter(dismissed)?.takeIf { it.disrupted } ?: return@mapNotNull null
            val tier = tierOf(left.severity) ?: return@mapNotNull null
            Signal.Line(i, leg.lineId, leg.lineName, left, tier)
        }
        return (lines + stopSignals(trip, progress, closures, places, dismissed, now))
            .sortedWith(compareByDescending<Signal> { it.tier }.thenBy { it.legIndex })
    }

    // Each coming stop with a notice in force that the rider hasn't dismissed and that says it's
    // closed ([ClosedNotice]) or moved ([MovedNotice]): the closure card the trip shows, as the trip
    // builds it, so a dismissal there holds here.
    private fun stopSignals(
        trip: ActiveTrip,
        progress: TripProgress?,
        closures: Map<String, List<StopDisruption>>,
        places: Map<String, StopPlace>,
        dismissed: Set<DismissedAlert>,
        now: Instant,
    ): List<Signal.Stop> {
        val legOf = comingStops(trip, progress).reversed().associate { it.value.id to it.index }
        return DepartureRows.withoutDismissed(closureCards(trip, progress, closures, places, now), dismissed).mapNotNull { row ->
            // Judged by the card's own notices: a stop's card carries every notice in force there, joined.
            val notices = row.stopDisruption.orEmpty()
            val closed = ClosedNotice.saysClosed(notices)
            if (!closed && !MovedNotice.saysMoved(notices)) return@mapNotNull null
            Signal.Stop(legOf[row.stopId] ?: return@mapNotNull null, row.stopId, row.stopName, closed, DismissedAlert.ofStopClosure(row))
        }
    }

    /**
     * The closure cards the trip's screen shows for the stops [trip] still has to reach ([comingStops]),
     * from their notices in [closures], placed by [places] as the screen places them ([stopPlaceKey]), and
     * folded as it folds them: one card a stop, carrying every notice in force there; one a place for a
     * notice two of its stops share. Before dismissals: what a check found live, to settle them by.
     */
    fun closureCards(
        trip: ActiveTrip,
        progress: TripProgress?,
        closures: Map<String, List<StopDisruption>>,
        places: Map<String, StopPlace>,
        now: Instant,
    ): List<DepartureRow> {
        val coming = comingStops(trip, progress).distinctBy { it.value.id }
        if (coming.isEmpty()) return emptyList()
        val names = stopNames(trip)
        val rows = coming.flatMap { (_, end) ->
            val notices = closures[end.id]?.takeIf { it.isNotEmpty() } ?: return@flatMap emptyList()
            val place = places[end.id] ?: StopPlace(area = end.area)
            val stop = StopArrivals(
                end.id, names[end.id].orEmpty(), emptyList(), SteadyClock.stamp(now), disruptions = notices,
                clusterId = place.area, hubId = place.hub,
            )
            DepartureRows.across(listOf(stop), now).filter { it.stopDisruption != null }
        }
        return DepartureRows.stopStatusFolded(rows)
    }

    /** Where the stop [stopId] of [trip] sits, as a dismissal of its notice is keyed ([stopPlaceKey]). */
    fun placeKey(trip: ActiveTrip, stopId: String, place: StopPlace?): String =
        stopPlaceKey(place?.hub.orEmpty(), place?.area.orEmpty(), stopNames(trip)[stopId].orEmpty(), stopId)

    private fun stopNames(trip: ActiveTrip): Map<String, String> =
        trip.route.legs.flatMap { listOf(it.fromId to it.fromName, it.toId to it.toName) }.toMap()

    // The coming rides, by leg index: the one the rider is on (waiting for it, or on it) and every one after.
    private fun comingRides(trip: ActiveTrip): List<IndexedValue<TripLeg>> =
        trip.route.legs.withIndex().filter { (i, leg) -> i >= trip.legIndex && !leg.isWalk }

    // TfL's `statusSeverity` names, for [tierOf].
    private const val PART_SUSPENDED = 3
    private const val PART_CLOSURE = 5
    private const val SEVERE_DELAYS = 6
    private const val PART_CLOSED = 11
}

/**
 * Whether a stop notice says the stop itself has moved ("Stop moved", "Stop B moved to Pancras
 * Road"), as [ClosedNotice] reads a closure: only the notice's own wording counts, so a lift or an
 * entrance notice never reads as the stop gone elsewhere.
 */
object MovedNotice {
    private val SAYS_MOVED = Regex(
        """\b(stop|station)(\s+[a-z0-9]{1,2})?\s+(has\s+(been\s+)?|is\s+|will\s+be\s+)?(temporarily\s+)?(moved|relocated)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun saysMoved(text: String): Boolean = SAYS_MOVED.containsMatchIn(text)
}
