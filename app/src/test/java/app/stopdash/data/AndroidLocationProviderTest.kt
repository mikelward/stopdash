package app.stopdash.data

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.Coordinates
import kotlinx.coroutines.async
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
}
