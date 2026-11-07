package app.stopdash.ui

import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.LineRef
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.ModeIcon
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopGroup
import app.stopdash.domain.StopQualifier
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list grouped by station: its order ([stationOrdered]), a ride's station and trains ([rideStation], [stationRideRows]), a station's card ([stationCard]). */
class StationGroupsTest {
    private fun row(stopId: String, cluster: String) = DepartureRow(
        stopId = stopId,
        stopName = "Place",
        clusterId = cluster,
        lineId = "line",
        lineName = "Line",
        direction = "",
        directionKey = "k",
        destination = "Somewhere",
        mode = "tube",
        upcoming = emptyList(),
        fetchedAt = Instant.EPOCH,
    )

    private fun group(key: String, stopId: String, cluster: String) =
        StopGroup(stopName = "Place", key = key, showHeader = true, rows = listOf(row(stopId, cluster)), placeKey = cluster)

    @Test
    fun `a station's groups run together, nearest station first, with no starred band`() {
        // A starred platform lifted its farther station to the top; grouped by station, that station goes back to
        // its distance, the starred platform still leading it.
        val starred = group("b-starred", "B1", "B")
        val near = group("a", "A1", "A")
        val other = group("b-other", "B2", "B")
        val meters = mapOf("A" to 100.0, "B" to 300.0)
        val ordered = stationOrdered(listOf(starred, near, other)) { meters[it.placeKey] }
        assertEquals(listOf("a", "b-starred", "b-other"), ordered.map { it.key })
    }

    @Test
    fun `a place's Tube, DLR and Elizabeth stops are one station, its Overground, rail and buses others`() {
        // As the list's menu groups them (maintainer, 2026-10-30).
        assertEquals(stationKey("A", "tube"), stationKey("A", "dlr"))
        assertEquals(stationKey("A", "tube"), stationKey("A", "elizabeth-line"))
        assertFalse(stationKey("A", "overground") == stationKey("A", "national-rail"))
        assertFalse(stationKey("A", "elizabeth-line") == stationKey("A", "overground"))
        assertFalse(stationKey("A", "dlr") == stationKey("A", "bus"))
    }

    @Test
    fun `a station of two records is one station, its card every record's, as near as its nearest record`() {
        // Both filed under the station's least record ([StationCodeStopFinder]); the high-speed record's every row is under the
        // main one ([StationRows]), so it has no group, but it's still where the station is nearest.
        val high = StopGroup(stopName = "Place", key = "h", showHeader = true, rows = listOf(row("H1", "H1")), placeKey = "H1")
        val main = StopGroup(stopName = "Place", key = "m", showHeader = true, rows = listOf(row("M1", "H1")), placeKey = "H1")
        assertEquals(groupStation(high), groupStation(main))
        val station = groupStation(high)
        val layout = StationLayout.of(
            groups = listOf(high, main),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = emptyList(),
            starred = emptySet(),
            placeDistanceMeters = emptyMap(),
            stopClusterIds = emptyMap(),
            stopDistanceMeters = mapOf("H1" to 140.0, "M1" to 150.0),
            mergedStationMeters = mapOf(station to 100.0),
        )
        assertEquals(listOf("H1", "M1"), layout.cards.getValue(station).group.rows.map { it.stopId })
        assertEquals(100.0, layout.meters.getValue(station), 0.0)
    }

    @Test
    fun `a station of two records is headed by their shortest name, shortened as a station's is`() {
        // The low-level record nearest, as St Pancras's Thameslink platforms can be.
        val low = StopGroup(stopName = "Example International LL", key = "l", showHeader = true,
            rows = listOf(row("L1", "H1").copy(stopName = "Example International LL")), placeKey = "H1")
        val main = StopGroup(stopName = "Example International", key = "m", showHeader = true,
            rows = listOf(row("M1", "H1").copy(stopName = "Example International")), placeKey = "H1")
        val layout = StationLayout.of(
            groups = listOf(low, main),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = emptyList(),
            starred = emptySet(),
            placeDistanceMeters = emptyMap(),
            stopClusterIds = emptyMap(),
            stopDistanceMeters = mapOf("L1" to 100.0, "M1" to 150.0),
        )
        assertEquals("Example Intl", layout.names.getValue(groupStation(low)))
    }

    @Test
    fun `an unmeasured station goes last, and stations as near keep their order`() {
        val unmeasured = group("c", "C1", "C")
        val first = group("a", "A1", "A")
        val second = group("b", "B1", "B")
        val meters = mapOf("A" to 100.0, "B" to 100.0)
        val ordered = stationOrdered(listOf(unmeasured, first, second)) { meters[it.placeKey] }
        assertEquals(listOf("a", "b", "c"), ordered.map { it.key })
    }

