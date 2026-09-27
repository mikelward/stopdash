package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FartherStationsTest {
    private val here = Coordinates(51.5, -0.12)

    // About 111 m per 0.001 degrees of latitude.
    private fun station(id: String, name: String, northMeters: Double, lines: Map<String, List<String>>) =
        IndexedStation(id, name, lines.keys.toList(), latitude = 51.5 + northMeters / 111_195.0, longitude = -0.12, lines = lines)

    private fun tube(vararg ids: String) = mapOf("tube" to ids.toList())
    // A stop the lookup reached with [lines], known to the index by none of its ids.
    private fun reached(vararg lines: Pair<String, String>) =
        listOf(FartherStations.ReachedStop(emptySet(), lines.mapTo(HashSet()) { FartherStations.Line(it.first, it.second) }))

    private fun reachedAt(id: String, vararg lines: Pair<String, String>) =
        FartherStations.ReachedStop(setOf(id), lines.mapTo(HashSet()) { FartherStations.Line(it.first, it.second) })

    private val near = station("940GNEAR", "Near", 800.0, tube("northern"))
    private val lineB = station("940GLINEB", "Line B", 2_600.0, tube("piccadilly"))
    private val lineC = station("940GLINEC", "Line C", 3_000.0, tube("victoria"))
    private val lineD = station("940GLINED", "Line D", 3_500.0, tube("central"))
    private val ground = station("910GGROUND", "Ground", 3_200.0, mapOf("overground" to listOf("suffragette")))
    private val rail = station("910GRAIL", "Rail", 2_000.0, mapOf("national-rail" to listOf("great-northern")))
    private val sameService = station("910GSAME", "Same Service", 2_200.0, mapOf("national-rail" to listOf("great-northern")))
    private val beyond = station("910GBEYOND", "Beyond", 6_000.0, mapOf("elizabeth-line" to listOf("elizabeth")))
    private val all = listOf(near, lineB, lineC, lineD, ground, rail, sameService, beyond)

    @Test
    fun `names the nearest station of each unreached line, nearest first`() {
        val picked = FartherStations.pick(all, here, reached = reached("tube" to "northern"))
        // Two tube lines at most (the nearest two), one button per service, nothing past 3 mi.
        assertEquals(listOf("910GRAIL", "940GLINEB", "940GLINEC", "910GGROUND"), picked.map { it.station.id })
        assertTrue(picked.zipWithNext().all { (a, b) -> a.meters <= b.meters })
    }

    @Test
    fun `a line gets its nearest station the other way too`() {
        val north = station("940GNORTH", "North", 1_000.0, tube("piccadilly"))
        val fartherNorth = station("940GNORTH2", "Farther North", 1_500.0, tube("piccadilly"))
        val south = station("940GSOUTH", "South", -2_000.0, tube("piccadilly"))
        val picked = FartherStations.pick(listOf(north, fartherNorth, south), here, reached = emptyList())
        // The second station on the same side adds no direction; the one past the rider does.
        assertEquals(listOf("940GNORTH", "940GSOUTH"), picked.map { it.station.id })
        assertEquals(listOf(listOf(FartherStations.Line("tube", "piccadilly"))), picked.map { it.lines }.distinct())
    }

    @Test
    fun `a station off to the side is not the other direction`() {
        val north = station("940GNORTH", "North", 1_000.0, tube("piccadilly"))
        // Due east: a right angle round from the nearest, not past it.
        val east = IndexedStation("940GEAST", "East", listOf("tube"), latitude = 51.5, longitude = -0.12 + 0.02, lines = tube("piccadilly"))
        assertEquals(listOf("940GNORTH"), FartherStations.pick(listOf(north, east), here, reached = emptyList()).map { it.station.id })
    }

    @Test
    fun `the tube cap counts lines, each with both its directions`() {
        val picked = FartherStations.pick(
            listOf(
                station("940GPN", "P North", 1_000.0, tube("piccadilly")),
                station("940GPS", "P South", -1_100.0, tube("piccadilly")),
                station("940GVN", "V North", 1_200.0, tube("victoria")),
                station("940GVS", "V South", -1_300.0, tube("victoria")),
                station("940GCN", "C North", 1_400.0, tube("central")),
            ),
            here,
            reached = emptyList(),
        )
        assertEquals(listOf("940GPN", "940GPS", "940GVN", "940GVS"), picked.map { it.station.id })
    }

    @Test
    fun `a National Rail route end keeps just its nearest station`() {
        val north = rail("910GN", 1_000.0, "thameslink", "910GEND")
        val south = rail("910GS", -1_500.0, "thameslink", "910GEND")
        assertEquals(listOf("910GN"), FartherStations.pick(listOf(north, south), here, reached = emptyList()).map { it.station.id })
    }

    @Test
    fun `each National Rail service counts as its own line`() {
        val thameslink = station("910GTHAMES", "Thameslink Stop", 2_800.0, mapOf("national-rail" to listOf("thameslink")))
        val picked = FartherStations.pick(listOf(rail, sameService, thameslink), here, reached = emptyList())
        assertEquals(listOf("910GRAIL", "910GTHAMES"), picked.map { it.station.id })
    }

    @Test
    fun `at most eight cards in all`() {
        val many = (1..10).map { station("910GR$it", "Rail $it", 1_000.0 + it * 100, mapOf("national-rail" to listOf("service-$it"))) }
        assertEquals(FartherStations.MAX_BUTTONS, FartherStations.pick(many, here, emptyList()).size)
    }

    @Test
    fun `a reached line gets no button, and neither does a hidden mode`() {
        val picked = FartherStations.pick(
            all,
            here,
            reached = reached("tube" to "northern", "tube" to "piccadilly", "national-rail" to "great-northern"),
            hidden = setOf("overground"),
        )
        assertEquals(listOf("940GLINEC", "940GLINED"), picked.map { it.station.id })
        assertTrue(FartherStations.pick(all, here, emptyList(), hidden = setOf("tube", "national-rail", "overground")).isEmpty())
    }

    @Test
    fun `a hidden line gets no button, and its mode's other lines still do`() {
        val picked = FartherStations.pick(
            listOf(near, lineC, lineD),
            here,
            reached = reached("tube" to "northern"),
            hidden = setOf(HiddenModes.lineKey("victoria", "Victoria line")),
        )
        assertEquals(listOf("940GLINED"), picked.map { it.station.id })
    }

    @Test
    fun `a station serving two unreached lines is one card`() {
        val both = station("940GBOTH", "Both", 1_500.0, tube("piccadilly", "victoria"))
        val picked = FartherStations.pick(listOf(near, both, lineC, ground), here, reached("tube" to "northern"))
        assertEquals(listOf("940GBOTH", "910GGROUND"), picked.map { it.station.id })
    }

    @Test
    fun `a place's tube lines all count toward the cap`() {
        val both = station("940GBOTH", "Both", 1_500.0, tube("piccadilly", "victoria"))
        val picked = FartherStations.pick(listOf(near, both, lineD), here, reached("tube" to "northern"))
        // Both is two tube lines already, so the Central line's station isn't offered.
        assertEquals(listOf("940GBOTH"), picked.map { it.station.id })
        assertEquals(setOf("piccadilly", "victoria"), picked.flatMap { it.lines }.mapTo(HashSet()) { it.id })
    }

    @Test
    fun `a capped tube line is left off a place that serves it`() {
        val first = station("940GFIRST", "First", 1_000.0, tube("piccadilly"))
        val second = station("940GSECOND", "Second", 1_100.0, tube("victoria"))
        val third = station("940GTHIRD", "Third", 1_200.0, mapOf("tube" to listOf("central"), "dlr" to listOf("dlr")))
        val picked = FartherStations.pick(listOf(first, second, third), here, emptyList())
        assertEquals(listOf("940GFIRST", "940GSECOND", "940GTHIRD"), picked.map { it.station.id })
        assertEquals(listOf(FartherStations.Line("dlr", "dlr")), picked.last().lines)
    }

    @Test
    fun `a farther place carries every unreached line it stands for`() {
        val both = station("940GBOTH", "Both", 1_500.0, tube("piccadilly", "victoria"))
        val picked = FartherStations.pick(listOf(near, both, ground), here, reached("tube" to "northern"))
        assertEquals(
            listOf(
                listOf(FartherStations.Line("tube", "piccadilly"), FartherStations.Line("tube", "victoria")),
                listOf(FartherStations.Line("overground", "suffragette")),
            ),
            picked.map { farther -> farther.lines.sortedBy { it.id } },
        )
    }

    @Test
    fun `an interchange's stations are one button, opening the interchange`() {
        val tubeStation = station("940GZZLUEXA", "Example", 2_000.0, tube("central")).copy(hubId = "HUBEXA")
        val dlr = station("940GZZDLEXA", "Example", 2_050.0, mapOf("dlr" to listOf("dlr"))).copy(hubId = "HUBEXA")
        val railStation = station("910GEXAMPLE", "Example Rail", 2_100.0, mapOf("national-rail" to listOf("c2c"))).copy(hubId = "HUBEXA")
        val hub = IndexedStation("HUBEXA", "Example", listOf("dlr", "national-rail", "tube"))
        val picked = FartherStations.pick(listOf(tubeStation, dlr, railStation, hub), here, emptyList())
        assertEquals(listOf("HUBEXA"), picked.map { it.station.id })
    }

    @Test
    fun `an unplaced station is never offered, nor a bus line`() {
        val unplaced = IndexedStation("910GNOWHERE", "Nowhere", listOf("national-rail"), lines = mapOf("national-rail" to listOf("x")))
        val bus = station("490GBUS", "Bus", 100.0, mapOf("bus" to listOf("1")))
        assertTrue(FartherStations.pick(listOf(unplaced, bus), here, emptyList()).isEmpty())
    }

    private fun rail(id: String, northMeters: Double, service: String, vararg ends: String) =
        station(id, id, northMeters, mapOf("national-rail" to listOf(service))).copy(routeEnds = mapOf(service to ends.toList()))

    @Test
    fun `a service counts by the ends it runs to, so a second station on another route qualifies`() {
        // One service, two routes out of London: one station on each, both sharing the south end.
        val parkSide = rail("910GPARK", 2_500.0, "thameslink", "910GSOUTH", "910GNORTHB")
        val townSide = rail("910GTOWN", 2_900.0, "thameslink", "910GSOUTH", "910GNORTHA")
        val samePark = rail("910GPARK2", 2_700.0, "thameslink", "910GSOUTH", "910GNORTHB")
        val picked = FartherStations.pick(listOf(parkSide, townSide, samePark), here, reached = emptyList())
        // The second station on the first route adds no end; the one on the other route does.
        assertEquals(listOf("910GPARK", "910GTOWN"), picked.map { it.station.id })
    }

    @Test
    fun `a nearby station's own route ends are reached, and only those`() {
        val nearby = rail("910GNEARBY", 500.0, "thameslink", "910GSOUTH", "910GNORTHB")
        val sameRoute = rail("910GSAME", 2_000.0, "thameslink", "910GSOUTH", "910GNORTHB")
        val otherRoute = rail("910GOTHER", 2_500.0, "thameslink", "910GSOUTH", "910GNORTHA")
        val picked = FartherStations.pick(
            listOf(nearby, sameRoute, otherRoute),
            here,
            reached = listOf(reachedAt("910GNEARBY", "national-rail" to "thameslink")),
        )
        assertEquals(listOf("910GOTHER"), picked.map { it.station.id })
    }

    @Test
    fun `a line reached through a stop with no route data counts as reached whole`() {
        val otherRoute = rail("910GOTHER", 2_500.0, "thameslink", "910GSOUTH", "910GNORTHA")
        val picked = FartherStations.pick(
            listOf(otherRoute),
            here,
            reached = listOf(reachedAt("910GNOTINDEXED", "national-rail" to "thameslink")),
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a service with no route data at any reached stop counts as reached whole`() {
        // Two nearby stations on one service: one with route data, one without. The second may reach
        // any end, so no farther station on that service is offered.
        val detailed = rail("910GDETAILED", 500.0, "thameslink", "910GSOUTH", "910GNORTHB")
        val unknown = station("910GUNKNOWN", "910GUNKNOWN", 600.0, mapOf("national-rail" to listOf("thameslink")))
        val otherRoute = rail("910GOTHER", 2_500.0, "thameslink", "910GSOUTH", "910GNORTHA")
        val picked = FartherStations.pick(
            listOf(detailed, unknown, otherRoute),
            here,
            reached = listOf(
                reachedAt("910GDETAILED", "national-rail" to "thameslink"),
                reachedAt("910GUNKNOWN", "national-rail" to "thameslink"),
            ),
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a line the index lists but the live lookup doesn't isn't counted as reached`() {
        // The weekly index still gives the nearby station a service TfL has since moved away.
        val nearby = station("910GNEARBY", "910GNEARBY", 500.0, mapOf("national-rail" to listOf("thameslink", "gone")))
        val goneElsewhere = station("910GELSE", "910GELSE", 2_500.0, mapOf("national-rail" to listOf("gone")))
        val picked = FartherStations.pick(
            listOf(nearby, goneElsewhere),
            here,
            reached = listOf(reachedAt("910GNEARBY", "national-rail" to "thameslink")),
        )
        assertEquals(listOf("910GELSE"), picked.map { it.station.id })
    }

    @Test
    fun `a farther record with no route data on a service reached by route is not offered`() {
        val nearby = rail("910GNEARBY", 500.0, "thameslink", "910GSOUTH", "910GNORTHB")
        val bare = station("910GBARE", "910GBARE", 2_000.0, mapOf("national-rail" to listOf("thameslink")))
        val picked = FartherStations.pick(
            listOf(nearby, bare),
            here,
            reached = listOf(reachedAt("910GNEARBY", "national-rail" to "thameslink")),
        )
        assertTrue(picked.isEmpty())
        // With the service unreached, the bare record still stands for the whole line.
        assertEquals(listOf("910GBARE"), FartherStations.pick(listOf(bare), here, reached = emptyList()).map { it.station.id })
    }

    @Test
    fun `a nearby terminus counts as a reached end`() {
        // Standing at a branch's terminus: the next station up the branch reaches only this terminus
        // and the far end this one already reaches, so it adds nothing.
        val terminus = rail("910GTERMINUS", 100.0, "south-western", "910GLONDON")
        val nextUp = rail("910GNEXTUP", 1_500.0, "south-western", "910GTERMINUS", "910GLONDON")
        val picked = FartherStations.pick(
            listOf(terminus, nextUp),
            here,
            reached = listOf(reachedAt("910GTERMINUS", "national-rail" to "south-western")),
        )
        assertTrue(picked.isEmpty())
    }
}
