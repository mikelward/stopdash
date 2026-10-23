package app.stopdash.domain

/**
 * How the near-me list and a searched station's page group their departures (Settings → *Group nearby
 * departures*, maintainer 2026-10-23).
 */
enum class NearbyGrouping {
    /** A card per platform or pole, each headed by its place and platform: the default. */
    PLATFORM,

    /** A heading per station that folds it, over one card of its departures, by platform. */
    STATION,
    ;

    companion object {
        /** A stored name read back; anything unrecognized (a newer build's value) is [PLATFORM]. */
        fun fromStored(name: String?): NearbyGrouping = entries.firstOrNull { it.name == name } ?: PLATFORM
    }
}
