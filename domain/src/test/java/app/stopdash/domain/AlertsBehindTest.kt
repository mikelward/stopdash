package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app's verdicts that a bus alert lies wholly behind a stop, as the widget and the watch take them
 * (SPEC *Disruptions*): applied by the alert's words where the snapshot is read, only to the line's sole
 * alert, and kept a day from when the app last reached them.
 */
class AlertsBehindTest {
    private val now: Instant = Instant.parse("2026-10-02T08:00:00Z")

    private val diversion = LineStatus(
        "99", 5, "Diversion",
        "Buses are not serving stops between 'Bank Station' and 'Moorgate Station'.",
        soleAlert = true,
    )
    private val verdict = AlertBehind("99", lineAlertFingerprint(diversion), "b4", "inbound")

    private fun stop(stopId: String, direction: String = "inbound") = StopArrivals(
        stopId, "Stop",
        listOf(Departure("99", "99", direction, "North End", null, now.plusSeconds(120), mode = "bus")),
        now,
    )

    private fun snapshot(status: LineStatus) = DeparturesSnapshot(
        stops = listOf(stop("b4"), stop("b2")),
        fetchedAt = now,
        lineStatuses = mapOf("99" to LineStatusCheck(status, now)),
    )

    // The rows a glance surface draws from [snapshot], by stop.
    private fun drawn(snapshot: DeparturesSnapshot) =
        DepartureRows.across(snapshot.stops, now, snapshot.liveLineStatuses(now)).associateBy { it.stopId }

    @Test
    fun `a verdict on the stored alert unflags its stop's row on a glance surface, and no other`() {
        val rows = drawn(snapshot(diversion).withAlertsBehind(setOf(verdict)))
        assertNull(rows.getValue("b4").status)
        assertEquals("Diversion", rows.getValue("b4").statusBehind?.description)
        assertEquals("Diversion", rows.getValue("b2").status?.description)
        // Without the verdicts, as before.
        assertEquals("Diversion", drawn(snapshot(diversion)).getValue("b4").status?.description)
    }

    @Test
    fun `a verdict counts only for the alert it was reached on, while it's the line's sole one`() {
        // Reworded: another alert, which nothing placed.
        val reworded = diversion.copy(fullText = "Buses are diverted away from 'Alpha Road'.")
        assertEquals("Diversion", drawn(snapshot(reworded).withAlertsBehind(setOf(verdict))).getValue("b4").status?.description)
        // Another alert under way since: the one shown no longer speaks for the line's.
        val notSole = diversion.copy(soleAlert = false)
        assertEquals("Diversion", drawn(snapshot(notSole).withAlertsBehind(setOf(verdict))).getValue("b4").status?.description)
        // Another line's verdict on the same words places nothing here.
        assertEquals(
            "Diversion",
            drawn(snapshot(diversion).withAlertsBehind(setOf(verdict.copy(lineId = "100")))).getValue("b4").status?.description,
        )
    }

    @Test
    fun `a direction's alert takes the verdicts on its own words`() {
        val inbound = diversion
        val split = LineStatus("99", 5, "Diversion", diversion.fullText, soleAlert = true, byDirection = mapOf("inbound" to inbound))
        val rows = drawn(snapshot(split).withAlertsBehind(setOf(verdict)))
        assertNull(rows.getValue("b4").status)
        // A row going the other way reads the line-wide status, which no verdict names for it.
        val outbound = snapshot(split).copy(stops = listOf(stop("b4", "outbound"))).withAlertsBehind(setOf(verdict))
        assertEquals("Diversion", drawn(outbound).getValue("b4").status?.description)
    }

    @Test
    fun `planned work starting today is an alert no verdict was reached on`() {
        val today = now.atZone(AlertStart.ZONE).toLocalDate()
        val withPlanned = diversion.copy(planned = listOf(PlannedAlert("Part Closure", "Closed between A and B.", today, severity = 3)))
        val rows = drawn(snapshot(withPlanned).withAlertsBehind(setOf(verdict.copy(fingerprint = lineAlertFingerprint(withPlanned)))))
        // The work under way now is flagged, whatever the verdict said of the alert shown before it.
        assertNotNull(rows.getValue("b4").status)
        assertNull(rows.getValue("b4").statusBehind)
    }

