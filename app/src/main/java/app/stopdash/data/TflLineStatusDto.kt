package app.stopdash.data

import app.stopdash.domain.AlertStart
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PartClosure
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.ResolvedDisruption
import app.stopdash.domain.mostSevereDisruption
import app.stopdash.domain.resolveDisruption
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * One line from TfL's `/Line/{ids}/Status` response. Only the fields LDN Go maps are
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
    // The stops TfL names the alert as affecting, also only in the detail: for a part closure or
    // suspension, the section it shuts, its two ends included ("between Wembley Park and Aldgate").
    // Unordered, and across every section the alert names ([affectedSections]).
    val affectedStops: List<TflAffectedStopDto> = emptyList(),
    // TfL's kind of alert: `PlannedWork`, `RealTime` or `Information`. Unlike the routes, the plain
    // request carries it too.
    val category: String = "",
)

/**
 * Whether TfL files this entry as planned work, the only kind whose text is read for a later start
 * ([AlertStart]). A real-time or information alert is happening now whatever dates its text
 * mentions ("suspended until further notice; replacement buses from 13 October"), and one TfL
 * gave no category for is treated the same, the safe side (maintainer, 2026-09-28).
 */
fun TflLineStatusEntryDto.isPlannedWork(): Boolean =
    disruption?.category.equals(PLANNED_WORK, ignoreCase = true)

private const val PLANNED_WORK = "PlannedWork"

// One route an alert affects: its TfL direction (`inbound`/`outbound`), and its stops in order
// (the section of it the alert covers, or the whole route).
@Serializable
data class TflAffectedRouteDto(
    val direction: String = "",
    val routeSectionNaptanEntrySequence: List<TflRouteSectionEntryDto> = emptyList(),
)

@Serializable
data class TflRouteSectionEntryDto(
    val stopPoint: TflAffectedStopDto = TflAffectedStopDto(),
)

// One stop an alert affects, or one of an affected route's; only its id is read.
@Serializable
data class TflAffectedStopDto(
    val naptanId: String = "",
    val id: String = "",
) {
    val stopId: String get() = naptanId.ifBlank { id }.trim()
}

/** The stop ids TfL names this entry's alert as affecting ([TflLineDisruptionDto.affectedStops]), if any. */
fun TflLineStatusEntryDto.affectedStopIds(): Set<String> =
    disruption?.affectedStops.orEmpty().map { it.stopId }.filterTo(LinkedHashSet()) { it.isNotEmpty() }

/**
 * A stretch of line an alert shuts ([affectedSections]): its [stops] in the order its route runs them,
 * and that route's [direction] (`inbound`, `outbound`), blank where TfL gave none.
 */
data class AffectedSection(val direction: String, val stops: List<String>)

/**
 * The stretches of line this entry's alert shuts ([PartClosure.sections]): along each affected route,
 * every unbroken run of two or more of its stops that TfL names as affected, in the order the route
 * runs. So an alert naming two sections apart ("between A and B, and between C and D") is two, never
 * one spanning the stops between them, and each runs one way: a section closed outbound doesn't place
 * a ride going inbound through the same stations. Each also keeps its route's direction, so a
 * direction's own status takes only its own sections, even where both directions run a stretch the
 * same way round (a bus's one-way loop). With no route's stops given there's nothing to split or
 * order the affected stops by, so no section is known, and the alert is never placed (Codex, PR #446).
 */
fun TflLineStatusEntryDto.affectedSections(): List<AffectedSection> {
    val affected = affectedStopIds()
    if (affected.isEmpty()) return emptyList()
    val sections = LinkedHashSet<AffectedSection>()
    disruption?.affectedRoutes.orEmpty().forEach { route ->
        val direction = route.direction.trim().lowercase().takeIf { it in LineAlertDirections.DIRECTIONS }.orEmpty()
        var run = mutableListOf<String>()
        (route.routeSectionNaptanEntrySequence.map { it.stopPoint.stopId } + "").forEach { stop ->
            if (stop.isNotEmpty() && stop in affected) {
                run += stop
            } else {
                if (run.size >= 2) sections += AffectedSection(direction, run)
                run = mutableListOf()
            }
        }
    }
    return sections.toList()
}

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
 * [directionsOf] gives the directions an alert was found to affect ([LineAlertDirections]),
 * or null when not looked up; from it the status is also reduced per direction
 * ([LineStatus.byDirection]), so a row only carries alerts for the way it is going. [sectionsOf] gives
 * the sections the same lookup found it shutting ([LineStatus.closures]), empty when not known; a
 * direction's status keeps only those of routes going its way, or of no stated direction.
 *
 * Given [now], a planned-work alert ([isPlannedWork]) whose text says it starts on a later day
 * ([AlertStart]) is kept out of the disruption and listed in [LineStatus.planned] instead; one that can't be dated, or whose posting
 * date can't be read ([onBadDate] is told), counts as under way. Without [now] every alert counts as under way, as before.
 */
