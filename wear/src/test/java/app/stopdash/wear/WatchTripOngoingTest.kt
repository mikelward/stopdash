package app.stopdash.wear

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import app.stopdash.data.WatchTrip
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The trip on the way as the watch's own ongoing activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WatchTripOngoingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val now = Instant.parse("2026-10-03T08:00:00Z")
    private val trip = WatchTrip(
        title = "Board in 3 min",
        steps = listOf(
            WatchTrip.Step("Walk to King's Cross St. Pancras (5 min)", walk = true),
            WatchTrip.Step("King's Cross St. Pancras → Victoria", "victoria", "Victoria", "tube"),
        ),
        current = 1,
        sentAt = now.toEpochMilli(),
    )
    private val elapsedNow = 10_000_000L

    @Test
    fun `it says the rider's step, never a countdown that would go out of date`() {
        val content = WatchTripOngoing.content(HeldTrip(trip, elapsedNow), now, elapsedNow)
        assertEquals("King's Cross St. Pancras → Victoria", content!!.text)
    }

    @Test
    fun `it lasts until the trip would read out of date`() {
        val content = WatchTripOngoing.content(HeldTrip(trip, elapsedNow - 30_000), now, elapsedNow)
        assertEquals(WatchTripState.STALE_AFTER.minusSeconds(30), content!!.lasts)
    }

    @Test
    fun `a stamp older than the time held runs it out sooner`() {
        val content = WatchTripOngoing.content(HeldTrip(trip.copy(sentAt = now.minusSeconds(90).toEpochMilli()), elapsedNow - 10_000), now, elapsedNow)
        assertEquals(WatchTripState.STALE_AFTER.minusSeconds(90), content!!.lasts)
    }

    @Test
    fun `no trip, or one out of date, has none`() {
        assertNull(WatchTripOngoing.content(null, now, elapsedNow))
        // Out of date but still shown by the app: the ongoing activity can't say so, so it goes.
        val stale = HeldTrip(trip, elapsedNow - WatchTripState.STALE_AFTER.plusSeconds(1).toMillis())
        assertNotNull(WatchTripState.shown(stale, now, elapsedNow))
        assertNull(WatchTripOngoing.content(stale, now, elapsedNow))
        val gone = HeldTrip(trip, elapsedNow - WatchTripState.GONE_AFTER.plusSeconds(1).toMillis())
        assertNull(WatchTripOngoing.content(gone, now, elapsedNow))
    }

    @Test
    fun `a trip posts a silent ongoing activity that opens the watch app`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WatchTripOngoing.update(app, fresh())
        val posted = shadowOf(manager).getNotification(WatchTripOngoing.ID)
        assertNotNull(posted)
        assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("King's Cross St. Pancras → Victoria", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(posted.timeoutAfter > 0)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(WatchTripOngoing.CHANNEL_ID).importance)
        assertEquals(WatchHomeActivity::class.java.name, shadowOf(posted.contentIntent).savedIntent.component?.className)
        // The ongoing activity rides in the notification's extras (the library's recovery reads the
        // active notifications, which Robolectric's shadow doesn't hand it back).
        assertTrue(posted.extras.containsKey("android.wearable.ongoingactivities.EXTENSIONS"))
    }

    @Test
    fun `the trip ending takes it off`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WatchTripOngoing.update(app, fresh())
        WatchTripOngoing.update(app, null)
        assertNull(shadowOf(manager).getNotification(WatchTripOngoing.ID))
    }

    @Test
    fun `without notifications allowed nothing is posted`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WatchTripOngoing.update(app, fresh())
        assertNull(shadowOf(manager).getNotification(WatchTripOngoing.ID))
    }

    @Test
    fun `setting the clock ahead past the stamp takes it off`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WatchTripOngoing.update(app, fresh())
        assertNotNull(shadowOf(manager).getNotification(WatchTripOngoing.ID))
        // As the clock reads after being set three minutes ahead: the phone's stamp is that old now.
        val held = HeldTrip(trip.copy(sentAt = Instant.now().minusSeconds(3 * 60).toEpochMilli()), SystemClock.elapsedRealtime())
        WatchTripState.take(held)
        try {
            ClockChangeReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
            assertNull(shadowOf(manager).getNotification(WatchTripOngoing.ID))
        } finally {
            WatchTripState.take(null)
        }
    }

    // Arrived just now, by the clocks [WatchTripOngoing.update] reads.
    private fun fresh() = HeldTrip(trip.copy(sentAt = Instant.now().toEpochMilli()), SystemClock.elapsedRealtime())
}