    @Test
    fun `planned work that is all that's under way once its day comes takes the verdict reached on its words`() {
        val today = now.atZone(AlertStart.ZONE).toLocalDate()
        val work = PlannedAlert("Diversion", diversion.fullText!!, today, severity = 5)
        val plannedOnly = LineStatus("99", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(work))
        // The app reaches its verdict on the work as the list shows it today: these words.
        val route = LineSequence(
            listOf(LineRoute("Bank - North End", listOf("b1", "b2", "b3", "b4", "b5"), "inbound")),
            mapOf("b1" to "Bank", "b2" to "Example Street", "b3" to "Moorgate", "b4" to "Alpha Road", "b5" to "North End"),
        )
        val reached = AlertsBehind.placement(listOf(stop("b4"), stop("b2")), mapOf("99" to plannedOnly), mapOf("99" to route), now).behind
        val onWork = AlertBehind("99", plannedShownFingerprint(work), "b4", "inbound")
        assertEquals(setOf(onWork), reached)
        // So a glance surface reading the stored check, the work still to come in it, places it there.
        val rows = drawn(snapshot(plannedOnly).withAlertsBehind(setOf(onWork)))
        assertNull(rows.getValue("b4").status)
        assertEquals("Diversion", rows.getValue("b4").statusBehind?.description)
        assertEquals("Diversion", rows.getValue("b2").status?.description)
        // Not yet due: nothing to flag or place, the verdict notwithstanding.
        val tomorrow = plannedOnly.copy(planned = listOf(work.copy(startsOn = today.plusDays(1))))
        assertNull(drawn(snapshot(tomorrow).withAlertsBehind(setOf(onWork))).getValue("b4").statusBehind)
    }

    @Test
    fun `work starting today beside other work takes no verdict, whatever each was found`() {
        val today = now.atZone(AlertStart.ZONE).toLocalDate()
        val work = PlannedAlert("Diversion", diversion.fullText!!, today, severity = 5)
        val other = PlannedAlert("Diversion", "Buses are diverted away from 'Alpha Road'.", today, severity = 5)
        val both = LineStatus("99", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(work, other))
        val verdicts = listOf(work, other).mapTo(HashSet()) { AlertBehind("99", plannedShownFingerprint(it), "b4", "inbound") }
        val rows = drawn(snapshot(both).withAlertsBehind(verdicts))
        assertEquals("Diversion", rows.getValue("b4").status?.description)
        assertNull(rows.getValue("b4").statusBehind)
        // As stored, their prose left out, the two read alike but for the words each shows: still none.
        fun stored(alert: PlannedAlert) = alert.copy(fullText = "", shownFingerprint = plannedShownFingerprint(alert))
        val asStored = both.copy(planned = listOf(stored(work), stored(other)))
        val storedRows = drawn(snapshot(asStored).withAlertsBehind(verdicts))
        assertEquals("Diversion", storedRows.getValue("b4").status?.description)
        assertNull(storedRows.getValue("b4").statusBehind)
        // Nor beside an alert already under way, even one in the same words: it was placed as itself.
        val onTop = diversion.copy(description = "Diverted", planned = listOf(work))
        val onTopRows = drawn(snapshot(onTop).withAlertsBehind(setOf(AlertBehind("99", plannedShownFingerprint(work), "b4", "inbound"))))
        assertNotNull(onTopRows.getValue("b4").status)
        assertNull(onTopRows.getValue("b4").statusBehind)
    }

    // The diversion still on the list's rows.
    private val onRows = setOf("99" to lineAlertFingerprint(diversion))

    // The list at b4 and b5, the diversion on its rows, these verdicts reached there.
    private fun reached(vararg verdicts: AlertBehind) = AlertPlacement(verdicts.toSet(), verdicts.toSet(), setOf("b4", "b5"), onRows)

    @Test
    fun `a verdict stands a day from when it was last reached, and is restamped hourly at most`() {
        val held = mapOf(verdict to now.minus(Duration.ofMinutes(10)))
        // Reached again within the hour: nothing to write.
        assertNull(AlertsBehind.recorded(held, reached(verdict), now))
        // Past the hour: stamped again.
        val later = now.plus(Duration.ofMinutes(50))
        assertEquals(mapOf(verdict to later), AlertsBehind.recorded(held, reached(verdict), later))
        // A new one is added beside it, and one the app didn't weigh this time stays.
        val other = verdict.copy(stopId = "b5")
        assertEquals(held + (other to now), AlertsBehind.recorded(held, reached(other), now))
        // Not reached for a day: gone, from the store and from what stands.
        val dayOld = now.minus(AlertsBehind.MAX_AGE)
        assertEquals(emptySet<AlertBehind>(), AlertsBehind.standing(mapOf(verdict to dayOld), now))
        assertEquals(emptyMap<AlertBehind, Instant>(), AlertsBehind.recorded(mapOf(verdict to dayOld), reached(), now))
        // Stamped ahead of the clock (it went back): not trusted.
        assertEquals(emptySet<AlertBehind>(), AlertsBehind.standing(mapOf(verdict to now.plusSeconds(60)), now))
    }

