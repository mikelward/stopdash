package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A ride's stops under its row on the way ([rideStopList]): which stop is the rider's. Well-known stations. */
class RideStopListTest {
    private val now = Instant.parse("2026-09-26T07:02:00Z")
    private val victoria = TripLeg(
        "tube", "victoria", "Victoria", "940GZZLUVIC", "Victoria", "940GZZLUKSX", "King's Cross St. Pancras", now, now.plusSeconds(600),
        path = listOf("940GZZLUGPK", "940GZZLUOXC", "940GZZLUWRR", "940GZZLUEUS", "940GZZLUKSX"),
        pathNames = listOf("Green Park", "Oxford Circus", "Warren Street", "Euston", ""),
    )

    @Test
    fun lists_the_boarding_stop_then_each_call_through_where_it_gets_off() {
        val stops = rideStopList(victoria, null, boarded = false)!!
        // A call the Planner left unnamed at the end takes the leg's own name for it.
        assertEquals(listOf("Victoria", "Green Park", "Oxford Circus", "Warren Street", "Euston", "King's Cross St. Pancras"), stops.names)
        assertEquals(0, stops.here)
    }

    @Test
    fun on_board_the_rider_is_at_the_stop_before_the_next() {
        val riding = TripProgress.Riding(victoria, "Warren Street", 3, null, getOffSoon = false)
        assertEquals(2, rideStopList(victoria, riding, boarded = true)!!.here)
        // At the last stop's approach: the one before it.
        assertEquals(4, rideStopList(victoria, TripProgress.Riding(victoria, "King's Cross St. Pancras", 1, null, false), boarded = true)!!.here)
    }

    @Test
    fun uncounted_stops_go_by_the_next_stop_s_name() {
        val riding = TripProgress.Riding(victoria, "Euston", null, null, getOffSoon = false)
        assertEquals(3, rideStopList(victoria, riding, boarded = true)!!.here)
    }

    @Test
    fun a_train_the_trip_cant_place_marks_no_stop() {
        assertNull(rideStopList(victoria, TripProgress.Riding(victoria, null, null, null, false), boarded = true)!!.here)
        assertNull(rideStopList(victoria, TripProgress.Lost(victoria), boarded = true)!!.here)
        // A stop ahead last known, but this answer's calls didn't place the train: where it is now isn't known (Codex, #968).
        assertNull(rideStopList(victoria, TripProgress.Lost(victoria, ahead = 1, placed = false), boarded = true)!!.here)
        assertEquals(1, rideStopList(victoria, TripProgress.Lost(victoria, ahead = 1, placed = true), boarded = true)!!.here)
        // Lost before boarding: still where it boards.
        assertEquals(0, rideStopList(victoria, TripProgress.Lost(victoria, ahead = 1, placed = true), boarded = false)!!.here)
    }

    @Test
    fun a_path_ending_at_an_unmatched_stop_area_still_names_where_it_gets_off() {
        // A bus arrival with no stop area of its own: the Planner's unnamed path ends at its area, which matches
        // neither end id, yet it's still where the ride gets off (Codex, #968). Hub names, synthetic ids.
        val bus = TripLeg(
            "bus", "example", "73", "490EXAMPLE1", "Euston", "490EXAMPLE2", "King's Cross St. Pancras", now, now.plusSeconds(600),
            path = listOf("490GEXAMPLE3", "490GEXAMPLE4"), pathNames = listOf("", ""),
        )
        assertEquals(listOf("Euston", "King's Cross St. Pancras"), rideStopList(bus, null, boarded = false)!!.names)
    }

