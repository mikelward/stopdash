package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a near-me stop notice is drawn ([planNotices], SPEC *Disruptions*). Public names, synthetic notices. */
class NoticePlanTest {
    private val now = Instant.parse("2026-01-01T08:00:00Z")

    private fun departure(line: String, destination: String, platform: String?, mode: String = "tube") =
        Departure(line, line, "outbound", destination, platform, now.plusSeconds(120), mode)

    private fun plan(vararg stops: StopArrivals): Pair<NoticePlan, List<StopGroup>> {
        val rows = DepartureRows.across(stops.toList(), now)
        val groups = StopGrouping.groupByStop(rows, warningsLead = false)
        val hubs = stops.filter { it.interchangeId.isNotBlank() }.associate { it.stopId to it.interchangeId }
        return planNotices(rows.filter { it.stopDisruption != null }, groups, groups, hubs) to groups
    }

    @Test
    fun `a station-wide notice goes above the station's first section, once`() {
        val station = StopArrivals(
            "940GZZLUKSX", "King's Cross St. Pancras",
            listOf(
                departure("victoria", "Brixton", "Southbound - Platform 2"),
                departure("piccadilly", "Cockfosters", "Eastbound - Platform 3"),
            ),
            fetchedAt = now,
            disruptions = listOf(StopDisruption("No step-free access to the Victoria line")),
        )
        val (plan, groups) = plan(station)
        assertTrue(groups.size > 1)
        assertEquals(listOf(0), plan.aboveGroup.keys.toList())
        assertEquals(1, plan.aboveGroup.getValue(0).size)
        assertTrue(plan.onPole.isEmpty() && plan.placeless.isEmpty())
    }

    @Test
    fun `grouped by station, an interchange's notice heads its own stop's mode, not the first listed`() {
        val tube = StopArrivals(
            "940GZZLUKSX", "King's Cross St. Pancras", listOf(departure("victoria", "Brixton", "Southbound - Platform 2")),
            fetchedAt = now,
            interchangeId = "HUBKGX",
        )
        val rail = StopArrivals(
            "910GKNGX", "London King's Cross", listOf(Departure("lner", "LNER", "outbound", "Edinburgh", "Platform 4", now.plusSeconds(600), "national-rail")),
            fetchedAt = now,
            interchangeId = "HUBKGX",
            disruptions = listOf(StopDisruption("London King's Cross station is closed.")),
        )
        val rows = DepartureRows.across(listOf(tube, rail), now)
        val groups = StopGrouping.groupByStop(rows, warningsLead = false, splitByMode = true)
        val hubs = mapOf("940GZZLUKSX" to "HUBKGX", "910GKNGX" to "HUBKGX")
        val notices = rows.filter { it.stopDisruption != null }
        val tubeGroup = groups.indexOfFirst { g -> g.rows.any { it.stopId == "940GZZLUKSX" } }
        val railGroup = groups.indexOfFirst { g -> g.rows.any { it.stopId == "910GKNGX" } }
        // The Underground's sooner train lists it first.
        assertTrue(tubeGroup in 0 until railGroup)
        // By stop/platform, as before: above the interchange's first section.
        assertEquals(listOf(tubeGroup), planNotices(notices, groups, groups, hubs).aboveGroup.keys.toList())
        // Grouped by station: above its own stop's, so the rail station's heading says it, not the Underground's.
        assertEquals(listOf(railGroup), planNotices(notices, groups, groups, hubs, stationOf = { g -> g.rows.first().let { "${it.clusterId}|${it.mode}" } }).aboveGroup.keys.toList())
    }

    @Test
    fun `a lettered pole's notice stays on that pole`() {
        fun pole(id: String, letter: String, notice: String? = null) = StopArrivals(
            id, "Euston Road", listOf(departure("18", "Sudbury", null, mode = "bus")),
            fetchedAt = now,
            clusterId = "490G000EXAMPLE",
            stopLetter = letter,
            disruptions = listOfNotNull(notice?.let(::StopDisruption)),
        )
        val (plan, groups) = plan(pole("490000001E", "E", "Bus Stop Closed"), pole("490000001F", "F"))
        val stopE = groups.single { g -> g.rows.any { it.stopId == "490000001E" } }
        assertEquals(setOf(stopE.key), plan.onPole.keys)
        assertTrue(plan.aboveGroup.isEmpty() && plan.placeless.isEmpty())
    }

    @Test
    fun `a pole's notice whose section is held behind a tap goes with the card instead`() {
        val stop = StopArrivals(
            "490000001E", "Euston Road", listOf(departure("18", "Sudbury", null, mode = "bus")),
            fetchedAt = now,
            clusterId = "490G000EXAMPLE",
            stopLetter = "E",
            disruptions = listOf(StopDisruption("Bus Stop Closed")),
        )
        val rows = DepartureRows.across(listOf(stop), now)
        val groups = StopGrouping.groupByStop(rows, warningsLead = false)
        val plan = planNotices(
            rows.filter { it.stopDisruption != null }, groups, emptyList(),
            hiddenGroupKeys = groups.mapTo(HashSet()) { it.key },
        )
        assertTrue(plan.onPole.isEmpty())
        assertEquals(listOf("490000001E"), plan.placeless.map { it.stopId })
    }

