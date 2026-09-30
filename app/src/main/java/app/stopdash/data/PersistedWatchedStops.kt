package app.stopdash.data

import app.stopdash.domain.LineRef
import app.stopdash.domain.WatchedStop
import app.stopdash.domain.riderLineName
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of the watched-stop set, kept in the `data` layer so the domain types
 * stay free of serialization annotations. `version` lets a future format change be detected
 * and discarded rather than mis-read; an unknown version reads as "no watched stops" (an
 * empty set), which fails safe — the user re-adds rather than the app mis-reading old bytes.
 *
 * This is a private persistence detail — the app opens no off-device channel of its own for
 * it — but the file is not strictly device-local: it rides Android backup and device-to-device
 * transfer like the rest of the app's config (SPEC §12 / *Privacy*), a platform path the user
 * controls, so a phone swap keeps the watched stops. Coordinates are deliberately not carried
 * (they are never part of a [WatchedStop]).
 */
@Serializable
internal data class PersistedWatchedStops(
    val version: Int = CURRENT_VERSION,
    val stops: List<PersistedWatchedStop> = emptyList(),
) {
    companion object {
        /** The current on-disk format. Bump when a field's meaning changes incompatibly. */
        const val CURRENT_VERSION = 1
    }
}

@Serializable
internal data class PersistedWatchedStop(
    val id: String,
    val name: String,
    val lines: List<PersistedWatchedLine> = emptyList(),
)

@Serializable
internal data class PersistedWatchedLine(
    val id: String,
    val name: String,
    val mode: String,
)

internal fun List<WatchedStop>.toPersisted(): PersistedWatchedStops =
    PersistedWatchedStops(stops = map { it.toPersisted() })

private fun WatchedStop.toPersisted(): PersistedWatchedStop =
    PersistedWatchedStop(
        id = id,
        name = name,
        lines = lines.map { PersistedWatchedLine(it.id, it.name, it.mode) },
    )

/**
 * The domain set, or null when the stored format is a version this build doesn't know — the
 * caller then reads it as [app.stopdash.domain.WatchedStopSet.Unavailable] and preserves the
 * file rather than overwriting it (see [DataStoreWatchedStopsStore]).
 *
 * This covers a newer version that still **decodes** (extra fields ignored). It does NOT yet
 * cover a future schema that changes an existing field's *shape* so the payload fails to
 * decode at all: that throws in [WatchedStopsSerializer], which today reports it as corruption
 * and discards the file. With v1 the only schema that has ever existed, a decode failure is
 * genuine corruption, so that is correct now — but a future incompatible schema bump MUST
 * first add a stable version-envelope read (parse `version` alone, and preserve unknown
 * raw bytes) so a downgrade can't silently delete the user's set. Tracked in `TODO.md`
 * (Phase 2 watched stops) — do not ship an incompatible schema without it.
 */
internal fun PersistedWatchedStops.toDomain(): List<WatchedStop>? {
    if (version != PersistedWatchedStops.CURRENT_VERSION) return null
    return stops.map { it.toDomain() }
}

private fun PersistedWatchedStop.toDomain(): WatchedStop =
    WatchedStop(
        id = id,
        name = name,
        lines = lines.map { LineRef(it.id, riderLineName(it.name, it.mode), it.mode) },
    )
