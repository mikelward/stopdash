package app.stopdash.domain

// Where a planned bus really boards and gets off (SPEC *Trips with a change*). The Planner names a
// bus stop by its pair ([TripLeg.fromArea], a road's two poles) and one pole of it, which can be the
// other side of the road, and at a bus station it can name a stand the line doesn't use. The line's
// route ([LineSequence]) says which pole or stand its bus uses; these place a leg there, and say
// whether that's known yet. Static network data and the Planner's legs only: no clock, no fetching.

/**
 * [leg] boarding and alighting at the poles its bus uses. Of the stop pair's poles on the line's
 * route ([sequences]), the one the route leaves by way of the leg's next stop, and the first pole of
 * the alighting pair after it — or, where it boards or gets off at a stop in no pair (a bus station's
 * stand), the stop of that name. Unchanged for anything but a bus, before its route loads (or when
 * it failed), or where the route gives no single answer.
 */
fun onPoles(leg: TripLeg, sequences: Map<String, LineSequence?>): TripLeg {
    if (leg.isWalk || (leg.fromArea.isEmpty() && leg.toArea.isEmpty() && !leg.isBus)) return leg
    val sequence = sequences[leg.lineId] ?: return leg
    val (from, to) = polesOf(leg, sequence) ?: return leg
    if (from == leg.fromId && to == leg.toId) return leg
    // A moved end is the route's stop throughout: where it is, which the trip walks the rider to
    // (the Planner's position where the route has none), and the Planner's stop it stands in for,
    // which the leg's keys go by ([boardingKey]).
    fun at(id: String, planned: Coordinates?) = sequence.stopPositions[id]?.let { (lat, lon) -> Coordinates(lat, lon) } ?: planned
    return leg.copy(
        fromId = from,
        toId = to,
        fromAt = if (from == leg.fromId) leg.fromAt else at(from, leg.fromAt),
        toAt = if (to == leg.toId) leg.toAt else at(to, leg.toAt),
        plannedFromId = if (from == leg.fromId) leg.plannedFromId else leg.plannedFromId.ifEmpty { leg.fromId },
        plannedToId = if (to == leg.toId) leg.plannedToId else leg.plannedToId.ifEmpty { leg.toId },
    )
}

/** A [lineId] bus boarding at the route's own stand [standId] in place of the Planner's [plannerId] ([placedStands]). */
data class PlacedStand(val lineId: String, val plannerId: String, val standId: String)

/**
 * The stands [routes]' buses board at in place of the one the Planner named ([onPoles]): a bus
 * station's, in no pair, found on the line's route by its name. The trip fetches only the Planner's
 * by itself, and the app moves a leg only to a stop it has fetched, so these are handed to the trip
 * to fetch.
 */
fun placedStands(routes: List<TripRoute>, sequences: Map<String, LineSequence?>): Set<PlacedStand> =
    routes.flatMap { it.rides }.filter { it.fromArea.isEmpty() }.mapNotNullTo(HashSet()) { leg ->
        onPoles(leg, sequences).fromId.takeIf { it != leg.fromId }?.let { PlacedStand(leg.lineId, leg.fromId, it) }
    }

/**
 * Whether [leg]'s bus is known to board and get off at the poles [onPoles] gives it: a train needs
 * no placing, and a bus waits for its line's route to give a single answer, whether it's named by a
 * stop pair or by a bus station's stand. Until then another pole of its pair, or another stand of
 * its bus station (the Planner can name one the line doesn't use), may be the one its bus uses, so
 * the route isn't vouched for there (Codex, #398).
 */
fun placedOnPoles(leg: TripLeg, sequences: Map<String, LineSequence?>): Boolean =
    leg.isWalk || (leg.fromArea.isEmpty() && leg.toArea.isEmpty() && !leg.isBus) ||
        sequences[leg.lineId]?.let { polesOf(leg, it) } != null

/**
 * Whether each of [route]'s buses is where it will stay: its line's route loaded ([sequences]: not
 * loading, nor failed) and the leg already at the poles or stands that route says its bus uses
 * ([onPoles]), boarding and alighting. Until a pair's poles are looked up the trip keeps the
 * Planner's, which may be the wrong side of the road, and a bus station's stand the Planner named can
 * be one the line doesn't use (Codex, #398). A bus the route can't place (no single answer) stays
 * where the Planner put it, so it's settled there.
 */
fun busesSettled(route: TripRoute, sequences: Map<String, LineSequence?>): Boolean =
    route.legs.none { leg ->
        fun unmoved() = onPoles(leg, sequences).let { it.fromId != leg.fromId || it.toId != leg.toId }
        when {
            leg.isWalk -> false
            leg.fromArea.isNotEmpty() || leg.toArea.isNotEmpty() -> sequences[leg.lineId] == null || unmoved()
            // A bus between stops in no pair (bus stations' stands) waits for its route too, then for
            // the move to the route's own stand where the Planner named another.
            leg.isBus -> sequences[leg.lineId] == null || unmoved()
            else -> false
        }
    }

/**
 * The pole [route]'s ride stopping at [end] boards or gets off at, by its line's route ([onPoles]),
 * or null until that route gives a single answer ([placedOnPoles]): a stop pair's other pole can't be
 * ruled out till then. A stop only a walk uses is where the rider walks to or from, placed by no bus.
 */