    @Test
    fun `a verdict the app weighs again and no longer finds behind the stop goes at once`() {
        // The route was refreshed, and the alert is on the way from the stop after all.
        val held = mapOf(verdict to now.minus(Duration.ofMinutes(10)))
        assertEquals(emptyMap<AlertBehind, Instant>(), AlertsBehind.recorded(held, AlertPlacement(emptySet(), setOf(verdict), setOf("b4"), onRows), now))
        // Weighing another stop leaves it alone.
        val elsewhere = verdict.copy(stopId = "b2")
        assertNull(AlertsBehind.recorded(held, AlertPlacement(emptySet(), setOf(elsewhere), setOf("b4", "b2"), onRows), now))
    }

    @Test
    fun `a verdict on an alert no longer on the list's rows goes at once`() {
        // TfL cleared it: the same words back later aren't taken as placed until the app places them again.
        val held = mapOf(verdict to now.minus(Duration.ofMinutes(10)))
        assertEquals(emptyMap<AlertBehind, Instant>(), AlertsBehind.recorded(held, AlertPlacement(emptySet(), emptySet(), setOf("b4")), now))
        // Still on the rows, though not weighed (its route not loaded yet): it stays.
        assertNull(AlertsBehind.recorded(held, AlertPlacement(emptySet(), emptySet(), setOf("b4"), onRows), now))
    }

    @Test
    fun `a placement that checked only some lines leaves the others' verdicts alone`() {
        // The widget's refresh asked TfL about another line only: this one's alert isn't known gone.
        val held = mapOf(verdict to now.minus(Duration.ofMinutes(10)))
        assertNull(AlertsBehind.recorded(held, AlertPlacement(emptySet(), emptySet(), setOf("b4"), lines = setOf("100")), now))
        // Asked about this line and its alert gone: dropped, as from the list.
        assertEquals(emptyMap<AlertBehind, Instant>(), AlertsBehind.recorded(held, AlertPlacement(emptySet(), emptySet(), setOf("b4"), lines = setOf("99")), now))
    }

    @Test
    fun `a placement speaking only for its stops leaves verdicts elsewhere, and adds none outside them`() {
        // The widget's refresh, at b4 alone: a verdict at b9 is the app's to keep or drop.
        val held = mapOf(verdict.copy(stopId = "b9") to now.minus(Duration.ofMinutes(10)))
        val atB4 = AlertPlacement(setOf(verdict), setOf(verdict), setOf("b4"), onRows, everyStop = false)
        assertEquals(held + (verdict to now), AlertsBehind.recorded(held, atB4, now))
        // A verdict reached at a stop the placement doesn't speak for isn't added.
        val outside = AlertPlacement(setOf(verdict), setOf(verdict), setOf("b2"), onRows, everyStop = false)
        assertNull(AlertsBehind.recorded(held, outside, now))
        // Nor one reached on a line it doesn't speak for, and that line's held verdict, though weighed
        // and not found behind, is left too.
        val otherLine = AlertPlacement(setOf(verdict), setOf(verdict), setOf("b4"), onRows, lines = setOf("100"))
        assertNull(AlertsBehind.recorded(emptyMap(), otherLine, now))
        val disproved = AlertPlacement(emptySet(), setOf(verdict), setOf("b4"), onRows, lines = setOf("100"))
        assertNull(AlertsBehind.recorded(mapOf(verdict to now), disproved, now))
    }

    @Test
    fun `a verdict at a stop the list no longer shows goes at once, and is never applied there`() {
        // The rider moved on: the list shows other stops, and the store keeps none of the old place.
        val held = mapOf(verdict to now.minus(Duration.ofMinutes(10)))
        assertEquals(emptyMap<AlertBehind, Instant>(), AlertsBehind.recorded(held, AlertPlacement(emptySet(), emptySet(), setOf("b9"), onRows), now))
        // A snapshot without the stop takes no verdict on it, so none reaches its statuses.
        val elsewhere = snapshot(diversion).copy(stops = listOf(stop("b2"))).withAlertsBehind(setOf(verdict))
        assertEquals(emptySet<StopWay>(), elsewhere.lineStatuses.getValue("99").status.behindAt)
    }
}
