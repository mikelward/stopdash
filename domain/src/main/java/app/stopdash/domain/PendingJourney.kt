package app.stopdash.domain

/**
 * One end of a [PendingJourney]: a station picked in the favorite-journey picker, or one of the rider's
 * favorite places (Home, Work, their own), kept by its id so the place itself stays the one source of
 * where it is.
 */
sealed interface PendingEnd {
    val name: String

    /** Identity for [PendingJourney.key]: a station and a place never share one. */
    val key: String

    // Worked out once, when the end is made or read, so a list keyed by it reads a string.
    data class Station(val stationId: String, override val name: String) : PendingEnd {
        override val key: String = "station:$stationId"
    }

    data class Place(val placeId: String, override val name: String) : PendingEnd {
        override val key: String = "place:$placeId"
    }
}

/**
 * A favorite journey StopDash can't follow yet (SPEC *Journeys*, maintainer 2026-10-10): saved from the
 * picker when no one line serves both ends, or when an end is a favorite place. Settings lists it grayed,
 * "Support for multi-leg journeys coming soon"; nothing else reads it, so the near-me list, the widget
 * and alerts see only [FavoriteJourney]s. Kept so it can be followed once journeys with a change are.
 * Stored on the device with the rest of the user's config and never logged (*Privacy*).
 */
data class PendingJourney(val from: PendingEnd, val to: PendingEnd) {
    /**
     * Direction-free identity: the same two ends picked either way round are one journey. Worked out
     * once, when the journey is made or read, so composition keying a list by it only reads it.
     */
    val key: String = if (from.key <= to.key) "${from.key}|${to.key}" else "${to.key}|${from.key}"
}

/**
 * The store's edits to the grayed list, which run inside its worker-dispatched DataStore update. Unmarked,
 * like [Journeys.add] and [Journeys.remove]: lint's WorkerThreadCall can't see through the `updateData`
 * block, so a mark here would flag the store's own off-main edit at every call site.
 */
object PendingJourneys {
    /** Adds [journey] unless one with its key is saved already. Scans the list: off the main thread. */
    fun add(saved: List<PendingJourney>, journey: PendingJourney): List<PendingJourney> =
        if (saved.any { it.key == journey.key }) saved else saved + journey

    /** Drops [journey] by key; a second Remove landing after the first is a no-op. Off the main thread. */
    fun remove(saved: List<PendingJourney>, journey: PendingJourney): List<PendingJourney> =
        saved.filterNot { it.key == journey.key }
}
