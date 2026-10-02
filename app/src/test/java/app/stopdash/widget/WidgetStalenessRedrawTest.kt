package app.stopdash.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scheduling decision behind the app-closed staleness redraw (SPEC D4): a fresh snapshot
 * enqueues exactly one unique redraw, a newer snapshot replaces the pending wake rather than
 * stacking, and a stale or absent snapshot enqueues nothing. The stale branch deliberately does
 * not *cancel* (that would cancel the boundary worker re-entering via its own `updateAll` — see
 * `applyStalenessRedrawPlan`); the removed-widget cancel is `cancelWidgetStalenessRedraw`. The
 * delay itself is the pure `Staleness.remainingUntilStale` (`StalenessTest`). Runs on an
 * in-memory WorkManager so no real work executes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetStalenessRedrawTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun wm() = WorkManager.getInstance(context)

    private fun scheduledWork(): List<WorkInfo> =
        wm().getWorkInfosForUniqueWork(WIDGET_STALENESS_WORK).get() +
            wm().getWorkInfosForUniqueWork(WIDGET_STALENESS_WORK_NEXT).get()

    private fun enqueuedCount() = scheduledWork().count { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun `a snapshot short of the threshold schedules one redraw`() {
        applyStalenessRedrawPlan(wm(), remaining = 4.minutes)
        val work = scheduledWork()
        assertEquals(1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.first().state)
    }

    @Test
    fun `an already-stale snapshot schedules nothing`() {
        applyStalenessRedrawPlan(wm(), remaining = Duration.ZERO)
        assertEquals(0, enqueuedCount())
    }

    @Test
    fun `the stale branch does not cancel a running boundary redraw`() {
        // The boundary worker re-enters provideGlance via updateAll and reaches the plan with
        // zero remaining; it must NOT cancel the unique work (that would cancel itself).
        applyStalenessRedrawPlan(wm(), remaining = 4.minutes)
        assertEquals(1, enqueuedCount())
        applyStalenessRedrawPlan(wm(), remaining = Duration.ZERO)
        assertEquals(1, enqueuedCount())
    }

    @Test
    fun `a newer snapshot replaces the previous scheduled redraw`() {
        applyStalenessRedrawPlan(wm(), remaining = 3.minutes)
        applyStalenessRedrawPlan(wm(), remaining = 2.minutes)
        assertEquals(1, enqueuedCount())
    }

    @Test
    fun `cancelWidgetStalenessRedraw cancels a scheduled redraw`() {
        // The removed-widget path (onDelete) — the one place a pending wake is cancelled.
        applyStalenessRedrawPlan(wm(), remaining = 4.minutes)
        assertEquals(1, enqueuedCount())
        cancelWidgetStalenessRedraw(context)
        assertEquals(0, enqueuedCount())
    }

    @Test
    fun `scheduleStalenessRedrawFor arms a fresh snapshot`() {
        scheduleStalenessRedrawFor(context, snapshotFetchedAt = now.minusSeconds(30), now = now)
        assertEquals(1, enqueuedCount())
    }

    @Test
    fun `scheduleStalenessRedrawFor schedules nothing for a stale or absent snapshot`() {
        scheduleStalenessRedrawFor(context, snapshotFetchedAt = now.minusSeconds(600), now = now)
        assertEquals(0, enqueuedCount())
        scheduleStalenessRedrawFor(context, snapshotFetchedAt = null, now = now)
        assertEquals(0, enqueuedCount())
    }

    @Test
    fun `a snapshot arms a redraw while any boundary is ahead, and none once all are past`() {
        // The delay itself (earliest of the arrivals' and each check's expiry) is the pure
        // DeparturesSnapshot.nextBoundary (LineStatusCheckTest).
        val check = LineStatusCheck(LineStatus("victoria", 6, "Severe Delays"), now.minusSeconds(200))
        val fresh = DeparturesSnapshot(stops = emptyList(), fetchedAt = now.minusSeconds(30), lineStatuses = mapOf("victoria" to check))
        scheduleStalenessRedrawFor(context, fresh, now)
        assertEquals(1, enqueuedCount())
        cancelWidgetStalenessRedraw(context)
        scheduleStalenessRedrawFor(context, fresh.copy(fetchedAt = now.minusSeconds(600)), now.plusSeconds(600))
        assertEquals(0, enqueuedCount())
    }

    @Test
    fun `a redraw scheduled from within a running one takes the other slot, leaving it be`() {
        applyStalenessRedrawPlan(wm(), remaining = 2.minutes)
        assertEquals(1, wm().getWorkInfosForUniqueWork(WIDGET_STALENESS_WORK).get().count { it.state == WorkInfo.State.ENQUEUED })
        // The redraw in the first slot is running and its render schedules the next boundary.
        applyStalenessRedrawPlan(wm(), remaining = 3.minutes, running = WIDGET_STALENESS_WORK)
        assertEquals(
            "the running slot is neither replaced nor cancelled",
            1,
            wm().getWorkInfosForUniqueWork(WIDGET_STALENESS_WORK).get().count { it.state == WorkInfo.State.ENQUEUED },
        )
        assertEquals(1, wm().getWorkInfosForUniqueWork(WIDGET_STALENESS_WORK_NEXT).get().count { it.state == WorkInfo.State.ENQUEUED })
        // A render with nothing running goes back to the first slot and drops the other.
        applyStalenessRedrawPlan(wm(), remaining = 4.minutes, running = null)
        assertEquals(1, enqueuedCount())
        assertEquals(1, wm().getWorkInfosForUniqueWork(WIDGET_STALENESS_WORK).get().count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `a render that must look again redraws by then, or at the snapshot's own boundary if sooner`() {
        val minute = kotlin.time.Duration.parse("1m")
        org.junit.Assert.assertEquals(minute, redrawIn(kotlin.time.Duration.ZERO, minute))
        org.junit.Assert.assertEquals(minute, redrawIn(kotlin.time.Duration.parse("4m"), minute))
        org.junit.Assert.assertEquals(kotlin.time.Duration.parse("20s"), redrawIn(kotlin.time.Duration.parse("20s"), minute))
        org.junit.Assert.assertEquals(kotlin.time.Duration.parse("4m"), redrawIn(kotlin.time.Duration.parse("4m"), null))
    }
}
