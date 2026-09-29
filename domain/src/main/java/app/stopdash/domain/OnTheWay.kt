package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * A trip the rider has started (SPEC *On the way*): the planned [route] to [destinationName], the
 * leg they're on ([legIndex], into [TripRoute.legs]) since [legStartedAt] (for a ride, when the rider
 * can be at its boarding stop, from which its train is picked), and the train followed on
 * it ([vehicleId], blank until one is picked; a bus can call at the other pole of the rider's stop
 * pair, [vehicleOffId]), due at the boarding stop at [boardsAt] (as last
 * seen, so a loop train's later lap isn't taken for it). [boarded] once that train has left the boarding stop
 * (the rider is taken to be on it), first seen at [boardedAt]; [dueOffAt] when that train was last seen due where the rider gets
 * off (null until it's predicted that far); [warnedLeg] is the leg whose "get off soon" has been said,
 * so it's said once. [alertLeft] while a "get off soon" is done with (its leg left, by the rider's
 * say-so ([atLeg]) or a refresh, or its train lost) and saved so, but may not be taken back yet: a
 * restart takes it back. [waitFrom] is when the wait for the ride's train began, where a train the
 * rider was left behind by has since restarted the leg ([legStartedAt]), so fixes while waiting stay
 * bounded however many trains are missed ([OnTheWay.watchesWait]). Kept on the device only: where a
 * rider is going is theirs (SPEC *Privacy*).
 */
data class ActiveTrip(
    val route: TripRoute,
    val destinationName: String,
    val startedAt: Instant,
    val legIndex: Int = 0,
    val legStartedAt: Instant = startedAt,
    val vehicleId: String = "",
    val boardsAt: Instant? = null,
    val boarded: Boolean = false,
    val boardedAt: Instant? = null,
    val dueOffAt: Instant? = null,
    val warnedLeg: Int = -1,
    val alertLeft: Boolean = false,
    val vehicleOffId: String = "",
    val waitFrom: Instant? = null,
) {
    /** The leg the rider is on, or null once they've arrived. */
    val leg: TripLeg? get() = route.legs.getOrNull(legIndex)
}

/** Where a started trip stands, for its screen, banner and notification (SPEC *On the way*). */
sealed interface TripProgress {
    /** Waiting at [leg]'s boarding stop for the train followed, due at [due] (null: none followed yet). */
    data class Waiting(val leg: TripLeg, val due: Instant?) : TripProgress

    /**
     * On [leg]'s train: next at [nextStop], getting off in [stopsLeft] stops (counting the stop
     * itself), expected there at [getOffAt] — null while that stop is beyond TfL's predictions, when
     * only the stops are counted from the plan. [stopsLeft] is null too when the plan's stops can't
     * be matched to the live ones (a bus leg's, [OnTheWay.checkable]). [getOffSoon] from one stop, or
     * two minutes, out.
     */
    data class Riding(
        val leg: TripLeg,
        val nextStop: String,
        val stopsLeft: Int?,
        val getOffAt: Instant?,
        val getOffSoon: Boolean,
    ) : TripProgress

    /**
     * Changing onto [leg], a ride straight after another, until about [until]: the change time the
     * Planner allows (as the route shows it), with no walk leg of its own.
     */
    data class Changing(val leg: TripLeg, val until: Instant) : TripProgress

    /** On foot along [leg] (a walk to a change or the destination), until about [until]. */
    data class Walking(val leg: TripLeg, val until: Instant) : TripProgress

    /**
     * The train followed can't be placed on [leg] — it doesn't call at the boarding stop or where
     * the rider gets off (the wrong train, or TfL lost it) — so another is to be picked; nothing is
     * claimed meanwhile (SPEC principle 1).
     */
    data class Lost(val leg: TripLeg) : TripProgress

    data object Arrived : TripProgress
}

/**
 * Follows a started trip from its train's calls (SPEC *On the way*). Pure: the caller fetches the
 * followed train's calls ([VehicleSource]) and the boarding stop's departures, and keeps the
 * [ActiveTrip] this returns.
 */
object OnTheWay {
    /** "Get off soon" from this many stops out, the stop itself counted as one. */
    const val GET_OFF_SOON_STOPS = 1

    /** …or from this long before the train is due where the rider gets off. */
    val GET_OFF_SOON_TIME: Duration = Duration.ofMinutes(2)

    /**
     * Whether [route] can be followed: each of its rides by a train its departures name. National
     * Rail's come from its own boards (SPEC *National Rail*), which name none.
     */
    fun canFollow(route: TripRoute): Boolean =
        route.legs.none { !it.isWalk && it.mode.equals(NATIONAL_RAIL_MODE, ignoreCase = true) }

    /**
     * With live location: a rider still this close to the boarding stop this long after their train
     * left it didn't get on (a train is well clear of its platform within a minute), and the window
     * after boarding in which a fix is asked for at all — past it, the rider is on the train.
     */
    const val MISSED_WITHIN_METERS = 150.0
    val MISSED_AFTER: Duration = Duration.ofMinutes(1)
    val MISSED_WINDOW: Duration = Duration.ofMinutes(5)

    /** How sure a fix must be to tell [MISSED_WITHIN_METERS] apart: the rider's train is well past it. */
    const val FIX_WITHIN_METERS = 50f

    /** How recent a fix must be: a train covers 150 m in seconds, so an older one can't say. */
    const val FIX_FRESH_WITHIN_MILLIS = 10_000L

    /** Whether [fix] is sure and recent enough to tell a rider left behind ([usableFix]'s test). */
    fun sureEnough(fix: LocationFix): Boolean {
        val accuracy = fix.accuracyMeters ?: return false
        val age = fix.ageMillis ?: return false
        return accuracy <= FIX_WITHIN_METERS && age <= FIX_FRESH_WITHIN_MILLIS
    }

    /**
     * Where [fix] places the rider, when it's sure enough for the left-behind check: fresh, precise,
     * within [FIX_WITHIN_METERS], and taken within [FIX_FRESH_WITHIN_MILLIS]. A vague one (indoors, a tunnel mouth) could put a rider already
     * on the train near the stop, so it's never acted on.
     */
    fun usableFix(fix: LocationFix): LocationFix? =
        fix.takeIf { !fix.isFallback && !fix.isCoarse && sureEnough(fix) }

