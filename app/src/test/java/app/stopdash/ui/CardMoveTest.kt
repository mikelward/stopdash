package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The trip list re-sorting as times move: a new order is told apart by identity, and taps wait out the slide. Synthetic stops; bus lines only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class CardMoveTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-04T08:00:00Z")

    private fun card(line: String, inMinutes: Long) = listOf(
        TripTiming.Estimate(
            TripRoute(listOf(TripLeg("bus", line, line, "A$line", "A$line", "B$line", "B$line", t, t.plusSeconds(inMinutes * 60)))),
            TripTiming.Basis.LIVE, t.plusSeconds(inMinutes * 60), emptyList(), false, t,
        ),
    )

    @Test
    fun an_order_that_holds_keeps_its_list_and_one_that_moves_is_new() {
        val first = listedCards(listOf(card("1", 10), card("2", 12)), null)
        val again = listedCards(listOf(card("1", 9), card("2", 11)), first.keys)
        assertSame(first.keys, again.keys)
        val swapped = listedCards(listOf(card("2", 8), card("1", 9)), again.keys)
        assertNotSame(again.keys, swapped.keys)
        assertEquals(again.keys.reversed(), swapped.keys)
    }

    // Frame by frame: a single jump of the clock would only start the animation, at its far end.
    private fun halfASlide() = repeat((CARD_MOVE_MILLIS / 2 / 16).toInt()) {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    @Test
    fun a_card_gaining_its_header_slides_down_rather_than_jumps() {
        val header = mutableStateOf(emptyList<app.stopdash.domain.RouteLabel>())
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                CardHeader(header.value)
                Text("body")
            }
        }
        composeRule.waitForIdle()
        val top = composeRule.onNodeWithText("body").getUnclippedBoundsInRoot().top
        // The card keeps its place in the list; only its header comes. It grows in, so the card
        // moves part of the way within the slide's time, not all at once (Codex, #543).
        header.value = listOf(app.stopdash.domain.RouteLabel.FASTEST)
        halfASlide()
        val midway = composeRule.onNodeWithText("body").getUnclippedBoundsInRoot().top
        composeRule.mainClock.advanceTimeBy(CARD_MOVE_MILLIS)
        val settled = composeRule.onNodeWithText("body").getUnclippedBoundsInRoot().top
        assert(midway > top && midway < settled) { "midway $midway between $top and $settled" }
        // And back: it shrinks away the same way.
        header.value = emptyList()
        halfASlide()
        val leaving = composeRule.onNodeWithText("body").getUnclippedBoundsInRoot().top
        composeRule.mainClock.advanceTimeBy(CARD_MOVE_MILLIS)
        assert(leaving > top && leaving < settled) { "leaving $leaving between $top and $settled" }
        assertEquals(top, composeRule.onNodeWithText("body").getUnclippedBoundsInRoot().top)
    }

    @Test
    fun a_tap_while_the_cards_slide_is_dropped_and_one_after_lands() {
        val order = mutableStateOf(listOf("a", "b"))
        var taps = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val moving = rememberMoving(order.value)
            Text("card", Modifier.clickable(enabled = !moving) { taps++ })
        }
        composeRule.waitForIdle()
        // The first order arrives with the list: nothing moved, so a tap lands.
        composeRule.onNodeWithText("card").performClick()
        assertEquals(1, taps)
        order.value = listOf("b", "a")
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("card").performClick()
        assertEquals(1, taps)
        composeRule.mainClock.advanceTimeBy(CARD_MOVE_MILLIS + 100)
        composeRule.onNodeWithText("card").performClick()
        assertEquals(2, taps)
    }
}
