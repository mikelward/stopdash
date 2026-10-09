package app.stopdash

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyAlerts
import app.stopdash.domain.JourneyEnd
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlinx.coroutines.launch
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The alarm that starts each journey alert check on time (SPEC *Journeys → Alerts*). Public stations only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JourneyAlertAlarmTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val journey = FavoriteJourney(JourneyEnd("940GZZLUEUS", "Euston"), JourneyEnd("940GZZLUWLO", "Waterloo"), "northern", "Northern", "tube")

    // A Monday at 06:00, two hours before the saved way's default window opens.
    private val early = LocalDateTime.of(2026, 10, 5, 6, 0).atZone(ZoneId.systemDefault()).toInstant()
    private val opens = LocalDateTime.of(2026, 10, 5, 8, 0).atZone(ZoneId.systemDefault()).toInstant()

    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun pending(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().filter { it.state == WorkInfo.State.ENQUEUED }

    private fun sync(schedules: Map<String, app.stopdash.domain.JourneyAlertSchedule> = JourneyAlerts.defaultsFor(journey)) =
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), schedules, early) }

    @Test
    fun a_check_due_later_is_backed_by_a_windowed_alarm_and_one_doze_delivers() {
        sync()
        val armed = alarms.scheduledAlarms
        assertEquals(2, armed.size)
        for (alarm in armed) {
            assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
            // Two hours on from the sync, as the check itself is.
            val inMillis = alarm.triggerAtMs - android.os.SystemClock.elapsedRealtime()
            assertEquals(java.time.Duration.ofHours(2).toMillis().toDouble(), inMillis.toDouble(), 1_000.0)
        }
        // One with a ten-minute window, however far ahead it is (a plain inexact one could be an hour late).
        assertEquals(1, armed.count { it.windowLengthMs == JourneyAlertAlarm.WINDOW.toMillis() })
        assertEquals(1, armed.count { it.allowWhileIdle })
        // The delayed job stays as the backstop.
        assertEquals(1, pending().size)
    }

    @Test
    fun nothing_watched_takes_the_alarm_down() {
        sync()
        sync(emptyMap())
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test
    fun the_alarm_starts_the_check_it_was_for_at_once() {
        sync()
        val before = pending().single()
        runBlocking { JourneyAlertAlarm.fire(context, opens, asks = true, now = opens) }
        val after = pending().single()
        assertNotEquals(before.id, after.id)
        assertEquals(0L, after.initialDelayMillis)
        assertNotNull(after.tags.find { it == JourneyAlertChecks.dueTag(opens) })
        // The other alarm, now with nothing to do, is taken down rather than left to wake the phone.
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test
    fun the_second_alarm_leaves_a_check_the_first_started_be() {
        sync()
        runBlocking { JourneyAlertAlarm.fire(context, opens, asks = true, now = opens) }
        val started = pending().single()
        runBlocking { JourneyAlertAlarm.fire(context, opens, asks = true, now = opens.plusSeconds(60)) }
        assertEquals(started.id, pending().single().id)
    }

    @Test
    fun an_alarm_waits_for_a_schedule_change_under_way_and_then_leaves_its_result_be() = kotlinx.coroutines.test.runTest {
        sync()
        // A sync is under way, holding the schedule's lock, when the alarm fires.
        JourneyAlertChecks.syncLock.lock()
        val fired = launch { JourneyAlertAlarm.fire(context, opens, asks = true, now = opens) }
        val retimed = try {
            testScheduler.advanceUntilIdle()
            assertFalse(fired.isCompleted)
            // That sync re-times the check, then lets go.
            val later = opens.plusSeconds(900)
            WorkManager.getInstance(context).enqueueUniqueWork(
                JOURNEY_ALERTS_WORK,
                androidx.work.ExistingWorkPolicy.REPLACE,
                JourneyAlertChecks.checkRequest(later, true, ZoneId.systemDefault(), java.time.Duration.ofMinutes(30)),
            ).result.get()
            pending().single().id
        } finally {
            // Always let go: the lock is shared, and a test that fails holding it would hang every one after.
            JourneyAlertChecks.syncLock.unlock()
        }
        fired.join()
        // The alarm, for the old time, found nothing waiting for it and left the new check alone.
        assertEquals(retimed, pending().single().id)
    }

    @Test
    fun an_alarm_for_a_check_since_re_timed_leaves_the_new_one_be() {
        sync()
        val before = pending().single()
        runBlocking { JourneyAlertAlarm.fire(context, opens.minusSeconds(900), asks = true, now = opens) }
        assertEquals(before.id, pending().single().id)
    }
}
