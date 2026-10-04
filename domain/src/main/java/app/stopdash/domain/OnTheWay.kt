package app.stopdash.domain

import androidx.annotation.WorkerThread
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
 * bounded however many trains are missed ([OnTheWay.watchesWait]). [leftRide] is the ride the rider
 * was on board when Next moved them past it, so Back straight to it restores it as it was, its train
 * or none named ([OnTheWay.atStep]); in memory only, so not past a restart. [onBoardSeen] once the rider is
 * known to be on board, by their word ([OnTheWay.atStep]) or seen along the ride ([OnTheWay.boardedOn]), not
 * only taken to be because the train followed left ([boarded]): until then the ride's step and its board of
 * departures stay up, since a rider still on the platform looks the same underground (maintainer, 2026-09-29).
 * [seenAlongStop] is the next stop on the ride's path ([TripLeg.path]) ahead of where the rider was seen
 * while on board with no train yet known to be theirs ([OnTheWay.onBoardAlong]); -1 otherwise.
 * [boardWarned] is the train whose "time to board" has been said ([OnTheWay.boardKey]): its leg and the train
 * followed there, so it's said once for each train the rider waits for, a missed one's next included.
 * [disruptionsHeard] is each "route disruption" already heard ([RouteDisruption.Signal.key]), so a restart
 * doesn't sound it again and only something new is heard. [disruptionsDismissed] is each the rider
 * dismissed on the trip's screen, read and kept going: no longer shown nor alerted on this trip, while
 * something new (another stop, a worse status) still is. [vehicleLeg] is the ride as the line of the
 * train followed runs it, where that's another of the ride's lines than the Planner's ([RideLines]):
 * its line is the one TfL answers for the train on, and the one the step tells the rider to board, and
 * the train's calls are checked against its own stops ([OnTheWay.ridden]). Null for the ride's own
 * line ([OnTheWay.followedLine], [OnTheWay.followedLineName]). [heldFrom] is when the train followed
 * was due at the boarding stop before a call there was first taken as the same one only because the
 * rider was seen still at the stop ([OnTheWay.atBoarding]): held there until then, and no later, so
 * a rider still there after it may have missed the train, however late each call since has run.
 * [destinations] and [destinationIds] are where the trip goes as the rider chose it, as the trip list
 * planned to it: every stop of a station complex (a stop id each, to the stop it stands for), or a
 * place. The route ends at only one of them, so a trip planned again from partway along asks for them
 * all, and doesn't call a connection missed that another of the complex's stops still makes. Empty for
 * a trip kept before they were. [destinationStopId] is the stop or station (an interchange's `HUB…`
 * included) the rider picked, to open the trip list to again; blank for a place, or a trip kept
 * before it was.
 * Kept on the device only: where a rider is going is theirs (SPEC *Privacy*).
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
    val leftRide: ActiveTrip? = null,
    val onBoardSeen: Boolean = false,
    val seenAlongStop: Int = -1,
    val boardWarned: String = "",
    val disruptionsHeard: Set<String> = emptySet(),
    val disruptionsDismissed: Set<String> = emptySet(),
    val vehicleLeg: TripLeg? = null,
    val heldFrom: Instant? = null,
    val destinations: List<TripDestination> = emptyList(),
    val destinationIds: Map<String, String> = emptyMap(),
    val destinationStopId: String = "",
    // The walks that are changes on foot ([OnTheWay.changesOnFoot]), by leg index: decided once when
    // the trip starts, so its steps never change on the way. Null on a trip kept by an older build,
    // which goes by the names alone.
    val onFootChanges: Set<Int>? = null,
) {
    /** The leg the rider is on, or null once they've arrived. */
    val leg: TripLeg? get() = route.legs.getOrNull(legIndex)
}

/** Where a started trip stands, for its screen, banner and notification (SPEC *On the way*). */
sealed interface TripProgress {
    /**
     * Waiting at [leg]'s boarding stop for the train followed, due at [due] (null: none followed yet),
     * on the line named [lineName]: the leg's own, or another of its lines the train followed is on
     * ([OnTheWay.followedLineName]), which is the one the rider boards.
     */
    data class Waiting(val leg: TripLeg, val due: Instant?, val lineName: String = leg.lineName) : TripProgress

    /**
     * On [leg]'s train: next at [nextStop] (null when its name isn't known), getting off in [stopsLeft] stops (counting the stop
     * itself), expected there at [getOffAt] — null while that stop is beyond TfL's predictions, when
     * only the stops are counted from the plan. [stopsLeft] is null too when the plan's stops can't
     * be matched to the live ones (a bus leg's, [OnTheWay.checkable]). [getOffSoon] from one stop, or
     * two minutes, out. [seen] once the rider is known to be on board ([ActiveTrip.onBoardSeen]):
     * until then they're only taken to be, and the step still says to take the ride. [byPosition] when
     * the stops are counted from where the rider was seen ([OnTheWay.ridingUnmatched]), not from any
     * answer of TfL's: they don't go stale with one.
     */
    data class Riding(
        val leg: TripLeg,
        val nextStop: String?,
        val stopsLeft: Int?,
        val getOffAt: Instant?,
        val getOffSoon: Boolean,
        val seen: Boolean = true,
        val byPosition: Boolean = false,
    ) : TripProgress

    /**
     * Changing onto [leg], a ride straight after another, until about [until]: the change time the
     * Planner allows (as the route shows it), with no walk leg of its own.
     */
    data class Changing(val leg: TripLeg, val until: Instant) : TripProgress

    /**
     * On foot along [leg] (a walk to a change or the destination), until about [until]: the walk to
     * the destination can run past it, as it ends only when the rider is seen there ([walkingToEnd]).
     * [metersLeft] is how far its end is, straight, from where the rider was last seen on it; null
     * with no fix yet, or none that places them.
     */
    data class Walking(
        val leg: TripLeg,
        val until: Instant,
        val metersLeft: Double? = null,
        // [metersLeft] is from a fix taken before this walk was seen ([ActiveTripTracker]'s estimate), so
        // it reads as approximate until a fix on the walk itself places the rider.
        val estimated: Boolean = false,
    ) : TripProgress

    /**
     * The train followed can't be placed on [leg] — it doesn't call at the boarding stop or where
     * the rider gets off (the wrong train, or TfL lost it) — so another is to be picked; nothing is
     * claimed meanwhile (SPEC principle 1).
     */
    data class Lost(val leg: TripLeg) : TripProgress

    data object Arrived : TripProgress
}

/**
 * Where a station can be walked into, as TfL publishes it (SPEC *On the way*): its own [point], and
 * each of its [entrances]. Kept apart because they're weighed apart ([OnTheWay.atStation]): the point
 * can sit well inside a big station, where an entrance is the door itself.
 */
data class StationPlaces(val point: Coordinates? = null, val entrances: List<Coordinates> = emptyList())

/** What a fix saw the rider at, where a trip moved on by it ([OnTheWay.seenAtStop]): for the log. */
enum class SeenAt(val label: String) {
    /** The stop's placed point: the Planner's, or the station's own. */
    POINT("its placed point"),

    /** One of the station's entrances. */
    ENTRANCE("an entrance"),
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

    /** "Time to board" from this long before the train followed is due at the boarding stop. */
    val BOARD_SOON_TIME: Duration = Duration.ofMinutes(2)

    /**
     * How far from when the rider said they're on board ([atStep]) the train they boarded can be due,
     * either way: a train at the platform, due a moment ago or about to leave.
     */
    val ON_BOARD_GRACE: Duration = Duration.ofMinutes(1)

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
     * there" (the test itself counts its uncertainty), so one sure to 80 m isn't thrown away; the
     * left-behind check keeps [sureEnough]'s tighter bound.
     */
    fun sureEnoughFor(trip: ActiveTrip, now: Instant): (LocationFix) -> Boolean = when {
        walksToEnd(trip, now) || seesWalkEnd(trip, now) || stationRiddenTo(trip, now) != null -> ::sureEnoughToArrive
        // Just after the train left, a rider only taken to be on it is also still awaited ([watchesWait]),
        // but a vague fix then can't tell the train from the platform it just left: the tighter bound holds.
        mayBeLeftBehind(trip, now) -> ::sureEnough
        watchesWait(trip, now) -> ::sureEnoughToArrive
        // On board by where they were seen: a fix as sure as the one that put them there moves them on
        // and sees them where they get off (Codex, PR #449).
        watchesRide(trip, now) -> ::sureEnoughToArrive
        else -> ::sureEnough
    }

    private fun sureEnoughToArrive(fix: LocationFix): Boolean {
        val accuracy = fix.accuracyMeters ?: return false
        val age = fix.ageMillis ?: return false
        return accuracy <= AT_STOP_WITHIN_METERS && age <= FIX_FRESH_WITHIN_MILLIS
    }

