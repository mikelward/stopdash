package app.stopdash.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.stopdash.ThreadRecorder
import app.stopdash.data.WatchRefreshOutcome
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap on the widget's header refreshes its stored stops in place (SPEC D5): what the note says
 * about it, the enqueue (off the caller's thread, one at a time), and the worker's end of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetTapRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stamp: Instant = Instant.parse("2026-09-18T08:00:00Z")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        WidgetTapRefresh.reset()
    }

    @After
    fun tearDown() = WidgetTapRefresh.reset()

    @Test
    fun `a tap says refreshing until it ends, and never past its bound`() {
        assertNull(WidgetTapRefresh.note(1_000L, stamp))
        WidgetTapRefresh.started(1_000L)
        assertEquals(WidgetTapNote.REFRESHING, WidgetTapRefresh.note(1_000L, stamp))
        assertEquals(WidgetTapNote.REFRESHING, WidgetTapRefresh.note(1_000L + TAP_REFRESH_NOTE_MILLIS - 1, stamp))
        // A refresh whose end never came (the process died) stops being claimed.
        assertNull(WidgetTapRefresh.note(1_000L + TAP_REFRESH_NOTE_MILLIS, stamp))
        WidgetTapRefresh.started(5_000L)
        WidgetTapRefresh.finished(WatchRefreshOutcome.REFRESHED, stamp, WidgetTapRefresh.begin())
        assertNull(WidgetTapRefresh.note(5_000L, stamp))
    }

    @Test
    fun `the render showing refreshing knows when to redraw it away`() {
        assertNull(WidgetTapRefresh.noteRemaining(1_000L))
        WidgetTapRefresh.started(1_000L)
        assertEquals(TAP_REFRESH_NOTE_MILLIS.milliseconds, WidgetTapRefresh.noteRemaining(1_000L))
        assertEquals((TAP_REFRESH_NOTE_MILLIS - 10_000L).milliseconds, WidgetTapRefresh.noteRemaining(11_000L))
        assertNull(WidgetTapRefresh.noteRemaining(1_000L + TAP_REFRESH_NOTE_MILLIS))
        WidgetTapRefresh.finished(WatchRefreshOutcome.REFRESHED, stamp, WidgetTapRefresh.begin())
        assertNull(WidgetTapRefresh.noteRemaining(11_000L))
    }

    @Test
    fun `the note and its expiry are read together`() {
        WidgetTapRefresh.started(1_000L)
        assertEquals(WidgetTapNote.REFRESHING to TAP_REFRESH_NOTE_MILLIS.milliseconds, WidgetTapRefresh.noteAndExpiry(1_000L, stamp))
        WidgetTapRefresh.finished(WatchRefreshOutcome.UNREACHABLE, stamp, WidgetTapRefresh.begin())
        // A failure's note lasts as long as its snapshot does: no expiry of its own.
        assertEquals(WidgetTapNote.UNREACHABLE to null, WidgetTapRefresh.noteAndExpiry(2_000L, stamp))
    }

    @Test
    fun `a failure is said only over the snapshot it couldn't replace`() {
        WidgetTapRefresh.started(0L)
        WidgetTapRefresh.finished(WatchRefreshOutcome.UNREACHABLE, stamp, WidgetTapRefresh.begin())
        assertEquals(WidgetTapNote.UNREACHABLE, WidgetTapRefresh.note(1_000L, stamp))
        // The app saved newer data since: the failure no longer speaks for what's shown.
        assertNull(WidgetTapRefresh.note(1_000L, stamp.plusSeconds(60)))
        // The next tap says it's refreshing again, not the old failure.
        WidgetTapRefresh.started(2_000L)
        assertEquals(WidgetTapNote.REFRESHING, WidgetTapRefresh.note(2_000L, stamp))
    }

    @Test
    fun `each failure says its own reason, and a partial refresh none`() {
        val cases = mapOf(
            WatchRefreshOutcome.RATE_LIMITED to WidgetTapNote.RATE_LIMITED,
            WatchRefreshOutcome.UNREACHABLE to WidgetTapNote.UNREACHABLE,
            WatchRefreshOutcome.KEY_REJECTED to WidgetTapNote.KEY_REJECTED,
            // Its missing stops are marked in the snapshot itself ("Some stops out of date").
            WatchRefreshOutcome.PARTLY_REFRESHED to null,
            WatchRefreshOutcome.DEBOUNCED to null,
            WatchRefreshOutcome.NO_STOPS to null,
        )
        for ((outcome, note) in cases) {
            WidgetTapRefresh.started(0L)
            WidgetTapRefresh.finished(outcome, stamp, WidgetTapRefresh.begin())
            assertEquals(outcome.name, note, WidgetTapRefresh.note(0L, stamp))
        }
    }

    @Test
    fun `a tap during a refresh keeps saying refreshing until its own refresh ends`() {
        WidgetTapRefresh.started(0L)
        val first = WidgetTapRefresh.begin()
        // Tapped again after the first refresh read the stops: its own refresh is queued behind.
        WidgetTapRefresh.started(1_000L)
        WidgetTapRefresh.finished(WatchRefreshOutcome.REFRESHED, stamp, first)
        assertEquals(WidgetTapNote.REFRESHING, WidgetTapRefresh.note(2_000L, stamp))
        WidgetTapRefresh.finished(WatchRefreshOutcome.DEBOUNCED, stamp, WidgetTapRefresh.begin())
        assertNull(WidgetTapRefresh.note(2_000L, stamp))
    }

    @Test
    fun `a tap joins a refresh not yet started, and queues behind a running one`() {
        assertEquals(ExistingWorkPolicy.KEEP, tapRefreshPolicy(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(ExistingWorkPolicy.KEEP, tapRefreshPolicy(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, tapRefreshPolicy(listOf(WorkInfo.State.RUNNING)))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, tapRefreshPolicy(listOf(WorkInfo.State.SUCCEEDED)))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, tapRefreshPolicy(emptyList()))
    }

    @Test
    fun `a tap hops off the caller's thread, then enqueues a refresh and redraws`() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val worker = pool.asCoroutineDispatcher()
            val redrawnOn = ThreadRecorder()
            val redraw: suspend (Context) -> Unit = { redrawnOn.note() }
            runBlocking { requestWidgetRefresh(context, worker, redraw) }
            // One refresh enqueued, whatever state it's reached: the test WorkManager may already have
            // run it (with no stored snapshot it ends at once and drops "Refreshing…"), so neither its
            // state nor the note is asserted here; the note's own tests cover it.
            assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(WIDGET_TAP_REFRESH_WORK).get().size)
            assertEquals(listOf("test-worker"), redrawnOn.threads())
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `with no stored snapshot the refresh ends at once and stops saying refreshing`() = runTest {
        WidgetTapRefresh.started(android.os.SystemClock.elapsedRealtime())
        val result = TestListenableWorkerBuilder<WidgetTapRefreshWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(WidgetTapRefresh.note(android.os.SystemClock.elapsedRealtime(), stamp))
    }
}
