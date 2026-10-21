package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic records: a hub HUBA holding stations STA1 and STA2, and a hub HUBB, as [Resolved] values;
 * and the synthetic Hill model ([HillHubs]) for the ends a route's rides get off at, resolved from their raw ids.
 */
class LegEndTest {
    private val a1 = Resolved(tflId = "910GA1", station = "STA1", hub = "HUBA")
    private val a1Other = Resolved(tflId = "940GA1", station = "STA1", hub = "HUBA")
    private val a2 = Resolved(tflId = "910GA2", station = "STA2", hub = "HUBA")
    private val b = Resolved(tflId = "910GB", station = "STB", hub = "HUBB")
    private val at = Instant.parse("2026-01-01T09:00:00Z")

    private val hubs = HillHubs.model

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
    fun `a step-free end's calls meet its level by the train's line, a National Rail train's by its station's`() {
        // Made-up access at one record of STA1: its Southern platforms not step-free, its National Rail ones to the platform.
        val levels = mapOf("910GA1" to mapOf("southern" to StepFreeLevel.NONE, StepFreeAccess.NATIONAL_RAIL to StepFreeLevel.PLATFORM))
        val end = LegEnd(StepFree.STATION, LegEnd.Level.STOP, setOf("STA1"), ids = setOf("910GA1", "940GA1"), levels = levels)
        // An operator TfL describes there is read by its own platforms; one it doesn't, by the station's National Rail ones.
        assertFalse(end.meets("910GA1", "southern", "national-rail"))
        assertTrue(end.meets("910GA1", "thameslink", "national-rail"))
        // A tube train isn't National Rail, and isn't described there: unknown, so no; nor anywhere the table is silent.
        assertFalse(end.meets("910GA1", "victoria", "tube"))
        assertFalse(end.meets("940GA1", "victoria", "tube"))
        // A bus stop is at the street; and with no level, every call meets it.
        assertTrue(end.meets("490GA1", "7", "bus"))
        assertTrue(end.copy(stepFree = StepFree.ANY).meets("940GA1", "victoria", "tube"))
        // So a Thameslink train, its own line, gets off at the record that meets the level and the Planner's own call.
        val leg = ride("940GA1", mode = "national-rail").copy(lineId = "thameslink")
        assertTrue(end.checksAccess)
        assertEquals(setOf("910GA1", "940GA1"), end.offIds(leg))
        assertEquals(setOf("910GA1"), end.offIds(leg, "southern2", "national-rail"))
        // Fully step-free, the platform isn't enough: only the Planner's own call, for its own line.
        assertEquals(setOf("940GA1"), end.copy(stepFree = StepFree.FULLY).offIds(leg))
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

    @Test
    fun `a hub end's ids are every station and stop of the hub, each to its own`() {
        val ids = hubs.idsOf(LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBEXA")))
        assertEquals("1009001:rail", ids["910GHILL"])
        assertEquals("1009001:rail", ids["910GHILLLL"])
        assertEquals("1009002:tube", ids["940GZZLUHIL"])
        assertEquals("490G000001", ids["490000001A"])
        assertFalse("910GVALE" in ids)
    }

    @Test
    fun `a stop end's ids are its station's alone, and a pole end's its poles`() {
        val station = hubs.idsOf(LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("1009001:rail")))
        assertTrue("910GHILL" in station && "910GHILLLL" in station)
        assertFalse("940GZZLUHIL" in station)
        assertEquals(mapOf("490000001A" to "490G000001"), hubs.idsOf(LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("490000001A"))))
        // A place the bundle doesn't hold is its own.
        assertEquals(mapOf("910GNONE" to "910GNONE"), hubs.idsOf(LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("910GNONE"))))
    }

    // A trip on the way to Hill's tube station, its last ride getting off where the Planner names, [stepFree] as planned.
    private fun toHill(last: TripLeg, stepFree: StepFree?, stopId: String = "940GZZLUHIL") = ActiveTrip(
        TripRoute(listOf(last, walk())), "Hill", startedAt = at, destinationIds = mapOf(stopId to stopId),
        destinationStopId = stopId, destinationLevel = Pick.Level.STATION, stepFree = stepFree,
    )

    @Test
    fun `On the way's last ride arrives anywhere in the destination's hub`() {
        // Planned to Hill's rail station, a ride to the tube station's hub: every station and stop of it.
        val ids = OnTheWay.arrivingIds(toHill(ride("910GHILL", mode = "national-rail"), StepFree.ANY), hubs)
        assertEquals("1009001:rail", ids["910GHILLLL"])
        assertEquals("1009002:tube", ids["940GZZLUHIL"])
        // Its end, as its ride lines are given it: the hub, getting off at any of those ids.
        assertEquals(
            LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBEXA"), ids = ids.keys),
            OnTheWay.arrivingEnd(toHill(ride("910GHILL", mode = "national-rail"), StepFree.ANY), hubs),
        )
        // An end already moved still reads the route as planned.
        val moved = toHill(ride("910GHILLLL", mode = "national-rail"), StepFree.ANY)
            .copy(plannedLegs = toHill(ride("910GHILL", mode = "national-rail"), StepFree.ANY).route.legs)
        assertEquals(ids, OnTheWay.arrivingIds(moved, hubs))
    }

    @Test
    fun `On the way's step-free last ride arrives by hub where its line meets the trip's level today`() {
        val last = ride("910GHILL", mode = "national-rail")
        // Hill's tube station is step-free to the platform on the ride's line; the rail station's other record isn't described.
        val access = StepFreeAccess(mapOf("940GZZLUHIL" to mapOf("l" to listOf(StepFreePlatform(StepFreeLevel.PLATFORM)))))
        val arrival = OnTheWay.arriving(toHill(last, StepFree.STATION), hubs, access)
        assertEquals("1009002:tube", arrival.stations["940GZZLUHIL"])
        assertEquals(mapOf("940GZZLUHIL" to mapOf("l" to StepFreeLevel.PLATFORM)), arrival.hubEnd?.levels)
        assertEquals(arrival.hubEnd, OnTheWay.arrivingEnd(toHill(last, StepFree.STATION), hubs, access))
        assertTrue(arrival.getsOffAt("940GZZLUHIL", last))
        assertFalse(arrival.getsOffAt("910GHILLLL", last))
        // The Planner's own call, for its own line, which the Planner checked.
        assertTrue(arrival.getsOffAt("910GHILL", last))
        assertFalse(arrival.getsOffAt("910GHILL", last.copy(lineId = "m")))
        // A trip kept before its level was: no wider, no narrower than before.
        assertTrue(OnTheWay.arriving(toHill(last, null), hubs, access) === Arrival.NONE)
    }

    @Test
    fun `On the way's last ride arrives by hub only where its level is known, checkable and the Planner's call is in it`() {
        val last = ride("910GHILL", mode = "national-rail")
        // Step-free with no table to check by, or a trip kept before its level was: where the Planner gets it off.
        assertEquals(emptyMap<String, String>(), OnTheWay.arrivingIds(toHill(last, StepFree.STATION), hubs))
        assertEquals(emptyMap<String, String>(), OnTheWay.arrivingIds(toHill(last, null), hubs))
        // Nor is it given a hub end: it gets off where the Planner does.
        assertNull(OnTheWay.arrivingEnd(toHill(last, StepFree.STATION), hubs))
        // The Planner gets off at Vale, outside the destination's hub, and walks on.
        assertEquals(emptyMap<String, String>(), OnTheWay.arrivingIds(toHill(ride("910GVALE", mode = "national-rail"), StepFree.ANY), hubs))
        // A trip to a place, which has no hub.
        assertEquals(emptyMap<String, String>(), OnTheWay.arrivingIds(toHill(last, StepFree.ANY, stopId = ""), hubs))
    }
}