    /**
     * The fix worth waiting for at [now], before settling for a vaguer one [sureEnoughFor] still
     * takes ([raceFix]'s accept): on a walk to a station, or nearly at the one the rider gets off at,
     * one sure enough to tell an entrance ([AT_ENTRANCE_WITHIN_METERS]). A quick fused fix too vague
     * for that mustn't end the wait for a GPS one that can see them at the door (Codex, PR #389); if
     * none comes, the vague one still settles a placed point.
     */
    fun preferredFor(trip: ActiveTrip, now: Instant): (LocationFix) -> Boolean {
        val usable = sureEnoughFor(trip, now)
        if (stationWalkedTo(trip, now) == null && stationRiddenTo(trip, now) == null) return usable
        return { fix -> usable(fix) && fix.accuracyMeters.let { it != null && it <= AT_ENTRANCE_WITHIN_METERS } }
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
     * The earliest a train can have been due at [trip]'s boarding stop and be the rider's: when they could be
     * there ([ActiveTrip.legStartedAt]), or a minute before once on board ([ON_BOARD_GRACE]): on board by
     * their word ([atStep]), the leg starts as they say it, the train at the platform about then. One due
     * before it left without them, as the trip has it.
     */
    fun boardableFrom(trip: ActiveTrip): Instant =
        if (trip.boarded) trip.legStartedAt.minus(ON_BOARD_GRACE) else trip.legStartedAt

    /**
     * The trains a ride could be followed on, soonest first: departures at its boarding stop of one of
     * its [lines], as its board lists them, that TfL names and the rider can reach by [readyAt], each
     * once. [lines] are the ride's lines the trip's cards offer ([RideLines.running]): the Planner's,
     * and another that runs between the same two stops (the Circle along the Hammersmith & City's
     * stretch, the Metropolitan past the Jubilee's stops), checked as running from stops checked open,
     * and not one the rider avoids. Which of the trains runs where the rider is going is for each
     * one's line route and calls to say, against its own line's ride ([lineOf], [mayTakeRide],
     * [runsAlong]).
     */
    fun candidates(departures: List<Departure>, lines: List<TripLeg>, readyAt: Instant): List<Departure> =
        departures.filter { train -> lineOf(lines, train) != null && train.vehicleId.isNotBlank() && !train.expectedArrival.isBefore(readyAt) }
            .sortedBy { it.expectedArrival }
            // A train's id is TfL's within its line, so two lines' trains can share one.
            .distinctBy { it.lineId to it.vehicleId }

    /**
     * The trains of [boards], the boards read for [ride] by stop id (its own stop's, and those of its
     * stop pair's other poles: [RideLines.polesToRead]), each kept only on the board of the stop it
     * may board from: a train of one of the ride's [lines] where its line boards ([TripLeg.fromId]),
     * any other train on the ride's own stop's. A line's train on another pole's board goes another
     * way (the other side of the road is its way back), so it's no train of the ride. A line boarding
     * at a stop whose board wasn't [read] keeps its trains wherever they're listed, as with one board.
     */
    fun listedFor(
        ride: TripLeg,
        boards: Map<String, List<Departure>>,
        lines: List<TripLeg>,
        read: Set<String> = boards.keys,
    ): List<Departure> =
        boards.flatMap { (stop, trains) ->
            trains.filter { train ->
                val from = lineOf(lines, train)?.fromId ?: ride.fromId
                from == stop || from !in read
            }
        }

    /** The ride as [train]'s line runs it, of the ride's [lines]; null when its line isn't one of them. */
    fun lineOf(lines: List<TripLeg>, train: Departure): TripLeg? =
        train.lineId.takeIf { it.isNotBlank() }?.let { id -> lines.firstOrNull { it.lineId == id } }

    /**
     * The ride as the rider takes it on another of the ride's lines ([ActiveTrip.vehicleLeg]): the line
     * of the train followed, or the line they're on board by where they were seen along
     * ([ridingUnmatched]); null on the Planner's own line. The one place that says which line they're
     * taking: whatever checks the ride as they take it (its stops, the line it names, its disruptions
     * and their direction) asks this, so a line ridden by position counts as one with a train told
     * (Codex, #459).
     */
    fun ridingOn(trip: ActiveTrip): TripLeg? = trip.vehicleLeg?.takeIf { trip.vehicleId.isNotBlank() || ridingUnmatched(trip) }

    /**
     * The ride as the rider takes it ([ridingOn]), the leg the train's calls are checked against: its
     * own boarding stop, stops between and stop where the rider gets off. The Planner's leg for its
     * own line, or with no train followed.
     */
    fun ridden(trip: ActiveTrip): TripLeg? = ridingOn(trip) ?: trip.leg

    /** The line TfL is asked about [trip]'s train on: the one it was followed on ([ridden]). */
    fun followedLine(trip: ActiveTrip): String = ridden(trip)?.lineId.orEmpty()

    /** The name of the line [trip]'s train is on ([followedLine]): the one the rider is told to board. */
    fun followedLineName(trip: ActiveTrip): String = ridden(trip)?.lineName.orEmpty()

    /**
     * Whether a train with [calls] ahead of it takes [leg]: it calls at the boarding stop, and later
     * where the rider gets off — not a train the other way, or to another branch. TfL predicts only
     * so far ahead ([VehicleSource]), so a train whose predictions end first takes the leg when every
     * stop predicted after boarding is on the leg's path.
     */
    fun runsAlong(leg: TripLeg, calls: List<VehicleCall>, heading: String? = null): Boolean {
        val on = calls.indexOfFirst { callsFrom(it, leg) }
        if (on < 0) return false
        return runsOn(leg, calls.drop(on + 1), heading)
    }

    /**
     * Whether a train already past [leg]'s boarding stop, with [calls] ahead of it, takes the rest of
     * the ride: as [runsAlong], from where it is. A train the rider says they're on may have just
     * left the stop, and TfL drop its call there (Codex, PR #384).
     */
    fun runsOn(leg: TripLeg, calls: List<VehicleCall>, heading: String? = null): Boolean {
        val off = calls.indexOfFirst { callsTo(it, leg) }
        if (off >= 0) return keepsToLeg(leg, calls.take(off))
        if (calls.isEmpty()) return false
        // A leg whose stops can't be checked (a bus) looks the same as a short working or another
        // branch until its calls reach the stop: meanwhile only the terminus it shows ([heading],
        // its departure's) against the Planner's tells them apart.
        if (!checkable(leg)) return heading != null && leg.headings.any { signed(it).equals(signed(heading), ignoreCase = true) }
        return keepsToLeg(leg, calls)
    }

    /**
     * Whether [leg]'s planned stops can be matched to a train's live calls: the Planner names a bus
     * leg's stops by their stop area ("490G…", a road's poles together), which the live calls (by
     * pole) never name, and TfL gives no way to tell which area a pole is in. Such a leg is checked
     * on its ends alone; a leg with no plan of its stops is too.
     */
    fun checkable(leg: TripLeg): Boolean = leg.path.isNotEmpty() && leg.path.none { it.startsWith(STOP_AREA_PREFIX) }

    // Each of [sequence]'s routes alone, under [ride]'s own ids where the Planner names a station by a
    // sibling of the one the route calls at ([LineSequence.callingAt]), as [ridePositions] places it:
    // route by route, as one route calling at the ride's id leaves the line's others as they are (Codex,
    // PR #462).
    private fun rideRoutes(ride: TripLeg, sequence: LineSequence): List<LineSequence> {
        val ids = (listOf(ride.fromId) + ride.path + ride.toId).distinct()
        return sequence.routes.map { route -> ids.fold(sequence.copy(routes = listOf(route))) { seq, id -> seq.callingAt(id) } }
    }

    /**
     * Whether every train of [ride]'s line that reaches stop [ahead] of its path the ride's way ran
     * there the way the ride does, by the line's routes ([sequence]): from the boarding stop, calling
     * at just the ride's stops between, in order. A train on that stop's board may then be the
     * rider's, where one that starts there, joins there from another branch, or reaches it by
     * another way from the boarding stop can't be, and nothing a train says of the stops ahead of it
     * tells which (Codex, PR #462). Where a route next calls at the ride's stop before that one, it is
     * running back towards them there, and that pass is passed over; any other pass counts, a loop
     * that comes round to the boarding stop later included (Codex, PR #462). False with no routes
     * known, or none that reach it.
     */
    @WorkerThread
    fun comesThroughBoarding(ride: TripLeg, ahead: Int, sequence: LineSequence?): Boolean {
        val stop = ride.path.getOrNull(ahead) ?: ride.toId
        val before = ride.path.getOrNull(ahead - 1) ?: ride.fromId
        val way = (listOf(ride.fromId) + ride.path.take(ahead + 1)).let { if (it.last() == stop) it else it + stop }
        val routes = rideRoutes(ride, sequence ?: return false).map { it.routes.single() }
        // Every way a route reaches that stop: a loop calling there twice brings trains to its board by
        // both (Codex, PR #462).
        val into = routes.flatMap { route ->
            route.stopIds.indices.filter { at ->
                route.stopIds[at] == stop && route.stopIds.getOrNull(at + 1) != before
            }.map { at -> route.stopIds to at }
        }
        return into.isNotEmpty() && into.all { (stops, at) -> at >= way.size - 1 && stops.subList(at - way.size + 1, at + 1) == way }
    }

    /**
     * The ride the rider is on their way to board: the leg they're on while it's a ride they aren't
     * yet seen on (waiting, changing onto it, or its train left with them only taken to be on it), or
     * the ride after the walk they're on. Null once seen riding, on the walk to the destination, and
     * once arrived.
     */
    fun upcomingRide(trip: ActiveTrip): TripLeg? {
        val leg = trip.leg ?: return null
        if (!leg.isWalk) return leg.takeIf { !trip.onBoardSeen }
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
        // Only taken to be on board: still at the stop, as far as anyone knows.
        is TripProgress.Riding -> trip.legStartedAt.takeIf { !trip.onBoardSeen }
        else -> null
    }

    /**
     * The lines whose routes [boardTrains] needs for [ride]'s board: those of its mode at the stop,
     * never a blank id (a departure TfL names no line for is unresolved without one, and asking for
     * a blank line's route only spends requests on an answer that can't come).
     */
    @WorkerThread
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
    @WorkerThread
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
     * [boardTrains] for [ride]'s board at every instant from [from] on, worked out once: each of its
     * trains judged on its own ([DirectTrips.judgeEach]: a train is kept or not, and its route loading
     * or unplaced, whatever else the board lists), so the trains still to come at a later instant are a suffix of them, and
     * what [BoardTrains] says of them is gathered from the end. The same answer as [boardTrains] at
     * each instant, without routing the board again for each.
     */
    @WorkerThread
    fun placeTrains(
        ride: TripLeg,
        departures: List<Departure>,
        fetchedAt: Instant,
        sequences: Map<String, LineSequence?>,
        from: Instant,
    ): PlacedTrains {
        val trains = Countdown.upcoming(departures.filter { it.mode.equals(ride.mode, ignoreCase = true) }, from)
        // The ride's ends and each line's route worked out once for the board, each train judged on them
        // (Codex on #557).
        val judged = DirectTrips.judgeEach(StopArrivals(ride.fromId, ride.fromName, trains, fetchedAt), ends(ride, sequences), sequences)
        return PlacedTrains(trains, judged.map { it.kept }, judged.map { it.pending }, judged.map { it.unresolved }, judged.map { it.miss })
    }

    /**
     * A board's trains as [placeTrains] routed them, soonest first, each with whether it's kept, its
     * route loading ([pending]) or it couldn't be checked ([unresolved]), and what couldn't be placed.
     */
    class PlacedTrains(
        private val trains: List<Departure>,
        kept: List<Boolean>,
        pending: List<Boolean>,
        unresolved: List<Boolean>,
        // Each train's, kept as they are: a set for every suffix would be quadratic (Codex on #557).
        private val misses: List<RouteMiss?>,
    ) {
        // The kept trains, soonest first, and how many of them come before each train of the board.
        private val keptTrains = trains.filterIndexed { i, _ -> kept[i] }
        private val keptBefore = IntArray(trains.size + 1).also { counts ->
            for (i in trains.indices) counts[i + 1] = counts[i] + if (kept[i]) 1 else 0
        }

        // From the end: whether any train from each on is pending or unresolved, and their misses.
        private val pendingFrom = BooleanArray(trains.size + 1).also { from ->
            for (i in trains.indices.reversed()) from[i] = pending[i] || from[i + 1]
        }
        private val unresolvedFrom = BooleanArray(trains.size + 1).also { from ->
            for (i in trains.indices.reversed()) from[i] = unresolved[i] || from[i + 1]
        }

        /** The instants something said of the board changes: each train's that's kept, loading or unplaced. */
        val changes: List<Instant> = trains.filterIndexed { i, _ -> kept[i] || pending[i] || unresolved[i] }.map { it.expectedArrival }

        /** [boardTrains] at [now]: the trains not yet gone from it on, as the board routed them. */
        @WorkerThread
        fun at(now: Instant): BoardTrains = trainsAt(now).copy(misses = missesAt(now))

        /** [at] without its [BoardTrains.misses], in constant time past the search: for every instant of a timeline. */
        @WorkerThread
        fun trainsAt(now: Instant): BoardTrains {
            val low = firstNotGone(now)
            return BoardTrains(keptTrains.subList(keptBefore[low], keptTrains.size), pendingFrom[low], unresolvedFrom[low])
        }

        /** [at]'s [BoardTrains.misses]: what couldn't be placed of the trains not yet gone at [now]. */
        @WorkerThread
        fun missesAt(now: Instant): Set<RouteMiss> =
            misses.subList(firstNotGone(now), misses.size).filterNotNullTo(LinkedHashSet())

        // The first train not yet gone at [now]: the board is soonest first, so every one after it is too.
        private fun firstNotGone(now: Instant): Int {
            var low = 0
            var high = trains.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (Countdown.hasDeparted(trains[mid], now)) low = mid + 1 else high = mid
            }
            return low
        }
    }

