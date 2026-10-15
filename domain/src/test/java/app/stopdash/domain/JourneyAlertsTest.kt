package app.stopdash.domain

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Journey alert schedules and decisions (SPEC *Journeys → Alerts*), on synthetic stops only. */
class JourneyAlertsTest {
    private val zone = ZoneId.of("Europe/London")

    // 2026-10-05 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(2026, 10, day, hour, minute), zone)

    private val journey = FavoriteJourney(JourneyEnd("A", "Alpha"), JourneyEnd("B", "Beta"), "victoria", "Victoria", "tube")
    private val other = FavoriteJourney(JourneyEnd("C", "Gamma"), JourneyEnd("D", "Delta"), "northern", "Northern", "tube")

    @Test
    fun `the default is weekdays from 8 to 10 and 16 to 18`() {
        val schedule = JourneyAlertSchedule.DEFAULT
        assertEquals(JourneyAlertSchedule.WEEKDAYS, schedule.days)
        assertTrue(schedule.isActive(at(5, 8)))
        assertTrue(schedule.isActive(at(5, 9, 59)))
        assertFalse(schedule.isActive(at(5, 10)))
        assertFalse(schedule.isActive(at(5, 12)))
        assertTrue(schedule.isActive(at(5, 17, 30)))
        assertFalse(schedule.isActive(at(5, 18)))
        // Saturday.
        assertFalse(schedule.isActive(at(10, 9)))
    }

    @Test
    fun `the next window opens later today, or on the next chosen day`() {
        val schedule = JourneyAlertSchedule.DEFAULT
        assertEquals(at(5, 8), schedule.nextStart(at(5, 7)))
        assertEquals(at(5, 16), schedule.nextStart(at(5, 8)))
        assertEquals(at(6, 8), schedule.nextStart(at(5, 19)))
        // Friday evening to Monday morning.
        assertEquals(at(12, 8), schedule.nextStart(at(9, 19)))
    }

