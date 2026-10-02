package app.stopdash.watch

import com.mikelward.androidlog.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Test

/** The watch link's log lines carry no user data, so they reach Crashlytics readable. */
class WatchStatusLogTest {
    @Test
    fun `a watch-link line keeps its words off the device`() {
        val device = mutableListOf<String>()
        val offDevice = mutableListOf<String>()
        val log = DebugLog().apply {
            addSink({ if ("watch:" in it) device += it }, DebugLog.Destination.DEVICE)
            addSink({ if ("watch:" in it) offDevice += it }, DebugLog.Destination.OFF_DEVICE)
        }
        log.watchStatus("app on 1 paired watch(es)")
        assertEquals(1, device.size)
        assertEquals(1, offDevice.size)
        assertEquals(true, offDevice.single().endsWith("watch: app on 1 paired watch(es)"))
    }
}
