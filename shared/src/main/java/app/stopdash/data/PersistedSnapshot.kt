package app.stopdash.data

import app.stopdash.domain.Departure
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.plannedAlertFingerprint
import app.stopdash.domain.lineAlertFingerprint
import app.stopdash.domain.RailFeed
import app.stopdash.domain.Staleness
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopWay
import app.stopdash.domain.Terminating
import app.stopdash.domain.WidgetJourney
import app.stopdash.domain.normalizeBranch
import app.stopdash.domain.riderLineName
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of a [DeparturesSnapshot], kept in the `data` layer so the domain types
 * stay free of serialization annotations and `java.time` never has to round-trip through a
 * serializer. `Instant`s are stored as epoch milliseconds (a plain `Long`), which is stable
 * across app versions and time zones. `version` lets a future format change be detected and
 * discarded rather than mis-read; an unknown version reads as "no snapshot".
 *
 * [PersistedStop] is also the Wear OS envelope's stop model ([WatchEnvelope]), so the watch
 * renders from exactly the widget's inputs: a field added here reaches the user's watch too, so
 * check it against the watch-sync disclosure (docs/PRIVACY.md). Otherwise the field set can
 * change with a `version` bump, and only the fields the restore actually needs are carried (the
 * transient refresh-cycle flags are not persisted; line statuses are, age-stamped; see
 * [DeparturesSnapshot]). The file itself is
 * not strictly device-local: it rides Android backup and device-to-device transfer like the rest
 * of the app's data (SPEC §12), a platform path the user controls — see the app's
 * `DataStoreSnapshotStore`.
 */
@Serializable
data class PersistedSnapshot(
    val version: Int = CURRENT_VERSION,
    val stops: List<PersistedStop> = emptyList(),
    val fetchedAtMillis: Long = 0L,
    // The widget's starred journeys and journey-only stops ([DeparturesSnapshot]), added in version
    // 2. Defaulted, so a version-1 snapshot reads back with none.
    val journeys: List<PersistedWidgetJourney> = emptyList(),
    val journeyOnlyStopIds: List<String> = emptyList(),
    // Stops the last refresh asked for but couldn't get ([DeparturesSnapshot.missingStopIds]).
    // Defaulted, so an older snapshot reads back with none, as before; an older build reading
    // this one ignores it and shows what it did before.
    val missingStopIds: List<String> = emptyList(),
    // Each shown line's last status check, stamped ([DeparturesSnapshot.lineStatuses]). Defaulted,
    // so an older snapshot reads back with none (no line marked, as before); an older build reading
    // this one ignores it, as it always has.
    val lineStatuses: List<PersistedLineStatus> = emptyList(),
    // The steady-clock frame the fetch and check stamps ([fetchedAtMillis], each stop's, each line
    // check's from version 4) were written in ([SteadyClock.Frame]), so a process of the same boot
    // reads them at their real age however the wall clock has been set since ([inFrame]).
    // Defaulted: an older snapshot, or one from a process that couldn't tell its boot, reads as the
    // wall clock's; an older build ignores it and reads the stamps as the wall clock's too.
    val stampFrame: PersistedFrame? = null,
) {
    companion object {
        /**
         * The current on-disk format. Bump when a field's meaning changes incompatibly. Version 2
         * added journey-only stops: a version-1 reader would show them as nearby stops, so it
         * must discard a version-2 file, while this build still reads version 1. Version 3 stamps
         * fetches by the steady clock ([stampFrame]): an older reader would take them for the wall
         * clock's, and could show a stop fetched before the clock was set as fresh, so it must
         * discard a version-3 file (Codex, PR #371). Version 4 stamps line checks by the steady
         * clock too: an older reader would take them for the wall clock's, the same way. This build
         * still reads the older ones, whose line checks are the wall clock's ([inFrame] moves them
         * into the steady frame), as are all of a version-1 or -2 file's stamps.
         */
        const val CURRENT_VERSION = 4

        /** The first format whose line checks are stamped by the steady clock, as its fetches are. */
        const val STEADY_CHECKS_VERSION = 4

        /** The formats this build reads. */
        val READABLE_VERSIONS = setOf(1, 2, 3, CURRENT_VERSION)
    }
}

