package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Searching and ranking the bundled index, on public station names and ids. */
class StationIndexTest {
    private val index = StationIndex(
        listOf(
            IndexedStation("HUBKGX", "King's Cross St. Pancras", listOf("tube", "national-rail")),
            IndexedStation("940GZZLUKSX", "King's Cross St. Pancras", listOf("tube"), hubId = "HUBKGX"),
            IndexedStation("940GZZLUCHX", "Charing Cross", listOf("tube"), hubId = "HUBCHX"),
            IndexedStation("940GZZLUKNG", "Kennington", listOf("tube")),
            IndexedStation("940GZZLUKSH", "Kilburn High Road", listOf("overground")),
        ),
    )

    @Test
    fun `lines near a place are every line of the stations within reach of it`() {
        // King's Cross and Euston, about 750 m apart; Victoria far from both.
        val stations = StationIndex(
            listOf(
                IndexedStation("940GZZLUKSX", "King's Cross St. Pancras", latitude = 51.5308, longitude = -0.1238, lines = mapOf("tube" to listOf("northern", "victoria"))),
                IndexedStation("910GKGX", "King's Cross", latitude = 51.5320, longitude = -0.1233, lines = mapOf("national-rail" to listOf("great-northern"))),
                IndexedStation("940GZZLUEUS", "Euston", latitude = 51.5282, longitude = -0.1337, lines = mapOf("tube" to listOf("northern", "victoria"))),
                IndexedStation("910GEUSTON", "Euston", latitude = 51.5281, longitude = -0.1340, lines = mapOf("national-rail" to listOf("london-northwestern"))),
                IndexedStation("940GZZLUVIC", "Victoria", latitude = 51.4965, longitude = -0.1447, lines = mapOf("tube" to listOf("district"))),
                IndexedStation("940GNOWHERE", "No position", lines = mapOf("tube" to listOf("central"))),
            ),
            lineNames = mapOf("northern" to "Northern", "victoria" to "Victoria", "great-northern" to "Great Northern"),
        )
        val kingsCross = Coordinates(51.5308, -0.1238)
        assertEquals(
            listOf(LineRef("northern", "Northern", "tube"), LineRef("victoria", "Victoria", "tube"), LineRef("great-northern", "Great Northern", "national-rail")),
            stations.linesNear(listOf(kingsCross), 500),
        )
        // Two places: each one's own stations, together, a line both share once; one with no name by its id.
        val euston = Coordinates(51.5282, -0.1337)
        assertEquals(
            listOf("northern", "victoria", "great-northern", "london-northwestern"),
            stations.linesNear(listOf(kingsCross, euston), 500).map { it.id },
        )
        assertEquals("london-northwestern", stations.linesNear(listOf(euston), 500).last().name)
        assertEquals(emptyList<LineRef>(), stations.linesNear(emptyList(), 500))
    }

    @Test
    fun `lines near a place are named as riders say them`() {
        val tottenhamCourtRoad = StationIndex(
            listOf(
                IndexedStation(
                    "910GTOTCTRD", "Tottenham Court Road", latitude = 51.5165, longitude = -0.1310,
                    lines = mapOf("elizabeth-line" to listOf("elizabeth")),
                ),
            ),
            lineNames = mapOf("elizabeth" to "Elizabeth line"),
        )
        assertEquals(
            listOf(LineRef("elizabeth", "Elizabeth", "elizabeth-line")),
            tottenhamCourtRoad.linesNear(listOf(Coordinates(51.5165, -0.1310)), 100),
        )
    }

    @Test
    fun `a station inside a matched hub is folded into the hub`() {
        assertEquals(listOf("HUBKGX"), index.search("kings").map { it.id })
    }

    @Test
    fun `KX, KGX and KC all find King's Cross first`() {
        for (query in listOf("kx", "kgx", "kc")) {
            assertEquals(query, "HUBKGX", index.search(query).first().id)
        }
    }

    @Test
    fun `positionOf returns a station's own coordinate and a hub's member center`() {
        val positioned = StationIndex(
            listOf(
                IndexedStation("HUBKGX", "King's Cross St. Pancras", listOf("tube")),
                IndexedStation("940GZZLUKSX", "King's Cross St. Pancras", listOf("tube"), hubId = "HUBKGX", latitude = 51.53, longitude = -0.12),
                IndexedStation("940GNRKSX", "King's Cross", listOf("national-rail"), hubId = "HUBKGX", latitude = 51.53, longitude = -0.124),
                IndexedStation("940GZZLUOXC", "Oxford Circus", listOf("tube"), latitude = 51.5, longitude = -0.14),
            ),
        )
        // A station with its own position.
        assertEquals(Coordinates(51.5, -0.14), positioned.positionOf("940GZZLUOXC"))
        // A hub carries none; its center is the average of its positioned members.
        val hub = positioned.positionOf("HUBKGX")!!
        assertEquals(51.53, hub.latitude, 1e-9)
        assertEquals(-0.122, hub.longitude, 1e-9)
        // An id the index doesn't hold.
        assertEquals(null, positioned.positionOf("490000000A"))
    }

