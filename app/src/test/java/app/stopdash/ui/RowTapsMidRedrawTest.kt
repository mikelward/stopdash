package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.stopdash.data.TflRouteSequenceDto
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStop
import app.stopdash.domain.RouteStopsRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A station row's tap or long press lands though the page redraws under the finger, as it does every
 * few seconds while departures count down: the row's gesture used to start over on each redraw,
 * dropping a press in progress, so a tap did nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class RowTapsMidRedrawTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Bumped mid-press: every action below is built afresh from it, as the page's are on each redraw.
    private var redraws by mutableIntStateOf(0)
    private val done = ArrayList<String>()

    private fun showRouteStops() {
        composeRule.setContent {
            val redraw = redraws
            RouteStopsSection(
                state = RouteStopsUi.Loaded(
                    listOf(
                        RouteStop("940GZZLUKSX", "King's Cross St. Pancras"),
                        RouteStop("940GZZLUEUS", "Euston", listOf(LineRef("northern", "Northern", "tube"))),
                        RouteStop("940GZZLUWRR", "Warren Street"),
                    ),
                ),
                railColor = Color.Blue,
                onRetry = {},
                onToggleJourneyTo = { done += "saved ${it.name} ($redraw)" },
                onOpenStop = { stop, _ -> done += "opened ${stop.name} ($redraw)" },
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a stop list's tap lands though the page redraws mid-press`() {
        showRouteStops()
        composeRule.onNodeWithText("Euston").performTouchInput { down(center) }
        redraws++
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Euston").performTouchInput { up() }
        composeRule.waitForIdle()
        // The latest action, not the one the press began under.
        assertEquals(listOf("opened Euston (1)"), done)
    }

    @Test
    fun `a stop list's long press lands though the page redraws mid-press`() {
        showRouteStops()
        composeRule.onNodeWithText("Warren Street").performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(200)
        redraws++
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(LONG_PRESS_MILLIS)
        composeRule.onNodeWithText("Warren Street").performTouchInput { up() }
        composeRule.waitForIdle()
        // It opens the stop's menu, whose item saves with the latest action.
        composeRule.onNodeWithTag("stopRideToggle").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("saved Warren Street (1)"), done)
    }

    @Test
    fun `a line map's tap lands though the page redraws mid-press`() {
        val northern: LineSequence = Json { ignoreUnknownKeys = true }
            .decodeFromString<TflRouteSequenceDto>(checkNotNull(javaClass.getResource("/fixtures/route_sequence_northern_outbound.json")).readText())
            .toLineSequence()
        val scheduler = TestCoroutineScheduler()
        val worker = StandardTestDispatcher(scheduler)
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = northern
            },
            io = worker,
            compute = worker,
        )
        composeRule.setContent {
            val redraw = redraws
            CompositionLocalProvider(
                LocalWorker provides worker,
                LocalRouteStops provides repository,
                LocalOpenLineMapStop provides { _, name, _ -> done += "opened $name ($redraw)" },
                // A long press offered too: the row takes its gestures itself, as on a route page.
                LocalLineMapExtras provides LineMapExtras(
                    onLongPress = { _, name -> done += "saved $name ($redraw)" },
                    longPressStops = setOf(NINE_ELMS),
                ),
            ) {
                val ui = rememberLineMap("northern", null, emptySet(), emptySet(), null, all = true, retry = 0)
                if (ui != null) LineMapColumn(LineMapSectionState(ui, atFirst = false, open = {}, toggleAll = {}, retry = {}), Color.Black)
            }
        }
        repeat(4) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Nine Elms").performTouchInput { down(center) }
        redraws++
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Nine Elms").performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(listOf("opened Nine Elms (1)"), done)
    }

    private companion object {
        // On screen in the map's first rows, where a touch reaches it.
        const val NINE_ELMS = "940GZZNEUGST"

        // Past the platform's long-press timeout (500 ms by default).
        const val LONG_PRESS_MILLIS = 600L
    }
}
