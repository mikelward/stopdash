package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * A trip the rider has started (SPEC *On the way*): the planned [route] to [destinationName], the
 * leg they're on ([legIndex], into [TripRoute.legs]) since [legStartedAt] (for a ride, when the rider
 * can be at its boarding stop, from which its train is picked), and the train followed on
 * it ([vehicleId], blank until one is picked), due at the boarding stop at [boardsAt] (as last
 * seen, so a loop train's later lap isn't taken for it). [boarded] once that train has left the boarding stop
 * (the rider is taken to be on it), first seen at [boardedAt]; [dueOffAt] when that train was last seen due where the rider gets
 * off (null until it's predicted that far); [warnedLeg] is the leg whose "get off soon" has been said,
 * so it's said once. Kept on the device only: where a rider is going is theirs (SPEC *Privacy*).
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

    /** [trip] following [train] on its current leg, not yet on board. */
    fun follow(trip: ActiveTrip, train: Departure): ActiveTrip =
        trip.copy(vehicleId = train.vehicleId, boardsAt = train.expectedArrival, boarded = false, boardedAt = null, dueOffAt = null)

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
                val off = ahead.indexOfFirst { calls(it, leg.toId, leg.toName) }
                if (!keepsToLeg(leg, if (off >= 0) ahead.take(off) else ahead)) return trip to TripProgress.Lost(leg)
                return trip.copy(boardsAt = due) to TripProgress.Waiting(leg, due)
            }
        }
        val off = calls.indexOfFirst { calls(it, leg.toId, leg.toName) }
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
     * Whether a location fix could tell anything about [trip] at [now]: its train has just left the
     * boarding stop, whose position is known. Outside that window no fix is asked for (battery).
     */
    fun wantsFix(trip: ActiveTrip, now: Instant): Boolean {
        val leg = trip.leg ?: return false
        val boardedAt = trip.boardedAt ?: return false
        return leg.mode in LEFT_BEHIND_MODES && leg.fromAt != null && trip.boarded && now.isBefore(boardedAt.plus(MISSED_WINDOW))
    }

    // Trains only: a train that has left is gone from its platform, while a bus or tram can sit near
    // its stop in traffic with the rider on it, so being near the stop says nothing there.
    private val LEFT_BEHIND_MODES = setOf("tube", "overground", "dlr", "elizabeth-line")

    /**
     * [trip] once the [rider] is seen still at the boarding stop after its train left (within
     * [MISSED_WITHIN_METERS], its fix's uncertainty included): not on it,
     * so the next train they can catch is picked (the maintainer's rule: switch when seen). Unchanged
     * with no fix, or none that says so; a fix underground never comes, so this never guesses.
     */
    fun seen(trip: ActiveTrip, rider: LocationFix?, now: Instant): ActiveTrip {
        val leg = trip.leg ?: return trip
        val at = leg.fromAt ?: return trip
        val boardedAt = trip.boardedAt ?: return trip
        if (rider == null || !wantsFix(trip, now) || now.isBefore(boardedAt.plus(MISSED_AFTER))) return trip
        val away = NearestStops.distanceMeters(rider.coordinates.latitude, rider.coordinates.longitude, at.latitude, at.longitude)
        // Wherever within its uncertainty the rider really is, they're still that close: a fix 140 m out
        // but only sure to 50 m could be a rider already moving off on the train.
        val accuracy = rider.accuracyMeters ?: return trip
        if (away + accuracy > MISSED_WITHIN_METERS) return trip
        return trip.copy(vehicleId = "", boardsAt = null, boarded = false, boardedAt = null, dueOffAt = null, legStartedAt = now, warnedLeg = -1)
    }

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
        val next = trip.copy(legIndex = trip.legIndex + 1, legStartedAt = from, vehicleId = "", boardsAt = null, boarded = false, boardedAt = null, dueOffAt = null)
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
