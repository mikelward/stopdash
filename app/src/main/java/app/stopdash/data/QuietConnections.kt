package app.stopdash.data

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Interceptor

/**
 * Which of one OkHttp client's connections have gone quiet, from the events of every request on them
 * ([listener]). A request that gets nothing in its whole wait, on a connection that answered no other
 * request meanwhile and is part-way through answering none, says that connection went quiet (seen after
 * a network change): TfL speaks HTTP/2, so every request to it in flight shares the one connection, and
 * each still waiting on it would wait out its own timeout for nothing, 30 s for a trip's plan. So the
 * connection is closed, and each request that was waiting on it fails at once as [ConnectionWentQuiet]
 * ([interceptor]), for [KtorTflClient] to ask once more on a fresh one with a rate token of its own, as
 * it does any retry. They're canceled before the close, so OkHttp doesn't quietly retry them beneath it,
 * and fail only once it's closed: asked again at once, a retry could otherwise be handed the quiet connection
 * still open, and OkHttp would ask it again itself once the close lands, a third time in all.
 * An answer on the connection meanwhile says it's answering and only the one request was slow, so it's
 * left alone, as is any other connection.
 *
 * Connections and calls are keys only, compared by identity: a test drives it with its own.
 */
internal class QuietConnections {
    private val lock = Any()
    private var events = 0L
    private val connections = IdentityHashMap<Any, Activity>()

    // Requests that were waiting on a connection closed for going quiet, until they're asked again, each with
    // the close it's failed after; and each such connection's close while it's under way.
    private val abandoned = IdentityHashMap<Any, CountDownLatch>()
    private val closing = IdentityHashMap<Any, CountDownLatch>()

    // What one connection is carrying: every request on it, those part-way through their answer, and
    // when it last started or finished one.
    private class Activity {
        val requests: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        val answering: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        var lastAnswer = 0L
    }

    /** How many connections are carrying a request now: one is forgotten once it carries none. */
    val tracked: Int get() = synchronized(lock) { connections.size }

    /** [request] took [connection]. */
    fun acquired(connection: Any, request: Any) {
        synchronized(lock) { connections.getOrPut(connection) { Activity() }.requests += request }
    }

    /** A request went out: the moment it's measured from ([timedOut]). */
    fun sent(): Long = synchronized(lock) { ++events }

    /** [request]'s answer started on [connection]: the moment it's now measured from. */
    fun answerStarted(connection: Any, request: Any): Long = synchronized(lock) {
        val at = ++events
        connections[connection]?.let {
            it.lastAnswer = at
            it.answering += request
        }
        at
    }

    /** [request]'s answer finished arriving on [connection]. */
    fun answerEnded(connection: Any, request: Any) {
        synchronized(lock) {
            val at = ++events
            connections[connection]?.let {
                it.lastAnswer = at
                it.answering -= request
            }
        }
    }

    /** [request] let go of [connection]; the connection is forgotten once it carries none. Repeating it is harmless. */
    fun released(connection: Any, request: Any) {
        synchronized(lock) {
            val activity = connections[connection] ?: return
            activity.requests -= request
            activity.answering -= request
            if (activity.requests.isEmpty()) connections -= connection
        }
    }

    /**
     * [request] got nothing on [connection] since [since]: the other requests waiting on it when it went
     * quiet with them, each marked as abandoned until the connection is [closed] ([takeAbandoned]), or null
     * when it answered another meanwhile or is part-way through answering one.
     */
    fun timedOut(connection: Any, request: Any, since: Long): List<Any>? = synchronized(lock) {
        val activity = connections[connection] ?: return@synchronized null
        val answeringOthers = activity.answering.any { it !== request }
        if (activity.lastAnswer > since || answeringOthers) return@synchronized null
        val waiting = activity.requests.filter { it !== request }
        // Two requests timing out on it at once share its one close.
        val close = closing.getOrPut(connection) { CountDownLatch(1) }
        waiting.forEach { abandoned[it] = close }
        waiting
    }

