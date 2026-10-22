package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant

/**
 * A station's National Rail trains over the next few hours, as a route page's timetable lists them (maintainer,
 * 2026-10-22: "Darwin, next ~4 hours"): each by when it's due to leave, with what National Rail says of it now. TfL
 * publishes no National Rail timetable, so this is the station's live board asked further ahead
 * ([RailBoardSource.timetable]), and it reaches no further than [HOURS] on from when it was asked: never later in
 * the day, never tomorrow.
 *
 * [complete] is false where part of it was cut short (more trains than National Rail lists at once, a stopdash proxy
 * too old to ask ahead, a train whose time couldn't be read): some trains may be missing. [age] is how old it already
 * was when it came in, as a board's is ([RailBoard.age]).
 */
data class RailTimetable(val trains: List<Train>, val complete: Boolean = true, val age: Duration = Duration.ZERO) {
    /**
     * A train due to leave at [scheduled]. [expected] is its estimate where it isn't its schedule ("On time" is none);
     * [canceled] one that won't run, and [delayed] one National Rail calls "Delayed" with no estimate. [ends] is
     * where it ends, by station code: each portion's, for a train that divides. [serviceId] is National Rail's, which
     * tells the same train apart on two overlapping asks.
     */
    data class Train(
        val scheduled: Instant,
        val lineId: String,
        val ends: Set<String>,
        val expected: Instant? = null,
        val canceled: Boolean = false,
        val delayed: Boolean = false,
        val serviceId: String = "",
    )

    /**
     * [lineId]'s trains, those ending at [end] (a station code) where it's given: how a route page's chip, one per
     * station a line's trains end at ([RouteChoices]), picks out its own.
     */
    @WorkerThread
    fun along(lineId: String, end: String?): RailTimetable =
        copy(trains = trains.filter { it.lineId == lineId && (end == null || end in it.ends) })

    companion object {
        /** How far on a timetable reaches: two of National Rail's longest windows, the second asked as far on as it goes. */
        const val HOURS = 4

        /** How long a timetable's estimates stand as live: past it, only its schedule is shown (SPEC D4). */
        val LIVE_FOR: Duration = Duration.ofMinutes(5)

        /** How often a shown timetable is asked again, for its estimates. */
        val REFRESH: Duration = Duration.ofMinutes(2)

        /**
         * [parts] as one timetable, in order of schedule: a train two of them list (where they overlap) once, by its
         * service id, else by its time, line and ends. Complete only if each is, and as old as the oldest.
         */
        @WorkerThread
        fun joined(parts: List<RailTimetable>): RailTimetable {
            val seen = HashSet<Any>()
            val trains = parts.flatMap { it.trains }
                .filter { seen.add(it.serviceId.ifBlank { Triple(it.scheduled, it.lineId, it.ends) }) }
                .sortedBy { it.scheduled }
            return RailTimetable(trains, parts.all { it.complete }, parts.maxOfOrNull { it.age } ?: Duration.ZERO)
        }
    }
}

/**
 * National Rail timetables for a route page (maintainer, 2026-10-22), asked of [source] by the station codes of TfL's
 * stop ids ([codes], read where it's asked, off the main thread). A failure is reported to [warn] by the station it
 * was asked at and its kind alone, never where it was asked toward (a ride's far end).
 */
class RailTimetables(
    private val source: RailBoardSource,
    private val codes: () -> RailStationCodes,
    private val warn: (String) -> Unit = {},
) {
    /** Whether a timetable can be asked for: a key of the rider's own, or stopdash's proxy. */
    val available: Boolean get() = source.available

    /**
     * [lineId]'s trains from [stopId] over the next [RailTimetable.HOURS] hours: to [to] (a stop id) where it's given,
     * those ending there where [ends] (a route page's way), else every one calling there (a ride's far end). Null where
     * [stopId] or [to] has no station code, so there's no timetable to ask for. Failures as [RailBoardSource]'s
     * ([TflException]), and one where [source] has none to give.
     */
    @WorkerThread
    suspend fun fetch(stopId: String, lineId: String, to: String?, ends: Boolean): RailTimetable? {
        val codes = codes()
        val crs = codes.crsFor(stopId) ?: return null
        val towards = if (to != null) codes.crsFor(to) ?: return null else null
        val timetable = try {
            source.timetable(crs, towards) ?: throw TflException.Unreachable("no national rail key", null)
        } catch (e: TflException) {
            warn("national rail timetable failed: ${e.message} for station $crs")
            throw e
        }
        return timetable.along(lineId, towards?.takeIf { ends })
    }
}
