package app.stopdash.data

import app.stopdash.domain.CallingPortion
import app.stopdash.domain.Departure
import app.stopdash.domain.NATIONAL_RAIL_MODE
import app.stopdash.domain.RailBoard
import app.stopdash.domain.TFL_RUN_OPERATORS
import app.stopdash.domain.UntimedTrain
import app.stopdash.domain.cleanStopName
import app.stopdash.domain.railLineId
import app.stopdash.domain.railVia
import app.stopdash.domain.riderLineName
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

/**
 * A National Rail departure board as Darwin's Live Departure Board service returns it (the Rail
 * Data Marketplace's `GetDepartureBoard`): the fields stopdash reads, the rest ignored.
 */
@Serializable
data class DarwinBoardDto(
    val generatedAt: String? = null,
    val trainServices: List<DarwinServiceDto>? = null,
)

@Serializable
data class DarwinServiceDto(
    // Scheduled departure, "HH:mm" UK time.
    val std: String? = null,
    // Expected departure: "On time", "HH:mm", "Delayed" (no estimate) or "Cancelled".
    val etd: String? = null,
    val platform: String? = null,
    val operator: String? = null,
    val operatorCode: String? = null,
    val isCancelled: Boolean = false,
    val destination: List<DarwinLocationDto>? = null,
    // Darwin's id for the service, which pairs it across two boards of the same station.
    val serviceID: String? = null,
    // Its stops after this station, one list per portion it divides into: only on a board asked for
    // with details (`GetDepBoardWithDetails`).
    val subsequentCallingPoints: List<DarwinCallingPointsDto>? = null,
)

@Serializable
data class DarwinCallingPointsDto(
    val callingPoint: List<DarwinCallingPointDto>? = null,
    // A portion it divides into that won't run today: its stops can't be reached on this train.
    val assocIsCancelled: Boolean = false,
    // A portion reached only by changing to another service: not stops this train makes.
    val serviceChangeRequired: Boolean = false,
) {
    /** Whether a rider who boards this train can stay on it to these stops. */
    val rideable: Boolean get() = !assocIsCancelled && !serviceChangeRequired
}

@Serializable
data class DarwinCallingPointDto(
    val locationName: String? = null,
    val crs: String? = null,
    // A stop it won't make today: not one it calls at.
    val isCancelled: Boolean = false,
)

@Serializable
data class DarwinLocationDto(
    val locationName: String? = null,
    val crs: String? = null,
    // "via Wimbledon": the stations it runs by, where two ways reach this destination.
    val via: String? = null,
)

private val UK = ZoneId.of("Europe/London")

/** The board's trains with a time ([toBoard]'s departures). */
fun DarwinBoardDto.toDepartures(stopIdFor: (String) -> String? = { null }, warn: (String) -> Unit = {}): List<Departure> =
    toBoard(stopIdFor, warn = warn).departures

/**
 * The board as stopdash shows it (SPEC principle 1): each train with an expected time, as an absolute
 * instant so its countdown runs like a TfL prediction, and apart from them each canceled train and
 * each "Delayed" with no estimate, at its scheduled time and never counted down ([RailBoard.untimed]).
 * Left out: a train with no operator, and the TfL-run services TfL's own feed already carries. Times
 * are UK clock times on the board's own date ([generatedAt]), rolled over midnight when they fall far
 * from it. Throws when a board with trains has no readable [generatedAt], or when every train with a
 * time has one it can't read (missing or garbled; those are left out and reported via [warn]), so the
 * failure is reported rather than read as empty. An untimed train whose schedule can't be read is left
 * out and reported too. A train's terminus gets TfL's id for it from its code ([stopIdFor]), so its
 * stop list matches it however the board spells the name ([Departure.destinationId]).
 */
