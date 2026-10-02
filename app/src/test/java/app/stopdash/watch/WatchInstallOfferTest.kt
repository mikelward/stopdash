package app.stopdash.watch

import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchInstallOfferTest {
    private class FakeNodes(
        var connected: Set<String> = emptySet(),
        var withApp: Set<String> = emptySet(),
        var failRead: Boolean = false,
        val unreachable: Set<String> = emptySet(),
    ) : WatchNodes {
        val opened = mutableListOf<String>()
        val threads = mutableListOf<String>()

        override suspend fun connected(): Set<String> {
            threads += Thread.currentThread().name
            if (failRead) throw IOException("no Data Layer")
            return connected
        }

        override suspend fun withApp(): Set<String> {
            threads += Thread.currentThread().name
            return withApp
        }

        override suspend fun openPlayStore(nodeId: String) {
            threads += Thread.currentThread().name
            if (nodeId in unreachable) throw IOException("out of reach")
            opened += nodeId
        }
    }

    private val logged = mutableListOf<String>()

    @Test
    fun `the watches are read and the install opened off the caller's thread`() {
        // AGENTS.md *Main-safe by default*: the caller is the main thread when the app comes to the
        // front, so the reads, the comparison and the opens all run on the worker instead.
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            val nodes = FakeNodes(connected = setOf("w1"))
            val offer = WatchInstallOffer(nodes, logged::add, worker = worker)
            runBlocking(caller) {
                offer.refresh()
                assertTrue(offer.install())
            }
            // Debug coroutines append " @coroutine#n" to the name; the thread is what matters.
            assertEquals(List(5) { "test-worker" }, nodes.threads.map { it.substringBefore(" @") })
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `nothing is offered before the watches are read`() {
        assertFalse(WatchInstallOffer(FakeNodes(connected = setOf("w1")), logged::add).available.value)
    }

    @Test
    fun `a connected watch without the app is offered it, and the install opens Play on that one`() = runTest {
        val nodes = FakeNodes(connected = setOf("w1", "w2"), withApp = setOf("w2"))
        val offer = WatchInstallOffer(nodes, logged::add)
        offer.refresh()
        assertTrue(offer.available.value)
        assertTrue(offer.install())
        assertEquals(listOf("w1"), nodes.opened)
    }

    @Test
    fun `no offer where every connected watch has the app, or none is connected`() = runTest {
        val nodes = FakeNodes(connected = setOf("w1"), withApp = setOf("w1"))
        val offer = WatchInstallOffer(nodes, logged::add)
        offer.refresh()
        assertFalse(offer.available.value)
        nodes.connected = emptySet()
        offer.refresh()
        assertFalse(offer.available.value)
    }

    @Test
    fun `the offer goes once the app is installed`() = runTest {
        val nodes = FakeNodes(connected = setOf("w1"))
        val offer = WatchInstallOffer(nodes, logged::add)
        offer.refresh()
        assertTrue(offer.available.value)
        nodes.withApp = setOf("w1")
        offer.refresh()
        assertFalse(offer.available.value)
    }

    @Test
    fun `watches that can't be read offer nothing, and say why in the log`() = runTest {
        val offer = WatchInstallOffer(FakeNodes(connected = setOf("w1"), failRead = true), logged::add)
        offer.refresh()
        assertFalse(offer.available.value)
        assertEquals(listOf("install offer: watches unreadable: IOException"), logged)
    }

    @Test
    fun `an install no watch received reports failure, so the phone can say so`() = runTest {
        val nodes = FakeNodes(connected = setOf("w1"), unreachable = setOf("w1"))
        val offer = WatchInstallOffer(nodes, logged::add)
        offer.refresh()
        assertFalse(offer.install())
        assertEquals(listOf("install offer: Play Store not opened on a watch: IOException"), logged)
    }

    @Test
    fun `an install reaching one of two watches counts as opened`() = runTest {
        val nodes = FakeNodes(connected = setOf("w1", "w2"), unreachable = setOf("w1"))
        val offer = WatchInstallOffer(nodes, logged::add)
        offer.refresh()
        assertTrue(offer.install())
        assertEquals(listOf("w2"), nodes.opened)
    }

    @Test
    fun `an install reads the watches again, so a stale offer opens nothing on a watch since gone`() = runTest {
        val nodes = FakeNodes(connected = setOf("w1"))
        val offer = WatchInstallOffer(nodes, logged::add)
        offer.refresh()
        assertTrue(offer.available.value)
        // Shown from the last read; the watch disconnects before the tap.
        nodes.connected = emptySet()
        assertFalse(offer.install())
        assertEquals(emptyList<String>(), nodes.opened)
        assertFalse(offer.available.value)
    }

    @Test
    fun `an install with no watch to offer it to reports failure`() = runTest {
        val offer = WatchInstallOffer(FakeNodes(), logged::add)
        offer.refresh()
        assertFalse(offer.install())
        assertEquals(listOf("install offer: no watch to install on"), logged)
    }
}
