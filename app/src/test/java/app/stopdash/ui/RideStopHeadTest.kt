package app.stopdash.ui

import app.stopdash.domain.Coordinates
import app.stopdash.domain.LineRef
import app.stopdash.domain.TripLeg
import app.stopdash.domain.rideStopList
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a stop tapped under Show stops opens with ([rideStopHead], [rideStopOpen]). Well-known stations, synthetic positions. */
class RideStopHeadTest {
    private val now = Instant.parse("2026-10-23T08:00:00Z")
    private val victoria = TripLeg(
        "tube", "victoria", "Victoria", "940GZZLUVIC", "Victoria", "940GZZLUKSX", "King's Cross St. Pancras", now, now.plusSeconds(600),
        path = listOf("940GZZLUGPK", "940GZZLUOXC", "940GZZLUKSX"),
        pathNames = listOf("Green Park", "Oxford Circus", "King's Cross St. Pancras"),
        headings = listOf("Walthamstow Central Underground Station"),
        fromAt = Coordinates(51.5, -0.14),
        toAt = Coordinates(51.53, -0.12),
    )

    @Test
    fun a_tapped_stop_is_headed_by_its_ride_with_its_heading_named_as_a_card_names_it() {
        val head = rideStopHead(victoria, rideStopList(victoria, null, boarded = false))
        assertEquals(LineRef("victoria", "Victoria", "tube"), head.line)
        assertEquals("Victoria", head.fromName)
        // Its " Underground Station" dropped, as a card's destination's is.
        assertEquals("Walthamstow Central", head.towards)
        // The Planner places its ends only.
        assertEquals(listOf(Coordinates(51.5, -0.14), null, null, Coordinates(51.53, -0.12)), head.positions)

        val open = rideStopOpen(head, "940GZZLUOXC", "Oxford Circus", 2)
        assertEquals("940GZZLUOXC", open.stopId)
        assertEquals("Walthamstow Central", open.towards)
        assertEquals(null, open.position)
        assertTrue(open.ridden)
        assertFalse(open.boarding)
        assertEquals(Coordinates(51.53, -0.12), rideStopOpen(head, "940GZZLUKSX", "King's Cross St. Pancras", 3).position)
    }

    @Test
    fun a_ride_heading_more_than_one_way_names_none() {
        // Trains to either end serve the ride: which the tapped stop's would be isn't known.
        val either = victoria.copy(headings = listOf("Walthamstow Central", "Seven Sisters"))
        assertEquals("", rideStopHead(either, rideStopList(either, null, boarded = false)).towards)
    }
}
