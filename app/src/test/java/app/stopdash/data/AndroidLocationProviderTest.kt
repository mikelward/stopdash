package app.stopdash.data

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.Coordinates
import app.stopdash.domain.LocationFix
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
