package app.stopdash.watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.stopdash.StopdashDebugLog
import app.stopdash.data.DataStoreAppSettings
import app.stopdash.domain.AppSettings
import app.stopdash.domain.FontSizeSettings
import app.stopdash.widget.WIDGET_REFRESH_WORK
import com.google.android.gms.wearable.Node
import com.mikelward.androidlog.DebugLog
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A watch in reach starts the opt-in minute updates again (SPEC D5): they stop while no widget is
 * placed and no watch is in reach, and the watch never fetches on its own, so its coming back is what
 * brings them back. On an in-memory WorkManager, so no tick runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WatchLiveRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val lines = mutableListOf<String>()
    private val sink = DebugLog.Sink { line -> synchronized(lines) { lines += line } }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        StopdashDebugLog.addSink(sink)
    }

    @After
    fun tearDown() {
        StopdashDebugLog.removeSink(sink)
    }

    private fun logged(text: String) = synchronized(lines) { lines.filter { text in it } }

    private fun pendingTicks() =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WIDGET_REFRESH_WORK).get()
            .count { it.state == WorkInfo.State.ENQUEUED }

    // Real time, not runTest's: the setting is read from DataStore within a bound.
    private fun resumed(setting: Boolean) = runBlocking {
        DataStoreAppSettings.from(context).setLiveWidgetRefresh(setting)
        TestListenableWorkerBuilder<WatchLiveRefreshResumeWorker>(context).build().doWork()
    }

    // Both as durable work, queued before the listener returns: a coroutine of the listener's own
    // could be torn down with the service before it finished (Codex on #918).
    @Test
    fun `a watch coming back queues its republish and the resume as work`() {
        val republished = mutableListOf<Context>()
        val resumes = mutableListOf<Context>()
        watchAppeared(context, republish = { republished += it }, resume = { resumes += it })
        assertEquals(1, republished.size)
        assertEquals(1, resumes.size)

        WatchLiveRefreshResumeWorker.enqueue(context)
        assertEquals(
            1,
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(WatchLiveRefreshResumeWorker.UNIQUE_NAME).get().size,
        )
    }

    @Test
    fun `the resume work starts the minute updates again`() {
        assertEquals(ListenableWorker.Result.success(), resumed(setting = true))
        assertEquals(1, pendingTicks())
    }

    @Test
    fun `with the setting off, the resume work starts nothing`() {
        assertEquals(ListenableWorker.Result.success(), resumed(setting = false))
        assertEquals(0, pendingTicks())
    }

    @Test
    fun `a watch asking again keeps the one tick already pending`() = runTest {
        val on = FakeSettings(flowOf(true))
        resumeLiveRefreshForWatch(context, on)
        val first = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WIDGET_REFRESH_WORK).get().single().id
        resumeLiveRefreshForWatch(context, on)
        val pending = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WIDGET_REFRESH_WORK).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(listOf(first), pending.map { it.id })
    }

    // A setting that can't be read is logged, never thrown at the listener or the worker, and said.
    @Test
    fun `a resume that fails is logged, not thrown`() = runTest {
        assertFalse(resumeLiveRefreshForWatch(context, FakeSettings(flow { throw IOException("unreadable") })))
        assertEquals(0, pendingTicks())
        assertEquals(1, logged("watch: live refresh resume failed: IOException").size)
        // A read that times out rather than throws fails the same way.
        assertFalse(resumeLiveRefreshForWatch(context, FakeSettings(flow { awaitCancellation() })))
        assertEquals(1, logged("watch: live refresh resume failed: setting unreadable").size)
    }

    // The reconnect is the one event that restarts the updates for a watch, so a resume that fails is
    // retried rather than reported done (Codex on #918).
    @Test
    fun `the resume work retries a resume that failed`() {
        val failing = FakeSettings(flow { throw IOException("unreadable") })
        assertEquals(ListenableWorker.Result.retry(), runBlocking { WatchLiveRefreshResumeWorker.resume(context, failing) })
        assertEquals(ListenableWorker.Result.success(), runBlocking { WatchLiveRefreshResumeWorker.resume(context, FakeSettings(flowOf(true))) })
    }

    // A watch's refresh request may be the one event that would restart the updates, and its worker
    // answers the watch rather than retrying, so a failed resume becomes retrying work (Codex on #918).
    @Test
    fun `a watch request whose resume fails leaves it to the retrying work`() = runTest {
        val retries = mutableListOf<Context>()
        resumeLiveRefreshForWatchRequest(context, resume = { false }, retryLater = { retries += it })
        assertEquals(1, retries.size)
        resumeLiveRefreshForWatchRequest(context, resume = { true }, retryLater = { retries += it })
        assertEquals(1, retries.size)
        // The real retry is the reconnect's resume work, one pending at most.
        resumeLiveRefreshForWatchRequest(context, resume = { false })
        assertEquals(
            1,
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(WatchLiveRefreshResumeWorker.UNIQUE_NAME).get().size,
        )
    }

    @Test
    fun `a watch only reachable through the cloud is away, not in reach`() {
        // Left at home on Wi-Fi or cellular: reachable, but not on the rider's wrist.
        assertEquals(WatchReach.AWAY, reachOf(listOf(FakeNode(nearby = false))))
        assertEquals(WatchReach.NONE, reachOf(emptyList()))
    }

    @Test
    fun `a nearby watch is in reach, among others that aren't`() {
        assertEquals(WatchReach.NEARBY, reachOf(listOf(FakeNode(nearby = false), FakeNode(nearby = true))))
    }

    private class FakeNode(private val nearby: Boolean) : Node {
        override fun getId() = "node"
        override fun getDisplayName() = "Watch"
        override fun isNearby() = nearby
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
}
