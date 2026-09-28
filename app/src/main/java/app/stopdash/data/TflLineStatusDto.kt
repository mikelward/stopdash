package app.stopdash.data

import app.stopdash.domain.LineStatus
import app.stopdash.domain.ResolvedDisruption
import app.stopdash.domain.mostSevereDisruption
import app.stopdash.domain.resolveDisruption
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
)

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
 * telling current from future needs the dates in the text.
 *
 * An **empty `lineStatuses`** (which the tolerant DTO accepts) is *unknown*, not good
 * service: manufacturing a clean status from absent data would show ordinary countdowns for
 * a line TfL never verified (SPEC principle 1). Returning null lets the caller treat that
 * line as unchecked — the "status unknown" path — rather than verified-clean.
 *
 * [directionsOf] gives the directions an alert's reason was found to affect ([LineAlertDirections]),
 * or null when not looked up; from it the status is also reduced per direction
 * ([LineStatus.byDirection]), so a row only carries alerts for the way it is going.
 */
fun TflLineDto.toLineStatus(directionsOf: (reason: String) -> Set<String>? = { null }): LineStatus? {
    if (lineStatuses.isEmpty()) return null
    val disrupted = lineStatuses
        .filter { it.statusSeverity != LineStatus.GOOD_SERVICE }
        .map { it to resolveDisruption(it.statusSeverityDescription, it.statusSeverity, it.reason) }
    val worst = mostSevereDisruption(disrupted.map { it.second })
    return if (worst != null) {
        val whole = worst.toLineStatus(id)
        // Each direction's worst, from the alerts that apply to it: its own, plus any whose
        // direction isn't known (counted for both, so an unlooked-up alert is never hidden). Kept
        // only when it differs from the line-wide status somewhere, so an unsplit line stays lean.
        val byDirection = LineAlertDirections.DIRECTIONS.associateWith { direction ->
            mostSevereDisruption(
                disrupted.filter { (entry, _) -> directionsOf(entry.reason)?.contains(direction) ?: true }.map { it.second },
            )?.toLineStatus(id) ?: LineStatus(id, LineStatus.GOOD_SERVICE, GOOD_SERVICE_LABEL)
        }
        whole.copy(byDirection = byDirection.takeIf { split -> split.values.any { it != whole } }.orEmpty())
    } else {
        // Non-empty, all good service: name the good status.
        LineStatus(
            lineId = id,
            severity = LineStatus.GOOD_SERVICE,
            description = lineStatuses.first().statusSeverityDescription.ifBlank { GOOD_SERVICE_LABEL },
        )
    }
}

private fun ResolvedDisruption.toLineStatus(lineId: String): LineStatus =
    LineStatus(
        lineId = lineId,
        severity = severity,
        description = label,
        // The chosen disruption's prose, for the route detail view; null when TfL named
        // the status but gave no reason (nothing to expand beyond the chip label).
        fullText = fullText.ifBlank { null },
    )

private const val GOOD_SERVICE_LABEL = "Good Service"
