package app.stopdash.domain

/**
 * Which stops are one place (SPEC *Journeys*): the one rule StopDash asks wherever it needs to know which
 * station a stop belongs to, instead of a copy per screen (maintainer, 2026-10-15).
 *
 * Two stops are one place when their names agree up to any cross street ("Hill Station / High Road" is
 * Hill Station's) and they stand within [NAME_REACH_METERS] ([sameNamed]), or when TfL files them under
 * one interchange and one of them is a station: King's Cross St Pancras lists bus stops by names no name
 * match would join. An interchange joins a stop to its station, never two bus stops to each other: two of
 * an interchange's poles named apart stay two places, as the route map keeps them (SPEC *Map*).
 *
 * A station's own bus stops are wider still ([ownStop]): any within [OWN_STOP_METERS] of it, whatever
 * they're called, as TfL files a station's bus stops apart from it. Not the other way round: a station of
 * another name that merely stands near a bus stop isn't that stop's.
 */
object StopPlace {
    /**
     * How far apart two stops named alike may be and still be one place (maintainer, 2026-10-15): a
     * station and the bus stands around it are a street or two apart, well inside it; two "Church Street"s in different boroughs are miles apart and stay two.
     */
    const val NAME_REACH_METERS = 250.0

    /**
     * How near a station a bus stop of any name must be to count as the station's own (maintainer,
     * 2026-09-26): wide enough for a station's own stops, which stand 60–90 m from its position,
     * narrow enough to leave the next road's.
     */
    const val OWN_STOP_METERS = 150.0

    /**
     * A stop as this rule weighs it: its [name], position (null where unknown), interchange ([interchangeId],
     * blank for none) and whether it is a [station], serving a mode other than bus.
     */
    data class Stop(
        val name: String,
        val latitude: Double?,
        val longitude: Double?,
        val interchangeId: String = "",
        val station: Boolean = false,
    )

    /** [stop] as this rule weighs it, without a position where [placed] says it has none. */
    fun of(stop: StopLocation): Stop {
        val placed = placed(stop)
        return Stop(
            name = stop.name,
            latitude = stop.latitude.takeIf { placed },
            longitude = stop.longitude.takeIf { placed },
            interchangeId = stop.interchangeId,
            station = isStation(stop),
        )
    }

    /**
     * Whether [stop] has a position. A stop TfL left either axis out of reads 0.0 on both
     * (toStopLocationOrNull), and a 0.0 latitude is never a real one in London; a 0.0 longitude can be
     * real, on the prime meridian, so it alone says nothing (Codex on #867).
     */
    fun placed(stop: StopLocation): Boolean = stop.latitude != 0.0

    /** Whether [stop] serves a mode other than bus: a station, a tram stop, a pier. */
    fun isStation(stop: StopLocation): Boolean =
        stop.lines.any { it.mode.isNotBlank() && !it.mode.equals(BUS, ignoreCase = true) }

    /** Whether [stop] is a bus stop: one only buses serve. */
    fun isBusStop(stop: StopLocation): Boolean =
        stop.lines.isNotEmpty() && stop.lines.all { it.mode.equals(BUS, ignoreCase = true) }

    /**
     * The part of [name] that names its place: before any cross street, in the form names are compared
     * by ([matchStopName]), case and punctuation aside. Empty for a name with nothing before its slash.
     */
    fun nameRoot(name: String): String =
        StationMatcher.normalize(matchStopName(name.substringBefore("/"))).lowercase()

    /**
     * Whether [a] and [b] are one place by name: their [nameRoot]s agree, no line qualifier tells them
     * apart ([conflictingQualifiers]: Hammersmith's two stations), and they stand within
     * [NAME_REACH_METERS]. Both halves are needed: a name alone joins same-named stops across town, a
     * distance alone a road that merely passes by. Never for a stop without a position.
     */
    fun sameNamed(a: Stop, b: Stop): Boolean {
        val root = nameRoot(a.name)
        if (root.isEmpty() || root != nameRoot(b.name)) return false
        if (conflictingQualifiers(a.name.substringBefore("/"), b.name.substringBefore("/"))) return false
        return within(a, b, NAME_REACH_METERS)
    }

    /** Whether TfL files [a] and [b] under one interchange. */
    fun sameInterchange(a: Stop, b: Stop): Boolean = a.interchangeId.isNotBlank() && a.interchangeId.equals(b.interchangeId, ignoreCase = true)

    /**
     * Whether [a] and [b] are one place: [sameNamed], or [sameInterchange] where one of them is a station.
     */
    fun samePlace(a: Stop, b: Stop): Boolean = sameNamed(a, b) || (sameInterchange(a, b) && (a.station || b.station))

    /**
     * Whether [stop] counts as one of [station]'s own: the [samePlace], or within [OWN_STOP_METERS] of it
     * whatever it's called.
     */
    fun ownStop(station: Stop, stop: Stop): Boolean = samePlace(station, stop) || within(station, stop, OWN_STOP_METERS)

    private fun within(a: Stop, b: Stop, meters: Double): Boolean {
        val latA = a.latitude ?: return false
        val lonA = a.longitude ?: return false
        val latB = b.latitude ?: return false
        val lonB = b.longitude ?: return false
        return NearestStops.distanceMeters(latA, lonA, latB, lonB) <= meters
    }

    private const val BUS = "bus"
}
