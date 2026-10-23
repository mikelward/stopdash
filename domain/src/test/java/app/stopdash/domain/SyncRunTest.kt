package app.stopdash.domain

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One sync at a time between phones sharing a fake Drive folder. Synthetic ride ids and values only. */
class SyncRunTest {
    private class FakeDrive : DriveAppData {
        data class File(val name: String, val text: String, val modifiedTime: String)

        val files = LinkedHashMap<String, File>()
        var time = 1_000_000L
        var nextId = 1
        var downloads = 0
        var uploads = 0
        var failing: DriveFailure? = null
        var failingUploads: DriveFailure? = null

        private fun stamp() = (time++).toString()

        override suspend fun list(): DriveResult<DriveListing> {
            failing?.let { return DriveResult.Failed(it) }
            return DriveResult.Ok(DriveListing(files.map { (id, f) -> DriveFile(id, f.name, f.modifiedTime) }, DriveClock(time, 0)))
        }

        override suspend fun download(id: String): DriveResult<String> {
            downloads++
            return files[id]?.let { DriveResult.Ok(it.text) } ?: DriveResult.Failed(DriveFailure.NotFound)
        }

        override suspend fun create(name: String, text: String): DriveResult<DriveFile> {
            failingUploads?.let { return DriveResult.Failed(it) }
            uploads++
            val id = "f${nextId++}"
            files[id] = File(name, text, stamp())
            return DriveResult.Ok(DriveFile(id, name, files.getValue(id).modifiedTime))
        }

        override suspend fun update(id: String, text: String): DriveResult<DriveFile> {
            failingUploads?.let { return DriveResult.Failed(it) }
            uploads++
            val file = files[id] ?: return DriveResult.Failed(DriveFailure.NotFound)
            files[id] = file.copy(text = text, modifiedTime = stamp())
            return DriveResult.Ok(DriveFile(id, file.name, files.getValue(id).modifiedTime))
        }

        override suspend fun delete(id: String): DriveResult<Unit> {
            files.remove(id)
            return DriveResult.Ok(Unit)
        }
    }

    private class FakeStore(var rides: Map<String, String>) : SyncedStore {
        override val kinds = listOf(RIDES)
        var refuse = false
        var written: String? = null

        override suspend fun entries() = mapOf(RIDES to rides)

        override suspend fun apply(expected: Map<String, Map<String, String>>, merged: Map<String, Map<String, String>>, mark: String): Boolean {
            if (refuse || expected != entries()) return false
            rides = merged.getValue(RIDES)
            written = mark
            return true
        }

        override suspend fun mark() = written

        override fun understood(kind: String, value: String) = !value.startsWith("v2:")

        override fun same(kind: String, a: String, b: String) = a == b
    }

    private inner class Phone(val id: String, rides: Map<String, String>, val debug: Boolean = false) {
        val store = FakeStore(rides)

        // The phone's own files, as DriveSync keeps them: the merge kept before the store is written, and the
        // result once the sync ends, unless [sync] is told it was stopped first.
        var state = SyncState.EMPTY
        var keepFails = false

        suspend fun sync(drive: FakeDrive, stoppedBeforeKeeping: Boolean = false): SyncOutcome {
            val before = state
            val run = SyncRun(drive, store, id, debug, elapsed = { 0L }, warn = { warnings += it }, keep = {
                if (keepFails) throw IOException("no space")
                state = it
            })
            val (outcome, next) = run.run(before)
            if (!stoppedBeforeKeeping && next != before) state = next
            return outcome
        }
    }

    private val warnings = mutableListOf<String>()

