package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stop's links from the bundled index: its lines, its interchange's other stations, the stations near it. Public stations, real positions. */
class StopLinksTest {
    private val kxTube = IndexedStation(
        "940GZZLUKSX", "King's Cross St. Pancras Underground Station", listOf("tube"), "HUBKGX", 51.53066, -0.12319,
        mapOf("tube" to listOf("northern", "victoria", "piccadilly")), platforms = listOf("9400ZZLUKSX1"),
    )
    private val kxRail = IndexedStation(
        "910GKNGX", "London King's Cross Rail Station", listOf("national-rail"), "HUBKGX", 51.53088, -0.12293,
        mapOf("national-rail" to listOf("great-northern", "thameslink")),
    )
    private val stPancras = IndexedStation(
        "910GSTPX", "London St Pancras International Rail Station", listOf("national-rail"), "HUBKGX", 51.53239, -0.12719,
        mapOf("national-rail" to listOf("southeastern", "thameslink")),
    )
    private val stPancrasSoutheastern = IndexedStation(
        "910GSTPADOM", "London St Pancras International Rail Station", listOf("national-rail"), "HUBKGX", 51.53251, -0.12646,
        mapOf("national-rail" to listOf("southeastern")),
    )
    private val hub = IndexedStation("HUBKGX", "King's Cross & St Pancras International", listOf("national-rail", "tube"))
    private val euston = IndexedStation(
        "940GZZLUEUS", "Euston Underground Station", listOf("tube"), "HUBEUS", 51.52830, -0.13317, mapOf("tube" to listOf("northern", "victoria")),
    )
    private val waterloo = IndexedStation(
        "940GZZLUWLO", "Waterloo Underground Station", listOf("tube"), "HUBWAT", 51.50322, -0.11478, mapOf("tube" to listOf("bakerloo")),
    )
    private val index = StationIndex(
        listOf(kxTube, kxRail, stPancras, stPancrasSoutheastern, hub, euston, waterloo),
        lineNames = mapOf("northern" to "Northern", "victoria" to "Victoria", "piccadilly" to "Piccadilly", "thameslink" to "Thameslink"),
    )

    @Test
    fun a_tube_station_s_lines_its_interchange_and_what_is_a_short_walk_away() {
        val links = index.linksOf("940GZZLUKSX")
        assertEquals(listOf("northern", "victoria", "piccadilly"), links.lines.map { it.id })
        assertEquals("Victoria", links.lines[1].name)
        assertEquals("tube", links.lines[1].mode)
        // King's Cross rail and St Pancras, not the tube station again.
        assertEquals(setOf("910GKNGX", "910GSTPX"), links.sameHub.map { it.id }.toSet())
        // Euston is under 800 m away; Waterloo, over 3 km, isn't listed.
        assertEquals(listOf("940GZZLUEUS"), links.nearby.map { it.id })
        val meters = links.nearby.single().meters!!
        assertTrue("Euston is $meters m away", meters in 600.0..800.0)
        assertEquals(setOf("northern", "victoria"), links.nearby.single().lineIds)
    }

    @Test
    fun a_station_under_several_ids_is_one_chip_asking_for_all_of_them() {
        // St Pancras is listed as two National Rail stations: one chip, both ids, every line (Codex on #664).
        val stPancrasChip = index.linksOf("940GZZLUKSX").sameHub.single { it.name == "London St Pancras International" }
        assertEquals("910GSTPX", stPancrasChip.id)
        assertEquals(setOf("southeastern", "thameslink"), stPancrasChip.lineIds)
        // Opened, its board asks for the other id too, and From and To open the interchange.
        assertEquals(listOf("910GSTPADOM"), index.linksOf("910GSTPX").ownIds)
        assertEquals("HUBKGX", index.linksOf("910GSTPX").openId)
        // A station under one id opens itself.
        assertEquals(null, index.linksOf("910GKNGX").openId)
        // Opened, either id's page counts the other as itself: not a "same interchange" chip, its lines its own.
        val opened = index.linksOf("910GSTPX")
        assertTrue(opened.sameHub.none { it.id == "910GSTPADOM" })
        assertEquals(listOf("southeastern", "thameslink"), opened.lines.map { it.id })
    }

