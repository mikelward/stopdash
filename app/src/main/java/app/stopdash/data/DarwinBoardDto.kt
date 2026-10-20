package app.stopdash.data

import androidx.annotation.WorkerThread
import app.stopdash.domain.CallingPortion
import app.stopdash.domain.Departure
import app.stopdash.domain.NATIONAL_RAIL_MODE
import app.stopdash.domain.RailBoard
import app.stopdash.domain.RailFollow
import app.stopdash.domain.RailPoint
import app.stopdash.domain.RailRun
import app.stopdash.domain.Resolved
import app.stopdash.domain.SuppressedRailBoard
import app.stopdash.domain.TFL_RUN_OPERATORS
import app.stopdash.domain.UntimedTrain
import app.stopdash.domain.cleanStopName
import app.stopdash.domain.railMessageText
import app.stopdash.domain.railLineId
import app.stopdash.domain.railVia
import app.stopdash.domain.riderLineName
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A National Rail departure board as Darwin's Live Departure Board service returns it (the Rail
 * Data Marketplace's `GetDepartureBoard`): the fields stopdash reads, the rest ignored.
 */
@Serializable
data class DarwinBoardDto(
    val generatedAt: String? = null,
    // The board's station: its name and code.
    val locationName: String? = null,
    val crs: String? = null,
    val trainServices: List<DarwinServiceDto>? = null,
    // False when Darwin has suppressed the station's board (its access closed at short notice, say): its
    // trains are left out though they may still run, so an empty board says nothing about them.
    val areServicesAvailable: Boolean = true,
    // National Rail's notices for the station, each an HTML fragment: `{"value": "<p>…</p>"}` as the
    // service writes them, read leniently ([messages]) so a changed shape costs the notices, not the board.
    val nrccMessages: List<JsonElement>? = null,
)

/** The board's station notices as plain text ([railMessageText]), in Darwin's order, each once. */
@WorkerThread
fun DarwinBoardDto.messages(): List<String> =
    nrccMessages.orEmpty().mapNotNull { message ->
        val html = when (message) {
            is JsonObject -> message["value"] ?: message["Value"]
            else -> message
        }.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        html?.let(::railMessageText)
    }.distinct()

// A suppressed board ([DarwinBoardDto.areServicesAvailable] false) is a failure the client reports, never
// a board with no trains (Codex, #883): a train followed isn't gone for its station's board going blank.
// Its notices go with the failure, as they often say why (Codex, #938).
private fun DarwinBoardDto.checkAvailable() {
    if (!areServicesAvailable) throw SuppressedRailBoard(messages())
}

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
    // Its stops before this station, one list per portion it joined from, as for the stops after.
    val previousCallingPoints: List<DarwinCallingPointsDto>? = null,
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
    // Scheduled time there, "HH:mm" UK time.
    val st: String? = null,
    // Expected there, a stop still to come: "On time", "HH:mm", or a word with no time ("Delayed",
    // "Cancelled", "No report"). Darwin gives this or [at], never both.
    val et: String? = null,
    // When it was there, a stop it has gone from (only among the stops before a board's station): "On time",
    // "HH:mm", or a word with no time ("No report").
    val at: String? = null,
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
 * failure is reported rather than read as empty, and for a board Darwin has suppressed
 * ([DarwinBoardDto.areServicesAvailable]). An untimed train whose schedule can't be read is left
 * out and reported too. A train's terminus gets TfL's id for it from its code ([stopIdFor]), so its
 * stop list matches it however the board spells the name ([Departure.destinationId]).
 */
