package app.stopdash.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FilePendingMarkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a pending opt-in is kept and cleared`() {
        val marker = FilePendingMarker(File(tmp.root, "pending"))
        assertFalse(marker.read())
        assertTrue(marker.save(true))
        assertTrue(marker.read())
        assertTrue(marker.save(true))
        assertTrue(marker.save(false))
        assertFalse(marker.read())
        assertTrue(marker.save(false))
    }

    @Test
    fun `a consent mark is kept and cleared, and one that can't be written says so`() {
        val mark = FileConsentMark(File(tmp.root, "opted-out"), "opt-out mark")
        assertEquals(false, mark.read())
        assertTrue(mark.save(true))
        assertEquals(true, mark.read())
        assertTrue(mark.save(false))
        assertEquals(false, mark.read())
        assertFalse(FileConsentMark(File(tmp.root, "missing-dir/opted-out"), "opt-out mark").save(true))
    }

    @Test
    fun `a yes mark is moved onto the no mark in one step, and nothing to move says so`() {
        val no = FileConsentMark(File(tmp.root, "opted-out"), "opt-out mark")
        val yes = FileConsentMark(File(tmp.root, "opted-in"), "opt-in mark")
        assertFalse(no.takeOver(yes))
        assertEquals(false, no.read())
        assertTrue(yes.save(true))
        assertTrue(no.takeOver(yes))
        assertEquals(true, no.read())
        assertEquals(false, yes.read())
        // Only another file mark can be moved onto it.
        assertFalse(no.takeOver(NoConsentMark))
        val blocked = FileConsentMark(File(File(tmp.root, "blocker").apply { writeText("") }, "opted-out"), "opt-out mark")
        assertTrue(yes.save(true))
        assertFalse(blocked.takeOver(yes))
        assertEquals(true, yes.read())
    }

    @Test
    fun `a mark whose stat fails can't be read, not read as absent`() {
        // Under a regular file, the stat fails (not a directory) rather than finding nothing:
        // `File.exists()` reads that as absent, which would take a no for never given.
        val blocker = File(tmp.root, "blocker").apply { writeText("") }
        val under = File(blocker, "opted-out")
        assertFalse(under.exists())
        assertThrows(IllegalStateException::class.java) { FileConsentMark(under, "opt-out mark").read() }
        assertFalse(FilePendingMarker(under).read())
        assertFalse(FileConsentMark(under, "opt-out mark").save(true))
        assertFalse(FileConsentMark(under, "opt-out mark").save(false))
    }

    @Test
    fun `a marker that can't be written reports it and reads as not pending`() {
        val marker = FilePendingMarker(File(tmp.root, "missing-dir/pending"))
        assertFalse(marker.save(true))
        assertFalse(marker.read())
    }
}
