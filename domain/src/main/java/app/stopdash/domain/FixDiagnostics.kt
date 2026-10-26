package app.stopdash.domain

import kotlin.math.roundToLong

/**
 * The one-line diagnostic for a location fix the app used: where it came from, how accurate the
 * provider says it is, and how old it is — never the coordinate (SPEC *Privacy*, `docs/PRIVACY.md`).
 *
 * It exists to explain a wrong nearby set (maintainer, 2026-09-23): underground the network provider
 * places the user by the station's Wi-Fi, often at a different station, and whether the lever is
 * accuracy (a confident but wrong fix), staleness (a last-known fix near the fallback limit), or the
 * provider can only be told apart from what the fix actually reported. A missing accuracy estimate is
 * printed as "unknown", never 0 m, which would read as a perfect fix.
 */
object FixDiagnostics {
    /** How the fix was chosen: a fresh request, the instant recent-cache path, or the fallback. */
    enum class Source(val label: String) {
        FRESH("fresh"),
        RECENT_CACHED("recent cached"),
        FALLBACK("last-known fallback"),
        /** The follow-up request to GPS/fused after a coarse fix was shown ([LocationProvider.precise]). */
        PRECISE("precise follow-up"),
        /** A remembered precise fix used in place of a coarse one that agreed with it ([PreciseFixMemory]). */
        REMEMBERED("remembered precise"),
        /** The network provider alone, asked for a trip on the way when no precise fix came. */
        APPROXIMATE("approximate"),
    }

    /**
     * The remembered precise fix as weighed against a coarse (network) fix of [coarseAccuracyMeters]:
     * its own provider, accuracy and age, how far it lies from the coarse fix, and whether it was
     * used. A distance between two fixes, never either position (SPEC *Privacy*); the positions go to [RecentPositions].
     */
    fun describeRemembered(considered: PreciseFixMemory.Considered, coarseAccuracyMeters: Float?): String {
        val recalled = considered.recalled
        val accuracy = coarseAccuracyMeters?.let { "its ${it.roundToLong()} m accuracy" } ?: "its accuracy (unknown)"
        val verdict = if (considered.used) "inside $accuracy, used" else "outside $accuracy, not used"
        return describe(Source.REMEMBERED, recalled.provider, recalled.accuracyMeters, recalled.ageMillis) +
            ", ${considered.apartMeters.roundToLong()} m from the network fix, $verdict"
    }

    fun describe(source: Source, provider: String, accuracyMeters: Float?, ageMillis: Long): String {
        val accuracy = accuracyMeters?.let { "accuracy ${it.roundToLong()} m" } ?: "accuracy unknown"
        val age = "${(ageMillis.coerceAtLeast(0) + 500) / 1000} s old"
        return "location fix: ${source.label} from $provider, $accuracy, $age"
    }

    /**
     * A position as `lat,lon` to 5 decimal places (about a metre), locale-independent — for
     * [RecentPositions] only, never the diagnostic log.
     */
    fun position(at: Coordinates): String =
        String.format(java.util.Locale.ROOT, "%.5f,%.5f", at.latitude, at.longitude)
}
