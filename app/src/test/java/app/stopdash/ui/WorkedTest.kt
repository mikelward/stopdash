package app.stopdash.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class WorkedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun a_held_stage_works_nothing_out_though_a_newer_answer_lands_before_its_effect_runs() {
        // A stage held ([rememberWorked]'s hold) reads the key its slot holds, "before". A run already under way lands
        // a newer answer between that composition and its effect, as a trip page's frame did under load: the held
        // stage starts no run of its own for "before", which would be worked out from this composition's inputs and
        // land over the newer answer (a frame of no routes, the list flashing "Checking trips…").
        val slot = mutableStateOf<Worked<String, String?>?>(Worked("before", "before's"))
        val runs = AtomicInteger()
        composeRule.setContent {
            // Inline, so a run would land at once.
            CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                // After this composition read the slot, before the stage's own effect runs (effects run in the order
                // they're composed): what a run under way landing looks like here.
                LaunchedEffect(Unit) { slot.value = Worked("newer", "newer's") }
                val shown = rememberWorked(slot, "wanted", keep = { _, _ -> true }, hold = true) {
                    runs.incrementAndGet()
                    "worked out while held"
                }
                Text(shown.orEmpty())
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, runs.get())
        assertEquals("newer", slot.value?.key)
        composeRule.onNodeWithText("newer's").assertExists()
    }

    @Test
    fun a_stage_held_then_let_go_works_out_what_is_wanted() {
        // Held, the last answer stands in; let go, the wanted key is worked out, once.
        val slot = mutableStateOf<Worked<String, String?>?>(Worked("before", "before's"))
        val runs = AtomicInteger()
        val hold = mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                val shown = rememberWorked(slot, "wanted", keep = { _, _ -> true }, hold = hold.value) {
                    runs.incrementAndGet()
                    "wanted's"
                }
                Text(shown.orEmpty())
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("before's").assertExists()
        assertEquals(0, runs.get())
        hold.value = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("wanted's").assertExists()
        assertEquals(1, runs.get())
    }
}
