package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.stopdash.domain.LegTimetables
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A shown trip asks for its timetables each minute, and stops in the background or off screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class WantTimetablesTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class FakeOwner : LifecycleOwner {
        // createUnsafe: no main-thread assertion, so the test drives states directly.
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val key = LegTimetables.Key("northern", "940GZZLUEUS", "940GZZLUKSX")

    @Test
    fun `asks each minute while shown, nothing in the background or off screen`() {
        val owner = FakeOwner()
        val asked = mutableListOf<Set<LegTimetables.Key>>()
        var shown by mutableStateOf(true)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (shown) WantTimetables(setOf(key)) { asked += it }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(1, asked.size)
        composeRule.mainClock.advanceTimeBy(TIMETABLES_ASK_EVERY.toMillis())
        assertEquals(2, asked.size)

        // In the background: no more asks.
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.mainClock.advanceTimeBy(TIMETABLES_ASK_EVERY.toMillis() * 3)
        assertEquals(2, asked.size)

        // Back in the foreground: asked at once, then each minute.
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(3, asked.size)

        // Off screen: no more asks.
        composeRule.runOnUiThread {
            shown = false
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(TIMETABLES_ASK_EVERY.toMillis() * 3)
        assertEquals(3, asked.size)
        assertEquals(setOf(setOf(key)), asked.toSet())
    }

    @Test
    fun `asks nothing until the frame is worked out`() {
        val owner = FakeOwner()
        val asked = mutableListOf<Set<LegTimetables.Key>>()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WantTimetables(null) { asked += it }
            }
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<Set<LegTimetables.Key>>(), asked)
    }
}