    /**
     * Whether [fix] is sure and recent enough for what [trip] wants a fix for at [now]: on a walk to
     * a stop ([walkingTo]), any fix sure to within [AT_STOP_WITHIN_METERS] can settle "they're
     * there" (the test itself counts its uncertainty), so one sure to 100 m isn't thrown away; the
     * left-behind check keeps [sureEnough]'s tighter bound.
     */
    fun sureEnoughFor(trip: ActiveTrip, now: Instant): (LocationFix) -> Boolean =
        if (seesWalkEnd(trip, now) || stationRiddenTo(trip, now) != null || watchesWait(trip, now)) ::sureEnoughToArrive else ::sureEnough

    private fun sureEnoughToArrive(fix: LocationFix): Boolean {
        val accuracy = fix.accuracyMeters ?: return false
        val age = fix.ageMillis ?: return false
        return accuracy <= AT_STOP_WITHIN_METERS && age <= FIX_FRESH_WITHIN_MILLIS
    }

    /** [fix] when it's a real, precise fix sure enough for what [trip] wants one for ([sureEnoughFor]). */
    fun usableFix(fix: LocationFix, trip: ActiveTrip, now: Instant): LocationFix? =
        fix.takeIf { !fix.isFallback && !fix.isCoarse && sureEnoughFor(trip, now)(fix) }

    /**
     * The train to follow for a leg: the soonest of its [trains] (the leg's line, heading its way,
     * as the trip lists them) that TfL names and the rider can reach by [readyAt]. The maintainer's
     * rule (2026-09-26): assume the next catchable train, and switch once another is seen to be the
     * one they're on.
     */
    fun pickTrain(trains: List<Departure>, readyAt: Instant): Departure? =
        trains.filter { it.vehicleId.isNotBlank() && !it.expectedArrival.isBefore(readyAt) }.minByOrNull { it.expectedArrival }

    /**
     * The trains [leg] could be followed on, soonest first: its line's departures at its boarding
     * stop that TfL names and the rider can reach by [readyAt], each once. Which of them runs where
     * the rider is going is for their calls to say ([runsAlong]).
     */
    fun candidates(departures: List<Departure>, leg: TripLeg, readyAt: Instant): List<Departure> =
        departures.filter { it.lineId == leg.lineId && it.vehicleId.isNotBlank() && !it.expectedArrival.isBefore(readyAt) }
            .sortedBy { it.expectedArrival }
            .distinctBy { it.vehicleId }

    /**
     * Whether a train with [calls] ahead of it takes [leg]: it calls at the boarding stop, and later
     * where the rider gets off — not a train the other way, or to another branch. TfL predicts only
     * so far ahead ([VehicleSource]), so a train whose predictions end first takes the leg when every
     * stop predicted after boarding is on the leg's path.
     */
    fun runsAlong(leg: TripLeg, calls: List<VehicleCall>, heading: String? = null): Boolean {
        val on = calls.indexOfFirst { calls(it, leg.fromId, leg.fromName) }
        if (on < 0) return false
        val ahead = calls.drop(on + 1)
        val off = ahead.indexOfFirst { calls(it, leg.toId, leg.toName) }
        if (off >= 0) return keepsToLeg(leg, ahead.take(off))
        if (ahead.isEmpty()) return false
        // A leg whose stops can't be checked (a bus) looks the same as a short working or another
        // branch until its calls reach the stop: meanwhile only the terminus it shows ([heading],
        // its departure's) against the Planner's tells them apart.
        if (!checkable(leg)) return heading != null && leg.headings.any { signed(it).equals(signed(heading), ignoreCase = true) }
        return keepsToLeg(leg, ahead)
    }

    /**
     * Whether [leg]'s planned stops can be matched to a train's live calls: the Planner names a bus
     * leg's stops by their stop area ("490G…", a road's poles together), which the live calls (by
     * pole) never name, and TfL gives no way to tell which area a pole is in. Such a leg is checked
     * on its ends alone; a leg with no plan of its stops is too.
     */
    fun checkable(leg: TripLeg): Boolean = leg.path.isNotEmpty() && leg.path.none { it.startsWith(STOP_AREA_PREFIX) }

    /**
     * The ride the rider is on their way to board: the leg they're on while it's a ride not yet
     * boarded (waiting, or changing onto it), or the ride after the walk they're on. Null while
     * riding, on the walk to the destination, and once arrived.
     */
    fun upcomingRide(trip: ActiveTrip): TripLeg? {
        val leg = trip.leg ?: return null
        if (!leg.isWalk) return leg.takeIf { !trip.boarded }
        return trip.route.legs.getOrNull(trip.legIndex + 1)?.takeIf { !it.isWalk }
    }

    // Where [ride] gets off, as a train's route may name it: its own stop and, for a bus stop pair
    // ([TripLeg.toArea]), every pole of that pair the lines' routes know of, as a trip's ride lines
    // count them ([RideLines]): another bus reaching the pair by the other pole takes the rider there too.
    private fun ends(ride: TripLeg, sequences: Map<String, LineSequence?>): List<DirectTrips.End> {
        val poles = if (ride.toArea.isEmpty()) emptySet() else {
            sequences.values.filterNotNull().flatMapTo(LinkedHashSet()) { seq -> seq.stopAreas.filterValues { it == ride.toArea }.keys }
        }
        return (listOf(ride.toId) + (poles - ride.toId)).map { DirectTrips.End(it, ride.toName) }
    }

    /**
     * When the rider can board [upcomingRide], as [progress] stands: the walk's end (with the change
     * time the Planner allows after it), the change's end, or, waiting, when the ride's leg began. A
     * train leaving before then is one they can't catch, grayed as a trip's cards gray it. Null
     * while riding or arrived.
     */
    fun readyAt(trip: ActiveTrip, progress: TripProgress?): Instant? = when (progress) {
        is TripProgress.Walking -> progress.until.plus(progress.leg.changeAfter)
        is TripProgress.Changing -> progress.until
        is TripProgress.Waiting -> trip.legStartedAt
        else -> null
    }

    /**
     * The lines whose routes [boardTrains] needs for [ride]'s board: those of its mode at the stop,
     * never a blank id (a departure TfL names no line for is unresolved without one, and asking for
     * a blank line's route only spends requests on an answer that can't come).
     */
    fun boardLineIds(ride: TripLeg, departures: List<Departure>): List<String> =
        departures.filter { it.mode.equals(ride.mode, ignoreCase = true) }
            .map { it.lineId }.filter { it.isNotBlank() }.distinct().sorted()

