package app.stopdash.ui

import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.LineRef
import app.stopdash.domain.FavoriteJourneysStore
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.Journeys
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.WidgetJourneysReport
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hiding a line from a ride drops that line's widget pins at once, as Settings' Remove drops a ride's
 * (SPEC *Hiding a line from a ride*), so a process that dies before the main screen's next report
 * doesn't leave them on the widget.
 */
class SetRideLineHiddenTest {
    // Public TfL interchanges only (SPEC *Privacy*).
    private val kingsCross = FavoriteJourney(JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras"), JourneyEnd("940GZZLUVIC", "Victoria"), listOf(LineRef("victoria", "", "")))
    private val euston = FavoriteJourney(JourneyEnd("940GZZLUEUS", "Euston"), JourneyEnd("940GZZLUWLO", "Waterloo"), listOf(LineRef("northern", "", "")))
    private val piccadilly = HiddenModes.lineKey("piccadilly", "Piccadilly")

    private class FakeJourneys(initial: List<FavoriteJourney>?) : FavoriteJourneysStore {
        val state = MutableStateFlow(initial)
        var ranOn: String? = null
        override fun journeys(): Flow<List<FavoriteJourney>?> = state
        override suspend fun toggle(journey: FavoriteJourney) {}
        override suspend fun add(journey: FavoriteJourney) {}
        override suspend fun remove(journey: FavoriteJourney) {}
        override suspend fun setLineHidden(journey: FavoriteJourney, entry: String, hidden: Boolean) {
            ranOn = Thread.currentThread().name
            state.value = state.value?.let { Journeys.withLineHidden(it, journey, entry, hidden) }
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
    fun `a hide unpins the line from that ride's widget pins, keeping every ride`() = runTest {
        val journeys = FakeJourneys(listOf(kingsCross, euston))
        val widget = FakeWidget()
        setRideLineHidden(kingsCross, piccadilly, true, journeys, widget, Mutex())
        assertEquals(setOf(piccadilly), journeys.state.value?.first()?.hiddenLines)
        val report = widget.reports.single()
        assertEquals(setOf(kingsCross.key, euston.key), report.keys)
        assertTrue(report.checks.isEmpty())
        assertEquals(mapOf(kingsCross.key to setOf("piccadilly")), report.hidden)
    }

    @Test
    fun `showing a line again writes no pins, since showing one pins nothing`() = runTest {
        val journeys = FakeJourneys(listOf(kingsCross.copy(hiddenLines = setOf(piccadilly))))
        val widget = FakeWidget()
        setRideLineHidden(kingsCross, piccadilly, false, journeys, widget, Mutex())
        assertEquals(emptySet<String>(), journeys.state.value?.single()?.hiddenLines)
        assertTrue(widget.reports.isEmpty())
    }

    @Test
    fun `a failed unpin is logged, not reported as a failed hide`() = runTest {
        val journeys = FakeJourneys(listOf(kingsCross))
        val warnings = mutableListOf<String>()
        setRideLineHidden(kingsCross, piccadilly, true, journeys, FakeWidget(fail = true), Mutex(), warn = { warnings += it })
        assertEquals(setOf(piccadilly), journeys.state.value?.single()?.hiddenLines)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `an unreadable store after the hide leaves the pins for the main screen`() = runTest {
        val widget = FakeWidget()
        setRideLineHidden(kingsCross, piccadilly, true, FakeJourneys(null), widget, Mutex())
        assertTrue(widget.reports.isEmpty())
    }

    @Test
    fun `the work runs off the caller's thread`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "ride-line-worker") }
        try {
            val journeys = FakeJourneys(listOf(kingsCross))
            setRideLineHidden(kingsCross, piccadilly, true, journeys, FakeWidget(), Mutex(), worker = worker.asCoroutineDispatcher())
            // Debug builds suffix the coroutine's name to the thread's.
            assertTrue(journeys.ranOn.orEmpty().startsWith("ride-line-worker"))
            assertTrue(!Thread.currentThread().name.startsWith("ride-line-worker"))
        } finally {
            worker.shutdown()
        }
    }
}
