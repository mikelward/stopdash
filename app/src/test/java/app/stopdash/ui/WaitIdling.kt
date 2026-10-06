package app.stopdash.ui

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Waits for [condition] on work a real single-thread [worker] does: each pass lets the main looper run
 * ([ComposeTestRule.waitForIdle]), checks, then runs whatever the worker has queued to its end, so what
 * it hands back is on the main looper for the next pass. [ComposeTestRule.waitUntil] only advances the
 * Compose frame clock between checks, never Robolectric's paused main looper, so work the worker handed
 * back through it (a route load landing, and the snapshot apply that recomposes on it) could sit there
 * unrun until the wait timed out, as `NextTrainsOffMainTest` did one CI run in a few. No clock: each pass
 * waits on the worker itself, and [passes] bounds a condition that never comes.
 */
internal fun ComposeTestRule.waitUntilWorked(worker: Executor, passes: Int = 50, condition: () -> Boolean) {
    repeat(passes) {
        waitForIdle()
        if (condition()) return
        val drained = CountDownLatch(1)
        worker.execute { drained.countDown() }
        check(drained.await(10, TimeUnit.SECONDS)) { "the worker didn't drain" }
    }
    throw ComposeTimeoutException("Condition still not satisfied after $passes passes")
}