@Serializable
data class PersistedStop(
    val stopId: String,
    val stopName: String,
    val departures: List<PersistedDeparture> = emptyList(),
    val fetchedAtMillis: Long = 0L,
    val lines: List<PersistedLine> = emptyList(),
    val arrivalsFresh: Boolean = true,
    // The stop's cluster for per-place grouping (SPEC D8). Defaulted, so a snapshot written by an
    // older build reads back blank — that stop groups on its own until the next refresh restamps
    // it — no version bump needed.
    val clusterId: String = "",
    // The bus pole's letter/bearing/towards for the per-pole split (SPEC D8) — persisted like
    // [clusterId] (grouping inputs the screen needs before the refresh), so a restored snapshot keeps
    // its per-pole headers rather than collapsing to bare/terminus until the fetch lands. Defaulted,
    // so an older build's snapshot reads back blank — no version bump needed.
    val stopLetter: String = "",
    val bearing: String = "",
    val towards: String = "",
    // The places no farther from the rider than this stop ([Terminating.Nearer]), so the widget's
    // refresh hides the same terminating services the app does. Defaulted: an older snapshot reads
    // back empty and hides nothing until the app next fetches the stop.
    val nearerIds: List<String> = emptyList(),
    val nearerNames: List<String> = emptyList(),
    // Why the stop's arrivals carry no National Rail times ([RailFeed]: live-but-empty, no key, or
    // board unavailable), by name, so a restored rail row still says "No key" or "No data"
    // rather than guessing. Defaulted: an older snapshot reads back null (the pre-persistence
    // behavior), and a name this build doesn't know reads back null too.
    val railFeed: String? = null,
)
// Stop disruptions (closures) are deliberately NOT persisted: a closure is a point-in-time
// claim the screen renders unconditionally, with no stale-safe rendering (unlike a countdown,
// which is withheld once stale), so a saved one would assert a possibly-reopened station on
// the next launch. This is the same reason Snapshot.mergeStop never ages a disruption. On
// restore a stop carries no disruptions; the immediate refresh re-establishes them.

/**
 * One line's status check. TfL's public status, not user data; the chip label only, since no
 * surface that reads the snapshot shows the full reason ([app.stopdash.domain.LineStatus.fullText]
 * is left out, keeping the snapshot and the watch envelope small), with the line's work still to
 * come ([planned]) the same way.
 */
@Serializable
data class PersistedLineStatus(
    val lineId: String,
    val severity: Int,
    val description: String,
    val checkedAtMillis: Long,
    // False for a check TfL gave no status for ([LineStatusCheck.known]). Defaulted: a check written
    // before this field was a verdict.
    val known: Boolean = true,
    // True when the user dismissed this status in the app ([LineStatusCheck.dismissed]). Carried
    // only by the watch envelope, which the phone builds with dismissals applied; the phone's own
    // stored snapshot never keeps it (see [DeparturesSnapshot.toPersisted]). Defaulted.
    val dismissed: Boolean = false,
    // The alert's full dismissal identity ([LineStatusCheck.fingerprint]). Null in a check written
    // before this field: its identity is then taken from what's stored, which can't match an alert
    // dismissed with a full reason, so it isn't marked until rewritten (the safe way).
    val fingerprint: String? = null,
    // The line's status for each direction TfL scoped its alerts to ([LineStatus.byDirection]), so a
    // row shows only the alerts for the way it's going, as in the app. Empty when the alerts weren't
    // split, and in a check written before this field: every row then shows the line-wide status,
    // which hides nothing. Defaulted, and ignored by an older build.
    val directions: List<PersistedDirectionStatus> = emptyList(),
    // True while a lookup of which way an alert applies was still running when this was checked
    // ([LineStatus.awaitingDirections]), so the widget's refresh asks again rather than reuse it.
    val awaitingDirections: Boolean = false,
    // The line's work still to come ([LineStatus.planned]), so the widget and the watch show its
    // calendar, and its ⚠ once its day comes, as the app does. Empty in a check written before this
    // field: the line then shows no calendar until the next check. Defaulted, and ignored by an
    // older build.
    val planned: List<PersistedPlannedAlert> = emptyList(),
    // Whether the alert shown is the line's only one under way ([LineStatus.soleAlert]): only then may
    // the app's verdict that it lies behind a stop be applied to it ([LineStatusCheck.withAlertsBehind]).
    // False in a check written before this field, so none is applied to it. Defaulted.
    val soleAlert: Boolean = false,
    // The stops and ways this alert lies wholly behind ([LineStatus.behindAt]). Carried only by the watch
    // envelope, which the phone builds with the app's verdicts applied, as [dismissed] is. Defaulted.
    val behind: List<PersistedStopWay> = emptyList(),
)

