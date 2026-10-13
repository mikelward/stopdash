package app.stopdash.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.ConnectedWifi
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.LearnedPlace
import app.stopdash.domain.LocationFix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The saved places' Wi-Fi on the phone: the setting, precise location, and the stored list. Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidWifiPlacesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = WifiPlacesStore({ app.getSharedPreferences(WifiPlacesStore.FILE, Context.MODE_PRIVATE) })
    private val home = FavoritePlace("home", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))
    private val router = ConnectedWifi("aa:bb:cc:00:00:01", metered = false)
    private val atHome = LocationFix(home.coordinate, isFallback = false, accuracyMeters = 10f, ageMillis = 0)

    private val work = FavoritePlace("work", FavoriteKind.WORK, "Work", Coordinates(51.51, -0.12))

    // The setting; null while its stored choice hasn't been read.
    private var on: Boolean? = true
    private var savedPlaces: FavoritePlacesSet = FavoritePlacesSet.Loaded(listOf(home))
    private var wifiReads = 0
    private var connected: ConnectedWifi? = router
    // When set, reading the Wi-Fi waits for it: a lesson caught mid-way.
    private var wifiGate: CompletableDeferred<Unit>? = null
    // When set, the saved places' nth read waits for the gate.
    private var placesReads = 0
    private var holdPlaces: Pair<Int, CompletableDeferred<Unit>>? = null

    private fun TestScope.places(log: MutableList<String> = mutableListOf()) = AndroidWifiPlaces(
        context = app,
        store = store,
        places = {
            placesReads++
            holdPlaces?.let { (at, gate) -> if (placesReads == at) gate.await() }
            savedPlaces
        },
        enabled = { on },
        wifi = { wifiReads++; wifiGate?.await(); connected },
        io = StandardTestDispatcher(testScheduler),
        scope = this,
        warn = { log += it },
    )

    // What's stored, from this build: a newer build's list would read as null.
    private fun stored() = checkNotNull(store.read())

    private fun precise() = shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @Test
    fun `a sure fix at home on its Wi-Fi is learned, kept on the phone, and then recognizes home`() = runTest {
        precise()
        val log = mutableListOf<String>()
        val wifiPlaces = places(log)
        wifiPlaces.observe(atHome)
        runCurrent()
        assertEquals(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))), stored())
        assertEquals(home, wifiPlaces.recognize())
        // The log says how many, never which network or place.
        assertEquals(listOf("Wi-Fi at saved places: 1 access points known, was 0"), log)
    }

    @Test
    fun `a fix too vague to teach anything doesn't even read the Wi-Fi`() = runTest {
        precise()
        places().observe(atHome.copy(accuracyMeters = 200f))
        runCurrent()
        assertEquals(0, wifiReads)
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `with nothing learned, recognizing reads no Wi-Fi`() = runTest {
        precise()
        assertNull(places().recognize())
        assertEquals(0, wifiReads)
    }

    @Test
    fun `turned off, nothing is used and what was learned is deleted`() = runTest {
        precise()
        store.write(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))))
        on = false
        val wifiPlaces = places()
        assertNull(wifiPlaces.recognize())
        assertTrue(stored().isEmpty())
        wifiPlaces.observe(atHome)
        runCurrent()
        assertTrue(stored().isEmpty())
        assertEquals(0, wifiReads)
    }

    @Test
    fun `turning it off forgets at once, whatever is on screen`() = runTest {
        precise()
        store.write(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))))
        places().forget()
        runCurrent()
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `turned off while a lesson waits on the Wi-Fi, the lesson is never written`() = runTest {
        precise()
        wifiGate = CompletableDeferred()
        val wifiPlaces = places()
        wifiPlaces.observe(atHome)
        runCurrent()
        assertEquals(1, wifiReads)
        // As Settings does it: the choice applied at once, then everything learned deleted.
        on = false
        wifiPlaces.forget()
        runCurrent()
        checkNotNull(wifiGate).complete(Unit)
        runCurrent()
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `turned off while recognizing waits on the Wi-Fi, nothing learned is used`() = runTest {
        precise()
        store.write(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))))
        wifiGate = CompletableDeferred()
        val wifiPlaces = places()
        val recognizing = async { wifiPlaces.recognize() }
        runCurrent()
        assertEquals(1, wifiReads)
        on = false
        wifiPlaces.forget()
        runCurrent()
        checkNotNull(wifiGate).complete(Unit)
        runCurrent()
        assertNull(recognizing.getCompleted())
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a place removed while a lesson waits on the Wi-Fi isn't learned back`() = runTest {
        precise()
        store.write(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:02"))))
        wifiGate = CompletableDeferred()
        val wifiPlaces = places()
        wifiPlaces.observe(atHome)
        runCurrent()
        assertEquals(1, wifiReads)
        // Home removed meanwhile, and the app reconciles on the change.
        savedPlaces = FavoritePlacesSet.Loaded(listOf(work))
        wifiPlaces.reconcile()
        runCurrent()
        assertTrue(stored().isEmpty())
        checkNotNull(wifiGate).complete(Unit)
        runCurrent()
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `while the setting's stored choice hasn't been read, nothing is used, learned or deleted`() = runTest {
        precise()
        val learned = listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01")))
        store.write(learned)
        on = null
        val wifiPlaces = places()
        wifiPlaces.reconcile()
        runCurrent()
        assertNull(wifiPlaces.recognize())
        wifiPlaces.observe(atHome.copy(coordinates = work.coordinate))
        runCurrent()
        assertEquals(learned, stored())
        assertEquals(0, wifiReads)
    }

    @Test
    fun `a newer build's list is never used or written over, but turning the setting off still deletes it`() = runTest {
        precise()
        val prefs = app.getSharedPreferences(WifiPlacesStore.FILE, Context.MODE_PRIVATE)
        val newer = """{"version":2,"places":[]}"""
        prefs.edit().putString("learned", newer).commit()
        val wifiPlaces = places()
        wifiPlaces.observe(atHome)
        runCurrent()
        assertNull(wifiPlaces.recognize())
        assertEquals(newer, prefs.getString("learned", null))
        on = false
        wifiPlaces.reconcile()
        runCurrent()
        assertTrue(!prefs.contains("learned"))
    }

    @Test
    fun `turned off while the saved places are read, nothing learned is used`() = runTest {
        precise()
        store.write(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))))
        val gate = CompletableDeferred<Unit>()
        // The second read: the one the recognition is decided with, after the Wi-Fi is read.
        holdPlaces = 2 to gate
        val wifiPlaces = places()
        val recognizing = async { wifiPlaces.recognize() }
        runCurrent()
        assertEquals(2, placesReads)
        on = false
        wifiPlaces.forget()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertNull(recognizing.getCompleted())
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `without precise location nothing is used, and what was learned is deleted`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        store.write(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))))
        assertNull(places().recognize())
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `with no saved places left what was learned goes, and an unreadable list decides nothing`() = runTest {
        precise()
        store.write(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))))
        savedPlaces = FavoritePlacesSet.Unavailable
        val wifiPlaces = places()
        wifiPlaces.observe(atHome)
        runCurrent()
        assertNull(wifiPlaces.recognize())
        assertEquals(1, stored().size)
        savedPlaces = FavoritePlacesSet.Loaded(emptyList())
        wifiPlaces.observe(atHome)
        runCurrent()
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `precise location taken away while the app was gone deletes it on the next check, fix or not`() = runTest {
        store.write(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        places().reconcile()
        runCurrent()
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a place removed or moved loses what it learned at once, while the others keep theirs`() = runTest {
        precise()
        store.write(
            listOf(
                LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01")),
                LearnedPlace("work", work.coordinate, listOf("aa:bb:cc:00:00:02")),
            ),
        )
        savedPlaces = FavoritePlacesSet.Loaded(listOf(home.copy(coordinate = Coordinates(51.52, -0.12)), work))
        places().reconcile()
        runCurrent()
        assertEquals(listOf("work"), stored().map { it.placeId })
        assertEquals(0, wifiReads)
    }

    @Test
    fun `a sure fix off Wi-Fi still clears out a place that was removed`() = runTest {
        precise()
        store.write(
            listOf(
                LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01")),
                LearnedPlace("work", work.coordinate, listOf("aa:bb:cc:00:00:02")),
            ),
        )
        savedPlaces = FavoritePlacesSet.Loaded(listOf(home))
        connected = null
        places().observe(atHome)
        runCurrent()
        assertEquals(listOf("home"), stored().map { it.placeId })
    }

    @Test
    fun `saved places discarded as unreadable take what they learned with them`() = runTest {
        precise()
        store.write(listOf(LearnedPlace("home", home.coordinate, listOf("aa:bb:cc:00:00:01"))))
        // A corrupt list replaced by an empty one: no places, as surely as the last one removed.
        savedPlaces = FavoritePlacesSet.Discarded
        places().reconcile()
        runCurrent()
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a stored list that can't be read is dropped and relearned, a newer build's is left alone`() {
        val prefs = app.getSharedPreferences("wifi_places_test", Context.MODE_PRIVATE)
        val warned = mutableListOf<String>()
        prefs.edit().putString("learned", "not json").commit()
        assertEquals(emptyList<LearnedPlace>(), WifiPlacesStore({ prefs }) { warned += it }.read())
        assertTrue(!prefs.contains("learned"))
        assertEquals(1, warned.size)
        val newer = """{"version":2,"places":[]}"""
        prefs.edit().putString("learned", newer).commit()
        assertNull(WifiPlacesStore({ prefs }).read())
        assertEquals(newer, prefs.getString("learned", null))
    }
}
