package app.stopdash.ui

import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopLocation
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list's opened farther cards are merged into it on the compute dispatcher, never the main thread
 * (AGENTS.md *Main thread: read and dispatch only*), and the screen gets only finished merges: the
 * list never arrives without the opened rows (which collapsed them and lost the list's place), nor
 * with rows from another moment than their cards' standing and failures.
 */
class FartherMergeOffMainTest {
    private val compute = Executors.newSingleThreadExecutor { Thread(it, "test-compute") }.asCoroutineDispatcher()

    @After
    fun tearDown() {
        compute.close()
    }

    private val now = Instant.parse("2026-09-18T08:00:00Z")
    private val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("E", "Euston", emptyList(), now)), fetchedAt = now)
    private val card = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MA", "Farther", emptyList(), now)), fetchedAt = now)
    private val second = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MB", "Farther still", emptyList(), now)), fetchedAt = now)
    private val openA = FartherLoad.Open(listOf(StopLocation("MA", "Farther", 51.5, -0.12)), mapOf("MA" to 2000.0))
    private val openB = FartherLoad.Open(listOf(StopLocation("MB", "Farther still", 51.5, -0.12)), mapOf("MB" to 2500.0))

    private fun cards(vararg open: Pair<String, Pair<FartherLoad.Open, DeparturesUiState>>) = OpenedCards(
        null,
        open.associate { (key, pair) -> key to pair.first },
        open.map { (_, pair) -> pair.first.distanceMeters.keys to pair.second },
    )

    private fun DeparturesUiState.ids() = (this as DeparturesUiState.Loaded).stops.map { it.stopId }

    @Test
    fun openedCards_areMergedOnCompute() = runBlocking {
        val threads = mutableListOf<String>()
        val shown = shownWithOpened(
            MutableStateFlow(list),
            MutableStateFlow(cards("a" to (openA to card))),
            compute,
            merge = { l, o ->
                synchronized(threads) { threads += Thread.currentThread().name }
                withOpenedFarther(l, o)
            },
        ).first()

        assertEquals(listOf("E", "MA"), shown.state.ids())
        assertTrue("merged on $threads", threads.isNotEmpty() && threads.all { it.startsWith("test-compute") })
    }

    @Test
    fun anotherCardOpening_neverShowsTheListWithoutTheOpenedRows() = runBlocking {
        // The bug: a second card tapped open dropped the first one's rows until the merge was back, so
        // the list collapsed them and landed on another card's rows. Every update the screen gets
        // carries every open card's rows.
        val opened = MutableStateFlow(cards("a" to (openA to card)))
        val release = CountDownLatch(1)
        val updates = mutableListOf<ShownFarther>()
        val job = launch {
            shownWithOpened(MutableStateFlow(list), opened, compute, merge = { l, o ->
                if (o.size == 2) release.await()
                withOpenedFarther(l, o)
            }).take(2).toList(updates)
        }
        while (synchronized(updates) { updates.isEmpty() }) yield()
        opened.value = cards("a" to (openA to card), "b" to (openB to second))
        release.countDown()
        job.join()

        assertEquals(listOf(listOf("E", "MA"), listOf("E", "MA", "MB")), updates.map { it.state.ids() })
        // Each with the cards' standing it was merged with.
        assertEquals(listOf(setOf("a"), setOf("a", "b")), updates.map { it.loads.keys })
    }

    @Test
    fun aFailingCard_arrivesWithItsRows() = runBlocking {
        // A card's failed refresh and its rows come in the same update, so its banner never waits
        // behind a picture that reads clean.
        val failed = card.copy(refreshFailure = DeparturesUiState.Error.Kind.NETWORK, partialRefresh = true)
        val shown = shownWithOpened(MutableStateFlow(list), MutableStateFlow(cards("a" to (openA to failed))), compute).first()
        assertTrue((shown.state as DeparturesUiState.Loaded).partialRefresh)
        assertEquals(listOf("E", "MA"), shown.state.ids())
    }

    @Test
    fun aCardReopening_dropsItsRowsWithItsNewStanding() = runBlocking {
        // A bus card reopened on new poles goes back to loading: its old rows go in the same update.
        val loads = mapOf<String, FartherLoad>("a" to FartherLoad.Loading)
        val shown = shownWithOpened(MutableStateFlow(list), MutableStateFlow(OpenedCards(null, loads, emptyList())), compute).first()
        assertSame(loads, shown.loads)
        assertSame(list, shown.state)
    }

    @Test
    fun aListNotLoaded_passesThrough() = runBlocking {
        val shown = shownWithOpened(
            MutableStateFlow<DeparturesUiState>(DeparturesUiState.Loading),
            MutableStateFlow(cards("a" to (openA to card))),
            compute,
        ).first()
        assertSame(DeparturesUiState.Loading, shown.state)
    }
}
