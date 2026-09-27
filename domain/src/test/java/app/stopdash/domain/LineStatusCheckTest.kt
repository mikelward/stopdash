package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot's age-stamped line statuses (SPEC D3/D4): a disruption is marked only while its
 * check is younger than the shared staleness threshold, two writers' checks merge newest-first, and
 * the widget's refresh re-checks the lines its stops show.
 */
class LineStatusCheckTest {
    private val t0: Instant = Instant.parse("2026-09-20T08:00:00Z")

    private val severe = LineStatus("victoria", 6, "Severe Delays")
    private val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")

    private fun departure(lineId: String = "victoria") = Departure(
        lineId = lineId,
        lineName = "Victoria",
        direction = "inbound",
        destination = "Brixton",
        platform = null,
        expectedArrival = t0.plusSeconds(300),
        mode = "tube",
    )

    private fun snapshot(checks: Map<String, LineStatusCheck>, lineId: String = "victoria") = DeparturesSnapshot(
        stops = listOf(StopArrivals("A", "Stop A", listOf(departure(lineId)), t0)),
        fetchedAt = t0,
        lineStatuses = checks,
    )

    @Test
    fun `a fresh disruption is marked, and withheld once as old as a stale countdown`() {
        val snap = snapshot(mapOf("victoria" to LineStatusCheck(severe, t0)))
        assertEquals(severe, snap.liveLineStatuses(t0.plusSeconds(60))["victoria"])
        assertTrue(snap.liveLineStatuses(t0.plus(Duration.ofMinutes(5))).isEmpty())
        // From the future (the clock moved back): not trusted either.
        assertTrue(snap.liveLineStatuses(t0.minusSeconds(1)).isEmpty())
    }

    @Test
    fun `a good service is kept as a check but never marked`() {
        val snap = snapshot(mapOf("victoria" to LineStatusCheck(good, t0)))
        assertTrue(snap.liveLineStatuses(t0).isEmpty())
        assertEquals(1, snap.lineStatuses.size)
    }

    @Test
    fun `the widget marks a disrupted line's rows through the shared row builder`() {
        val snap = snapshot(mapOf("victoria" to LineStatusCheck(severe, t0)))
        val now = t0.plusSeconds(30)
        val rows = DepartureRows.across(snap.stops, now, snap.liveLineStatuses(now))
        assertEquals(severe, rows.single().status)
    }

    @Test
    fun `fresh rows rank first, and a status-only row counts as fresh whatever its stop's age`() {
        fun row(lineId: String, departures: List<Departure>, status: LineStatus? = null) = DepartureRow(
            stopId = lineId, stopName = lineId, lineId = lineId, lineName = lineId, direction = "", directionKey = "",
            destination = "", mode = "tube", upcoming = departures, fetchedAt = t0, status = status,
        )
        val staleRow = row("old", listOf(departure("old")))
        val suspension = row("waterloo-city", emptyList(), LineStatus("waterloo-city", 5, "Suspended"))
        val freshRow = row("victoria", listOf(departure()))
        val staleStops = setOf("old", "waterloo-city")
        val ordered = DepartureRows.freshFirst(listOf(staleRow, suspension, freshRow)) { it.stopId in staleStops }
        assertEquals(listOf("waterloo-city", "victoria", "old"), ordered.map { it.lineId })
    }

    @Test
    fun `merging keeps each line's newer check, whichever side has it`() {
        val older = mapOf("victoria" to LineStatusCheck(severe, t0), "jubilee" to LineStatusCheck(good.copy(lineId = "jubilee"), t0.plusSeconds(60)))
        val newer = mapOf("victoria" to LineStatusCheck(good, t0.plusSeconds(30)), "jubilee" to LineStatusCheck(severe.copy(lineId = "jubilee"), t0))
        val merged = LineStatusCheck.newest(older, newer)
        assertEquals(good, merged.getValue("victoria").status)
        assertEquals(LineStatus.GOOD_SERVICE, merged.getValue("jubilee").status.severity)
        assertEquals(setOf("victoria"), LineStatusCheck.newest(older, newer, setOf("victoria")).keys)
    }

