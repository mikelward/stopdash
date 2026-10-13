package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import app.stopdash.domain.StationMatch
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A new query's matches start at the top, best match first, not where the last query's were scrolled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class StationSearchScrollTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun matches(prefix: String, query: String = prefix.lowercase()) =
        StationSearchViewModel.Result.Matches((0 until 40).map { StationMatch("$prefix$it", "$prefix $it") }, query = query)

    @Test
    fun a_new_query_shows_its_best_match_first() {
        var state by mutableStateOf(StationSearchViewModel.State(query = "kin", result = matches("Kin"), yoursRead = true))
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(state = state, onQueryChange = {}, onOpenStation = {}, onBack = {}, autoFocus = false)
            }
        }
        composeRule.onNodeWithTag("stationSearchMatches").performScrollToIndex(30)
        composeRule.waitForIdle()

        state = StationSearchViewModel.State(query = "king", result = matches("King"), yoursRead = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("King 0").assertExists()
    }

    // The last query's top row still matches the next, but lower down (a looser match): the list used to
    // keep that row in view, scrolling the new best match off the top as the next letter's answer landed.
    @Test
    fun a_new_query_shows_its_best_match_first_when_the_last_top_row_still_matches() {
        val oldTop = StationMatch("old", "Stop Old")
        val newTop = StationMatch("new", "Stop New")
        val rest = (0 until 40).map { StationMatch("s$it", "Stop $it") }
        var state by mutableStateOf(
            StationSearchViewModel.State(
                query = "stop",
                result = StationSearchViewModel.Result.Matches(listOf(oldTop) + rest, query = "stop"),
                yoursRead = true,
            ),
        )
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(state = state, onQueryChange = {}, onOpenStation = {}, onBack = {}, autoFocus = false)
            }
        }
        composeRule.waitForIdle()

        // The letter is typed first; the last query's rows stay up while its search runs.
        state = state.copy(query = "stop n", searching = true)
        composeRule.waitForIdle()
        state = state.copy(
            result = StationSearchViewModel.Result.Matches(listOf(newTop, oldTop) + rest, query = "stop n"),
            searching = false,
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stop New").assertIsDisplayed()
    }

    // Within one query the rows only grow, below what's shown, and a list the rider has scrolled stays put.
    @Test
    fun rows_added_to_the_same_query_leave_the_scroll_alone() {
        val first = (0 until 40).map { StationMatch("s$it", "Stop $it") }
        var state by mutableStateOf(
            StationSearchViewModel.State(
                query = "stop",
                result = StationSearchViewModel.Result.Matches(first, query = "stop"),
                searching = true,
                yoursRead = true,
            ),
        )
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(state = state, onQueryChange = {}, onOpenStation = {}, onBack = {}, autoFocus = false)
            }
        }
        composeRule.onNodeWithTag("stationSearchMatches").performScrollToIndex(30)
        composeRule.waitForIdle()

        val more = (40 until 60).map { StationMatch("s$it", "Stop $it") }
        state = state.copy(result = StationSearchViewModel.Result.Matches(first + more, query = "stop"), searching = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stop 30").assertIsDisplayed()
        composeRule.onNodeWithText("Stop 0").assertDoesNotExist()
    }

    // Process death while the next letter's search ran, the last query's rows still up and scrolled: the
    // restored scroll is that query's, so it mustn't land on the new query's rows (Codex, #825).
    @Test
    fun a_scroll_saved_over_the_last_query_isnt_restored_onto_the_next() {
        val rows = (0 until 40).map { StationMatch("s$it", "Stop $it") }
        var state by mutableStateOf(
            StationSearchViewModel.State(
                query = "stop",
                result = StationSearchViewModel.Result.Matches(rows, query = "stop"),
                yoursRead = true,
            ),
        )
        // What the screen comes back with after the restore: the typed query, searched afresh. Not
        // snapshot state, so setting it doesn't recompose (and drop the list) before the save.
        var restoredAs: StationSearchViewModel.State? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            // Read first, so the content recomposes when it changes even while [restoredAs] stands in.
            val current = state
            StopDashTheme {
                StationSearchScreen(state = restoredAs ?: current, onQueryChange = {}, onOpenStation = {}, onBack = {}, autoFocus = false)
            }
        }
        composeRule.onNodeWithTag("stationSearchMatches").performScrollToIndex(30)
        composeRule.waitForIdle()
        state = state.copy(query = "stop n", searching = true)
        composeRule.waitForIdle()

        restoredAs = StationSearchViewModel.State(query = "stop n", searching = true, yoursRead = true)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        restoredAs = null
        state = StationSearchViewModel.State(
            query = "stop n",
            result = StationSearchViewModel.Result.Matches(listOf(StationMatch("new", "Stop New")) + rows, query = "stop n"),
            yoursRead = true,
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stop New").assertIsDisplayed()
    }
}
