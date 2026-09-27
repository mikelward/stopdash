package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The farther bus places (SPEC *Finding stops → Farther stations*). Synthetic ids and names. */
class FartherBusesTest {
    private fun bus(id: String) = LineRef(id, id, "bus")

    private fun pole(id: String, cluster: String, vararg routes: String, hub: String = "") =
        StopLocation(id, "Stop $cluster", 0.0, 0.0, routes.map(::bus), clusterId = cluster, hubId = hub)

    private fun cluster(key: String, meters: Double, vararg poles: StopLocation) =
        NearbySelection.NearbyCluster(key, poles.toList(), meters)

    @Test
    fun `each farther bus place is a candidate with all its routes, nearest first`() {
        val candidates = FartherBuses.candidates(
            listOf(
                cluster("J1", 600.0, pole("J1A", "J1", "73", "390")),
                cluster("J2", 700.0, pole("J2A", "J2", "73")),
            ),
        )
        assertEquals(listOf("J1", "J2"), candidates.map { it.key })
        assertEquals(listOf(bus("73"), bus("390")), candidates[0].lines)
        assertEquals(600.0, candidates[0].meters, 0.0)
    }

    @Test
    fun `same-named clusters of one interchange are one place, differently named ones aren't`() {
        fun named(id: String, cluster: String, name: String, route: String) =
            StopLocation(id, name, 0.0, 0.0, listOf(bus(route)), clusterId = cluster, hubId = "HUBX")
        val candidates = FartherBuses.candidates(
            listOf(
                cluster("J1", 600.0, named("J1A", "J1", "Example Station", "1")),
                cluster("J2", 650.0, named("J2A", "J2", "Example Station", "2")),
                cluster("J3", 700.0, named("J3A", "J3", "Example Road", "3")),
            ),
        )
        assertEquals(listOf("hub:HUBX|Example Station", "hub:HUBX|Example Road"), candidates.map { it.key })
        assertEquals(listOf("J1A", "J2A"), candidates[0].stops.map { it.id })
        assertEquals(listOf(bus("1"), bus("2")), candidates[0].lines)
        assertEquals(listOf(bus("3")), candidates[1].lines)
    }

    @Test
    fun `route-less stops, other modes and hidden buses give no candidate`() {
        val routeless = cluster("J0", 400.0, pole("J0A", "J0"))
        val tram = cluster("T1", 500.0, StopLocation("T1A", "Tram stop", 0.0, 0.0, listOf(LineRef("tram", "Tram", "tram")), clusterId = "T1"))
        val buses = cluster("J1", 600.0, pole("J1A", "J1", "73"))
        assertEquals(listOf("J1"), FartherBuses.candidates(listOf(routeless, tram, buses)).map { it.key })
        assertTrue(FartherBuses.candidates(listOf(buses), hidden = setOf("bus")).isEmpty())
    }

    @Test
    fun `a hidden route isn't named, and a place with only hidden routes gives no candidate`() {
        val hidden = setOf(HiddenModes.lineKey("73", "73"))
        val candidates = FartherBuses.candidates(
            listOf(cluster("J1", 600.0, pole("J1A", "J1", "73", "390")), cluster("J2", 700.0, pole("J2A", "J2", "73"))),
            hidden = hidden,
        )
        assertEquals(listOf("J1"), candidates.map { it.key })
        assertEquals(listOf(bus("390")), candidates.single().lines)
    }

    @Test
    fun `a candidate's routes are its bus routes only, not another mode it also serves`() {
        val mixed = cluster(
            "J1", 600.0,
            StopLocation("J1A", "Stop J1", 0.0, 0.0, listOf(bus("73"), LineRef("tram", "Tram", "tram")), clusterId = "J1"),
        )
        assertEquals(listOf(bus("73")), FartherBuses.candidates(listOf(mixed)).single().lines)
    }

    @Test
    fun `a mode TfL sends capitalized still counts as bus`() {
        val capitalized = cluster(
            "J1", 600.0,
            StopLocation("J1A", "Stop J1", 0.0, 0.0, listOf(LineRef("73", "73", "Bus")), clusterId = "J1"),
        )
        assertEquals(listOf("73"), FartherBuses.candidates(listOf(capitalized)).single().lines.map { it.id })
    }

    @Test
    fun `a place records the stations a pole is within 150 m of, and not one farther`() {
        // 0.001° of latitude is about 111 m; 0.0015° about 167 m.
        val station = StopLocation("940GEX", "Example", 0.0, 0.0, listOf(LineRef("northern", "Northern", "tube")))
        fun at(key: String, lat: Double) =
            cluster(key, 700.0, StopLocation("${key}A", "Stop $key", lat, 0.0, listOf(bus("234")), clusterId = key))
        val candidates = FartherBuses.candidates(listOf(at("NEAR", 0.001), at("FAR", 0.0015)), stations = listOf(station))
        assertEquals(listOf(setOf("940GEX"), emptySet<String>()), candidates.map { it.stationIds })
        assertEquals(
            listOf(emptySet<String>(), emptySet<String>()),
            FartherBuses.candidates(listOf(at("NEAR", 0.001), at("FAR", 0.0015))).map { it.stationIds },
        )
    }

    @Test
    fun `the stations the list may show are the eager stops of a shown mode other than bus`() {
        val tube = StopLocation("940GEX", "Example", 0.0, 0.0, listOf(LineRef("northern", "Northern", "tube")))
        val rail = StopLocation("910GEX", "Example Rail", 0.0, 0.0, listOf(LineRef("c2c", "c2c", "national-rail")))
        val eager = listOf(
            cluster("S1", 300.0, tube),
            cluster("S2", 400.0, rail),
            cluster("J1", 100.0, pole("J1A", "J1", "73")),
        )
        assertEquals(listOf("940GEX", "910GEX"), FartherBuses.stationStops(eager).map { it.id })
        // A hidden mode's station isn't on the list, so it draws no bus place to it.
        assertEquals(listOf("940GEX"), FartherBuses.stationStops(eager, hidden = setOf("national-rail")).map { it.id })
    }
}