fun DarwinBoardDto.toBoard(
    stopIdFor: (String) -> String? = { null },
    // Every TfL stop id of a station code (RailStationCodes.stopIdsFor), for a train's calling points.
    stopIdsFor: (String) -> Set<String> = { emptySet() },
    // A station's records as the station model places them ([CallingPortion.calls]); null without a model.
    placeFor: ((Set<String>) -> Resolved?)? = null,
    warn: (String) -> Unit = {},
): RailBoard {
    checkAvailable()
    // Read whether or not trains run: a disruption that empties the board is when its notice matters most.
    val messages = messages()
    val services = trainServices.orEmpty()
    if (services.isEmpty()) return RailBoard(emptyList(), messages = messages)
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
    val boardCrs = crsOf(crs)
    // Trains whose time didn't read as a clock time or a known word: left out (never a guessed
    // time), and reported below, so a changed feed doesn't pass for an empty timetable.
    var timed = 0
    var unreadable = 0
    var unscheduled = 0
    // Trains listed with a time and a terminus but no schedule to know them by: no trip can follow one.
    var unfollowable = 0
    // Trains to a terminus Darwin has no code for ("???"): none to follow one to (Codex, #883).
    var unknownTerminus = 0
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
        val destinationCrs = crsOf(only?.crs)
        val destinationId = destinationCrs?.let(stopIdFor).orEmpty()
        val lineId = railLineId(operator, service.operatorCode)
        // What a trip follows it by (RailFollow): this station, when it's due to leave, where it ends, and its
        // line. None for a train that divides, whose portions each run their own way.
        val followId = if (boardCrs != null && destinationCrs != null) {
            parseClock(service.std.orEmpty().trim())?.let { scheduleNear(now, it) }?.let { RailFollow.idOf(boardCrs, it, destinationCrs, lineId) }
        } else {
            null
        }
        fun train(at: Instant) = Departure(
            lineId = lineId,
            // Named as a rider knows it, here where the feed's name comes in (SPEC *Line pill colors*).
            lineName = riderLineName(operator, NATIONAL_RAIL_MODE),
            direction = "",
            destination = destination.joinToString(" & "),
            platform = service.platform?.trim()?.ifBlank { null }?.let { "Platform $it" },
            expectedArrival = at,
            mode = NATIONAL_RAIL_MODE,
            destinationId = destinationId,
            via = via,
            callingAt = service.subsequentCallingPoints?.let { callingPortions(it, stopIdsFor, placeFor) },
            railServiceId = service.serviceID?.trim().orEmpty(),
            vehicleId = followId.orEmpty(),
        )
        if (canceled || etd == "Delayed") {
            // Placed by its schedule, never counted down.
            val at = parseClock(service.std.orEmpty().trim())?.let { scheduleNear(now, it) }
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
        if (followId == null && boardCrs != null && destinationCrs != null) unfollowable++
        if (boardCrs != null && only?.crs?.trim() == UNKNOWN_CRS) unknownTerminus++
    }
    if (unreadable > 0) {
        // Every timed train unreadable is a failed board (the caller reports it), not an empty one,
        // however many cancelled or TfL-run trains were rightly left out beside them.
        check(unreadable < timed) { "board times unreadable" }
        warn("national rail board: $unreadable train(s) with unreadable times left out")
    }
    if (unscheduled > 0) warn("national rail board: $unscheduled canceled or delayed train(s) with unreadable schedules left out")
    // Listed, but a trip passes it over for the next it can follow, as it does a train that divides: said here, so
    // a trip that picked a later train can be explained (maintainer, 2026-10-16).
    if (unfollowable > 0) warn("national rail board: $unfollowable train(s) with no readable schedule can't be followed")
    if (unknownTerminus > 0) warn("national rail board: $unknownTerminus train(s) to a terminus with no known code can't be followed")
    return RailBoard(departures, untimed, messages)
}

/**
 * The board's trains as [RailRun]s, for following one (SPEC *On the way*): each with when it's due to
 * leave here and its estimate (none where it's canceled or "Delayed"), its stops before by their
 * scheduled times, each it has gone from with when it was there ([RailPoint.left]) and each it's still
 * to reach with its estimate, and those it calls at after, through its terminus, with their estimates.
 * Left out by what it is: a TfL-run train ([TFL_RUN_OPERATORS]), and one that divides (no one terminus
 * to follow it to). A train it can't read whole (no operator, no terminus, no schedule here, no stops
 * after though it runs from here, stops after that don't reach its terminus, or a stop with no code or,
 * before here, no schedule) fails the board
 * instead (Codex, #883): left out, it would read as a train gone from the station, which only a board
 * listing every train it has can say. Throws, as [toBoard] does, for a board with trains whose time it
 * was made at can't be read, for one Darwin has suppressed, and for one naming no station.
 */
