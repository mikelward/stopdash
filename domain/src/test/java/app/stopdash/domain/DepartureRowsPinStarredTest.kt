package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DepartureRows.pinStarred]: starred services lift to the top, warnings still lead, and the
 * soonest-first order carries through within each band.
 */
class DepartureRowsPinStarredTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun timed(stopId: String, lineId: String, directionKey: String, offset: Long) =
        DepartureRow(
            stopId = stopId,
            stopName = "Stop $stopId",
            lineId = lineId,
            lineName = lineId,
            direction = directionKey,
            directionKey = directionKey,
            destination = "Somewhere",
            mode = "tube",
            upcoming = listOf(
                Departure(lineId, lineId, directionKey, "Somewhere", null, now.plusSeconds(offset), "tube"),
            ),
            fetchedAt = now,
        )

    private fun statusRow(stopId: String, lineId: String) = DepartureRow(
        stopId = stopId,
        stopName = "Stop $stopId",
        lineId = lineId,
        lineName = lineId,
        direction = "",
        directionKey = STATUS_DIRECTION_KEY,
        destination = "",
        mode = "tube",
        upcoming = emptyList(),
        fetchedAt = now,
        status = LineStatus(lineId, severity = 6, description = "Suspended"),
    )

    private fun closureRow(stopId: String) = DepartureRow(
        stopId = stopId,
        stopName = "Stop $stopId",
        lineId = "",
        lineName = "",
        direction = "",
        directionKey = STOP_STATUS_DIRECTION_KEY,
        destination = "",
        mode = "",
        upcoming = emptyList(),
        fetchedAt = now,
        stopDisruption = "Station closed",
    )

    @Test
    fun `an empty starred set leaves the list unchanged`() {
        val rows = listOf(timed("A", "central", "e", 60), timed("B", "victoria", "s", 120))
        assertEquals(rows, DepartureRows.pinStarred(rows, emptySet()))
    }

    @Test
    fun `a starred row lifts above the unstarred ones`() {
        val central = timed("A", "central", "e", 60)
        val victoria = timed("B", "victoria", "s", 120)
        val district = timed("C", "district", "w", 180)
        val rows = listOf(central, victoria, district)
        val pinned = DepartureRows.pinStarred(rows, setOf(StarredRow.of(district)))
        assertEquals(listOf(district, central, victoria), pinned)
    }

    @Test
    fun `soonest-first order is preserved within the starred and unstarred bands`() {
        val a = timed("A", "central", "e", 60)
        val b = timed("B", "victoria", "s", 120)
        val c = timed("C", "district", "w", 180)
        val d = timed("D", "jubilee", "n", 240)
        val rows = listOf(a, b, c, d)
        val pinned = DepartureRows.pinStarred(rows, setOf(StarredRow.of(b), StarredRow.of(d)))
        // Starred keep their relative order (b before d); unstarred keep theirs (a before c).
        assertEquals(listOf(b, d, a, c), pinned)
    }

    @Test
    fun `a stop closure stays above a starred service`() {
        val closure = closureRow("A")
        val starredTimed = timed("A", "central", "e", 60)
        val rows = listOf(closure, starredTimed)
        val pinned = DepartureRows.pinStarred(rows, setOf(StarredRow.of(starredTimed)))
        assertEquals(listOf(closure, starredTimed), pinned)
    }

    @Test
    fun `a no-departure status row stays above a starred service`() {
        val suspended = statusRow("A", "victoria")
        val starredTimed = timed("A", "central", "e", 60)
        val rows = listOf(suspended, starredTimed)
        val pinned = DepartureRows.pinStarred(rows, setOf(StarredRow.of(starredTimed)))
        assertEquals(listOf(suspended, starredTimed), pinned)
    }

    @Test
    fun `a starred service that is now a status row stays in the warning band`() {
        // A starred line that went suspended returns no predictions — its row is now a
        // warning, so it leads with the other warnings rather than jumping the pinned band.
        val suspendedStarred = statusRow("A", "victoria")
        val timedUnstarred = timed("B", "central", "e", 60)
        val rows = listOf(timedUnstarred, suspendedStarred)
        // The star key still matches (stop A, victoria, status sentinel).
        val pinned = DepartureRows.pinStarred(rows, setOf(StarredRow.of(suspendedStarred)))
        assertEquals(listOf(suspendedStarred, timedUnstarred), pinned)
    }

    @Test
    fun `with warningsLead false an unstarred alert is not hoisted above a starred service`() {
        // On the near-me list the caller passes warningsLead=false: an unstarred suspended line stays
        // where the distance sort left it (last here) rather than jumping the starred service, so a
        // stop isn't hoisted merely for carrying an alert (maintainer, 2026-09-22).
        val starredTimed = timed("A", "central", "e", 60)
        val unstarredAlert = statusRow("B", "victoria")
        val rows = listOf(starredTimed, unstarredAlert)
        val pinned = DepartureRows.pinStarred(rows, setOf(StarredRow.of(starredTimed)), warningsLead = false)
        assertEquals(listOf(starredTimed, unstarredAlert), pinned)
    }

    @Test
    fun `with warningsLead false a starred alert still lifts with the starred band`() {
        // The star still wins: a starred service that is currently an alert lifts with the starred
        // band even when warnings no longer form their own leading band.
        val starredAlert = statusRow("A", "victoria")
        val unstarredTimed = timed("B", "central", "e", 60)
        val rows = listOf(unstarredTimed, starredAlert)
        val pinned = DepartureRows.pinStarred(rows, setOf(StarredRow.of(starredAlert)), warningsLead = false)
        assertEquals(listOf(starredAlert, unstarredTimed), pinned)
    }

    @Test
    fun `a quiet row isn't a warning, so it doesn't lead the starred band`() {
        val quiet = statusRow("A", "victoria").copy(quiet = true)
        val starredTimed = timed("B", "central", "e", 60)
        val pinned = DepartureRows.pinStarred(listOf(quiet, starredTimed), setOf(StarredRow.of(starredTimed)))
        assertEquals(listOf(starredTimed, quiet), pinned)
    }
}
