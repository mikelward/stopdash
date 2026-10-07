package app.stopdash.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stopdash.domain.UsageEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class UsageEventsTest {
    private val sent = mutableListOf<UsageEvent>()

    @After
    fun reset() = UsageEvents.resetForTest()

    @Test
    fun `an event is sent only while the rider has opted in`() {
        var consent: Boolean? = false
        UsageEvents.consent = { consent }
        UsageEvents.install { sent += it }
        val tap = UsageEvent.Tapped(UsageEvent.Tap.SETTINGS)
        // A no: dropped, not held for later.
        UsageEvents.log(tap)
        consent = true
        UsageEvents.settle()
        assertEquals(emptyList<UsageEvent>(), sent)
        UsageEvents.log(tap)
        assertEquals(listOf<UsageEvent>(tap), sent)
        // Withdrawn: the next one isn't sent.
        consent = false
        UsageEvents.log(UsageEvent.FarawayFavorites)
        assertEquals(listOf<UsageEvent>(tap), sent)
    }

    @Test
    fun `events raised before the choice loads are held, then sent on a yes`() {
        var consent: Boolean? = null
        UsageEvents.consent = { consent }
        UsageEvents.install { sent += it }
        val open = UsageEvent.Opened(UsageEvent.OpenedFrom.WIDGET)
        val home = UsageEvent.ScreenView(UsageEvent.Screen.HOME)
        UsageEvents.log(open)
        UsageEvents.log(home)
        assertEquals(emptyList<UsageEvent>(), sent)
        consent = true
        UsageEvents.settle()
        assertEquals(listOf<UsageEvent>(open, home), sent)
        // Released once: a second settle sends nothing again.
        UsageEvents.settle()
        assertEquals(listOf<UsageEvent>(open, home), sent)
    }

    @Test
    fun `events held before the choice loads are dropped on a no`() {
        var consent: Boolean? = null
        UsageEvents.consent = { consent }
        UsageEvents.install { sent += it }
        UsageEvents.log(UsageEvent.Opened(UsageEvent.OpenedFrom.LAUNCHER))
        consent = false
        UsageEvents.settle()
        consent = true
        UsageEvents.settle()
        assertEquals(emptyList<UsageEvent>(), sent)
    }

    @Test
    fun `only the latest few are held, the oldest dropped first`() {
        var consent: Boolean? = null
        UsageEvents.consent = { consent }
        UsageEvents.install { sent += it }
        val taps = List(UsageEvents.MAX_HELD + 2) { if (it < 2) UsageEvent.FarawayFavorites else UsageEvent.Tapped(UsageEvent.Tap.SEARCH) }
        taps.forEach(UsageEvents::log)
        consent = true
        UsageEvents.settle()
        assertEquals(taps.drop(2), sent)
    }

    @Test
    fun `nothing is held in a build with nowhere to send`() {
        var consent: Boolean? = null
        UsageEvents.consent = { consent }
        UsageEvents.log(UsageEvent.FarawayFavorites)
        UsageEvents.install { sent += it }
        consent = true
        UsageEvents.settle()
        assertEquals(emptyList<UsageEvent>(), sent)
    }

    @Test
    fun `with nowhere to send, or a send that throws, the tap carries on`() {
        UsageEvents.consent = { true }
        UsageEvents.log(UsageEvent.FarawayFavorites) // no Firebase: nothing installed
        UsageEvents.install { throw IllegalStateException("sdk") }
        UsageEvents.log(UsageEvent.FarawayFavorites)
    }

    @Test
    fun `a cancellation from the send ends the release rather than being taken for a failure`() = runTest {
        val consent = MutableStateFlow<Boolean?>(null)
        UsageEvents.consent = { consent.value }
        UsageEvents.install { sent += it; throw CancellationException("stopping") }
        val open = UsageEvent.Opened(UsageEvent.OpenedFrom.LAUNCHER)
        UsageEvents.log(open)
        UsageEvents.log(UsageEvent.ScreenView(UsageEvent.Screen.HOME))
        val release = whenConsentLoads(consent, backgroundScope, StandardTestDispatcher(testScheduler), UsageEvents::settle)
        consent.value = true
        runCurrent()
        // The coroutine releasing them is canceled, as structured concurrency asks, not carried past it.
        assertTrue(release.isCancelled)
        assertEquals(listOf<UsageEvent>(open), sent)
    }

    @Test
    fun `an event's bundle holds its parameters as strings, and nothing else`() {
        val event = UsageEvent.LocationFix(UsageEvent.FixOutcome.FRESH, 30f, java.time.Duration.ofSeconds(2))
        val bundle = usageEventBundle(event)
        assertEquals(event.params.keys, bundle.keySet())
        event.params.forEach { (key, value) -> assertEquals(value, bundle.getString(key)) }
    }
}
