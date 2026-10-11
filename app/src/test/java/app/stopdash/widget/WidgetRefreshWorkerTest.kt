package app.stopdash.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.stopdash.data.DataStoreAppSettings
import app.stopdash.data.WatchRefreshOutcome
import app.stopdash.domain.AppSettings
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.ARRIVALS_REUSE
import java.time.Instant
import app.stopdash.domain.FontSizeSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

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

    @Test
    fun `disabling when nothing is scheduled is a no-op`() = runTest {
        applyWidgetRefreshSetting(context, enabled = false)
        assertEquals(0, enqueuedCount())
    }

    /**
     * With the setting on but no widget installed (the Robolectric default — no host), the worker
     * retires the chain: it returns success without fetching or rescheduling, so it doesn't loop
     * every minute with no surface to update (Codex P1 on #56). The render path
     * (`resumeWidgetRefreshIfEnabled`) restarts it when a widget is added.
     */
    @Test
    fun `the worker retires without rescheduling when no widget is installed`() = runTest {
        DataStoreAppSettings.from(context).setLiveWidgetRefresh(true)
        val worker = TestListenableWorkerBuilder<WidgetRefreshWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, enqueuedCount())
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
        resumeWidgetRefreshIfEnabled(context, FakeSettings(flowOf(false)))
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
        resumeWidgetRefreshIfEnabled(context, FakeSettings(flow { awaitCancellation() }))
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
