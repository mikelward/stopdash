package app.stopdash.telemetry

import app.stopdash.StopdashDebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Stands telemetry up at process start, failing closed: if creating the backend, registering the
 * log sink, or starting the consent load throws, [failClosed] still runs, switching any SDKs it can
 * reach off and publishing the choice as off. Otherwise an earlier opt-in could keep collecting from
 * the SDKs' persisted flags while the Settings switch, waiting on a load that never started, stays
 * disabled with no way to withdraw.
 *
 * A backend that throws while being created can't be reached to switch off; the switch is still
 * published as off and usable.
 */
internal fun startTelemetry(
    createBackend: () -> TelemetryBackend?,
    registerSink: () -> Unit,
    startLoad: (TelemetryBackend?) -> Unit,
    failClosed: (TelemetryGate?) -> Unit = TelemetryConsent::loadFailed,
) {
    var backend: TelemetryBackend? = null
    try {
        backend = createBackend()
        if (backend != null) registerSink()
        startLoad(backend)
    } catch (e: Exception) {
        StopdashDebugLog.warning("telemetry: setup failed: %s", e::class.simpleName)
        try {
            failClosed(backend?.let { TelemetryGate(it, NoPendingMarker) })
        } catch (e2: Exception) {
            StopdashDebugLog.warning("telemetry: fail-closed setup failed: %s", e2::class.simpleName)
        }
    }
}

/**
 * Acquires two SDK handles for one backend. If either can't be acquired, the one that was (or can
 * still be) is switched off before the failure is rethrown, with any failure switching it off
 * attached as suppressed. A half-built backend would otherwise leave the SDK it did reach
 * collecting, with no handle anywhere to stop it.
 */
internal fun <A, B> acquireBoth(
    first: () -> A,
    switchFirstOff: (A) -> Unit,
    second: () -> B,
    switchSecondOff: (B) -> Unit,
): Pair<A, B> {
    val a = try {
        first()
    } catch (e: Exception) {
        try {
            switchSecondOff(second())
        } catch (e2: Exception) {
            e.addSuppressed(e2)
        }
        throw e
    }
    val b = try {
        second()
    } catch (e: Exception) {
        try {
            switchFirstOff(a)
        } catch (e2: Exception) {
            e.addSuppressed(e2)
        }
        throw e
    }
    return a to b
}

/**
 * Runs [action] on [dispatcher] once [consent] has loaded, whichever way it went: the usage events held
 * until then are sent or dropped ([UsageEvents.settle]).
 */
internal fun whenConsentLoads(
    consent: StateFlow<Boolean?>,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    action: () -> Unit,
): Job = scope.launch(dispatcher) {
    consent.first { it != null }
    action()
}

/**
 * Settles [sink] as soon as [consent] has loaded, whichever way it went (a load, a fail-closed
 * load, or the user answering first), so the lines it held don't wait for the next log line.
 */
internal fun settleWhenConsentLoads(
    sink: CrashlyticsLogSink,
    consent: StateFlow<Boolean?>,
    scope: CoroutineScope,
): Job = scope.launch {
    consent.first { it != null }
    sink.settle()
}
