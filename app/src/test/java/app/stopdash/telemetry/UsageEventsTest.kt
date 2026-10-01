package app.stopdash.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stopdash.domain.UsageEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageEventsTest {
    private val sent = mutableListOf<UsageEvent>()

    @After
    fun reset() = UsageEvents.resetForTest()

    @Test
    fun `an event is sent only while the rider has opted in`() {
        var consent: Boolean? = null
        UsageEvents.consent = { consent }
        UsageEvents.install { sent += it }
        val tap = UsageEvent.Tapped(UsageEvent.Tap.SETTINGS)
        // Not yet loaded, then a no: dropped, not held for later.
        UsageEvents.log(tap)
        consent = false
        UsageEvents.log(tap)
        assertEquals(emptyList<UsageEvent>(), sent)
        consent = true
        UsageEvents.log(tap)
        assertEquals(listOf<UsageEvent>(tap), sent)
        // Withdrawn: the next one isn't sent.
        consent = false
        UsageEvents.log(UsageEvent.FarawayFavorites)
        assertEquals(listOf<UsageEvent>(tap), sent)
    }

    @Test
    fun `with nowhere to send, or a send that throws, the tap carries on`() {
        UsageEvents.consent = { true }
        UsageEvents.log(UsageEvent.FarawayFavorites) // no Firebase: nothing installed
        UsageEvents.install { throw IllegalStateException("sdk") }
        UsageEvents.log(UsageEvent.FarawayFavorites)
    }

    @Test
    fun `an event's bundle holds its parameters as strings, and nothing else`() {
        val event = UsageEvent.LocationFix(UsageEvent.FixOutcome.FRESH, 30f, java.time.Duration.ofSeconds(2))
        val bundle = usageEventBundle(event)
        assertEquals(event.params.keys, bundle.keySet())
        event.params.forEach { (key, value) -> assertEquals(value, bundle.getString(key)) }
    }
}
