package app.stopdash.data

import app.stopdash.domain.StopTimetable
import app.stopdash.domain.scheduleDays
import kotlinx.serialization.Serializable

/**
 * TfL's `/Line/{id}/Timetable/{stop}` answer, the parts read: each route's schedules, one per day
 * type, with every departure from the stop. Station intervals, periods and the stations list are
 * ignored ([kotlinx.serialization.json.Json] `ignoreUnknownKeys`).
 */
@Serializable
data class TflTimetableResponseDto(val timetable: TflTimetableDto? = null) {
    /**
     * As a [StopTimetable]. A schedule whose days can't be read is left out, and the timetable marked
     * unreadable, since it might run on any day ([StopTimetable.readable]); one with a journey whose
     * time can't be read, or with no journey list at all, is kept but marked incomplete, so its days
     * can't be said to have nothing due ([StopTimetable.DaySchedule.complete]).
     */
    fun toStopTimetable(): StopTimetable {
        val all = timetable?.routes.orEmpty().flatMap { it.schedules }
        val read = all.mapNotNull { schedule ->
            val days = scheduleDays(schedule.name)
            if (days.isEmpty()) return@mapNotNull null
            val minutes = schedule.knownJourneys?.map { it.minutes() }
            StopTimetable.DaySchedule(days, minutes.orEmpty().filterNotNull(), complete = minutes != null && null !in minutes)
        }
        return StopTimetable(read, readable = read.size == all.size)
    }
}

@Serializable
data class TflTimetableDto(val routes: List<TflTimetableRouteDto> = emptyList())

@Serializable
data class TflTimetableRouteDto(val schedules: List<TflScheduleDto> = emptyList())

@Serializable
// [knownJourneys] is null when TfL leaves it out, which isn't the same as an empty list: a schedule
// with no journeys runs nothing that day, one with none sent says nothing about it.
data class TflScheduleDto(val name: String = "", val knownJourneys: List<TflKnownJourneyDto>? = null)

/** One departure: TfL sends its hour (24 and over past midnight) and minute as strings. */
@Serializable
data class TflKnownJourneyDto(val hour: String = "", val minute: String = "") {
    fun minutes(): Int? {
        val h = hour.trim().toIntOrNull() ?: return null
        val m = minute.trim().toIntOrNull() ?: return null
        return if (h >= 0 && m in 0..59) h * 60 + m else null
    }
}