    /**
     * Of [departures] at [ride]'s boarding stop, gone or still to come, those whose line's route
     * ([sequences], by line) takes them where the rider gets off: not another branch's, as on the
     * ride's board ([boardTrains]). A line whose route isn't among [sequences] keeps none.
     */
    @WorkerThread
    fun takesRide(ride: TripLeg, departures: List<Departure>, sequences: Map<String, LineSequence?>): List<Departure> =
        routed(ride, departures, Instant.EPOCH, sequences).stops.firstOrNull()?.departures.orEmpty()

    /**
     * Of [trains] at [ride]'s boarding stop, in order, those its line's route ([sequences], by line)
     * doesn't rule out: the ones it takes where the rider gets off ([takesRide]), and any it can't
     * say of (the route loading or failed, or the train's way not placed on it), whose own calls
     * then decide ([runsAlong]). A train the route sends another way, or to another branch, is left
     * out, so the few a pick asks after ([candidates]) are ones that may be the rider's: at a fork
     * the first few can all turn off.
     */
    @WorkerThread
    fun mayTakeRide(ride: TripLeg, trains: List<Departure>, sequences: Map<String, LineSequence?>): List<Departure> =
        trains.filter { train ->
            val result = routed(ride, listOf(train), Instant.EPOCH, sequences)
            result.pending || result.unresolved || result.stops.firstOrNull()?.departures.orEmpty().isNotEmpty()
        }

    private fun routed(ride: TripLeg, departures: List<Departure>, fetchedAt: Instant, sequences: Map<String, LineSequence?>) =
        DirectTrips.filter(listOf(StopArrivals(ride.fromId, ride.fromName, departures, fetchedAt)), ends(ride, sequences), sequences)

    /**
     * [trip] following [train] on its current leg, not yet on board: on [on], the ride as the train's
     * line runs it ([lineOf]), where that's another of the ride's lines than the Planner's.
     */
    fun follow(trip: ActiveTrip, train: Departure, on: TripLeg? = null): ActiveTrip =
        // On board already by the rider's word ([atStep]), they're on this train: they stay so.
        trip.copy(
            vehicleId = train.vehicleId, vehicleLeg = on?.takeIf { it != trip.leg }, vehicleOffId = "", boardsAt = train.expectedArrival, boarded = trip.boarded,
            boardedAt = trip.boardedAt.takeIf { trip.boarded }, dueOffAt = null, heldFrom = null,
        )

    /**
     * [trip] brought up to date at [now] from its followed train's [calls] (null when they couldn't
     * be fetched, or no train is followed): the trip as it now stands — moved on to the next leg once
     * the rider has got off, or a walk has had its time — and where it stands. Moves at most as far
     * as the calls show; a leg reached here waits for its own train to be picked. The calls are a
     * prediction window ([VehicleSource]): the stop missing from them is not yet predicted, not
     * passed, while the train's next stop is still on the leg; and an empty list ends a ride only once
     * the rider was seen due off.
     */
    fun advance(trip: ActiveTrip, calls: List<VehicleCall>?, now: Instant, atBoarding: Boolean = false): Pair<ActiveTrip, TripProgress> {
        // Checked against the ride as the followed train's line runs it ([ridden]).
        val leg = ridden(trip)
        // On board by the rider's word while the train still stands at the boarding stop ([atStep]):
        // its call there is behind them, so it's neither the next stop nor one left to count, and nor
        // is any before it TfL is late to drop (Codex, PR #384). The call is the one on the lap they
        // boarded ([boardedCall]): with none, nothing is dropped, and a call there on the next lap,
        // after they were seen due off, still says they got off.
        var on = trip
        val ahead = if (leg != null && !leg.isWalk && trip.boarded && calls != null) {
            val boarding = boardedCall(trip, leg, calls, now)
            if (boarding >= 0) {
                // Kept up to date while it stands there, as a train still to come is: one held at the
                // platform stays the call they boarded at however late it runs, where the next lap
                // comes a lap on (Codex, PR #384). Still there, it isn't due off yet, however soon a
                // short ride's stop was last seen due: that's seen afresh from what follows the call,
                // not taken for the train having got there (Codex, PR #384).
                on = trip.copy(boardsAt = calls[boarding].expected, dueOffAt = null)
                calls.drop(boarding + 1).dropWhile { callsFrom(it, leg) }
            } else {
                calls
            }
        } else {
            calls
        }
        return advanceAlong(on, ahead, now, atBoarding)
    }

    /**
     * Whether [rider] is seen still at the stop [trip]'s ride boards at, sure enough to tell
     * ([usableFix]) and within [MISSED_WITHIN_METERS] of where the Planner places it: the train's call
     * there is then still theirs to board, however late it has come to run ([ActiveTrip.heldFrom]).
     * A loop's next lap would have taken them away, so a call that jumps a lap's worth later is the
     * same one, late: a train held on its way round to the rider. Only the fixes a trip already
     * takes (while waiting) are used: none is asked for this.
     */
    fun atBoarding(trip: ActiveTrip, rider: LocationFix?): Boolean {
        val leg = trip.leg ?: return false
        if (leg.isWalk) return false
        val at = leg.fromAt ?: return false
        val fix = rider?.let(::usableFix) ?: return false
        return near(fix, at, MISSED_WITHIN_METERS)
    }

    private fun advanceAlong(trip: ActiveTrip, calls: List<VehicleCall>?, now: Instant, atBoarding: Boolean): Pair<ActiveTrip, TripProgress> {
        val leg = trip.leg ?: return trip to TripProgress.Arrived
        if (leg.isWalk) {
            val until = trip.legStartedAt.plus(leg.run)
            // A walk within one place is no step of its own: straight on to boarding the ride after it.
            if (changesOnFoot(trip, trip.legIndex)) return advanceAlong(pastChange(trip, trip.legStartedAt), calls, now, atBoarding)
            // The walk to the destination ends when the rider is seen there ([seen]), not on its time,
            // up to [END_WALK_GRACE] past it (maintainer, 2026-10-03).
            return if (now.isBefore(until) || walksToEnd(trip, now)) trip to TripProgress.Walking(leg, until) else nextLeg(trip, now)
        }
        changeUntil(trip, now)?.let { return trip to TripProgress.Changing(leg, it) }
        if (trip.vehicleId.isBlank() || calls == null) {
            ridingAlong(trip, leg)?.let { return trip to it }
            return trip to if (trip.boarded) TripProgress.Lost(leg) else TripProgress.Waiting(leg, null)
        }
        // The calls are checked against the ride as the followed train's line runs it ([ridden]): its
        // own stops, where another of the ride's lines takes it by other stops between. The progress
        // is the leg's, the step the rider is on.
        val on = ridden(trip) ?: leg
        // The train is still to come while it calls at the boarding stop about when it was due there
        // ([ActiveTrip.boardsAt], kept up to date as it runs late): a call there a lap later is a
        // loop coming round again, after the rider's ride. Once on board, the rider stays on it
        // whatever it calls at next.
        if (!trip.boarded) {
            val (boarding, heldFrom) = upcomingBoarding(trip, on, calls, now, atBoarding)
            // A train due at the boarding stop before the rider can be there (revised earlier) isn't
            // one they can catch, still due or gone: another is to be picked.
            val due = if (boarding >= 0) calls[boarding].expected else trip.boardsAt
            if (due != null && due.isBefore(trip.legStartedAt)) return trip to TripProgress.Lost(leg)
            if (boarding >= 0) {
                // Its calls from there leaving the leg before the stop (a diversion, a short working):
                // not a train the rider can take.
                val ahead = calls.drop(boarding + 1)
                val off = ahead.indexOfFirst { arrivesAt(trip, on, it) }
                if (!keepsToLeg(on, if (off >= 0) ahead.take(off) else ahead)) return trip to TripProgress.Lost(leg)
                return trip.copy(boardsAt = due, heldFrom = heldFrom) to TripProgress.Waiting(leg, due, followedLineName(trip))
            }
        }
        val off = calls.indexOfFirst { arrivesAt(trip, on, it) }
        // The stop again a lap later, after the rider was seen due there by now: they got off. A lap
        // is told from a delay by order, not time: the next lap reaches the stop only after coming
        // round through the boarding stop again, where a held train still has only the leg ahead.
        if (off >= 0 && seenPast(trip, now) && calls.take(off).any { callsFrom(it, on) }) {
            return nextLeg(trip, now)
        }
        if (off < 0) {
            val next = calls.firstOrNull()
            if (next != null && !checkable(on) && !seenPast(trip, now)) {
                // A bus with its stop beyond the predictions: on it, but its stops left can't be counted.
                return trip.copy(boarded = true, boardedAt = boardedSince(trip, now)) to TripProgress.Riding(leg, next.stopName, null, null, false, trip.onBoardSeen)
            }
            val along = next?.let { onPath(on, it) } ?: -1
            if (along >= 0) {
                // Its predictions leave the leg before reaching the stop: a diversion, not the ride.
                if (!keepsToLeg(on, calls)) return trip to TripProgress.Lost(leg)
                // Still on the leg, with the stop beyond the predictions: count the stops, claim no time.
                val stopsLeft = (on.path.indexOf(on.toId).takeIf { it >= 0 } ?: on.path.lastIndex) - along + 1
                val soon = stopsLeft <= GET_OFF_SOON_STOPS
                return trip.copy(boarded = true, boardedAt = boardedSince(trip, now)) to
                TripProgress.Riding(leg, next!!.stopName, stopsLeft, null, soon, trip.onBoardSeen)
            }
            // The train's calls have left the leg (or TfL has none left). The rider got off only if
            // they were on it and it was seen due at their stop by now — a train that passes it runs
            // a little early at most. Otherwise it was never theirs, turned off the leg (a diversion,
            // another branch), or TfL stopped predicting it early: nothing is claimed.
            return if (seenPast(trip, now)) nextLeg(trip, now) else trip to TripProgress.Lost(leg)
        }
        // Calls off the leg before the stop: a diversion or another branch, not the rider's ride.
        if (!keepsToLeg(on, calls.take(off))) return trip to TripProgress.Lost(leg)
        val getOffAt = calls[off].expected
        val stopsLeft = off + 1
        val soon = stopsLeft <= GET_OFF_SOON_STOPS || !now.plus(GET_OFF_SOON_TIME).isBefore(getOffAt)
        val riding = trip.copy(boarded = true, boardedAt = boardedSince(trip, now), dueOffAt = getOffAt)
        return riding to TripProgress.Riding(leg, calls.first().stopName, stopsLeft, getOffAt, soon, trip.onBoardSeen)
    }

    // Whether [call] is where [trip]'s rider gets off [leg]: the stop the Planner named, or the other
    // pole of its pair that the followed train was found calling at ([ActiveTrip.vehicleOffId]).
    private fun arrivesAt(trip: ActiveTrip, leg: TripLeg, call: VehicleCall): Boolean =
        callsTo(call, leg) || (trip.vehicleOffId.isNotEmpty() && call.stopId == trip.vehicleOffId)