    /**
     * What [boardTrains] found: the trains, soonest first; whether a line's route is still loading
     * ([pending]); and whether a train couldn't be checked ([unresolved]: its route failed or doesn't
     * place it), so an empty or short list isn't taken for the whole answer.
     */
    data class BoardTrains(
        val trains: List<Departure>,
        val pending: Boolean,
        val unresolved: Boolean = false,
        // Each train that couldn't be placed, for the debug log (as every trip filter reports them).
        val misses: Set<RouteMiss> = emptySet(),
    )

    /**
     * Every train at [ride]'s boarding stop that takes the rider to where they get off (maintainer,
     * 2026-09-28): of its [departures] (fetched at [fetchedAt]), those of the ride's mode on any line
     * whose route ([sequences], by line) calls at the ride's alighting stop, not just the Planner's
     * line — each is a way to the same stop. A train for another branch is left out, as on a From…
     * To… page ([DirectTrips.filter]); a line whose route is still loading isn't guessed at.
     */
    fun boardTrains(
        ride: TripLeg,
        departures: List<Departure>,
        fetchedAt: Instant,
        sequences: Map<String, LineSequence?>,
        now: Instant,
    ): BoardTrains {
        val sameMode = Countdown.upcoming(departures.filter { it.mode.equals(ride.mode, ignoreCase = true) }, now)
        if (sameMode.isEmpty()) return BoardTrains(emptyList(), pending = false, unresolved = false)
        val result = routed(ride, sameMode, fetchedAt, sequences)
        val kept = result.stops.firstOrNull()?.departures.orEmpty().sortedBy { it.expectedArrival }
        return BoardTrains(kept, result.pending, result.unresolved, result.misses)
    }

    /**
     * Of [departures] at [ride]'s boarding stop, gone or still to come, those whose line's route
     * ([sequences], by line) takes them where the rider gets off: not another branch's, as on the
     * ride's board ([boardTrains]). A line whose route isn't among [sequences] keeps none.
     */
    fun takesRide(ride: TripLeg, departures: List<Departure>, sequences: Map<String, LineSequence?>): List<Departure> =
        routed(ride, departures, Instant.EPOCH, sequences).stops.firstOrNull()?.departures.orEmpty()

    private fun routed(ride: TripLeg, departures: List<Departure>, fetchedAt: Instant, sequences: Map<String, LineSequence?>) =
        DirectTrips.filter(listOf(StopArrivals(ride.fromId, ride.fromName, departures, fetchedAt)), ends(ride, sequences), sequences)

    /** [trip] following [train] on its current leg, not yet on board. */
    fun follow(trip: ActiveTrip, train: Departure): ActiveTrip =
        trip.copy(vehicleId = train.vehicleId, vehicleOffId = "", boardsAt = train.expectedArrival, boarded = false, boardedAt = null, dueOffAt = null)

    /**
     * [trip] brought up to date at [now] from its followed train's [calls] (null when they couldn't
     * be fetched, or no train is followed): the trip as it now stands — moved on to the next leg once
     * the rider has got off, or a walk has had its time — and where it stands. Moves at most as far
     * as the calls show; a leg reached here waits for its own train to be picked. The calls are a
     * prediction window ([VehicleSource]): the stop missing from them is not yet predicted, not
     * passed, while the train's next stop is still on the leg; and an empty list ends a ride only once
     * the rider was seen due off.
     */
    fun advance(trip: ActiveTrip, calls: List<VehicleCall>?, now: Instant): Pair<ActiveTrip, TripProgress> {
        val leg = trip.leg ?: return trip to TripProgress.Arrived
        if (leg.isWalk) {
            val until = trip.legStartedAt.plus(leg.run)
            return if (now.isBefore(until)) trip to TripProgress.Walking(leg, until) else nextLeg(trip, now)
        }
        changeUntil(trip, now)?.let { return trip to TripProgress.Changing(leg, it) }
        if (trip.vehicleId.isBlank() || calls == null) {
            return trip to if (trip.boarded) TripProgress.Lost(leg) else TripProgress.Waiting(leg, null)
        }
        // The train is still to come while it calls at the boarding stop about when it was due there
        // ([ActiveTrip.boardsAt], kept up to date as it runs late): a call there a lap later is a
        // loop coming round again, after the rider's ride. Once on board, the rider stays on it
        // whatever it calls at next.
        if (!trip.boarded) {
            val boarding = upcomingBoarding(trip, leg, calls)
            // A train due at the boarding stop before the rider can be there (revised earlier) isn't
            // one they can catch, still due or gone: another is to be picked.
            val due = if (boarding >= 0) calls[boarding].expected else trip.boardsAt
            if (due != null && due.isBefore(trip.legStartedAt)) return trip to TripProgress.Lost(leg)
            if (boarding >= 0) {
                // Its calls from there leaving the leg before the stop (a diversion, a short working):
                // not a train the rider can take.
                val ahead = calls.drop(boarding + 1)
                val off = ahead.indexOfFirst { arrivesAt(trip, leg, it) }
                if (!keepsToLeg(leg, if (off >= 0) ahead.take(off) else ahead)) return trip to TripProgress.Lost(leg)
                return trip.copy(boardsAt = due) to TripProgress.Waiting(leg, due)
            }
        }
        val off = calls.indexOfFirst { arrivesAt(trip, leg, it) }
        // The stop again a lap later, after the rider was seen due there by now: they got off. A lap
        // is told from a delay by order, not time: the next lap reaches the stop only after coming
        // round through the boarding stop again, where a held train still has only the leg ahead.
        if (off >= 0 && seenPast(trip, now) && calls.take(off).any { calls(it, leg.fromId, leg.fromName) }) {
            return nextLeg(trip, now)
        }
        if (off < 0) {
            val next = calls.firstOrNull()
            if (next != null && !checkable(leg) && !seenPast(trip, now)) {
                // A bus with its stop beyond the predictions: on it, but its stops left can't be counted.
                return trip.copy(boarded = true, boardedAt = boardedSince(trip, now)) to TripProgress.Riding(leg, next.stopName, null, null, false)
            }
            val along = next?.let { onPath(leg, it) } ?: -1
            if (along >= 0) {
                // Its predictions leave the leg before reaching the stop: a diversion, not the ride.
                if (!keepsToLeg(leg, calls)) return trip to TripProgress.Lost(leg)
                // Still on the leg, with the stop beyond the predictions: count the stops, claim no time.
                val stopsLeft = (leg.path.indexOf(leg.toId).takeIf { it >= 0 } ?: leg.path.lastIndex) - along + 1
                val soon = stopsLeft <= GET_OFF_SOON_STOPS
                return trip.copy(boarded = true, boardedAt = boardedSince(trip, now)) to
                TripProgress.Riding(leg, next!!.stopName, stopsLeft, null, soon)
            }
            // The train's calls have left the leg (or TfL has none left). The rider got off only if
            // they were on it and it was seen due at their stop by now — a train that passes it runs
            // a little early at most. Otherwise it was never theirs, turned off the leg (a diversion,
            // another branch), or TfL stopped predicting it early: nothing is claimed.
            return if (seenPast(trip, now)) nextLeg(trip, now) else trip to TripProgress.Lost(leg)
        }
        // Calls off the leg before the stop: a diversion or another branch, not the rider's ride.
        if (!keepsToLeg(leg, calls.take(off))) return trip to TripProgress.Lost(leg)
        val getOffAt = calls[off].expected
        val stopsLeft = off + 1
        val soon = stopsLeft <= GET_OFF_SOON_STOPS || !now.plus(GET_OFF_SOON_TIME).isBefore(getOffAt)
        val riding = trip.copy(boarded = true, boardedAt = boardedSince(trip, now), dueOffAt = getOffAt)
        return riding to TripProgress.Riding(leg, calls.first().stopName, stopsLeft, getOffAt, soon)
    }

