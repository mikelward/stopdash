package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.stopdash.domain.Departure
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A list grouped by station draws its loading cards before its stations are worked out; the stations then land
 * above them. A list the rider hasn't scrolled stays at its top for them, rather than keeping the card it led with
 * in view and opening a page down (bug report, 2026-10-24: From… Victoria opened at Hyde Park Corner).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class StationsLandAtTopTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    // The nearest place is in; the farther ones are still loading. Synthetic stops and lines; no user data.
    private val loaded = (1..3).map { i ->
        StopArrivals(
            "S$i", "Stop $i",
            listOf(Departure("l$i", "L$i", "outbound", "Dest $i", null, now.plusSeconds(60L + i * 10), "bus")),
            fetchedAt = now.minusSeconds(60),
        )
    }
    private val pending = (1..12).map { i -> StopRef("P$i", "Pending $i") }
    private val meters = loaded.mapIndexed { i, stop -> stop.stopId to 10.0 + i }.toMap() +
        pending.mapIndexed { i, stop -> stop.id to 500.0 + i * 100 }.toMap()

    private fun open(scrolledBy: Int = 0): Pair<LazyListState, () -> Unit> {
        val scheduler = TestCoroutineScheduler()
        val worker = StandardTestDispatcher(scheduler)
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberListStateFor("set")
            StopDashTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        MainScreen(
                            DeparturesUiState.Loaded(loaded, now.minusSeconds(60), pendingStops = pending),
                            now, {},
                            stopDistanceMeters = meters,
                            listKey = "set",
                            listState = listState,
                            stationGroups = true,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // The worker's answers one at a time, until the list draws anything: its cards are worked out before its stations.
        repeat(8) {
            if (composeRule.runOnIdle { listState.layoutInfo.visibleItemsInfo.isEmpty() }) {
                scheduler.runCurrent()
                composeRule.waitForIdle()
            }
        }
        // The loading cards are up and the stations not yet: what the bug's first frames drew.
        composeRule.runOnIdle {
            assertTrue(listState.leadKey().toString().startsWith("pending|"))
            assertTrue(listState.layoutInfo.visibleItemsInfo.none { it.key.toString().startsWith("station|") })
        }
        if (scrolledBy > 0) {
            composeRule.runOnIdle { runBlocking { listState.scrollToItem(0, scrolledBy) } }
            composeRule.waitForIdle()
        }
        return listState to {
            repeat(8) {
                scheduler.advanceUntilIdle()
                composeRule.waitForIdle()
            }
        }
    }

    @Test
    fun `a new list grouped by station opens at its top once its stations are in`() {
        val (listState, work) = open()
        work()
        composeRule.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
            assertTrue(listState.leadKey().toString().startsWith("station|"))
        }
    }

    // Hold still: a list the rider has moved keeps what they were reading in view as the stations land.
    @Test
    fun `a list the rider has scrolled keeps its card in view as its stations land`() {
        val (listState, work) = open(scrolledBy = 10)
        val reading = composeRule.runOnIdle { listState.leadKey() }
        work()
        composeRule.runOnIdle { assertEquals(reading, listState.leadKey()) }
    }

    // The item the list is scrolled to, not one drawn in the top padding above it.
    private fun LazyListState.leadKey(): Any = layoutInfo.visibleItemsInfo.first { it.index == firstVisibleItemIndex }.key
}
