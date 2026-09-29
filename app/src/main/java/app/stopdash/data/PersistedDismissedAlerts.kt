package app.stopdash.data

import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.Staleness
import app.stopdash.domain.SteadyClock
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of the dismissed-alerts set, kept in the `data` layer so the domain types stay
 * free of serialization annotations (mirrors [PersistedStarredRows]). `version` lets a future
 * format change be detected and discarded rather than mis-read; an unknown version reads as
 * "nothing dismissed" (an empty set), which fails **safe** — a dismissed card reappears rather than
 * a warning being hidden by bytes this build can't verify (SPEC principle 2).
 *
 * A private persistence detail — the app opens no off-device channel of its own for it — but the
 * file is not strictly device-local: it rides Android backup and device-to-device transfer like the
 * rest of the app's config (SPEC *Privacy*), a platform path the user controls. Each entry is the
 * place key and TfL's own public notice text, no coordinate.
 */
@Serializable
internal data class PersistedDismissedAlerts(
    val version: Int = CURRENT_VERSION,
    val alerts: List<PersistedDismissedAlert> = emptyList(),
    // True when the end times ([PersistedDismissedAlert.endedAtMillis]) are the steady clock's
    // ([SteadyClock]), in [endsFrame], as the line checks they're weighed against are. Defaulted: an
    // older build's are the wall clock's, and an older build reads these as the wall clock's too,
    // which is how it compares its own checks; either way an end time counts for only one staleness
    // window, so no version bump, which would drop every dismissal.
    val steadyEnds: Boolean = false,
    val endsFrame: PersistedFrame? = null,
) {
    companion object {
        /** The current on-disk format. Bump when a field's meaning changes incompatibly. */
        const val CURRENT_VERSION = 1
    }
}

@Serializable
internal data class PersistedDismissedAlert(
    val alertKey: String,
    val contentSignature: String,
    // When a refresh saw this line alert end, for one kept a while for the widget's stored copy
    // ([Dismissed.keepingEnded]). Null for an ordinary dismissal. Defaulted, so an older
    // build's file reads as none ended, and an older build ignores it (it then keeps the entry as
    // an ordinary dismissal, pruned by its own next refresh).
    val endedAtMillis: Long? = null,
)

/** This set, with each of [ended]'s end times (steady stamps in this process's frame) kept, marked as in it. */
internal fun Set<DismissedAlert>.toPersisted(ended: Map<DismissedAlert, Instant> = emptyMap()): PersistedDismissedAlerts =
    PersistedDismissedAlerts(
        alerts = map { PersistedDismissedAlert(it.alertKey, it.contentSignature, ended[it]?.toEpochMilli()) },
        steadyEnds = true,
        endsFrame = SteadyClock.source?.frame?.toPersisted(),
    )

/**
 * The entries marked ended ([PersistedDismissedAlert.endedAtMillis]), each end time moved into the
 * frame [to] as the stored snapshot's stamps are ([inFrame]), so it's weighed against a line check
 * in the same one however the clock has been set since; none for an unknown version. One from an
 * earlier boot can't be moved, but it was seen before [to]'s boot started, so it's taken as then,
 * plus the margin the snapshot keeps that boot's checks within ([fromEarlierBoot]): it hides every
 * check the snapshot kept from before the reboot. Taken from [to]'s own start each
 * read, it moves with the clock as those checks do once the snapshot has adopted this boot, and
 * neither file has to have been written since for the two to agree (Codex, PR #386). An older
 * build's, the wall clock's, is taken in as the wall clock reads it now.
 */
internal fun PersistedDismissedAlerts.ended(to: SteadyClock.Frame? = SteadyClock.source?.frame): Map<DismissedAlert, Instant> {
    if (version != PersistedDismissedAlerts.CURRENT_VERSION) return emptyMap()
    val from = endsFrame?.toDomain()
    fun here(millis: Long): Instant = when {
        !steadyEnds -> SteadyClock.stamp(Instant.ofEpochMilli(millis))
        from != null && to != null && from.boot != to.boot ->
            Instant.ofEpochMilli(to.originMillis + Staleness.CLOCK_SKEW.inWholeMilliseconds)
        else -> Instant.ofEpochMilli(millis).plus(SteadyClock.shiftBetween(from, to))
    }
    return alerts.mapNotNull { a -> a.endedAtMillis?.let { DismissedAlert(a.alertKey, a.contentSignature) to here(it) } }.toMap()
}

/**
 * The domain set, or null when the stored format is a version this build doesn't know — the caller
 * then reads it as an empty set (a dismissed card reappears, never a warning hidden). Like
 * [PersistedStarredRows.toDomain] this covers a newer version that still **decodes** (extra fields
 * ignored); a payload that fails to decode at all throws in [DismissedAlertsSerializer] and is
 * reported as corruption. With v1 the only schema a decode failure is genuine corruption.
 */
internal fun PersistedDismissedAlerts.toDomain(): Set<DismissedAlert>? {
    if (version != PersistedDismissedAlerts.CURRENT_VERSION) return null
    return alerts.mapTo(mutableSetOf()) { DismissedAlert(it.alertKey, it.contentSignature) }
}
