package app.stopdash

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.ui.ActiveTripTracker.BoardPost
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A trip's alerts said by its own notification while that's up (maintainer, 2026-10-09): one
 * notification for the trip, so no second one crowds the Live Update's chip. Well-known stations only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TripAlertsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val now = Instant.now()
    private val leg = TripLeg("overground", "mildmay", "Mildmay", "910GHGHI", "Highbury & Islington", "910GSTFD", "Stratford", now, now.plusSeconds(600))
    private val trip = ActiveTrip(TripRoute(listOf(leg)), "Stratford", startedAt = now, vehicleId = "EXAMPLE", boarded = true)
    private val riding = TripProgress.Riding(leg, "Stratford", 1, now.plus(Duration.ofMinutes(2)), getOffSoon = true)
    private val logged = mutableListOf<String>()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        TripAlerts.clearAll()
        OnTheWayNotification.dismissedTrip = null
    }

    @After
    fun tearDown() = TripAlerts.clearAll()

    private fun showTrip() {
        OnTheWayNotification.ensureChannel(app)
        OnTheWayNotification.show(app, trip, riding, failed = false, updatedAt = Instant.now())
    }

    private fun tripNotification(): Notification = shadowOf(manager).getNotification(OnTheWayNotification.ID)

    private fun onlyAlertsOnce(n: Notification) = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0

    @Test
    fun `with the trip's notification up, get off soon is said in it, heard once, and nothing else is posted`() {
        showTrip()
        assertTrue(GetOffSoonAlert.post(app, trip, riding, now, logged::add))
        // No notification of its own: the trip's is the only one.
        assertEquals(listOf(OnTheWayNotification.ID), manager.activeNotifications.map { it.id })
        assertEquals("on the way: get-off alert shown in the trip's notification, 1 stop left, by train", logged.single())

        showTrip()
        val alerting = tripNotification()
        // On the alert's own loud channel, so it's heard (and muted) as its own notification was.
        assertEquals(GetOffSoonAlert.CHANNEL_ID, alerting.channelId)
        assertEquals("Get off at Stratford", alerting.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Next stop", alerting.extras.getString(Notification.EXTRA_TEXT))
        assertFalse("the alert isn't heard", onlyAlertsOnce(alerting))

        // Shown again on the next tick: still said, but quietly.
        showTrip()
        val kept = tripNotification()
        assertEquals("Get off at Stratford", kept.extras.getString(Notification.EXTRA_TITLE))
        assertTrue("heard again on every tick", onlyAlertsOnce(kept))

        // Done: the trip's notification says the step again, on its own quiet channel.
        GetOffSoonAlert.cancel(app)
        showTrip()
        assertEquals(OnTheWayNotification.CHANNEL_ID, tripNotification().channelId)
        assertTrue(onlyAlertsOnce(tripNotification()))
    }

    @Test
    fun `with no trip notification up, an alert posts on its own as before`() {
        assertTrue(GetOffSoonAlert.post(app, trip, riding, now, logged::add))
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(GetOffSoonAlert.CHANNEL_ID, posted.channelId)
        assertTrue(TripAlerts.alerts.value.isEmpty())
    }

    @Test
    fun `time to board is kept up to date in the trip's notification, heard only when new`() {
        showTrip()
        val waiting = TripProgress.Waiting(leg, now.plus(Duration.ofMinutes(2)))
        assertTrue(TimeToBoardAlert.post(app, trip, waiting, BoardPost.NEW, now, now, logged::add))
        showTrip()
        assertEquals(TimeToBoardAlert.CHANNEL_ID, tripNotification().channelId)
        assertFalse(onlyAlertsOnce(tripNotification()))
        // Kept up to date while it's up: quietly, and still counted as showing.
        assertTrue(TimeToBoardAlert.post(app, trip, waiting, BoardPost.KEEP, now, now, logged::add))
        showTrip()
        assertTrue(onlyAlertsOnce(tripNotification()))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `the most pressing standing alert is said, and a muted or lapsed one isn't`() {
        val later = now.plus(Duration.ofMinutes(5))
        val alerts = mapOf(
            TripAlerts.Kind.DISRUPTION to TripAlerts.Alert(TripAlerts.Kind.DISRUPTION, "Jubilee: Minor Delays", null, later, sound = true),
            TripAlerts.Kind.GET_OFF to TripAlerts.Alert(TripAlerts.Kind.GET_OFF, "Get off at Stratford", null, later, sound = true),
        )
        assertEquals(TripAlerts.Kind.GET_OFF, TripAlerts.top(alerts, now) { true }?.kind)
        // Its channel muted: the next one is said instead.
        assertEquals(TripAlerts.Kind.DISRUPTION, TripAlerts.top(alerts, now) { it != GetOffSoonAlert.CHANNEL_ID }?.kind)
        // Past their time: none.
        assertNull(TripAlerts.top(alerts, later) { true })
    }

    @Test
    fun `get off soon posted again as its stop's time moves stays quiet`() {
        showTrip()
        assertTrue(GetOffSoonAlert.post(app, trip, riding, now, logged::add))
        showTrip()
        assertFalse(onlyAlertsOnce(tripNotification()))
        // TfL moves the stop's time: the tracker says it again, for its deadline only.
        assertTrue(GetOffSoonAlert.post(app, trip, riding.copy(getOffAt = now.plus(Duration.ofMinutes(3))), now, logged::add))
        showTrip()
        assertTrue("heard again for a moved deadline", onlyAlertsOnce(tripNotification()))
    }

    @Test
    fun `a new alert a more pressing one would hide waits, unheard, rather than being counted as said`() {
        showTrip()
        assertTrue(GetOffSoonAlert.post(app, trip, riding, now, logged::add))
        val waiting = TripProgress.Waiting(leg, now.plus(Duration.ofMinutes(2)))
        // Held: false, so the tracker offers it again next refresh rather than marking it said.
        assertFalse(TimeToBoardAlert.post(app, trip, waiting, BoardPost.NEW, now, now, logged::add))
        assertFalse(TripAlerts.alerts.value.containsKey(TripAlerts.Kind.BOARD))
        assertEquals(1, manager.activeNotifications.size)
        // Once nothing outranks it, it's taken.
        GetOffSoonAlert.cancel(app)
        assertTrue(TimeToBoardAlert.post(app, trip, waiting, BoardPost.NEW, now, now, logged::add))
        assertTrue(TripAlerts.alerts.value.getValue(TripAlerts.Kind.BOARD).sound)
    }
}
