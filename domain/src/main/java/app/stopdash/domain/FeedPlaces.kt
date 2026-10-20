package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * [leg] with each id it names placed by the model ([TripLeg.placed]): its ends, the stops the Planner
 * named, and its path. A bus end's stop pair is placed through the end's own pole, else the point the
 * Planner gave, so a pair the bundle split lands on the stop the leg uses (dev-docs/station-resolution.md
 * *Where it's built*). Called where planner legs enter the app (the Planner's answer, a trip restored
 * from disk), never in a decision.
 */
@WorkerThread
fun Hubs.place(leg: TripLeg): TripLeg {
    val placed = LinkedHashMap<String, Resolved>()
    fun end(id: String, area: String, at: Coordinates?) {
        if (id.isNotBlank()) placed.getOrPut(id) { resolve(id, near = at) }
        if (area.isNotBlank()) placed.getOrPut(area) { resolve(area, pole = id.ifBlank { null }, near = at) }
    }
    end(leg.fromId, leg.fromArea, leg.fromAt)
    end(leg.toId, leg.toArea, leg.toAt)
    for (id in listOf(leg.plannedFromId, leg.plannedToId) + leg.path) {
        if (id.isNotBlank()) placed.getOrPut(id) { resolve(id) }
    }
    return leg.copy(placed = placed)
}

/** [route] with each leg placed ([place]). */
@WorkerThread
fun Hubs.place(route: TripRoute): TripRoute = route.copy(legs = route.legs.map { place(it) })

/**
 * [trip] with every planner leg it keeps placed ([place]): its route, the legs as planned, the ride last
 * followed and last got off, and the ride it left, so a trip read back from disk compares as one just
 * planned does.
 */
@WorkerThread
fun Hubs.place(trip: ActiveTrip): ActiveTrip = trip.copy(
    route = place(trip.route),
    vehicleLeg = trip.vehicleLeg?.let { place(it) },
    offLeg = trip.offLeg?.let { place(it) },
    plannedLegs = trip.plannedLegs?.map { place(it) },
    leftRide = trip.leftRide?.let { place(it) },
)

/** [departures] with each terminus placed ([Departure.destinationPlace]); one with no terminus id as it is. */
@WorkerThread
fun Hubs.place(departures: List<Departure>): List<Departure> =
    departures.map { it.takeIf { d -> d.destinationId.isBlank() } ?: it.copy(destinationPlace = resolve(it.destinationId)) }

/** [board] with each train's terminus placed ([place]), the trains it lists with no time included. */
@WorkerThread
fun Hubs.place(board: RailBoard): RailBoard = board.copy(departures = place(board.departures), untimed = placeUntimed(board.untimed))

// [untimed]'s trains placed ([place]), each kept with its own mark.
@WorkerThread
private fun Hubs.placeUntimed(untimed: List<UntimedTrain>): List<UntimedTrain> =
    untimed.map { it.copy(train = place(listOf(it.train)).single()) }
