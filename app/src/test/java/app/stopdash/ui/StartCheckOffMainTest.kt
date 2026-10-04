package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether an open route can be followed, and start, is worked out on the screen's worker ([LocalWorker]), never the
 * main thread (AGENTS.md *Main thread: read and dispatch only*): a bus route's check walks its line
 * routes' stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class StartCheckOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()

    @After
    fun tearDown() {
        worker.close()
    }

    private val at = Instant.parse("2026-09-18T08:00:00Z")
    private val route = TripRoute(listOf(TripLeg("tube", "red", "Red", "A", "A", "C", "C", at, at.plusSeconds(600))))

    @Test
    fun theCheck_isWorkedOutOnTheWorker() {
        val threads = mutableListOf<String>()
        var check: StartCheck? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) {
                check = rememberStartCheck(
                    route, emptyMap(), originUnconfirmed = false,
                    follow = { synchronized(threads) { threads += Thread.currentThread().name }; true },
                    start = { _, _, _ -> synchronized(threads) { threads += Thread.currentThread().name }; true },
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { check != null }
        assertEquals(StartCheck(canFollow = true, canStart = true), check)
        assertEquals(2, threads.size)
        assertTrue("checked on $threads", threads.all { it.startsWith("test-worker") })
    }

    @Test
    fun anAnswerForOldInputs_isNotRead_untilTheNewOneIsIn() {
        // The worker answers the first check, then holds the second until the test releases it.
        val release = CountDownLatch(1)
        var calls = 0
        var originUnconfirmed by mutableStateOf(false)
        var check: StartCheck? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) {
                check = rememberStartCheck(route, emptyMap(), originUnconfirmed) { _, _, unconfirmed ->
                    if (++calls > 1) release.await()
                    !unconfirmed
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { check?.canStart == true }
        // A relocation begins: the old yes is never read while its replacement is worked out.
        originUnconfirmed = true
        composeRule.waitForIdle()
        assertNull(check)
        release.countDown()
        composeRule.waitUntil(timeoutMillis = 5_000) { check != null }
        assertEquals(StartCheck(canFollow = true, canStart = false), check)
    }

    @Test
    fun aRouteRebuiltEqual_isCheckedAgain_notLeftWaiting() {
        // Every assignment a change, as a refresh handing over a new route is, equal or not.
        var shown by mutableStateOf(route, neverEqualPolicy())
        // Counted on the worker, read here: atomic, so the wait below sees the second check.
        val calls = AtomicInteger()
        var check: StartCheck? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) {
                check = rememberStartCheck(shown, emptyMap(), originUnconfirmed = false) { _, _, _ -> calls.incrementAndGet(); true }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { check?.canStart == true }
        // A refresh rebuilds the route equal but not the same: it's checked again and Start comes back.
        shown = route.copy()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) { calls.get() == 2 && check?.canStart == true }
    }

    @Test
    fun aRouteThatCantBeFollowed_cantStart_andIsNotCheckedFurther() {
        var started = false
        var check: StartCheck? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) {
                check = rememberStartCheck(route, emptyMap(), originUnconfirmed = false, follow = { false }) { _, _, _ ->
                    started = true
                    true
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { check != null }
        assertEquals(StartCheck(canFollow = false, canStart = false), check)
        assertFalse(started)
    }

    @Test
    fun noRoute_isNotChecked() {
        var checked = false
        var check: StartCheck? = StartCheck(canFollow = true, canStart = true)
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) {
                check = rememberStartCheck(null, emptyMap(), originUnconfirmed = false, follow = { checked = true; true })
            }
        }
        composeRule.waitForIdle()
        assertNull(check)
        assertFalse(checked)
    }
}