    // Whether [call] is where [trip]'s rider gets off [leg]: the stop the Planner named, or the other
    // pole of its pair that the followed train was found calling at ([ActiveTrip.vehicleOffId]).
    private fun arrivesAt(trip: ActiveTrip, leg: TripLeg, call: VehicleCall): Boolean =
        calls(call, leg.toId, leg.toName) || (trip.vehicleOffId.isNotEmpty() && call.stopId == trip.vehicleOffId)

    /**
     * When the change onto [trip]'s ride ends, while the rider is still making it: a ride straight
     * after another, not yet boarded, before the change time the Planner allows is up. Null otherwise.
     */
    fun changeUntil(trip: ActiveTrip, now: Instant): Instant? {
        val leg = trip.leg ?: return null
        val before = trip.route.legs.getOrNull(trip.legIndex - 1) ?: return null
        if (leg.isWalk || before.isWalk || trip.boarded || !now.isBefore(trip.legStartedAt)) return null
        return trip.legStartedAt
    }

    // A bus blind's place, however it was cleaned: the live feed turns "X Bus Station" into "X Bus"
    // ([cleanStopName] drops only "Station"), the Planner's heading into "X".
    private fun signed(name: String): String {
        val clean = cleanStopName(name)
        return if (clean.endsWith(BUS, ignoreCase = true) && clean.length > BUS.length) clean.dropLast(BUS.length).trim() else clean
    }

    private const val BUS = " Bus"

    /**
     * Whether a location fix could tell anything about [trip] at [now]: the rider is walking to a
     * boarding stop whose position is known or can be read ([seesWalkEnd]), waiting for a ride's
     * train and may already be on another ([watchesWait], [seenAlong]), their train has just
     * left the boarding stop, or it's nearly where they get off ([stationRiddenTo]). Outside those no
     * fix is asked for (battery): a walk is minutes, the other windows a few.
     */
    fun wantsFix(trip: ActiveTrip, now: Instant): Boolean {
        if (seesWalkEnd(trip, now) || stationRiddenTo(trip, now) != null || watchesWait(trip, now)) return true
        return mayBeLeftBehind(trip, now)
    }

    /**
     * How far a rider still waiting for a ride's train must be seen to have moved toward where they
     * get off to be taken as on board (maintainer, 2026-09-29: "a significant step towards the next
     * change"): this much further from the boarding stop, and this much nearer where they get off,
     * wherever within the fix's uncertainty they really are. Waiting on a platform, or crossing a
     * big station, doesn't move a rider this far toward the change; a train covers it in a minute.
     */
    const val ALONG_METERS = 400.0

    /**
     * How long into a wait for a ride's train fixes are asked for, to see the rider already on their
     * way ([seenAlong]): about twenty at most, however many trains the rider is left behind by
     * ([ActiveTrip.waitFrom]). Past it, a wait gone on this long (a delay, a train never coming) asks
     * for none (battery): the train followed leaving still says they boarded, and Next says so by hand.
     */
    val WAITING_WINDOW: Duration = Duration.ofMinutes(10)

    /**
     * How soon a train must be due at the stop a rider is seen at ([seenAlong]) to be the one they're
     * on: at the platform or pulling in. One due there later is still on its way to it, behind them.
     */
    val AT_STOP_DUE_WITHIN: Duration = Duration.ofMinutes(1)

    /**
     * How near the ride's way a rider seen between its stops must be ([seenAlong]): its stops joined
     * by straight lines, which track and road bend away from, but not this far between a few stops.
     * The fix's uncertainty is counted against them. Nearer where they get off, but off to one side,
     * they aren't on it.
     */
    const val ROUTE_WITHIN_METERS = 300.0

    /**
     * Where a rider is seen along a ride ([seenAlong]): [from], the index into the ride's path of
     * the first stop the train they're on can still be due at, and whether they're [atStop] there
     * (so it's due there about now) or still short of it. [atEnd] when that stop is where they get
     * off: they've ridden it, whichever train they took ([rideDone]).
     */
    data class Along(val from: Int, val atStop: Boolean, val atEnd: Boolean = false)

    /**
     * [trip] with the ride it awaited done, its rider seen where they get off ([Along.atEnd]): the
     * next leg starts now, as when a rider on a train is seen at their station ([seen]).
     */
    fun rideDone(trip: ActiveTrip, now: Instant): ActiveTrip = nextLeg(trip.copy(dueOffAt = null), now).first

    /** Whether a fix is asked for [trip] at [now] to see the rider already on their way ([seenAlong]). */
    fun watchesWait(trip: ActiveTrip, now: Instant): Boolean {
        val from = trip.waitFrom ?: trip.legStartedAt
        // A wait that began after now is a clock set back: its ten minutes can't be counted from it,
        // so they're taken as over rather than run again from the earlier time (Codex, PR #383).
        return waitingToBoard(trip, now) != null && !now.isBefore(from) && now.isBefore(from.plus(WAITING_WINDOW))
    }