    /**
     * When the change onto [trip]'s ride ends, while the rider is still making it: a ride straight
     * after another, or after a walk within one place from another ride ([changesOnFoot]), not yet
     * boarded, before the change time the Planner allows (and that walk's time) is up. Null
     * otherwise. The skipped walk counts so no train is followed before the rider can reach it.
     */
    fun changeUntil(trip: ActiveTrip, now: Instant): Instant? {
        val leg = trip.leg ?: return null
        val before = trip.route.legs.getOrNull(trip.legIndex - 1) ?: return null
        val afterRide = !before.isWalk || changesOnFoot(trip, trip.legIndex - 1)
        if (leg.isWalk || !afterRide || trip.boarded || !now.isBefore(trip.legStartedAt)) return null
        return trip.legStartedAt
    }

    // A bus blind's place, however it was cleaned: the live feed turns "X Bus Station" into "X Bus"
    // ([cleanStopName] drops only "Station"), the Planner's heading into "X". Compared, so without a
    // line qualifier ([matchStopName]).
    private fun signed(name: String): String {
        val clean = matchStopName(name)
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
        // On the walk to the destination until seen there (maintainer, 2026-10-03): a fix about every
        // refresh for the walk's length, and up to [END_WALK_GRACE] after it.
        if (walksToEnd(trip, now)) return true
        if (seesWalkEnd(trip, now) || stationRiddenTo(trip, now) != null || watchesWait(trip, now)) return true
        if (watchesRide(trip, now)) return true
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

    /**
     * When [trip]'s rider gets where they're going, as its screen shows it with the time left
     * (maintainer, 2026-10-01): [arrival], and [live] when it's TfL's prediction for where they get off
     * the ride they're on, with only walks after. Otherwise it's estimated: from the train's time (due at
     * the boarding stop, the Planner's time on board after), or the end of a walk or change, with the
     * Planner's times for the legs and changes still ahead. A rider still to board whose train followed
     * is past its time, or not yet found or lost, is timed from the next one the board lists that they
     * can catch ([nextDue]), rather than shown none (maintainer, 2026-10-03). Null with no train to time
     * a ride from (none on the board either, or not yet told for a rider on board by where they were
     * seen), with where they get off beyond TfL's predictions, once arrived, or with no leg.
     */
    data class Eta(val arrival: Instant, val live: Boolean)

    /**
     * [trip]'s [Eta] at [now], from where it stands ([progress]); [nextDue] is when the next train the
     * rider can catch is due at the ride's boarding stop, as its board lists it ([nextDue]), for a rider
     * still to board whose train followed has gone by or isn't known.
     */
    @WorkerThread
    fun eta(trip: ActiveTrip, progress: TripProgress?, now: Instant, nextDue: Instant? = null): Eta? {
        // [etaFrom]'s cheap exits first, before [etaTail] goes over the route (Codex, #526).
        if (progress == TripProgress.Arrived || ridingUnmatched(trip)) return null
        val anchor = etaSource(trip, progress, now, nextDue)?.first ?: return null
        if (anchor.isBefore(now)) return null
        return etaFrom(trip, progress, now, etaTail(trip) ?: return null, nextDue)
    }

    /**
     * What follows [trip]'s current leg to the end ([rest]: the legs ahead and the changes between them)
     * and whether that's walks only with no change ([walksOnly]), which an arrival can stay live through;
     * null with no current leg. It goes over the route's legs, so it's worked out off the main thread and
     * held while the trip's at the same leg; [etaFrom] then times it from progress at once.
     */
    class EtaTail(val rest: Duration, val walksOnly: Boolean)

    /** [EtaTail] for [trip] at its current leg: over the route's legs, so off the main thread. */
    @WorkerThread
    fun etaTail(trip: ActiveTrip): EtaTail? {
        val leg = trip.leg ?: return null
        val ahead = trip.route.legs.drop(trip.legIndex + 1)
        // Changes only between legs: the last leg's is to no next leg, as the route leaves it out (Codex, PR #449).
        val changes = (listOf(leg) + ahead).dropLast(1).fold(Duration.ZERO) { sum, it -> sum.plus(it.changeAfter) }
        val rest = ahead.fold(changes) { sum, next -> sum.plus(next.run) }
        return EtaTail(rest, ahead.all { it.isWalk } && changes.isZero)
    }

    /**
     * [trip]'s [Eta] at [now] from where it stands ([progress]) and what follows its current leg ([tail],
     * from [etaTail] for the same leg), without going over the route: a held [tail] times the latest
     * progress at once.
     */
    fun etaFrom(trip: ActiveTrip, progress: TripProgress?, now: Instant, tail: EtaTail, nextDue: Instant? = null): Eta? {
        if (progress == TripProgress.Arrived) return null
        // On board by where they were seen, with no train: counted stops only, which stand still between
        // fixes, so a time from them would slide later as the clock runs (Codex, PR #449).
        if (ridingUnmatched(trip)) return null
        val (anchor, after, live) = etaSource(trip, progress, now, nextDue) ?: return null
        // Gone by with nothing newer (a train still listed past its time, a walk running long): no
        // arrival, rather than one lifted to now that slides later as the clock runs, live or not
        // (Codex, PR #449).
        if (anchor.isBefore(now)) return null
        // A change still ahead is the Planner's time, as a ride is: estimated (Codex, PR #449).
        return Eta(anchor.plus(after).plus(tail.rest), live && tail.walksOnly)
    }

    /**
     * What [eta] times [trip] from at [now] (its source: TfL's time where the rider gets off, the
     * train's at the boarding stop, or the end of a change or walk), what follows it on the current leg,
     * and whether it's live; null with none. An arrival worked out earlier stands only while its source
     * is still to come.
     */
    fun etaSource(trip: ActiveTrip, progress: TripProgress?, now: Instant, nextDue: Instant? = null): Triple<Instant, Duration, Boolean>? {
        val leg = trip.leg ?: return null
        return when (progress) {
            // Its stop beyond TfL's predictions: no time for it, as the step claims none (maintainer,
            // 2026-09-29), rather than one counted from now that slides later between stops (Codex, PR #449).
            is TripProgress.Riding -> Triple(progress.getOffAt ?: return null, Duration.ZERO, true)
            // The train followed, or once it's gone by (TfL can list it past its time while it's at the
            // stop, or after it's left without a word) or with none yet, the board's next one the rider
            // can catch: a prediction of its own, not one sliding later as the clock runs (Codex, PR #449).
            // None on the board either: no arrival.
            is TripProgress.Waiting -> Triple(progress.due?.takeIf { !it.isBefore(now) } ?: upcoming(nextDue, now) ?: return null, leg.run, false)
            // Its train lost before boarding: another is to be picked, the board's next the soonest it can be.
            is TripProgress.Lost if !trip.boarded -> Triple(upcoming(nextDue, now) ?: return null, leg.run, false)
            is TripProgress.Changing -> Triple(progress.until, leg.run, false)
            is TripProgress.Walking -> Triple(progress.until, Duration.ZERO, false)
            else -> null
        }
    }

    // [at] when it's still to come at [now].
    private fun upcoming(at: Instant?, now: Instant): Instant? = at?.takeIf { !it.isBefore(now) }

    /**
     * When the next of [trains] (a ride's board, kept to those taking the rider where they get off) is
     * due that the rider can catch: at or after [readyAt], when they can be at the boarding stop, and
     * [now]. Null with none. For [eta], once the train followed has gone by or isn't known.
     */
    fun nextDue(trains: List<Departure>, readyAt: Instant?, now: Instant): Instant? {
        val from = if (readyAt != null && readyAt.isAfter(now)) readyAt else now
        return trains.filter { !it.expectedArrival.isBefore(from) }.minOfOrNull { it.expectedArrival }
    }

    /**
     * Whether a fix is asked for [trip] at [now] to follow a rider on board by where they were seen
     * ([ridingUnmatched]): where they are is all that counts their stops, and sees them where they get
     * off. For the ride's planned time from when they were seen on, and [AT_GET_OFF_AFTER] more for a
     * slow train; past it none is (battery), and Next says they're off.
     */
    fun watchesRide(trip: ActiveTrip, now: Instant): Boolean {
        val leg = trip.leg ?: return false
        val from = trip.boardedAt ?: return false
        // A clock set back to before they were seen on ends it, rather than running it again (Codex, PR #449).
        return ridingUnmatched(trip) && !now.isBefore(from) && now.isBefore(from.plus(leg.run).plus(AT_GET_OFF_AFTER))
    }

    /** Whether a fix is asked for [trip] at [now] to see the rider already on their way ([seenAlong]). */
    fun watchesWait(trip: ActiveTrip, now: Instant): Boolean {
        val from = trip.waitFrom ?: trip.legStartedAt
        // A wait that began after now is a clock set back: its ten minutes can't be counted from it,
        // so they're taken as over rather than run again from the earlier time (Codex, PR #383).
        return waitingToBoard(trip, now) != null && !now.isBefore(from) && now.isBefore(from.plus(WAITING_WINDOW))
    }

    /**
     * The ride [trip]'s rider is still to board at [now]: its leg, not yet seen on board (its train
     * may have left, the rider only taken to be on it: maintainer, 2026-09-29), its change (if any)
     * done. A fix then can see them already on their way along it ([seenAlong]); null otherwise.
     */
    fun waitingToBoard(trip: ActiveTrip, now: Instant): TripLeg? {
        val leg = trip.leg ?: return null
        return leg.takeIf { !leg.isWalk && !trip.onBoardSeen && changeUntil(trip, now) == null }
    }

    /**
     * Each stop of [leg]'s ride placed from its line's route ([sequence]): its boarding stop, the
     * stops along its path and where it gets off, by id. A stop area the Planner names ("490G…", a
     * road's poles together) is placed at the middle of its poles; the boarding and alighting stops
     * fall back on the Planner's own points. A stop the route doesn't place is left out.
     */
    @WorkerThread
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
     * it. Null when they aren't seen along it, or the boarding stop isn't placed. [on] is the ride as
     * one of its lines runs it ([RideLines]), with [positions] its own stops': another line can take the
     * ride by other stops between, and the rider is seen along the way its trains go.
     */
    fun seenAlong(trip: ActiveTrip, rider: LocationFix, positions: Map<String, Coordinates>, now: Instant, on: TripLeg? = null): Along? {
        // On board by where they were seen, the line they were seen along ([ridden]): its own path.
        val waiting = waitingToBoard(trip, now) ?: ridden(trip)?.takeIf { ridingUnmatched(trip) } ?: return null
        val leg = on ?: waiting
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
     * [trip] on board its ride once the rider is seen [along] it, with no train found that is theirs
     * (maintainer, 2026-10-01: seen at the next stop, they're on): on board by where they were seen,
     * the train followed let go (it may be a later one, still to come), and the stops counted from
     * there ([ridingAlong]) until a train is found that is ([boardedOn]). Seen further on since, they
     * stay where they were seen furthest: a train doesn't go back. [on] is the ride as the line they
     * were seen along runs it ([RideLines]): another of the ride's lines is kept as the line ridden
     * ([ActiveTrip.vehicleLeg]), as a train told on it is, and its stops counted on its own path
     * (Codex, PR #449). Seen along another line than the one they're counted on, they're counted on
     * that one from then: its stops aren't the other's to compare.
     */
    fun onBoardAlong(trip: ActiveTrip, along: Along, now: Instant, on: TripLeg? = null): ActiveTrip {
        val ahead = ahead(along)
        val line = on?.takeIf { it != trip.leg }
        return if (ridingUnmatched(trip) && line == trip.vehicleLeg) {
            trip.copy(seenAlongStop = maxOf(trip.seenAlongStop, ahead))
        } else {
            trip.copy(
                vehicleId = "", vehicleLeg = line, vehicleOffId = "", boardsAt = null, boarded = true,
                // Already on board by where they were seen, since then: the ride's time runs from there.
                boardedAt = trip.boardedAt.takeIf { ridingUnmatched(trip) } ?: now,
                dueOffAt = null, heldFrom = null, onBoardSeen = true, seenAlongStop = ahead,
            )
        }
    }

    /**
     * The next stop of the ride's path ahead of a rider seen at [along]: seen at a stop, the train is
     * there or just leaving it, so the stop after.
     */
    fun ahead(along: Along): Int = if (along.atStop) along.from + 1 else along.from

    /** Whether [trip]'s rider is on board its ride by where they were seen, no train yet known to be theirs. */
    fun ridingUnmatched(trip: ActiveTrip): Boolean {
        val leg = trip.leg ?: return false
        return !leg.isWalk && trip.onBoardSeen && trip.vehicleId.isBlank() && trip.seenAlongStop >= 0
    }

    // On [leg] by where the rider was last seen ([ActiveTrip.seenAlongStop]), with no train's calls:
    // its next stop and the stops left from there, counted on the path of the line they were seen along
    // ([ridden]: the plan's, or another of the ride's lines), and no time claimed. A leg whose stops
    // can't be counted (a bus's) names only the stop. Null when not on board so.
    private fun ridingAlong(trip: ActiveTrip, leg: TripLeg): TripProgress.Riding? {
        if (!ridingUnmatched(trip)) return null
        val from = trip.seenAlongStop
        val on = ridden(trip) ?: leg
        // A stop not named is left unnamed, not called by where they get off unless it's that one (Codex, PR #449).
        val next = on.pathNames.getOrNull(from)?.takeIf { it.isNotBlank() } ?: on.toName.takeIf { on.path.getOrNull(from) == on.toId }
        val stopsLeft = if (checkable(on)) {
            ((on.path.indexOf(on.toId).takeIf { it >= 0 } ?: on.path.lastIndex) - from + 1).coerceAtLeast(1)
        } else {
            null
        }
        val soon = stopsLeft != null && stopsLeft <= GET_OFF_SOON_STOPS
        return TripProgress.Riding(leg, next, stopsLeft, null, soon, seen = true, byPosition = true)
    }

    /**
     * [trip] on board [train] with its [calls] ahead, when it's the train the rider was seen on
     * ([seenAlong], at stop [from] of the ride's path when [atStop], else short of it): it has left
     * the boarding stop (its calls don't reach it), and its calls from there place it on the ride at
     * or beyond that stop, due there about now if the rider was seen at it, whichever
     * train the trip was following (the maintainer's rule: switch when seen). [on] is the ride as the
     * train's line runs it ([RideLines]), the stops its calls are placed against, with [from] along
     * its path ([seenAlong] of the same); the caller offers only trains of the ride's lines that run
     * where the rider gets off ([takesRide]). Null when it isn't.
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
        on: TripLeg? = null,
    ): Pair<ActiveTrip, TripProgress>? {
        val planned = trip.leg ?: return null
        val leg = on ?: planned
        val off = (placed(leg, calls, from, now, boardingPoles, alightingPoles, areas, atStop) as? Placed.At ?: return null).off
        val left = train.expectedArrival.takeIf { !it.isAfter(now) } ?: now
        // Kept with the pole it calls at for the rider's stop, where that's the pair's other one, so
        // it's known there as their stop ([arrivesAt]).
        val offId = calls.getOrNull(off)?.takeIf { !callsTo(it, leg) }?.stopId.orEmpty()
        val aboard = trip.copy(
            vehicleId = train.vehicleId, vehicleLeg = leg.takeIf { it != planned }, vehicleOffId = offId,
            boardsAt = train.expectedArrival, boarded = true, boardedAt = left, dueOffAt = null, onBoardSeen = true, seenAlongStop = -1, heldFrom = null,
        )
        return advance(aboard, calls, now).takeIf { it.second is TripProgress.Riding }
    }

    /**
     * Whether a train's [calls] show it behind the rider [boardedOn] tests it against, by the same
     * rules: still to leave the boarding stop, calling next behind where they were seen, or, seen at a
     * stop, still due there. A train its calls don't place (none, or none on the ride) isn't behind,
     * nor known not to be.
     */
    fun behind(
        trip: ActiveTrip,
        calls: List<VehicleCall>,
        from: Int,
        now: Instant,
        boardingPoles: Set<String> = emptySet(),
        alightingPoles: Set<String> = emptySet(),
        areas: Map<String, String> = emptyMap(),
        atStop: Boolean = false,
        on: TripLeg? = null,
    ): Boolean {
        val leg = on ?: trip.leg ?: return false
        return placed(leg, calls, from, now, boardingPoles, alightingPoles, areas, atStop) == Placed.Behind
    }

    /**
     * Where along the ride a train [boardedOn] takes for the rider's calls next, by the same rules: the
     * position on the ride's path (as [on] runs it) of the stop its [calls] reach next, the path's
     * length for where the rider gets off, -1 for a bus's stop not placed before the first. Two trains
     * between the same two stops call next at the same one, so this tells them apart no more than their
     * calls do. Null when [boardedOn] wouldn't take it.
     */
    fun nextAlong(
        trip: ActiveTrip,
        calls: List<VehicleCall>,
        from: Int,
        now: Instant,
        boardingPoles: Set<String> = emptySet(),
        alightingPoles: Set<String> = emptySet(),
        areas: Map<String, String> = emptyMap(),
        atStop: Boolean = false,
        on: TripLeg? = null,
    ): Int? {
        val leg = on ?: trip.leg ?: return null
        return (placed(leg, calls, from, now, boardingPoles, alightingPoles, areas, atStop) as? Placed.At)?.at
    }

    /** How a train taken for the rider's stands to an older one of its line that left ([twinOf]). */
    enum class Twin { APART, SAME, UNKNOWN }

    /**
     * Whether an older train of its line that left the boarding stop, with [calls], may be at the
     * same spot as the train taken for the rider's, which calls next at [next] ([nextAlong]) on the ride
     * as [on] runs it: a later train that has caught up to between the same two stops calls next where
     * theirs does, so neither can be told for theirs (TODO, *Two trains between the same two stops*).
     * APART only when its calls prove it ahead of them: none of them on the ride (past where the rider
     * gets off), or its next call further on, where every way the line's routes ([sequence]) run there
     * from the boarding stop calls at [next]'s stop first, so it has passed that stop. SAME calling next
     * at the same stop. UNKNOWN otherwise: no calls (in a race with TfL's predictions), a next call it
     * doesn't place, one before [next]'s, or one further on that a faster train skipping [next]'s stop
     * calls next while still short of it (Codex, PR #465).
     */
    fun twinOf(
        trip: ActiveTrip,
        next: Int,
        calls: List<VehicleCall>,
        from: Int,
        now: Instant,
        sequence: LineSequence?,
        boardingPoles: Set<String> = emptySet(),
        alightingPoles: Set<String> = emptySet(),
        areas: Map<String, String> = emptyMap(),
        on: TripLeg? = null,
    ): Twin {
        val leg = on ?: trip.leg ?: return Twin.UNKNOWN
        if (calls.isEmpty()) return Twin.UNKNOWN
        val onRide = calls.any { call ->
            callsTo(call, leg) || call.stopId in alightingPoles || onPath(leg, call) >= 0 ||
                areas[call.stopId]?.let { it in leg.path || it == leg.toArea } == true
        }
        if (!onRide) return Twin.APART
        val older = nextAlong(trip, calls, from, now, boardingPoles, alightingPoles, areas, atStop = false, on = leg) ?: return Twin.UNKNOWN
        return when {
            older == next -> Twin.SAME
            older > next && passes(leg, sequence, next, older) -> Twin.APART
            else -> Twin.UNKNOWN
        }
    }

    /**
     * Whether every way [sequence]'s routes run [ride]'s line from its boarding stop to its stop [to] (a
     * position on its path, its length for where the rider gets off) calls at its stop [at] between, so a
     * train calling next at [to] has passed [at]'s stop. A route that skips it (a fast service), or none
     * running that way, leaves that unknown. A road's poles count as their stop pair ([LineSequence.stopAreas]).
     */
    @WorkerThread
    fun passes(ride: TripLeg, sequence: LineSequence?, at: Int, to: Int): Boolean {
        if (sequence == null || at < 0 || at >= to || at >= ride.path.size) return false
        fun stopAt(i: Int) = if (i >= ride.path.size) ride.toId else ride.path[i]
        // Route by route under the ride's ids ([rideRoutes]): a fast route naming a stop by a sibling id is
        // still a way that skips it, not one left out (Codex, PR #465). Every way from each pass of the
        // boarding stop: a loop's second time round can skip the stop its first calls at (Codex, PR #465).
        val ways = rideRoutes(ride, sequence).flatMap { seen ->
            fun isStop(id: String, stop: String) =
                id == stop || seen.stopAreas[id] == stop || (stop == ride.toId && ride.toArea.isNotEmpty() && seen.stopAreas[id] == ride.toArea)
            fun boards(id: String) = id == ride.fromId || (ride.fromArea.isNotEmpty() && seen.stopAreas[id] == ride.fromArea)
            val ids = seen.routes.single().stopIds
            ids.indices.filter { boards(ids[it]) }.mapNotNull { start ->
                val end = (start + 1 until ids.size).firstOrNull { isStop(ids[it], stopAt(to)) } ?: return@mapNotNull null
                ids.subList(start + 1, end).any { isStop(it, stopAt(at)) }
            }
        }
        return ways.isNotEmpty() && ways.all { it }
    }

    // Where a train's calls place it against a rider seen at or short of stop [from] of [leg]'s path:
    // at or past them (with its call where they get off, -1 for none, and where along the path it
    // calls next), behind them, or, null, not placed at all.
    private sealed interface Placed {
        data class At(val off: Int, val at: Int) : Placed
        data object Behind : Placed
    }

    private fun placed(
        leg: TripLeg,
        calls: List<VehicleCall>,
        from: Int,
        now: Instant,
        boardingPoles: Set<String>,
        alightingPoles: Set<String>,
        areas: Map<String, String>,
        atStop: Boolean,
    ): Placed? {
        val next = calls.firstOrNull() ?: return null
        val off = calls.indexOfFirst { callsTo(it, leg) || it.stopId in alightingPoles }
        // Still to call at the boarding stop on its way there: not yet left it, so not the rider's.
        if ((if (off >= 0) calls.take(off) else calls).any { callsFrom(it, leg) || it.stopId in boardingPoles }) return Placed.Behind
        val at = if (checkable(leg)) {
            // Where it calls next along the ride: where the rider gets off, when the path leaves that out.
            val at = onPath(leg, next).takeIf { it >= 0 } ?: if (callsTo(next, leg)) leg.path.size else return null
            if (at < from) return Placed.Behind
            at
        } else {
            // A leg whose stops can't be matched to the live ones by id (a bus's) is taken on reaching
            // where the rider gets off: any bus of the mode would pass the rest.
            if (off < 0) return null
            // Where it calls next along the ride, its pole placed by stop area ([areas]): behind where
            // the rider was seen, it's a later bus, not theirs. Once they were seen past the first
            // stop, one that can't be placed can't be told from a later one (Codex, PR #383).
            val at = if (off == 0) leg.path.size else areas[next.stopId]?.let { leg.path.indexOf(it) } ?: -1
            if (from > 0 && at < 0) return null
            if (from > 0 && at < from) return Placed.Behind
            at
        }
        // Seen at that stop, a train still due there later is on its way to it, behind them.
        if (atStop && at == from && next.expected.isAfter(now.plus(AT_STOP_DUE_WITHIN))) return Placed.Behind
        return Placed.At(off, at)
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
    // Not one already seen on board ([ActiveTrip.onBoardSeen]), whether still counted by where they were
    // seen or since put on their train: they weren't, and its tighter bound would throw away the fixes
    // that move them on, as its window would ask for fixes for nothing (Codex, PR #449).
    private fun mayBeLeftBehind(trip: ActiveTrip, now: Instant): Boolean {
        val leg = trip.leg ?: return false
        val boardedAt = trip.boardedAt ?: return false
        return leg.mode in LEFT_BEHIND_MODES && leg.fromAt != null && trip.boarded && !trip.onBoardSeen &&
            now.isBefore(boardedAt.plus(MISSED_WINDOW))
    }

    /**
     * A rider this close to the stop they're walking to (a fix's uncertainty included) is there:
     * a station's published position can sit well inside it, away from the entrance they stand at.
     * 150 m told a rider still outside a station they'd arrived (maintainer, 2026-09-29): its
     * entrances ([AT_ENTRANCE_WITHIN_METERS]) now reach the edges of a big one.
     */
    const val AT_STOP_WITHIN_METERS = 100.0

    /**
     * How near a walk's two ends between two rides are for it to be a change on foot rather than a
     * step ([changesOnFoot]): two arrival radii ([AT_STOP_WITHIN_METERS]), so the areas that count as
     * at either end overlap or meet, and the rider's location can't follow the walk from one to the
     * other (maintainer, 2026-10-03).
     */
    const val CHANGE_ON_FOOT_WITHIN_METERS = 2 * AT_STOP_WITHIN_METERS

    /**
     * [CHANGE_ON_FOOT_WITHIN_METERS] where both ends are in one interchange of the bundled index
     * (maintainer, 2026-10-03). Its stations 200–290 m apart are changed between mostly indoors,
     * where location can't follow the walk predictably, so as a step it would only end on a tap:
     * Paddington's Elizabeth line, Bakerloo and main line to the Hammersmith & City (211, 255 and
     * 285 m), London Bridge (208 m), West Hampstead (212 m), Canary Wharf (213 and 242 m), Seven
     * Sisters (220 m). As high a bar everywhere would also skip a walk along the street between two
     * places (Aldgate to Aldgate East at 245 m, Bayswater to Queensway at 226 m). It never stretches
     * past this: King's Cross to St Pancras, 304 m and more, stays a walk.
     */
    const val CHANGE_ON_FOOT_IN_HUB_WITHIN_METERS = 290.0


    /**
     * …or this close to one of its entrances ([StationPlaces.entrances]), which is the door itself,
     * not a point inside: 150 m around every entrance of a big interchange reached well out into
     * the streets around it, telling a rider still on their way there that they'd arrived
     * (maintainer, 2026-09-29).
     */
    const val AT_ENTRANCE_WITHIN_METERS = 50.0

    /**
     * Where [trip]'s rider is walking to at [now], when that's a ride's boarding stop with a known
     * position: the walk from where they started, or one between rides. Null otherwise (a ride, or
     * the walk to the destination, which ends when they're seen there: [walksToEnd]), and once the walk's estimated
     * time is up at [now]: it ends on its time then, with no fix to wait for.
     */
    fun walkingTo(trip: ActiveTrip, now: Instant): Coordinates? = walkingToRide(trip, now)?.fromAt

    /**
     * Whether [trip]'s rider is on the walk to the destination at [now], the trip's last leg, with the
     * destination placed ([walkEnd]): it ends when they're seen there ([seen]), not on its time, so the trip
     * doesn't say they've arrived while they're still on their way; never seen there, it ends
     * [END_WALK_GRACE] past its time (maintainer, 2026-10-03). Unplaced, it ends on its time.
     */
    fun walksToEnd(trip: ActiveTrip, now: Instant): Boolean {
        val leg = trip.leg ?: return false
        // A clock set back to before the walk began ends the wait rather than running it again, as the
        // ride and wait windows do (Codex, PR #521): never GPS kept on past its bound.
        return leg.isWalk && trip.legIndex == trip.route.legs.lastIndex && walkEnd(trip) != null &&
            !now.isBefore(trip.legStartedAt) && now.isBefore(trip.legStartedAt.plus(leg.run).plus(END_WALK_GRACE))
    }

    /**
     * Where the walk [trip]'s rider is on ends: its own end where the Planner places it (a stop's;
     * a walk's end is never kept from TfL's answer, Codex PR #359), else for the walk to the
     * destination the place the rider chose ([ActiveTrip.destinations], kept on the device with the
     * trip; Codex, PR #521), else the boarding stop of the ride after it. Null off a walk, or unplaced.
     */
    fun walkEnd(trip: ActiveTrip): Coordinates? {
        val leg = trip.leg?.takeIf { it.isWalk } ?: return null
        leg.toAt?.let { return it }
        if (trip.legIndex == trip.route.legs.lastIndex) {
            return trip.destinations.filterIsInstance<TripDestination.Place>().singleOrNull()?.coordinate
        }
        return trip.route.legs.getOrNull(trip.legIndex + 1)?.fromAt
    }

    /**
     * How long past its time the walk to the destination waits to see the rider there ([walksToEnd])
     * before it ends anyway: a fix that never lands near it (indoors, a destination placed off its
     * door) mustn't keep the trip, and GPS, running (maintainer, 2026-10-03).
     */
    val END_WALK_GRACE: Duration = Duration.ofMinutes(10)

    /**
     * How far, straight, the end of the walk [trip]'s rider is on is from [rider]: the nearest of
     * [walkEnd] and the [station] walked to's own point and entrances. Null off a walk, with an unplaced end, or with a fix
     * that doesn't place the rider (a fallback or coarse one).
     */
    fun metersLeft(trip: ActiveTrip, rider: LocationFix?, station: StationPlaces = StationPlaces()): Double? {
        val fix = rider?.takeIf { !it.isFallback && !it.isCoarse } ?: return null
        if (trip.leg?.isWalk != true) return null
        // The nearest place that ends the walk, as [seen] ends it: the walk's end, and for a station walked
        // to its own published point and each entrance (Codex, PR #521), so the rider nearing an entrance
        // isn't told they're hundreds of meters off.
        val ends = listOfNotNull(walkEnd(trip), station.point) + station.entrances
        return ends.minOfOrNull { NearestStops.distanceMeters(fix.coordinates.latitude, fix.coordinates.longitude, it.latitude, it.longitude) }
    }

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
     * On a walk to a ride, the rider is at its stop when near its placed position or the [station]'s
     * own, or at any of its entrances (the maintainer, 2026-09-28: the Planner can place a big
     * station's stop 200 m from the entrance the rider stands at) — [atStation]. On a train nearly
     * where they get off ([stationRiddenTo]), they're off when seen at that station the same way.
     */
    fun seen(trip: ActiveTrip, rider: LocationFix?, now: Instant, station: StationPlaces = StationPlaces()): ActiveTrip {
        // On the walk to the destination: arrived once seen there (within [AT_STOP_WITHIN_METERS], the
        // fix's uncertainty included), or once [END_WALK_GRACE] past its time (maintainer, 2026-10-03).
        if (walksToEnd(trip, now)) {
            val end = walkEnd(trip)
            return if (rider != null && end != null && near(rider, end, AT_STOP_WITHIN_METERS)) walked(trip, now) else trip
        }
        walkingToRide(trip, now)?.let { ride ->
            val there = rider != null && atStation(rider, ride.fromAt, station) != null
            return if (there) walked(trip, now) else trip
        }
        // On a train nearly at where they get off, and seen at that station, its placed point or any of
        // its entrances: they're off, whatever the train followed says (it can be a later one than
        // theirs, still a stop or two away: the maintainer, 2026-09-28).
        stationRiddenTo(trip, now)?.let { ride ->
            val there = rider != null && atStation(rider, ride.toAt, station) != null
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
            vehicleId = "", vehicleLeg = null, vehicleOffId = "", boardsAt = null, boarded = false, boardedAt = null, dueOffAt = null, heldFrom = null,
            legStartedAt = now, warnedLeg = -1, waitFrom = trip.waitFrom ?: trip.legStartedAt, onBoardSeen = false, seenAlongStop = -1,
        )
    }

    /**
     * Where [rider] is seen at a station placed at [placed] (the Planner's point, when it gives one)
     * and [station] (read for it): within [AT_STOP_WITHIN_METERS] of either point, or
     * [AT_ENTRANCE_WITHIN_METERS] of an entrance, the fix's uncertainty included. Null when neither.
     */
    fun atStation(rider: LocationFix, placed: Coordinates?, station: StationPlaces): SeenAt? = when {
        listOfNotNull(placed, station.point).any { near(rider, it, AT_STOP_WITHIN_METERS) } -> SeenAt.POINT
        station.entrances.any { near(rider, it, AT_ENTRANCE_WITHIN_METERS) } -> SeenAt.ENTRANCE
        else -> null
    }

    /**
     * What [seen] would see [rider] at, to move [trip] on at [now]: the stop a walk ends at, or the
     * station a ride gets off at ([atStation]). Null when it wouldn't, so the log can say what moved
     * a trip on without saying where.
     */
    fun seenAtStop(trip: ActiveTrip, rider: LocationFix?, now: Instant, station: StationPlaces = StationPlaces()): SeenAt? {
        rider ?: return null
        if (walksToEnd(trip, now)) return walkEnd(trip)?.takeIf { near(rider, it, AT_STOP_WITHIN_METERS) }?.let { SeenAt.POINT }
        walkingToRide(trip, now)?.let { return atStation(rider, it.fromAt, station) }
        return stationRiddenTo(trip, now)?.let { atStation(rider, it.toAt, station) }
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
        // A walk within one place isn't one location can end (nothing tells which platform the rider is
        // at): its time still counts, as when it's passed on its own ([pastChange]), so a trip restored
        // on one doesn't pick a train the rider can't reach (Codex P1, #494).
        if (changesOnFoot(trip, trip.legIndex)) return pastChange(trip, trip.legStartedAt)
        return trip.copy(
            legIndex = trip.legIndex + 1, legStartedAt = now.plus(leg.changeAfter),
            vehicleId = "", vehicleLeg = null, vehicleOffId = "", boardsAt = null, boarded = false, boardedAt = null, dueOffAt = null, waitFrom = null, heldFrom = null,
            onBoardSeen = false, seenAlongStop = -1,
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
        vehicleId = "", vehicleLeg = null, vehicleOffId = "", boardsAt = null, boarded = false, boardedAt = null, dueOffAt = null, warnedLeg = -1, waitFrom = null, heldFrom = null,
        onBoardSeen = false, seenAlongStop = -1,
    )

    /**
     * A step of a trip on the way, as its screen lists them and Back and Next move through them: a
     * walk is one step, and a ride two (maintainer, 2026-09-29), boarding it and then getting off it
     * ([onBoard]), so the rider can say they're on before they say they're off.
     */
    data class Step(val leg: Int, val onBoard: Boolean = false)

    /**
     * Which of [route]'s walks are changes on foot between two rides, by leg index: walks with a ride
     * straight before and after whose two ends are within [CHANGE_ON_FOOT_WITHIN_METERS] of each
     * other, so the rider's location can't tell them leaving one stop from reaching the other
     * ([AT_STOP_WITHIN_METERS] round each), or within [CHANGE_ON_FOOT_IN_HUB_WITHIN_METERS] where
     * [stations] puts both ends in one interchange. Such a walk is the change between the rides
     * ([changeUntil], the route page's "N min to change"), not a step of its own ([steps]): the trip
     * goes straight to boarding the ride after it, whose card names the stop or platform to go to,
     * and the walk's time only says which trains are in reach (maintainer, 2026-10-03). Hammersmith's
     * two stations (about 150 m) and Paddington's (up to about 285 m, one interchange) are a change;
     * King's Cross and St Pancras (about 300 m and more) are a walk.
     *
     * Each end is placed by the Planner's own positions first (the walk's, or the ride's it meets),
     * then by [stations] by stop id. With an end not placed, a walk that starts and ends at the same
     * name ("Stratford" to "Stratford") is a change, as before. One before the first ride is no change
     * and stays a step. Decided once when a trip starts ([ActiveTrip.onFootChanges]).
     */
    fun changesOnFoot(route: TripRoute, stations: StationIndex = StationIndex.EMPTY): Set<Int> =
        route.legs.indices.filterTo(LinkedHashSet()) { changesOnFoot(route, it, stations) }

    /**
     * Whether [trip]'s leg at [index] is a change on foot, as decided when it started
     * ([ActiveTrip.onFootChanges]); by the names alone for a trip kept by an older build, as it
     * was shown then.
     */
    fun changesOnFoot(trip: ActiveTrip, index: Int): Boolean =
        trip.onFootChanges?.let { index in it } ?: sameNameChange(trip.route, index)

    private fun changesOnFoot(route: TripRoute, index: Int, stations: StationIndex): Boolean {
        val (leg, before, onward) = between(route, index) ?: return false
        val fromIds = listOf(leg.fromId, before.toId).filter { it.isNotBlank() }
        val toIds = listOf(leg.toId, onward.fromId).filter { it.isNotBlank() }
        val from = leg.fromAt ?: before.toAt ?: fromIds.firstNotNullOfOrNull(stations::placeOf)
        val to = leg.toAt ?: onward.fromAt ?: toIds.firstNotNullOfOrNull(stations::placeOf)
        if (from == null || to == null) return sameName(leg)
        // One interchange's stations are changed between mostly indoors, where location can't follow
        // a little farther either; distinct places that far apart (Aldgate to Aldgate East) it can.
        val hub = fromIds.firstNotNullOfOrNull(stations::interchangeOf)
        val within = if (hub != null && hub == toIds.firstNotNullOfOrNull(stations::interchangeOf)) {
            CHANGE_ON_FOOT_IN_HUB_WITHIN_METERS
        } else {
            CHANGE_ON_FOOT_WITHIN_METERS
        }
        return distance(from, to) <= within
    }

    // A walk between two rides that starts and ends at the same name: the fallback where its ends
    // can't be placed, and the whole rule for a trip kept by an older build.
    private fun sameNameChange(route: TripRoute, index: Int): Boolean =
        between(route, index)?.let { sameName(it.first) } == true

    private fun sameName(leg: TripLeg): Boolean {
        val from = leg.fromName.trim()
        // By the matching form alone, qualifiers and all: the two Hammersmiths are two stations
        // ([sameStopName]) but one place to change at, a street apart, as the distance rule finds
        // them ([matchStopName]).
        return from.isNotEmpty() && matchStopName(from).equals(matchStopName(leg.toName.trim()), ignoreCase = true)
    }

    // [route]'s walk at [index] with the rides straight before and after it, or null when it isn't one.
    private fun between(route: TripRoute, index: Int): Triple<TripLeg, TripLeg, TripLeg>? {
        val leg = route.legs.getOrNull(index)?.takeIf { it.isWalk } ?: return null
        val before = route.legs.getOrNull(index - 1)?.takeIf { !it.isWalk } ?: return null
        val onward = route.legs.getOrNull(index + 1)?.takeIf { !it.isWalk } ?: return null
        return Triple(leg, before, onward)
    }

    /** Every step of [trip], in order ([Step]), leaving out a change on foot ([changesOnFoot]). */
    fun steps(trip: ActiveTrip): List<Step> = trip.route.legs.flatMapIndexed { index, leg ->
        when {
            changesOnFoot(trip, index) -> emptyList()
            leg.isWalk -> listOf(Step(index))
            else -> listOf(Step(index), Step(index, onBoard = true))
        }
    }

    /**
     * The step [trip] is at: its leg, and on a ride, whether they're on board it — seen so, not only
     * taken to be because their train left (maintainer, 2026-09-29: still on the platform looks the
     * same underground, so the ride's step stays until location or their word says otherwise).
     */
    fun stepOf(trip: ActiveTrip): Step =
        // On a walk within one place, the step is boarding the ride it changes onto ([changesOnFoot]).
        if (changesOnFoot(trip, trip.legIndex)) Step(trip.legIndex + 1)
        else Step(trip.legIndex, trip.onBoardSeen && trip.leg?.isWalk == false)

    /**
     * How many of [trip]'s steps are behind the rider, in order ([steps]): those before theirs, or
     * all of them from an arrival kept because forgetting the trip failed, which is past the last
     * step rather than at none (Codex, PR #384).
     */
    fun stepsDone(trip: ActiveTrip): Int {
        val steps = steps(trip)
        return steps.indexOf(stepOf(trip)).takeIf { it >= 0 } ?: steps.size
    }

    /**
     * The step before [trip]'s, for Back: none at the first, nor from an arrival kept because
     * forgetting the trip failed (its [ActiveTrip.legIndex] past the last leg). That arrival is being
     * forgotten, and the tracker moves it nowhere; End trip is the way out there
     * (Codex, PR #384).
     */
    fun stepBefore(trip: ActiveTrip): Step? {
        val steps = steps(trip)
        val at = steps.indexOf(stepOf(trip))
        return if (at < 0) null else steps.getOrNull(at - 1)
    }

    /**
     * The step after [trip]'s, for Next: none at the last, nor after an arrival kept because forgetting
     * the trip failed, which Next would otherwise start over from its first step (Codex, PR #384).
     */
    fun stepAfter(trip: ActiveTrip): Step? {
        val steps = steps(trip)
        val at = steps.indexOf(stepOf(trip))
        return if (at < 0) null else steps.getOrNull(at + 1)
    }

    /**
     * [trip] at [step] at [now], because the rider said so: the start of its leg ([atLeg]), or on board
     * a ride (maintainer, 2026-09-29). On board, they're on the train followed, the next they could
     * catch as the trip assumes, or with none yet one at the platform about [now], which the leg then
     * starts from ([ActiveTrip.legStartedAt]) for picking it. A train followed that is still more than
     * [ON_BOARD_GRACE] from the stop isn't the one they're on, so it's let go and one at the platform
     * picked the same way. Their word is dated past the left-behind check ([seen]), which only
     * second-guesses that assumption, so a train still standing at the platform can't have it taken
     * back.
     */
    @WorkerThread
    fun atStep(trip: ActiveTrip, step: Step, now: Instant): ActiveTrip {
        // Back to the ride Next just moved them past, on board: its train as it was, or none named if
        // none was, not one looked for afresh at a stop they left minutes ago (Codex, PR #384). Its "get off soon", taken back
        // when they left it, is to be said again. What the rider let go of or has heard since stays so: an
        // older snapshot never brings back what Keep going dismissed, nor sounds again what was heard (Codex on #519).
        trip.leftRide?.takeIf { step.onBoard && step == stepBefore(trip) && stepOf(it) == step }
            ?.let {
                return it.copy(
                    warnedLeg = -1, alertLeft = false, leftRide = null,
                    disruptionsDismissed = it.disruptionsDismissed + trip.disruptionsDismissed,
                    disruptionsHeard = it.disruptionsHeard + trip.disruptionsHeard,
                )
            }
        val moved = if (!step.onBoard) {
            atLeg(trip, step.leg, now)
        } else {
            val at = if (step.leg == trip.legIndex) trip else atLeg(trip, step.leg, now)
            // A train still minutes from the stop can't be the one they're on: more likely the one at
            // the platform, which the tracker picks as with none followed.
            val coming = at.vehicleId.isNotBlank() && at.boardsAt?.isAfter(now.plus(ON_BOARD_GRACE)) == true
            val train = if (coming) at.copy(vehicleId = "", vehicleLeg = null, vehicleOffId = "", boardsAt = null, dueOffAt = null, heldFrom = null) else at
            train.copy(boarded = true, boardedAt = now.minus(MISSED_WINDOW), legStartedAt = now, onBoardSeen = true, seenAlongStop = -1)
        }
        // On board a ride and moved on by Next: kept, so Back can undo just that. Kept with no train
        // named too, so Back is on board as they said, not on one at the platform by then (Codex, PR #384).
        val left = trip.takeIf { stepOf(it).onBoard && step == stepAfter(it) }
        return moved.copy(leftRide = left?.copy(leftRide = null))
    }

    /**
     * Whether the rider can put [trip] at [step] at [now] ([atStep]): a step of the route, other than
     * the one they're on, that the move doesn't carry straight through to arriving — past the last
     * leg, or onto a closing walk of no length (the Planner allows one). Arriving forgets the trip,
     * so no Back could undo it; End trip is the way out there (Codex, PR #351). None from an arrival
     * kept because forgetting the trip failed, which is at no step: the tracker moves it nowhere, so
     * no row or button offers a move (Codex, PR #384).
     */
    fun canGoTo(trip: ActiveTrip, step: Step, now: Instant): Boolean {
        val steps = steps(trip)
        // On a walk within one place its step is the ride's ([stepOf]), which the rider can still
        // say they've reached, ending the walk early.
        return stepOf(trip) in steps && step in steps && (step != stepOf(trip) || changesOnFoot(trip, trip.legIndex)) &&
            advance(atStep(trip, step, now), null, now).second != TripProgress.Arrived
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

    /**
     * The train a "time to board" is said for ([ActiveTrip.boardWarned]): [trip]'s leg and the train
     * followed on it. A train the rider is left behind by hands over to the next one, a new key, so
     * that one is said for in its time.
     */
    fun boardKey(trip: ActiveTrip): String =
        // Another line's train is told by its line too: TfL's train ids are its line's own.
        "${trip.legIndex}/${trip.vehicleId}" + trip.vehicleLeg?.lineId?.takeIf { it.isNotBlank() && it != trip.leg?.lineId }?.let { "/$it" }.orEmpty()

    /**
     * Whether [progress] is time to board (maintainer, 2026-09-27): waiting for the train followed,
     * due at the boarding stop within [BOARD_SOON_TIME] of [now] (or already due, still not left), the
     * rider not yet on board by their word. Not while there's no train followed or no time for it, nor
     * before the rider can board at all ([ActiveTrip.legStartedAt], still ahead while a walk within
     * one place runs, [pastChange]), as a change's time holds it back (Codex P2, #494).
     */
    fun boardSoon(trip: ActiveTrip, progress: TripProgress, now: Instant): Boolean =
        progress is TripProgress.Waiting && progress.due != null && trip.vehicleId.isNotBlank() &&
            !trip.boarded && !trip.onBoardSeen && !now.plus(BOARD_SOON_TIME).isBefore(progress.due) &&
            !now.isBefore(trip.legStartedAt)

    /** Whether [progress] calls for "time to board" not yet said on [trip] for its train. */
    fun shouldBoard(trip: ActiveTrip, progress: TripProgress, now: Instant): Boolean =
        boardSoon(trip, progress, now) && trip.boardWarned != boardKey(trip)

    /** [trip] with "time to board" said for its train. */
    fun saidBoard(trip: ActiveTrip): ActiveTrip = trip.copy(boardWarned = boardKey(trip))

    /**
     * Whether a "time to board" said on [trip] still stands: its train is still the one followed, and
     * the rider still waits for it ([progress]). Boarded, left behind, a leg moved on, the trip ended:
     * it's done with, and taken down.
     */
    fun boardStands(trip: ActiveTrip, progress: TripProgress?): Boolean =
        trip.boardWarned.isNotEmpty() && trip.boardWarned == boardKey(trip) && progress is TripProgress.Waiting &&
            !trip.boarded && !trip.onBoardSeen

    // The next leg, from when this one was done plus the change the Planner allows after it (a
    // change with no walk leg of its own): a walk's time runs from there, and a ride's train is
    // picked from there. Done is when the rider was due off (or the walk's time was up), not when
    // it was noticed, which is later after a while away; [now] when that isn't known.
    private fun nextLeg(trip: ActiveTrip, now: Instant): Pair<ActiveTrip, TripProgress> {
        val leg = trip.leg
        val doneAt = if (leg?.isWalk == true) trip.legStartedAt.plus(leg.run) else trip.dueOffAt
        val from = (doneAt?.takeIf { it.isBefore(now) } ?: now).plus(leg?.changeAfter ?: Duration.ZERO)
        val next = trip.copy(legIndex = trip.legIndex + 1, legStartedAt = from, vehicleId = "", vehicleLeg = null, vehicleOffId = "", boardsAt = null, boarded = false, boardedAt = null, dueOffAt = null, waitFrom = null, onBoardSeen = false, seenAlongStop = -1, heldFrom = null)
        val onward = next.leg ?: return next to TripProgress.Arrived
        if (onward.isWalk) {
            val until = from.plus(onward.run)
            // A walk already done while away: on to the leg after it.
            // A walk within one place: straight on to boarding the ride after it, ready once its time is up.
            if (changesOnFoot(next, next.legIndex)) {
                val boarding = pastChange(next, from)
                val ride = boarding.leg!!
                return boarding to (changeUntil(boarding, now)?.let { TripProgress.Changing(ride, it) } ?: TripProgress.Waiting(ride, null))
            }
            return if (now.isBefore(until) || walksToEnd(next, now)) next to TripProgress.Walking(onward, until) else nextLeg(next, now)
        }
        return next to (changeUntil(next, now)?.let { TripProgress.Changing(onward, it) } ?: TripProgress.Waiting(onward, null))
    }

    // [trip] past the walk within one place it's on ([changesOnFoot]), started at [from]: at the ride
    // after it, which the rider can board once the walk's time and the change after it are up. Nothing
    // tells when they reach its platform, so the walk is no step of its own; its time only says which
    // trains are in reach (maintainer, 2026-10-03).
    private fun pastChange(trip: ActiveTrip, from: Instant): ActiveTrip {
        val walk = trip.leg!!
        return trip.copy(legIndex = trip.legIndex + 1, legStartedAt = from.plus(walk.run).plus(walk.changeAfter))
    }

    // A bus stop area's id, naming a road's poles together.
    private const val STOP_AREA_PREFIX = "490G"

    // A bus stop's id, a pole's or a stop area's.
    private const val BUS_STOP_PREFIX = "490"

    // Where in [calls] the followed train is still to call at [leg]'s boarding stop for the rider, or
    // -1 once it has left. A call there before any of its calls on the leg is still to come, however
    // late. One after calls on the leg is a loop's: either the train still on its previous lap, or
    // its next lap after the rider's ride. With when it was due there ([ActiveTrip.boardsAt]), the
    // previous lap is the call about then (within [LATE_BY]), or, before the time it was due there
    // when first taken so ([ActiveTrip.heldFrom]), any while the rider is still seen at the stop
    // ([atBoarding]): a train held on its way round to them. That time is kept, not moved on by the
    // later call each such reading takes, or each would put off the next (Codex, PR #452). Once it
    // has come, a rider still there may have missed the train, which then went on to its next lap:
    // that's left to the bound, and to the check for a rider left behind (Codex, PR #452). Without,
    // it's one a ride on the leg follows. With it, the trip's [ActiveTrip.heldFrom] as it now stands.
    private fun upcomingBoarding(trip: ActiveTrip, leg: TripLeg, calls: List<VehicleCall>, now: Instant, atBoarding: Boolean): Pair<Int, Instant?> {
        // The first call there the rider can catch (from [ActiveTrip.legStartedAt]): one before it is
        // an earlier lap they couldn't, when a later lap's call there follows; with none, it's the
        // picked train revised to leave too soon.
        val there = calls.indices.filter { callsFrom(calls[it], leg) }
        if (there.isEmpty()) return -1 to trip.heldFrom
        val boarding = there.firstOrNull { !calls[it].expected.isBefore(trip.legStartedAt) } ?: there.last()
        val onLeg = calls.indexOfFirst { onPath(leg, it) >= 0 || callsTo(it, leg) }
        if (onLeg < 0 || boarding < onLeg) return boarding to trip.heldFrom
        val boardsAt = trip.boardsAt ?: return (if (runsAlong(leg, calls.drop(boarding))) boarding else -1) to trip.heldFrom
        if (!calls[boarding].expected.isAfter(boardsAt.plus(LATE_BY))) return boarding to trip.heldFrom
        val heldFrom = trip.heldFrom ?: boardsAt
        return if (atBoarding && now.isBefore(heldFrom)) boarding to heldFrom else -1 to trip.heldFrom
    }

    // How much later than last seen a train can come to be due at the boarding stop in one refresh
    // and still be the same call, not a loop's next lap.
    private val LATE_BY: Duration = Duration.ofMinutes(5)

    // Where in [calls] a boarded train calls at [leg]'s boarding stop on the lap the rider boarded:
    // the first call there with none of the ride's stops due since they boarded before it. Those
    // before it are the lap before, or stops before the boarding stop, which TfL can be slow to drop,
    // however late the train has come to run. One there after a stop of the ride due since they
    // boarded is the next lap's, however near now, and whether or not that stop's time has come: the
    // train has been there with them since, or is there now (Codex, PR #384). Told by order, not by a
    // window around a time, which a late train or a lap nearly round slips past (Codex, PR #384).
    // Order can't see a whole lap missed while the app was away, so the call also can't be due later
    // than the ride's planned time (or [LATE_BY]) after they boarded, when the train had the time to
    // take them all the way, and a loop is longer than the ride; nor more than [LATE_BY] after the
    // call was last seen there ([ActiveTrip.boardsAt], kept up to date by [advance]), which is what
    // lets a train held at the platform run later still. Once they were seen due off, only that last
    // holds: a train still standing there was seen there at each refresh, where on a tight loop the
    // next lap's call there can come round within the ride's planned time (Codex, PR #384). -1 when
    // there's none, as once the call is gone or only the next lap's is there.
    private fun boardedCall(trip: ActiveTrip, leg: TripLeg, calls: List<VehicleCall>, now: Instant): Int {
        // When they boarded: their word ([atStep] starts the leg then), or when the train left with them.
        val since = listOfNotNull(trip.boardedAt, trip.legStartedAt).max()
        val lastSeen = trip.boardsAt?.plus(LATE_BY)
        val latest = if (seenPast(trip, now)) lastSeen ?: return -1 else listOfNotNull(since.plus(maxOf(leg.run, LATE_BY)), lastSeen).max()
        for (i in calls.indices) {
            val call = calls[i]
            if (callsFrom(call, leg)) return if (call.expected.isAfter(latest)) -1 else i
            if (call.expected.isAfter(since) && (onPath(leg, call) >= 0 || arrivesAt(trip, leg, call))) return -1
        }
        return -1
    }

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
        return leg.pathNames.indexOfFirst { it.isNotBlank() && namesStop(call, it, leg) }
    }

    // Whether [call] is at [leg]'s boarding or alighting stop.
    private fun callsFrom(call: VehicleCall, leg: TripLeg): Boolean = calls(call, leg, leg.fromId, leg.fromName)
    private fun callsTo(call: VehicleCall, leg: TripLeg): Boolean = calls(call, leg, leg.toId, leg.toName)

    // Whether [call] is at the stop [id] (or, by name, the same place: TfL's arrivals can name a
    // station by another of its ids).
    // A bus pole is never matched by name to another pole, or to a stop area the Planner gave for want
    // of a pole: a route can call at two "High Street"s, and a pole's id is authoritative ([Terminating]).
    private fun calls(call: VehicleCall, leg: TripLeg, id: String, name: String): Boolean {
        if (call.stopId == id) return true
        if (StopDisruptionBatch.isPole(call.stopId) && id.startsWith(BUS_STOP_PREFIX)) return false
        return name.isNotBlank() && namesStop(call, name, leg)
    }

    // Whether [call] names the stop [name] (one of [leg]'s, but not by [call]'s id). Exactly, or by
    // [sameStopName] since TfL's sources disagree on a line qualifier. A line-qualified [name]
    // ("Paddington (H&C)") isn't matched that loosely by a call at another of [leg]'s own stops,
    // though: a Circle train calls at the plain "Paddington" on its way, and that isn't the rider's
    // stop, while a call by an id the ride doesn't otherwise know is the station under another of its
    // ids (Codex, PR #499; maintainer, 2026-10-03).
    private fun namesStop(call: VehicleCall, name: String, leg: TripLeg): Boolean {
        if (exactStopName(call.stopName, name)) return true
        if (!sameStopName(call.stopName, name)) return false
        return !isLineQualified(name) || call.stopId !in stopsOf(leg)
    }

    private fun stopsOf(leg: TripLeg): Set<String> = (leg.path + leg.fromId + leg.toId).filter { it.isNotBlank() }.toSet()
}
