package app.stopdash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.ProcessExits
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager

/**
 * The startup record of why the previous processes ended, driven through the real platform
 * queries (Robolectric's `ActivityManager`) into a fresh [DebugLog], and the bug report's read of
 * it after a busy run has pushed it out of the ring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProcessExitReasonsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun seedExit(reason: Int, description: String = "killed for another-app.example") {
        val exitInfo = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
            .setTimestamp(1_700_000_000_000L)
            .setDescription(description)
            .build()
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(exitInfo)
    }

    @Test
    fun `each recent exit is pinned with its reason named and no description`() {
        seedExit(ApplicationExitInfo.REASON_CRASH_NATIVE)
        seedExit(ApplicationExitInfo.REASON_LOW_MEMORY)
        val log = DebugLog()

        logRecentProcessExits(context, log)

        val pinned = log.pinnedSnapshot()
        val exits = pinned.filter { "processExit " in it }
        assertEquals(pinned.toString(), 2, exits.size)
        assertTrue(exits.toString(), exits.any { "reason=crashNative" in it })
        assertTrue(exits.toString(), exits.any { "reason=lowMemory" in it })
        assertTrue(exits.toString(), exits.all { "importance=foreground" in it })
        // The platform's own text can name another app; TODO.md and docs/PRIVACY.md keep it out.
        assertTrue(pinned.toString(), pinned.none { "description=" in it || "another-app" in it })
        assertTrue(pinned.toString(), pinned.any { "ownPackage lastUpdateTime=" in it })
    }

    @Test
    fun `a full batch reaches the report after a busy run has pushed it out of the ring`() {
        repeat(ProcessExits.DEFAULT_MAX_RECORDS) { seedExit(ApplicationExitInfo.REASON_ANR) }
        val log = DebugLog()
        logRecentProcessExits(context, log)
        repeat(DebugLog.DEFAULT_MAX_ENTRIES + 50) { log.event("busy %s", it) }
        // Only the pinned copy carries them now, so reading the ring alone loses them.
        assertTrue(log.snapshot().none { "processExit " in it })

        val report = log.reportLines()

        assertEquals(
            report.take(10).toString(),
            ProcessExits.DEFAULT_MAX_RECORDS,
            report.count { "processExit reason=anr" in it },
        )
        assertTrue(report.any { "ownPackage lastUpdateTime=" in it })
        // Ahead of the whole ring, which the report still carries in full.
        assertTrue(report.indexOfLast { "processExit " in it } < report.indexOfFirst { "busy " in it })
        assertEquals(DebugLog.DEFAULT_MAX_ENTRIES, report.count { "busy " in it })
        assertTrue(report.last().endsWith("busy ${DebugLog.DEFAULT_MAX_ENTRIES + 49}"))
    }

    @Test
    fun `a pinned line still in the ring is reported once`() {
        val log = DebugLog()
        log.pinnedEvent("processExits none")
        log.event("later")

        val report = log.reportLines()

        assertEquals(report.toString(), 1, report.count { "processExits none" in it })
        assertTrue(report.last().endsWith("later"))
    }

    @Test
    fun `the background collection runs off the caller and logs a failure rather than losing it`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val log = DebugLog()
        var ran = false

        logRecentProcessExitsInBackground(scope, log, dispatcher) { ran = true }
        // Queued, not run inline on the caller's thread.
        assertFalse(ran)
        scope.testScheduler.advanceUntilIdle()
        assertTrue(ran)

        val job = logRecentProcessExitsInBackground(scope, log, dispatcher) {
            throw IllegalStateException("probe")
        }
        scope.testScheduler.advanceUntilIdle()

        assertTrue(job.isCompleted && !job.isCancelled)
        assertTrue(log.snapshot().toString(), log.snapshot().any { "processExits collection failed" in it })
    }
}