fun DarwinBoardDto.toRuns(): List<RailRun> {
    checkAvailable()
    val services = trainServices.orEmpty()
    if (services.isEmpty()) return emptyList()
    val now = generatedAt?.let {
        try {
            OffsetDateTime.parse(it)
        } catch (e: DateTimeParseException) {
            null
        }
    }?.atZoneSameInstant(UK) ?: throw IllegalStateException("board has no valid generatedAt")
    val atCrs = crsOf(crs) ?: throw IllegalStateException("board names no station")
    val atName = cleanStopName(locationName?.trim().orEmpty())
    fun unreadable(what: String): Nothing = throw IllegalStateException("board has a train with $what")
    // A portion's stops before here, read back along its timetable from [here], its time here: each stop's schedule
    // the latest reading of its clock time at or before the next one's, so a train that left its origin more than
    // six hours, or a day, before the board is still read whole (Codex, #883). One it has gone from has when it was
    // there; one it's still to reach, its estimate (Codex, #883), each read beside its schedule.
    // A stop Darwin has no code for ("???") is passed over, here and in [after]: no board can be asked by it, nor a
    // trip's stop matched to it (Codex, #883).
    fun before(points: List<DarwinCallingPointDto>, here: Instant): List<RailPoint> {
        var later = here
        return points.filterNot { it.crs?.trim() == UNKNOWN_CRS }.asReversed().map { point ->
            val code = point.crs?.trim()?.uppercase()?.ifBlank { null } ?: unreadable("an earlier stop it can't read")
            val scheduled = point.st?.trim()?.let(::parseClock)?.let { instantAtOrBefore(later, it) }
                ?: unreadable("an earlier stop it can't read")
            later = scheduled
            val there = point.at?.trim()?.ifBlank { null }
            val time = (there ?: point.et)?.trim()
            val expected = if (time == "On time") scheduled else time?.let(::parseClock)?.let { instantNear(scheduled.atZone(UK), it) }
            RailPoint(code, cleanStopName(point.locationName?.trim().orEmpty()), scheduled, expected, left = there != null)
        }.asReversed()
    }
    // A portion's stops after here, read on along its timetable from [here], its time here: each stop's schedule the
    // earliest reading of its clock time at or after the one before's, so a train held here past six hours keeps its
    // stops on the day it runs (Codex, #883). Each estimate is read beside the board, as a time still to come.
    fun after(points: List<DarwinCallingPointDto>, here: Instant): List<RailPoint> {
        var earlier = here
        return points.filterNot { it.crs?.trim() == UNKNOWN_CRS }.map { point ->
            val code = point.crs?.trim()?.uppercase()?.ifBlank { null } ?: unreadable("a later stop with no code")
            val scheduled = point.st?.trim()?.let(::parseClock)?.let { instantAtOrAfter(earlier, it) }
            if (scheduled != null) earlier = scheduled
            val expected = if (point.et?.trim() == "On time") scheduled else clockNear(now, point.et)
            RailPoint(code, cleanStopName(point.locationName?.trim().orEmpty()), scheduled, expected)
        }
    }
    return services.mapNotNull { service ->
        if (service.operatorCode?.uppercase() in TFL_RUN_OPERATORS) return@mapNotNull null
        if (service.destination.orEmpty().size > 1) return@mapNotNull null
        val operator = service.operator?.trim()?.ifBlank { null } ?: unreadable("no operator")
        val terminus = service.destination?.singleOrNull()
        // A terminus Darwin has no code for ("???") is none to follow it to: no id, as for one that divides, so it's
        // left out by what it is (Codex, #883).
        if (terminus?.crs?.trim() == UNKNOWN_CRS) return@mapNotNull null
        val destinationCrs = crsOf(terminus?.crs) ?: unreadable("no terminus")
        val scheduled = service.std?.trim()?.let(::parseClock)?.let { scheduleNear(now, it) } ?: unreadable("no schedule")
        val etd = service.etd?.trim()
        val canceled = service.isCancelled || etd == "Cancelled"
        val expected = when {
            canceled -> null
            etd == "On time" -> scheduled
            else -> clockNear(now, etd)
        }
        // A stop it was canceled at isn't one it called at, nor is a stop on a portion it doesn't run on as one
        // train (a join canceled, a change of train): never a picked station it's found again by (Codex, #883).
        // Each portion that joins it is kept apart: a rider on one doesn't pass another's stops (Codex, #883).
        val rideable = service.previousCallingPoints.orEmpty().filter { it.rideable }
        val portions = rideable.map { list -> before(list.callingPoint.orEmpty().filterNot { it.isCancelled }, scheduled) }
        // The portion it runs to its terminus on, canceled stops and all. Darwin's runs there, so stops after none
        // of which reach it are a board read wrong, never a train cut short (Codex, #883).
        val after = service.subsequentCallingPoints.orEmpty().filter { it.rideable && !it.callingPoint.isNullOrEmpty() }
        val through = after.firstOrNull { list -> list.callingPoint!!.last().crs?.trim()?.uppercase() == destinationCrs }
            ?: if (after.isEmpty()) null else unreadable("stops after that don't reach its terminus")
        // A stop on its way that Darwin has no code for is passed over, so the stops given may not be every one it
        // still calls at: a trip's stop could be that one, never to be read as one it won't call at (Codex, #883).
        val unnamed = (rideable.flatMap { it.callingPoint.orEmpty() } + through?.callingPoint.orEmpty())
            .any { !it.isCancelled && it.crs?.trim() == UNKNOWN_CRS }
        RailRun(
            atCrs = atCrs,
            atName = atName,
            lineId = railLineId(operator, service.operatorCode),
            scheduled = scheduled,
            expected = expected,
            platform = service.platform?.trim()?.ifBlank { null }?.let { "Platform $it" },
            destinationCrs = destinationCrs,
            previous = portions.firstOrNull().orEmpty(),
            next = after(through?.callingPoint.orEmpty().filterNot { it.isCancelled }, scheduled)
                // Running from here, it calls somewhere on: no stops after is a board read short, never a train
                // that goes nowhere (Codex, #883).
                .ifEmpty { if (canceled) emptyList() else unreadable("no stops after") },
            canceled = canceled,
            joining = portions.drop(1),
            // With its stops after through its terminus, [RailRun.next] is every one it still calls at, where it was
            // cut short too (Codex, #883). A train canceled here may come with none, which says nothing of where it
            // ends, and one with a stop passed over for want of a code has one more than it gives (Codex, #883).
            throughTerminus = through != null && !unnamed,
        )
    }
}

