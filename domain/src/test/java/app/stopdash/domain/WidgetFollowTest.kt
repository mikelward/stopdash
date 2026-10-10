package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFollowTest {
    // Public stations, by their published positions. One tube station is picked per place, so Euston
    // is in the outer ring from King's Cross, not shown.
    private val kingsCross = StopLocation("940GZZLUKSX", "King's Cross St. Pancras Underground Station", 51.5308, -0.1238, listOf(LineRef("victoria", "Victoria", "tube")), clusterId = "940GZZLUKSX")
    private val euston = StopLocation("940GZZLUEUS", "Euston Underground Station", 51.5282, -0.1337, listOf(LineRef("northern", "Northern", "tube")), clusterId = "940GZZLUEUS")
    private val waterloo = StopLocation("940GZZLUWLO", "Waterloo Underground Station", 51.5036, -0.1143, listOf(LineRef("bakerloo", "Bakerloo", "tube")), clusterId = "940GZZLUWLO")

    private val atKingsCross = Coordinates(kingsCross.latitude, kingsCross.longitude)
    private val atWaterloo = Coordinates(waterloo.latitude, waterloo.longitude)
    private val fetched = Instant.parse("2026-10-09T08:00:00Z")

    private fun arrivals(stop: StopLocation) = StopArrivals(stop.id, stop.name, emptyList(), fetched, stop.lines, clusterId = stop.clusterId)

    private val atKingsCrossSnapshot = DeparturesSnapshot(
        // Laid out as a follow at King's Cross lays it out: nothing nearer than the station itself.
        stops = listOf(arrivals(kingsCross).copy(nearer = Terminating.nearer(kingsCross.id, listOf(Terminating.Place(kingsCross.id, kingsCross.clusterId, kingsCross.name, 0.0))))),
        fetchedAt = fetched,
        nearestFirst = listOf(kingsCross.id),
    )

    @Test
    fun `only a recent and accurate last known position is followed`() {
        val at = atKingsCross
        assertTrue(WidgetFollow.followable(LocationFix(at, isFallback = false, accuracyMeters = 20f, ageMillis = 60_000)))
        // Older than ten minutes: the rider may have moved since.
        assertFalse(WidgetFollow.followable(LocationFix(at, isFallback = false, accuracyMeters = 20f, ageMillis = 11 * 60_000)))
        // Vaguer than a short walk: it could pick another stop.
        assertFalse(WidgetFollow.followable(LocationFix(at, isFallback = false, accuracyMeters = 500f, ageMillis = 60_000)))
        // No accuracy or no age: it can't show it's either.
        assertFalse(WidgetFollow.followable(LocationFix(at, isFallback = false, ageMillis = 60_000)))
        assertFalse(WidgetFollow.followable(LocationFix(at, isFallback = false, accuracyMeters = 20f)))
        assertFalse(WidgetFollow.followable(null))
    }

    @Test
    fun `the stops shown where the rider is are left as they are`() {
        val stored = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), listOf(kingsCross, euston, waterloo), atKingsCross, hidden = emptySet(), now = fetched)
        assertNull(stored)
    }

    @Test
    fun `a move picks the new place's stops and drops the old`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), listOf(kingsCross, euston, waterloo), atWaterloo, hidden = emptySet(), now = fetched)!!
        assertEquals(setOf(waterloo.id), moved.nearby)
        assertEquals(setOf(waterloo.id), moved.placeholders)
        assertEquals(listOf(waterloo.id), moved.snapshot.stops.map { it.stopId })
        assertEquals(listOf(waterloo.id), moved.snapshot.nearestFirst)
        // A placeholder has no trains yet and isn't fresh, so the refresh fetches it.
        val placeholder = moved.snapshot.stops.single()
        assertEquals(Instant.EPOCH, placeholder.fetchedAt)
        assertFalse(placeholder.arrivalsFresh)
        assertEquals(waterloo.lines, placeholder.lines)
    }

    @Test
    fun `a pinned journey's origin stays when the rider leaves it`() {
        val pinned = atKingsCrossSnapshot.copy(journeys = listOf(WidgetJourney(kingsCross.id, setOf(JourneyCall("victoria", "Brixton", null)))))
        val moved = WidgetFollow.moved(pinned, setOf(kingsCross.id), listOf(kingsCross, euston, waterloo), atWaterloo, hidden = emptySet(), now = fetched)!!
        assertEquals(listOf(kingsCross.id, waterloo.id), moved.snapshot.stops.map { it.stopId })
        assertEquals(setOf(kingsCross.id), moved.snapshot.journeyOnlyStopIds)
        // It isn't nearby: the widget shows only its journey.
        assertEquals(setOf(waterloo.id), moved.nearby)
    }

    @Test
    fun `a new stop the refresh couldn't fetch is missing, not a stop with no trains`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), listOf(kingsCross, euston, waterloo), atWaterloo, hidden = emptySet(), now = fetched)!!
        val settled = WidgetFollow.settled(moved.snapshot, moved, fetched)
        assertTrue(settled.stops.isEmpty())
        assertEquals(setOf(waterloo.id), settled.missingStopIds)
        // Fetched, it stays.
        val fetchedNow = moved.snapshot.copy(stops = moved.snapshot.stops.map { it.copy(fetchedAt = fetched, arrivalsFresh = true) })
        assertEquals(fetchedNow, WidgetFollow.settled(fetchedNow, moved, fetched))
    }

    @Test
    fun `nowhere with a route nearby shows no stops`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), emptyList(), atWaterloo, hidden = emptySet(), now = fetched)!!
        assertTrue(moved.nearby.isEmpty())
        assertTrue(moved.snapshot.stops.isEmpty())
    }

    @Test
    fun `a stored set whose stops failed to fetch is laid out again`() {
        // A follow stored Waterloo as the set, then its fetch failed: the snapshot still holds King's Cross.
        val again = WidgetFollow.moved(atKingsCrossSnapshot, setOf(waterloo.id), listOf(kingsCross, euston, waterloo), atWaterloo, hidden = emptySet(), now = fetched)!!
        assertEquals(setOf(waterloo.id), again.placeholders)
        assertEquals(listOf(waterloo.id), again.snapshot.stops.map { it.stopId })
        // Once fetched and stored, nothing more to do.
        val stored = again.snapshot.copy(stops = again.snapshot.stops.map { it.copy(fetchedAt = fetched, arrivalsFresh = true) })
        assertNull(WidgetFollow.moved(stored, setOf(waterloo.id), listOf(kingsCross, euston, waterloo), atWaterloo, hidden = emptySet(), now = fetched))
    }

    @Test
    fun `the newest followable fix is followed, not just the newest`() {
        val gps = LocationFix(atKingsCross, isFallback = false, accuracyMeters = 10f, ageMillis = 3 * 60_000)
        // Newer, but too vague to pick a stop by.
        val network = LocationFix(atWaterloo, isFallback = false, accuracyMeters = 1_500f, ageMillis = 30_000)
        assertEquals(gps, WidgetFollow.best(listOf(network, gps)))
        val newerGps = gps.copy(ageMillis = 60_000)
        assertEquals(newerGps, WidgetFollow.best(listOf(gps, network, newerGps)))
        assertNull(WidgetFollow.best(listOf(network)))
        assertNull(WidgetFollow.best(emptyList()))
    }

    @Test
    fun `an old place's stops still stored are laid out away, even under an empty set`() {
        // A follow to nowhere with a route stored the empty set, but its layout wasn't stored: the snapshot
        // still holds King's Cross. Laid out again, so its trains stop being fetched.
        val again = WidgetFollow.moved(atKingsCrossSnapshot, emptySet(), emptyList(), atWaterloo, hidden = emptySet(), now = fetched)!!
        assertTrue(again.nearby.isEmpty())
        assertTrue(again.snapshot.stops.isEmpty())
        // Stored, it's settled.
        assertNull(WidgetFollow.moved(again.snapshot, emptySet(), emptyList(), atWaterloo, hidden = emptySet(), now = fetched))
    }

    @Test
    fun `the same stops seen from a new spot are laid out again`() {
        val stPancras = StopLocation("910GSTPX", "London St Pancras International Rail Station", 51.5320, -0.1270, listOf(LineRef("thameslink", "Thameslink", "national-rail")), clusterId = "910GSTPX")
        val found = listOf(kingsCross, stPancras)
        val first = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), found, atKingsCross, hidden = emptySet(), now = fetched)!!
        assertEquals(listOf(kingsCross.id, stPancras.id), first.snapshot.nearestFirst)
        val settled = first.snapshot.copy(stops = first.snapshot.stops.map { it.copy(fetchedAt = fetched, arrivalsFresh = true) })
        assertNull(WidgetFollow.moved(settled, first.nearby, found, atKingsCross, hidden = emptySet(), now = fetched))
        // A short walk west: the same two stops, St Pancras now the nearer.
        val west = Coordinates(stPancras.latitude, stPancras.longitude - 0.001)
        val again = WidgetFollow.moved(settled, first.nearby, found, west, hidden = emptySet(), now = fetched)!!
        assertEquals(first.nearby, again.nearby)
        assertTrue(again.placeholders.isEmpty())
        assertEquals(listOf(stPancras.id, kingsCross.id), again.snapshot.nearestFirst)
        assertFalse(kingsCross.id in again.snapshot.stops.single { it.stopId == stPancras.id }.nearer.ids)
    }

    // A synthetic spot and three synthetic bus poles around it: a pair of one stop area (northbound
    // ~100 m off, southbound ~128 m), and a lone southbound pole ~103 m off, in a stop area of its own.
    private val here = Coordinates(51.5, -0.12)
    private fun pole(id: String, cluster: String, dLatMeters: Double, dLonMeters: Double) = StopLocation(
        id, "Stop $id",
        here.latitude + dLatMeters / 111_195.0,
        here.longitude + dLonMeters / (111_195.0 * Math.cos(Math.toRadians(here.latitude))),
        listOf(LineRef("55", "55", "bus")),
        clusterId = cluster,
    )
    private val pairNorth = pole("490000000PN", "490000000P", 100.0, 0.0)
    private val pairSouth = pole("490000000PS", "490000000P", 0.0, 128.0)
    private val loneSouth = pole("490000000LS", "490000000L", -103.0, 0.0)
    private val poles = listOf(pairNorth, pairSouth, loneSouth)

    private fun bus(direction: String, destination: String, inSeconds: Long) =
        Departure("55", "55", direction, destination, null, fetched.plusSeconds(inSeconds), "bus")

    // Each pole fetched: northbound at the pair's north pole, southbound at both southbound poles.
    private fun fetchedAround(snapshot: DeparturesSnapshot) = snapshot.copy(
        stops = snapshot.stops.map { stop ->
            val departures = if (stop.stopId == pairNorth.id) listOf(bus("inbound", "North", 120)) else listOf(bus("outbound", "South", 240))
            stop.copy(departures = departures, fetchedAt = fetched, arrivalsFresh = true)
        },
    )

    @Test
    fun `a follow keeps a route's two directions at one place, as the app's list does`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), poles, here, hidden = emptySet(), now = fetched)!!
        assertEquals(poles.mapTo(HashSet()) { it.id }, moved.nearby)
        val settled = WidgetFollow.settled(fetchedAround(moved.snapshot), moved, fetched)
        // The lone pole is the nearer southbound stop, but within a few meters of the pair's: the line
        // shows from the pair, both directions, rather than split across two places.
        assertEquals(
            listOf(FoldChoice("55", "inbound", pairNorth.id), FoldChoice("55", "outbound", pairSouth.id)),
            settled.nearbyChoices,
        )
        val rows = DepartureRows.across(settled.stops, fetched, settled.liveLineStatuses(fetched))
        val shown = DepartureRows.glanceFolded(rows, settled.nearestFirst, settled.nearbyChoices) { false }
        assertEquals(setOf(pairNorth.id, pairSouth.id), shown.mapTo(HashSet()) { it.stopId })
        // Settled, the next refresh from the same spot changes nothing.
        assertNull(WidgetFollow.moved(settled, moved.nearby, poles, here, hidden = emptySet(), now = fetched))
    }

    @Test
    fun `the app's stop choices stand while they are the ones the follow's position gives`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), poles, here, hidden = emptySet(), now = fetched)!!
        val settled = WidgetFollow.settled(fetchedAround(moved.snapshot), moved, fetched)
        // The app saved the same choices (as it would from here): nothing to redo.
        assertNull(WidgetFollow.moved(settled, moved.nearby, poles, here, hidden = emptySet(), now = fetched))
        // Choices the app saved from elsewhere (the lone pole southbound) give way to this position's.
        val elsewhere = settled.copy(nearbyChoices = listOf(FoldChoice("55", "inbound", pairNorth.id), FoldChoice("55", "outbound", loneSouth.id)))
        val again = WidgetFollow.moved(elsewhere, moved.nearby, poles, here, hidden = emptySet(), now = fetched)!!
        assertTrue(again.placeholders.isEmpty())
        assertEquals(settled.nearbyChoices, again.snapshot.nearbyChoices)
    }

    @Test
    fun `a refresh where nothing moved still folds a line that has just appeared`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), poles, here, hidden = emptySet(), now = fetched)!!
        // Settled while only the southbound buses were in their prediction window.
        val southOnly = fetchedAround(moved.snapshot).let { s ->
            s.copy(stops = s.stops.map { if (it.stopId == pairNorth.id) it.copy(departures = emptyList()) else it })
        }
        val settled = WidgetFollow.settled(southOnly, moved, fetched)
        val followed = WidgetFollow.followed(settled, moved.nearby, poles, here, hidden = emptySet(), now = fetched)
        assertNull(followed.moved)
        // The next refresh brings a northbound bus: worked out from the position, both directions at the pair.
        val refreshed = WidgetFollow.withChoices(fetchedAround(settled), followed.position, fetched)
        assertEquals(
            listOf(FoldChoice("55", "inbound", pairNorth.id), FoldChoice("55", "outbound", pairSouth.id)),
            refreshed.nearbyChoices,
        )
    }

    @Test
    fun `a line's status row over stale arrivals has a choice, as the widget draws it`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), poles, here, hidden = emptySet(), now = fetched)!!
        val later = fetched.plusSeconds(3_600)
        // The arrivals have gone stale; the line was checked since, and is suspended.
        val suspended = fetchedAround(moved.snapshot).copy(
            lineStatuses = mapOf("55" to LineStatusCheck(LineStatus("55", 20, "Suspended"), later)),
        )
        val choices = GlanceRows.choices(suspended, moved.nearby, moved.position.distances, later, emptySet(), Dismissals.NONE, emptySet())
        assertTrue(choices.any { it.lineId == "55" })
    }

    @Test
    fun `choices are worked out over the rows the widget draws, dismissals included`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), poles, here, hidden = emptySet(), now = fetched)!!
        // The pair's southbound pole also serves a suspended route, so its warning heads that pole on its
        // own: the pair is two headers, and the 55 splits, southbound to the lone pole.
        val warned = fetchedAround(moved.snapshot).let { s ->
            s.copy(
                stops = s.stops.map { if (it.stopId == pairSouth.id) it.copy(lines = it.lines + LineRef("99", "99", "bus")) else it },
                lineStatuses = mapOf("99" to LineStatusCheck(LineStatus("99", 20, "Suspended"), fetched)),
            )
        }
        val nearby = moved.nearby
        val distances = moved.position.distances
        fun southbound(dismissals: Dismissals) =
            GlanceRows.choices(warned, nearby, distances, fetched, emptySet(), dismissals, emptySet())
                .single { it.lineId == "55" && it.direction == "outbound" }.stopId
        assertEquals(loneSouth.id, southbound(Dismissals.NONE))
        // Dismissed, the warning is hidden and the pair is one header again, as the widget draws it.
        val alert = DismissedAlert.ofLineStatus(LineStatus("99", 20, "Suspended"))
        assertEquals(pairSouth.id, southbound(Dismissals(setOf(alert))))
        // A dismissal since ended still hides the check made before it ended, as the widget draws it.
        assertEquals(pairSouth.id, southbound(Dismissals(emptySet(), mapOf(alert to fetched.plusSeconds(60)))))
    }
}
