package app.stopdash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TestWorkerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    // A model cleared while its work settles whatever happens (TripViewModel's closure check, under NonCancellable)
    // still hops to the worker after it's cleared. Closed, the worker takes every such hop before it stops, so none
    // is rejected onto Dispatchers.IO to reach Main after the test has reset it (the leak that failed whichever
    // TripViewModelTest came next, 2026-10-21).
    @Test
    fun `closing waits for work a cleared scope still hands the worker`() = runTest(dispatcher) {
        val worker = TestWorker("worker")
        val ranOn = ThreadRecorder()
        var settled = false
        val model = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        model.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    withContext(worker) { ranOn.note() }
                    withContext(worker) { ranOn.note() }
                    settled = true
                }
            }
        }
        runCurrent()
        model.cancel()
        worker.close(this)
        assertTrue(settled)
        assertEquals(listOf("worker", "worker"), ranOn.threads())
    }
}
