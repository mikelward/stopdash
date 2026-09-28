package app.stopdash.data

import app.stopdash.domain.DismissedAlert
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

internal fun Set<DismissedAlert>.toPersisted(ended: Map<DismissedAlert, Instant> = emptyMap()): PersistedDismissedAlerts =
    PersistedDismissedAlerts(
        alerts = map { PersistedDismissedAlert(it.alertKey, it.contentSignature, ended[it]?.toEpochMilli()) },
    )

/** The entries marked ended ([PersistedDismissedAlert.endedAtMillis]); none for an unknown version. */
internal fun PersistedDismissedAlerts.ended(): Map<DismissedAlert, Instant> {
    if (version != PersistedDismissedAlerts.CURRENT_VERSION) return emptyMap()
    return alerts.mapNotNull { a -> a.endedAtMillis?.let { DismissedAlert(a.alertKey, a.contentSignature) to Instant.ofEpochMilli(it) } }.toMap()
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
