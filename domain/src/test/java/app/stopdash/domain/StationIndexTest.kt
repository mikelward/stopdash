package app.stopdash.domain

import org.junit.Assert.assertEquals
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
    fun `a folded stop is always within a To destination's reach`() {
        // Margin for a destination measured from its stops' middle, not the search position.
        assertTrue(StationIndex.FOLD_RADIUS_METERS + 50 <= DirectTrips.DESTINATION_RADIUS_METERS)
        val kept = StationMatch("A", "Example", latitude = 51.5, longitude = -0.12)
        val near = StationMatch("B", "Example", latitude = 51.5020, longitude = -0.12) // ~222 m: folds
        val beyond = StationMatch("C", "Example", latitude = 51.5027, longitude = -0.12) // ~300 m: stays
        assertEquals(listOf("A", "C"), StationIndex.foldNeighbors(listOf(kept, near, beyond)).map { it.id })
    }
}
