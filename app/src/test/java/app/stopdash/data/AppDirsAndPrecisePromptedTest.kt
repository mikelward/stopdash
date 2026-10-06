package app.stopdash.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The startup reads the maintainer's bug report caught on the main thread (2026-10-05): the app's
 * directories are named without touching the disk, a store makes its directory when it writes, and
 * the precise-location flag is read and written on a worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppDirsAndPrecisePromptedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the directories named are Android's own`() {
        assertEquals(context.cacheDir.canonicalPath, AppDirs.cache(context).canonicalPath)
        assertEquals(context.noBackupFilesDir.canonicalPath, AppDirs.noBackup(context).canonicalPath)
    }

    @Test
    fun `a store under a directory that isn't there yet makes it when it writes`() {
        val dir = File(AppDirs.cache(context), "not-yet")
        dir.deleteRecursively()
        val store = FileNearbyStopsStore(File(dir, "nearby-stops.json"), warn = {})
        store.save(emptyList())
        assertTrue(File(dir, "nearby-stops.json").exists())
        dir.deleteRecursively()
    }

    private fun recordingWorker(ranOn: MutableSet<String>): Pair<CoroutineDispatcher, () -> Unit> {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val base = pool.asCoroutineDispatcher()
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) =
                base.dispatch(context) { ranOn += Thread.currentThread().name; block.run() }
        }
        return worker to { pool.shutdown() }
    }

    @Test
    fun `the precise flag is read and written on the worker, and a mark is seen at once`() {
        val ranOn = mutableSetOf<String>()
        val readOn = mutableSetOf<String>()
        val (worker, stop) = recordingWorker(ranOn)
        try {
            val prefs = { readOn += Thread.currentThread().name.substringBefore(" @"); context.getSharedPreferences("test.precise", Context.MODE_PRIVATE) }
            prefs().edit().clear().commit()
            readOn.clear()
            val flag = PrecisePrompted(prefs, worker)
            assertFalse(runBlocking { flag.get() })
            runBlocking { flag.mark() }
            assertTrue(runBlocking { flag.get() })
            assertEquals(setOf("test-worker"), readOn)
            assertEquals(setOf("test-worker"), ranOn)
            // Kept: a new process reads it back.
            assertTrue(runBlocking { PrecisePrompted(prefs, worker).get() })
        } finally {
            stop()
        }
    }

    @Test
    fun `the process's flag is one, so a mark outlives the activity that made it`() {
        val first = PrecisePrompted.of(context)
        runBlocking { first.mark() }
        val next = PrecisePrompted.of(context)
        assertTrue(first === next)
        assertTrue(runBlocking { next.get() })
    }
}
