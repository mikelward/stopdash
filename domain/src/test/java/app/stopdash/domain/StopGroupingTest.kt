package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The group's qualifier by kind, so the assertions read one field at a time.
private val StopGroup.platform: String? get() = (qualifier as? StopQualifier.Platform)?.number
private val StopGroup.platformDir: String? get() = (qualifier as? StopQualifier.Platform)?.direction
private val StopGroup.compass: String? get() = (qualifier as? StopQualifier.Compass)?.label
private val StopGroup.towards: String? get() = (qualifier as? StopQualifier.Towards)?.towards
private val StopGroup.busLetter: String? get() = (qualifier as? StopQualifier.BusStop)?.letter
private val StopGroup.busTowards: String? get() = (qualifier as? StopQualifier.BusStop)?.towards
private val StopGroup.busBearing: String? get() = (qualifier as? StopQualifier.BusBearing)?.bearing

/**
 * The pure per-place grouping rule (SPEC D8): one group per place (stops sharing a display
 * name — a junction's poles, a station's platforms), bare-name header, warnings leading their
 * place. The direction/terminus subhead is a follow-up and is not exercised here. Public
 * infrastructure/line names only in the fixtures — no user route data (SPEC *Privacy*).
 */
class StopGroupingTest {

    private val now: Instant = Instant.parse("2026-09-21T08:00:00Z")

    private fun dep(destination: String, platform: String = "", mode: String = "tube") =
        Departure(
            lineId = "l",
            lineName = "L",
            direction = "",
            destination = destination,
            platform = platform,
            expectedArrival = now.plusSeconds(60),
            mode = mode,
        )

    private fun row(
        stopId: String,
        stopName: String,
        clusterId: String = "",
        lineId: String = "l",
        direction: String = "",
        destination: String = "Terminus",
        platform: String = "",
        mode: String = "tube",
        stopLetter: String = "",
        bearing: String = "",
        towards: String = "",
        upcoming: List<Departure> = listOf(dep(destination, platform, mode)),
        stopDisruption: String? = null,
    ) = DepartureRow(
        stopId = stopId,
        stopName = stopName,
        clusterId = clusterId,
        stopLetter = stopLetter,
        bearing = bearing,
        towards = towards,
        lineId = lineId,
        lineName = lineId.uppercase(),
        direction = direction,
        directionKey = direction.ifBlank { platform.ifBlank { destination } },
        destination = destination,
        mode = mode,
        upcoming = upcoming,
        fetchedAt = now,
        stopDisruption = stopDisruption,
    )

    @Test
    fun `a platform row with later Platform Unknown trains folded in keeps its platform header`() {
        // Highbury & Islington: three named Platform 7 trains and two later "Platform Unknown" ones
        // fold into one row, which still heads "Platform 7".
        fun richmond(platform: String, offset: Long) =
            Departure("mildmay", "Mildmay", "outbound", "Richmond (London)", platform, now.plusSeconds(offset), "overground")
        val stop = StopArrivals(
            stopId = "910GHGHI",
            stopName = "Highbury & Islington",
            departures = listOf(
                richmond("Platform 7", 420), richmond("Platform 7", 1020), richmond("Platform 7", 1740),
                richmond("Platform Unknown", 2400), richmond("Platform Unknown", 3300),
            ),
            fetchedAt = now,
        )

        val groups = StopGrouping.groupByStop(DepartureRows.across(listOf(stop), now))

        assertEquals(listOf(StopQualifier.Platform("7", null)), groups.map { it.qualifier })
    }

    @Test
    fun `a line whose every train has no time heads its own platform, never a warned stop`() {
        val canceled = UntimedTrain(
            Departure("great-example", "Great Example", "", "Far", "Platform 3", now.plusSeconds(120), "national-rail"),
            canceled = true,
        )
        val stop = StopArrivals("910GEXAMPLE", "Example", emptyList(), fetchedAt = now, untimed = listOf(canceled))
        val rows = DepartureRows.across(listOf(stop), now)

        assertEquals(listOf(StopQualifier.Platform("3", null)), StopGrouping.groupByStop(rows).map { it.qualifier })
        // Not a "No departures" warning: its stop doesn't head a group of its own for one.
        assertEquals(emptySet<String>(), StopGrouping.warnedStopsOf(rows))
    }

