package app.stopdash

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

/**
 * A worker on a real thread named [name], for a test that checks work runs off the caller's.
 * [close] stops it only once nothing is left to hop between it and the test's queue: shutting it
 * down sooner rejects a hop still to come (a cleared model's work that settles whatever happens, under
 * `NonCancellable`), which kotlinx reruns on [kotlinx.coroutines.Dispatchers.IO], where nothing waits
 * for it, so it can reach Main after the test has reset it and fail whichever test is next.
 */
internal class TestWorker(name: String) : CoroutineDispatcher() {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, name) }
    private val inner = executor.asCoroutineDispatcher()
    private val dispatched = AtomicInteger()
    private val late = AtomicInteger()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (executor.isShutdown) late.incrementAndGet()
        dispatched.incrementAndGet()
        inner.dispatch(context, block)
    }

    /** Waits until everything handed to the worker so far has run. */
    fun flush() {
        executor.submit {}.get()
    }

    /** Hands work back and forth with [scope]'s queue until a round hands the worker none, then stops it. */
    fun close(scope: TestScope) {
        do {
            val before = dispatched.get()
            scope.advanceUntilIdle()
            flush()
            scope.advanceUntilIdle()
        } while (dispatched.get() != before)
        executor.shutdown()
        check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "worker didn't stop" }
        check(late.get() == 0) { "work reached the worker after it stopped" }
    }
}
