package app.stopdash.ui

import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.Coordinates
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.StationIndex
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The places offered as farther cards are worked out off the caller's thread (AGENTS.md *Main thread:
 * read and dispatch only*): the nearby set's walks and the index read alike.
 */
class FartherPlacesTest {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()

    @After
    fun tearDown() {
        worker.close()
    }

    @Test
    fun theFartherPlaces_areWorkedOutOnTheWorker() = runBlocking {
        // Where each tier of the nearby set was walked, and where the index was read.
        val eagerWalked = mutableListOf<String>()
        val moreWalked = mutableListOf<String>()
        val indexRead = mutableListOf<String>()
        fun recorded(into: MutableList<String>) = object : AbstractList<NearbySelection.NearbyCluster>() {
            override val size: Int get() = 0.also { into += Thread.currentThread().name.substringBefore(" @") }
            override fun get(index: Int): NearbySelection.NearbyCluster = throw IndexOutOfBoundsException(index)
        }

        val places = fartherPlacesOf(
            recorded(eagerWalked), recorded(moreWalked), Coordinates(51.5, -0.12), emptyList(), emptySet(), worker,
        ) {
            indexRead += Thread.currentThread().name.substringBefore(" @")
            StationIndex(emptyList())
        }

        assertEquals(emptyList<CollapsedPlaces.Place>(), places)
        for (ran in listOf(eagerWalked, moreWalked, indexRead)) {
            assertTrue("$ran", ran.isNotEmpty() && ran.all { it == "test-worker" })
        }
    }
}
