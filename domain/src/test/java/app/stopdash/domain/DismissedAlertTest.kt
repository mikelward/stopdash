package app.stopdash.domain

import java.time.Instant
import kotlin.time.toJavaDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [DismissedAlert], [Dismissed], and [stopPlaceKey]: the dismissal identity and toggle rule. */
class DismissedAlertTest {

    private fun closureRow(
        stopId: String,
        stopName: String,
        notice: String,
        hubId: String = "",
        clusterId: String = "",
    ) = DepartureRow(
        stopId = stopId,
        stopName = stopName,
        clusterId = clusterId,
        lineId = "",
        lineName = "",
        direction = "",
        directionKey = STOP_STATUS_DIRECTION_KEY,
        destination = "",
        mode = "",
        upcoming = emptyList(),
        fetchedAt = Instant.EPOCH,
        stopDisruption = notice,
        hubId = hubId,
    )

    @Test
    fun `place key is the hub when there is one`() {
        val row = closureRow("940GZZLUKSX", "King's Cross St. Pancras", "closed", hubId = "HUBKGX", clusterId = "940GZZLUKSX")
        assertEquals("HUBKGX", stopPlaceKey(row))
    }

    @Test
    fun `place key is a real StopArea cluster when there is no hub`() {
        // A real stationNaptan (differs from the display name) — the poles of a junction share it.
        val row = closureRow("490000001E", "Example Road", "closed", clusterId = "490G000EXAMPLE")
        assertEquals("490G000EXAMPLE", stopPlaceKey(row))
    }

    @Test
    fun `place key falls back to the stop id for a name-fallback cluster`() {
        // clusterId == stopName means the cluster is only the display-name fallback (no stationNaptan),
        // which can't distinguish two unrelated same-named stops — so each keys on its own stop id.
        val row = closureRow("490000010A", "Church Road", "closed", clusterId = "Church Road")
        assertEquals("490000010A", stopPlaceKey(row))
    }

    @Test
    fun `the StopArrivals place key matches the row's`() {
        // The overload used to scope reconcile must agree with the row overload on "one place".
        val stop = StopArrivals(
            stopId = "940GZZLUKSX",
            stopName = "King's Cross St. Pancras",
            departures = emptyList(),
            fetchedAt = Instant.EPOCH,
            clusterId = "940GZZLUKSX",
            hubId = "HUBKGX",
        )
        assertEquals("HUBKGX", stopPlaceKey(stop))
    }

    @Test
    fun `ofStopClosure keys on the place and the notice text`() {
        val row = closureRow("A", "Stop A", "Bus Stop Closed", clusterId = "490G000A")
        assertEquals(DismissedAlert("490G000A", "Bus Stop Closed"), DismissedAlert.ofStopClosure(row))
    }

    @Test
    fun `ofLineStatus keys on the line and changes with severity or wording`() {
        val minor = LineStatus("victoria", 9, "Minor Delays", "Victoria line: minor delays.")
        val alert = DismissedAlert.ofLineStatus(minor)
        assertEquals("line:victoria", alert.alertKey)
        // An escalation, a relabel, or new prose is a different alert, so a dismiss never buries it.
        assertNotEquals(alert, DismissedAlert.ofLineStatus(minor.copy(severity = 6)))
        assertNotEquals(alert, DismissedAlert.ofLineStatus(minor.copy(description = "Severe Delays")))
        assertNotEquals(alert, DismissedAlert.ofLineStatus(minor.copy(fullText = "Victoria line: minor delays due to a train fault.")))
        assertEquals(alert, DismissedAlert.ofLineStatus(minor.copy()))
    }

    @Test
    fun `of picks the stop closure, else the line status, else nothing`() {
        val closure = closureRow("A", "Stop A", "Bus Stop Closed", clusterId = "490G000A")
        assertEquals(DismissedAlert.ofStopClosure(closure), DismissedAlert.of(closure))
        val status = LineStatus("victoria", 6, "Severe Delays")
        val lineRow = closure.copy(stopDisruption = null, lineId = "victoria", status = status)
        assertEquals(DismissedAlert.ofLineStatus(status), DismissedAlert.of(lineRow))
        assertNull(DismissedAlert.of(lineRow.copy(status = null)))
    }

