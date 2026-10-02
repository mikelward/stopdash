package app.stopdash.widget

import app.stopdash.domain.AlertBehind
import app.stopdash.domain.AlertPlacement
import app.stopdash.domain.AlertsBehind
import app.stopdash.domain.AlertsBehindStore
import app.stopdash.domain.Departure
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.lineAlertFingerprint
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The widget's refresh placing the alerts it just fetched (SPEC *Disruptions*): with the routes the app
 * already holds and no request, at the stops stored, speaking only for the stops still stored and the
 * lines whose stored check is still its answer when it keeps what it found.
 */
class WidgetPlacesAlertsTest {
    private val now: Instant = Instant.parse("2026-10-02T08:00:00Z")

    // A made-up bus route north from Bank, and an alert on a stretch at its south end.
    private val route = LineSequence(
        listOf(LineRoute("Bank - North End", listOf("b1", "b2", "b3", "b4", "b5"), "inbound")),
        mapOf("b1" to "Bank", "b2" to "Example Street", "b3" to "Moorgate", "b4" to "Alpha Road", "b5" to "North End"),
    )
    private val diversion = LineStatus(
        "99", 5, "Diversion", "Buses are not serving stops between 'Bank Station' and 'Moorgate Station'.", soleAlert = true,
    )
    private val fingerprint = lineAlertFingerprint(diversion)

    private fun stop(id: String) = StopArrivals(
        id, "Stop", listOf(Departure("99", "99", "inbound", "North End", null, now.plusSeconds(120), mode = "bus")), now,
    )

    // What the widget has stored: [ids]'s stops, with [checks] by line (this refresh's answer, by default).
    private fun stored(vararg ids: String, checks: Map<String, LineStatusCheck> = mapOf("99" to LineStatusCheck(diversion, now))) =
        DeparturesSnapshot(ids.map { stop(it) }, now, lineStatuses = checks)

    private class Recording : AlertsBehindStore {
        var placed: AlertPlacement? = null
        override fun verdicts(): Flow<Set<AlertBehind>> = flowOf(emptySet())
        override suspend fun record(placement: AlertPlacement) {
            placed = placement
        }
    }

    @Test
    fun `an alert fetched while the app is closed is placed with the route the app holds`() = runTest {
        val store = Recording()
        val asked = mutableListOf<String>()
        placeWidgetAlerts({ stored("b4", "b2") }, listOf(diversion), setOf("99"), sequenceOf = { asked += it; route }, store = store, now = now)
        val placed = store.placed!!
        assertEquals(setOf(AlertBehind("99", fingerprint, "b4", "inbound")), placed.behind)
        assertEquals(setOf("b4", "b2"), placed.stops)
        // It speaks only for those stops, not for every stop the list shows.
        assertEquals(false, placed.everyStop)
        // Only the lines it asked TfL about count as checked.
        assertEquals(setOf("99"), placed.lines)
        assertEquals(listOf("99"), asked)
    }

    @Test
    fun `with no route held nothing is placed, though the alerts are still seen`() = runTest {
        val store = Recording()
        placeWidgetAlerts({ stored("b4") }, listOf(diversion), setOf("99"), sequenceOf = { null }, store = store, now = now)
        assertEquals(emptySet<AlertBehind>(), store.placed!!.behind)
        assertEquals(setOf("99" to fingerprint), store.placed!!.alerts)
    }

    @Test
    fun `a line TfL left out of its answer was checked too, so its verdict goes`() = runTest {
        // Asked about 99 and 100, answered for 100 alone: 99 got a no-verdict check, its alert gone.
        val other = LineStatus("100", 10, "Good Service", "")
        val checks = mapOf("99" to LineStatusCheck.noVerdict("99", now), "100" to LineStatusCheck(other, now))
        val store = Recording()
        placeWidgetAlerts({ stored("b4", checks = checks) }, listOf(other), setOf("99", "100"), sequenceOf = { route }, store = store, now = now)
        assertEquals(setOf("99", "100"), store.placed!!.lines)
        // TfL knowing none of them (an empty answer) is a check of each as well.
        val empty = Recording()
        val none = mapOf("99" to LineStatusCheck.noVerdict("99", now))
        placeWidgetAlerts({ stored("b4", checks = none) }, emptyList(), setOf("99"), sequenceOf = { route }, store = empty, now = now)
        assertEquals(setOf("99"), empty.placed!!.lines)
        assertEquals(emptySet<Pair<String, String>>(), empty.placed!!.alerts)
        // A verdict held on 99 then goes, where it would stay were only the returned lines checked.
        val held = AlertBehind("99", fingerprint, "b4", "inbound")
        assertEquals(emptyMap<AlertBehind, Instant>(), AlertsBehind.recorded(mapOf(held to now), empty.placed!!, now))
    }

