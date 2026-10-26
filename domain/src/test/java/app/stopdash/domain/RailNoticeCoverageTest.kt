package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A station's National Rail board messages on its card ([DepartureRows.across]): a route message a
 * disrupted line's status already links to is that line's alert, so the card leaves it to the line's ⚠
 * (maintainer, 2026-10-26).
 */
class RailNoticeCoverageTest {
    private val now: Instant = Instant.parse("2026-10-26T15:00:00Z")
    private val oxted = "Trains running between Oxted and East Croydon may be delayed by up to 20 minutes. " +
        "Latest information can be found in the Disruptions area of the National Rail website " +
        "(https://www.nationalrail.co.uk/service-disruptions/oxted-20261026/)."
    private val toilets = "The accessible toilets on platform B are out of order at London St Pancras International station."
    private val tulseHill = "Thameslink trains running between Sutton and Tulse Hill may be delayed. " +
        "Latest information (https://www.nationalrail.co.uk/service-disruptions/tulse-hill-20261026/)."

    private fun card(vararg statuses: LineStatus, notices: List<String> = listOf(oxted, toilets, tulseHill), name: String = "London St Pancras International LL"): String? =
        DepartureRows.across(
            listOf(StopArrivals("910GSTPXBOX", name, emptyList(), now, railNotices = notices)),
            now,
            statuses.associateBy { it.lineId },
        ).firstOrNull { it.stopDisruption != null }?.stopDisruption

    private fun disrupted(lineId: String, page: String) =
        LineStatus(lineId, 9, "Minor Delays", "https://www.nationalrail.co.uk/service-disruptions/$page/")

    @Test
    fun `a route message a disrupted line links to leaves the card`() {
        val shown = card(disrupted("southern", "oxted-20261026"), disrupted("thameslink", "tulse-hill-20261026"))
        assertEquals(toilets, shown)
    }

    @Test
    fun `a route message no line's status covers stays`() {
        // TfL hasn't caught up with the Tulse Hill disruption: the card still says it.
        assertEquals(listOf(toilets, tulseHill).joinToString("\n\n"), card(disrupted("southern", "oxted-20261026")))
        // A line on good service links to nothing it flags.
        val good = LineStatus("southern", LineStatus.GOOD_SERVICE, "Good Service", "https://www.nationalrail.co.uk/service-disruptions/oxted-20261026/")
        assertEquals(listOf(oxted, toilets, tulseHill).joinToString("\n\n"), card(good))
        assertEquals(listOf(oxted, toilets, tulseHill).joinToString("\n\n"), card())
    }

    @Test
    fun `a route message naming this station stays`() {
        // At East Croydon, the Oxted message is about its own trains.
        assertEquals(oxted, card(disrupted("southern", "oxted-20261026"), notices = listOf(oxted), name = "East Croydon Rail Station"))
    }

    @Test
    fun `a route message naming this station another way stays`() {
        // Codex on #1237: a curly apostrophe, no "St." dot, no leading "London" still name the station.
        val kx = "Trains to King’s Cross may be delayed (https://www.nationalrail.co.uk/service-disruptions/oxted-20261026/)."
        assertEquals(kx, card(disrupted("southern", "oxted-20261026"), notices = listOf(kx), name = "London King's Cross"))
        val stp = "Lifts at St Pancras International are out of use (https://www.nationalrail.co.uk/service-disruptions/oxted-20261026/)."
        assertEquals(stp, card(disrupted("southern", "oxted-20261026"), notices = listOf(stp), name = "London St. Pancras International LL"))
        val hc = "Hammersmith and City trains (https://www.nationalrail.co.uk/service-disruptions/oxted-20261026/)."
        assertEquals(hc, card(disrupted("southern", "oxted-20261026"), notices = listOf(hc), name = "Hammersmith & City"))
    }

    @Test
    fun `a short station name is matched as a whole word`() {
        // Codex on #1237: Lee is too short for a substring key, but a notice naming it is about it.
        val lee = "Trains between Lee and Hither Green may be delayed (https://www.nationalrail.co.uk/service-disruptions/oxted-20261026/)."
        assertEquals(lee, card(disrupted("southern", "oxted-20261026"), notices = listOf(lee), name = "Lee Rail Station"))
        // "Leeds" isn't Lee.
        val leeds = "Trains to Leeds may be delayed (https://www.nationalrail.co.uk/service-disruptions/oxted-20261026/)."
        assertNull(card(disrupted("southern", "oxted-20261026"), notices = listOf(leeds), name = "Lee Rail Station"))
    }

    @Test
    fun `a card with every message covered goes`() {
        assertNull(card(disrupted("southern", "oxted-20261026"), notices = listOf(oxted)))
    }

    @Test
    fun `the pages a text links to are keyed as a status's are`() {
        assertEquals(
            listOf("nationalrail.co.uk/service-disruptions/oxted-20261026"),
            RailIncidents.pagesIn(oxted),
        )
        assertEquals(RailIncidents.linkOf("https://www.nationalrail.co.uk/service-disruptions/oxted-20261026/"), RailIncidents.pagesIn(oxted).single())
    }
}
