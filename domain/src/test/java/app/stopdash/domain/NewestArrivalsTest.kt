package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewestArrivalsTest {
    private val t0 = Instant.parse("2026-01-01T12:00:05Z")

    private fun board(vararg arrivals: Long) = arrivals.map {
        Departure(
            lineId = "northern", lineName = "Northern", direction = "inbound", destination = "Morden",
            platform = "Southbound - Platform 4", expectedArrival = t0.plusSeconds(it), mode = "tube", vehicleId = "124",
        )
    }

    @Test
    fun `an answer older than the stop's last is set aside for that last`() {
        // A fresh answer made at :35, then a cache copy made at :05.
        val newest = NewestArrivals()
        val fresh = board(130)
        assertEquals(fresh, newest.pick("940GZZLUCTN", t0.plusSeconds(30), fresh).departures)
        val pick = newest.pick("940GZZLUCTN", t0, board(160))
        assertEquals(fresh, pick.departures)
        assertEquals(Duration.ofSeconds(30), pick.setAside)
    }

    @Test
    fun `a newer or equally new answer is taken and becomes the one kept`() {
        val newest = NewestArrivals()
        newest.pick("940GZZLUCTN", t0, board(160))
        val same = board(158)
        assertEquals(same, newest.pick("940GZZLUCTN", t0, same).departures)
        val later = board(120)
        val pick = newest.pick("940GZZLUCTN", t0.plusSeconds(40), later)
        assertEquals(later, pick.departures)
        assertNull(pick.setAside)
        // What's kept now is the later answer: a copy between the two is set aside for it.
        assertEquals(later, newest.pick("940GZZLUCTN", t0.plusSeconds(20), board(140)).departures)
    }

    @Test
    fun `each stop is judged against its own last answer`() {
        val newest = NewestArrivals()
        newest.pick("940GZZLUCTN", t0.plusSeconds(60), board(100))
        val other = board(200)
        assertEquals(other, newest.pick("940GZZLUEUS", t0, other).departures)
    }

    @Test
    fun `an answer far older than any cache copy is taken, so a clock moved back doesn't freeze the stop`() {
        val newest = NewestArrivals()
        newest.pick("940GZZLUCTN", t0.plusSeconds(600), board(700))
        val back = board(90)
        val pick = newest.pick("940GZZLUCTN", t0, back)
        assertEquals(back, pick.departures)
        assertNull(pick.setAside)
        // ...and is the one kept from then on.
        assertEquals(back, newest.pick("940GZZLUCTN", t0.minusSeconds(10), board(100)).departures)
    }

    @Test
    fun `an undated answer is taken as it comes and leaves the kept one alone`() {
        val newest = NewestArrivals()
        val dated = board(100)
        newest.pick("940GZZLUCTN", t0.plusSeconds(30), dated)
        val undated = emptyList<Departure>()
        assertEquals(undated, newest.pick("940GZZLUCTN", null, undated).departures)
        assertEquals(dated, newest.pick("940GZZLUCTN", t0, board(130)).departures)
    }

    @Test
    fun `the least recently answered stop is dropped past the bound`() {
        val newest = NewestArrivals()
        newest.pick("first", t0.plusSeconds(60), board(100))
        for (i in 1..NewestArrivals.MAX) newest.pick("stop$i", t0, board(100))
        // "first" was dropped, so an older answer for it is no longer set aside.
        val older = board(130)
        assertEquals(older, newest.pick("first", t0, older).departures)
    }
}
