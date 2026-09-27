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
}
