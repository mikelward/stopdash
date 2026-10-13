package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopGroup
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The departures list, as a refresh changes it (SPEC *Update indicator*): rows that trade places slide
 * there with taps paused until they stop, and a list for another set of stops comes in row by row,
 * taps paused meanwhile, as one replacing a loading screen does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class RefreshSlidesTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    // The rows are worked out only when the test says.
    private val scheduler = TestCoroutineScheduler()
    private val held = StandardTestDispatcher(scheduler)
    private val pause = TapPause { composeRule.mainClock.currentTime }
    private var state by mutableStateOf<DeparturesUiState>(DeparturesUiState.Loading)
    private var listKey by mutableStateOf("kings-cross")

    private fun kingsCross(victoriaIn: Long, piccadillyIn: Long) = StopArrivals(
        stopId = "940GZZLUKSX",
        stopName = "King's Cross St. Pancras",
        departures = listOf(
            Departure("victoria", "Victoria", "southbound", "Brixton", null, now.plusSeconds(victoriaIn), "tube"),
            Departure("piccadilly", "Piccadilly", "westbound", "Heathrow T5", null, now.plusSeconds(piccadillyIn), "tube"),
        ),
        fetchedAt = now,
    )

    private val euston = StopArrivals(
        stopId = "940GZZLUEUS",
        stopName = "Euston",
        departures = listOf(Departure("northern", "Northern", "southbound", "Morden", null, now.plusSeconds(180), "tube")),
        fetchedAt = now,
    )

    private fun show(stops: StopArrivals) {
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held, LocalTapPause provides pause) {
                    MainScreen(state = state, now = now, onRefresh = {}, listKey = listKey)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        state = DeparturesUiState.Loaded(stops = listOf(stops), fetchedAt = now)
        workOutRows()
        // Past the reveal of the first rows, so only what follows pauses taps.
        composeRule.mainClock.advanceTimeBy(REVEAL_MILLIS + TAP_PAUSE_FLOOR_MILLIS)
        assertFalse(pause.ignoresTaps())
    }

    private fun workOutRows() {
        Snapshot.sendApplyNotifications()
        repeat(5) {
            scheduler.advanceUntilIdle()
            composeRule.mainClock.advanceTimeByFrame()
        }
    }

    private fun top(text: String) = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    @Test
    fun `rows that trade places on a refresh slide there, taps paused until they stop`() {
        show(kingsCross(victoriaIn = 240, piccadillyIn = 360))
        val brixtonWas = top("Brixton")
        val heathrowWas = top("Heathrow T5")
        assertTrue(brixtonWas < heathrowWas)

        // The Piccadilly's train is now the sooner: its row moves up.
        state = DeparturesUiState.Loaded(stops = listOf(kingsCross(victoriaIn = 240, piccadillyIn = 120)), fetchedAt = now)
        Snapshot.sendApplyNotifications()
        var between = 0
        repeat(40) { frame ->
            scheduler.advanceUntilIdle()
            composeRule.mainClock.advanceTimeByFrame()
            val at = top("Brixton")
            if (at > brixtonWas + 0.5.dp && at < heathrowWas - 0.5.dp) {
                between++
                assertTrue("frame $frame", pause.ignoresTaps())
            }
        }
        assertTrue("it slid", between > 3)
        assertEquals(heathrowWas, top("Brixton"))
        assertEquals(brixtonWas, top("Heathrow T5"))
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `two platforms' cards slide when a refresh has them trade places`() {
        // Euston's Northern line runs south from two pairs of platforms, the Bank branch's and the
        // Charing Cross branch's: one line and direction, each platform its own card.
        fun euston(viaBankIn: Long, viaCharingCrossIn: Long) = StopArrivals(
            stopId = "940GZZLUEUS",
            stopName = "Euston",
            departures = listOf(
                Departure("northern", "Northern", "inbound", "Morden", "Southbound - Platform 1", now.plusSeconds(viaBankIn), "tube"),
                Departure("northern", "Northern", "inbound", "Kennington", "Southbound - Platform 6", now.plusSeconds(viaCharingCrossIn), "tube"),
            ),
            fetchedAt = now,
        )
        show(euston(viaBankIn = 120, viaCharingCrossIn = 300))
        val mordenWas = top("Morden")
        val kenningtonWas = top("Kennington")
        assertTrue(mordenWas < kenningtonWas)

        state = DeparturesUiState.Loaded(stops = listOf(euston(viaBankIn = 400, viaCharingCrossIn = 60)), fetchedAt = now)
        Snapshot.sendApplyNotifications()
        var between = 0
        repeat(40) { frame ->
            scheduler.advanceUntilIdle()
            composeRule.mainClock.advanceTimeByFrame()
            val at = top("Morden")
            if (at > mordenWas + 0.5.dp && at < kenningtonWas - 0.5.dp) {
                between++
                assertTrue("frame $frame", pause.ignoresTaps())
            }
        }
        assertTrue("it slid", between > 3)
        assertEquals(kenningtonWas, top("Morden"))
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `rows of one direction on two platforms in one card slide when they trade places`() {
        // Camden Town's southbound Northern line leaves from Platforms 2 and 4: one line and direction,
        // two rows told apart by platform alone, in one card where a card groups by stop (a journey's).
        val rows = DepartureRows.forStop(
            "940GZZLUCTN",
            "Camden Town",
            listOf(
                Departure("northern", "Northern", "inbound", "Morden", "Southbound - Platform 2", now.plusSeconds(60), "tube"),
                Departure("northern", "Northern", "inbound", "Kennington", "Southbound - Platform 4", now.plusSeconds(90), "tube"),
            ),
            now,
        )
        assertEquals(2, rows.size)
        fun card(order: List<DepartureRow>) = stopCard(StopGroup("Camden Town", "camden-town", showHeader = false, rows = order), RouteTopology.EMPTY)
        var shown by mutableStateOf(card(rows))
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalTapPause provides pause) {
                    Column {
                        StopGroupCard(shown, now, starred = emptySet(), onToggleStar = {}, starringAvailable = false, onOpenDetail = { _, _ -> })
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        val mordenWas = top("Morden")
        val kenningtonWas = top("Kennington")

        shown = card(rows.reversed())
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        // Still near where it was, on its way: the row itself moves, not its contents into another's place.
        assertTrue(top("Morden") < mordenWas + (kenningtonWas - mordenWas) / 4)
        assertTrue(pause.ignoresTaps())
        composeRule.mainClock.advanceTimeBy(CARD_MOVE_MILLIS / 2)
        assertTrue(top("Morden") > mordenWas && top("Morden") < kenningtonWas)
        composeRule.mainClock.advanceTimeBy(CARD_MOVE_MILLIS)
        assertEquals(kenningtonWas, top("Morden"))
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `the list giving way to no departures, and back, comes in row by row, taps paused meanwhile`() {
        show(kingsCross(victoriaIn = 240, piccadillyIn = 360))

        // A refresh finds nothing upcoming: the "No departures" screen, with its Refresh button, takes
        // the rows' place with no loading screen between (Codex, #818).
        state = DeparturesUiState.Loaded(stops = listOf(kingsCross(victoriaIn = 240, piccadillyIn = 360).copy(departures = emptyList())), fetchedAt = now)
        workOutRows()
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(0)
        composeRule.onAllNodesWithText("No upcoming departures").assertCountEquals(1)
        assertTrue(pause.ignoresTaps())
        composeRule.mainClock.advanceTimeBy(REVEAL_MILLIS + TAP_PAUSE_FLOOR_MILLIS)
        assertFalse(pause.ignoresTaps())

        // And back: the rows take the Refresh button's place the same way.
        state = DeparturesUiState.Loaded(stops = listOf(kingsCross(victoriaIn = 240, piccadillyIn = 360)), fetchedAt = now)
        workOutRows()
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(1)
        assertTrue(pause.ignoresTaps())
        composeRule.mainClock.advanceTimeBy(REVEAL_MILLIS + TAP_PAUSE_FLOOR_MILLIS)
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `a list for other stops comes in row by row, taps paused meanwhile`() {
        show(kingsCross(victoriaIn = 240, piccadillyIn = 360))

        // Moved on: another set of stops, whose rows are still being worked out.
        listKey = "euston"
        state = DeparturesUiState.Loaded(stops = listOf(euston), fetchedAt = now)
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(1_000)
        // Neither the old rows nor the new: a placeholder, never one list's rows under the other's stops.
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(0)
        composeRule.onAllNodesWithText("Morden").assertCountEquals(0)

        workOutRows()
        composeRule.onAllNodesWithText("Morden").assertCountEquals(1)
        assertTrue(pause.ignoresTaps())
        composeRule.mainClock.advanceTimeBy(REVEAL_MILLIS + 100L)
        assertFalse(pause.ignoresTaps())
    }
}
