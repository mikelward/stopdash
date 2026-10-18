package app.stopdash.domain

import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class RailIncidentsTest {
    @After
    fun tearDown() {
        SteadyClock.source = null
    }

    private val link = "https://www.nationalrail.co.uk/service-disruptions/london-euston-20261017/"
    private val incident = RailIncident("Disruption at London Euston", "Lines are blocked.", listOf(link))

    private fun linkOnly(text: String = link) = LineStatus("avanti-west-coast", 0, "Special Service", fullText = text)

    @Test
    fun `only a bare National Rail disruption link counts as one`() {
        assertEquals("nationalrail.co.uk/service-disruptions/london-euston-20261017", RailIncidents.linkOf(" $link "))
        assertEquals(
            "nationalrail.co.uk/service-disruptions/london-euston-20261017",
            RailIncidents.linkOf("http://nationalrail.co.uk/service-disruptions/London-Euston-20261017"),
        )
        assertNull(RailIncidents.linkOf("Minor delays. See $link"))
        assertNull(RailIncidents.linkOf("https://www.nationalrail.co.uk/journey-planner/"))
        assertNull(RailIncidents.linkOf("https://example.com/service-disruptions/x/"))
        assertNull(RailIncidents.linkOf(null))
    }

    @Test
    fun `the incident's summary and text replace the link, which is kept after them`() {
        val status = RailIncidents.withIncidents(listOf(linkOnly()), listOf(incident)).single()
        assertEquals(link, status.fullText)
        assertEquals("Disruption at London Euston\n\nLines are blocked.\n\n$link", status.shownText)
    }

    @Test
    fun `a link matches however either side writes it`() {
        val written = RailIncident("Disruption at London Euston", null, listOf("https://nationalrail.co.uk/service-disruptions/london-euston-20261017?utm=x"))
        val status = RailIncidents.withIncidents(listOf(linkOnly()), listOf(written)).single()
        assertEquals("Disruption at London Euston\n\n$link", status.shownText)
    }

    @Test
    fun `TfL's own words and an unmatched link are left as they are`() {
        val words = linkOnly("Minor delays between Euston and Watford Junction.")
        val other = linkOnly("https://www.nationalrail.co.uk/service-disruptions/elsewhere-20261017/")
        val (a, b) = RailIncidents.withIncidents(listOf(words, other), listOf(incident))
        assertEquals(words, a)
        assertEquals(other, b)
    }

    @Test
    fun `each direction's status gets its words too`() {
        val line = linkOnly().copy(byDirection = mapOf("outbound" to linkOnly()))
        val status = RailIncidents.withIncidents(listOf(line), listOf(incident)).single()
        assertTrue(status.forDirection("outbound").shownText!!.startsWith("Disruption at London Euston"))
        assertTrue(RailIncidents.wanted(listOf(LineStatus("x", 10, "Good Service", byDirection = mapOf("inbound" to linkOnly())))))
        assertFalse(RailIncidents.wanted(listOf(linkOnly("Minor delays."))))
    }

    @Test
    fun `an active incident wins a shared link over a cleared one`() {
        val cleared = RailIncident("Earlier disruption", null, listOf(link), active = false)
        val status = RailIncidents.withIncidents(listOf(linkOnly()), listOf(cleared, incident)).single()
        assertTrue(status.shownText!!.startsWith("Disruption at London Euston"))
    }

    @Test
    fun `a cleared incident alone leaves TfL's link`() {
        val cleared = RailIncident("Earlier disruption", "Lines have reopened.", listOf(link), active = false)
        val status = RailIncidents.withIncidents(listOf(linkOnly()), listOf(cleared)).single()
        assertEquals(link, status.shownText)
    }

    @Test
    fun `words never outlive the text they were put to`() {
        val status = RailIncidents.withIncidents(listOf(linkOnly()), listOf(incident)).single()
        // Re-ranked to another alert's words: the incident's no longer stand for them.
        assertEquals("Severe delays.", status.copy(fullText = "Severe delays.").shownText)
    }

    @Test
    fun `words past their own age give way to TfL's link, however new the status carrying them`() {
        val now = Instant.now()
        val young = RailIncidents.withIncidents(listOf(linkOnly()), listOf(incident), until = now.plusSeconds(60)).single()
        assertTrue(young.shownText!!.startsWith("Disruption at London Euston"))
        val old = RailIncidents.withIncidents(listOf(linkOnly()), listOf(incident), until = now.minusSeconds(1)).single()
        assertEquals(link, old.shownText)
        // Judged in the steady frame: the clock set back doesn't bring them back.
        assertTrue(young.detail!!.current(now))
        assertFalse(young.detail!!.current(now.plusSeconds(61)))
    }

    @Test
    fun `a notification shows the words only if they're still current when it comes down`() {
        val now = Instant.now()
        val status = RailIncidents.withIncidents(listOf(linkOnly()), listOf(incident), until = now.plusSeconds(300)).single()
        assertTrue(status.shownTextThrough(now.plusSeconds(60))!!.startsWith("Disruption at London Euston"))
        // Up for 35 minutes, past the words' age: TfL's link, not words that would go stale on screen (Codex, #943).
        assertEquals(link, status.shownTextThrough(now.plus(Duration.ofMinutes(35))))
        val alert = JourneyLineAlert(status.lineId, "Avanti", status.description, status.fullText, status.detail)
        assertTrue(alert.shownTextThrough(now.plusSeconds(60))!!.startsWith("Disruption at London Euston"))
        assertEquals(link, alert.shownTextThrough(now.plus(Duration.ofMinutes(35))))
        assertEquals("Severe delays.", JourneyLineAlert("x", "X", "Severe Delays", "Severe delays.").shownTextThrough(now))
    }

    @Test
    fun `the cache hands out when its words stop being usable`() = runTest {
        val clock = Clock()
        val cache = RailIncidentCache(clock = { clock.now })
        val held = cache.current { FetchedIncidents(listOf(incident), age = Duration.ofMinutes(1)) }
        // Five minutes from the proxy's fetch, a minute before it was had here.
        assertEquals(clock.now.plus(Duration.ofMinutes(4)), held?.until)
    }

    @Test
    fun `the time an answer takes to arrive counts against its words`() = runTest {
        val clock = Clock()
        val cache = RailIncidentCache(clock = { clock.now })
        val asked = clock.now
        // Twenty seconds between the ask and the answer being read: dated from the ask (Codex, #943).
        val held = cache.current { clock.advance(Duration.ofSeconds(20)); FetchedIncidents(listOf(incident), age = Duration.ofMinutes(1)) }
        assertEquals(asked.plus(Duration.ofMinutes(4)), held?.until)
    }

    @Test
    fun `an alert under way gets its words too, for when a worse one is dismissed (Codex, #943)`() {
        val worse = LineStatus("avanti-west-coast", 6, "Severe Delays", "Severe delays.", underWay = listOf(LineAlert(9, "Minor Delays", link)))
        assertTrue(RailIncidents.wanted(listOf(worse)))
        val status = RailIncidents.withIncidents(listOf(worse), listOf(incident)).single()
        assertNull(status.detail)
        // Shown in its own place, the alert under way finds its words by its own text.
        val standIn = status.copy(severity = 9, description = "Minor Delays", fullText = link)
        assertTrue(standIn.shownText!!.startsWith("Disruption at London Euston"))
    }

    @Test
    fun `a placed closure whose text is only a link shows the incident's words too (Codex, #943)`() {
        val closure = PartClosure(4, "Part Suspended", link, listOf(listOf("EUS", "WFJ")))
        val worse = LineStatus("avanti-west-coast", 3, "Part Closure", "Engineering work.", closures = listOf(closure))
        assertTrue(RailIncidents.wanted(listOf(worse)))
        val status = RailIncidents.withIncidents(listOf(worse), listOf(incident)).single()
        assertEquals("Engineering work.", status.shownText)
        assertTrue(status.naming(closure).shownText!!.startsWith("Disruption at London Euston"))
    }

    @Test
    fun `dismissing keeps keying on TfL's text, fetched words or not`() {
        val plain = linkOnly()
        val worded = RailIncidents.withIncidents(listOf(plain), listOf(incident)).single()
        assertEquals(DismissedAlert.ofLineStatus(plain), DismissedAlert.ofLineStatus(worded))
    }

    private class Clock(var now: Instant = Instant.parse("2026-10-17T12:00:00Z")) {
        fun advance(by: Duration) { now = now.plus(by) }
    }

    @Test
    fun `an answer is reused for two minutes, then asked again`() = runTest {
        val clock = Clock()
        val cache = RailIncidentCache(clock = { clock.now })
        var asked = 0
        val fetch: suspend () -> FetchedIncidents = { asked++; FetchedIncidents(listOf(incident)) }
        cache.incidents(fetch = fetch)
        clock.advance(Duration.ofSeconds(119))
        assertEquals(listOf(incident), cache.incidents(fetch = fetch))
        assertEquals(1, asked)
        clock.advance(Duration.ofSeconds(1))
        cache.incidents(fetch = fetch)
        assertEquals(2, asked)
    }

    @Test
    fun `callers asking together ask once`() = runTest {
        val cache = RailIncidentCache(clock = { Instant.EPOCH })
        val gate = CompletableDeferred<Unit>()
        var asked = 0
        val fetch: suspend () -> FetchedIncidents = { asked++; gate.await(); FetchedIncidents(listOf(incident)) }
        val first = async { cache.incidents(fetch = fetch) }
        val second = async { cache.incidents(fetch = fetch) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        assertEquals(listOf(incident), first.await())
        assertEquals(listOf(incident), second.await())
        assertEquals(1, asked)
    }

    @Test
    fun `a failure is told and waited out, and the last words serve until five minutes from the proxy's fetch`() = runTest {
        val clock = Clock()
        val cache = RailIncidentCache(clock = { clock.now })
        val failures = mutableListOf<Exception>()
        var fail = false
        var asked = 0
        // The proxy's copy was already a minute old when it answered.
        val fetch: suspend () -> FetchedIncidents = {
            asked++
            if (fail) throw IOException("down") else FetchedIncidents(listOf(incident), age = Duration.ofMinutes(1))
        }
        cache.incidents(fetch = fetch)
        fail = true
        clock.advance(Duration.ofMinutes(2))
        assertEquals(listOf(incident), cache.incidents(onFailure = { failures += it }, fetch = fetch))
        assertEquals(1, failures.size)
        // Not asked again within two minutes of the failure.
        clock.advance(Duration.ofMinutes(1))
        cache.incidents(onFailure = { failures += it }, fetch = fetch)
        assertEquals(2, asked)
        // Four minutes since it was had, five since the proxy fetched it: no longer used.
        clock.advance(Duration.ofMinutes(1))
        assertNull(cache.incidents(onFailure = { failures += it }, fetch = fetch))
    }

    @Test
    fun `a caller that goes away mid-ask doesn't hold the next one off`() = runTest {
        val cache = RailIncidentCache(clock = { Instant.EPOCH })
        val gate = CompletableDeferred<Unit>()
        val canceled = async { cache.incidents { gate.await(); FetchedIncidents(listOf(incident)) } }
        testScheduler.runCurrent()
        canceled.cancel()
        testScheduler.runCurrent()
        assertEquals(listOf(incident), cache.incidents { FetchedIncidents(listOf(incident)) })
    }

    @Test
    fun `the clock set back neither holds a refresh off nor keeps old words current`() = runTest {
        val setBack = object : SteadyClock.Source {
            var by: Duration = Duration.ZERO
            override val frame = SteadyClock.Frame("device/7", 0L)
            override fun offset(): Duration = by
        }
        SteadyClock.source = setBack
        val clock = Clock()
        val cache = RailIncidentCache(clock = { clock.now })
        var asked = 0
        var fail = false
        val fetch: suspend () -> FetchedIncidents = { asked++; if (fail) throw IOException("down") else FetchedIncidents(listOf(incident)) }
        cache.incidents(fetch = fetch)
        // Three minutes on, the clock is set back an hour: it's still time to ask again.
        clock.advance(Duration.ofMinutes(3))
        clock.now = clock.now.minus(Duration.ofHours(1))
        setBack.by = Duration.ofHours(1)
        fail = true
        assertEquals(listOf(incident), cache.incidents(fetch = fetch))
        assertEquals(2, asked)
        // And the words still age out five minutes after they were fetched.
        clock.advance(Duration.ofMinutes(2))
        assertNull(cache.incidents(fetch = fetch))
    }

    @Test
    fun `words the proxy already held too long are never used`() = runTest {
        val cache = RailIncidentCache(clock = { Instant.EPOCH })
        assertNull(cache.incidents { FetchedIncidents(listOf(incident), age = Duration.ofMinutes(6)) })
    }
}
