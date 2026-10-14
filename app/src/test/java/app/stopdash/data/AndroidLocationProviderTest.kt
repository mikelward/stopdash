package app.stopdash.data

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FixSelection
import app.stopdash.domain.LocationFix
import app.stopdash.domain.WifiPlaceSource
import app.stopdash.domain.WifiPlaces
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What a location provider remembers of a precise fix. Synthetic positions only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidLocationProviderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(LocationManager::class.java)

    private fun gps(latitude: Double) = Location(LocationManager.GPS_PROVIDER).apply {
        this.latitude = latitude
        longitude = -0.12
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    // Whether a coarse fix just by [latitude] would be taken for the remembered precise one.
    private fun recalledNear(latitude: Double) =
        AndroidLocationProvider.preciseMemory.instead(Coordinates(latitude + 0.0001, -0.12), 500f, SystemClock.elapsedRealtime())

    @Test
    fun `a trip's fix isn't remembered for a later lookup, while a near-me one is`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).simulateLocation(gps(51.5))
        val trip = AndroidLocationProvider(app, remembers = false).current(forceFresh = false)
        assertEquals(51.5, checkNotNull(trip).coordinates.latitude, 0.0)
        // With how sure it is, for a caller that must not act on a vague one.
        assertEquals(5f, checkNotNull(trip.accuracyMeters), 0f)
        assertNull(recalledNear(51.5))
        // The same fix taken for near me is remembered, so the check above can tell.
        shadowOf(manager).simulateLocation(gps(51.6))
        AndroidLocationProvider(app).current(forceFresh = false)
        assertNotNull(recalledNear(51.6))
    }

    @Test
    fun `the remembered fix goes once precise location is taken away`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).simulateLocation(gps(51.9))
        AndroidLocationProvider(app).current(forceFresh = false)
        assertEquals(51.9, checkNotNull(AndroidLocationProvider.rememberedPreciseFix(app)).coordinates.latitude, 0.0)
        // Precise location turned down to approximate: the fix isn't used, and it's deleted, not kept
        // for if it comes back (Codex, #542).
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertNull(AndroidLocationProvider.rememberedPreciseFix(app))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertNull(AndroidLocationProvider.rememberedPreciseFix(app))
    }

    @Test
    fun `a trip's precise fix waits for GPS, says how sure it is, and isn't remembered`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        // A quick coarse network fix is there too; the trip waits for GPS instead.
        shadowOf(manager).simulateLocation(Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 51.7
            longitude = -0.12
            accuracy = 800f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        shadowOf(manager).simulateLocation(gps(51.8))
        // On the test's own dispatcher: the request is made, its fix delivered on the main looper, and
        // the result taken up, each step run in turn, with no other thread involved.
        val got = async { AndroidLocationProvider(app, remembers = false).preciseFix() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        val fix = checkNotNull(got.getCompleted())
        assertEquals(51.8, fix.coordinates.latitude, 0.0)
        assertEquals(5f, checkNotNull(fix.accuracyMeters), 0f)
        // And how long ago it was taken: just now.
        assertTrue(checkNotNull(fix.ageMillis) < 1_000L)
        assertNull(recalledNear(51.8))
    }

    // The quality each provider's pending single-fix request asked for.
    private fun askedQuality(provider: String) = shadowOf(manager).getLocationRequests(provider).single().quality

    @Test
    fun `under precise location, fused and GPS are asked for their most accurate fix, first fix and follow-up alike`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .forEach { shadowOf(manager).setProviderEnabled(it, true) }
        val provider = AndroidLocationProvider(app, remembers = false)
        // The first fix: every provider asked at once, none answering yet.
        val locating = async { provider.current(forceFresh = true) }
        runCurrent()
        assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, askedQuality(LocationManager.FUSED_PROVIDER))
        assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, askedQuality(LocationManager.GPS_PROVIDER))
        // A network fix is what it is; it's asked as before.
        assertEquals(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY, askedQuality(LocationManager.NETWORK_PROVIDER))
        locating.cancel()
        runCurrent()
        // The follow-up after a coarse fix: GPS and fused only, and both for their best.
        val following = async { provider.preciseFix() }
        runCurrent()
        assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, askedQuality(LocationManager.FUSED_PROVIDER))
        assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, askedQuality(LocationManager.GPS_PROVIDER))
        assertTrue(shadowOf(manager).getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())
        following.cancel()
    }

    @Test
    fun `under approximate location, fused is asked as before`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        val locating = async { AndroidLocationProvider(app, remembers = false).current(forceFresh = true) }
        runCurrent()
        // Its fix is approximate whatever is asked, so it isn't asked to run GPS for nothing.
        assertEquals(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY, askedQuality(LocationManager.FUSED_PROVIDER))
        locating.cancel()
    }

    // A saved place's Wi-Fi as the provider sees it: what it recognizes, and every fix it's shown.
    private class FakeWifiPlaces(private val place: FavoritePlace?) : WifiPlaceSource {
        var asked = 0
        val observed = mutableListOf<LocationFix>()
        override suspend fun recognize(): FavoritePlace? = place.also { asked++ }
        override fun observe(fix: LocationFix) {
            observed += fix
        }
    }

    private val home = FavoritePlace("home", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))

    private fun network(latitude: Double) = Location(LocationManager.NETWORK_PROVIDER).apply {
        this.latitude = latitude
        longitude = -0.12
        accuracy = 400f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    // Only the network provider answers, as indoors where GPS can't see the sky.
    private fun indoors(latitude: Double) {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .forEach { shadowOf(manager).setProviderEnabled(it, false) }
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).simulateLocation(network(latitude))
    }

    private fun kotlinx.coroutines.test.TestScope.locate(provider: AndroidLocationProvider): LocationFix? {
        val got = async { provider.current(forceFresh = true) }
        repeat(3) {
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
        runCurrent()
        return got.getCompleted()
    }

    @Test
    fun `indoors on a saved place's Wi-Fi, a rough fix gives way to the place, with no banner`() = runTest {
        // A network fix a kilometer out, claiming 400 m, while on home's Wi-Fi.
        indoors(51.51)
        val wifi = FakeWifiPlaces(home)
        val fix = checkNotNull(locate(AndroidLocationProvider(app, remembers = false, wifiPlaces = wifi)))
        assertEquals(home.coordinate, fix.coordinates)
        assertEquals(false, fix.isCoarse)
        assertEquals(false, fix.isFallback)
        assertEquals(WifiPlaces.RECOGNIZED_ACCURACY_METERS, checkNotNull(fix.accuracyMeters), 0f)
        // A rough fix teaches nothing.
        assertTrue(wifi.observed.isEmpty())
    }

    @Test
    fun `off a saved place's Wi-Fi, a rough fix stays rough`() = runTest {
        indoors(51.51)
        val wifi = FakeWifiPlaces(null)
        val fix = checkNotNull(locate(AndroidLocationProvider(app, remembers = false, wifiPlaces = wifi)))
        assertEquals(51.51, fix.coordinates.latitude, 0.0)
        assertEquals(true, fix.isCoarse)
        assertEquals(1, wifi.asked)
    }

    @Test
    fun `a precise fix is never second-guessed by the Wi-Fi, and teaches it`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).simulateLocation(gps(51.7))
        val wifi = FakeWifiPlaces(home)
        val fix = checkNotNull(AndroidLocationProvider(app, remembers = false, wifiPlaces = wifi).current(forceFresh = false))
        assertEquals(51.7, fix.coordinates.latitude, 0.0)
        assertEquals(0, wifi.asked)
        assertEquals(listOf(51.7), wifi.observed.map { it.coordinates.latitude })
        assertEquals(5f, checkNotNull(wifi.observed.single().accuracyMeters), 0f)
    }

    @Test
    fun `the precise follow-up teaches the Wi-Fi too`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).simulateLocation(gps(51.8))
        val wifi = FakeWifiPlaces(null)
        val got = async { AndroidLocationProvider(app, remembers = false, wifiPlaces = wifi).preciseFix() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(51.8, checkNotNull(got.getCompleted()).coordinates.latitude, 0.0)
        assertEquals(listOf(51.8), wifi.observed.map { it.coordinates.latitude })
    }

    @Test
    fun `a last-known fix fallen back on teaches the Wi-Fi nothing`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .forEach { shadowOf(manager).setProviderEnabled(it, false) }
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        // A GPS fix from 20 s ago: sure enough to teach, were it current, but too old for GPS to hand
        // back as its current fix, so the fresh fix times out and this one is fallen back on.
        shadowOf(manager).simulateLocation(gps(51.7))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        val wifi = FakeWifiPlaces(null)
        val got = async { AndroidLocationProvider(app, remembers = false, wifiPlaces = wifi).current(forceFresh = true) }
        runCurrent()
        advanceTimeBy(FixSelection.FRESH_FIX_TIMEOUT_MILLIS + 1)
        runCurrent()
        val fix = checkNotNull(got.getCompleted())
        assertEquals(51.7, fix.coordinates.latitude, 0.0)
        assertEquals(true, fix.isFallback)
        assertTrue(wifi.observed.isEmpty())
    }

    @Test
    fun `a minutes-old fix handed back as current is turned away, and GPS's own answer is used`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .forEach { shadowOf(manager).setProviderEnabled(it, false) }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .forEach { shadowOf(manager).setProviderEnabled(it, true) }
        // Long enough on the clock for a fix to be five minutes old.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        val warnings = mutableListOf<String>()
        val got = async { AndroidLocationProvider(app, warn = { warnings += it }, remembers = false).current(forceFresh = true) }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        // The passive provider hands back a precise fix taken five minutes ago as its current one, as
        // on a device (bug report, 2026-10-14).
        shadowOf(manager).simulateLocation(
            LocationManager.PASSIVE_PROVIDER,
            gps(51.6).apply { elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - Duration.ofSeconds(309).toNanos() },
        )
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        // Past the grace a rough fix waits for an accurate one: held there, the old fix would have
        // been used by now, flagged approximate.
        advanceTimeBy(FixSelection.COARSE_GRACE_MILLIS + 500)
        runCurrent()
        assertFalse(got.isCompleted)
        shadowOf(manager).simulateLocation(gps(51.7))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        val fix = checkNotNull(got.getCompleted())
        assertEquals(51.7, fix.coordinates.latitude, 0.0)
        assertEquals(false, fix.isCoarse)
        assertEquals(false, fix.isFallback)
        assertTrue(warnings.toString(), warnings.any { it == "fresh fix: passive provider gave a 309 s old fix, not used" })
    }

    @Test
    fun `with only approximate location the Wi-Fi is never asked`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).simulateLocation(network(51.51))
        val wifi = FakeWifiPlaces(home)
        val fix = checkNotNull(locate(AndroidLocationProvider(app, remembers = false, wifiPlaces = wifi)))
        assertEquals(51.51, fix.coordinates.latitude, 0.0)
        assertEquals(0, wifi.asked)
    }

    @Test
    fun `a shown trip's fixes come as the rider moves, and stop once they're no longer wanted`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val got = mutableListOf<LocationFix>()
        val watching = launch {
            AndroidLocationProvider(app, remembers = false).preciseUpdates(Duration.ofSeconds(5), 10f).collect { got += it }
        }
        runCurrent()
        // Every 5 s while they move 10 m, from GPS (no fused provider here).
        val asked = shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).single()
        assertEquals(5_000L, asked.intervalMillis)
        assertEquals(10f, asked.minUpdateDistanceMeters, 0f)
        shadowOf(manager).simulateLocation(gps(51.5))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(listOf(51.5), got.map { it.coordinates.latitude })
        // With how sure it is and how old, for the trip to judge it by.
        assertEquals(5f, checkNotNull(got.single().accuracyMeters), 0f)
        assertTrue(checkNotNull(got.single().ageMillis) < 1_000L)
        // Not remembered for a later lookup: a trip's fixes never are.
        assertNull(recalledNear(51.5))
        // No longer collected: the request is removed (battery), and no more come.
        watching.cancel()
        runCurrent()
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        shadowOf(manager).simulateLocation(gps(51.6))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(1, got.size)
    }

    @Test
    fun `with no GPS or fused provider, a shown trip's updates say so once per outage, not each retry`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        val said = mutableListOf<String>()
        val provider = AndroidLocationProvider(app, warn = { said += it }, remembers = false)
        // The trip asks again every few seconds while location is wanted (Codex, #458).
        repeat(3) { assertEquals(emptyList<LocationFix>(), provider.preciseUpdates(Duration.ofSeconds(5), 10f).toList()) }
        assertEquals(listOf("location updates skipped: no GPS or fused provider enabled"), said)
        // Back, then gone again: a new outage, said again.
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val watching = launch { provider.preciseUpdates(Duration.ofSeconds(5), 10f).collect {} }
        runCurrent()
        watching.cancel()
        runCurrent()
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        provider.preciseUpdates(Duration.ofSeconds(5), 10f).toList()
        assertEquals(2, said.size)
    }

    @Test
    fun `with only approximate location, a shown trip asks for no updates`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        // Ends at once, with nothing: GPS can't be asked for without precise location.
        assertEquals(emptyList<LocationFix>(), AndroidLocationProvider(app).preciseUpdates(Duration.ofSeconds(5), 10f).toList())
    }
}
