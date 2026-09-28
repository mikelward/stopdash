package app.stopdash.data

import app.stopdash.domain.AlertStart
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.ResolvedDisruption
import app.stopdash.domain.mostSevereDisruption
import app.stopdash.domain.resolveDisruption
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * One line from TfL's `/Line/{ids}/Status` response. Only the fields stopdash maps are
 * declared; the client's `Json { ignoreUnknownKeys = true }` drops the rest. A line
 * carries a list of [TflLineStatusEntryDto] — usually one ("Good Service"), several when
 * disrupted.
 */
@Serializable
data class TflLineDto(
    val id: String = "",
    val name: String = "",
    val lineStatuses: List<TflLineStatusEntryDto> = emptyList(),
)

@Serializable
data class TflLineStatusEntryDto(
    val statusSeverity: Int = LineStatus.GOOD_SERVICE,
    val statusSeverityDescription: String = "",
    // TfL's full human-readable text for this status. Vague on its own for buses (the
    // description is often just "Special Service"), so it is scanned to name the actual
    // disruption ("Diversion") — see [resolveDisruption].
    val reason: String = "",
    // Only filled in by a `?detail=true` request ([LineAlertDirections]): the plain one leaves
    // `affectedRoutes` empty, and the detail is ~40× the size, so it is fetched once per new alert.
    val disruption: TflLineDisruptionDto? = null,
    // When TfL posted the alert (`fromDate`) — not when the work starts, which only the text says
    // ([AlertStart]), but the anchor that places a start written without its year.
    val validityPeriods: List<TflValidityPeriodDto> = emptyList(),
)

@Serializable
data class TflValidityPeriodDto(
    val fromDate: String? = null,
)

/** When TfL posted this entry: its earliest `fromDate`, or null when it gave none. */
fun TflLineStatusEntryDto.postedAt(): Instant? =
    validityPeriods.mapNotNull { period -> period.fromDate?.let { runCatching { Instant.parse(it) }.getOrNull() } }.minOrNull()

/** The `fromDate`s TfL gave for this entry that can't be read, so none of them anchors a year. */
fun TflLineStatusEntryDto.unreadablePostedDates(): List<String> =
    validityPeriods.mapNotNull { it.fromDate?.takeIf { raw -> runCatching { Instant.parse(raw) }.isFailure } }

@Serializable
data class TflLineDisruptionDto(
    val affectedRoutes: List<TflAffectedRouteDto> = emptyList(),
)

// One route an alert affects; only its TfL direction (`inbound`/`outbound`) is read. The route's
// stop list rides along in the detail response but isn't declared, so it is skipped.
@Serializable
data class TflAffectedRouteDto(
    val direction: String = "",
)

/** The directions of travel TfL scopes this entry's alert to (`inbound`, `outbound`), if any. */
fun TflLineStatusEntryDto.affectedDirections(): Set<String> =
    disruption?.affectedRoutes.orEmpty()
        .map { it.direction.trim().lowercase() }
        .filterTo(HashSet()) { it in LineAlertDirections.DIRECTIONS }

/**
 * Reduces a line's statuses to the one the surfaces show: its worst disruption if any, else
 * good service — or **null when TfL reported no status at all**.
 *
 * Every non-good entry is resolved to a `(label, severity)` by [resolveDisruption] — TfL's
 * own wording where it names the disruption, a concise label read from the reason where the
 * wording is only a vague "Special Service" — and the most severe of them (lowest severity)
 * is shown. Reducing every entry to the same shape is what lets a diversion inferred from a
 * severity-0 catch-all be compared honestly against a graded delay: the catch-all neither
 * always wins (severity 0 sorts as most severe) nor is discarded when it is in fact the
 * worse of the two. A disrupted line is always kept disrupted — its countdowns are never
 * presented as verified-clean (SPEC principle 1).
 *
 * TfL's `isNow` flag is deliberately **not** used to hide a "future" alert: it reads `false`
 * even for planned closures currently in effect (a Sunday Overground closure in progress),
 * so it marks "unplanned", not "current", and filtering on it would hide live disruptions —
 * telling current from future needs the dates in the text, which [now] enables below.
 *
 * An **empty `lineStatuses`** (which the tolerant DTO accepts) is *unknown*, not good
 * service: manufacturing a clean status from absent data would show ordinary countdowns for
 * a line TfL never verified (SPEC principle 1). Returning null lets the caller treat that
 * line as unchecked — the "status unknown" path — rather than verified-clean.
 *
 * [directionsOf] gives the directions an alert's reason was found to affect ([LineAlertDirections]),
 * or null when not looked up; from it the status is also reduced per direction
 * ([LineStatus.byDirection]), so a row only carries alerts for the way it is going.
 *
 * Given [now], an alert whose text says it starts on a later day ([AlertStart]) is kept out of the
 * disruption and listed in [LineStatus.planned] instead; one that can't be dated, or whose posting
 * date can't be read ([onBadDate] is told), counts as under way. Without [now] every alert counts as under way, as before.
 */
