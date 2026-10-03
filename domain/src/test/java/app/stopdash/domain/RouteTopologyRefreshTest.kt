package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Reading TfL's current route patterns, and when they may stand in for the bundled ones (SPEC *Branch merging*). */
class RouteTopologyRefreshTest {

    private fun route(name: String, vararg stops: String) = LineRoute(name, stops.toList())

    @Test
    fun `a route's ends and via branch are read from its name`() {
        val patterns = routePatternsOf(
            listOf(
                route("Edgware  &harr;  Morden  via Bank", "E", "C", "B", "M"),
                route("Edgware  &harr;  Morden  via Charing Cross", "E", "C", "X", "M"),
                route("Edgware ↔ Battersea Power Station", "E", "C", "X", "P"),
            ),
        )
        assertEquals(
            listOf(
                RoutePattern("Bank", listOf("E", "C", "B", "M"), "Edgware", "Morden"),
                // TfL's "Charing Cross" normalized as the board shows it.
                RoutePattern("Charing X", listOf("E", "C", "X", "M"), "Edgware", "Morden"),
                RoutePattern(null, listOf("E", "C", "X", "P"), "Edgware", "Battersea Power Station"),
            ),
            patterns,
        )
    }

    @Test
    fun `a route run both ways is one pattern, a loop that differs each way is two`() {
        val patterns = routePatternsOf(
            listOf(
                route("Edgware  &harr;  Morden  via Bank", "E", "C", "B", "M"),
                route("Morden  &harr;  Edgware  via Bank", "M", "B", "C", "E"),
                route("Cockfosters  &harr;  Heathrow Terminal 4", "K", "H", "T"),
                route("Heathrow Terminal 4  &harr;  Cockfosters", "T", "L", "H", "K"),
            ),
        )
        assertEquals(3, patterns?.size)
        // The same stops the other way under another branch are a different route, not a repeat.
        assertEquals(
            2,
            routePatternsOf(
                listOf(route("Edgware  &harr;  Morden  via Bank", "E", "M"), route("Morden  &harr;  Edgware", "M", "E")),
            )?.size,
        )
    }

    @Test
    fun `a name that doesn't read as two ends leaves the whole line unread`() {
        assertNull(routePatternsOf(listOf(route("Edgware  &harr;  Morden", "E", "M"), route("Morden", "M", "E"))))
        assertNull(routePatternsOf(listOf(route("  &harr;  Morden", "E", "M"))))
        // Too few stops to be a route.
        assertNull(routePatternsOf(listOf(route("Edgware  &harr;  Morden", "E"))))
        assertNull(routePatternsOf(emptyList()))
    }

    private val bank = RoutePattern("Bank", listOf("E", "C", "B", "M"), "Edgware", "Morden")
    private val charingX = RoutePattern("Charing X", listOf("E", "C", "X", "M"), "Edgware", "Morden")
    private val bundled = RouteTopology(mapOf("northern" to listOf(bank, charingX)))

    @Test
    fun `current patterns covering every bundled route replace the line's`() {
        // A station added on the Bank branch, and a new terminus: both taken.
        val extended = bank.copy(stops = listOf("E", "C", "N", "B", "M"))
        val extension = RoutePattern(null, listOf("E", "C", "X", "P"), "Edgware", "Battersea Power Station")
        val refreshed = bundled.withLive(mapOf("northern" to listOf(extended, charingX, extension)))
        assertEquals(listOf(extended, charingX, extension), refreshed.patternsByLine["northern"])
        // Either way round, and however TfL spells the ends, still covers.
        assertTrue(covers(listOf(bank.copy(stops = bank.stops.reversed(), endA = "Morden", endB = "Edgware"), charingX), listOf(bank, charingX)))
    }

