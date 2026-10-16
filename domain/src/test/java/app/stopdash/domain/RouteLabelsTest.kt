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
    // now (null: withheld), costing [pence] (null: unpriced).
    private fun card(rides: Int, inMinutes: Long?, walk: Long = 5, pence: Int? = null): TripTiming.Estimate {
        val legs = listOf(walk("X", "A", walk)) + (0 until rides).map { ride("line$it", "S$it", "S${it + 1}") }
        val arrival = inMinutes?.let { t.plusSeconds(it * 60) }
        return TripTiming.Estimate(
            route = TripRoute(legs, pence?.let { TripFare(it) }),
            basis = if (arrival == null) TripTiming.Basis.UNKNOWN else TripTiming.Basis.LIVE,
            arrival = arrival,
            legs = emptyList(),
            blocked = false,
            start = t,
        )
    }

    private fun leg(mode: String, line: String, from: String, to: String) = TripLeg(
        mode = mode, lineId = line, lineName = line, fromId = from, fromName = from, toId = to, toName = to,
        departure = t, arrival = t,
    )

    // A card's best route riding [legs] (`490…` ids for London bus stops), arriving [inMinutes] from now, costing [pence].
    private fun card(inMinutes: Long, vararg legs: TripLeg, pence: Int? = null) = TripTiming.Estimate(
        route = TripRoute(legs.toList(), pence?.let { TripFare(it) }),
        basis = TripTiming.Basis.LIVE,
        arrival = t.plusSeconds(inMinutes * 60),
        legs = emptyList(),
        blocked = false,
        start = t,
    )

    private val bus = leg("bus", "1", "490000001A", "490000002B")
    private val secondBus = leg("bus", "2", "490000002B", "490000003C")
    private val train = leg("national-rail", "rail", "910GAAAAAAA", "910GBBBBBBB")
    private val tube = leg("tube", "tube", "940GZZAAAAA", "940GZZBBBBB")

    private val none = emptyList<RouteLabel>()
    private val fastest = listOf(RouteLabel.FASTEST)
    private val simplest = listOf(RouteLabel.SIMPLEST)
    private val leastWalking = listOf(RouteLabel.LEAST_WALKING)
    private val cheapest = listOf(RouteLabel.CHEAPEST)

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
    fun `every card walking least is labeled so`() {
        // A bus to the station rather than the twelve-minute walk there: a change, but four minutes on foot.
        assertEquals(
            listOf(listOf(RouteLabel.FASTEST, RouteLabel.SIMPLEST), none, leastWalking),
            routeLabels(listOf(card(1, 30, walk = 12), card(2, 33, walk = 10), card(2, 36, walk = 4))),
        )
        // Two walking as little: both.
        assertEquals(
            listOf(fastest, leastWalking, leastWalking),
            routeLabels(listOf(card(2, 30, walk = 12), card(2, 33, walk = 4), card(2, 36, walk = 4))),
        )
        // The one walking least can also ride fewest.
        assertEquals(
            listOf(fastest, listOf(RouteLabel.SIMPLEST, RouteLabel.LEAST_WALKING)),
            routeLabels(listOf(card(2, 30, walk = 12), card(1, 36, walk = 4))),
        )
        // However little less: a minute's walk saved still walks least.
        assertEquals(listOf(fastest, leastWalking), routeLabels(listOf(card(2, 30, walk = 5), card(2, 33, walk = 4))))
    }

    @Test
    fun `the first card walking least says so beside fastest`() {
        // The top card walks as little as any (maintainer, 2026-10-04): "Fastest · Least walking", and
        // the card tied with it says so too.
        assertEquals(
            listOf(listOf(RouteLabel.FASTEST, RouteLabel.LEAST_WALKING), none, leastWalking),
            routeLabels(listOf(card(2, 30, walk = 2), card(2, 33, walk = 16), card(2, 36, walk = 2))),
        )
    }

    @Test
    fun `none walks least when every card walks as much`() {
        assertEquals(listOf(fastest, none, none), routeLabels(listOf(card(2, 30, walk = 6), card(2, 33, walk = 6), card(2, 36, walk = 6))))
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

    @Test
    fun `every card at the lowest fare is cheapest`() {
        // The bus the whole way for a bus fare, beside the tube for more.
        assertEquals(
            listOf(fastest, none, cheapest),
            routeLabels(listOf(card(2, 30, pence = 290), card(2, 34, pence = 340), card(2, 41, pence = 175))),
        )
        // Two buses at the bus fare: both.
        assertEquals(
            listOf(fastest, cheapest, cheapest),
            routeLabels(listOf(card(2, 30, pence = 290), card(2, 38, pence = 175), card(2, 41, pence = 175))),
        )
        // However little less: ten pence saved still costs least.
        assertEquals(listOf(fastest, cheapest), routeLabels(listOf(card(2, 30, pence = 290), card(2, 34, pence = 280))))
    }

    @Test
    fun `the first card cheapest says so beside fastest and the others`() {
        assertEquals(
            listOf(listOf(RouteLabel.FASTEST, RouteLabel.CHEAPEST), none),
            routeLabels(listOf(card(2, 30, pence = 175), card(2, 34, pence = 290))),
        )
        // Simplest, least walking and cheapest at once, read in that order.
        assertEquals(
            listOf(fastest, listOf(RouteLabel.SIMPLEST, RouteLabel.LEAST_WALKING, RouteLabel.CHEAPEST)),
            routeLabels(listOf(card(2, 30, walk = 10, pence = 290), card(1, 40, walk = 2, pence = 175))),
        )
    }

    @Test
    fun `none is cheapest when every card costs as much or one has no fare`() {
        assertEquals(listOf(fastest, none), routeLabels(listOf(card(2, 30, pence = 290), card(2, 34, pence = 290))))
        // A card with no fare could cost least, so none is called cheapest.
        assertEquals(listOf(fastest, none, none), routeLabels(listOf(card(2, 30, pence = 290), card(2, 34), card(2, 41, pence = 175))))
        // None priced: nothing to compare.
        assertEquals(listOf(fastest, none), routeLabels(listOf(card(2, 30), card(2, 34))))
        // A lone card, however cheap, isn't called cheapest.
        assertEquals(listOf(none), routeLabels(listOf(card(1, 30, pence = 175))))
    }

    @Test
    fun `the bus fare is cheapest beside unpriced cards that ride a train`() {
        // The report's shape (2026-10-16): the bus to the tube priced, the bus to a Thameslink train
        // twice unpriced, and buses the whole way on one bus fare. Riding a train costs more than any
        // bus fare, so the buses are cheapest though two cards show no fare.
        assertEquals(
            listOf(fastest, none, none, cheapest),
            routeLabels(
                listOf(
                    card(47, bus, tube, pence = 485),
                    card(55, bus, train),
                    card(65, bus, train),
                    card(67, bus, secondBus, pence = 175),
                ),
            ),
        )
        // With no other card priced, the bus fare still costs less than the train.
        assertEquals(
            listOf(listOf(RouteLabel.FASTEST, RouteLabel.CHEAPEST), none),
            routeLabels(listOf(card(30, bus, pence = 175), card(40, train))),
        )
    }

    @Test
    fun `an unpriced card holds cheapest back unless it costs more than a bus fare`() {
        // The lowest fare is a train's, which an unpriced train could undercut.
        assertEquals(listOf(fastest, none, none), routeLabels(listOf(card(30, tube, pence = 290), card(34, train), card(40, tube, pence = 340))))
        // An unpriced bus the whole way could cost the same bus fare; a tram costs a bus fare too, a
        // replacement bus perhaps nothing, and a walk nothing at all.
        for (unpriced in listOf(secondBus, leg("tram", "tram", "940GZZCRAAA", "940GZZCRBBB"), leg("replacement-bus", "", "490000001A", "490000002B"))) {
            assertEquals(listOf(fastest, none, none), routeLabels(listOf(card(30, tube, pence = 485), card(34, unpriced), card(40, bus, pence = 175))))
        }
        val walkOnly = routeLabels(listOf(card(30, tube, pence = 485), card(34, walk("X", "Y", 30)), card(40, bus, pence = 175)))
        assertEquals(listOf(false, false, false), walkOnly.map { RouteLabel.CHEAPEST in it })
        // A bus beyond London may charge its own fare, so it isn't the bus fare a train costs more than.
        val beyond = leg("bus", "1", "490000001A", "210000000X")
        assertEquals(listOf(fastest, none, none), routeLabels(listOf(card(30, tube, pence = 485), card(34, train), card(40, beyond, pence = 175))))
    }

    @Test
    fun `cheapest follows least walking and other keeps the rest in order`() {
        // Sorting stays as it was: labeled cards first, by their first label, the rest under Other.
        assertEquals(
            listOf(
                HeadedCard(0, fastest),
                HeadedCard(3, simplest),
                HeadedCard(2, leastWalking),
                HeadedCard(4, cheapest),
                HeadedCard(1, listOf(RouteLabel.OTHER)),
            ),
            headedCards(
                listOf(
                    card(2, 30, walk = 10, pence = 290),
                    card(2, 34, walk = 10, pence = 290),
                    card(2, 36, walk = 2, pence = 290),
                    card(1, 40, walk = 10, pence = 290),
                    card(2, 45, walk = 10, pence = 175),
                ),
            ),
        )
    }
}