    @Test
    fun `a refresh re-checks the shown lines, reusing a recent check`() = runTest {
        val now = t0.plusSeconds(120)
        val prior = snapshot(mapOf("victoria" to LineStatusCheck(good, t0))).let {
            it.copy(stops = it.stops + StopArrivals("B", "Stop B", listOf(departure("jubilee")), t0))
        }
        var asked: Set<String>? = null
        val refreshed = WidgetRefresh.refreshedLineStatuses(prior, now, reuse = Duration.ofSeconds(90)) { ids ->
            asked = ids
            listOf(severe, severe.copy(lineId = "jubilee"))
        }
        // Victoria's check is 120 s old, past the 90 s reuse, so both lines are asked about.
        assertEquals(setOf("victoria", "jubilee"), asked)
        assertEquals(LineStatusCheck(severe, now), refreshed.lineStatuses["victoria"])
        assertEquals(now, refreshed.lineStatuses.getValue("jubilee").checkedAt)

        var askedAgain: Set<String>? = null
        WidgetRefresh.refreshedLineStatuses(refreshed, now.plusSeconds(10), reuse = Duration.ofSeconds(90)) {
            askedAgain = it
            emptyList()
        }
        assertNull("both checked 10 s ago: nothing asked", askedAgain)
    }

    @Test
    fun `a slow lookup is stamped when TfL answered, not when the refresh began`() = runTest {
        val began = t0.plusSeconds(120)
        val answered = began.plusSeconds(200)
        val refreshed = WidgetRefresh.refreshedLineStatuses(snapshot(emptyMap()), began, answeredAt = { answered }) {
            listOf(severe)
        }
        assertEquals(LineStatusCheck(severe, answered), refreshed.lineStatuses["victoria"])
        // Still marked a moment after the answer, though the refresh began over three minutes before.
        assertEquals(severe, refreshed.liveLineStatuses(answered.plusSeconds(150))["victoria"])
    }

    @Test
    fun `a failed lookup keeps the prior checks to age out, and drops lines no longer shown`() = runTest {
        val prior = snapshot(
            mapOf("victoria" to LineStatusCheck(severe, t0), "gone" to LineStatusCheck(severe.copy(lineId = "gone"), t0)),
        )
        val refreshed = WidgetRefresh.refreshedLineStatuses(prior, t0.plusSeconds(200)) { null }
        assertEquals(setOf("victoria"), refreshed.lineStatuses.keys)
        assertEquals(t0, refreshed.lineStatuses.getValue("victoria").checkedAt)
    }

    @Test
    fun `a line TfL gave no status for gets a no-verdict check, and reads as unchecked`() = runTest {
        val refreshed = WidgetRefresh.refreshedLineStatuses(snapshot(emptyMap()), t0) { emptyList() }
        assertEquals(LineStatusCheck.noVerdict("victoria", t0), refreshed.lineStatuses["victoria"])
        assertEquals(false, refreshed.statusKnown("victoria", t0))
        assertTrue(refreshed.liveLineStatuses(t0).isEmpty())
    }

    @Test
    fun `after the clock moves back, a new check replaces the future-dated one`() = runTest {
        // Checked "at" 08:10 on a clock that has since been set back to 08:00.
        val future = snapshot(mapOf("victoria" to LineStatusCheck(severe, t0.plusSeconds(600))))
        val refreshed = WidgetRefresh.refreshedLineStatuses(future, t0) { listOf(good) }
        assertEquals(LineStatusCheck(good, t0), refreshed.lineStatuses["victoria"])
        // And merging two writers' checks at t0 prefers the one that isn't from the future.
        val merged = LineStatusCheck.newest(future.lineStatuses, refreshed.lineStatuses, now = t0)
        assertEquals(t0, merged.getValue("victoria").checkedAt)
    }

    @Test
    fun `a line's status is known only while it has a live check`() {
        val snap = snapshot(mapOf("victoria" to LineStatusCheck(good, t0)))
        assertTrue(snap.statusKnown("victoria", t0.plusSeconds(60)))
        assertEquals(false, snap.statusKnown("victoria", t0.plus(Duration.ofMinutes(5))))
        assertEquals(false, snap.statusKnown("jubilee", t0))
        assertEquals(false, snap.statusKnown("", t0))
    }

