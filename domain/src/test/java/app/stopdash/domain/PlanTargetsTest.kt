package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Well-known public station ids as examples; example bus stop ids. */
class PlanTargetsTest {
    private fun station(id: String, @Suppress("UNUSED_PARAMETER") name: String) = PlanTargets.Member(id, listOf(LineRef("rail", "Rail", "national-rail")))

    private fun bus(id: String) = PlanTargets.Member(id, listOf(LineRef("1", "1", "bus"), LineRef("2", "2", "bus")))

    @Test
    fun `plans to each station code and one bus stop`() {
        val members = listOf(
            station("940GZZLUKSX", "King's Cross St. Pancras"),
            station("910GKGX", "London King's Cross"),
            station("910GSTPX", "St Pancras International"),
            station("910GSTPXBL", "St Pancras International LL"),
            bus("490000001A"),
            bus("490000001B"),
            station("HUBKGX", "King's Cross"),
        )
        assertEquals(listOf("940GZZLUKSX", "910GKGX", "910GSTPX", "910GSTPXBL", "490000001A"), PlanTargets.of(members))
    }

    @Test
    fun `stations sharing a prefix stay apart`() {
        val members = listOf(station("940GZZLUWHP", "West Hampstead"), station("910GWHMDSTD", "West Hampstead Thameslink"))
        assertEquals(listOf("940GZZLUWHP", "910GWHMDSTD"), PlanTargets.of(members))
    }

    @Test
    fun `stations shortened to one name stay apart`() {
        val members = listOf(station("940GZZLUHSC", "Hammersmith"), station("940GZZLUHSD", "Hammersmith"))
        assertEquals(listOf("940GZZLUHSC", "940GZZLUHSD"), PlanTargets.of(members))
    }

    @Test
    fun `a code extending another's is still its own station`() {
        val members = listOf(station("910GABWD", "Abbey Wood"), station("910GABWDXR", "Abbey Wood"))
        assertEquals(listOf("910GABWD", "910GABWDXR"), PlanTargets.of(members))
    }

    @Test
    fun `an ordinary station is one target`() {
        assertEquals(listOf("940GZZLUOXC"), PlanTargets.of(listOf(station("940GZZLUOXC", "Oxford Circus"))))
    }

    @Test
    fun `a trip via a complex goes via each of its stations, not its bus stops`() {
        val members = listOf(
            bus("490000002A"),
            station("910GFNPK", "Finsbury Park"),
            station("940GZZLUFPK", "Finsbury Park"),
            station("HUBFPK", "Finsbury Park"),
        )
        assertEquals(listOf("910GFNPK", "940GZZLUFPK"), PlanTargets.via(members))
    }

    @Test
    fun `a trip via bus stops alone goes via one of them`() {
        assertEquals(listOf("490000002A"), PlanTargets.via(listOf(bus("490000002A"), bus("490000002B"))))
    }

    @Test
    fun `a trip via an ordinary station goes via it alone`() {
        assertEquals(listOf("940GZZLUOXC"), PlanTargets.via(listOf(station("940GZZLUOXC", "Oxford Circus"))))
    }

    @Test
    fun `a trip via a complex is keyed by the stations it goes via`() {
        val stops = PlanTargets.viaStops(listOf(station("910GFNPK", "Finsbury Park"), station("940GZZLUFPK", "Finsbury Park")))
        assertEquals(listOf("910GFNPK", "940GZZLUFPK"), stops.ids)
        assertEquals(" via 910GFNPK,940GZZLUFPK", stops.key)
    }

    @Test
    fun `a complex holding a station the trip goes to is still planned via`() {
        // Maintainer, 2026-10-16: only the destination itself is ignored, so to Finsbury Park's Underground station
        // via the interchange it belongs to asks via each of its stations, as any other complex.
        val members = listOf(station("910GFNPK", "Finsbury Park"), station("940GZZLUFPK", "Finsbury Park"), bus("490000002A"))
        assertEquals(listOf("910GFNPK", "940GZZLUFPK"), PlanTargets.viaStops(members).ids)
    }
}
