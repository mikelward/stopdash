package app.stopdash.widget

import app.stopdash.domain.Departure
import app.stopdash.domain.LineStatus
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The widget refresh's status lookup: which failures are answers and which leave checks to age out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetLineStatusesTest {
    private class StatusClient(private val answer: () -> List<LineStatus>) : TflClient {
        override suspend fun arrivals(stopId: String): List<Departure> = emptyList()
        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = answer()
        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
    }

    @Test
    fun `lines TfL doesn't know are an empty answer, so they get no-verdict checks and aren't re-asked`() = runTest {
        val client = StatusClient { throw TflException.NotFound(null) }
        assertEquals(emptyList<LineStatus>(), widgetLineStatuses(client, setOf("southern")))
    }

    @Test
    fun `any other failure is no answer, leaving the prior checks to age out`() = runTest {
        val client = StatusClient { throw RuntimeException("offline") }
        assertNull(widgetLineStatuses(client, setOf("victoria")))
    }

    @Test
    fun `an answer passes through`() = runTest {
        val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")
        assertEquals(listOf(good), widgetLineStatuses(StatusClient { listOf(good) }, setOf("victoria")))
    }

    @Test
    fun `a refresh forgets a dismissal once TfL answers with a different status for its line`() = runTest {
        val severe = LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val closure = app.stopdash.domain.DismissedAlert("940GEXAMPLE", "Closed")
        var stored = setOf(app.stopdash.domain.DismissedAlert.ofLineStatus(severe), closure,
            app.stopdash.domain.DismissedAlert.ofLineStatus(severe.copy(lineId = "jubilee")))
        val store = object : app.stopdash.domain.DismissedAlertsStore {
            override fun dismissed() = kotlinx.coroutines.flow.flowOf(stored)
            override suspend fun dismiss(alert: app.stopdash.domain.DismissedAlert) {}
            override suspend fun reconcile(live: Set<app.stopdash.domain.DismissedAlert>, checkedPlaces: Set<String>) {
                stored = app.stopdash.domain.Dismissed.reconcile(stored, live, checkedPlaces)
            }
        }
        // Victoria is good now; Jubilee wasn't asked about; the closure isn't a line.
        reconcileWidgetDismissals(store, listOf(LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")))
        assertEquals(setOf(closure, app.stopdash.domain.DismissedAlert.ofLineStatus(severe.copy(lineId = "jubilee"))), stored)
        // The same alert still live keeps its dismissal.
        stored = stored + app.stopdash.domain.DismissedAlert.ofLineStatus(severe)
        reconcileWidgetDismissals(store, listOf(severe))
        assertEquals(true, app.stopdash.domain.DismissedAlert.ofLineStatus(severe) in stored)
    }

    @Test
    fun `a line still waiting on its alerts' directions keeps its direction dismissals`() = runTest {
        val north = LineStatus("victoria", 6, "Severe Delays", "Signal failure northbound.")
        val dismissal = app.stopdash.domain.DismissedAlert.ofLineStatus(north)
        var stored = setOf(dismissal)
        val store = object : app.stopdash.domain.DismissedAlertsStore {
            override fun dismissed() = kotlinx.coroutines.flow.flowOf(stored)
            override suspend fun dismiss(alert: app.stopdash.domain.DismissedAlert) {}
            override suspend fun reconcile(live: Set<app.stopdash.domain.DismissedAlert>, checkedPlaces: Set<String>) {
                stored = app.stopdash.domain.Dismissed.reconcile(stored, live, checkedPlaces)
            }
        }
        // Not split yet, so the northbound alert isn't among the line's statuses: not a sign it ended.
        reconcileWidgetDismissals(store, listOf(LineStatus("victoria", 6, "Severe Delays", "Both.", awaitingDirections = true)))
        assertEquals(setOf(dismissal), stored)
        // Once split, and the northbound alert gone, it's forgotten.
        reconcileWidgetDismissals(store, listOf(LineStatus("victoria", 6, "Severe Delays", "Both.")))
        assertEquals(emptySet<app.stopdash.domain.DismissedAlert>(), stored)
    }
}
