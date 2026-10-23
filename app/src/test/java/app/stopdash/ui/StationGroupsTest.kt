package app.stopdash.ui

import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.FavoriteJourney
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
        val journey = FavoriteJourney(JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras"), JourneyEnd("940GZZLUVIC", "Victoria"), "victoria", mode = "tube")
        val card = JourneyCard(journey, JourneyCardState.NotChecked(), boardingIds = listOf("940GZZLUKSX"))
        assertEquals("KX|TUBE", rideStation(card, mapOf("940GZZLUKSX" to "KX"), emptyMap()))
        // Every row of its origin moved onto its card: the origin's StopArea still finds the station.
        assertEquals("HUBKGX|TUBE", rideStation(card, emptyMap(), mapOf("940GZZLUKSX" to "HUBKGX")))
        assertNull(rideStation(card, mapOf("940GZZLUEUS" to "EUS"), emptyMap()))
        // Not yet placed: its saved origin.
        assertEquals("KX|TUBE", rideStation(card.copy(boardingIds = emptyList()), mapOf("940GZZLUKSX" to "KX"), emptyMap()))
        // A rail ride boarding at the same stop is the place's rail station, never its Underground one.
        assertEquals("KX|RAIL", rideStation(card.copy(journey = journey.copy(mode = "national-rail")), mapOf("940GZZLUKSX" to "KX"), emptyMap()))
    }

    @Test
    fun `a ride's trains go to the card of the station each leaves from, its own where that isn't listed`() {
        val journey = FavoriteJourney(JourneyEnd("A1", "Place"), JourneyEnd("Z", "Far"), "line")
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
        // As does one with more to say than its trains, so what it says stays with them under its heading: a
        // route it couldn't check, a far end it couldn't check, a closure, a train held again while it checks.
        val saying = listOf(
            card.copy(state = trains.copy(incomplete = true)),
            card.copy(destinationUnchecked = true),
            card.copy(closures = listOf(listOf(row("Z", "Z")))),
            card.copy(state = JourneyCardState.Checking("route", held = trains)),
        )
        saying.forEach { ride ->
            assertFalse(joinsStationCard(ride))
            assertTrue(stationRideRows(mapOf("A" to listOf(ride))) { mapOf("A1" to "A")[it.stopId] }.byStation.isEmpty())
        }
        assertTrue(joinsStationCard(card))
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
        val journey = FavoriteJourney(JourneyEnd("A1", "Place"), JourneyEnd("Z", "Far"), "line")
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
        val journey = FavoriteJourney(JourneyEnd("N1", "Near"), JourneyEnd("Z", "Far end"), "line")
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
        assertEquals(mapOf(journey.key to "N|TUBE"), layout.rideStations)
        assertEquals(mapOf("N|TUBE" to 100.0, "F|TUBE" to 300.0), layout.meters)
        // The ride's train in its own group before the station's, the station's card its platforms by number.
        val rideGroup = layout.rideGroups.getValue("N|TUBE").single()
        assertEquals(journey.key, rideGroup.journey.key)
        assertEquals(listOf("N1"), rideGroup.card.group.rows.map { it.stopId })
        assertEquals(listOf(ModeIcon.TUBE), rideGroup.modes)
        assertEquals(listOf("N1", "N2"), layout.cards.getValue("N|TUBE").group.rows.map { it.stopId })
        assertEquals(setOf(1), layout.cards.getValue("N|TUBE").breaks)
        assertEquals(setOf("Z"), layout.rideEnds.values.single())
        assertTrue(rideGroup.card.group.rows.single() in layout.rideEnds.keys)
        assertFalse(layout.cards.getValue("N|TUBE").group.rows.first() in layout.rideEnds.keys)
    }

    @Test
    fun `a place's stations go by mode, each heading one icon, modes sharing an icon together`() {
        val bus = StopGroup(stopName = "Place", key = "b", showHeader = true, rows = listOf(row("A3", "A").copy(mode = "bus")), placeKey = "A")
        val rail = StopGroup(
            stopName = "Place", key = "r", showHeader = true,
            rows = listOf(row("A2", "A").copy(mode = "national-rail"), row("A2", "A").copy(mode = "elizabeth-line")), placeKey = "A",
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
            mapOf("A|BUS" to listOf(ModeIcon.BUS), "A|TUBE" to listOf(ModeIcon.TUBE), "A|RAIL" to listOf(ModeIcon.RAIL), "B|TUBE" to listOf(ModeIcon.TUBE)),
            layout.modes,
        )
        // Each of the place's stations as near as the place, in the order they came.
        assertEquals(listOf("b", "t", "r", "o"), layout.rank.entries.sortedBy { it.value }.map { it.key })
        assertEquals(listOf("A2", "A2"), layout.cards.getValue("A|RAIL").group.rows.map { it.stopId })
        // Its modes by name too, for a screen reader to tell the place's stations apart.
        assertEquals(listOf("national-rail", "elizabeth-line"), layout.modeIds.getValue("A|RAIL"))
    }

    @Test
    fun `a ride boarding at a stop not listed goes under its own mode's station at its place, or none`() {
        val bus = StopGroup(stopName = "Place", key = "b", showHeader = true, rows = listOf(row("A3", "A").copy(mode = "bus")), placeKey = "A")
        val rail = StopGroup(stopName = "Place", key = "r", showHeader = true, rows = listOf(row("A2", "A").copy(mode = "national-rail")), placeKey = "A")
        fun ride(mode: String) = JourneyCard(
            FavoriteJourney(JourneyEnd("A9", "Place"), JourneyEnd("Z", "Far end"), lineId = mode, mode = mode),
            JourneyCardState.Trains(emptyList()),
            boardingIds = listOf("A9"),
        )
        val overground = ride("overground")
        val tube = ride("tube")
        val layout = StationLayout.of(
            groups = listOf(bus, rail),
            cardOf = { group -> StopCard(group, group.rows.map { emptyList() }) },
            journeyCards = listOf(overground, tube),
            starred = emptySet(),
            placeDistanceMeters = mapOf("A" to 100.0),
            stopClusterIds = mapOf("A9" to "A"),
        )
        // The Overground shares rail's icon, so rail's station; no Underground station is listed, so no station.
        assertEquals(mapOf(overground.journey.key to "A|RAIL"), layout.rideStations)
    }

    @Test
    fun `a ride's train leaving from a held or opened farther place joins its own station's card, which is drawn`() {
        val own = group("a", "A1", "A")
        val heldPlace = group("h", "H1", "H")
        val fartherPlace = group("f", "F1", "F")
        val journey = FavoriteJourney(JourneyEnd("A1", "Place"), JourneyEnd("Z", "Far end"), "line")
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
        assertEquals(listOf("A1", "H1", "F1"), layout.rideGroups.getValue("A|TUBE").single().card.group.rows.map { it.stopId })
        assertEquals(listOf("A1"), layout.cards.getValue("A|TUBE").group.rows.map { it.stopId })
        assertEquals(listOf("H1"), layout.cards.getValue("H|TUBE").group.rows.map { it.stopId })
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
    fun `a station's card leads with its starred rows and its rides' trains, then its platforms by number`() {
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
        // A starred row leads, then the ride's train, then the platforms, each a block of its own.
        val led = stationCard(listOf(card(ride)), listOf(card(platform5), card(platform1)), starred = setOf(StarredRow.of(platform1.rows[1])))
        assertEquals(listOf("S1b", "R", "S1a", "S5"), led.group.rows.map { it.stopId })
        assertEquals(setOf(1, 2, 3), led.breaks)
        // A lone platform is its own card.
        val lone = card(platform5)
        assertTrue(stationCard(emptyList(), listOf(lone), starred = emptySet()) === lone)
    }

}
