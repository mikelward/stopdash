package app.stopdash

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Get off soon": its channel, the alert, and what happens with notifications off. Well-known stations only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GetOffSoonAlertTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val now = Instant.parse("2026-09-26T07:02:00Z")
    private val leg = TripLeg("overground", "mildmay", "Mildmay", "910GHGHI", "Highbury & Islington", "910GSTFD", "Stratford", now, now.plusSeconds(600))
    private val trip = ActiveTrip(TripRoute(listOf(leg)), "Stratford", startedAt = now, vehicleId = "EXAMPLE", boarded = true)
    private val riding = TripProgress.Riding(leg, "Stratford", 1, now.plus(Duration.ofMinutes(2)), getOffSoon = true)
    private val logged = mutableListOf<String>()

    @Test
    fun `the app may vibrate, so the alert is felt in a pocket`() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        assertTrue(requested.orEmpty().contains(Manifest.permission.VIBRATE))
    }

    @Test
    fun `alerts on its own loud channel and opens the trip`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(GetOffSoonAlert.post(app, trip, riding, now, logged::add))
        val channel = manager.getNotificationChannel(GetOffSoonAlert.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.shouldVibrate())
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(GetOffSoonAlert.CHANNEL_ID, posted.channelId)
        assertEquals("Get off at Stratford", posted.extras.getString("android.title"))
        assertEquals("Next stop", posted.extras.getString("android.text"))
        // Gone five minutes after the stop, not left to go stale.
        assertEquals(Duration.ofMinutes(7).toMillis(), posted.timeoutAfter)
        // Posted again (a refresh after the app died before it was marked said): heard once only.
        assertTrue(posted.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        val open = shadowOf(posted.contentIntent).savedIntent
        assertTrue(open.getBooleanExtra(GetOffSoonAlert.EXTRA_OPEN_ON_THE_WAY, false))
    }

    @Test
    fun `a late alert lasts only until five minutes after the stop, and one past that isn't posted`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Posted four minutes after the stop was due: one minute left, not five.
        val late = riding.copy(getOffAt = now.minus(Duration.ofMinutes(4)))
        assertTrue(GetOffSoonAlert.post(app, trip, late, now, logged::add))
        assertEquals(Duration.ofMinutes(1).toMillis(), shadowOf(manager).allNotifications.single().timeoutAfter)
        GetOffSoonAlert.cancel(app)
        // Six minutes after: too late to be of use, so nothing is posted (and nothing's left to retry).
        val tooLate = riding.copy(getOffAt = now.minus(Duration.ofMinutes(6)))
        assertTrue(GetOffSoonAlert.post(app, trip, tooLate, now, logged::add))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `with notifications refused nothing is posted, and it's logged without the stop`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(GetOffSoonAlert.canAlert(app))
        assertFalse(GetOffSoonAlert.post(app, trip, riding, now, logged::add))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertFalse(logged.single().contains("Stratford"))
    }
}
