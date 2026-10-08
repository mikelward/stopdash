package app.stopdash

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.FavoriteJourneysStore
import app.stopdash.domain.JourneyAlertSchedule
import app.stopdash.domain.JourneyAlerts
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusBatch
import app.stopdash.domain.TimeWindow
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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

/** Arming the journey alert check (SPEC *Journeys → Alerts*), outside any window so nothing runs. Public stations only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JourneyAlertChecksTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val journey = FavoriteJourney(JourneyEnd("940GZZLUEUS", "Euston"), JourneyEnd("940GZZLUWLO", "Waterloo"), "northern", "Northern", "tube")

    // A Monday at 06:00, before either default window.
    private val early = LocalDateTime.of(2026, 10, 5, 6, 0).atZone(ZoneId.systemDefault()).toInstant()

    private val pool = Executors.newSingleThreadExecutor { Thread(it, "test-io") }

    // Counts the hops onto it, so the test sees the sync left its caller's thread.
    private class Counting(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        @Volatile var dispatched = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            inner.dispatch(context, block)
        }
    }

    private val io = Counting(pool.asCoroutineDispatcher())

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        pool.shutdown()
    }

    private fun states(): List<WorkInfo.State> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().map { it.state }

    @Test
    fun a_watched_direction_arms_the_check_off_the_callers_thread() {
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states())
        assertTrue(io.dispatched > 0)
    }

    @Test
    fun nothing_watched_cancels_the_check() {
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), emptyMap(), early, io = io) }
        assertTrue(states().none { it == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun notifications_off_arm_nothing() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
        assertTrue(states().none { it == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun unreadable_stores_change_nothing() {
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
        runBlocking { JourneyAlertChecks.sync(context, null, null, early, io = io) }
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states())
    }

    @Test
    fun notifications_turned_off_forget_what_was_announced_so_it_shows_again_later() {
        AnnouncedJourneyAlerts.write(context, mapOf(journey.key to "said"))
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Mid-window, so the direction is watched now.
        val mid = LocalDateTime.of(2026, 10, 5, 9, 0).atZone(ZoneId.systemDefault()).toInstant()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), mid, io = io) }
        assertTrue(AnnouncedJourneyAlerts.read(context).isEmpty())
    }

    @Test
    fun the_notification_gate_is_asked_off_the_callers_thread() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(JourneyAlertNotification.Gate.NEEDS_PERMISSION, runBlocking { JourneyAlertNotification.gate(context, io) })
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(JourneyAlertNotification.Gate.OPEN, runBlocking { JourneyAlertNotification.gate(context, io) })
        assertTrue(io.dispatched >= 2)
    }

    @Test
    fun a_check_that_only_closes_a_window_needs_no_network() {
        val closing = LocalDateTime.of(2026, 10, 5, 9, 55).atZone(ZoneId.systemDefault()).toInstant()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), closing, io = io) }
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single()
        assertEquals(NetworkType.NOT_REQUIRED, work.constraints.requiredNetworkType)
    }

    @Test
    fun a_check_inside_a_window_waits_for_a_network() {
        val inside = LocalDateTime.of(2026, 10, 5, 8, 30).atZone(ZoneId.systemDefault()).toInstant()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), inside, io = io) }
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single()
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    @Test
    fun turning_notifications_back_on_in_settings_rearms_the_check_on_return() {
        val store = object : FavoriteJourneysStore {
            override fun journeys() = flowOf<List<FavoriteJourney>?>(listOf(journey))
            override fun alertSchedules() = flowOf<Map<String, JourneyAlertSchedule>?>(JourneyAlerts.defaultsFor(journey))
            override suspend fun toggle(journey: FavoriteJourney) {}
            override suspend fun remove(journey: FavoriteJourney) {}
            override suspend fun add(journey: FavoriteJourney) {}
        }
        // Armed, then notifications turned off: the return finds them off and cancels.
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { JourneyAlertChecks.onForeground(context, store, io) { early } }
        assertTrue(states().none { it == WorkInfo.State.ENQUEUED })
        // Back on in Android's settings: the next return arms it again.
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { JourneyAlertChecks.onForeground(context, store, io) { early } }
        assertTrue(states().any { it == WorkInfo.State.ENQUEUED })
    }

    private val stored = object : FavoriteJourneysStore {
        override fun journeys() = flowOf<List<FavoriteJourney>?>(listOf(journey))
        override fun alertSchedules() = flowOf<Map<String, JourneyAlertSchedule>?>(JourneyAlerts.defaultsFor(journey))
        override suspend fun toggle(journey: FavoriteJourney) {}
        override suspend fun remove(journey: FavoriteJourney) {}
        override suspend fun add(journey: FavoriteJourney) {}
    }

    @Test
    fun a_return_to_the_app_arms_a_check_nothing_scheduled() {
        // Notifications were on all along, but no check is pending (an earlier failure, say).
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), emptyMap(), early, io = io) }
        assertTrue(states().none { it == WorkInfo.State.ENQUEUED })
        runBlocking { JourneyAlertChecks.onForeground(context, stored, io) { early } }
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states().filter { it == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun a_return_to_the_app_leaves_a_pending_check_alone() {
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
        val before = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single().id
        runBlocking { JourneyAlertChecks.onForeground(context, stored, io) { early } }
        val after = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }.id
        assertEquals(before, after)
    }

    @Test
    fun a_resync_reads_the_stores_off_the_callers_thread() {
        runBlocking { JourneyAlertChecks.resync(context, stored, io, early) }
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states())
        assertTrue(io.dispatched > 0)
    }

    @Test
    fun a_time_zone_change_reschedules_from_the_stores() {
        // Armed, then the direction's alerts turned off: the change re-reads the saved schedules.
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
        val nothingWatched = object : FavoriteJourneysStore by stored {
            override fun alertSchedules() = flowOf<Map<String, JourneyAlertSchedule>?>(emptyMap())
        }
        runBlocking { JourneyAlertClockReceiver.resync(context, nothingWatched, io) }
        assertTrue(states().none { it == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun a_time_zone_change_inside_a_window_checks_at_once() {
        // The re-timed close usually takes the shown alert down; a disruption still under way comes
        // straight back rather than a quarter hour later (Codex on #700).
        val mid = LocalDateTime.of(2026, 10, 5, 9, 0).atZone(ZoneId.systemDefault()).toInstant()
        runBlocking { JourneyAlertClockReceiver.resync(context, stored, io, now = { mid }) }
        val check = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(0L, check.initialDelayMillis)
    }

    @Test
    fun the_app_listens_for_the_clock_and_time_zone_changing() {
        for (action in listOf(android.content.Intent.ACTION_TIME_CHANGED, android.content.Intent.ACTION_TIMEZONE_CHANGED)) {
            val receivers = context.packageManager.queryBroadcastReceivers(android.content.Intent(action).setPackage(context.packageName), 0)
            assertTrue(action, JourneyAlertClockReceiver::class.java.name in receivers.map { it.activityInfo.name })
        }
    }

    @Test
    fun a_clock_change_that_cannot_resync_is_put_right_on_the_next_return() {
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
        val before = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single().id
        val broken = object : FavoriteJourneysStore by stored {
            override fun journeys() = kotlinx.coroutines.flow.flow<List<FavoriteJourney>?> { throw IllegalStateException("broken") }
        }
        runBlocking { JourneyAlertClockReceiver.resync(context, broken, io, retryAfterMillis = 0) }
        assertTrue(JourneyAlertState.stale(context))
        // The next return to the app re-times the check although one is pending, and clears the mark.
        runBlocking { JourneyAlertChecks.onForeground(context, stored, io) { early } }
        val after = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }.id
        assertTrue(before != after)
        assertTrue(!JourneyAlertState.stale(context))
    }

    @Test
    fun a_clock_change_whose_first_read_comes_back_unreadable_tries_again() {
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
        val before = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single().id
        var reads = 0
        // The store reports an unreadable file as null, not a throw; the first read fails, the retry works.
        val flaky = object : FavoriteJourneysStore by stored {
            override fun journeys() = kotlinx.coroutines.flow.flow<List<FavoriteJourney>?> { emit(if (reads++ == 0) null else listOf(journey)) }
        }
        runBlocking { JourneyAlertClockReceiver.resync(context, flaky, io, retryAfterMillis = 0, now = { early }) }
        assertEquals(2, reads)
        val after = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }.id
        assertTrue(before != after)
        assertFalse(JourneyAlertState.stale(context))
    }

    @Test
    fun a_prompt_denied_for_good_sends_allow_to_settings_after_a_restart() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(JourneyAlertNotification.Gate.NEEDS_PERMISSION, runBlocking { JourneyAlertNotification.gate(context, io) })
        JourneyAlertState.setPromptGone(context, true)
        // Read back from disk, as a fresh process would.
        assertEquals(JourneyAlertNotification.Gate.APP_OFF, runBlocking { JourneyAlertNotification.gate(context, io) })
        JourneyAlertState.setPromptGone(context, false)
    }

    @Test
    fun a_swiped_away_prompt_is_offered_again_and_a_final_refusal_is_not() {
        try {
            // Swiped away on first asking: no rationale before or after. Allow asks again.
            assertTrue(!JourneyAlertState.recordPrompt(context, granted = false, rationaleBefore = false, rationaleAfter = false))
            // Refused once: Android now offers a rationale, and the prompt is still there.
            assertTrue(!JourneyAlertState.recordPrompt(context, granted = false, rationaleBefore = false, rationaleAfter = true))
            // Refused again: the rationale is gone with it, and so is the prompt.
            assertTrue(JourneyAlertState.recordPrompt(context, granted = false, rationaleBefore = true, rationaleAfter = false))
            assertTrue(JourneyAlertState.promptGone(context))
            // Asked again (another screen's prompt): Android answers at once, no rationale either side, and
            // the final refusal stands (Codex on #700).
            assertTrue(JourneyAlertState.recordPrompt(context, granted = false, rationaleBefore = false, rationaleAfter = false))
            // Granted later in Settings and asked again: not gone.
            assertTrue(!JourneyAlertState.recordPrompt(context, granted = true, rationaleBefore = false, rationaleAfter = false))
            // Swiped away again and again: still offered every time (Codex on #700).
            repeat(3) { assertTrue(!JourneyAlertState.recordPrompt(context, granted = false, rationaleBefore = false, rationaleAfter = false)) }
            assertTrue(!JourneyAlertState.promptGone(context))
            // Refused for good before this install recorded it (an upgrade): Android answers at once with no
            // prompt shown, no rationale either side. Faster than a swipe, so it's found final (Codex on #700).
            assertTrue(JourneyAlertState.recordPrompt(context, granted = false, rationaleBefore = false, rationaleAfter = false, answeredAtOnce = true))
            assertTrue(JourneyAlertState.promptGone(context))
        } finally {
            JourneyAlertState.setPromptGone(context, false)
            JourneyAlertState.recordPrompt(context, granted = true, rationaleBefore = false, rationaleAfter = false)
        }
    }

    @Test
    fun a_check_timed_in_another_time_zone_is_re_timed_on_the_next_return() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London"))
            runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
            val before = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single().id
            // The device moves zone and nothing was marked stale (the clock-change write failed, say).
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Singapore"))
            assertTrue(!JourneyAlertState.stale(context))
            runBlocking { JourneyAlertChecks.onForeground(context, stored, io) { early } }
            val after = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }.id
            assertTrue(before != after)
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun a_check_timed_by_a_clock_since_set_is_re_timed_on_the_next_return() {
        val previous = JourneyAlertChecks.clockMinute
        try {
            JourneyAlertChecks.clockMinute = { 1_000L }
            runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), early, io = io) }
            val before = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single().id
            // A minute's correction leaves it be.
            JourneyAlertChecks.clockMinute = { 1_001L }
            runBlocking { JourneyAlertChecks.onForeground(context, stored, io) { early } }
            assertEquals(before, WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }.id)
            // The clock set an hour on, with nothing marked stale: the return re-times it.
            JourneyAlertChecks.clockMinute = { 1_060L }
            assertTrue(!JourneyAlertState.stale(context))
            runBlocking { JourneyAlertChecks.onForeground(context, stored, io) { early } }
            val after = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }.id
            assertTrue(before != after)
        } finally {
            JourneyAlertChecks.clockMinute = previous
        }
    }

    // Whether a check's decision on [journey], made from [watch], would still be carried out.
    private fun stillApplies(before: Long, watch: JourneyAlertChecks.Watch?, journeys: List<FavoriteJourney>, schedules: Map<String, JourneyAlertSchedule>, now: java.time.Instant) =
        runBlocking { JourneyAlertChecks.applying(before, context, journeys, schedules, { now }, io) { unchanged -> unchanged(journey.key, watch) } }

    @Test
    fun a_decision_on_a_journey_changed_since_is_skipped() {
        val mid = LocalDateTime.of(2026, 10, 5, 9, 0).atZone(ZoneId.systemDefault())
        val outKey = JourneyAlerts.directionKey(journey, journey.from.stopId)
        val backKey = JourneyAlerts.directionKey(journey, journey.to.stopId)
        val both = JourneyAlerts.defaultsFor(journey)
        val morning = both.getValue(outKey)
        // What a check at 9:00 on the defaults decided from: the way out, until 10:00.
        val read = JourneyAlertChecks.watches(listOf(journey), both, mid).getValue(journey.key)
        assertEquals(setOf(journey.from.stopId), read.directions)
        var before = JourneyAlertChecks.syncs()
        // Nothing synced since: carried out.
        assertTrue(stillApplies(before, read, listOf(journey), both, mid.toInstant()))
        // A sync still watching it the same way changes nothing.
        before = JourneyAlertChecks.syncs()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), both, mid.toInstant(), io = io) }
        assertTrue(stillApplies(before, read, listOf(journey), both, mid.toInstant()))
        // Its window shortened: the alert's timeout no longer holds.
        before = JourneyAlertChecks.syncs()
        val shorter = morning.copy(windows = listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(9, 30))))
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), both + (outKey to shorter), mid.toInstant(), io = io) }
        assertTrue(!stillApplies(before, read, listOf(journey), both, mid.toInstant()))
        // Watched for the other direction instead: an alert for the first no longer speaks for it.
        before = JourneyAlertChecks.syncs()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), mapOf(backKey to morning), mid.toInstant(), io = io) }
        assertTrue(!stillApplies(before, read, listOf(journey), both, mid.toInstant()))
        // Turned off: a post is skipped...
        before = JourneyAlertChecks.syncs()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), emptyMap(), mid.toInstant(), io = io) }
        assertTrue(!stillApplies(before, read, listOf(journey), both, mid.toInstant()))
        // ...and a clear decided while it was off is skipped once it's turned back on (Codex on #700).
        before = JourneyAlertChecks.syncs()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), both, mid.toInstant(), io = io) }
        assertTrue(!stillApplies(before, null, listOf(journey), emptyMap(), mid.toInstant()))
    }

    @Test
    fun a_check_inside_a_window_is_backed_by_one_at_its_close_needing_no_network() {
        val mid = LocalDateTime.of(2026, 10, 5, 9, 0).atZone(ZoneId.systemDefault()).toInstant()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), mid, io = io) }
        val close = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_CLOSE_WORK).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, close.state)
        assertEquals(NetworkType.NOT_REQUIRED, close.constraints.requiredNetworkType)
        // Nothing watched any more: nothing to close.
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), emptyMap(), mid, io = io) }
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_CLOSE_WORK).get().none { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun the_close_check_is_timed_for_the_first_window_to_close_even_with_another_still_open() {
        // One direction 08:00-10:00, the other 08:00-11:00: the check due at 10:00 needs a network, so
        // the one that needs none is at 10:00 too, not 11:00 (Codex on #700).
        val outKey = JourneyAlerts.directionKey(journey, journey.from.stopId)
        val backKey = JourneyAlerts.directionKey(journey, journey.to.stopId)
        val schedules = mapOf(
            outKey to JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0)))),
            backKey to JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(11, 0)))),
        )
        val nineFifty = LocalDateTime.of(2026, 10, 5, 9, 50).atZone(ZoneId.systemDefault()).toInstant()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), schedules, nineFifty, io = io) }
        val close = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_CLOSE_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(java.time.Duration.ofMinutes(10).toMillis(), close.initialDelayMillis)
    }

    @Test
    fun a_close_check_leaves_the_main_check_be_and_arms_only_the_next_close() {
        // A close check asks TfL nothing, and doesn't re-time the main check, which may be running or
        // waiting for a network as it is (Codex on #700).
        val outKey = JourneyAlerts.directionKey(journey, journey.from.stopId)
        val backKey = JourneyAlerts.directionKey(journey, journey.to.stopId)
        val schedules = mapOf(
            outKey to JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0)))),
            backKey to JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(11, 0)))),
        )
        val zone = ZoneId.systemDefault()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), schedules, LocalDateTime.of(2026, 10, 5, 9, 50).atZone(zone).toInstant(), io = io) }
        fun pending(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().single { it.state == WorkInfo.State.ENQUEUED }
        val main = pending(JOURNEY_ALERTS_WORK).id
        val ten = LocalDateTime.of(2026, 10, 5, 10, 0).atZone(zone).toInstant()
        val since = JourneyAlertChecks.syncs()
        runBlocking { JourneyAlertChecks.applying(since, context, listOf(journey), schedules, { ten }, io, closeOnly = true) { } }
        assertEquals(main, pending(JOURNEY_ALERTS_WORK).id)
        // And a main check running alongside still re-arms itself after (Codex on #700).
        assertEquals(since, JourneyAlertChecks.syncs())
        // The next close: the way back's, at 11:00.
        assertEquals(java.time.Duration.ofHours(1).toMillis(), pending(JOURNEY_ALERTS_CLOSE_WORK).initialDelayMillis)
    }

    @Test
    fun a_close_check_that_reads_an_unsynced_edit_syncs_in_full() {
        // An edit not yet synced, read first by a close check: it's a change, so a running check doesn't
        // carry on from the old settings, and the main check is re-timed from the new (Codex on #700).
        val zone = ZoneId.systemDefault()
        val mid = LocalDateTime.of(2026, 10, 5, 9, 0).atZone(zone).toInstant()
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), JourneyAlerts.defaultsFor(journey), mid, io = io) }
        val main = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }.id
        val since = JourneyAlertChecks.syncs()
        val edited = JourneyAlerts.defaultsFor(journey).mapValues { (_, schedule) -> schedule.copy(windows = listOf(TimeWindow(LocalTime.of(8, 30), LocalTime.of(9, 30)))) }
        runBlocking { JourneyAlertChecks.applying(since, context, listOf(journey), edited, { mid }, io, closeOnly = true) { } }
        assertTrue(JourneyAlertChecks.syncs() != since)
        val rearmed = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }.id
        assertTrue(rearmed != main)
    }

    @Test
    fun a_check_finishing_after_a_settings_change_leaves_the_new_schedule() {
        val before = JourneyAlertChecks.syncs()
        // The user turns the direction off while a check is under way: the check's own reschedule,
        // from the settings it read earlier, must not bring the alerts back.
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), emptyMap(), early, io = io) }
        assertTrue(WorkInfo.State.ENQUEUED !in states())
        runBlocking { JourneyAlertChecks.applying(before, context, listOf(journey), JourneyAlerts.defaultsFor(journey), { early }, io) { } }
        assertTrue(WorkInfo.State.ENQUEUED !in states())
        // With nothing synced since, it reschedules.
        val now = JourneyAlertChecks.syncs()
        runBlocking { JourneyAlertChecks.applying(now, context, listOf(journey), JourneyAlerts.defaultsFor(journey), { early }, io) { } }
        assertTrue(WorkInfo.State.ENQUEUED in states())
    }

    @Test
    fun an_edit_saved_but_not_yet_synced_still_counts_against_a_check() {
        val mid = LocalDateTime.of(2026, 10, 5, 9, 0).atZone(ZoneId.systemDefault())
        val outKey = JourneyAlerts.directionKey(journey, journey.from.stopId)
        val both = JourneyAlerts.defaultsFor(journey)
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), both, mid.toInstant(), io = io) }
        val read = JourneyAlertChecks.watches(listOf(journey), both, mid).getValue(journey.key)
        val before = JourneyAlertChecks.syncs()
        // The window shortened in the store after the check read it, with no sync from the watcher yet.
        val shorter = both + (outKey to both.getValue(outKey).copy(windows = listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(9, 30)))))
        val applied = runBlocking {
            JourneyAlertChecks.applying(before, context, listOf(journey), both, { mid.toInstant() }, io, latest = { listOf(journey) to shorter }) { unchanged -> unchanged(journey.key, read) }
        }
        assertFalse(applied)
        assertTrue(JourneyAlertChecks.syncs() != before)
        // Re-armed from the newer settings as an edit is, at once while a window is open (Codex on #700).
        assertEquals(0L, WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }.initialDelayMillis)
        // Stored as read: carried out.
        val now = JourneyAlertChecks.syncs()
        val same = runBlocking {
            JourneyAlertChecks.applying(now, context, listOf(journey), shorter, { mid.toInstant() }, io, latest = { listOf(journey) to shorter }) { unchanged ->
                unchanged(journey.key, JourneyAlertChecks.watches(listOf(journey), shorter, mid).getValue(journey.key))
            }
        }
        assertTrue(same)
    }

    @Test
    fun a_check_that_missed_a_direction_opening_rearms_at_once() {
        val mid = LocalDateTime.of(2026, 10, 5, 9, 0).atZone(ZoneId.systemDefault()).toInstant()
        val schedules = JourneyAlerts.defaultsFor(journey)
        fun check() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(JOURNEY_ALERTS_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }
        // Answered for every direction it found open: the next check a quarter hour on.
        runBlocking { JourneyAlertChecks.applying(JourneyAlertChecks.syncs(), context, listOf(journey), schedules, { mid }, io) { } }
        assertTrue(check().initialDelayMillis > 0)
        // A direction opened while the request was out: checked at once.
        runBlocking { JourneyAlertChecks.applying(JourneyAlertChecks.syncs(), context, listOf(journey), schedules, { mid }, io, checkNow = { true }) { } }
        assertEquals(0L, check().initialDelayMillis)
    }

    @Test
    fun a_journey_saved_again_on_another_line_counts_as_changed() {
        val mid = LocalDateTime.of(2026, 10, 5, 9, 0).atZone(ZoneId.systemDefault())
        val both = JourneyAlerts.defaultsFor(journey)
        runBlocking { JourneyAlertChecks.sync(context, listOf(journey), both, mid.toInstant(), io = io) }
        val read = JourneyAlertChecks.watches(listOf(journey), both, mid).getValue(journey.key)
        val before = JourneyAlertChecks.syncs()
        // Removed and saved again between the same stations on another line, while the check was out.
        val other = journey.copy(lineId = "${journey.lineId}-other")
        runBlocking { JourneyAlertChecks.sync(context, listOf(other), JourneyAlerts.defaultsFor(other), mid.toInstant(), io = io) }
        assertTrue(!stillApplies(before, read, listOf(journey), both, mid.toInstant()))
    }

    @Test
    fun a_direction_opening_while_a_check_waits_calls_for_another_at_once() {
        val zone = ZoneId.systemDefault()
        val both = JourneyAlerts.defaultsFor(journey)
        // The check asked just before the way back's window opened: nothing was watched then.
        val askedAt = LocalDateTime.of(2026, 10, 5, 15, 59).atZone(zone)
        val asked = JourneyAlerts.directionsOf(listOf(journey), both, askedAt)
        assertFalse(JourneyAlertChecks.unaskedDirections(asked, listOf(journey), both, askedAt))
        // By the time it re-arms, the way back is open and nothing has asked about it.
        val reArmAt = LocalDateTime.of(2026, 10, 5, 16, 0).atZone(zone)
        assertTrue(JourneyAlertChecks.unaskedDirections(asked, listOf(journey), both, reArmAt))
        // Asked about what's open: nothing new.
        val morning = LocalDateTime.of(2026, 10, 5, 9, 0).atZone(zone)
        assertFalse(JourneyAlertChecks.unaskedDirections(JourneyAlerts.directionsOf(listOf(journey), both, morning), listOf(journey), both, morning))
    }

    @Test
    fun a_failed_group_of_lines_keeps_what_the_others_answered() {
        val lines = (1..40).map { "line-number-%02d".format(it) }.toSet()
        val groups = LineStatusBatch.chunks(lines)
        assertTrue(groups.size >= 2)
        var sent = 0
        val statuses = runBlocking {
            JourneyAlertChecks.answered(lines) { chunk ->
                if (sent++ > 0) throw java.io.IOException("offline")
                chunk.map { LineStatus(it, 10, "Good Service") }
            }
        }
        assertEquals(groups.first().toSet(), statuses!!.map { it.lineId }.toSet())
        // Nothing answered at all: no answer.
        assertNull(runBlocking { JourneyAlertChecks.answered(lines) { throw java.io.IOException("offline") } })
    }
}
