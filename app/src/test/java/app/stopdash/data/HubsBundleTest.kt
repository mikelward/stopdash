package app.stopdash.data

import app.stopdash.domain.BundledStops
import app.stopdash.domain.Hubs
import app.stopdash.domain.Hubs.Membership
import app.stopdash.domain.Pick
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The station model ([Hubs]) read from the real bundle, at big stations and interchanges only
 * (AGENTS.md *Privacy*): the rules themselves are the builders' Python tests, on fixtures.
 */
class HubsBundleTest {
    private val hubs: Hubs by lazy {
        val dir = listOf(File("src/main/assets/stations"), File("app/src/main/assets/stations")).first { it.exists() }
        val index = StationIndexStore.parse(File(dir, "station_index.json").readText())
        val stops = File(dir, "stops.tsv").bufferedReader().use { BundledStops.read(it) }
        val reasons = mutableListOf<String>()
        Hubs.of(index, stops) { reasons += it } ?: error("the bundled model is unavailable: $reasons")
    }

    @Test
    fun `St Pancras's three records are one station, in one hub with King's Cross and the Underground`() {
        val stPancras = hubs.stationOf("910GSTPX")
        assertTrue(stPancras is Membership.Of)
        assertEquals(stPancras, hubs.stationOf("910GSTPXBOX"))
        assertEquals(stPancras, hubs.stationOf("910GSTPADOM"))
        assertFalse(hubs.sameStation("910GSTPX", "910GKNGX"))
        assertFalse(hubs.sameStation("910GSTPX", "940GZZLUKSX"))
        assertFalse(hubs.sameStation("910GKNGX", "940GZZLUKSX"))
        assertEquals(Membership.Of("HUBKGX"), hubs.hubOf("910GSTPXBOX"))
        assertEquals("its page asks every record", setOf("910GSTPX", "910GSTPXBOX", "910GSTPADOM"), hubs.recordsOf("910GSTPX").toSet())
        assertTrue(hubs.sameHub("910GKNGX", "940GZZLUKSX"))
        val members = hubs.membersOf("HUBKGX")
        assertNotNull(members)
        assertEquals(3, members!!.stations.size)
        assertNotNull(hubs.placeOf("HUBKGX", Hubs.Level.HUB))
    }

    @Test
    fun `search's St Pancras row opens St Pancras, and its King's Cross row the interchange`() {
        val dir = listOf(File("src/main/assets/stations"), File("app/src/main/assets/stations")).first { it.exists() }
        val index = StationIndexStore.parse(File(dir, "station_index.json").readText())
        val rows = index.memberRows.getValue("HUBKGX").associateBy { it.match.name }
        val stPancras = rows.getValue("St Pancras International").match
        assertEquals(Pick.Level.STATION, stPancras.level)
        assertEquals(hubs.stationOf("910GSTPX"), hubs.stationOf(stPancras.id))
        assertEquals("HUBKGX", rows.getValue("King's Cross").match.id)
        // A row saved before, at the interchange's id, reads as the row now listed.
        assertEquals(stPancras, hubs.resolved(stPancras.copy(id = "HUBKGX", level = null)))
    }

    @Test
    fun `a King's Cross pick saved before phase 2 reads as the row it was picked from`() {
        // St Pancras's own row stands for St Pancras alone, and is arrived at there, never at King's Cross.
        val stPancras = hubs.picked("HUBKGX", "St Pancras International")
        assertEquals(Pick.Level.STATION, stPancras.level)
        assertEquals(hubs.stationOf("910GSTPX"), hubs.stationOf(stPancras.id))
        val arrivals = hubs.idsOf(stPancras)
        assertTrue(arrivals.keys.containsAll(listOf("910GSTPX", "910GSTPXBOX", "910GSTPADOM")))
        assertFalse("910GKNGX" in arrivals)
        assertFalse("940GZZLUKSX" in arrivals)
        // The King's Cross row takes in the Underground's King's Cross St. Pancras: two stations, so the hub.
        assertEquals(Pick("HUBKGX", "King's Cross", Pick.Level.HUB), hubs.picked("HUBKGX", "King's Cross"))
        assertTrue(hubs.idsOf(hubs.picked("HUBKGX", "King's Cross")).keys.containsAll(listOf("910GKNGX", "940GZZLUKSX", "910GSTPX")))
    }

    @Test
    fun `Liverpool Street's and Paddington's main line and low level are one station each`() {
        assertTrue(hubs.sameStation("910GLIVST", "910GLIVSTLL"))
        assertTrue(hubs.sameStation("910GPADTON", "910GPADTLL"))
        assertFalse("the Underground is a station of its own", hubs.sameStation("910GLIVST", "940GZZLULVT"))
        assertTrue(hubs.sameHub("910GLIVST", "940GZZLULVT"))
    }

    @Test
    fun `the two Hammersmiths stay two stations`() {
        assertFalse(hubs.sameStation("940GZZLUHSC", "940GZZLUHSD"))
    }

    @Test
    fun `Reading's and Weybridge's twin records are one station each`() {
        assertTrue(hubs.sameStation("910GRDNGSTN", "910GRDNG4AB"))
        assertTrue(hubs.sameStation("910GWEYBDGB", "910GWEYBDGE"))
    }

    @Test
    fun `a platform is its station's, and an id the bundle doesn't hold is only itself`() {
        assertEquals(hubs.stationOf("940GZZLUKSX"), hubs.stationOf("9400ZZLUKSX3"))
        assertEquals(Membership.Unbundled, hubs.stationOf("910GNOWHERE"))
        assertEquals(Membership.Unbundled, hubs.hubOf("490NOWHERE1"))
        assertTrue(hubs.sameStation("910GNOWHERE", "910GNOWHERE"))
        assertFalse(hubs.sameStation("910GNOWHERE", "910GSTPX"))
    }

    @Test
    fun `every bundled bus pole has a stop and a hub`() {
        val dir = listOf(File("src/main/assets/stations"), File("app/src/main/assets/stations")).first { it.exists() }
        val stops = File(dir, "stops.tsv").bufferedReader().use { BundledStops.read(it) }
        val poles = stops.memberships().filter { it.second.type == 'B' }
        assertTrue("${poles.size} poles", poles.size > 15_000)
        poles.forEach { (stop, _) ->
            assertTrue(stop.id, hubs.stopOf(stop.id) is Membership.Of)
            assertTrue(stop.id, hubs.hubOf(stop.id) is Membership.Of)
        }
    }
}
