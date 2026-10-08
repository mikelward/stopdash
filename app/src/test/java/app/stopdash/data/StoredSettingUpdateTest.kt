package app.stopdash.data

import app.stopdash.ThreadRecorder
import app.stopdash.domain.AppSettings
import app.stopdash.domain.FontSizeSettings
import app.stopdash.ui.HomeLines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StoredSettingHolder.update], as the disruptions summary's line chips use it
 * ([SummaryNetworksSetting.toggle]): each tap applied to the latest choice, in order, never to the
 * placeholder before the stored one is read, and off the caller's thread (Codex, #642).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredSettingUpdateTest {

    /** Settings whose only part in play is how the holder reads and writes. */
    private object NoSettings : AppSettings {
        override fun userApiKey() = flowOf<String?>(null)
        override suspend fun setUserApiKey(key: String?) {}
        override fun liveWidgetRefresh() = flowOf(false)
        override suspend fun setLiveWidgetRefresh(enabled: Boolean) {}
        override fun fontSize() = flowOf(FontSizeSettings())
        override suspend fun setFontScale(scale: Float) {}
        override suspend fun setPinchEnabled(enabled: Boolean) {}
        override fun skipBugReportConsent() = flowOf(false)
        override suspend fun setSkipBugReportConsent(enabled: Boolean) {}
    }

    private fun holder(scope: CoroutineScope, stored: MutableSharedFlow<Set<String>>, written: MutableList<Set<String>>) =
        StoredSettingHolder(
            scope,
            initial = emptySet(),
            read = { stored },
            write = { _, value -> written += value },
            label = "test lines",
        ).also { it.warm(NoSettings) }

    // On the test scheduler, run with [runCurrent]: it never moves past the load timeout.
    private fun TestScope.onScheduler() = CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))

    @Test
    fun `quick taps each apply to the latest choice, in order`() = runTest {
        val stored = MutableSharedFlow<Set<String>>(replay = 1)
        val written = mutableListOf<Set<String>>()
        val holder = holder(onScheduler(), stored, written)
        stored.emit(setOf("dlr"))
        runCurrent()
        holder.update { HomeLines.toggle(it, "victoria") }
        holder.update { HomeLines.toggle(it, "central") }
        holder.update { HomeLines.toggle(it, "victoria") }
        runCurrent()
        assertEquals(setOf("dlr", "central"), holder.current)
        assertEquals(listOf(setOf("dlr", "victoria"), setOf("dlr", "victoria", "central"), setOf("dlr", "central")), written)
    }

    @Test
    fun `a tap before the stored choice is read waits for it`() = runTest {
        val stored = MutableSharedFlow<Set<String>>(replay = 1)
        val written = mutableListOf<Set<String>>()
        val holder = holder(onScheduler(), stored, written)
        holder.update { HomeLines.toggle(it, "victoria") }
        runCurrent()
        assertTrue("nothing applied to the placeholder", written.isEmpty())
        stored.emit(setOf("tram"))
        runCurrent()
        assertEquals(setOf("tram", "victoria"), holder.current)
    }

    @Test
    fun `a tap waits for what it reads besides the setting, and the next waits behind it`() = runTest {
        val stored = MutableSharedFlow<Set<String>>(replay = 1)
        val written = mutableListOf<Set<String>>()
        val holder = holder(onScheduler(), stored, written)
        stored.emit(setOf("tram"))
        runCurrent()
        val keyRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        holder.update(awaitFirst = { keyRead.await() }) { HomeLines.toggle(it, "victoria") }
        holder.update { HomeLines.toggle(it, "central") }
        runCurrent()
        assertTrue("nothing applied before the key is read", written.isEmpty())
        keyRead.complete(Unit)
        runCurrent()
        assertEquals(listOf(setOf("tram", "victoria"), setOf("tram", "victoria", "central")), written)
    }

    @Test
    fun `settled waits for a tap asked before it`() = runTest {
        val stored = MutableSharedFlow<Set<String>>(replay = 1)
        val written = mutableListOf<Set<String>>()
        val holder = holder(onScheduler(), stored, written)
        stored.emit(setOf("tram"))
        runCurrent()
        holder.update { HomeLines.toggle(it, "victoria") }
        val settled = async { holder.settled() }
        runCurrent()
        assertEquals(setOf("tram", "victoria"), settled.await())
    }

    @Test
    fun `an edit counts as pending from the ask until it is in force`() = runTest {
        val stored = MutableSharedFlow<Set<String>>(replay = 1)
        val written = mutableListOf<Set<String>>()
        val holder = holder(onScheduler(), stored, written)
        holder.update { HomeLines.toggle(it, "victoria") }
        // Pending at once, before the stored value is read and the edit can run.
        assertEquals(1, holder.editsPending.value)
        stored.emit(setOf("tram"))
        runCurrent()
        assertEquals(setOf("tram", "victoria"), holder.current)
        assertEquals(0, holder.editsPending.value)
    }

    @Test
    fun `a tap is worked out on the holder's own thread, not the caller's`() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "settings-worker") }
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            val stored = MutableSharedFlow<Set<String>>(replay = 1).also { it.tryEmit(emptySet()) }
            val holder = holder(scope, stored, mutableListOf())
            val ranOn = ThreadRecorder()
            holder.update { ranOn.note(); HomeLines.toggle(it, "dlr") }
            runBlocking { withTimeout(5_000) { holder.changes.first { "dlr" in it } } }
            assertEquals(listOf("settings-worker"), ranOn.threads())
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }
}
