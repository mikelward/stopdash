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
    /**
     * Where on the train to ride: [ANY] for a change across the platform, which every door is near; [FRONT_MIDDLE] and
     * [BACK_MIDDLE] for a way off about a third of the way along, between an end and the middle (maintainer, 2026-11-06).
     */
    enum class Spot { FRONT, FRONT_MIDDLE, MIDDLE, BACK_MIDDLE, BACK, ANY }

    /**
     * Which part of a platform a way leaves from, by where it meets the platform from the [Platforms.endA] end: [A] in
     * its first fifth, [A_MIDDLE] in its second, [MIDDLE] in the middle fifth, [B_MIDDLE] and [B] likewise from the
     * [Platforms.endB] end; or [ACROSS], the other side of the same platform: any carriage.
     */
    enum class End { A, A_MIDDLE, MIDDLE, B_MIDDLE, B, ACROSS }

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

    /**
     * A key of [Platforms.ways] for one of a station's exits, by the ticket hall or street it leads to, where the station
     * has several and the best depends on where the rider is going (King's Cross: the Northern ticket hall for Granary
     * Square, the tube ticket hall for Euston Road; maintainer, 2026-11-05). Chosen by the walk on ([exitToward]), before
     * the station's one [EXIT].
     */
    fun exitTo(name: String): String = "$EXIT:$name"

    /**
     * One of a station's named exits: the compass [bearing] it leads out toward, in degrees from north, as its drawing and
     * the streets around it place it, and the [sign] the station's own signs give it ("Regent's Canal"), where known: a
     * place name, said as the signs say it, never a guess.
     */
    data class Exit(val bearing: Double, val sign: String? = null)

    /** Each station's named exits ([exitTo], [Exit]): what [exitToward] matches the walk on against. */
    val EXITS: Map<String, Map<String, Exit>> = mapOf(
        // King's Cross St Pancras: the tube ticket hall comes up on Euston Road, to the south; the Northern ticket hall,
        // serving the Northern and Piccadilly lines beside the tunnel to St Pancras, is at the Regent's Canal end and
        // leads north, to Granary Square, Regent's Canal, Pancras Square and Coal Drops Yard (maintainer, 2026-11-05).
        // The sub-surface lines' ticket hall is by Euston Road too; their platforms aren't read yet.
        // Its signs call the Northern ticket hall's way out Regent's Canal (maintainer, 2026-11-05); the tube ticket hall's
        // wording isn't recorded yet.
        "940GZZLUKSX" to mapOf("tube-ticket-hall" to Exit(180.0), "northern-ticket-hall" to Exit(0.0, sign = "Regent's Canal")),
    )

    /** How far from an exit's bearing the walk on can head and still be through it ([exitToward]). */
    const val EXIT_WITHIN_DEGREES = 60.0

    /** How far the walk on must go for its heading to choose an exit ([exitToward]): nearer, any exit will do. */
    const val EXIT_FROM_METERS = 150.0

    /**
     * Which of [exits] (a station's, [EXITS]) the walk from [from] to [to] leaves by: the one whose bearing is within
     * [EXIT_WITHIN_DEGREES] of the walk's, where it's the only one and the walk goes at least [EXIT_FROM_METERS]. Null
     * otherwise: a walk off between two exits, or too short to tell, says nothing (SPEC principle 1).
     */
    fun exitToward(exits: Map<String, Exit>, from: Coordinates, to: Coordinates): String? {
        if (NearestStops.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude) < EXIT_FROM_METERS) return null
        val heading = Compass.bearingDegrees(from.latitude, from.longitude, to.latitude, to.longitude)
        return exits.filterValues { exit -> angleBetween(heading, exit.bearing) <= EXIT_WITHIN_DEGREES }.keys.singleOrNull()
    }

    // The smaller angle between two bearings, in degrees.
    private fun angleBetween(a: Double, b: Double): Double = ((a - b) % 360.0 + 360.0).let { minOf(it % 360.0, 360.0 - it % 360.0) }

    /** Where the facts were read: TfL's station layout drawings as published in 2015. */
    const val SOURCE = "TfL station layout axonometrics, 2015, via ianvisits.co.uk/articles/3d-maps-of-every-underground-station-ab-14630"

    /** Where to ride ([spot]), and the sign of the exit it's for ([Exit.sign]) where it's one of a station's named exits. */
    data class Stand(val spot: Spot, val exitSign: String? = null)

    /** Where to ride on [trip]'s ride [index] ([stand]), without the exit's sign. */
    @WorkerThread
    fun standAt(trip: ActiveTrip, index: Int, table: List<Platforms> = TABLE): Spot? = stand(trip, index, table)?.spot

    /**
     * Where to ride on [trip]'s ride [index] to be near what comes after it: the next ride's line where the rider
     * changes at the same station, else the way out, with the sign of the exit where it's a named one the walk on heads
     * for. Null where it isn't known (see [PlatformEnds]).
     */
    @WorkerThread
    fun stand(trip: ActiveTrip, index: Int, table: List<Platforms> = TABLE): Stand? {
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
            // A change at the same station is to its line; a walk on, or a ride from a stop outside it (a bus from the
            // street, the Planner giving no walk between), leaves by the exit.
            !onward.isWalk && (onward.fromId in station || onward.fromArea in station) -> onward
            else -> null
        }
        var exitSign: String? = null
        // The next ride's line toward its first stop after the station (its branch and way), then the line alone. Leaving
        // the station, the exit the walk on heads for, where the station has several ([exitToward]), then its one exit.
        val ways = if (next == null) {
            val walkOn = onward?.takeIf { it.isWalk }
            val from = ride.toAt ?: walkOn?.fromAt
            // Where the way out heads for: the walk on's end, or straight to the next ride's stop.
            // Where the walk on ends: its own position where given; else the next ride's stop, a public one; else, where it
            // ends the trip, the trip's destination place, already kept with the trip. The Planner keeps no walk's end, as
            // it can be the rider's own place (Codex, #1261), so this never adds one.
            val to = if (walkOn == null) {
                onward?.fromAt
            } else {
                walkOn.toAt
                    ?: legs.getOrNull(index + 2)?.takeIf { !it.isWalk }?.fromAt
                    ?: trip.destinations.filterIsInstance<TripDestination.Place>().singleOrNull()?.coordinate?.takeIf { index + 1 == legs.lastIndex }
            }
            val exits = station.firstNotNullOfOrNull { EXITS[it] }
            val named = exits?.takeIf { from != null && to != null }?.let { exitToward(it, checkNotNull(from), checkNotNull(to)) }
            named?.let { exitSign = exits[it]?.sign }
            listOfNotNull(named?.let(::exitTo), EXIT)
        } else {
            val first = RouteDisruption.rideCalls(next).dropWhile { it in station || it == next.fromId || it == next.fromArea }.firstOrNull()
            listOfNotNull(first?.let { toward(next.lineId, it) }, next.lineId)
        }
        val here = table.filter { it.stationId in station && it.lineId == ride.lineId && prev in setOf(it.endA, it.endB) }
        // An entry held to trains from this side first, then the platforms' own.
        val candidates = here.filter { it.arriving == prev } + here.filter { it.arriving == null }
        val (way, platforms, end) = ways.firstNotNullOfOrNull { way ->
            candidates.firstNotNullOfOrNull { p -> p.ways[way]?.let { Triple(way, p, it) } }
        } ?: return null
        // The train comes in at the end toward the stop before, so its front stops at the other.
        val inAtA = prev == platforms.endA
        val spot = when (end) {
            End.ACROSS -> Spot.ANY
            End.MIDDLE -> Spot.MIDDLE
            End.A -> if (inAtA) Spot.BACK else Spot.FRONT
            End.A_MIDDLE -> if (inAtA) Spot.BACK_MIDDLE else Spot.FRONT_MIDDLE
            End.B_MIDDLE -> if (inAtA) Spot.FRONT_MIDDLE else Spot.BACK_MIDDLE
            End.B -> if (inAtA) Spot.FRONT else Spot.BACK
        }
        // The exit's sign only where it's the named exit that was found, never the station's one exit.
        return Stand(spot, exitSign.takeIf { way != EXIT })
    }

    // TfL station ids, for reading the table.
    private const val CHANCERY_LANE = "940GZZLUCHL"
    private const val CHARING_CROSS = "940GZZLUCHX"
    private const val COVENT_GARDEN = "940GZZLUCGN"
    private const val EMBANKMENT = "940GZZLUEMB"
    private const val ANGEL = "940GZZLUAGL"
    private const val ARSENAL = "940GZZLUASL"
    private const val BOND_STREET = "940GZZLUBND"
    private const val BETHNAL_GREEN = "940GZZLUBLG"
    private const val BAYSWATER = "940GZZLUBWT"
    private const val BOROUGH = "940GZZLUBOR"
    private const val BOW_ROAD = "940GZZLUBWR"
    private const val BRIXTON = "940GZZLUBXN"
    private const val CALEDONIAN_ROAD = "940GZZLUCAR"
    private const val CLAPHAM_NORTH = "940GZZLUCPN"
    private const val FINSBURY_PARK = "940GZZLUFPK"
    private const val HIGHBURY = "940GZZLUHAI"
    private const val MANOR_HOUSE = "940GZZLUMRH"
    private const val OVAL = "940GZZLUOVL"
    private const val SEVEN_SISTERS = "940GZZLUSVS"
    private const val STEPNEY_GREEN = "940GZZLUSGN"
    private const val STOCKWELL = "940GZZLUSKW"
    private const val STRATFORD = "940GZZLUSTD"
    private const val VAUXHALL = "940GZZLUVXL"
    private const val GREEN_PARK = "940GZZLUGPK"
    private const val HYDE_PARK_CORNER = "940GZZLUHPC"
    private const val VICTORIA = "940GZZLUVIC"
    private const val WESTMINSTER = "940GZZLUWSM"
    private const val HIGH_STREET_KENSINGTON = "940GZZLUHSK"
    private const val HOLBORN = "940GZZLUHBN"
    private const val ARCHWAY = "940GZZLUACY"
    private const val EAST_FINCHLEY = "940GZZLUEFY"
    private const val HIGHGATE = "940GZZLUHGT"
    private const val HOLLAND_PARK = "940GZZLUHPK"
    private const val KENNINGTON = "940GZZLUKNG"
    private const val LAMBETH_NORTH = "940GZZLULBN"
    private const val MILE_END = "940GZZLUMED"
    private const val NOTTING_HILL_GATE = "940GZZLUNHG"
    private const val QUEENSWAY = "940GZZLUQWY"
    private const val PICCADILLY_CIRCUS = "940GZZLUPCC"
    private const val REGENTS_PARK = "940GZZLURGP"
    private const val RUSSELL_SQUARE = "940GZZLURSQ"
    private const val WATERLOO = "940GZZLUWLO"
    private const val CAMDEN_TOWN = "940GZZLUCTN"
    private const val ELEPHANT_AND_CASTLE = "940GZZLUEAC"
    private const val EUSTON = "940GZZLUEUS"
    private const val KINGS_CROSS = "940GZZLUKSX"
    private const val LEICESTER_SQUARE = "940GZZLULSQ"
    private const val MORNINGTON_CRESCENT = "940GZZLUMTC"
    private const val OXFORD_CIRCUS = "940GZZLUOXC"
    private const val TOTTENHAM_COURT_ROAD = "940GZZLUTCR"
    private const val WARREN_STREET = "940GZZLUWRR"
    private const val ARNOS_GROVE = "940GZZLUASG"
    private const val BANK = "940GZZLUBNK"
    private const val BLACKFRIARS = "940GZZLUBKF"
    private const val BOUNDS_GREEN = "940GZZLUBDS"
    private const val FINCHLEY_ROAD = "940GZZLUFYR"
    private const val GOLDERS_GREEN = "940GZZLUGGN"
    private const val HAMPSTEAD = "940GZZLUHTD"
    private const val HATTON_CROSS = "940GZZLUHNX"
    private const val HEATHROW_123 = "940GZZLUHRC"
    private const val HOUNSLOW_CENTRAL = "940GZZLUHWC"
    private const val HOUNSLOW_WEST = "940GZZLUHWT"
    private const val KENTISH_TOWN = "940GZZLUKSH"
    private const val KILBURN_PARK = "940GZZLUKPK"
    private const val LANCASTER_GATE = "940GZZLULGT"
    private const val LEYTONSTONE = "940GZZLULYS"
    private const val MAIDA_VALE = "940GZZLUMVL"
    private const val MARBLE_ARCH = "940GZZLUMBA"
    private const val MORDEN = "940GZZLUMDN"
    private const val OAKWOOD = "940GZZLUOAK"
    private const val PIMLICO = "940GZZLUPCO"
    private const val QUEENS_PARK = "940GZZLUQPS"
    private const val SLOANE_SQUARE = "940GZZLUSSQ"
    private const val SOUTHGATE = "940GZZLUSGT"
    private const val SOUTHWARK = "940GZZLUSWK"
    private const val SWISS_COTTAGE = "940GZZLUSWC"
    private const val TEMPLE = "940GZZLUTMP"
    private const val TURNPIKE_LANE = "940GZZLUTPN"
    private const val WANSTEAD = "940GZZLUWSD"
    private const val WARWICK_AVENUE = "940GZZLUWKA"
    private const val WHITECHAPEL = "940GZZLUWPL"
    private const val WHITE_CITY = "940GZZLUWCY"
    private const val WOOD_GREEN = "940GZZLUWOG"
    private const val ALDGATE = "940GZZLUALD"
    private const val BAKER_STREET = "940GZZLUBST"
    private const val BALHAM = "940GZZLUBLM"
    private const val BARBICAN = "940GZZLUBBN"
    private const val BARONS_COURT = "940GZZLUBSC"
    private const val BELSIZE_PARK = "940GZZLUBZP"
    private const val BERMONDSEY = "940GZZLUBMY"
    private const val BLACKHORSE_ROAD = "940GZZLUBLR"
    private const val BROMLEY_BY_BOW = "940GZZLUBBB"
    private const val CANADA_WATER = "940GZZLUCWR"
    private const val CANNON_STREET = "940GZZLUCST"
    private const val CHALK_FARM = "940GZZLUCFM"
    private const val CLAPHAM_COMMON = "940GZZLUCPC"
    private const val CLAPHAM_SOUTH = "940GZZLUCPS"
    private const val COLLIERS_WOOD = "940GZZLUCSD"
    private const val EARLS_COURT = "940GZZLUECT"
    private const val EDGWARE_ROAD = "940GZZLUERB"
    private const val EUSTON_SQUARE = "940GZZLUESQ"
    private const val FARRINGDON = "940GZZLUFCN"
    private const val FULHAM_BROADWAY = "940GZZLUFBY"
    private const val GANTS_HILL = "940GZZLUGTH"
    private const val GLOUCESTER_ROAD = "940GZZLUGTR"
    private const val GOODGE_STREET = "940GZZLUGDG"
    private const val GREAT_PORTLAND_STREET = "940GZZLUGPS"
    private const val HOLLOWAY_ROAD = "940GZZLUHWY"
    private const val KNIGHTSBRIDGE = "940GZZLUKNB"
    private const val LIVERPOOL_STREET = "940GZZLULVT"
    private const val LONDON_BRIDGE = "940GZZLULNB"
    private const val MANSION_HOUSE = "940GZZLUMSH"
    private const val MARYLEBONE = "940GZZLUMYB"
    private const val MONUMENT = "940GZZLUMMT"
    private const val MOORGATE = "940GZZLUMGT"
    private const val NEWBURY_PARK = "940GZZLUNBP"
    private const val OLD_STREET = "940GZZLUODS"
    private const val PADDINGTON = "940GZZLUPAC"
    private const val PARSONS_GREEN = "940GZZLUPSG"
    private const val REDBRIDGE = "940GZZLURBG"
    private const val SHEPHERDS_BUSH = "940GZZLUSBC"
    private const val SOUTH_KENSINGTON = "940GZZLUSKS"
    private const val SOUTH_WIMBLEDON = "940GZZLUSWN"
    private const val ST_JOHNS_WOOD = "940GZZLUSJW"
    private const val ST_PAULS = "940GZZLUSPU"
    private const val TOOTING_BEC = "940GZZLUTBC"
    private const val TOOTING_BROADWAY = "940GZZLUTBY"
    private const val TOTTENHAM_HALE = "940GZZLUTMH"
    private const val TOWER_HILL = "940GZZLUTWH"
    private const val TUFNELL_PARK = "940GZZLUTFP"
    private const val WALTHAMSTOW_CENTRAL = "940GZZLUWWL"
    private const val WEST_BROMPTON = "940GZZLUWBN"

    /**
     * Every station read so far ([SOURCE]). Each entry says what its drawing shows, so it can be checked
     * against it; a station rebuilt since 2015 is left out until it's read from how it is now.
     */
    val TABLE: List<Platforms> = listOf(
        // Elephant & Castle, Northern line, platforms 1 and 2: the passage to the Bakerloo line leaves from the north
        // (Borough) end; the Northern ticket hall's lifts are at the other end, and the Bakerloo's at this one, so the way
        // out isn't read.
        Platforms(ELEPHANT_AND_CASTLE, "northern", BOROUGH, KENNINGTON, mapOf("bakerloo" to End.A)),
        // Elephant & Castle, Bakerloo line, platforms 3 and 4, where the line ends: the passage to the Northern line is at
        // the buffer end, the front of a train in from Lambeth North. Its ticket hall's lifts land partway along.
        Platforms(ELEPHANT_AND_CASTLE, "bakerloo", LAMBETH_NORTH, "", mapOf("northern" to End.B)),
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
        // Embankment, Bakerloo line, platforms 5 and 6: both interchange passages, to escalators 1 and 2 up to the
        // Northern line, the District and Circle lines and the ticket hall, meet the platforms midway.
        Platforms(
            EMBANKMENT, "bakerloo", CHARING_CROSS, WATERLOO,
            mapOf(EXIT to End.MIDDLE, "northern" to End.MIDDLE, "district" to End.MIDDLE, "circle" to End.MIDDLE),
        ),
        // Embankment, Northern line: on platform 3 (northbound) the passages to the Bakerloo line, the District and
        // Circle lines and escalators 8 and 10 leave midway. On platform 4 (southbound) escalators 5 and 6 land from
        // about a fifth to halfway along from the Charing Cross end: nearer the middle than either end (maintainer,
        // 2026-11-05).
        Platforms(
            EMBANKMENT, "northern", CHARING_CROSS, WATERLOO,
            mapOf(EXIT to End.MIDDLE, "bakerloo" to End.MIDDLE, "district" to End.MIDDLE, "circle" to End.MIDDLE),
        ),
        // Finsbury Park, Victoria and Piccadilly lines: each direction's two share an island, northbound on platforms 1
        // (Piccadilly, toward Manor House) and 2 (Victoria, toward Seven Sisters), southbound on 3 (Piccadilly, toward
        // Arsenal) and 4 (Victoria, toward Highbury & Islington). The ways out leave from more than one place along them.
        Platforms(FINSBURY_PARK, "victoria", HIGHBURY, SEVEN_SISTERS, mapOf(toward("piccadilly", MANOR_HOUSE) to End.ACROSS), arriving = HIGHBURY),
        Platforms(FINSBURY_PARK, "victoria", HIGHBURY, SEVEN_SISTERS, mapOf(toward("piccadilly", ARSENAL) to End.ACROSS), arriving = SEVEN_SISTERS),
        Platforms(FINSBURY_PARK, "piccadilly", ARSENAL, MANOR_HOUSE, mapOf(toward("victoria", SEVEN_SISTERS) to End.ACROSS), arriving = ARSENAL),
        Platforms(FINSBURY_PARK, "piccadilly", ARSENAL, MANOR_HOUSE, mapOf(toward("victoria", HIGHBURY) to End.ACROSS), arriving = MANOR_HOUSE),
        // Green Park, Victoria line, platforms 3 and 4: the subway to the Piccadilly line starts at the Oxford Circus
        // end, the passage to the Jubilee line at the Victoria end. Escalators C land partway along, so the way out
        // isn't read.
        Platforms(GREEN_PARK, "victoria", OXFORD_CIRCUS, VICTORIA, mapOf("piccadilly" to End.A, "jubilee" to End.B)),
        // Green Park, Piccadilly line, platforms 1 and 2: the subway to the Victoria line and the escalators to the
        // ticket hall leave from the Hyde Park Corner end. The Jubilee line is reached two ways (that subway, or the
        // passage past the lift from nearer the Piccadilly Circus end), so it isn't read.
        Platforms(GREEN_PARK, "piccadilly", HYDE_PARK_CORNER, PICCADILLY_CIRCUS, mapOf(EXIT to End.A, "victoria" to End.A)),
        // Green Park, Jubilee line, platforms 5 and 6: the escalators to the middle concourse and the passage to the
        // Victoria line are at the Bond Street end. The Piccadilly line is reached two ways, so it isn't read.
        Platforms(GREEN_PARK, "jubilee", BOND_STREET, WESTMINSTER, mapOf(EXIT to End.A, "victoria" to End.A)),
        // Highbury & Islington, Victoria line, platforms 3 to 6: both pairs connect at the south (King's Cross) end, to
        // the lower concourse, the ticket hall and the Overground's platforms. (Each shares an island with the Moorgate
        // trains going the same way, not read: they're National Rail.)
        Platforms(HIGHBURY, "victoria", KINGS_CROSS, FINSBURY_PARK, mapOf(EXIT to End.A, "mildmay" to End.A, "windrush" to End.A)),
        // Highgate, Northern line, platforms 1 and 2: escalators 1 and 3, the only way up to the ticket hall, land between
        // the two platforms midway along, a little toward the Archway end.
        Platforms(HIGHGATE, "northern", ARCHWAY, EAST_FINCHLEY, mapOf(EXIT to End.MIDDLE)),
        // Holborn, Central line, platforms 1 and 2: the passages to the escalators, up to the ticket hall and down to
        // the Piccadilly line, meet the platforms midway.
        Platforms(HOLBORN, "central", TOTTENHAM_COURT_ROAD, CHANCERY_LANE, mapOf(EXIT to End.MIDDLE, "piccadilly" to End.MIDDLE)),
        // Holborn, Piccadilly line, platforms 3 and 4: the cross passages to the lower concourse and its escalators
        // meet the platforms midway.
        Platforms(HOLBORN, "piccadilly", COVENT_GARDEN, RUSSELL_SQUARE, mapOf(EXIT to End.MIDDLE, "central" to End.MIDDLE)),
        // Kennington, Northern line: northbound, the Charing Cross branch (platform 1, toward Waterloo) and the Bank
        // branch (3, toward Elephant & Castle) share an island; southbound, both branches' platforms toward Oval (2 and 4)
        // share the other. A change of branch the same way is across the platform. (The 2015 drawing is before the
        // Battersea branch, so only Oval is read southbound.)
        Platforms(KENNINGTON, "northern", OVAL, WATERLOO, mapOf(toward("northern", ELEPHANT_AND_CASTLE) to End.ACROSS), arriving = OVAL),
        Platforms(KENNINGTON, "northern", OVAL, ELEPHANT_AND_CASTLE, mapOf(toward("northern", WATERLOO) to End.ACROSS), arriving = OVAL),
        Platforms(KENNINGTON, "northern", WATERLOO, OVAL, mapOf(toward("northern", OVAL) to End.ACROSS), arriving = WATERLOO),
        Platforms(KENNINGTON, "northern", ELEPHANT_AND_CASTLE, OVAL, mapOf(toward("northern", OVAL) to End.ACROSS), arriving = ELEPHANT_AND_CASTLE),
        // King's Cross St Pancras, deep-level lines: the cross-passage stairs joining the Victoria, Piccadilly and
        // Northern lines sit at each one's southern end (maintainer, 2026-11-05), as the drawing shows. The sub-surface
        // lines and the main line stations depend on which (King's Cross or St Pancras International), so they wait for
        // exits chosen by destination.
        // Northern line, platforms 7 and 8: escalators at the Angel end lead down to the Victoria and Piccadilly lines
        // and up to the tube ticket hall on Euston Road; the Euston end (the drawing's "west end") leads to the Northern
        // ticket hall, the way to King's Cross's north side (Granary Square, Regent's Canal, Pancras Square, Coal Drops
        // Yard; maintainer, 2026-11-05). The other lines can also be reached through the Northern ticket hall, but only
        // up and down again.
        Platforms(
            KINGS_CROSS, "northern", EUSTON, ANGEL,
            mapOf(
                "victoria" to End.B,
                "piccadilly" to End.B,
                exitTo("tube-ticket-hall") to End.B,
                exitTo("northern-ticket-hall") to End.A,
            ),
        ),
        // Victoria line, platforms 3 and 4, running east to west: the stairs to the Piccadilly and Northern lines are at
        // the Euston (west) end, beside the Piccadilly line's south end.
        Platforms(KINGS_CROSS, "victoria", EUSTON, HIGHBURY, mapOf("piccadilly" to End.A, "northern" to End.A)),
        // Piccadilly line, platforms 5 and 6: the stairs to the Victoria and Northern lines and the escalators to the tube
        // ticket hall are at the south (Russell Square) end; the escalators at the north (Caledonian Road) end go up to the
        // Northern ticket hall (maintainer, 2026-11-05).
        Platforms(
            KINGS_CROSS, "piccadilly", RUSSELL_SQUARE, CALEDONIAN_ROAD,
            mapOf(
                "victoria" to End.A,
                "northern" to End.A,
                exitTo("tube-ticket-hall") to End.A,
                exitTo("northern-ticket-hall") to End.B,
            ),
        ),
        // Leicester Square, Northern line, platforms 3 and 4: the escalators to the ticket hall and the subway to
        // the Piccadilly line leave from the south (Charing Cross) end.
        Platforms(LEICESTER_SQUARE, "northern", CHARING_CROSS, TOTTENHAM_COURT_ROAD, mapOf("piccadilly" to End.A, EXIT to End.A)),
        // Mile End: each direction's Central and District / Hammersmith & City platforms share an island, eastbound on
        // platforms 4 (Central, toward Stratford) and 3 (toward Bow Road), westbound on 1 (Central, toward Bethnal Green)
        // and 2 (toward Stepney Green). The stairs to the ticket hall land from about a quarter to halfway along from the
        // west end, so the way out isn't read.
        Platforms(MILE_END, "central", BETHNAL_GREEN, STRATFORD, mapOf(toward("district", BOW_ROAD) to End.ACROSS, toward("hammersmith-city", BOW_ROAD) to End.ACROSS), arriving = BETHNAL_GREEN),
        Platforms(MILE_END, "central", BETHNAL_GREEN, STRATFORD, mapOf(toward("district", STEPNEY_GREEN) to End.ACROSS, toward("hammersmith-city", STEPNEY_GREEN) to End.ACROSS), arriving = STRATFORD),
        Platforms(MILE_END, "district", STEPNEY_GREEN, BOW_ROAD, mapOf(toward("central", STRATFORD) to End.ACROSS), arriving = STEPNEY_GREEN),
        Platforms(MILE_END, "district", STEPNEY_GREEN, BOW_ROAD, mapOf(toward("central", BETHNAL_GREEN) to End.ACROSS), arriving = BOW_ROAD),
        Platforms(MILE_END, "hammersmith-city", STEPNEY_GREEN, BOW_ROAD, mapOf(toward("central", STRATFORD) to End.ACROSS), arriving = STEPNEY_GREEN),
        Platforms(MILE_END, "hammersmith-city", STEPNEY_GREEN, BOW_ROAD, mapOf(toward("central", BETHNAL_GREEN) to End.ACROSS), arriving = BOW_ROAD),
        // Notting Hill Gate, Central line, platforms 3 and 4: the escalators and the lower concourse to the ticket hall
        // and the District and Circle lines join at the east (Queensway) end.
        Platforms(NOTTING_HILL_GATE, "central", QUEENSWAY, HOLLAND_PARK, mapOf(EXIT to End.A, "district" to End.A, "circle" to End.A)),
        // Notting Hill Gate, District and Circle lines, platforms 1 and 2: the stairs to the lower concourse are at the
        // Bayswater end.
        Platforms(NOTTING_HILL_GATE, "district", BAYSWATER, HIGH_STREET_KENSINGTON, mapOf(EXIT to End.A, "central" to End.A)),
        Platforms(NOTTING_HILL_GATE, "circle", BAYSWATER, HIGH_STREET_KENSINGTON, mapOf(EXIT to End.A, "central" to End.A)),
        // Oxford Circus, Bakerloo and Victoria lines: each direction's two share an island, Victoria northbound
        // (platform 6, toward Warren Street) beside Bakerloo northbound (4, toward Regent's Park), Bakerloo southbound
        // (3, toward Piccadilly Circus) beside Victoria southbound (5, toward Green Park). Its exits and the Central
        // line are reached from more than one place along them, so those aren't read.
        Platforms(OXFORD_CIRCUS, "victoria", GREEN_PARK, WARREN_STREET, mapOf(toward("bakerloo", REGENTS_PARK) to End.ACROSS), arriving = GREEN_PARK),
        Platforms(OXFORD_CIRCUS, "victoria", GREEN_PARK, WARREN_STREET, mapOf(toward("bakerloo", PICCADILLY_CIRCUS) to End.ACROSS), arriving = WARREN_STREET),
        Platforms(OXFORD_CIRCUS, "bakerloo", PICCADILLY_CIRCUS, REGENTS_PARK, mapOf(toward("victoria", WARREN_STREET) to End.ACROSS), arriving = PICCADILLY_CIRCUS),
        Platforms(OXFORD_CIRCUS, "bakerloo", PICCADILLY_CIRCUS, REGENTS_PARK, mapOf(toward("victoria", GREEN_PARK) to End.ACROSS), arriving = REGENTS_PARK),
        // Piccadilly Circus, Bakerloo line, platforms 1 and 2, and Piccadilly line, platforms 3 and 4: the overbridges
        // and the mid concourse, to each other and to escalators 1 to 11, meet both pairs of platforms midway.
        Platforms(PICCADILLY_CIRCUS, "bakerloo", OXFORD_CIRCUS, CHARING_CROSS, mapOf(EXIT to End.MIDDLE, "piccadilly" to End.MIDDLE)),
        Platforms(PICCADILLY_CIRCUS, "piccadilly", GREEN_PARK, LEICESTER_SQUARE, mapOf(EXIT to End.MIDDLE, "bakerloo" to End.MIDDLE)),
        // Stockwell, Victoria and Northern lines: each direction's two share an island, northbound on platforms 1
        // (Victoria, toward Vauxhall) and 2 (Northern, toward Oval), southbound on 3 (Northern, toward Clapham North) and
        // 4 (Victoria, toward Brixton). Both islands' escalators to the ticket hall are at the north end: Vauxhall's on
        // the Victoria line, Oval's on the Northern.
        Platforms(STOCKWELL, "victoria", VAUXHALL, BRIXTON, mapOf(EXIT to End.A)),
        Platforms(STOCKWELL, "victoria", VAUXHALL, BRIXTON, mapOf(toward("northern", OVAL) to End.ACROSS), arriving = BRIXTON),
        Platforms(STOCKWELL, "victoria", VAUXHALL, BRIXTON, mapOf(toward("northern", CLAPHAM_NORTH) to End.ACROSS), arriving = VAUXHALL),
        Platforms(STOCKWELL, "northern", OVAL, CLAPHAM_NORTH, mapOf(EXIT to End.A)),
        Platforms(STOCKWELL, "northern", OVAL, CLAPHAM_NORTH, mapOf(toward("victoria", VAUXHALL) to End.ACROSS), arriving = CLAPHAM_NORTH),
        Platforms(STOCKWELL, "northern", OVAL, CLAPHAM_NORTH, mapOf(toward("victoria", BRIXTON) to End.ACROSS), arriving = OVAL),
        // Warren Street, Victoria line, platforms 3 and 4: escalators 7 and 9, the only way off, land midway along.
        Platforms(WARREN_STREET, "victoria", EUSTON, OXFORD_CIRCUS, mapOf("northern" to End.MIDDLE, EXIT to End.MIDDLE)),
        // Archway, Northern line, platforms 1 and 2: two routes off, the stairs from the escalator concourse (about 17% to
        // 36% of the way from the Highgate end) and the stair and lift route at the Highgate end. The station runs one-way
        // routes in and out and the drawing doesn't say which is which, so between the Highgate
        // end and the middle, near either.
        Platforms(ARCHWAY, "northern", HIGHGATE, TUFNELL_PARK, mapOf(EXIT to End.A_MIDDLE)),
        // Aldgate, Circle line, platforms 1 to 4: the ticket hall overbridge crosses the tracks about a third of the
        // way from the north (Liverpool Street) end; its stairs land on the islands about 28% (northward flights) and
        // 50% (southward flights) of the way from that end.
        Platforms(ALDGATE, "circle", LIVERPOOL_STREET, TOWER_HILL, mapOf(EXIT to End.A_MIDDLE)),
        // Angel, Northern line, platforms 1 (southbound, to Old Street) and 2 (northbound, to King's Cross): the
        // escalators from the main concourse reach the platforms at their west (King's Cross) end, within the first 5%
        // of their length.
        Platforms(ANGEL, "northern", KINGS_CROSS, OLD_STREET, mapOf(EXIT to End.A)),
        // Arsenal, Piccadilly line, platform 2 (westbound, to Holloway Road): the first of the two passages from the
        // ticket hall comes down by stairs about 47% of the way from the east (Finsbury Park) end.
        Platforms(ARSENAL, "piccadilly", FINSBURY_PARK, HOLLOWAY_ROAD, mapOf(EXIT to End.MIDDLE), arriving = FINSBURY_PARK),
        // Arsenal, Piccadilly line, platform 1 (eastbound, to Finsbury Park): the second passage from the ticket hall
        // comes down by stairs about 72% of the way from the east (Finsbury Park) end, toward the Holloway Road end.
        Platforms(ARSENAL, "piccadilly", FINSBURY_PARK, HOLLOWAY_ROAD, mapOf(EXIT to End.B_MIDDLE), arriving = HOLLOWAY_ROAD),
        // Balham, Northern line, platforms 1 (northbound, to Clapham South) and 2 (southbound, to Tooting Bec):
        // escalators 1 and 3 land between the platforms, with cross-passages to both about 42 to 58% of the way from
        // the Tooting Bec end. The ticket hall also leads to Balham National Rail station.
        Platforms(BALHAM, "northern", CLAPHAM_SOUTH, TOOTING_BEC, mapOf(EXIT to End.MIDDLE)),
        // Barbican, Circle line, platforms 1 (eastbound, to Moorgate) and 2 (westbound, to Farringdon): the overbridge
        // and stairs to the ticket hall are at the east (Moorgate) end, beyond 90% of the platform length.
        Platforms(BARBICAN, "circle", MOORGATE, FARRINGDON, mapOf(EXIT to End.A)),
        // Barbican, Hammersmith & City line, platforms 1 (eastbound, to Moorgate) and 2 (westbound, to Farringdon): the
        // overbridge and stairs to the ticket hall are at the east (Moorgate) end, beyond 90% of the platform length.
        Platforms(BARBICAN, "hammersmith-city", MOORGATE, FARRINGDON, mapOf(EXIT to End.A)),
        // Barbican, Metropolitan line, platforms 1 (eastbound, to Moorgate) and 2 (westbound, to Farringdon): the
        // overbridge and stairs to the ticket hall are at the east (Moorgate) end, beyond 90% of the platform length.
        Platforms(BARBICAN, "metropolitan", MOORGATE, FARRINGDON, mapOf(EXIT to End.A)),
        // Bayswater, Circle line, platforms 1 (westbound, to Notting Hill Gate) and 2 (eastbound, to Paddington): the
        // stairs to the ticket hall and overbridge rise from the east (Paddington) end of both platforms.
        Platforms(BAYSWATER, "circle", PADDINGTON, NOTTING_HILL_GATE, mapOf(EXIT to End.A)),
        // Bayswater, District line, platforms 1 (westbound, to Notting Hill Gate) and 2 (eastbound, to Paddington): the
        // stairs to the ticket hall and overbridge rise from the east (Paddington) end of both platforms.
        Platforms(BAYSWATER, "district", PADDINGTON, NOTTING_HILL_GATE, mapOf(EXIT to End.A)),
        // Bermondsey, Jubilee line, platforms 1 (westbound, to London Bridge) and 2 (eastbound, to Canada Water): the
        // escalators from the lower ticket hall come down into the station box between the platforms, about 70% of the
        // way from the west (London Bridge) end.
        Platforms(BERMONDSEY, "jubilee", LONDON_BRIDGE, CANADA_WATER, mapOf(EXIT to End.B_MIDDLE)),
        // Bethnal Green, Central line, platforms 1 (westbound, to Liverpool Street) and 2 (eastbound, to Mile End): the
        // escalators to the ticket hall land between the platforms, with cross-passages about 30 to 40% of the way from
        // the west (Liverpool Street) end.
        Platforms(BETHNAL_GREEN, "central", LIVERPOOL_STREET, MILE_END, mapOf(EXIT to End.A_MIDDLE)),
        // Blackhorse Road, Victoria line, platforms 1 (northbound, to Walthamstow Central) and 2 (southbound, to
        // Tottenham Hale): the escalators to the ticket hall land within the island, with passages about 52 to 64% of
        // the way from the Tottenham Hale end. The ticket hall also serves the Overground station.
        Platforms(BLACKHORSE_ROAD, "victoria", WALTHAMSTOW_CENTRAL, TOTTENHAM_HALE, mapOf(EXIT to End.MIDDLE)),
        // Borough, Northern line, platforms 1 and 2: the passage and stairs from the lower concourse (lifts to the
        // ticket hall) reach both platforms about midway along (measured about 0.55 of the length from the London
        // Bridge end on platform 2, and 0.39 to 0.51 on platform 1 depending on where its south end is read); platform
        // 1 northbound to London Bridge, platform 2 southbound to Elephant & Castle.
        Platforms(BOROUGH, "northern", LONDON_BRIDGE, ELEPHANT_AND_CASTLE, mapOf(EXIT to End.MIDDLE)),
        // Bow Road, District line, platforms 1 and 2: the stairs up to the ticket hall leave from the east end of both
        // platforms, the Bromley-by-Bow end; platform 2 eastbound, platform 1 westbound to Mile End.
        Platforms(BOW_ROAD, "district", BROMLEY_BY_BOW, MILE_END, mapOf(EXIT to End.A)),
        // Bow Road, Hammersmith & City line, platforms 1 and 2: the stairs up to the ticket hall leave from the east
        // end of both platforms, the Bromley-by-Bow end; platform 2 eastbound, platform 1 westbound to Mile End.
        Platforms(BOW_ROAD, "hammersmith-city", BROMLEY_BY_BOW, MILE_END, mapOf(EXIT to End.A)),
        // Caledonian Road, Piccadilly line, platforms 1 and 2: the passages to the lifts up to the ticket hall meet
        // both platforms at about 40-60% of their length from the Holloway Road end (measured 0.49 to 0.55); a second
        // passage (marked A) and a spiral stair meet them about 28% from the Holloway Road end, unmarked as the way
        // out, so the lifts are graded; platform 1 eastbound to Holloway Road, platform 2 westbound to King's Cross.
        Platforms(CALEDONIAN_ROAD, "piccadilly", HOLLOWAY_ROAD, KINGS_CROSS, mapOf(EXIT to End.MIDDLE)),
        // Cannon Street, Circle line, platforms 1 and 2: the ticket hall opens along the middle stretch of platform 1
        // (westbound to Mansion House), and the footbridge stairs land on platform 2 (eastbound to Monument) about
        // 43-46% of its length from the Monument end. Cannon Street National Rail station is above.
        Platforms(CANNON_STREET, "circle", MONUMENT, MANSION_HOUSE, mapOf(EXIT to End.MIDDLE)),
        // Cannon Street, District line, platforms 1 and 2: the ticket hall opens along the middle stretch of platform 1
        // (westbound to Mansion House), and the footbridge stairs land on platform 2 (eastbound to Monument) about
        // 43-46% of its length from the Monument end. Cannon Street National Rail station is above.
        Platforms(CANNON_STREET, "district", MONUMENT, MANSION_HOUSE, mapOf(EXIT to End.MIDDLE)),
        // Chalk Farm, Northern line, platforms 1 and 2: the stairs to the lifts and spiral stair up to the ticket hall
        // leave from the Belsize Park end of both platforms; platform 1 northbound to Belsize Park, platform 2
        // southbound to Camden Town.
        Platforms(CHALK_FARM, "northern", BELSIZE_PARK, CAMDEN_TOWN, mapOf(EXIT to End.A)),
        // Chancery Lane, Central line, platform 2 (eastbound to St. Paul's): the escalators to the middle concourse and
        // ticket hall leave from the east end of the platform, about 5-10% of its length from the St. Paul's end.
        Platforms(CHANCERY_LANE, "central", ST_PAULS, HOLBORN, mapOf(EXIT to End.A), arriving = HOLBORN),
        // Chancery Lane, Central line, platform 1 (westbound to Holborn): the passage to the lower concourse and the
        // escalators up leaves about 28% of the platform's length from the St. Paul's (east) end. Unlabeled cross
        // passages near the west third lead only to platform 2 and a shaft, not to the ticket hall.
        Platforms(CHANCERY_LANE, "central", ST_PAULS, HOLBORN, mapOf(EXIT to End.A_MIDDLE), arriving = ST_PAULS),
        // Charing Cross, Northern line, platforms 5 and 6: both platforms open at their south-east (Embankment) end
        // onto the Northern line ticket hall and escalators D, which also lead down the interchange passage to the
        // former Jubilee concourse and the Bakerloo line; platform 6 southbound to Embankment, platform 5 northbound to
        // Leicester Square. Charing Cross National Rail station is above.
        Platforms(CHARING_CROSS, "northern", EMBANKMENT, LEICESTER_SQUARE, mapOf(EXIT to End.A, "bakerloo" to End.A)),
        // Clapham Common, Northern line, platforms 1 and 2: the stairs to the lower concourse and escalators up to the
        // ticket hall leave from the Clapham South end of the island platform; platform 1 northbound to Clapham North,
        // platform 2 southbound to Clapham South.
        Platforms(CLAPHAM_COMMON, "northern", CLAPHAM_SOUTH, CLAPHAM_NORTH, mapOf(EXIT to End.A)),
        // Clapham North, Northern line, platforms 1 and 2: one island platform; the lower concourse and the stairs up
        // to the ticket hall join it at the very Clapham Common end (about 100% from the Stockwell end).
        Platforms(CLAPHAM_NORTH, "northern", CLAPHAM_COMMON, STOCKWELL, mapOf(EXIT to End.A)),
        // Clapham South, Northern line, platforms 1 and 2: two separate platform tunnels; the escalators to the ticket
        // hall meet platform 1 at its Balham end, and the cross passages reach platform 2 within about 8-17% of its
        // Balham end.
        Platforms(CLAPHAM_SOUTH, "northern", BALHAM, CLAPHAM_COMMON, mapOf(EXIT to End.A)),
        // Colliers Wood, Northern line, platforms 1 and 2: the escalators to the ticket hall land in a cross passage
        // between the two platforms about 47% of the way from the South Wimbledon end.
        Platforms(COLLIERS_WOOD, "northern", SOUTH_WIMBLEDON, TOOTING_BROADWAY, mapOf(EXIT to End.MIDDLE)),
        // Covent Garden, Piccadilly line, platform 2 (eastbound, trains from Leicester Square): the only way off is the
        // stairs up to the overbridge, about 23% of the way from the Leicester Square end, which leads over platform 1
        // to the lower circulating area and the lifts. The platforms carry no direction labels; platform 2 being
        // eastbound comes from the app's step-free platform data and matches left-hand running.
        Platforms(COVENT_GARDEN, "piccadilly", LEICESTER_SQUARE, HOLBORN, mapOf(EXIT to End.A_MIDDLE), arriving = LEICESTER_SQUARE),
        // Covent Garden, Piccadilly line, platform 1 (westbound, trains from Holborn): the lower circulating area and
        // the lifts open off the platform about 42-52% of the way from the Leicester Square end.
        Platforms(COVENT_GARDEN, "piccadilly", LEICESTER_SQUARE, HOLBORN, mapOf(EXIT to End.MIDDLE), arriving = HOLBORN),
        // Earl's Court, Piccadilly line, platforms 5 and 6: one island; the escalators up to the lower concourse, which
        // has the stairs up to District platforms 1 and 2 and 3 and 4, land about 15-20% of the way from the Barons
        // Court (west) end. A lift shaft sits about 43% of the way along, so the way out is left out.
        Platforms(EARLS_COURT, "piccadilly", BARONS_COURT, GLOUCESTER_ROAD, mapOf("district" to End.A)),
        // Edgware Road (Bakerloo), Bakerloo line, platforms 1 and 2: the lifts and lower concourse cross the platforms,
        // with stairs down to both platforms about 39-63% of the way from the Marylebone end.
        Platforms(EDGWARE_ROAD, "bakerloo", MARYLEBONE, PADDINGTON, mapOf(EXIT to End.MIDDLE)),
        // Euston Square, Circle line, platforms 1 and 2: the ticket hall (entrances A and B) bridges the platforms near
        // the Great Portland Street (west) end, with the stairs meeting them about 18-21% of the way along.
        Platforms(EUSTON_SQUARE, "circle", GREAT_PORTLAND_STREET, KINGS_CROSS, mapOf(EXIT to End.A)),
        // Euston Square, Hammersmith & City line, platforms 1 and 2: the ticket hall (entrances A and B) bridges the
        // platforms near the Great Portland Street (west) end, with the stairs meeting them about 18-21% of the way
        // along.
        Platforms(EUSTON_SQUARE, "hammersmith-city", GREAT_PORTLAND_STREET, KINGS_CROSS, mapOf(EXIT to End.A)),
        // Euston Square, Metropolitan line, platforms 1 and 2: the ticket hall (entrances A and B) bridges the
        // platforms near the Great Portland Street (west) end, with the stairs meeting them about 18-21% of the way
        // along.
        Platforms(EUSTON_SQUARE, "metropolitan", GREAT_PORTLAND_STREET, KINGS_CROSS, mapOf(EXIT to End.A)),
        // Fulham Broadway, District line, platforms 1 and 2: the stairs up to the ticket hall and shopping arcade
        // (entrance A) land about 42-47% of the way from the Parsons Green (south) end; the overbridge at the south end
        // and the stairs at the West Brompton (north) end are marked as emergency stairs.
        Platforms(FULHAM_BROADWAY, "district", PARSONS_GREEN, WEST_BROMPTON, mapOf(EXIT to End.MIDDLE)),
        // Gants Hill, Central line, platforms 1 and 2: a concourse runs between the two platforms along their eastern
        // half, and the escalators up to the ticket hall leave from its Newbury Park (east) end, about 90% of the way
        // from the Redbridge end.
        Platforms(GANTS_HILL, "central", NEWBURY_PARK, REDBRIDGE, mapOf(EXIT to End.A)),
        // Gloucester Road, Circle line, platforms 2 and 3: the stairs up to the ticket hall are at the South Kensington
        // (east) end, as is the way down (stairs, spiral stair and lifts) to Piccadilly platforms 4 and 5. The
        // footbridge at the west end only links the platforms.
        Platforms(GLOUCESTER_ROAD, "circle", SOUTH_KENSINGTON, HIGH_STREET_KENSINGTON, mapOf(EXIT to End.A, "piccadilly" to End.A)),
        // Gloucester Road, District line, platforms 1 and 3: the stairs up to the ticket hall are at the South
        // Kensington (east) end, as is the way down (stairs, spiral stair and lifts) to Piccadilly platforms 4 and 5.
        // The footbridge at the west end only links the platforms.
        Platforms(GLOUCESTER_ROAD, "district", SOUTH_KENSINGTON, EARLS_COURT, mapOf(EXIT to End.A, "piccadilly" to End.A)),
        // Gloucester Road, Piccadilly line, platforms 4 and 5: the stairs, spiral stair and lifts up to the ticket hall
        // and the District and Circle platforms are at the South Kensington (east) end.
        Platforms(GLOUCESTER_ROAD, "piccadilly", SOUTH_KENSINGTON, EARLS_COURT, mapOf(EXIT to End.A, "district" to End.A, "circle" to End.A)),
        // Goodge Street, Northern line, platforms 1 and 2: the lifts and spiral stairs reach a lower concourse beside
        // the tracks whose cross passages and stairs meet the platforms about halfway along (platform 1 northbound to
        // Warren Street about 55% from the Warren Street end; platform 2 southbound to Tottenham Court Road about
        // 50-70%).
        Platforms(GOODGE_STREET, "northern", WARREN_STREET, TOTTENHAM_COURT_ROAD, mapOf(EXIT to End.MIDDLE)),
        // Great Portland Street, Circle line, platform 2 (eastbound to Euston Square): the ramped stairs from the
        // ticket hall land about 55% along from the Baker Street end; an overbridge further west links the two
        // platforms.
        Platforms(GREAT_PORTLAND_STREET, "circle", BAKER_STREET, EUSTON_SQUARE, mapOf(EXIT to End.MIDDLE), arriving = BAKER_STREET),
        // Great Portland Street, Circle line, platform 1 (westbound to Baker Street): the ramped stairs from the ticket
        // hall land about 35% along from the Baker Street end, beside a subway to the south side.
        Platforms(GREAT_PORTLAND_STREET, "circle", BAKER_STREET, EUSTON_SQUARE, mapOf(EXIT to End.A_MIDDLE), arriving = EUSTON_SQUARE),
        // Great Portland Street, Hammersmith & City line, platform 2 (eastbound to Euston Square): the ramped stairs
        // from the ticket hall land about 55% along from the Baker Street end.
        Platforms(GREAT_PORTLAND_STREET, "hammersmith-city", BAKER_STREET, EUSTON_SQUARE, mapOf(EXIT to End.MIDDLE), arriving = BAKER_STREET),
        // Great Portland Street, Hammersmith & City line, platform 1 (westbound to Baker Street): the ramped stairs
        // from the ticket hall land about 35% along from the Baker Street end.
        Platforms(GREAT_PORTLAND_STREET, "hammersmith-city", BAKER_STREET, EUSTON_SQUARE, mapOf(EXIT to End.A_MIDDLE), arriving = EUSTON_SQUARE),
        // Great Portland Street, Metropolitan line, platform 2 (eastbound to Euston Square): the ramped stairs from the
        // ticket hall land about 55% along from the Baker Street end.
        Platforms(GREAT_PORTLAND_STREET, "metropolitan", BAKER_STREET, EUSTON_SQUARE, mapOf(EXIT to End.MIDDLE), arriving = BAKER_STREET),
        // Great Portland Street, Metropolitan line, platform 1 (westbound to Baker Street): the ramped stairs from the
        // ticket hall land about 35% along from the Baker Street end.
        Platforms(GREAT_PORTLAND_STREET, "metropolitan", BAKER_STREET, EUSTON_SQUARE, mapOf(EXIT to End.A_MIDDLE), arriving = EUSTON_SQUARE),
        // Hampstead, Northern line, platforms 1 and 2: two passages from the lift concourse drop by stairs to the
        // platforms about 37% and 60% along from the Golders Green end (platform 1 northbound to Golders Green,
        // platform 2 southbound to Belsize Park); the drawing does not show which platform each serves.
        Platforms(HAMPSTEAD, "northern", GOLDERS_GREEN, BELSIZE_PARK, mapOf(EXIT to End.MIDDLE)),
        // Hatton Cross, Piccadilly line, platforms 1 and 2 (island): the stairs from the ticket hall land on the
        // eastern part of the island, about 60-80% along from the west end (platform 1 westbound to Heathrow, labeled
        // Terminal 4; platform 2 eastbound to Hounslow West).
        Platforms(HATTON_CROSS, "piccadilly", HEATHROW_123, HOUNSLOW_WEST, mapOf(EXIT to End.B_MIDDLE)),
        // Holland Park, Central line, platforms 1 and 2: the lifts from the ticket hall come down to two passages that
        // meet the platforms just past halfway, about 52-53% from the west end (platform 2 eastbound to Notting Hill
        // Gate, platform 1 westbound to Shepherd's Bush).
        Platforms(HOLLAND_PARK, "central", NOTTING_HILL_GATE, SHEPHERDS_BUSH, mapOf(EXIT to End.MIDDLE)),
        // Hounslow West, Piccadilly line, platforms 1 and 2 (island): the stairs from the walkway overbridge land about
        // 25-42% along from the west end (platform 1 westbound to Hatton Cross, platform 2 eastbound to Hounslow
        // Central).
        Platforms(HOUNSLOW_WEST, "piccadilly", HATTON_CROSS, HOUNSLOW_CENTRAL, mapOf(EXIT to End.A_MIDDLE)),
        // Hyde Park Corner, Piccadilly line, platforms 1 and 2: the escalators to the ticket hall rise from the east
        // end of both platforms, the end toward Green Park; nothing leaves the west (Knightsbridge) end.
        Platforms(HYDE_PARK_CORNER, "piccadilly", GREEN_PARK, KNIGHTSBRIDGE, mapOf(EXIT to End.A)),
        // Kentish Town, Northern line, platform 1 (northbound to Tufnell Park): the escalators from the ticket hall
        // come down onto platform 1 about 39% along from the Camden Town end; the deeper southbound platform 2 is
        // reached by stairs from that landing, and the drawing does not show where they meet it.
        Platforms(KENTISH_TOWN, "northern", CAMDEN_TOWN, TUFNELL_PARK, mapOf(EXIT to End.A_MIDDLE), arriving = CAMDEN_TOWN),
        // Kilburn Park, Bakerloo line, platforms 1 and 2: the escalators to the ticket hall rise from a passage between
        // the two platforms about a third of the way along from the south (Maida Vale) end (about 33% on platform 2,
        // 38% on platform 1); no other way off.
        Platforms(KILBURN_PARK, "bakerloo", MAIDA_VALE, QUEENS_PARK, mapOf(EXIT to End.A_MIDDLE)),
        // Lambeth North, Bakerloo line, platforms 1 and 2: both platforms end at the west (Waterloo) end in the lower
        // concourse, with the lifts and stairs up to the ticket hall; nothing at the Elephant & Castle end.
        Platforms(LAMBETH_NORTH, "bakerloo", WATERLOO, ELEPHANT_AND_CASTLE, mapOf(EXIT to End.A)),
        // Lancaster Gate, Central line, platforms 1 and 2: stairs from both platforms, about 10 to 20% along from the
        // west (Queensway) end, climb to the lower concourse and the lifts to the ticket hall.
        Platforms(LANCASTER_GATE, "central", QUEENSWAY, MARBLE_ARCH, mapOf(EXIT to End.A)),
        // Maida Vale, Bakerloo line, platform 2 (southbound): the platforms are staggered; the escalators to the ticket
        // hall land at the north (Kilburn Park) end of platform 2, within its first tenth.
        Platforms(MAIDA_VALE, "bakerloo", KILBURN_PARK, WARWICK_AVENUE, mapOf(EXIT to End.A), arriving = KILBURN_PARK),
        // Maida Vale, Bakerloo line, platform 1 (northbound): the escalators to the ticket hall land about 45% along
        // platform 1 from its north (Kilburn Park) end.
        Platforms(MAIDA_VALE, "bakerloo", KILBURN_PARK, WARWICK_AVENUE, mapOf(EXIT to End.MIDDLE), arriving = WARWICK_AVENUE),
        // Manor House, Piccadilly line, platforms 1 and 2: the escalators to the ticket hall rise from a passage
        // between the platforms about midway along (about 44% from the south end on platform 2, about 58% on platform
        // 1); the ticket hall leads to street stairs 1 to 7.
        Platforms(MANOR_HOUSE, "piccadilly", TURNPIKE_LANE, FINSBURY_PARK, mapOf(EXIT to End.MIDDLE)),
        // Mansion House, Circle line, platforms 1 to 3: the only ways off are toward the east (Cannon Street) end:
        // eastbound platform 3 meets the passageway to the ticket hall at its east end, and a footbridge from that
        // passageway lands on the westbound island (platforms 1 and 2) about a fifth of the way from its east end;
        // nothing at the west (Blackfriars) end.
        Platforms(MANSION_HOUSE, "circle", CANNON_STREET, BLACKFRIARS, mapOf(EXIT to End.A)),
        // Mansion House, District line, platforms 1 to 3: the only ways off are toward the east (Cannon Street) end:
        // eastbound platform 3 meets the passageway to the ticket hall at its east end, and a footbridge from that
        // passageway lands on the westbound island (platforms 1 and 2) about a fifth of the way from its east end;
        // nothing at the west (Blackfriars) end.
        Platforms(MANSION_HOUSE, "district", CANNON_STREET, BLACKFRIARS, mapOf(EXIT to End.A)),
        // Marble Arch, Central line, platforms 1 and 2: escalators 1 and 3 to the ticket hall rise from a concourse
        // between the platforms, reached by stairs about 48% along from the west (Lancaster Gate) end.
        Platforms(MARBLE_ARCH, "central", LANCASTER_GATE, BOND_STREET, mapOf(EXIT to End.MIDDLE)),
        // Marylebone, Bakerloo line, platforms 1 and 2: two overbridges about 48% along the platforms from the west end
        // meet a lower concourse with spiral stairs, and a passage leads to escalators 1 and 3 up to the ticket hall
        // (and the National Rail station).
        Platforms(MARYLEBONE, "bakerloo", EDGWARE_ROAD, BAKER_STREET, mapOf(EXIT to End.MIDDLE)),
        // Oval, Northern line, platform 1 (northbound, to Kennington): the lower concourse meets platform 1 only at its
        // north (Kennington) end, about 93% along from the Stockwell end, labeled "Lower concourse & platform 1 (north
        // end)"; the escalators to the ticket hall rise from there.
        Platforms(OVAL, "northern", KENNINGTON, STOCKWELL, mapOf(EXIT to End.A), arriving = STOCKWELL),
        // Oval, Northern line, platform 2 (southbound, to Stockwell): two passages from the lower concourse reach
        // platform 2 at about 10% and 36% along from its Stockwell end, so the spot between them is graded between the
        // Stockwell end and the middle.
        Platforms(OVAL, "northern", KENNINGTON, STOCKWELL, mapOf(EXIT to End.B_MIDDLE), arriving = KENNINGTON),
        // Pimlico, Victoria line, platforms 1 (northbound, to Victoria) and 2 (southbound, to Vauxhall): both platforms
        // open, through pillared gaps about 29% along from the east (Vauxhall) end, onto the short concourse at the
        // foot of the escalators to the ticket hall; cross passages farther west lead only between the platforms.
        Platforms(PIMLICO, "victoria", VAUXHALL, VICTORIA, mapOf(EXIT to End.A_MIDDLE)),
        // Queensway, Central line, platforms 1 (westbound, to Notting Hill Gate) and 2 (eastbound, to Lancaster Gate):
        // the lower concourse to the lifts and spiral stairs meets both platforms by stairs about 62% along from the
        // Notting Hill Gate end (the stairs span about 50% to 75%).
        Platforms(QUEENSWAY, "central", NOTTING_HILL_GATE, LANCASTER_GATE, mapOf(EXIT to End.B_MIDDLE)),
        // Redbridge, Central line, platforms 1 (westbound, to Wanstead) and 2 (eastbound, to Gants Hill): one island
        // platform; the only stairs, up to the ticket hall, are at its west (Wanstead) end, about 3% along.
        Platforms(REDBRIDGE, "central", WANSTEAD, GANTS_HILL, mapOf(EXIT to End.A)),
        // Regent's Park, Bakerloo line, platforms 1 (northbound, to Baker Street) and 2 (southbound, to Oxford Circus):
        // two passages from the lower concourse (spiral stairs and lifts to the ticket hall) reach each platform at
        // about 45% and 65% along from the Baker Street end; the spot between them is graded middle.
        Platforms(REGENTS_PARK, "bakerloo", BAKER_STREET, OXFORD_CIRCUS, mapOf(EXIT to End.MIDDLE)),
        // Russell Square, Piccadilly line, platforms 1 and 2: the interchange passageways from the lifts and the spiral
        // staircase reach both platforms at about 75% to 90% along from the north end, by stairs at the south end
        // (toward Holborn, by the drawing's north-up compass; the drawing has no "to" labels).
        Platforms(RUSSELL_SQUARE, "piccadilly", HOLBORN, KINGS_CROSS, mapOf(EXIT to End.A)),
        // Shepherd's Bush, Central line, platforms 1 (westbound, to White City) and 2 (eastbound, to Holland Park): the
        // passages from the lower concourse and escalators to the ticket hall meet the island platform only at its west
        // (White City) end.
        Platforms(SHEPHERDS_BUSH, "central", WHITE_CITY, HOLLAND_PARK, mapOf(EXIT to End.A)),
        // Sloane Square, Circle line, platforms 1 (westbound, to South Kensington) and 2 (eastbound, to Victoria): both
        // escalator banks to the ticket hall land at the very west (South Kensington) end of their platforms.
        Platforms(SLOANE_SQUARE, "circle", SOUTH_KENSINGTON, VICTORIA, mapOf(EXIT to End.A)),
        // Sloane Square, District line, platforms 1 (westbound, to South Kensington) and 2 (eastbound, to Victoria):
        // both escalator banks to the ticket hall land at the very west (South Kensington) end of their platforms.
        Platforms(SLOANE_SQUARE, "district", SOUTH_KENSINGTON, VICTORIA, mapOf(EXIT to End.A)),
        // South Wimbledon, Northern line, platforms 1 (northbound, to Colliers Wood) and 2 (southbound, to Morden): the
        // escalators from the ticket hall land at a cross passage that meets platform 1 about 46% and platform 2 about
        // 42% along from the Colliers Wood end; a second cross passage at the Morden end only links the platforms.
        Platforms(SOUTH_WIMBLEDON, "northern", COLLIERS_WOOD, MORDEN, mapOf(EXIT to End.MIDDLE)),
        // Southgate, Piccadilly line, platforms 1 and 2: the escalators from the ticket hall come down to a concourse
        // between the two platforms, about 45% of the way along both from the Arnos Grove end; the only other cross
        // passage, at the Arnos Grove end, has no way out.
        Platforms(SOUTHGATE, "piccadilly", ARNOS_GROVE, OAKWOOD, mapOf(EXIT to End.MIDDLE)),
        // Southwark, Jubilee line, platforms 1 and 2: escalators 4 to 6 rise from the lower-level central concourse,
        // about 45% of the way along both platforms from the London Bridge end, to the intermediate concourse that
        // leads to both the main ticket hall and the Waterloo East ticket hall (for Network Rail at Waterloo East); the
        // stairs at each end are emergency stairs.
        Platforms(SOUTHWARK, "jubilee", LONDON_BRIDGE, WATERLOO, mapOf(EXIT to End.MIDDLE)),
        // St. John's Wood, Jubilee line, platforms 1 and 2: the escalators from the ticket hall land in a cross passage
        // between the platforms about 55% of the way along both from the Swiss Cottage end; the cross passage at the
        // Swiss Cottage end has no way out.
        Platforms(ST_JOHNS_WOOD, "jubilee", SWISS_COTTAGE, BAKER_STREET, mapOf(EXIT to End.MIDDLE)),
        // St. Paul's, Central line, platform 1 (westbound): it opens at its east end, about 10% of the way along from
        // the Bank end, into the middle concourse, which leads to the escalators to the ticket hall.
        Platforms(ST_PAULS, "central", BANK, CHANCERY_LANE, mapOf(EXIT to End.A), arriving = BANK),
        // St. Paul's, Central line, platform 2 (eastbound, below platform 1): openings into the lower concourse and
        // stairs up to the middle concourse and the escalators are about 30% of the way along from the Bank end; the
        // passage at its west end is the draught relief tunnel.
        Platforms(ST_PAULS, "central", BANK, CHANCERY_LANE, mapOf(EXIT to End.A_MIDDLE), arriving = CHANCERY_LANE),
        // Stepney Green, District line, platforms 1 and 2: the stairs up to the ticket hall and the overbridge are at
        // the east (Mile End) end of both platforms (about 80% and 85% of the way along from the west end).
        Platforms(STEPNEY_GREEN, "district", MILE_END, WHITECHAPEL, mapOf(EXIT to End.A)),
        // Stepney Green, Hammersmith & City line, platforms 1 and 2: the stairs up to the ticket hall and the
        // overbridge are at the east (Mile End) end of both platforms (about 80% and 85% of the way along from the west
        // end).
        Platforms(STEPNEY_GREEN, "hammersmith-city", MILE_END, WHITECHAPEL, mapOf(EXIT to End.A)),
        // Swiss Cottage, Jubilee line, platforms 1 and 2: the escalators to the ticket hall leave from a concourse
        // between the platforms about a third of the way along both from the Finchley Road end.
        Platforms(SWISS_COTTAGE, "jubilee", FINCHLEY_ROAD, ST_JOHNS_WOOD, mapOf(EXIT to End.A_MIDDLE)),
        // Temple, Circle line, platforms 1 and 2: the only stairs, up to the ticket hall, are at the east (Blackfriars)
        // end of both platforms (85% or more of the way along from the west end).
        Platforms(TEMPLE, "circle", BLACKFRIARS, EMBANKMENT, mapOf(EXIT to End.A)),
        // Temple, District line, platforms 1 and 2: the only stairs, up to the ticket hall, are at the east
        // (Blackfriars) end of both platforms (85% or more of the way along from the west end).
        Platforms(TEMPLE, "district", BLACKFRIARS, EMBANKMENT, mapOf(EXIT to End.A)),
        // Tooting Bec, Northern line, platforms 1 and 2: the escalators to the ticket hall leave from a concourse
        // between the platforms about halfway along them.
        Platforms(TOOTING_BEC, "northern", BALHAM, TOOTING_BROADWAY, mapOf(EXIT to End.MIDDLE)),
        // Tooting Broadway, Northern line, platform 1 (northbound): the two cross passages to the escalator concourse
        // meet it about 15% and 35% of the way along from the Colliers Wood end, with the escalator foot about a third
        // of the way along; the cross passage at the Tooting Bec end has no way out.
        Platforms(TOOTING_BROADWAY, "northern", COLLIERS_WOOD, TOOTING_BEC, mapOf(EXIT to End.A_MIDDLE), arriving = COLLIERS_WOOD),
        // Tooting Broadway, Northern line, platform 2 (southbound): the two cross passages to the escalator concourse
        // meet it about 30% and 50% of the way along from the Colliers Wood end, with the escalator foot about 45% of
        // the way along; the cross passage at the Tooting Bec end has no way out.
        Platforms(TOOTING_BROADWAY, "northern", COLLIERS_WOOD, TOOTING_BEC, mapOf(EXIT to End.MIDDLE), arriving = TOOTING_BEC),
        // Tufnell Park, Northern line, platforms 1 and 2: stairs from 45% to 55% of the way along both platforms
        // (northbound platform 1 is above southbound platform 2) lead down to the lower concourse and the lifts to the
        // ticket hall.
        Platforms(TUFNELL_PARK, "northern", ARCHWAY, KENTISH_TOWN, mapOf(EXIT to End.MIDDLE)),
        // Turnpike Lane, Piccadilly line, platforms 1 and 2: the single bank of escalators to the ticket hall lands in
        // cross-passages between the platforms about 63-67% of the way from the Wood Green end, toward Manor House; no
        // other way off the platforms is drawn (subway B passes over the platform tunnels without connecting).
        Platforms(TURNPIKE_LANE, "piccadilly", WOOD_GREEN, MANOR_HOUSE, mapOf(EXIT to End.B_MIDDLE)),
        // Walthamstow Central, Victoria line, platforms 1 and 2: terminus island platform; the only way off is the
        // escalator at the east (sidings) end, away from Blackhorse Road, up to the concourse, the bus station passage
        // C and the National Rail entrances.
        Platforms(WALTHAMSTOW_CENTRAL, "victoria", BLACKHORSE_ROAD, "", mapOf(EXIT to End.B)),
        // Wanstead, Central line, platforms 1 and 2: the only escalators to the ticket hall land in a short concourse
        // between the platforms, reached by cross-passages about 43-52% of the way from the Leytonstone end; platform 1
        // is westbound to Leytonstone, platform 2 eastbound to Redbridge.
        Platforms(WANSTEAD, "central", LEYTONSTONE, REDBRIDGE, mapOf(EXIT to End.MIDDLE)),
        // Warwick Avenue, Bakerloo line, platforms 1 and 2: the only escalators to the ticket hall (entrances A and B)
        // land between the platforms, with cross-passages beside them, about 44-50% of the way from the Maida Vale end;
        // platform 1 is northbound to Maida Vale, platform 2 southbound to Paddington.
        Platforms(WARWICK_AVENUE, "bakerloo", MAIDA_VALE, PADDINGTON, mapOf(EXIT to End.MIDDLE)),
        // Wood Green, Piccadilly line, platforms 1 and 2: the only escalators to the ticket hall land between the
        // platforms, reached by cross-passages about 31% (platform 1) and 40% (platform 2) of the way from the Bounds
        // Green end; the cross-passage near the Turnpike Lane end only links the two platforms.
        Platforms(WOOD_GREEN, "piccadilly", BOUNDS_GREEN, TURNPIKE_LANE, mapOf(EXIT to End.A_MIDDLE)),
    )
}
