package app.stopdash.data

import app.stopdash.StopdashDebugLog
import app.stopdash.domain.SlowRequest
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response

/**
 * Times each HTTP call through OkHttp's own events and hands a slow one, or one whose connect had to
 * be retried, to [warn] as one [SlowRequest] line: a refresh's total alone can't say which request
 * held it up, nor whether the time went on a stalled connection or a slow answer. One listener per
 * call ([factory]); its callbacks run on OkHttp's threads, never the main one.
 */
internal class SlowRequestListener(
    private val warn: (String) -> Unit,
    private val nanos: () -> Long = System::nanoTime,
) : EventListener() {
    private var startedAt = 0L
    private var connectionAt: Long? = null
    private var newConnection = false
    private val failedConnects = mutableListOf<String>()
    private var sentAt: Long? = null
    private var answerAt: Long? = null

    override fun callStart(call: Call) {
        startedAt = nanos()
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        newConnection = true
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        // The address family, not the address: whether IPv6 is the one stalling is the useful part.
        val family = if (inetSocketAddress.address is Inet6Address) "IPv6" else "IPv4"
        failedConnects += "$family ${ioe::class.simpleName}"
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        if (connectionAt == null) connectionAt = nanos()
    }

    override fun requestHeadersStart(call: Call) {
        sentAt = nanos()
    }

    override fun responseHeadersStart(call: Call) {
        answerAt = nanos()
    }

    override fun callEnd(call: Call) = finish(call, null)

    override fun callFailed(call: Call, ioe: IOException) = finish(call, ioe::class.simpleName)

    private fun finish(call: Call, failure: String?) {
        val endedAt = nanos()
        val sent = sentAt
        val answer = answerAt
        val timings = SlowRequest.Timings(
            host = call.request().url.host,
            path = call.request().url.encodedPath,
            totalMillis = millis(endedAt - startedAt),
            connectionMillis = connectionAt?.let { millis(it - startedAt) },
            newConnection = newConnection,
            failedConnects = failedConnects.toList(),
            answerMillis = if (sent != null && answer != null) millis(answer - sent) else null,
            readMillis = answer?.let { millis(endedAt - it) },
            failure = failure,
        )
        SlowRequest.describe(timings)?.let(warn)
    }

    private fun millis(nanos: Long) = nanos / 1_000_000

    companion object {
        fun factory(warn: (String) -> Unit): Factory = Factory { SlowRequestListener(warn) }
    }
}

/** The debug log's network lines: a slow request, a wait for a request slot. Coarse timing only. */
internal fun logNetworkWarning(message: String) = StopdashDebugLog.warning("network: %s", message)
