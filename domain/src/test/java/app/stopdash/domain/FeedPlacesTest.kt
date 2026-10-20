package app.stopdash.domain

import java.io.BufferedReader
import java.io.StringReader
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic records around the obviously-fake (51.5, -0.12); never a real stop. */
class FeedPlacesTest {
    private val index = StationIndex(
        listOf(
            IndexedStation("910GHILL", "Hill", listOf("national-rail"), "HUBEXA", 51.500, -0.120,
                platforms = listOf("9100HILL1"), icsCode = "1009001", station = "1009001:rail", modelHub = "HUBEXA"),
            IndexedStation("910GHILLLL", "Hill LL", listOf("national-rail"), "HUBEXA", 51.502, -0.120,
                icsCode = "1009001", station = "1009001:rail", modelHub = "HUBEXA"),
            IndexedStation("940GZZLUHIL", "Hill", listOf("tube"), "HUBEXA", 51.501, -0.121,
                icsCode = "1009002", station = "1009002:tube", modelHub = "HUBEXA"),
            IndexedStation("HUBEXA", "Hill", listOf("national-rail", "tube")),
        ),
        revision = "abc",
    )

    private val hubs = Hubs.of(
        index,
        BundledStops.read(
            BufferedReader(
                StringReader(
                    listOf(
                        "# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=abc",
                        "L\t1\tbus\t1",
                        // An area split into two stops.
                        "S\t490000005A\tB\tHigh Road\t51.520000\t-0.120000\t490G000005\t\t\t\t\t1\t\t490000005A\t\t490000005A",
                        "S\t490000005B\tB\tMill Lane\t51.520300\t-0.120000\t490G000005\t\t\t\t\t1\t\t490000005B\t\t490000005A",
                    ).joinToString("\n"),
                ),
            ),
        ),
    )!!

    private val at = Instant.parse("2026-01-01T09:00:00Z")

    private fun leg(
        fromId: String,
        toId: String,
        path: List<String> = emptyList(),
        fromArea: String = "",
        toArea: String = "",
        toAt: Coordinates? = null,
    ) = TripLeg(
        mode = "bus", lineId = "1", lineName = "1", fromId = fromId, fromName = "", toId = toId, toName = "",
        departure = at, arrival = at.plusSeconds(600), path = path, fromArea = fromArea, toArea = toArea, toAt = toAt,
    )

    @Test
    fun `a leg's ends and path are placed at their station and hub`() {
        val placed = hubs.place(
            leg("940GZZLUHIL", "9100HILL1", path = listOf("910GHILLLL", "9100HILL1")).copy(mode = "tube"),
        )
        assertEquals("1009002:tube", placed.placeOf("940GZZLUHIL").station)
        // A platform and another record of its station are one station, in the tube's hub.
        assertEquals("1009001:rail", placed.placeOf("9100HILL1").station)
        assertEquals("1009001:rail", placed.placeOf("910GHILLLL").station)
        assertEquals("HUBEXA", placed.placeOf("9100HILL1").hub)
        assertEquals("HUBEXA", placed.placeOf("940GZZLUHIL").hub)
        // The id TfL gave is kept for what TfL needs.
        assertEquals("9100HILL1", placed.placeOf("9100HILL1").tflId)
    }

    @Test
    fun `a split stop pair is placed through the leg's own pole, else the point the Planner gave`() {
        val byPole = hubs.place(leg("910GHILL", "490000005B", toArea = "490G000005"))
        assertEquals("490000005B", byPole.placeOf("490G000005").station)
        assertEquals("490000005B", byPole.placeOf("490000005B").station)

        // No pole named: the area's pole nearest where the Planner put the stop.
        val byPoint = hubs.place(leg("910GHILL", "", toArea = "490G000005", toAt = Coordinates(51.52, -0.12)))
        assertEquals("490000005A", byPoint.placeOf("490G000005").station)
    }

    @Test
    fun `an id the leg wasn't placed with is its own station and hub`() {
        val placed = hubs.place(leg("940GZZLUHIL", "910GHILL"))
        // As a copy moving an end to another id leaves it: never the old end's place.
        val moved = placed.copy(toId = "910GELSEWHERE")
        assertEquals(Resolved.exact("910GELSEWHERE"), moved.placeOf(moved.toId))
        // A leg never placed (no model): every id is itself.
        assertEquals(Resolved.exact("910GHILL"), leg("940GZZLUHIL", "910GHILL").placeOf("910GHILL"))
        assertNull(leg("940GZZLUHIL", "910GHILL").placed["910GHILL"])
    }

    @Test
    fun `a rail board's trains are placed by their terminus, those it lists with no time too`() {
        val train = Departure("thameslink", "Thameslink", "", "Hill", null, at, "national-rail", destinationId = "910GHILL")
        val board = RailBoard(listOf(train), untimed = listOf(UntimedTrain(train, canceled = true)))
        val placed = hubs.place(board)
        assertEquals("1009001:rail", placed.departures.single().destinationPlace?.station)
        assertEquals("1009001:rail", placed.untimed.single().train.destinationPlace?.station)
        assertTrue(placed.untimed.single().canceled)
        // One with no terminus id stays as it is.
        assertNull(hubs.place(listOf(train.copy(destinationId = ""))).single().destinationPlace)
    }

    @Test
    fun `a kept trip has every leg it holds placed`() {
        val ride = leg("940GZZLUHIL", "910GHILLLL")
        val trip = ActiveTrip(
            route = TripRoute(listOf(ride)),
            destinationName = "Hill",
            startedAt = at,
            vehicleLeg = ride,
            offLeg = ride,
            plannedLegs = listOf(ride),
            leftRide = ActiveTrip(TripRoute(listOf(ride)), "Hill", at),
        )
        val placed = hubs.place(trip)
        val legs = placed.route.legs + listOfNotNull(placed.vehicleLeg, placed.offLeg) +
            placed.plannedLegs.orEmpty() + placed.leftRide!!.route.legs
        assertEquals(5, legs.size)
        assertTrue(legs.all { it.placeOf("910GHILLLL").station == "1009001:rail" })
        // Nothing else about the trip moves.
        assertSame(trip.destinationName, placed.destinationName)
        assertEquals(trip.copy(route = placed.route, vehicleLeg = placed.vehicleLeg, offLeg = placed.offLeg,
            plannedLegs = placed.plannedLegs, leftRide = placed.leftRide), placed)
    }
}