    @Test
    fun `a move to new stops while it places leaves their verdicts alone`() = runTest {
        // Stored when it starts: b4 and b2. Stored by the time it keeps what it found: b4 and b5, the app
        // having moved the list on while the routes were read.
        val reads = ArrayDeque(listOf(stored("b4", "b2"), stored("b4", "b5")))
        val store = Recording()
        placeWidgetAlerts({ reads.removeFirst() }, listOf(diversion), setOf("99"), sequenceOf = { route }, store = store, now = now)
        val placed = store.placed!!
        // It speaks for b4 alone, still shown and placed.
        assertEquals(setOf("b4"), placed.stops)
        // So a verdict the app reached at the new stop stays, and one at the stop left is the app's to drop.
        val atNew = AlertBehind("99", fingerprint, "b5", "inbound")
        val atLeft = AlertBehind("99", fingerprint, "b2", "inbound")
        val kept = AlertsBehind.recorded(mapOf(atNew to now, atLeft to now), placed, now)!!
        assertEquals(setOf(atNew, atLeft, AlertBehind("99", fingerprint, "b4", "inbound")), kept.keys)
    }

    @Test
    fun `a newer check the app stored while it places leaves that line to the app`() = runTest {
        // The app stored a newer check of 99 meanwhile: the diversion has cleared.
        val cleared = mapOf("99" to LineStatusCheck(LineStatus("99", 10, "Good Service", ""), now.plusSeconds(30)))
        val reads = ArrayDeque(listOf(stored("b4"), stored("b4", checks = cleared)))
        val store = Recording()
        placeWidgetAlerts({ reads.removeFirst() }, listOf(diversion), setOf("99"), sequenceOf = { route }, store = store, now = now)
        val placed = store.placed!!
        assertEquals(emptySet<String>(), placed.lines)
        // So the stale answer neither restores the verdict the app dropped nor touches one it holds.
        assertNull(AlertsBehind.recorded(emptyMap(), placed, now))
        val held = AlertBehind("99", "other", "b4", "inbound")
        assertNull(AlertsBehind.recorded(mapOf(held to now), placed, now))
        // A newer check of the same alert is still this answer: the line is spoken for.
        val same = mapOf("99" to LineStatusCheck(diversion, now.plusSeconds(30)))
        val sameReads = ArrayDeque(listOf(stored("b4"), stored("b4", checks = same)))
        val again = Recording()
        placeWidgetAlerts({ sameReads.removeFirst() }, listOf(diversion), setOf("99"), sequenceOf = { route }, store = again, now = now)
        assertEquals(setOf("99"), again.placed!!.lines)
    }

    @Test
    fun `none stored, before or after it places, records nothing`() = runTest {
        val none = Recording()
        placeWidgetAlerts({ null }, listOf(diversion), setOf("99"), sequenceOf = { route }, store = none, now = now)
        assertNull(none.placed)
        val reads = ArrayDeque(listOf(stored("b4"), null))
        val gone = Recording()
        placeWidgetAlerts({ reads.removeFirst() }, listOf(diversion), setOf("99"), sequenceOf = { route }, store = gone, now = now)
        assertNull(gone.placed)
    }

    @Test
    fun `no line answered places nothing, and a failure to keep it doesn't fail the refresh`() = runTest {
        val store = Recording()
        placeWidgetAlerts({ stored("b4") }, emptyList(), emptySet(), sequenceOf = { route }, store = store, now = now)
        assertNull(store.placed)
        val failing = object : AlertsBehindStore {
            override fun verdicts(): Flow<Set<AlertBehind>> = flowOf(emptySet())
            override suspend fun record(placement: AlertPlacement) = throw java.io.IOException("disk full")
        }
        placeWidgetAlerts({ stored("b4") }, listOf(diversion), setOf("99"), sequenceOf = { route }, store = failing, now = now)
        // Nor does a failure reading the stored snapshot.
        placeWidgetAlerts({ throw java.io.IOException("unreadable") }, listOf(diversion), setOf("99"), sequenceOf = { route }, store = store, now = now)
        assertNull(store.placed)
    }
}