/** A stop and a way its rows go ([StopWay]), within [PersistedLineStatus.behind]. */
@Serializable
data class PersistedStopWay(val stopId: String, val direction: String = "")

/** One direction's status within a [PersistedLineStatus]: the chip label, as for the line's. */
@Serializable
data class PersistedDirectionStatus(
    // TfL's `inbound` or `outbound`.
    val direction: String,
    val severity: Int,
    val description: String,
    // As [PersistedLineStatus.fingerprint], for this direction's status. Null in the watch envelope.
    val fingerprint: String? = null,
    // As [PersistedLineStatus.dismissed], for this direction's status. Only in the watch envelope.
    val dismissed: Boolean = false,
    // As [PersistedLineStatus.planned], for this direction's status.
    val planned: List<PersistedPlannedAlert> = emptyList(),
    // As [PersistedLineStatus.soleAlert], for this direction's status.
    val soleAlert: Boolean = false,
    // As [PersistedLineStatus.behind], for this direction's status. Only in the watch envelope.
    val behind: List<PersistedStopWay> = emptyList(),
)

/**
 * One alert for work still to come ([PlannedAlert]): its chip label, the day it starts, and its rank
 * for when that day comes. TfL's public status, as the rest of a check is, and like a status its
 * prose is left out: its dismissal identity is kept instead.
 */
@Serializable
data class PersistedPlannedAlert(
    val label: String,
    // The day it starts in London, as ISO `yyyy-MM-dd` ([LocalDate.toString]).
    val startsOn: String,
    val severity: Int = PlannedAlert.PART_CLOSURE,
    val isFallback: Boolean = false,
    // Its full dismissal identity ([plannedAlertFingerprint]), TfL's prose included. Kept in the watch
    // envelope too: it is what [dismissed] stands for there.
    val fingerprint: String,
    // As [PersistedLineStatus.dismissed], for this alert. Only in the watch envelope.
    val dismissed: Boolean = false,
)

private fun PlannedAlert.toPersisted(dismissed: Set<String>): PersistedPlannedAlert {
    val identity = plannedAlertFingerprint(this)
    return PersistedPlannedAlert(label, startsOn.toString(), severity, isFallback, identity, identity in dismissed)
}

// Null for a day that doesn't read: the alert is left out rather than guessed at (it can only come
// from a store this build didn't write).
private fun PersistedPlannedAlert.toDomain(): PlannedAlert? =
    try {
        PlannedAlert(label, fullText = "", LocalDate.parse(startsOn), severity, isFallback, fingerprint)
    } catch (_: DateTimeParseException) {
        null
    }

fun LineStatusCheck.toPersisted(): PersistedLineStatus =
    PersistedLineStatus(
        status.lineId, status.severity, status.description, checkedAt.toEpochMilli(), known, dismissed, fingerprint,
        directions = status.byDirection.entries.sortedBy { it.key }.map { (direction, it) ->
            PersistedDirectionStatus(
                direction, it.severity, it.description, directionFingerprints[direction], direction in dismissedDirections,
                planned = it.planned.map { alert -> alert.toPersisted(dismissedPlanned) },
                soleAlert = it.soleAlert,
                behind = it.behindAt.toPersisted(),
            )
        },
        awaitingDirections = status.awaitingDirections,
        planned = status.planned.map { it.toPersisted(dismissedPlanned) },
        soleAlert = status.soleAlert,
        behind = status.behindAt.toPersisted(),
    )

