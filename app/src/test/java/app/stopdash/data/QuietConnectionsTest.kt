package app.stopdash.data

import app.stopdash.domain.TflRateLimiter
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietConnectionsTest {
    // Plain objects for connections and requests: the rule only compares them by identity.
    private val watch = QuietConnections()
    private val connection = Any()
    private val other = Any()
    private val stalled = Any()
    private val waiting = Any()
    private val answered = Any()

    @Test
    fun `a request that got nothing, on a connection that answered nothing since it went out, says the connection went quiet`() {
        watch.acquired(connection, waiting)
        watch.acquired(connection, stalled)
        val sent = watch.sent()
        assertEquals("the one other request waiting on it", listOf(waiting), watch.timedOut(connection, stalled, sent))
        assertTrue("the request waiting on it is told so", watch.takeAbandoned(waiting))
        assertFalse("once only", watch.takeAbandoned(waiting))
        assertFalse("the one that timed out failed for its own wait", watch.takeAbandoned(stalled))
    }

    @Test
    fun `an answer on the connection since the request went out says it's answering, and only that request was slow`() {
        watch.acquired(connection, waiting)
        watch.acquired(connection, stalled)
        watch.acquired(connection, answered)
        val sent = watch.sent()
        watch.answerStarted(connection, answered)
        watch.answerEnded(connection, answered)
        assertNull(watch.timedOut(connection, stalled, sent))
        assertFalse("nothing waiting on it is told so", watch.takeAbandoned(waiting))
    }

    @Test
    fun `an answer from before the request went out doesn't count`() {
        watch.acquired(connection, answered)
        watch.answerStarted(connection, answered)
        watch.answerEnded(connection, answered)
        watch.released(connection, answered)
        watch.acquired(connection, waiting)
        watch.acquired(connection, stalled)
        val sent = watch.sent()
        assertEquals(listOf(waiting), watch.timedOut(connection, stalled, sent))
    }

    @Test
    fun `another request part-way through its answer says the connection is answering`() {
        watch.acquired(connection, answered)
        watch.answerStarted(connection, answered)
        watch.acquired(connection, stalled)
        val sent = watch.sent()
        assertNull(watch.timedOut(connection, stalled, sent))
    }

    @Test
    fun `an answer on another connection says nothing of this one`() {
        watch.acquired(connection, stalled)
        watch.acquired(other, answered)
        val sent = watch.sent()
        watch.answerStarted(other, answered)
        watch.answerEnded(other, answered)
        assertEquals(emptyList<Any>(), watch.timedOut(connection, stalled, sent))
    }

    @Test
    fun `a request whose own answer started and then went quiet is measured from that start`() {
        watch.acquired(connection, waiting)
        watch.acquired(connection, stalled)
        watch.sent()
        val started = watch.answerStarted(connection, stalled)
        assertEquals(listOf(waiting), watch.timedOut(connection, stalled, started))
    }

    @Test
    fun `a request that ended is not told its connection went quiet`() {
        watch.acquired(connection, waiting)
        watch.acquired(connection, stalled)
        watch.timedOut(connection, stalled, watch.sent())
        watch.ended(waiting)
        assertFalse(watch.takeAbandoned(waiting))
    }

    @Test
    fun `a connection is forgotten once it carries no request`() {
        watch.acquired(connection, stalled)
        watch.acquired(other, waiting)
        watch.released(connection, stalled)
        watch.released(connection, stalled)
        assertEquals(1, watch.tracked)
        watch.released(other, waiting)
        assertEquals(0, watch.tracked)
    }

    // A local HTTP/2 server whose first connection hears every request and answers none, as one does after
    // a network change; a fresh connection answers at once. [heard] counts each path's requests.
    private val server = MockWebServer().apply { protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE) }
    private val quietUntilTheEnd = CountDownLatch(1)
    private val heard = ConcurrentHashMap<String, AtomicInteger>()
    private val heardOnTheQuietOne = ConcurrentHashMap<String, CountDownLatch>()
    private val pool = Executors.newCachedThreadPool()

    private fun heardOnTheQuietOne(path: String) = heardOnTheQuietOne.getOrPut(path) { CountDownLatch(1) }

    private fun heard(path: String) = heard[path]?.get() ?: 0

    private fun startServer(answer: (path: String) -> String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                heard.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                if (request.connectionIndex == 0) {
                    heardOnTheQuietOne(path).countDown()
                    quietUntilTheEnd.await()
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(answer(path)).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        quietUntilTheEnd.countDown()
        pool.shutdownNow()
        server.close()
    }

    @Test
    fun `requests waiting on a connection that went quiet fail at once as such, and OkHttp asks none of them again`() {
        startServer { "answered" }
        val warnings = CopyOnWriteArrayList<String>()
        // Plans may wait 30 s; a board, here, half a second.
        val plans = OkHttpClient.Builder()
            .tflDefaults({ warnings += it }, QuietConnections())
            .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val boards = plans.newBuilder().readTimeout(500, TimeUnit.MILLISECONDS).build()

        // The first opens the connection; the second joins it, so OkHttp would try another route for it.
        val first = pool.submit<Unit> { plans.newCall(Request(server.url("/first"))).execute().close() }
        assertTrue("the first plan reached the connection", heardOnTheQuietOne("/first").await(10, TimeUnit.SECONDS))
        val second = pool.submit<Unit> { plans.newCall(Request(server.url("/second"))).execute().close() }
        assertTrue("the second plan reached the same connection", heardOnTheQuietOne("/second").await(10, TimeUnit.SECONDS))
        assertThrows(SocketTimeoutException::class.java) {
            boards.newCall(Request(server.url("/board"))).execute()
        }

        for (plan in listOf(first, second)) {
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { plan.get(10, TimeUnit.SECONDS) }
            assertTrue("failed as its connection's doing: ${failure.cause}", failure.cause is ConnectionWentQuiet)
        }
        // Each asked once: a retry is the client's alone, so it takes a rate token and is the only one.
        assertEquals(listOf(1, 1), listOf(heard("/first"), heard("/second")))
        assertEquals(listOf("connection went quiet; closed it under its 2 other requests"), warnings.filter { "quiet" in it })
    }

    @Test
    fun `a trip's plan waiting on a connection that went quiet is asked once more, with a rate token, and answers`() {
        startServer { path -> if (path.startsWith("/Journey/")) """{ "journeys": [] }""" else "[]" }
        val warnings = CopyOnWriteArrayList<String>()
        // The production client ([KtorTflClient.defaultHttpClient]), where Ktor builds a plan's OkHttp
        // client apart from a board's, on the one connection pool.
        val http = KtorTflClient.defaultHttpClient({ warnings += it }) {
            protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            // A board's wait, cut short; a plan is still given 30 s ([KtorTflClient.SLOW_SOCKET_TIMEOUT_MILLIS]).
            readTimeout(500, TimeUnit.MILLISECONDS)
        }
        val tokens = AtomicInteger()
        val limiter = object : TflRateLimiter {
            override suspend fun acquire() {
                tokens.incrementAndGet()
            }
        }
        val client = KtorTflClient(
            httpClient = http, baseUrl = server.url("/").toString().trimEnd('/'),
            rateLimiterFor = { limiter }, warn = { warnings += it }, stallRetryDelayMillis = 0,
        )
        val planPath = "/Journey/JourneyResults/940GZZLUKSX/to/940GZZLUEUS"

        try {
            runBlocking {
                // King's Cross to Euston: big stations, public TfL ids (SPEC *Privacy*).
                val plan = async(Dispatchers.IO) { client.fewestChangesBetween("940GZZLUKSX", "940GZZLUEUS") }
                assertTrue(
                    "the plan reached the connection",
                    withContext(Dispatchers.IO) { heardOnTheQuietOne(planPath).await(10, TimeUnit.SECONDS) },
                )
                client.arrivals("940GZZLUEUS")
                assertEquals(emptyList<Any>(), withTimeout(10_000) { plan.await() })
            }
        } finally {
            http.close()
        }

        assertEquals("the plan was asked twice, not a third time", 2, heard(planPath))
        assertEquals("and the board twice, its own stall's one retry", 2, heard("/StopPoint/940GZZLUEUS/Arrivals"))
        assertEquals("each asking took a rate token", 4, tokens.get())
        assertEquals(1, warnings.count { it == "request was waiting on a connection that went quiet; asking once more" })
    }
}
