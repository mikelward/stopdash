package app.stopdash.domain

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class SlowRequestTest {
    private fun timings(
        totalMillis: Long,
        connectionMillis: Long? = 5,
        newConnection: Boolean = false,
        failedConnects: List<String> = emptyList(),
        answerMillis: Long? = 100,
        readMillis: Long? = 10,
        failure: String? = null,
    ) = SlowRequest.Timings(
        host = "api.tfl.gov.uk",
        path = "/StopPoint/490000000A,490000000B/Disruption",
        totalMillis = totalMillis,
        connectionMillis = connectionMillis,
        newConnection = newConnection,
        failedConnects = failedConnects,
        answerMillis = answerMillis,
        readMillis = readMillis,
        failure = failure,
    )

    @Test
    fun `a quick request with every connect working is not logged`() {
        assertNull(SlowRequest.describe(timings(totalMillis = 300)))
    }

    @Test
    fun `a slow answer says where the time went`() {
        assertEquals(
            "TfL StopPoint/…/Disruption took 9800 ms: connection 5 ms (reused), answer after 9700 ms, read 95 ms",
            SlowRequest.describe(timings(totalMillis = 9_800, answerMillis = 9_700, readMillis = 95)),
        )
    }

    @Test
    fun `a stalled connect retried on another address is told, however quick the rest`() {
        assertEquals(
            "TfL StopPoint/…/Disruption took 10015 ms: connection 10004 ms (new; 1 failed: IPv6 SocketTimeoutException), " +
                "answer after 8 ms, read 3 ms",
            SlowRequest.describe(
                timings(
                    totalMillis = 10_015,
                    connectionMillis = 10_004,
                    newConnection = true,
                    failedConnects = listOf("IPv6 SocketTimeoutException"),
                    answerMillis = 8,
                    readMillis = 3,
                ),
            ),
        )
    }

    @Test
    fun `a failed connect is logged even when the request was quick`() {
        val line = SlowRequest.describe(
            timings(totalMillis = 400, newConnection = true, failedConnects = listOf("IPv6 ConnectException")),
        )
        assertEquals(
            "TfL StopPoint/…/Disruption took 400 ms: connection 5 ms (new; 1 failed: IPv6 ConnectException), " +
                "answer after 100 ms, read 10 ms",
            line,
        )
    }

    @Test
    fun `a request that never connected says so and why it failed`() {
        assertEquals(
            "TfL StopPoint/…/Disruption took 20010 ms: no connection (2 failed: IPv6 SocketTimeoutException, " +
                "IPv4 SocketTimeoutException), failed: SocketTimeoutException",
            SlowRequest.describe(
                timings(
                    totalMillis = 20_010,
                    connectionMillis = null,
                    failedConnects = listOf("IPv6 SocketTimeoutException", "IPv4 SocketTimeoutException"),
                    answerMillis = null,
                    readMillis = null,
                    failure = "SocketTimeoutException",
                ),
            ),
        )
    }

    @Test
    fun `an endpoint keeps the API's words and leaves every identifier out`() {
        assertEquals("StopPoint/…/Arrivals", SlowRequest.endpoint("/StopPoint/940GZZLUVIC/Arrivals"))
        assertEquals("Line/…/Status", SlowRequest.endpoint("/Line/victoria,district/Status"))
        assertEquals("StopPoint/…", SlowRequest.endpoint("/StopPoint/HUBKGX"))
        assertEquals("Line/…/Route/Sequence/…", SlowRequest.endpoint("/Line/88/Route/Sequence/inbound"))
        assertEquals("StopPoint", SlowRequest.endpoint("/StopPoint"))
        assertEquals("…/GetDepartureBoard/…", SlowRequest.endpoint("/1010-live/LDBWS/api/20220120/GetDepartureBoard/KGX"))
        assertEquals("StopPoint/Search", SlowRequest.endpoint("/StopPoint/Search"))
        assertEquals("other", SlowRequest.endpoint("/Some/New/Endpoint"))
    }

    @Test
    fun `a typed search that looks like a path word is still left out`() {
        // A place search sends the typed text as a path segment; one capitalized word must not pass.
        assertEquals("Journey/JourneyResults/…/to/…", SlowRequest.endpoint("/Journey/JourneyResults/MyHome/to/940GZZLUKSX"))
        assertEquals("StopPoint/…", SlowRequest.endpoint("/StopPoint/Somewhere"))
        // Even one spelling a path word: it's left out by where it sits, not what it says.
        assertEquals("Journey/JourneyResults/…/to/…", SlowRequest.endpoint("/Journey/JourneyResults/Line/to/940GZZLUKSX"))
    }

    @Test
    fun `a request is named by who it went to`() {
        assertEquals("TfL", SlowRequest.source("api.tfl.gov.uk"))
        assertEquals("National Rail", SlowRequest.source("api1.raildata.org.uk"))
    }
}