// In a stable order, so an unchanged set writes the same.
private fun Set<StopWay>.toPersisted(): List<PersistedStopWay> =
    sortedWith(compareBy({ it.stopId }, { it.direction })).map { PersistedStopWay(it.stopId, it.direction) }

private fun List<PersistedStopWay>.toDomain(): Set<StopWay> = mapTo(HashSet()) { StopWay(it.stopId, it.direction) }

fun PersistedLineStatus.toDomain(): LineStatusCheck {
    val byDirection = directions.associate {
        it.direction to LineStatus(
            lineId, it.severity, it.description, planned = it.planned.mapNotNull { alert -> alert.toDomain() },
            soleAlert = it.soleAlert, behindAt = it.behind.toDomain(),
        )
    }
    val status = LineStatus(
        lineId, severity, description, byDirection = byDirection, awaitingDirections = awaitingDirections,
        planned = planned.mapNotNull { it.toDomain() }, soleAlert = soleAlert, behindAt = behind.toDomain(),
    )
    return LineStatusCheck(
        status, Instant.ofEpochMilli(checkedAtMillis), known, dismissed, fingerprint ?: lineAlertFingerprint(status),
        dismissedDirections = directions.filter { it.dismissed }.mapTo(HashSet()) { it.direction },
        directionFingerprints = directions.associate { it.direction to (it.fingerprint ?: lineAlertFingerprint(byDirection.getValue(it.direction))) },
        dismissedPlanned = (planned + directions.flatMap { it.planned }).filter { it.dismissed }.mapTo(HashSet()) { it.fingerprint },
    )
}

/**
 * This line status with no dismissal marked, on the line, any direction, or any planned alert, and no
 * verdict of the app's applied ([PersistedLineStatus.behind]): both are applied where the phone's
 * snapshot is read, never stored with it.
 */
private fun PersistedLineStatus.undismissed(): PersistedLineStatus {
    fun List<PersistedPlannedAlert>.undismissed() = map { it.copy(dismissed = false) }
    return copy(
        dismissed = false,
        directions = directions.map { it.copy(dismissed = false, planned = it.planned.undismissed(), behind = emptyList()) },
        planned = planned.undismissed(),
        behind = emptyList(),
    )
}

/** The persisted form of [DeparturesSnapshot.lineStatuses], in a stable (line id) order. */
fun Map<String, LineStatusCheck>.toPersistedStatuses(): List<PersistedLineStatus> =
    values.sortedBy { it.status.lineId }.map { it.toPersisted() }

/** The lines [stops] could show a row or a status for, as [LineStatusCheck.linesOf] counts them. */
fun linesOfPersisted(stops: List<PersistedStop>): Set<String> =
    stops.flatMapTo(HashSet()) { stop -> stop.departures.map { it.lineId } + stop.lines.map { it.id } }
        .filterTo(HashSet()) { it.isNotBlank() }

/**
 * [a] and [b]'s line checks merged, the later check per line winning ([LineStatusCheck.newest]),
 * kept only for the lines [stops] show.
 */
fun newestStatuses(
    a: List<PersistedLineStatus>,
    b: List<PersistedLineStatus>,
    stops: List<PersistedStop>,
    now: Instant,
): List<PersistedLineStatus> =
    LineStatusCheck.newest(
        a.associate { it.lineId to it.toDomain() },
        b.associate { it.lineId to it.toDomain() },
        linesOfPersisted(stops),
        now,
    ).toPersistedStatuses()

/** A [SteadyClock.Frame] on disk. */
@Serializable
data class PersistedFrame(val boot: String, val originMillis: Long)

fun SteadyClock.Frame.toPersisted(): PersistedFrame = PersistedFrame(boot, originMillis)

fun PersistedFrame.toDomain(): SteadyClock.Frame = SteadyClock.Frame(boot, originMillis)

/**
 * This snapshot with its fetch and check stamps moved from the frame they were written in
 * ([stampFrame]) into [to] ([SteadyClock.shiftBetween]), and marked as in it: within one boot, a
 * stop fetched or a line checked before the clock was set reads at its real age, not as newer or
 * older than it is. One written in an earlier boot can't be moved, but it was written before [to]'s
 * boot started, which bounds it ([fromEarlierBoot]). A line check from before version 4 is the
 * wall clock's: it's taken into this process's steady frame as it reads the wall clock now
 * ([SteadyClock.stamp]), which is how it was aged before.
 */
