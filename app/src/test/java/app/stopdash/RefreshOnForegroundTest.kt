package app.stopdash

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * The refresh-on-open state machine (SPEC D6): [refreshOnForeground] must refresh on a
 * return to the foreground, the activity rebuilt while away included, but never on the initial
 * start or a rotation — both of which are covered by the ViewModel's own initial load, so
 * refreshing there would double-fetch. Driven by a plain [LifecycleRegistry] on a test dispatcher, so the
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
    fun `a rotation does not refresh the rebuilt activity`() = runTest(dispatcher) {
        // A configuration change in the foreground stops the activity for the change and starts a new
        // one, observed afresh: its first STARTED is the recreated first frame and must not fetch again
        // (the ViewModel survived, and no time passed).
        val away = ForegroundAway()
        var rotating = false
        var refreshes = 0
        val before = FakeOwner()
        val first = launch {
            refreshOnForeground(before.lifecycle, away = away, changingConfigurations = { rotating }, worker = dispatcher) { refreshes++ }
        }
        before.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        rotating = true
        before.registry.currentState = Lifecycle.State.CREATED
        advanceUntilIdle()
        first.cancel()

        val after = FakeOwner()
        val second = launch {
            refreshOnForeground(after.lifecycle, away = away, changingConfigurations = { false }, worker = dispatcher) { refreshes++ }
        }
        after.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        assertEquals("a rotation isn't a return", 0, refreshes)

        second.cancel()
    }

    @Test
    fun `a return to an activity rebuilt while away refreshes`() = runTest(dispatcher) {
        // A setting changed while the app was away (dark theme, say) rebuilds the activity only as it
        // comes back, so the return is the new observer's first start. It was taken for the initial one
        // and skipped, leaving the list on its old times until the timer's first tick a minute later
        // (a bug report, 2026-10-16).
        val away = ForegroundAway()
        val lines = mutableListOf<String>()
        var refreshes = 0
        val before = FakeOwner()
        val first = launch { refreshOnForeground(before.lifecycle, away = away, worker = dispatcher) { refreshes++ } }
        before.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        // The app leaves; the activity is rebuilt as it comes back.
        before.registry.currentState = Lifecycle.State.CREATED
        advanceUntilIdle()
        first.cancel()

        val after = FakeOwner()
        val second = launch {
            refreshOnForeground(after.lifecycle, away = away, log = { lines += it }, worker = dispatcher) { refreshes++ }
        }
        after.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        assertEquals("the return refreshes the rebuilt activity", 1, refreshes)
        assertEquals(listOf("back in front, to a screen rebuilt while away: refreshing"), lines)

        // And it's taken once: a later start the app didn't leave for isn't another.
        after.registry.currentState = Lifecycle.State.CREATED
        after.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        assertEquals(2, refreshes)
        assertEquals("back in front: refreshing", lines.last())

        second.cancel()
    }

    @Test
    fun `a return is taken on the main thread and logged off it`() {
        // A log line is I/O, never the main thread's (AGENTS.md *Main thread*): driven from a real
        // single-thread main, with the production worker, so a return that wrote its line where it
        // was taken would show here.
        val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        try {
            runBlocking {
                val mainThread = withContext(main) { Thread.currentThread() }
                val owner = FakeOwner()
                val takenOn = CopyOnWriteArrayList<Thread>()
                val loggedOn = CopyOnWriteArrayList<Thread>()
                val logged = CompletableDeferred<Unit>()
                val job = launch(main) {
                    refreshOnForeground(owner.lifecycle, log = {
                        loggedOn += Thread.currentThread()
                        logged.complete(Unit)
                    }) { takenOn += Thread.currentThread() }
                }
                withContext(main) { owner.registry.currentState = Lifecycle.State.STARTED }
                withContext(main) {
                    owner.registry.currentState = Lifecycle.State.CREATED
                    owner.registry.currentState = Lifecycle.State.STARTED
                }
                withTimeout(10_000) { logged.await() }
                job.cancelAndJoin()
                assertEquals(listOf(mainThread), takenOn.toList())
                assertEquals(1, loggedOn.size)
                assertNotEquals(mainThread, loggedOn.single())
            }
        } finally {
            main.close()
        }
    }

    @Test
    fun `a return gated by a relocation says so`() = runTest(dispatcher) {
        val owner = FakeOwner()
        val lines = mutableListOf<String>()
        val job = launch {
            refreshOnForeground(owner.lifecycle, isBusy = { true }, log = { lines += it }, worker = dispatcher) {}
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        assertEquals("the first start says nothing: it isn't a return", emptyList<String>(), lines)
        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        assertEquals(listOf("back in front: a relocation started since is under way"), lines)

        job.cancel()
    }
}
