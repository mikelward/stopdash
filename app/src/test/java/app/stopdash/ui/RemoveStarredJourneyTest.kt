package app.stopdash.ui

import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.StarredJourney
import app.stopdash.domain.StarredJourneysStore
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.WidgetJourneysReport
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' Remove unstars a journey and unpins it from the widget at once (SPEC *Journeys*). */
class RemoveStarredJourneyTest {
    // Public TfL interchanges only (SPEC *Privacy*).
    private val victoria = StarredJourney(JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras"), "victoria")
    private val northern = StarredJourney(JourneyEnd("940GZZLUEUS", "Euston"), JourneyEnd("940GZZLUWLO", "Waterloo"), "northern")

    private class FakeJourneys(initial: List<StarredJourney>?) : StarredJourneysStore {
        val state = MutableStateFlow(initial)
        override fun journeys(): Flow<List<StarredJourney>?> = state
        override suspend fun toggle(journey: StarredJourney) {}
        override suspend fun remove(journey: StarredJourney) {
            state.value = state.value?.filterNot { it.key == journey.key }
        }
    }

    private class FakeWidget(val fail: Boolean = false) : SnapshotStore by SnapshotStore.NONE {
        val reports = mutableListOf<WidgetJourneysReport>()
        override suspend fun updateWidgetJourneys(report: WidgetJourneysReport, origins: List<StopArrivals>) {
            if (fail) throw IOException("disk")
            reports += report
        }
    }

    @Test
    fun `removing a journey unpins it from the widget, keeping the others`() = runTest {
        val journeys = FakeJourneys(listOf(victoria, northern))
        val widget = FakeWidget()
        removeStarredJourney(victoria, journeys, widget, Mutex())
        assertEquals(listOf(northern), journeys.state.value)
        assertEquals(setOf(northern.key), widget.reports.single().keys)
        assertTrue(widget.reports.single().checks.isEmpty())
    }

    @Test
    fun `a failed unpin is logged, not reported as a failed removal`() = runTest {
        val journeys = FakeJourneys(listOf(victoria))
        val warnings = mutableListOf<String>()
        removeStarredJourney(victoria, journeys, FakeWidget(fail = true), Mutex(), warn = { warnings += it })
        assertEquals(emptyList<StarredJourney>(), journeys.state.value)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `an unreadable store after the removal leaves the pins for the main screen`() = runTest {
        val journeys = object : StarredJourneysStore {
            override fun journeys(): Flow<List<StarredJourney>?> = MutableStateFlow(null)
            override suspend fun toggle(journey: StarredJourney) {}
            override suspend fun remove(journey: StarredJourney) {}
        }
        val widget = FakeWidget()
        removeStarredJourney(victoria, journeys, widget, Mutex())
        assertTrue(widget.reports.isEmpty())
    }

    @Test
    fun `the work runs off the caller's thread`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "remove-worker") }
        try {
            var ranOn: String? = null
            val journeys = object : StarredJourneysStore {
                override fun journeys(): Flow<List<StarredJourney>?> = MutableStateFlow(emptyList())
                override suspend fun toggle(journey: StarredJourney) {}
                override suspend fun remove(journey: StarredJourney) {
                    ranOn = Thread.currentThread().name
                }
            }
            removeStarredJourney(victoria, journeys, FakeWidget(), Mutex(), worker = worker.asCoroutineDispatcher())
            // Debug builds suffix the coroutine's name to the thread's.
            assertTrue(ranOn.orEmpty().startsWith("remove-worker"))
            assertTrue(!Thread.currentThread().name.startsWith("remove-worker"))
        } finally {
            worker.shutdown()
        }
    }
}
