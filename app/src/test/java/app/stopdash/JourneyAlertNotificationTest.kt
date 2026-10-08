package app.stopdash

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyAlertAction
import app.stopdash.domain.JourneyAlertResult
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.JourneyLineAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The silent journey alert (SPEC *Journeys → Alerts*): posted per journey, found again, and cleared. Public stations only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JourneyAlertNotificationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val journey = FavoriteJourney(JourneyEnd("940GZZLUEUS", "Euston"), JourneyEnd("940GZZLUWLO", "Waterloo"), "northern", "Northern", "tube")
    private val result = JourneyAlertResult(journey.key, listOf(JourneyLineAlert("northern", "Northern", "Severe Delays", "Northern line: severe delays.")))

    @Before
    fun allow() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun an_alert_is_posted_silently_on_its_own_channel_titled_by_direction() {
        assertTrue(JourneyAlertNotification.post(context, journey, result))
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(JourneyAlertNotification.CHANNEL_ID, posted.channelId)
        assertEquals("Euston ➔ Waterloo", posted.extras.getString("android.title"))
        assertEquals("Northern: Severe Delays", posted.extras.getCharSequence("android.text").toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(JourneyAlertNotification.CHANNEL_ID).importance)
        assertEquals(setOf(journey.key), JourneyAlertNotification.showing(context))
    }

    @Test
    fun nothing_is_posted_without_permission() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(JourneyAlertNotification.post(context, journey, result))
        assertTrue(JourneyAlertNotification.showing(context).isEmpty())
    }

    @Test
    fun an_alert_no_longer_watched_is_taken_down_and_forgotten() {
        JourneyAlertNotification.post(context, journey, result)
        AnnouncedJourneyAlerts.write(context, mapOf(journey.key to result.fingerprint))
        JourneyAlertNotification.clearExcept(context, emptyMap())
        assertTrue(JourneyAlertNotification.showing(context).isEmpty())
        assertTrue(AnnouncedJourneyAlerts.read(context).isEmpty())
    }

    @Test
    fun the_channel_turned_off_means_no_alert() {
        JourneyAlertNotification.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(JourneyAlertNotification.CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        assertFalse(JourneyAlertNotification.canAlert(context))
        assertFalse(JourneyAlertNotification.post(context, journey, result))
    }

    @Test
    fun a_swiped_alert_is_remembered_by_what_it_said() {
        val ran = mutableListOf<String>()
        val previous = JourneyAlertDismissReceiver.writes
        JourneyAlertDismissReceiver.writes = java.util.concurrent.Executor { ran += Thread.currentThread().name; it.run() }
        try {
            JourneyAlertNotification.post(context, journey, result)
            AnnouncedJourneyAlerts.write(context, mapOf(journey.key to result.fingerprint))
            val manager = context.getSystemService(NotificationManager::class.java)
            // Swiped: Android sends the delete intent.
            shadowOf(manager).allNotifications.single().deleteIntent.send()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(1, ran.size)
            assertEquals(mapOf(journey.key to result.fingerprint), AnnouncedJourneyAlerts.read(context, AnnouncedJourneyAlerts.DISMISSED))
        } finally {
            JourneyAlertDismissReceiver.writes = previous
        }
    }

    @Test
    fun an_alert_times_out_with_its_window_so_it_never_outlives_it() {
        val now = java.time.Instant.parse("2026-10-05T08:50:00Z")
        assertTrue(JourneyAlertNotification.post(context, journey, result, until = now.plusSeconds(600), now = now))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(600_000L, shadowOf(manager).allNotifications.single().timeoutAfter)
        // A window already closed posts nothing.
        assertFalse(JourneyAlertNotification.post(context, journey, result, until = now, now = now))
    }

    @Test
    fun a_swipe_landing_during_a_check_is_kept_when_the_check_records_its_own() {
        val other = "other-journey"
        AnnouncedJourneyAlerts.updateDismissed(context) { mapOf(other to "old") }
        AnnouncedJourneyAlerts.write(context, mapOf(journey.key to result.fingerprint))
        // The check, applying its clear of the other journey, after a swipe recorded mid-check.
        AnnouncedJourneyAlerts.dismiss(context, journey.key, result.fingerprint)
        AnnouncedJourneyAlerts.updateDismissed(context) { it - other }
        assertEquals(mapOf(journey.key to result.fingerprint), AnnouncedJourneyAlerts.read(context, AnnouncedJourneyAlerts.DISMISSED))
    }

    @Test
    fun a_swipe_racing_a_renewal_takes_the_renewed_copy_down() {
        val previous = JourneyAlertDismissReceiver.writes
        JourneyAlertDismissReceiver.writes = java.util.concurrent.Executor { it.run() }
        try {
            JourneyAlertNotification.post(context, journey, result)
            AnnouncedJourneyAlerts.write(context, mapOf(journey.key to result.fingerprint))
            val manager = context.getSystemService(NotificationManager::class.java)
            val swipedCopy = shadowOf(manager).allNotifications.single().deleteIntent
            // A check renews it just before the swipe of the earlier copy is heard.
            JourneyAlertNotification.post(context, journey, result)
            swipedCopy.send()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue(JourneyAlertNotification.showing(context).isEmpty())
        } finally {
            JourneyAlertDismissReceiver.writes = previous
        }
    }

    @Test
    fun a_renewal_saying_something_new_survives_an_old_swipe() {
        JourneyAlertNotification.post(context, journey, result)
        JourneyAlertNotification.cancelIfSaying(context, journey.key, "something else")
        assertEquals(setOf(journey.key), JourneyAlertNotification.showing(context))
    }

    @Test
    fun a_swipe_of_an_earlier_copy_doesnt_take_down_changed_text() {
        val previous = JourneyAlertDismissReceiver.writes
        JourneyAlertDismissReceiver.writes = java.util.concurrent.Executor { it.run() }
        try {
            JourneyAlertNotification.post(context, journey, result)
            AnnouncedJourneyAlerts.write(context, mapOf(journey.key to result.fingerprint))
            val manager = context.getSystemService(NotificationManager::class.java)
            val earlier = shadowOf(manager).allNotifications.single().deleteIntent
            val worse = JourneyAlertResult(journey.key, listOf(JourneyLineAlert("northern", "Northern", "Suspended", null)))
            JourneyAlertNotification.post(context, journey, worse)
            earlier.send()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            // The swipe is of what the earlier copy said; the changed alert stays up.
            assertEquals(mapOf(journey.key to result.fingerprint), AnnouncedJourneyAlerts.read(context, AnnouncedJourneyAlerts.DISMISSED))
            assertEquals(setOf(journey.key), JourneyAlertNotification.showing(context))
        } finally {
            JourneyAlertDismissReceiver.writes = previous
        }
    }

    @Test
    fun clearing_other_journeys_keeps_a_swipe_recorded_meanwhile() {
        AnnouncedJourneyAlerts.updateDismissed(context) { mapOf("gone" to "old") }
        AnnouncedJourneyAlerts.write(context, mapOf(journey.key to result.fingerprint))
        AnnouncedJourneyAlerts.dismiss(context, journey.key, result.fingerprint)
        JourneyAlertNotification.clearExcept(context, mapOf(journey.key to null))
        assertEquals(mapOf(journey.key to result.fingerprint), AnnouncedJourneyAlerts.read(context, AnnouncedJourneyAlerts.DISMISSED))
    }

    @Test
    fun a_swipe_of_an_alert_posted_but_never_recorded_is_kept_while_its_journey_is_watched() {
        // The check died between posting and recording: no record, but the journey is still watched.
        AnnouncedJourneyAlerts.dismiss(context, journey.key, result.fingerprint) { true }
        assertEquals(mapOf(journey.key to result.fingerprint), AnnouncedJourneyAlerts.read(context, AnnouncedJourneyAlerts.DISMISSED))
    }

    @Test
    fun a_swipe_heard_after_its_alert_was_cleared_is_dropped() {
        AnnouncedJourneyAlerts.write(context, mapOf(journey.key to result.fingerprint))
        // The journey removed: its alert and record cleared before the swipe is heard.
        JourneyAlertNotification.clearExcept(context, emptyMap())
        AnnouncedJourneyAlerts.dismiss(context, journey.key, result.fingerprint)
        // Nothing of the removed journey is kept on the device.
        assertEquals(emptyMap<String, String>(), AnnouncedJourneyAlerts.read(context, AnnouncedJourneyAlerts.DISMISSED))
        assertEquals(emptyMap<String, String>(), AnnouncedJourneyAlerts.read(context))
    }

    @Test
    fun an_alert_found_for_a_direction_no_longer_watched_is_cleared() {
        val found = result.copy(directions = setOf(journey.from.stopId), until = java.time.Instant.ofEpochSecond(1_000))
        JourneyAlertNotification.post(context, journey, found)
        AnnouncedJourneyAlerts.write(context, mapOf(journey.key to found.fingerprint))
        // Still watched the same way: kept.
        JourneyAlertNotification.clearExcept(context, mapOf(journey.key to JourneyAlertResult.scope(found.directions, found.until)))
        assertEquals(setOf(journey.key), JourneyAlertNotification.showing(context))
        // Watched now only the other way: it goes, and so does its record.
        JourneyAlertNotification.clearExcept(context, mapOf(journey.key to JourneyAlertResult.scope(setOf(journey.to.stopId), found.until)))
        assertEquals(emptySet<String>(), JourneyAlertNotification.showing(context))
        assertEquals(emptyMap<String, String>(), AnnouncedJourneyAlerts.read(context))
    }

    @Test
    fun an_alert_up_with_no_record_is_judged_by_what_it_says() {
        val found = result.copy(directions = setOf(journey.from.stopId), until = java.time.Instant.ofEpochSecond(1_000))
        // Posted, but the process died before its record was saved.
        JourneyAlertNotification.post(context, journey, found)
        assertEquals(emptyMap<String, String>(), AnnouncedJourneyAlerts.read(context))
        // Still watched the way it says: kept.
        JourneyAlertNotification.clearExcept(context, mapOf(journey.key to JourneyAlertResult.scope(found.directions, found.until)))
        assertEquals(setOf(journey.key), JourneyAlertNotification.showing(context))
        // Watched the other way now: it goes.
        JourneyAlertNotification.clearExcept(context, mapOf(journey.key to JourneyAlertResult.scope(setOf(journey.to.stopId), found.until)))
        assertEquals(emptySet<String>(), JourneyAlertNotification.showing(context))
    }

    @Test
    fun an_alert_up_is_judged_by_what_it_says_over_an_older_record() {
        val older = result.copy(directions = setOf(journey.to.stopId), until = java.time.Instant.ofEpochSecond(1_000))
        val newer = result.copy(directions = setOf(journey.from.stopId), until = java.time.Instant.ofEpochSecond(2_000))
        // The way back's alert recorded, then replaced by the way out's, and the process died before saving it.
        AnnouncedJourneyAlerts.write(context, mapOf(journey.key to older.fingerprint))
        JourneyAlertNotification.post(context, journey, newer)
        // Watched the way the alert up says: kept, however the stale record reads (Codex on #700).
        JourneyAlertNotification.clearExcept(context, mapOf(journey.key to JourneyAlertResult.scope(newer.directions, newer.until)))
        assertEquals(setOf(journey.key), JourneyAlertNotification.showing(context))
    }

    @Test
    fun a_check_records_onto_the_record_as_it_stands_not_its_old_copy() {
        val found = result.copy(directions = setOf(journey.from.stopId), until = java.time.Instant.ofEpochSecond(1_000))
        JourneyAlertNotification.post(context, journey, found)
        AnnouncedJourneyAlerts.write(context, mapOf(journey.key to found.fingerprint))
        // The journey's alerts turned off while a check was out: the sync takes the alert down and forgets it.
        JourneyAlertNotification.clearExcept(context, emptyMap())
        // The check, done with nothing for it, doesn't bring the record back (Codex on #700).
        AnnouncedJourneyAlerts.recordDone(context, emptyList())
        assertEquals(emptyMap<String, String>(), AnnouncedJourneyAlerts.read(context))
        // What it did post is recorded.
        AnnouncedJourneyAlerts.recordDone(context, listOf(JourneyAlertAction.Post(found)))
        assertEquals(mapOf(journey.key to found.fingerprint), AnnouncedJourneyAlerts.read(context))
    }
}
