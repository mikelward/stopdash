package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which stops are one place: the one rule (SPEC *Journeys*). */
class StopPlaceTest {
    // Synthetic names and positions (SPEC *Privacy*); King's Cross's interchange id is TfL's public one.
    private fun stop(name: String, latitude: Double? = 51.5, hub: String = "", station: Boolean = false) =
        StopPlace.Stop(name, latitude, latitude?.let { -0.12 }, hub, station)

    @Test
    fun `a name's root is what comes before its cross street, compared loosely`() {
        assertEquals("hill", StopPlace.nameRoot("Hill Station  / High Road"))
        assertEquals("hill", StopPlace.nameRoot("Hill Underground Station"))
        assertEquals("kings cross", StopPlace.nameRoot("King's Cross"))
        assertEquals("st pauls", StopPlace.nameRoot("St. Paul's"))
        assertEquals("", StopPlace.nameRoot(" / High Road"))
    }

    @Test
    fun `named alike up to the cross street and close by is one place`() {
        val hill = stop("Hill Underground Station", station = true)
        assertTrue(StopPlace.sameNamed(hill, stop("Hill Station / High Road", 51.5009)))
        assertTrue(StopPlace.sameNamed(stop("Hill Station"), stop("hill station", 51.5009)))
        // Out to 250 m (maintainer, 2026-10-15), and not past it.
        assertTrue(StopPlace.sameNamed(hill, stop("Hill Station / High Road", 51.5022)))
        assertFalse(StopPlace.sameNamed(hill, stop("Hill Station / High Road", 51.5023)))
        // Another name as close by is another place, and so is a name with nothing before its slash.
        assertFalse(StopPlace.sameNamed(hill, stop("Hill Road", 51.5009)))
        assertFalse(StopPlace.sameNamed(stop(" / High Road"), stop(" / High Road", 51.5009)))
        // Nothing says a stop without a position is the same place.
        assertFalse(StopPlace.sameNamed(stop("Hill", null), stop("Hill")))
    }

    @Test
    fun `a line qualifier tells two stations of one name apart`() {
        val circle = stop("Hammersmith (H&C Line)", station = true)
        assertFalse(StopPlace.sameNamed(circle, stop("Hammersmith (Dist&Picc Line)", 51.5009, station = true)))
        assertTrue(StopPlace.sameNamed(circle, stop("Hammersmith (H&C and Circle Lines)", 51.5009, station = true)))
        assertTrue(StopPlace.sameNamed(circle, stop("Hammersmith / King Street", 51.5009)))
    }

    @Test
    fun `an interchange joins a stop to its station, never two bus stops`() {
        // Bus stops TfL files under King's Cross St Pancras by names no name match would join.
        val tube = stop("King's Cross St. Pancras", hub = "HUBKGX", station = true)
        val pole = stop("St Pancras Intern'l & King's X Stns", 51.505, hub = "HUBKGX")
        val otherPole = stop("King's Cross / Caledonian Road", 51.51, hub = "hubkgx")
        assertTrue(StopPlace.samePlace(pole, tube))
        assertTrue(StopPlace.samePlace(tube, otherPole))
        // Two stations of one interchange are one place.
        assertTrue(StopPlace.samePlace(tube, stop("London King's Cross", 51.51, hub = "HUBKGX", station = true)))
        // Two poles named apart stay two places, as the route map keeps them.
        assertFalse(StopPlace.samePlace(pole, otherPole))
        assertFalse(StopPlace.sameInterchange(stop("A"), stop("B")))
        assertFalse(StopPlace.samePlace(pole, stop("Euston", 51.505, hub = "HUBEUS", station = true)))
    }

    @Test
    fun `a station's own stops take in any close by, whatever they're called`() {
        val station = stop("Hill Underground Station", station = true)
        assertTrue(StopPlace.ownStop(station, stop("Example Road", 51.5013)))
        assertFalse(StopPlace.ownStop(station, stop("Example Road", 51.5014)))
        // Named for it, a little farther still, or in its interchange anywhere.
        assertTrue(StopPlace.ownStop(station, stop("Hill Station / High Road", 51.5022)))
        assertTrue(StopPlace.ownStop(station.copy(hubId = "HUBEXA"), stop("Example Road", 51.51, hub = "HUBEXA")))
        // Not the other way round: a station merely near a bus stop isn't that stop's place.
        assertFalse(StopPlace.samePlace(stop("Example Road", 51.5013), station))
    }

    @Test
    fun `a stop is weighed with its interchange, and no position where TfL left one out`() {
        val tube = StopLocation("940GZZLUKSX", "King's Cross St. Pancras", 51.53, -0.12, listOf(LineRef("northern", "Northern", "tube")), hubId = "HUBKGX")
        assertEquals(StopPlace.Stop("King's Cross St. Pancras", 51.53, -0.12, "HUBKGX", true), StopPlace.of(tube))
        val unplaced = StopPlace.of(tube.copy(latitude = 0.0, longitude = 0.0))
        assertNull(unplaced.latitude)
        assertNull(unplaced.longitude)
        // On the prime meridian a longitude is really 0.0, and the stop is still placed (Codex on #867).
        val meridian = tube.copy(longitude = 0.0)
        assertEquals(StopPlace.Stop("King's Cross St. Pancras", 51.53, 0.0, "HUBKGX", true), StopPlace.of(meridian))
        val pole = StopLocation("490EXAMPLEA", "Example Road", 51.5309, 0.0, listOf(LineRef("30", "30", "bus")))
        assertTrue(StopPlace.ownStop(StopPlace.of(meridian), StopPlace.of(pole)))
        val bus = tube.copy(lines = listOf(LineRef("30", "30", "bus")))
        assertFalse(StopPlace.isStation(bus))
        assertTrue(StopPlace.isBusStop(bus))
        // A stop a tram serves as well as a bus is a station's: it has its rail.
        val tramToo = bus.copy(lines = bus.lines + LineRef("tram", "Tram", "tram"))
        assertTrue(StopPlace.isStation(tramToo))
        assertFalse(StopPlace.isBusStop(tramToo))
        // A stop with no lines listed is neither.
        assertFalse(StopPlace.isBusStop(bus.copy(lines = emptyList())))
    }
}
