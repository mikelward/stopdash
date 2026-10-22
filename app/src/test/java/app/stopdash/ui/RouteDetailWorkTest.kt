package app.stopdash.ui

import app.stopdash.ThreadRecorder
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.domain.RouteFocus
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopQualifier
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The route page's trains and termini are worked out on a worker before it reads them (AGENTS.md
 * *Main thread*), never in composition. Synthetic stops only.
 */
class RouteDetailWorkTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun departure(destination: String, offsetSeconds: Long) = Departure(
        lineId = "victoria",
        lineName = "Victoria",
        direction = "outbound",
        destination = destination,
        platform = null,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = "tube",
    )

    private val row = DepartureRow(
        stopId = "940GA",
        stopName = "Example Stop",
        lineId = "victoria",
        lineName = "Victoria",
        direction = "outbound",
        directionKey = "outbound",
        destination = "Brixton",
        mode = "tube",
        upcoming = listOf(departure("Brixton", 120), departure("Stockwell", 240), departure("Brixton", 360)),
        fetchedAt = now,
    )

    @Test
    fun `the route's trains and termini are worked out on the worker`() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val base = pool.asCoroutineDispatcher()
            val ranOn = ThreadRecorder()
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) =
                    base.dispatch(context) { ranOn.note(); block.run() }
            }
            val work = runBlocking { routeDetailWork(row, focus = null, RouteTopology.EMPTY, worker) }
            assertEquals(setOf("test-worker"), ranOn.threads().toSet())
            // With no route tapped, the page names every terminus the row runs to.
            assertEquals(listOf("Brixton", "Stockwell"), work.destinations)
            // A tapped route lists only its own trains, and names only its terminus.
            val tapped = runBlocking { routeDetailWork(row, RouteFocus("Brixton", null), RouteTopology.EMPTY, worker) }
            assertEquals(listOf(120L, 360L), tapped.departures.map { it.expectedArrival.epochSecond - now.epochSecond })
            assertEquals(listOf("Brixton"), tapped.destinations)
            // Its only terminus in the forms the title shortens it through, worked out with the rest; none for two.
            assertEquals(DestinationAbbreviations.forms("Brixton"), tapped.titleForms)
            assertEquals(null, work.titleForms)
            // Whether any train is grayed is found there too, so the page only walks them to gray one.
            assertEquals(false, work.grayed)
            val withDoubtful = row.copy(upcoming = row.upcoming.mapIndexed { i, d -> if (i == 2) d.copy(doubtful = true) else d })
            assertEquals(true, runBlocking { routeDetailWork(withDoubtful, focus = null, RouteTopology.EMPTY, worker) }.grayed)
            // Where it's boarded is found there too: the followed train's platform, or a bus's pole.
            assertEquals(null, work.boarding)
            val platformed = row.copy(upcoming = row.upcoming.map { it.copy(platform = "Southbound - Platform 2") })
            assertEquals(
                StopQualifier.Platform("2", null),
                runBlocking { routeDetailWork(platformed, focus = null, RouteTopology.EMPTY, worker) }.boarding,
            )
            val bus = platformed.copy(mode = "bus", stopLetter = "K", upcoming = platformed.upcoming.map { it.copy(mode = "bus") })
            assertEquals(StopQualifier.BusStop("K", null), runBlocking { routeDetailWork(bus, focus = null, RouteTopology.EMPTY, worker) }.boarding)
            // Either of a close pair is grayed too.
            val withPaired = row.copy(upcoming = row.upcoming.mapIndexed { i, d -> if (i == 2) d.copy(paired = true) else d })
            assertEquals(true, runBlocking { routeDetailWork(withPaired, focus = null, RouteTopology.EMPTY, worker) }.grayed)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `the route's trains on the stop's other platforms are listed, the stop named alone when they span two`() {
        fun rail(destination: String, platform: String?, minutes: Long, via: String = "") = Departure(
            "great-example", "Great Example", "", destination, platform, now.plusSeconds(minutes * 60), "national-rail", via = via,
        )
        fun rowsOf(vararg trains: Departure) =
            DepartureRows.across(listOf(StopArrivals("910GEXAMPLE", "Example", trains.toList(), now)), now)
        // One platform, a loop's two ways round to one terminus: every train, and its platform named.
        val loop = rowsOf(rail("Far", "Platform 1", 11, "North"), rail("Far", "Platform 1", 32, "South"), rail("Far", "Platform 1", 41, "North"))
        val one = runBlocking { routeDetailWork(loop.single(), RouteFocus("Far", null), RouteTopology.EMPTY, Dispatchers.Unconfined, loop) }
        assertEquals(listOf(11L, 32L, 41L), one.departures.map { (it.expectedArrival.epochSecond - now.epochSecond) / 60 })
        assertEquals(StopQualifier.Platform("1", null), one.boarding)
        // Another platform's train to it, and another way's on a third: listed with them, the platform left unsaid.
        val spread = rowsOf(rail("Far", "Platform 1", 11), rail("Far", "Platform 3", 20), rail("Near", "Platform 2", 5), rail("Far", "Platform 1", 41))
        val tapped = spread.single { it.platform == "1" }
        val work = runBlocking { routeDetailWork(tapped, RouteFocus("Far", null), RouteTopology.EMPTY, Dispatchers.Unconfined, spread) }
        assertEquals(listOf(11L, 20L, 41L), work.departures.map { (it.expectedArrival.epochSecond - now.epochSecond) / 60 })
        assertEquals(null, work.boarding)
        // Another of its trains in a row with no platform known: any may be its, so none is named.
        val unknown = rowsOf(rail("Far", "Platform 1", 11), rail("Far", null, 30), rail("Far", "Platform 1", 41))
        val unsure = runBlocking {
            routeDetailWork(unknown.single { it.platform == "1" }, RouteFocus("Far", null), RouteTopology.EMPTY, Dispatchers.Unconfined, unknown)
        }
        assertEquals(listOf(11L, 30L, 41L), unsure.departures.map { (it.expectedArrival.epochSecond - now.epochSecond) / 60 })
        assertEquals(null, unsure.boarding)
        // A sooner train on the route at another platform: followed, the page naming no one platform for both.
        val sooner = rowsOf(rail("Far", "Platform 1", 20), rail("Far", "Platform 3", 9))
        val soonest = runBlocking { routeDetailWork(sooner.single { it.platform == "1" }, RouteFocus("Far", null), RouteTopology.EMPTY, Dispatchers.Unconfined, sooner) }
        assertEquals("Platform 3", soonest.followed?.platform)
        assertEquals(listOf(9L, 20L), soonest.departures.map { (it.expectedArrival.epochSecond - now.epochSecond) / 60 })
        assertEquals(null, soonest.boarding)
        // The tapped route gone from its platform: followed at the other, which the page then names.
        val gone = rowsOf(rail("Near", "Platform 1", 4), rail("Far", "Platform 3", 9))
        val moved = runBlocking { routeDetailWork(gone.single { it.platform == "1" }, RouteFocus("Far", null), RouteTopology.EMPTY, Dispatchers.Unconfined, gone) }
        assertEquals("Platform 3", moved.followed?.platform)
        assertEquals(listOf("Far"), moved.destinations)
        assertEquals(StopQualifier.Platform("3", null), moved.boarding)
        // The other way's platform lists none of the route's, so its own still names its platform.
        val near = runBlocking { routeDetailWork(spread.single { it.platform == "2" }, null, RouteTopology.EMPTY, Dispatchers.Unconfined, spread) }
        assertEquals(StopQualifier.Platform("2", null), near.boarding)
    }

    @Test
    fun `the page follows the work's soonest train at any platform, else the row's own until it's in`() {
        fun rail(destination: String, platform: String, minutes: Long) = Departure(
            "great-example", "Great Example", "", destination, platform, now.plusSeconds(minutes * 60), "national-rail",
        )
        val rows = DepartureRows.across(
            listOf(StopArrivals("910GEXAMPLE", "Example", listOf(rail("Near", "Platform 1", 4), rail("Far", "Platform 3", 6), rail("Far", "Platform 1", 9)), now)),
            now,
        )
        val platform1 = rows.single { it.platform == "1" }
        val atThree = rows.single { it.platform == "3" }.upcoming.single()
        val far = RouteFocus("Far", null)
        val ask = Any()
        val worked = ask to RouteDetailWork.NONE.copy(followed = atThree)
        // The work's answer, Platform 3's sooner Far train, over the row's own later one: the route's, not the platform's.
        assertEquals(atThree, pageFollowed(platform1, far, RouteTopology.EMPTY, worked, ask, siblings = true))
        // Before the work's first answer: the row's own Far train, never its Near one.
        assertEquals(platform1.upcoming[1], pageFollowed(platform1, far, RouteTopology.EMPTY, null, ask, siblings = true))
        // An answer for an earlier ask (a row since refreshed) may follow a train that has gone: the row's own,
        // which is current, until this ask's is in (Codex, #854).
        assertEquals(platform1.upcoming[1], pageFollowed(platform1, far, RouteTopology.EMPTY, worked, Any(), siblings = true))
        // No other rows to look in: the row's own, whatever was worked.
        assertEquals(platform1.upcoming[1], pageFollowed(platform1, far, RouteTopology.EMPTY, worked, ask, siblings = false))
        // No route tapped: the row's soonest.
        assertEquals("Near", pageFollowed(platform1, null, RouteTopology.EMPTY, worked, ask, siblings = true)?.destination)
        // Its Far trains gone with no answer yet: the row's soonest, as before.
        val nearOnly = platform1.copy(upcoming = platform1.upcoming.take(1))
        assertEquals("Near", pageFollowed(nearOnly, far, RouteTopology.EMPTY, null, ask, siblings = true)?.destination)
    }

    @Test
    fun `a status row has no termini`() {
        val status = row.copy(upcoming = emptyList())
        val work = runBlocking { routeDetailWork(status, focus = null, RouteTopology.EMPTY, kotlinx.coroutines.Dispatchers.Unconfined) }
        assertEquals(RouteDetailWork.NONE, work)
    }
}
