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
    fun `a swipe outlasts an alert only held back`() {
        val dismissed = mapOf("k" to "fp")
        val clear = listOf(JourneyAlertAction.Clear("k"))
        // Held back (abroad, its window still open): the swipe is kept for the return.
        assertEquals(dismissed, JourneyAlerts.dismissedAfter(dismissed, clear, heldBack = setOf("k")))
        // Cleared for good (window closed, lines clear): forgotten, so the next swipe counts.
        assertEquals(emptyMap<String, String>(), JourneyAlerts.dismissedAfter(dismissed, clear))
    }
}
