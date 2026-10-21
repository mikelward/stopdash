package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `a focus on a train's own way follows that way's train, and the place once none is left`() {
        // Two trains to Morden that TfL names no via for, one round each way a loop runs: one row's one destination.
        val clockwise = Departure("northern", "Northern", "northbound", "Morden", null, now.plusSeconds(60), "tube", via = "Bank")
        val anticlockwise = Departure("northern", "Northern", "northbound", "Morden", null, now.plusSeconds(120), "tube", via = "Charing Cross")
        val both = row(HGT, clockwise, anticlockwise)
        val way = RouteFocus("Morden", null, anticlockwise.wayKey)
        // The later one, on its own way, not the place's soonest.
        assertEquals(now.plusSeconds(120), followedDeparture(both, way, topology)?.expectedArrival)
        assertEquals(now.plusSeconds(120), focusedDeparture(both, way, topology)?.expectedArrival)
        // And with no way named, the place's soonest, as a tap on the row follows.
        assertEquals(now.plusSeconds(60), followedDeparture(both, RouteFocus("Morden", null), topology)?.expectedArrival)
        // Its way's train gone: the place's train, never nothing while one is left there.
        val left = row(HGT, clockwise)
        assertEquals(now.plusSeconds(60), followedDeparture(left, way, topology)?.expectedArrival)
    }

    @Test
    fun `a train's way is told by its direction, its platform's bound, its destination id and its via`() {
        val train = Departure("northern", "Northern", "outbound", "Morden", "Southbound - Platform 2", now, "tube", destinationId = MDN, via = "Bank")
        assertEquals(train.wayKey, train.copy(expectedArrival = now.plusSeconds(60), vehicleId = "001").wayKey)
        assertTrue(train.wayKey != train.copy(direction = "inbound").wayKey)
        assertTrue(train.wayKey != train.copy(platform = "Northbound - Platform 1").wayKey)
        assertTrue(train.wayKey != train.copy(destinationId = KNG).wayKey)
        assertTrue(train.wayKey != train.copy(via = "Charing Cross").wayKey)
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
    fun `a rail route lists every train to its terminus, whichever way round it runs`() {
        // A loop to one terminus both ways round, as the card groups them: the page lists them all, the
        // stop list following the soonest (maintainer, 2026-10-14).
        fun rail(via: String, inSeconds: Long) =
            Departure("south-western-railway", "SWR", "", "London Waterloo", "Platform 1", now.plusSeconds(inSeconds), "national-rail", via = via)
        val station = row("910GSTATION", rail("North", 60), rail("South", 120), rail("North", 180))
        val untimed = UntimedTrain(rail("South", 240), canceled = true)
        val withUntimed = station.copy(untimed = listOf(untimed, UntimedTrain(rail("North", 300), canceled = true)))
        assertEquals(listOf(60L, 120L, 180L), routeDepartures(withUntimed, null).map { it.expectedArrival.epochSecond - now.epochSecond })
        assertEquals(listOf("South", "North"), routeUntimed(withUntimed, null).map { it.train.via })
        // As many as the card shows on that route, and more.
        val card = DepartureRows.destinationLines(withUntimed, 3).single()
        assertTrue(routeDepartures(withUntimed, RouteFocus.of(card)).containsAll(card.times))
    }

    @Test
    fun `a route lists its trains on the stop's other platforms too`() {
        fun rail(destination: String, platform: String?, inSeconds: Long) =
            Departure("great-example", "Great Example", "", destination, platform, now.plusSeconds(inSeconds), "national-rail")
        val rows = DepartureRows.across(
            listOf(
                StopArrivals(
                    "910GEXAMPLE",
                    "Example",
                    listOf(
                        rail("Far", "Platform 1", 120), rail("Far", "Platform 1", 600),
                        // The same terminus from another platform, and from none named yet.
                        rail("Far", "Platform 3", 300), rail("Far", null, 900),
                        // The other way, from its own platform.
                        rail("Near", "Platform 2", 60),
                    ),
                    now,
                    untimed = listOf(UntimedTrain(rail("Far", "Platform 3", 420), canceled = true)),
                ),
                // Another stop's train to the same terminus isn't this stop's.
                StopArrivals("910GOTHER", "Other", listOf(rail("Far", "Platform 1", 200)), now),
            ),
            now,
        )
        val platform1 = rows.single { it.stopId == "910GEXAMPLE" && it.platform == "1" }
        val focus = RouteFocus.of(DepartureRows.destinationLines(platform1, 3).single())
        assertEquals(
            listOf(120L, 300L, 600L, 900L),
            routeDepartures(platform1, focus, siblings = rows).map { it.expectedArrival.epochSecond - now.epochSecond },
        )
        assertEquals(listOf(420L), routeUntimed(platform1, focus, siblings = rows).map { it.train.expectedArrival.epochSecond - now.epochSecond })
        // Without them, its own platform's alone, as before.
        assertEquals(listOf(120L, 600L), routeDepartures(platform1, focus).map { it.expectedArrival.epochSecond - now.epochSecond })
        // The other platforms' rows are picked out: never itself, another stop's, or another line's.
        val others = otherPlatforms(platform1, rows + platform1.copy(lineId = "other-line", directionKey = "Platform 9"))
        assertFalse(platform1 in others)
        assertTrue(others.all { it.stopId == "910GEXAMPLE" && it.lineId == "great-example" })
        assertEquals(setOf("2", "3", ""), others.map { it.platform }.toSet())
    }

    @Test
    fun `a tapped route gone from its platform is followed at another, before any other route`() {
        fun rail(destination: String, platform: String, inSeconds: Long) =
            Departure("great-example", "Great Example", "", destination, platform, now.plusSeconds(inSeconds), "national-rail")
        val canceled = UntimedTrain(rail("Elsewhere", "Platform 4", 30), canceled = true)
        val rows = DepartureRows.across(
            listOf(
                StopArrivals(
                    "910GEXAMPLE",
                    "Example",
                    // Platform 1 has only Near trains left; Far's leave from Platform 3.
                    listOf(rail("Near", "Platform 1", 60), rail("Far", "Platform 3", 300), rail("Far", "Platform 3", 600)),
                    now,
                    // And a route whose only train left is a canceled one at Platform 4.
                    untimed = listOf(canceled),
                ),
            ),
            now,
        )
        val platform1 = rows.single { it.platform == "1" }
        val far = RouteFocus("Far", null)
        assertEquals("Platform 3", followedDeparture(platform1, far, siblings = rows)?.platform)
        assertEquals(listOf(300L, 600L), routeDepartures(platform1, far, siblings = rows).map { it.expectedArrival.epochSecond - now.epochSecond })
        // The tapped route's train, never a sooner one of another route.
        assertEquals("Near", followedDeparture(platform1, RouteFocus("Near", null), siblings = rows)?.destination)
        // A route left with only a train with no time, at another platform: that train, not the row's.
        val elsewhere = RouteFocus("Elsewhere", null)
        assertEquals(canceled.train, followedDeparture(platform1, elsewhere, siblings = rows))
        assertEquals(listOf(canceled), routeUntimed(platform1, elsewhere, siblings = rows))
        // Nowhere at the stop: the row's soonest, as before.
        assertEquals("Near", followedDeparture(platform1, RouteFocus("Gone", null), siblings = rows)?.destination)
        // Without the stop's other rows, as before too.
        assertEquals("Near", followedDeparture(platform1, far)?.destination)
        assertEquals(null, focusedDeparture(platform1, far))
    }

    @Test
    fun `the route's soonest train is followed whichever platform it's at`() {
        fun rail(destination: String, platform: String, inSeconds: Long) =
            Departure("great-example", "Great Example", "", destination, platform, now.plusSeconds(inSeconds), "national-rail")
        // A Far train moved to Platform 3, sooner than Platform 1's own.
        val rows = DepartureRows.across(
            listOf(StopArrivals("910GEXAMPLE", "Example", listOf(rail("Far", "Platform 1", 600), rail("Far", "Platform 3", 300), rail("Near", "Platform 2", 60)), now)),
            now,
        )
        val platform1 = rows.single { it.platform == "1" }
        val far = RouteFocus("Far", null)
        assertEquals("Platform 3", followedDeparture(platform1, far, siblings = rows)?.platform)
        // Without the other rows, the row's own.
        assertEquals("Platform 1", followedDeparture(platform1, far)?.platform)
        assertEquals("Platform 1", focusedDeparture(platform1, far)?.platform)
    }

    @Test
    fun `a route's page moves to the platform it still leaves from, and closes once none does`() {
        fun rail(destination: String, platform: String, inSeconds: Long) =
            Departure("great-example", "Great Example", "", destination, platform, now.plusSeconds(inSeconds), "national-rail")
        // Platform 1's row is gone; Far still leaves from Platforms 3 and 4.
        val rows = DepartureRows.across(
            listOf(
                StopArrivals("910GEXAMPLE", "Example", listOf(rail("Far", "Platform 4", 600), rail("Far", "Platform 3", 300), rail("Near", "Platform 2", 60)), now),
                StopArrivals("910GOTHER", "Other", listOf(rail("Far", "Platform 1", 30)), now),
            ),
            now,
        )
        val far = RouteFocus("Far", null)
        // The platform with its soonest train, at its own stop, never another stop's.
        assertEquals("3", routeAnchor(rows, "910GEXAMPLE", "great-example", "", far)?.platform)
        // Its trains all gone: none, so the page closes.
        assertEquals(null, routeAnchor(rows, "910GEXAMPLE", "great-example", "", RouteFocus("Gone", null)))
        // Another line's or another way's rows are never it.
        assertEquals(null, routeAnchor(rows, "910GEXAMPLE", "other-line", "", far))
        assertEquals(null, routeAnchor(rows, "910GEXAMPLE", "great-example", "outbound", far))
        // A tube line TfL names no way for is told apart by its platform alone: no other platform is its.
        val circle = DepartureRows.across(
            listOf(
                StopArrivals(
                    "940GZZLUBST",
                    "Baker Street",
                    listOf(Departure("circle", "Circle", "", "Edgware Road (Circle Line)", "Eastbound - Platform 6", now.plusSeconds(60), "tube")),
                    now,
                ),
            ),
            now,
        )
        assertEquals(null, routeAnchor(circle, "940GZZLUBST", "circle", "", RouteFocus("Edgware Road (Circle Line)", null)))
    }

    @Test
    fun `a route's trains on another platform the other way are never its own`() {
        // A Circle line platform each way round, both trains "to Edgware Road": the way TfL names keeps them apart.
        fun circle(direction: String, platform: String, inSeconds: Long) =
            Departure("circle", "Circle", direction, "Edgware Road (Circle Line)", platform, now.plusSeconds(inSeconds), "tube")
        val rows = DepartureRows.across(
            listOf(
                StopArrivals(
                    "940GZZLUBST",
                    "Baker Street",
                    listOf(circle("outbound", "Westbound - Platform 5", 120), circle("inbound", "Eastbound - Platform 6", 60)),
                    now,
                ),
            ),
            now,
        )
        val westbound = rows.single { it.direction == "outbound" }
        assertEquals(listOf(120L), routeDepartures(westbound, null, siblings = rows).map { it.expectedArrival.epochSecond - now.epochSecond })
        // TfL leaving the way off both: each platform is its own way, so neither takes the other's.
        val wayless = DepartureRows.across(
            listOf(
                StopArrivals(
                    "940GZZLUBST",
                    "Baker Street",
                    listOf(circle("", "Westbound - Platform 5", 120), circle("", "Eastbound - Platform 6", 60)),
                    now,
                ),
            ),
            now,
        )
        val platform5 = wayless.single { it.platform == "5" }
        assertEquals(emptyList<DepartureRow>(), otherPlatforms(platform5, wayless))
        assertEquals(listOf(120L), routeDepartures(platform5, null, siblings = wayless).map { it.expectedArrival.epochSecond - now.epochSecond })
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
