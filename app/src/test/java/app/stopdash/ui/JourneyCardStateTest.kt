package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.JourneyChange
import app.stopdash.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops only. */
class JourneyCardStateTest {
    private val now = Instant.parse("2026-09-24T08:00:00Z")

    private fun departure(to: String, inSeconds: Long) =
        Departure("example", "Example", "outbound", to, null, now.plusSeconds(inSeconds), "tube")

    @Test
    fun `a row split into direct and change trains is shown, and deduplicated, whole`() {
        val whole = DepartureRows.across(
            listOf(StopArrivals("KING", "King", listOf(departure("West End", 60), departure("North End", 300)), fetchedAt = now)),
            now,
        ).single()
        val direct = whole.copy(upcoming = whole.upcoming.filter { it.destination == "North End" }, destination = "North End")
        val change = whole.copy(upcoming = whole.upcoming.filter { it.destination == "West End" }, destination = "West End")
        val state = JourneyCardState.Trains(listOf(direct), changes = listOf(JourneyChange("FORK", "Fork", change)))
        val shown = state.shownRows.single()
        assertEquals(listOf("West End", "North End"), shown.upcoming.map { it.destination })
        // The near-me copy of that row, every departure on the card, isn't repeated below it.
        assertTrue(DepartureRows.withoutShownAbove(listOf(whole), state.shownRows).isEmpty())
        // Its stop cards come with it, worked out where the card is judged, never where it's drawn.
        assertEquals(state.groups, state.cards.map { it.group })
        assertEquals(listOf(listOf("North End")), state.cards.flatMap { card -> card.lines.map { row -> row.map { it.destination } } })
        val atFork = state.changeStops.single()
        assertEquals(atFork.groups, atFork.cards.map { it.group })
        assertEquals(listOf(listOf("West End")), atFork.cards.flatMap { card -> card.lines.map { row -> row.map { it.destination } } })
    }

    @Test
    fun `a farther ride leaves out what a nearer one shows, says so when that's all, and its own view keeps it`() {
        fun rowsAt(stop: String, vararg to: String) = DepartureRows.across(
            listOf(StopArrivals(stop, stop, to.mapIndexed { i, dest -> departure(dest, 60L + i * 60) }, fetchedAt = now)),
            now,
        )
        fun ride(from: String, to: String, rows: List<app.stopdash.domain.DepartureRow>) = JourneyCard(
            app.stopdash.domain.FavoriteJourney(app.stopdash.domain.JourneyEnd(from, from), app.stopdash.domain.JourneyEnd(to, to), "example"),
            JourneyCardState.Trains(rows, limit = JOURNEY_ROWS),
        )
        val near = ride("KING", "NORTH", rowsAt("KING", "North End"))
        val far = ride("QUEEN", "NORTH", rowsAt("QUEEN", "North End"))
        val other = ride("QUEEN", "WEST", rowsAt("QUEEN", "North End", "West End"))
        val cards = withoutRepeatedRoutes(listOf(near, far, other), viewKey = null)
        val (kept, shownAbove, part) = cards.map { it.state as JourneyCardState.Trains }
        assertEquals(near, cards[0])
        // Its only route is the nearer ride's: none drawn, and the card says why.
        assertTrue(shownAbove.shownAbove)
        assertTrue(shownAbove.drawnRows.isEmpty())
        // Its rows stay whole, for the widget's pins.
        assertEquals(far.state, shownAbove.copy(repeated = emptySet()))
        // A row with a route the nearer ride doesn't show stays.
        assertTrue(!part.shownAbove)
        assertTrue(part.drawnRows.any { row -> row.upcoming.any { it.destination == "West End" } })
        assertTrue(kept.repeated.isEmpty())
        // Open in its own view, the farther ride shows everything.
        assertEquals(far, withoutRepeatedRoutes(listOf(near, far), viewKey = far.journey.key)[1])
        // A ride whose only trains are to change from, all shown above: none drawn, and it says so.
        val changing = JourneyCard(
            far.journey.copy(to = app.stopdash.domain.JourneyEnd("WEST", "WEST")),
            JourneyCardState.Trains(emptyList(), changes = listOf(JourneyChange("FORK", "Fork", rowsAt("QUEEN", "North End").single())), limit = JOURNEY_ROWS),
        )
        val changes = withoutRepeatedRoutes(listOf(near, changing), viewKey = null)[1].state as JourneyCardState.Trains
        assertTrue(changes.changesShownAbove)
        assertTrue(changes.changeStops.isEmpty())
        assertTrue(changes.shownRows.isEmpty())
    }

