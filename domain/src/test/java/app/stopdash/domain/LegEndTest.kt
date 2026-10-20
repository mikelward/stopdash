package app.stopdash.domain

import java.io.BufferedReader
import java.io.StringReader
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic records: a hub HUBA holding stations STA1 and STA2, and a hub HUBB, as [Resolved] values;
 * and a station model ([hubs]) around the obviously-fake (51.5, -0.12), as `HubsTest` builds it, for the
 * ends a route's rides get off at, resolved from their raw ids.
 */
class LegEndTest {
    private val a1 = Resolved(tflId = "910GA1", station = "STA1", hub = "HUBA")
    private val a1Other = Resolved(tflId = "940GA1", station = "STA1", hub = "HUBA")
    private val a2 = Resolved(tflId = "910GA2", station = "STA2", hub = "HUBA")
    private val b = Resolved(tflId = "910GB", station = "STB", hub = "HUBB")
    private val at = Instant.parse("2026-01-01T09:00:00Z")

    // Hill: a hub HUBEXA of a rail station (records 910GHILL and 910GHILLLL) and a tube station
    // (940GZZLUHIL), with a bus stop of two poles (area 490G000001); Vale, a station and hub of its own;
    // and an area 490G000005 the model split into two stops.
    private val index = StationIndex(
        listOf(
            IndexedStation("910GHILL", "Hill", listOf("national-rail"), "HUBEXA", 51.500, -0.120,
                icsCode = "1009001", station = "1009001:rail", modelHub = "HUBEXA"),
            IndexedStation("910GHILLLL", "Hill LL", listOf("national-rail"), "HUBEXA", 51.502, -0.120,
                icsCode = "1009001", station = "1009001:rail", modelHub = "HUBEXA"),
            IndexedStation("940GZZLUHIL", "Hill", listOf("tube"), "HUBEXA", 51.501, -0.121,
                icsCode = "1009002", station = "1009002:tube", modelHub = "HUBEXA"),
            IndexedStation("910GVALE", "Vale", listOf("national-rail"), latitude = 51.53, longitude = -0.12,
                station = "910GVALE", modelHub = "910GVALE"),
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
                        "S\t490000001A\tB\tHill Station\t51.500500\t-0.120000\t490G000001\t\tA\t\t\t1\t1009001\t490G000001\t1009001:rail\tHUBEXA",
                        "S\t490000001B\tB\tHill Station\t51.500700\t-0.120000\t490G000001\t\tB\t\t\t1\t1009001\t490G000001\t1009001:rail\tHUBEXA",
                        "S\t490000005A\tB\tHigh Road\t51.520000\t-0.120000\t490G000005\t\t\t\t\t1\t\t490000005A\t\t490000005A",
                        "S\t490000005B\tB\tMill Lane\t51.520300\t-0.120000\t490G000005\t\t\t\t\t1\t\t490000005B\t\t490000005A",
                    ).joinToString("\n"),
                ),
            ),
        ),
    )!!

    // A ride getting off at [toId], as the Planner names it: raw ids only.
    private fun ride(toId: String, mode: String = "tube", toArea: String = "") = TripLeg(
        mode = mode, lineId = "l", lineName = "L", fromId = "910GFROM", fromName = "", toId = toId, toName = "",
        departure = at, arrival = at.plusSeconds(600), toArea = toArea,
    )

    // [leg] with its end moved to the pole [pole] its bus uses, as [onPoles] moves it.
    private fun moved(leg: TripLeg, pole: String) = leg.copy(toId = pole, plannedToId = leg.toId)

    private fun walk() = TripLeg(
        mode = TripLeg.WALKING, lineId = "", lineName = "", fromId = "x", fromName = "", toId = "y", toName = "",
        departure = at, arrival = at.plusSeconds(60),
    )

    @Test
    fun `a stop end takes another record of the station but not the hub's other station`() {
        val end = LegEnd.at(a1, StepFree.ANY)
        assertTrue(end.endsAt(a1Other))
        assertFalse(end.endsAt(a2))
    }

    @Test
    fun `a hub end takes every station in the hub`() {
        val end = LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBA"))
        assertTrue(end.endsAt(a1))
        assertTrue(end.endsAt(a2))
        assertFalse(end.endsAt(b))
    }

    @Test
    fun `a pole end takes only its own poles`() {
        val area = Resolved(tflId = "490GAREA", station = "S1", hub = "H", stops = setOf("S1", "S2"), poles = setOf("490A"))
        val end = LegEnd(StepFree.ANY, LegEnd.Level.POLE, area.poles)
        assertTrue(end.endsAt(Resolved(tflId = "490A", station = "S1", hub = "H")))
        assertFalse(end.endsAt(Resolved(tflId = "490B", station = "S2", hub = "H")))
    }

    @Test
    fun `a call the model names no stop for ends at its own poles`() {
        val call = Resolved(tflId = "910GA1", station = null, hub = null)
        val end = LegEnd.at(call, StepFree.STATION)
        assertEquals(LegEnd.Level.POLE, end.level)
        assertTrue(end.endsAt(a1))
        assertFalse(end.endsAt(a2))
    }

    @Test
    fun `a split area the model couldn't place matches no child stop or hub`() {
        // Its stops and hubs list every child, for avoids and dismissals only.
        val area = Resolved(
            tflId = "490GAREA", station = null, hub = null,
            stops = setOf("STA1", "STB"), hubs = setOf("HUBA", "HUBB"), poles = setOf("490A", "490B"),
        )
        assertFalse(LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBA")).endsAt(area))
        assertFalse(LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("STA1")).endsAt(area))
        // As where a ride ends, it ends at its own poles, never at a child stop.
        val end = LegEnd.at(area, StepFree.ANY)
        assertEquals(LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("490A", "490B")), end)
        assertFalse(end.endsAt(a1))
    }

    @Test
    fun `a ride's end resolves through the model from the leg's raw ids`() {
        // A middle ride getting off at Hill's low-level record: the rail station's, as the model files it,
        // so the other record gets the ride off there and the tube station of the same hub doesn't.
        val first = ride("910GHILLLL")
        val pairs = LegEnd.of(TripRoute(listOf(first, ride("910GVALE"))), StepFree.ANY, setOf("910GVALE"), hubs)
        val end = LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("1009001:rail"))
        assertEquals(end, pairs[0].second)
        assertTrue(end.endsAt(hubs.resolve("910GHILL")))
        assertFalse(end.endsAt(hubs.resolve("940GZZLUHIL")))
        // The last ride, into the destination's hub, ends at it.
        assertEquals(LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("910GVALE")), pairs[1].second)
        // With no model, each id is only itself, as a ride's planned end ([LegEnd.planned]) has it.
        assertEquals(
            LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("910GHILLLL")),
            LegEnd.of(TripRoute(listOf(first, ride("910GVALE"))), StepFree.ANY, setOf("910GVALE"), null)[0].second,
        )
    }

    @Test
    fun `a last ride ending at a split area with no pole or point keeps the Planner's call`() {
        val pairs = LegEnd.of(TripRoute(listOf(ride("490G000005", mode = "bus"))), StepFree.ANY, setOf("490000005A"), hubs)
        assertEquals(LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("490000005A", "490000005B")), pairs.single().second)
    }

    @Test
    fun `a call naming several records is never where a ride gets off`() {
        // A National Rail CRS naming both of HUBA's stations: the hub agrees, but no one TfL id does.
        val crs = Resolved(tflId = null, station = null, hub = "HUBA", poles = setOf("910GA1", "910GA2"))
        assertFalse(LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBA")).endsAt(crs))
        assertFalse(LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("910GA1")).endsAt(crs))
        assertTrue(LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBA")).endsAt(a1))
    }

    @Test
    fun `a route's middle ride ends at its call and the last at the destination's hubs`() {
        val first = ride("910GVALE")
        val last = ride("910GHILL")
        val pairs = LegEnd.of(TripRoute(listOf(walk(), first, walk(), last)), StepFree.FULLY, setOf("HUBEXA"), hubs)
        assertEquals(listOf(first, last), pairs.map { it.first })
        assertEquals(LegEnd(StepFree.FULLY, LegEnd.Level.STOP, setOf("910GVALE")), pairs[0].second)
        assertEquals(LegEnd(StepFree.FULLY, LegEnd.Level.HUB, setOf("HUBEXA")), pairs[1].second)
    }

    @Test
    fun `a last ride the Planner gets off outside the destination's hubs ends at its call`() {
        val last = ride("910GVALE", mode = "bus")
        val pairs = LegEnd.of(TripRoute(listOf(last, walk())), StepFree.ANY, setOf("HUBEXA"), hubs)
        assertEquals(LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("910GVALE")), pairs.single().second)
    }

    @Test
    fun `the same leg last in one route and in the middle of another is two pairs`() {
        val leg = ride("910GHILL")
        val asLast = LegEnd.of(TripRoute(listOf(leg)), StepFree.ANY, setOf("HUBEXA"), hubs).single()
        val asMiddle = LegEnd.of(TripRoute(listOf(leg, ride("910GVALE"))), StepFree.ANY, setOf("910GVALE"), hubs).first()
        assertTrue(asLast != asMiddle)
    }

    @Test
    fun `a ride's planned end is the Planner's call by its own id`() {
        // Worked out with no model at hand: the call's raw id, the same wherever the leg is.
        val end = LegEnd.planned(ride("910GHILL"), StepFree.STATION)
        assertEquals(LegEnd(StepFree.STATION, LegEnd.Level.STOP, setOf("910GHILL")), end)
        assertEquals(end, LegEnd.planned(ride("910GHILL"), StepFree.STATION))
        // A bus moved to its route's pole: that pole alone.
        assertEquals(
            LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("490000001A")),
            LegEnd.planned(moved(ride("490000001B", mode = "bus", toArea = "490G000001"), "490000001A"), StepFree.ANY),
        )
    }

    @Test
    fun `a bus moved to its route's pole gets off at the Planner's stop`() {
        val ride = moved(ride("490000001B", mode = "bus", toArea = "490G000001"), "490000001A")
        val end = LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("490G000001"))
        assertEquals(end, LegEnd.of(TripRoute(listOf(ride, walk())), StepFree.ANY, setOf("910GVALE"), hubs).single().second)
        assertTrue(end.endsAt(hubs.resolve("490000001A")))
        // As the last ride into the destination's hub, by the pair's hub.
        assertEquals(
            LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBEXA")),
            LegEnd.of(TripRoute(listOf(ride)), StepFree.ANY, setOf("HUBEXA"), hubs).single().second,
        )
    }

    @Test
    fun `a bus moved within a pair the model split gets off at its own pole`() {
        // The area's poles are in two stops; the Planner's is in one, the bus's in the other.
        val ride = moved(ride("490000005A", mode = "bus", toArea = "490G000005"), "490000005B")
        val end = LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("490000005B"))
        assertEquals(end, LegEnd.of(TripRoute(listOf(ride)), StepFree.ANY, setOf("490000005A"), hubs).single().second)
        assertTrue(end.endsAt(hubs.resolve("490000005B")))
        assertFalse(end.endsAt(hubs.resolve("490000005A")))
    }

    @Test
    fun `a bus moved to another stand with no pair gets off at that stand`() {
        val ride = moved(ride("490000001B", mode = "bus"), "490000001A")
        val end = LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("490000001A"))
        assertEquals(end, LegEnd.of(TripRoute(listOf(ride)), StepFree.ANY, setOf("HUBEXA"), hubs).single().second)
    }
}
