package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * How an interchange opened from one of its station names ([StationMatch.lead]) orders its stations
 * (SPEC *Finding stops → Find a station*): that name's [modes] first, in their order, and within a
 * mode the stations of that [name] first, so "St Pancras International" opens on St Pancras's own
 * trains before King's Cross's, and "King's Cross" on the Underground.
 */
data class StationLead(val name: String, val modes: List<String>) {
    // Worked out at the first ranking, on the list's worker: building a lead does no work.
    private val own by lazy { StationMatcher.normalize(StationIndex.memberName(name)).lowercase() }

    /** Where a row of [mode] at the stop named [stopName] goes among the interchange's: lower first. */
    @WorkerThread
    fun rankOf(mode: String, stopName: String): Int {
        val modeRank = modes.indexOf(mode).let { if (it < 0) modes.size else it }
        return modeRank * 2 + stopRank(stopName)
    }

    /** 0 for a stop of this name's own station, 1 for another of the interchange's. */
    @WorkerThread
    fun stopRank(stopName: String): Int =
        if (StationMatcher.normalize(StationIndex.memberName(stopName)).lowercase() == own) 0 else 1

    companion object {
        /** The lead [match] opens with, or null for a row that isn't one of an interchange's names. */
        fun of(match: StationMatch): StationLead? = match.lead.takeIf { it.isNotEmpty() }?.let { StationLead(match.name, it) }
    }
}
