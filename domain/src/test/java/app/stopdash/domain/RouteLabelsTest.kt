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

    private fun walk(from: String, to: String, minutes: Long) = TripLeg(
        mode = TripLeg.WALKING, lineId = "", lineName = "", fromId = from, fromName = from, toId = to, toName = to,
        departure = t, arrival = t.plusSeconds(minutes * 60),
    )

    // A card's best route riding [rides] times after [walk] minutes on foot, arriving [inMinutes] from
    // now (null: withheld).
    private fun card(rides: Int, inMinutes: Long?, walk: Long = 5): TripTiming.Estimate {
        val legs = listOf(walk("X", "A", walk)) + (0 until rides).map { ride("line$it", "S$it", "S${it + 1}") }
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

    private val none = emptyList<RouteLabel>()
    private val fastest = listOf(RouteLabel.FASTEST)
    private val simplest = listOf(RouteLabel.SIMPLEST)
    private val leastWalking = listOf(RouteLabel.LEAST_WALKING)

    @Test
    fun `the first card is fastest and the one with fewest rides simplest`() {
        assertEquals(
            listOf(fastest, none, simplest),
            routeLabels(listOf(card(2, 30), card(2, 34), card(1, 40))),
        )
    }

    @Test
    fun `a card both fastest and simplest says so once`() {
        assertEquals(
            listOf(listOf(RouteLabel.FASTEST, RouteLabel.SIMPLEST), none),
            routeLabels(listOf(card(1, 30), card(2, 35))),
        )
    }

    @Test
    fun `of several with the fewest rides the earliest shown is simplest`() {
        assertEquals(
            listOf(fastest, simplest, none),
            routeLabels(listOf(card(3, 30), card(2, 34), card(2, 36))),
        )
    }

    @Test
    fun `none is simplest when every card rides as often`() {
        assertEquals(listOf(fastest, none, none), routeLabels(listOf(card(2, 30), card(2, 34), card(2, 36))))
    }

    @Test
    fun `a walk the whole way rides fewest`() {
        assertEquals(listOf(fastest, simplest), routeLabels(listOf(card(2, 25), card(0, 32))))
    }

    @Test
    fun `a lone card and a withheld first arrival aren't called fastest`() {
        assertEquals(listOf(none), routeLabels(listOf(card(1, 30))))
        assertEquals(emptyList<List<RouteLabel>>(), routeLabels(emptyList()))
        // The first card's arrival withheld: nothing to call it fastest by, though one still rides fewest.
        assertEquals(listOf(none, simplest), routeLabels(listOf(card(2, null), card(1, null))))
    }

    @Test
    fun `the card walking clearly less than the first walks least`() {
        // A bus to the station rather than the twelve-minute walk there: a change, but four minutes on foot.
        assertEquals(
            listOf(listOf(RouteLabel.FASTEST, RouteLabel.SIMPLEST), none, leastWalking),
            routeLabels(listOf(card(1, 30, walk = 12), card(2, 33, walk = 10), card(2, 36, walk = 4))),
        )
        // Of two walking as little, the earliest shown.
        assertEquals(
            listOf(fastest, leastWalking, none),
            routeLabels(listOf(card(2, 30, walk = 12), card(2, 33, walk = 4), card(2, 36, walk = 4))),
        )
        // The one walking least can also ride fewest.
        assertEquals(
            listOf(fastest, listOf(RouteLabel.SIMPLEST, RouteLabel.LEAST_WALKING)),
            routeLabels(listOf(card(2, 30, walk = 12), card(1, 36, walk = 4))),
        )
    }

    @Test
    fun `none walks least when the saving is small or the first walks least`() {
        // Four minutes less is no reason to pick it.
        assertEquals(listOf(fastest, none), routeLabels(listOf(card(2, 30, walk = 12), card(2, 33, walk = 8))))
        // The first card already walks least: Fastest says all there is.
        assertEquals(listOf(fastest, none), routeLabels(listOf(card(2, 30, walk = 2), card(2, 33, walk = 10))))
        // Exactly five minutes less counts, however short the first walk.
        assertEquals(listOf(fastest, leastWalking), routeLabels(listOf(card(2, 30, walk = 6), card(2, 33, walk = 1))))
    }

    @Test
    fun `the rest follow fastest and simplest under one other header`() {
        // Simplest arrives last but sits beside Fastest; the two between keep their order under "Other".
        assertEquals(
            listOf(
                HeadedCard(0, listOf(RouteLabel.FASTEST)),
                HeadedCard(3, listOf(RouteLabel.SIMPLEST)),
                HeadedCard(1, listOf(RouteLabel.OTHER)),
                HeadedCard(2, emptyList()),
            ),
            headedCards(listOf(card(2, 30), card(2, 34), card(3, 36), card(1, 40))),
        )
    }

    @Test
    fun `other follows a card that is both`() {
        assertEquals(
            listOf(HeadedCard(0, listOf(RouteLabel.FASTEST, RouteLabel.SIMPLEST)), HeadedCard(1, listOf(RouteLabel.OTHER)), HeadedCard(2, emptyList())),
            headedCards(listOf(card(1, 30), card(2, 35), card(2, 38))),
        )
    }

    @Test
    fun `other follows fastest when none is simplest`() {
        assertEquals(
            listOf(HeadedCard(0, listOf(RouteLabel.FASTEST)), HeadedCard(1, listOf(RouteLabel.OTHER)), HeadedCard(2, emptyList())),
            headedCards(listOf(card(2, 30), card(2, 34), card(2, 36))),
        )
    }

    @Test
    fun `simplest leads when the first arrival is withheld`() {
        assertEquals(
            listOf(HeadedCard(1, listOf(RouteLabel.SIMPLEST)), HeadedCard(0, listOf(RouteLabel.OTHER))),
            headedCards(listOf(card(2, null), card(1, null))),
        )
    }

    @Test
    fun `with nothing labeled no card is headed other`() {
        assertEquals(listOf(HeadedCard(0, emptyList())), headedCards(listOf(card(1, 30))))
        assertEquals(emptyList<HeadedCard>(), headedCards(emptyList()))
        // A withheld first arrival and every card riding as often: neither label applies.
        assertEquals(listOf(HeadedCard(0, emptyList()), HeadedCard(1, emptyList())), headedCards(listOf(card(2, null), card(2, 34))))
    }

    @Test
    fun `least walking follows simplest whatever their order`() {
        assertEquals(
            listOf(
                HeadedCard(0, fastest),
                HeadedCard(3, simplest),
                HeadedCard(2, leastWalking),
                HeadedCard(1, listOf(RouteLabel.OTHER)),
            ),
            headedCards(listOf(card(2, 30, walk = 10), card(2, 34, walk = 10), card(2, 36, walk = 2), card(1, 40, walk = 10))),
        )
    }

    @Test
    fun `two cards both labeled leave none for other`() {
        assertEquals(
            listOf(HeadedCard(0, listOf(RouteLabel.FASTEST)), HeadedCard(1, listOf(RouteLabel.SIMPLEST))),
            headedCards(listOf(card(2, 25), card(0, 32))),
        )
    }
}
