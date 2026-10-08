package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.ModeGroups
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The overflow menu's mode checkboxes (SPEC *Finding stops → Hiding a mode*): the same six groups
 * wherever the user is, ticked when shown, a tap reporting the group and its new state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModeMenuTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    @Test
    fun `the overflow menu lists each mode group, ticked when shown, and toggles one`() {
        var toggled: Pair<String, Boolean>? = null
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loading,
                    now = now,
                    onRefresh = {},
                    hiddenModes = setOf("overground", "national-rail", "international-rail"),
                    onSetModeGroupShown = { group, shown -> toggled = group.key to shown },
                )
            }
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        for (name in listOf("Underground", "Bus", "Tram", "Boat")) {
            composeRule.onNodeWithText(name).assertIsOn()
        }
        // What a group's name doesn't say is under it, kept short.
        composeRule.onNodeWithText("Tube, DLR, Elizabeth").assertExists()
        composeRule.onNodeWithText("Thameslink, Southern, …").assertExists()
        composeRule.onNodeWithText("Overground").assertIsOff()
        composeRule.onNodeWithText("National Rail").assertIsOff().performClick()
        assertEquals("rail" to true, toggled)
        composeRule.onNodeWithText("Bus").performClick()
        assertEquals("bus" to false, toggled)
        assertEquals(6, ModeGroups.ALL.size)
        composeRule.onNodeWithText("Coach").assertDoesNotExist()
    }

    @Test
    fun `the hide item names every group mid-sentence, keeping the Tube and DLR capitalized`() {
        assertEquals(
            listOf("Underground", "Overground", "National Rail", "bus", "tram", "boat"),
            ModeGroups.ALL.map { groupNameInSentence(it) },
        )
    }
}
