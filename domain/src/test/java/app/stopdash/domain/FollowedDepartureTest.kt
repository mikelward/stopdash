package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowedDepartureTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    // The Northern line's two trunks, enough for Highgate (north of the junction, where they merge)
    // and Kennington (a junction, where they stay two routes).
    private val topology = RouteTopology(
        mapOf(
            "northern" to listOf(
                RoutePattern("Bank", listOf(HBT, HGT, CTN, EUS, BNK, KNG, MDN), "High Barnet", "Morden"),
                RoutePattern("Charing X", listOf(HBT, HGT, CTN, MTC, EUS, CHX, KNG, MDN), "High Barnet", "Morden"),
            ),
        ),
    )

    private fun northern(destination: String, branch: String?, inSeconds: Long) =
        Departure("northern", "Northern", "northbound", destination, null, now.plusSeconds(inSeconds), "tube", branch)

    private fun row(stopId: String, vararg departures: Departure) = DepartureRows.across(
        listOf(StopArrivals(stopId = stopId, stopName = "Stop", departures = departures.toList(), fetchedAt = now)),
        now,
    ).first { it.upcoming.isNotEmpty() }

    /** The focus a tap on the card's route row for [destination] via [branch] hands the detail. */
    private fun tap(row: DepartureRow, destination: String, branch: String?, grouping: RouteTopology = topology): RouteFocus =
        RouteFocus.of(
            DepartureRows.destinationLines(row, 3, grouping)
                .first { group -> group.destination == destination && group.times.first().branch == branch },
        )

    @Test
    fun `no focus follows the soonest train`() {
        val kennington = row(KNG, northern("Edgware", "Charing X", 60), northern("High Barnet", "Bank", 120))
        assertEquals("Edgware", followedDeparture(kennington, null, topology)?.destination)
    }

    @Test
    fun `a tapped route follows its own soonest train, not the row's`() {
        val kennington = row(
            KNG,
            northern("Edgware", "Charing X", 60),
            northern("High Barnet", "Bank", 120),
            northern("High Barnet", "Charing X", 180),
        )
        val followed = followedDeparture(kennington, tap(kennington, "High Barnet", "Charing X"), topology)
        assertEquals("High Barnet", followed?.destination)
        assertEquals("Charing X", followed?.branch)
    }

    @Test
    fun `once a split-out route ends, it falls back to the soonest, not the other branch's row`() {
        val before = row(
            KNG,
            northern("Edgware", "Charing X", 60),
            northern("High Barnet", "Bank", 120),
            northern("High Barnet", "Charing X", 180),
        )
        val focus = tap(before, "High Barnet", "Charing X")
        // The Charing X train has gone; High Barnet via Bank is a separate row at Kennington.
        val after = row(KNG, northern("Edgware", "Charing X", 60), northern("High Barnet", "Bank", 120))
        assertEquals("Edgware", followedDeparture(after, focus, topology)?.destination)
    }

    @Test
    fun `a merged route keeps following its other branch`() {
        val before = row(HGT, northern("High Barnet", "Bank", 60), northern("High Barnet", "Charing X", 120))
        val focus = tap(before, "High Barnet", "Bank")
        // Past the junction the two trunks are one route row, so the Charing X train is still it.
        val after = row(HGT, northern("High Barnet", "Charing X", 120))
        assertEquals("Charing X", followedDeparture(after, focus, topology)?.branch)
    }

    @Test
    fun `a tap made before the topology loaded still follows the tapped route once it has`() {
        // Cold start: the card grouped without topology, so Bank and Charing X were two rows at
        // Highgate and the tap took the Bank one. The topology then loads and merges them.
        val before = row(HGT, northern("High Barnet", "Bank", 60), northern("High Barnet", "Charing X", 120))
        val focus = tap(before, "High Barnet", "Bank", grouping = RouteTopology.EMPTY)
        val after = row(HGT, northern("Edgware", null, 30), northern("High Barnet", "Charing X", 120))
        assertEquals("High Barnet", followedDeparture(after, focus, topology)?.destination)
        // And with no topology at all the branch alone identifies the route.
        assertEquals("Edgware", followedDeparture(after, focus, RouteTopology.EMPTY)?.destination)
    }

    @Test
    fun `a tapped route lists all its own departures, past the card's three`() {
        val kennington = row(
            KNG,
            northern("Edgware", "Charing X", 60),
            northern("High Barnet", "Charing X", 120),
            northern("High Barnet", "Bank", 180),
            northern("High Barnet", "Charing X", 300),
            northern("High Barnet", "Charing X", 480),
            northern("High Barnet", "Charing X", 660),
            northern("High Barnet", "Charing X", 840),
        )
        val times = routeDepartures(kennington, tap(kennington, "High Barnet", "Charing X"), topology)
        assertEquals(listOf(120L, 300L, 480L, 660L, 840L), times.map { it.expectedArrival.epochSecond - now.epochSecond })
    }

    @Test
    fun `a merged route lists both branches' departures`() {
        val highgate = row(HGT, northern("High Barnet", "Bank", 60), northern("High Barnet", "Charing X", 120))
        val times = routeDepartures(highgate, tap(highgate, "High Barnet", "Bank"), topology)
        assertEquals(listOf("Bank", "Charing X"), times.map { it.branch })
    }

    @Test
    fun `once the tapped route ends, the list follows the fallback train's route`() {
        val before = row(KNG, northern("Edgware", "Charing X", 60), northern("High Barnet", "Charing X", 180))
        val focus = tap(before, "High Barnet", "Charing X")
        val after = row(KNG, northern("Edgware", "Charing X", 60), northern("Edgware", "Charing X", 240))
        assertEquals(listOf("Edgware", "Edgware"), routeDepartures(after, focus, topology).map { it.destination })
    }

    @Test
    fun `the route's trains with no time go with its times, not another route's`() {
        // One platform, so one row with two destination lines.
        val toFar = Departure("great-example", "Great Example", "", "Far", "Platform 1", now.plusSeconds(300), "national-rail")
        val toNear = toFar.copy(destination = "Near", expectedArrival = now.plusSeconds(120))
        val farCanceled = UntimedTrain(toFar.copy(expectedArrival = now.plusSeconds(60)), canceled = true)
        val nearDelayed = UntimedTrain(toNear.copy(expectedArrival = now.plusSeconds(30)), canceled = false)
        val row = DepartureRows.across(
            listOf(StopArrivals("910GEXAMPLE", "Example", listOf(toFar, toNear), now, untimed = listOf(farCanceled, nearDelayed))),
            now,
        ).single()
        val far = RouteFocus.of(DepartureRows.destinationLines(row, 3).first { it.destination == "Far" })
        assertEquals(listOf(farCanceled), routeUntimed(row, far))
        // No focus follows the soonest train, to Near, and its route's own.
        assertEquals(listOf(nearDelayed), routeUntimed(row, null))
    }

    @Test
    fun `a route whose every train has no time follows its own train, never another route's`() {
        // One platform: a coming train to Far, and only a canceled one to Elsewhere.
        val toFar = Departure("great-example", "Great Example", "", "Far", "Platform 1", now.plusSeconds(300), "national-rail")
        val elsewhereCanceled = UntimedTrain(toFar.copy(destination = "Elsewhere", expectedArrival = now.plusSeconds(60)), canceled = true)
        val row = DepartureRows.across(
            listOf(StopArrivals("910GEXAMPLE", "Example", listOf(toFar), now, untimed = listOf(elsewhereCanceled))),
            now,
        ).single()
        val elsewhere = RouteFocus.of(DepartureRows.destinationLines(row, 3).single { it.destination == "Elsewhere" })
        assertEquals("Elsewhere", followedDeparture(row, elsewhere)?.destination)
        assertEquals(emptyList<Departure>(), routeDepartures(row, elsewhere))
        assertEquals(listOf(elsewhereCanceled), routeUntimed(row, elsewhere))
    }

    @Test
    fun `a row whose every train has no time follows its soonest by schedule`() {
        val canceled = UntimedTrain(
            Departure("great-example", "Great Example", "", "Far", "Platform 1", now.plusSeconds(60), "national-rail"),
            canceled = true,
        )
        val row = DepartureRows.across(listOf(StopArrivals("910GEXAMPLE", "Example", emptyList(), now, untimed = listOf(canceled))), now).single()
        assertEquals(canceled.train, followedDeparture(row, null))
        assertEquals(listOf(canceled), routeUntimed(row, null))
    }

    private companion object {
        const val HBT = "940GZZLUHBT"
        const val HGT = "940GZZLUHGT"
        const val CTN = "940GZZLUCTN"
        const val MTC = "940GZZLUMTC"
        const val EUS = "940GZZLUEUS"
        const val BNK = "940GZZLUBNK"
        const val CHX = "940GZZLUCHX"
        const val KNG = "940GZZLUKNG"
        const val MDN = "940GZZLUMDN"
    }
}
