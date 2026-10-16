package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A header over a card in a trip's routes (maintainer, 2026-09-30): which route gets there soonest,
 * which takes the fewest rides, which walks least (maintainer, 2026-10-03) and which costs least
 * (maintainer, 2026-10-16), so a rider choosing between them sees the trade at a glance rather than
 * working it out from the pills. **Other** heads the routes that are none of these. A card can carry
 * several, read in this order.
 */
enum class RouteLabel { FASTEST, SIMPLEST, LEAST_WALKING, CHEAPEST, OTHER }

/** A card of a trip's routes by its [index] in the list ranked best first, and the [header] over it (empty for none). */
data class HeadedCard(val index: Int, val header: List<RouteLabel>)

/**
 * The labels over each of a trip's cards, given each card's best route in the order shown ([cards],
 * best first); empty for a card with none.
 *
 * - **Fastest** is the first card, since the list is already ordered by the arrival StopDash can
 *   stand behind: never on a lone card (fastest of one says nothing) nor one whose arrival is
 *   withheld, which can't be called fastest.
 * - **Simplest** is the card with the fewest rides, the earliest shown where several tie, and only
 *   when the cards don't all ride the same number of times: otherwise none is simpler.
 * - **Least walking** is every card walking least by the Planner's times, all of them where several
 *   tie, the first card included (maintainer, 2026-10-04: "Fastest · Least walking" on the top card
 *   when it walks as little as any), and only when the cards don't all walk as much: otherwise none
 *   walks less.
 * - **Cheapest** is every card at the lowest fare, all of them where several tie, the first card
 *   included, and only when some card costs more: with every fare alike none costs less. A card with
 *   no fare could be the cheapest, so it holds the header back, unless the lowest fare is one bus fare
 *   and that card rides a train ([BusFare.costsMoreThanABus]; maintainer, 2026-10-16: the bus the
 *   whole way beside an unpriced Thameslink), which costs more for sure.
 * - A card that is several says so once, in that order.
 */
@WorkerThread
fun routeLabels(cards: List<TripTiming.Estimate>): List<List<RouteLabel>> {
    val labels = List(cards.size) { mutableListOf<RouteLabel>() }
    if (cards.size < 2) return labels
    if (cards[0].arrival != null) labels[0] += RouteLabel.FASTEST
    val rides = cards.map { it.route.rides.size }
    rides.indexOf(rides.min()).takeIf { rides.min() < rides.max() }?.let { labels[it] += RouteLabel.SIMPLEST }
    val walking = cards.map { it.route.walking }
    val least = walking.min()
    if (least < walking.max()) walking.forEachIndexed { i, walk -> if (walk == least) labels[i] += RouteLabel.LEAST_WALKING }
    val fares = cards.map { it.route.fare?.pence }
    val lowest = fares.filterNotNull().minOrNull() ?: return labels
    val atLowest = fares.indices.filter { fares[it] == lowest }
    val busFare = atLowest.any { BusFare.onOneBusFare(cards[it].route) }
    // Every other card costs more: priced above the lowest, or unpriced but dearer than a bus fare.
    val dearer = fares.indices.filter { i -> fares[i]?.let { it > lowest } ?: (busFare && BusFare.costsMoreThanABus(cards[i].route)) }
    if (dearer.isNotEmpty() && atLowest.size + dearer.size == cards.size) atLowest.forEach { labels[it] += RouteLabel.CHEAPEST }
    return labels
}

/**
 * A trip's cards in the order shown ([cards] best first, as for [routeLabels]), each with the header
 * over it: the labeled cards first, Fastest then Simplest then Least walking then Cheapest (by each
 * card's first label), then the rest in their own order under a single **Other** header (maintainer, 2026-09-30).
 * So Simplest moves up to sit beside Fastest, and the unlabeled cards read as one group rather than
 * being split around it. With no card labeled, nothing is "other" than anything, so the cards keep
 * their order under no header at all.
 */
@WorkerThread
fun headedCards(cards: List<TripTiming.Estimate>): List<HeadedCard> {
    val labels = routeLabels(cards)
    if (labels.all { it.isEmpty() }) return cards.indices.map { HeadedCard(it, emptyList()) }
    val (labeled, rest) = labels.indices.partition { labels[it].isNotEmpty() }
    return labeled.sortedBy { labels[it].first() }.map { HeadedCard(it, labels[it]) } +
        rest.mapIndexed { i, index -> HeadedCard(index, if (i == 0) listOf(RouteLabel.OTHER) else emptyList()) }
}
