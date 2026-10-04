package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
// Real text layout, so a long header wraps as it would on a phone.
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class CardMoveTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-04T08:00:00Z")

    private fun card(line: String, inMinutes: Long?) = listOf(
        TripTiming.Estimate(
            TripRoute(listOf(TripLeg("bus", line, line, "A$line", "A$line", "B$line", "B$line", t, t.plusSeconds((inMinutes ?: 0) * 60)))),
            if (inMinutes == null) TripTiming.Basis.UNKNOWN else TripTiming.Basis.LIVE,
            inMinutes?.let { t.plusSeconds(it * 60) }, emptyList(), false, t,
        ),
    )

    @Test
    fun a_header_coming_or_going_is_a_move_though_the_order_holds() {
        val first = listedCards(listOf(card("1", 10), card("2", 12)), null)
        // The first card's arrival withheld: the order is the same, its header isn't.
        val withheld = listedCards(listOf(card("1", null), card("2", 12)), first.keys)
        assertEquals(first.keys.keys, withheld.keys.keys)
        assertNotSame(first.keys, withheld.keys)
    }

    @Test
    fun an_order_that_holds_keeps_its_list_and_one_that_moves_is_new() {
        val first = listedCards(listOf(card("1", 10), card("2", 12)), null)
        val again = listedCards(listOf(card("1", 9), card("2", 11)), first.keys)
        assertSame(first.keys, again.keys)
        val swapped = listedCards(listOf(card("2", 8), card("1", 9)), again.keys)
        assertNotSame(again.keys, swapped.keys)
        assertEquals(again.keys.keys.reversed(), swapped.keys.keys)
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
    fun a_card_whose_header_grows_a_line_slides_rather_than_jumps() {
        val header = mutableStateOf(listOf(app.stopdash.domain.RouteLabel.FASTEST))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            // Narrow, so three labels wrap onto a second line.
            androidx.compose.foundation.layout.Column(Modifier.width(96.dp)) {
                CardHeader(header.value)
                Text("body")
            }
        }
        composeRule.waitForIdle()
        val top = composeRule.onNodeWithText("body").getUnclippedBoundsInRoot().top
        header.value = listOf(
            app.stopdash.domain.RouteLabel.FASTEST, app.stopdash.domain.RouteLabel.SIMPLEST, app.stopdash.domain.RouteLabel.LEAST_WALKING,
        )
        halfASlide()
        val midway = composeRule.onNodeWithText("body").getUnclippedBoundsInRoot().top
        repeat(3) { halfASlide() }
        val settled = composeRule.onNodeWithText("body").getUnclippedBoundsInRoot().top
        // The header still shows, only longer: the card slides by its extra height (Codex, #543).
        assert(midway > top && midway < settled) { "midway $midway between $top and $settled" }
    }

    @Test
    fun a_tap_while_a_card_slides_is_dropped_and_one_after_it_settles_lands() {
        val above = mutableStateOf(false)
        var taps = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                // Something coming in over the card, as a re-plan's note or a header does: it grows
                // over a slide's time, pushing the card down as it goes.
                androidx.compose.animation.AnimatedVisibility(
                    above.value,
                    enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(CARD_MOVE_MILLIS.toInt())),
                ) { androidx.compose.foundation.layout.Box(Modifier.height(40.dp)) }
                val sliding = rememberSliding()
                Text("card", Modifier.sliding(sliding).clickable(enabled = !sliding.moving) { taps++ })
            }
        }
        composeRule.waitForIdle()
        // Still: a tap lands.
        composeRule.onNodeWithText("card").performClick()
        assertEquals(1, taps)
        above.value = true
        halfASlide()
        // Moving under the finger: dropped (Codex, #543), whatever moved it.
        composeRule.onNodeWithText("card").performClick()
        assertEquals(1, taps)
        repeat(3) { halfASlide() }
        composeRule.onNodeWithText("card").performClick()
        assertEquals(2, taps)
    }

    @Test
    fun a_tap_on_a_card_sliding_to_its_new_order_is_dropped() {
        val order = mutableStateOf(listOf("a", "b"))
        var taps = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            androidx.compose.foundation.lazy.LazyColumn {
                items(order.value.size, key = { order.value[it] }) { index ->
                    val key = order.value[index]
                    val sliding = rememberSliding()
                    Text(
                        key,
                        Modifier
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = androidx.compose.animation.core.tween(CARD_MOVE_MILLIS.toInt()))
                            .height(48.dp)
                            .sliding(sliding)
                            .clickable(enabled = !sliding.moving) { if (key == "a") taps++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("a").performClick()
        assertEquals(1, taps)
        // Re-sorted: "a" slides down to its new place, and a tap on it on the way is dropped.
        order.value = listOf("b", "a")
        halfASlide()
        composeRule.onNodeWithText("a").performClick()
        assertEquals(1, taps)
        repeat(3) { halfASlide() }
        composeRule.onNodeWithText("a").performClick()
        assertEquals(2, taps)
    }
}
