package app.stopdash

import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.OFF_DEVICE_PLACEHOLDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainThreadViolationsTest {
    private val device = mutableListOf<String>()
    private val offDevice = mutableListOf<String>()
    private val log = DebugLog().apply {
        addSink({ if ("main thread:" in it) device += it }, DebugLog.Destination.DEVICE)
        addSink({ if ("main thread:" in it) offDevice += it }, DebugLog.Destination.OFF_DEVICE)
    }
    private val violations = MainThreadViolations(log, "com.example.app")

    // A disk read three frames under the app's own call, as StrictMode reports one.
    private val diskRead = listOf(
        StackTraceElement("android.os.StrictMode", "onReadFromDisk", "StrictMode.java", 1),
        StackTraceElement("java.io.FileInputStream", "<init>", "FileInputStream.java", 2),
        StackTraceElement("com.example.app.Store", "load", "Store.kt", 42),
        StackTraceElement("com.example.app.Screen", "show", "Screen.kt", 7),
    )

    @Test
    fun `a violation is said by its kind and the app's own call under it`() {
        violations.report("DiskReadViolation", diskRead)

        assertTrue(device.toString(), device.single().endsWith("main thread: DiskReadViolation at com.example.app.Store.load(Store.kt:42)"))
    }

    @Test
    fun `the kind crosses off the device, the place in the code doesn't`() {
        violations.report("DiskReadViolation", diskRead)

        assertTrue(offDevice.toString(), offDevice.single().endsWith("main thread: DiskReadViolation at $OFF_DEVICE_PLACEHOLDER"))
    }

    @Test
    fun `the same violation is said once a run, and only so many in all`() {
        repeat(3) { violations.report("DiskReadViolation", diskRead) }
        assertEquals(1, device.size)

        repeat(2 * MainThreadViolations.MAX_REPORTED) { line ->
            violations.report("DiskWriteViolation", listOf(StackTraceElement("com.example.app.Store", "save", "Store.kt", line)))
        }
        assertEquals(MainThreadViolations.MAX_REPORTED, device.size)
    }

    @Test
    fun `where R8 has renamed the app's classes, the first frame outside the platform stands in`() {
        val renamed = diskRead.take(2) + StackTraceElement("a.b", "c", "SourceFile", 3)

        assertEquals("a.b.c(SourceFile:3)", MainThreadViolations.firstAppFrame(renamed, "com.example.app"))
    }

    @Test
    fun `with only the platform's frames under it, a violation says so`() {
        assertNull(MainThreadViolations.firstAppFrame(diskRead.take(2), "com.example.app"))

        violations.report("DiskWriteViolation", diskRead.take(2))

        assertTrue(device.single().endsWith("main thread: DiskWriteViolation at none of the app's"))
    }
}