    @Test
    fun `a first sync uploads this phone's file, named for it`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        assertEquals(SyncOutcome.Synced(emptyMap(), newerPhone = false), a.sync(drive))
        val file = drive.files.values.single()
        assertEquals("phone-a.json", file.name)
        val ledger = (SyncFile.decode(file.text) { } as SyncFileRead.Read).ledger
        assertEquals(mapOf("x" to "X"), Sync.valuesOf(ledger, RIDES))
        assertEquals(file.text, a.state.uploaded)
    }

    @Test
    fun `two phones end with one list`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", mapOf("y" to "Y"))
        a.sync(drive)
        b.sync(drive)
        a.sync(drive)
        assertEquals(mapOf("x" to "X", "y" to "Y"), a.store.rides)
        assertEquals(mapOf("x" to "X", "y" to "Y"), b.store.rides)
    }

    @Test
    fun `a ride removed on one phone goes from the other, which says it went`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X", "y" to "Y"))
        val b = Phone("b", emptyMap())
        a.sync(drive)
        b.sync(drive)
        a.store.rides = mapOf("y" to "Y")
        a.sync(drive)
        assertEquals(SyncOutcome.Synced(mapOf(RIDES to setOf("x")), newerPhone = false, changed = setOf(RIDES)), b.sync(drive))
        assertEquals(mapOf("y" to "Y"), b.store.rides)
    }

    @Test
    fun `a ride changed on another phone is said as a change the store took in`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", emptyMap())
        a.sync(drive)
        b.sync(drive)
        a.store.rides = mapOf("x" to "X with a line hidden")
        assertEquals(SyncOutcome.Synced(emptyMap(), newerPhone = false), a.sync(drive))
        assertEquals(SyncOutcome.Synced(emptyMap(), newerPhone = false, changed = setOf(RIDES)), b.sync(drive))
        assertEquals(mapOf("x" to "X with a line hidden"), b.store.rides)
    }

    @Test
    fun `a file unchanged since last read isn't downloaded again, nor one unchanged here uploaded`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", mapOf("y" to "Y"))
        a.sync(drive)
        b.sync(drive)
        val downloads = drive.downloads
        val uploads = drive.uploads
        b.sync(drive)
        assertEquals(downloads, drive.downloads)
        assertEquals(uploads, drive.uploads)
    }

    @Test
    fun `debug builds and release builds never read each other's files`() = runTest {
        val drive = FakeDrive()
        val release = Phone("a", mapOf("x" to "X"))
        val debug = Phone("a", mapOf("y" to "Y"), debug = true)
        release.sync(drive)
        debug.sync(drive)
        assertEquals(setOf("phone-a.json", "debug-phone-a.json"), drive.files.values.mapTo(HashSet()) { it.name })
        assertEquals(mapOf("y" to "Y"), debug.store.rides)
        release.sync(drive)
        assertEquals(mapOf("x" to "X"), release.store.rides)
    }

    @Test
    fun `a newer phone's file is skipped and said, the rest synced`() = runTest {
        val drive = FakeDrive()
        drive.files["n"] = FakeDrive.File("phone-n.json", """{"version":99,"kinds":[]}""", "1")
        val b = Phone("b", mapOf("y" to "Y"))
        val a = Phone("a", mapOf("x" to "X"))
        b.sync(drive)
        assertEquals(SyncOutcome.Synced(emptyMap(), newerPhone = true, changed = setOf(RIDES)), a.sync(drive))
        assertEquals(mapOf("x" to "X", "y" to "Y"), a.store.rides)
    }

    @Test
    fun `a ride in a newer build's shape is kept for it, never removed or written here`() = runTest {
        val drive = FakeDrive()
        val newer = SyncLedger(mapOf(RIDES to mapOf("z" to SyncEntry(SyncStamp(500, 0, "n"), "v2:Z"))), SyncStamp(500, 0, "n"))
        drive.files["n"] = FakeDrive.File("phone-n.json", SyncFile.encode(newer), "1")
        val a = Phone("a", mapOf("x" to "X"))
        a.sync(drive)
        a.sync(drive)
        assertEquals(mapOf("x" to "X"), a.store.rides)
        assertEquals("v2:Z", a.state.ledger.kinds.getValue(RIDES).getValue("z").value)
    }

    @Test
    fun `a ride a newer build changed keeps this phone's copy, and that build's change isn't undone`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        a.sync(drive)
        // A newer build's phone changes the ride into a shape this build can't read.
        val reshaped = SyncLedger(mapOf(RIDES to mapOf("x" to SyncEntry(SyncStamp(5_000_000, 0, "n"), "v2:X"))), SyncStamp(5_000_000, 0, "n"))
        drive.files["n"] = FakeDrive.File("phone-n.json", SyncFile.encode(reshaped), "1")
        assertEquals(SyncOutcome.Synced(emptyMap(), newerPhone = false), a.sync(drive))
        a.sync(drive)
        assertEquals(mapOf("x" to "X"), a.store.rides)
        assertEquals("v2:X", a.state.ledger.kinds.getValue(RIDES).getValue("x").value)
        val own = drive.files.values.single { it.name == "phone-a.json" }
        assertEquals("v2:X", Sync.valuesOf((SyncFile.decode(own.text) { } as SyncFileRead.Read).ledger, RIDES).getValue("x"))
    }

    @Test
    fun `Drive coming to nothing changes nothing here`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        a.sync(drive)
        val before = a.state
        drive.failing = DriveFailure.Offline
        assertEquals(SyncOutcome.Failed(DriveFailure.Offline), a.sync(drive))
        assertSame(before, a.state)
    }

    @Test
    fun `a removal taken in before the upload failed is still said, and the next sync sends the file`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X", "y" to "Y"))
        val b = Phone("b", emptyMap())
        a.sync(drive)
        b.sync(drive)
        a.store.rides = mapOf("y" to "Y")
        a.sync(drive)
        drive.failingUploads = DriveFailure.Offline
        assertEquals(SyncOutcome.Failed(DriveFailure.Offline, mapOf(RIDES to setOf("x")), setOf(RIDES)), b.sync(drive))
        assertEquals(mapOf("y" to "Y"), b.store.rides)
        drive.failingUploads = null
        val uploads = drive.uploads
        assertEquals(SyncOutcome.Synced(emptyMap(), newerPhone = false), b.sync(drive))
        assertEquals(uploads + 1, drive.uploads)
    }

    @Test
    fun `a change landing while a sync works is taken in by the next, not undone`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", mapOf("y" to "Y"))
        a.sync(drive)
        b.store.refuse = true
        val before = b.state
        assertEquals(SyncOutcome.Overtaken, b.sync(drive))
        // Only the merge kept before writing the store, which the next sync settles.
        assertEquals(before, b.state.copy(pending = null))
        assertEquals(mapOf("y" to "Y"), b.store.rides)
        b.store.refuse = false
        b.sync(drive)
        assertEquals(mapOf("x" to "X", "y" to "Y"), b.store.rides)
    }

    @Test
    fun `a sync stopped after writing the store is settled by the next, never taken for changes here`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", emptyMap())
        a.sync(drive)
        b.sync(drive)
        a.store.rides = mapOf("x" to "X2")
        a.sync(drive)
        // b takes in X2, and its process goes before it keeps the result.
        b.sync(drive, stoppedBeforeKeeping = true)
        assertEquals(mapOf("x" to "X2"), b.store.rides)
        // Meanwhile a changes it again.
        a.store.rides = mapOf("x" to "X3")
        a.sync(drive)
        b.sync(drive)
        assertEquals(mapOf("x" to "X3"), b.store.rides)
        a.sync(drive)
        assertEquals(mapOf("x" to "X3"), a.store.rides)
        assertEquals("a", b.state.ledger.kinds.getValue(RIDES).getValue("x").stamp.phone)
    }

    @Test
    fun `an edit here after a stopped sync is still this phone's change`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", emptyMap())
        a.sync(drive)
        b.sync(drive)
        a.store.rides = mapOf("x" to "X2")
        a.sync(drive)
        b.sync(drive, stoppedBeforeKeeping = true)
        b.store.rides = mapOf("x" to "B")
        b.sync(drive)
        a.sync(drive)
        assertEquals(mapOf("x" to "B"), a.store.rides)
        assertEquals(mapOf("x" to "B"), b.store.rides)
    }

    @Test
    fun `a ride taken in by a stopped sync and removed here stays removed`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", emptyMap())
        a.sync(drive)
        a.store.rides = mapOf("x" to "X", "y" to "Y")
        a.sync(drive)
        // b takes in x and y, and its process goes before it keeps the result; then the rider removes y.
        b.sync(drive, stoppedBeforeKeeping = true)
        b.store.rides = mapOf("x" to "X")
        b.sync(drive)
        a.sync(drive)
        assertEquals(mapOf("x" to "X"), b.store.rides)
        assertEquals(mapOf("x" to "X"), a.store.rides)
    }

    @Test
    fun `a stopped sync settled by a sync a change here overtakes stays settled`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", emptyMap())
        a.sync(drive)
        b.sync(drive)
        a.store.rides = mapOf("x" to "X", "y" to "Y")
        a.sync(drive)
        b.sync(drive, stoppedBeforeKeeping = true)
        a.store.rides = mapOf("x" to "X", "y" to "Y2")
        a.sync(drive)
        // b's next sync settles the stopped one, then a change here overtakes it.
        b.store.refuse = true
        assertEquals(SyncOutcome.Overtaken, b.sync(drive))
        b.store.refuse = false
        b.sync(drive)
        a.sync(drive)
        assertEquals(mapOf("x" to "X", "y" to "Y2"), b.store.rides)
        assertEquals(mapOf("x" to "X", "y" to "Y2"), a.store.rides)
    }

    @Test
    fun `a stopped sync whose store was never written is settled as unwritten`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", mapOf("y" to "Y"))
        a.sync(drive)
        b.store.refuse = true
        assertEquals(SyncOutcome.Overtaken, b.sync(drive))
        b.store.refuse = false
        b.store.rides = emptyMap()
        b.sync(drive)
        a.sync(drive)
        // y was removed here, and x never reached this store, so b takes it in now.
        assertEquals(mapOf("x" to "X"), b.store.rides)
        assertEquals(mapOf("x" to "X"), a.store.rides)
    }

    @Test
    fun `a merge that can't be kept leaves the store as it was`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", mapOf("y" to "Y"))
        a.sync(drive)
        b.keepFails = true
        assertTrue(runCatching { b.sync(drive) }.exceptionOrNull() is IOException)
        assertEquals(mapOf("y" to "Y"), b.store.rides)
        b.keepFails = false
        b.sync(drive)
        assertEquals(mapOf("x" to "X", "y" to "Y"), b.store.rides)
    }

    @Test
    fun `this phone's file is made again when the rider deleted it from Drive`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        a.sync(drive)
        drive.files.clear()
        a.sync(drive)
        assertEquals("phone-a.json", drive.files.values.single().name)
    }

    @Test
    fun `changes are stamped by Drive's clock`() = runTest {
        val drive = FakeDrive()
        drive.time = 42_000
        val a = Phone("a", mapOf("x" to "X"))
        a.sync(drive)
        assertEquals(42_000L, a.state.ledger.kinds.getValue(RIDES).getValue("x").stamp.wall)
    }

    @Test
    fun `the kept state reads back as written, and a newer or broken one as nothing kept`() = runTest {
        val drive = FakeDrive()
        val a = Phone("a", mapOf("x" to "X"))
        val b = Phone("b", mapOf("y" to "Y"))
        a.sync(drive)
        b.sync(drive)
        assertEquals(b.state, SyncStateFile.decode(SyncStateFile.encode(b.state)) { warnings += it })
        val kept = b.state.copy(pending = SyncState.Pending("m1", a.state.ledger))
        assertEquals(kept, SyncStateFile.decode(SyncStateFile.encode(kept)) { warnings += it })
        assertNull(SyncStateFile.decode("""{"version":99,"ledger":""}""") { warnings += it })
        assertTrue(warnings.isEmpty())
        assertNull(SyncStateFile.decode("not json") { warnings += it })
        assertTrue(warnings.single().startsWith("sync state unreadable ("))
        assertFalse("not json" in warnings.single())
    }

    private companion object {
        const val RIDES = "rides"
    }
}
