package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.StopArrivals
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The list's opened farther cards are merged into it on the screen's worker ([LocalWorker]), never
 * the main thread (AGENTS.md *Main thread: read and dispatch only*): the merge walks every card's stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class FartherMergeOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()

    @After
    fun tearDown() {
        worker.close()
    }

    private val now = Instant.parse("2026-09-18T08:00:00Z")
    private val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("E", "Euston", emptyList(), now)), fetchedAt = now)
    private val card = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MA", "Farther", emptyList(), now)), fetchedAt = now)

    @Test
    fun openedCards_areMergedOnTheWorker() {
        val threads = mutableListOf<String>()
        var kept: MergedFarther? = null
        var shown: DeparturesUiState = DeparturesUiState.Loading
        // A card's stop ids are the same set from one composition to the next, as the screen's are.
        val ids = setOf("MA")
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) {
                shown = rememberWithOpenedFarther(
                    list,
                    listOf(ids to card),
                    cached = null,
                    onMerged = { kept = it },
                    merge = { l, o ->
                        synchronized(threads) { threads += Thread.currentThread().name }
                        withOpenedFarther(l, o)
                    },
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { (shown as? DeparturesUiState.Loaded)?.stops?.size == 2 }

        assertEquals(listOf("E", "MA"), (shown as DeparturesUiState.Loaded).stops.map { it.stopId })
        assertTrue("merged on $threads", threads.isNotEmpty() && threads.all { it.startsWith("test-worker") })
        assertTrue(kept?.merged === shown)
    }

    @Test
    fun aRecreatedScreen_showsTheLastMergeOfTheSameStatesAtOnce() {
        val opened = listOf(setOf("MA") to card)
        val merged = withOpenedFarther(list, opened)
        var first: DeparturesUiState? = null
        composeRule.setContent {
            // Never merged here, so the first frame is what it had before working anything out.
            val shown = rememberWithOpenedFarther(
                list, opened, cached = MergedFarther(list, opened, merged), onMerged = {}, merge = { l, _ -> l },
            )
            if (first == null) first = shown
        }
        composeRule.waitForIdle()
        assertTrue(first === merged)
    }

    @Test
    fun aKeptMerge_matchesOnlyTheVerySameInputs() {
        val ids = setOf("MA")
        val opened = listOf(ids to card)
        val kept = MergedFarther(list, opened, withOpenedFarther(list, opened))
        assertTrue(kept.isOf(list, listOf(ids to card)))
        // Compared by identity, so the check never walks a card's stop ids: an equal copy is new.
        assertFalse(kept.isOf(list, listOf(HashSet(ids) to card)))
        assertFalse(kept.isOf(list.copy(), opened))
    }
}
