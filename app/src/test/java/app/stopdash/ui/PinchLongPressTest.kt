package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import app.stopdash.domain.FontSizeSettings
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two fingers down are a pinch to resize the text (SPEC *Display size*), never a long press on what the first one
 * landed on (maintainer, 2026-10-24: "add favorite" and "hide" menus opened mid-pinch): [unlessPinch] and
 * [unlessPinchAt] let a long press through only while no second finger has come down in the gesture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class PinchLongPressTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var clicked = 0
    private var tapped = 0

    private fun show() {
        composeRule.setContent {
            StopDashTheme(fontSize = FontSizeState(FontSizeSettings(), onScaleSettled = {}, onPinchEnabledChange = {})) {
                Column {
                    // A row as a menu-opening clickable is ([combinedClickable]), and as a tap detector is.
                    Box(Modifier.size(200.dp).testTag("clickable").combinedClickable(onClick = {}, onLongClick = unlessPinch { clicked++ }))
                    Box(Modifier.size(200.dp).testTag("taps").pointerInput(Unit) { detectTapGestures(onLongPress = unlessPinchAt { tapped++ }) })
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun longPressWith(tag: String, fingers: Int) {
        composeRule.onNodeWithTag(tag).performTouchInput {
            down(0, center)
            // The second finger a moment after the first, as a pinch lands, well before the long press's timeout.
            if (fingers > 1) {
                advanceEventTime(50)
                down(1, center + Offset(40f, 0f))
            }
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            if (fingers > 1) up(1)
            up(0)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `one finger held opens its menu`() {
        show()
        longPressWith("clickable", fingers = 1)
        longPressWith("taps", fingers = 1)
        assertEquals(1, clicked)
        assertEquals(1, tapped)
    }

    @Test
    fun `two fingers down open nothing`() {
        show()
        longPressWith("clickable", fingers = 2)
        longPressWith("taps", fingers = 2)
        assertEquals(0, clicked)
        assertEquals(0, tapped)
    }

    @Test
    fun `a screen reader's long press after a pinch still opens its menu`() {
        show()
        longPressWith("clickable", fingers = 2)
        composeRule.onNodeWithTag("clickable").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, clicked)
    }
}
