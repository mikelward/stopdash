package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Where on the train to ride so a change or the way out is close when the rider gets off (SPEC *Where to
 * stand*; maintainer, 2026-11-05): the front, the middle or the back, or any carriage where the change is across the
 * platform.
 *
 * Read by hand from TfL's station layout drawings ([SOURCE]): each [Platforms] records, for a line's
 * platforms at a station, the station each end leads toward and which end the way to each next line or the
 * exit leaves from. Only the facts are kept, never the drawings. Front and back aren't stored: they follow
 * from which end the rider's train comes in at, the stop before on its ride ([standAt]), so a train either
 * way reads its own. A station not listed, a ride whose stop before isn't known or isn't one of the ends,
 * or a way on not listed, says nothing: a guess sends the rider the wrong way down the platform
 * (SPEC principle 1).
 */
object PlatformEnds {
    /** Where on the train to ride: [ANY] for a change across the platform, which every door is near. */
    enum class Spot { FRONT, MIDDLE, BACK, ANY }

    /**
     * Which part of a platform a way leaves from: the end toward [Platforms.endA], the middle, or toward [Platforms.endB];
     * or [ACROSS], the other side of the same platform: any carriage.
     */
    enum class End { A, MIDDLE, B, ACROSS }

    /**
     * A line's platforms at a station: [stationId] (TfL's `940G…`), [lineId], the stations each end leads
     * toward ([endA], [endB]: the stop before or after it on the line), and where each way leaves ([ways]),
     * by the line it goes to (TfL's id), the line toward a stop ([toward]), or [EXIT]. A station two of a line's
     * routes cross at its own platforms (Euston's two Northern line branches) has one entry each, told apart by
     * their ends. [arriving], where set, holds the entry to trains coming in from that end's station: the ways
     * only one platform of the pair has (the one sharing an island with another line going the same way).
     */
    data class Platforms(
        val stationId: String,
        val lineId: String,
        val endA: String,
        val endB: String,
        val ways: Map<String, End>,
        val arriving: String? = null,
    )

    /** The way out of the station, as a key of [Platforms.ways]. */
    const val EXIT = "exit"

    /**
     * A key of [Platforms.ways] for [lineId]'s trains whose next stop after the station is [stopId]: which branch
     * (Euston's Northern line: Camden Town or King's Cross for the Bank branch, Mornington Crescent or Warren
     * Street for Charing Cross) and which way, where the line alone doesn't say. Looked up before the line's own.
     */
    fun toward(lineId: String, stopId: String): String = "$lineId>$stopId"

    /** Where the facts were read: TfL's station layout drawings as published in 2015. */
    const val SOURCE = "TfL station layout axonometrics, 2015, via ianvisits.co.uk/articles/3d-maps-of-every-underground-station-ab-14630"

    /**
     * Where to ride on [trip]'s ride [index] to be near what comes after it: the next ride's line where the rider
     * changes at the same station, else the way out. Null where it isn't known (see [PlatformEnds]).
     */
    @WorkerThread
    fun standAt(trip: ActiveTrip, index: Int, table: List<Platforms> = TABLE): Spot? {
        val legs = trip.route.legs
        // The ride as the rider takes it ([RouteDisruption.rideAt]): another line's train followed comes in its own way.
        val ride = legs.getOrNull(index)?.takeIf { !it.isWalk }?.let { RouteDisruption.rideAt(trip, index, it) } ?: return null
        val station = setOf(ride.toId, ride.toArea).filter { it.isNotBlank() }
        val prev = RouteDisruption.rideCalls(ride).dropLastWhile { it in station }.lastOrNull() ?: return null
        val onward = legs.getOrNull(index + 1)
        val next = when {
            onward == null -> null
            // A change on foot within the station ([OnTheWay.changesOnFoot]) is to the ride after it, never the
            // exit: one the table doesn't list says nothing (Codex on #1257).
            onward.isWalk && OnTheWay.changesOnFoot(trip, index + 1) -> legs.getOrNull(index + 2)?.takeIf { !it.isWalk } ?: return null
            // A change at the same station is to its line; a walk on leaves by the exit.
            !onward.isWalk && (onward.fromId in station || onward.fromArea in station) -> onward
            onward.isWalk -> null
            // A ride from another stop, with no walk between: where it's reached from isn't known.
            else -> return null
        }
        // The next ride's line toward its first stop after the station (its branch and way), then the line alone.
        val ways = if (next == null) {
            listOf(EXIT)
        } else {
            val first = RouteDisruption.rideCalls(next).dropWhile { it in station || it == next.fromId || it == next.fromArea }.firstOrNull()
            listOfNotNull(first?.let { toward(next.lineId, it) }, next.lineId)
        }
        val here = table.filter { it.stationId in station && it.lineId == ride.lineId && prev in setOf(it.endA, it.endB) }
        // An entry held to trains from this side first, then the platforms' own.
        val candidates = here.filter { it.arriving == prev } + here.filter { it.arriving == null }
        val (platforms, end) = ways.firstNotNullOfOrNull { way -> candidates.firstNotNullOfOrNull { p -> p.ways[way]?.let { p to it } } } ?: return null
        // The train comes in at the end toward the stop before, so its front stops at the other.
        val inAtA = prev == platforms.endA
        return when (end) {
            End.ACROSS -> Spot.ANY
            End.MIDDLE -> Spot.MIDDLE
            End.A -> if (inAtA) Spot.BACK else Spot.FRONT
            End.B -> if (inAtA) Spot.FRONT else Spot.BACK
        }
    }

