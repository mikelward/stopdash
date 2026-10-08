package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A *To…* from a searched station (SPEC *Finding stops → From… To…*): whether its destination search
 * is up, and what was picked — a stop ([stopId], [name]) or a place ([place], a saved favorite or a
 * geocoded result, routed to by its coordinate, D9). At most one of the two is set. Pure, so the
 * picker's transitions are tested apart from the activity that holds them.
 */
data class ToChoice(
    val picking: Boolean = false,
    val stopId: String? = null,
    val name: String = "",
    val place: TripDestination.Place? = null,
) {
    /** Whether the trip's area (its picker or its trip) takes the station page's place. */
    val open: Boolean get() = picking || stopId != null || place != null

    /** Whether a destination has been picked, so Back from the picker returns to the trip. */
    val hasDestination: Boolean get() = stopId != null || place != null

    /** Open the destination search, keeping any destination picked before. */
    fun startPicking(): ToChoice = copy(picking = true)

    /** A stop picked: it replaces any place picked before. */
    fun pickStop(match: StationMatch): ToChoice = ToChoice(stopId = match.id, name = match.name)

    /** A place picked: its coordinate is the destination, replacing any stop picked before. */
    fun pickPlace(place: TripDestination.Place): ToChoice = ToChoice(name = place.name, place = place)

    /** Leave the search, back to the trip picked before (if any). */
    fun closePicker(): ToChoice = copy(picking = false)

    /** Forget the destination (the search stays as it was). */
    fun clearDestination(): ToChoice = copy(stopId = null, name = "", place = null)

    /**
     * Where a saved place with no stop in range opens ([PlaceStart]): it has no list to show, so its
     * trip, from the coordinate alone — the To… a change of start carries ([changeTo]), else its To…
     * search. Null when the trip is already open, so nothing changes.
     */
    fun openedWithoutStops(changeTo: ToChoice?): ToChoice? = if (open) null else changeTo ?: startPicking()

    companion object {
        val NONE = ToChoice()

        /**
         * Where [trip] on the way goes, as the rider chose it, for planning it again from partway
         * along: its place, or the stop or station picked ([ActiveTrip.destinationStopId]). A trip kept
         * before that was stored goes to the stop its route ends at, by the destination's name.
         */
        @WorkerThread
        fun of(trip: ActiveTrip): ToChoice {
            trip.destinations.filterIsInstance<TripDestination.Place>().firstOrNull()?.let { return ToChoice(name = it.name, place = it) }
            val stopId = trip.destinationStopId.ifBlank { trip.route.legs.lastOrNull { !it.isWalk }?.toId.orEmpty() }
            return if (stopId.isBlank()) NONE else ToChoice(stopId = stopId, name = trip.destinationName)
        }
    }
}
