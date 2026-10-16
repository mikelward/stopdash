package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.temporal.ChronoUnit

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
 *   stand behind: never on a lone card (fastest of one says nothing), one whose arrival is withheld,
 *   which can't be called fastest, nor one a card below gets there before, in an earlier minute
 *   (maintainer, 2026-10-16): an estimate stays below a live route it may not beat, and the header
 *   over it would say what the card below plainly contradicts. It's by when each gets there, not the
 *   minutes it shows from now (maintainer, 2026-10-16: arrival time is what matters), so cards
 *   arriving in the same minute tie, and midnight or the clock change can't make a later one read
 *   as sooner.
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
    val first = cards[0].arrival?.truncatedTo(ChronoUnit.MINUTES)
    if (first != null && cards.drop(1).none { it.arrival?.truncatedTo(ChronoUnit.MINUTES)?.isBefore(first) == true }) {
        labels[0] += RouteLabel.FASTEST
    }
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
 *
 * The first card stays first whenever its arrival is known, under whatever labels it has, none
 * included: it's the route the list ranked best, and it loses Fastest only to a sooner card ranked
 * below it for want of a live train, which a label must not lift over it (Codex, #899). Only a first
 * card whose arrival is withheld gives up its place to the labeled ones.
 */
@WorkerThread
fun headedCards(cards: List<TripTiming.Estimate>): List<HeadedCard> {
    val labels = routeLabels(cards)
    if (labels.all { it.isEmpty() }) return cards.indices.map { HeadedCard(it, emptyList()) }
    val lead = if (cards.first().arrival != null) listOf(HeadedCard(0, labels[0])) else emptyList()
    val (labeled, rest) = labels.indices.filter { index -> lead.none { it.index == index } }.partition { labels[it].isNotEmpty() }
    return lead + labeled.sortedBy { labels[it].first() }.map { HeadedCard(it, labels[it]) } +
        rest.mapIndexed { i, index -> HeadedCard(index, if (i == 0) listOf(RouteLabel.OTHER) else emptyList()) }
}
