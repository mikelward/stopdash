package app.stopdash.data

import app.stopdash.domain.Departure
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.LineRef
import app.stopdash.domain.RailFeed
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopDisruption
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistedSnapshotTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun sample() = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                stopId = "940GZZLUOXC",
                stopName = "Oxford Circus",
                departures = listOf(
                    Departure(
                        lineId = "victoria",
                        lineName = "Victoria",
                        direction = "inbound",
                        destination = "Brixton",
                        platform = "Platform 1",
                        expectedArrival = now.plusSeconds(180),
                        mode = "tube",
                    ),
                ),
                fetchedAt = now,
                lines = listOf(LineRef("victoria", "Victoria", "tube")),
                arrivalsFresh = true,
            ),
            StopArrivals(
                stopId = "940GZZLUKSX",
                stopName = "King's Cross St. Pancras",
                departures = emptyList(),
                fetchedAt = now.minusSeconds(600),
                lines = emptyList(),
                // A carried-from-prior stop: this flag must survive the round trip, since it
                // gates a status row's "No departures" claim.
                arrivalsFresh = false,
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `round trips through the persisted form unchanged`() {
        val snapshot = sample()
        assertEquals(snapshot, snapshot.toPersisted().toDomain())
    }

    @Test
    fun `stop disruptions are not persisted, since a closure cannot be safely aged`() {
        val closed = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    stopId = "940GZZLUOXC",
                    stopName = "Oxford Circus",
                    departures = emptyList(),
                    fetchedAt = now,
                    disruptions = listOf(StopDisruption("Station closed until further notice")),
                ),
            ),
            fetchedAt = now,
        )
        val restored = closed.toPersisted().toDomain()!!
        // The closure is dropped on the way to disk, so a cleared-since closure can't
        // resurrect on the next launch; everything else survives.
        assertEquals(emptyList<StopDisruption>(), restored.stops.single().disruptions)
        assertEquals(closed.stops.single().copy(disruptions = emptyList()), restored.stops.single())
    }

    @Test
    fun `a null platform survives the round trip`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    stopId = "490000",
                    stopName = "A Bus Stop",
                    departures = listOf(
                        Departure("73", "73", "outbound", "Victoria", null, now.plusSeconds(60), "bus"),
                    ),
                    fetchedAt = now,
                ),
            ),
            fetchedAt = now,
        )
        val restored = snapshot.toPersisted().toDomain()
        assertNull(restored!!.stops.single().departures.single().platform)
        assertEquals(snapshot, restored)
    }

    @Test
    fun `the via branch survives the round trip`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    stopId = "940GZZLUEUS",
                    stopName = "Euston",
                    departures = listOf(
                        Departure(
                            "northern", "Northern", "outbound", "Battersea Power",
                            null, now.plusSeconds(120), "tube", branch = "Charing X",
                        ),
                    ),
                    fetchedAt = now,
                ),
            ),
            fetchedAt = now,
        )
        val restored = snapshot.toPersisted().toDomain()!!
        assertEquals("Charing X", restored.stops.single().departures.single().branch)
        assertEquals(snapshot, restored)
    }

    @Test
    fun `a stop's nearer places survive the round trip, for the widget's refresh`() {
        val nearer = app.stopdash.domain.Terminating.Nearer(setOf("490000000001A", "490G00000001"), setOf("example road"))
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    "490000000003C", "Far Street",
                    listOf(Departure("example", "Example", "inbound", "Example Road", null, now, "bus", destinationId = "490000000001A")),
                    now, nearer = nearer,
                ),
            ),
            fetchedAt = now,
        )
        assertEquals(snapshot, snapshot.toPersisted().toDomain())
    }

    @Test
    fun `a departure's train survives the round trip`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    "940GZZLUCWR", "Canada Water",
                    listOf(Departure("jubilee", "Jubilee", "outbound", "Stanmore", null, now, "tube", vehicleId = "162")),
                    now,
                ),
            ),
            fetchedAt = now,
        )
        val restored = snapshot.toPersisted().toDomain()!!
        assertEquals("162", restored.stops.single().departures.single().vehicleId)
        assertEquals(snapshot, restored)
    }

    @Test
    fun `the bus pole letter, bearing, and towards survive the round trip`() {
        // They are grouping inputs like clusterId, so a restored snapshot must keep its per-pole
        // "Stop D (towards Farringdon)" / "(Eastbound)" sub-headers instead of collapsing to bare/terminus
        // until the refresh lands (Codex P2, PR #118).
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    stopId = "490000129D",
                    stopName = "King's Cross Station",
                    departures = emptyList(),
                    fetchedAt = now,
                    lines = listOf(LineRef("17", "17", "bus")),
                    stopLetter = "D",
                    bearing = "E",
                    towards = "Farringdon Or Holborn Circus",
                ),
            ),
            fetchedAt = now,
        )
        val restored = snapshot.toPersisted().toDomain()!!.stops.single()
        assertEquals("D", restored.stopLetter)
        assertEquals("E", restored.bearing)
        assertEquals("Farringdon Or Holborn Circus", restored.towards)
        assertEquals(snapshot, snapshot.toPersisted().toDomain())
    }

    @Test
    fun `restoring normalizes an older build's raw branch spelling`() {
        // A snapshot a previous build wrote can carry TfL's raw "Charing Cross" / "Bank Branch";
        // restore folds them to the canonical short label so a row never shows two spellings for
        // one trunk across a process restart (before the first refresh, and while offline).
        val legacy = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    stopId = "940GZZLUEUS",
                    stopName = "Euston",
                    departures = listOf(
                        Departure(
                            "northern", "Northern", "outbound", "Battersea Power",
                            null, now.plusSeconds(120), "tube", branch = "Charing Cross",
                        ),
                        Departure(
                            "northern", "Northern", "outbound", "Edgware",
                            null, now.plusSeconds(300), "tube", branch = "Bank Branch",
                        ),
                    ),
                    fetchedAt = now,
                ),
            ),
            fetchedAt = now,
        )
        val restored = legacy.toPersisted().toDomain()!!
        assertEquals(
            listOf("Charing X", "Bank"),
            restored.stops.single().departures.map { it.branch },
        )
    }

    @Test
    fun `an unknown format version is discarded rather than mis-read`() {
        val fromFuture = sample().toPersisted().copy(version = PersistedSnapshot.CURRENT_VERSION + 1)
        assertNull(fromFuture.toDomain())
    }

    @Test
    fun `a stop's National Rail feed state survives the round trip`() {
        for (feed in RailFeed.entries) {
            val snapshot = DeparturesSnapshot(
                stops = listOf(
                    StopArrivals(
                        stopId = "910GEXAMPLE",
                        stopName = "Example",
                        departures = emptyList(),
                        fetchedAt = now,
                        lines = listOf(LineRef("southern", "Southern", "national-rail")),
                        railFeed = feed,
                    ),
                ),
                fetchedAt = now,
            )
            assertEquals(feed, snapshot.toPersisted().toDomain()!!.stops.single().railFeed)
        }
    }

    @Test
    fun `an unknown or missing feed state reads back as none`() {
        val stop = PersistedStop(stopId = "910GEXAMPLE", stopName = "Example", railFeed = "SOMETHING_NEW")
        assertNull(PersistedSnapshot(stops = listOf(stop)).toDomain()!!.stops.single().railFeed)
        assertNull(PersistedSnapshot(stops = listOf(stop.copy(railFeed = null))).toDomain()!!.stops.single().railFeed)
    }

    @Test
    fun `missing stops survive the round trip`() {
        val snapshot = sample().copy(missingStopIds = setOf("940GZZLUBND"))
        assertEquals(snapshot, snapshot.toPersisted().toDomain())
    }
}
