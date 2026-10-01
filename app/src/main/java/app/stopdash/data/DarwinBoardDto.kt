package app.stopdash.data

import app.stopdash.domain.Departure
import app.stopdash.domain.NATIONAL_RAIL_MODE
import app.stopdash.domain.TFL_RUN_OPERATORS
import app.stopdash.domain.cleanStopName
import app.stopdash.domain.railLineId
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
)

@Serializable
data class DarwinLocationDto(
    val locationName: String? = null,
    val crs: String? = null,
)

private val UK = ZoneId.of("Europe/London")

/**
 * The board's departures stopdash stands behind (SPEC principle 1): each with an expected time,
 * as an absolute instant so its countdown runs like a TfL prediction. Left out: a cancelled train,
 * one "Delayed" with no estimate, one with no operator, and the TfL-run services TfL's own
 * feed already carries. Times are UK clock times on the board's own date ([generatedAt]), rolled
 * over midnight when they fall far from it. Throws when a board with trains has no readable
 * [generatedAt], or when every train with a time has one it can't read (missing or garbled; those
 * are left out and reported via [warn]), so the failure is reported rather than read as empty.
 */
fun DarwinBoardDto.toDepartures(warn: (String) -> Unit = {}): List<Departure> {
    val services = trainServices.orEmpty()
    if (services.isEmpty()) return emptyList()
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
    val departures = services.mapNotNull { service ->
        if (service.isCancelled) return@mapNotNull null
        if (service.operatorCode?.uppercase() in TFL_RUN_OPERATORS) return@mapNotNull null
        val operator = service.operator?.trim()?.ifBlank { null } ?: return@mapNotNull null
        val expected = when (val etd = service.etd?.trim()) {
            "Delayed", "Cancelled" -> return@mapNotNull null
            // A missing time (no estimate, or "On time" with no schedule) is unreadable, not skipped.
            "On time" -> service.std.orEmpty()
            else -> etd.orEmpty()
        }
        timed++
        val time = parseClock(expected.trim()) ?: run {
            unreadable++
            return@mapNotNull null
        }
        val at = instantNear(now, time) ?: return@mapNotNull null
        // Each place cleaned on its own, so a train dividing for two keeps neither's qualifier.
        val destination = service.destination.orEmpty().mapNotNull { it.locationName?.trim()?.ifBlank { null }?.let(::cleanStopName) }
        Departure(
            lineId = railLineId(operator, service.operatorCode),
            // Named as a rider knows it, here where the feed's name comes in (SPEC *Line pill colors*).
            lineName = riderLineName(operator, NATIONAL_RAIL_MODE),
            direction = "",
            destination = destination.joinToString(" & "),
            platform = service.platform?.trim()?.ifBlank { null }?.let { "Platform $it" },
            expectedArrival = at,
            mode = NATIONAL_RAIL_MODE,
        )
    }
    if (unreadable > 0) {
        // Every timed train unreadable is a failed board (the caller reports it), not an empty one,
        // however many cancelled or TfL-run trains were rightly left out beside them.
        check(unreadable < timed) { "board times unreadable" }
        warn("national rail board: $unreadable train(s) with unreadable times left out")
    }
    return departures
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