    @Test
    fun `CX finds Charing Cross`() {
        assertEquals("940GZZLUCHX", index.search("cx").first().id)
    }

    @Test
    fun `better tiers rank first, and a hub leads its tier`() {
        // "ki": King's Cross and Kilburn High Road start with it (the hub first), Kennington only
        // has the letters in order.
        assertEquals(listOf("HUBKGX", "940GZZLUKSH", "940GZZLUKNG"), index.search("ki").map { it.id })
    }

    @Test
    fun `TfL's extra matches slot in by their own tier`() {
        val local = index.search("kings")
        val busStop = StationMatch("490000000001A", "Kings Road", listOf("bus"))
        val unmatched = StationMatch("490000000002B", "Somewhere Else", listOf("bus"))
        val ranked = index.rank("kings", local, listOf(unmatched, busStop, StationMatch("HUBKGX", "dup")))
        assertEquals(listOf("HUBKGX", "490000000001A", "490000000002B"), ranked.map { it.id })
        assertEquals("King's Cross St. Pancras", ranked.first().name)
    }

    @Test
    fun `TfL's matches rank alongside the index's, not after them`() {
        // A TfL bus stop that starts with the query, with a shorter name than the indexed station,
        // ranks ahead of it: one ranking over both sources.
        val local = index.search("ken")
        val busStop = StationMatch("490000000003C", "Kent Road", listOf("bus"))
        assertEquals(listOf("490000000003C", "940GZZLUKNG"), index.rank("ken", local, listOf(busStop)).map { it.id })
    }

    @Test
    fun `a TfL match inside a matched interchange stays folded into it`() {
        val local = index.search("kings")
        val member = StationMatch("940GZZLUKSX", "King's Cross St. Pancras", listOf("tube"))
        assertEquals(listOf("HUBKGX"), index.rank("kings", local, listOf(member)).map { it.id })
    }

    @Test
    fun `a TfL member folds into its interchange when only TfL matched both`() {
        val hub = StationMatch("HUBKGX", "King's Cross St. Pancras", listOf("tube", "national-rail"))
        val member = StationMatch("940GZZLUKSX", "King's Cross St. Pancras", listOf("tube"))
        assertEquals(listOf("HUBKGX"), index.rank("pancras", emptyList(), listOf(member, hub)).map { it.id })
    }

    @Test
    fun `the user's stops join the index and lead their tier`() {
        val busStop = StationMatch("490000000001A", "Kennington Road", listOf("bus"))
        val yours = index.withYours(YourStops(favorites = listOf(busStop)))
        assertEquals(listOf("490000000001A", "940GZZLUKNG"), yours.search("kenn").map { it.id })
        assertEquals(listOf("940GZZLUKNG"), index.search("kenn").map { it.id })
    }

    @Test
    fun `a starred station folded into its interchange makes the interchange lead`() {
        val hubs = StationIndex(
            listOf(
                IndexedStation("HUBAAA", "Kings Place", listOf("tube")),
                IndexedStation("HUBKGX", "Kings Crossing", listOf("tube")),
                IndexedStation("940GZZLUKSX", "King's Cross St. Pancras", listOf("tube"), hubId = "HUBKGX"),
            ),
        )
        val starred = StationMatch("940GZZLUKSX", "King's Cross St. Pancras", listOf("tube"))
        assertEquals(listOf("HUBAAA", "HUBKGX"), hubs.search("kings").map { it.id })
        assertEquals(listOf("HUBKGX", "HUBAAA"), hubs.withYours(YourStops(favorites = listOf(starred))).search("kings").map { it.id })
    }

    @Test
    fun `the user's stops lead their tier by last use`() {
        val road = StationMatch("490000000001A", "Kennington Road", listOf("bus"))
        val lane = StationMatch("490000000002B", "Kennington Lane", listOf("bus"))
        // Lane opened last: it leads, then Road, then the station neither chose.
        val yours = index.withYours(YourStops(recent = listOf(lane, road)))
        assertEquals(listOf("490000000002B", "490000000001A", "940GZZLUKNG"), yours.search("kenn").map { it.id })
        val reopened = index.withYours(YourStops(recent = listOf(road, lane)))
        assertEquals(listOf("490000000001A", "490000000002B", "940GZZLUKNG"), reopened.search("kenn").map { it.id })
    }

    @Test
    fun `a stop seen lately matches but doesn't lead`() {
        val seen = StationMatch("490000000002B", "Kennington Park Road", listOf("bus"))
        val yours = index.withYours(YourStops(known = listOf(seen)))
        assertEquals(listOf("940GZZLUKNG", "490000000002B"), yours.search("kenn").map { it.id })
    }