    @Test
    fun what_marks_no_stop_without_the_path_agrees_with_the_list() {
        // The quick check a held list drops its marker by (Codex, #968) never says "no stop" where the list marks one.
        val cases = listOf(
            TripProgress.Riding(victoria, "Warren Street", 3, now, getOffSoon = false),
            TripProgress.Riding(victoria, null, null, null, false),
            TripProgress.Riding(victoria, "Warren Street", null, now, getOffSoon = false),
            TripProgress.Lost(victoria),
            TripProgress.Lost(victoria, ahead = 1, placed = false),
            TripProgress.Lost(victoria, ahead = 1, placed = true),
            null,
        )
        for (progress in cases) for (boarded in listOf(true, false)) for (stale in listOf(true, false)) {
            val here = rideStopList(victoria, progress, boarded, stale = stale)!!.here
            if (marksNoStop(progress, boarded, stale)) assertNull("$progress boarded=$boarded stale=$stale", here)
        }
        assertTrue(marksNoStop(TripProgress.Lost(victoria, ahead = 1, placed = false), boarded = true, stale = false))
        assertFalse(marksNoStop(TripProgress.Lost(victoria, ahead = 1, placed = true), boarded = true, stale = false))
    }

    @Test
    fun a_lost_train_on_another_branch_marks_no_stop() {
        // Followed on a shorter branch between the same two stops: the lost train's stop ahead isn't counted along it,
        // so no stop is marked rather than one stops off (Codex, #968).
        val other = victoria.copy(path = listOf("940GZZLUTCR", "940GZZLUKSX"), pathNames = listOf("Tottenham Court Road", ""))
        assertNull(rideStopList(other, TripProgress.Lost(victoria, ahead = 4, placed = true), boarded = true)!!.here)
        // The planned stops, though in another copy of the leg, still mark it.
        val copy = victoria.copy(path = victoria.path.toList())
        assertEquals(4, rideStopList(copy, TripProgress.Lost(victoria, ahead = 4, placed = true), boarded = true)!!.here)
    }

    @Test
    fun a_train_the_rider_isnt_seen_on_leaves_them_where_it_boards() {
        // Its train has left, but nothing says they're on it: the step still says to take it (Codex, #968).
        val unseen = TripProgress.Riding(victoria, "Warren Street", 3, null, getOffSoon = false, seen = false)
        assertEquals(0, rideStopList(victoria, unseen, boarded = false)!!.here)
    }

    @Test
    fun a_ride_still_to_come_marks_where_it_boards() {
        assertEquals(0, rideStopList(victoria, null, boarded = false)!!.here)
    }

    @Test
    fun stops_the_planner_left_unnamed_take_the_line_s_names_else_are_left_out() {
        // A bus's path often comes with ids alone (Codex, #968): never shown as codes.
        val unnamed = victoria.copy(pathNames = listOf("", "", "Warren Street", "", ""))
        val stops = rideStopList(unnamed, null, boarded = false, lineNames = mapOf("940GZZLUGPK" to "Green Park"))!!
        assertEquals(listOf("Victoria", "Green Park", "Warren Street", "King's Cross St. Pancras"), stops.names)
        assertTrue(stops.missing)
        // Named in full by the Planner: nothing to look up (Codex, #968).
        assertFalse(rideStopList(victoria, null, boarded = false)!!.missing)
        // On board past Oxford Circus, which has no name: the dot on the last named stop before it.
        val riding = TripProgress.Riding(unnamed, "Warren Street", 3, null, getOffSoon = false)
        assertEquals(1, rideStopList(unnamed, riding, boarded = true, lineNames = mapOf("940GZZLUGPK" to "Green Park"))!!.here)
    }

    @Test
    fun a_next_stop_named_twice_on_a_loop_marks_no_stop() {
        val loop = victoria.copy(pathNames = listOf("Green Park", "Oxford Circus", "Green Park", "Euston", ""))
        assertNull(rideStopList(loop, TripProgress.Riding(loop, "Green Park", null, null, false), boarded = true)!!.here)
    }

    @Test
    fun a_line_s_pole_names_stand_for_their_stop_areas() {
        // A bus path names its stops by area (490G…), the route by pole (Codex, #968). Synthetic ids, public names.
        val sequence = LineSequence(
            routes = emptyList(),
            stopNames = mapOf("490000001A" to "Archway", "490000001B" to "Archway", "490000002N" to "Highgate Hill"),
            stopAreas = mapOf("490000001A" to "490G0000001", "490000001B" to "490G0000001", "490000002N" to "490G0000002"),
        )
        val names = lineStopNames(sequence)
        assertEquals("Archway", names["490G0000001"])
        assertEquals("Highgate Hill", names["490G0000002"])
        assertEquals("Highgate Hill", names["490000002N"])
    }

