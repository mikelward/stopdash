package app.stopdash.domain

/**
 * Where the rider was when they tapped the *To…* search's **From** row to change where the trip starts
 * (SPEC *Finding stops → Where a trip starts*, maintainer 2026-09-28): the near-me list's *To…*
 * search, or a searched station's. Kept while the *From…* search is up, so leaving it goes back
 * there rather than abandoning the trip being planned.
 */
sealed interface OriginChange {
    /** From the near-me *To…* search: the trip started from the rider's position. */
    data object NearMe : OriginChange

    /** From the *To…* search of the station [id] ([name]): the trip started there. */
    data class Station(val id: String, val name: String) : OriginChange

    /** Where the rider lands next, as a *To…* search open over it. */
    sealed interface Landing {
        /** The near-me *To…* search. */
        data object NearMePicker : Landing

        /** The *To…* search of the station [id] ([name]). */
        data class StationPicker(val id: String, val name: String) : Landing
    }

    companion object {
        /**
         * Back from the *From…* search: where [change] began, or null (the list) when the search
         * wasn't opened from a *To…* search's From row.
         */
        fun back(change: OriginChange?): Landing? = when (change) {
            null -> null
            NearMe -> Landing.NearMePicker
            is Station -> Landing.StationPicker(change.id, change.name)
        }

        /**
         * "Here" picked in the *From…* search: the trip starts from the rider's position, so a *To…*
         * search that opened it goes on at the near-me one, whichever it began at; else the list (null).
         */
        fun here(change: OriginChange?): Landing? = if (change == null) null else Landing.NearMePicker

        /**
         * What's left of [change] once the rider leaves the *From…* search for [landing]. Back to the
         * station it began at still has that station's stops to load before its *To…* search can
         * appear (and that can fail), so the change stays under way until it does; any other landing
         * ends it.
         */
        fun afterLeaving(change: OriginChange?, landing: Landing?): OriginChange? =
            if (landing is Landing.StationPicker) change else null

        /**
         * The *To…* a station's page leaves behind when the rider backs out of it to the *From…* search.
         * A change of start stays under way until the new station's *To…* search appears, so backing out
         * of one still loading its stops, or that failed, keeps it picking: the next station picked opens
         * at its *To…* search too. With no change under way, nothing is kept.
         */
        fun toAfterStationClosed(change: OriginChange?): ToChoice =
            if (change == null) ToChoice.NONE else ToChoice.NONE.startPicking()
    }
}
