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
 * [Nearer], but a service ending at that very stop is still dropped ([forStop]): it goes nowhere for
 * anyone boarding there, wherever the rider is (maintainer, 2026-10-09). Pure, so it is JVM-tested.
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

    /**
     * [stop]'s [Nearer] places plus the stop itself — its id and its stop area or station — so a
     * service ending where it's listed is dropped even where the rider's distance isn't known (a
     * searched station, a journey's far end; SPEC *Departures*). By TfL's id only, never the stop's
     * own name: a loop service can be bound for the stop it's listed at by name and still call
     * elsewhere first (Codex, #729), and only the id says it ends here, as in [RouteStops.resolve].
     * Not the other poles of its place (a bus ending at the pole across the road): each surface holds
     * a different set of a place's stops, so that would hide a service on one and not another
     * (maintainer, 2026-10-09; `TODO.md`).
     */
    fun forStop(stop: StopArrivals): Nearer =
        stop.nearer.copy(ids = stop.nearer.ids + listOf(stop.stopId, stop.clusterId).filter(String::isNotBlank))

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
