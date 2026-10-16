package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopGrouping
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A stop card's rows checked again ([RowsChecking]): their times stand a moment, then give way to a
 * spinner, and come back at once; a row with no answer of its own spins at once. A ride's own line
 * with no train due draws a dash heard as what it means.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class RowsCheckingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val victoria = StopArrivals(
        "940GZZLUVIC",
        "Victoria",
        listOf(
            Departure("victoria", "Victoria", "northbound", "Walthamstow Central", "Northbound - Platform 5", now.plusSeconds(300), "tube"),
        ),
        fetchedAt = now,
    )

    private fun timedCard(): StopCard =
        stopCard(StopGrouping.groupByStop(DepartureRows.across(listOf(victoria), now), warningsLead = false).single(), RouteTopology.EMPTY)

    private fun show(card: StopCard, checking: () -> RowsChecking?, noTrainsSpoken: String? = null) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StopGroupCard(
                    card,
                    now,
                    starred = emptySet(),
                    onToggleStar = {},
                    starringAvailable = false,
                    onOpenDetail = null,
                    checking = checking(),
                    noTrainsSpoken = noTrainsSpoken,
                )
            }
        }
    }

    private fun spinners() = composeRule.onAllNodesWithContentDescription("Checking trains…")

    // The clock stepped by hand, a state change applied and drawn on the frame after it, as CardMoveTest steps its slides.
    private fun advance(millis: Long) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    @Test
    fun a_row_checked_again_keeps_its_time_a_moment_then_spins_and_its_time_comes_back_at_once() {
        var checking by mutableStateOf<RowsChecking?>(null)
        composeRule.mainClock.autoAdvance = false
        show(timedCard(), { checking })
        advance(0)
        composeRule.onAllNodesWithText("5 min").assertCountEquals(1)

        checking = RowsChecking("Checking trains…")
        advance(NOTE_SETTLE_MILLIS / 2)
        // A quick check changes nothing on screen.
        composeRule.onAllNodesWithText("5 min").assertCountEquals(1)
        spinners().assertCountEquals(0)

        advance(NOTE_SETTLE_MILLIS)
        // Still checking: the time gives way, and the row itself stays.
        composeRule.onAllNodesWithText("Walthamstow Central").assertCountEquals(1)
        composeRule.onAllNodesWithText("5 min").assertCountEquals(0)
        spinners().assertCountEquals(1)

        checking = null
        advance(0)
        composeRule.onAllNodesWithText("5 min").assertCountEquals(1)
        spinners().assertCountEquals(0)
    }

    @Test
    fun a_row_with_no_answer_of_its_own_spins_at_once() {
        val journey = FavoriteJourney(JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUWRR", "Warren Street"), "victoria", "Victoria", "tube")
        val line = JourneyCard(journey, JourneyCardState.Checking("victoria's route")).lineCard
        composeRule.mainClock.autoAdvance = false
        show(line, { RowsChecking("Checking trains…", atOnce = true) })
        advance(0)
        spinners().assertCountEquals(1)
        // No dash, which would say no train is due.
        composeRule.onAllNodesWithText("–").assertCountEquals(0)
    }

    @Test
    fun a_ride_with_no_train_due_draws_its_line_with_a_dash_heard_as_none_soon() {
        val journey = FavoriteJourney(JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUWRR", "Warren Street"), "victoria", "Victoria", "tube")
        val line = JourneyCard(journey, JourneyCardState.Trains(emptyList())).lineCard
        show(line, { null }, noTrainsSpoken = "No trains to Warren Street soon")
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("–").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("No trains to Warren Street soon").assertCountEquals(1)
        // The line it's for, heard by name.
        composeRule.onAllNodesWithContentDescription("Victoria", substring = true).fetchSemanticsNodes().let { assert(it.isNotEmpty()) }
    }
}