fun PersistedSnapshot.inFrame(to: SteadyClock.Frame?): PersistedSnapshot {
    val from = stampFrame?.toDomain()
    val wallChecks = version < PersistedSnapshot.STEADY_CHECKS_VERSION
    val moved = if (from != null && to != null && from.boot != to.boot) {
        // The boot's start, in the fetch stamps' steady frame and as the wall clock reads it now.
        val bootWall = SteadyClock.toWall(Instant.ofEpochMilli(to.originMillis)).toEpochMilli()
        fromEarlierBoot(to.originMillis, if (wallChecks) bootWall else to.originMillis)
    } else {
        val shift = SteadyClock.shiftBetween(from, to).toMillis()
        if (shift == 0L) {
            this
        } else {
            copy(
                stops = stops.map { it.copy(fetchedAtMillis = it.fetchedAtMillis + shift) },
                fetchedAtMillis = fetchedAtMillis + shift,
                lineStatuses = if (wallChecks) lineStatuses else lineStatuses.map { it.copy(checkedAtMillis = it.checkedAtMillis + shift) },
            )
        }
    }
    val checks = if (wallChecks) {
        moved.lineStatuses.map { it.copy(checkedAtMillis = SteadyClock.stamp(Instant.ofEpochMilli(it.checkedAtMillis)).toEpochMilli()) }
    } else {
        moved.lineStatuses
    }
    // Written as this format from here on: its stamps mean the steady clock's now.
    return moved.copy(lineStatuses = checks, stampFrame = to?.toPersisted(), version = PersistedSnapshot.CURRENT_VERSION)
}

/**
 * This snapshot, written in a boot before the one that started at [bootStartMillis] (in its fetch
 * stamps' clock; [bootStartChecksMillis] in its line checks', which is that too from version 4):
 * everything in it was fetched or checked before then, so a stamp later than that, by more than
 * [Staleness.CLOCK_SKEW], can only be from before the clock was set back, and its real age can't be
 * told. Such a stop is restamped stale as of the boot's start and marked unrefreshed
 * ([PersistedStop.notAfter]), and such a line check dropped. The boot's start doesn't move, so every
 * read makes the same call, and the stop stays stale as the clock catches up with its old stamp,
 * with no write needed (Codex, PR #371).
 */
fun PersistedSnapshot.fromEarlierBoot(bootStartMillis: Long, bootStartChecksMillis: Long = bootStartMillis): PersistedSnapshot {
    val kept = stops.map { it.notAfter(bootStartMillis) }
    return copy(
        stops = kept,
        fetchedAtMillis = if (afterBoot(fetchedAtMillis, bootStartMillis)) kept.maxOfOrNull { it.fetchedAtMillis } ?: staleAtBoot(bootStartMillis) else fetchedAtMillis,
        lineStatuses = lineStatuses.filterNot { afterBoot(it.checkedAtMillis, bootStartChecksMillis) },
    )
}

/**
 * This stop, fetched before a boot that started at [bootStartMillis]: restamped stale as of then and
 * unrefreshed if its stamp is later than that ([PersistedSnapshot.fromEarlierBoot]).
 */
fun PersistedStop.notAfter(bootStartMillis: Long): PersistedStop =
    if (afterBoot(fetchedAtMillis, bootStartMillis)) copy(fetchedAtMillis = staleAtBoot(bootStartMillis), arrivalsFresh = false) else this

/** Whether a stamp is later than a boot's start by more than [Staleness.CLOCK_SKEW]. */
internal fun afterBoot(millis: Long, bootStartMillis: Long): Boolean = millis > bootStartMillis + Staleness.CLOCK_SKEW.inWholeMilliseconds

private fun staleAtBoot(bootStartMillis: Long) = bootStartMillis - Staleness.THRESHOLD.inWholeMilliseconds