    @Test
    fun a_same_named_station_nearby_in_no_interchange_is_still_listed() {
        // Bethnal Green's tube and Overground stations: one name, two stations about 460 m apart (Codex on #664).
        val tube = IndexedStation("940GZZLUBLG", "Bethnal Green Underground Station", listOf("tube"), "", 51.52725, -0.05501)
        val overground = IndexedStation("910GBTHNLGR", "Bethnal Green Rail Station", listOf("overground"), "", 51.52390, -0.05960)
        val links = StationIndex(listOf(tube, overground)).linksOf("940GZZLUBLG")
        assertEquals(listOf("910GBTHNLGR"), links.nearby.map { it.id })
        assertEquals("Bethnal Green", links.nearby.single().name)
    }

    @Test
    fun a_station_under_two_ids_in_no_interchange_is_one_station() {
        // Weybridge is two records, one place, no hub: one stop asking for both, not a nearby duplicate (Codex on #664).
        val a = IndexedStation("910GWEYBDGB", "Weybridge Rail Station", listOf("national-rail"), "", 51.36176, -0.45772)
        val b = IndexedStation("910GWEYBDGE", "Weybridge Rail Station", listOf("national-rail"), "", 51.36176, -0.45772)
        val links = StationIndex(listOf(a, b)).linksOf("910GWEYBDGB")
        assertEquals(listOf("910GWEYBDGE"), links.ownIds)
        assertTrue(links.nearby.isEmpty())
        assertEquals(null, links.openId)
    }

    @Test
    fun a_platform_resolves_to_its_station() {
        assertEquals(listOf("northern", "victoria", "piccadilly"), index.linksOf("9400ZZLUKSX1").lines.map { it.id })
    }

    @Test
    fun an_interchange_carries_every_member_s_lines_once_and_lists_its_members() {
        val links = index.linksOf("HUBKGX")
        assertEquals(
            listOf("northern", "victoria", "piccadilly", "great-northern", "thameslink", "southeastern"),
            links.lines.map { it.id },
        )
        assertEquals(setOf("940GZZLUKSX", "910GKNGX", "910GSTPX"), links.sameHub.map { it.id }.toSet())
        assertTrue(links.nearby.none { it.id.startsWith("HUB") })
    }

    @Test
    fun a_rail_line_s_pill_takes_the_name_riders_know_it_by() {
        // Euston's West Midlands Trains service runs as London Northwestern Railway (Codex on #664).
        val eustonRail = IndexedStation(
            "910GEUSTON", "London Euston Rail Station", listOf("national-rail"), "HUBEUS", 51.52818, -0.13379,
            mapOf("national-rail" to listOf("west-midlands-trains")),
        )
        val withRail = StationIndex(listOf(eustonRail), lineNames = mapOf("west-midlands-trains" to "West Midlands Trains"))
        assertEquals("London Northwestern Railway", withRail.linksOf("910GEUSTON").lines.single().name)
    }

    @Test
    fun an_interchange_member_named_as_the_stop_is_kept() {
        // Balham's tube and rail stations clean to one name; the rail one is still its own station (Codex on #664).
        val tube = IndexedStation("940GZZLUBLM", "Balham Underground Station", listOf("tube"), "HUBBAL", 51.44315, -0.15255)
        val rail = IndexedStation("910GBALHAM", "Balham Rail Station", listOf("national-rail"), "HUBBAL", 51.44331, -0.15255)
        val links = StationIndex(listOf(tube, rail)).linksOf("940GZZLUBLM")
        assertEquals(listOf("910GBALHAM"), links.sameHub.map { it.id })
        assertEquals("Balham", links.sameHub.single().name)
        assertEquals(listOf("national-rail"), links.sameHub.single().modes)
    }

    @Test
    fun a_stop_the_index_does_not_hold_links_nowhere() {
        assertSame(StopLinks.NONE, index.linksOf("490000000X"))
    }

    @Test
    fun a_station_in_no_interchange_lists_none_and_nothing_nearby_beyond_the_limit() {
        val links = index.linksOf("940GZZLUWLO")
        assertTrue(links.sameHub.isEmpty())
        assertTrue(links.nearby.isEmpty())
        assertEquals(listOf("bakerloo"), links.lines.map { it.id })
        // No line name in the index: its id stands in.
        assertEquals("bakerloo", links.lines.single().name)
    }
}
