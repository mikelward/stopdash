package app.stopdash

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.annotation.Config

/** The trip's ongoing notification: its next step, quietly, opening the trip. Well-known stations only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnTheWayNotificationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-09-26T07:02:00Z")
    private val leg = TripLeg("overground", "mildmay", "Mildmay", "910GHGHI", "Highbury & Islington", "910GSTFD", "Stratford", now, now.plusSeconds(900))
    private val trip = ActiveTrip(TripRoute(listOf(leg)), "Stratford", startedAt = now, vehicleId = "EXAMPLE", boarded = true)
    private val riding = TripProgress.Riding(leg, "Hackney Central", 4, now.plus(Duration.ofMinutes(12)), getOffSoon = false)

    @Test
    fun `shows the next step, ongoing and quiet, and opens the trip`() {
        OnTheWayNotification.ensureChannel(app)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(OnTheWayNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        val notification = OnTheWayNotification.build(app, trip, riding, failed = false, updatedAt = now, now = now)
        assertEquals(OnTheWayNotification.CHANNEL_ID, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Get off at Stratford", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("4 stops · next Hackney Central", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("To Stratford", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertTrue(shadowOf(notification.contentIntent).savedIntent.getBooleanExtra(GetOffSoonAlert.EXTRA_OPEN_ON_THE_WAY, false))
    }

    @Test
    fun `a foreground start Android refuses is said, not only logged, until one goes ahead`() {
        assertFalse(OnTheWayService.promote { throw IllegalStateException("not allowed") })
        assertTrue(OnTheWayService.refused.value)
        assertTrue(OnTheWayService.promote {})
        assertFalse(OnTheWayService.refused.value)
    }

    @Test
    fun `a failed update says so rather than pass the step off as current`() {
        val notification = OnTheWayNotification.build(app, trip, riding, failed = true, updatedAt = null, now = now)
        assertEquals("Couldn't update just now", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `a train's time from no recent answer says it's updating, not as current`() {
        val waiting = TripProgress.Waiting(leg, now.plus(Duration.ofMinutes(4)))
        // Restored after the app was closed: no answer yet, so the old time isn't stood behind.
        val restored = OnTheWayNotification.build(app, trip, waiting, failed = false, updatedAt = null, now = now)
        assertEquals("Updating…", restored.extras.getString(Notification.EXTRA_TEXT))
        val answered = OnTheWayNotification.build(app, trip, waiting, failed = false, updatedAt = now, now = now)
        assertEquals("Due in 4 min", answered.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `a trip ended mid-wait stops being followed at once, not after the wait`() = runTest {
        val kept = MutableStateFlow<ActiveTrip?>(trip)
        var refreshes = 0
        val following = launch { followTrip(kept, MutableStateFlow(0), Duration.ofSeconds(30), Duration.ofHours(4)) { refreshes++ } }
        advanceTimeBy(5_000)
        kept.value = null // End trip, or arrival.
        following.join()
        assertEquals(1, refreshes)
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun `a trip still being started is waited for, not taken for none`() = runTest {
        // Started from the Start tap: the trip isn't saved yet when the service first looks.
        val kept = MutableStateFlow<ActiveTrip?>(null)
        val starting = MutableStateFlow(1)
        var refreshes = 0
        val following = launch { followTrip(kept, starting, Duration.ofSeconds(30), Duration.ofHours(4)) { refreshes++ } }
        advanceTimeBy(1_000)
        kept.value = trip
        starting.value = 0
        advanceTimeBy(1_000)
        assertEquals(1, refreshes)
        kept.value = null
        following.join()
    }

    @Test
    fun `the phone is kept awake while a kept trip is read again, not only once it's followed`() = runTest {
        // Storage fails for five minutes, longer than one hold of the wake lock.
        val kept = MutableStateFlow<ActiveTrip?>(null)
        var reads = 0
        var renewals = 0
        val following = launch {
            followTrip(kept, MutableStateFlow(0), Duration.ofSeconds(30), Duration.ofHours(4), restore = {
                reads++
                false
            }, keepAwake = { renewals++ }) {}
        }
        advanceTimeBy(301_000)
        assertEquals(reads, renewals)
        following.cancel()
    }

    @Test
    fun `following that fails stops the service and says why, not leaves it up`() = runTest {
        val said = mutableListOf<String>()
        var stops = 0
        var failed = 0
        followThenStop(warn = { said += it }, stop = { stops++ }, failed = { failed++ }) { throw IllegalStateException("broken") }
        assertEquals(1, stops)
        assertEquals(1, failed)
        assertEquals(listOf("following failed: IllegalStateException"), said)
        followThenStop(warn = { said += it }, stop = { stops++ }, failed = { failed++ }) {}
        assertEquals(2, stops)
        assertEquals(1, failed)
        assertEquals(1, said.size)
    }

    @Test
    fun `a follower that fails leaves the trip's screen saying it updates only while open`() = runTest {
        OnTheWayService.promote {}
        followThenStop(warn = {}, stop = {}, failed = OnTheWayService::notFollowing) { throw IllegalStateException("broken") }
        assertTrue(OnTheWayService.refused.value)
        OnTheWayService.promote {}
        assertFalse(OnTheWayService.refused.value)
    }

    @Test
    fun `a kept trip that can't be read is retried for minutes, not a fresh four hours`() = runTest {
        var reads = 0
        followTrip(MutableStateFlow(null), MutableStateFlow(0), Duration.ofSeconds(30), Duration.ofHours(4), restore = {
            reads++
            false
        }) {}
        // Its age isn't known until it's read, so the reads get only OnTheWayWakeLock.READ_FOR.
        assertEquals(OnTheWayWakeLock.READ_FOR.toMillis(), currentTime)
        assertEquals(20, reads)
    }

    @Test
    fun `a kept trip that couldn't be read yet is read again, not taken for none`() = runTest {
        // Storage fails twice as the service starts; the trip is there once it can be read.
        val kept = MutableStateFlow<ActiveTrip?>(null)
        var reads = 0
        var refreshes = 0
        val following = launch {
            followTrip(kept, MutableStateFlow(0), Duration.ofSeconds(30), Duration.ofHours(4), restore = {
                reads++
                if (reads == 3) kept.value = trip
                reads >= 3
            }) { refreshes++ }
        }
        advanceTimeBy(61_000)
        assertEquals(3, reads)
        assertEquals(1, refreshes)
        kept.value = null
        following.join()
    }

    @Test
    fun `a trip left on the way is followed in the background only up to its cap`() = runTest {
        // Never ended or arrived: the service lets go at the cap rather than keep the phone awake.
        val kept = MutableStateFlow<ActiveTrip?>(trip)
        var refreshes = 0
        followTrip(kept, MutableStateFlow(0), Duration.ofSeconds(30), Duration.ofSeconds(100)) { refreshes++ }
        assertEquals(100_000L, currentTime)
        assertEquals(4, refreshes) // At 0, 30, 60 and 90 s.
    }

    @Test
    fun `the cap counts from when the trip started, not from each start of the service`() = runTest {
        // Restarted for a trip already followed for nearly four hours: only the minute left runs.
        val kept = MutableStateFlow<ActiveTrip?>(trip)
        var refreshes = 0
        followTrip(kept, MutableStateFlow(0), Duration.ofSeconds(30), Duration.ofHours(4),
            startedFor = { Duration.ofHours(4).minusSeconds(70) }) { refreshes++ }
        assertEquals(70_000L, currentTime)
        assertEquals(3, refreshes) // At 0, 30 and 60 s.
    }

    @Test
    fun `following a trip keeps the phone awake enough to refresh, and lets go after`() {
        val lock = OnTheWayWakeLock.acquire(app)
        val held = ShadowPowerManager.getLatestWakeLock()
        assertTrue(held.isHeld)
        assertEquals(OnTheWayWakeLock.TAG, shadowOf(held).tag)
        OnTheWayWakeLock.release(lock)
        assertFalse(held.isHeld)
    }

    @Test
    fun `location refused as the service starts falls back to following without it, not a crash`() {
        val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val withLocation = special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val tried = mutableListOf<Int>()
        val warned = mutableListOf<String>()
        // Permission revoked between the check and the start: Android 14+ throws on the location type.
        val located = enterForeground(canLocate = true, warn = { warned += it }) { type ->
            tried += type
            if (type == withLocation) throw SecurityException("revoked")
        }
        assertEquals(false, located)
        assertEquals(listOf(withLocation, special), tried)
        assertEquals(1, warned.size)
        // Refused altogether (from the background): not started, said so.
        assertNull(enterForeground(canLocate = false, warn = { warned += it }) { throw IllegalStateException("background") })
        assertEquals(true, enterForeground(canLocate = true, warn = {}) {})
    }
}
