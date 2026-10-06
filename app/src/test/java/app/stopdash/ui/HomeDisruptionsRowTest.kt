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
import androidx.compose.ui.layout.boundsInParent
import org.junit.Assert.assertFalse
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
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
    fun pills_going_away_on_a_refresh_never_draw_one_that_is_gone() {
        // A refresh that clears the pills recomposes the old pills' slots before the row is measured
        // again: each must cope with an index past the new lists, not crash the app.
        var row by mutableStateOf(TripRow(checking = false, lines = listOf(leg("1")), unknown = true, unknownLines = listOf(leg("2"))))
        composeRule.setContent { HomeDisruptionsRow(row) }
        composeRule.waitForIdle()
        row = TripRow(checking = true)
        composeRule.onNodeWithContentDescription("Disruptions: Checking…").assertExists()
        row = TripRow(checking = false, lines = listOf(leg("3")))
        composeRule.onNodeWithContentDescription("Disruptions: 3").assertExists()
        row = TripRow(checking = false)
        composeRule.onNodeWithContentDescription("Disruptions: None").assertExists()
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

    @Test
    fun the_word_stands_where_the_lead_does_not_fit_beside_it() {
        var wordRight = -1f
        var led = false
        var rowWidth = -1
        composeRule.setContent {
            Box(Modifier.width(120.dp).onGloballyPositioned { rowWidth = it.size.width }) {
                OneLine(
                    pills = 0,
                    pill = {},
                    labelAt = null,
                    label = {},
                    word = { Box(Modifier.width(60.dp).height(10.dp).onGloballyPositioned { wordRight = it.boundsInParent().right }) },
                    modifier = Modifier.fillMaxWidth(),
                    lead = { Box(Modifier.width(100.dp).height(10.dp).onGloballyPositioned { led = true }) },
                ) {}
            }
        }
        composeRule.waitForIdle()
        // A lead as wide as a large font makes "Disruptions:" leaves the word its room: it's left out.
        assertFalse(led)
        assertTrue("word ends at $wordRight of $rowWidth", wordRight in 0f..rowWidth.toFloat())
    }

    @Test
    fun the_count_stands_where_the_lead_would_leave_no_room_for_it() {
        var led = false
        var counted = false
        composeRule.setContent {
            Box(Modifier.width(120.dp)) {
                OneLine(
                    pills = 2,
                    pill = { Box(Modifier.width(70.dp).height(10.dp)) },
                    labelAt = null,
                    label = {},
                    word = null,
                    modifier = Modifier.fillMaxWidth(),
                    lead = { Box(Modifier.width(110.dp).height(10.dp).onGloballyPositioned { led = true }) },
                ) { Box(Modifier.width(30.dp).height(10.dp).onGloballyPositioned { counted = true }) }
            }
        }
        composeRule.waitForIdle()
        // The lead would leave no room even for "+N": it goes, and the disruptions are still said.
        assertFalse(led)
        assertTrue(counted)
    }

    @Test
    fun an_item_wider_than_the_line_is_counted_not_clipped() {
        var drawn = false
        var counted = false
        composeRule.setContent {
            Box(Modifier.width(120.dp)) {
                OneLine(
                    pills = 1,
                    // A stop list too long for the line.
                    pill = { Box(Modifier.width(300.dp).height(10.dp).onGloballyPositioned { drawn = true }) },
                    labelAt = null,
                    label = {},
                    word = null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Box(Modifier.width(30.dp).height(10.dp).onGloballyPositioned { counted = true }) }
            }
        }
        composeRule.waitForIdle()
        assertFalse(drawn)
        assertTrue(counted)
    }

    @Test(timeout = 10_000)
    fun a_row_with_no_pills_and_a_word_wider_than_it_lays_out() {
        var laidOut = false
        composeRule.setContent {
            Box(Modifier.width(40.dp)) {
                OneLine(
                    pills = 0,
                    pill = {},
                    labelAt = null,
                    label = {},
                    word = { Box(Modifier.width(60.dp).height(10.dp).onGloballyPositioned { laidOut = true }) },
                    modifier = Modifier.fillMaxWidth(),
                ) {}
            }
        }
        composeRule.waitForIdle()
        // It used to count down past zero forever on the main thread.
        assertTrue(laidOut)
    }

    @Test
    fun the_word_stands_where_not_even_the_count_fits_beside_it() {
        var wordRight = -1f
        var counted = false
        var rowWidth = -1
        composeRule.setContent {
            Box(Modifier.width(80.dp).onGloballyPositioned { rowWidth = it.size.width }) {
                OneLine(
                    pills = 3,
                    pill = { Box(Modifier.width(50.dp).height(10.dp)) },
                    labelAt = null,
                    label = {},
                    word = { Box(Modifier.width(60.dp).height(10.dp).onGloballyPositioned { wordRight = it.boundsInParent().right }) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Box(Modifier.width(40.dp).height(10.dp).onGloballyPositioned { counted = true }) }
            }
        }
        composeRule.waitForIdle()
        // No pill fits beside the word, nor the count: the word shows whole, inside the row.
        assertFalse(counted)
        assertTrue("word ends at $wordRight of $rowWidth", wordRight in 0f..rowWidth.toFloat())
    }
}