/**
 * This snapshot with what it holds from before the clock was set back distrusted for good: a stop
 * stamped ahead of [now] ([Staleness.isFromFuture]) is restamped stale as of [now] and marked
 * unrefreshed, and a line check stamped ahead of it is dropped, so the line reads unchecked. A
 * surface already treats such a stamp as stale, but only while it's ahead: once the clock passes
 * it, it would read as fresh again with nothing to say it was ever ahead. A store applies this on
 * every write, so the first write after the clock moves back records it, and a fresh fetch then
 * replaces the stop rather than losing to its later-looking stamp. Fetch and check stamps are the
 * steady clock's ([SteadyClock]), so within a boot only one read across a reboot ([inFrame]) can be
 * ahead.
 */
fun PersistedSnapshot.distrustingFuture(now: Instant): PersistedSnapshot {
    if (!fetchAhead(fetchedAtMillis, now) && stops.none { fetchAhead(it.fetchedAtMillis, now) } &&
        lineStatuses.none { fetchAhead(it.checkedAtMillis, now) }
    ) {
        return this
    }
    val kept = stops.map { it.distrustingFuture(now) }
    return copy(
        stops = kept,
        fetchedAtMillis = if (fetchAhead(fetchedAtMillis, now)) kept.maxOfOrNull { it.fetchedAtMillis } ?: staleAt(now) else fetchedAtMillis,
        lineStatuses = lineStatuses.filterNot { fetchAhead(it.checkedAtMillis, now) },
    )
}

/** This stop, restamped stale as of [now] and unrefreshed if it's stamped ahead of it ([PersistedSnapshot.distrustingFuture]). */
fun PersistedStop.distrustingFuture(now: Instant): PersistedStop =
    if (fetchAhead(fetchedAtMillis, now)) copy(fetchedAtMillis = staleAt(now), arrivalsFresh = false) else this

// A fetch or check stamp in the stored snapshot is the steady clock's ([Staleness.age]).
private fun fetchAhead(millis: Long, now: Instant) = Staleness.isFromFuture(Staleness.age(Instant.ofEpochMilli(millis), now))

// A line check the watch holds, which is the wall clock's ([WatchEnvelope]): beyond
// [Staleness.CLOCK_SKEW], so a check stamped by another device's clock a little ahead is kept.
internal fun checkAhead(millis: Long, now: Instant) = Staleness.isFromFuture((now.toEpochMilli() - millis).milliseconds)

// Stale as of [now], stamped as a fetch is ([SteadyClock]).
private fun staleAt(now: Instant) = SteadyClock.stamp(now).toEpochMilli() - Staleness.THRESHOLD.inWholeMilliseconds

@Serializable
data class PersistedWidgetJourney(
    val originId: String,
    val calls: List<PersistedJourneyCall> = emptyList(),
    val key: String = "",
    val shownFrom: String = "",
)

@Serializable
data class PersistedJourneyCall(
    val lineId: String,
    val destination: String,
    val branch: String? = null,
)

@Serializable
data class PersistedDeparture(
    val lineId: String,
    val lineName: String,
    val direction: String,
    val destination: String,
    val platform: String? = null,
    val expectedArrivalMillis: Long,
    val mode: String,
    // The "via" branch, when TfL gave one. Nullable with a default, so a snapshot written by
    // an older build (no branch field) reads back as null — restored departures show no branch
    // until the next refresh, no version bump needed.
    val branch: String? = null,
    // The terminus stop id (TfL `destinationNaptanId`), so a restored or widget-refreshed snapshot
    // hides the same terminating services ([Terminating]). Defaulted blank for an older snapshot.
    val destinationId: String = "",
    // TfL's id for the train making the departure, so one restored can still be followed. Defaulted
    // blank (no train) for an older snapshot.
    val vehicleId: String = "",
)

@Serializable
data class PersistedLine(
    val id: String,
    val name: String,
    val mode: String,
)

fun WidgetJourney.toPersisted(): PersistedWidgetJourney =
    PersistedWidgetJourney(originId, calls.map { PersistedJourneyCall(it.lineId, it.destination, it.branch) }, key, shownFrom)