    /** [connection], gone quiet, is closed, or its close was tried: what was waiting on it may now fail. */
    fun closed(connection: Any) {
        synchronized(lock) { closing.remove(connection) }?.countDown()
    }

    /**
     * The close of the connection [request] was waiting on when it went quiet, for it to fail after; null
     * when it wasn't waiting on one. Once only.
     */
    fun takeAbandoned(request: Any): CountDownLatch? = synchronized(lock) { abandoned.remove(request) }

    /** [request] has ended, however: nothing more to ask of it. */
    fun ended(request: Any) {
        synchronized(lock) { abandoned -= request }
    }

    /**
     * Says so, as [ConnectionWentQuiet], when a request failed for waiting on a connection closed for going
     * quiet: an [application interceptor][okhttp3.OkHttpClient.Builder.addInterceptor], so it sees the
     * failure as the request's caller will.
     */
    fun interceptor(): Interceptor = Interceptor { chain ->
        try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            val close = takeAbandoned(chain.call()) ?: throw e
            // Failed once the connection is closed, so the caller's retry, asked at once, can't be handed it.
            try {
                close.await()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted waiting for a quiet connection to close").apply { initCause(e) }
            }
            throw ConnectionWentQuiet(e)
        }
    }

    /**
     * One request's events, for [OkHttp's event listener factory][okhttp3.OkHttpClient.Builder.eventListenerFactory]:
     * closes its connection when it times out there and the connection went quiet, saying so to [warn].
     */
    fun listener(warn: (String) -> Unit): EventListener = Listener(warn)

    private inner class Listener(private val warn: (String) -> Unit) : EventListener() {
        private var connection: Connection? = null
        private var since = 0L

        override fun connectionAcquired(call: Call, connection: Connection) {
            // A retry takes a fresh connection having let go of the last without saying so.
            this.connection?.let { if (it !== connection) released(it, call) }
            this.connection = connection
            acquired(connection, call)
        }

        override fun requestHeadersStart(call: Call) {
            since = sent()
        }

        override fun responseHeadersStart(call: Call) {
            connection?.let { since = answerStarted(it, call) }
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            connection?.let { answerEnded(it, call) }
        }

        override fun responseFailed(call: Call, ioe: IOException) {
            val quiet = connection ?: return
            if (ioe !is SocketTimeoutException) return
            val waiting = timedOut(quiet, call, since) ?: return
            try {
                // Counts only (SPEC *Privacy*): which requests is the refresh's business, not this line's.
                warn("connection went quiet; closed it under its ${waiting.size} other requests")
                // Canceled first: OkHttp retries no canceled request, so each is its caller's to ask again.
                waiting.forEach { (it as? Call)?.cancel() }
                try {
                    quiet.socket().close()
                } catch (e: IOException) {
                    // Left to OkHttp's own check of the connection, which it does after any timeout.
                    warn("closing a quiet connection failed (${e::class.simpleName})")
                }
            } finally {
                // However the close went, the requests canceled for it are failed now, never left waiting.
                closed(quiet)
            }
        }

        override fun connectionReleased(call: Call, connection: Connection) {
            released(connection, call)
            if (this.connection === connection) this.connection = null
        }

        override fun callEnd(call: Call) = done(call)

        override fun callFailed(call: Call, ioe: IOException) = done(call)

        // However the call ended, its connection no longer carries it, and it's not to be asked again.
        private fun done(call: Call) {
            connection?.let { released(it, call) }
            connection = null
            ended(call)
        }
    }
}

/**
 * A request that failed because the connection it was waiting on went quiet and was closed
 * ([QuietConnections]): asked once more on a fresh one, however long it was allowed to wait, since the
 * connection failed it, not its own wait.
 */
internal class ConnectionWentQuiet(cause: IOException) : IOException("its connection went quiet", cause)