    @Test
    fun `an extension past a terminus it replaces is taken`() {
        // Edgware ↔ Morden via Bank becomes Edgware ↔ Newtown: the old ends no longer match, but the
        // new route still runs every stop of the old one, so no leg loses its branch.
        val extended = bank.copy(stops = bank.stops + "N", endB = "Newtown")
        assertTrue(covers(listOf(extended, charingX), listOf(bank, charingX)))
        assertEquals(listOf(extended, charingX), bundled.withLive(mapOf("northern" to listOf(extended, charingX))).patternsByLine["northern"])
        // Cut back short of a stop it ran: that leg would lose its pattern, so the bundled line stays.
        assertFalse(covers(listOf(bank.copy(stops = listOf("E", "C", "B")), charingX), listOf(bank, charingX)))
        // Rerouted so the old stops aren't run in order either way round.
        assertFalse(covers(listOf(bank.copy(stops = listOf("E", "B", "C", "M")), charingX), listOf(bank, charingX)))
    }

    @Test
    fun `current patterns missing a bundled route keep the bundled line`() {
        // One answer without the Charing X route: taking it would make the leg look single-path.
        assertFalse(covers(listOf(bank), listOf(bank, charingX)))
        val refreshed = bundled.withLive(mapOf("northern" to listOf(bank)))
        assertEquals(listOf(bank, charingX), refreshed.patternsByLine["northern"])
        // A branch renamed is a route missing too.
        assertFalse(covers(listOf(bank, charingX.copy(branch = "Charing Cross")), listOf(bank, charingX)))
    }

    @Test
    fun `a line the bundled data doesn't model is left out, and nothing live changes nothing`() {
        val other = RoutePattern(null, listOf("A", "B"), "Aldgate", "Barking")
        val refreshed = bundled.withLive(mapOf("district" to listOf(other)))
        assertEquals(setOf("northern"), refreshed.patternsByLine.keys)
        assertSame(bundled, bundled.withLive(emptyMap()))
    }

    private fun sequenceOf(vararg routes: LineRoute) = LineSequence(routes.toList(), emptyMap())

    @Test
    fun `current patterns are read for each bundled line through the route cache`() = runTest {
        val asked = mutableListOf<String>()
        val extended = listOf(
            route("Edgware  &harr;  Morden  via Bank", "E", "C", "N", "B", "M"),
            route("Edgware  &harr;  Morden  via Charing Cross", "E", "C", "X", "M"),
        )
        val routes = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    asked += "$lineId/$direction"
                    return sequenceOf(*extended.toTypedArray())
                }
            },
            // Both directions on the test's own thread: [asked] isn't safe to add to from two.
            compute = StandardTestDispatcher(testScheduler),
        )
        val warnings = mutableListOf<String>()
        val current = currentPatterns(bundled, routes) { warnings += it }
        assertEquals(setOf("northern"), current.keys)
        assertEquals(listOf("E", "C", "N", "B", "M"), current.getValue("northern")[0].stops)
        assertEquals(emptyList<String>(), warnings)
        // Both ways, once each; a second refresh answers from the cache.
        currentPatterns(bundled, routes)
        assertEquals(listOf("northern/inbound", "northern/outbound"), asked.sorted())
    }

    @Test
    fun `a line not fetched, unread, or missing a route is left out, and says so`() = runTest {
        fun repository(answer: () -> LineSequence) = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = answer()
            },
            compute = StandardTestDispatcher(testScheduler),
        )
        val cases = listOf(
            repository { throw TflException.RateLimited(null) } to "not fetched (RateLimited)",
            repository { sequenceOf(route("Morden", "M", "E")) } to "unreadable",
            repository { sequenceOf(route("Edgware  &harr;  Morden  via Bank", "E", "M")) } to "no longer run a bundled route",
        )
        for ((routes, why) in cases) {
            val warnings = mutableListOf<String>()
            assertEquals(why, emptyMap<String, List<RoutePattern>>(), currentPatterns(bundled, routes) { warnings += it })
            assertEquals(why, 1, warnings.size)
            assertTrue(warnings.single(), warnings.single().contains(why) && warnings.single().contains("northern"))
        }
    }

    @Test
    fun `a refreshed topology groups as the bundled one does on the same routes`() {
        val refreshed = bundled.withLive(mapOf("northern" to listOf(bank.copy(), charingX.copy())))
        for (stop in listOf("E", "C", "B", "X", "M")) {
            for (branch in listOf("Bank", "Charing X")) {
                assertEquals(
                    bundled.grouping("northern", stop, "Morden", branch),
                    refreshed.grouping("northern", stop, "Morden", branch),
                )
            }
        }
    }
}
