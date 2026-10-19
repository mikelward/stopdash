package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** A trip's timetable asks stop while the app is in the background or the trip is off screen. */
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
    fun `asks while shown, nothing in the background, and again on return`() {
        val owner = FakeOwner()
        val asked = mutableListOf<Set<LegTimetables.Key>>()
        var shown by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (shown) WantTimetables(setOf(key)) { asked += it }
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(setOf(key)), asked)

        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitForIdle()
        assertEquals(listOf(setOf(key), emptySet()), asked)

        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()
        assertEquals(listOf(setOf(key), emptySet(), setOf(key)), asked)

        // Off screen: nothing wanted.
        shown = false
        composeRule.waitForIdle()
        assertEquals(emptySet<LegTimetables.Key>(), asked.last())
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
