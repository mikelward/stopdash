package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The board's rows are built once ([listRowsOf]), a Tube train just past its time kept a moment
 * ([app.stopdash.domain.Countdown.onBoard]), and every board-side reader takes them from there: the
 * list, the journey cards (which judge such a train gone), the "?" rows, a platform view and a route page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class ListRowsGraceTest {
    private val due: Instant = Instant.parse("2026-09-18T08:00:00Z")
    private val stopId = "940GZZLUKSX"

    private fun train(at: Instant) = Departure("victoria", "Victoria", "outbound", "Brixton", "Southbound - Platform 5", at, "tube")

    private fun stop(vararg trains: Departure) = StopArrivals(
        stopId, "King's Cross St. Pancras", trains.toList(), fetchedAt = due,
        lines = listOf(LineRef("victoria", "Victoria", "tube")),
    )

    private fun rows(stop: StopArrivals, now: Instant, last: ListRows? = null) = listRowsOf(
        DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = due, determinedLineIds = setOf("victoria")),
        now,
        stopDistanceMeters = mapOf(stopId to 100.0),
        dismissed = emptySet(),
        hiddenModes = emptySet(),
        alertSequences = emptyMap(),
        journeyDestinationStops = emptyList(),
        last = last,
    )

    @Test
    fun one_build_keeps_a_just_due_train_for_the_list_and_drops_it_for_the_cards() {
        val built = rows(stop(train(due), train(due.plusSeconds(240))), due.plusSeconds(10))
        val listed = built.marked.rows.single { it.lineId == "victoria" }.upcoming
        assertEquals(listOf(true, false), listed.map { it.pastDue })
        // A route page finds the row the list shows, its just-due train in it.
        assertEquals(listOf(true, false), built.all.single { it.lineId == "victoria" }.upcoming.map { it.pastDue })
        // The cards, from the same build, never count on it.
        assertEquals(listOf(due.plusSeconds(240)), built.forCards.single { it.lineId == "victoria" }.upcoming.map { it.expectedArrival })
    }

    @Test
    fun a_line_the_list_shows_a_just_due_train_for_has_no_quiet_candidate_from_the_same_build() {
        val only = stop(train(due))
        assertTrue(rows(only, due.plusSeconds(10)).quietCandidates.orEmpty().none { it.lineId == "victoria" })
        // Once it's gone from the list, from the same build, the line may read "?".
        val after = rows(only, due.plusSeconds(31))
        assertTrue(after.marked.rows.none { it.lineId == "victoria" && it.upcoming.isNotEmpty() })
        assertEquals(listOf("victoria"), after.quietCandidates.orEmpty().map { it.lineId })
        assertEquals(setOf("quiet:$stopId|victoria"), after.quietIds)
    }

    @Test
    fun a_platform_view_shows_the_list_s_own_rows() {
        val built = rows(stop(train(due), train(due.plusSeconds(240))), due.plusSeconds(10))
        val view = platformViewOf(
            built.source, built.now, setOf(stopId), splitKey = "", station = false, stationTitle = "King's Cross St. Pancras",
            starred = emptySet(), dismissed = emptySet(), rows = built.marked.rows, hiddenModes = emptySet(),
            alertSequences = emptyMap(), board = built.board,
        )
        assertEquals(listOf(true, false), view.rows.single { it.lineId == "victoria" }.upcoming.map { it.pastDue })
    }

    @Test
    fun a_route_page_opened_from_a_journey_card_shows_the_card_s_trains_and_no_just_due_one() {
        val built = rows(stop(train(due), train(due.plusSeconds(240))), due.plusSeconds(10))
        // A card's row, as the cards read the build: no just-due train.
        val card = built.forCards.single { it.lineId == "victoria" }
        assertEquals(listOf(false), card.upcoming.map { it.pastDue })
        val shown = shownRowsOf(built, built.marked.rows, listOf(card), starred = emptySet(), stopDistanceMeters = mapOf(stopId to 100.0))
        // The card's page shows the card's trains, the ones it counts on, with no just-due one.
        assertEquals(card.upcoming, shown.cardRowsByKey[card.detailKey()]?.upcoming)
    }

    @Test
    fun the_quiet_revision_moves_on_only_when_the_candidates_change_worked_out_with_the_rows() {
        val only = stop(train(due))
        // Gone from the list: Victoria may read "?".
        val first = rows(only, due.plusSeconds(31))
        // Rebuilt by the screen's clock with the same candidates: the same revision, so nothing's watched again.
        val same = rows(only, due.plusSeconds(41), last = first)
        assertEquals(first.quietIds, same.quietIds)
        assertEquals(first.quietRevision, same.quietRevision)
        // A train comes onto the board: no candidate now, and a new revision.
        val busy = rows(stop(train(due.plusSeconds(300))), due.plusSeconds(51), last = same)
        assertTrue(busy.quietIds.isEmpty())
        assertTrue(busy.quietRevision != same.quietRevision)
    }
}