    @Test
    fun `every platform's view keeps a suspended line beside a platformless line of untimed trains`() {
        // A suspended line with no predictions, and another line whose only train is canceled and names
        // no platform: neither has a platform to show under, so they group together.
        val canceled = UntimedTrain(
            Departure("great-example", "Great Example", "", "Far", null, now.plusSeconds(120), "national-rail"),
            canceled = true,
        )
        val timed = Departure("other-example", "Other Example", "", "Near", "Platform 3", now.plusSeconds(300), "national-rail")
        val stop = StopArrivals(
            "910GEXAMPLE", "Example", listOf(timed), fetchedAt = now,
            lines = listOf(LineRef("suspended-example", "Suspended Example", "national-rail")),
            untimed = listOf(canceled),
        )
        val suspended = LineStatus("suspended-example", 2, "Suspended")
        val groups = StopGrouping.groupByStop(DepartureRows.across(listOf(stop), now, mapOf("suspended-example" to suspended)))
        val unplaced = StopGrouping.unplacedStatusRows(groups)
        assertEquals(listOf("suspended-example"), unplaced.map { it.lineId })
        assertTrue(unplaced.single().isStatusOnly)
    }

    @Test
    fun `a direction served by two platforms heads each platform separately`() {
        // Camden Town: one southbound TfL direction from Platforms 2 and 4, northbound from 1 and 3.
        fun northern(direction: String, destination: String, platform: String, offset: Long) =
            Departure("northern", "Northern", direction, destination, platform, now.plusSeconds(offset), "tube")
        val stop = StopArrivals(
            stopId = "940GZZLUCTN",
            stopName = "Camden Town",
            departures = listOf(
                northern("inbound", "Morden", "Southbound - Platform 2", 60),
                northern("inbound", "Morden", "Southbound - Platform 4", 90),
                northern("outbound", "Edgware", "Northbound - Platform 1", 120),
                northern("outbound", "High Barnet", "Northbound - Platform 3", 150),
            ),
            fetchedAt = now,
        )

        val groups = StopGrouping.groupByStop(DepartureRows.across(listOf(stop), now))

        assertEquals(
            setOf(
                StopQualifier.Platform("1", "Northbound"),
                StopQualifier.Platform("2", "Southbound"),
                StopQualifier.Platform("3", "Northbound"),
                StopQualifier.Platform("4", "Southbound"),
            ),
            groups.mapTo(HashSet()) { it.qualifier },
        )
    }

