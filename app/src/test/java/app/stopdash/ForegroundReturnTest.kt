package app.stopdash

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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The foreground-return relocate wiring across the Settings/Licenses overlay switch (SPEC *Finding
 * stops* / D6). Drives the real production host [NearbyArea] with the real [ForegroundReturnLatcher]
 * in its `aboveOverlay` slot and [ConsumeForegroundReturn] in its `body`, through a fake lifecycle.
 * Toggling `overlayOpen` is what removes the consumer (the departures view) from composition while
 * keeping the observer composed — the exact topology the fix depends on. A return that lands while
 * the overlay is open must still re-locate once it closes; moving the observer into `body` (the
 * pre-fix bug) would leave it out of composition during the return, so the post-close assertion
 * would fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForegroundReturnTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class FakeOwner : LifecycleOwner {
        // createUnsafe: no main-thread assertion, so the test drives states directly.
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `a return while an overlay is open relocates once the overlay closes`() {
        val owner = FakeOwner()
        var pending by mutableStateOf(false)
        var overlayOpen by mutableStateOf(false)
        val ready = true
        var relocates = 0

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                NearbyArea(
                    overlayOpen = overlayOpen,
                    // Above the overlay switch — stays composed while an overlay is open.
                    aboveOverlay = {
                        ForegroundReturnLatcher(
                            isReady = { ready },
                            isBusy = { false },
                            onReturn = { pending = true },
                        )
                    },
                    overlayContent = {},
                    // The departures view — out of composition while an overlay is open.
                    body = {
                        ConsumeForegroundReturn(
                            pending = pending,
                            isBusy = { false },
                            onConsumed = { pending = false },
                            onRelocate = { relocates++ },
                        )
                    },
                )
            }
        }

        // Initial start is skipped (the ViewModel init load covers it).
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.waitForIdle()
        assertEquals(0, relocates)

        // Open an overlay, then background and return to the foreground while it's still open.
        overlayOpen = true
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        composeRule.waitForIdle()
        assertEquals("no relocate while the departures view is out of composition", 0, relocates)

        // Close the overlay: the departures view re-enters and consumes the latched return.
        overlayOpen = false
        composeRule.waitForIdle()
        assertEquals("re-entering after a return relocates", 1, relocates)
    }

    @Test
    fun `a return before the set is ready does not latch`() {
        // A return before the near-me set resolves (the gate is still up, e.g. a permission grant via
        // Settings) is the gate's own locate() — latching here would double-fetch after the grant.
        val owner = FakeOwner()
        var pending by mutableStateOf(false)
        val ready = false
        // Whether the gate is owed a fresh look is the view model's to say
        // ([NearbyStopsViewModel.locateAfterLeftBehind]): each such return is handed to it.
        var withoutSet = 0

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ForegroundReturnLatcher(
                    isReady = { ready },
                    isBusy = { false },
                    onReturn = { pending = true },
                    onReturnWithoutSet = { withoutSet++ },
                )
            }
        }

        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.waitForIdle()
        assertEquals("the initial start isn't a return", 0, withoutSet)
        composeRule.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        composeRule.waitForIdle()
        assertEquals("a return before Ready must not latch", false, pending)
        assertEquals(1, withoutSet)
    }
}