    @Test
    fun `a ride whose direct trains are shown above says so only when no train to change from of its own follows`() {
        fun rowsAt(stop: String, vararg to: String) = DepartureRows.across(
            listOf(StopArrivals(stop, stop, to.mapIndexed { i, dest -> departure(dest, 60L + i * 60) }, fetchedAt = now)),
            now,
        )
        fun ride(from: String, to: String, state: JourneyCardState.Trains) = JourneyCard(
            app.stopdash.domain.FavoriteJourney(app.stopdash.domain.JourneyEnd(from, from), app.stopdash.domain.JourneyEnd(to, to), "example"),
            state,
        )
        val near = ride("KING", "NORTH", JourneyCardState.Trains(rowsAt("KING", "North End"), limit = JOURNEY_ROWS))
        // Its direct train is the nearer ride's, its train to change from isn't: that one is drawn, unannounced.
        val unique = ride(
            "QUEEN", "EAST",
            JourneyCardState.Trains(rowsAt("QUEEN", "North End"), changes = listOf(JourneyChange("FORK", "Fork", rowsAt("QUEEN", "West End").single())), limit = JOURNEY_ROWS),
        )
        val drawn = withoutRepeatedRoutes(listOf(near, unique), viewKey = null)[1].state as JourneyCardState.Trains
        assertTrue(drawn.shownAbove)
        assertTrue(!drawn.saysShownAbove)
        assertEquals(1, drawn.visibleChanges.size)
        // Its train to change from is the nearer ride's too: nothing of its own is left, and it says so.
        val repeat = ride(
            "QUEEN", "EAST",
            JourneyCardState.Trains(rowsAt("QUEEN", "North End"), changes = listOf(JourneyChange("FORK", "Fork", rowsAt("QUEEN", "North End").single())), limit = JOURNEY_ROWS),
        )
        assertTrue((withoutRepeatedRoutes(listOf(near, repeat), viewKey = null)[1].state as JourneyCardState.Trains).saysShownAbove)
    }

    @Test
    fun `the list's rows come with their stop cards, grouped as the list draws them`() {
        val rows = DepartureRows.across(
            listOf(
                StopArrivals("KING", "King", listOf(departure("West End", 60), departure("North End", 300)), fetchedAt = now),
                StopArrivals("QUEEN", "Queen", listOf(departure("East End", 120)), fetchedAt = now),
            ),
            now,
        )
        val cards = ListCards.of(rows, warningsLead = false, app.stopdash.domain.RouteTopology.EMPTY)
        assertEquals(app.stopdash.domain.StopGrouping.groupByStop(rows, warningsLead = false), cards.groups)
        // Each group's card is found by the group itself, its rows' route rows worked out with it.
        cards.groups.forEach { group ->
            val card = cards.of(group)
            assertTrue(card.group === group)
            assertEquals(group.rows.size, card.lines.size)
        }
        assertEquals(listOf("King", "Queen"), cards.groups.map { it.stopName })
        assertTrue(ListCards.NONE.groups.isEmpty())
    }

    @Test
    fun `a ride's stops are as far as their place's nearest stop, its near-me groups included`() {
        // Two poles of one place: the ride boards at the farther, the near-me list shows the nearer.
        fun pole(id: String, letter: String, to: String) =
            StopArrivals(id, "Park", listOf(departure(to, 60)), fetchedAt = now, clusterId = "PARK", stopLetter = letter)
        val ridden = DepartureRows.across(listOf(pole("PARK-K", "K", "Hill")), now)
        val nearMe = ListCards.of(DepartureRows.across(listOf(pole("PARK-L", "L", "Vale")), now), warningsLead = false, app.stopdash.domain.RouteTopology.EMPTY)
        val card = JourneyCard(
            app.stopdash.domain.FavoriteJourney(app.stopdash.domain.JourneyEnd("PARK-K", "Park"), app.stopdash.domain.JourneyEnd("HILL", "Hill"), "example"),
            JourneyCardState.Trains(ridden),
        )
        val meters = mapOf("PARK-K" to 240.0, "PARK-L" to 120.0)
        val place = card.state.let { (it as JourneyCardState.Trains).groups.single().placeKey }
        assertEquals(mapOf(place to 120.0), ridePlaceMeters(listOf(card), nearMe.groups, meters))
        // Without the near-me group, its own stop's distance.
        assertEquals(mapOf(place to 240.0), ridePlaceMeters(listOf(card), emptyList(), meters))
        // Its headings' forms come with the card and its groups, worked out where the card is judged.
        assertEquals(listOf("Park", "Pk", "Pk"), card.fromForms)
        val stop = (card.state as JourneyCardState.Trains).stopForms.single()
        assertEquals(listOf("Park", "Pk", "Pk"), stop.name)
        // A lone pole has nothing to split it by, so no qualifier.
        assertTrue(stop.qualifier.isEmpty())
        // A platform shortens with the names; a pole's letter never does.
        fun group(qualifier: app.stopdash.domain.StopQualifier) =
            app.stopdash.domain.StopGroup("King's Cross St. Pancras", "kx", showHeader = true, rows = emptyList(), qualifier = qualifier)
        val platform = RideStopForms.of(group(app.stopdash.domain.StopQualifier.Platform("6", "Westbound")))
        assertEquals(listOf("King's Cross St. Pancras", "King's X St. Pancras", "King's X St. Pancras"), platform.name)
        assertEquals(listOf(" – Platform 6", " – Plat 6"), platform.qualifier)
        assertEquals(listOf(" – Stop K", " – Stop K"), RideStopForms.of(group(app.stopdash.domain.StopQualifier.BusStop("K", null))).qualifier)
        // Only the origin's group may carry a far ride's distance, which is measured to it.
        val both = card.copy(
            state = JourneyCardState.Trains(DepartureRows.across(listOf(pole("PARK-K", "K", "Hill"), pole("PARK-L", "L", "Hill")), now)),
        )
        val origin = (both.state as JourneyCardState.Trains).groups.single { group -> group.rows.any { it.stopId == "PARK-K" } }
        assertEquals(setOf(origin.key), both.originGroupKeys)
        // Off the near-me list (no distances), and with no trains to head, nothing.
        assertTrue(ridePlaceMeters(listOf(card), nearMe.groups, emptyMap()).isEmpty())
        assertTrue(ridePlaceMeters(listOf(card.copy(state = JourneyCardState.Checking("route"))), nearMe.groups, meters).isEmpty())
    }
}