    @Test
    fun `two stops group separately and both show a header`() {
        val rows = listOf(
            row("A", "Archway", destination = "Morden"),
            row("B", "Brixton", destination = "Walthamstow"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("Archway", "Brixton"), groups.map { it.stopName })
        assertTrue(groups.all { it.showHeader })
    }

    @Test
    fun `a station's several directions merge into one group in first-appearance order`() {
        val rows = listOf(
            row("OXC", "Oxford Circus", lineId = "central", destination = "Hainault"),
            row("KSX", "King's Cross", lineId = "victoria", destination = "Brixton"),
            row("OXC", "Oxford Circus", lineId = "bakerloo", destination = "Harrow"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // One group per stop, however many directions the station serves.
        assertEquals(listOf("Oxford Circus", "King's Cross"), groups.map { it.stopName })
        assertEquals(3, groups[0].rows.size + groups[1].rows.size)
    }

    @Test
    fun `two poles sharing a cluster merge under one header`() {
        // The junction case: a northbound and a southbound pole are separate TfL stop ids that
        // share a cluster (TfL's stationNaptan), so they read as one place under a single header —
        // the way a Tube station's platforms already did (SPEC *Finding stops*). A third, distinct
        // place makes the merged place's own header show (a lone place would imply itself).
        val rows = listOf(
            row("P1", "Turnpike Lane", clusterId = "490G0TPL", direction = "inbound", destination = "Central"),
            row("P2", "Turnpike Lane", clusterId = "490G0TPL", direction = "outbound", destination = "North"),
            row("A", "Archway", clusterId = "490G0ARW", destination = "Morden"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("Turnpike Lane", "Archway"), groups.map { it.stopName })
        // The merged place carries both poles' rows, from both stop ids, under one group.
        val cluster = groups.first()
        assertEquals(2, cluster.rows.size)
        assertEquals(setOf("P1", "P2"), cluster.rows.mapTo(mutableSetOf()) { it.stopId })
        assertTrue(groups.all { it.showHeader })
    }

    @Test
    fun `two same-named stops in different clusters stay apart`() {
        // The reason the key is the cluster, not the name: TfL spells one station several ways and
        // gives adjacent stations similar names, so two stops that read alike but are different
        // places (different stationNaptan) must not merge (maintainer, 2026-09-21).
        val rows = listOf(
            row("K1", "King's Cross Station", clusterId = "940GZZLUKSX", destination = "Morden"),
            row("S1", "King's Cross Station", clusterId = "490G000760", destination = "Barnet"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(2, groups.size)
        assertEquals(setOf("K1"), groups[0].rows.mapTo(mutableSetOf()) { it.stopId })
        assertEquals(setOf("S1"), groups[1].rows.mapTo(mutableSetOf()) { it.stopId })
    }

    @Test
    fun `a pole with a suspended-line warning stays its own group`() {
        // Same class as the closure case, one line down: a line-status "No departures" row (a
        // suspended line, no predictions) also names no pole or direction, so merging it beside a
        // same-named pole's live same-line departures would read as "141 suspended" next to live
        // 141 times with nothing to say which pole (SPEC principle 2). Any warning row (no
        // countdown) keeps its stop's group its own.
        val rows = listOf(
            row("P1", "Turnpike Lane", clusterId = "490G0TPL", lineId = "141", upcoming = emptyList()),
            row("P2", "Turnpike Lane", clusterId = "490G0TPL", lineId = "141", direction = "outbound", destination = "Palmers Green"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // Same cluster, so they would merge — but the warned pole is carved out to its own group.
        assertEquals(2, groups.size)
        // The warned pole leads (no countdown), on its own; the running pole is a separate group.
        assertEquals(setOf("P1"), groups.first().rows.mapTo(mutableSetOf()) { it.stopId })
        assertTrue(groups.first().rows.single().upcoming.isEmpty())
        assertEquals(setOf("P2"), groups.last().rows.mapTo(mutableSetOf()) { it.stopId })
    }

    @Test
    fun `a station splits into one header per platform`() {
        // King's Cross: a rail platform ("Northbound - Platform 1") splits a station's cards into one
        // header per platform — the physical platform a rider stands on — so a busy interchange isn't
        // a wall of cards under one bare name (SPEC D8). Public line names/termini only (Privacy).
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "outbound", destination = "Walthamstow Central", platform = "Northbound - Platform 1"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "piccadilly", direction = "outbound", destination = "Cockfosters", platform = "Eastbound - Platform 3"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "inbound", destination = "Brixton", platform = "Southbound - Platform 2"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // One group per platform, in first-appearance (soonest-first) order, each with its compass.
        assertEquals(listOf("1", "3", "2"), groups.map { it.platform })
        assertEquals(listOf("Northbound", "Eastbound", "Southbound"), groups.map { it.platformDir })
        assertTrue(groups.all { it.stopName == "King's Cross" && it.showHeader })
    }

    @Test
    fun `a station's platform blocks stay adjacent when another place interleaves by time`() {
        // SPEC D8: a place's cards stay adjacent. A station split into platform blocks must not have
        // a nearer bus stop's block wedged between its platforms just because that bus leaves sooner
        // than the station's second platform. Rows arrive soonest-first (KX P1, bus, KX P3); the
        // station's two blocks must still come out adjacent, the bus after both.
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "outbound", destination = "Walthamstow Central", platform = "Northbound - Platform 1"),
            row("BUS", "York Way", clusterId = "490G0YRK", lineId = "390", direction = "outbound", destination = "Archway", platform = "", mode = "bus"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "piccadilly", direction = "outbound", destination = "Cockfosters", platform = "Eastbound - Platform 3"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(
            listOf("King's Cross" to "1", "King's Cross" to "3", "York Way" to null),
            groups.map { it.stopName to it.platform },
        )
    }

    @Test
    fun `a cluster's platform groups share one canonical place name`() {
        // Members of one cluster can carry different cleaned names (the reason to group by
        // stationNaptan, not by name). Every platform group of the place must show the same name,
        // never "King's Cross" beside "King's Cross St. Pancras" (Codex P2, PR #109). First name wins.
        val rows = listOf(
            row("A", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "outbound", destination = "Walthamstow Central", platform = "Northbound - Platform 1"),
            row("B", "King's Cross St. Pancras", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "inbound", destination = "Brixton", platform = "Southbound - Platform 2"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("1", "2"), groups.map { it.platform })
        assertEquals(listOf("King's Cross", "King's Cross"), groups.map { it.stopName })
    }

    @Test
    fun `one platform's lines group together whatever TfL's inbound-outbound`() {
        // At King's Cross TfL tags the same Eastbound Platform 2 `inbound` for the Circle and
        // `outbound` for the Hammersmith & City (confirmed against live data, 2026-09-22). The
        // platform is the key, so one physical platform's trains are one group regardless.
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "circle", direction = "inbound", destination = "Edgware Road", platform = "Eastbound - Platform 2"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "hammersmith-city", direction = "outbound", destination = "Barking", platform = "Eastbound - Platform 2"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(1, groups.size)
        assertEquals("2", groups.single().platform)
        assertEquals("Eastbound", groups.single().platformDir)
        assertEquals(setOf("circle", "hammersmith-city"), groups.single().rows.mapTo(mutableSetOf()) { it.lineId })
        assertTrue(groups.single().showHeader)
    }

    @Test
    fun `a row resolves its platform from a later prediction when the soonest has none`() {
        // A row is one (line, direction); TfL can leave the platform blank on the soonest prediction
        // while a later one carries it. The row must still resolve to its platform header, not drop
        // to the bare header until the blank prediction departs (Codex P2, PR #109).
        val rows = listOf(
            row(
                "KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria",
                direction = "outbound", destination = "Walthamstow Central",
                upcoming = listOf(
                    dep("Walthamstow Central", platform = ""),
                    dep("Walthamstow Central", platform = "Northbound - Platform 1"),
                ),
            ),
        )
        val group = StopGrouping.groupByStop(rows).single()
        assertEquals("1", group.platform)
        assertEquals("Northbound", group.platformDir)
    }

    @Test
    fun `mixed-case compass canonicalizes on the platform direction`() {
        // TfL supplying "Northbound" and "NORTHBOUND" must not read as two different directions — the
        // canonical casing keeps the platform-direction label one spelling (Codex P2, PR #109). The
        // two platforms are still their own groups (different numbers).
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "outbound", destination = "Walthamstow Central", platform = "Northbound - Platform 1"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "northern", direction = "outbound", destination = "High Barnet", platform = "NORTHBOUND - Platform 5"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("1", "5"), groups.map { it.platform })
        assertEquals(listOf("Northbound", "Northbound"), groups.map { it.platformDir })
    }

    @Test
    fun `two platforms of one direction split into two groups`() {
        // Eastbound spans Platform 2 (sub-surface lines) and Platform 6 (Piccadilly) at King's Cross.
        // The platform is the key, so these split into two groups — the physical platforms a rider
        // must choose between, which a compass-only split would have conflated (maintainer, 2026-09-22).
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "circle", direction = "inbound", destination = "Edgware Road", platform = "Eastbound - Platform 2"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "piccadilly", direction = "outbound", destination = "Cockfosters", platform = "Eastbound - Platform 6"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("2", "6"), groups.map { it.platform })
        assertTrue(groups.all { it.platformDir == "Eastbound" })
    }

    @Test
    fun `a rail direction with no platform number falls to a bare compass`() {
        // A rare platform name carries a compass but no number ("Northbound"). With no platform to
        // split on, the group keys on the bare compass instead.
        val group = StopGrouping.groupByStop(
            listOf(row("X", "Somewhere", clusterId = "940X", destination = "End", platform = "Northbound")),
        ).single()
        assertNull(group.platform)
        assertEquals("Northbound", group.compass)
    }

    @Test
    fun `a row whose predictions span two platforms falls to the compass, not a wrong platform`() {
        // One (line, direction) row can carry departures from more than one platform — a terminus, a
        // platform change — and the card merges their countdowns. Claiming "Platform 1" would file the
        // Platform 2 train under the wrong platform (SPEC principle 1), so the row falls back to the
        // compass the two platforms share rather than pick the first (Codex P1, PR #119).
        val group = StopGrouping.groupByStop(
            listOf(
                row(
                    "EUS", "Euston", clusterId = "940GZZLUEUS", direction = "outbound", destination = "Watford",
                    upcoming = listOf(
                        dep("Watford", "Northbound - Platform 1"),
                        dep("Watford", "Northbound - Platform 2"),
                    ),
                ),
            ),
        ).single()
        assertNull(group.platform)
        assertEquals("Northbound", group.compass)
    }

    @Test
    fun `a platform's direction is taken from a later prediction when the first omits it`() {
        // TfL can leave the compass off the soonest prediction ("Platform 2") while a later one on the
        // same platform carries it ("Eastbound - Platform 2"); the sub-header keeps the direction cue
        // rather than drop it until the bare prediction expires (Codex P2, PR #119).
        val group = StopGrouping.groupByStop(
            listOf(
                row(
                    "KSX", "King's Cross", clusterId = "940GZZLUKSX", direction = "inbound", destination = "Edgware Road",
                    upcoming = listOf(
                        dep("Edgware Road", "Platform 2"),
                        dep("Edgware Road", "Eastbound - Platform 2"),
                    ),
                ),
            ),
        ).single()
        assertEquals("2", group.platform)
        assertEquals("Eastbound", group.platformDir)
    }

    @Test
    fun `a platform group takes its direction from any line row that names one`() {
        // Two lines share Platform 2, but only one line's predictions carry the compass ("Eastbound -
        // Platform 2") while the other's are bare ("Platform 2"). The platform group's direction is a
        // consensus across every row, so it reads "Eastbound" regardless of which row sorts first
        // (Codex P2, PR #119).
        val bareFirst = StopGrouping.groupByStop(
            listOf(
                row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "circle", direction = "inbound", destination = "Edgware Road", platform = "Platform 2"),
                row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "metropolitan", direction = "outbound", destination = "Aldgate", platform = "Eastbound - Platform 2"),
            ),
        ).single()
        assertEquals("2", bareFirst.platform)
        assertEquals("Eastbound", bareFirst.platformDir)
        // Order-independent: the naming row first gives the same result.
        val namedFirst = StopGrouping.groupByStop(
            listOf(
                row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "metropolitan", direction = "outbound", destination = "Aldgate", platform = "Eastbound - Platform 2"),
                row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "circle", direction = "inbound", destination = "Edgware Road", platform = "Platform 2"),
            ),
        ).single()
        assertEquals("Eastbound", namedFirst.platformDir)
    }

    @Test
    fun `a row with one platform but a conflicting direction claims no platform at all`() {
        // The row has one numbered platform ("Eastbound - Platform 2") plus a direction-only
        // "Westbound" (no platform number). The Westbound isn't known to be at Platform 2, and its
        // direction contradicts, so the row can't claim the platform for it: it falls back to a bare,
        // unqualified group rather than file the Westbound departure under "Platform 2" (SPEC
        // principle 1; Codex P1, PR #119).
        val group = StopGrouping.groupByStop(
            listOf(
                row(
                    "KSX", "King's Cross", clusterId = "940GZZLUKSX", direction = "inbound", destination = "End",
                    upcoming = listOf(
                        dep("End", "Eastbound - Platform 2"),
                        dep("End", "Westbound"),
                    ),
                ),
            ),
        ).single()
        assertNull(group.platform)
        assertNull(group.compass)
    }

    @Test
    fun `a row whose predictions carry different compasses stays unqualified`() {
        // The platforms AND the compasses diverge (a platform change across directions). Neither a
        // platform nor a compass can be claimed for the merged card, so the row stays the bare place
        // header rather than file the second departure under the first's direction (Codex P1, PR #119).
        val group = StopGrouping.groupByStop(
            listOf(
                row(
                    "X", "Somewhere", clusterId = "940X", direction = "outbound", destination = "End",
                    upcoming = listOf(
                        dep("End", "Northbound - Platform 1"),
                        dep("End", "Southbound - Platform 2"),
                    ),
                ),
            ),
        ).single()
        assertNull(group.platform)
        assertNull(group.compass)
    }

    @Test
    fun `a bus whose arrivals platform reads like a rail one still splits on its stop letter`() {
        // A bus prediction can carry a stop-local `platform` that looks like a rail platform
        // ("Platform 1"); the bus must still read its pole letter, not be filed under "Platform 1"
        // (Codex P1, PR #119).
        val group = StopGrouping.groupByStop(
            listOf(
                row(
                    "490D", "King's Cross Station", clusterId = "490G00247", mode = "bus", stopLetter = "D",
                    upcoming = listOf(dep("Farringdon", "Platform 1", mode = "bus")),
                ),
            ),
        ).single()
        assertNull(group.platform)
        assertEquals("D", group.busLetter)
    }

    @Test
    fun `a bus pole with no platform or letter keeps the bare place header`() {
        // A bus arrival carries no platform and, without the near-me letter, no stop letter, so it
        // resolves no split. With two diverging routes it stays the bare per-place header. Public
        // route/place names only (SPEC *Privacy*).
        val rows = listOf(
            row("P1", "Cranley Gardens", clusterId = "490G00005712", lineId = "43", direction = "inbound", destination = "London Bridge", platform = "", mode = "bus"),
            row("P1", "Cranley Gardens", clusterId = "490G00005712", lineId = "134", direction = "inbound", destination = "North Finchley", platform = "", mode = "bus"),
        )
        val cranley = StopGrouping.groupByStop(rows).first { it.stopName == "Cranley Gardens" }
        assertNull(cranley.platform)
        assertNull(cranley.qualifier)
    }

    @Test
    fun `a bus stop-letter platform value is not read as a rail platform`() {
        // A bus prediction can carry a stop-local `platform` (the stop letter). It must not be read
        // as a rail "Platform N", or one cluster's poles would split on it; without a captured stop
        // letter they merge under one bare header (Codex P2, PR #109).
        val rows = listOf(
            row("PA", "Turnpike Lane", clusterId = "490G0TPL", lineId = "141", direction = "inbound", destination = "London Bridge", platform = "Stop A", mode = "bus"),
            row("PB", "Turnpike Lane", clusterId = "490G0TPL", lineId = "141", direction = "outbound", destination = "London Bridge", platform = "Stop B", mode = "bus"),
        )
        val group = StopGrouping.groupByStop(rows).single()
        assertNull(group.platform)
        assertEquals(setOf("PA", "PB"), group.rows.mapTo(mutableSetOf()) { it.stopId })
    }

    @Test
    fun `a suspended line at a station does not collapse the live platform groups`() {
        // One stop id with a suspended declared line (a directionless "No departures" status row)
        // plus live rows carrying platforms: the live rows must still split by platform, not all
        // collapse to one bare group because the stop is "warned" (Codex P2, PR #109).
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "circle", destination = "", upcoming = emptyList()),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "outbound", destination = "Walthamstow Central", platform = "Northbound - Platform 1"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "inbound", destination = "Brixton", platform = "Southbound - Platform 2"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // The live rows keep their platform groups.
        assertEquals(setOf("1", "2"), groups.mapNotNull { it.platform }.toSet())
        // The suspended line is its own directionless group (a warning), not merged into a block.
        assertTrue(groups.any { it.qualifier == null && it.rows.all { r -> r.upcoming.isEmpty() } })
    }

    @Test
    fun `a single stop hides its header — the stop is implied`() {
        val rows = listOf(row("KSX", "King's Cross", destination = "Brixton"))
        val group = StopGrouping.groupByStop(rows).single()
        assertFalse(group.showHeader)
    }

    @Test
    fun `a closure-only stop counts as a place, so a lone departures stop keeps its header`() {
        // Codex P1 (PR #91): on a location-free list, one stop's arrivals failed leaving only a
        // closure (rendered header-less, outside grouping) while another stop has departures. The
        // closure's stop is still a place, so the departures stop must show its name header rather
        // than read as a single-place list and drop it.
        val rows = listOf(
            row("A", "Archway", destination = "", stopDisruption = "Closed", upcoming = emptyList()),
            row("B", "Brixton", destination = "Morden"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // Only the departures stop is grouped; the closure forms no group.
        assertEquals(listOf("Brixton"), groups.map { it.stopName })
        // It still shows its header, because Archway (the closure) is a second place on screen.
        assertTrue(groups.single().showHeader)
    }

    @Test
    fun `a lone stop with both a closure and departures still implies its place`() {
        // The single-place case is preserved: one stop carries both a closure (header-less) and its
        // own departures. One place, so the departures header stays hidden.
        val rows = listOf(
            row("A", "Archway", destination = "", stopDisruption = "Closed", upcoming = emptyList()),
            row("A", "Archway", destination = "Morden"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("Archway"), groups.map { it.stopName })
        assertFalse(groups.single().showHeader)
    }

    @Test
    fun `a stop with a warning leads an ordinary-only stop`() {
        // Grouping must not rely on the caller pre-sorting warnings first: even with the ordinary
        // stop's row first in the input, the warned stop's group leads. Stop closures render
        // outside grouping now, so a line-status "No departures" warning stands for the class here.
        val rows = listOf(
            row("A", "Archway", destination = "Morden"),
            row("B", "Brixton", lineId = "vic", upcoming = emptyList()),
        )
        assertEquals(listOf("Brixton", "Archway"), StopGrouping.groupByStop(rows).map { it.stopName })
    }

    @Test
    fun `with warningsLead false a warned place does not lead - it keeps first-appearance order`() {
        // The near-me path orders rows by distance and passes warningsLead=false, so a place is NOT
        // lifted for carrying a line-status alert — it stays in first-appearance (distance) order, so
        // a nearer stop is never pushed below a farther one just for having an alert (maintainer,
        // 2026-09-22). Here Archway (nearer, ordered first) leads the warned Brixton behind it.
        val rows = listOf(
            row("A", "Archway", destination = "Morden"),
            row("B", "Brixton", lineId = "vic", upcoming = emptyList()),
        )
        val groups = StopGrouping.groupByStop(rows, warningsLead = false)
        assertEquals(listOf("Archway", "Brixton"), groups.map { it.stopName })
    }

    @Test
    fun `each stop's rows stay contiguous even when two stops carry warnings`() {
        // Two warned stops: each stays one block led by its warning (option B). The cost the
        // maintainer accepted is that stop A's ordinary rows can sit above stop B's warning,
        // rather than a single global warning band across stops. (Line-status warnings — closures
        // no longer group here.)
        val rows = listOf(
            row("A", "Archway", lineId = "vic", upcoming = emptyList()),
            row("A", "Archway", destination = "Morden"),
            row("B", "Brixton", lineId = "vic", upcoming = emptyList()),
            row("B", "Brixton", destination = "Walthamstow"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("Archway", "Brixton"), groups.map { it.stopName })
        // Each group's own warning row (no countdown) leads its own rows.
        assertTrue(groups.all { it.rows.first().upcoming.isEmpty() })
    }

    @Test
    fun `a bus place splits into one group per stop letter`() {
        // The bus analog of the rail compass: poles of one bus place (same display-name cluster) each
        // carry a stop letter, so the place splits into one header per pole — "(D)", "(E)" — instead
        // of a wall of cards under one bare name (SPEC D8).
        val rows = listOf(
            row("BD", "King's Cross Station", lineId = "17", destination = "Farringdon", mode = "bus", stopLetter = "D"),
            row("BE", "King's Cross Station", lineId = "30", destination = "Angel", mode = "bus", stopLetter = "E"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(2, groups.size)
        assertEquals(listOf("D", "E"), groups.map { it.busLetter })
        // Same place, so both headers carry the one place name; each its own pole's rows.
        assertTrue(groups.all { it.stopName == "King's Cross Station" && it.showHeader })
        assertEquals(setOf("BD"), groups[0].rows.mapTo(mutableSetOf()) { it.stopId })
    }

    @Test
    fun `a bus pole with no letter falls back to its compass bearing`() {
        val rows = listOf(
            row("B1", "Some Road", lineId = "24", destination = "Pimlico", mode = "bus", bearing = "SW"),
            row("B2", "Some Road", lineId = "88", destination = "Camden", mode = "bus", bearing = "NE"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("SW", "NE"), groups.map { it.busBearing })
    }

    @Test
    fun `the stop letter wins over the bearing when a pole has both`() {
        val group = StopGrouping.groupByStop(
            listOf(row("B", "Some Road", mode = "bus", stopLetter = "A", bearing = "N")),
        ).single()
        assertEquals("A", group.busLetter)
        assertNull(group.busBearing)
    }

    @Test
    fun `the stop letter wins over the pole's towards`() {
        // A lettered pole labels on the letter (the primary pole cue); its "towards" rides along.
        val group = StopGrouping.groupByStop(
            listOf(row("B", "King's Cross Station", mode = "bus", stopLetter = "D", towards = "Farringdon", destination = "Bank")),
        ).single()
        assertEquals("D", group.busLetter)
        assertNull(group.towards)
    }

    @Test
    fun `a letter-less pole is headed by its towards, ahead of its bearing, whatever its buses' destinations`() {
        // The sign's "towards" heads the pole even when several routes serve it with different
        // destinations (the sign shows it all the same), and before its compass bearing.
        val rows = listOf(
            row("BP", "Turnpike Lane", lineId = "141", destination = "Bank", mode = "bus", towards = "King's Cross Or Euston", bearing = "S"),
            row("BP", "Turnpike Lane", lineId = "341", destination = "Waterloo", mode = "bus", towards = "King's Cross Or Euston", bearing = "S"),
        )
        val group = StopGrouping.groupByStop(rows).single()
        assertEquals("King's Cross Or Euston", group.towards)
        assertNull(group.busBearing)
        assertTrue(group.showHeader)
    }

    @Test
    fun `two letter-less poles whose signs read the same but face different ways stay apart`() {
        // Same cluster, same "towards", opposite bearings: two places to stand, so two groups, each
        // still headed by its towards (Codex P2, #492). Both directions: the same bearing still merges.
        val apart = StopGrouping.groupByStop(
            listOf(
                row("B1", "King's Cross Station", clusterId = "C", lineId = "73", mode = "bus", towards = "Euston", bearing = "W"),
                row("B2", "King's Cross Station", clusterId = "C", lineId = "390", mode = "bus", towards = "Euston", bearing = "E"),
            ),
        )
        assertEquals(2, apart.size)
        assertEquals(listOf("Euston", "Euston"), apart.map { it.towards })
        val together = StopGrouping.groupByStop(
            listOf(
                row("B1", "King's Cross Station", clusterId = "C", lineId = "73", mode = "bus", towards = "Euston", bearing = "W"),
                row("B2", "King's Cross Station", clusterId = "C", lineId = "390", mode = "bus", towards = "Euston", bearing = "W"),
            ),
        )
        assertEquals(1, together.size)
    }

    @Test
    fun `a letter-less pole without a towards falls back to its bearing`() {
        val group = StopGrouping.groupByStop(listOf(row("BP", "Turnpike Lane", mode = "bus", bearing = "S"))).single()
        assertEquals("S", group.busBearing)
        assertNull(group.towards)
    }

    @Test
    fun `a bus stop is never headed by one bus's destination`() {
        // Every route heading one way used to head the pole "➔ Bank": that names where those buses
        // go, not where the stop heads, so a pole with no letter, towards or bearing stays bare.
        val rows = listOf(
            row("BP", "Turnpike Lane", lineId = "141", destination = "Bank", mode = "bus"),
            row("BP", "Turnpike Lane", lineId = "341", destination = "Bank", mode = "bus"),
        )
        assertNull(StopGrouping.groupByStop(rows).single().qualifier)
    }

    @Test
    fun `a rail place with a platform takes no terminus`() {
        // Rail's cue is the platform (with its compass in parens); the terminus is the bus fallback,
        // so a rail platform group never carries one even when its trains share a destination.
        val rows = listOf(
            row("KSX", "King's Cross", platform = "Northbound - Platform 1", destination = "Walthamstow"),
            row("A", "Archway", destination = "Morden"),
        )
        val kings = StopGrouping.groupByStop(rows).first { it.stopName == "King's Cross" }
        assertEquals("1", kings.platform)
        assertEquals("Northbound", kings.platformDir)
        assertNull(kings.towards)
    }

    @Test
    fun `a compass-less non-bus place takes no terminus`() {
        // The terminus is bus-only for now (the qualifier chain is mode-aware): a bare-platform tube
        // group sharing one destination still shows the bare name, not "-> Terminus".
        val rows = listOf(
            row("X", "Some Depot", mode = "tube", destination = "Aldgate"),
            row("A", "Archway", mode = "tube", destination = "Morden"),
        )
        val depot = StopGrouping.groupByStop(rows).first { it.stopName == "Some Depot" }
        assertNull(depot.towards)
        assertNull(depot.compass)
    }
}
