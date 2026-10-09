package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Services that end where the rider already is. Example stop ids and public names only. */
class TerminatingTest {
    private val now = Instant.parse("2026-09-24T08:00:00Z")

    private fun dep(destination: String, destinationId: String = "") =
        Departure("example", "Example", "inbound", destination, null, now, "bus", destinationId = destinationId)

    // The rider is 80 m from pole A (area 490G00000001, "Example Road") and 300 m from pole C.
    private val places = listOf(
        Terminating.Place("490000000001A", "490G00000001", "Example Road", 80.0),
        Terminating.Place("490000000001B", "490G00000001", "Example Road", 120.0),
        Terminating.Place("490000000003C", "490G00000003", "Far Street", 300.0),
    )

    @Test
    fun `a service terminating at the stop it leaves from is dropped`() {
        val here = dep("Example Road", destinationId = "490000000001A")
        val onward = dep("Somewhere Else", destinationId = "490000000099Z")
        assertEquals(listOf(onward), Terminating.drop(listOf(here, onward), "490000000001A", places))
    }

    @Test
    fun `a service ending at the rider's nearest place is dropped from a farther stop`() {
        val byArea = dep("Example Road", destinationId = "490G00000001")
        val byName = dep("Example Road")
        val onward = dep("Somewhere Else")
        assertEquals(listOf(onward), Terminating.drop(listOf(byArea, byName, onward), "490000000003C", places))
    }

    @Test
    fun `a terminus farther than the boarding stop is kept`() {
        val toFar = dep("Far Street", destinationId = "490000000003C")
        assertEquals(listOf(toFar), Terminating.drop(listOf(toFar), "490000000001A", places))
    }

    @Test
    fun `a stop with no known distance keeps a departure ending at a nearby place`() {
        val here = dep("Example Road", destinationId = "490000000001A")
        assertEquals(listOf(here), Terminating.drop(listOf(here), "940GZZLUOXC", places))
    }

    @Test
    fun `names compare cleaned and case-folded`() {
        val station = listOf(Terminating.Place("940GZZLUWWL", "940GZZLUWWL", "Walthamstow Central Underground Station", 50.0))
        val train = Departure("victoria", "Victoria", "inbound", "walthamstow central", null, now, "tube")
        assertEquals(emptyList<Departure>(), Terminating.drop(listOf(train), "940GZZLUWWL", station))
    }

    @Test
    fun `a station's line qualifier doesn't part its name from a destination without one`() {
        // TfL brackets Hammersmith by its line in a stop's name; a destination may name it either way.
        val station = listOf(Terminating.Place("940GZZLUHSC", "940GZZLUHSC", "Hammersmith (H&C Line) Underground Station", 50.0))
        val bare = Departure("circle", "Circle", "inbound", "Hammersmith", null, now, "tube")
        val shortened = Departure("circle", "Circle", "inbound", cleanStopName("Hammersmith (H&C Line) Underground Station"), null, now, "tube")
        val onward = Departure("circle", "Circle", "inbound", "Edgware Road (Circle)", null, now, "tube")
        assertEquals(listOf(onward), Terminating.drop(listOf(bare, shortened, onward), "940GZZLUHSC", station))
    }

    @Test
    fun `a destination id that isn't a nearer place keeps the service, whatever its name`() {
        // Another "Example Road" farther away: TfL's id says it isn't the one nearby.
        val elsewhere = dep("Example Road", destinationId = "490000000077Q")
        assertEquals(listOf(elsewhere), Terminating.drop(listOf(elsewhere), "490000000003C", places))
    }

    @Test
    fun `forStop adds the stop itself to its nearer places, by id only`() {
        val stop = StopArrivals("940GZZLUWWL", "Walthamstow Central Underground Station", emptyList(), now, clusterId = "940GZZLUWWL")
        val byId = Departure("victoria", "Victoria", "", "Walthamstow Central", null, now, "tube", destinationId = "940GZZLUWWL")
        val byName = Departure("victoria", "Victoria", "", "Walthamstow Central", null, now, "tube")
        val onward = Departure("victoria", "Victoria", "", "Brixton", null, now, "tube", destinationId = "940GZZLUBXN")
        // A same-named service with no id may be a loop that calls elsewhere before coming back: kept.
        assertEquals(listOf(byName, onward), Terminating.drop(listOf(byId, byName, onward), Terminating.forStop(stop)))
    }

    @Test
    fun `a stop with no known distance has no nearer places`() {
        assertTrue(Terminating.nearer("940GZZLUOXC", places).isEmpty)
    }
}
