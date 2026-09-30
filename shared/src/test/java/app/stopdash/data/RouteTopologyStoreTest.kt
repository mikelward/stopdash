package app.stopdash.data

import app.stopdash.domain.LineRoute
import app.stopdash.domain.routePatternsOf
import app.stopdash.domain.withLive
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the **bundled** `route_topology.json` — that it parses and, on real TfL naptanIds,
 * decides the Northern-line merges correctly. This is the guard against a broken regeneration:
 * a malformed asset, or one that no longer distinguishes the two trunks, would fail here rather
 * than silently shipping a wrong (or absent) merge. Reads the real asset file directly (an
 * Android asset isn't on the unit-test classpath), so no Robolectric or Context is needed.
 */
class RouteTopologyStoreTest {
    private fun bundled() = RouteTopologyStore.parse(assetText())

    @Test
    fun `the bundled asset merges past the junction and keeps the branch at it`() {
        val topology = bundled()

        // Highgate → High Barnet (past the northern junction, on the single shared branch): the
        // two trunks have physically joined, so the rows merge into one line with no label.
        assertNull(topology.grouping("northern", HIGHGATE, "High Barnet", "Bank").label)
        assertNull(topology.grouping("northern", HIGHGATE, "High Barnet", "Charing X").label)
        assertEquals(
            topology.grouping("northern", HIGHGATE, "High Barnet", "Bank").mergeKey,
            topology.grouping("northern", HIGHGATE, "High Barnet", "Charing X").mergeKey,
        )

        // Camden Town → High Barnet: the junction itself — a Bank train and a Charing Cross train
        // reach it by different approaches, so the branch stays, each row labeled (the rider still
        // picks a trunk/platform), even though both go to High Barnet the same way onward.
        assertEquals("Bank", topology.grouping("northern", CAMDEN, "High Barnet", "Bank").label)
        assertEquals("Charing X", topology.grouping("northern", CAMDEN, "High Barnet", "Charing X").label)
        assertNotEquals(
            topology.grouping("northern", CAMDEN, "High Barnet", "Bank").mergeKey,
            topology.grouping("northern", CAMDEN, "High Barnet", "Charing X").mergeKey,
        )

        // High Barnet → Morden: the trunks diverge ahead (which central stations you pass) — kept.
        assertEquals("Bank", topology.grouping("northern", HIGH_BARNET, "Morden", "Bank").label)
        assertEquals("Charing X", topology.grouping("northern", HIGH_BARNET, "Morden", "Charing X").label)

        // Euston (a trunk stop, on both branches): kept both ways.
        assertEquals("Bank", topology.grouping("northern", EUSTON, "High Barnet", "Bank").label)
        assertEquals("Charing X", topology.grouping("northern", EUSTON, "Morden", "Charing X").label)

        // Kennington → Battersea Power: TfL tags these trains "via Charing Cross", but the bundled
        // Battersea patterns carry no via, so the branch matches no serving pattern → the label is
        // kept raw ("Charing X" is the trunk it runs — redundant but not wrong; maintainer's call).
        assertEquals("Charing X", topology.grouping("northern", KENNINGTON, "Battersea Power", "Charing X").label)
    }

    @Test
    fun `a line with any invalid pattern is disabled, not silently half-loaded`() {
        // One valid Bank pattern and one invalid (no stops). Dropping only the bad route would
        // leave the leg looking single-path and merge/drop wrongly, so the whole line is disabled
        // and every arrival on it keeps TfL's raw branch.
        val json = """
            {"version":1,"lines":{"northern":[
              {"branch":"Bank","stops":["940GZZLUCTN","940GZZLUEUS","940GZZLUKNG","940GZZLUMDN"],
               "endA":"Camden Town","endB":"Morden"},
              {"branch":"Charing X","stops":[],"endA":"Camden Town","endB":"Morden"}
            ]}}
        """.trimIndent()
        val topology = RouteTopologyStore.parse(json)
        // Line disabled → unknown → raw label kept, nothing merged.
        val g = topology.grouping("northern", "940GZZLUEUS", "Morden", "Bank")
        assertEquals("Bank", g.label)
        assertEquals("raw:Bank", g.mergeKey)
    }

    @Test
    fun `a version the build does not understand falls back to empty`() {
        val bumped = assetText().replaceFirst("\"version\":1", "\"version\":2")
        assertNotEquals("test asset must contain version:1", assetText(), bumped)
        // An unknown version resolves every branch as raw (nothing merged), the safe fallback.
        assertEquals("Bank", RouteTopologyStore.parse(bumped).grouping("northern", CAMDEN, "High Barnet", "Bank").label)
    }

    @Test
    fun `routes read back from the bundled asset derive the same patterns, so a refresh changes nothing`() {
        // TfL's Route/Sequence names each route "A  &harr;  B  via X" and lists it both ways; the
        // asset was made from exactly that, so the same routes read back must give the same lines.
        val topology = bundled()
        for ((lineId, patterns) in topology.patternsByLine) {
            val routes = patterns.flatMap { p ->
                val name = "${p.endA}  &harr;  ${p.endB}" + (p.branch?.let { "  via ${if (it == "Charing X") "Charing Cross" else it}" } ?: "")
                listOf(LineRoute(name, p.stops, "inbound"), LineRoute(name, p.stops.reversed(), "outbound"))
            }
            val derived = routePatternsOf(routes)
            assertEquals(lineId, patterns, derived)
            // And they stand in for the bundled line, unchanged.
            assertEquals(lineId, patterns, topology.withLive(mapOf(lineId to derived!!)).patternsByLine[lineId])
        }
    }

    private fun assetText(): String {
        val file = File("src/main/assets/route_topology.json")
        assertTrue("bundled asset present at ${file.absolutePath}", file.exists())
        return file.readText()
    }

    private companion object {
        const val HIGH_BARNET = "940GZZLUHBT"
        const val HIGHGATE = "940GZZLUHGT"
        const val CAMDEN = "940GZZLUCTN"
        const val EUSTON = "940GZZLUEUS"
        const val KENNINGTON = "940GZZLUKNG"
    }
}
