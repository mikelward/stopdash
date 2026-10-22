package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.domain.DestinationGroup
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopGroup
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A stop card's route rows carry their destinations' labels in the forms they shorten through, and their
 * branches' short forms, worked out with the card on the worker ([StopCard.labels]), so a row only measures
 * them. Synthetic stops only.
 */
class StopCardLabelsTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun departure(destination: String, offsetSeconds: Long) = Departure(
        lineId = "northern",
        lineName = "Northern",
        direction = "outbound",
        destination = destination,
        platform = null,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = "tube",
    )

    private fun row(directionKey: String, vararg upcoming: Departure) = DepartureRow(
        stopId = "940GA",
        stopName = "Example Stop",
        lineId = "northern",
        lineName = "Northern",
        direction = "outbound",
        directionKey = directionKey,
        destination = upcoming.firstOrNull()?.destination.orEmpty(),
        mode = "tube",
        upcoming = upcoming.toList(),
        fetchedAt = now,
    )

    private fun group(vararg rows: DepartureRow) = StopGroup("Example Stop", key = "example", showHeader = true, rows = rows.toList())

    @Test
    fun `each route row's destination comes in full, shortened and at its floor`() {
        val card = stopCard(
            group(row("outbound", departure("East Finchley", 120), departure("Morden", 240)), row("inbound")),
            RouteTopology.EMPTY,
        )
        // One route row per destination, each labeled as it shortens; a status row, with no trains, has none.
        assertEquals(
            listOf(listOf(DestinationAbbreviations.forms("East Finchley"), DestinationAbbreviations.forms("Morden")), emptyList()),
            card.labels.map { routes -> routes.map { it.forms } },
        )
        // Shortened forms, not the name over again.
        assertNotEquals("East Finchley", card.labels[0][0].forms!![1])
        // No branch, so no short form of one.
        assertEquals(listOf(null, null), card.labels[0].map { it.shortBranch })
    }

    @Test
    fun `a route row's branch comes in its short form too`() {
        val viaCross = DestinationGroup(destination = "Edgware", branch = "Charing Cross", times = emptyList())
        val label = StopCard(group(row("outbound", departure("Edgware", 120))), listOf(listOf(viaCross))).labels.single().single()
        assertEquals(DestinationAbbreviations.forms("Edgware"), label.forms)
        assertEquals("Charing X", label.shortBranch)
    }

    @Test
    fun `a destination TfL doesn't name is labeled by its direction, else left to the row`() {
        val unnamed = DestinationGroup(destination = "", branch = null, times = emptyList())
        // The direction as the cue where there's no destination ([DepartureLabels.destinationLabel]).
        assertEquals(
            DestinationAbbreviations.forms("Inbound"),
            StopCard(group(row("inbound", departure("", 120))), listOf(listOf(unnamed))).labels.single().single().forms,
        )
        // Neither: the row says "Destination unknown" itself.
        assertEquals(null, StopCard(group(row("", departure("", 120))), listOf(listOf(unnamed))).labels.single().single().forms)
    }
}