    @Test
    fun `a schedule with no days or no valid window never opens`() {
        assertNull(JourneyAlertSchedule(days = emptySet()).nextStart(at(5, 7)))
        val backwards = JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(10, 0), LocalTime.of(8, 0))))
        assertNull(backwards.nextStart(at(5, 7)))
        assertFalse(backwards.isActive(at(5, 9)))
    }

    private fun both(schedule: JourneyAlertSchedule, j: FavoriteJourney = journey) = mapOf(
        JourneyAlerts.directionKey(j, j.from.stopId) to schedule,
        JourneyAlerts.directionKey(j, j.to.stopId) to schedule,
    )

    @Test
    fun `turning alerts on watches the saved way in the morning and the way back in the evening`() {
        val defaults = JourneyAlerts.defaultsFor(journey)
        assertEquals(listOf(journey), JourneyAlerts.active(listOf(journey), defaults, at(5, 9)))
        assertEquals(listOf(journey.reversed()), JourneyAlerts.active(listOf(journey), defaults, at(5, 17)))
        assertEquals(emptyList<FavoriteJourney>(), JourneyAlerts.active(listOf(journey), defaults, at(5, 12)))
        assertEquals(emptyList<FavoriteJourney>(), JourneyAlerts.active(listOf(journey), defaults, at(10, 9)))
    }

    @Test
    fun `overlapping directions are both watched, the saved way titling it, what's found holding until the first close`() {
        val overlap = mapOf(
            JourneyAlerts.directionKey(journey, "A") to JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(9, 30)))),
            JourneyAlerts.directionKey(journey, "B") to JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(9, 0), LocalTime.of(10, 0)))),
        )
        assertEquals(listOf(journey, journey.reversed()), JourneyAlerts.activeDirections(journey, overlap, at(5, 9, 15)))
        assertEquals(listOf(journey), JourneyAlerts.active(listOf(journey), overlap, at(5, 9, 15)))
        assertEquals(at(5, 9, 30), JourneyAlerts.watchedUntil(journey, overlap, at(5, 9, 15)))
        // Once only the way back is open, until its own close.
        assertEquals(at(5, 10), JourneyAlerts.watchedUntil(journey, overlap, at(5, 9, 45)))
        assertNull(JourneyAlerts.watchedUntil(journey, overlap, at(5, 11)))
    }

    @Test
    fun `one direction can be off while the other is watched`() {
        val outOnly = mapOf(JourneyAlerts.directionKey(journey, "A") to JourneyAlertSchedule.DEFAULT)
        assertEquals(listOf(journey), JourneyAlerts.active(listOf(journey), outOnly, at(5, 17)))
        val backOnly = mapOf(JourneyAlerts.directionKey(journey, "B") to JourneyAlertSchedule.DEFAULT)
        assertEquals(listOf(journey.reversed()), JourneyAlerts.active(listOf(journey), backOnly, at(5, 9)))
    }

    @Test
    fun `nothing is checked while no journey is watched`() {
        assertNull(JourneyAlerts.nextCheck(listOf(journey), emptyMap(), at(5, 9)))
        // A schedule for a journey no longer saved doesn't count.
        assertNull(JourneyAlerts.nextCheck(emptyList(), mapOf(journey.key to JourneyAlertSchedule.DEFAULT), at(5, 9)))
    }

    @Test
    fun `inside a window the next check is a quarter hour on, outside it the next opening`() {
        val schedules = both(JourneyAlertSchedule.DEFAULT)
        assertEquals(at(5, 9, 15), JourneyAlerts.nextCheck(listOf(journey), schedules, at(5, 9)))
        assertEquals(at(5, 16), JourneyAlerts.nextCheck(listOf(journey), schedules, at(5, 11)))
    }

    @Test
    fun `a late check doesn't push back the ones after it`() {
        val schedules = both(JourneyAlertSchedule.DEFAULT)
        // The 08:00 check run at 08:10 still arms 08:15, not 08:25 (Codex on #716).
        assertEquals(at(5, 8, 15), JourneyAlerts.nextCheck(listOf(journey), schedules, at(5, 8, 10)))
        assertEquals(at(5, 8, 30), JourneyAlerts.nextCheck(listOf(journey), schedules, at(5, 8, 15)))
        // A window set to open at 07:22 counts its quarter hours from then.
        val odd = JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(7, 22), LocalTime.of(10, 0))))
        assertEquals(at(5, 7, 37), JourneyAlerts.nextCheck(listOf(journey), both(odd), at(5, 7, 30)))
    }

    @Test
    fun `a window starting in the hour the clocks skip counts from the jump`() {
        // 2027-03-28: Europe/London jumps from 01:00 to 02:00 (Sunday).
        val night = JourneyAlertSchedule(days = setOf(DayOfWeek.SUNDAY), windows = listOf(TimeWindow(LocalTime.of(1, 30), LocalTime.of(3, 0))))
        val after = ZonedDateTime.of(LocalDateTime.of(2027, 3, 28, 2, 10), zone)
        assertEquals(ZonedDateTime.of(LocalDateTime.of(2027, 3, 28, 2, 15), zone), JourneyAlerts.nextCheck(listOf(journey), both(night), after))
    }

    @Test
    fun `touching windows count their quarter hours from the first one's start`() {
        val joined = JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(9, 0)), TimeWindow(LocalTime.of(9, 0), LocalTime.of(9, 50))))
        assertEquals(at(5, 9, 15), JourneyAlerts.nextCheck(listOf(journey), both(joined), at(5, 9, 5)))
    }

    @Test
    fun `a window closing sooner than the quarter hour is checked as it closes`() {
        val schedules = both(JourneyAlertSchedule.DEFAULT)
        assertEquals(at(5, 10), JourneyAlerts.nextCheck(listOf(journey), schedules, at(5, 9, 55)))
    }

    @Test
    fun `another journey's window opening sooner than the quarter hour is checked then`() {
        val early = JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(9, 0))))
        val later = JourneyAlertSchedule(windows = listOf(TimeWindow(LocalTime.of(8, 5), LocalTime.of(9, 0))))
        val schedules = both(early) + both(later, other)
        assertEquals(at(5, 8, 5), JourneyAlerts.nextCheck(listOf(journey, other), schedules, at(5, 8)))
    }

    @Test
    fun `only journeys whose window is open are active`() {
        val schedules = both(JourneyAlertSchedule.DEFAULT)
        assertEquals(listOf(journey), JourneyAlerts.active(listOf(journey, other), schedules, at(5, 9)))
        assertEquals(emptyList<FavoriteJourney>(), JourneyAlerts.active(listOf(journey, other), schedules, at(5, 12)))
    }

    @Test
    fun `a journey's lines are its own plus those its widget pins run, this direction's first`() {
        val snapshot = DeparturesSnapshot(
            stops = emptyList(),
            fetchedAt = at(5, 9).toInstant(),
            journeys = listOf(
                WidgetJourney("A", setOf(JourneyCall("victoria", "Brixton", null)), key = journey.key, shownFrom = "A"),
                WidgetJourney("A2", setOf(JourneyCall("bus-1", "Somewhere", null)), key = WidgetJourneys.poleKey(journey.key, "A2"), shownFrom = "A"),
                WidgetJourney("C", setOf(JourneyCall("northern", "Morden", null)), key = other.key, shownFrom = "C"),
            ),
        )
        val pinned = JourneyAlerts.pinnedLines(snapshot)
        assertEquals(mapOf("A" to setOf("victoria", "bus-1")), pinned[journey.key])
        // The way the pins were worked out.
        assertEquals(setOf("victoria", "bus-1"), JourneyAlerts.lines(journey, pinned.getValue(journey.key)))
        // The other way, with no pins of its own: the pins for this way stand in.
        assertEquals(setOf("victoria", "bus-1"), JourneyAlerts.lines(journey.reversed(), pinned.getValue(journey.key)))
        // Pins of its own win.
        val both = mapOf("A" to setOf("victoria", "bus-1"), "B" to setOf("victoria", "bus-2"))
        assertEquals(setOf("victoria", "bus-2"), JourneyAlerts.lines(journey.reversed(), both))
        assertEquals(setOf("victoria"), JourneyAlerts.lines(journey, emptyMap()))
        assertEquals(emptyMap<String, Map<String, Set<String>>>(), JourneyAlerts.pinnedLines(null))
        // A line hidden from the ride isn't asked about, though a pin from before still names it; its own line always is.
        val hiding = journey.copy(hiddenLines = setOf(HiddenModes.lineKey("bus-1", "1"), HiddenModes.lineKey("victoria", "Victoria")))
        assertEquals(setOf("victoria"), JourneyAlerts.lines(hiding, pinned.getValue(journey.key)))
    }

    private val suspended = LineStatus("victoria", 6, "Severe Delays", fullText = "Victoria line: severe delays.")
    private val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")

    @Test
    fun `a disrupted line makes an alert, a good service none, and an unanswered journey no result`() {
        val lines = mapOf(journey.key to setOf("victoria"), other.key to setOf("northern"))
        val results = JourneyAlerts.results(listOf(journey, other), lines, mapOf("victoria" to suspended))
        assertEquals(1, results.size)
        assertEquals(journey.key, results.single().key)
        assertEquals("Victoria", results.single().alerts.single().lineName)
        assertEquals("Severe Delays", results.single().alerts.single().description)

        val clear = JourneyAlerts.results(listOf(journey), lines, mapOf("victoria" to good))
        assertTrue(clear.single().alerts.isEmpty())
    }

    @Test
    fun `an alert is posted once, again only when it changes, and cleared when the line recovers`() {
        val bad = JourneyAlerts.results(listOf(journey), mapOf(journey.key to setOf("victoria")), mapOf("victoria" to suspended))
        val active = setOf(journey.key)
        val first = JourneyAlerts.actions(bad, active, announced = emptyMap(), showing = emptySet())
        assertTrue(first.single() is JourneyAlertAction.Post)
        val announced = JourneyAlerts.announcedAfter(emptyMap(), first)

        // The same alert, swiped away: not again.
        val swiped = mapOf(journey.key to bad.single().fingerprint)
        assertEquals(emptyList<JourneyAlertAction>(), JourneyAlerts.actions(bad, active, announced, showing = emptySet(), dismissed = swiped))
        // The same alert, gone only because it timed out (a late check): posted again.
        assertEquals(listOf(JourneyAlertAction.Post(bad.single())), JourneyAlerts.actions(bad, active, announced, showing = emptySet()))
        // Still showing: renewed, silently.
        assertEquals(listOf(JourneyAlertAction.Post(bad.single(), renew = true)), JourneyAlerts.actions(bad, active, announced, setOf(journey.key)))
        // Swiped while the check's request was in flight, so still read as showing: the swipe wins.
        assertEquals(emptyList<JourneyAlertAction>(), JourneyAlerts.actions(bad, active, announced, setOf(journey.key), dismissed = swiped))

        // Worse: posted again.
        val worse = JourneyAlerts.results(listOf(journey), mapOf(journey.key to setOf("victoria")), mapOf("victoria" to suspended.copy(severity = 5, description = "Part Closure")))
        assertTrue(JourneyAlerts.actions(worse, active, announced, setOf(journey.key)).single() is JourneyAlertAction.Post)

        // Recovered: cleared and forgotten, so a later disruption is heard.
        val clear = JourneyAlerts.results(listOf(journey), mapOf(journey.key to setOf("victoria")), mapOf("victoria" to good))
        val cleared = JourneyAlerts.actions(clear, active, announced, setOf(journey.key))
        assertEquals(listOf(JourneyAlertAction.Clear(journey.key)), cleared)
        assertTrue(JourneyAlerts.announcedAfter(announced, cleared).isEmpty())
    }

    @Test
    fun `a swipe stops counting once the alert says something new or is cleared`() {
        val result = JourneyAlertResult(journey.key, listOf(JourneyLineAlert("victoria", "Victoria", "Severe Delays", null)))
        val swiped = mapOf(journey.key to "older")
        assertEquals(emptyMap<String, String>(), JourneyAlerts.dismissedAfter(swiped, listOf(JourneyAlertAction.Post(result))))
        assertEquals(emptyMap<String, String>(), JourneyAlerts.dismissedAfter(swiped, listOf(JourneyAlertAction.Clear(journey.key))))
        val same = mapOf(journey.key to result.fingerprint)
        assertEquals(same, JourneyAlerts.dismissedAfter(same, listOf(JourneyAlertAction.Post(result, renew = true))))
    }

    @Test
    fun `an unanswered check leaves what is shown alone`() {
        val announced = mapOf(journey.key to "x")
        assertEquals(emptyList<JourneyAlertAction>(), JourneyAlerts.actions(emptyList(), setOf(journey.key), announced, setOf(journey.key)))
    }

    @Test
    fun `a closed window takes its notification down`() {
        val actions = JourneyAlerts.actions(emptyList(), active = emptySet(), announced = mapOf(journey.key to "x"), showing = setOf(journey.key))
        assertEquals(listOf(JourneyAlertAction.Clear(journey.key)), actions)
    }

    @Test
    fun `schedules for journeys no longer saved are pruned`() {
        val schedules = both(JourneyAlertSchedule.DEFAULT) + both(JourneyAlertSchedule(days = setOf(DayOfWeek.SUNDAY)), other)
        assertEquals(setOf(journey.key), JourneyAlerts.prune(schedules, listOf(journey)).keys.map(JourneyAlerts::journeyKey).toSet())
    }

    @Test
    fun `a schedule under a key naming neither of a journey's directions is pruned`() {
        val own = both(JourneyAlertSchedule.DEFAULT)
        // The bare journey key, and one for a stop that is neither end.
        val stray = mapOf(journey.key to JourneyAlertSchedule.DEFAULT, JourneyAlerts.directionKey(journey, "Z") to JourneyAlertSchedule.DEFAULT)
        assertEquals(own, JourneyAlerts.prune(own + stray, listOf(journey)))
    }

    @Test
    fun `an alert on a line not answered this time neither clears nor changes`() {
        val active = setOf(journey.key)
        val victoria = JourneyLineAlert("victoria", "Victoria", "Severe Delays", null)
        val announced = mapOf(journey.key to JourneyAlertResult(journey.key, listOf(victoria)).fingerprint)
        // Only the other line answered, and it's fine: the Victoria alert stays as it is.
        val partial = JourneyAlerts.results(listOf(journey), mapOf(journey.key to setOf("victoria", "northern")), mapOf("northern" to good.copy(lineId = "northern")))
        assertEquals(setOf("victoria"), partial.single().unanswered)
        assertEquals(emptyList<JourneyAlertAction>(), JourneyAlerts.actions(partial, active, announced, setOf(journey.key)))
        // A line never alerted on that goes unanswered (one TfL doesn't know) holds nothing back.
        val unknown = JourneyAlerts.results(listOf(journey), mapOf(journey.key to setOf("victoria", "elsewhere")), mapOf("victoria" to good))
        assertEquals(listOf(JourneyAlertAction.Clear(journey.key)), JourneyAlerts.actions(unknown, active, announced, setOf(journey.key)))
    }

    @Test
    fun `the repeated hour as the clocks go back closes at its own second occurrence`() {
        val london = java.time.ZoneId.of("Europe/London")
        // Sunday 25 October 2026: 01:00–02:00 happens twice.
        val schedule = JourneyAlertSchedule(setOf(java.time.DayOfWeek.SUNDAY), listOf(TimeWindow(java.time.LocalTime.of(1, 0), java.time.LocalTime.of(1, 30))))
        val local = java.time.LocalDateTime.of(2026, 10, 25, 1, 15)
        val second = java.time.ZonedDateTime.ofStrict(local, java.time.ZoneOffset.UTC, london)
        val end = schedule.currentEnd(second)!!
        assertEquals(java.time.ZonedDateTime.ofStrict(java.time.LocalDateTime.of(2026, 10, 25, 1, 30), java.time.ZoneOffset.UTC, london), end)
        assertTrue(end.isAfter(second))
        // And from the first occurrence, its own end, the earlier one.
        val first = java.time.ZonedDateTime.ofStrict(local, java.time.ZoneOffset.ofHours(1), london)
        assertEquals(java.time.ZoneOffset.ofHours(1), schedule.currentEnd(first)!!.offset)
        // A start already past in the first occurrence comes round again in the second.
        val afterFirstStart = java.time.ZonedDateTime.ofStrict(java.time.LocalDateTime.of(2026, 10, 25, 1, 45), java.time.ZoneOffset.ofHours(1), london)
        assertTrue(schedule.nextStart(afterFirstStart)!!.isAfter(afterFirstStart))
    }

    @Test
    fun `a window starting in the hour skipped as the clocks go forward opens at the jump`() {
        val london = ZoneId.of("Europe/London")
        // Sunday 29 March 2026: 01:00–02:00 doesn't happen.
        val schedule = JourneyAlertSchedule(setOf(DayOfWeek.SUNDAY), listOf(TimeWindow(LocalTime.of(1, 30), LocalTime.of(2, 30))))
        val before = ZonedDateTime.of(LocalDateTime.of(2026, 3, 29, 0, 45), london)
        val opens = schedule.nextStart(before)!!
        assertEquals(LocalTime.of(2, 0), opens.toLocalTime())
        // Where it opens is where it's active from: the two agree.
        assertTrue(schedule.isActive(opens))
        assertFalse(schedule.isActive(opens.minusSeconds(1)))
    }

    @Test
    fun `a window the clocks go back into opens again when they do`() {
        val london = ZoneId.of("Europe/London")
        // Sunday 25 October 2026: at 02:00 BST the clocks go back to 01:00 GMT, inside 00:30–01:30 again.
        val schedule = JourneyAlertSchedule(setOf(DayOfWeek.SUNDAY), listOf(TimeWindow(LocalTime.of(0, 30), LocalTime.of(1, 30))))
        val closed = ZonedDateTime.ofStrict(LocalDateTime.of(2026, 10, 25, 1, 30), java.time.ZoneOffset.ofHours(1), london)
        assertFalse(schedule.isActive(closed))
        val again = schedule.nextStart(closed)!!
        assertEquals(ZonedDateTime.ofStrict(LocalDateTime.of(2026, 10, 25, 1, 0), java.time.ZoneOffset.UTC, london), again)
        assertTrue(schedule.isActive(again))
    }

    @Test
    fun `the lines a shown alert is about are read back from what it said`() {
        val shown = JourneyAlertResult(journey.key, listOf(JourneyLineAlert("victoria", "Victoria", "Severe Delays", "A signal failure."), JourneyLineAlert("northern", "Northern", "Minor Delays", null)))
        assertEquals(setOf("victoria", "northern"), JourneyAlertResult.linesIn(shown.fingerprint))
        assertEquals(emptySet<String>(), JourneyAlertResult.linesIn(null))
    }

    @Test
    fun `overlapping and touching windows close together at the last end`() {
        val schedule = JourneyAlertSchedule(
            setOf(DayOfWeek.MONDAY),
            listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0)), TimeWindow(LocalTime.of(9, 0), LocalTime.of(11, 0)), TimeWindow(LocalTime.of(11, 0), LocalTime.of(11, 30))),
        )
        val monday = ZonedDateTime.of(LocalDateTime.of(2026, 10, 5, 9, 30), ZoneId.of("Europe/London"))
        assertEquals(LocalTime.of(11, 30), schedule.currentEnd(monday)!!.toLocalTime())
    }

    @Test
    fun `a line that couldn't be placed this time holds its alert, and one placed elsewhere doesn't`() {
        val active = setOf(journey.key)
        val pinned = JourneyLineAlert("northern", "Northern", "Severe Delays", null)
        val announced = mapOf(journey.key to JourneyAlertResult(journey.key, listOf(pinned)).fingerprint)
        val asked = mapOf(journey.key to setOf("victoria"))
        // Pins unreadable: the Northern line may still be this journey's, so the alert stays.
        val unsure = JourneyAlerts.results(listOf(journey), asked, mapOf("victoria" to good), inconclusive = mapOf(journey.key to setOf("northern")))
        assertEquals(emptyList<JourneyAlertAction>(), JourneyAlerts.actions(unsure, active, announced, setOf(journey.key)))
        // Pins read and the Northern line not among them: it isn't this direction's, so the alert goes.
        val placed = JourneyAlerts.results(listOf(journey), asked, mapOf("victoria" to good))
        assertEquals(listOf(JourneyAlertAction.Clear(journey.key)), JourneyAlerts.actions(placed, active, announced, setOf(journey.key)))
    }

    @Test
    fun `the open directions say which way a journey is watched`() {
        val schedules = JourneyAlerts.defaultsFor(journey)
        val morning = ZonedDateTime.of(LocalDateTime.of(2026, 10, 5, 9, 0), ZoneId.systemDefault())
        val evening = ZonedDateTime.of(LocalDateTime.of(2026, 10, 5, 17, 0), ZoneId.systemDefault())
        val there = JourneyAlerts.directionsOf(listOf(journey), schedules, morning)
        val back = JourneyAlerts.directionsOf(listOf(journey), schedules, evening)
        assertEquals(setOf(journey.from.stopId), there[journey.key])
        assertEquals(setOf(journey.to.stopId), back[journey.key])
    }

    @Test
    fun `the first open window's close is found, and nothing when none is open`() {
        val schedules = JourneyAlerts.defaultsFor(journey)
        assertEquals(at(5, 10), JourneyAlerts.closesAfter(listOf(journey), schedules, at(5, 9)))
        assertEquals(at(5, 18), JourneyAlerts.closesAfter(listOf(journey), schedules, at(5, 17)))
        assertNull(JourneyAlerts.closesAfter(listOf(journey), schedules, at(5, 12)))
        // A schedule for a journey no longer saved doesn't count.
        assertNull(JourneyAlerts.closesAfter(emptyList(), schedules, at(5, 9)))
    }

    @Test
    fun `a swipe of one direction's alert doesn't hide the other's once the first has closed`() {
        val lines = mapOf(journey.key to setOf("victoria"))
        val statuses = mapOf("victoria" to suspended)
        val active = setOf(journey.key)
        // Both ways open, then only the way back: the same disruption, found for different directions.
        val both = JourneyAlerts.results(listOf(journey), lines, statuses, directions = mapOf(journey.key to setOf("A", "B"))).single()
        val back = JourneyAlerts.results(listOf(journey), lines, statuses, directions = mapOf(journey.key to setOf("B"))).single()
        assertTrue(both.fingerprint != back.fingerprint)
        // The first alert was swiped; the way back's is posted when only it is open.
        val swiped = mapOf(journey.key to both.fingerprint)
        val announced = mapOf(journey.key to both.fingerprint)
        assertEquals(listOf(JourneyAlertAction.Post(back)), JourneyAlerts.actions(listOf(back), active, announced, showing = emptySet(), dismissed = swiped))
        // The directions line names no line.
        assertEquals(setOf("victoria"), JourneyAlertResult.linesIn(both.fingerprint))
        assertTrue(both.copy(unanswered = setOf("victoria")).leavesOut(both.fingerprint))
    }

    @Test
    fun `a swipe holds for its window only, however late the check after it runs`() {
        val lines = mapOf(journey.key to setOf("victoria"))
        val statuses = mapOf("victoria" to suspended)
        val active = setOf(journey.key)
        val out = mapOf(journey.key to setOf("A"))
        val monday = JourneyAlerts.results(listOf(journey), lines, statuses, directions = out, until = mapOf(journey.key to at(5, 10).toInstant())).single()
        val tuesday = JourneyAlerts.results(listOf(journey), lines, statuses, directions = out, until = mapOf(journey.key to at(6, 10).toInstant())).single()
        // Within one window, the same alert reads the same: a swipe holds.
        val swiped = mapOf(journey.key to monday.fingerprint)
        assertEquals(emptyList<JourneyAlertAction>(), JourneyAlerts.actions(listOf(monday), active, swiped, showing = emptySet(), dismissed = swiped))
        // The next morning's window, with no check in between to forget the swipe: posted again.
        assertEquals(listOf(JourneyAlertAction.Post(tuesday)), JourneyAlerts.actions(listOf(tuesday), active, swiped, showing = emptySet(), dismissed = swiped))
        assertEquals(setOf("victoria"), JourneyAlertResult.linesIn(tuesday.fingerprint))
    }

    @Test
    fun `what an alert says comes from the alert up where its record was never written`() {
        val said = "@x until 0\nnorthern|Severe delays"
        val older = "@x until 0\nvictoria|Minor delays"
        // Recorded only, up only, up overruling an older record, and an alert up that records nothing.
        assertEquals(mapOf(journey.key to said), JourneyAlerts.shown(mapOf(journey.key to said), emptyMap()))
        assertEquals(mapOf(journey.key to said), JourneyAlerts.shown(emptyMap(), mapOf(journey.key to said)))
        assertEquals(mapOf(journey.key to said), JourneyAlerts.shown(mapOf(journey.key to older), mapOf(journey.key to said)))
        assertEquals(mapOf(journey.key to older), JourneyAlerts.shown(mapOf(journey.key to older), mapOf(journey.key to null)))
        // So a line only the alert up names is left unanswered, not cleared by going unasked.
        val asked = mapOf(journey.key to setOf("victoria"))
        assertEquals(mapOf(journey.key to setOf("northern")), JourneyAlerts.unasked(listOf(journey), asked, JourneyAlerts.shown(emptyMap(), mapOf(journey.key to said))))
        // Unless the rider has hidden that line from the ride since: it goes unasked on purpose.
        val hiding = journey.copy(hiddenLines = setOf(HiddenModes.lineKey("northern", "Northern line")))
        assertEquals(mapOf(journey.key to emptySet<String>()), JourneyAlerts.unasked(listOf(hiding), asked, JourneyAlerts.shown(emptyMap(), mapOf(journey.key to said))))
        // And a partial answer leaves that alert as it is.
        val partial = JourneyAlertResult(journey.key, emptyList(), unanswered = setOf("northern"), directions = setOf("A"), until = null)
        assertEquals(emptyList<JourneyAlertAction>(), JourneyAlerts.actions(listOf(partial), setOf(journey.key), JourneyAlerts.shown(emptyMap(), mapOf(journey.key to said)), setOf(journey.key)))
    }

    @Test
    fun `a pin moved while a check was out changes the lines it asked about`() {
        val at = ZonedDateTime.of(LocalDateTime.of(2026, 10, 5, 9, 0), JourneyAlerts.zone())
        val schedules = JourneyAlerts.defaultsFor(journey)
        val pinned = mapOf(journey.key to mapOf(journey.from.stopId to setOf("northern")))
        val asked = JourneyAlerts.linesFor(listOf(journey), schedules, at, pinned)
        assertEquals(mapOf(journey.key to setOf("victoria", "northern")), asked)
        // The pin gone by the time the answer came back: no longer the same lines, so the answer doesn't hold.
        assertTrue(asked != JourneyAlerts.linesFor(listOf(journey), schedules, at, emptyMap()))
        assertEquals(asked, JourneyAlerts.linesFor(listOf(journey), schedules, at, pinned))
    }

    @Test
    fun `every watched journey whose lines changed is moved, not only those with something to do`() {
        val asked = mapOf("a" to setOf("victoria"), "b" to setOf("northern"), "gone" to setOf("central"))
        val now = mapOf("a" to setOf("victoria"), "b" to setOf("northern", "jubilee"))
        // "b" gained a pin; "gone" is no longer watched, so needs no check of its own.
        assertEquals(setOf("b"), JourneyAlerts.moved(asked, now))
    }

    @Test
    fun `alerts are held back only on a mobile network known to be abroad`() {
        assertTrue(JourneyAlerts.abroad("fr"))
        assertTrue(JourneyAlerts.abroad("ES"))
        assertFalse(JourneyAlerts.abroad("gb"))
        assertFalse(JourneyAlerts.abroad("GB"))
        // The Crown Dependencies' networks are home too.
        assertFalse(JourneyAlerts.abroad("je"))
        assertFalse(JourneyAlerts.abroad("im"))
        // No network to say (Wi-Fi only, no SIM, airplane mode): alerts fire as they would anyway.
        assertFalse(JourneyAlerts.abroad(null))
        assertFalse(JourneyAlerts.abroad(""))
        assertFalse(JourneyAlerts.abroad("  "))
    }

    @Test
    fun `only a network the phone is registered on gives its country`() {
        assertEquals("fr", JourneyAlerts.registeredCountry("fr", simReady = true, networkOperator = "20801"))
        // A nearby cell's country, with no SIM or no registration, says nothing of the rider's network.
        assertNull(JourneyAlerts.registeredCountry("fr", simReady = false, networkOperator = "20801"))
        assertNull(JourneyAlerts.registeredCountry("fr", simReady = true, networkOperator = ""))
        assertNull(JourneyAlerts.registeredCountry("fr", simReady = true, networkOperator = null))
        assertNull(JourneyAlerts.registeredCountry("", simReady = true, networkOperator = "23410"))
    }

    @Test
    fun `london takes in the commuter belt and stops short of the next cities`() {
        // Public station positions only (AGENTS.md *Privacy*).
        val inside = mapOf(
            "Euston" to Coordinates(51.5282, -0.1337),
            "Reading" to Coordinates(51.4585, -0.9719),
            "Shenfield" to Coordinates(51.6309, 0.3297),
            "Brighton" to Coordinates(50.8290, -0.1413),
            "Cambridge" to Coordinates(52.1940, 0.1376),
            "Oxford" to Coordinates(51.7535, -1.2700),
            "Peterborough" to Coordinates(52.5747, -0.2502),
        )
        for ((name, at) in inside) assertTrue(name, JourneyAlerts.inLondon(at))
        val outside = mapOf(
            "Birmingham New Street" to Coordinates(52.4778, -1.8990),
            "Bristol Temple Meads" to Coordinates(51.4491, -2.5813),
            "Manchester Piccadilly" to Coordinates(53.4774, -2.2309),
            "Paris Nord" to Coordinates(48.8809, 2.3553),
        )
        for ((name, at) in outside) assertFalse(name, JourneyAlerts.inLondon(at))
    }

    @Test
    fun `alerts are held back only where the phone is known to be away`() {
        assertTrue(JourneyAlerts.awayFromLondon(LocationFix(Coordinates(52.4778, -1.8990), isFallback = false, accuracyMeters = 20f)))
        assertFalse(JourneyAlerts.awayFromLondon(LocationFix(Coordinates(51.5282, -0.1337), isFallback = false, accuracyMeters = 20f)))
        // No position (location not allowed all the time, or no fix): alerts fire as they would anyway.
        assertFalse(JourneyAlerts.awayFromLondon(null))
    }

    @Test
    fun `a fallback fix says nothing of where the phone is now`() {
        // Handed back when a fresh fix timed out: it may be from before the rider crossed the line.
        assertFalse(JourneyAlerts.awayFromLondon(LocationFix(Coordinates(52.4778, -1.8990), isFallback = true, accuracyMeters = 20f)))
    }

    @Test
    fun `a fix is away only when all of its uncertainty lies outside`() {
        // Just past the edge, due north of Charing Cross: about 121 km out.
        val pastTheEdge = Coordinates(51.5073 + 121.0 / 111.2, -0.1276)
        assertTrue(JourneyAlerts.awayFromLondon(LocationFix(pastTheEdge, isFallback = false, accuracyMeters = 50f)))
        // The same point from an approximate fix 3 km wide could be inside: alerts fire.
        assertFalse(JourneyAlerts.awayFromLondon(LocationFix(pastTheEdge, isFallback = false, isCoarse = true, accuracyMeters = 3_000f)))
        // A fix that reports no accuracy can't say its uncertainty lies outside: alerts fire.
        assertFalse(JourneyAlerts.awayFromLondon(LocationFix(Coordinates(52.4778, -1.8990), isFallback = false)))
        // Far enough out, even a rough fix settles it.
        assertTrue(JourneyAlerts.awayFromLondon(LocationFix(Coordinates(52.4778, -1.8990), isFallback = false, accuracyMeters = 3_000f)))
    }

    @Test
    fun `a swipe outlasts an alert only held back`() {
        val dismissed = mapOf("k" to "fp")
        val clear = listOf(JourneyAlertAction.Clear("k"))
        // Held back (abroad or away, its window still open): the swipe is kept for the return.
        assertEquals(dismissed, JourneyAlerts.dismissedAfter(dismissed, clear, heldBack = setOf("k")))
        // Cleared for good (window closed, lines clear): forgotten, so the next swipe counts.
        assertEquals(emptyMap<String, String>(), JourneyAlerts.dismissedAfter(dismissed, clear))
    }

    // A bus route north from Bank, its stops past Moorgate made up, and an alert in TfL's style placing a
    // diversion at its south end (as in RouteDisruptionTest). Made-up words.
    private val busRoute = LineSequence(
        listOf(LineRoute("Bank - North End", listOf("b1", "b2", "b3", "b4", "b5", "b6"))),
        mapOf(
            "b1" to "Bank Station / King William Street", "b2" to "Example Street", "b3" to "Moorgate Station",
            "b4" to "Alpha Road", "b5" to "Beta Road", "b6" to "North End",
        ),
    )
    private val southEnd = "EXAMPLE, EC2 - ROUTE 99 is on diversion northbound via Example Street. Buses are not " +
        "serving stops between 'Bank Station/King William Street' and 'Moorgate Station'. Please allow extra time."
    private val diverted = LineStatus("99", 6, "Diversion", southEnd, soleAlert = true)
    private fun busRide(from: String, to: String, mode: String = "bus") =
        FavoriteJourney(JourneyEnd(from, busRoute.stopNames.getValue(from)), JourneyEnd(to, busRoute.stopNames.getValue(to)), "99", "99", mode)
    private fun alertsFor(
        ride: FavoriteJourney,
        status: LineStatus,
        routes: Map<String, LineSequence> = mapOf(status.lineId to busRoute),
        origins: Set<String> = setOf(ride.from.stopId),
        modes: Map<String, String> = emptyMap(),
    ) = JourneyAlerts.results(
        listOf(ride), mapOf(ride.key to setOf(status.lineId)), mapOf(status.lineId to status),
        directions = mapOf(ride.key to origins), routes = routes, modes = modes,
    ).single().alerts

    @Test
    fun `a bus diversion at the far end of the route doesn't alert a ride it doesn't reach`() {
        // A diversion miles down the route alerted a ride nowhere near it (maintainer, 2026-10-15).
        assertTrue(alertsFor(busRide("b4", "b6"), diverted).isEmpty())
        // A ride out of the stretch, or through it, is alerted.
        assertEquals(listOf("Diversion"), alertsFor(busRide("b3", "b5"), diverted).map { it.description })
        assertEquals(listOf("Diversion"), alertsFor(busRide("b1", "b6"), diverted).map { it.description })
        // Unknown is on: the route not read, a ride the route doesn't run (the way back, here), or a line
        // that isn't a bus.
        assertEquals(1, alertsFor(busRide("b4", "b6"), diverted, routes = emptyMap()).size)
        assertEquals(1, alertsFor(busRide("b4", "b6"), diverted, origins = setOf("b4", "b6")).size)
        assertEquals(1, alertsFor(busRide("b4", "b6", mode = "tube"), diverted).size)
    }

    @Test
    fun `a line a ride pins is weighed as its own mode`() {
        // A line pinned rather than saved on is ridden as the snapshot says, not as the ride's own (Codex on #866):
        // a rail line's closure away from the stops stays on, as a bus line's alert off the ride doesn't.
        val pinnedBus = diverted.copy(lineId = "98")
        assertTrue(alertsFor(busRide("b4", "b6"), pinnedBus, modes = mapOf("98" to "bus")).isEmpty())
        assertEquals(1, alertsFor(busRide("b4", "b6"), pinnedBus, modes = mapOf("98" to "overground")).size)
        assertEquals(1, alertsFor(busRide("b4", "b6"), pinnedBus).size)
        val lines = mapOf(busRide("b4", "b6").key to setOf("98"))
        assertEquals(setOf("98"), JourneyAlerts.routesToRead(listOf(busRide("b4", "b6")), lines, mapOf("98" to pinnedBus), mapOf("98" to "bus")))
        assertEquals(emptySet<String>(), JourneyAlerts.routesToRead(listOf(busRide("b4", "b6")), lines, mapOf("98" to pinnedBus), mapOf("98" to "overground")))
        // The modes come from the snapshot's stops.
        val snapshot = DeparturesSnapshot(
            stops = listOf(StopArrivals("b4", "Alpha Road", emptyList(), java.time.Instant.EPOCH, lines = listOf(LineRef("98", "98", "bus")))),
            fetchedAt = java.time.Instant.EPOCH,
        )
        assertEquals(mapOf("98" to "bus"), JourneyAlerts.lineModes(snapshot))
    }

    @Test
    fun `a pinned line is placed from the poles its pin boards and gets off at`() {
        // A line pinned beside the ride boards at its own pole, in no stop area the saved ends name, and
        // gets off at a far-end stop of its own (maintainer, 2026-10-15).
        val beside = busRoute.copy(
            routes = listOf(LineRoute("Bank - North End", listOf("b1", "b2", "b3", "p4", "b5", "p6"))),
            stopNames = busRoute.stopNames + mapOf("p4" to "Alpha Road Stand", "p6" to "North End Stand"),
        )
        val ride = FavoriteJourney(JourneyEnd("b4", "Alpha Road", areaId = "A4"), JourneyEnd("b6", "North End", areaId = "A6"), "99", "99", "bus")
        val pinned = diverted.copy(lineId = "98")
        fun alerts(pins: List<WidgetJourney>) = JourneyAlerts.results(
            listOf(ride), mapOf(ride.key to setOf("98")), mapOf("98" to pinned),
            directions = mapOf(ride.key to setOf("b4")), routes = mapOf("98" to beside), modes = mapOf("98" to "bus"),
            pins = mapOf(ride.key to pins),
        ).single().alerts
        val pin = WidgetJourney("p4", setOf(JourneyCall("98", "North End", null)), ride.key, shownFrom = "b4", alightIds = setOf("p6", "x9"))
        // Without the pin's poles the ride can't be placed on the line, so its alert stays on.
        assertEquals(1, alerts(emptyList()).size)
        assertEquals(1, alerts(listOf(pin.copy(alightIds = emptySet()))).size)
        // With them it's placed, off the stretch; a far-end stop the line doesn't call at (x9) is no ride of its.
        assertTrue(alerts(listOf(pin)).isEmpty())
        // A pin for the other way, or of another line, isn't this way's ride on the line.
        assertEquals(1, alerts(listOf(pin.copy(shownFrom = "b6"))).size)
        assertEquals(1, alerts(listOf(pin.copy(calls = setOf(JourneyCall("97", "North End", null))))).size)
        // Pins are each journey's, its neighboring poles' with its own.
        val snapshot = DeparturesSnapshot(
            stops = emptyList(), fetchedAt = java.time.Instant.EPOCH,
            journeys = listOf(pin, pin.copy(key = WidgetJourneys.poleKey(ride.key, "p4"))),
        )
        assertEquals(setOf(ride.key), JourneyAlerts.pins(snapshot).keys)
        assertEquals(2, JourneyAlerts.pins(snapshot).getValue(ride.key).size)
    }

    @Test
    fun `the way back is placed by its stop areas, its poles across the road`() {
        // The way back boards and alights at the other poles of the same stop areas (Codex on #866).
        val bothWays = busRoute.copy(
            routes = busRoute.routes + LineRoute("North End - Bank", listOf("n6", "n5", "n4", "n3", "n2", "n1")),
            // The stretch's stops named as the alert names them; the ride's ends, across the road, named otherwise.
            stopNames = busRoute.stopNames + mapOf(
                "n1" to "Bank Station / King William Street", "n2" to "Example Street", "n3" to "Moorgate Station",
                "n4" to "Alpha Rd", "n5" to "Beta Road", "n6" to "North End Stand",
            ),
            stopAreas = mapOf("b4" to "A4", "n4" to "A4", "b6" to "A6", "n6" to "A6"),
        )
        val ride = FavoriteJourney(JourneyEnd("b4", "Alpha Road", areaId = "A4"), JourneyEnd("b6", "North End", areaId = "A6"), "99", "99", "bus")
        val diversionBothWays = diverted.copy(fullText = southEnd.replace("northbound", "in both directions"))
        // Watched both ways: the way back (n6 to n4) is found by its areas, and is off the stretch too.
        assertTrue(alertsFor(ride, diversionBothWays, routes = mapOf("99" to bothWays), origins = setOf("b4", "b6")).isEmpty())
        // Without them the way back can't be placed, so the alert stays on.
        val noAreas = ride.copy(from = ride.from.copy(areaId = ""), to = ride.to.copy(areaId = ""))
        assertEquals(1, alertsFor(noAreas, diversionBothWays, routes = mapOf("99" to bothWays), origins = setOf("b4", "b6")).size)
    }

    @Test
    fun `a bus line's alert on the ride is the one said when the worst is off it`() {
        val missed = "Bus stop 'Beta Road' will not be served."
        val several = LineStatus(
            "99", 5, "Diversion", southEnd,
            underWay = listOf(LineAlert(5, "Diversion", southEnd), LineAlert(6, "Part Closure", missed)),
        )
        val said = alertsFor(busRide("b4", "b6"), several).single()
        assertEquals("Part Closure", said.description)
        assertEquals(missed, said.fullText)
        // With both on the ride, the worst is said, as before.
        assertEquals("Diversion", alertsFor(busRide("b3", "b6"), several).single().description)
    }

    @Test
    fun `a route is read only for a bus ride's line with an alert a route could place`() {
        val ride = busRide("b4", "b6")
        val lines = mapOf(ride.key to setOf("99"))
        assertEquals(setOf("99"), JourneyAlerts.routesToRead(listOf(ride), lines, mapOf("99" to diverted)))
        // Not for a tube line, a good service, or an alert whose words couldn't place it.
        assertEquals(emptySet<String>(), JourneyAlerts.routesToRead(listOf(busRide("b4", "b6", mode = "tube")), lines, mapOf("99" to diverted)))
        assertEquals(emptySet<String>(), JourneyAlerts.routesToRead(listOf(ride), lines, mapOf("99" to LineStatus("99", LineStatus.GOOD_SERVICE, "Good Service"))))
        assertEquals(emptySet<String>(), JourneyAlerts.routesToRead(listOf(ride), lines, mapOf("99" to diverted.copy(soleAlert = false))))
    }
}