// A board's "HH:mm" [text] as the instant it stands for on a board made at [now]; null for a word.
private fun clockNear(now: ZonedDateTime, text: String?): Instant? =
    text?.trim()?.let(::parseClock)?.let { instantNear(now, it) }

/**
 * The instant a UK clock [time] on a board made at [now] stands for: the reading on the day before,
 * of or after, and in either offset when the clocks go back and the hour repeats, that lies within
 * six hours before to 18 hours after the board and closest to it. So 00:10 on a board made at 23:40
 * is the next day, and 01:30 on a board made at 01:20 GMT, after the clocks went back, is 01:30 GMT
 * rather than the hour-earlier BST reading.
 */
internal fun instantNear(now: ZonedDateTime, time: LocalTime): Instant? = nearest(now, time, Duration.ofHours(6), Duration.ofHours(18))

/**
 * The instant a train's schedule [time] here, on a board made at [now], stands for: as [instantNear], but read
 * up to 18 hours before the board and less than six after it. A board lists no train due more than about four
 * hours on (Darwin's furthest offset and window), while one held at the station stays on it however late it
 * is, so a train held there more than six hours keeps the day it was picked by, never tomorrow's (Codex, #883).
 */
internal fun scheduleNear(now: ZonedDateTime, time: LocalTime): Instant? = nearest(now, time, Duration.ofHours(18), Duration.ofHours(6))

