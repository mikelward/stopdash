package app.stopdash.sync

import app.stopdash.domain.SyncEntry
import app.stopdash.domain.SyncLedger
import app.stopdash.domain.SyncState
import app.stopdash.domain.SyncStamp
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What Drive sync keeps on this phone: synthetic ride ids and values only. */
class DriveSyncLocalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val warnings = mutableListOf<String>()

    // The files' own thread, noting each block it runs.
    private val ioThreads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val ioPool = Executors.newSingleThreadExecutor { Thread(it, "io") }
    private val io = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            ioPool.execute {
                ioThreads += threadName()
                block.run()
            }
        }
    }

    @After
    fun tearDown() = ioPool.shutdown()

    // The thread's own name, without the coroutine a debug run appends.
    private fun threadName() = Thread.currentThread().name.substringBefore(" @")

    private fun local() = DriveSyncLocal(File(tmp.root, "drive-sync"), warn = { warnings += it }, io = io)

    private val state = SyncState(
        SyncLedger(mapOf("rides" to mapOf("x" to SyncEntry(SyncStamp(1_000, 0, "a"), "X"))), SyncStamp(1_000, 0, "a")),
        mapOf("f1" to SyncState.ReadFile("1", "{}")),
        uploaded = "{}",
    )

    @Test
    fun `off until turned on, and turning it off keeps what was synced`() = runTest {
        val local = local()
        assertFalse(local.isOn())
        local.setOn(true)
        local.saveState(state)
        assertTrue(local().isOn())
        local.setOn(false)
        assertFalse(local.isOn())
        assertEquals(state, local().state())
    }

    @Test
    fun `a switch that can't be kept is said, not thrown at the tap`() = runTest {
        // A file where the folder would be: nothing can be written in it.
        val blocked = DriveSyncLocal(tmp.newFile("blocked"), warn = { warnings += it }, io = io)
        assertFalse(DriveSync.saved(blocked, on = true))
        assertFalse(blocked.isOn())
        assertTrue(DriveSync.saved(local(), on = true))
    }

    @Test
    fun `this install's id is made once and kept`() = runTest {
        val id = local().phone()
        assertEquals(id, local().phone())
        assertTrue(id.isNotBlank())
    }

    @Test
    fun `nothing kept starts afresh, as does a broken file, said without its text`() = runTest {
        assertEquals(SyncState.EMPTY, local().state())
        assertTrue(warnings.isEmpty())
        File(tmp.root, "drive-sync").mkdirs()
        File(tmp.root, "drive-sync/state.json").writeText("not json")
        assertEquals(SyncState.EMPTY, local().state())
        assertFalse(warnings.single().contains("not json"))
    }

    @Test
    fun `a write lands whole, leaving nothing half-written beside it`() = runTest {
        local().saveState(state)
        assertEquals(setOf("state.json"), File(tmp.root, "drive-sync").list()!!.toSet())
    }

    @Test
    fun `its files are read and written on its own thread, never the caller's`() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller") }
        try {
            runBlocking(caller.asCoroutineDispatcher()) {
                val local = local()
                local.setOn(true)
                local.phone()
                local.saveState(state)
                File(tmp.root, "drive-sync/state.json").writeText("not json")
                // The broken file's warning is said where it was read.
                var warnedOn = ""
                DriveSyncLocal(File(tmp.root, "drive-sync"), warn = { warnedOn = threadName() }, io = io).state()
                assertEquals("io", warnedOn)
                // Back on the caller once each returns.
                assertEquals("caller", threadName())
            }
        } finally {
            caller.shutdown()
        }
        assertTrue("$ioThreads", ioThreads.size >= 4 && ioThreads.all { it == "io" })
    }
}
