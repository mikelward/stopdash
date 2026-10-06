package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
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
 * line suspended. Likewise a change's board ([unpredicted]): only one read in the refresh that asks.
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

        /**
         * What Keep going lets go of ([ActiveTrip.disruptionsDismissed]): the leg and the alert as its
         * Dismiss identifies it ([DismissedAlert]), so it stays let go of whatever is learned of it later
         * (placed on the leg, say), where [key] would be heard again (Codex on #519).
         */
        val dismissKey: String get() = key

        /**
         * The line [lineId] (named [lineName]) the leg rides has an alert: [status], as shown on the trip,
         * or, [placed], a part closure TfL places on the leg's own stretch, which raised it to [tier]
         * ([lineSignal]).
         */
        data class Line(
            override val legIndex: Int,
            val lineId: String,
            val lineName: String,
            val status: LineStatus,
            override val tier: Tier,
            val placed: Boolean = false,
        ) : Signal {
            // Placing it is an escalation the rider hasn't heard: the first check after a new alert,
            // before its detail lands, finds it unplaced, so a placed one is a key of its own and is heard
            // again when the next check places it (Codex, PR #446).
            override val key: String get() = dismissKey + if (placed) "/placed" else ""

            override val dismissKey: String get() = lineDismissKey(legIndex, status)
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

        /**
         * No train of the line [lineId] (named [lineName]) that may take the leg is predicted at its
         * boarding stop [stopId] (named [stopName]), a change the rider is a few minutes from
         * ([changeNear], [unpredicted]). Heard once for the leg, however often trains come and go there.
         */
        data class Unpredicted(
            override val legIndex: Int,
            val lineId: String,
            val lineName: String,
            val stopId: String,
            val stopName: String,
        ) : Signal {
            override val tier: Tier get() = Tier.MEDIUM
            override val key: String get() = "unpredicted/$legIndex/$lineId/$stopId"
        }
    }

    /**
     * What's known ([signals], worst first), and until when it stands ([until]): no later than its
     * evidence goes stale, null when nothing is known.
     */
    data class Found(
        val signals: List<Signal>,
        val until: Instant?,
        // How long each signal's own evidence stands, by its key ([Signal.key]), where known: what's
        // left once some are let go of stands as long as its own do, not as long as one let go of
        // (Codex on #519). One not listed stands as long as the whole ([until]).
        val stands: Map<String, Instant> = emptyMap(),
        // The coming stations' other notices ([stationNotes]): shown on the trip's screen, never alerted.
        val notes: List<StationNote> = emptyList(),
        // How long [notes] stand: as long as the first of them ([StationNote.until]), as [until] does for
        // the signals, so the screen only reads it; each refresh finds the rest again.
        val notesUntil: Instant? = null,
    ) {
        /** What's known with [signal] too, which stands no later than [stands]: so neither does the whole. */
        @WorkerThread
        fun with(signal: Signal, stands: Instant): Found =
            copy(signals = ordered(signals + signal), until = until?.let { minOf(it, stands) } ?: stands, stands = this.stands + (signal.key to stands))

        /** How long [signal] (one of [signals]) stands: its own time ([stands]), else the whole's ([until]). */
        fun standsUntil(signal: Signal): Instant? = stands[signal.key] ?: until

        companion object {
            val NONE = Found(emptyList(), null)
        }
    }

    /**
     * [signals] as the trip's screen shows them: each thing known once, as the alert has it (its own
     * words, [Signal.Line.status]'s or a stop's or a change's), so two legs on one line with the same
     * words read as one card. A line's alert is told apart by its dismissal identity ([DismissedAlert.ofLineStatus]:
     * severity, label and prose), so Keep going never lets go of one no card showed (Codex on #519).
     * Worked out with what's known, never in composition (Codex on #519).
     */
    @WorkerThread
    fun cards(signals: List<Signal>): List<Signal> = signals.distinctBy { signal ->
        when (signal) {
            is Signal.Line -> DismissedAlert.ofLineStatus(signal.status)
            is Signal.Stop -> Pair(signal.stopId, signal.closed)
            is Signal.Unpredicted -> Pair(signal.lineId, signal.stopId)
        }
    }

    /** [signals] worst first, then in route order, as they're said. */
    fun ordered(signals: List<Signal>): List<Signal> =
        signals.sortedWith(compareByDescending<Signal> { it.tier }.thenBy { it.legIndex })

    /**
     * Where a stop sits, for matching a dismissal of its notice as the trip's closure card does
     * ([stopPlaceKey]): its stop [area] and interchange [hub] ("HUB…"), blank where not known; and the
     * interchange's [hubName] and member-station [aliases] ([HubInfo]), so a notice is titled by the
     * interchange and leads with none of its names ([cleanDisruptionBody]), empty where not resolved.
     */
    data class StopPlace(val area: String = "", val hub: String = "", val hubName: String = "", val aliases: List<String> = emptyList())

    /**
     * TfL `statusSeverity` values that are a signal, and how sure: a line not running at all is
     * [Tier.HIGH] (as a trip's ranking counts it, [TripTiming.NOT_RUNNING_SEVERITIES]); severe delays,
     * a part suspension or a part closure is [Tier.MEDIUM], a part one raised to High where TfL places
     * it on the leg's own stretch ([lineSignal]). Anything else (minor delays, a reduced service) never
     * alerts.
     */
    fun tierOf(severity: Int): Tier? = when (severity) {
        in TripTiming.NOT_RUNNING_SEVERITIES -> Tier.HIGH
        in PART_SEVERITIES, SEVERE_DELAYS -> Tier.MEDIUM
        else -> null
    }

    /**
     * What the line alert [status] (shown for the coming leg [index], [leg]) is a signal of, if
     * anything: [status] at its own [tierOf], unless a part closure or suspension TfL places on the
     * leg's own stretch ([LineStatus.closureOn], from where it boards through where it gets off) would
     * say more. Then that closure is the signal, [Tier.HIGH] and named for what it is, even behind an
     * alert TfL ranks above it that never alerts (minor delays) or is only Medium (severe delays):
     * [Signal.Line.placed] only so, when placing it raised the tier. Shown already at High, the alert
     * stands as it is, and isn't heard again for being placed (Codex, PR #446). One whose stretch
     * isn't known, or lies elsewhere on the line, stays Medium: TfL's section may not be all a closure
     * touches, so it's never dropped, even behind an alert that never alerts, which it then stands in
     * for, named for itself (Codex, PR #446). A closure the rider dismissed (as the alert shown)
     * doesn't come back for being placed: its text already names its stretch.
     */
    @WorkerThread
    fun lineSignal(index: Int, leg: TripLeg, status: LineStatus, dismissed: Set<DismissedAlert>): Signal.Line? {
        // Its shown alert dismissed, the worst other alert under way the rider hasn't stands in for it,
        // rather than the line going quiet (Codex on #519).
        val shown = (status.remainingAfter(dismissed) ?: underWayOf(status).filter { it.disrupted && DismissedAlert.ofLineStatus(it) !in dismissed }.minByOrNull { it.severity })
            ?.takeIf { it.disrupted }
        val tier = shown?.let { tierOf(it.severity) }
        if (shown != null && tier == Tier.HIGH) return Signal.Line(index, leg.lineId, leg.lineName, shown, tier)
        // The closures under way the rider hasn't dismissed, named for themselves.
        fun undismissed(closure: PartClosure) = status.naming(closure).takeIf { DismissedAlert.ofLineStatus(it) !in dismissed }
        status.closures.filter { it.coversRide(rideCalls(leg)) }.sortedBy { it.severity }.firstNotNullOfOrNull(::undismissed)
            ?.let { return Signal.Line(index, leg.lineId, leg.lineName, it, Tier.HIGH, placed = true) }
        if (shown != null && tier != null) return Signal.Line(index, leg.lineId, leg.lineName, shown, tier)
        return status.closures.sortedBy { it.severity }.firstNotNullOfOrNull(::undismissed)
            ?.let { Signal.Line(index, leg.lineId, leg.lineName, it, Tier.MEDIUM) }
    }

    /**
     * [dismissed], and what of [status] the rider let go of on [trip]'s leg [index] with Keep going
     * ([ActiveTrip.disruptionsDismissed]): weighed as [lineSignal] picks, so the line's next alert stands
     * in for one let go of, as it does for a dismissal on the list, rather than the line going quiet
     * for the rest of the trip (Codex on #519).
     */
    private fun dismissedOn(trip: ActiveTrip, index: Int, status: LineStatus, dismissed: Set<DismissedAlert>): Set<DismissedAlert> {
        if (trip.disruptionsDismissed.isEmpty()) return dismissed
        val candidates = listOf(status) + status.byDirection.values + status.closures.map(status::naming) + underWayOf(status)
        val letGo = candidates.filter { lineDismissKey(index, it) in trip.disruptionsDismissed }.map(DismissedAlert::ofLineStatus)
        return if (letGo.isEmpty()) dismissed else dismissed + letGo
    }

    // Each alert under way on [status]'s line ([LineStatus.underWay]) as a status of its own.
    private fun underWayOf(status: LineStatus): List<LineStatus> =
        status.underWay.map { LineStatus(status.lineId, it.severity, it.description, it.fullText, soleAlert = true) }

    // [Signal.Line.dismissKey]: the leg and the alert as a dismissal knows it.
    private fun lineDismissKey(legIndex: Int, status: LineStatus): String =
        DismissedAlert.ofLineStatus(status).let { "line/$legIndex/${it.alertKey}/${it.contentSignature}" }

    /**
     * Whether a bus line's alert ([status], its own words) puts it off [leg]'s ride, on its line's routes
     * ([sequence]) (maintainer, 2026-10-01): TfL gives a bus diversion's stretch only as prose
     * ("not serving stops between Bank Station and Moorgate Station"), and an alert at the far end of
     * a long route says nothing of the rider's part of it. Off only where every way a route of the line
     * runs the ride (its boarding stop, then where it gets off) has a stretch the alert gives
     * between two of its stops ([AlertStops.stretched]) and the ride calls at none of it. A stop
     * merely named isn't enough: it may be an aside (Codex, PR #455). With several alerts under way
     * ([LineStatus.underWay]), each must be off the ride on its own words (maintainer, 2026-10-03: a
     * route with five, each about the far end, flagged a ride none of them reached). Unknown is on: no
     * text, no route, a ride no route runs, an alert that gives no stretch on a route, or several under
     * way whose words weren't all kept.
     * Buses only: a tube or rail line's delays spread along it, so naming a station elsewhere doesn't
     * keep them off the ride, and its part closures are placed by TfL itself ([lineSignal]).
     */
    @WorkerThread
    fun offRide(leg: TripLeg, status: LineStatus, sequence: LineSequence?): Boolean {
        if (!leg.mode.equals(BUS, ignoreCase = true) || sequence == null) return false
        // Every way a route could run the ride, its ends matched as bus placement matches them
        // ([ridesOf]): the stop, its pair, or a stand of the same name (Codex, PR #455).
        val rides = ridesOf(leg, sequence)
        return alertsOf(status).let { alerts -> alerts.isNotEmpty() && alerts.all { (alert, directions) -> alertOffRide(alert, directions, rides, sequence) } }
    }

    /**
     * The alerts under way on a bus line of several ([LineStatus.underWay]) that may reach [leg]'s ride,
     * each as a status of its own, on its own words: what's left once each one [offRide] would leave out
     * is. Each is its own signal, dismissed on its own (maintainer, 2026-10-03: Keep going lets go of the
     * one seen, and the others still show). With no route to place them on, every one may. Null where
     * they can't be told apart ([splits]).
     */
    @WorkerThread
    fun alertsOnRide(leg: TripLeg, status: LineStatus, sequence: LineSequence?): List<LineStatus>? {
        if (!splits(leg, status)) return null
        if (sequence == null) return alertsOf(status).map { it.first }
        val rides = ridesOf(leg, sequence)
        return alertsOf(status).filterNot { (alert, directions) -> alertOffRide(alert, directions, rides, sequence) }.map { it.first }
    }

    // Whether [alert] (one of a line's alerts, scoped to [directions] where known) is off every one of
    // [rides]. An alert TfL scopes to the other way is off every route that runs the ride this way,
    // whatever its words say (maintainer, 2026-10-03; Codex on #519): with no route left, it's off the
    // ride. A route of no stated way counts. Otherwise only one about part of the route ([scopable]) can
    // be, by the stretch its words give.
    private fun alertOffRide(alert: LineStatus, directions: Set<String>?, rides: List<Pair<LineRoute, List<String>>>, sequence: LineSequence): Boolean {
        val theirs = directions?.let { ways -> rides.filter { (route, _) -> route.direction.isBlank() || ways.any { it.equals(route.direction, ignoreCase = true) } } } ?: rides
        if (rides.isNotEmpty() && theirs.isEmpty()) return true
        return scopable(alert) && alert.fullText?.let { clearOf(it, sequence, theirs) } == true
    }

    // Whether [alert]'s route can change what's said of it: its words may place it ([scopable]), or TfL
    // scoped it to one way, which a route's ways can rule out.
    private fun weighable(alert: LineStatus, directions: Set<String>?): Boolean = scopable(alert) || directions != null

    /**
     * Whether [leg]'s line's alerts under way ([status]) are each a signal of their own ([alertsOnRide]):
     * a bus line with several whose words were kept.
     */
    fun splits(leg: TripLeg, status: LineStatus): Boolean =
        leg.mode.equals(BUS, ignoreCase = true) && !status.soleAlert && status.underWay.size > 1

    /** Whether any alert under way on [status]'s line could be placed on a ride ([scopable]): its route is worth reading. */
    @WorkerThread
    fun placeable(status: LineStatus): Boolean = alertsOf(status).any { (alert, directions) -> weighable(alert, directions) }

    /**
     * Whether every alert under way on [status]'s line could be left out of a ride it doesn't reach
     * ([offRide]), each on its own words ([scopable]): the line's sole alert, or each of several TfL's
     * answer kept ([LineStatus.underWay]). None known is never.
     */
    @WorkerThread
    fun scopableOnRide(status: LineStatus): Boolean = alertsOf(status).let { alerts -> alerts.isNotEmpty() && alerts.all { scopable(it.first) } }

    // Each alert under way on [status]'s line as a status of its own, the line's sole alert, with the
    // directions it affects where known ([LineAlert.directions]): [status] itself where it's the one, each
    // of [LineStatus.underWay] where several are, none where their words weren't kept.
    private fun alertsOf(status: LineStatus): List<Pair<LineStatus, Set<String>?>> = when {
        status.soleAlert -> listOf(status to status.underWay.singleOrNull()?.directions?.takeIf { it.isNotEmpty() })
        status.underWay.size > 1 -> status.underWay.map {
            LineStatus(status.lineId, it.severity, it.description, it.fullText, soleAlert = true) to it.directions?.takeIf { ways -> ways.isNotEmpty() }
        }
        else -> emptyList()
    }

    /**
     * Whether a bus line's alert ([status], its own words) lies wholly behind the stop [stopId], on its
     * line's routes ([sequence]): a diversion a bus from the stop has already left behind, which
     * shouldn't flag the stop's departures (maintainer, 2026-10-02). What [offRide] asks of a ride, for
     * a rider whose ride isn't known: each route through the stop, from there to its end, stands in for
     * it, as a bus from the stop may take them anywhere along it. So unknown is ahead, as there: a stop no
     * route calls at, a route the alert gives no stretch on, or one whose stretch is at the stop or after
     * it. The caller says it's a bus; a tube or rail line's delays spread along it. Only routes going
     * [direction] (TfL's `inbound`/`outbound`, blank for either) count, as [status] is that way's: a
     * stop both ways call at would otherwise read the stretch the other way round (Codex, PR #469).
     */
    @WorkerThread
    fun behind(stopId: String, status: LineStatus, sequence: LineSequence, direction: String = ""): Boolean {
        if (!scopable(status)) return false
        val text = status.fullText ?: return false
        val routes = sequence.routes.filter { direction.isBlank() || it.direction.isBlank() || it.direction.equals(direction, ignoreCase = true) }
        // Every time a route calls at the stop (a loop may call twice), and everywhere it goes after.
        val rides = routes.flatMap { route ->
            route.stopIds.indices.filter { route.stopIds[it] == stopId }.map { route to route.stopIds.subList(it, route.stopIds.size) }
        }
        return clearOf(text, sequence, rides)
    }

    // Whether [text] keeps clear of every one of [rides] (a route of [sequence], and the stops of it the
    // rider may call at): it gives a stretch on the route ([AlertStops.stretched]) and the ride calls at
    // none of it. No rides is unknown, so not clear.
    private fun clearOf(text: String, sequence: LineSequence, rides: List<Pair<LineRoute, List<String>>>): Boolean =
        rides.isNotEmpty() && rides.all { (route, ride) ->
            val stops = route.stopIds.map { RouteStop(it, sequence.stopNames[it].orEmpty()) }
            val affected = AlertStops.stretched(text, stops)
            affected.isNotEmpty() && ride.none { it in affected }
        }

    /**
     * Whether [status], as an alert, could be left out of a ride it doesn't reach ([offRide]): its
     * line's sole alert, about part of the route, and saying nothing of the whole. Asked before a route
     * is fetched, so one that can only stay on costs none (Codex, PR #455).
     */
    fun scopable(status: LineStatus): Boolean {
        // Only the line's sole alert: with others under way their words are lost, and may reach the ride.
        if (!status.soleAlert) return false
        val text = status.fullText
        if (text.isNullOrBlank()) return false
        // One alert can bundle an effect along the whole route with a stretch ("Severe delays throughout
        // the route. Buses are not serving stops between …"): the stretch isn't all of it (Codex, PR #455).
        if (LINE_WIDE.containsMatchIn(text) || LINE_WIDE.containsMatchIn(status.description)) return false
        // Nor any sentence saying the route isn't running, however it's worded ("no route 99 buses are
        // operating", "buses aren't expected to run"): a negation anywhere in it with running, operating
        // or being in service (Codex, PR #455).
        if (text.split('.', ';', ':', '!', '?', '\n').any { NOT_IN_SERVICE_NEGATION.containsMatchIn(it) && NOT_IN_SERVICE_VERB.containsMatchIn(it) }) return false
        // Only a status that is itself about part of the route: a suspension, closure, delays or
        // anything else is the line's as a whole, whatever stretch its words also give. A list of what
        // may be left out rather than of what may not, so a label not thought of keeps the alert
        // (Codex, PR #455).
        return status.description.trim().lowercase() in STRETCH_LABELS
    }

    /**
     * Where [leg] calls, in order: where it boards, its path, and where it gets off. The path runs
     * through the end, but may leave it out (as [TripRoute.passedAt] allows), so the end is added
     * unless the path already ends with it: without it, the last stretch would never be placed (Codex,
     * PR #446). A loop's path can pass the end earlier on, so it's the path's last call that counts. None for a leg the Planner gave no path for: its two ends alone don't say which way
     * it runs between them (a branch, a loop), so nothing is placed on it (Codex, PR #446).
     */
    fun rideCalls(leg: TripLeg): List<String> =
        if (leg.path.isEmpty()) emptyList()
        else (listOf(leg.fromId) + leg.path + listOf(leg.toId).filterNot { it == leg.path.last() }).filter { it.isNotBlank() }

    /** The coming legs' lines ([rideLine]): the ones whose status the alert needs, in route order. */
    fun comingLines(trip: ActiveTrip): List<String> =
        comingRides(trip).map { (i, leg) -> rideLine(trip, i, leg).id }.filter { it.isNotBlank() }.distinct()

    /**
     * The line coming leg [index] ([leg]) of [trip] goes by, as its id and name: the line of the train
     * the trip follows on the leg it's on, which can be another of the ride's lines than the Planner's
     * ([OnTheWay.followedLine]), else the leg's own. A Circle train taken along the Hammersmith &
     * City's stretch is checked as the Circle, not as a line the rider isn't on (Codex, PR #451).
     */
    fun rideLine(trip: ActiveTrip, index: Int, leg: TripLeg): LineRef =
        rideAt(trip, index, leg).let { LineRef(it.lineId, it.lineName, it.mode) }

    /**
     * Coming leg [index] ([leg]) of [trip] as the rider takes it: on the leg they're on, taking another
     * of the ride's lines ([OnTheWay.ridingOn]: its train followed, or on board on it by where they were
     * seen), the ride as that line runs it, its own stops at either end (a bus's other pole of the pair,
     * say); else the leg itself.
     */
    fun rideAt(trip: ActiveTrip, index: Int, leg: TripLeg): TripLeg =
        if (index == trip.legIndex) OnTheWay.ridingOn(trip) ?: leg else leg

    /**
     * The stops the trip still has to reach, each with the coming leg that reaches it first: a coming
     * ride's two ends (less where the rider has already boarded the one they're on), and a coming
     * walk's end where no ride meets it. Mirrors [TripClosures.ends], over what's left. The ride the
     * rider is on is taken as they take it ([rideAt]): another line's train followed there gets off at
     * its own stop, and a closure there is checked even after the cards' checks are behind them
     * (Codex, PR #451).
     */
    fun comingStops(trip: ActiveTrip, progress: TripProgress?): List<IndexedValue<TripClosures.End>> {
        if (progress == TripProgress.Arrived) return emptyList()
        val legs = trip.route.legs
        // On board the leg they're on, by the trip's word or the progress's: a boarded train that's
        // lost ([TripProgress.Lost]) has still left the stop behind.
        val boarded = progress is TripProgress.Riding || trip.boarded || trip.onBoardSeen
        val all = (trip.legIndex until legs.size).flatMap { i ->
            val leg = rideAt(trip, i, legs[i])
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
    /**
     * The coming bus lines whose routes [signals] would read to place their alert ([offRide]): a ride
     * whose line, as [signals] reads it the same day, direction and dismissals, gives a signal TfL
     * hasn't placed and that could be left out ([scopable]). The same per-ride path as [signals], so
     * a route is fetched only where it can change the answer — never for an alert dismissed, placed,
     * route-wide or not sounding at all (Codex, PR #455).
     */
    @WorkerThread
    fun routesWanted(
        trip: ActiveTrip,
        statuses: Map<String, LineStatus>,
        directions: Map<Int, String>,
        dismissed: Set<DismissedAlert>,
        now: Instant,
    ): Set<String> {
        val shown = LineStatus.asOf(statuses, now)
        return comingRides(trip).mapNotNullTo(LinkedHashSet()) { (i, leg) ->
            val ride = rideAt(trip, i, leg)
            if (!ride.mode.equals(BUS, ignoreCase = true)) return@mapNotNullTo null
            val status = shown[ride.lineId] ?: return@mapNotNullTo null
            val way = directions[i]?.let(status::forDirection) ?: status
            // Split, each alert is weighed on its own: worth the route while one the rider hasn't
            // dismissed could be placed, whichever shows (Codex on #519).
            if (splits(ride, way)) {
                return@mapNotNullTo ride.lineId.takeIf { alertsOf(way).any { (alert, directions) ->
                    // Only one that would sound: minor delays never alert, wherever they are, nor one the rider
                    // let go of on this trip with Keep going (Codex on #519).
                    val tier = tierOf(alert.severity)
                    tier != null && weighable(alert, directions) && DismissedAlert.ofLineStatus(alert) !in dismissed &&
                        Signal.Line(i, ride.lineId, ride.lineName, alert, tier).dismissKey !in trip.disruptionsDismissed
                } }
            }
            lineSignal(i, ride, way, dismissedOn(trip, i, way, dismissed))
                ?.takeIf { !it.placed && placeable(it.status) }?.let { ride.lineId }
        }
    }

    @WorkerThread
    fun signals(
        trip: ActiveTrip,
        progress: TripProgress?,
        statuses: Map<String, LineStatus>,
        directions: Map<Int, String>,
        closures: Map<String, List<StopDisruption>>,
        places: Map<String, StopPlace>,
        dismissed: Set<DismissedAlert>,
        now: Instant,
        // The coming lines' routes, by line, where had: what places a bus alert by the stops it names ([offRide]).
        sequences: Map<String, LineSequence> = emptyMap(),
        // Told, by its leg, of each line alert left out for being off the ride, for the log: once an alert.
        leftOff: (legIndex: Int) -> Unit = {},
    ): List<Signal> {
        if (progress == TripProgress.Arrived) return emptyList()
        val shown = LineStatus.asOf(statuses, now)
        val lines = comingRides(trip).flatMap { (i, leg) ->
            // The leg as the rider takes it ([rideAt]): another line's train followed is checked, named
            // and placed as that line, on its own stretch (Codex, PR #451).
            val ride = rideAt(trip, i, leg)
            val status = shown[ride.lineId] ?: return@flatMap emptyList()
            val way = directions[i]?.let(status::forDirection) ?: status
            val sequence = sequences[ride.lineId]
            // Several alerts under way on a bus line: each that may reach the ride is a signal of its own,
            // named and keyed for itself, so dismissing the one seen leaves the others (maintainer,
            // 2026-10-03). Split before any dismissal is weighed: the one shown dismissed on the list
            // doesn't take the rest with it (Codex on #519). One that never alerts (minor delays) is left
            // out, as for any line, unless TfL places it as a part closure on the ride's stretch: then
            // it's High and placed, as [lineSignal] has it (Codex on #519).
            alertsOnRide(ride, way, sequence)?.let { each ->
                // Each alert left out is told, for the log (Codex on #519).
                repeat(alertsOf(way).size - each.size) { leftOff(i) }
                val calls = rideCalls(ride)
                return@flatMap each.filter { DismissedAlert.ofLineStatus(it) !in dismissed }.mapNotNull { alert ->
                    val tier = tierOf(alert.severity)
                    val onRide = tier != Tier.HIGH && way.closures.any { closure ->
                        closure.severity == alert.severity && closure.description == alert.description &&
                            closure.fullText?.ifBlank { null } == alert.fullText?.ifBlank { null } && closure.coversRide(calls)
                    }
                    if (onRide) Signal.Line(i, ride.lineId, ride.lineName, alert, Tier.HIGH, placed = true)
                    else tier?.let { Signal.Line(i, ride.lineId, ride.lineName, alert, it) }
                }
            }
            val signal = lineSignal(i, ride, way, dismissedOn(trip, i, way, dismissed)) ?: return@flatMap emptyList()
            if (signal.placed) return@flatMap listOf(signal)
            if (offRide(ride, signal.status, sequence)) {
                leftOff(i)
                return@flatMap emptyList()
            }
            listOf(signal)
        }
        return ordered(lines + stopSignals(trip, progress, closures, places, dismissed, now))
    }

    /**
     * How soon the rider must be able to board a ride at a change for its board to be read for a train
     * ([changeNear], [unpredicted]): "a few minutes from its boarding stop" (tiers agreed with the
     * maintainer, 2026-09-29). Well inside TfL's predictions (about half an hour ahead), so a line
     * running there has a train predicted by then; a ride further off may be past them (Low, never alerts).
     */
    val NEARS_CHANGE: Duration = Duration.ofMinutes(5)

    /**
     * The ride boarded at a change (one with a ride before it on the route) that [trip]'s rider is next
     * to board, by its leg, while they can board it within [NEARS_CHANGE] of [now], as [progress]
     * stands: waiting for it or changing onto it, walking to it, or on the ride before, due off soon
     * enough with the change's walk and time after. Null when no such ride is that near, the rider is
     * already on it, or when they can board isn't known (the ride before past TfL's predictions).
     */
    fun changeNear(trip: ActiveTrip, progress: TripProgress?, now: Instant): IndexedValue<TripLeg>? {
        val legs = trip.route.legs
        val (index, ready) = when (progress) {
            is TripProgress.Waiting, is TripProgress.Changing, is TripProgress.Lost -> {
                // A train left with them, or their word: they're on it, not waiting for one.
                if (trip.boarded || trip.onBoardSeen) return null
                trip.legIndex to (OnTheWay.readyAt(trip, progress) ?: trip.legStartedAt)
            }
            is TripProgress.Walking -> (trip.legIndex + 1) to progress.until.plus(progress.leg.changeAfter)
            is TripProgress.Riding -> {
                var at = progress.getOffAt?.plus(progress.leg.changeAfter) ?: return null
                var i = trip.legIndex + 1
                // The walk between the two rides, at the Planner's time, and the change time after it.
                legs.getOrNull(i)?.takeIf { it.isWalk }?.let { walk ->
                    at = at.plus(walk.run).plus(walk.changeAfter)
                    i++
                }
                i to at
            }
            else -> return null
        }
        val ride = legs.getOrNull(index)?.takeIf { !it.isWalk } ?: return null
        if ((0 until index).none { !legs[it].isWalk } || ready.isAfter(now.plus(NEARS_CHANGE))) return null
        return IndexedValue(index, ride)
    }

    /**
     * Whether [ride] (leg [legIndex]), boarded at a change, has no train of its lines predicted on its
     * boarding stop's board ([departures], a fresh answer): none listed of one of the ride's [lines], the
     * ones the trip would follow on it ([OnTheWay.candidates]), that the train's own line route
     * ([sequences], by line) doesn't send another way from the ride as its line runs it
     * ([OnTheWay.mayTakeRide]). A train its route can't place, or any without its route, counts as
     * predicted, so a route that can't be had never reads as no train. So does one TfL names no line for
     * (of the ride's mode, or none given), which may be the rider's, as a trip's filters leave it
     * unresolved ([DirectTrips.filter]); and one listed before the rider can get there: that may only be
     * where the predictions end (Low, never alerts).
     */
    fun unpredicted(
        legIndex: Int,
        ride: TripLeg,
        departures: List<Departure>,
        lines: List<TripLeg>,
        sequences: Map<String, LineSequence?>,
    ): Signal.Unpredicted? {
        // A ride with no line named can't be told on the board: unknown, never a signal.
        if (ride.lineId.isBlank()) return null
        val predicted = departures.any { train ->
            val on = OnTheWay.lineOf(lines, train)
                ?: ride.takeIf { train.lineId.isBlank() && (train.mode.isBlank() || train.mode.equals(ride.mode, ignoreCase = true)) }
                ?: return@any false
            OnTheWay.mayTakeRide(on, listOf(train), sequences).isNotEmpty()
        }
        if (predicted) return null
        return Signal.Unpredicted(legIndex, ride.lineId, ride.lineName, ride.fromId, ride.fromName)
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
     * A notice in force at station [stopName] ([stopId]) the trip still reaches on leg [legIndex] that
     * neither closes nor moves it ([text], TfL's words, cleaned): a lift or an escalator out, an exit
     * shut. Worth knowing on the way, never a reason to alert (SPEC *On the way*; maintainer 2026-10-04).
     * It holds [until] the first of its notices ends, each over every window TfL lists it under that
     * runs on from the one in force; null when none ends. [support] is, for each of its notices, the
     * stops that list it in force (an interchange's), each with when its own listing ends (null: open),
     * so a notice stands while any stop's check and listing of it both do, and the note while all its
     * notices do.
     */
    data class StationNote(
        val legIndex: Int,
        val stopId: String,
        val stopName: String,
        val text: String,
        val until: Instant? = null,
        val support: List<Map<String, Instant?>> = listOf(mapOf(stopId to until)),
    ) {
        /**
         * What its × lets go of ([ActiveTrip.disruptionsDismissed]): the station as headed and TfL's words,
         * so it stays gone on this trip until the notice changes. No leg in it: the same notice at the same
         * station is the same, whichever leg reaches it, and a split leg ([OffPlan]) leaves it as it is.
         */
        val dismissKey: String get() = "note/$stopName/$text"
    }

    /**
     * Each coming stop's notices in force ([closureCards]) that the rider hasn't dismissed and that say
     * neither closed nor moved: those are [Signal.Stop]s and alert. In route order.
     */
    @WorkerThread
    fun stationNotes(
        trip: ActiveTrip,
        progress: TripProgress?,
        closures: Map<String, List<StopDisruption>>,
        places: Map<String, StopPlace>,
        dismissed: Set<DismissedAlert>,
        now: Instant,
    ): List<StationNote> {
        if (progress == TripProgress.Arrived) return emptyList()
        val legOf = comingStops(trip, progress).reversed().associate { it.value.id to it.index }
        // The coming stops at each interchange, whose cards fold into one at the first of them.
        val atHub = legOf.keys.groupBy { places[it]?.hub.orEmpty() }
        fun alerts(text: String) = ClosedNotice.saysClosed(text) || MovedNotice.saysMoved(text)
        val cards = DepartureRows.withoutDismissed(closureCards(trip, progress, closures, places, now), dismissed)
            .filter { it.stopId in legOf }.sortedBy { legOf.getValue(it.stopId) }
        // One note a place, never its name, so two places of one name each keep theirs; an interchange's
        // stops whose cards didn't fold (one also closed, say) make one (Codex, #567).
        return cards.groupBy { stopPlaceKey(it) }.values.mapNotNull { same ->
            val row = same.first()
            // Each card's notices judged one by one, not by its joined words, so a lift notice beside a
            // closure is still noted, the closure alerting on its own; and each once, however many windows
            // or stops TfL lists it under (Codex, #567).
            val retained = same.flatMap { card ->
                closures[card.stopId].orEmpty().filter { it.isActiveAt(now) }.map { normalizeDisruptionText(it.description) }
            }.distinct().filter { !alerts(it) }
            if (retained.isEmpty()) return@mapNotNull null
            // The stops it's read across: an interchange's coming ones, folded or not (Codex, #567).
            val members = row.hubId.takeIf { it.isNotEmpty() }?.let { atHub[it] } ?: same.map { it.stopId }.distinct()
            fun listing(text: String) = members.associateWith { id ->
                closures[id].orEmpty().filter { normalizeDisruptionText(it.description) == text }
            }.filterValues { it.isNotEmpty() }
            // Until it ends, over its windows run together across those stops, so a shorter one ending under
            // a longer one doesn't let go of a note still in force (Codex, #567).
            fun endOf(text: String): Instant? {
                val windows = listing(text).values.flatten().map { (it.validFrom ?: Instant.MIN) to (it.validTo ?: Instant.MAX) }
                return DepartureRows.spanAt(windows, now)?.second?.takeIf { it != Instant.MAX }
            }
            // The stops listing it in force, each with when its own listing of it ends: the checks it stands
            // on, each only as long as that stop lists it (Codex, #567).
            fun supportOf(text: String): Map<String, Instant?> = listing(text)
                .filterValues { listed -> listed.any { it.isActiveAt(now) } }
                .mapValues { (_, listed) ->
                    DepartureRows.spanAt(listed.map { (it.validFrom ?: Instant.MIN) to (it.validTo ?: Instant.MAX) }, now)
                        ?.second?.takeIf { it != Instant.MAX }
                }
            // Each cleaned of its place name on its own, so one outage each stop leads with its own name for
            // is said once, under the interchange's heading (Codex, #567).
            val said = retained.groupBy { cleanDisruptionBody(it, stopName = row.stopName, hubName = row.hubName, aliases = row.placeAliases) }
                .filterKeys { it.isNotBlank() }
            if (said.isEmpty()) return@mapNotNull null
            // A notice said stands while any of its wordings does; the note until the first of them ends.
            val ends = said.values.map { texts -> texts.map(::endOf).let { e -> if (e.any { it == null }) null else e.maxOf { checkNotNull(it) } } }
            val until = ends.filterNotNull().minOrNull()
            val support = said.values.map { texts ->
                // A stop listing it in more than one wording stands on the latest of them.
                texts.map(::supportOf).fold(emptyMap<String, Instant?>()) { all, one ->
                    all + one.mapValues { (id, end) -> if (id in all) all[id]?.let { prior -> end?.let { maxOf(it, prior) } } else end }
                }
            }
            val text = said.keys.joinToString("\n\n")
            StationNote(legOf.getValue(row.stopId), row.stopId, row.hubName.ifBlank { row.stopName }, text, until, support)
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
                clusterId = place.area, hubId = place.hub, hubName = place.hubName, placeAliases = place.aliases,
            )
            DepartureRows.across(listOf(stop), now).filter { it.stopDisruption != null }
        }
        return DepartureRows.stopStatusFolded(rows)
    }

    /** Where the stop [stopId] of [trip] sits, as a dismissal of its notice is keyed ([stopPlaceKey]). */
    fun placeKey(trip: ActiveTrip, stopId: String, place: StopPlace?): String =
        stopPlaceKey(place?.hub.orEmpty(), place?.area.orEmpty(), stopNames(trip)[stopId].orEmpty(), stopId)

    private fun stopNames(trip: ActiveTrip): Map<String, String> =
        (trip.route.legs + listOfNotNull(trip.vehicleLeg)).flatMap { listOf(it.fromId to it.fromName, it.toId to it.toName) }.toMap()

    // The coming rides, by leg index: the one the rider is on (waiting for it, or on it) and every one after.
    private fun comingRides(trip: ActiveTrip): List<IndexedValue<TripLeg>> =
        trip.route.legs.withIndex().filter { (i, leg) -> i >= trip.legIndex && !leg.isWalk }

    // TfL's mode for a bus, for [offRide].
    private const val BUS = "bus"

    // TfL's `statusSeverity` for severe delays, for [tierOf].
    private const val SEVERE_DELAYS = 6

    // A line shut over part of its length, which TfL can place by the stops it names ([lineSignal]).
    private val PART_SEVERITIES = LineStatus.PART_SEVERITIES

    // TfL's labels (and [resolveDisruption]'s inferred ones) for a status about part of a route.
    private val STRETCH_LABELS = setOf("diversion", "diverted", "curtailed", "part closure", "part closed", "part suspended")

    private val NOT_IN_SERVICE_NEGATION = Regex(NEGATION, RegexOption.IGNORE_CASE)
    private val NOT_IN_SERVICE_VERB = Regex("""\b(?:run(?:s|ning)?|operat\w*|in\s+service)\b""", RegexOption.IGNORE_CASE)

    // Wording for an effect along the whole route, not a stretch of it: delays, a thinner service, no
    // service at all, buses cancelled or all of them affected, the route itself closed (not a road,
    // the usual reason for a diversion), or the route as a whole. A part
    // suspension is a stretch (Codex, PR #455).
    private val LINE_WIDE = Regex(
        """\b(?:delay|throughout|whole\s+(?:route|line)|entire\s+(?:route|line)|all\s+(?:stops|routes|along)|reduced\s+(?:service|frequency)|less\s+frequent|frequency|no\s+(?:[\w'’]+\s+){0,3}?(?:service|buses)\b|not\s+(?:running|operating|in\s+service)|(?<!\bpart\s)(?<!\bpartly\s)suspend|withdrawn|cancel\w*|(?:route|service|line)s?\b[^.;:]{0,20}?\b(?:is|are|been)\s+closed|all\s+(?:[\w'’]+\s+){0,2}?(?:buses|services|journeys|trips))""" +
            // A contraction ends inside a word, so its own alternative, outside the word boundary above.
            """|n['’]t\s+(?:running|operating|in\s+service)""",
        RegexOption.IGNORE_CASE,
    )
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
