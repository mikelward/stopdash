package app.stopdash.wear

import app.stopdash.data.WatchEnvelope
import app.stopdash.data.WatchStarKey
import app.stopdash.data.toPersisted
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.RoutePattern
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.Staleness
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.Terminating
import java.time.Instant
import kotlin.time.toJavaDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The complication's timeline over a fixed clock: synthetic stops only, no user data. */
class ComplicationTimelineTest {
    private val fetched: Instant = Instant.parse("2026-09-24T08:00:00Z")
    private val boundary: Instant = fetched.plus(Staleness.THRESHOLD.toJavaDuration())

    private fun departure(seconds: Long, line: String = "victoria", destination: String = "Brixton", mode: String = "tube") = Departure(
        lineId = line,
        lineName = line.replaceFirstChar { it.uppercase() },
        direction = "inbound",
        destination = destination,
        platform = null,
        expectedArrival = fetched.plusSeconds(seconds),
        mode = mode,
    )

    private fun stop(id: String, departures: List<Departure>, fresh: Boolean = true, line: String = "victoria") = StopArrivals(
        stopId = id,
        stopName = "Stop $id",
        departures = departures,
        fetchedAt = fetched,
        lines = listOf(LineRef(line, line.replaceFirstChar { it.uppercase() }, "tube")),
        arrivalsFresh = fresh,
    )

    private fun envelope(vararg stops: StopArrivals, starred: Set<StarredRow> = emptySet(), hidden: List<String> = emptyList()) =
        WatchEnvelope(stops = stops.map { it.toPersisted() }, starred = starred.map(WatchStarKey::of), hiddenModes = hidden)

    private fun List<ComplicationEntry>.at(t: Instant): ComplicationContent =
        single { it.start <= t && (it.end == null || t < it.end) }.content

    @Test
    fun `nothing to show is the no-data dash`() {
        val noData = listOf(ComplicationEntry(fetched, null, ComplicationContent.NoData))
        assertEquals("never synced", noData, ComplicationTimeline.entries(null, fetched))
        assertEquals("no stops", noData, ComplicationTimeline.entries(WatchEnvelope(), fetched))
        assertEquals("stops but no rows", noData, ComplicationTimeline.entries(envelope(stop("940GA", emptyList())), fetched))
    }

