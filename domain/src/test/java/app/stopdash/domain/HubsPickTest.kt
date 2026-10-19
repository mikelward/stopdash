package app.stopdash.domain

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a pick is, read back ([Hubs.picked]) and arrived at ([Hubs.idsOf]). Synthetic records around
 * the obviously-fake (51.5, -0.12); never a real stop.
 */
class HubsPickTest {
    private val index = StationIndex(
        listOf(
            // An interchange of three stations: Example Cross's rail, Example Pancras's two rail records,
            // and a tube station named for both, which search folds into the Example Cross row.
            IndexedStation("910GEXCROSS", "Example Cross", listOf("national-rail"), "HUBEXX", 51.500, -0.120,
                icsCode = "1009101", station = "1009101:rail", modelHub = "HUBEXX"),
            IndexedStation("910GEXPANC", "Example Pancras", listOf("national-rail"), "HUBEXX", 51.501, -0.121,
                platforms = listOf("9100EXPANC1"), icsCode = "1009102", station = "1009102:rail", modelHub = "HUBEXX"),
            IndexedStation("910GEXPANCLL", "Example Pancras LL", listOf("national-rail"), "HUBEXX", 51.5015, -0.121,
                icsCode = "1009102", station = "1009102:rail", modelHub = "HUBEXX"),
            IndexedStation("940GZZLUEXC", "Example Cross Example Pancras", listOf("tube"), "HUBEXX", 51.5005, -0.1205,
                icsCode = "1009103", station = "1009103:tube", modelHub = "HUBEXX"),
            IndexedStation("HUBEXX", "Example Cross & Example Pancras", listOf("national-rail", "tube")),
            // A lone station whose forecourt poles are filed under its record.
            IndexedStation("910GVALE", "Vale", listOf("national-rail"), latitude = 51.53, longitude = -0.12,
                station = "910GVALE", modelHub = "910GVALE"),
        ),
        revision = "abc",
    )

    private val tsv = listOf(
        "# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=abc",
        "L\t1\tbus\t1",
        // A clean area's two poles, owned by Example Pancras's station.
        "S\t490000001A\tB\tExample Pancras\t51.501200\t-0.121000\t490G000001\t\tA\t\t\t1\t1009102\t490G000001\t1009102:rail\tHUBEXX",
        "S\t490000001B\tB\tExample Pancras\t51.501400\t-0.121000\t490G000001\t\tB\t\t\t1\t1009102\t490G000001\t1009102:rail\tHUBEXX",
        // An area split into two stops, at a junction of their own.
        "S\t490000005A\tB\tHigh Road\t51.520000\t-0.120000\t490G000005\t\t\t\t\t1\t\t490000005A\t\t490000005A",
        "S\t490000005B\tB\tMill Lane\t51.520300\t-0.120000\t490G000005\t\t\t\t\t1\t\t490000005B\t\t490000005A",
        // Two poles filed under Vale's station record: one of its own name, which Vale owns, and a
        // cross-street pole of another stop.
        "S\t490000009A\tB\tVale\t51.530500\t-0.120000\t910GVALE\t\t\t\t\t1\t\t490000009A\t910GVALE\t910GVALE",
        "S\t490000009B\tB\tVale/High Road\t51.530700\t-0.120000\t910GVALE\t\t\t\t\t1\t\t490000009B\t\t910GVALE",
        // A stop joined from two whole areas, either side of its road.
        "S\t490000007A\tB\tExample Green\t51.540000\t-0.120000\t490G000007\t\t\t\t\t1\t\t490G000007\t\t490G000007",
        "S\t490000007B\tB\tExample Green\t51.540200\t-0.120000\t490G000008\t\t\t\t\t1\t\t490G000007\t\t490G000007",
    ).joinToString("\n")

    private val hubs = Hubs.of(index, BundledStops.read(BufferedReader(StringReader(tsv))))!!

    @Test
    fun `search lists an interchange's station names with the records behind each`() {
        val rows = index.memberRows.getValue("HUBEXX")
        assertEquals(listOf("Example Cross", "Example Pancras"), rows.map { it.match.name })
        assertEquals(listOf("910GEXCROSS", "940GZZLUEXC"), rows[0].records)
        assertEquals(listOf("910GEXPANC", "910GEXPANCLL"), rows[1].records)
        assertEquals("the rows search shows are these", rows.map { it.match }, index.hubNames)
    }

