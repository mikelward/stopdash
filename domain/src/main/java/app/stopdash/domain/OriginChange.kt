package app.stopdash.domain

/**
 * Where the rider was when they tapped a *To…* search's From chip to change where the trip starts
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
         * wasn't opened from a *To…* search's chip.
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
    }
}