    @Test
    fun `same-named results a street apart fold into the best-ranked one`() {
        val index = StationIndex(listOf(IndexedStation("940GZZLUEXA", "Example", listOf("tube"))))
        val local = index.search("example")
        // TfL lists the station again (lending it a position) and one stop area per stand around it.
        val remote = listOf(
            StationMatch("940GZZLUEXA", "Example", listOf("tube"), 51.5, -0.12),
            StationMatch("490G00000001", "Example", listOf("bus"), 51.5010, -0.12),
            StationMatch("490G00000002", "Example", listOf("bus"), 51.4990, -0.1205),
            // A differently named stop nearby stays its own result.
            StationMatch("490G00000003", "Example / High Road", listOf("bus"), 51.5005, -0.12),
            // A same-named stop miles away is a different place, and stays.
            StationMatch("490G00000004", "Example", listOf("bus"), 51.6, -0.12),
        )
        val ranked = index.rank("example", local, remote)
        assertEquals(listOf("940GZZLUEXA", "490G00000004", "490G00000003"), ranked.map { it.id })
        // The kept station takes on the folded stops' modes.
        assertEquals(listOf("tube", "bus"), ranked.first().modes)
    }

    @Test
    fun `a result with no position is never folded`() {
        val ranked = listOf(
            StationMatch("A", "Example", latitude = 51.5, longitude = -0.12),
            StationMatch("B", "Example"),
            StationMatch("C", "Example", latitude = 51.5, longitude = -0.12),
        )
        assertEquals(listOf("A", "B"), StationIndex.foldNeighbors(ranked).map { it.id })
    }

    @Test
    fun `a same-named result folds only within the fold radius`() {
        val kept = StationMatch("A", "Example", latitude = 51.5, longitude = -0.12)
        val near = StationMatch("B", "Example", latitude = 51.5020, longitude = -0.12) // ~222 m: folds
        val beyond = StationMatch("C", "Example", latitude = 51.5027, longitude = -0.12) // ~300 m: stays
        assertEquals(listOf("A", "C"), StationIndex.foldNeighbors(listOf(kept, near, beyond)).map { it.id })
    }

    @Test
    fun `a platform is at the station TfL lists it under, whatever code it carries`() {
        val index = StationIndex(
            listOf(
                IndexedStation("910GEXAMPLE", "Example", platforms = listOf("9100EXAMPLE1", "9100EXAMPLELL2")),
                IndexedStation("910GEXAMPLELL", "Example Low Level", platforms = listOf("9100EXAMPLELL1")),
                IndexedStation("940GZZEXA", "Example Underground", platforms = listOf("9400ZZEXA3")),
            ),
        )
        assertEquals("910GEXAMPLE", index.stationOf("9100EXAMPLE1"))
        // Listed under the main station, not the one its code names.
        assertEquals("910GEXAMPLE", index.stationOf("9100EXAMPLELL2"))
        assertEquals("910GEXAMPLELL", index.stationOf("9100EXAMPLELL1"))
        assertEquals("940GZZEXA", index.stationOf("9400ZZEXA3"))
    }

    @Test
    fun `an unlisted access area is at the listed station its code names`() {
        val index = StationIndex(listOf(IndexedStation("910GEXAMPLELL", "Example"), IndexedStation("940GZZEXA", "Example")))
        assertEquals("910GEXAMPLELL", index.stationOf("9100EXAMPLELL"))
        assertEquals("940GZZEXA", index.stationOf("9400ZZEXA"))
        // A platform's own digit names no station; nor does a stop that isn't a train's.
        assertNull(index.stationOf("9100EXAMPLELL1"))
        assertNull(index.stationOf("9100UNLISTED"))
        assertNull(index.stationOf("490000001A"))
        assertNull(index.stationOf("910GEXAMPLELL"))
        assertNull(StationIndex.EMPTY.stationOf("9100EXAMPLELL"))
    }

    @Test
    fun `a platform listed under two stations is placed only by its code`() {
        val index = StationIndex(
            listOf(
                IndexedStation("910GFIRST", "First", platforms = listOf("9100SHARED1", "9100SECOND")),
                IndexedStation("910GSECOND", "Second", platforms = listOf("9100SHARED1", "9100SECOND")),
            ),
        )
        assertNull(index.stationOf("9100SHARED1"))
        assertEquals("910GSECOND", index.stationOf("9100SECOND"))
    }

    @Test
    fun `a stop's interchange and place come by its station, or the station its platform is under`() {
        val paddington = StationIndex(
            listOf(
                IndexedStation("HUBPAD", "Paddington"),
                IndexedStation("940GZZLUPAH", "Paddington (H&C Line)", hubId = "HUBPAD", latitude = 51.5, longitude = -0.12, platforms = listOf("9400ZZLUPAH1")),
                IndexedStation("940GZZLUBST", "Baker Street", latitude = 51.52, longitude = -0.15),
            ),
        )
        assertEquals("HUBPAD", paddington.interchangeOf("940GZZLUPAH"))
        assertEquals("HUBPAD", paddington.interchangeOf("9400ZZLUPAH1"))
        assertEquals("HUBPAD", paddington.interchangeOf("HUBPAD"))
        // Both directions: a station in no interchange, and a stop the index doesn't hold, have none.
        assertEquals(null, paddington.interchangeOf("940GZZLUBST"))
        assertEquals(null, paddington.interchangeOf("490000000X"))
        assertEquals(Coordinates(51.5, -0.12), paddington.placeOf("9400ZZLUPAH1"))
        assertEquals(null, paddington.placeOf("490000000X"))
    }
}
