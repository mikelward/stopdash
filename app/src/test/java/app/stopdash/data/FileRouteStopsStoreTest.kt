package app.stopdash.data

import app.stopdash.domain.LineRef
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteStopsStore
import app.stopdash.domain.StopLocation
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic stops and the obviously-fake (51.5, -0.12) only. */
class FileRouteStopsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val at = Instant.parse("2026-09-24T08:00:00Z")

    private val contents = RouteStopsStore.Contents(
        sequences = mapOf(
            "43/inbound" to RouteStopsStore.Timed(
                at,
                LineSequence(
                    routes = listOf(LineRoute("Park ↔ Hill", listOf("490000001A", "490000002B"), direction = "inbound")),
                    stopNames = mapOf("490000001A" to "Park", "490000002B" to "Hill"),
                    stopLines = mapOf("490000002B" to listOf(LineRef("northern", "Northern", "tube"))),
                    stopPositions = mapOf("490000001A" to (51.5 to -0.12)),
                    stopAreas = mapOf("490000001A" to "490G00000001"),
                    stopHubs = mapOf("490000001A" to "HUBEXA"),
                ),
            ),
        ),
        poles = mapOf(
            "490G00000001" to RouteStopsStore.Timed(
                at,
                listOf(
                    StopLocation(
                        "490000001A", "Park", 51.5, -0.12, listOf(LineRef("43", "43", "bus")),
                        clusterId = "490G00000001", stopLetter = "A", bearing = "N", towards = "Hill",
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `routes and poles round-trip through the file`() {
        val file = File(tmp.root, "route-stops.json")
        FileRouteStopsStore(file).save(contents)
        assertEquals(contents, FileRouteStopsStore(file).load())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun `no file loads as empty`() {
        assertEquals(RouteStopsStore.Contents(), FileRouteStopsStore(File(tmp.root, "route-stops.json")).load())
    }

    @Test
    fun `an unparseable file loads as empty and is deleted`() {
        val file = File(tmp.root, "route-stops.json").apply { writeText("not json") }
        val warnings = mutableListOf<String>()
        assertEquals(RouteStopsStore.Contents(), FileRouteStopsStore(file) { warnings += it }.load())
        assertFalse(file.exists())
        assertTrue(warnings.single().startsWith("route cache unparseable"))
    }

    @Test
    fun `a file from another version loads as empty and is deleted`() {
        val file = File(tmp.root, "route-stops.json").apply { writeText("""{"version":99,"sequences":[]}""") }
        assertEquals(RouteStopsStore.Contents(), FileRouteStopsStore(file).load())
        assertFalse(file.exists())
    }

    @Test
    fun `a leftover temp file from an interrupted save is deleted on load`() {
        val file = File(tmp.root, "route-stops.json")
        val leftover = File(file.path + ".tmp").apply { writeText("{}") }
        FileRouteStopsStore(file).load()
        assertFalse(leftover.exists())
    }
}