    @Test
    fun a_ride_the_planner_listed_no_stops_for_takes_them_from_its_line_through_where_it_gets_off() {
        // A third of the recorded plans' rides come with no path (Codex, #968). Public names, synthetic ids.
        val ids = listOf("940GZZLUVIC", "940GZZLUGPK", "940GZZLUOXC", "940GZZLUWRR", "940GZZLUEUS", "940GZZLUKSX", "940GZZLUWLO")
        val names = listOf("Victoria", "Green Park", "Oxford Circus", "Warren Street", "Euston", "King's Cross St. Pancras", "Walthamstow Central")
        val sequence = LineSequence(
            routes = listOf(LineRoute("Brixton - Walthamstow Central", ids)),
            stopNames = ids.zip(names).toMap(),
        )
        val bare = victoria.copy(path = emptyList(), pathNames = emptyList(), headings = listOf("Walthamstow Central"))
        val ride = withRouteStops(bare, LegEnd.planned(bare, StepFree.ANY), sequence)
        assertEquals(ids.subList(1, 6), ride.path)
        assertEquals(listOf("Victoria", "Green Park", "Oxford Circus", "Warren Street", "Euston", "King's Cross St. Pancras"), rideStopList(ride, null, boarded = false)!!.names)
        // Where the route can't place it, unchanged: no list rather than a guess.
        assertEquals(bare, withRouteStops(bare, LegEnd.planned(bare, StepFree.ANY), sequence.copy(routes = emptyList())))
    }

    @Test
    fun a_ride_with_no_stops_gets_off_at_its_station_under_another_of_its_ids() {
        // The leg names St Pancras by its main id, the route calls at its low level (Codex, #968). Synthetic ids.
        val ids = listOf("910GBLFR", "910GFRNDNLT", "910GSTPXBOX", "910GKNTSHTN")
        val sequence = LineSequence(
            routes = listOf(LineRoute("Thameslink", ids)),
            stopNames = mapOf("910GBLFR" to "Blackfriars", "910GFRNDNLT" to "Farringdon", "910GSTPXBOX" to "St Pancras International LL", "910GKNTSHTN" to "Kentish Town"),
            stationCodes = mapOf("910GSTPXBOX" to "1000STP", "910GSTPANCR" to "1000STP"),
        )
        val leg = TripLeg(
            "national-rail", "thameslink", "Thameslink", "910GBLFR", "Blackfriars", "910GSTPANCR", "St Pancras International", now, now.plusSeconds(600),
            headings = listOf("Kentish Town"),
        )
        assertEquals(listOf("910GFRNDNLT", "910GSTPXBOX"), withRouteStops(leg, LegEnd.planned(leg, StepFree.ANY), sequence).path)
    }

    @Test
    fun a_ride_with_no_stops_gets_off_at_the_stand_its_route_places_it_at() {
        // A bus station's stand the Planner named under another spelling than the route's, matched by name only
        // where the route places the leg ([onPoles]; Codex, #968). Synthetic ids, hub names.
        val ids = listOf("490EXAMPLE1", "490EXAMPLE2", "490EXAMPLE3")
        val sequence = LineSequence(
            routes = listOf(LineRoute("North", ids)),
            stopNames = mapOf("490EXAMPLE1" to "Euston", "490EXAMPLE2" to "Euston Square", "490EXAMPLE3" to "Victoria Bus Station"),
        )
        val bus = TripLeg(
            "bus", "example", "73", "490EXAMPLE1", "Euston", "490EXAMPLE9", "VICTORIA BUS STATION", now, now.plusSeconds(600),
        )
        assertEquals("490EXAMPLE3", onPoles(bus, mapOf("example" to sequence)).toId)
        assertEquals(ids.subList(1, 3), withRouteStops(bus, LegEnd.planned(bus, StepFree.ANY), sequence).path)
    }

