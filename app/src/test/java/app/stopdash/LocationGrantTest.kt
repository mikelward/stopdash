package app.stopdash

import android.Manifest
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationGrantTest {
    @Test
    fun `the grant is read off the caller's thread`() {
        // The disclosure opens from a tap, so the caller is the main thread: the checks run on the worker.
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            val threads = ThreadRecorder()
            val granted = runBlocking(caller) {
                foregroundLocationGranted({ threads.note(); false }, io = worker)
            }
            assertFalse(granted)
            assertEquals(listOf("test-worker", "test-worker"), threads.threads())
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `approximate alone counts as allowed while in use`() = runBlocking {
        assertTrue(foregroundLocationGranted({ it == Manifest.permission.ACCESS_COARSE_LOCATION }))
        assertTrue(foregroundLocationGranted({ it == Manifest.permission.ACCESS_FINE_LOCATION }))
        assertFalse(foregroundLocationGranted({ false }))
    }

    @Test
    fun `location access is precise, approximate or none`() = runBlocking {
        assertEquals(LocationAccess.PRECISE, locationAccess({ true }))
        assertEquals(LocationAccess.APPROXIMATE, locationAccess({ it == Manifest.permission.ACCESS_COARSE_LOCATION }))
        assertEquals(LocationAccess.NONE, locationAccess({ false }))
    }

    @Test
    fun `location access is read off the caller's thread`() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            val threads = ThreadRecorder()
            val access = runBlocking(caller) { locationAccess({ threads.note(); false }, io = worker) }
            assertEquals(LocationAccess.NONE, access)
            assertEquals(listOf("test-worker", "test-worker"), threads.threads())
        } finally {
            caller.close()
            worker.close()
        }
    }
}
