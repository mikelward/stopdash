package app.stopdash.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySetupTest {

    private class FakeBackend(override var collecting: Boolean) : TelemetryBackend {
        override fun switchCollection(enabled: Boolean) {
            collecting = enabled
        }
        override fun checkUnsent(result: (Boolean?) -> Unit) = result(false)
        override fun discardUnsent() {}
    }

    @Test
    fun `a sink that fails to register leaves an opted-in install off, with the switch usable`() {
        val backend = FakeBackend(collecting = true)
        val holder = TelemetryConsentHolder()
        var loadStarted = false
        startTelemetry(
            createBackend = { backend },
            registerSink = { throw IllegalStateException("sink") },
            startLoad = { loadStarted = true },
            failClosed = holder::loadFailed,
        )
        assertFalse(loadStarted)
        assertFalse(backend.collecting)
        assertEquals(false, holder.state.value)
    }

    @Test
    fun `a load that fails to start leaves the SDKs off`() {
        val backend = FakeBackend(collecting = true)
        val holder = TelemetryConsentHolder()
        startTelemetry(
            createBackend = { backend },
            registerSink = {},
            startLoad = { throw IllegalStateException("launch") },
            failClosed = holder::loadFailed,
        )
        assertFalse(backend.collecting)
        assertEquals(false, holder.state.value)
    }

    @Test
    fun `a backend that fails to build still shows the switch off`() {
        val holder = TelemetryConsentHolder()
        startTelemetry(
            createBackend = { throw IllegalStateException("firebase") },
            registerSink = {},
            startLoad = {},
            failClosed = holder::loadFailed,
        )
        assertEquals(false, holder.state.value)
    }

    @Test
    fun `a clean setup starts the load and leaves the choice to it`() {
        val backend = FakeBackend(collecting = false)
        val holder = TelemetryConsentHolder()
        var loaded: TelemetryBackend? = null
        var sinks = 0
        startTelemetry(
            createBackend = { backend },
            registerSink = { sinks++ },
            startLoad = { loaded = it },
            failClosed = holder::loadFailed,
        )
        assertTrue(loaded === backend)
        assertEquals(1, sinks)
        assertEquals(null, holder.state.value)
    }

    private class Sdk(var on: Boolean = true)

    @Test
    fun `an SDK that can't be reached leaves the one that was switched off`() {
        val analytics = Sdk()
        val thrown = runCatching {
            acquireBoth(
                first = { analytics },
                switchFirstOff = { it.on = false },
                second = { throw IllegalStateException("crashlytics") },
                switchSecondOff = { _: Sdk -> },
            )
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertFalse(analytics.on)
    }

    @Test
    fun `when the first SDK can't be reached, the second is still switched off`() {
        val crashlytics = Sdk()
        val thrown = runCatching {
            acquireBoth(
                first = { throw IllegalStateException("analytics") },
                switchFirstOff = { _: Sdk -> },
                second = { crashlytics },
                switchSecondOff = { it.on = false },
            )
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertFalse(crashlytics.on)
    }

    @Test
    fun `both SDKs reached are handed back untouched`() {
        val a = Sdk()
        val b = Sdk()
        val (first, second) = acquireBoth({ a }, { it.on = false }, { b }, { it.on = false })
        assertTrue(first === a && second === b && a.on && b.on)
    }

    @Test
    fun `the sink settles as soon as the stored choice loads, without waiting for another line`() {
        val sent = mutableListOf<String>()
        val consent = MutableStateFlow<Boolean?>(null)
        val sink = CrashlyticsLogSink({ consent.value }, { sent += it }, {}, { it.run() })
        val scope = TestScope(StandardTestDispatcher())
        settleWhenConsentLoads(sink, consent, scope)

        sink.log("held at startup")
        scope.testScheduler.advanceUntilIdle()
        assertTrue(sent.isEmpty())

        consent.value = true
        scope.testScheduler.advanceUntilIdle()
        assertEquals(listOf("held at startup"), sent)
    }
}
