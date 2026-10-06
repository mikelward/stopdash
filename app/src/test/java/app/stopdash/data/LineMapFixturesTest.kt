package app.stopdash.data

import app.stopdash.domain.LineMap
import app.stopdash.domain.LineSequence
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line page's map from recorded TfL route sequences, read through the app's own parser: the
 * Northern line (two trunks, Euston on both, three northern ends), the District (five ends meeting at
 * Earl's Court) and the Circle (a loop that runs on to Hammersmith).
 */
class LineMapFixturesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun sequence(name: String): LineSequence =
        json.decodeFromString<TflRouteSequenceDto>(checkNotNull(javaClass.getResource("/fixtures/$name")).readText()).toLineSequence()

    private val northern = sequence("route_sequence_northern_outbound.json")
    private val district = sequence("route_sequence_district.json")
    private val circle = sequence("route_sequence_circle.json")

    private fun LineMap.names(filter: (LineMap.Row) -> Boolean) = rows.filter(filter).mapTo(HashSet()) { it.name }

    private fun List<LineMap.Item>.labels() = map { item ->
        when (item) {
            is LineMap.Item.Station -> item.row.name
            is LineMap.Item.Fold -> if (item.section) "[${item.ends.joinToString(" · ")}]" else "[${item.first} to ${item.last}]"
        }
    }

    @Test
    fun `the Northern line has Euston on each trunk and meets at its three junctions`() {
        val map = LineMap.of(northern)!!
        assertEquals(setOf("Finchley Central", "Camden Town", "Kennington"), map.names { it.junction })
        assertEquals(setOf("Edgware", "High Barnet", "Mill Hill East", "Morden", "Battersea Power Station"), map.names { it.end })
        assertEquals(2, map.rows.count { it.name == "Euston" })
        assertTrue(map.rows.filter { it.name == "Euston" }.none { it.junction })
    }

    @Test
    fun `with no positions recorded, the Northern line keeps TfL's order`() {
        // This recording was trimmed before the app read stations' positions, so nothing says which
        // end is north: the map runs as TfL lists the line, northbound.
        assertTrue(northern.stopPositions.isEmpty())
        assertEquals("Battersea Power Station", LineMap.of(northern)!!.rows.first().name)
    }

    @Test
    fun `today's Battersea closure folds with its branch, saying there's no service in it`() {
        // Shut both ways: TfL lists it once each way round.
        val closure = listOf(listOf("940GZZLUKNG", "940GZZNEUGST", "940GZZBPSUST"), listOf("940GZZBPSUST", "940GZZNEUGST", "940GZZLUKNG"))
        val map = LineMap.of(northern, closures = closure)!!
        assertEquals(setOf("Nine Elms", "Battersea Power Station"), map.names { it.unserved })
        val items = map.folded(emptySet())
        // Off the rider's own stops and rides, the branch it shuts folds, Kennington still on the page.
        assertTrue("Kennington" in items.labels())
        assertTrue("Nine Elms" !in items.labels())
        val branch = items.filterIsInstance<LineMap.Item.Fold>().single { it.level != null }
        assertEquals(LineMap.Level.CLOSURE, branch.level)
        assertEquals(listOf("Battersea Power Station"), branch.ends)
        assertTrue("its stations named only once it's opened", branch.unnamed)
    }

    @Test
    fun `the District runs north up, its two eastern ends meeting at Earl's Court`() {
        val map = LineMap.of(district)!!
        assertEquals(3, map.columns)
        assertEquals("Edgware Road (Circle)", map.rows.first().name)
        assertEquals(
            setOf("Edgware Road (Circle)", "Upminster", "Kensington (Olympia)", "Wimbledon", "Richmond", "Ealing Broadway"),
            map.names { it.end },
        )
        val earlsCourt = map.rows.single { it.name == "Earl's Court" }
        assertTrue(earlsCourt.junction)
        assertEquals("it parts three ways", 3, earlsCourt.bottom.count { it.from == earlsCourt.column })
        assertTrue(map.rows.single { it.name == "Turnham Green" }.junction)
    }

    @Test
    fun `with good service the District folds to its ends and junctions`() {
        assertEquals(
            listOf(
                "Edgware Road (Circle)", "[Paddington to High Street Kensington]", "Upminster", "[Upminster Bridge to Gloucester Road]",
                "Earl's Court", "Kensington (Olympia)", "[West Brompton to Wimbledon Park]", "Wimbledon",
                "[West Kensington to Stamford Brook]", "Turnham Green", "[Gunnersbury to Kew Gardens]", "Richmond",
                "[Chiswick Park to Ealing Common]", "Ealing Broadway",
            ),
            LineMap.of(district)!!.folded(emptySet()).labels(),
        )
    }

    @Test
    fun `the Circle is one rail from Edgware Road round to Edgware Road and on to Hammersmith`() {
        val map = LineMap.of(circle)!!
        assertEquals(1, map.columns)
        assertEquals("Edgware Road (Circle)", map.rows.first().name)
        assertEquals("Hammersmith (H&C)", map.rows.last().name)
        assertEquals("Edgware Road twice: no train turns there", 2, map.rows.count { it.name == "Edgware Road (Circle)" })
        assertTrue(map.rows.none { it.junction })
    }

    @Test
    fun `a starred interchange on the Circle stays out of its fold`() {
        val map = LineMap.of(circle, starred = setOf("940GZZLUKSX"))!!
        assertEquals(
            listOf("Edgware Road (Circle)", "[Paddington to Farringdon]", "King's Cross St. Pancras", "[Euston Square to Goldhawk Road]", "Hammersmith (H&C)"),
            map.folded(emptySet()).labels(),
        )
    }
}
