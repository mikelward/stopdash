package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopGrouping
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a platform or station view's buttons open ([platformViewOf]'s station and map stop, SPEC D8). */
class PlatformViewTest {
    private val now = Instant.parse("2026-09-24T08:00:00Z")

    private fun dep(line: String, to: String, platform: String?, mode: String = "tube") =
        Departure(line, line, "outbound", to, platform, now.plusSeconds(120), mode)

    private fun view(
        stops: List<StopArrivals>,
        ids: Set<String>,
        splitKey: String,
        station: Boolean = false,
        meters: Map<String, Double> = emptyMap(),
    ): PlatformView = platformViewOf(
        source = DeparturesUiState.Loaded(stops, now),
        now = now,
        ids = ids,
        splitKey = splitKey,
        station = station,
        stationTitle = "Example Cross",
        starred = emptySet(),
        dismissed = emptySet(),
        rows = emptyList(),
        hiddenModes = emptySet(),
        alertSequences = emptyMap(),
        stopDistanceMeters = meters,
    )

    private fun groups(stops: List<StopArrivals>) = StopGrouping.groupByStop(DepartureRows.across(stops, now))

    // One stop with two platforms, as a tube station's are.
    private val hub = StopArrivals(
        "HUB", "Example Cross",
        listOf(dep("victoria", "North End", "Northbound - Platform 1"), dep("piccadilly", "East End", "Eastbound - Platform 3")),
        fetchedAt = now,
    )

    @Test
    fun `a platform whose station holds more offers it, and its measured stop on the map`() {
        val platform = groups(listOf(hub)).first { it.qualifier != null }
        val view = view(listOf(hub), setOf("HUB"), platform.splitKey, meters = mapOf("HUB" to 120.0))
        val station = checkNotNull(view.station)
        assertEquals(stationClusterOf("", "HUB"), station.ids)
        assertEquals("Example Cross", station.title)
        assertEquals(false, station.bus)
        assertEquals("HUB" to "Example Cross", view.mapStop)
        // Not measured (off the near-me list, D1): no map.
        assertNull(view(listOf(hub), setOf("HUB"), platform.splitKey).mapStop)
    }

    @Test
    fun `the whole station offers no station, but still its map`() {
        val view = view(listOf(hub), setOf(stationClusterOf("", "HUB")), "", station = true, meters = mapOf("HUB" to 120.0))
        assertNull(view.station)
        assertEquals("HUB" to "Example Cross", view.mapStop)
    }

    @Test
    fun `a bus pole offers its place's other poles as all stops, and maps itself, not a nearer pole`() {
        fun pole(id: String, letter: String, to: String) =
            StopArrivals(id, "Example Road", listOf(dep("1", to, null, mode = "bus")), fetchedAt = now, clusterId = "G1", stopLetter = letter)
        val a = pole("POLE-A", "A", "North End")
        val b = pole("POLE-B", "B", "South End")
        val group = groups(listOf(a)).single()
        val view = view(listOf(a, b), setOf("POLE-A"), group.splitKey, meters = mapOf("POLE-A" to 50.0, "POLE-B" to 20.0))
        val station = checkNotNull(view.station)
        assertEquals("G1", station.ids)
        assertEquals(true, station.bus)
        assertEquals("POLE-A" to "Example Road", view.mapStop)
        // A lone pole is its whole place: nothing more to open.
        assertNull(view(listOf(a), setOf("POLE-A"), group.splitKey).station)
    }
}