    @Test
    fun `a notice filed against several poles of a junction heads the junction, not the pole`() {
        fun pole(id: String, letter: String) = StopArrivals(
            id, "Euston Road", listOf(departure("18", "Sudbury", null, mode = "bus")),
            fetchedAt = now,
            clusterId = "490G000EXAMPLE",
            stopLetter = letter,
            disruptions = listOf(StopDisruption("Bus Stop Closed")),
        )
        val stops = listOf(pole("490000001E", "E"), pole("490000001F", "F"))
        val rows = DepartureRows.across(stops, now)
        val groups = StopGrouping.groupByStop(rows, warningsLead = false)
        val notices = rows.filter { it.stopDisruption != null }
        // Filed against both poles; the fold upstream keeps one copy.
        val shared = setOf(stopPlaceKey(notices.first()) to "Bus Stop Closed")
        val plan = planNotices(notices.take(1), groups, groups, shared = shared)
        assertEquals(listOf(0), plan.aboveGroup.keys.toList())
        assertTrue(plan.onPole.isEmpty())
    }

    @Test
    fun `a closed station with nothing running has no section, so it's placed by distance`() {
        val closed = StopArrivals(
            "940GZZLUESQ", "Euston Square", emptyList(),
            fetchedAt = now,
            disruptions = listOf(StopDisruption("Station closed due to strike action.")),
        )
        val open = StopArrivals("940GZZLUEUS", "Euston", listOf(departure("victoria", "Brixton", "Platform 5")), fetchedAt = now)
        val (plan, _) = plan(open, closed)
        assertEquals(listOf("940GZZLUESQ"), plan.placeless.map { it.stopId })
        assertTrue(plan.aboveGroup.isEmpty() && plan.onPole.isEmpty())
    }

    @Test
    fun `an interchange's notice goes above a member's sections, by hub`() {
        // Reported on the tube station, which has nothing running; the interchange's other station does.
        val tube = StopArrivals(
            "940GZZLUKSX", "King's Cross St. Pancras", emptyList(),
            fetchedAt = now,
            interchangeId = "HUBKGX",
            hubName = "King's Cross & St Pancras International",
            disruptions = listOf(StopDisruption("No step-free access")),
        )
        val rail = StopArrivals(
            "910GSTPX", "London St Pancras International",
            listOf(departure("thameslink", "Brighton", "Platform A", mode = "national-rail")),
            fetchedAt = now,
            interchangeId = "HUBKGX",
        )
        val (plan, _) = plan(rail, tube)
        assertEquals(listOf(0), plan.aboveGroup.keys.toList())
        assertTrue(plan.placeless.isEmpty())
    }

    @Test
    fun `a notice doesn't join an unrelated stop that only shares its name`() {
        fun stop(id: String, departures: List<Departure>, notice: String? = null) = StopArrivals(
            id, "High Street", departures,
            fetchedAt = now,
            clusterId = "High Street",
            disruptions = listOfNotNull(notice?.let(::StopDisruption)),
        )
        val (plan, _) = plan(
            stop("490000002A", emptyList(), "Bus Stop Closed"),
            stop("490000003B", listOf(departure("25", "Ilford", null, mode = "bus"))),
        )
        assertTrue(plan.aboveGroup.isEmpty())
        assertEquals(listOf("490000002A"), plan.placeless.map { it.stopId })
    }

    @Test
    fun `an arrow-only pole's notice stays on that pole`() {
        fun pole(id: String, bearing: String, notice: String? = null) = StopArrivals(
            id, "Euston Road", listOf(departure("18", "Sudbury", null, mode = "bus")),
            fetchedAt = now,
            clusterId = "490G000EXAMPLE",
            bearing = bearing,
            disruptions = listOfNotNull(notice?.let(::StopDisruption)),
        )
        val (plan, groups) = plan(pole("490000001N", "N", "Bus Stop Closed"), pole("490000001S", "S"))
        val north = groups.single { g -> g.rows.any { it.stopId == "490000001N" } }
        assertEquals(setOf(north.key), plan.onPole.keys)
        assertTrue(plan.aboveGroup.isEmpty() && plan.placeless.isEmpty())
    }

    @Test
    fun `a pole sharing its section with a neighbor heads the place instead`() {
        fun pole(id: String, notice: String? = null) = StopArrivals(
            id, "Euston Road", listOf(departure("18", "Sudbury", null, mode = "bus")),
            fetchedAt = now,
            clusterId = "490G000EXAMPLE",
            disruptions = listOfNotNull(notice?.let(::StopDisruption)),
        )
        val (plan, groups) = plan(pole("490000001X", "Bus Stop Closed"), pole("490000001Y"))
        val shared = groups.single { g -> g.rows.any { it.stopId == "490000001X" } }
        assertTrue(shared.rows.any { it.stopId == "490000001Y" })
        assertTrue(plan.onPole.isEmpty())
        assertEquals(listOf("490000001X"), plan.aboveGroup.values.flatten().map { it.stopId })
    }
}