    @Test
    fun a_held_list_moves_its_stop_back_to_where_it_boards_before_boarding() {
        // Back from on board to boarding: the dot goes back to where it boards at once (Codex, #968).
        val held = rideStopList(victoria, TripProgress.Riding(victoria, "Warren Street", 3, null, false), boarded = true)!!
        assertEquals(2, held.here)
        assertEquals(0, held.shownStops(stale = false, progress = null, boarded = false).here)
        assertEquals(0, held.shownStops(stale = false, progress = TripProgress.Riding(victoria, null, null, null, false, seen = false), boarded = true).here)
        // Still on board: held as it is.
        assertEquals(2, held.shownStops(stale = false, progress = TripProgress.Riding(victoria, "Warren Street", 3, null, false), boarded = true).here)
        // Agreeing with the list worked out again.
        assertEquals(rideStopList(victoria, null, boarded = false)!!.here, held.shownStops(false, null, false).here)
    }

    @Test
    fun calls_too_old_to_trust_mark_no_stop_but_a_seen_position_still_does() {
        // TfL's answer gone stale: the card withholds its stops, so the list marks none (SPEC D4; Codex, #968).
        val fromCalls = TripProgress.Riding(victoria, "Warren Street", 3, null, getOffSoon = false)
        assertNull(rideStopList(victoria, fromCalls, boarded = true, stale = true)!!.here)
        val fromPosition = TripProgress.Riding(victoria, "Warren Street", 3, null, getOffSoon = false, byPosition = true)
        assertEquals(2, rideStopList(victoria, fromPosition, boarded = true, stale = true)!!.here)
        // A lost train placed by those same calls: none either (Codex, #968).
        assertNull(rideStopList(victoria, TripProgress.Lost(victoria, ahead = 1, placed = true), boarded = true, stale = true)!!.here)
        // Still waiting, the boarding stop is no call's claim: it stays marked.
        assertEquals(0, rideStopList(victoria, TripProgress.Waiting(victoria, null), boarded = false, stale = true)!!.here)
    }

    @Test
    fun a_bus_path_ending_at_the_stop_area_keeps_where_it_gets_off() {
        // The path ends at the area (490G…) while the leg names a pole; with no route names, the leg's own name
        // still marks where it gets off (Codex, #968). Synthetic ids, public names.
        val bus = TripLeg(
            "bus", "43", "43", "490000001A", "Archway", "490000003N", "Highgate", now, now.plusSeconds(600),
            path = listOf("490G0000002", "490G0000003"), pathNames = listOf("Highgate Hill", ""), toArea = "490G0000003",
        )
        assertEquals(listOf("Archway", "Highgate Hill", "Highgate"), rideStopList(bus, null, boarded = false)!!.names)
    }

    @Test
    fun a_held_list_unmarks_only_a_stop_tfl_s_calls_placed() {
        val riding = TripProgress.Riding(victoria, "Warren Street", 3, null, false)
        val fromCalls = rideStopList(victoria, riding, boarded = true)!!
        assertNull(fromCalls.shownStops(stale = true, riding, boarded = true).here)
        assertEquals(2, fromCalls.shownStops(stale = false, riding, boarded = true).here)
        val byPosition = riding.copy(byPosition = true)
        val seen = rideStopList(victoria, byPosition, boarded = true)!!
        assertEquals(2, seen.shownStops(stale = true, byPosition, boarded = true).here)
        val waiting = rideStopList(victoria, TripProgress.Waiting(victoria, null), boarded = false)!!
        assertEquals(0, waiting.shownStops(stale = true, TripProgress.Waiting(victoria, null), boarded = false).here)
    }

    @Test
    fun a_ride_with_no_named_stops_has_no_list() {
        assertNull(rideStopList(victoria.copy(path = emptyList(), pathNames = emptyList()), null, boarded = false))
    }
}
