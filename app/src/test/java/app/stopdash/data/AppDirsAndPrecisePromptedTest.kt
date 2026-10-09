package app.stopdash.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stopdash.ThreadRecorder
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

    private fun recordingWorker(ranOn: ThreadRecorder): Pair<CoroutineDispatcher, () -> Unit> {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val base = pool.asCoroutineDispatcher()
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) =
                base.dispatch(context) { ranOn.note(); block.run() }
        }
        return worker to { pool.shutdown() }
    }

    @Test
    fun `the precise flag is read and written on the worker, and a mark is seen at once`() {
        val ranOn = ThreadRecorder()
        val readOn = ThreadRecorder()
        val (worker, stop) = recordingWorker(ranOn)
        try {
            val prefs = { readOn.note(); context.getSharedPreferences("test.precise", Context.MODE_PRIVATE) }
            prefs().edit().clear().commit()
            readOn.clear()
            val flag = PrecisePrompted(prefs, worker)
            assertFalse(runBlocking { flag.get() })
            runBlocking { flag.mark() }
            assertTrue(runBlocking { flag.get() })
            assertEquals(setOf("test-worker"), readOn.threads().toSet())
            assertEquals(setOf("test-worker"), ranOn.threads().toSet())
            // Kept: a new process reads it back.
            assertTrue(runBlocking { PrecisePrompted(prefs, worker).get() })
        } finally {
            stop()
        }
    }

    @Test
    fun `the last answer is read and written on the worker, and kept`() {
        val ranOn = ThreadRecorder()
        val (worker, stop) = recordingWorker(ranOn)
        try {
            val prefs = { context.getSharedPreferences("test.precise.answer", Context.MODE_PRIVATE) }
            prefs().edit().clear().commit()
            val flag = PrecisePrompted(prefs, worker)
            assertFalse(runBlocking { flag.lastRefused() })
            runBlocking { flag.answered(granted = false) }
            assertTrue(runBlocking { flag.lastRefused() })
            assertTrue(runBlocking { PrecisePrompted(prefs, worker).lastRefused() })
            // A later grant (one-time included) clears it, so an expired grant prompts again.
            runBlocking { flag.answered(granted = true) }
            assertFalse(runBlocking { PrecisePrompted(prefs, worker).lastRefused() })
            // A grant seen some other way (Settings) clears a refusal too, so a later reset of that
            // grant isn't read as the refusal before it.
            runBlocking { flag.answered(granted = false) }
            runBlocking { flag.grantSeen() }
            assertFalse(runBlocking { PrecisePrompted(prefs, worker).lastRefused() })
            assertEquals(setOf("test-worker"), ranOn.threads().toSet())
        } finally {
            stop()
        }
    }

    @Test
    fun `an install from before the answer was kept reads asked-once as a refusal`() {
        val (worker, stop) = recordingWorker(ThreadRecorder())
        try {
            val prefs = { context.getSharedPreferences("test.precise.legacy", Context.MODE_PRIVATE) }
            prefs().edit().clear().commit()
            assertFalse(runBlocking { PrecisePrompted(prefs, worker).lastRefused() })
            // Asked before this version, no answer kept: Settings, as before.
            prefs().edit().putBoolean("precise_prompted", true).commit()
            assertTrue(runBlocking { PrecisePrompted(prefs, worker).lastRefused() })
            // A grant seen clears it, so an expired grant after that prompts again.
            runBlocking { PrecisePrompted(prefs, worker).grantSeen() }
            assertFalse(runBlocking { PrecisePrompted(prefs, worker).lastRefused() })
        } finally {
            stop()
        }
    }

    @Test
    fun `a prompt dismissed by this version isn't read as an old refusal after a restart`() {
        val (worker, stop) = recordingWorker(ThreadRecorder())
        try {
            val prefs = { context.getSharedPreferences("test.precise.dismissed", Context.MODE_PRIVATE) }
            prefs().edit().clear().commit()
            runBlocking {
                val flag = PrecisePrompted(prefs, worker)
                flag.mark()
                flag.askedWithoutRefusal()
            }
            // A new process: asked, but no refusal recorded, so not refused.
            assertFalse(runBlocking { PrecisePrompted(prefs, worker).lastRefused() })
            // A refusal for good already recorded stands.
            runBlocking { PrecisePrompted(prefs, worker).answered(granted = false) }
            runBlocking { PrecisePrompted(prefs, worker).askedWithoutRefusal() }
            assertTrue(runBlocking { PrecisePrompted(prefs, worker).lastRefused() })
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
