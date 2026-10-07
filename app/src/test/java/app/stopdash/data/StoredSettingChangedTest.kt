package app.stopdash.data

import app.stopdash.domain.AppSettings
import app.stopdash.domain.FontSizeSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StoredSettingHolder]'s change hook, which sends a setting's usage event: once per change the user
 * makes, from the value before to the one after, never for one that leaves it as it was, and off the
 * caller's thread (AGENTS.md *Main thread: read and dispatch only*).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredSettingChangedTest {

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

    private fun holder(scope: CoroutineScope, stored: MutableSharedFlow<Set<String>>, changed: (Set<String>, Set<String>) -> Unit) =
        StoredSettingHolder(
            scope,
            initial = emptySet(),
            read = { stored },
            write = { _, _ -> },
            label = "test modes",
            changed = changed,
        ).also { it.warm(NoSettings) }

    private fun TestScope.onScheduler() = CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))

    @Test
    fun `each change the user makes is reported once, from the value before`() = runTest {
        val stored = MutableSharedFlow<Set<String>>(replay = 1)
        val changes = mutableListOf<Pair<Set<String>, Set<String>>>()
        val holder = holder(onScheduler(), stored, changed = { before, after -> changes += before to after })
        stored.emit(setOf("bus"))
        runCurrent()
        // The stored value read back is no change of the user's.
        assertEquals(emptyList<Any>(), changes)
        holder.set(setOf("bus", "tram"))
        holder.set(setOf("bus", "tram"))
        holder.set(setOf("tram"))
        runCurrent()
        assertEquals(listOf(setOf("bus") to setOf("bus", "tram"), setOf("bus", "tram") to setOf("tram")), changes)
    }

    @Test
    fun `a change is reported on the holder's own thread, not the caller's`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "settings-worker") }
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            val stored = MutableSharedFlow<Set<String>>(replay = 1).also { it.tryEmit(emptySet()) }
            val reported = CountDownLatch(1)
            var ranOn = ""
            val holder = holder(scope, stored, changed = { _, _ ->
                ranOn = Thread.currentThread().name
                reported.countDown()
            })
            holder.set(setOf("dlr"))
            assertTrue(reported.await(5, TimeUnit.SECONDS))
            assertEquals("settings-worker", ranOn.substringBefore(" @"))
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }
}
