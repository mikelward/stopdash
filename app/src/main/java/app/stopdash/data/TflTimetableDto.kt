package app.stopdash.data

import app.stopdash.domain.StopTimetable
import app.stopdash.domain.scheduleDays
import kotlinx.serialization.Serializable

/**
 * TfL's `/Line/{id}/Timetable/{stop}` answer, the parts read: each route's schedules, one per day
 * type, with every departure from the stop, and its station intervals, the stops each departure's
 * pattern calls at after it. Periods and the stations list are ignored
 * ([kotlinx.serialization.json.Json] `ignoreUnknownKeys`).
 */
@Serializable
data class TflTimetableResponseDto(val timetable: TflTimetableDto? = null) {
    /**
     * As a [StopTimetable]. A schedule whose days can't be read is left out, and the timetable marked
     * unreadable, since it might run on any day ([StopTimetable.readable]); one with a journey whose
     * time can't be read (one reaching [reaching], with it), or with no journey list at all, is kept but marked incomplete, so its days
     * can't be said to have nothing due ([StopTimetable.DaySchedule.complete]).
     *
     * With [reaching], only the departures whose pattern calls there after the stop: a branching
     * line's trains for the other branch don't take a rider there. One whose pattern can't be read
     * leaves its schedule incomplete, since it might be one that does.
     */
    fun toStopTimetable(reaching: String? = null): StopTimetable {
        val routes = timetable?.routes.orEmpty()
        val all = routes.flatMap { route -> route.schedules.map { route to it } }
        val read = all.mapNotNull { (route, schedule) ->
            val days = scheduleDays(schedule.name)
            if (days.isEmpty()) return@mapNotNull null
            // Each pattern's stops by its id; a departure reaches [reaching] if its pattern calls there.
            // A pattern whose id can't be read matches no departure: it's left out, not keyed as null.
            val calls = route.stationIntervals.mapNotNull { interval -> interval.id.trim().toIntOrNull()?.let { it to interval.intervals.map { stop -> stop.stopId } } }.toMap()
            var complete = schedule.knownJourneys != null
            val minutes = schedule.knownJourneys.orEmpty().mapNotNull { journey ->
                // One whose pattern is known not to reach [reaching] is no departure for the rider, so
                // its time needn't be read: unreadable, it doesn't leave the schedule incomplete.
                if (reaching != null) {
                    val pattern = calls[journey.intervalId] ?: run { complete = false; return@mapNotNull null }
                    if (reaching !in pattern) return@mapNotNull null
                }
                journey.minutes() ?: run { complete = false; null }
            }
            StopTimetable.DaySchedule(days, minutes, complete = complete)
        }
        return StopTimetable(read, readable = read.size == all.size)
    }
}

@Serializable
data class TflTimetableDto(val routes: List<TflTimetableRouteDto> = emptyList())

@Serializable
data class TflTimetableRouteDto(
    val schedules: List<TflScheduleDto> = emptyList(),
    val stationIntervals: List<TflStationIntervalDto> = emptyList(),
)

/** One calling pattern from the stop: its [id] (a departure's `intervalId`) and the stops it calls at after. */
@Serializable
data class TflStationIntervalDto(val id: String = "", val intervals: List<TflIntervalDto> = emptyList())

@Serializable
data class TflIntervalDto(val stopId: String = "")

@Serializable
// [knownJourneys] is null when TfL leaves it out, which isn't the same as an empty list: a schedule
// with no journeys runs nothing that day, one with none sent says nothing about it.
data class TflScheduleDto(val name: String = "", val knownJourneys: List<TflKnownJourneyDto>? = null)

/**
 * One departure: TfL sends its hour (24 and over past midnight) and minute as strings, and which
 * calling pattern it runs ([TflStationIntervalDto.id]).
 */
@Serializable
data class TflKnownJourneyDto(val hour: String = "", val minute: String = "", val intervalId: Int? = null) {
    fun minutes(): Int? {
        val h = hour.trim().toIntOrNull() ?: return null
        val m = minute.trim().toIntOrNull() ?: return null
        return if (h >= 0 && m in 0..59) h * 60 + m else null
    }
}
