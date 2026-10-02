package app.stopdash.domain

/**
 * The header over a card in a trip's routes (maintainer, 2026-09-30): which route gets there
 * soonest and which takes the fewest rides, so a rider choosing between them sees the trade at a
 * glance rather than working it out from the pills. **Other** heads the routes that are neither.
 */
enum class RouteLabel { FASTEST, SIMPLEST, FASTEST_AND_SIMPLEST, OTHER }

/** A card of a trip's routes by its [index] in the list ranked best first, and the [header] over it, if any. */
data class HeadedCard(val index: Int, val header: RouteLabel?)

/**
 * The label over each of a trip's cards, given each card's best route in the order shown ([cards],
 * best first), or null for a card with none.
 *
 * - **Fastest** is the first card, since the list is already ordered by the arrival LDN Go can
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

/**
 * A trip's cards in the order shown ([cards] best first, as for [routeLabels]), each with the header
 * over it: the labeled cards first, Fastest then Simplest, then the rest in their own order under a
 * single **Other** header (maintainer, 2026-09-30). So Simplest moves up to sit beside Fastest, and
 * the unlabeled cards read as one group rather than being split around it. With no card labeled,
 * nothing is "other" than anything, so the cards keep their order under no header at all.
 */
fun headedCards(cards: List<TripTiming.Estimate>): List<HeadedCard> {
    val labels = routeLabels(cards)
    if (labels.all { it == null }) return cards.indices.map { HeadedCard(it, null) }
    val (labeled, rest) = labels.indices.partition { labels[it] != null }
    return labeled.map { HeadedCard(it, labels[it]) } +
        rest.mapIndexed { i, index -> HeadedCard(index, RouteLabel.OTHER.takeIf { i == 0 }) }
}