fun TflLineDto.toLineStatus(
    now: Instant? = null,
    // Told of a posting date that can't be read, the raw value only (TfL's public text, no user data).
    onBadDate: (String) -> Unit = {},
    sectionsOf: (entry: TflLineStatusEntryDto) -> List<AffectedSection> = { emptyList() },
    directionsOf: (entry: TflLineStatusEntryDto) -> Set<String>? = { null },
): LineStatus? {
    if (lineStatuses.isEmpty()) return null
    val today = now?.atZone(AlertStart.ZONE)?.toLocalDate()
    val alerts = lineStatuses
        .filter { it.statusSeverity != LineStatus.GOOD_SERVICE }
        .map { entry ->
            val resolved = resolveDisruption(entry.statusSeverityDescription, entry.statusSeverity, entry.reason)
            Alert(
                entry = entry,
                resolved = resolved,
                // Only a part closure or suspension TfL words itself is placed: a diversion read
                // from a catch-all's reason ranks with a part closure but isn't one, and a delay's
                // affected stops don't shut anything.
                sections = if (resolved.inferred || resolved.severity !in LineStatus.PART_SEVERITIES) emptyList() else sectionsOf(entry),
                // Set only for work that hasn't started ([AlertStart]); null is under way, or unknown.
                startsOn = now?.takeIf { entry.isPlannedWork() && entry.readablePostedDate(onBadDate) }
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
    // come beside it, soonest first. For one [direction], each closure keeps only the sections of
    // routes going that way, or of none; line-wide (null), all of them.
    fun reduce(direction: String?, applies: (TflLineStatusEntryDto) -> Boolean): LineStatus {
        val mine = alerts.filter { applies(it.entry) }
        val under = mine.filter { it.startsOn == null }
        val current = mostSevereDisruption(under.map { it.resolved })
        val planned = mine.mapNotNull { alert ->
            alert.startsOn?.let {
                PlannedAlert(alert.resolved.label, alert.resolved.fullText, it, alert.resolved.severity, alert.resolved.isFallback, closure = alert.closure(direction))
            }
        }.sortedBy { it.startsOn }.distinct()
        // Every part closure under way TfL words itself, not only the one shown, placed or not
        // ([LineStatus.closures]).
        val closures = under.mapNotNull { it.closure(direction) }.distinct()
        // One alert under way, however many entries TfL repeats it in: its words are the whole story.
        val sole = under.map { it.resolved.severity to it.resolved.fullText }.distinct().size == 1
        return (current?.toLineStatus(id)?.copy(closures = closures, soleAlert = sole) ?: good).copy(planned = planned)
    }
    val whole = reduce(null) { true }
    if (alerts.isEmpty()) return whole
    // Each direction's own, from the alerts that apply to it: its own, plus any whose direction
    // isn't known (counted for both, so an unlooked-up alert is never hidden). Kept only when it
    // differs from the line-wide status somewhere, so an unsplit line stays lean.
    val byDirection = LineAlertDirections.DIRECTIONS.associateWith { direction ->
        reduce(direction) { entry -> directionsOf(entry)?.contains(direction) ?: true }
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
    // The sections TfL places it on ([affectedSections]), for a part closure or suspension only.
    val sections: List<AffectedSection>,
    val startsOn: LocalDate?,
) {
    // The part closure or suspension this is, TfL wording it itself ([LineStatus.closures]), placed by
    // its sections for [direction] (routes going that way, or of none), or all of them line-wide (null).
    fun closure(direction: String?): PartClosure? {
        if (resolved.inferred || resolved.severity !in LineStatus.PART_SEVERITIES) return null
        val placed = sections.filter { direction == null || it.direction.isEmpty() || it.direction == direction }
        return PartClosure(resolved.severity, resolved.label, resolved.fullText.ifBlank { null }, placed.map { it.stops }.distinct())
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
        isFallback = isFallback,
    )

private const val GOOD_SERVICE_LABEL = "Good Service"
