package app.stopdash.ui

import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopLocation
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The open cards are worked out on the compute dispatcher, never in composition (AGENTS.md *Main
 * thread: read and dispatch only*), each with the departures it was opened with and its standing.
 */
class OpenedCardsTest {
    private val compute = Executors.newSingleThreadExecutor { Thread(it, "test-compute") }.asCoroutineDispatcher()

    @After
    fun tearDown() {
        compute.close()
    }

    private val now = Instant.parse("2026-09-18T08:00:00Z")
    private val clean = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MA", "Farther", emptyList(), now)), fetchedAt = now)
    private val state = MutableStateFlow<DeparturesUiState>(clean)
    private val open = FartherLoad.Open(listOf(StopLocation("MA", "Farther", 51.5, -0.12)), mapOf("MA" to 2000.0), state)

    @Test
    fun openCards_carryTheirDepartures_asTheyChange() = runBlocking {
        val loads = MutableStateFlow(Picked(null, mapOf("a" to open, "b" to FartherLoad.Loading)))
        val opened = openedCards(loads, compute)

        val first = opened.first()
        // The cards' standing comes with their rows, from the same moment.
        assertSame(loads.value.loads, first.loads)
        assertEquals(1, first.cards.size)
        assertSame(open.distanceMeters.keys, first.cards.single().first)
        assertSame(clean, first.cards.single().second)

        val failed = clean.copy(refreshFailure = DeparturesUiState.Error.Kind.NETWORK)
        state.value = failed
        assertSame(failed, opened.first().cards.single().second)
    }

    @Test
    fun aCardsRows_comeFromTheModelItWasOpenedWith() = runBlocking {
        // Reopened on new poles with a new model: its rows are the new model's, never the old one's
        // under the new standing or the new one's under the old.
        val loads = MutableStateFlow(Picked(null, mapOf("a" to open)))
        val opened = openedCards(loads, compute)
        assertSame(clean, opened.first().cards.single().second)

        val other = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MC", "New pole", emptyList(), now)), fetchedAt = now)
        val reopened = FartherLoad.Open(listOf(StopLocation("MC", "New pole", 51.5, -0.12)), mapOf("MC" to 2100.0), MutableStateFlow(other))
        loads.value = Picked(null, mapOf("a" to reopened))
        val latest = opened.first()
        assertSame(loads.value.loads, latest.loads)
        assertSame(reopened.distanceMeters.keys, latest.cards.single().first)
        assertSame(other, latest.cards.single().second)
    }

    @Test
    fun aCardReopening_dropsItsRowsWithItsNewStanding() = runBlocking {
        // A bus card reopened on new poles goes back to loading: its old rows go in the same update.
        val loads = MutableStateFlow(Picked(null, mapOf("a" to open)))
        val opened = openedCards(loads, compute)
        assertEquals(1, opened.first().cards.size)

        loads.value = Picked(null, mapOf("a" to FartherLoad.Loading))
        val reopened = opened.first()
        assertSame(loads.value.loads, reopened.loads)
        assertTrue(reopened.cards.isEmpty())
    }

    @Test
    fun aPlaceNoLongerOffered_goesWithItsRows() = runBlocking {
        // A relocation drops a place: the new places and its closed card come in one update, so its
        // rows never outlive its card.
        val place = CollapsedPlaces.Place("a", "MA", "Farther", 2000.0, emptyList())
        val loads = MutableStateFlow(Picked(listOf(place), mapOf("a" to open)))
        val opened = openedCards(loads, compute)
        assertEquals(listOf(place), opened.first().offered)
        assertEquals(1, opened.first().cards.size)

        loads.value = Picked(emptyList(), emptyMap())
        val moved = opened.first()
        assertEquals(emptyList<CollapsedPlaces.Place>(), moved.offered)
        assertTrue(moved.cards.isEmpty())
    }

    @Test
    fun noOpenCard_carriesTheCardsWithNoRows() = runBlocking {
        val loads = mapOf<String, FartherLoad>("a" to FartherLoad.Loading)
        val none = openedCards(MutableStateFlow(Picked(null, loads)), compute).first()
        assertSame(loads, none.loads)
        assertTrue(none.cards.isEmpty())
    }
}
