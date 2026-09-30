package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic stops and lines only. */
class RouteLabelsTest {
    private val t = Instant.parse("2026-09-30T12:00:00Z")

    private fun ride(line: String, from: String, to: String) = TripLeg(
        mode = "tube", lineId = line, lineName = line, fromId = from, fromName = from, toId = to, toName = to,
        departure = t, arrival = t,
    )

    private fun walk(from: String, to: String) = TripLeg(
        mode = TripLeg.WALKING, lineId = "", lineName = "", fromId = from, fromName = from, toId = to, toName = to,
        departure = t, arrival = t,
    )

    // A card's best route riding [rides] times, arriving [inMinutes] from now (null: withheld).
    private fun card(rides: Int, inMinutes: Long?): TripTiming.Estimate {
        val legs = listOf(walk("X", "A")) + (0 until rides).map { ride("line$it", "S$it", "S${it + 1}") }
        val arrival = inMinutes?.let { t.plusSeconds(it * 60) }
        return TripTiming.Estimate(
            route = TripRoute(legs),
            basis = if (arrival == null) TripTiming.Basis.UNKNOWN else TripTiming.Basis.LIVE,
            arrival = arrival,
            legs = emptyList(),
            blocked = false,
            start = t,
        )
    }

    @Test
    fun `the first card is fastest and the one with fewest rides simplest`() {
        assertEquals(
            listOf(RouteLabel.FASTEST, null, RouteLabel.SIMPLEST),
            routeLabels(listOf(card(2, 30), card(2, 34), card(1, 40))),
        )
    }

    @Test
    fun `a card both fastest and simplest says so once`() {
        assertEquals(
            listOf(RouteLabel.FASTEST_AND_SIMPLEST, null),
            routeLabels(listOf(card(1, 30), card(2, 35))),
        )
    }

    @Test
    fun `of several with the fewest rides the earliest shown is simplest`() {
        assertEquals(
            listOf(RouteLabel.FASTEST, RouteLabel.SIMPLEST, null),
            routeLabels(listOf(card(3, 30), card(2, 34), card(2, 36))),
        )
    }

    @Test
    fun `none is simplest when every card rides as often`() {
        assertEquals(listOf(RouteLabel.FASTEST, null, null), routeLabels(listOf(card(2, 30), card(2, 34), card(2, 36))))
    }

    @Test
    fun `a walk the whole way rides fewest`() {
        assertEquals(listOf(RouteLabel.FASTEST, RouteLabel.SIMPLEST), routeLabels(listOf(card(2, 25), card(0, 32))))
    }

    @Test
    fun `a lone card and a withheld first arrival aren't called fastest`() {
        assertEquals(listOf<RouteLabel?>(null), routeLabels(listOf(card(1, 30))))
        assertEquals(emptyList<RouteLabel?>(), routeLabels(emptyList()))
        // The first card's arrival withheld: nothing to call it fastest by, though one still rides fewest.
        assertEquals(listOf(null, RouteLabel.SIMPLEST), routeLabels(listOf(card(2, null), card(1, null))))
    }
}
