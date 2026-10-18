package app.stopdash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationIndexStoreTest {
    @Test
    fun `parses stations, cleans their names and keeps the hub`() {
        val index = StationIndexStore.parse(
            """
            {"version":1,"stations":[
              {"id":"940GZZLUEXA","name":"Example Underground Station","modes":["tube"],"hub":"HUBEXA"},
              {"id":"HUBEXA","name":"Example","modes":["tube"]},
              {"id":"","name":"No Id"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("940GZZLUEXA", "HUBEXA"), index.stations.map { it.id })
        assertEquals("Example", index.stations.first().name)
        assertEquals("HUBEXA", index.stations.first().interchangeId)
    }

    @Test
    fun `a station's position and tube lines are read when present, and absent on an older index`() {
        val index = StationIndexStore.parse(
            """
            {"version":1,"stations":[
              {"id":"940GZZLUEXA","name":"Example","modes":["tube"],"lat":51.5,"lon":-0.12,"modeLines":{"tube":["northern"]}},
              {"id":"910GEXAMPLE","name":"Example Rail","modes":["national-rail"],"lines":["northern"],
               "modeLines":{"national-rail":["thameslink"]},"routeEnds":{"thameslink":["910GNORTH","910GSOUTH"]}}
            ]}
            """.trimIndent(),
        )
        val (tube, rail) = index.stations
        assertEquals(51.5, tube.latitude!!, 0.0)
        assertEquals(-0.12, tube.longitude!!, 0.0)
        assertEquals(mapOf("tube" to listOf("northern")), tube.lines)
        assertEquals(null, rail.latitude)
        assertEquals(mapOf("national-rail" to listOf("thameslink")), rail.lines)
        assertEquals(mapOf("thameslink" to listOf("910GNORTH", "910GSOUTH")), rail.routeEnds)
        assertTrue(tube.routeEnds.isEmpty())
        assertTrue("an older index has no line names", index.lineNames.isEmpty())
    }

    @Test
    fun `line names are read as TfL spells them`() {
        val index = StationIndexStore.parse(
            """
            {"version":1,"stations":[{"id":"940GZZLUEXA","name":"Example","modes":["tube"]}],
             "lineNames":{"hammersmith-city":"Hammersmith & City","elizabeth":"Elizabeth line"}}
            """.trimIndent(),
        )
        assertEquals(mapOf("hammersmith-city" to "Hammersmith & City", "elizabeth" to "Elizabeth line"), index.lineNames)
    }

    @Test
    fun `an entry without an id or name is left out and counted`() {
        val index = StationIndexStore.parse(
            """{"version":1,"stations":[{"id":"910GEXA","name":"Example Rail Station"},{"id":"","name":"No Id"},{"id":"910GNONAME"}]}""",
        )
        assertEquals(listOf("910GEXA"), index.stations.map { it.id })
        assertEquals(2, index.skippedEntries)
    }

    @Test
        fun `a newer format is ignored whole`() {
        var warned = ""
        val index = StationIndexStore.parse("""{"version":2,"stations":[{"id":"X","name":"Y"}]}""") { warned = it }
        assertTrue(index.stations.isEmpty())
        assertTrue(warned.contains("version"))
    }
}
