package app.stopdash

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavBackStack
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
 * The foreground-return relocate wiring across a page opened over the near-me view (SPEC *Finding
 * stops* / D6). Drives the real production host [NearbyArea] with the real [ForegroundReturnLatcher]
 * in its `aboveOverlay` slot and [ConsumeForegroundReturn] in its `body`, through a fake lifecycle.
 * Opening Settings is what removes the consumer (the departures view) from composition while
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
        val pages = Pages(NavBackStack(Page.NearMe(0)))
        var overlayOpen by pages.flag(Page.Settings)
        val ready = true
        var relocates = 0

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                NearbyArea(
                    pages = pages,
                    // Above the back stack — stays composed while a page is open over the near-me view.
                    aboveOverlay = {
                        ForegroundReturnLatcher(
                            isReady = { ready },
                            isBusy = { false },
                            onReturn = { pending = true },
                        )
                    },
                    overlayContent = {},
                    // The departures view — out of composition while a page is open over it.
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
    fun `a return to an observer rebuilt while away still latches`() {
        // The activity rebuilt as the app comes back (a setting changed while it was away) composes a new
        // observer, whose first start is that return: the mark of having left is retained, not counted
        // from the observer's own starts (a bug report, 2026-10-16).
        val owner = FakeOwner()
        var pending by mutableStateOf(false)
        var composed by mutableStateOf(true)

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (composed) {
                    ForegroundReturnLatcher(
                        isReady = { true },
                        isBusy = { false },
                        onReturn = { pending = true },
                    )
                }
            }
        }

        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.waitForIdle()
        assertEquals("the initial start isn't a return", false, pending)
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitForIdle()
        // Rebuilt while away: the old observer goes and a new one comes.
        composed = false
        composeRule.waitForIdle()
        composed = true
        composeRule.waitForIdle()
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.waitForIdle()
        assertEquals("the return latches on the rebuilt observer", true, pending)
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
