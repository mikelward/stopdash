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

    // From N, eastbound by E1 and E2, then parting for Ex or Ey; westbound by W1 and W2, then parting for Wx or Wy.
    private val eastWest = LineSequence(
        routes = listOf(
            LineRoute("N ↔ Ex", listOf("N", "E1", "E2", "EX"), direction = "inbound"),
            LineRoute("N ↔ Ey", listOf("N", "E1", "E2", "EY"), direction = "inbound"),
            LineRoute("N ↔ Wx", listOf("N", "W1", "W2", "WX"), direction = "outbound"),
            LineRoute("N ↔ Wy", listOf("N", "W1", "W2", "WY"), direction = "outbound"),
        ),
        stopNames = mapOf(
            "N" to "N", "E1" to "E1", "E2" to "E2", "EX" to "Ex", "EY" to "Ey",
            "W1" to "W1", "W2" to "W2", "WX" to "Wx", "WY" to "Wy",
        ),
        stopPositions = mapOf(
            "N" to (51.0 to 0.0), "E1" to (51.0 to 0.01), "E2" to (51.0 to 0.02), "EX" to (51.01 to 0.03),
            "EY" to (50.99 to 0.03), "W1" to (51.0 to -0.01), "W2" to (51.0 to -0.02), "WX" to (51.01 to -0.03),
            "WY" to (50.99 to -0.03),
        ),
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
    fun `on a ride's page the ways reaching its far end come first in their way, by stop or by stop area`() {
        val board = rows(HGT, train("Morden", "Bank", 120), train("High Barnet", null, 60, direction = "inbound"))
        val south = board.single { it.direction == "outbound" }
        // A favorite ride to Battersea (maintainer, 2026-10-21): its way first among the southbound, the line's
        // order after it, and north still after south.
        val toBattersea = RouteChoices.of(northern, south, board, null, reaching = setOf(BPS))
        assertEquals(listOf("Battersea Power Station", "Morden/Bank", "Morden/Charing X", "High Barnet"), toBattersea.choices.map(::label))
        // The page's own train still picks its choice.
        assertEquals(1, toBattersea.current)
        // Its far end by its stop area: Charing Cross's two ways, by Charing Cross to Morden and to Battersea.
        val areas = northern.copy(stopAreas = mapOf(CHX to "HUBCHX"))
        val toCharingX = RouteChoices.of(areas, south, board, null, reaching = setOf("HUBCHX"))
        assertEquals(listOf("Morden/Charing X", "Battersea Power Station", "Morden/Bank", "High Barnet"), toCharingX.choices.map(::label))
        // None: the line's own order.
        assertEquals(
            listOf("Morden/Bank", "Morden/Charing X", "Battersea Power Station", "High Barnet"),
            RouteChoices.of(northern, south, board, null).choices.map(::label),
        )
    }

    @Test
    fun `a journey card's own row beside the stop's chooses its train's way, not its place's soonest's`() {
        // Morden by Bank first, then by Charing Cross, neither with a branch, so one group for the place: a card to
        // Charing Cross keeps only the later, and its page has the card's row beside the stop's (Codex on #1049).
        val bank = Departure("northern", "Northern", "outbound", "Morden", null, now.plusSeconds(120), "tube", via = "Bank")
        val charingX = Departure("northern", "Northern", "outbound", "Morden", null, now.plusSeconds(300), "tube", via = "Charing Cross")
        val stop = rows(HGT, bank, charingX)
        val south = stop.single { it.direction == "outbound" }
        val card = south.copy(upcoming = south.upcoming.filter { it.via == "Charing Cross" })
        val focus = RouteFocus.of(DepartureRows.destinationLines(card, 3).single())
        val choices = RouteChoices.of(northern, card, stop, focus)
        assertEquals("Morden/Charing X", label(choices.choices[choices.current!!]))
        // By Bank is the stop's train, to move the page to.
        assertSame(south, choices.choices.single { label(it) == "Morden/Bank" }.live?.row)
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
    fun `each way a grouped train runs hands the page its own train, followed by its way`() {
        // Two trains to Morden with no branch named, one by Bank, one by Charing Cross: one row's one destination.
        val bank = Departure("northern", "Northern", "outbound", "Morden", null, now.plusSeconds(60), "tube", null, via = "Bank")
        val charing = Departure("northern", "Northern", "outbound", "Morden", null, now.plusSeconds(120), "tube", null, via = "Charing Cross")
        val board = rows(HGT, bank, charing)
        val south = board.single { it.direction == "outbound" }
        val choices = RouteChoices.of(northern, south, board, null)
        val viaBank = choices.choices.indexOfFirst { it.path == listOf(HGT, CTN, EUS, BNK, KNG, MDN) }
        val viaCharing = choices.choices.indexOfFirst { it.path == listOf(HGT, CTN, MTC, EUS, CHX, KNG, MDN) }
        // Each way's own train, by its way: the page can follow either apart from the other (Codex on #1038).
        assertEquals(RouteFocus("Morden", null, bank.wayKey), choices.choices[viaBank].live?.focus)
        assertEquals(RouteFocus("Morden", null, charing.wayKey), choices.choices[viaCharing].live?.focus)
        assertSame(south, choices.choices[viaCharing].live?.row)
        // A tap on the row follows the soonest, by Bank; handed the Charing Cross way, the page is on that one.
        assertEquals(viaBank, choices.current)
        assertEquals(viaCharing, RouteChoices.of(northern, south, board, choices.choices[viaCharing].live?.focus).current)
        // A way no train runs is just that.
        assertNull(choices.choices.single { it.path.lastOrNull() == BPS }.live)
    }

    @Test
    fun `a group whose trains all run one way hands the page the place, not a way`() {
        val board = rows(HGT, train("Morden", "Bank", 60), train("Morden", "Bank", 180))
        val south = board.single { it.direction == "outbound" }
        val choices = RouteChoices.of(northern, south, board, null)
        assertEquals(RouteFocus("Morden", "Bank"), choices.choices.single { label(it) == "Morden/Bank" }.live?.focus)
    }

    @Test
    fun `a train whose way can't be told keeps a choice of its own, with no stops`() {
        val board = rows(HGT, train("Check Front of Train", null, 60))
        val south = board.single { it.direction == "outbound" }
        val choices = RouteChoices.of(northern, south, board, null)
        val unknown = choices.choices.single { it.destination == "Check Front of Train" }
        assertEquals(emptyList<String>(), unknown.path)
        assertEquals(choices.choices.indexOf(unknown), choices.current)
        assertEquals("outbound|live|Check Front of Train||$CTN", unknown.key)
        // How far it goes at least, by its way's routes: all three run on to Camden Town before they part.
        assertEquals(CTN, unknown.atLeast)
        // A way's own choices make no guess.
        assertEquals(listOf<String?>(null), choices.choices.filter { it.path.isNotEmpty() }.map { it.atLeast }.distinct())
    }

    @Test
    fun `a train with no destination at a terminus goes at least as far as its routes all run`() {
        // Morden, northbound: by Bank or by Charing Cross, both through Kennington first.
        val board = rows(MDN, train("Check Front of Train", null, 60, direction = "inbound"))
        val north = board.single { it.direction == "inbound" }
        val unknown = RouteChoices.of(northern, north, board, null).choices.single { it.path.isEmpty() }
        assertEquals(KNG, unknown.atLeast)
        // With its way unknown, no guess: it could go either way.
        val anyWay = rows(MDN, train("Check Front of Train", null, 60, direction = ""))
        val blank = RouteChoices.of(northern, anyWay.single(), anyWay, null).choices.single { it.path.isEmpty() }
        assertEquals(null, blank.atLeast)
    }

    @Test
    fun `a train with no destination goes at least as far as its platform's way runs, whatever its direction`() {
        // Where TfL's direction names the other way round (as on a loop), the platform's bound decides, as a train
        // with a destination is resolved (Codex on #1045). Eastbound from N runs by E1 and E2, then parts.
        val east = Departure("northern", "Northern", "outbound", "Check Front of Train", "Eastbound - Platform 1", now.plusSeconds(60), "tube", null)
        val board = rows("N", east)
        val unknown = RouteChoices.of(eastWest, board.single(), board, null).choices.single { it.path.isEmpty() }
        assertEquals("E2", unknown.atLeast)
    }

    @Test
    fun `trains with no destination on opposite platforms keep their own guesses, whichever the page follows`() {
        // Neither has a TfL direction, so only their platforms tell their ways apart: each choice's guess is its own
        // trains', not the soonest's on any platform (Codex on #1045).
        val east = Departure("northern", "Northern", "", "Check Front of Train", "Eastbound - Platform 1", now.plusSeconds(60), "tube", null)
        val west = Departure("northern", "Northern", "", "Check Front of Train", "Westbound - Platform 2", now.plusSeconds(120), "tube", null)
        val board = rows("N", east, west)
        val westRow = board.single { row -> row.upcoming.any { it.platform == west.platform } }
        val choices = RouteChoices.of(eastWest, westRow, board, null)
        val unknown = choices.choices.filter { it.path.isEmpty() }
        assertEquals(setOf("E2", "W2"), unknown.map { it.atLeast }.toSet())
        assertEquals(2, unknown.map { it.key }.distinct().size)
        assertEquals("W2", choices.choices[choices.current!!].atLeast)
    }

    @Test
    fun `trains with no destination on opposite platforms each hand the page their own, not the sooner`() {
        // Both name the same place, so the page, following it across the stop's platforms, took the sooner (east)
        // from either chip; each hands over by its own train's way (Codex on #1045).
        val east = Departure("northern", "Northern", "", "Check Front of Train", "Eastbound - Platform 1", now.plusSeconds(60), "tube", null)
        val west = Departure("northern", "Northern", "", "Check Front of Train", "Westbound - Platform 2", now.plusSeconds(120), "tube", null)
        val board = rows("N", east, west)
        val eastRow = board.single { row -> row.upcoming.any { it.platform == east.platform } }
        val fromEast = RouteChoices.of(eastWest, eastRow, board, RouteFocus("Check Front of Train", null))
        val westChoice = fromEast.choices.indexOfFirst { it.atLeast == "W2" }
        val handed = fromEast.choices[westChoice].live!!
        assertEquals(west.wayKey, handed.focus.way)
        assertEquals(east.wayKey, fromEast.choices.single { it.atLeast == "E2" }.live?.focus?.way)
        // Handed over, the page follows the later train, west, and its chip is the page's own.
        assertSame(west, followedDeparture(handed.row, handed.focus, RouteTopology.EMPTY, board))
        val onWest = RouteChoices.of(eastWest, handed.row, board, handed.focus)
        assertEquals("W2", onWest.choices[onWest.current!!].atLeast)
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

    // A National Rail line through A: south by a stopping and a fast pattern to Zulu, one ending there under its
    // station's other id, and a stopping one to Yankee; north to November.
    private val rail = LineSequence(
        routes = listOf(
            LineRoute("November - Zulu", listOf("N", "A", "B", "C", "Z"), "outbound"),
            LineRoute("November - Zulu (fast)", listOf("N", "A", "C", "Z2"), "outbound"),
            LineRoute("November - Yankee", listOf("N", "A", "B", "Y"), "outbound"),
            LineRoute("Zulu - November", listOf("Z", "C", "B", "A", "N"), "inbound"),
        ),
        stopNames = mapOf("N" to "November", "A" to "Alpha", "B" to "Bravo", "C" to "Charlie", "Y" to "Yankee", "Z" to "Zulu", "Z2" to "Zulu"),
        stationCodes = mapOf("Z" to "ZUL", "Z2" to "ZUL"),
    )

    private fun rail(destination: String, inSeconds: Long, direction: String = "outbound") =
        Departure("southern", "Southern", direction, destination, null, now.plusSeconds(inSeconds), "national-rail")

    @Test
    fun `a National Rail line's chips are where it goes, its patterns to one station one chip`() {
        val choices = RouteChoices.of(rail, empty("A", "southern", "national-rail"), emptyList(), null)
        // Its stopping and fast patterns to Zulu are one way, whichever of its ids each ends at; each chip lists the
        // first pattern TfL lists while no train is on it.
        assertEquals(listOf("Zulu", "Yankee", "November"), choices.choices.map(::label))
        assertEquals(listOf("A", "B", "C", "Z"), choices.choices[0].path)
        // Known by where it goes, not its stops.
        assertEquals(listOf("outbound|to|ZUL", "outbound|to|Y", "inbound|to|N"), choices.choices.map { it.key })
        assertNull(choices.current)
        // A ride's far end on a pattern of its own first: Yankee's, by its stopping pattern.
        assertEquals(
            listOf("Yankee", "Zulu", "November"),
            RouteChoices.of(rail, empty("A", "southern", "national-rail"), emptyList(), null, reaching = setOf("Y")).choices.map(::label),
        )
    }

    @Test
    fun `each National Rail chip follows its place's soonest train, and the page's own picks its chip`() {
        val board = rows("A", rail("Zulu", 300), rail("Zulu", 120), rail("Yankee", 240), rail("November", 60, direction = "inbound"))
        val south = board.single { it.direction == "outbound" }
        val north = board.single { it.direction == "inbound" }
        val yankee = RouteFocus.of(DepartureRows.destinationLines(south, 3).single { it.destination == "Yankee" })
        val choices = RouteChoices.of(rail, south, board, yankee)
        // The row's own way first.
        assertEquals(listOf("Zulu", "Yankee", "November"), choices.choices.map(::label))
        assertEquals(1, choices.current)
        // A train to Zulu could take either pattern, so it goes on Zulu's chip by its name; the soonest is followed.
        assertSame(south, choices.choices[0].live?.row)
        assertEquals(RouteFocus("Zulu", null), choices.choices[0].live?.focus)
        assertSame(north, choices.choices[2].live?.row)
        // Each chip the same one with its trains as without, so a refresh keeps the one chosen.
        assertEquals(
            RouteChoices.of(rail, empty("A", "southern", "national-rail"), emptyList(), null).choices.map { it.key },
            choices.choices.map { it.key },
        )
        // Without a tap, the row's soonest train is the page's.
        assertEquals(0, RouteChoices.of(rail, south, board, null).current)
    }

    @Test
    fun `National Rail chips keep their order whichever train the page follows, as its boards name no way`() {
        // Darwin gives a train no direction, so the page opens on no way (Codex on #1095): the chips keep TfL's order,
        // not the followed train's way first, so choosing one the other way, or a refresh, never reorders them.
        val board = rows("A", rail("Zulu", 120), rail("November", 60, direction = "inbound"))
        val south = board.single { it.direction == "outbound" }
        val north = board.single { it.direction == "inbound" }
        val toZulu = RouteChoices.of(rail, south, board, RouteFocus.of(DepartureRows.destinationLines(south, 3).single()), first = "")
        val toNovember = RouteChoices.of(rail, north, board, RouteFocus.of(DepartureRows.destinationLines(north, 3).single()), first = "")
        assertEquals(listOf("Zulu", "Yankee", "November"), toZulu.choices.map(::label))
        assertEquals(toZulu.choices.map { it.key }, toNovember.choices.map { it.key })
        assertEquals(0, toZulu.current)
        assertEquals(2, toNovember.current)
    }

    @Test
    fun `a National Rail train to a place none of its line's patterns end at has a chip of its own`() {
        // Short workings: to Bravo by the stops it calls at, and to Charlie, by either pattern, by its name.
        val board = rows("A", rail("Charlie", 120), rail("Bravo", 240), rail("Zulu", 300))
        val south = board.single()
        val choices = RouteChoices.of(rail, south, board, null)
        assertEquals(listOf("Zulu", "Yankee", "Bravo", "Charlie", "November"), choices.choices.map(::label))
        assertEquals(listOf("A", "B"), choices.choices[2].path)
        assertEquals(listOf("outbound|to|B", "outbound|to|name:charlie"), choices.choices.subList(2, 4).map { it.key })
        assertTrue(choices.choices.subList(2, 4).all { it.live?.row === south })
        // The soonest train's chip is the page's.
        assertEquals(3, choices.current)
    }

    @Test
    fun `a National Rail train its board names otherwise goes on its station's chip by TfL's id for it`() {
        // A stopping and a fast pattern to St Albans City, which a board calls "St Albans" (Codex on #1095): which
        // pattern its train runs can't be told, so it's placed by the station TfL's id for where it ends names.
        val line = LineSequence(
            routes = listOf(
                LineRoute("November - St Albans", listOf("N", "A", "B", "SAC"), "outbound"),
                LineRoute("November - St Albans (fast)", listOf("N", "A", "SAC"), "outbound"),
            ),
            stopNames = mapOf("N" to "November", "A" to "Alpha", "B" to "Bravo", "SAC" to "St Albans City"),
        )
        val board = rows("A", rail("St Albans", 120).copy(destinationId = "SAC"))
        val choices = RouteChoices.of(line, board.single(), board, null)
        assertEquals(listOf("outbound|to|SAC"), choices.choices.map { it.key })
        assertSame(board.single(), choices.choices.single().live?.row)
    }

    private fun ride(from: String, to: String, mode: String = "tube", lineId: String = "northern") = TripLeg(
        mode = mode, lineId = lineId, lineName = "Northern", fromId = from, fromName = "", toId = to, toName = "Kennington",
        departure = now, arrival = now,
    )

    @Test
    fun `a ride's ways are each route from where it boards to where it gets off`() {
        // Camden Town to Kennington: by Bank or by Charing Cross, Battersea's route the same way as the latter.
        val choices = RouteChoices.forRide(northern, ride(CTN, KNG))
        assertEquals(listOf("Kennington/Bank", "Kennington/Charing X"), choices.choices.map(::label))
        assertEquals(listOf(CTN, EUS, BNK, KNG), choices.choices[0].path)
        assertEquals(listOf(CTN, MTC, EUS, CHX, KNG), choices.choices[1].path)
        // The ride itself can't say which.
        assertNull(choices.current)
        // Past where the branches meet, one way: no choice to make.
        assertEquals(listOf(listOf(KNG, MDN)), RouteChoices.forRide(northern, ride(KNG, MDN)).choices.map { it.path })
        // Northbound too, by the way it's ridden.
        assertEquals(listOf(listOf(KNG, BNK, EUS, CTN), listOf(KNG, CHX, EUS, MTC, CTN)), RouteChoices.forRide(northern, ride(KNG, CTN)).choices.map { it.path })
    }

    @Test
    fun `a ride's end found by its station, and none for National Rail`() {
        // A far end saved under another id of its station: the routes calling at that one.
        val stations = northern.copy(stationCodes = mapOf(KNG to "KNG", "940GZZLUKNG2" to "KNG"))
        assertEquals(2, RouteChoices.forRide(stations, ride(CTN, "940GZZLUKNG2")).choices.size)
        assertEquals(RouteChoices.NONE, RouteChoices.forRide(northern, ride(CTN, KNG, mode = "national-rail")))
        // Nor for one saved with no mode, on a line known to be National Rail (Codex on #1039); a tube line's still
        // offers its ways.
        assertEquals(RouteChoices.NONE, RouteChoices.forRide(northern, ride(CTN, KNG, mode = "", lineId = "c2c")))
        assertEquals(2, RouteChoices.forRide(northern, ride(CTN, KNG, mode = "")).choices.size)
    }

    @Test
    fun `a way calling at a ride's station under another id is offered too, at either end`() {
        // The Bank branch's route calls at Camden Town and Kennington under other ids of theirs.
        val siblings = mapOf(CTN to "CTN", "940GZZLUCTN2" to "CTN", KNG to "KNG", "940GZZLUKNG2" to "KNG")
        val bank = southbound[0].let { route -> route.copy(stopIds = route.stopIds.map { mapOf(CTN to "940GZZLUCTN2", KNG to "940GZZLUKNG2")[it] ?: it }) }
        val mixed = northern.copy(routes = listOf(bank) + southbound.drop(1), stationCodes = siblings)
        val choices = RouteChoices.forRide(mixed, ride(CTN, KNG))
        assertEquals(listOf("Kennington/Bank", "Kennington/Charing X"), choices.choices.map(::label))
        // Boarding at the ride's own id, and getting off where that way calls.
        assertEquals(listOf(CTN, EUS, BNK, "940GZZLUKNG2"), choices.choices[0].path)
        // A list row's page finds the way from its stop's sibling id the same way.
        val atCamden = RouteChoices.of(mixed, empty(CTN), emptyList(), null)
        assertTrue(atCamden.choices.any { it.branch == "Bank" && it.path.first() == CTN })
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