    @Test
    fun `each departure counts down until it leaves, then the next takes over`() {
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(departure(120), departure(240)))), fetched)
        val first = entries.at(fetched) as ComplicationContent.Departure
        assertEquals(fetched.plusSeconds(120), first.at)
        assertEquals("Brixton", first.destination)
        assertEquals(fetched.plusSeconds(240), (entries.at(fetched.plusSeconds(120)) as ComplicationContent.Departure).at)
    }

    @Test
    fun `a row that runs out shows its empty form until the boundary, then the stale form`() {
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(departure(60)))), fetched)
        assertEquals(ComplicationContent.Empty("VIC", "Victoria", uncertain = false), entries.at(fetched.plusSeconds(60)))
        assertEquals(ComplicationContent.Stale("VIC", "Victoria"), entries.at(boundary))
        assertEquals(null, entries.last().end)
        // No departure entry starts after the boundary, so a live countdown never overlaps the stale one.
        assertTrue(entries.filter { it.content is ComplicationContent.Departure }.all { it.end!! <= boundary })
    }

    @Test
    fun `a busy row counts down every departure in the window, never a false None`() {
        val busy = (1..50L).map { departure(it * 5) }
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", busy)), fetched)
        assertEquals(50, entries.count { it.content is ComplicationContent.Departure })
        // "None" only once the last of them has left.
        assertEquals(fetched.plusSeconds(250), entries.single { it.content is ComplicationContent.Empty }.start)
    }

    @Test
    fun `a departure past the boundary is never counted down`() {
        val late = boundary.epochSecond - fetched.epochSecond + 60
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(departure(60), departure(late)))), fetched)
        assertEquals(ComplicationContent.Stale("VIC", "Victoria"), entries.at(boundary.plusSeconds(1)))
        assertTrue(entries.none { (it.content as? ComplicationContent.Departure)?.at == fetched.plusSeconds(late) })
    }

    @Test
    fun `a carried-forward stop marks every time, and its empty form, uncertain`() {
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(departure(60)), fresh = false)), fetched)
        assertTrue((entries.at(fetched) as ComplicationContent.Departure).uncertain)
        assertEquals(ComplicationContent.Empty("VIC", "Victoria", uncertain = true), entries.at(fetched.plusSeconds(60)))
    }

    @Test
    fun `an already stale stop shows only the stale form`() {
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(departure(3600)))), boundary.plusSeconds(5))
        assertEquals(listOf(ComplicationEntry(boundary.plusSeconds(5), null, ComplicationContent.Stale("VIC", "Victoria"))), entries)
    }

    @Test
    fun `the default row is the top starred row, else the widget's first`() {
        val a = stop("940GA", listOf(departure(60)))
        val b = stop("940GB", listOf(departure(300, line = "central", destination = "Ealing")), line = "central")
        assertEquals(StarredRow("940GA", "victoria", "inbound"), ComplicationTimeline.defaultRow(envelope(a, b), fetched))
        val star = StarredRow("940GB", "central", "inbound")
        assertEquals(star, ComplicationTimeline.defaultRow(envelope(a, b, starred = setOf(star)), fetched))
    }

    @Test
    fun `a hidden mode never feeds the complication`() {
        val bus = stop("940GA", listOf(departure(60, line = "73", destination = "Oxford Circus", mode = "bus")), line = "73")
        val tube = stop("940GB", listOf(departure(300, line = "central", destination = "Ealing")), line = "central")
        assertEquals("central", ComplicationTimeline.defaultRow(envelope(bus, tube, hidden = listOf("bus")), fetched)?.lineId)
    }

    @Test
    fun `a chosen row whose stop left the envelope falls back to the default row`() {
        val env = envelope(stop("940GA", listOf(departure(60))))
        val gone = StarredRow("940GGONE", "central", "inbound")
        assertEquals(ComplicationTimeline.entries(env, fetched), ComplicationTimeline.entries(env, fetched, gone))
    }

    @Test
    fun `a star for a hidden mode or a line the stop no longer serves never becomes the row`() {
        val hiddenStar = StarredRow("940GA", "73", "inbound")
        val bus = StopArrivals(
            stopId = "940GA",
            stopName = "Stop 940GA",
            departures = emptyList(),
            fetchedAt = fetched,
            lines = listOf(LineRef("73", "73", "bus")),
        )
        assertEquals(null, ComplicationTimeline.defaultRow(envelope(bus, starred = setOf(hiddenStar), hidden = listOf("bus")), fetched))
        val goneLine = StarredRow("940GA", "central", "inbound")
        assertEquals(null, ComplicationTimeline.defaultRow(envelope(stop("940GA", emptyList()), starred = setOf(goneLine)), fetched))
    }

    @Test
    fun `a departure with no destination reads as a dash, never blank`() {
        // No destination and no direction or platform to fall back on.
        val blank = departure(60, destination = "").copy(direction = "")
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(blank))), fetched)
        assertEquals("—", (entries.at(fetched) as ComplicationContent.Departure).destination)
    }

    @Test
    fun `a via-branch that's a choice ahead joins the destination, as on the tile`() {
        // Two trunks from this stop (940GA) to Morden: the branch is a choice ahead of it.
        val topology = RouteTopology(
            mapOf(
                "northern" to listOf(
                    RoutePattern("Bank", listOf("940GNORTH", "940GA", "940GBANK", "940GMORDEN"), "North", "Morden"),
                    RoutePattern("Charing X", listOf("940GNORTH", "940GA", "940GCHX", "940GMORDEN"), "North", "Morden"),
                ),
            ),
        )
        val via = departure(60, line = "northern", destination = "Morden").copy(branch = "Bank")
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(via), line = "northern")), fetched, topology = topology)
        assertEquals("Morden/Bank", (entries.at(fetched) as ComplicationContent.Departure).destination)
    }

    @Test
    fun `a starred row with no departures shows its empty form rather than no data`() {
        val star = StarredRow("940GA", "victoria", "inbound")
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", emptyList()), starred = setOf(star)), fetched)
        assertEquals(ComplicationContent.Empty("VIC", "Victoria", uncertain = false), entries.at(fetched))
    }

    @Test
    fun `a pick whose mode is hidden gives way to the default row`() {
        val bus = departure(60, line = "73", destination = "Oxford Circus", mode = "bus")
        val tube = departure(120)
        val stop = StopArrivals(
            stopId = "940GA",
            stopName = "Stop 940GA",
            departures = listOf(bus, tube),
            fetchedAt = fetched,
            lines = listOf(LineRef("73", "73", "bus"), LineRef("victoria", "Victoria", "tube")),
        )
        val env = envelope(stop, hidden = listOf("bus"))
        val pick = StarredRow("940GA", "73", "inbound")
        assertEquals(ComplicationTimeline.entries(env, fetched), ComplicationTimeline.entries(env, fetched, pick))
        assertEquals("Victoria", (ComplicationTimeline.entries(env, fetched, pick).first().content as ComplicationContent.Departure).lineName)
        // Shown again once the mode is.
        val shown = ComplicationTimeline.entries(env.copy(hiddenModes = emptyList()), fetched, pick).first().content
        assertEquals("Oxford Circus", (shown as ComplicationContent.Departure).destination)
    }

    @Test
    fun `a pick the widget's terminating filter removes gives way to the default row`() {
        // Every 73 here terminates at a stop no farther from the rider: the widget drops them.
        val ending = departure(60, line = "73", destination = "Stop 940GNEAR", mode = "bus").copy(destinationId = "940GNEAR")
        val stop = StopArrivals(
            stopId = "940GA",
            stopName = "Stop 940GA",
            departures = listOf(ending, departure(120)),
            fetchedAt = fetched,
            lines = listOf(LineRef("73", "73", "bus"), LineRef("victoria", "Victoria", "tube")),
            nearer = Terminating.Nearer(ids = setOf("940GNEAR")),
        )
        val env = envelope(stop)
        val pick = StarredRow("940GA", "73", "inbound")
        assertEquals(ComplicationTimeline.entries(env, fetched), ComplicationTimeline.entries(env, fetched, pick))
    }

    @Test
    fun `a pick is judged by its own direction's services, not the other direction's`() {
        // Outbound 73s all terminate nearer the rider; inbound ones don't. An outbound pick goes.
        val outbound = departure(60, line = "73", destination = "Stop 940GNEAR", mode = "bus")
            .copy(direction = "outbound", destinationId = "940GNEAR")
        val inbound = departure(90, line = "73", destination = "Oxford Circus", mode = "bus")
        val stop = StopArrivals(
            stopId = "940GA",
            stopName = "Stop 940GA",
            departures = listOf(outbound, inbound, departure(120)),
            fetchedAt = fetched,
            lines = listOf(LineRef("73", "73", "bus"), LineRef("victoria", "Victoria", "tube")),
            nearer = Terminating.Nearer(ids = setOf("940GNEAR")),
        )
        val env = envelope(stop)
        assertEquals(false, ComplicationTimeline.shows(env, StarredRow("940GA", "73", "outbound"), fetched))
        assertEquals(true, ComplicationTimeline.shows(env, StarredRow("940GA", "73", "inbound"), fetched))
        // Still filtered once its only (terminating) service has left, not resurrected as empty.
        assertEquals(false, ComplicationTimeline.shows(env, StarredRow("940GA", "73", "outbound"), fetched.plusSeconds(200)))
    }

    @Test
    fun `a disrupted line's entries are marked until the check expires, then split and cleared`() {
        val check = LineStatusCheck(LineStatus("victoria", 6, "Severe Delays"), fetched.minusSeconds(180))
        val env = envelope(stop("940GA", listOf(departure(240)))).let { it.copy(lineStatuses = listOf(check.toPersisted())) }
        val entries = ComplicationTimeline.entries(env, fetched)
        val expiry = fetched.plusSeconds(120)
        assertEquals("Severe Delays", (entries.at(fetched) as ComplicationContent.Departure).disruption)
        assertTrue(entries.any { it.start == expiry })
        assertNull((entries.at(expiry) as ComplicationContent.Departure).disruption)
    }

    @Test
    fun `a row is marked with its own direction's status, not the other way's`() {
        // The row's services run inbound; the alert TfL scopes to outbound says nothing about them.
        val outbound = LineStatus("victoria", 6, "Severe Delays", "Signal failure southbound.")
        val inbound = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")
        val split = LineStatus("victoria", 6, "Severe Delays", "Signal failure southbound.", byDirection = mapOf("inbound" to inbound, "outbound" to outbound))
        val env = envelope(stop("940GA", listOf(departure(240)))).copy(lineStatuses = listOf(LineStatusCheck(split, fetched).toPersisted()))
        assertNull((ComplicationTimeline.entries(env, fetched).at(fetched) as ComplicationContent.Departure).disruption)
        // Going the affected way, it's marked.
        val affected = split.copy(byDirection = mapOf("inbound" to outbound, "outbound" to inbound))
        val marked = env.copy(lineStatuses = listOf(LineStatusCheck(affected, fetched).toPersisted()))
        assertEquals("Severe Delays", (ComplicationTimeline.entries(marked, fetched).at(fetched) as ComplicationContent.Departure).disruption)
    }

    @Test
    fun `a picked row with nothing left to run keeps its own direction's status`() {
        val outbound = LineStatus("victoria", 6, "Severe Delays", "Signal failure southbound.")
        val inbound = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")
        val split = LineStatus("victoria", 6, "Severe Delays", "Signal failure southbound.", byDirection = mapOf("inbound" to inbound, "outbound" to outbound))
        // The picked inbound row's last train leaves at +60; the alert only affects outbound.
        val env = envelope(stop("940GA", listOf(departure(60)))).copy(lineStatuses = listOf(LineStatusCheck(split, fetched).toPersisted()))
        val picked = StarredRow("940GA", "victoria", "inbound")
        // Built after that train has gone, so only the pick says which way the row runs.
        val later = fetched.plusSeconds(90)
        val entries = ComplicationTimeline.entries(env, later, picked)
        assertNull((entries.at(later) as ComplicationContent.Empty).disruption)
        // Picked the affected way, it's marked.
        val affected = ComplicationTimeline.entries(env, later, StarredRow("940GA", "victoria", "outbound"))
        assertEquals("Severe Delays", (affected.at(later) as ComplicationContent.Empty).disruption)
    }

    @Test
    fun `a check that outlives the stop's times keeps the stale entry marked until it expires`() {
        val check = LineStatusCheck(LineStatus("victoria", 6, "Severe Delays"), fetched.plusSeconds(60))
        val env = envelope(stop("940GA", listOf(departure(900)))).let { it.copy(lineStatuses = listOf(check.toPersisted())) }
        val boundary = fetched.plusSeconds(300)
        // Built when the envelope arrives, after the check was made.
        val entries = ComplicationTimeline.entries(env, fetched.plusSeconds(60))
        assertEquals("Severe Delays", (entries.at(boundary) as ComplicationContent.Stale).disruption)
        assertNull((entries.at(fetched.plusSeconds(360)) as ComplicationContent.Stale).disruption)
        // Built after the stop's boundary, the stale entry is marked the same way.
        val late = ComplicationTimeline.entries(env, boundary.plusSeconds(10))
        assertEquals("Severe Delays", (late.at(boundary.plusSeconds(10)) as ComplicationContent.Stale).disruption)
    }

    @Test
    fun `a live suspension at a stale stop still leads the default row`() {
        val staleStop = stop("940GA", emptyList(), line = "waterloo-city").copy(fetchedAt = fetched.minusSeconds(320))
        val check = LineStatusCheck(LineStatus("waterloo-city", 5, "Suspended"), fetched.minusSeconds(30))
        val env = envelope(stop("940GB", listOf(departure(120))), staleStop).copy(lineStatuses = listOf(check.toPersisted()))
        assertEquals("waterloo-city", ComplicationTimeline.widgetRows(env, fetched).first().lineId)
    }

    @Test
    fun `a default suspension hands over to the next row when its check expires`() {
        val suspended = stop("940GA", emptyList(), line = "waterloo-city")
        val check = LineStatusCheck(LineStatus("waterloo-city", 5, "Suspended"), fetched.minusSeconds(120))
        val env = envelope(suspended, stop("940GB", listOf(departure(240)))).copy(lineStatuses = listOf(check.toPersisted()))
        val entries = ComplicationTimeline.entries(env, fetched)
        assertEquals("Suspended", (entries.at(fetched) as ComplicationContent.Empty).disruption)
        // At the check's expiry the widget's first row is Victoria's, and so is the complication's.
        val after = entries.at(fetched.plusSeconds(180)) as ComplicationContent.Departure
        assertEquals("Victoria", after.lineName)
        assertEquals(fetched.plusSeconds(240), after.at)
    }

    @Test
    fun `a disrupted default row whose train has gone hands over when its check expires`() {
        // Victoria's last train leaves at +90, leaving only its status; the check expires at +310.
        val victoria = stop("940GA", listOf(departure(90)))
        val central = stop("940GB", listOf(departure(340, line = "central", destination = "Ealing")), line = "central")
            .copy(fetchedAt = fetched.plusSeconds(60))
        val check = LineStatusCheck(LineStatus("victoria", 6, "Severe Delays"), fetched.plusSeconds(10))
        val env = envelope(victoria, central).copy(lineStatuses = listOf(check.toPersisted()))
        val entries = ComplicationTimeline.entries(env, fetched.plusSeconds(60))
        assertEquals("Victoria", (entries.at(fetched.plusSeconds(60)) as ComplicationContent.Departure).lineName)
        // At the expiry the widget's first row is Central's, and so is the complication's.
        val after = entries.at(fetched.plusSeconds(310)) as ComplicationContent.Departure
        assertEquals("Central", after.lineName)
    }

    @Test
    fun `a check from the future never marks the complication`() {
        val check = LineStatusCheck(LineStatus("victoria", 6, "Severe Delays"), fetched.plusSeconds(120))
        val env = envelope(stop("940GA", listOf(departure(240)))).copy(lineStatuses = listOf(check.toPersisted()))
        val entries = ComplicationTimeline.entries(env, fetched)
        assertTrue(entries.none { (it.content as? ComplicationContent.Departure)?.disruption != null })
        assertTrue(entries.none { (it.content as? ComplicationContent.Empty)?.disruption != null })
        assertTrue(entries.none { (it.content as? ComplicationContent.Stale)?.disruption != null })
    }

    @Test
    fun `a lone suspension is the default row, marked, never no data`() {
        val stop = stop("940GA", emptyList(), line = "waterloo-city")
        val check = LineStatusCheck(LineStatus("waterloo-city", 5, "Suspended"), fetched)
        val env = envelope(stop).copy(lineStatuses = listOf(check.toPersisted()))
        val first = ComplicationTimeline.entries(env, fetched).first().content as ComplicationContent.Empty
        assertEquals("Suspended", first.disruption)
    }

    @Test
    fun `a stop from before the clock was set back is stale for the whole timeline`() {
        val env = envelope(stop("940GA", listOf(departure(240), departure(600))))
        // Built an hour before its stamp: its age can't be told, so it never shows a countdown,
        // not even once the clock passes the stamp.
        val entries = ComplicationTimeline.entries(env, fetched.minusSeconds(3600))
        assertTrue(entries.all { it.content is ComplicationContent.Stale })
        assertTrue(entries.at(fetched.plusSeconds(10)) is ComplicationContent.Stale)
        // A moment ahead is another clock's tick, not a rollback: it still counts down.
        assertTrue(ComplicationTimeline.entries(env, fetched.minusSeconds(30)).first().content is ComplicationContent.Departure)
    }

}
