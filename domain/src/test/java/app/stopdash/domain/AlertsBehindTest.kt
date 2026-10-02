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
    fun `a verdict at a stop the list no longer shows goes at once, and is never applied there`() {
        // The rider moved on: the list shows other stops, and the store keeps none of the old place.
        val held = mapOf(verdict to now.minus(Duration.ofMinutes(10)))
        assertEquals(emptyMap<AlertBehind, Instant>(), AlertsBehind.recorded(held, AlertPlacement(emptySet(), emptySet(), setOf("b9"), onRows), now))
        // A snapshot without the stop takes no verdict on it, so none reaches its statuses.
        val elsewhere = snapshot(diversion).copy(stops = listOf(stop("b2"))).withAlertsBehind(setOf(verdict))
        assertEquals(emptySet<StopWay>(), elsewhere.lineStatuses.getValue("99").status.behindAt)
    }
}
