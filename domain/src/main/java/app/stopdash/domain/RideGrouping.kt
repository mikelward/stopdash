package app.stopdash.domain

/**
 * How the near-me list groups a favorite ride's departures (Settings → *Group favorite rides*,
 * maintainer 2026-10-14). The ride's own view heads every stop either way.
 */
enum class RideGrouping {
    /** Under the ride's heading, with no heading for each stop it boards from: the default. */
    RIDE,

    /** Under each stop it boards from, starred and saying where the ride goes, with no ride heading. */
    STOP,
    ;

    companion object {
        /** A stored name read back; anything unrecognized (a newer build's value) is [RIDE]. */
        fun fromStored(name: String?): RideGrouping = entries.firstOrNull { it.name == name } ?: RIDE
    }
}
