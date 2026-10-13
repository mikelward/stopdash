package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Learning and recognizing saved places by their Wi-Fi. Synthetic positions and addresses only. */
class WifiPlacesTest {
    private val home = FavoritePlace("home", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))
    private val work = FavoritePlace("work", FavoriteKind.WORK, "Work", Coordinates(51.51, -0.12)) // about 1.1 km north
    private val places = listOf(home, work)

    // About 55 m, 170 m and 330 m north of home.
    private val inTheGarden = Coordinates(51.5005, -0.12)
    private val downTheRoad = Coordinates(51.5015, -0.12)
    private val aWalkAway = Coordinates(51.503, -0.12)

    private val router = ConnectedWifi("AA:BB:CC:00:00:01", metered = false)
    private val upstairs = ConnectedWifi("aa:bb:cc:00:00:02", metered = false)
    private val hotspot = ConnectedWifi("aa:bb:cc:00:00:09", metered = true)

    private fun sure(at: Coordinates) = LocationFix(at, isFallback = false, accuracyMeters = 10f, ageMillis = 0)

    private fun learned(vararg steps: Pair<Coordinates, ConnectedWifi>): List<LearnedPlace> =
        steps.fold(emptyList()) { acc, (at, wifi) -> WifiPlaces.learn(acc, places, sure(at), wifi) }

    @Test
    fun `a sure fix at a saved place teaches it the Wi-Fi the phone is on, and that Wi-Fi then says the rider is there`() {
        val learned = learned(home.coordinate to router)
        assertEquals(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))), learned)
        assertEquals(home, WifiPlaces.recognize(learned, places, router))
        // Nothing else is recognized: not another access point, and not with no Wi-Fi at all.
        assertNull(WifiPlaces.recognize(learned, places, upstairs))
        assertNull(WifiPlaces.recognize(learned, places, null))
    }

    @Test
    fun `a home with several access points learns each as the phone moves between them`() {
        val learned = learned(home.coordinate to router, inTheGarden to upstairs)
        assertEquals(home, WifiPlaces.recognize(learned, places, router))
        assertEquals(home, WifiPlaces.recognize(learned, places, upstairs))
        assertEquals(2, WifiPlaces.count(learned))
    }

    @Test
    fun `a metered network moves with the rider, so it is never learned or recognized`() {
        assertEquals(emptyList<LearnedPlace>(), learned(home.coordinate to hotspot))
        val stored = listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:09")))
        assertNull(WifiPlaces.recognize(stored, places, hotspot))
    }

    @Test
    fun `only a sure fix teaches, not a vague, rough, fallback or old one`() {
        val unsure = listOf(
            LocationFix(home.coordinate, isFallback = false, accuracyMeters = 80f, ageMillis = 0),
            LocationFix(home.coordinate, isFallback = false, isCoarse = true, accuracyMeters = 10f),
            LocationFix(home.coordinate, isFallback = true, accuracyMeters = 10f),
            LocationFix(home.coordinate, isFallback = false, accuracyMeters = 10f, ageMillis = MoveFollow.MAX_AGE_MILLIS + 1),
            LocationFix(home.coordinate, isFallback = false, accuracyMeters = null),
        )
        for (fix in unsure) assertEquals(fix.toString(), emptyList<LearnedPlace>(), WifiPlaces.learn(emptyList(), places, fix, router))
    }

    @Test
    fun `a fix away from every saved place teaches nothing`() {
        assertEquals(emptyList<LearnedPlace>(), learned(downTheRoad to router))
    }

    @Test
    fun `GPS well away from a place while on its Wi-Fi forgets that Wi-Fi, but a rider down the road keeps it`() {
        val atHome = learned(home.coordinate to router, home.coordinate to upstairs)
        // Down the road, still on the router: within reach of a fix's own error, so it stays.
        assertEquals(atHome, WifiPlaces.learn(atHome, places, sure(downTheRoad), router))
        // A walk away and still on it: it isn't fixed at home, so it goes; the other stays.
        val after = WifiPlaces.learn(atHome, places, sure(aWalkAway), router)
        assertNull(WifiPlaces.recognize(after, places, router))
        assertEquals(home, WifiPlaces.recognize(after, places, upstairs))
        // The last one gone takes the place's entry with it.
        assertEquals(emptyList<LearnedPlace>(), WifiPlaces.learn(after, places, sure(aWalkAway), upstairs))
    }

    @Test
    fun `an access point belongs to one place, the last it was seen at`() {
        val moved = learned(home.coordinate to router, work.coordinate to router)
        assertEquals(work, WifiPlaces.recognize(moved, places, router))
        assertEquals(listOf("work"), moved.map { it.placeId })
        // One stored at both (as an older build might have) recognizes neither.
        val both = listOf(
            LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01")),
            LearnedPlace("work", work.coordinate, listOf("aa:bb:cc:00:00:01")),
        )
        assertNull(WifiPlaces.recognize(both, places, router))
    }

    @Test
    fun `a place removed, or moved somewhere else, forgets what it learned`() {
        val learned = learned(home.coordinate to router)
        assertNull(WifiPlaces.recognize(learned, listOf(work), router))
        assertEquals(emptyList<LearnedPlace>(), WifiPlaces.current(learned, listOf(work)))
        val movedHome = home.copy(coordinate = Coordinates(51.52, -0.12))
        assertNull(WifiPlaces.recognize(learned, listOf(movedHome, work), router))
        // Edited a few meters, it's the same place: what it learned stands.
        val nudged = home.copy(coordinate = Coordinates(51.50005, -0.12))
        assertEquals(nudged, WifiPlaces.recognize(learned, listOf(nudged, work), router))
    }

    @Test
    fun `a place keeps its newest access points, up to a limit`() {
        val many = (1..WifiPlaces.MAX_PER_PLACE + 2).map { ConnectedWifi("aa:bb:cc:00:01:%02x".format(it), metered = false) }
        val learned = many.fold(emptyList<LearnedPlace>()) { acc, wifi -> WifiPlaces.learn(acc, places, sure(home.coordinate), wifi) }
        assertEquals(WifiPlaces.MAX_PER_PLACE, WifiPlaces.count(learned))
        assertNull(WifiPlaces.recognize(learned, places, many.first()))
        assertEquals(home, WifiPlaces.recognize(learned, places, many.last()))
        // Seen again, an access point counts as new: it isn't the next to go.
        val seenAgain = WifiPlaces.learn(learned, places, sure(home.coordinate), many[2])
        assertEquals("aa:bb:cc:00:01:03", seenAgain.single().accessPoints.last())
    }

    @Test
    fun `an address Android withholds, or one that isn't an address, is never kept`() {
        assertEquals("aa:bb:cc:00:00:01", WifiPlaces.normalize(" AA:BB:CC:00:00:01 "))
        for (bad in listOf(null, "", "02:00:00:00:00:00", "00:00:00:00:00:00", "not an address", "aa:bb:cc:00:00")) {
            assertNull(bad, WifiPlaces.normalize(bad))
        }
        val withheld = ConnectedWifi("02:00:00:00:00:00", metered = false)
        assertTrue(learned(home.coordinate to withheld).isEmpty())
    }
}
