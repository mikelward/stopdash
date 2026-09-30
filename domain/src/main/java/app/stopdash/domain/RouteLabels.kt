package app.stopdash.domain

/**
 * The header over a card in a trip's routes (maintainer, 2026-09-30): which route gets there
 * soonest and which takes the fewest rides, so a rider choosing between them sees the trade at a
 * glance rather than working it out from the pills.
 */
enum class RouteLabel { FASTEST, SIMPLEST, FASTEST_AND_SIMPLEST }

/**
 * The label over each of a trip's cards, given each card's best route in the order shown ([cards],
 * best first), or null for a card with none.
 *
 * - **Fastest** is the first card, since the list is already ordered by the arrival StopDash can
 *   stand behind: never on a lone card (fastest of one says nothing) nor one whose arrival is
 *   withheld, which can't be called fastest.
 * - **Simplest** is the card with the fewest rides, the earliest shown where several tie, and only
 *   when the cards don't all ride the same number of times: otherwise none is simpler.
 * - One card that is both says so once.
 */
fun routeLabels(cards: List<TripTiming.Estimate>): List<RouteLabel?> {
    val labels = MutableList<RouteLabel?>(cards.size) { null }
    if (cards.size < 2) return labels
    val fastest = 0.takeIf { cards[0].arrival != null }
    val rides = cards.map { it.route.rides.size }
    val simplest = rides.indexOf(rides.min()).takeIf { rides.min() < rides.max() }
    fastest?.let { labels[it] = RouteLabel.FASTEST }
    simplest?.let { labels[it] = if (it == fastest) RouteLabel.FASTEST_AND_SIMPLEST else RouteLabel.SIMPLEST }
    return labels
}
