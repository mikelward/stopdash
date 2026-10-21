package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChoicesTest {
    private val now: Instant = Instant.parse("2026-10-21T08:00:00Z")

    // The Northern line's High Barnet branch through Highgate: south to Morden by Bank or by Charing Cross, or to
    // Battersea by Charing Cross; north to High Barnet whichever way a train came.
    private val southbound = listOf(
        LineRoute("High Barnet &harr; Morden via Bank", listOf(HBT, HGT, CTN, EUS, BNK, KNG, MDN), "outbound"),
        LineRoute("High Barnet &harr; Morden via Charing Cross", listOf(HBT, HGT, CTN, MTC, EUS, CHX, KNG, MDN), "outbound"),
        LineRoute("High Barnet &harr; Battersea Power Station", listOf(HBT, HGT, CTN, MTC, EUS, CHX, KNG, BPS), "outbound"),
    )
    private val northern = LineSequence(
        routes = southbound + southbound.map { it.copy(stopIds = it.stopIds.reversed(), direction = "inbound") },
        stopNames = mapOf(
            HBT to "High Barnet", HGT to "Highgate", CTN to "Camden Town", MTC to "Mornington Crescent", EUS to "Euston",
            BNK to "Bank", CHX to "Charing Cross", KNG to "Kennington", MDN to "Morden", BPS to "Battersea Power Station",
        ),
    )

    private fun train(destination: String, branch: String?, inSeconds: Long, direction: String = "outbound", destinationId: String = "") =
        Departure("northern", "Northern", direction, destination, null, now.plusSeconds(inSeconds), "tube", branch, destinationId = destinationId)

    private fun rows(stopId: String, vararg departures: Departure) = DepartureRows.across(
        listOf(StopArrivals(stopId = stopId, stopName = "Highgate", departures = departures.toList(), fetchedAt = now)),
        now,
    )

    // A line's row with no train at [stopId], as a status row or a quiet one is.
    private fun empty(stopId: String, lineId: String = "northern", mode: String = "tube") = DepartureRow(
        stopId = stopId, stopName = "", lineId = lineId, lineName = lineId, direction = "", directionKey = "",
        destination = "", mode = mode, upcoming = emptyList(), fetchedAt = now,
    )

    private fun label(choice: RouteChoice) = buildString {
        append(choice.destination)
        if (choice.showBranch) append("/").append(choice.branch)
        choice.parting?.let { append(" via ").append(it) }
    }

    @Test
    fun `a line's every way on from a stop is a choice, routes sharing one way after it one choice`() {
        val choices = RouteChoices.of(northern, empty(HGT), emptyList(), null)
        // Two ways to Morden told apart by their vias; Battersea and High Barnet by their names alone. The three
        // routes north call at the same stops from here, so they're one.
        assertEquals(listOf("Morden/Bank", "Morden/Charing X", "Battersea Power Station", "High Barnet"), choices.choices.map(::label))
        assertEquals(listOf(HGT, CTN, EUS, BNK, KNG, MDN), choices.choices[0].path)
        assertEquals(listOf(HGT, HBT), choices.choices[3].path)
        assertEquals("inbound", choices.choices[3].direction)
        // No train to say which, so none is the page's.
        assertNull(choices.current)
        assertTrue(choices.choices.all { it.live == null })
    }

    @Test
    fun `the page's train picks its own choice, and each route's trains at the stop go on theirs`() {
        val board = rows(
            HGT,
            train("Morden", "Bank", 120),
            train("Battersea Power Station", "Charing X", 240),
            train("High Barnet", null, 60, direction = "inbound"),
        )
        val south = board.single { it.direction == "outbound" }
        val north = board.single { it.direction == "inbound" }
        val battersea = RouteFocus.of(DepartureRows.destinationLines(south, 3).single { it.destination == "Battersea Power Station" })
        val choices = RouteChoices.of(northern, south, board, battersea)
        // The row's own way first.
        assertEquals(listOf("Morden/Bank", "Morden/Charing X", "Battersea Power Station", "High Barnet"), choices.choices.map(::label))
        assertEquals(2, choices.current)
        assertSame(south, choices.choices[0].live?.row)
        assertEquals(RouteFocus("Morden", "Bank"), choices.choices[0].live?.focus)
        // A way with no train at the stop is still offered, by its stops alone.
        assertNull(choices.choices[1].live)
        // The other way's trains are on its choice, from their own row.
        assertSame(north, choices.choices[3].live?.row)
        // Without a tap, the soonest train is the page's.
        assertEquals(0, RouteChoices.of(northern, south, board, null).current)
    }

    @Test
    fun `a short working is a choice of its own after the line's routes, and a train ending here none`() {
        val board = rows(
            HGT,
            train("Kennington", "Charing X", 60),
            train("Morden", "Bank", 120),
            train("Highgate", null, 30, destinationId = HGT),
        )
        val south = board.single { it.direction == "outbound" }
        val choices = RouteChoices.of(northern, south, board, null)
        assertEquals(
            listOf("Morden/Bank", "Morden/Charing X", "Battersea Power Station", "Kennington", "High Barnet"),
            choices.choices.map(::label),
        )
        assertEquals(listOf(HGT, CTN, MTC, EUS, CHX, KNG), choices.choices[3].path)
        assertEquals(RouteFocus("Kennington", "Charing X"), choices.choices[3].live?.focus)
        // The soonest train that goes anywhere is the page's: the one ending here offers no way.
        assertEquals(3, choices.current)
    }

    @Test
    fun `a way a grouped train runs isn't one with no train, though only the group's soonest is followed`() {
        // Two trains to Morden with no branch named, one by Bank, one by Charing Cross: one row's one destination.
        val bank = Departure("northern", "Northern", "outbound", "Morden", null, now.plusSeconds(60), "tube", null, via = "Bank")
        val charing = Departure("northern", "Northern", "outbound", "Morden", null, now.plusSeconds(120), "tube", null, via = "Charing Cross")
        val board = rows(HGT, bank, charing)
        val south = board.single { it.direction == "outbound" }
        val choices = RouteChoices.of(northern, south, board, null).choices
        val viaBank = choices.single { it.path == listOf(HGT, CTN, EUS, BNK, KNG, MDN) }
        val viaCharing = choices.single { it.path == listOf(HGT, CTN, MTC, EUS, CHX, KNG, MDN) }
        // The soonest is followed; the other way has a train too, though the page can't follow it on its own.
        org.junit.Assert.assertNotNull(viaBank.live)
        assertEquals(false, viaBank.unfollowedTrain)
        assertEquals(null, viaCharing.live)
        assertEquals(true, viaCharing.unfollowedTrain)
        // A way no train runs is just that.
        assertEquals(false, choices.single { it.path.lastOrNull() == BPS }.unfollowedTrain)
    }

    @Test
    fun `a train whose way can't be told keeps a choice of its own, with no stops`() {
        val board = rows(HGT, train("Check Front of Train", null, 60))
        val south = board.single { it.direction == "outbound" }
        val choices = RouteChoices.of(northern, south, board, null)
        val unknown = choices.choices.single { it.destination == "Check Front of Train" }
        assertEquals(emptyList<String>(), unknown.path)
        assertEquals(choices.choices.indexOf(unknown), choices.current)
        assertEquals("outbound|live|Check Front of Train|", unknown.key)
    }

    @Test
    fun `a stop with one way on is that way, and two ways to one place by no via part by their stops`() {
        val line = LineSequence(
            routes = listOf(
                LineRoute("A &harr; Z", listOf("A", "B", "Z"), "outbound"),
                LineRoute("A &harr; Z", listOf("A", "C", "Z"), "outbound"),
                LineRoute("A &harr; Y", listOf("A", "B", "Y"), "outbound"),
                LineRoute("Y &harr; A", listOf("Y", "B", "A"), "inbound"),
            ),
            stopNames = mapOf("A" to "Alpha", "B" to "Bravo", "C" to "Charlie", "Y" to "Yankee", "Z" to "Zulu"),
        )
        val one = RouteChoices.of(line, empty("Y", "l"), emptyList(), null)
        assertEquals(listOf("Alpha"), one.choices.map(::label))
        assertEquals(0, one.current)
        assertEquals(
            listOf("Zulu via Bravo", "Zulu via Charlie", "Yankee"),
            RouteChoices.of(line, empty("A", "l"), emptyList(), null).choices.map(::label),
        )
    }

    @Test
    fun `a way a route names its via for keeps it where an unnamed route runs the same stops`() {
        val line = LineSequence(
            routes = listOf(
                LineRoute("A &harr; Z via X", listOf("A", "B", "Z"), "outbound"),
                LineRoute("A &harr; Z", listOf("A", "B", "Z"), "outbound"),
                LineRoute("A &harr; Z via Y", listOf("A", "C", "Z"), "outbound"),
            ),
            stopNames = mapOf("A" to "Alpha", "B" to "Bravo", "C" to "Charlie", "Z" to "Zulu"),
        )
        assertEquals(listOf("Zulu/X", "Zulu/Y"), RouteChoices.of(line, empty("A", "l"), emptyList(), null).choices.map(::label))
    }

    @Test
    fun `a National Rail line offers no choices`() {
        assertEquals(RouteChoices.NONE, RouteChoices.of(northern, empty(HGT, "thameslink", "national-rail"), emptyList(), null))
    }

    private companion object {
        const val HBT = "940GZZLUHBT"
        const val HGT = "940GZZLUHGT"
        const val CTN = "940GZZLUCTN"
        const val MTC = "940GZZLUMTC"
        const val EUS = "940GZZLUEUS"
        const val BNK = "940GZZLUBNK"
        const val CHX = "940GZZLUCHX"
        const val KNG = "940GZZLUKNG"
        const val MDN = "940GZZLUMDN"
        const val BPS = "940GZZBPSUST"
    }
}