    // TfL station ids, for reading the table.
    private const val CHARING_CROSS = "940GZZLUCHX"
    private const val CAMDEN_TOWN = "940GZZLUCTN"
    private const val EUSTON = "940GZZLUEUS"
    private const val KINGS_CROSS = "940GZZLUKSX"
    private const val LEICESTER_SQUARE = "940GZZLULSQ"
    private const val MORNINGTON_CRESCENT = "940GZZLUMTC"
    private const val OXFORD_CIRCUS = "940GZZLUOXC"
    private const val TOTTENHAM_COURT_ROAD = "940GZZLUTCR"
    private const val WARREN_STREET = "940GZZLUWRR"

    /**
     * Every station read so far ([SOURCE]). Each entry says what its drawing shows, so it can be checked
     * against it; a station rebuilt since 2015 is left out until it's read from how it is now.
     */
    val TABLE: List<Platforms> = listOf(
        // Euston, Northern line (Charing Cross branch), platforms 1 and 2: the passages to the Victoria line and the
        // Bank branch, and escalators 7 and 8 up to the ticket hall, leave from the Mornington Crescent end; the
        // Warren Street end leads only to a disused passage.
        Platforms(EUSTON, "northern", MORNINGTON_CRESCENT, WARREN_STREET, mapOf("victoria" to End.A, "northern" to End.A, EXIT to End.A)),
        // Euston, Northern line (Bank branch), platforms 3 and 6: escalators 1 to 4 land at the Camden Town end, where
        // the passages to the Charing Cross branch start.
        Platforms(EUSTON, "northern", CAMDEN_TOWN, KINGS_CROSS, mapOf("northern" to End.A, EXIT to End.A)),
        // Platform 3 (northbound, in from King's Cross) shares an island with the Victoria line's platform 4, northbound
        // toward King's Cross; platform 6 (southbound, in from Camden Town) with its platform 5, toward Warren Street.
        Platforms(EUSTON, "northern", CAMDEN_TOWN, KINGS_CROSS, mapOf(toward("victoria", KINGS_CROSS) to End.ACROSS), arriving = KINGS_CROSS),
        Platforms(EUSTON, "northern", CAMDEN_TOWN, KINGS_CROSS, mapOf(toward("victoria", WARREN_STREET) to End.ACROSS), arriving = CAMDEN_TOWN),
        // Euston, Victoria line, platforms 4 and 5: escalators 1 to 4, and the passages to the Charing Cross branch,
        // land at the Warren Street end.
        Platforms(
            EUSTON, "victoria", WARREN_STREET, KINGS_CROSS,
            mapOf(EXIT to End.A, toward("northern", MORNINGTON_CRESCENT) to End.A, toward("northern", WARREN_STREET) to End.A),
        ),
        // Platform 4 (northbound, in from Warren Street) shares an island with the Bank branch's platform 3, toward
        // Camden Town; platform 5 (southbound, in from King's Cross) with its platform 6, toward King's Cross.
        Platforms(EUSTON, "victoria", WARREN_STREET, KINGS_CROSS, mapOf(toward("northern", CAMDEN_TOWN) to End.ACROSS), arriving = WARREN_STREET),
        Platforms(EUSTON, "victoria", WARREN_STREET, KINGS_CROSS, mapOf(toward("northern", KINGS_CROSS) to End.ACROSS), arriving = KINGS_CROSS),
        // Leicester Square, Northern line, platforms 3 and 4: the escalators to the ticket hall and the subway to
        // the Piccadilly line leave from the south (Charing Cross) end.
        Platforms(LEICESTER_SQUARE, "northern", CHARING_CROSS, TOTTENHAM_COURT_ROAD, mapOf("piccadilly" to End.A, EXIT to End.A)),
        // Warren Street, Victoria line, platforms 3 and 4: escalators 7 and 9, the only way off, land midway along.
        Platforms(WARREN_STREET, "victoria", EUSTON, OXFORD_CIRCUS, mapOf("northern" to End.MIDDLE, EXIT to End.MIDDLE)),
    )
}
