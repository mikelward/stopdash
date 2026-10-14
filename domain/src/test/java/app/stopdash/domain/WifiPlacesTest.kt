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

    // Access points of one office network, as the phone reads them: the network's opaque id, never its name.
    private val desk = ConnectedWifi("aa:bb:cc:00:10:01", metered = false, network = "net-1")
    private val meetingRoom = ConnectedWifi("aa:bb:cc:00:10:02", metered = false, network = "net-1")
    private val corridor = ConnectedWifi("aa:bb:cc:00:10:03", metered = false, network = "net-1")

    private fun sure(at: Coordinates) = LocationFix(at, isFallback = false, accuracyMeters = 10f, ageMillis = 0)

    private fun learned(vararg steps: Pair<Coordinates, ConnectedWifi>): List<LearnedPlace> =
        steps.fold(emptyList()) { acc, (at, wifi) -> WifiPlaces.learn(acc, places, sure(at), wifi) }

    @Test
    fun `a sure fix at a saved place teaches it the Wi-Fi the phone is on, and that Wi-Fi then says the rider is there`() {
        val learned = learned(home.coordinate to router)
        assertEquals(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))), learned)
        assertEquals(home, WifiPlaces.recognize(learned, places, router)?.place)
        // Nothing else is recognized: not another access point, and not with no Wi-Fi at all.
        assertNull(WifiPlaces.recognize(learned, places, upstairs)?.place)
        assertNull(WifiPlaces.recognize(learned, places, null)?.place)
    }

    @Test
    fun `a home with several access points learns each as the phone moves between them`() {
        val learned = learned(home.coordinate to router, inTheGarden to upstairs)
        assertEquals(home, WifiPlaces.recognize(learned, places, router)?.place)
        assertEquals(home, WifiPlaces.recognize(learned, places, upstairs)?.place)
        assertEquals(2, WifiPlaces.count(learned))
    }

    @Test
    fun `a metered network moves with the rider, so it is never learned or recognized`() {
        assertEquals(emptyList<LearnedPlace>(), learned(home.coordinate to hotspot))
        val stored = listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:09")))
        assertNull(WifiPlaces.recognize(stored, places, hotspot)?.place)
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
        assertNull(WifiPlaces.recognize(after, places, router)?.place)
        assertEquals(home, WifiPlaces.recognize(after, places, upstairs)?.place)
        // The last one gone takes the place's entry with it.
        assertEquals(emptyList<LearnedPlace>(), WifiPlaces.learn(after, places, sure(aWalkAway), upstairs))
    }

    @Test
    fun `an access point belongs to one place, the last it was seen at`() {
        val moved = learned(home.coordinate to router, work.coordinate to router)
        assertEquals(work, WifiPlaces.recognize(moved, places, router)?.place)
        assertEquals(listOf("work"), moved.map { it.placeId })
        // One stored at both (as an older build might have) recognizes neither.
        val both = listOf(
            LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01")),
            LearnedPlace("work", work.coordinate, listOf("aa:bb:cc:00:00:01")),
        )
        assertNull(WifiPlaces.recognize(both, places, router)?.place)
    }

    @Test
    fun `a place removed, or moved somewhere else, forgets what it learned`() {
        val learned = learned(home.coordinate to router)
        assertNull(WifiPlaces.recognize(learned, listOf(work), router)?.place)
        assertEquals(emptyList<LearnedPlace>(), WifiPlaces.current(learned, listOf(work)))
        val movedHome = home.copy(coordinate = Coordinates(51.52, -0.12))
        assertNull(WifiPlaces.recognize(learned, listOf(movedHome, work), router)?.place)
        // Edited a few meters, it's the same place: what it learned stands.
        val nudged = home.copy(coordinate = Coordinates(51.50005, -0.12))
        assertEquals(nudged, WifiPlaces.recognize(learned, listOf(nudged, work), router)?.place)
    }

    @Test
    fun `a place keeps its newest access points, up to a limit`() {
        val many = (1..WifiPlaces.MAX_PER_PLACE + 2).map { ConnectedWifi("aa:bb:cc:00:01:%02x".format(it), metered = false) }
        val learned = many.fold(emptyList<LearnedPlace>()) { acc, wifi -> WifiPlaces.learn(acc, places, sure(home.coordinate), wifi) }
        assertEquals(WifiPlaces.MAX_PER_PLACE, WifiPlaces.count(learned))
        assertNull(WifiPlaces.recognize(learned, places, many.first())?.place)
        assertEquals(home, WifiPlaces.recognize(learned, places, many.last())?.place)
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

    @Test
    fun `a place's network says the rider is there on an access point it never saw, when the rough fix is near`() {
        val learned = learned(work.coordinate to desk)
        assertEquals(listOf(LearnedPlace("work", work.coordinate, listOf("aa:bb:cc:00:10:01"), listOf("net-1"))), learned)
        // The access point it learned settles it, with or without a rough fix.
        assertEquals(RecognizedPlace(work, WifiMatch.ACCESS_POINT), WifiPlaces.recognize(learned, places, desk))
        // One it never saw, on the same network, with a rough fix about 780 m off.
        assertEquals(
            RecognizedPlace(work, WifiMatch.NETWORK),
            WifiPlaces.recognize(learned, places, meetingRoom, near = Coordinates(51.517, -0.12)),
        )
        // With no rough fix, or one more than a kilometer off, the network alone says nothing.
        assertNull(WifiPlaces.recognize(learned, places, meetingRoom))
        assertNull(WifiPlaces.recognize(learned, places, meetingRoom, near = Coordinates(51.52, -0.12)))
        // A network stands in for a fix less sure than an access point's.
        assertEquals(WifiPlaces.FORGET_BEYOND_METERS.toFloat(), RecognizedPlace(work, WifiMatch.NETWORK).accuracyMeters, 0f)
        assertEquals(WifiPlaces.RECOGNIZED_ACCURACY_METERS, RecognizedPlace(work, WifiMatch.ACCESS_POINT).accuracyMeters, 0f)
    }

    @Test
    fun `a network learned at two places near the rough fix says neither, and the one near it when only one is`() {
        val shared = { bssid: String -> ConnectedWifi(bssid, metered = false, network = "net-2") }
        val learned = learned(home.coordinate to shared("aa:bb:cc:00:20:01"), work.coordinate to shared("aa:bb:cc:00:20:02"))
        val elsewhere = shared("aa:bb:cc:00:20:03")
        // Halfway between, about 560 m from each: either could be it.
        assertNull(WifiPlaces.recognize(learned, places, elsewhere, near = Coordinates(51.505, -0.12)))
        // Just south of home, more than a kilometer from work.
        assertEquals(home, WifiPlaces.recognize(learned, places, elsewhere, near = Coordinates(51.4995, -0.12))?.place)
    }

    @Test
    fun `GPS on a place's network between 250 m and a kilometer from it forgets the network there, nearer or farther keeps it`() {
        val atWork = learned(work.coordinate to desk)
        // About 170 m off, still on the network: within a fix's own error of the place, so it stays.
        assertEquals(atWork, WifiPlaces.learn(atWork, places, sure(Coordinates(51.5115, -0.12)), corridor))
        // About 2 km off, another site on the same network: too far to be taken for the place anyway.
        assertEquals(atWork, WifiPlaces.learn(atWork, places, sure(Coordinates(51.528, -0.12)), corridor))
        // About 330 m off, where a rough fix could put the rider at the place wrongly: it goes there,
        // and the access point the place learned stays.
        val after = WifiPlaces.learn(atWork, places, sure(Coordinates(51.513, -0.12)), corridor)
        assertEquals(listOf(LearnedPlace("work", work.coordinate, listOf("aa:bb:cc:00:10:01"))), after)
        assertNull(WifiPlaces.recognize(after, places, meetingRoom, near = work.coordinate))
    }

    @Test
    fun `a metered network's name is never learned or recognized`() {
        val hotspotNetwork = ConnectedWifi("aa:bb:cc:00:30:01", metered = true, network = "net-3")
        assertEquals(emptyList<LearnedPlace>(), learned(work.coordinate to hotspotNetwork))
        val stored = listOf(LearnedPlace("work", work.coordinate, emptyList(), listOf("net-3")))
        assertNull(WifiPlaces.recognize(stored, places, hotspotNetwork, near = work.coordinate))
    }

    @Test
    fun `with the access point's address withheld, the network is still learned and recognized`() {
        val withheld = ConnectedWifi("02:00:00:00:00:00", metered = false, network = "net-1")
        val learned = learned(work.coordinate to withheld)
        assertEquals(listOf(LearnedPlace("work", work.coordinate, emptyList(), listOf("net-1"))), learned)
        assertEquals(WifiPlaces.current(learned, places), learned)
        assertEquals(RecognizedPlace(work, WifiMatch.NETWORK), WifiPlaces.recognize(learned, places, meetingRoom, near = work.coordinate))
        assertEquals(1, WifiPlaces.networkCount(learned))
        assertEquals(0, WifiPlaces.count(learned))
    }

    @Test
    fun `a place keeps its newest networks, up to a limit`() {
        val many = (1..WifiPlaces.MAX_NETWORKS_PER_PLACE + 1).map { ConnectedWifi("aa:bb:cc:00:40:%02x".format(it), metered = false, network = "net-$it") }
        val learned = many.fold(emptyList<LearnedPlace>()) { acc, wifi -> WifiPlaces.learn(acc, places, sure(work.coordinate), wifi) }
        assertEquals((2..WifiPlaces.MAX_NETWORKS_PER_PLACE + 1).map { "net-$it" }, learned.single().networks)
    }

    @Test
    fun `a network two nearby saved places share stays at both, so the rough fix near both says neither`() {
        // A third place about 600 m north of home: within a kilometer of it, on the same network.
        val gym = FavoritePlace("gym", FavoriteKind.CUSTOM, "Gym", Coordinates(51.5054, -0.12))
        val nearby = listOf(home, gym)
        val shared = { bssid: String -> ConnectedWifi(bssid, metered = false, network = "net-4") }
        val learned = listOf(home.coordinate to shared("aa:bb:cc:00:50:01"), gym.coordinate to shared("aa:bb:cc:00:50:02"))
            .fold(emptyList<LearnedPlace>()) { acc, (at, wifi) -> WifiPlaces.learn(acc, nearby, sure(at), wifi) }
        // Learned at the gym, the network isn't taken from home.
        assertEquals(listOf("net-4"), learned.single { it.placeId == "home" }.networks)
        assertEquals(listOf("net-4"), learned.single { it.placeId == "gym" }.networks)
        // Back home on an access point never seen, with a rough fix that is near both: neither.
        assertNull(WifiPlaces.recognize(learned, nearby, shared("aa:bb:cc:00:50:03"), near = home.coordinate))
        // A rough fix about 600 m south of home, more than a kilometer from the gym: home.
        assertEquals(home, WifiPlaces.recognize(learned, nearby, shared("aa:bb:cc:00:50:03"), near = Coordinates(51.4946, -0.12))?.place)
    }
}