    /**
     * The ride [trip]'s rider is still to board at [now]: its leg, not yet boarded, its change (if
     * any) done. A fix then can see them already on their way along it ([seenAlong]); null otherwise.
     */
    fun waitingToBoard(trip: ActiveTrip, now: Instant): TripLeg? {
        val leg = trip.leg ?: return null
        return leg.takeIf { !leg.isWalk && !trip.boarded && changeUntil(trip, now) == null }
    }

    /**
     * Each stop of [leg]'s ride placed from its line's route ([sequence]): its boarding stop, the
     * stops along its path and where it gets off, by id. A stop area the Planner names ("490G…", a
     * road's poles together) is placed at the middle of its poles; the boarding and alighting stops
     * fall back on the Planner's own points. A stop the route doesn't place is left out.
     */
    fun ridePositions(leg: TripLeg, sequence: LineSequence): Map<String, Coordinates> {
        val seen = sequence.callingAt(leg.fromId).callingAt(leg.toId)
        fun placed(id: String): Coordinates? {
            seen.stopPositions[id]?.let { (lat, lon) -> return Coordinates(lat, lon) }
            val poles = seen.stopAreas.filterValues { it == id }.keys.mapNotNull { seen.stopPositions[it] }
            if (poles.isEmpty()) return null
            return Coordinates(poles.map { it.first }.average(), poles.map { it.second }.average())
        }
        val placed = LinkedHashMap<String, Coordinates>()
        (listOf(leg.fromId) + leg.path + leg.toId).distinct().forEach { id -> placed(id)?.let { placed[id] = it } }
        leg.fromAt?.let { placed.putIfAbsent(leg.fromId, it) }
        leg.toAt?.let { placed.putIfAbsent(leg.toId, it) }
        return placed
    }

    /**
     * The poles of stop pair [area] ("490G…", a road's poles together) that [sequence], a line's route,
     * knows of; none for a blank area (a station, or a stop the Planner named by pole alone).
     */
    fun pairPoles(area: String, sequence: LineSequence): Set<String> =
        if (area.isBlank()) emptySet() else sequence.stopAreas.filterValues { it == area }.keys

    /**
     * Whether [rider] is seen along [trip]'s ride while its train is still awaited
     * ([waitingToBoard]), from the ride's stops' [positions] ([ridePositions]): clear of the boarding
     * stop (further than [MISSED_WITHIN_METERS], where a rider is still taken to be waiting), and
     * either at one of the ride's later stops ([AT_STOP_WITHIN_METERS]) or [ALONG_METERS] on toward
     * where they get off (maintainer, 2026-09-29). Then they boarded, whichever train the trip was
     * following. The value places them on the ride's path ([Along]): at the stop they were seen at,
     * or short of the first stop they aren't yet past, so the train they're on calls there or beyond
     * it. Null when they aren't seen along it, or the boarding stop isn't placed.
     */
    fun seenAlong(trip: ActiveTrip, rider: LocationFix, positions: Map<String, Coordinates>, now: Instant): Along? {
        val leg = waitingToBoard(trip, now) ?: return null
        val accuracy = rider.accuracyMeters?.toDouble() ?: return null
        val boarding = positions[leg.fromId] ?: return null
        val fromBoarding = distance(rider.coordinates, boarding)
        if (fromBoarding - accuracy <= MISSED_WITHIN_METERS) return null
        val end = positions[leg.toId]
        // At where they get off: the ride is done, however they got there (Codex, PR #383).
        if (end != null && near(rider, end, AT_STOP_WITHIN_METERS)) return Along(leg.path.size, atStop = true, atEnd = true)
        // The furthest stop they're at: stops a road's width apart can both be within reach.
        leg.path.indices.lastOrNull { i -> positions[leg.path[i]]?.let { near(rider, it, AT_STOP_WITHIN_METERS) } == true }
            ?.let { return Along(it, atStop = true) }
        if (end == null) return null
        val toEnd = distance(rider.coordinates, end)
        val gained = distance(boarding, end) - (toEnd + accuracy)
        if (fromBoarding - accuracy < ALONG_METERS || gained < ALONG_METERS) return null
        // Near the ride's way, not only further on toward its end (Codex, PR #383).
        val way = (listOf(leg.fromId) + leg.path + leg.toId).distinct().mapNotNull { positions[it] }
        val offWay = way.zipWithNext { a, b -> fromLine(rider.coordinates, a, b) }.minOrNull() ?: return null
        if (offWay + accuracy > ROUTE_WITHIN_METERS) return null
        // Between stops: past each stop both further from the boarding stop than it is and nearer
        // where they get off, so a train still to call at one is behind them. The fix's uncertainty
        // counts toward passing it: a train of theirs passed over claims nothing, where one behind
        // them taken for theirs is the failure (Codex, PR #383).
        val passed = leg.path.indices.lastOrNull { i ->
            positions[leg.path[i]]?.let { fromBoarding + accuracy > distance(it, boarding) && toEnd - accuracy < distance(it, end) } == true
        }
        return Along(passed?.plus(1) ?: 0, atStop = false)
    }

