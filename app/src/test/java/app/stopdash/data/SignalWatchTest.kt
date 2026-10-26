package app.stopdash.data

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The signal watch's binder calls stay off its caller's thread, the main thread included (Codex, #1235). */
class SignalWatchTest {
    @Test
    fun `registering and removing the callback run on the io dispatcher, not the caller's thread`() = runBlocking {
        val callerPool = Executors.newSingleThreadExecutor()
        val ioPool = Executors.newSingleThreadExecutor()
        try {
            val caller = callerPool.asCoroutineDispatcher()
            val io = ioPool.asCoroutineDispatcher()
            val callerThread = withContext(caller) { Thread.currentThread() }
            var started: Thread? = null
            var stopped: Thread? = null
            val job = launch(caller) {
                watchWith(io, fail = { _, _ -> }, start = { started = Thread.currentThread() }, stop = { stopped = Thread.currentThread() })
            }
            while (started == null) Thread.yield()
            job.cancelAndJoin()
            assertNotEquals(callerThread, started)
            assertNotEquals(callerThread, stopped)
            assertNotNull(stopped)
        } finally {
            callerPool.shutdown()
            ioPool.shutdown()
        }
    }

    @Test
    fun `a start that throws is said, with the exception, and nothing is stopped`() = runBlocking {
        val failures = mutableListOf<Pair<Throwable, String>>()
        var stops = 0
        val ioPool = Executors.newSingleThreadExecutor()
        try {
            val job = launch {
                watchWith(ioPool.asCoroutineDispatcher(), fail = { e, what -> failures += e to what }, start = { throw SecurityException("x") }, stop = { stops++ })
            }
            while (failures.isEmpty()) kotlinx.coroutines.yield()
            job.cancelAndJoin()
            assertEquals("signal watch not started", failures.single().second)
            assertTrue(failures.single().first is SecurityException)
            assertEquals(0, stops)
        } finally {
            ioPool.shutdown()
        }
    }
}
