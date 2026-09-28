package app.stopdash.domain

import java.time.DayOfWeek

/**
 * The kind of a saved favorite place (SPEC D9). HOME, WORK and SCHOOL are single named slots;
 * CUSTOM is a user-named place, and there may be any number of them.
 */
enum class FavoriteKind { HOME, WORK, SCHOOL, CUSTOM }

/**
 * A place the user saves to route to (SPEC D9). Stored by **coordinate** — plus a human [label] and
 * the resolved [placeName] for display — so a trip plans to the coordinate and TfL walks the last
 * leg; no stop is pinned, so a closed local station never breaks the trip. The coordinate is
 * Location data (the type already declared for the nearby lookup), sent only to TfL, and is handled
 * like watched-stop data: never logged or placed in any off-device artifact of ours. It rides
 * Android backup with the rest of the user's config (a user-controlled platform channel, SPEC
 * *Privacy*).
 *
 * [id] is stable across edits so a favorite can be edited or removed by identity — a CUSTOM place
 * especially, since several may coexist. HOME, WORK and SCHOOL are singletons: [FavoritePlaces]
 * keeps at most one of each.
 *
 * [showOnDays] are the days the place is offered as a one-tap route chip on the near-me list (SPEC
 * *Routing from the near-me list*) — Work on weekdays, say. Every day by default, so a place saved
 * before the choice existed keeps its chip; empty means never.
 */
data class FavoritePlace(
    val id: String,
    val kind: FavoriteKind,
    val label: String,
    val coordinate: Coordinates,
    val placeName: String? = null,
    val showOnDays: Set<DayOfWeek> = EVERY_DAY,
) {
    /** Whether the place's chip shows on the near-me list on [day]. */
    fun showsOn(day: DayOfWeek): Boolean = day in showOnDays

    companion object {
        val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.values().toSet()
    }
}

/**
 * Pure operations over the ordered favorites list — JVM-testable, no Android. The list order is the
 * display order; [upsert] keeps a replaced entry in place rather than moving it to the end.
 */
object FavoritePlaces {
    /** The single HOME favorite, or null when unset. */
    fun home(places: List<FavoritePlace>): FavoritePlace? = places.firstOrNull { it.kind == FavoriteKind.HOME }

    /** The single WORK favorite, or null when unset. */
    fun work(places: List<FavoritePlace>): FavoritePlace? = places.firstOrNull { it.kind == FavoriteKind.WORK }

    /**
     * Add or replace [place]. A singleton kind (HOME/WORK/SCHOOL) replaces any existing one of that
     * kind — matched by kind, keeping its position; a CUSTOM place replaces one with the same [id],
     * or is appended when none matches. This is the only write path, so at most one HOME, WORK and
     * SCHOOL ever exist.
     */
    fun upsert(places: List<FavoritePlace>, place: FavoritePlace): List<FavoritePlace> {
        val matchIndex = when (place.kind) {
            FavoriteKind.CUSTOM -> places.indexOfFirst { it.id == place.id }
            else -> places.indexOfFirst { it.kind == place.kind }
        }
        return if (matchIndex >= 0) {
            places.toMutableList().also { it[matchIndex] = place }
        } else {
            places + place
        }
    }

    /** The places whose chip shows on the near-me list on [day], in list order. */
    fun onMainScreen(places: List<FavoritePlace>, day: DayOfWeek): List<FavoritePlace> =
        places.filter { it.showsOn(day) }

    /** Remove the favorite with [id], if present. */
    fun remove(places: List<FavoritePlace>, id: String): List<FavoritePlace> =
        places.filterNot { it.id == id }
}
