package app.stopdash.ui

import android.os.Looper
import androidx.compose.runtime.LaunchedEffect
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A [RealWorker] test's effects come back to the main thread from the worker, as the app's do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class RealWorkerComposeRuleTest {
    @get:Rule
    val rule = RealWorkerComposeRule()

    @Test
    @RealWorker
    fun an_effect_comes_back_to_the_main_thread_from_its_worker() {
        // Under the rule's default it carried on on the worker (TripScreenScreenshotTest on CI, 2026-10-22).
        Executors.newSingleThreadExecutor { Thread(it, "effect-worker") }.asCoroutineDispatcher().use { worker ->
            val resumedOn = AtomicReference<Thread>()
            rule.compose.setContent {
                LaunchedEffect(Unit) {
                    withContext(worker) {}
                    resumedOn.set(Thread.currentThread())
                }
            }
            rule.compose.waitUntil(5_000) { resumedOn.get() != null }
            assertEquals(Looper.getMainLooper().thread, resumedOn.get())
        }
    }
}
