package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.DayOfWeek

/**
 * Which saved favorite places the near-me list offers as one-tap routes (SPEC D9 → *Routing from
 * the near-me list*): every favorite, less the ones the rider is already at — "Home" while standing
 * at home is a route to nowhere.
 *
 * The rule leans towards **showing** a place. A chip wrongly hidden costs the one-tap route (the
 * rider has to go through To…); a chip wrongly shown costs a little space. So a place is hidden only
 * on a [precise] fix — a fallback, coarse or failed-to-update position shows every place — and it
 * takes a hysteresis band to come back: hidden within [HIDE_WITHIN_METERS], shown again only beyond
 * [SHOW_BEYOND_METERS], so a fix wandering across one line doesn't make the chip flicker.
 *
 * Pure and JVM-testable. The coordinate comparison happens in memory only; nothing here is logged.
 */
object FavoriteShortcuts {
    /** Nearer than this to a place on a precise fix, and its chip is hidden. */
    const val HIDE_WITHIN_METERS = 200.0

    /** A hidden place's chip comes back only once the rider is farther than this from it. */
    const val SHOW_BEYOND_METERS = 250.0

    /** A fix reporting worse accuracy than this is too rough to hide a place on. */
    const val MAX_ACCURACY_METERS = 100f

    /**
     * Whether a fix reporting [accuracyMeters] is close enough to hide a place on. Unknown accuracy
     * counts as rough, since the rule leans towards showing; an approximate-only location grant
     * reports its kilometer-scale radius here, which is how it is told apart (Codex).
     */
    fun isAccurate(accuracyMeters: Float?): Boolean = accuracyMeters != null && accuracyMeters <= MAX_ACCURACY_METERS

    /**
     * The key a place is remembered by: its stable id **and** its coordinate, so editing a hidden
     * favorite to a new spot starts it afresh rather than inheriting "the rider is at it" from where
     * it used to be (Codex). The coordinate goes in as a hash only — the memory is saved with the
     * screen's state, and a coordinate has no business there (SPEC *Privacy*).
     */
    fun memoryKey(place: FavoritePlace): String =
        "${place.id}#${place.coordinate.hashCode().toUInt().toString(16)}"

    /**
     * The hysteresis memory after a fix at [at]: the [memoryKey]s of [places] the rider is at. [hiddenBefore]
     * is the previous memory; a place between the two radii keeps whatever it was.
     *
     * On an imprecise fix (or none) the memory is **kept, not cleared** (Codex): such a fix shows every
     * chip for as long as it lasts ([shown]), but says nothing about whether the rider left, so the
     * next precise fix in the 200–250 m band must still find the place hidden. Only keys still among
     * [places] are kept, so a deleted or moved favorite drops out.
     */
    @WorkerThread
    fun hiddenIds(
        places: List<FavoritePlace>,
        at: Coordinates?,
        precise: Boolean,
        hiddenBefore: Set<String> = emptySet(),
    ): Set<String> {
        if (at == null || !precise) {
            return places.mapNotNullTo(LinkedHashSet()) { place -> memoryKey(place).takeIf { it in hiddenBefore } }
        }
        return places.mapNotNullTo(LinkedHashSet()) { place ->
            val meters = NearestStops.distanceMeters(
                at.latitude, at.longitude, place.coordinate.latitude, place.coordinate.longitude,
            )
            val hide = when {
                meters <= HIDE_WITHIN_METERS -> true
                meters > SHOW_BEYOND_METERS -> false
                else -> memoryKey(place) in hiddenBefore
            }
            memoryKey(place).takeIf { hide }
        }
    }

    /**
     * The chips to show: [places] in their saved order, less those in [hidden] — but every one of
     * them on an imprecise fix, where hiding would be a guess ([hiddenIds] keeps the memory meanwhile)
     * — and, given [today], less those not set to show that day. The day is applied here, after the
     * memory, rather than to the places [hiddenIds] sees: a place off today keeps its "already there"
     * state, so it doesn't come back inside the 200–250 m band on its next day (Codex).
     */
    @WorkerThread
    fun shown(
        places: List<FavoritePlace>,
        hidden: Set<String>,
        precise: Boolean = true,
        today: DayOfWeek? = null,
    ): List<FavoritePlace> {
        val near = if (!precise) places else places.filterNot { memoryKey(it) in hidden }
        return if (today == null) near else FavoritePlaces.onMainScreen(near, today)
    }
}
