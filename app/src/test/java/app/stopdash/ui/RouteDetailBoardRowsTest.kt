package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A route page opened from the list shows the list's own row's trains ([RouteDetailScreen]'s `boardRows`):
 * a Tube train just past its time stays, grayed at "0", as on the list, and nothing is filtered in
 * composition, the trains coming from the page's work on its worker. A trip's page, from rows built at
 * another time, still drops a train at its time, worked out on the worker too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class RouteDetailBoardRowsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val due = Instant.parse("2026-09-18T08:00:00Z")
    private val now = due.plusSeconds(10)
    private val stop = StopArrivals(
        "940GZZLUEUS", "Euston",
        listOf(
            Departure("victoria", "Victoria", "outbound", "Brixton", "Southbound - Platform 5", due, "tube"),
            Departure("victoria", "Victoria", "outbound", "Brixton", "Southbound - Platform 5", due.plusSeconds(250), "tube"),
        ),
        fetchedAt = due,
    )

    private fun show(boardRows: Boolean): TestCoroutineScheduler {
        val scheduler = TestCoroutineScheduler()
        val worker = StandardTestDispatcher(scheduler)
        val row = DepartureRows.across(listOf(stop), now, grace = true).first { it.upcoming.isNotEmpty() }
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides worker) {
                    RouteDetailScreen(
                        row = row, isStarred = false, starrable = true, disruptionUnknown = false, stale = false, now = now,
                        onToggleStar = {}, onBack = {}, routeStops = RouteStopsUi.Loading, boardRows = boardRows,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return scheduler
    }

    @Test
    fun a_page_from_the_list_keeps_its_just_due_train_worked_out_on_the_worker() {
        val scheduler = show(boardRows = true)
        // Nothing until the page's work runs on its worker: none of it in composition.
        composeRule.onAllNodesWithText("0 · 4 min").assertCountEquals(0)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("0 · 4 min").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("0 min, due, 4 min").assertCountEquals(1)
    }

    @Test
    fun a_trip_s_page_drops_a_train_at_its_time() {
        val scheduler = show(boardRows = false)
        // The train past its time is left out on the worker, after the page's trains are, never in composition
        // (Codex on #1105): the countdown holds its line blank until then, never showing it.
        repeat(2) {
            composeRule.onAllNodesWithText("4 min", substring = true).assertCountEquals(0)
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
        composeRule.onAllNodesWithText("0 · 4 min").assertCountEquals(0)
        composeRule.onAllNodesWithText("4 min").assertCountEquals(1)
    }
}