fun TflLineDto.toLineStatus(
    now: Instant? = null,
    // Told of a posting date that can't be read, the raw value only (TfL's public text, no user data).
    onBadDate: (String) -> Unit = {},
    directionsOf: (reason: String) -> Set<String>? = { null },
): LineStatus? {
    if (lineStatuses.isEmpty()) return null
    val today = now?.atZone(AlertStart.ZONE)?.toLocalDate()
    val alerts = lineStatuses
        .filter { it.statusSeverity != LineStatus.GOOD_SERVICE }
        .map { entry ->
            Alert(
                entry = entry,
                resolved = resolveDisruption(entry.statusSeverityDescription, entry.statusSeverity, entry.reason),
                // Set only for work that hasn't started ([AlertStart]); null is under way, or unknown.
                startsOn = now?.takeIf { entry.readablePostedDate(onBadDate) }
                    ?.let { AlertStart.startDate(entry.reason, it, entry.postedAt()) }?.takeIf { it.isAfter(today) },
            )
        }
    // A good status names TfL's own good wording where it gave one; a line whose only alerts are
    // still to come is a good service now, never named after the alert ("Special Service").
    val good = LineStatus(
        lineId = id,
        severity = LineStatus.GOOD_SERVICE,
        description = lineStatuses.firstOrNull { it.statusSeverity == LineStatus.GOOD_SERVICE }
            ?.statusSeverityDescription?.ifBlank { null } ?: GOOD_SERVICE_LABEL,
    )
    // The worst alert under way among those [applies] to, else good service, with the ones still to
    // come beside it, soonest first.
    fun reduce(applies: (TflLineStatusEntryDto) -> Boolean): LineStatus {
        val mine = alerts.filter { applies(it.entry) }
        val current = mostSevereDisruption(mine.filter { it.startsOn == null }.map { it.resolved })
        val planned = mine.mapNotNull { alert ->
            alert.startsOn?.let { PlannedAlert(alert.resolved.label, alert.resolved.fullText, it, alert.resolved.severity, alert.resolved.isFallback) }
        }.sortedBy { it.startsOn }.distinct()
        return (current?.toLineStatus(id) ?: good).copy(planned = planned)
    }
    val whole = reduce { true }
    if (alerts.isEmpty()) return whole
    // Each direction's own, from the alerts that apply to it: its own, plus any whose direction
    // isn't known (counted for both, so an unlooked-up alert is never hidden). Kept only when it
    // differs from the line-wide status somewhere, so an unsplit line stays lean.
    val byDirection = LineAlertDirections.DIRECTIONS.associateWith { direction ->
        reduce { entry -> directionsOf(entry.reason)?.contains(direction) ?: true }
    }
    return whole.copy(byDirection = byDirection.takeIf { split -> split.values.any { it != whole } }.orEmpty())
}

// False when TfL gave a posting date that can't be read: without the anchor a missing year is a
// guess, so the alert stays under way, the safe side (Codex, PR #337), and the oddity is reported.
private fun TflLineStatusEntryDto.readablePostedDate(onBadDate: (String) -> Unit): Boolean {
    val bad = unreadablePostedDates()
    bad.forEach(onBadDate)
    return bad.isEmpty()
}

private class Alert(
    val entry: TflLineStatusEntryDto,
    val resolved: ResolvedDisruption,
    val startsOn: LocalDate?,
)

private fun ResolvedDisruption.toLineStatus(lineId: String): LineStatus =
    LineStatus(
        lineId = lineId,
        severity = severity,
        description = label,
        // The chosen disruption's prose, for the route detail view; null when TfL named
        // the status but gave no reason (nothing to expand beyond the chip label).
        fullText = fullText.ifBlank { null },
        isFallback = isFallback,
    )

private const val GOOD_SERVICE_LABEL = "Good Service"
