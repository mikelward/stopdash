package app.stopdash.domain

import app.stopdash.domain.PlatformEnds.Spot
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where on the train to ride, from the table read off TfL's station layouts: major stations only. */
class PlatformEndsTest {
    private val t0 = Instant.parse("2026-11-05T09:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    // TfL ids, as the Planner gives a tube ride's calls.
    private val oxfordCircus = "940GZZLUOXC"
    private val warrenStreet = "940GZZLUWRR"
    private val euston = "940GZZLUEUS"
    private val kingsCross = "940GZZLUKSX"
    private val goodgeStreet = "940GZZLUGDG"
    private val morningtonCrescent = "940GZZLUMTC"
    private val camdenTown = "940GZZLUCTN"
    private val tottenhamCourtRoad = "940GZZLUTCR"
    private val leicesterSquare = "940GZZLULSQ"

    private fun ride(line: String, from: String, vararg calls: String) =
        TripLeg("tube", line, line, from, from, calls.last(), calls.last(), at(0), at(10), path = calls.toList())

    private fun change(line: String, at: String, to: String) = TripLeg("tube", line, line, at, at, to, to, at(12), at(20), path = listOf(to))

    private fun walk(from: String) = TripLeg(TripLeg.WALKING, "", "", from, from, "home", "home", at(10), at(15))

    private fun spot(vararg legs: TripLeg, index: Int = 0) =
        PlatformEnds.standAt(ActiveTrip(TripRoute(legs.toList()), "There", startedAt = t0), index)

    @Test
    fun `the end a way leaves from is the front one way and the back the other`() {
        // Euston's Charing Cross branch: the way to the Victoria line is at the Mornington Crescent end.
        val northbound = ride("northern", goodgeStreet, warrenStreet, euston)
        assertEquals(Spot.FRONT, spot(northbound, change("victoria", euston, kingsCross)))
        val southbound = ride("northern", camdenTown, morningtonCrescent, euston)
        assertEquals(Spot.BACK, spot(southbound, change("victoria", euston, kingsCross)))
        // Getting off at the end of the trip, or walking on, is the way out: the same end there.
        assertEquals(Spot.FRONT, spot(northbound))
        assertEquals(Spot.FRONT, spot(northbound, walk(euston)))
    }

    @Test
    fun `a station two of a line's routes cross is told apart by where the train comes from`() {
        // Euston's Bank branch has its escalators at the Camden Town end; a train from King's Cross runs to it.
        assertEquals(Spot.FRONT, spot(ride("northern", "940GZZLUAGL", kingsCross, euston)))
        assertEquals(Spot.BACK, spot(ride("northern", "940GZZLUMTC", camdenTown, euston)))
    }

    @Test
    fun `a change onto a line with branches goes by the next train's first stop`() {
        // Euston's Victoria line: the Charing Cross branch is through the passages at the Warren Street end.
        val northbound = ride("victoria", "940GZZLUGPK", warrenStreet, euston)
        assertEquals(Spot.BACK, spot(northbound, change("northern", euston, morningtonCrescent)))
        val southbound = ride("victoria", "940GZZLUHAI", kingsCross, euston)
        assertEquals(Spot.FRONT, spot(southbound, change("northern", euston, warrenStreet)))
        // The Bank branch the same way is across the platform: any carriage (maintainer, 2026-11-05). The other way is
        // on another island, which the drawing doesn't place.
        assertEquals(Spot.ANY, spot(northbound, change("northern", euston, camdenTown)))
        assertNull(spot(northbound, change("northern", euston, kingsCross)))
        assertEquals(Spot.ANY, spot(southbound, change("northern", euston, kingsCross)))
        assertNull(spot(southbound, change("northern", euston, camdenTown)))
        // From the Bank branch the same way: across to the Victoria line going the same way.
        assertEquals(Spot.ANY, spot(ride("northern", "940GZZLUAGL", kingsCross, euston), change("victoria", euston, kingsCross)))
        // From the Bank branch to the Charing Cross branch, through the passages at the Camden Town end.
        assertEquals(Spot.FRONT, spot(ride("northern", "940GZZLUAGL", kingsCross, euston), change("northern", euston, warrenStreet)))
    }

    @Test
    fun `the second batch reads as its drawings show`() {
        // Oxford Circus: the Victoria and Bakerloo lines going the same way share an island.
        val victoriaNorth = ride("victoria", "940GZZLUVIC", "940GZZLUGPK", oxfordCircus)
        assertEquals(Spot.ANY, spot(victoriaNorth, change("bakerloo", oxfordCircus, "940GZZLURGP")))
        // The other way is the other island, which the drawing reaches from more than one place: nothing.
        assertNull(spot(victoriaNorth, change("bakerloo", oxfordCircus, "940GZZLUPCC")))
        assertEquals(Spot.ANY, spot(ride("bakerloo", "940GZZLUMYB", "940GZZLURGP", oxfordCircus), change("victoria", oxfordCircus, "940GZZLUGPK")))
        // Holborn: the way to the Piccadilly line is midway along the Central line's platforms.
        assertEquals(Spot.MIDDLE, spot(ride("central", "940GZZLUOXC", tottenhamCourtRoad, "940GZZLUHBN"), change("piccadilly", "940GZZLUHBN", "940GZZLURSQ")))
        // Embankment's Bakerloo line: midway to the District line.
        assertEquals(Spot.MIDDLE, spot(ride("bakerloo", "940GZZLULBN", "940GZZLUWLO", "940GZZLUEMB"), change("district", "940GZZLUEMB", "940GZZLUWSM")))
        // Embankment's Northern line: midway, either way.
        assertEquals(Spot.MIDDLE, spot(ride("northern", "940GZZLUKNG", "940GZZLUWLO", "940GZZLUEMB"), change("bakerloo", "940GZZLUEMB", "940GZZLUCHX")))
        assertEquals(Spot.MIDDLE, spot(ride("northern", leicesterSquare, "940GZZLUCHX", "940GZZLUEMB"), change("bakerloo", "940GZZLUEMB", "940GZZLUWLO")))
    }

    @Test
    fun `green park's ways are at the ends its drawing shows`() {
        val greenPark = "940GZZLUGPK"
        // Victoria line southbound, in from Oxford Circus: the Jubilee line is at the Victoria end, the front.
        assertEquals(Spot.FRONT, spot(ride("victoria", warrenStreet, oxfordCircus, greenPark), change("jubilee", greenPark, "940GZZLUWSM")))
        // Northbound, in from Victoria: the Piccadilly line is at the Oxford Circus end, the front.
        assertEquals(Spot.FRONT, spot(ride("victoria", "940GZZLUPCO", "940GZZLUVIC", greenPark), change("piccadilly", greenPark, "940GZZLUHPC")))
        // Piccadilly line eastbound, in from Hyde Park Corner: the exit is at that end, the back.
        assertEquals(Spot.BACK, spot(ride("piccadilly", "940GZZLUKNB", "940GZZLUHPC", greenPark)))
        // Jubilee line westbound, in from Westminster: the Victoria line is at the Bond Street end, the front.
        assertEquals(Spot.FRONT, spot(ride("jubilee", "940GZZLUWLO", "940GZZLUWSM", greenPark), change("victoria", greenPark, oxfordCircus)))
        // Ways it reaches more than one way say nothing.
        assertNull(spot(ride("jubilee", "940GZZLUWLO", "940GZZLUWSM", greenPark), change("piccadilly", greenPark, "940GZZLUHPC")))
        assertNull(spot(ride("victoria", warrenStreet, oxfordCircus, greenPark)))
    }

    @Test
    fun `finsbury park and stockwell change across the platform the same way`() {
        val finsburyPark = "940GZZLUFPK"
        // Victoria northbound, in from Highbury & Islington: the Piccadilly line northbound is across the platform.
        val north = ride("victoria", kingsCross, "940GZZLUHAI", finsburyPark)
        assertEquals(Spot.ANY, spot(north, change("piccadilly", finsburyPark, "940GZZLUMRH")))
        // Southbound Piccadilly is the other island, reached from more than one place: nothing.
        assertNull(spot(north, change("piccadilly", finsburyPark, "940GZZLUASL")))
        val stockwell = "940GZZLUSKW"
        // Northern line southbound, in from Oval: the Victoria line toward Brixton is across; the exit is at the Oval
        // end, the back.
        val south = ride("northern", "940GZZLUKNG", "940GZZLUOVL", stockwell)
        assertEquals(Spot.ANY, spot(south, change("victoria", stockwell, "940GZZLUBXN")))
        assertEquals(Spot.BACK, spot(south))
        // Victoria line northbound, in from Brixton: the exit is at the Vauxhall end, the front.
        assertEquals(Spot.FRONT, spot(ride("victoria", "940GZZLUBXN", stockwell)))
    }

    @Test
    fun `king's cross on the northern line`() {
        // Southbound, in from Euston: the Victoria line is at the Angel end, the front.
        val south = ride("northern", camdenTown, euston, kingsCross)
        assertEquals(Spot.FRONT, spot(south, change("victoria", kingsCross, "940GZZLUHAI")))
        // Its exits are told apart by where they lead: with no walk on to choose by, nothing.
        assertNull(spot(south))
        // Walking on north (Granary Square): the Northern ticket hall, at the Euston end, the back (maintainer,
        // 2026-11-05). South (Euston Road): the tube ticket hall, at the Angel end, the front. Major places only.
        val at = Coordinates(51.5308, -0.1238)
        val arrived = south.copy(toAt = at)
        fun walkTo(lat: Double, lon: Double) = TripLeg(TripLeg.WALKING, "", "", kingsCross, kingsCross, "there", "there", at(10), at(20), fromAt = at, toAt = Coordinates(lat, lon))
        assertEquals(Spot.BACK, spot(arrived, walkTo(51.5352, -0.1255)))
        assertEquals(Spot.FRONT, spot(arrived, walkTo(51.5268, -0.1240)))
        // East or west, between the two, or too near to tell: nothing.
        assertNull(spot(arrived, walkTo(51.5308, -0.1150)))
        assertNull(spot(arrived, walkTo(51.5310, -0.1238)))
        // As the Planner gives a walk, with no end position (Codex, #1261): its end is the trip's destination place, or
        // the next ride's stop.
        val bare = walkTo(51.5352, -0.1255).copy(toAt = null)
        val toPlace = ActiveTrip(
            TripRoute(listOf(arrived, bare)), "Granary Square", startedAt = t0,
            destinations = listOf(TripDestination.Place(Coordinates(51.5352, -0.1255), "Granary Square")),
        )
        assertEquals(Spot.BACK, PlatformEnds.standAt(toPlace, 0))
        val onToBus = TripLeg("bus", "73", "73", "490000000X", "Euston Road", "490000000Y", "Oxford Street", at(25), at(40), fromAt = Coordinates(51.5268, -0.1240))
        assertEquals(Spot.FRONT, spot(arrived, bare, onToBus))
        // No position anywhere: nothing.
        assertNull(spot(arrived, bare))
        // The Piccadilly line southbound, in from Caledonian Road, walking on north: the Northern ticket hall is at that
        // end, the back.
        val piccadilly = ride("piccadilly", "940GZZLUHWY", "940GZZLUCRD", kingsCross).copy(toAt = at)
        assertEquals(Spot.BACK, spot(piccadilly, walkTo(51.5352, -0.1255)))
        assertEquals(Spot.FRONT, spot(piccadilly, walkTo(51.5268, -0.1240)))
        // The deep-level changes are at the southern ends (maintainer, 2026-11-05): the Piccadilly line northbound, in
        // from Russell Square, has them at the back; the Victoria line westbound, in from Highbury, at the front.
        assertEquals(Spot.BACK, spot(ride("piccadilly", "940GZZLUHBN", "940GZZLURSQ", kingsCross), change("victoria", kingsCross, euston)))
        assertEquals(Spot.FRONT, spot(ride("victoria", "940GZZLUFPK", "940GZZLUHAI", kingsCross), change("northern", kingsCross, "940GZZLUAGL")))
    }

    @Test
    fun `an exit is chosen by the bearing it leads out toward`() {
        val exits = mapOf("north" to 0.0, "south" to 180.0)
        val from = Coordinates(51.5308, -0.1238)
        assertEquals("north", PlatformEnds.exitToward(exits, from, Coordinates(51.5340, -0.1210)))
        assertEquals("south", PlatformEnds.exitToward(exits, from, Coordinates(51.5270, -0.1270)))
        // Across north, wrapping past 360 degrees.
        assertEquals("north", PlatformEnds.exitToward(exits, from, Coordinates(51.5340, -0.1270)))
        assertNull(PlatformEnds.exitToward(exits, from, Coordinates(51.5308, -0.1100)))
        // Two exits within reach of the heading: neither.
        assertNull(PlatformEnds.exitToward(mapOf("a" to 0.0, "b" to 40.0), from, Coordinates(51.5340, -0.1230)))
    }

    @Test
    fun `mile end and kennington change across the platform the same way`() {
        val mileEnd = "940GZZLUMED"
        val eastbound = ride("central", "940GZZLULST", "940GZZLUBLG", mileEnd)
        assertEquals(Spot.ANY, spot(eastbound, change("district", mileEnd, "940GZZLUBWR")))
        assertNull(spot(eastbound, change("district", mileEnd, "940GZZLUSGN")))
        assertEquals(Spot.ANY, spot(ride("hammersmith-city", "940GZZLUBWR", mileEnd), change("central", mileEnd, "940GZZLUBLG")))
        val kennington = "940GZZLUKNG"
        // Northbound from Oval on the Charing Cross branch, changing to the Bank branch: across the platform.
        assertEquals(Spot.ANY, spot(ride("northern", "940GZZLUSKW", "940GZZLUOVL", kennington), change("northern", kennington, "940GZZLUEAC")))
        // Southbound from Waterloo, changing for Oval: across the platform.
        assertEquals(Spot.ANY, spot(ride("northern", "940GZZLUEMB", "940GZZLUWLO", kennington), change("northern", kennington, "940GZZLUOVL")))
    }

    @Test
    fun `elephant and castle, highbury and notting hill gate`() {
        val elephant = "940GZZLUEAC"
        // Northern line southbound, in from Borough: the Bakerloo line is at that end, the back.
        assertEquals(Spot.BACK, spot(ride("northern", "940GZZLULBR", "940GZZLUBOR", elephant), change("bakerloo", elephant, "940GZZLULBN")))
        // Bakerloo line, in from Lambeth North to where it ends: the Northern line is at the front.
        assertEquals(Spot.FRONT, spot(ride("bakerloo", "940GZZLUWLO", "940GZZLULBN", elephant), change("northern", elephant, "940GZZLUKNG")))
        // Victoria line southbound into Highbury & Islington, from Finsbury Park: the way out is at the King's Cross end.
        assertEquals(Spot.FRONT, spot(ride("victoria", "940GZZLUSVS", "940GZZLUFPK", "940GZZLUHAI")))
        val nottingHill = "940GZZLUNHG"
        // Central line eastbound, in from Holland Park: the District and Circle lines are at the Queensway end, the front.
        assertEquals(Spot.FRONT, spot(ride("central", "940GZZLUSBC", "940GZZLUHPK", nottingHill), change("circle", nottingHill, "940GZZLUBWT")))
        // Circle line, in from Bayswater: the way out is at that end, the back.
        assertEquals(Spot.BACK, spot(ride("circle", "940GZZLUPAC", "940GZZLUBWT", nottingHill)))
    }

    @Test
    fun `piccadilly circus is midway on both lines`() {
        assertEquals(Spot.MIDDLE, spot(ride("bakerloo", "940GZZLUEMB", "940GZZLUCHX", "940GZZLUPCC"), change("piccadilly", "940GZZLUPCC", "940GZZLUGPK")))
        assertEquals(Spot.MIDDLE, spot(ride("piccadilly", "940GZZLUHPC", "940GZZLUGPK", "940GZZLUPCC")))
    }

    @Test
    fun `a way midway along is the middle either way`() {
        // Warren Street's Victoria line: the escalators land midway along.
        assertEquals(Spot.MIDDLE, spot(ride("victoria", kingsCross, euston, warrenStreet), change("northern", warrenStreet, goodgeStreet)))
        assertEquals(Spot.MIDDLE, spot(ride("victoria", "940GZZLUGPK", oxfordCircus, warrenStreet)))
    }

    @Test
    fun `a change on foot within the station is to the ride after it`() {
        // The Planner can give an interchange as a walk between two rides (Codex on #1257): its way is the next
        // ride's line, never the exit. Victoria to the Bank branch the same way at Euston is across the platform.
        val victoria = ride("victoria", "940GZZLUGPK", warrenStreet, euston)
        val inside = TripLeg(TripLeg.WALKING, "", "", euston, euston, euston, euston, at(10), at(12))
        val trip = ActiveTrip(TripRoute(listOf(victoria, inside, change("northern", euston, camdenTown))), "There", startedAt = t0, onFootChanges = setOf(1))
        assertEquals(Spot.ANY, PlatformEnds.standAt(trip, 0))
        // To a Bank branch train the other way, on another island the drawing doesn't place: nothing.
        val otherWay = ActiveTrip(TripRoute(listOf(victoria, inside, change("northern", euston, kingsCross))), "There", startedAt = t0, onFootChanges = setOf(1))
        assertNull(PlatformEnds.standAt(otherWay, 0))
        // Euston's Charing Cross branch to the Victoria line, as a walk: its own entry, the front northbound.
        val northern = ride("northern", goodgeStreet, warrenStreet, euston)
        val toVictoria = ActiveTrip(TripRoute(listOf(northern, inside, change("victoria", euston, kingsCross))), "There", startedAt = t0, onFootChanges = setOf(1))
        assertEquals(Spot.FRONT, PlatformEnds.standAt(toVictoria, 0))
    }

    @Test
    fun `nothing is said where it isn't known`() {
        // A station not read, a ride with no calls given, or a walk.
        assertNull(spot(ride("northern", euston, warrenStreet, goodgeStreet)))
        assertNull(spot(ride("northern", warrenStreet, euston).copy(path = emptyList())))
        assertNull(spot(walk(euston)))
        // A train coming from somewhere neither end leads to can't be placed.
        assertNull(spot(ride("northern", oxfordCircus, tottenhamCourtRoad, euston)))
        // Leicester Square's Northern line from the north: the Piccadilly line is at the Charing Cross end, the front.
        assertEquals(Spot.FRONT, spot(ride("northern", "940GZZLUGDG", tottenhamCourtRoad, leicesterSquare), change("piccadilly", leicesterSquare, "940GZZLUCGN")))
    }
}