    /**
     * [trip] on board [train] with its [calls] ahead, when it's the train the rider was seen on
     * ([seenAlong], at stop [from] of the ride's path when [atStop], else short of it): it has left
     * the boarding stop (its calls don't reach it), and its calls from there place it on the ride at
     * or beyond that stop, due there about now if the rider was seen at it, whichever
     * train the trip was following (the maintainer's rule: switch when seen). The caller offers only
     * the ride's own line's trains that run where the rider gets off ([takesRide]), as the trip follows
     * no other line's. Null when it isn't.
     */
    fun boardedOn(
        trip: ActiveTrip,
        train: Departure,
        calls: List<VehicleCall>,
        from: Int,
        now: Instant,
        // The poles of the ride's boarding and alighting stop pairs ([pairPoles]): a bus can use the
        // other pole of either from the one the Planner named.
        boardingPoles: Set<String> = emptySet(),
        alightingPoles: Set<String> = emptySet(),
        // Each pole's stop area ([LineSequence.stopAreas]), placing a bus's calls on the ride's path.
        areas: Map<String, String> = emptyMap(),
        atStop: Boolean = false,
    ): Pair<ActiveTrip, TripProgress>? {
        val leg = trip.leg ?: return null
        val next = calls.firstOrNull() ?: return null
        val off = calls.indexOfFirst { calls(it, leg.toId, leg.toName) || it.stopId in alightingPoles }
        // Still to call at the boarding stop on its way there: not yet left it, so not the rider's.
        if ((if (off >= 0) calls.take(off) else calls).any { calls(it, leg.fromId, leg.fromName) || it.stopId in boardingPoles }) return null
        val at = if (checkable(leg)) {
            // Where it calls next along the ride: where the rider gets off, when the path leaves that out.
            val at = onPath(leg, next).takeIf { it >= 0 } ?: if (calls(next, leg.toId, leg.toName)) leg.path.size else return null
            if (at < from) return null
            at
        } else {
            // A leg whose stops can't be matched to the live ones by id (a bus's) is taken on reaching
            // where the rider gets off: any bus of the mode would pass the rest.
            if (off < 0) return null
            // Where it calls next along the ride, its pole placed by stop area ([areas]): behind where
            // the rider was seen, it's a later bus, not theirs. Once they were seen past the first
            // stop, one that can't be placed can't be told from a later one (Codex, PR #383).
            val at = if (off == 0) leg.path.size else areas[next.stopId]?.let { leg.path.indexOf(it) } ?: -1
            if (from > 0 && at < from) return null
            at
        }
        // Seen at that stop, a train still due there later is on its way to it, behind them.
        if (atStop && at == from && next.expected.isAfter(now.plus(AT_STOP_DUE_WITHIN))) return null
        val left = train.expectedArrival.takeIf { !it.isAfter(now) } ?: now
        // Kept with the pole it calls at for the rider's stop, where that's the pair's other one, so
        // it's known there as their stop ([arrivesAt]).
        val offId = calls.getOrNull(off)?.takeIf { !calls(it, leg.toId, leg.toName) }?.stopId.orEmpty()
        val on = trip.copy(
            vehicleId = train.vehicleId, vehicleOffId = offId,
            boardsAt = train.expectedArrival, boarded = true, boardedAt = left, dueOffAt = null,
        )
        return advance(on, calls, now).takeIf { it.second is TripProgress.Riding }
    }

