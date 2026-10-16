package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.stopdash.domain.Departure
import app.stopdash.domain.Countdown
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RailFeed
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.Staleness
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlin.time.toJavaDuration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A stop card's rows checked ([RowsChecking]): their times stand a moment, then fade and pulse, heard
 * as being checked, and come back at once; a row with no answer of its own spins at once. A stale row
 * fades only while its own stop is being fetched. A ride's own line with no train due draws a dash
 * heard as what it means.
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

    // A time drawn as being checked: faded, and heard as [spoken] after it.
    private fun checked(text: String, spoken: String) = composeRule.onAllNodes(
        SemanticsMatcher("$text, being checked") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                node.config.getOrNull(SemanticsProperties.StateDescription) == spoken
        },
    )

    // The clock stepped by hand, a state change applied and drawn on the frame after it, as CardMoveTest steps its slides.
    private fun advance(millis: Long) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    @Test
    fun a_row_checked_again_keeps_its_time_a_moment_then_fades_and_comes_back_at_once() {
        var checking by mutableStateOf<RowsChecking?>(null)
        composeRule.mainClock.autoAdvance = false
        show(timedCard(), { checking })
        advance(0)
        composeRule.onAllNodesWithText("5 min").assertCountEquals(1)

        checking = RowsChecking("Checking trains…")
        advance(NOTE_SETTLE_MILLIS / 2)
        // A quick check changes nothing on screen.
        checked("5 min", "Checking trains…").assertCountEquals(0)

        advance(NOTE_SETTLE_MILLIS)
        // Still checking: the time stays, as it was, faded and heard as being checked; no spinner.
        composeRule.onAllNodesWithText("Walthamstow Central").assertCountEquals(1)
        checked("5 min", "Checking trains…").assertCountEquals(1)
        spinners().assertCountEquals(0)

        checking = null
        advance(0)
        composeRule.onAllNodesWithText("5 min").assertCountEquals(1)
        checked("5 min", "Checking trains…").assertCountEquals(0)
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

    @Test
    fun a_stale_row_fades_only_while_a_refresh_is_under_way() {
        val stale = timedCard()
        var refreshing by mutableStateOf<RowsChecking?>(RowsChecking("Checking…", stopIds = setOf(victoria.stopId)))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StopGroupCard(
                    stale,
                    // Past the staleness threshold: the row shows its train's time, marked as a guess.
                    now.plus(Staleness.THRESHOLD.toJavaDuration()).plusSeconds(60),
                    starred = emptySet(),
                    onToggleStar = {},
                    starringAvailable = false,
                    onOpenDetail = null,
                    checkingStale = refreshing,
                )
            }
        }
        advance(0)
        val guess = Countdown.staleLabel(victoria.departures)
        checked(guess, "Checking…").assertCountEquals(1)
        // Nothing under way: the guess stands as it is, no work about to replace it.
        refreshing = null
        advance(0)
        composeRule.onAllNodesWithText(guess).assertCountEquals(1)
        checked(guess, "Checking…").assertCountEquals(0)
    }

    @Test
    fun a_stale_row_stands_while_only_other_stops_are_fetched() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StopGroupCard(
                    timedCard(),
                    now.plus(Staleness.THRESHOLD.toJavaDuration()).plusSeconds(60),
                    starred = emptySet(),
                    onToggleStar = {},
                    starringAvailable = false,
                    onOpenDetail = null,
                    // Its own request failed; another stop's is still out.
                    checkingStale = RowsChecking("Checking…", stopIds = setOf("940GZZLUOXC")),
                )
            }
        }
        advance(NOTE_SETTLE_MILLIS)
        val guess = Countdown.staleLabel(victoria.departures)
        composeRule.onAllNodesWithText(guess).assertCountEquals(1)
        checked(guess, "Checking…").assertCountEquals(0)
    }

    // A National Rail line with no key and a disruption to show: a status row reading "No key".
    private fun noKeyCard(fetchedAt: Instant): StopCard {
        val stop = StopArrivals(
            "910GEXAMPLE",
            "Example",
            departures = emptyList(),
            fetchedAt = fetchedAt,
            lines = listOf(LineRef("great-northern", "Great Northern", "national-rail")),
            railFeed = RailFeed.NO_KEY,
        )
        val statuses = mapOf("great-northern" to LineStatus("great-northern", severity = 6, description = "Severe Delays"))
        return stopCard(StopGrouping.groupByStop(DepartureRows.across(listOf(stop), now, statuses), warningsLead = false).single(), RouteTopology.EMPTY)
    }

    private fun noKeyChecked() = composeRule.onAllNodes(
        SemanticsMatcher("No key, being checked") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "No key" } == true &&
                node.config.getOrNull(SemanticsProperties.StateDescription) != null
        },
    )

    @Test
    fun no_key_stands_while_its_card_is_checked_as_no_check_gives_it_times() {
        composeRule.mainClock.autoAdvance = false
        show(noKeyCard(now), { RowsChecking("Checking trains…") })
        advance(NOTE_SETTLE_MILLIS)
        composeRule.onAllNodesWithText("No key").assertCountEquals(1)
        noKeyChecked().assertCountEquals(0)
    }

    @Test
    fun a_stale_status_row_stands_while_its_stop_is_fetched_having_no_guess_to_fade() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StopGroupCard(
                    noKeyCard(now),
                    now.plus(Staleness.THRESHOLD.toJavaDuration()).plusSeconds(60),
                    starred = emptySet(),
                    onToggleStar = {},
                    starringAvailable = false,
                    onOpenDetail = null,
                    checkingStale = RowsChecking("Checking…", stopIds = setOf("910GEXAMPLE")),
                )
            }
        }
        advance(NOTE_SETTLE_MILLIS)
        composeRule.onAllNodesWithText("No key").assertCountEquals(1)
        noKeyChecked().assertCountEquals(0)
    }

    @Test
    fun a_fresh_row_is_left_as_it_is_while_a_refresh_is_under_way() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StopGroupCard(
                    timedCard(),
                    now,
                    starred = emptySet(),
                    onToggleStar = {},
                    starringAvailable = false,
                    onOpenDetail = null,
                    checkingStale = RowsChecking("Checking…"),
                )
            }
        }
        advance(NOTE_SETTLE_MILLIS)
        composeRule.onAllNodesWithText("5 min").assertCountEquals(1)
        checked("5 min", "Checking…").assertCountEquals(0)
    }
}
