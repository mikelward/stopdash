package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search field's cursor (From… and To… share it): back from a picked station the cursor sits
 * after the typed text, not before it, and moving it doesn't search again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StationSearchFieldTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var query by mutableStateOf("")
    private var showing by mutableStateOf(true)
    private val queries = mutableListOf<String>()

    private fun show(initial: String) {
        query = initial
        composeRule.setContent {
            StopDashTheme {
                // Like the app: a picked station replaces the search, and Back brings it back afresh.
                if (showing) {
                    StationSearchScreen(
                        state = StationSearchViewModel.State(query = query, yoursRead = true),
                        onQueryChange = { queries += it; query = it },
                        onOpenStation = {},
                        onBack = {},
                        autoFocus = false,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertSelection(range: TextRange) {
        composeRule.onNodeWithTag("stationSearchField")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, range))
    }

    @Test
    fun back_from_a_station_puts_the_cursor_after_the_text() {
        show("kings")
        showing = false
        composeRule.waitForIdle()
        showing = true
        composeRule.waitForIdle()
        assertSelection(TextRange(5))
    }

    @Test
    fun typing_goes_where_the_cursor_is_and_moving_it_does_not_search() {
        show("kings")
        composeRule.onNodeWithTag("stationSearchField").performTextInputSelection(TextRange(2))
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), queries)
        composeRule.onNodeWithTag("stationSearchField").performTextInput("x")
        composeRule.waitForIdle()
        assertEquals("kixngs", query)
        assertSelection(TextRange(3))
    }

    @Test
    fun query_field_value_puts_the_cursor_at_the_end() {
        assertEquals(TextRange(5), queryFieldValue("kings").selection)
        assertEquals(TextRange(0), queryFieldValue("").selection)
    }
}
