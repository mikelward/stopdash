package app.stopdash.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.stopdash.domain.TripLeg
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The home screen's disruptions row keeps to one line, counting only the pills it leaves out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class HomeDisruptionsRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun leg(id: String) = TripLeg("bus", id, id, "", "", "", "", Instant.EPOCH, Instant.EPOCH)

    @Test
    fun a_word_alone_is_never_counted_and_the_row_is_heard_whole() {
        var row by mutableStateOf(TripRow(checking = true))
        composeRule.setContent { HomeDisruptionsRow(row) }
        composeRule.onNodeWithContentDescription("Disruptions: Checking…").assertExists()
        val lines = (1..50).map { leg("$it") }
        row = TripRow(checking = false, unknown = true, unknownLines = lines)
        // Every line named, not only the pills that fit.
        composeRule.onNodeWithContentDescription("Disruptions: Unknown: ${(1..50).joinToString(", ")}").assertExists()
    }

    @Test
    fun something_unchecked_with_no_line_to_name_still_reads_unknown() {
        composeRule.setContent { HomeDisruptionsRow(TripRow(checking = false, unknown = true, unknownStops = "Bank")) }
        composeRule.onNodeWithContentDescription("Disruptions: Unknown").assertExists()
    }

    @Test
    fun only_the_pills_that_fit_are_drawn_and_the_rest_counted() {
        val composed = mutableSetOf<Int>()
        val placed = mutableSetOf<Int>()
        var counted = -1
        composeRule.setContent {
            OneLine(
                pills = 50,
                pill = { i -> composed += i; Text("pill $i", Modifier.width(80.dp).onGloballyPositioned { placed += i }) },
                labelAt = null,
                label = {},
                word = { Text("Checking…") },
            ) { more -> Text("+$more", Modifier.onGloballyPositioned { counted = more }) }
        }
        composeRule.waitForIdle()
        // Composed only until the line is full, never all fifty (Codex, #577).
        assertTrue("composed $composed placed $placed", composed.size <= placed.size + 2 && composed.size < 50)
        // The count is of the pills left out alone: the word is never counted.
        assertTrue("placed $placed counted $counted", placed.isNotEmpty())
        assertEquals(50, counted + placed.size)
    }
}
