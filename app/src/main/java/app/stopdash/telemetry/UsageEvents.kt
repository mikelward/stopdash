package app.stopdash.telemetry

import app.stopdash.StopdashDebugLog
import app.stopdash.domain.UsageEvent
import kotlinx.coroutines.CancellationException

/**
 * Where the app's custom usage events go (SPEC *Privacy*): to Analytics, and only while the rider
 * has opted in to **Help make StopDash better**. Each event carries categories and buckets only
 * ([UsageEvent]); nothing a call site passes is sent as it is.
 *
 * An event raised before the stored choice has loaded (a cold start's open and first screen) is
 * **held** in memory, at most [MAX_HELD] with the oldest dropped first, and [settle] then sends them on
 * a yes and drops them on a no, as the crash log's held lines are. Otherwise every cold start would go
 * uncounted. One raised while the choice reads no, or in a build with no Firebase (nothing ever
 * [install]ed), is dropped. Sending is the SDK's own asynchronous log call, so a tap never waits on it;
 * a send that throws is logged and dropped, never rethrown into the tap that raised it. A cancellation
 * is the exception: it ends the coroutine it came from ([settle] run by [whenConsentLoads]), as
 * structured concurrency asks.
 */
object UsageEvents {
    @Volatile
    private var send: ((UsageEvent) -> Unit)? = null

    // The choice in force, read at each event; a seam for tests.
    @Volatile
    internal var consent: () -> Boolean? = { TelemetryConsent.state.value }

    // Guards [held] and the consent read deciding whether an event joins it, so [settle] can't drain
    // between that read and the add, leaving an event held for good.
    private val lock = Any()
    private val held = ArrayDeque<UsageEvent>()

    /** Sends each opted-in event to [send] from now on. */
    fun install(send: (UsageEvent) -> Unit) {
        this.send = send
    }

    /** Sends [event] if the rider has opted in and there's somewhere to send it; holds it until the choice loads. */
    fun log(event: UsageEvent) {
        val target = send ?: return
        val optedIn = synchronized(lock) {
            consent().also { choice ->
                if (choice == null) {
                    if (held.size == MAX_HELD) held.removeFirst()
                    held.addLast(event)
                }
            }
        }
        if (optedIn == true) deliver(target, event)
    }

    /** A setting the rider changed: its event, and the standing choices sent again ([UsageProperties]). */
    fun settingChanged(event: UsageEvent.SettingChanged) = settingChanged(listOf(event))

    /** A change of setting that's several events ([UsageEvent.SettingChanged.tripModes]); none sends nothing. */
    fun settingChanged(events: List<UsageEvent.SettingChanged>) {
        if (events.isEmpty()) return
        events.forEach(::log)
        UsageProperties.refresh()
    }

    /** The stored choice has loaded: what was held is sent on a yes and dropped on a no. */
    fun settle() {
        val target = send
        val release = synchronized(lock) {
            if (consent() == null) return
            val all = held.toList()
            held.clear()
            all.takeIf { consent() == true }.orEmpty()
        }
        if (target != null) release.forEach { deliver(target, it) }
    }

    private fun deliver(target: (UsageEvent) -> Unit, event: UsageEvent) {
        try {
            target(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StopdashDebugLog.warning("telemetry: usage event %s failed: %s", event.name, e::class.simpleName)
        }
    }

    internal fun resetForTest() {
        send = null
        consent = { TelemetryConsent.state.value }
        synchronized(lock) { held.clear() }
        ScreenReports.left()
    }

    /** The most events held before the choice loads: a cold start raises a handful. */
    internal const val MAX_HELD = 16
}
