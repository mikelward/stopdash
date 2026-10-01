package app.stopdash.telemetry

import app.stopdash.StopdashDebugLog
import app.stopdash.domain.UsageEvent

/**
 * Where the app's custom usage events go (SPEC *Privacy*): to Analytics, and only while the rider
 * has opted in to **Help make StopDash better**. Each event carries categories and buckets only
 * ([UsageEvent]); nothing a call site passes is sent as it is.
 *
 * An event is dropped, not held, while the stored choice hasn't loaded or reads no, and in a build
 * with no Firebase, where nothing is ever [install]ed. Sending is the SDK's own asynchronous log
 * call, so a tap never waits on it; a send that throws is logged and dropped, never rethrown into
 * the tap that raised it.
 */
object UsageEvents {
    @Volatile
    private var send: ((UsageEvent) -> Unit)? = null

    // The choice in force, read at each event; a seam for tests.
    @Volatile
    internal var consent: () -> Boolean? = { TelemetryConsent.state.value }

    /** Sends each opted-in event to [send] from now on. */
    fun install(send: (UsageEvent) -> Unit) {
        this.send = send
    }

    /** Sends [event] if the rider has opted in and there's somewhere to send it. */
    fun log(event: UsageEvent) {
        if (consent() != true) return
        val target = send ?: return
        try {
            target(event)
        } catch (e: Exception) {
            StopdashDebugLog.warning("telemetry: usage event %s failed: %s", event.name, e::class.simpleName)
        }
    }

    internal fun resetForTest() {
        send = null
        consent = { TelemetryConsent.state.value }
    }
}
