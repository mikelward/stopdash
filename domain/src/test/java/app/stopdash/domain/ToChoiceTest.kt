package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToChoiceTest {
    private val bank = StationMatch("940GZZLUBNK", "Bank", listOf("tube"))
    // A hub's position (King's Cross St Pancras), not anyone's place.
    private val place = TripDestination.Place(Coordinates(51.5308, -0.1238), "King's Cross")

    @Test
    fun `nothing chosen leaves the station page up`() {
        assertFalse(ToChoice.NONE.open)
        assertTrue(ToChoice.NONE.startPicking().open)
    }

    @Test
    fun `a picked place becomes the destination by its coordinate`() {
        val chosen = ToChoice.NONE.startPicking().pickPlace(place)
        assertEquals(place, chosen.place)
        assertEquals("King's Cross", chosen.name)
        assertNull(chosen.stopId)
        assertFalse("the search closes on a pick", chosen.picking)
        assertTrue(chosen.open)
        assertTrue(chosen.hasDestination)
    }

    @Test
    fun `a stop picked after a place replaces it, and the other way round`() {
        val toStop = ToChoice.NONE.pickPlace(place).startPicking().pickStop(bank)
        assertEquals("940GZZLUBNK", toStop.stopId)
        assertNull(toStop.place)
        val toPlace = toStop.startPicking().pickPlace(place)
        assertNull(toPlace.stopId)
        assertEquals(place, toPlace.place)
    }

    @Test
    fun `back from the search returns to a picked place's trip, or closes when nothing was picked`() {
        val reopened = ToChoice.NONE.pickPlace(place).startPicking().closePicker()
        assertEquals(place, reopened.place)
        assertTrue(reopened.open)
        assertFalse(ToChoice.NONE.startPicking().hasDestination)
    }

    @Test
    fun `clearing drops a place as well as a stop`() {
        assertFalse(ToChoice.NONE.pickPlace(place).clearDestination().open)
        assertFalse(ToChoice.NONE.pickStop(bank).clearDestination().open)
    }

    private val t0 = java.time.Instant.parse("2026-10-02T08:00:00Z")
    private val ride = TripLeg("tube", "victoria", "Victoria", "940GZZLUVIC", "Victoria", "940GZZLUKSX", "King's Cross St. Pancras", t0, t0.plusSeconds(600))
    private val trip = ActiveTrip(TripRoute(listOf(ride)), "King's Cross", startedAt = t0)

    @Test
    fun `a trip on the way is planned again to the stop or station picked`() {
        assertEquals(ToChoice(stopId = "HUBKGX", name = "King's Cross"), ToChoice.of(trip.copy(destinationStopId = "HUBKGX")))
    }

    @Test
    fun `a trip on the way to a place is planned again to it`() {
        val place = TripDestination.Place(Coordinates(51.5, -0.12), "Work")
        assertEquals(ToChoice(name = "Work", place = place), ToChoice.of(trip.copy(destinations = listOf(place))))
    }

    @Test
    fun `a trip kept before its destination was is planned again to where its route ends`() {
        assertEquals(ToChoice(stopId = "940GZZLUKSX", name = "King's Cross"), ToChoice.of(trip))
    }

    @Test
    fun `a place with no stop in range opens on its To search`() {
        val opened = ToChoice.NONE.openedWithoutStops(changeTo = null)
        assertEquals(ToChoice.NONE.startPicking(), opened)
        assertTrue(opened!!.open)
    }

    @Test
    fun `a place with no stop in range keeps the To a change of start carries`() {
        val carried = ToChoice.NONE.pickStop(bank)
        assertEquals(carried, ToChoice.NONE.openedWithoutStops(changeTo = carried))
    }

    @Test
    fun `a place with no stop in range leaves an open trip as it is`() {
        assertNull(ToChoice.NONE.startPicking().openedWithoutStops(changeTo = null))
        assertNull(ToChoice.NONE.pickPlace(place).openedWithoutStops(changeTo = ToChoice.NONE.pickStop(bank)))
    }
}
