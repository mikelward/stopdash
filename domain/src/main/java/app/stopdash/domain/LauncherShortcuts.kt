package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * The launcher's long-press shortcuts to the saved favorite places (SPEC *Launcher shortcuts*): one
 * per place, each starting a trip there from the rider's position as the place's chip does. A
 * shortcut carries only the place's [FavoritePlace.id], never its coordinate, so what the launcher
 * keeps names no position, and a tap routes to the place as it is saved now.
 */
object LauncherShortcuts {
    private const val PREFIX = "place:"

    /** The shortcut id standing for [place]. */
    fun idFor(place: FavoritePlace): String = idFor(place.id)

    /** The shortcut id standing for the place saved as [placeId]. */
    fun idFor(placeId: String): String = PREFIX + placeId

    /** The place id a shortcut id stands for, or null for an id that isn't a place's. */
    fun placeIdOf(shortcutId: String): String? = shortcutId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    /**
     * The places offered, in the saved order (the order Settings lists them), at most [max]: the
     * launcher shows the first few. Every day's, unlike the chips: a long press is asked for, and a
     * shortcut list redrawn each midnight would cost a wakeup for nothing. A place with no name to
     * show is left out.
     */
    @WorkerThread
    fun offered(places: List<FavoritePlace>, max: Int): List<FavoritePlace> =
        places.filter { it.label.isNotBlank() || !it.placeName.isNullOrBlank() }.take(max.coerceAtLeast(0))

    /** The [pinned] shortcut ids standing for a place no longer saved, to be disabled. */
    @WorkerThread
    fun stale(pinned: Collection<String>, places: List<FavoritePlace>): List<String> {
        val saved = places.mapTo(HashSet()) { it.id }
        return pinned.filter { id -> placeIdOf(id)?.let { it !in saved } == true }
    }

    /** The saved place [placeId] names, or null when it's gone. */
    @WorkerThread
    fun find(places: List<FavoritePlace>, placeId: String): FavoritePlace? = places.firstOrNull { it.id == placeId }
}