fun endPole(route: TripRoute, end: TripClosures.End, sequences: Map<String, LineSequence?>): String? {
    val poles = route.rides.filter { it.lineId == end.lineId && (it.fromId == end.id || it.toId == end.id) }.map { ride ->
        if (!placedOnPoles(ride, sequences)) return null
        val placed = onPoles(ride, sequences)
        if (ride.fromId == end.id) placed.fromId else placed.toId
    }.distinct()
    return if (poles.isEmpty()) end.id else poles.singleOrNull()
}

/**
 * Where [leg] boards, as a key that holds when [onPoles] moves it: its stop pair; else the stop the
 * Planner named, which a bus station's stand moved to the route's own of that name remembers. Not the
 * stop's name, which stands at one bus station, or two places, can share (Codex, #398).
 */
fun boardingKey(leg: TripLeg): String = leg.fromArea.ifEmpty { leg.plannedFromId.ifEmpty { leg.fromId } }

/** Where [leg] gets off, as a key that holds when [onPoles] moves it: as [boardingKey], at its other end. */
fun alightingKey(leg: TripLeg): String = leg.toArea.ifEmpty { leg.plannedToId.ifEmpty { leg.toId } }

/**
 * Whether the route's stop [id] is the Planner's [stop]: the stop itself, or, for a bus, the stop
 * pair ("490G…") the Planner names its path by, which holds both of a road's poles.
 */
fun isStop(sequence: LineSequence, id: String, stop: String): Boolean =
    id == stop || sequence.stopAreas[id] == stop

// Whether the route's stop [id] is where [leg] boards: the Planner's stop, or any pole of its pair.
private fun boardsAt(leg: TripLeg, sequence: LineSequence, id: String) =
    id == leg.fromId || (leg.fromArea.isNotEmpty() && sequence.stopAreas[id] == leg.fromArea)

// A stand in no pair by its name, used only where the route doesn't call at the stand itself: the
// Planner can name a bus station's stand the line doesn't use, as where it gets off (maintainer,
// 2026-09-30), and the route's own stand is the only tie between them.
private fun boardsByName(leg: TripLeg, sequence: LineSequence, id: String) =
    leg.fromArea.isEmpty() && sequence.stopNames[id]?.equals(leg.fromName, ignoreCase = true) == true

// Whether the route's stop [id] is where [leg] gets off, as [boardsAt] at its other end.
private fun alightsAt(leg: TripLeg, sequence: LineSequence, id: String) =
    id == leg.toId || (leg.toArea.isNotEmpty() && sequence.stopAreas[id] == leg.toArea)

// A stop in no pair (a bus station's stands, "Archway Station") by its name, as [boardsByName].
private fun alightsByName(leg: TripLeg, sequence: LineSequence, id: String) =
    leg.toArea.isEmpty() && sequence.stopNames[id]?.equals(leg.toName, ignoreCase = true) == true

/**
 * Every way a route of [sequence] could run [leg]'s ride, each as its route and the stops from where
 * it boards through where it gets off, matching the ends as [onPoles] does: the stop, any pole of its
 * pair, or, on a route that calls at neither, a stand of the same name. A loop calls at an end more
 * than once, so every boarding visit pairs with every later alighting one; which is the rider's isn't
 * asked (Codex, PR #455). For a reader that must hold whichever way it's run ([RouteDisruption.offRide]).
 */
fun ridesOf(leg: TripLeg, sequence: LineSequence): List<Pair<LineRoute, List<String>>> =
    sequence.routes.flatMap { route ->
        val ids = route.stopIds
        ids.indices.filter { boardsAt(leg, sequence, ids[it]) }
            .ifEmpty { ids.indices.filter { boardsByName(leg, sequence, ids[it]) } }
            .flatMap { from ->
                val later = (from + 1 until ids.size)
                later.filter { alightsAt(leg, sequence, ids[it]) }
                    .ifEmpty { later.filter { alightsByName(leg, sequence, ids[it]) } }
                    .map { to -> route to ids.subList(from, to + 1) }
            }
    }

// The poles [leg]'s bus boards and gets off at by its line's route ([onPoles]), or null where the
// route gives no single answer.
private fun polesOf(leg: TripLeg, sequence: LineSequence): Pair<String, String>? {
    fun boards(id: String) = boardsAt(leg, sequence, id)
    fun boardsNamed(id: String) = boardsByName(leg, sequence, id)
    fun alights(id: String) = alightsAt(leg, sequence, id)
    fun named(id: String) = alightsByName(leg, sequence, id)
    val next = leg.path.firstOrNull()
    val ends = sequence.routes.flatMap { route ->
        val boarding = route.stopIds.indices.filter { boards(route.stopIds[it]) }
            .ifEmpty { route.stopIds.indices.filter { boardsNamed(route.stopIds[it]) } }
        boarding.flatMap { i ->
            val on = route.stopIds.subList(i + 1, route.stopIds.size)
            // Every stop of the name, so two along the route are no single answer.
            val offs = listOfNotNull(on.indexOfFirst(::alights).takeIf { it >= 0 })
                .ifEmpty { on.indices.filter { named(on[it]) } }
            // The way the Planner rides: by its next stop, before or at where it gets off.
            offs.filter { off -> next == null || on.subList(0, off + 1).any { isStop(sequence, it, next) } }
                .map { off -> route.stopIds[i] to on[off] }
        }
    }.distinct()
    return ends.singleOrNull()
}
