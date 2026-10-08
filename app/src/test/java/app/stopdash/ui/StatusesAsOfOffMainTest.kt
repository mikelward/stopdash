package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.ThreadRecorder
import app.stopdash.domain.LineAlert
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trip page's statuses are brought up to today ([LineStatus.asOf]) on the screen's worker
 * ([LocalWorker]), never in composition (AGENTS.md *Main thread*; Codex on #519): it goes through
 * every alert under way. Made-up words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class StatusesAsOfOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()

    @After
    fun tearDown() {
        worker.close()
    }

    private val now = Instant.parse("2026-10-03T08:00:00Z")

    @Test
    fun startedWork_joinsTheAlertsUnderWayOnTheWorker() {
        val read = ThreadRecorder()
        val alerts = listOf(LineAlert(6, "Diversion", "Bus stop 'Alpha Road' will not be served."), LineAlert(9, "Minor Delays", "Minor delays."))
        val started = PlannedAlert("Part Closure", "Road closed from today.", LocalDate.of(2026, 10, 3))
        val status = LineStatus("99", 6, "Diversion", alerts.first().fullText, planned = listOf(started), underWay = Watched(alerts, read))
        // One map from one composition to the next, as the screen's state holds it.
        val statuses = mapOf("99" to status)
        var shown: Map<String, LineStatus> = emptyMap()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) {
                shown = rememberStatusesAsOf(statuses, LocalDate.of(2026, 10, 2), now)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { shown["99"]?.planned?.isEmpty() == true }

        // The work started today is under way now, beside the alerts that were.
        assertEquals(3, shown.getValue("99").underWay.size)
        assertTrue(read.threads().isNotEmpty())
        assertEquals(setOf("test-worker"), read.threads().toSet())
    }

    @Test
    fun sortedToday_areShownAsTheyAre_withNoWait() {
        val read = ThreadRecorder()
        val status = LineStatus("99", 6, "Diversion", "Diverted.", underWay = Watched(listOf(LineAlert(6, "Diversion", "Diverted.")), read))
        val statuses = mapOf("99" to status)
        var shown: Map<String, LineStatus>? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) {
                shown = rememberStatusesAsOf(statuses, LocalDate.of(2026, 10, 3), now)
            }
        }
        composeRule.waitForIdle()
        assertSame(statuses, shown)
        assertTrue(read.threads().isEmpty())
    }

    @Test
    fun sortedOnADayGoneBy_showNoneUntilBroughtUpToToday() {
        // Planned work starting today, from statuses sorted yesterday: not shown as still to come
        // while today's answer is worked out, but as not known yet (Codex on #519).
        val started = PlannedAlert("Part Closure", "Road closed from today.", LocalDate.of(2026, 10, 3))
        val statuses = mapOf("99" to LineStatus("99", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(started)))
        val gate = CountDownLatch(1)
        worker.executor.execute { gate.await() }
        var shown: Map<String, LineStatus>? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) {
                shown = rememberStatusesAsOf(statuses, LocalDate.of(2026, 10, 2), now)
            }
        }
        composeRule.waitForIdle()
        assertEquals(emptyMap<String, LineStatus>(), shown)
        gate.countDown()
        composeRule.waitUntil(timeoutMillis = 5_000) { shown?.containsKey("99") == true }
        assertEquals("Part Closure", shown?.getValue("99")?.description)
    }
}
