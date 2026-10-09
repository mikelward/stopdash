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
        val stored = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), listOf(kingsCross, euston, waterloo), atKingsCross, hidden = emptySet())
        assertNull(stored)
    }

    @Test
    fun `a move picks the new place's stops and drops the old`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), listOf(kingsCross, euston, waterloo), atWaterloo, hidden = emptySet())!!
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
        val moved = WidgetFollow.moved(pinned, setOf(kingsCross.id), listOf(kingsCross, euston, waterloo), atWaterloo, hidden = emptySet())!!
        assertEquals(listOf(kingsCross.id, waterloo.id), moved.snapshot.stops.map { it.stopId })
        assertEquals(setOf(kingsCross.id), moved.snapshot.journeyOnlyStopIds)
        // It isn't nearby: the widget shows only its journey.
        assertEquals(setOf(waterloo.id), moved.nearby)
    }

    @Test
    fun `a new stop the refresh couldn't fetch is missing, not a stop with no trains`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), listOf(kingsCross, euston, waterloo), atWaterloo, hidden = emptySet())!!
        val settled = WidgetFollow.settled(moved.snapshot, moved.placeholders)
        assertTrue(settled.stops.isEmpty())
        assertEquals(setOf(waterloo.id), settled.missingStopIds)
        // Fetched, it stays.
        val fetchedNow = moved.snapshot.copy(stops = moved.snapshot.stops.map { it.copy(fetchedAt = fetched, arrivalsFresh = true) })
        assertEquals(fetchedNow, WidgetFollow.settled(fetchedNow, moved.placeholders))
    }

    @Test
    fun `nowhere with a route nearby shows no stops`() {
        val moved = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), emptyList(), atWaterloo, hidden = emptySet())!!
        assertTrue(moved.nearby.isEmpty())
        assertTrue(moved.snapshot.stops.isEmpty())
    }

    @Test
    fun `a stored set whose stops failed to fetch is laid out again`() {
        // A follow stored Waterloo as the set, then its fetch failed: the snapshot still holds King's Cross.
        val again = WidgetFollow.moved(atKingsCrossSnapshot, setOf(waterloo.id), listOf(kingsCross, euston, waterloo), atWaterloo, hidden = emptySet())!!
        assertEquals(setOf(waterloo.id), again.placeholders)
        assertEquals(listOf(waterloo.id), again.snapshot.stops.map { it.stopId })
        // Once fetched and stored, nothing more to do.
        val stored = again.snapshot.copy(stops = again.snapshot.stops.map { it.copy(fetchedAt = fetched, arrivalsFresh = true) })
        assertNull(WidgetFollow.moved(stored, setOf(waterloo.id), listOf(kingsCross, euston, waterloo), atWaterloo, hidden = emptySet()))
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
        val again = WidgetFollow.moved(atKingsCrossSnapshot, emptySet(), emptyList(), atWaterloo, hidden = emptySet())!!
        assertTrue(again.nearby.isEmpty())
        assertTrue(again.snapshot.stops.isEmpty())
        // Stored, it's settled.
        assertNull(WidgetFollow.moved(again.snapshot, emptySet(), emptyList(), atWaterloo, hidden = emptySet()))
    }

    @Test
    fun `the same stops seen from a new spot are laid out again`() {
        val stPancras = StopLocation("910GSTPX", "London St Pancras International Rail Station", 51.5320, -0.1270, listOf(LineRef("thameslink", "Thameslink", "national-rail")), clusterId = "910GSTPX")
        val found = listOf(kingsCross, stPancras)
        val first = WidgetFollow.moved(atKingsCrossSnapshot, setOf(kingsCross.id), found, atKingsCross, hidden = emptySet())!!
        assertEquals(listOf(kingsCross.id, stPancras.id), first.snapshot.nearestFirst)
        val settled = first.snapshot.copy(stops = first.snapshot.stops.map { it.copy(fetchedAt = fetched, arrivalsFresh = true) })
        assertNull(WidgetFollow.moved(settled, first.nearby, found, atKingsCross, hidden = emptySet()))
        // A short walk west: the same two stops, St Pancras now the nearer.
        val west = Coordinates(stPancras.latitude, stPancras.longitude - 0.001)
        val again = WidgetFollow.moved(settled, first.nearby, found, west, hidden = emptySet())!!
        assertEquals(first.nearby, again.nearby)
        assertTrue(again.placeholders.isEmpty())
        assertEquals(listOf(stPancras.id, kingsCross.id), again.snapshot.nearestFirst)
        assertFalse(kingsCross.id in again.snapshot.stops.single { it.stopId == stPancras.id }.nearer.ids)
    }

    @Test
    fun `the app's stop choices give way to the follow's order`() {
        // Worked out from distances the widget never sees, so a follow can't keep them.
        val chosen = atKingsCrossSnapshot.copy(nearbyChoices = listOf(FoldChoice("victoria", "inbound", kingsCross.id)))
        val again = WidgetFollow.moved(chosen, setOf(kingsCross.id), listOf(kingsCross, euston, waterloo), atKingsCross, hidden = emptySet())!!
        assertTrue(again.snapshot.nearbyChoices.isEmpty())
        assertTrue(again.placeholders.isEmpty())
        assertNull(WidgetFollow.moved(again.snapshot, again.nearby, listOf(kingsCross, euston, waterloo), atKingsCross, hidden = emptySet()))
    }
}