    @Test
    fun `a ride's station is its origin's place, else its origin's stop area, else none, with its own mode`() {
        val journey = FavoriteJourney(JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras"), JourneyEnd("940GZZLUVIC", "Victoria"), listOf(LineRef("victoria", "", "tube")))
        val card = JourneyCard(journey, JourneyCardState.NotChecked(), boardingIds = listOf("940GZZLUKSX"))
        assertEquals("KX|tube", rideStation(card, mapOf("940GZZLUKSX" to "KX"), emptyMap()))
        // Every row of its origin moved onto its card: the origin's StopArea still finds the station.
        assertEquals("HUBKGX|tube", rideStation(card, emptyMap(), mapOf("940GZZLUKSX" to "HUBKGX")))
        assertNull(rideStation(card, mapOf("940GZZLUEUS" to "EUS"), emptyMap()))
        // Not yet placed: its saved origin.
        assertEquals("KX|tube", rideStation(card.copy(boardingIds = emptyList()), mapOf("940GZZLUKSX" to "KX"), emptyMap()))
        // A rail ride boarding at the same stop is the place's rail station, never its Underground one.
        assertEquals("KX|rail", rideStation(card.copy(journey = journey.copy(lines = journey.lines.map { it.copy(mode = "national-rail") })), mapOf("940GZZLUKSX" to "KX"), emptyMap()))
    }

    @Test
    fun `a ride's trains go to the card of the station each leaves from, its own where that isn't listed`() {
        val journey = FavoriteJourney(JourneyEnd("A1", "Place"), JourneyEnd("Z", "Far"), listOf(LineRef("line", "", "")))
        val trains = JourneyCardState.Trains(listOf(row("A1", "A"), row("B1", "B"), row("C1", "C")))
        val card = JourneyCard(journey, trains, boardingIds = listOf("A1", "B1", "C1"), destinationIds = setOf("Z"))
        val rows = stationRideRows(mapOf("A" to listOf(card))) { mapOf("A1" to "A", "B1" to "B")[it.stopId] }
        assertEquals(mapOf("A" to listOf("A1", "C1"), "B" to listOf("B1")), rows.byStation.mapValues { (_, pairs) -> pairs.map { it.first.stopId } })
        // Each opens as the ride's card would, with its far end.
        assertEquals(3, rows.ends.size)
        assertTrue(rows.ends.values.all { it == setOf("Z") })
        // Known by the row itself: the station's own row of the rest of that line's trains, the same in every field
        // a row is known by, is no ride's.
        val rideRow = rows.byStation.getValue("B").single().first
        val stationsOwn = rideRow.copy()
        assertEquals(rideRow.detailKey(), stationsOwn.detailKey())
        assertTrue(rideRow in rows.ends.keys)
        assertFalse(stationsOwn in rows.ends.keys)
        // Still checking, with nothing drawn: it keeps its own heading and card.
        val checking = card.copy(state = JourneyCardState.Checking("route"))
        assertTrue(stationRideRows(mapOf("A" to listOf(checking))) { mapOf("A1" to "A")[it.stopId] }.byStation.isEmpty())
        // Rides By station, one with more to say than its trains joins all the same, what it says going under the
        // station's heading, so the list stays grouped by station: a route it couldn't check, a far end it couldn't
        // check, a notice, more stops than it fetched, trains behind "+N more". Otherwise (By ride or By stop, or grouped
        // by platform) it keeps its heading, what it says under it.
        val saying = listOf(
            card.copy(state = trains.copy(incomplete = true)),
            card.copy(destinationUnchecked = true),
            card.copy(closures = listOf(listOf(row("Z", "Z")))),
            card.copy(state = trains.copy(capped = true)),
            card.copy(state = trains.copy(limit = 1)),
        )
        val joinedRows = stationRideRows(mapOf("A" to listOf(card))) { mapOf("A1" to "A")[it.stopId] }.byStation.mapValues { (_, p) -> p.size }
        saying.forEach { ride ->
            assertTrue(joinsStationCard(ride, sayingUnderStation = true))
            assertFalse(joinsStationCard(ride))
            // Its drawn trains; those behind "+N more" stay in their platforms' rows, its "More to …" under the heading.
            assertEquals(
                if (ride.state == trains.copy(limit = 1)) mapOf("A" to 1) else joinedRows,
                stationRideRows(mapOf("A" to listOf(ride)), sayingUnderStation = true) { mapOf("A1" to "A")[it.stopId] }.byStation.mapValues { (_, p) -> p.size },
            )
            assertTrue(stationRideRows(mapOf("A" to listOf(ride))) { mapOf("A1" to "A")[it.stopId] }.byStation.isEmpty())
        }
        assertTrue(joinsStationCard(card))
        // Checking again, holding the trains it drew last, it stays in the card rather than flash out to a heading of
        // its own each refresh.
        val again = card.copy(state = JourneyCardState.Checking("route", held = trains))
        assertTrue(joinsStationCard(again))
        assertEquals(
            stationRideRows(mapOf("A" to listOf(card))) { mapOf("A1" to "A")[it.stopId] }.byStation.mapValues { (_, p) -> p.map { it.first.stopId } },
            stationRideRows(mapOf("A" to listOf(again))) { mapOf("A1" to "A")[it.stopId] }.byStation.mapValues { (_, p) -> p.map { it.first.stopId } },
        )
        // A ride boarding nowhere listed leads the list instead.
        assertTrue(stationRideRows(mapOf(null to listOf(card))) { mapOf("A1" to "A")[it.stopId] }.byStation.isEmpty())
    }

    @Test
    fun `a faraway place's heading opens its station, else its stop area, never a stop's name`() {
        fun stop(id: String, name: String, cluster: String) = app.stopdash.domain.StopLocation(id, name, 0.0, 0.0, emptyList(), clusterId = cluster)
        fun place(stationId: String, vararg stops: app.stopdash.domain.StopLocation) =
            CollapsedPlaces.Place(key = "k", stationId = stationId, name = "Euston Road", meters = 600.0, lines = emptyList(), stops = stops.toList())
        assertEquals("940GZZLUEUS", fartherStationId(place("940GZZLUEUS")))
        assertEquals("490G000EXAMPLE", fartherStationId(place("", stop("490000001E", "Euston Road", "490G000EXAMPLE"))))
        // TfL gave no stop area: the cluster is the stop's name, which is no page, so the pole's own.
        assertEquals("490000001E", fartherStationId(place("", stop("490000001E", "Euston Road", "Euston Road"))))
        assertEquals("490000001E", fartherStationId(place("", stop("490000001E", "Euston Road", "Euston Rd Stop"))))
        assertEquals("490000001E", fartherStationId(place("", stop("490000001E", "Euston Road", ""))))
        assertNull(fartherStationId(place("")))
    }

    @Test
    fun `two lists read through as one, in order, without copying either`() {
        val first = mutableListOf("a", "b")
        val joined = Joined(first, listOf("c"))
        assertEquals(listOf("a", "b", "c"), joined.toList())
        assertEquals(3, joined.size)
        assertEquals("c", joined[2])
        // Read through: a change to either shows.
        first += "b2"
        assertEquals(listOf("a", "b", "b2", "c"), joined.toList())
    }

    @Test
    fun `a ride with only trains to change from is drawn under its station, each saying where to change`() {
        val journey = FavoriteJourney(JourneyEnd("A1", "Place"), JourneyEnd("Z", "Far"), listOf(LineRef("line", "", "")))
        val change = app.stopdash.domain.JourneyChange("FORK", "Fork", row("A1", "A"))
        val card = JourneyCard(journey, JourneyCardState.Trains(emptyList(), changes = listOf(change)), boardingIds = listOf("A1"), destinationIds = setOf("Z"))
        assertTrue(joinsStationCard(card))
        val rows = stationRideRows(mapOf("A" to listOf(card))) { mapOf("A1" to "A")[it.stopId] }
        val joined = rows.byStation.getValue("A").single().first
        assertEquals("A1", joined.stopId)
        assertEquals(setOf("Z"), rows.ends[joined])
        assertEquals("Fork", rows.changeAt[joined])
        assertEquals(listOf(journey.key), rows.rides.getValue("A").map { it.journey.key })
        // A direct train says nothing of changing.
        val direct = card.copy(state = JourneyCardState.Trains(listOf(row("A1", "A"))))
        val directRows = stationRideRows(mapOf("A" to listOf(direct))) { mapOf("A1" to "A")[it.stopId] }
        assertTrue(directRows.changeAt.isEmpty())
        assertEquals(listOf("A1"), directRows.rides.getValue("A").single().rows.map { it.first.stopId })
        // Its trains to change from all shown above by a nearer ride: it keeps its own card, which says so.
        assertFalse(joinsStationCard(card.copy(state = JourneyCardState.Trains(emptyList(), changes = listOf(change), repeatedChanges = setOf(0)))))
    }

    @Test
    fun `rides grouped By station lead their station's card, with no group of their own`() {
        val platform1 = StopGroup(
            stopName = "Near", key = "n1", showHeader = true, rows = listOf(row("N1", "N")), placeKey = "N",
            qualifier = StopQualifier.Platform("1", null),
        )
        val platform2 = StopGroup(
            stopName = "Near", key = "n2", showHeader = true, rows = listOf(row("N2", "N")), placeKey = "N",
            qualifier = StopQualifier.Platform("2", null),
        )
        val journey = FavoriteJourney(JourneyEnd("N1", "Near"), JourneyEnd("Z", "Far end"), listOf(LineRef("line", "", "")))
        // Its train, a row of its own (the list has already left it out of the platform's).
        val train = row("N2", "N").copy(destination = "Far end")
        val ride = JourneyCard(journey, JourneyCardState.Trains(listOf(train)), boardingIds = listOf("N2"), destinationIds = setOf("Z"))
        val layout = StationLayout.of(
            groups = listOf(platform2, platform1),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(ride),
            starred = emptySet(),
            placeDistanceMeters = mapOf("N" to 100.0),
            stopClusterIds = emptyMap(),
            rideGrouping = app.stopdash.domain.RideGrouping.STATION,
        )
        assertTrue(layout.rideGroups.isEmpty())
        val card = layout.cards.getValue("N|tube")
        // The ride's train first, the rest of its platform (2) straight after, then platform 1, each platform marked off.
        assertEquals(listOf("Far end", "Somewhere", "Somewhere"), card.group.rows.map { it.destination })
        assertEquals(listOf("N2", "N2", "N1"), card.group.rows.map { it.stopId })
        assertEquals(setOf(2), card.breaks)
        // Known as the ride's, to wear its star and open as its card would.
        assertTrue(card.group.rows.first() in layout.rideEnds.keys)
        assertFalse(card.group.rows[1] in layout.rideEnds.keys)
    }

    @Test
    fun `by station, a train a ride takes from a platform's row is drawn once, in the ride's rows`() {
        // A platform's row of two branches: the ride takes one branch's trains, the list keeps the row for the other's.
        fun train(minutes: Long, destination: String) =
            app.stopdash.domain.Departure("line", "Line", "", destination, null, Instant.EPOCH.plusSeconds(minutes * 60), "tube")
        val bank = listOf(train(0, "Far end"), train(3, "Far end"))
        val other = train(2, "Elsewhere")
        val platform = row("N1", "N").copy(upcoming = (bank + other).sortedBy { it.expectedArrival }, platform = "Platform 2")
        val group = StopGroup(stopName = "Place", key = "n1", showHeader = true, rows = listOf(platform), placeKey = "N")
        val journey = FavoriteJourney(JourneyEnd("N1", "Near"), JourneyEnd("Z", "Far end"), listOf(app.stopdash.domain.LineRef("line", "Line", "tube")))
        val ride = JourneyCard(
            journey,
            JourneyCardState.Trains(listOf(platform.copy(upcoming = bank, destination = "Far end"))),
            boardingIds = listOf("N1"),
            destinationIds = setOf("Z"),
        )
        val layout = StationLayout.of(
            groups = listOf(group),
            cardOf = { stopCard(it, RouteTopology.EMPTY) },
            journeyCards = listOf(ride),
            starred = emptySet(),
            placeDistanceMeters = mapOf("N" to 100.0),
            stopClusterIds = emptyMap(),
            rideGrouping = app.stopdash.domain.RideGrouping.STATION,
        )
        val trains = layout.cards.getValue("N|tube").group.rows.flatMap { it.upcoming }
        // Each train once: the ride's two, then the platform's other branch.
        assertEquals(bank + other, trains)
        // A platform with none of a ride's trains is the list's own card, untouched.
        assertNull(withoutRideTrains(group, emptyMap()))
        assertNull(withoutRideTrains(group, mapOf(("N1" to "other") to listOf(platform))))
        // And a row the ride takes every train of goes.
        assertEquals(emptyList<DepartureRow>(), withoutRideTrains(group, mapOf(("N1" to "line") to listOf(platform)))?.rows)
    }

    @Test
    fun `grouped by platform, a ride stays in its platforms only where the list shows every train of it`() {
        val journey = FavoriteJourney(JourneyEnd("N1", "Near"), JourneyEnd("Z", "Far end"), listOf(LineRef("line", "", "")))
        // Rows with trains: a line's status alone shows none of a ride's.
        fun train(minutes: Long) = app.stopdash.domain.Departure("line", "Line", "", "Somewhere", null, Instant.EPOCH.plusSeconds(minutes * 60), "tube")
        fun timed(stopId: String, vararg minutes: Long) = row(stopId, "N").copy(upcoming = minutes.map(::train))
        val listed = listOf(timed("N1", 2), timed("N2", 3))
        fun ride(vararg rows: DepartureRow) = JourneyCard(journey, JourneyCardState.Trains(rows.toList()), boardingIds = listOf("N1"), destinationIds = setOf("Z"))
        val shown = ride(timed("N1", 2))
        assertEquals(listOf(shown), ridesInPlatforms(listOf(shown), listed))
        // A train the list doesn't show (another stop's), or none yet: its own card.
        assertTrue(ridesInPlatforms(listOf(ride(timed("X1", 2))), listed).isEmpty())
        assertTrue(ridesInPlatforms(listOf(ride()), listed).isEmpty())
        assertTrue(ridesInPlatforms(listOf(shown), listOf(row("N1", "N"))).isEmpty())
        // A row with a train the ride can't take (another branch) would wear its star: its own card instead.
        assertTrue(ridesInPlatforms(listOf(shown), listOf(timed("N1", 2, 5))).isEmpty())
        // Nor where it has more to say than its trains: a closure, a far end it couldn't check, trains behind "+N more".
        assertTrue(ridesInPlatforms(listOf(shown.copy(closures = listOf(listOf(row("Z", "Z"))))), listed).isEmpty())
        assertTrue(ridesInPlatforms(listOf(shown.copy(destinationUnchecked = true)), listed).isEmpty())
        assertTrue(ridesInPlatforms(listOf(shown.copy(state = (shown.state as JourneyCardState.Trains).copy(incomplete = true))), listed).isEmpty())
        // A train to change from says where to change only on the ride's own card.
        val change = app.stopdash.domain.JourneyChange("FORK", "Fork", timed("N1", 2))
        val changing = shown.copy(state = JourneyCardState.Trains(emptyList(), changes = listOf(change)))
        assertTrue(ridesInPlatforms(listOf(changing), listed).isEmpty())
    }

    @Test
    fun `rides By station draw each notice once under their station's heading, never the station's own`() {
        val platform1 = StopGroup(
            stopName = "Near", key = "n1", showHeader = true, rows = listOf(row("N1", "N")), placeKey = "N",
            qualifier = StopQualifier.Platform("1", null),
        )
        fun ride(to: String, vararg notices: DepartureRow) = JourneyCard(
            FavoriteJourney(JourneyEnd("N1", "Near"), JourneyEnd(to, "Far end"), listOf(LineRef("line", "", ""))),
            JourneyCardState.Trains(listOf(row("N1", "N").copy(destination = "Far end"))),
            closures = notices.map { listOf(it) },
            boardingIds = listOf("N1"),
            destinationIds = setOf(to),
        )
        val farNotice = row("Z", "Z").copy(stopDisruption = "Minor delays")
        val ownNotice = row("N1", "N").copy(stopDisruption = "Lift out of service")
        val first = ride("Z", farNotice, ownNotice)
        val second = ride("Z2", farNotice)
        fun layout(grouping: app.stopdash.domain.RideGrouping, listed: List<DepartureRow> = emptyList()) = StationLayout.of(
            groups = listOf(platform1),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(first, second),
            starred = emptySet(),
            placeDistanceMeters = mapOf("N" to 100.0),
            stopClusterIds = emptyMap(),
            rideGrouping = grouping,
            closureRows = listed,
        )
        // The far end's notice once, under the first ride to have it; the station's own stop's never, the list showing it
        // already.
        fun texts(notices: List<Pair<List<DepartureRow>, String>>?) = notices.orEmpty().map { (rows, _) -> rows.first().stopDisruption }
        val notices = layout(app.stopdash.domain.RideGrouping.STATION, listOf(ownNotice)).rideNotices
        assertEquals(listOf("Minor delays"), texts(notices[first.journey.key]))
        assertEquals(emptyList<String?>(), texts(notices[second.journey.key]))
        // A boarding stop's notice the list doesn't show (a neighboring pole it doesn't have) is the ride's to show.
        assertEquals(listOf("Minor delays", "Lift out of service"), texts(layout(app.stopdash.domain.RideGrouping.STATION).rideNotices[first.journey.key]))
        // By ride, each ride keeps its own heading, its notices under it as ever.
        assertTrue(layout(app.stopdash.domain.RideGrouping.RIDE).rideNotices.isEmpty())
        // Each layout says which grouping it was worked out for, so one standing in for a new setting is drawn by its own.
        assertEquals(app.stopdash.domain.RideGrouping.STATION, layout(app.stopdash.domain.RideGrouping.STATION).rideGrouping)
        assertEquals(app.stopdash.domain.RideGrouping.RIDE, layout(app.stopdash.domain.RideGrouping.RIDE).rideGrouping)
        // And which rides it put in the station's card, so one standing in keeps a ride's placeholder it has no trains for.
        assertEquals(setOf(first.journey.key, second.journey.key), layout(app.stopdash.domain.RideGrouping.STATION).rideJoined)
        assertTrue(layout(app.stopdash.domain.RideGrouping.RIDE).rideJoined.isEmpty())
        // And the cards it drew them from, which the list draws: every ride under its station's heading By station; By ride,
        // only those not in a group of their own, here both, each with a notice to say beside its trains.
        assertEquals(listOf(first, second), layout(app.stopdash.domain.RideGrouping.STATION).stationRides.values.single())
        assertEquals(listOf(first, second), layout(app.stopdash.domain.RideGrouping.RIDE).stationRides.values.single())
    }

    @Test
    fun `rides grouped By stop have a group for each stop they board from, each headed by its stop`() {
        val platform1 = StopGroup(
            stopName = "Near", key = "n1", showHeader = true, rows = listOf(row("N1", "N")), placeKey = "N",
            qualifier = StopQualifier.Platform("1", null),
        )
        val journey = FavoriteJourney(JourneyEnd("N1", "Near"), JourneyEnd("Z", "Far end"), listOf(LineRef("line", "", "")))
        val trains = JourneyCardState.Trains(
            listOf(
                row("N1", "N").copy(destination = "Far end", platform = "Platform 1"),
                row("N2", "N").copy(destination = "Far end", platform = "Platform 2"),
            ),
        )
        val ride = JourneyCard(journey, trains, boardingIds = listOf("N1", "N2"), destinationIds = setOf("Z"))
        fun layout(grouping: app.stopdash.domain.RideGrouping) = StationLayout.of(
            groups = listOf(platform1),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(ride),
            starred = emptySet(),
            placeDistanceMeters = mapOf("N" to 100.0),
            stopClusterIds = emptyMap(),
            rideGrouping = grouping,
        )
        val byStop = layout(app.stopdash.domain.RideGrouping.STOP).rideGroups.getValue("N|tube")
        assertEquals(trains.groups.size, byStop.size)
        assertTrue(byStop.size > 1)
        // Each with that stop's trains alone, headed by that stop, kept apart from the others.
        assertEquals(trains.groups.map { group -> group.rows.map { it.stopId } }, byStop.map { part -> part.card.group.rows.map { it.stopId } })
        assertEquals(byStop.size, byStop.map { it.part }.distinct().size)
        assertTrue(byStop.all { it.fromForms.first().startsWith("Place") })
        assertEquals(byStop.size, byStop.map { it.fromForms.first() }.distinct().size)
        // A ride boarding from one stop there, By stop, is headed by that stop too, folding as one group.
        val single = JourneyCard(journey, JourneyCardState.Trains(listOf(trains.rows.first())), boardingIds = listOf("N1"), destinationIds = setOf("Z"))
        val one = StationLayout.of(
            groups = listOf(platform1),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(single),
            starred = emptySet(),
            placeDistanceMeters = mapOf("N" to 100.0),
            stopClusterIds = emptyMap(),
            rideGrouping = app.stopdash.domain.RideGrouping.STOP,
        ).rideGroups.getValue("N|tube").single()
        assertTrue(one.fromForms.first().startsWith("Place"))
        assertTrue(one.fromForms != single.fromForms)
        assertEquals("", one.part)
        // By ride, one group for them all.
        val byRide = layout(app.stopdash.domain.RideGrouping.RIDE).rideGroups.getValue("N|tube")
        assertEquals(1, byRide.size)
        assertEquals(2, byRide.single().card.group.rows.size)
        // Checking again, held from its last check, it keeps its groups, both ways.
        val again = ride.copy(state = JourneyCardState.Checking("route", held = trains))
        fun checkingLayout(grouping: app.stopdash.domain.RideGrouping) = StationLayout.of(
            groups = listOf(platform1),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(again),
            starred = emptySet(),
            placeDistanceMeters = mapOf("N" to 100.0),
            stopClusterIds = emptyMap(),
            rideGrouping = grouping,
        ).rideGroups.getValue("N|tube")
        assertEquals(1, checkingLayout(app.stopdash.domain.RideGrouping.RIDE).size)
        assertEquals(byStop.size, checkingLayout(app.stopdash.domain.RideGrouping.STOP).size)
    }

    @Test
    fun `the layout worked out on the worker orders the stations, places the rides and builds each card`() {
        val nearPlatform2 = StopGroup(
            stopName = "Near", key = "n2", showHeader = true, rows = listOf(row("N2", "N")), placeKey = "N",
            qualifier = StopQualifier.Platform("2", null),
        )
        val nearPlatform1 = StopGroup(
            stopName = "Near", key = "n1", showHeader = true, rows = listOf(row("N1", "N")), placeKey = "N",
            qualifier = StopQualifier.Platform("1", null),
        )
        val far = group("f", "F1", "F")
        val journey = FavoriteJourney(JourneyEnd("N1", "Near"), JourneyEnd("Z", "Far end"), listOf(LineRef("line", "", "")))
        val ride = JourneyCard(journey, JourneyCardState.Trains(listOf(row("N1", "N"))), boardingIds = listOf("N1"), destinationIds = setOf("Z"))
        val layout = StationLayout.of(
            groups = listOf(far, nearPlatform2, nearPlatform1),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(ride),
            starred = emptySet(),
            placeDistanceMeters = mapOf("N" to 100.0, "F" to 300.0),
            stopClusterIds = emptyMap(),
        )
        assertEquals(listOf("n2", "n1", "f"), layout.rank.entries.sortedBy { it.value }.map { it.key })
        assertEquals(mapOf(journey.key to "N|tube"), layout.rideStations)
        assertEquals(mapOf("N|tube" to 100.0, "F|tube" to 300.0), layout.meters)
        // The ride's train in its own group before the station's, the station's card its platforms by number.
        val rideGroup = layout.rideGroups.getValue("N|tube").single()
        assertEquals(journey.key, rideGroup.journey.key)
        assertEquals(listOf("N1"), rideGroup.card.group.rows.map { it.stopId })
        assertEquals(listOf(ModeIcon.TUBE), rideGroup.modes)
        assertEquals(listOf("N1", "N2"), layout.cards.getValue("N|tube").group.rows.map { it.stopId })
        assertEquals(setOf(1), layout.cards.getValue("N|tube").breaks)
        assertEquals(setOf("Z"), layout.rideEnds.values.single())
        assertTrue(rideGroup.card.group.rows.single() in layout.rideEnds.keys)
        assertFalse(layout.cards.getValue("N|tube").group.rows.first() in layout.rideEnds.keys)
    }

    @Test
    fun `rides By station, a ride boarding at no listed station has its trains, to change from too, starred as its own station's`() {
        // Headed by its station alone (maintainer, 2026-10-25), so its trains wear its star, and those to change from say
        // where to change as a station card's ride's do, with no heading of their own.
        val near = group("n", "N1", "N")
        val change = app.stopdash.domain.JourneyChange("FORK", "Fork", row("M1", "M").copy(destination = "Fork end"))
        val direct = JourneyCard(
            FavoriteJourney(JourneyEnd("M1", "Middle"), JourneyEnd("Z", "Far end"), listOf(LineRef("line", "", ""))),
            JourneyCardState.Trains(listOf(row("M1", "M").copy(destination = "Far end")), changes = listOf(change)),
            boardingIds = listOf("M1"),
        )
        fun layout(grouping: app.stopdash.domain.RideGrouping) = StationLayout.of(
            groups = listOf(near),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(direct),
            starred = emptySet(),
            placeDistanceMeters = mapOf("N" to 100.0),
            stopClusterIds = emptyMap(),
            rideGrouping = grouping,
        )
        val byStation = layout(app.stopdash.domain.RideGrouping.STATION)
        val trains = (direct.state as JourneyCardState.Trains)
        val changeRow = trains.changeStops.single().cards.single().group.rows.single()
        assertEquals(listOf(direct.journey.key), byStation.ridesAtop.map { it.journey.key })
        assertEquals(direct.journey, byStation.loneRideRows[trains.rideCard.group.rows.single()])
        assertEquals(direct.journey, byStation.loneRideRows[changeRow])
        assertEquals("Fork", byStation.rideChangeAt[changeRow])
        // By ride, its own heading says which ride: nothing starred here.
        assertTrue(layout(app.stopdash.domain.RideGrouping.RIDE).loneRideRows.isEmpty())
    }

    @Test
    fun `an opened faraway ride has its trains, to change from too, starred as the layout stars a near one's`() {
        // Not in the layout, which holds the near rides, so worked out apart ([FarRides]): headed by where it boards, rides
        // By station, its trains wear its star all the same (Codex, #1244).
        val change = app.stopdash.domain.JourneyChange("FORK", "Fork", row("F1", "F").copy(destination = "Fork end"))
        val far = JourneyCard(
            FavoriteJourney(JourneyEnd("F1", "Faraway"), JourneyEnd("Z", "Far end"), listOf(LineRef("line", "", ""))),
            JourneyCardState.Trains(listOf(row("F1", "F").copy(destination = "Far end")), changes = listOf(change)),
            boardingIds = listOf("F1"),
        )
        val rides = FarRides.of(listOf(far))
        val trains = far.state as JourneyCardState.Trains
        val changeRow = trains.changeStops.single().cards.single().group.rows.single()
        assertEquals(far, rides.cards[far.journey.key])
        assertEquals(far.journey, rides.rideRows[trains.rideCard.group.rows.single()])
        assertEquals(far.journey, rides.rideRows[changeRow])
        assertEquals("Fork", rides.rideChangeAt[changeRow])
    }

    @Test
    fun `a ride boarding at no listed station goes before the first station farther than it, not atop the list`() {
        // A ride whose stop is a farther place's was drawn atop the list, above nearer stations (bug report, 2026-10-24).
        val near = group("n", "N1", "N")
        val far = group("f", "F1", "F")
        fun ride(from: String, to: String) =
            JourneyCard(FavoriteJourney(JourneyEnd(from, from), JourneyEnd(to, to), listOf(LineRef("line", "", ""))), JourneyCardState.NotChecked(), boardingIds = listOf(from))
        val between = ride("M1", "Z")
        val beyond = ride("X1", "Z")
        val unmeasured = ride("U1", "Z")
        val atNear = ride("N1", "Z")
        val layout = StationLayout.of(
            groups = listOf(near, far),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(beyond, unmeasured, between, atNear),
            starred = emptySet(),
            placeDistanceMeters = mapOf("N" to 100.0, "F" to 1000.0),
            stopClusterIds = emptyMap(),
            rideMeters = mapOf(between.journey.key to 380.0, beyond.journey.key to 2000.0, atNear.journey.key to 100.0),
        )
        assertEquals(
            mapOf("F|tube" to listOf(between.journey.key), StationLayout.AFTER_STATIONS to listOf(beyond.journey.key)),
            layout.ridesBefore.mapValues { (_, cards) -> cards.map { it.journey.key } },
        )
        // Nearest first, as the stations are.
        assertEquals(listOf("F|tube", StationLayout.AFTER_STATIONS), layout.ridesBefore.keys.toList())
        // One at a listed station stays with it, and only one with no distance leads the list.
        assertEquals(mapOf(atNear.journey.key to "N|tube"), layout.rideStations)
        assertEquals(listOf(unmeasured.journey.key), layout.ridesAtop.map { it.journey.key })
    }

    @Test
    fun `a place's stations go by mode group, each heading one icon`() {
        val bus = StopGroup(stopName = "Place", key = "b", showHeader = true, rows = listOf(row("A3", "A").copy(mode = "bus")), placeKey = "A")
        val rail = StopGroup(
            stopName = "Place", key = "r", showHeader = true,
            rows = listOf(row("A2", "A").copy(mode = "national-rail"), row("A2", "A").copy(mode = "overground")), placeKey = "A",
        )
        val layout = StationLayout.of(
            groups = listOf(bus, group("t", "A1", "A"), rail, group("o", "B1", "B")),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = emptyList(),
            starred = emptySet(),
            placeDistanceMeters = mapOf("A" to 100.0, "B" to 200.0),
            stopClusterIds = emptyMap(),
        )
        assertEquals(
            mapOf("A|bus" to listOf(ModeIcon.BUS), "A|tube" to listOf(ModeIcon.TUBE), "A|rail" to listOf(ModeIcon.RAIL), "B|tube" to listOf(ModeIcon.TUBE)),
            layout.modes,
        )
        // Each of the place's stations as near as the place, in the order they came.
        assertEquals(listOf("b", "t", "r", "o"), layout.rank.entries.sortedBy { it.value }.map { it.key })
        assertEquals(listOf("A2", "A2"), layout.cards.getValue("A|rail").group.rows.map { it.stopId })
        // Its modes by name too, for a screen reader to tell the place's stations apart.
        assertEquals(listOf("national-rail", "overground"), layout.modeIds.getValue("A|rail"))
    }

    @Test
    fun `a ride boarding at a stop not listed goes under its own mode's station at its place, or none`() {
        val bus = StopGroup(stopName = "Place", key = "b", showHeader = true, rows = listOf(row("A3", "A").copy(mode = "bus")), placeKey = "A")
        val rail = StopGroup(stopName = "Place", key = "r", showHeader = true, rows = listOf(row("A2", "A").copy(mode = "national-rail")), placeKey = "A")
        fun ride(mode: String) = JourneyCard(
            FavoriteJourney(JourneyEnd("A9", "Place"), JourneyEnd("Z", "Far end"), listOf(LineRef(mode, "", mode))),
            JourneyCardState.Trains(emptyList()),
            boardingIds = listOf("A9"),
        )
        val nationalRail = ride("national-rail")
        val overground = ride("overground")
        val tube = ride("tube")
        val layout = StationLayout.of(
            groups = listOf(bus, rail),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(nationalRail, overground, tube),
            starred = emptySet(),
            placeDistanceMeters = mapOf("A" to 100.0),
            stopClusterIds = mapOf("A9" to "A"),
        )
        // National Rail goes under rail's station; no Overground or Underground station is listed, so no station.
        assertEquals(mapOf(nationalRail.journey.key to "A|rail"), layout.rideStations)
    }

    @Test
    fun `a ride's train leaving from a held or opened farther place joins its own station's card, which is drawn`() {
        val own = group("a", "A1", "A")
        val heldPlace = group("h", "H1", "H")
        val fartherPlace = group("f", "F1", "F")
        val journey = FavoriteJourney(JourneyEnd("A1", "Place"), JourneyEnd("Z", "Far end"), listOf(LineRef("line", "", "")))
        val ride = JourneyCard(
            journey,
            JourneyCardState.Trains(listOf(row("A1", "A"), row("H1", "H"), row("F1", "F"))),
            boardingIds = listOf("A1", "H1", "F1"),
        )
        val farther = FartherCard(
            CollapsedPlaces.Place("farther:F", "F1", "Farther", 900.0, emptyList()),
            FartherLoad.Open(emptyList(), mapOf("F1" to 900.0)),
        )
        val held = PendingPlace(CollapsedPlaces.Place("pending:H", "H1", "Held", 200.0, emptyList()), distanced = true, stopIds = setOf("H1"))
        assertEquals(setOf("H", "F"), offCardPlaces(listOf(own, heldPlace, fartherPlace), listOf(farther), listOf(held)))
        val layout = StationLayout.of(
            groups = listOf(own, heldPlace, fartherPlace),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(ride),
            starred = emptySet(),
            placeDistanceMeters = mapOf("A" to 100.0, "H" to 200.0, "F" to 900.0),
            stopClusterIds = emptyMap(),
            farther = listOf(farther),
            held = listOf(held),
        )
        // All three of its trains in its group before its own station; the others' cards have only their own rows.
        assertEquals(listOf("A1", "H1", "F1"), layout.rideGroups.getValue("A|tube").single().card.group.rows.map { it.stopId })
        assertEquals(listOf("A1"), layout.cards.getValue("A|tube").group.rows.map { it.stopId })
        assertEquals(listOf("H1"), layout.cards.getValue("H|tube").group.rows.map { it.stopId })
        assertEquals(3, layout.rideEnds.size)
        // A ride boarding at a held place itself joins no card: it leads the list with its own heading.
        val fromHeld = ride.copy(journey = journey.copy(from = JourneyEnd("H1", "Held")), boardingIds = listOf("H1"))
        val heldLayout = StationLayout.of(
            listOf(own, heldPlace), { group -> StopCard(group, group.rows.map { emptyList() }) }, listOf(fromHeld),
            emptySet(), emptyMap(), emptyMap(), held = listOf(held),
        )
        assertTrue(heldLayout.rideStations.isEmpty())
        assertTrue(heldLayout.rideEnds.isEmpty())
    }

    @Test
    fun `a station checks for a closure while any of its platforms does, and only then`() {
        // Grouped by station there's no platform heading to show the spinner on: the station's heading shows it.
        val a1 = group("a1", "A1", "A")
        val a2 = group("a2", "A2", "A")
        val b = group("b", "B1", "B")
        val groups = listOf(a1, a2, b)
        assertEquals(setOf(groupStation(a1)), checkingStations(groups, setOf("A2")))
        assertEquals(setOf(groupStation(a1), groupStation(b)), checkingStations(groups, setOf("A1", "B1")))
        assertTrue(checkingStations(groups, setOf("Z9")).isEmpty())
        assertTrue(checkingStations(groups, emptySet()).isEmpty())
    }

    @Test
    fun `a list's places read the same across a refresh of their departures, and differ when a place comes or goes`() {
        val before = ListCards.of(listOf(row("A1", "A"), row("B1", "B")), warningsLead = false, RouteTopology.EMPTY)
        val refreshed = ListCards.of(listOf(row("A1", "A").copy(destination = "Elsewhere"), row("B1", "B")), warningsLead = false, RouteTopology.EMPTY)
        val moved = ListCards.of(listOf(row("A1", "A"), row("C1", "C")), warningsLead = false, RouteTopology.EMPTY)
        assertEquals(before.places, refreshed.places)
        assertTrue(before.places != moved.places)
    }

    @Test
    fun `a list carries on from the last while it keeps every place, so the last stations may stand in for it`() {
        fun list(vararg places: String, last: ListCards? = null) =
            ListCards.of(places.map { row("${it}1", it) }, warningsLead = false, RouteTopology.EMPTY, last = last)
        val first = list("A", "B")
        val refreshed = list("A", "B", last = first)
        val opened = list("A", "B", "C", last = refreshed)
        // The same places, or more: the earlier lists' stations stand in, through any number of them.
        assertTrue(refreshed.grownFrom(first))
        assertTrue(opened.grownFrom(refreshed))
        assertTrue(opened.grownFrom(first))
        // Never the other way, nor once a place has gone, nor for a list worked out apart from them.
        assertFalse(first.grownFrom(opened))
        val moved = list("A", "C", last = opened)
        assertFalse(moved.grownFrom(opened))
        assertFalse(moved.grownFrom(first))
        assertFalse(list("A", "B").grownFrom(first))
    }

    @Test
    fun `a station's card leads with the platforms of its starred rows and rides' trains, then the others by number`() {
        val platform5 = StopGroup(
            stopName = "Place", key = "p5", showHeader = true, rows = listOf(row("S5", "S")), placeKey = "S",
            qualifier = StopQualifier.Platform("5", null),
        )
        val platform1 = StopGroup(
            stopName = "Place", key = "p1", showHeader = true, rows = listOf(row("S1a", "S"), row("S1b", "S")), placeKey = "S",
            qualifier = StopQualifier.Platform("1", null),
        )
        val ride = StopGroup(stopName = "Place", key = "ride", showHeader = false, rows = listOf(row("R", "S")), placeKey = "S")
        fun card(group: StopGroup) = StopCard(group, group.rows.map { emptyList() })
        // The platforms came soonest first (5 before 1); the card lists them by number, a heavier line before each.
        val plain = stationCard(emptyList(), listOf(card(platform5), card(platform1)), starred = emptySet())
        assertEquals(listOf("S1a", "S1b", "S5"), plain.group.rows.map { it.stopId })
        assertEquals(3, plain.lines.size)
        assertEquals(setOf(2), plain.breaks)
        // A ride's train boarding no platform here leads; then a starred row's platform, its starred row first and its
        // other trains straight after; then the rest, each a block of its own.
        val led = stationCard(listOf(card(ride)), listOf(card(platform5), card(platform1)), starred = setOf(StarredRow.of(platform1.rows[1])))
        assertEquals(listOf("R", "S1b", "S1a", "S5"), led.group.rows.map { it.stopId })
        assertEquals(setOf(1, 3), led.breaks)
        // A ride's train leaving from platform 5 brings platform 5 to the top, its other trains under the ride's, then 1.
        val fromFive = StopGroup(stopName = "Place", key = "ride", showHeader = false, rows = listOf(row("S5", "S").copy(destination = "Far")), placeKey = "S")
        val floated = stationCard(listOf(card(fromFive)), listOf(card(platform5), card(platform1)), starred = emptySet())
        assertEquals(listOf("Far", "Somewhere", "Somewhere", "Somewhere"), floated.group.rows.map { it.destination })
        assertEquals(listOf("S5", "S5", "S1a", "S1b"), floated.group.rows.map { it.stopId })
        assertEquals(setOf(2), floated.breaks)
        // A ride taking every train from platform 2 of a stop whose platform 1 is still listed: its own block, never
        // platform 1's, which isn't a favorite's.
        val stopPlatform1 = StopGroup(
            stopName = "Place", key = "q1", showHeader = true, rows = listOf(row("Q", "S").copy(platform = "Platform 1")), placeKey = "S",
            qualifier = StopQualifier.Platform("1", null),
        )
        val fromTwo = StopGroup(stopName = "Place", key = "ride", showHeader = false, rows = listOf(row("Q", "S").copy(platform = "Platform 2", destination = "Far")), placeKey = "S")
        val apart = stationCard(listOf(card(fromTwo)), listOf(card(stopPlatform1), card(platform5)), starred = emptySet())
        assertEquals(listOf("Platform 2", "Platform 1", ""), apart.group.rows.map { it.platform })
        assertEquals(setOf(1, 2), apart.breaks)
        // Northbound and Southbound groups of one stop, neither naming a platform: a ride's train matches neither by
        // guess, so it stays its own block rather than join the other direction's trains.
        val north = StopGroup(stopName = "Place", key = "n", showHeader = true, rows = listOf(row("D", "S").copy(destination = "North")), placeKey = "S")
        val south = StopGroup(stopName = "Place", key = "s", showHeader = true, rows = listOf(row("D", "S").copy(destination = "South")), placeKey = "S")
        val southRide = StopGroup(stopName = "Place", key = "ride", showHeader = false, rows = listOf(row("D", "S").copy(destination = "Far")), placeKey = "S")
        val compass = stationCard(listOf(card(southRide)), listOf(card(north), card(south)), starred = emptySet())
        assertEquals("Far", compass.group.rows.first().destination)
        assertEquals(setOf(1, 2), compass.breaks)
        // A lone platform is its own card.
        val lone = card(platform5)
        assertTrue(stationCard(emptyList(), listOf(lone), starred = emptySet()) === lone)
    }

}
