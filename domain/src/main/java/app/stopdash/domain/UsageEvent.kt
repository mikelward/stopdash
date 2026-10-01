package app.stopdash.domain

import java.time.Duration

/**
 * The custom usage events sent with **Help make StopDash better** on (SPEC *Privacy*, maintainer
 * 2026-09-24): what was used and how it went, never what it was used on. Every parameter value is a
 * category or a bucket from the closed vocabulary here, so no stop, line, journey, search text or
 * coordinate can reach one: nothing a caller passes is sent as it is.
 *
 * [name] and each parameter key follow Firebase's rules (letters, digits and underscores, starting
 * with a letter, at most 40 characters); each value is at most 100 characters.
 */
sealed class UsageEvent(val name: String, val params: Map<String, String>) {

    /** A tap, by what was tapped. */
    class Tapped(kind: Tap) : UsageEvent("tap", mapOf("kind" to kind.value))

    /** What a [Tapped] names. */
    enum class Tap(val value: String) {
        JOURNEY_CARD("journey_card"),
        STOP_ROW("stop_row"),
        CHANGE_CARD("change_card"),
        SWAP("swap"),
        STAR("star"),
        UNSTAR("unstar"),
        SEARCH("search"),
        SETTINGS("settings"),
    }

    /**
     * A farther place opened on the near-me list (the farther cards that took over "More stops"),
     * by its mode group ([modeGroup]): a bus place is "bus", a station the group of its first line
     * (a station's lines share one in all but a few interchanges); one with no lines is "other".
     */
    class FartherPlace(modes: List<String>) :
        UsageEvent("reveal", mapOf("what" to "farther_place", "mode" to (modes.firstOrNull()?.let(::modeGroup) ?: "other")))

    /** The near-me list's "Faraway favorites" reveal. */
    object FarawayFavorites : UsageEvent("reveal", mapOf("what" to "faraway_favorites"))

    /** The location permission the rider left the app with, after it asked. */
    class LocationPermission(grant: Grant) : UsageEvent("location_permission", mapOf("grant" to grant.value))

    /** What a [LocationPermission] names. */
    enum class Grant(val value: String) {
        PRECISE("precise"),
        APPROXIMATE("approximate"),
        DENIED("denied");

        companion object {
            /** The grant an answer left: precise with fine location, approximate with coarse alone. */
            fun of(fine: Boolean, coarse: Boolean): Grant = when {
                fine -> PRECISE
                coarse -> APPROXIMATE
                else -> DENIED
            }
        }
    }

    /**
     * How a near-me fix went: a fresh one, the last known one, or none; its accuracy and how long it
     * took, each in bands ([accuracyBand], [timeBand]), unknown where there was no fix.
     */
    class LocationFix(outcome: FixOutcome, accuracyMeters: Float?, took: Duration?) : UsageEvent(
        "location_fix",
        mapOf(
            "outcome" to outcome.value,
            "accuracy" to accuracyBand(accuracyMeters.takeIf { outcome != FixOutcome.FAILED }),
            "time_to_fix" to timeBand(took),
        ),
    )

    /** What a [LocationFix] names. */
    enum class FixOutcome(val value: String) { FRESH("fresh"), LAST_KNOWN("last_known"), FAILED("failed") }

    /**
     * How many stops a near-me lookup found, per mode group ([ModeGroups.ALL]), each in a bucket
     * ([countBucket]): every group present, a group with none as "0". A stop counts once for each
     * group any of its lines runs in (a station with the Tube and the Overground, for both).
     */
    class NearbyStops(stops: List<StopLocation>) : UsageEvent(
        "nearby_stops",
        ModeGroups.ALL.associate { group ->
            group.key to countBucket(stops.count { stop -> stop.lines.any { modeGroup(it.mode) == group.key } })
        },
    )

    override fun toString(): String = "$name$params"

    companion object {
        /** A mode's group key ([ModeGroups.ALL]), or "other" for one no group names, never the raw id. */
        fun modeGroup(mode: String): String =
            ModeGroups.ALL.firstOrNull { g -> g.modes.any { it.equals(mode, ignoreCase = true) } }?.key ?: "other"

        /** A count as "0", "1", "2-3" or "4+". */
        fun countBucket(count: Int): String = when {
            count <= 0 -> "0"
            count == 1 -> "1"
            count <= 3 -> "2-3"
            else -> "4+"
        }

        /** A fix's accuracy radius in bands; "unknown" with none. */
        fun accuracyBand(meters: Float?): String = when {
            meters == null || meters.isNaN() || meters < 0f -> "unknown"
            meters <= 10f -> "0-10m"
            meters <= 25f -> "10-25m"
            meters <= 50f -> "25-50m"
            meters <= 100f -> "50-100m"
            meters <= 500f -> "100-500m"
            else -> "500m+"
        }

        /** How long a fix took, in bands; "unknown" with no time. */
        fun timeBand(took: Duration?): String = when {
            took == null || took.isNegative -> "unknown"
            took < Duration.ofSeconds(1) -> "0-1s"
            took < Duration.ofSeconds(3) -> "1-3s"
            took < Duration.ofSeconds(10) -> "3-10s"
            took < Duration.ofSeconds(30) -> "10-30s"
            else -> "30s+"
        }
    }
}