fun DeparturesSnapshot.toPersisted(): PersistedSnapshot =
    PersistedSnapshot(
        stops = stops.map { it.toPersisted() },
        fetchedAtMillis = fetchedAt.toEpochMilli(),
        journeys = journeys.map { j ->
            PersistedWidgetJourney(
                j.originId, j.calls.map { PersistedJourneyCall(it.lineId, it.destination, it.branch) }, j.key, j.shownFrom,
            )
        },
        journeyOnlyStopIds = journeyOnlyStopIds.sorted(),
        missingStopIds = missingStopIds.sorted(),
        // Dismissals are judged where the snapshot is read ([DeparturesSnapshot.withDismissals]),
        // never stored, so a stored flag can't outlive the dismissal it came from; so are the app's
        // verdicts on alerts behind a stop ([DeparturesSnapshot.withAlertsBehind]).
        lineStatuses = lineStatuses.toPersistedStatuses().map { it.undismissed() },
    )

/**
 * The domain snapshot, or null when the stored format is a version this build doesn't know
 * — discarded rather than mis-read, so a forward-incompatible change fails safe to "no
 * last-good".
 */
fun PersistedSnapshot.toDomain(): DeparturesSnapshot? {
    if (version !in PersistedSnapshot.READABLE_VERSIONS) return null
    return DeparturesSnapshot(
        stops = stops.map { it.toDomain() },
        fetchedAt = Instant.ofEpochMilli(fetchedAtMillis),
        journeys = journeys.map { j ->
            WidgetJourney(
                j.originId, j.calls.mapTo(HashSet()) { JourneyCall(it.lineId, it.destination, it.branch) }, j.key, j.shownFrom,
            )
        },
        journeyOnlyStopIds = journeyOnlyStopIds.toSet(),
        missingStopIds = missingStopIds.toSet(),
        // A flag an earlier build stored is ignored, as [toPersisted] no longer writes one.
        lineStatuses = lineStatuses.associate { it.lineId to it.undismissed().toDomain() },
    )
}

fun StopArrivals.toPersisted(): PersistedStop =
    PersistedStop(
        stopId = stopId,
        stopName = stopName,
        departures = departures.map { it.toPersisted() },
        fetchedAtMillis = fetchedAt.toEpochMilli(),
        lines = lines.map { PersistedLine(it.id, it.name, it.mode) },
        // Stop disruptions are intentionally not carried to disk (point-in-time; see above).
        arrivalsFresh = arrivalsFresh,
        clusterId = clusterId,
        stopLetter = stopLetter,
        bearing = bearing,
        towards = towards,
        nearerIds = nearer.ids.sorted(),
        nearerNames = nearer.names.sorted(),
        railFeed = railFeed?.name,
    )

fun PersistedStop.toDomain(): StopArrivals =
    StopArrivals(
        stopId = stopId,
        stopName = stopName,
        departures = departures.map { it.toDomain() },
        fetchedAt = Instant.ofEpochMilli(fetchedAtMillis),
        // Names renamed on the way back in, as on the way in from TfL ([riderLineName]).
        lines = lines.map { LineRef(it.id, riderLineName(it.name, it.mode), it.mode) },
        // No disruptions restored — the immediate refresh re-establishes any current one.
        arrivalsFresh = arrivalsFresh,
        clusterId = clusterId,
        stopLetter = stopLetter,
        bearing = bearing,
        towards = towards,
        nearer = Terminating.Nearer(nearerIds.toSet(), nearerNames.toSet()),
        railFeed = railFeed?.let { name -> RailFeed.entries.firstOrNull { it.name == name } },
    )

private fun Departure.toPersisted(): PersistedDeparture =
    PersistedDeparture(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrivalMillis = expectedArrival.toEpochMilli(),
        mode = mode,
        branch = branch,
        destinationId = destinationId,
        vehicleId = vehicleId,
    )

internal fun PersistedDeparture.toDomain(): Departure =
    Departure(
        lineId = lineId,
        lineName = riderLineName(lineName, mode),
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrival = Instant.ofEpochMilli(expectedArrivalMillis),
        mode = mode,
        // Fold an older build's raw spelling ("Charing Cross", "Bank Branch") to the canonical
        // short label on restore, so a persisted row matches a freshly-fetched one (SPEC).
        branch = normalizeBranch(branch),
        destinationId = destinationId,
        vehicleId = vehicleId,
    )
