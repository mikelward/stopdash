package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")
    private val earlier: Instant = now.minusSeconds(600)

    private fun departure(lineId: String, offsetSeconds: Long) = Departure(
        lineId = lineId,
        lineName = lineId,
        direction = "inbound",
        destination = "Brixton",
        platform = null,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = "tube",
    )

    private fun prior(
        departures: List<Departure>,
        disruptions: List<StopDisruption> = emptyList(),
        fetchedAt: Instant = earlier,
    ) = StopArrivals(
        "940GZZLUKSX",
        "King's Cross St. Pancras",
        departures,
        fetchedAt,
        disruptions = disruptions,
    )

    private fun merge(
        freshDepartures: List<Departure>?,
        freshDisruptions: List<StopDisruption>?,
        prior: StopArrivals?,
        lines: List<LineRef> = emptyList(),
        hubId: String = "",
        hubName: String = "",
    ) = Snapshot.mergeStop(
        stopId = "940GZZLUKSX",
        stopName = "King's Cross St. Pancras",
        clusterId = "940GZZLUKSX",
        lines = lines,
        freshDepartures = freshDepartures,
        freshDisruptions = freshDisruptions,
        prior = prior,
        now = now,
        hubId = hubId,
        hubName = hubName,
    )

    @Test
    fun `a stop's National Rail feed comes with its arrivals, kept with the prior when they are`() {
        val fresh = Snapshot.mergeStop(
            "910GEXAMPLE", "Example", "", emptyList(), emptyList(), emptyList(), prior = null, now = now,
            freshRailFeed = RailFeed.NO_KEY,
        )
        assertEquals(RailFeed.NO_KEY, fresh?.railFeed)
        // Arrivals failed: the prior departures and the feed they came with stand together.
        val kept = Snapshot.mergeStop(
            "910GEXAMPLE", "Example", "", emptyList(), null, emptyList(), prior = fresh, now = now,
            freshRailFeed = null,
        )
        assertEquals(RailFeed.NO_KEY, kept?.railFeed)
    }

    @Test
    fun `a stop's trains with no time come with its arrivals, kept with the prior when they are`() {
        val delayed = UntimedTrain(departure("great-example", 60), canceled = false)
        val fresh = Snapshot.mergeStop(
            "910GEXAMPLE", "Example", "", emptyList(), emptyList(), emptyList(), prior = null, now = now,
            freshUntimed = listOf(delayed),
        )
        assertEquals(listOf(delayed), fresh?.untimed)
        // Arrivals failed: the prior departures and their untimed trains stand together.
        val kept = Snapshot.mergeStop("910GEXAMPLE", "Example", "", emptyList(), null, emptyList(), prior = fresh, now = now)
        assertEquals(listOf(delayed), kept?.untimed)
        // Fresh arrivals with none drop them.
        val cleared = Snapshot.mergeStop("910GEXAMPLE", "Example", "", emptyList(), emptyList(), emptyList(), prior = fresh, now = now)
        assertEquals(emptyList<UntimedTrain>(), cleared?.untimed)
    }

    @Test
    fun `fresh arrivals and disruptions are stamped now`() {
        val result = merge(
            freshDepartures = listOf(departure("victoria", 120)),
            freshDisruptions = listOf(StopDisruption("Station closed")),
            prior = null,
        )!!
        assertEquals(listOf("victoria"), result.departures.map { it.lineId })
        assertEquals(listOf("Station closed"), result.disruptions.map { it.description })
        assertEquals(now, result.fetchedAt)
    }

    @Test
    fun `the interchange id and name thread onto the merged stop`() {
        val result = merge(
            freshDepartures = null,
            freshDisruptions = listOf(StopDisruption("No step free access")),
            prior = null,
            hubId = "HUBKGX",
            hubName = "King's Cross & St Pancras International",
        )!!
        assertEquals("HUBKGX", result.hubId)
        assertEquals("King's Cross & St Pancras International", result.hubName)
    }

    @Test
    fun `failed arrivals keep the prior departures at their older age`() {
        val prior = prior(listOf(departure("northern", 300)))
        // Arrivals failed (null); the disruption request succeeded (empty).
        val result = merge(freshDepartures = null, freshDisruptions = emptyList(), prior = prior)!!
        assertEquals(prior.departures, result.departures)
        // Stamped at the prior fetch, not now — the departures shown are that old, so the
        // stale withhold fires on time rather than reading them as just-updated (SPEC D4).
        assertEquals(earlier, result.fetchedAt)
    }

    @Test
    fun `a successful empty arrivals replaces the prior departures`() {
        val prior = prior(listOf(departure("northern", 300)))
        // The stop genuinely has no departures now (empty, not failed): the prior ones are
        // dropped, but the stop stays as a known-empty entry (arrivals succeeded), not
        // confused with one whose arrivals failed.
        val result = merge(freshDepartures = emptyList(), freshDisruptions = emptyList(), prior = prior)!!
        assertEquals(emptyList<Departure>(), result.departures)
        assertEquals(now, result.fetchedAt)
    }

    @Test
    fun `failed arrivals with no prior still show a fresh disruption, stamped now`() {
        val result = merge(
            freshDepartures = null,
            freshDisruptions = listOf(StopDisruption("Station closed")),
            prior = null,
        )!!
        assertEquals(emptyList<Departure>(), result.departures)
        assertEquals(listOf("Station closed"), result.disruptions.map { it.description })
        assertEquals(now, result.fetchedAt)
    }

    @Test
    fun `a failed disruption drops the prior notice rather than aging it`() {
        val prior = prior(
            listOf(departure("northern", 300)),
            disruptions = listOf(StopDisruption("Station closed")),
        )
        // Arrivals refreshed but the disruption fetch failed. A retained closure would show
        // beside a fresh arrivals stamp with no way to mark it aged (a stop-status row has
        // no countdown to withhold), so it is dropped — the caller's disruptionUnknown
        // banner carries the uncertainty instead of a possibly-reopened station reading live.
        val result = merge(
            freshDepartures = listOf(departure("northern", 60)),
            freshDisruptions = null,
            prior = prior,
        )!!
        assertEquals(emptyList<StopDisruption>(), result.disruptions)
        assertEquals(now, result.fetchedAt)
    }

    @Test
    fun `a known-empty stop is kept even with no lines or disruption`() {
        // Arrivals succeeded but returned none, no lines, no disruption: still KEPT as a
        // determinate known-empty stop, so a failure elsewhere can't turn a real "no
        // departures" into a whole-screen error (arrivals were fetched — the stop is known).
        val result = merge(freshDepartures = emptyList(), freshDisruptions = emptyList(), prior = null)!!
        assertEquals(emptyList<Departure>(), result.departures)
        assertEquals(now, result.fetchedAt)
    }

    @Test
    fun `a stop whose arrivals failed with nothing to fall back on is dropped`() {
        // Arrivals failed, no prior, no fresh disruption: unknown, so it drops out and the
        // caller surfaces the failure instead of a fabricated "no departures".
        assertNull(merge(freshDepartures = null, freshDisruptions = emptyList(), prior = null))
    }

    @Test
    fun `an empty stop with declared lines is kept so a suspended line can surface`() {
        // Arrivals succeeded (empty, not failed), so "no departures" is a real fact and the
        // declared line can surface as a status row.
        val result = merge(
            freshDepartures = emptyList(),
            freshDisruptions = emptyList(),
            prior = null,
            lines = listOf(LineRef("circle", "Circle", "tube")),
        )!!
        assertEquals(listOf("circle"), result.lines.map { it.id })
        assertEquals(now, result.fetchedAt)
    }

    @Test
    fun `arrivalsFresh reflects whether the arrivals were fetched this snapshot`() {
        // Fetched (even empty) → fresh, so a status row may claim "No departures".
        assertTrue(
            merge(freshDepartures = emptyList(), freshDisruptions = emptyList(), prior = null)!!.arrivalsFresh,
        )
        // Carried from a prior (arrivals failed) → not fresh.
        val prior = prior(listOf(departure("northern", 300)))
        assertFalse(
            merge(freshDepartures = null, freshDisruptions = emptyList(), prior = prior)!!.arrivalsFresh,
        )
        // Disruption-only (arrivals failed, no prior, fresh notice, stamped now) → not fresh.
        assertFalse(
            merge(
                freshDepartures = null,
                freshDisruptions = listOf(StopDisruption("Station closed")),
                prior = null,
            )!!.arrivalsFresh,
        )
    }

    @Test
    fun `a line-only stop whose arrivals failed with no prior is dropped`() {
        // Arrivals failed (null) with no prior: we never learned the stop's departures, so
        // it is NOT kept on its declared lines alone — otherwise the screen would assert
        // "no departures" for a stop it never reached, hiding the failure (SPEC principle 1).
        assertNull(
            merge(
                freshDepartures = null,
                freshDisruptions = emptyList(),
                prior = null,
                lines = listOf(LineRef("circle", "Circle", "tube")),
            ),
        )
    }
}