fun DarwinBoardDto.toBoard(
    stopIdFor: (String) -> String? = { null },
    // Every TfL stop id of a station code (RailStationCodes.stopIdsFor), for a train's calling points.
    stopIdsFor: (String) -> Set<String> = { emptySet() },
    warn: (String) -> Unit = {},
): RailBoard {
    val services = trainServices.orEmpty()
    if (services.isEmpty()) return RailBoard(emptyList())
    // A board with trains but no readable time it was made at can't date them: a failure (the
    // client reports it), not a quiet "no departures".
    val generated = generatedAt?.let {
        try {
            OffsetDateTime.parse(it)
        } catch (e: DateTimeParseException) {
            null
        }
    } ?: throw IllegalStateException("board has no valid generatedAt")
    val now = generated.atZoneSameInstant(UK)
    // Trains whose time didn't read as a clock time or a known word: left out (never a guessed
    // time), and reported below, so a changed feed doesn't pass for an empty timetable.
    var timed = 0
    var unreadable = 0
    var unscheduled = 0
    val departures = mutableListOf<Departure>()
    val untimed = mutableListOf<UntimedTrain>()
    for (service in services) {
        if (service.operatorCode?.uppercase() in TFL_RUN_OPERATORS) continue
        val operator = service.operator?.trim()?.ifBlank { null } ?: continue
        val etd = service.etd?.trim()
        val canceled = service.isCancelled || etd == "Cancelled"
        // Each place cleaned on its own, so a train dividing for two keeps neither's qualifier.
        val destination = service.destination.orEmpty().mapNotNull { it.locationName?.trim()?.ifBlank { null }?.let(::cleanStopName) }
        // Only a train with one destination: a dividing train's portions each run their own way.
        val only = service.destination?.singleOrNull()
        val via = only?.let { railVia(it.via) }.orEmpty()
        val destinationId = only?.crs?.trim()?.ifBlank { null }?.let(stopIdFor).orEmpty()
        fun train(at: Instant) = Departure(
            lineId = railLineId(operator, service.operatorCode),
            // Named as a rider knows it, here where the feed's name comes in (SPEC *Line pill colors*).
            lineName = riderLineName(operator, NATIONAL_RAIL_MODE),
            direction = "",
            destination = destination.joinToString(" & "),
            platform = service.platform?.trim()?.ifBlank { null }?.let { "Platform $it" },
            expectedArrival = at,
            mode = NATIONAL_RAIL_MODE,
            destinationId = destinationId,
            via = via,
            callingAt = service.subsequentCallingPoints?.let { callingPortions(it, stopIdsFor) },
            railServiceId = service.serviceID?.trim().orEmpty(),
        )
        if (canceled || etd == "Delayed") {
            // Placed by its schedule, never counted down.
            val at = parseClock(service.std.orEmpty().trim())?.let { instantNear(now, it) }
            if (at == null) unscheduled++ else untimed += UntimedTrain(train(at), canceled)
            continue
        }
        val expected = when (etd) {
            // A missing time (no estimate, or "On time" with no schedule) is unreadable, not skipped.
            "On time" -> service.std.orEmpty()
            else -> etd.orEmpty()
        }
        timed++
        val time = parseClock(expected.trim()) ?: run {
            unreadable++
            continue
        }
        val at = instantNear(now, time) ?: continue
        departures += train(at)
    }
    if (unreadable > 0) {
        // Every timed train unreadable is a failed board (the caller reports it), not an empty one,
        // however many cancelled or TfL-run trains were rightly left out beside them.
        check(unreadable < timed) { "board times unreadable" }
        warn("national rail board: $unreadable train(s) with unreadable times left out")
    }
    if (unscheduled > 0) warn("national rail board: $unscheduled canceled or delayed train(s) with unreadable schedules left out")
    return RailBoard(departures, untimed)
}

/**
 * The instant a UK clock [time] on a board made at [now] stands for: the reading on the day before,
 * of or after, and in either offset when the clocks go back and the hour repeats, that lies within
 * six hours before to 18 hours after the board and closest to it. So 00:10 on a board made at 23:40
 * is the next day, and 01:30 on a board made at 01:20 GMT, after the clocks went back, is 01:30 GMT
 * rather than the hour-earlier BST reading.
 */
internal fun instantNear(now: ZonedDateTime, time: LocalTime): Instant? {
    val made = now.toInstant()
    return (-1L..1L).flatMap { days ->
        val local = now.toLocalDate().plusDays(days).atTime(time)
        val offsets = UK.rules.getValidOffsets(local)
        // A time the clocks skip (spring forward) has no offset: read it as the zone does.
        if (offsets.isEmpty()) listOf(local.atZone(UK).toInstant()) else offsets.map { local.toInstant(it) }
    }
        .filter { Duration.between(made, it) >= Duration.ofHours(-6) && Duration.between(made, it) < Duration.ofHours(18) }
        .minByOrNull { Duration.between(made, it).abs() }
}

private fun parseClock(text: String): LocalTime? =
    try {
        LocalTime.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }

/**
 * A train's [lists] of calling points as [CallingPortion]s: each portion's stops by every TfL id of
 * their station, less those it won't make; complete when every stop had one. Only portions the
 * rider can stay aboard for count ([DarwinCallingPointsDto.rideable]): one that won't run today, or
 * one reached by changing service, can't be boarded, so it's left out rather than counted as a way to
 * reach its stops. A train with no rideable portion at all reaches none of them: one complete portion
 * calling nowhere, a sure "misses", never no answer the route would then fill in.
 */
internal fun callingPortions(lists: List<DarwinCallingPointsDto>, stopIdsFor: (String) -> Set<String>): List<CallingPortion> {
    val rideable = lists.filter { it.rideable }
    if (rideable.isEmpty()) return if (lists.isEmpty()) emptyList() else listOf(CallingPortion(emptySet(), complete = true))
    return rideable.map { list ->
        val points = list.callingPoint.orEmpty().filterNot { it.isCancelled }
        val ids = points.map { point -> point.crs?.trim()?.ifBlank { null }?.let(stopIdsFor).orEmpty() }
        CallingPortion(ids.flatten().toSet(), complete = ids.none { it.isEmpty() })
    }
}