    private fun distance(a: Coordinates, b: Coordinates): Double =
        NearestStops.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    // How far [point] is from the straight line between [a] and [b], in meters: flat, as the Earth
    // is over the few kilometers between a ride's stops.
    private fun fromLine(point: Coordinates, a: Coordinates, b: Coordinates): Double {
        val east = METERS_PER_DEGREE * kotlin.math.cos(Math.toRadians(point.latitude))
        val ax = (a.longitude - point.longitude) * east
        val ay = (a.latitude - point.latitude) * METERS_PER_DEGREE
        val dx = (b.longitude - a.longitude) * east
        val dy = (b.latitude - a.latitude) * METERS_PER_DEGREE
        val length = dx * dx + dy * dy
        val along = if (length == 0.0) 0.0 else (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(ax + along * dx, ay + along * dy)
    }

    // A degree of latitude, and of longitude at the equator, in meters.
    private const val METERS_PER_DEGREE = 111_195.0

    // Whether [trip]'s train has just left the boarding stop, where a fix can see the rider left behind.
    private fun mayBeLeftBehind(trip: ActiveTrip, now: Instant): Boolean {
        val leg = trip.leg ?: return false
        val boardedAt = trip.boardedAt ?: return false
        return leg.mode in LEFT_BEHIND_MODES && leg.fromAt != null && trip.boarded && now.isBefore(boardedAt.plus(MISSED_WINDOW))
    }

    /**
     * A rider this close to the stop they're walking to (a fix's uncertainty included) is there:
     * a station's published position can sit well inside it, away from the entrance they stand at.
     */
    const val AT_STOP_WITHIN_METERS = 150.0

    /**
     * Where [trip]'s rider is walking to at [now], when that's a ride's boarding stop with a known
     * position: the walk from where they started, or one between rides. Null otherwise (a ride, or
     * the walk to the destination, which ends the trip on its time), and once the walk's estimated
     * time is up at [now]: it ends on its time then, with no fix to wait for.
     */
    fun walkingTo(trip: ActiveTrip, now: Instant): Coordinates? = walkingToRide(trip, now)?.fromAt

    /**
     * Whether a fix can see [trip]'s rider at the end of their walk at [now]: its stop is placed by
     * the Planner ([walkingTo]), or it's a station whose own position and entrances can be read
     * ([stationWalkedTo]), which the Planner often leaves unplaced (Codex, PR #352).
     */
    fun seesWalkEnd(trip: ActiveTrip, now: Instant): Boolean = walkingTo(trip, now) != null || stationWalkedTo(trip, now) != null

    /**
     * The ride [trip]'s rider is walking to at [now] when it boards at a station, with entrances of
     * its own to be seen at ([seen]); null for a bus or tram stop, which has none, so its walk costs
     * no request for them (Codex, PR #352).
     */
    fun stationWalkedTo(trip: ActiveTrip, now: Instant): TripLeg? =
        walkingToRide(trip, now)?.takeIf { it.mode in STATION_MODES }

    // Modes whose boarding stops are stations: the Tube, Overground, DLR, Elizabeth line and rail.
    private val STATION_MODES = setOf("tube", "overground", "dlr", "elizabeth-line", "national-rail")

    /**
     * How long before the followed train is due where the rider gets off that a fix is asked for, to
     * see them already there: about two stops (the maintainer, 2026-09-28). The train followed can be
     * a later one than theirs, so they can be there well before it.
     */
    val AT_GET_OFF_BEFORE: Duration = Duration.ofMinutes(4)

    /**
     * How long after the followed train was due where the rider gets off that fixes are still asked
     * for: a train running a little late. Past it no more are (battery): a stale time, with TfL gone
     * quiet, mustn't keep GPS on for the rest of the trip (Codex, PR #359).
     */
    val AT_GET_OFF_AFTER: Duration = Duration.ofMinutes(5)

    /**
     * The ride [trip]'s rider is on at [now] when it gets off at a station and its train is due there
     * within [AT_GET_OFF_BEFORE] (or up to [AT_GET_OFF_AFTER] late): a fix then can see them already
     * at that station ([seen]),
     * by its placed point ([TripLeg.toAt], which the Planner often leaves out) or the station's own
     * position and entrances, read for it. Null otherwise: a bus or tram stops in the street, where
     * being near the stop says nothing of being off, and a train not yet predicted that far can't say
     * when.
     */
    fun stationRiddenTo(trip: ActiveTrip, now: Instant): TripLeg? {
        val leg = trip.leg ?: return null
        if (leg.isWalk || leg.mode !in STATION_MODES || !trip.boarded) return null
        val due = trip.dueOffAt ?: return null
        return leg.takeIf { !now.isBefore(due.minus(AT_GET_OFF_BEFORE)) && now.isBefore(due.plus(AT_GET_OFF_AFTER)) }
    }

    /** The ride [trip]'s rider is walking to at [now], its boarding stop placed or not. */
    fun walkingToRide(trip: ActiveTrip, now: Instant): TripLeg? {
        val leg = trip.leg ?: return null
        if (!leg.isWalk || !now.isBefore(trip.legStartedAt.plus(leg.run))) return null
        val next = trip.route.legs.getOrNull(trip.legIndex + 1) ?: return null
        return next.takeIf { !it.isWalk }
    }

    // Trains only: a train that has left is gone from its platform, while a bus or tram can sit near
    // its stop in traffic with the rider on it, so being near the stop says nothing there.
    private val LEFT_BEHIND_MODES = setOf("tube", "overground", "dlr", "elizabeth-line")

    /**
     * [trip] once the [rider] is seen still at the boarding stop after its train left (within
     * [MISSED_WITHIN_METERS], its fix's uncertainty included): not on it,
     * so the next train they can catch is picked (the maintainer's rule: switch when seen). Unchanged
     * with no fix, or none that says so; a fix underground never comes, so this never guesses.
     * On a walk to a ride, the rider is at its stop when near its placed position or any of the
     * station's [entrances] (the maintainer, 2026-09-28: the Planner can place a big station's stop
     * 200 m from the entrance the rider stands at). On a train nearly where they get off
     * ([stationRiddenTo]), they're off when seen at that station the same way.
     */
    fun seen(trip: ActiveTrip, rider: LocationFix?, now: Instant, entrances: List<Coordinates> = emptyList()): ActiveTrip {
        walkingToRide(trip, now)?.let { ride ->
            // Its placed point, the station's own point and entrances: whichever are known.
            val there = rider != null && (listOfNotNull(ride.fromAt) + entrances).any { near(rider, it, AT_STOP_WITHIN_METERS) }
            return if (there) walked(trip, now) else trip
        }
        // On a train nearly at where they get off, and seen at that station, its placed point or any of
        // its [entrances]: they're off, whatever the train followed says (it can be a later one than
        // theirs, still a stop or two away: the maintainer, 2026-09-28).
        stationRiddenTo(trip, now)?.let { ride ->
            val there = rider != null && (listOfNotNull(ride.toAt) + entrances).any { near(rider, it, AT_STOP_WITHIN_METERS) }
            // Off when seen, not when the train followed was due: a rider seen after its time starts
            // the next leg now, not partly done (Codex, PR #359).
            if (there) return nextLeg(trip.copy(dueOffAt = null), now).first
        }
        val leg = trip.leg ?: return trip
        val at = leg.fromAt ?: return trip
        val boardedAt = trip.boardedAt ?: return trip
        // Only in its own window: a fix asked for nearly where they get off says nothing of the stop behind.
        if (rider == null || !mayBeLeftBehind(trip, now) || now.isBefore(boardedAt.plus(MISSED_AFTER))) return trip
        // Wherever within its uncertainty the rider really is, they're still that close: a fix 140 m out
        // but only sure to 50 m could be a rider already moving off on the train.
        if (!near(rider, at, MISSED_WITHIN_METERS)) return trip
        return trip.copy(
            vehicleId = "", vehicleOffId = "", boardsAt = null, boarded = false, boardedAt = null, dueOffAt = null,
            legStartedAt = now, warnedLeg = -1, waitFrom = trip.waitFrom ?: trip.legStartedAt,
        )
    }

    // Whether [rider] is within [meters] of [at] wherever within its uncertainty they really are.
    private fun near(rider: LocationFix, at: Coordinates, meters: Double): Boolean {
        val accuracy = rider.accuracyMeters ?: return false
        val away = NearestStops.distanceMeters(rider.coordinates.latitude, rider.coordinates.longitude, at.latitude, at.longitude)
        return away + accuracy <= meters
    }

    /**
     * [trip] with its walk done at [now], seen there by location rather than waited out on the
     * walk's time (maintainer, 2026-09-28): on to the ride, its train picked from now.
     */
    fun walked(trip: ActiveTrip, now: Instant): ActiveTrip {
        val leg = trip.leg ?: return trip
        if (!leg.isWalk) return trip
        return trip.copy(
            legIndex = trip.legIndex + 1, legStartedAt = now.plus(leg.changeAfter),
            vehicleId = "", vehicleOffId = "", boardsAt = null, boarded = false, boardedAt = null, dueOffAt = null, waitFrom = null,
        )
    }

    /**
     * [trip] with the rider at the start of leg [index] at [now], because they said so (maintainer,
     * 2026-09-28): **Next**, or a tap on a leg, for when location and the walk's time can't tell (a
     * station far bigger than the point TfL places it at, no fix). The leg starts now, as if they'd
     * just got there: a walk's time runs from now, a ride's train is picked from now, and a "get off
     * soon" for it is said again. Past the last leg is arrived; an earlier leg goes back to it, so a
     * tap made by mistake can be undone.
     */
    fun atLeg(trip: ActiveTrip, index: Int, now: Instant): ActiveTrip = trip.copy(
        legIndex = index.coerceIn(0, trip.route.legs.size), legStartedAt = now,
        vehicleId = "", vehicleOffId = "", boardsAt = null, boarded = false, boardedAt = null, dueOffAt = null, warnedLeg = -1, waitFrom = null,
    )

    /**
     * Whether the rider can put [trip] at leg [index] at [now] ([atLeg]): a leg of the route, other
     * than the one they're on, that the move doesn't carry straight through to arriving — past the
     * last leg, or onto a closing walk of no length (the Planner allows one). Arriving forgets the trip,
     * so no Back could undo it; End trip is the way out there (Codex, PR #351).
     */
    fun canGoTo(trip: ActiveTrip, index: Int, now: Instant): Boolean =
        index in trip.route.legs.indices && index != trip.legIndex &&
            advance(atLeg(trip, index, now), null, now).second != TripProgress.Arrived

    // When the rider boarded, as first seen: when the train was last due to leave the boarding stop
    // ([ActiveTrip.boardsAt], kept up to date as it ran late), not when a refresh first saw it gone, so
    // a refresh missed as it left (TfL unreachable, the app not running) doesn't stretch the left-behind
    // window ([MISSED_WINDOW]) past the train.
    private fun boardedSince(trip: ActiveTrip, now: Instant): Instant =
        trip.boardedAt ?: trip.boardsAt?.takeIf { !it.isAfter(now) } ?: now

    /** [trip] with its "get off soon" said for the leg it's on, so it isn't said again. */
    fun warned(trip: ActiveTrip): ActiveTrip = trip.copy(warnedLeg = trip.legIndex)

    /** Whether [progress] calls for "get off soon" not yet said on [trip]. */
    fun shouldWarn(trip: ActiveTrip, progress: TripProgress): Boolean =
        progress is TripProgress.Riding && progress.getOffSoon && trip.warnedLeg != trip.legIndex

    // The next leg, from when this one was done plus the change the Planner allows after it (a
    // change with no walk leg of its own): a walk's time runs from there, and a ride's train is
    // picked from there. Done is when the rider was due off (or the walk's time was up), not when
    // it was noticed, which is later after a while away; [now] when that isn't known.
    private fun nextLeg(trip: ActiveTrip, now: Instant): Pair<ActiveTrip, TripProgress> {
        val leg = trip.leg
        val doneAt = if (leg?.isWalk == true) trip.legStartedAt.plus(leg.run) else trip.dueOffAt
        val from = (doneAt?.takeIf { it.isBefore(now) } ?: now).plus(leg?.changeAfter ?: Duration.ZERO)
        val next = trip.copy(legIndex = trip.legIndex + 1, legStartedAt = from, vehicleId = "", vehicleOffId = "", boardsAt = null, boarded = false, boardedAt = null, dueOffAt = null, waitFrom = null)
        val onward = next.leg ?: return next to TripProgress.Arrived
        if (onward.isWalk) {
            val until = from.plus(onward.run)
            // A walk already done while away: on to the leg after it.
            return if (now.isBefore(until)) next to TripProgress.Walking(onward, until) else nextLeg(next, now)
        }
        return next to (changeUntil(next, now)?.let { TripProgress.Changing(onward, it) } ?: TripProgress.Waiting(onward, null))
    }

    // A bus stop area's id, naming a road's poles together.
    private const val STOP_AREA_PREFIX = "490G"

    // A bus stop's id, a pole's or a stop area's.
    private const val BUS_STOP_PREFIX = "490"

    // Where in [calls] the followed train is still to call at [leg]'s boarding stop for the rider, or
    // -1 once it has left. A call there before any of its calls on the leg is still to come, however
    // late. One after calls on the leg is a loop's: either the train still on its previous lap, or
    // its next lap after the rider's ride. With when it was due there ([ActiveTrip.boardsAt]), the
    // previous lap is the call about then (within [LATE_BY]). Without, it's one a ride on the leg follows.
    private fun upcomingBoarding(trip: ActiveTrip, leg: TripLeg, calls: List<VehicleCall>): Int {
        // The first call there the rider can catch (from [ActiveTrip.legStartedAt]): one before it is
        // an earlier lap they couldn't, when a later lap's call there follows; with none, it's the
        // picked train revised to leave too soon.
        val atBoarding = calls.indices.filter { calls(calls[it], leg.fromId, leg.fromName) }
        if (atBoarding.isEmpty()) return -1
        val boarding = atBoarding.firstOrNull { !calls[it].expected.isBefore(trip.legStartedAt) } ?: atBoarding.last()
        val onLeg = calls.indexOfFirst { onPath(leg, it) >= 0 || calls(it, leg.toId, leg.toName) }
        if (onLeg < 0 || boarding < onLeg) return boarding
        val boardsAt = trip.boardsAt
        val previousLap = if (boardsAt != null) {
            !calls[boarding].expected.isAfter(boardsAt.plus(LATE_BY))
        } else {
            runsAlong(leg, calls.drop(boarding))
        }
        return if (previousLap) boarding else -1
    }

    // How much later than last seen a train can come to be due at the boarding stop in one refresh
    // and still be the same call, not a loop's next lap.
    private val LATE_BY: Duration = Duration.ofMinutes(5)

    // Whether the rider, on [trip]'s train, was seen due at their stop by [now]: with its calls no
    // longer reaching the stop, it has called there and they got off.
    private fun seenPast(trip: ActiveTrip, now: Instant): Boolean {
        val dueOff = trip.dueOffAt ?: return false
        return trip.boarded && !now.plus(EARLY).isBefore(dueOff)
    }

    // How early a train can pass the stop it was last seen due at, and still count as having called.
    private val EARLY: Duration = Duration.ofMinutes(1)

    // Whether [calls] (between boarding and getting off) are each one of [leg]'s own stops, in the
    // leg's order: a train running via another branch, or a loop's long way round, doesn't take it.
    // A leg with no path to check against can't be told apart, so is taken on the calls' ends alone.
    private fun keepsToLeg(leg: TripLeg, calls: List<VehicleCall>): Boolean {
        if (!checkable(leg)) return true
        val along = calls.map { onPath(leg, it) }
        return along.all { it >= 0 } && along.zipWithNext().all { (a, b) -> a < b }
    }

    // Where [call] is along [leg]'s path (its stops after boarding), or -1 when it isn't.
    // By id, or (as for the leg's ends) by name when TfL names a station by another id — never for two
    // different bus poles.
    private fun onPath(leg: TripLeg, call: VehicleCall): Int {
        val byId = leg.path.indexOf(call.stopId)
        if (byId >= 0 || StopDisruptionBatch.isPole(call.stopId)) return byId
        return leg.pathNames.indexOfFirst { it.isNotBlank() && it.equals(call.stopName, ignoreCase = true) }
    }

    // Whether [call] is at the stop [id] (or, by name, the same place: TfL's arrivals can name a
    // station by another of its ids).
    // A bus pole is never matched by name to another pole, or to a stop area the Planner gave for want
    // of a pole: a route can call at two "High Street"s, and a pole's id is authoritative ([Terminating]).
    private fun calls(call: VehicleCall, id: String, name: String): Boolean {
        if (call.stopId == id) return true
        if (StopDisruptionBatch.isPole(call.stopId) && id.startsWith(BUS_STOP_PREFIX)) return false
        return name.isNotBlank() && call.stopName.equals(cleanStopName(name), ignoreCase = true)
    }
}
