package app.stopdash.domain

/**
 * A place the rider picked (dev-docs/station-model.md *Saved data*): the TfL [id] it was picked by,
 * its [name] as shown, and the [level] it was picked at, so a screen given a pick opens, plans to and
 * arrives at what was picked rather than guessing from the id. One id can name two levels (a junction
 * is named after its anchor stop), so the level is kept, never inferred, from phase 2 on; a pick saved
 * before has none and is read through [Hubs.picked].
 *
 * A station is picked by one of its records, a bus stop by one of its poles, a hub by its interchange
 * id or, for a junction, a pole of the stop it was picked through. A [Level.PLACE] (a searched or
 * favorite place, no stop at all) carries its position and is never resolved through [Hubs].
 * TfL's ids and the rider's own picks: stored on the device and never logged (*Privacy*).
 */
data class Pick(
    val id: String,
    val name: String,
    val level: Level,
    // A pre-phase-2 station pick's own bus stop, by its lowest-id pole: set only by [Hubs.picked] for
    // a station record whose own-named bus poles were filed under it, so the stop the rider saw stays
    // part of the pick after a refresh that changes which station owns it. Not part of [key].
    val extraStopId: String? = null,
    // A [Level.PLACE]'s position; null for every other level, which the model places.
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    /** A level a rider can pick at; the first three are the model's ([Hubs.Level]). */
    enum class Level(val code: String) {
        STATION("station"),
        STOP("stop"),
        HUB("hub"),
        PLACE("place");

        /** The model's level, or null for a place, which the model doesn't hold. */
        val model: Hubs.Level?
            get() = when (this) {
                STATION -> Hubs.Level.STATION
                STOP -> Hubs.Level.STOP
                HUB -> Hubs.Level.HUB
                PLACE -> null
            }

        companion object {
            /** The level saved as [code], or null for none or one this build doesn't know. */
            fun ofCode(code: String?): Level? = entries.firstOrNull { it.code == code }
        }
    }

    /**
     * Identity for matching two picks (replacing a recent, dropping a duplicate pending end): the
     * level and id. Worked out once, when the pick is made or read, so a list keyed by it reads a string.
     */
    val key: String = "${level.code}:$id"
}
