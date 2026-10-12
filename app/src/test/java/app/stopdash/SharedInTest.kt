package app.stopdash

import app.stopdash.domain.SharedPlace
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executors

class SharedInTest {
    @Test
    fun `a share is read off the caller's thread`() {
        // AGENTS.md *Main thread*: the caller is the main thread when a share arrives, and shared text can
        // be long, so the reading runs on the worker.
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        val threads = ThreadRecorder()
        try {
            val ask = runBlocking(caller) {
                readSharedIn(SharedIn(text = "Somewhere"), compute = worker) {
                    threads.note()
                    SharedPlace.Ask.Search(it.text!!)
                }
            }
            assertEquals(SharedPlace.Ask.Search("Somewhere"), ask)
            assertEquals(listOf("test-worker"), threads.threads())
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `shared text is searched, and nothing to go on reads as nothing`() = runBlocking {
        assertEquals(SharedPlace.Ask.Search("Somewhere"), readSharedIn(SharedIn(text = "Somewhere")))
        assertNull(readSharedIn(SharedIn()))
    }
}