// The reading of [time] on the day before, of or after [now], in either offset in a repeated hour, from [before]
// ahead of [now] to less than [after] beyond it, closest to it.
private fun nearest(now: ZonedDateTime, time: LocalTime, before: Duration, after: Duration): Instant? {
    val made = now.toInstant()
    return (-1L..1L).flatMap { days -> readings(now.toLocalDate().plusDays(days).atTime(time)) }
        .filter { Duration.between(made, it) >= before.negated() && Duration.between(made, it) < after }
        .minByOrNull { Duration.between(made, it).abs() }
}

// [local]'s instants in the UK: two in the hour the clocks go back, and one the zone reads for a time they skip.
private fun readings(local: LocalDateTime): List<Instant> {
    val offsets = UK.rules.getValidOffsets(local)
    return if (offsets.isEmpty()) listOf(local.atZone(UK).toInstant()) else offsets.map { local.toInstant(it) }
}

/**
 * The earliest reading of UK clock [time] at or after [earlier], in either offset when the clocks go back: a stop
 * after another is due there no sooner, so read on along a train's timetable a time can't be placed before the
 * stop that comes before it, however late the train is running.
 */
internal fun instantAtOrAfter(earlier: Instant, time: LocalTime): Instant? {
    val date = earlier.atZone(UK).toLocalDate()
    return (0L..1L).flatMap { days -> readings(date.plusDays(days).atTime(time)) }.filter { !it.isBefore(earlier) }.minOrNull()
}

/**
 * The latest reading of UK clock [time] at or before [later], in either offset when the clocks go back: a stop
 * before another is due there no later, so read back along a train's timetable a time can't be placed after the
 * stop that follows it, however long before the board the train set out.
 */
internal fun instantAtOrBefore(later: Instant, time: LocalTime): Instant? {
    val date = later.atZone(UK).toLocalDate()
    return (-1L..0L).flatMap { days -> readings(date.plusDays(days).atTime(time)) }.filter { !it.isAfter(later) }.maxOrNull()
}

// Darwin's sign for a station whose code it doesn't know (Codex, #883).
private const val UNKNOWN_CRS = "???"

// A station's code as a board gives it, in capitals; null for none, or for "???", which no board can be asked by.
private fun crsOf(text: String?): String? = text?.trim()?.uppercase()?.ifBlank { null }?.takeIf { it != UNKNOWN_CRS }

// A board's "HH:mm", with or without the asterisk Darwin appends to a time it's unsure of (Codex, #883).
private fun parseClock(text: String): LocalTime? =
    try {
        LocalTime.parse(text.trim().removeSuffix("*"))
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
internal fun callingPortions(
    lists: List<DarwinCallingPointsDto>,
    stopIdsFor: (String) -> Set<String>,
    // Each stop's records as the station model places them ([CallingPortion.calls]); null without a model.
    placeFor: ((Set<String>) -> Resolved?)? = null,
): List<CallingPortion> {
    val rideable = lists.filter { it.rideable }
    if (rideable.isEmpty()) return if (lists.isEmpty()) emptyList() else listOf(CallingPortion(emptySet(), complete = true))
    return rideable.map { list ->
        val points = list.callingPoint.orEmpty().filterNot { it.isCancelled }
        val ids = points.map { point -> point.crs?.trim()?.ifBlank { null }?.let(stopIdsFor).orEmpty() }
        // In calling order, with a model: a stop with no record kept as null in its place, so a portion of
        // nothing but such stops still says what it calls at before anything placed. None without a model.
        val placed = placeFor?.let { place -> ids.map { stop -> stop.takeIf { it.isNotEmpty() }?.let(place) } }.orEmpty()
        CallingPortion(ids.flatten().toSet(), complete = ids.none { it.isEmpty() }, calls = placed)
    }
}
