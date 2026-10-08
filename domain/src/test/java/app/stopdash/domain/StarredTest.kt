package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure star identity and toggle rule — no DataStore, no Android.
 */
class StarredTest {
    private val victoria = StarredRow("940GZZLUKSX", "victoria", "southbound")
    private val central = StarredRow("940GZZLUOXC", "central", "eastbound")

    @Test
    fun `toggle adds a row that is not starred`() {
        assertEquals(setOf(victoria), Starred.toggle(emptySet(), victoria))
    }

    @Test
    fun `toggle removes a row that is already starred`() {
        assertEquals(setOf(central), Starred.toggle(setOf(victoria, central), victoria))
    }

    @Test
    fun `toggle is its own inverse`() {
        val once = Starred.toggle(setOf(central), victoria)
        assertTrue(victoria in once)
        assertEquals(setOf(central), Starred.toggle(once, victoria))
    }

    @Test
    fun `the star key is the row's stop, line, and resolved direction key`() {
        val row = DepartureRow(
            stopId = "940GZZLUKSX",
            stopName = "King's Cross St. Pancras",
            lineId = "victoria",
            lineName = "Victoria",
            direction = "southbound",
            directionKey = "southbound",
            destination = "Brixton",
            mode = "tube",
            upcoming = emptyList(),
            fetchedAt = Instant.parse("2026-09-18T08:00:00Z"),
        )
        assertEquals(StarredRow("940GZZLUKSX", "victoria", "southbound"), StarredRow.of(row))
    }

    @Test
    fun `the rider's stops are each starred row's and each journey's ends`() {
        // A journey's end recorded with its station keeps the station too; one without keeps the pole alone.
        val journey = FavoriteJourney(
            JourneyEnd("490000000A", "Stop A", areaId = "490G00000A"),
            JourneyEnd("940GZZLUEUS", "Euston"),
            "northern",
        )
        assertEquals(
            setOf("940GZZLUKSX", "940GZZLUOXC", "490000000A", "490G00000A", "940GZZLUEUS"),
            riderStopIds(setOf(victoria, central), listOf(journey)),
        )
        assertEquals(emptySet<String>(), riderStopIds(emptySet(), emptyList()))
    }
}
