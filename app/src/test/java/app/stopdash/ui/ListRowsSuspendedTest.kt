package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PartClosure
import app.stopdash.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A train headed into a stretch TfL says has no service is grayed once in the board's build ([listRowsOf]),
 * so the list, the journey cards and a platform view all show it so (maintainer, 2026-10-20). Made-up stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class ListRowsSuspendedTest {
    private val now: Instant = Instant.parse("2026-10-20T08:00:00Z")
    private val stopId = "a1"

    private val route = LineSequence(
        listOf(
            LineRoute("South End - North End", listOf("s1", "a1", "a2", "n1"), "inbound"),
            LineRoute("North End - South End", listOf("n1", "a2", "a1", "s1"), "outbound"),
        ),
        mapOf("s1" to "South End", "a1" to "Alpha", "a2" to "Beta", "n1" to "North End"),
    )
    private val words = "Tube Line: No service between South End and Beta northbound only."
    private val suspended = LineStatus(
        "tl", 3, "Part Suspended", words, soleAlert = true,
        closures = listOf(PartClosure(3, "Part Suspended", words, listOf(listOf("s1", "a1", "a2")))),
    )

    private fun train(direction: String, destination: String, inSeconds: Long) =
        Departure("tl", "Tube Line", direction, destination, null, now.plusSeconds(inSeconds), "tube")

    private fun rows(routes: Map<String, LineSequence?>) = listRowsOf(
        DeparturesUiState.Loaded(
            stops = listOf(
                StopArrivals(
                    stopId, "Alpha", listOf(train("inbound", "North End", 120), train("outbound", "South End", 180)),
                    fetchedAt = now, lines = listOf(LineRef("tl", "Tube Line", "tube")),
                ),
            ),
            fetchedAt = now,
            lineStatuses = mapOf("tl" to suspended),
            determinedLineIds = setOf("tl"),
        ),
        now,
        stopDistanceMeters = mapOf(stopId to 100.0),
        dismissed = emptySet(),
        hiddenModes = emptySet(),
        alertSequences = routes,
        journeyDestinationStops = emptyList(),
    )

    private fun stuck(rows: List<app.stopdash.domain.DepartureRow>) =
        rows.filter { it.lineId == "tl" }.flatMap { it.upcoming }.associate { it.direction to it.suspended }

    @Test
    fun the_list_the_cards_and_a_platform_view_gray_a_train_headed_into_the_stretch() {
        val built = rows(mapOf("tl" to route))
        val expected = mapOf("inbound" to true, "outbound" to false)
        assertEquals(expected, stuck(built.marked.rows))
        assertEquals(expected, stuck(built.forCards))
        val view = platformViewOf(
            built.source, built.now, setOf(stopId), splitKey = "", station = false, stationTitle = "Alpha",
            starred = emptySet(), dismissed = emptySet(), rows = built.marked.rows, hiddenModes = emptySet(),
            alertSequences = mapOf("tl" to route),
        )
        assertEquals(expected, stuck(view.rows))
    }

    @Test
    fun with_no_route_loaded_its_trains_stay_as_tfl_times_them() {
        val built = rows(emptyMap())
        assertEquals(mapOf("inbound" to false, "outbound" to false), stuck(built.marked.rows))
    }
}
