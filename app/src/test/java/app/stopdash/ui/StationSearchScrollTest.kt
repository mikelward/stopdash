package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    private fun matches(prefix: String) =
        StationSearchViewModel.Result.Matches((0 until 40).map { StationMatch("$prefix$it", "$prefix $it") })

    @Test
    fun a_new_query_shows_its_best_match_first() {
        var state by mutableStateOf(StationSearchViewModel.State(query = "kin", result = matches("Kin"), yoursRead = true))
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(state = state, onQueryChange = {}, onOpenStation = {}, onRetry = {}, onBack = {}, autoFocus = false)
            }
        }
        composeRule.onNodeWithTag("stationSearchMatches").performScrollToIndex(30)
        composeRule.waitForIdle()

        state = StationSearchViewModel.State(query = "king", result = matches("King"), yoursRead = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("King 0").assertExists()
    }
}
