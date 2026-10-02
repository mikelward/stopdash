package app.stopdash.watch

import app.stopdash.data.WatchRefreshReply
import app.stopdash.data.WatchSyncContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone's Data Layer listener makes of a watch's events, and what it logs. */
class WatchListenerEventsTest {
    private val logged = mutableListOf<String>()

    @Test
    fun `a watch's refresh request is taken and logged`() {
        val id = WatchListenerEvents.refreshRequest(WatchSyncContract.REFRESH_PATH, WatchRefreshReply.encodeRequest(42L), logged::add)
        assertEquals(42L, id)
        assertEquals(listOf("refresh requested"), logged)
    }

    @Test
    fun `an unreadable refresh request is logged and ignored`() {
        assertNull(WatchListenerEvents.refreshRequest(WatchSyncContract.REFRESH_PATH, byteArrayOf(), logged::add))
        assertEquals(listOf("refresh request unreadable"), logged)
    }

    @Test
    fun `a message on another path is ignored without a line`() {
        assertNull(WatchListenerEvents.refreshRequest("/elsewhere", WatchRefreshReply.encodeRequest(1L), logged::add))
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun `StopDash appearing on a watch is logged as a count and republishes`() {
        assertTrue(WatchListenerEvents.capabilityChanged(WatchSyncContract.WATCH_CAPABILITY, 1, logged::add))
        assertEquals(listOf("app on 1 paired watch(es)"), logged)
    }

    @Test
    fun `StopDash gone from every watch is logged and republishes nothing`() {
        assertFalse(WatchListenerEvents.capabilityChanged(WatchSyncContract.WATCH_CAPABILITY, 0, logged::add))
        assertEquals(listOf("app on 0 paired watch(es)"), logged)
    }

    @Test
    fun `another app's capability is ignored without a line`() {
        assertFalse(WatchListenerEvents.capabilityChanged("someone_else", 1, logged::add))
        assertEquals(emptyList<String>(), logged)
    }
}