    @Test
    fun `the next boundary is the earliest of the arrivals' and each check's expiry`() {
        val snap = snapshot(mapOf("victoria" to LineStatusCheck(severe, t0.minusSeconds(90))))
        assertEquals(t0.plusSeconds(210), snap.nextBoundary(t0))
        // Past the check's expiry, the arrivals' boundary is next.
        assertEquals(t0.plus(Duration.ofMinutes(5)), snap.nextBoundary(t0.plusSeconds(210)))
        assertNull(snap.nextBoundary(t0.plus(Duration.ofMinutes(5))))
    }

    @Test
    fun `a no-verdict check's expiry is no boundary, since it's unchecked already`() {
        val snap = snapshot(mapOf("victoria" to LineStatusCheck.noVerdict("victoria", t0.minusSeconds(90))))
        assertEquals(t0.plus(Duration.ofMinutes(5)), snap.nextBoundary(t0))
    }

    @Test
    fun `a good service's expiry after the arrivals go stale is no boundary, a disruption's is`() {
        val goodLater = snapshot(mapOf("victoria" to LineStatusCheck(good, t0.plusSeconds(30))))
        assertEquals(t0.plus(Duration.ofMinutes(5)), goodLater.nextBoundary(t0.plusSeconds(60)))
        assertNull(goodLater.nextBoundary(t0.plus(Duration.ofMinutes(5))))
        // Before the arrivals' boundary it still counts: the countdown's line turns unchecked.
        val goodEarlier = snapshot(mapOf("victoria" to LineStatusCheck(good, t0.minusSeconds(90))))
        assertEquals(t0.plusSeconds(210), goodEarlier.nextBoundary(t0))
        // A disruption's mark lasts past the arrivals, so its expiry always counts.
        val severeLater = snapshot(mapOf("victoria" to LineStatusCheck(severe, t0.plusSeconds(30))))
        assertEquals(t0.plusSeconds(330), severeLater.nextBoundary(t0.plus(Duration.ofMinutes(5))))
    }

    @Test
    fun `a good check's expiry counts only while a stop serving its line is fresh`() {
        // An older carried stop serves Jubilee; the fresher stop serves only Victoria.
        val older = StopArrivals("B", "Stop B", listOf(departure("jubilee")), t0.minusSeconds(200))
        val snap = snapshot(mapOf("jubilee" to LineStatusCheck(good.copy(lineId = "jubilee"), t0.minusSeconds(60))))
            .let { it.copy(stops = it.stops + older) }
        // Jubilee's stop goes stale at +100, before its check expires at +240: no boundary there.
        assertEquals(t0.plusSeconds(300), snap.nextBoundary(t0.plusSeconds(150)))
    }

    @Test
    fun `a good check for a line a stop only declares is no boundary`() {
        val declaredOnly = StopArrivals("B", "Stop B", emptyList(), t0, lines = listOf(LineRef("jubilee", "Jubilee", "tube")))
        val snap = snapshot(mapOf("jubilee" to LineStatusCheck(good.copy(lineId = "jubilee"), t0.minusSeconds(60))))
            .let { it.copy(stops = it.stops + declaredOnly) }
        assertEquals(t0.plusSeconds(300), snap.nextBoundary(t0))
    }

    @Test
    fun `a re-checked line TfL leaves out replaces its old disruption, here and in any later merge`() = runTest {
        val prior = snapshot(mapOf("victoria" to LineStatusCheck(severe, t0)))
        val later = t0.plusSeconds(120)
        val refreshed = WidgetRefresh.refreshedLineStatuses(prior, later) { emptyList() }
        assertTrue(refreshed.liveLineStatuses(later).isEmpty())
        // Merged against the stored copy that still holds the disruption, the newer no-verdict wins.
        val merged = LineStatusCheck.newest(prior.lineStatuses, refreshed.lineStatuses, now = later)
        assertEquals(false, merged.getValue("victoria").known)
    }
}
