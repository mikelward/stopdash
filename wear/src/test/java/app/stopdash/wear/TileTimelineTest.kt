package app.stopdash.wear

import app.stopdash.data.WatchEnvelope
import app.stopdash.data.WatchStarKey
import app.stopdash.data.toPersisted
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tile's timeline over a fixed clock: synthetic stops only, no user data. */
class TileTimelineTest {
    private val fetched: Instant = Instant.parse("2026-09-24T08:00:00Z")

    private fun departure(seconds: Long, line: String = "victoria", destination: String = "Brixton") = Departure(
        lineId = line,
        lineName = line.replaceFirstChar { it.uppercase() },
        direction = "inbound",
        destination = destination,
        platform = null,
        expectedArrival = fetched.plusSeconds(seconds),
        mode = "tube",
    )

    private fun stop(id: String, departures: List<Departure>, at: Instant = fetched, line: String = "victoria") = StopArrivals(
        stopId = id,
        stopName = "Stop $id",
        departures = departures,
        fetchedAt = at,
        lines = listOf(LineRef(line, line.replaceFirstChar { it.uppercase() }, "tube")),
    )

    // Every line checked good at the fetch by default, as a complete refresh leaves it; [checked]
    // false leaves the lines unchecked.
    private fun envelope(vararg stops: StopArrivals, starred: Set<StarredRow> = emptySet(), checked: Boolean = true) =
        WatchEnvelope(
            stops = stops.map { it.toPersisted() },
            starred = starred.map(WatchStarKey::of),
            lineStatuses = if (!checked) emptyList() else stops
                .flatMap { s -> s.departures.map { it.lineId } + s.lines.map { it.id } }
                .distinct()
                .map { LineStatusCheck(LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service"), stops.maxOf { s -> s.fetchedAt }).toPersisted() },
        )

    private fun List<TileEntry>.at(t: Instant): TileFrame = single { it.start <= t && (it.end == null || t < it.end) }.frame

    private fun rows(frame: TileFrame) = (frame as TileFrame.Rows).lines.filterIsInstance<TileLine.Departure>().map { it.row }

    @Test
    fun `setup states are one open-ended entry`() {
        val never = TileTimeline.entries(null, fetched)
        assertEquals(listOf(TileEntry(fetched, null, TileFrame.NeverSynced)), never)
        assertEquals(listOf(TileEntry(fetched, null, TileFrame.NoStops)), TileTimeline.entries(WatchEnvelope(), fetched))
    }

    @Test
    fun `stops that all failed to load read as out of date, not as no stops`() {
        val env = WatchEnvelope(missingStopIds = listOf("940GMISSING"))
        assertEquals(listOf(TileEntry(fetched, null, TileFrame.NoneLoaded)), TileTimeline.entries(env, fetched))
    }

    @Test
    fun `a departed service drops off at its exact time, even mid-minute`() {
        val entries = TileTimeline.entries(envelope(stop("940GA", listOf(departure(90), departure(200)))), fetched)
        assertEquals("0 · 1 min", rows(entries.at(fetched.plusSeconds(89)))[0].countdown)
        assertEquals("1 min", rows(entries.at(fetched.plusSeconds(90)))[0].countdown)
        assertTrue(entries.any { it.start == fetched.plusSeconds(90) })
    }

    @Test
    fun `countdowns tick at each minute boundary`() {
        val entries = TileTimeline.entries(envelope(stop("940GA", listOf(departure(150)))), fetched)
        assertEquals("2 min", rows(entries.at(fetched))[0].countdown)
        assertEquals("2 min", rows(entries.at(fetched.plusSeconds(30)))[0].countdown)
        assertEquals("1 min", rows(entries.at(fetched.plusSeconds(31)))[0].countdown)
        assertEquals("1 min", rows(entries.at(fetched.plusSeconds(90)))[0].countdown)
        assertEquals("0 min", rows(entries.at(fetched.plusSeconds(91)))[0].countdown)
    }

    @Test
    fun `each stop turns stale at its own boundary while a fresher one stays live`() {
        val older = stop("940GOLD", listOf(departure(600)), at = fetched.minusSeconds(120))
        val newer = stop("940GNEW", listOf(departure(600, line = "central", destination = "Ealing")), line = "central")
        // A screen with room for both stops' headers and rows beside the notes.
        val entries = TileTimeline.schedule(envelope(older, newer), fetched, screen = TileScreen(454, 1f)).entries
        val justAfterOld = entries.at(fetched.plusSeconds(181)) as TileFrame.Rows
        assertEquals("?", rows(justAfterOld).single { it.lineId == "victoria" }.countdown)
        assertEquals("6 min", rows(justAfterOld).single { it.lineId == "central" }.countdown)
        assertFalse(justAfterOld.stale)
        assertTrue("a stale stop beside a fresh one is partial", justAfterOld.partial)
    }

    @Test
    fun `the last entry is open-ended and stale, with every countdown withheld`() {
        val entries = TileTimeline.entries(envelope(stop("940GA", listOf(departure(900)))), fetched)
        val last = entries.last()
        assertNull(last.end)
        assertEquals(fetched.plusSeconds(300), last.start)
        val frame = last.frame as TileFrame.Rows
        assertTrue(frame.stale)
        assertEquals(listOf("?"), rows(frame).map { it.countdown })
    }

    @Test
    fun `a stop with no departures still turns stale at its boundary`() {
        val entries = TileTimeline.entries(envelope(stop("940GA", emptyList())), fetched)
        val first = entries.first().frame as TileFrame.Rows
        assertTrue(rows(first).isEmpty())
        assertFalse(first.stale)
        assertEquals(fetched.plusSeconds(300), entries.last().start)
        assertTrue((entries.last().frame as TileFrame.Rows).stale)
    }

    @Test
    fun `no entry counts down a service that has gone`() {
        val entries = TileTimeline.entries(envelope(stop("940GA", listOf(departure(45), departure(400)))), fetched)
        for (entry in entries) {
            val shown = rows(entry.frame).map { it.countdown }
            if (entry.start >= fetched.plusSeconds(45)) assertFalse(shown.toString(), shown.any { it.startsWith("0 ·") })
        }
    }

    @Test
    fun `favorites come first`() {
        val star = StarredRow("940GB", "central", "inbound")
        val entries = TileTimeline.entries(
            envelope(
                stop("940GA", listOf(departure(60))),
                stop("940GB", listOf(departure(500, line = "central", destination = "Ealing")), line = "central"),
                starred = setOf(star),
            ),
            fetched,
        )
        val first = rows(entries.first().frame)
        assertEquals("central", first[0].lineId)
        assertTrue(first[0].starred)
    }

    @Test
    fun `past the entry cap the tail withholds every countdown and asks for a refresh`() {
        // A different line leaving every 2 s: each departure reshuffles the five lines, so the
        // frame changes far more often than the cap allows.
        val busy = (1..150L).map { departure(it * 2, line = "l$it", destination = "Stop $it") }
        val schedule = TileTimeline.schedule(envelope(stop("940GA", busy)), fetched)
        assertEquals(TileTimeline.MAX_SCHEDULED, schedule.entries.size)
        // A pending refresh's two notices, each ending mid-entry, split two entries: still within the cap.
        val notices = listOf(
            RefreshNotice(RefreshNotice.Kind.REFRESHING, schedule.entries[10].start.plusMillis(500)),
            RefreshNotice(RefreshNotice.Kind.PHONE_OUT_OF_REACH, schedule.entries[20].start.plusMillis(500)),
        )
        assertEquals(TileTimeline.MAX_ENTRIES, TileTimeline.withNotice(schedule.entries, notices).size)
        val tail = schedule.entries.last()
        assertNull(tail.end)
        assertEquals(schedule.refreshAt, tail.start)
        assertTrue((tail.frame as TileFrame.Rows).stale)
        assertTrue(rows(tail.frame).all { it.countdown == "?" })
        // Every entry before the cut is live and correct at its own start.
        assertFalse((schedule.entries.first().frame as TileFrame.Rows).stale)
    }

    @Test
    fun `an uncapped timeline asks for no refresh`() {
        assertNull(TileTimeline.schedule(envelope(stop("940GA", listOf(departure(90)))), fetched).refreshAt)
    }

    @Test
    fun `a stop fetched long ago costs no extra entries`() {
        val ancient = stop("940GOLD", listOf(departure(60)), at = fetched.minusSeconds(365L * 24 * 3600))
        val fresh = stop("940GNEW", listOf(departure(600, line = "central", destination = "Ealing")), line = "central")
        val entries = TileTimeline.entries(envelope(ancient, fresh), fetched)
        // One per age minute and countdown minute up to the fresh stop's boundary, not one per minute
        // since the old fetch.
        assertTrue(entries.size.toString(), entries.size < 20)
    }

    @Test
    fun `two stops get the widget's stop headers`() {
        val a = stop("940GA", listOf(departure(60)))
        val b = stop("940GB", listOf(departure(120, line = "central", destination = "Ealing")), line = "central")
        val lines = (TileTimeline.frame(envelope(a, b), fetched) as TileFrame.Rows).lines
        assertEquals(listOf("Stop 940GA", "Stop 940GB"), lines.filterIsInstance<TileLine.Header>().map { it.text })
        assertTrue(lines.first() is TileLine.Header)
    }

    @Test
    fun `with no rows anywhere each stop shows its own empty form`() {
        val fresh = stop("940GA", emptyList())
        val carried = stop("940GB", emptyList()).copy(arrivalsFresh = false)
        val lines = (TileTimeline.frame(envelope(fresh, carried), fetched) as TileFrame.Rows).lines
        assertEquals(listOf(TileLine.EmptyStop("Stop 940GA", false), TileLine.EmptyStop("Stop 940GB", true)), lines)
        // Past the boundary, even a fresh fetch's absence is no longer current.
        val later = (TileTimeline.frame(envelope(fresh), fetched.plusSeconds(301)) as TileFrame.Rows).lines
        assertEquals(listOf(TileLine.EmptyStop("Stop 940GA", true)), later)
        // Two poles of one place: one line, uncertain if either is.
        val pole = stop("940GC", emptyList()).copy(stopName = "Stop 940GA", arrivalsFresh = false)
        val place = (TileTimeline.frame(envelope(fresh, pole), fetched) as TileFrame.Rows).lines
        assertEquals(listOf(TileLine.EmptyStop("Stop 940GA", true)), place)
    }

    @Test
    fun `a stop that failed to load marks the tile partial`() {
        val env = envelope(stop("940GA", listOf(departure(60)))).copy(missingStopIds = listOf("940GMISSING"))
        assertTrue((TileTimeline.frame(env, fetched) as TileFrame.Rows).partial)
    }

    @Test
    fun `stops left out for size are counted on the tile`() {
        val env = envelope(stop("940GA", listOf(departure(60)))).copy(omittedStops = 2)
        assertEquals(2, (TileTimeline.frame(env, fetched) as TileFrame.Rows).omitted)
    }

    @Test
    fun `ticks that change nothing shown spend no entries`() {
        // One row, a departure every second: only its whole-minute label and the age stamp change.
        val busy = (60..260L).map { departure(it) }
        val schedule = TileTimeline.schedule(envelope(stop("940GA", busy)), fetched)
        assertNull(schedule.refreshAt)
        assertTrue(schedule.entries.size.toString(), schedule.entries.size < 20)
        schedule.entries.zipWithNext { a, b -> assertTrue("adjacent entries differ", a.frame != b.frame) }
    }

    @Test
    fun `the line budget leaves room for the status lines and the font scale`() {
        val small = TileScreen(heightDp = 192, fontScale = 1f)
        assertEquals(4, TileTimeline.lineBudget(small, notes = 0))
        assertEquals(3, TileTimeline.lineBudget(small, notes = 2))
        assertTrue(TileTimeline.lineBudget(TileScreen(192, 1.5f), notes = 0) < 4)
        assertEquals(TileTimeline.MAX_LINES, TileTimeline.lineBudget(TileScreen(454, 1f), notes = 2))
        assertEquals("never none", 1, TileTimeline.lineBudget(TileScreen(100, 2f), notes = 2))
        assertEquals(TileTimeline.MAX_LINES - 1, TileTimeline.lineBudget(null, notes = 1))
        // The Refresh chip, padded and spaced, costs a small screen a whole line.
        assertEquals(3, TileTimeline.lineBudget(small, notes = 0, refreshLine = true))
        assertEquals(TileTimeline.MAX_LINES - 2, TileTimeline.lineBudget(null, notes = 1, refreshLine = true))
    }

    @Test
    fun `a partial tile lists fewer lines, so its note doesn't push one off`() {
        val busy = (1..8L).map { departure(it * 60, line = "l$it", destination = "Stop $it") }
        val env = envelope(stop("940GA", busy))
        // The Refresh line takes one line; a partial note takes another.
        assertEquals(TileTimeline.MAX_LINES - 1, rows(TileTimeline.frame(env, fetched)).size)
        val partial = env.copy(missingStopIds = listOf("940GMISSING"))
        assertEquals(TileTimeline.MAX_LINES - 2, rows(TileTimeline.frame(partial, fetched)).size)
    }

    @Test
    fun `a stop header keeps its spoken form`() {
        val a = stop("940GA", listOf(departure(60)))
        val b = stop("940GB", listOf(departure(120, line = "central", destination = "Ealing")), line = "central")
        val headers = (TileTimeline.frame(envelope(a, b), fetched) as TileFrame.Rows).lines.filterIsInstance<TileLine.Header>()
        assertTrue(headers.all { it.spoken.isNotBlank() })
    }

    @Test
    fun `a mode hidden on the phone is left out, as on the widget`() {
        val bus = Departure("73", "73", "outbound", "Oxford Circus", null, fetched.plusSeconds(120), "bus")
        val tube = departure(180)
        val env = envelope(stop("940GA", listOf(bus, tube))).copy(hiddenModes = listOf("bus"))
        assertEquals(listOf("victoria"), rows(TileTimeline.frame(env, fetched)).map { it.lineId })
    }

    @Test
    fun `when every row is of a hidden mode the tile says so, not "No departures"`() {
        val bus = Departure("73", "73", "outbound", "Oxford Circus", null, fetched.plusSeconds(120), "bus")
        val env = envelope(stop("940GA", listOf(bus), line = "73")).copy(hiddenModes = listOf("bus"))
        assertEquals(listOf(TileLine.OnlyHidden), (TileTimeline.frame(env, fetched) as TileFrame.Rows).lines)
    }

    @Test
    fun `a busy hidden mode doesn't spend the entry budget`() {
        val buses = (1..150L).map { Departure("73", "73", "outbound", "Oxford Circus", null, fetched.plusSeconds(it * 2), "bus") }
        val env = envelope(stop("940GA", buses + departure(200))).copy(hiddenModes = listOf("bus"))
        val schedule = TileTimeline.schedule(env, fetched)
        assertNull(schedule.refreshAt)
        assertTrue(schedule.entries.size < TileTimeline.MAX_ENTRIES)
    }

    @Test
    fun `a refresh notice shows until it expires, splitting the entry it ends in`() {
        val entries = TileTimeline.entries(envelope(stop("940GA", listOf(departure(150)))), fetched)
        val notice = RefreshNotice(RefreshNotice.Kind.RATE_LIMITED, fetched.plusSeconds(45))
        val noticed = TileTimeline.withNotice(entries, listOf(notice))
        fun at(t: Instant) = noticed.single { it.start <= t && (it.end == null || t < it.end) }
        assertEquals(RefreshNotice.Kind.RATE_LIMITED, at(fetched.plusSeconds(44)).notice)
        assertNull(at(fetched.plusSeconds(45)).notice)
        assertTrue(noticed.any { it.start == fetched.plusSeconds(45) })
        assertEquals(entries, TileTimeline.withNotice(entries, emptyList()))
    }

    @Test
    fun `a pending refresh turns to out of reach on the timeline, with no process to do it`() {
        val entries = TileTimeline.entries(envelope(stop("940GA", listOf(departure(600)))), fetched)
        val notices = RefreshPolicy.notices(RefreshState.Pending(fetched, 1L), fetched)
        val noticed = TileTimeline.withNotice(entries, notices)
        fun at(t: Instant) = noticed.single { it.start <= t && (it.end == null || t < it.end) }.notice
        val timeout = fetched.plus(RefreshPolicy.TIMEOUT)
        assertEquals(RefreshNotice.Kind.REFRESHING, at(timeout.minusSeconds(1)))
        assertEquals(RefreshNotice.Kind.PHONE_OUT_OF_REACH, at(timeout))
        assertNull(at(timeout.plus(RefreshPolicy.NOTICE_FOR)))
    }

    @Test
    fun `the next change is the soonest countdown minute, departure or boundary, and none once stale`() {
        val env = envelope(stop("940GA", listOf(departure(90))))
        // The departure's count drops from 1 to 0 just after 30 s; the age stamp turns at 60 s.
        assertEquals(fetched.plusSeconds(30).plusMillis(1), TileTimeline.nextChange(env, fetched))
        assertEquals(fetched.plusSeconds(60), TileTimeline.nextChange(env, fetched.plusSeconds(31)))
        assertEquals(fetched.plusSeconds(90), TileTimeline.nextChange(env, fetched.plusSeconds(61)))
        // After the last departure, the minutes of the age stamp, then the boundary.
        assertEquals(fetched.plusSeconds(5 * 60), TileTimeline.nextChange(env, fetched.plusSeconds(4 * 60 + 1)))
        assertNull(TileTimeline.nextChange(env, fetched.plusSeconds(5 * 60)))
        assertNull(TileTimeline.nextChange(null, fetched))
    }

    @Test
    fun `the tile offers All stops only when fresh and complete, with no refresh under way`() {
        val fresh = TileFrame.Rows(emptyList(), ageMinutes = 0, stale = false, partial = false)
        assertTrue(TileLayout.offersAllStops(fresh, null))
        assertFalse("out of date", TileLayout.offersAllStops(fresh.copy(stale = true), null))
        assertFalse("a stop failed", TileLayout.offersAllStops(fresh.copy(partial = true), null))
        assertFalse("stops left out for size", TileLayout.offersAllStops(fresh.copy(omitted = 1), null))
        assertFalse("refreshing", TileLayout.offersAllStops(fresh, RefreshNotice.Kind.REFRESHING))
    }

    private val severe = LineStatus("victoria", 6, "Severe Delays")

    private fun withStatus(env: WatchEnvelope, status: LineStatus, at: Instant = fetched) =
        env.copy(lineStatuses = env.lineStatuses.filterNot { it.lineId == status.lineId } + LineStatusCheck(status, at).toPersisted())

    @Test
    fun `a disrupted line is marked under its departures, until its check expires`() {
        val env = withStatus(envelope(stop("940GA", listOf(departure(120), departure(600)))), severe, at = fetched.minusSeconds(120))
        val entries = TileTimeline.entries(env, fetched)
        val before = (entries.at(fetched) as TileFrame.Rows).lines
        assertEquals(TileLine.Disruption::class, before[1]::class)
        val mark = before[1] as TileLine.Disruption
        assertEquals("Severe Delays", mark.description)
        assertFalse(mark.alone)
        // The check was 2 min old, so it expires 3 min in, ahead of the stop's own boundary.
        val expiry = fetched.plusSeconds(180)
        assertTrue(entries.any { it.start == expiry })
        assertTrue((entries.at(expiry) as TileFrame.Rows).lines.none { it is TileLine.Disruption })
    }

    @Test
    fun `a suspended line with no predictions shows as its status alone, ahead of the rest`() {
        val stop = stop("940GA", listOf(departure(120))).copy(
            lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("waterloo-city", "Waterloo-city", "tube")),
        )
        val env = withStatus(envelope(stop), LineStatus("waterloo-city", 5, "Suspended"))
        val lines = (TileTimeline.frame(env, fetched) as TileFrame.Rows).lines
        val first = lines.first() as TileLine.Disruption
        assertTrue(first.alone)
        assertEquals("waterloo-city", first.row.lineId)
    }

    @Test
    fun `a withheld tail marks no disruption`() {
        val env = withStatus(envelope(stop("940GA", listOf(departure(120)))), severe)
        val lines = (TileTimeline.frame(env, fetched, withhold = true) as TileFrame.Rows).lines
        assertTrue(lines.none { it is TileLine.Disruption })
    }

    @Test
    fun `an unchecked line says disruptions couldn't be checked, taking a line, and offers Refresh`() {
        val busy = (1..8L).map { departure(it * 60, line = "l$it", destination = "Stop $it") }
        val unchecked = TileTimeline.frame(envelope(stop("940GA", busy), checked = false), fetched) as TileFrame.Rows
        assertTrue(unchecked.statusUnknown)
        assertEquals(TileTimeline.MAX_LINES - 2, unchecked.lines.size)
        assertFalse(TileLayout.offersAllStops(unchecked, null))
        val checked = TileTimeline.frame(envelope(stop("940GA", busy)), fetched) as TileFrame.Rows
        assertFalse(checked.statusUnknown)
    }

    @Test
    fun `an unchecked line that doesn't fit raises no note`() {
        // Victoria (checked) fits the one line; Jubilee (unchecked) is below the cap.
        val env = envelope(stop("940GA", listOf(departure(120), departure(240, line = "jubilee"))))
        val checkedOnlyVictoria = env.copy(lineStatuses = env.lineStatuses.filter { it.lineId == "victoria" })
        val frame = TileTimeline.frame(checkedOnlyVictoria, fetched, budget = 1) as TileFrame.Rows
        assertFalse(frame.statusUnknown)
        assertEquals(listOf("victoria"), rows(frame).map { it.lineId })
    }

    @Test
    fun `a line turns unchecked when its check expires, ahead of the stop's boundary`() {
        val env = withStatus(envelope(stop("940GA", listOf(departure(240)))), LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service"), at = fetched.minusSeconds(120))
        val entries = TileTimeline.entries(env, fetched)
        assertFalse((entries.at(fetched) as TileFrame.Rows).statusUnknown)
        assertTrue((entries.at(fetched.plusSeconds(180)) as TileFrame.Rows).statusUnknown)
    }

    @Test
    fun `a check made after the fetch keeps the timeline going to its expiry, then drops the mark`() {
        // Arrivals at the fetch, statuses a minute later (the worker's order): the stop goes stale
        // at +5 min, the check at +6 min.
        val env = withStatus(envelope(stop("940GA", listOf(departure(900)))), severe, at = fetched.plusSeconds(60))
        // Built when the envelope arrives, after the check was made.
        val entries = TileTimeline.entries(env, fetched.plusSeconds(60))
        val afterStop = (entries.at(fetched.plusSeconds(330)) as TileFrame.Rows).lines
        assertTrue(afterStop.any { it is TileLine.Disruption })
        val last = entries.last()
        assertNull(last.end)
        assertEquals(fetched.plusSeconds(360), last.start)
        assertTrue((last.frame as TileFrame.Rows).lines.none { it is TileLine.Disruption })
    }

    @Test
    fun `with room for one line, a disrupted service shows as its status, never as no departures`() {
        val env = withStatus(envelope(stop("940GA", listOf(departure(120)))), severe)
        val lines = (TileTimeline.frame(env, fetched, budget = 1) as TileFrame.Rows).lines
        val only = lines.single() as TileLine.Disruption
        assertTrue(only.alone)
    }

    @Test
    fun `a suspension stays on the tile past its stop's boundary until its own check expires`() {
        val stop = stop("940GA", emptyList()).copy(
            lines = listOf(LineRef("waterloo-city", "Waterloo-city", "tube")),
        )
        val env = withStatus(envelope(stop), LineStatus("waterloo-city", 5, "Suspended"), at = fetched.plusSeconds(60))
        val entries = TileTimeline.entries(env, fetched.plusSeconds(60))
        val past = (entries.at(fetched.plusSeconds(330)) as TileFrame.Rows).lines
        assertTrue((past.single() as TileLine.Disruption).alone)
        assertTrue((entries.last().frame as TileFrame.Rows).lines.none { it is TileLine.Disruption })
    }

    @Test
    fun `a live suspension at a stale stop isn't pushed below the cap by fresh rows`() {
        val staleStop = stop("940GA", emptyList(), at = fetched.minusSeconds(320)).copy(
            lines = listOf(LineRef("waterloo-city", "Waterloo-city", "tube")),
        )
        val env = withStatus(
            envelope(stop("940GB", listOf(departure(120))), staleStop),
            LineStatus("waterloo-city", 5, "Suspended"),
            at = fetched.minusSeconds(30),
        )
        val lines = (TileTimeline.frame(env, fetched, budget = 1) as TileFrame.Rows).lines
        assertEquals("waterloo-city", (lines.single() as TileLine.Disruption).row.lineId)
    }

    @Test
    fun `a check from the future stays unshown for the whole timeline, even once its instant passes`() {
        val env = withStatus(envelope(stop("940GA", listOf(departure(240)))), severe, at = fetched.plusSeconds(60))
        val entries = TileTimeline.entries(env, fetched)
        assertTrue(entries.none { (it.frame as? TileFrame.Rows)?.lines.orEmpty().any { l -> l is TileLine.Disruption } })
    }

    @Test
    fun `a check from the future doesn't stretch the timeline`() {
        val env = withStatus(envelope(stop("940GA", listOf(departure(120)))), severe, at = fetched.plusSeconds(86_400))
        val entries = TileTimeline.entries(env, fetched)
        assertEquals(fetched.plusSeconds(300), entries.last().start)
    }
}
