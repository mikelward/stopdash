package app.stopdash.domain

/**
 * Where the rider was when they tapped a **From** row to change where the trip starts (SPEC *Finding
 * stops → Where a trip starts*, maintainer 2026-09-28): the near-me trip's, or a searched station's —
 * its *To…* search, or its routes. Kept while the *From…* search is up, so leaving it goes back there
 * rather than abandoning the trip being planned, and [to] — the trip's *To…* as it was, its search up
 * or a destination picked — goes with the rider to wherever the trip starts next.
 */
sealed interface OriginChange {
    val to: ToChoice

    /** From the near-me trip: it started from the rider's position. */
    data class NearMe(override val to: ToChoice) : OriginChange

    /** From the trip from the station [id] ([name]): it started there. */
    data class Station(val id: String, val name: String, override val to: ToChoice) : OriginChange

    /** Where the rider lands next: a trip with [to] as its *To…*, its search or its routes. */
    sealed interface Landing {
        val to: ToChoice

        /** The near-me trip. */
        data class NearMe(override val to: ToChoice) : Landing

        /** The trip from the station [id] ([name]). */
        data class Station(val id: String, val name: String, override val to: ToChoice) : Landing
    }

    companion object {
        /**
         * The *To…* a change of start keeps: [to] as it was, except that one with no destination picked
         * yet is still its search, whichever way the trip had it open.
         */
        fun kept(to: ToChoice): ToChoice = if (to.hasDestination) to else to.startPicking()

        /**
         * Back from the *From…* search: where [change] began, or null (the list) when the search
         * wasn't opened from a trip's From row.
         */
        fun back(change: OriginChange?): Landing? = when (change) {
            null -> null
            is NearMe -> Landing.NearMe(change.to)
            is Station -> Landing.Station(change.id, change.name, change.to)
        }

        /**
         * "Here" picked in the *From…* search: the trip starts from the rider's position, so a trip
         * that opened it goes on as the near-me one, whichever it began at, with its *To…*; else the
         * list (null).
         */
        fun here(change: OriginChange?): Landing? = change?.let { Landing.NearMe(it.to) }

        /**
         * What's left of [change] once the rider leaves the *From…* search for [landing]. Back to the
         * station it began at still has that station's stops to load before its trip can appear (and
         * that can fail), so the change stays under way until it does; any other landing ends it.
         */
        fun afterLeaving(change: OriginChange?, landing: Landing?): OriginChange? =
            if (landing is Landing.Station) change else null

        /**
         * Whether leaving the *From…* search for [landing] also closes *Lines…* under it, as a stop's
         * page opened from a line's map is: near me, or the list, are the home screen's own, so the
         * line search goes with it. Back to a station stays above it.
         */
        fun closesLines(landing: Landing?): Boolean = landing !is Landing.Station

        /**
         * The *To…* a station's page leaves behind when the rider backs out of it to the *From…* search.
         * A change of start stays under way until the new station's trip appears, so backing out of one
         * still loading its stops, or that failed, keeps the trip's *To…*: the next station picked opens
         * at it too. With no change under way, nothing is kept.
         */
        fun toAfterStationClosed(change: OriginChange?): ToChoice = change?.to ?: ToChoice.NONE
    }
}