    @Test
    fun `reconcile prunes a line dismissal only when that line was checked`() {
        val stale = DismissedAlert.ofLineStatus(LineStatus("victoria", 6, "Severe Delays"))
        assertEquals(emptySet<DismissedAlert>(), Dismissed.reconcile(setOf(stale), emptySet(), setOf(lineAlertKey("victoria"))))
        assertEquals(setOf(stale), Dismissed.reconcile(setOf(stale), emptySet(), setOf(lineAlertKey("central"))))
    }

    @Test
    fun `dismiss adds an alert`() {
        val a = DismissedAlert("P", "S1")
        assertEquals(setOf(a), Dismissed.dismiss(emptySet(), a))
    }

    @Test
    fun `dismiss retains a concurrent notice at the same place`() {
        // Two distinct notices shown at one place (the fold keeps a card for each): dismissing the
        // second must not un-dismiss the first — a dismiss only adds, so both are kept.
        val first = DismissedAlert("P", "S1")
        val second = DismissedAlert("P", "S2")
        assertEquals(setOf(first, second), Dismissed.dismiss(setOf(first), second))
    }

    @Test
    fun `reconcile keeps a dismissal whose notice is still shown`() {
        val a = DismissedAlert("P", "S1")
        assertEquals(setOf(a), Dismissed.reconcile(setOf(a), live = setOf(a), checkedPlaces = setOf("P")))
    }

    @Test
    fun `reconcile drops a dismissal whose notice has resolved at a checked place`() {
        // Both places were checked; P's notice is gone, so its stale dismissal is pruned — a later
        // same-text closure at P is then shown, never suppressed by the resolved incident.
        val gone = DismissedAlert("P", "S1")
        val stillThere = DismissedAlert("Q", "T1")
        assertEquals(
            setOf(stillThere),
            Dismissed.reconcile(setOf(gone, stillThere), live = setOf(stillThere), checkedPlaces = setOf("P", "Q")),
        )
    }

    @Test
    fun `reconcile keeps a dismissal for a place not checked this cycle`() {
        // P wasn't queried this cycle (a different nearby set, or its disruption lookup failed), so
        // its dismissal is retained even though nothing at P is currently shown — persist-until-change.
        val a = DismissedAlert("P", "S1")
        assertEquals(setOf(a), Dismissed.reconcile(setOf(a), live = emptySet(), checkedPlaces = setOf("Q")))
    }

    @Test
    fun `reconcile keeps concurrent notices both still shown`() {
        val first = DismissedAlert("P", "S1")
        val second = DismissedAlert("P", "S2")
        assertEquals(
            setOf(first, second),
            Dismissed.reconcile(setOf(first, second), live = setOf(first, second), checkedPlaces = setOf("P")),
        )
    }

    @Test
    fun `a dropped line dismissal is kept one staleness window from when its end was seen`() {
        val now = java.time.Instant.parse("2026-09-18T08:00:00Z")
        val severe = LineStatus("victoria", 6, "Severe Delays", "Signal failure.")
        val dismissal = DismissedAlert.ofLineStatus(severe)
        val closure = DismissedAlert("HUBKGX", "Station closed")
        // First seen ended: kept, marked ended now. The widget doesn't carry closures, so that goes.
        val first = Dismissed.keepingEnded(setOf(dismissal, closure), emptyMap(), emptySet(), now)
        assertEquals(Dismissed.Kept(setOf(dismissal), mapOf(dismissal to now)), first)
        // A later reconcile keeps its first-seen time, whether or not the alert is live again (an
        // ended one only hides checks made before then, so a recurrence shows regardless).
        val soon = now.plusSeconds(60)
        assertEquals(first, Dismissed.keepingEnded(first.dismissed, first.ended, emptySet(), soon))
        assertEquals(first, Dismissed.keepingEnded(first.dismissed, first.ended, setOf(dismissal), soon))
        // Once the window has passed, every check it could hide is stale: gone.
        val later = now.plus(Staleness.THRESHOLD.toJavaDuration())
        assertEquals(Dismissed.Kept(emptySet(), emptyMap()), Dismissed.keepingEnded(first.dismissed, first.ended, emptySet(), later))
        // An end time after now means the clock went back: it would hide a recurrence, so it goes.
        assertEquals(Dismissed.Kept(emptySet(), emptyMap()), Dismissed.keepingEnded(first.dismissed, first.ended, emptySet(), now.minusSeconds(3_600)))
        // An ordinary dismissal reconcile keeps is left as it is.
        assertEquals(Dismissed.Kept(setOf(dismissal), emptyMap()), Dismissed.keepingEnded(setOf(dismissal), emptyMap(), setOf(dismissal), now))
    }
}
