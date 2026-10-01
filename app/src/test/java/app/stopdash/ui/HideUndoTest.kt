package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An Undo offer outlives the screen that made it, briefly: hiding the only mode a trip starts from
 * ends the trip, and the screen landed on offers the Undo instead (SPEC *Finding stops → Hiding a
 * mode*). Two stand-in screens share one [HideUndoCarrier], as the app's screens do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class HideUndoTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var clock = 0L
    private val carrier = HideUndoCarrier(now = { clock })
    private var onTrip by mutableStateOf(true)
    private var tripShown: String? = null
    private var listShown: String? = null

    @Composable
    private fun Screen(name: String, onHide: (String) -> Unit, onUnhide: (String) -> Unit) {
        val host = remember { SnackbarHostState() }
        val hide = rememberHideWithUndo(onHide, onUnhide, host)
        Scaffold(snackbarHost = { SnackbarHost(host) }) { padding ->
            TextButton(onClick = { hide?.invoke("tube") }, modifier = Modifier.padding(padding)) { Text("Hide on $name") }
        }
    }

    /** The trip's hide closes it when [closesTrip], as hiding its last origin's mode does. */
    private fun show(closesTrip: Boolean) {
        composeRule.setContent {
            CompositionLocalProvider(LocalHideUndoCarrier provides carrier) {
                StopDashTheme {
                    if (onTrip) {
                        Screen("trip", onHide = { if (closesTrip) onTrip = false }, onUnhide = { tripShown = it })
                    } else {
                        Screen("list", onHide = {}, onUnhide = { listShown = it })
                    }
                }
            }
        }
    }

    @Test
    fun `a hide that closes its screen is offered Undo on the screen landed on`() {
        show(closesTrip = true)
        composeRule.onNodeWithText("Hide on trip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hide on list").assertExists()
        composeRule.onNodeWithText("Tube & DLR hidden").assertExists()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals("tube", listShown)
        assertNull(tripShown)
    }

    @Test
    fun `an offer answered on its own screen isn't offered again`() {
        show(closesTrip = false)
        composeRule.onNodeWithText("Hide on trip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals("tube", tripShown)
        onTrip = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hide on list").assertExists()
        composeRule.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun `an offer left behind long ago isn't put back on a screen come back to later`() {
        show(closesTrip = false)
        composeRule.onNodeWithText("Hide on trip").performClick()
        composeRule.waitForIdle()
        // The rider leaves the trip well after the snackbar's own few seconds.
        clock += 10_000
        onTrip = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hide on list").assertExists()
        composeRule.onNodeWithText("Undo").assertDoesNotExist()
        assertNull(listShown)
    }
}
