package app.stopdash.domain

/**
 * Hides the services that go nowhere for the rider (maintainer, 2026-09-24; SPEC *Departures*): a
 * departure whose terminus is a nearby place **no farther from the rider than the stop it leaves
 * from** — a bus arriving to terminate at this very stop, or a train ending at the station the
 * rider is closest to. Boarding it would only bring them to where they already are, so it has no
 * stops beyond the rider's location worth listing.
 *
 * Each near-me stop carries its [Nearer] places — worked out once from the stops' distances when
 * its arrivals are fetched, and saved with it — so the widget's own background refresh, which has
 * no location, drops the same services. A terminus is recognized by TfL's `destinationNaptanId`
 * against those places' stop ids and stop areas or stations; only when TfL gives no id, by its
 * cleaned name. A stop with no known distance (a journey's far origin, a searched station) has no
 * [Nearer] and keeps every departure. Pure, so it is JVM-tested.
 */
object Terminating {
    /** A nearby stop, with its stop area or station ([clusterId]) and distance from the rider. */
    data class Place(val stopId: String, val clusterId: String, val name: String, val meters: Double)

    /** The ids and name keys of the places no farther from the rider than one stop. */
    data class Nearer(val ids: Set<String> = emptySet(), val names: Set<String> = emptySet()) {
        val isEmpty: Boolean get() = ids.isEmpty() && names.isEmpty()
    }

    /** [stopId]'s [Nearer] places among [places], or empty when its distance isn't known. */
    fun nearer(stopId: String, places: List<Place>): Nearer {
        val boarding = places.firstOrNull { it.stopId == stopId }?.meters ?: return Nearer()
        val within = places.filter { it.meters <= boarding }
        return Nearer(
            ids = within.flatMapTo(HashSet()) { listOf(it.stopId, it.clusterId) }.apply { remove("") },
            names = within.mapTo(HashSet()) { nameKey(it.name) }.apply { remove("") },
        )
    }

    /** [departures] without those terminating at one of [nearer]'s places. */
    fun drop(departures: List<Departure>, nearer: Nearer): List<Departure> {
        if (nearer.isEmpty) return departures
        return departures.filterNot { d ->
            // TfL's id is authoritative when given: two places can share a name ("High Street"), so a
            // name only stands in for a missing id.
            if (d.destinationId.isNotBlank()) {
                d.destinationId in nearer.ids
            } else {
                d.destination.isNotBlank() && nameKey(d.destination) in nearer.names
            }
        }
    }

    /** [departures] at [stopId] without those terminating no farther than it, per [places]. */
    fun drop(departures: List<Departure>, stopId: String, places: List<Place>): List<Departure> =
        drop(departures, nearer(stopId, places))

    // Names compare cleaned and case-folded, so TfL's "Walthamstow Central Underground Station" as
    // a stop and "Walthamstow Central" as a destination are one place — and without a line
    // qualifier ([matchStopName]), so "Hammersmith (H&C Line)" and "Hammersmith" are too.
    private fun nameKey(name: String): String = matchStopName(name).lowercase()
}