    @Test
    fun `the user's own bus stop rows are their stop's own pole, so one stop is one row`() {
        val bus = listOf("bus")
        assertEquals(StationMatch("490000001A", "Example Pancras", bus, level = Pick.Level.STOP), hubs.asStopRow(StationMatch("490G000001", "Example Pancras", bus)))
        assertEquals("490000001A", hubs.asStopRow(StationMatch("490000001B", "Example Pancras", bus)).id)
        // A stop joined from two areas: either area, either pole.
        assertEquals("490000007A", hubs.asStopRow(StationMatch("490G000008", "Example Green", bus)).id)
        assertEquals("490000007A", hubs.asStopRow(StationMatch("490000007B", "Example Green", bus)).id)
        // A split area is the stop of the pole carrying the row's name.
        assertEquals("490000005B", hubs.asStopRow(StationMatch("490G000005", "Mill Lane", bus)).id)
        // A station, an interchange's station name, or an id the bundle doesn't hold stays as it is.
        val station = StationMatch("910GEXPANC", "Example Pancras", listOf("national-rail"))
        assertEquals(station, hubs.asStopRow(station))
        val nameRow = StationMatch("HUBEXX", "Example Pancras", listOf("national-rail"), lead = listOf("national-rail"))
        assertEquals(nameRow, hubs.asStopRow(nameRow))
        val stranger = StationMatch("490099999X", "Elsewhere", bus)
        assertEquals(stranger, hubs.asStopRow(stranger))
        // A bus stop shown under the station record its poles are filed under is the stop of its named
        // pole; the record's own rail row is the station.
        assertEquals("490000009B", hubs.asStopRow(StationMatch("910GVALE", "Vale/High Road", bus)).id)
        assertEquals(Pick.Level.STOP, hubs.asStopRow(StationMatch("910GVALE", "Vale", bus)).level)
        val vale = StationMatch("910GVALE", "Vale", listOf("national-rail"))
        assertEquals(vale, hubs.asStopRow(vale))
    }

