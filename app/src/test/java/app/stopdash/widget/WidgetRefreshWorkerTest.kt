package app.stopdash.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.stopdash.data.DataStoreAppSettings
import app.stopdash.data.WatchRefreshOutcome
import app.stopdash.domain.AppSettings
import app.stopdash.watch.WatchReach
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.ARRIVALS_REUSE
import java.time.Instant
import app.stopdash.domain.FontSizeSettings
import app.stopdash.StopdashDebugLog
import app.stopdash.ThreadRecorder
import com.mikelward.androidlog.DebugLog
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scheduling side of the opt-in "live widget" refresh (SPEC D5): turning the setting on
 * enqueues exactly one unique refresh tick, turning it off cancels the pending one, and a second
 * schedule REPLACEs rather than stacking (so the self-rescheduling chain is never more than one
 * deep). The worker's own fetch-and-save cycle is covered by the pure `WidgetRefreshTest`; here we
 * only assert the WorkManager plumbing, on an in-memory WorkManager so no real work runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetRefreshWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val lines = mutableListOf<String>()
    private val sink = DebugLog.Sink { line -> synchronized(lines) { lines += line } }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        StopdashDebugLog.addSink(sink)
        // No watch unless a test puts one in reach: the real question asks Play services.
        LiveRefreshSurfaces.watchReach = { WatchReach.NONE }
    }

    @After
    fun tearDown() {
        StopdashDebugLog.removeSink(sink)
        LiveRefreshSurfaces.reset()
    }

    private fun logged(text: String) = synchronized(lines) { lines.filter { text in it } }

    private fun wm() = WorkManager.getInstance(context)

    private fun scheduledWork(): List<WorkInfo> =
        wm().getWorkInfosForUniqueWork(WIDGET_REFRESH_WORK).get()

    private fun enqueuedCount() = scheduledWork().count { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun `enabling the setting enqueues one refresh tick`() = runTest {
        applyWidgetRefreshSetting(context, enabled = true)
        val work = scheduledWork()
        assertEquals(1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.first().state)
    }

    @Test
    fun `disabling the setting cancels the pending tick`() = runTest {
        applyWidgetRefreshSetting(context, enabled = true)
        assertEquals(1, enqueuedCount())
        applyWidgetRefreshSetting(context, enabled = false)
        assertEquals(0, enqueuedCount())
    }

    /**
     * Re-applying the enabled setting — as the startup `LaunchedEffect` does on every activity
     * recreation (a rotation) — keeps the existing tick rather than REPLACE-ing it, so repeated
     * config changes can't reset the pending delay or abort a running fetch (Codex P2 on #56). The
     * same **work id** across both calls is what proves it wasn't cancelled and re-enqueued.
     */
    @Test
    fun `re-applying the enabled setting keeps the existing tick`() = runTest {
        applyWidgetRefreshSetting(context, enabled = true)
        val firstId = scheduledWork().single { it.state == WorkInfo.State.ENQUEUED }.id
        applyWidgetRefreshSetting(context, enabled = true)
        val enqueued = scheduledWork().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, enqueued.size)
        assertEquals(firstId, enqueued.single().id)
    }

    @Test
    fun `scheduling twice keeps only one pending tick`() = runTest {
        scheduleWidgetRefresh(context)
        scheduleWidgetRefresh(context)
        assertEquals(1, enqueuedCount())
    }

    /**
     * A resume while a tick runs: that tick may already have decided to wait, there being nothing
     * to update when it asked, so the resume goes after it rather than being skipped (Codex on
     * #918). A tick waiting to run already is the chain going on, and is kept.
     */
    @Test
    fun `a resume follows a running tick and keeps a waiting one`() {
        assertNull(liveRefreshResumePolicy(listOf(WorkInfo.State.ENQUEUED)))
        assertNull(liveRefreshResumePolicy(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, liveRefreshResumePolicy(listOf(WorkInfo.State.RUNNING)))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, liveRefreshResumePolicy(listOf(WorkInfo.State.SUCCEEDED)))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, liveRefreshResumePolicy(emptyList()))
        // A check parked minutes out is replaced by a minute tick, unless a tick is running.
        assertEquals(ExistingWorkPolicy.REPLACE, liveRefreshResumePolicy(listOf(WorkInfo.State.ENQUEUED), parkedWaiting = true))
        assertNull(liveRefreshResumePolicy(listOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED), parkedWaiting = true))
    }

    /**
     * A check parked five minutes out (nothing to update, or nothing stored yet) gives way to a minute
     * tick when something resumes the chain, a widget placed or a watch asking, rather than keeping
     * the newcomer waiting up to five minutes for its first update (Codex on #918).
     */
    @Test
    fun `a resume replaces a parked check with a minute tick`() = runTest {
        scheduleWidgetRefresh(context, LIVE_REFRESH_RECHECK_MILLIS, parked = true)
        resumeWidgetRefreshIfEnabled(context, FakeSettings(flowOf(true)))
        val next = scheduledWork().single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(WIDGET_REFRESH_INTERVAL_MILLIS, next.initialDelayMillis)
        assertFalse(LIVE_REFRESH_PARKED_TAG in next.tags)
        assertEquals(1, logged("widget: live refresh: a minute tick replaces the waiting check").size)
        // A minute tick already waiting is kept.
        val kept = next.id
        resumeWidgetRefreshIfEnabled(context, FakeSettings(flowOf(true)))
        assertEquals(listOf(kept), scheduledWork().filter { it.state == WorkInfo.State.ENQUEUED }.map { it.id })
    }

    // The successor waits behind the tick, and the tick's own reschedule leaves exactly one.
    @Test
    fun `a successor queued behind a tick is replaced by that tick's own next one`() = runTest {
        scheduleWidgetRefresh(context)
        scheduleWidgetRefresh(context, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
        assertEquals(setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), scheduledWork().map { it.state }.toSet())

        scheduleWidgetRefresh(context)

        assertEquals(1, enqueuedCount())
        assertTrue(scheduledWork().none { it.state == WorkInfo.State.BLOCKED })
    }

    @Test
    fun `disabling when nothing is scheduled is a no-op`() = runTest {
        applyWidgetRefreshSetting(context, enabled = false)
        assertEquals(0, enqueuedCount())
    }

    /**
     * With the setting on but no widget installed (the Robolectric default — no host) and no watch in
     * reach, the worker neither fetches nor stops: the next tick is a check five minutes out, said
     * once for the run (maintainer, 2026-10-17). Fetching every minute would be for no one (Codex P1 on
     * #56); stopping left a widget placed or a watch come home without updates whenever the one event
     * that would have restarted them was lost (Codex on #918).
     */
    @Test
    fun `with no widget placed and no watch in reach, the worker waits on a five-minute check`() = runTest {
        DataStoreAppSettings.from(context).setLiveWidgetRefresh(true)
        val worker = TestListenableWorkerBuilder<WidgetRefreshWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val next = scheduledWork().single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(LIVE_REFRESH_RECHECK_MILLIS, next.initialDelayMillis)
        assertTrue(LIVE_REFRESH_PARKED_TAG in next.tags)
        assertTrue("no fetch", logged(NOTHING_STORED).isEmpty())
        assertFalse(liveRefreshGoesOn(context, FakeSettings(flowOf(true))))
        assertEquals(1, enqueuedCount())
        assertEquals(1, logged("widget: live refresh waiting: no widget placed and no watch in reach; checking every 5 min").size)
    }

    /**
     * Waiting replaces the chain, so a resume queued behind a running tick (a widget placed, a watch
     * asking, as that tick decided) is left to run a minute out rather than replaced by the check.
     */
    @Test
    fun `a tick that waits leaves a resume queued behind it`() = runTest {
        scheduleWidgetRefresh(context)
        scheduleWidgetRefresh(context, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
        val before = scheduledWork().map { it.id }.toSet()
        parkLiveRefresh(context, NO_LIVE_REFRESH_SURFACE)
        assertEquals(before, scheduledWork().map { it.id }.toSet())
        assertTrue(scheduledWork().none { LIVE_REFRESH_PARKED_TAG in it.tags })
    }

    /**
     * A phone in a pocket and a watch on the wrist, no widget placed: every update reaches the watch
     * through the watch sync, and the watch fetches nothing itself, so the chain goes on for it
     * (maintainer, 2026-10-16). It gets past the check to the stored stops, which a test has none of.
     */
    @Test
    fun `a watch in reach keeps the worker going with no widget placed`() = runTest {
        DataStoreAppSettings.from(context).setLiveWidgetRefresh(true)
        LiveRefreshSurfaces.watchReach = { WatchReach.NEARBY }
        val worker = TestListenableWorkerBuilder<WidgetRefreshWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(logged("no watch in reach").isEmpty())
        assertEquals(1, logged("widget: live refresh waiting: nothing stored to refresh").size)
    }

    /**
     * Nothing stored yet, so nothing to refresh: the chain waits on the five-minute check, said once,
     * for a widget as for a watch alone. A widget's first snapshot pokes its render, which brings the
     * minute back sooner; a watch hears of nothing, so stopping there left it without minute updates
     * until something else restarted them (Codex on #918).
     */
    @Test
    fun `nothing stored yet waits, for a widget as for a watch`() = runTest {
        DataStoreAppSettings.from(context).setLiveWidgetRefresh(true)
        LiveRefreshSurfaces.watchReach = { WatchReach.NEARBY }
        assertEquals(ListenableWorker.Result.success(), nothingStoredYet(context))
        assertEquals(ListenableWorker.Result.success(), nothingStoredYet(context))
        val next = scheduledWork().single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(LIVE_REFRESH_RECHECK_MILLIS, next.initialDelayMillis)
        assertTrue(LIVE_REFRESH_PARKED_TAG in next.tags)
        assertEquals(1, logged("widget: live refresh waiting: nothing stored to refresh; checking every 5 min").size)

        wm().cancelUniqueWork(WIDGET_REFRESH_WORK).result.get()
        LiveRefreshSurfaces.widgetPlaced = { true }
        assertEquals(ListenableWorker.Result.success(), nothingStoredYet(context))
        assertEquals(LIVE_REFRESH_RECHECK_MILLIS, scheduledWork().single { it.state == WorkInfo.State.ENQUEUED }.initialDelayMillis)
    }

    @Test
    fun `the chain fetches for a widget or a watch in reach, waits with neither, and ends when turned off`() = runTest {
        val on = FakeSettings(flowOf(true))
        LiveRefreshSurfaces.widgetPlaced = { false }
        LiveRefreshSurfaces.watchReach = { WatchReach.NEARBY }
        assertTrue(liveRefreshGoesOn(context, on))
        LiveRefreshSurfaces.widgetPlaced = { true }
        LiveRefreshSurfaces.watchReach = { WatchReach.NONE }
        assertTrue(liveRefreshGoesOn(context, on))
        assertEquals(0, enqueuedCount())
        LiveRefreshSurfaces.widgetPlaced = { false }
        assertFalse(liveRefreshGoesOn(context, on))
        assertEquals(LIVE_REFRESH_RECHECK_MILLIS, scheduledWork().single { it.state == WorkInfo.State.ENQUEUED }.initialDelayMillis)
        // Turned off, it ends whatever is there, scheduling nothing and saying nothing: the rider ended it.
        wm().cancelUniqueWork(WIDGET_REFRESH_WORK).result.get()
        LiveRefreshSurfaces.widgetPlaced = { true }
        assertFalse(liveRefreshGoesOn(context, FakeSettings(flowOf(false))))
        assertEquals(0, enqueuedCount())
        assertEquals(1, logged("live refresh waiting").size)
    }

    // A placed widget is the cheap local answer, so a widget user's minute never waits on Play services.
    @Test
    fun `a phone with a widget placed never asks for a watch`() = runTest {
        var asked = 0
        LiveRefreshSurfaces.widgetPlaced = { true }
        LiveRefreshSurfaces.watchReach = { asked++; WatchReach.NONE }
        assertNull(LiveRefreshSurfaces.whyNone(context))
        assertEquals(0, asked)
    }

    /**
     * Removing the last widget leaves the pending tick for the next tick to decide, which asks before
     * it fetches. A cancel there could take the tick a watch's resume had just queued, the watch
     * having come back after the removal asked for it, and nothing would start the chain again
     * (Codex on #918).
     */
    @Test
    fun `the last widget removed leaves a tick a watch's resume queued`() = runTest {
        // No widget placed and, when the removal looks, no watch in reach either.
        LiveRefreshSurfaces.widgetPlaced = { false }
        // The watch comes back and its resume queues a tick, then the last widget's removal lands.
        resumeWidgetRefreshIfEnabled(context, FakeSettings(flowOf(true)))
        assertEquals(1, enqueuedCount())
        StopDashWidget().onDelete(context, object : androidx.glance.GlanceId {})
        assertEquals(1, enqueuedCount())
        // With the watch on the wrist, the tick goes on; with nothing to update, it waits.
        LiveRefreshSurfaces.watchReach = { WatchReach.NEARBY }
        assertTrue(liveRefreshGoesOn(context, FakeSettings(flowOf(true))))
        LiveRefreshSurfaces.watchReach = { WatchReach.NONE }
        assertFalse(liveRefreshGoesOn(context, FakeSettings(flowOf(true))))
        assertEquals(1, logged("widget: live refresh waiting: no widget placed and no watch in reach").size)
    }

    /**
     * A watch left at home online is reachable through the cloud, but not on the wrist (Codex on
     * #918): the chain waits rather than fetch for it, its coming back near the phone may not change
     * the set of reachable watches, which is all the capability listener reports, and the check five
     * minutes out is what sees it.
     */
    @Test
    fun `an away watch neither fetches nor stops, and the next check is minutes out`() = runTest {
        DataStoreAppSettings.from(context).setLiveWidgetRefresh(true)
        LiveRefreshSurfaces.watchReach = { WatchReach.AWAY }
        val worker = TestListenableWorkerBuilder<WidgetRefreshWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val next = scheduledWork().single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(LIVE_REFRESH_RECHECK_MILLIS, next.initialDelayMillis)
        assertTrue(LIVE_REFRESH_PARKED_TAG in next.tags)
        assertTrue("no fetch", logged(NOTHING_STORED).isEmpty())
        // Said once for a run of away answers.
        assertFalse(liveRefreshGoesOn(context, FakeSettings(flowOf(true))))
        assertEquals(1, logged("widget: live refresh waiting: no widget placed and the watch is away; checking every 5 min").size)
    }

    // The wait's end is said once the tick that ends it has refreshed.
    @Test
    fun `an away watch back near the phone gets the minute updates again`() = runTest {
        val on = FakeSettings(flowOf(true))
        LiveRefreshSurfaces.watchReach = { WatchReach.AWAY }
        assertFalse(liveRefreshGoesOn(context, on))
        LiveRefreshSurfaces.watchReach = { WatchReach.NEARBY }
        assertTrue(liveRefreshGoesOn(context, on))
        LiveRefreshSurfaces.updating()
        LiveRefreshSurfaces.updating()
        assertEquals(1, logged("widget: live refresh: every minute again").size)
    }

    /**
     * A watch question that fails counts as a watch there, so a transient Play services error can't
     * stop the watch's updates and leave it falling behind: the rider chose the minute's cost already.
     * It's said once for a run of failures, and again when the question is answered.
     */
    @Test
    fun `a watch check that fails counts as a watch there, said once until it answers`() = runTest {
        val on = FakeSettings(flowOf(true))
        LiveRefreshSurfaces.watchReach = { throw IllegalStateException("no answer") }
        assertTrue(liveRefreshGoesOn(context, on))
        assertTrue(liveRefreshGoesOn(context, on))
        assertEquals(1, logged("widget: live refresh: watch check failed: IllegalStateException; updating anyway").size)
        // Answered again: said, and a watch gone now makes it wait.
        LiveRefreshSurfaces.watchReach = { WatchReach.NONE }
        assertFalse(liveRefreshGoesOn(context, on))
        assertEquals(1, logged("widget: live refresh: watch check answers again").size)
        assertEquals(1, logged("widget: live refresh waiting: no widget placed and no watch in reach").size)
    }

    // A Play services call that never returns can't hold a tick open.
    @Test
    fun `a watch check that doesn't answer in time counts as a watch there`() {
        LiveRefreshSurfaces.watchReach = { awaitCancellation() }
        runBlocking { assertNull(LiveRefreshSurfaces.whyNone(context, boundMillis = 50)) }
        assertEquals(1, logged("widget: live refresh: watch check timed out; updating anyway").size)
    }

    // Both questions are calls into the system: asked on the io dispatcher, never the caller's thread.
    @Test
    fun `the widget and the watch are asked on the io dispatcher, not the caller's thread`() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor { Thread(it, "test-io") }.asCoroutineDispatcher()
        try {
            val threads = ThreadRecorder()
            LiveRefreshSurfaces.widgetPlaced = { threads.note(); false }
            LiveRefreshSurfaces.watchReach = { threads.note(); WatchReach.NEARBY }
            assertNull(runBlocking(caller) { LiveRefreshSurfaces.whyNone(context, io) })
            assertEquals(listOf("test-io", "test-io"), threads.threads())
        } finally {
            caller.close()
            io.close()
        }
    }

    /**
     * A tick Android held back says how late it ran and what Android holds the app to: a widget 4 hours
     * behind with the setting on left nothing in the log to say why (bug report, 2026-10-16).
     */
    @Test
    fun `a tick that runs late says how late, and what Android holds the app to`() = runTest {
        DataStoreAppSettings.from(context).setLiveWidgetRefresh(true)
        val ago = 11 * 60_000L
        val worker = TestListenableWorkerBuilder<WidgetRefreshWorker>(context)
            .setInputData(liveRefreshInput(android.os.SystemClock.elapsedRealtime() - ago, System.currentTimeMillis() - ago))
            .build()
        worker.doWork()
        val late = logged("widget: live refresh ran ")
        assertEquals(1, late.size)
        val seconds = Regex("ran (\\d+) s after due \\(standby bucket ").find(late.single())?.groupValues?.get(1)?.toLong()
        assertTrue("about 10 min late: ${late.single()}", seconds != null && seconds in 600L..660L)
    }

    @Test
    fun `a tick on time, or nudged by Doze, says nothing about when it ran`() = runTest {
        DataStoreAppSettings.from(context).setLiveWidgetRefresh(true)
        val nudged = WIDGET_REFRESH_INTERVAL_MILLIS + LIVE_REFRESH_LATE_LOG_MILLIS / 2
        for (ago in listOf(0L, nudged)) {
            TestListenableWorkerBuilder<WidgetRefreshWorker>(context)
                .setInputData(liveRefreshInput(android.os.SystemClock.elapsedRealtime() - ago, System.currentTimeMillis() - ago))
                .build()
                .doWork()
        }
        assertTrue(logged("after due").isEmpty())
    }

    @Test
    fun `starting a chain that wasn't scheduled says so, and an alive one says nothing`() = runTest {
        applyWidgetRefreshSetting(context, enabled = true)
        applyWidgetRefreshSetting(context, enabled = true)
        assertEquals(1, logged("widget: live refresh wasn't scheduled; starting it").size)
    }

    private class FakeSettings(private val flow: Flow<Boolean>) : AppSettings {
        override fun liveWidgetRefresh(): Flow<Boolean> = flow
        override suspend fun setLiveWidgetRefresh(enabled: Boolean) {}
        override fun fontSize(): Flow<FontSizeSettings> = flowOf(FontSizeSettings())
        override suspend fun setFontScale(scale: Float) {}
        override suspend fun setPinchEnabled(enabled: Boolean) {}
        override fun skipBugReportConsent(): Flow<Boolean> = flowOf(false)
        override suspend fun setSkipBugReportConsent(enabled: Boolean) {}
        override fun userApiKey(): Flow<String?> = flowOf(null)
        override suspend fun setUserApiKey(key: String?) {}
    }

    @Test
    fun `resume schedules a tick when the setting reads on`() = runTest {
        resumeWidgetRefreshIfEnabled(context, FakeSettings(flowOf(true)))
        assertEquals(1, enqueuedCount())
    }

    @Test
    fun `resume does not schedule when the setting reads off`() = runTest {
        assertTrue(resumeWidgetRefreshIfEnabled(context, FakeSettings(flowOf(false))))
        assertEquals(0, enqueuedCount())
    }

    /**
     * A persistent settings read failure makes `liveWidgetRefresh()` retry forever (never
     * emitting). On the widget render path the bounded read must give up and skip resume rather
     * than hang `provideContent` — and a timed-out read is never treated as "off", so it neither
     * schedules nor crashes; it simply does nothing this render (Codex P2 on #56).
     */
    @Test
    fun `resume gives up without scheduling when the settings read hangs`() = runTest {
        // False: the setting couldn't be read, for a caller that can try again.
        assertFalse(resumeWidgetRefreshIfEnabled(context, FakeSettings(flow { awaitCancellation() })))
        assertEquals(0, enqueuedCount())
    }

    @Test
    fun `a failed refresh's outcome still answers once a line check is stored, but not new arrivals`() {
        val stop = StopArrivals("A", "Stop A", emptyList(), Instant.EPOCH)
        val before = DeparturesSnapshot(listOf(stop), Instant.EPOCH)
        // Its own status-only write, or the app's, changes only the line checks.
        val checked = before.copy(
            lineStatuses = mapOf("victoria" to LineStatusCheck(LineStatus("victoria", 20, "Suspended"), Instant.EPOCH)),
        )
        assertTrue(StoredSnapshotRefresh.sameArrivals(before, checked))
        val refetched = before.copy(stops = listOf(stop.copy(fetchedAt = Instant.EPOCH.plusSeconds(60))))
        assertFalse(StoredSnapshotRefresh.sameArrivals(before, refetched))
    }

    @Test
    fun `a failed refresh answers the next only with the same TfL key`() {
        val prior = DeparturesSnapshot(listOf(StopArrivals("A", "Stop A", emptyList(), Instant.EPOCH)), Instant.EPOCH)
        val at = Instant.parse("2026-09-18T08:00:00Z")
        val soon = at.plusSeconds(10)
        val rejected = StoredSnapshotRefresh.Failed(at, WatchRefreshOutcome.KEY_REJECTED, prior, "EXAMPLE")
        assertTrue(rejected.answers(prior, "EXAMPLE", soon))
        // The refused key cleared, or another pasted: TfL is asked again, not answered from the old failure (SPEC D7).
        assertFalse(rejected.answers(prior, null, soon))
        assertFalse(rejected.answers(prior, "OTHER", soon))
        // A key pasted over a keyless rate limit lifts the budget, so it's asked again too.
        val limited = StoredSnapshotRefresh.Failed(at, WatchRefreshOutcome.RATE_LIMITED, prior, null)
        assertTrue(limited.answers(prior, null, soon))
        assertFalse(limited.answers(prior, "EXAMPLE", soon))
        // Past the reuse window, a new request fetches whatever the key.
        assertFalse(rejected.answers(prior, "EXAMPLE", at.plus(ARRIVALS_REUSE)))
    }

    @Test
    fun `a timed refresh's failure answers the next timed one, never a tap or a watch's request`() {
        val prior = DeparturesSnapshot(listOf(StopArrivals("A", "Stop A", emptyList(), Instant.EPOCH)), Instant.EPOCH)
        val at = Instant.parse("2026-09-18T08:00:00Z")
        val soon = at.plusSeconds(10)
        // It may have reused a journey-only stop for the longer far window, which a tap doesn't.
        val timed = StoredSnapshotRefresh.Failed(at, WatchRefreshOutcome.RATE_LIMITED, prior, null, automatic = true)
        assertTrue(timed.answers(prior, null, soon, automatic = true))
        assertFalse(timed.answers(prior, null, soon, automatic = false))
        // A tap's failure answers either, since it reused no stop for longer than the near window.
        val tapped = StoredSnapshotRefresh.Failed(at, WatchRefreshOutcome.RATE_LIMITED, prior, null)
        assertTrue(tapped.answers(prior, null, soon, automatic = true))
        assertTrue(tapped.answers(prior, null, soon, automatic = false))
    }
}
