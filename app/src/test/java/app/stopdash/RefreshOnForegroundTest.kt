package app.stopdash

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The refresh-on-open state machine (SPEC D6): [refreshOnForeground] must refresh on a
 * return to the foreground but never on the initial start or a configuration change —
 * both of which are covered by the ViewModel's own initial load, so refreshing there
 * would double-fetch. Driven by a plain [LifecycleRegistry] on a test dispatcher, so the
 * lifecycle transitions (initial start, background→foreground, recreation) are asserted
 * without a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshOnForegroundTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeOwner : LifecycleOwner {
        // createUnsafe: no main-thread assertion, so the test drives states directly.
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `refreshes on a foreground return but not on the initial start`() = runTest(dispatcher) {
        val owner = FakeOwner()
        var refreshes = 0
        val job = launch { refreshOnForeground(owner.lifecycle) { refreshes++ } }

        owner.registry.currentState = Lifecycle.State.STARTED // initial start
        advanceUntilIdle()
        assertEquals("first foreground is the init load — must be skipped", 0, refreshes)

        owner.registry.currentState = Lifecycle.State.CREATED // backgrounded
        owner.registry.currentState = Lifecycle.State.STARTED // returned to the foreground
        advanceUntilIdle()
        assertEquals("a return from the background refreshes", 1, refreshes)

        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.currentState = Lifecycle.State.STARTED // and again
        advanceUntilIdle()
        assertEquals("every subsequent return refreshes", 2, refreshes)

        job.cancel()
    }

    @Test
    fun `a foreground return during a relocation is gated, and resumes once it clears`() = runTest(dispatcher) {
        // RefreshOnForeground passes isBusy = { relocating.value }: a return to the foreground
        // while a manual re-locate's fix is in flight must not refresh the current (soon-to-be-
        // previous) set and save it as fresh before the fix resolves (Codex).
        val owner = FakeOwner()
        var refreshes = 0
        var relocating = true
        val job = launch { refreshOnForeground(owner.lifecycle, isBusy = { relocating }) { refreshes++ } }

        owner.registry.currentState = Lifecycle.State.STARTED // initial start (always skipped)
        advanceUntilIdle()
        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.currentState = Lifecycle.State.STARTED // return while relocating
        advanceUntilIdle()
        assertEquals("a return during a relocate is gated", 0, refreshes)

        // Once the relocate clears, a later return refreshes normally.
        relocating = false
        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        assertEquals("returns refresh again once the relocate has settled", 1, refreshes)

        job.cancel()
    }

    @Test
    fun `each time the app leaves the foreground is reported, but not the observer going away`() = runTest(dispatcher) {
        // The near-me list marks a relocation still under way as from before the app left, so the
        // return re-locates over it rather than waiting on it (Codex on #220).
        val owner = FakeOwner()
        var left = 0
        val job = launch { refreshOnForeground(owner.lifecycle, onBackground = { left++ }) {} }

        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        assertEquals(0, left)
        owner.registry.currentState = Lifecycle.State.CREATED
        advanceUntilIdle()
        assertEquals(1, left)
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.CREATED
        advanceUntilIdle()
        assertEquals(2, left)

        // Its host leaving while the app is in the foreground isn't the app leaving.
        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertEquals(2, left)
    }

    @Test
    fun `a recreated activity skips its own first start`() = runTest(dispatcher) {
        // A configuration change restarts refreshOnForeground fresh; its first STARTED is
        // the recreated first frame and must not fetch again (the ViewModel survived).
        val owner = FakeOwner()
        var refreshes = 0
        val job = launch { refreshOnForeground(owner.lifecycle) { refreshes++ } }

        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        assertEquals(0, refreshes)

        job.cancel()
    }
}