    @Test
    fun `two recent picks at one bus stop list once, as the newer, and clearing it takes both`() {
        val bus = listOf("bus")
        val newer = SearchEntry.Stop(StationMatch("490000007B", "Example Green", bus))
        val older = SearchEntry.Stop(StationMatch("490G000007", "Example Green", bus))
        val other = SearchEntry.Stop(StationMatch("490000001A", "Example Pancras", bus))
        val recent = listOf(newer, older, other)
        // Shown lately at that stop too, by its other area: not listed again.
        val known = listOf(StationMatch("490G000008", "Example Green", bus))
        val yours = YourStops.of(emptyList(), emptyList(), recent, known, asRow = hubs::asStopRow)
        assertEquals(listOf("490000007B", "490000001A"), yours.recent.map { it.id })
        assertEquals(listOf(newer, other), yours.recentPicks)
        assertEquals(emptyList<StationMatch>(), yours.known)
        assertEquals(listOf("490000007B", "490000001A"), yours.all.map { it.id })
        // Without the model, each is its own row.
        assertEquals(3, YourStops.of(emptyList(), emptyList(), recent, emptyList()).recent.size)
        assertEquals(listOf(other), RecentStations.remove(recent, newer, hubs::asStopRow))
        assertEquals(listOf(older, other), RecentStations.remove(recent, newer))
        // Picked again by its other pole on a full list: the older pick at that stop goes, not the oldest place.
        val full = listOf(older) + (1..7).map { SearchEntry.Stop(StationMatch("940GZZLUX$it", "Example $it", listOf("tube"))) }
        val added = RecentStations.add(full, newer, max = 8, asRow = hubs::asStopRow)
        assertEquals(listOf(newer) + full.drop(1), added)
        // A pair an older build kept at one stop is one entry once anything else is picked: the full list
        // keeps its oldest distinct place.
        val pair = listOf(newer, older) + (1..6).map { SearchEntry.Stop(StationMatch("940GZZLUX$it", "Example $it", listOf("tube"))) }
        val gallery = SearchEntry.Place(PlaceHit("Sample Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE))
        assertEquals(listOf(gallery, newer) + pair.drop(2), RecentStations.add(pair, gallery, max = 8, asRow = hubs::asStopRow))
    }

    @Test
    fun `a saved place in a split area lists as the stop of the pole it was saved at`() {
        val bus = listOf("bus")
        // Saved at the area, named as no pole is: the pole it was saved at decides, not the area's lowest pole.
        val area = StationMatch("490G000005", "Example Junction", bus)
        assertEquals("490000005B", YourStops.rowAt(area, "490000005B", hubs::asStopRow).id)
        assertEquals("490000005A", YourStops.rowAt(area, "", hubs::asStopRow).id)
        // A pole the bundle doesn't hold leaves the area to decide; with no model the area stays.
        assertEquals("490000005A", YourStops.rowAt(area, "490099999X", hubs::asStopRow).id)
        assertEquals(area, YourStops.rowAt(area, "490000005B") { it })
        val ride = FavoriteJourney(
            from = JourneyEnd("490000005B", "Example Junction", areaId = "490G000005"),
            to = JourneyEnd("490000001A", "Example Pancras", areaId = "490G000001"),
            lineId = "1",
            mode = "bus",
        )
        assertEquals(listOf("490000005B", "490000001A"), YourStops.of(listOf(ride), emptyList(), emptyList(), emptyList(), asRow = hubs::asStopRow).favorites.map { it.id })
    }

    @Test
    fun `a favorite ride's end and a starred place at one bus stop are one search row, and a recent pick there leads`() {
        val bus = listOf("bus")
        val ride = FavoriteJourney(
            from = JourneyEnd("490000007A", "Example Green", areaId = "490G000007"),
            to = JourneyEnd("490000001A", "Example Pancras", areaId = "490G000001"),
            lineId = "1",
            mode = "bus",
        )
        // The star's place was recorded by the other side's stop area.
        val starred = listOf(StationMatch("490G000008", "Example Green", bus))
        val yours = YourStops.of(listOf(ride), starred, recent = emptyList(), known = emptyList(), asRow = hubs::asStopRow)
        assertEquals(listOf("490000007A", "490000001A"), yours.favorites.map { it.id })
        assertEquals(listOf("490G000007", "490G000001", "490G000008"), YourStops.of(listOf(ride), starred, emptyList(), emptyList()).favorites.map { it.id })
        // Picked lately by the pole across the road: listed once, as the pick, which keeps its own id.
        val picked = YourStops.of(listOf(ride), starred, recent = listOf(SearchEntry.Stop(StationMatch("490000007B", "Example Green", bus))), known = emptyList(), asRow = hubs::asStopRow)
        assertEquals(listOf("490000007B"), picked.recent.map { it.id })
        assertEquals(listOf("490000001A"), picked.favorites.map { it.id })
    }

    @Test
    fun `a stop is looked up by its whole areas and its poles outside them, a station by every record`() {
        // One area, both its poles: one request, whichever id the row carries.
        assertEquals(listOf("490G000001"), hubs.lookupIdsOf("490000001A"))
        assertEquals(listOf("490G000001"), hubs.lookupIdsOf("490G000001"))
        // Joined from two areas: each area, so the pole across the road is asked for too.
        assertEquals(listOf("490G000007", "490G000008"), hubs.lookupIdsOf("490000007B"))
        // A pole filed under a station record is asked for itself, never the station.
        assertEquals(listOf("490000009A"), hubs.lookupIdsOf("490000009A"))
        // A pole of a split area is its own stop; the split area names no one stop, so the caller asks it alone.
        assertEquals(listOf("490000005A"), hubs.lookupIdsOf("490000005A"))
        assertEquals(emptyList<String>(), hubs.lookupIdsOf("490G000005"))
        // A station is the id asked, then every record of it.
        assertEquals(listOf("910GEXPANCLL", "910GEXPANC"), hubs.lookupIdsOf("910GEXPANCLL"))
        assertEquals(emptyList<String>(), hubs.lookupIdsOf("HUBEXX"))
        assertEquals(emptyList<String>(), hubs.lookupIdsOf("490099999X"))
    }

    @Test
    fun `an interchange saved from a station's own row reads as that station, by its first record`() {
        assertEquals(Pick("910GEXPANC", "Example Pancras", Pick.Level.STATION), hubs.picked("HUBEXX", "Example Pancras"))
    }

    @Test
    fun `one saved from a row folded from two stations' names reads as the hub, and any other name too`() {
        assertEquals(Pick("HUBEXX", "Example Cross", Pick.Level.HUB), hubs.picked("HUBEXX", "Example Cross"))
        assertEquals(
            Pick("HUBEXX", "Example Cross & Example Pancras", Pick.Level.HUB),
            hubs.picked("HUBEXX", "Example Cross & Example Pancras"),
        )
    }

    @Test
    fun `a row saved at an interchange's station name reads as the row now carries it`() {
        val saved = StationMatch("HUBEXX", "Example Pancras", listOf("national-rail"), lead = listOf("national-rail"))
        assertEquals("the station, by its lead record, as search lists it now", index.memberRows.getValue("HUBEXX")[1].match, hubs.resolved(saved))
        val folded = StationMatch("HUBEXX", "Example Cross", listOf("national-rail", "tube"), lead = listOf("national-rail", "tube"))
        assertEquals(folded.copy(level = Pick.Level.HUB), hubs.resolved(folded))
        val station = StationMatch("910GEXPANC", "Example Pancras", listOf("national-rail"))
        assertEquals("any other row is itself", station, hubs.resolved(station))
        assertEquals(
            PendingEnd.Station("910GEXPANC", "Example Pancras", Pick.Level.STATION),
            hubs.resolved(PendingEnd.Station("HUBEXX", "Example Pancras")),
        )
        val picked = PendingEnd.Station("HUBEXX", "Example Pancras", Pick.Level.HUB)
        assertEquals("an end that says its level is as saved", picked, hubs.resolved(picked))
    }

    @Test
    fun `a station record a stop's poles are filed under reads as the stop when the entry says so`() {
        assertEquals("by the starred pole", Pick("490000009B", "Vale", Pick.Level.STOP), hubs.picked("910GVALE", "Vale", stopId = "490000009B"))
        assertEquals("by a starred pole filed elsewhere since", Pick("490000005B", "Vale", Pick.Level.STOP), hubs.picked("910GVALE", "Vale", stopId = "490000005B"))
        assertEquals("by a starred pole the bundle dropped", Pick("490099999X", "Vale", Pick.Level.STOP), hubs.picked("910GVALE", "Vale", stopId = "490099999X"))
        assertEquals("a station's own id as the stop id is the station", Pick.Level.STATION, hubs.picked("910GVALE", "Vale", stopId = "910GVALE").level)
        assertEquals("by a bus-only mode", Pick("490000009A", "Vale", Pick.Level.STOP), hubs.picked("910GVALE", "Vale", modes = listOf("bus")))
        assertEquals(
            "by a pole's name that isn't the record's",
            Pick("490000009B", "Vale/High Road", Pick.Level.STOP),
            hubs.picked("910GVALE", "Vale/High Road"),
        )
    }

    @Test
    fun `and as the station, with its same-named stop, when the entry kept only an id and a name`() {
        val pick = hubs.picked("910GVALE", "Vale")
        assertEquals(Pick("910GVALE", "Vale", Pick.Level.STATION, extraStopId = "490000009A"), pick)
        assertEquals("not part of its identity", "station:910GVALE", pick.key)
        assertNull("a rail row's own pick keeps no stop", hubs.picked("910GVALE", "Vale", modes = listOf("national-rail")).extraStopId)
    }

    @Test
    fun `a stop area reads as its stop, by a pole, and a split one by the stop of the saved name`() {
        assertEquals(Pick("490000001A", "Example Pancras", Pick.Level.STOP), hubs.picked("490G000001", "Example Pancras"))
        assertEquals(Pick("490000005B", "Mill Lane", Pick.Level.STOP), hubs.picked("490G000005", "Mill Lane"))
        assertEquals("a name no pole carries: the lowest-id pole's", Pick("490000005A", "Renamed", Pick.Level.STOP), hubs.picked("490G000005", "Renamed"))
        assertEquals(Pick("490000005B", "Mill Lane", Pick.Level.STOP), hubs.picked("490000005B", "Mill Lane"))
    }

    @Test
    fun `a station record or platform reads as its station, and an unbundled id keeps its id`() {
        assertEquals(Pick.Level.STATION, hubs.picked("910GEXPANCLL", "Example Pancras").level)
        assertEquals(Pick.Level.STATION, hubs.picked("9100EXPANC1", "Example Pancras").level)
        assertEquals(Pick("490099999X", "Gone", Pick.Level.STOP), hubs.picked("490099999X", "Gone"))
        assertEquals(Pick("HUBGONE", "Gone", Pick.Level.HUB), hubs.picked("HUBGONE", "Gone"))
        assertEquals(Pick("910GGONE", "Gone", Pick.Level.STATION), hubs.picked("910GGONE", "Gone"))
        assertEquals("a bus area the bundle dropped", Pick("490G000099", "Gone", Pick.Level.STOP), hubs.picked("490G000099", "Gone"))
        assertEquals("another region's", Pick("037G00001", "Gone", Pick.Level.STOP), hubs.picked("037G00001", "Gone"))
        assertEquals("saved as a bus stop", Pick("GONE1", "Gone", Pick.Level.STOP), hubs.picked("GONE1", "Gone", modes = listOf("bus")))
    }

    @Test
    fun `a station's records are every record of it, the lead first, from any of its ids`() {
        assertEquals(listOf("910GEXPANC", "910GEXPANCLL"), hubs.recordsOf("910GEXPANCLL"))
        assertEquals("from a platform", listOf("910GEXPANC", "910GEXPANCLL"), hubs.recordsOf("9100EXPANC1"))
        assertEquals("a hub is no station", emptyList<String>(), hubs.recordsOf("HUBEXX"))
        assertEquals(emptyList<String>(), hubs.recordsOf("910GGONE"))
    }

    @Test
    fun `a station is arrived at by its records, their platforms and the bus stops it owns`() {
        assertEquals(
            mapOf(
                "910GEXPANC" to "1009102:rail", "9100EXPANC1" to "1009102:rail", "910GEXPANCLL" to "1009102:rail",
                "490G000001" to "490G000001", "490000001A" to "490G000001", "490000001B" to "490G000001",
            ),
            hubs.idsOf(Pick("910GEXPANCLL", "Example Pancras", Pick.Level.STATION)),
        )
    }

    @Test
    fun `a trip's arrivals are its destination's ids at the level it was picked at`() {
        // St Pancras's low level picked as a station: the station's every record and its own bus stop.
        val station = hubs.idsOf(Pick("910GEXPANCLL", "Example Pancras", Pick.Level.STATION))
        assertEquals(station, hubs.arrivalIdsOf("910GEXPANCLL", "Example Pancras", Pick.Level.STATION))
        // A row saved without its level reads as its pick would: the record is the station.
        assertEquals(station, hubs.arrivalIdsOf("910GEXPANCLL", "Example Pancras", null))
        // Never the rest of the interchange.
        assertNull(hubs.arrivalIdsOf("910GEXPANC", "Example Pancras", Pick.Level.STATION)!!["910GEXCROSS"])
        // A bus stop by either pole: its poles and its area.
        assertEquals(
            mapOf("490G000001" to "490G000001", "490000001A" to "490G000001", "490000001B" to "490G000001"),
            hubs.arrivalIdsOf("490000001B", "Example Pancras", Pick.Level.STOP),
        )
        // An interchange is still looked up member by member, and an id the bundle doesn't hold is itself.
        assertNull(hubs.arrivalIdsOf("HUBEXX", "Example Cross & Example Pancras", Pick.Level.HUB))
        assertNull(hubs.arrivalIdsOf("HUBEXX", "Example Cross & Example Pancras", null))
        assertEquals(mapOf("910GGONE" to "910GGONE"), hubs.arrivalIdsOf("910GGONE", "Gone", null))
    }

    @Test
    fun `a stop by its poles and whole areas, never an area it shares with another stop`() {
        assertEquals(mapOf("490000005B" to "490000005B"), hubs.idsOf(Pick("490000005B", "Mill Lane", Pick.Level.STOP)))
        assertEquals("490G000001", hubs.idsOf(Pick("490000001B", "Example Pancras", Pick.Level.STOP))["490G000001"])
    }

    @Test
    fun `a hub by every station and stop in it`() {
        val ids = hubs.idsOf(Pick("HUBEXX", "Example Cross & Example Pancras", Pick.Level.HUB))
        assertEquals(setOf("1009101:rail", "1009102:rail", "1009103:tube", "490G000001"), ids.values.toSet())
        assertEquals("1009103:tube", ids["940GZZLUEXC"])
        // A junction is a hub of its stops, named after its anchor's id.
        assertEquals(
            setOf("490000005A", "490000005B"),
            hubs.idsOf(Pick("490000005A", "High Road", Pick.Level.HUB)).values.toSet(),
        )
    }

    @Test
    fun `a station's extra stop joins it, an unbundled pick is only itself, and a place is none`() {
        val vale = hubs.idsOf(Pick("910GVALE", "Vale", Pick.Level.STATION, extraStopId = "490000009A"))
        assertEquals("490000009A", vale["490000009A"])
        assertTrue("the record too", "910GVALE" in vale)
        assertEquals(
            "an extra stop a later bundle dropped is still itself",
            "490099999X",
            hubs.idsOf(Pick("910GVALE", "Vale", Pick.Level.STATION, extraStopId = "490099999X"))["490099999X"],
        )
        assertEquals(mapOf("910GGONE" to "910GGONE"), hubs.idsOf(Pick("910GGONE", "Gone", Pick.Level.STATION)))
        assertEquals(emptyMap<String, String>(), hubs.idsOf(Pick("place:1", "Home", Pick.Level.PLACE, latitude = 51.5, longitude = -0.12)))
    }

    @Test
    fun `a level is saved as its code and read back, and an unknown code is none`() {
        Pick.Level.entries.forEach { assertEquals(it, Pick.Level.ofCode(it.code)) }
        assertNull(Pick.Level.ofCode("junction"))
        assertNull(Pick.Level.ofCode(null))
    }
}
