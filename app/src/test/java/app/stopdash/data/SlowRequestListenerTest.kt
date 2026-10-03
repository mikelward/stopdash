package app.stopdash.data

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import okhttp3.Connection
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

/** The OkHttp events a call goes through, timed into one log line when it was slow. */
class SlowRequestListenerTest {
    private val call = OkHttpClient().newCall(
        Request.Builder().url("https://api.tfl.gov.uk/StopPoint/490000000A,490000000B/Disruption").build(),
    )

    // Never read: the listener only notes when a connection was in hand.
    private val connection = object : Connection {
        override fun route() = error("unused")
        override fun socket() = error("unused")
        override fun handshake() = null
        override fun protocol() = error("unused")
    }

    private var now = 0L
    private val logged = mutableListOf<String>()
    private val listener = SlowRequestListener(warn = { logged += it }, nanos = { now })

    private fun at(millis: Long) {
        now = millis * 1_000_000
    }

    @Test
    fun `a connect that stalled and was retried on another address is logged with where the time went`() {
        val v6 = InetSocketAddress(InetAddress.getByName("::1"), 443)
        val v4 = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 443)
        at(0); listener.callStart(call)
        listener.connectStart(call, v6, Proxy.NO_PROXY)
        at(10_000); listener.connectFailed(call, v6, Proxy.NO_PROXY, null, SocketTimeoutException())
        listener.connectStart(call, v4, Proxy.NO_PROXY)
        at(10_004); listener.connectionAcquired(call, connection)
        listener.requestHeadersStart(call)
        at(10_012); listener.responseHeadersStart(call)
        at(10_015); listener.callEnd(call)

        assertEquals(
            listOf(
                "TfL StopPoint/…/Disruption took 10015 ms: connection 10004 ms (new; 1 failed: IPv6 SocketTimeoutException), " +
                    "answer after 8 ms, read 3 ms",
            ),
            logged,
        )
    }

    @Test
    fun `a quick call on a reused connection logs nothing`() {
        at(0); listener.callStart(call)
        at(1); listener.connectionAcquired(call, connection)
        listener.requestHeadersStart(call)
        at(200); listener.responseHeadersStart(call)
        at(210); listener.callEnd(call)

        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun `a call that timed out waiting for its answer is logged as failed`() {
        at(0); listener.callStart(call)
        at(2); listener.connectionAcquired(call, connection)
        listener.requestHeadersStart(call)
        at(10_002); listener.callFailed(call, SocketTimeoutException())

        assertEquals(
            listOf("TfL StopPoint/…/Disruption took 10002 ms: connection 2 ms (reused), failed: SocketTimeoutException"),
            logged,
        )
    }
}
