package app.stopdash.data

import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteStops
import app.stopdash.domain.railVia
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A National Rail board's "via" against a recorded `/Line/southern/Route/Sequence/outbound`, trimmed
 * to its two routes from London Victoria to Bognor Regis (public network data only): one inland by
 * Horsham and Arundel, one along the coast by Hove and Worthing. The destination alone can't tell
 * them apart; the via the board names can.
 */
class RouteSequenceViaTest {
    private val southern: LineSequence = Json { ignoreUnknownKeys = true }.decodeFromString<TflRouteSequenceDto>(
        checkNotNull(javaClass.getResource("/fixtures/route_sequence_southern_outbound_bognor.json")).readText(),
    ).toLineSequence()

    private fun resolve(via: String) = RouteStops.resolve(southern, VICTORIA, "Bognor Regis", null, "southern", via = railVia(via))

    private fun names(via: String) = (resolve(via) as? RouteStops.Resolution.Found)?.stops?.map { it.name }

    @Test
    fun `with no via the two ways to one terminus stay ambiguous`() {
        assertEquals(RouteStops.Resolution.Ambiguous(2), RouteStops.resolve(southern, VICTORIA, "Bognor Regis", null, "southern"))
    }

    @Test
    fun `a via picks the way that calls there, by TfL's own stop names`() {
        val inland = checkNotNull(names("via Horsham"))
        assertTrue("Arundel" in inland && "Hove" !in inland)
        assertEquals("Bognor Regis", inland.last())
        val coast = checkNotNull(names("via Hove"))
        assertTrue("Worthing" in coast && "Horsham" !in coast)
        // Two stations, in the order the train calls at them.
        assertEquals(inland, names("via Horsham & Arundel"))
    }

    @Test
    fun `a via both ways call at narrows nothing, and one out of order or off both fits neither`() {
        assertEquals(RouteStops.Resolution.Ambiguous(2), resolve("via Gatwick Airport"))
        assertEquals(RouteStops.Resolution.ViaMatchesNoRoute, resolve("via Arundel & Horsham"))
        assertEquals(RouteStops.Resolution.ViaMatchesNoRoute, resolve("via Guildford"))
    }

    private companion object {
        const val VICTORIA = "910GVICTRIC"
    }
}
