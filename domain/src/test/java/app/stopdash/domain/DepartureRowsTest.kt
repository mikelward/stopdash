package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureRowsTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun departure(
        lineId: String,
        lineName: String,
        direction: String,
        destination: String,
        offsetSeconds: Long,
        platform: String? = null,
        mode: String = "tube",
        branch: String? = null,
    ) = Departure(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = mode,
        branch = branch,
    )

    private fun rowWith(vararg upcoming: Departure): DepartureRow {
        val soonest = upcoming.first()
        return DepartureRow(
            stopId = "940GZZLUEUS",
            stopName = "Euston",
            lineId = soonest.lineId,
            lineName = soonest.lineName,
            direction = soonest.direction,
            directionKey = soonest.direction,
            destination = soonest.destination,
            mode = soonest.mode,
            upcoming = upcoming.toList(),
            fetchedAt = now,
        )
    }

    // A National Rail train of the synthetic Great Example line, from a station's board.
    private fun rail(destination: String, offsetSeconds: Long, platform: String? = null) =
        departure("great-example", "Great Example", "", destination, offsetSeconds, platform, mode = "national-rail")

    @Test
    fun `a train with no time joins its line's row by platform, else by destination, else a row of its own, never as a departure`() {
        val timed = rail("Far", 300, "Platform 4")
        val unplatformed = rail("Farther", 600)
        val samePlatform = UntimedTrain(rail("Far", 120, "Platform 4"), canceled = true)
        // No platform yet: it joins the line's row that has a timed train to the same place.
        val noPlatform = UntimedTrain(rail("Far", 900), canceled = false)
        // Nowhere a timed train of its line goes: a row of its own.
        val elsewhere = UntimedTrain(rail("Nowhere", 200), canceled = true)
        val stop = StopArrivals(
            "910GEXAMPLE", "Example", listOf(timed, unplatformed), fetchedAt = now,
            untimed = listOf(noPlatform, samePlatform, elsewhere),
        )
        val rows = DepartureRows.across(listOf(stop), now)
        val far = rows.single { it.directionKey == "Platform 4" }
        assertEquals(listOf(timed), far.upcoming)
        // Soonest scheduled first.
        assertEquals(listOf(samePlatform, noPlatform), far.untimed)
        assertEquals(emptyList<UntimedTrain>(), rows.single { it.directionKey == "Farther" }.untimed)
        // Its own row, with trains (never a status row), after the timed rows since none is coming.
        val alone = rows.single { elsewhere in it.untimed }
        assertEquals(listOf(elsewhere), alone.untimed)
        assertEquals(emptyList<Departure>(), alone.upcoming)
        assertTrue(alone.hasTrains)
        assertFalse(alone.isStatusOnly)
        assertEquals(alone, rows.last())
        // Never among a row's departures, which everything that times a train reads.
        assertTrue(rows.all { row -> row.upcoming.none { d -> stop.untimed.any { it.train == d } } })
    }

    @Test
    fun `a train with no time named for another platform goes in that platform's row, never its destination's`() {
        val timed = rail("Far", 300, "Platform 4")
        val canceled = UntimedTrain(rail("Far", 120, "Platform 2"), canceled = true)
        val stop = StopArrivals("910GEXAMPLE", "Example", listOf(timed), fetchedAt = now, untimed = listOf(canceled))
        val rows = DepartureRows.across(listOf(stop), now)
        assertEquals(emptyList<UntimedTrain>(), rows.single { it.directionKey == "Platform 4" }.untimed)
        val platform2 = rows.single { canceled in it.untimed }
        assertEquals("Platform 2", platform2.directionKey)
        assertEquals("2", platform2.platform)

        // A direction split by platform: it joins its own platform's row, not the sooner one's.
        fun outbound(platform: String, offsetSeconds: Long) =
            departure("great-example", "Great Example", "outbound", "Far", offsetSeconds, platform, mode = "national-rail")
        val split = StopArrivals(
            "910GEXAMPLE", "Example", listOf(outbound("Platform 4", 300), outbound("Platform 2", 500)), fetchedAt = now,
            untimed = listOf(canceled),
        )
        val splitRows = DepartureRows.across(listOf(split), now)
        assertEquals(listOf(canceled), splitRows.single { it.platform == "2" }.untimed)
        assertEquals(emptyList<UntimedTrain>(), splitRows.single { it.platform == "4" }.untimed)

        // A timed train with no platform yet: the canceled one's named platform is its own row, not
        // hidden in the platformless one.
        val unplatformed = StopArrivals("910GEXAMPLE", "Example", listOf(rail("Far", 300)), fetchedAt = now, untimed = listOf(canceled))
        val unplatformedRows = DepartureRows.across(listOf(unplatformed), now)
        assertEquals(emptyList<UntimedTrain>(), unplatformedRows.single { it.upcoming.isNotEmpty() }.untimed)
        assertEquals("2", unplatformedRows.single { canceled in it.untimed }.platform)
        // Unsplit, a row takes it where one of its own trains is on that platform.
        val unsplit = DepartureRows.across(listOf(split), now, splitPlatforms = false)
        assertEquals(listOf(canceled), unsplit.single().untimed)
    }

    @Test
    fun `a canceled train is gone from its row at its scheduled time, a delayed one isn't`() {
        val timed = rail("Far", 600, "Platform 4")
        val canceled = UntimedTrain(rail("Far", -30, "Platform 4"), canceled = true)
        val delayed = UntimedTrain(rail("Far", -30, "Platform 4"), canceled = false)
        val stop = StopArrivals("910GEXAMPLE", "Example", listOf(timed), fetchedAt = now, untimed = listOf(canceled, delayed))
        assertEquals(listOf(delayed), DepartureRows.across(listOf(stop), now).single().untimed)
    }

    @Test
    fun `a train with no time ending where the rider is goes nowhere for them, as a timed one doesn't`() {
        val timed = rail("Far", 300, "Platform 4")
        val here = UntimedTrain(rail("Far", 120, "Platform 4").copy(destinationId = "910GHERE"), canceled = true)
        val stop = StopArrivals(
            "910GEXAMPLE", "Example", listOf(timed), fetchedAt = now,
            untimed = listOf(here),
            nearer = Terminating.Nearer(ids = setOf("910GHERE")),
        )
        assertEquals(emptyList<UntimedTrain>(), DepartureRows.across(listOf(stop), now).single().untimed)
    }

    @Test
    fun `a line whose every train has no time gets a row at its platform, carrying the line's status alone`() {
        val delayed = UntimedTrain(rail("Far", 300, "Platform 2"), canceled = false)
        val canceled = UntimedTrain(rail("Far", 120, "Platform 2"), canceled = true)
        val line = LineRef("great-example", "Great Example", "national-rail")
        val stop = StopArrivals("910GEXAMPLE", "Example", emptyList(), fetchedAt = now, lines = listOf(line), untimed = listOf(delayed, canceled))
        val disrupted = LineStatus("great-example", 6, "Severe Delays")
        // The line's status rides on its trains' row; no "No departures" row beside it says otherwise.
        val row = DepartureRows.across(listOf(stop), now, mapOf("great-example" to disrupted)).single()
        assertEquals("Platform 2", row.directionKey)
        assertEquals(listOf(canceled, delayed), row.untimed)
        assertEquals(emptyList<Departure>(), row.upcoming)
        assertEquals(disrupted, row.status)
        assertFalse(row.isStatusOnly)
        // Dismissing the alert leaves the trains.
        val kept = DepartureRows.withoutDismissed(listOf(row), setOf(DismissedAlert.ofLineStatus(disrupted))).single()
        assertNull(kept.status)
        assertEquals(listOf(canceled, delayed), kept.untimed)
    }

    @Test
    fun `nearbyDeduped never lets a row whose every train has no time stand in for a farther stop's trains`() {
        fun outbound(offsetSeconds: Long) =
            departure("great-example", "Great Example", "outbound", "Far", offsetSeconds, mode = "national-rail")
        val rows = DepartureRows.across(
            listOf(
                StopArrivals("A", "Stop A", emptyList(), fetchedAt = now, untimed = listOf(UntimedTrain(outbound(120), canceled = true))),
                StopArrivals("B", "Stop B", listOf(outbound(300)), fetchedAt = now),
            ),
            now,
        )
        val deduped = DepartureRows.nearbyDeduped(rows, mapOf("A" to 100.0, "B" to 300.0))
        // The nearer stop's canceled train and the farther one's coming train both stay.
        assertEquals(listOf("A", "B"), deduped.map { it.stopId }.sorted())
    }

    @Test
    fun `byStopDistance puts a stop's rows whose every train has no time after its coming trains, by schedule`() {
        fun train(lineId: String, platform: String, offsetSeconds: Long) =
            departure(lineId, lineId, "", "Far", offsetSeconds, platform, mode = "national-rail")
        val rows = DepartureRows.across(
            listOf(
                StopArrivals(
                    "A", "Stop A", listOf(train("c-line", "Platform 3", 900)), fetchedAt = now,
                    // Line names in the opposite order to their schedules, so neither breaks the tie.
                    untimed = listOf(
                        UntimedTrain(train("a-line", "Platform 1", 600), canceled = true),
                        UntimedTrain(train("b-line", "Platform 2", 120), canceled = false),
                    ),
                ),
            ),
            now,
        )
        // Fed in reverse, so the order comes from the sort, not from [DepartureRows.across].
        val ordered = DepartureRows.byStopDistance(rows.reversed(), mapOf("A" to 100.0))
        assertEquals(listOf("c-line", "b-line", "a-line"), ordered.map { it.lineId })
    }

    @Test
    fun `parts of one row joined again keep every part's trains, timed and with no time`() {
        val base = rowWith(rail("Far", 300, "Platform 4"))
        val toNear = UntimedTrain(rail("Near", 120, "Platform 4"), canceled = true)
        val toFar = UntimedTrain(rail("Far", 600, "Platform 4"), canceled = false)
        // A journey card's direct train with its own canceled one, and a train to change from with its.
        val direct = base.copy(untimed = listOf(toFar))
        val change = base.copy(upcoming = listOf(rail("Near", 400, "Platform 4")), untimed = listOf(toNear))
        val joined = DepartureRows.joined(listOf(direct, change))
        assertEquals(listOf(now.plusSeconds(300), now.plusSeconds(400)), joined.upcoming.map { it.expectedArrival })
        assertEquals(listOf(toNear, toFar), joined.untimed)
        assertEquals("Far", joined.destination)
    }

    @Test
    fun `a row the card above shows without some of its trains with no time stays in the list`() {
        val row = rowWith(rail("Far", 300)).copy(untimed = listOf(UntimedTrain(rail("Elsewhere", 120), canceled = true)))
        // The card's copy keeps the timed train but not the one to Elsewhere, a line of its own here.
        assertEquals(listOf(row), DepartureRows.withoutShownAbove(listOf(row), listOf(row.copy(untimed = emptyList()))))
        assertEquals(emptyList<DepartureRow>(), DepartureRows.withoutShownAbove(listOf(row), listOf(row)))
    }

    @Test
    fun `a destination line puts its trains with no time among its times, capped together`() {
        val row = rowWith(rail("Far", 300), rail("Far", 600), rail("Far", 900)).copy(
            untimed = listOf(
                UntimedTrain(rail("Far", 120), canceled = true),
                UntimedTrain(rail("Far", 450), canceled = false),
                UntimedTrain(rail("Elsewhere", 200), canceled = true),
            ),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        val line = lines.first()
        // The canceled one before the first time and the delayed one after it fill the cap of three, so
        // the later times are past it.
        assertEquals(listOf(now.plusSeconds(300)), line.times.map { it.expectedArrival })
        assertEquals(listOf(true, false), line.untimed.map { it.canceled })
        assertTrue(line.untimed.none { it.train.destination == "Elsewhere" })
        // A destination no timed train goes to is a line of its own, after the timed one though it was
        // scheduled sooner: none of its trains is coming.
        assertEquals(listOf("Far", "Elsewhere"), lines.map { it.destination })
        assertEquals(emptyList<Departure>(), lines[1].times)
        assertEquals(listOf(true), lines[1].untimed.map { it.canceled })
    }

    @Test
    fun `destinationLines yields one group for a non-branching row`() {
        val row = rowWith(
            departure("victoria", "Victoria", "outbound", "Brixton", 120),
            departure("victoria", "Victoria", "outbound", "Brixton", 360),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        assertEquals(1, lines.size)
        assertEquals("Brixton", lines[0].destination)
        assertNull(lines[0].branch)
        assertEquals(2, lines[0].times.size)
    }

    @Test
    fun `destinationLines splits a branching direction, soonest group first`() {
        // Same line and direction, two destinations — the soonest (Brixton) leads, the
        // divergent one (Walthamstow) keeps its own group and its own times (SPEC D8).
        val row = rowWith(
            departure("victoria", "Victoria", "outbound", "Brixton", 120),
            departure("victoria", "Victoria", "outbound", "Walthamstow Central", 300),
            departure("victoria", "Victoria", "outbound", "Brixton", 480),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        assertEquals(listOf("Brixton", "Walthamstow Central"), lines.map { it.destination })
        assertEquals(2, lines[0].times.size)
        assertEquals(1, lines[1].times.size)
    }

    @Test
    fun `destinationLines splits one terminus reached via two branches`() {
        // The P1 case: same terminus, different trunks. Each branch is its own group with its
        // own countdown, so a divergent train's time never sits under the wrong branch.
        val row = rowWith(
            departure("northern", "Northern", "northbound", "Edgware", 120, branch = "Bank"),
            departure("northern", "Northern", "northbound", "Edgware", 540, branch = "Charing Cross"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        assertEquals(2, lines.size)
        assertEquals(listOf("Bank", "Charing Cross"), lines.map { it.branch })
        lines.forEach { assertEquals("Edgware", it.destination) }
    }

    @Test
    fun `destinationLines caps the countdowns within each group`() {
        val many = (1..8).map { departure("victoria", "Victoria", "outbound", "Brixton", it * 60L) }
        val lines = DepartureRows.destinationLines(rowWith(*many.toTypedArray()), maxTimes = 3)
        assertEquals(1, lines.size)
        assertEquals(3, lines[0].times.size)
    }

    @Test
    fun `destinationLines keeps a divergent destination whose soonest train is past the cap`() {
        // Three imminent Morden trains fill the first maxTimes, then a Battersea train. Capping
        // the flat list before grouping would drop Battersea's line entirely; grouping first
        // keeps it, with its own (single) countdown (SPEC D8).
        val row = rowWith(
            departure("northern", "Northern", "southbound", "Morden", 60),
            departure("northern", "Northern", "southbound", "Morden", 120),
            departure("northern", "Northern", "southbound", "Morden", 180),
            departure("northern", "Northern", "southbound", "Battersea Power Station", 240),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        assertEquals(listOf("Morden", "Battersea Power Station"), lines.map { it.destination })
        assertEquals(3, lines[0].times.size)
        assertEquals(1, lines[1].times.size)
        assertEquals(240L, lines[1].times.single().expectedArrival.epochSecond - now.epochSecond)
    }

    @Test
    fun `a two-way line at a stop becomes one row per direction`() {
        val southbound1 = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val southbound2 = departure("victoria", "Victoria", "outbound", "Brixton", 360)
        val northbound = departure("victoria", "Victoria", "inbound", "Walthamstow Central", 240)

        val rows = DepartureRows.forStop(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = listOf(southbound1, northbound, southbound2),
            now = now,
        )

        assertEquals(2, rows.size)
        // Ordered by soonest departure: southbound (120s) before northbound (240s).
        assertEquals("outbound", rows[0].direction)
        assertEquals("Brixton", rows[0].destination)
        assertEquals(listOf(southbound1, southbound2), rows[0].upcoming)
        assertEquals("inbound", rows[1].direction)
        assertEquals("Walthamstow Central", rows[1].destination)
        assertEquals(listOf(northbound), rows[1].upcoming)
    }

    @Test
    fun `carries the caller's stop identity onto every row`() {
        val rows = DepartureRows.forStop(
            stopId = "490000123A",
            stopName = "High Street",
            departures = listOf(departure("24", "24", "", "Pimlico", 90)),
            now = now,
        )

        assertEquals(1, rows.size)
        assertEquals("490000123A", rows[0].stopId)
        assertEquals("High Street", rows[0].stopName)
        assertEquals("", rows[0].direction)
    }

    @Test
    fun `drops departed services and omits a group with nothing upcoming`() {
        val gone = departure("bakerloo", "Bakerloo", "inbound", "Harrow & Wealdstone", -30)
        val liveSoon = departure("central", "Central", "outbound", "Ealing Broadway", 60)
        val liveLater = departure("central", "Central", "outbound", "Ealing Broadway", 300)

        val rows = DepartureRows.forStop(
            stopId = "940GZZLUOXC",
            stopName = "Oxford Circus",
            departures = listOf(gone, liveLater, liveSoon),
            now = now,
        )

        // The Bakerloo group had only a departed service, so it yields no row.
        assertEquals(1, rows.size)
        assertEquals("central", rows[0].lineId)
        assertEquals(listOf(liveSoon, liveLater), rows[0].upcoming)
    }

    @Test
    fun `headline destination is the soonest departure's, even when a direction branches`() {
        // A branching line: same (line, direction), different destinations.
        val soonerBranch = departure("london-overground", "Mildmay line", "outbound", "Richmond", 180)
        val laterBranch = departure("london-overground", "Mildmay line", "outbound", "Clapham Junction", 300)

        val rows = DepartureRows.forStop(
            stopId = "910GWLWICH",
            stopName = "Willesden Junction",
            departures = listOf(laterBranch, soonerBranch),
            now = now,
        )

        assertEquals(1, rows.size)
        assertEquals("Richmond", rows[0].destination)
        assertEquals(listOf(soonerBranch, laterBranch), rows[0].upcoming)
    }

    @Test
    fun `rows are ordered by soonest departure, ties broken by line then direction`() {
        val busSoon = departure("29", "29", "", "Trafalgar Square", 120)
        val tubeOutbound = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val tubeInbound = departure("victoria", "Victoria", "inbound", "Walthamstow Central", 120)

        val rows = DepartureRows.forStop(
            stopId = "seed",
            stopName = "Seed",
            departures = listOf(tubeOutbound, busSoon, tubeInbound),
            now = now,
        )

        // All three share 120s, so the tie-break decides: line "29" < "Victoria",
        // then Victoria's "inbound" < "outbound".
        assertEquals(listOf("29", "Victoria", "Victoria"), rows.map { it.lineName })
        assertEquals(listOf("", "inbound", "outbound"), rows.map { it.direction })
    }

    @Test
    fun `blank direction does not merge opposite directions, falling back to platform`() {
        // TfL omitted direction, but the platform names it — the two must stay apart.
        val north = departure("northern", "Northern", "", "High Barnet", 120, platform = "Northbound - Platform 1")
        val south = departure("northern", "Northern", "", "Morden", 240, platform = "Southbound - Platform 4")

        val rows = DepartureRows.forStop("940GZZLUEUS", "Euston", listOf(south, north), now)

        assertEquals(2, rows.size)
        assertEquals(listOf("High Barnet", "Morden"), rows.map { it.destination })
    }

    @Test
    fun `a blank direction takes its platform's one direction, so a service stays one row`() {
        // Kentish Town West, from the live feed: the Mildmay line's Platform 1 trains to Clapham
        // Junction came back both `outbound` and with no direction. The blank one belongs to the
        // same row, not a second "Clapham Junction" row keyed on its platform.
        val tagged = departure("mildmay", "Mildmay", "outbound", "Clapham Junction", 360, platform = "Platform 1", mode = "overground")
        val blank = departure("mildmay", "Mildmay", "", "Clapham Junction", 1800, platform = "Platform 1", mode = "overground")
        val richmond = departure("mildmay", "Mildmay", "outbound", "Richmond (London)", 60, platform = "Platform 1", mode = "overground")

        val rows = DepartureRows.forStop("910GKNTSHTW", "Kentish Town West", listOf(blank, tagged, richmond), now)

        assertEquals(1, rows.size)
        assertEquals("outbound", rows[0].direction)
        assertEquals(listOf(richmond, tagged, blank.copy(direction = "outbound")), rows[0].upcoming)
    }

    @Test
    fun `a blank direction takes its direction from a train the terminating filter hides`() {
        // The tagged train is hidden (synthetic: its terminus id is marked nearer the rider), but it
        // is still evidence for the same service's blank train: the kept one keys `outbound`, as a
        // star or pick on it expects.
        val hidden = departure("mildmay", "Mildmay", "outbound", "Clapham Junction", 60, platform = "Platform 1", mode = "overground")
            .copy(destinationId = "910GCMDNRD")
        val kept = departure("mildmay", "Mildmay", "", "Clapham Junction", 300, platform = "Platform 1", mode = "overground")
        val stop = StopArrivals(
            "910GKNTSHTW", "Kentish Town West", listOf(hidden, kept), fetchedAt = now,
            nearer = Terminating.Nearer(ids = setOf("910GCMDNRD")),
        )

        val row = DepartureRows.across(listOf(stop), now).single()

        assertEquals("Clapham Junction", row.destination)
        assertEquals("outbound", row.directionKey)
    }

    @Test
    fun `a blank direction matches its platform however TfL spells it`() {
        // "Westbound - Platform 1" and a bare "Platform 1" are the same platform: the blank train
        // joins the tagged one's row rather than keying on its own spelling.
        val tagged = departure("district", "District", "inbound", "Richmond", 60, platform = "Westbound - Platform 1")
        val blank = departure("district", "District", "", "Richmond", 300, platform = "Platform 1")

        val rows = DepartureRows.forStop("940GZZLUEXA", "Example", listOf(tagged, blank), now)

        assertEquals(1, rows.size)
        assertEquals("inbound", rows.single().direction)
    }

    @Test
    fun `a blank direction keeps its inferred direction after the tagged trains leave`() {
        // Same snapshot, clock advanced past the only tagged train: the blank one must stay on the
        // `outbound` row rather than fall back to a "Platform 1" key (a star on it would unpin).
        val tagged = departure("mildmay", "Mildmay", "outbound", "Clapham Junction", 360, platform = "Platform 1", mode = "overground")
        val blank = departure("mildmay", "Mildmay", "", "Clapham Junction", 1800, platform = "Platform 1", mode = "overground")

        val rows = DepartureRows.forStop("910GKNTSHTW", "Kentish Town West", listOf(tagged, blank), now.plusSeconds(600))

        assertEquals("outbound", rows.single().direction)
        assertEquals("outbound", rows.single().directionKey)
    }

    @Test
    fun `a blank direction on Platform Unknown takes nothing, even from one tagged train`() {
        // "Platform Unknown" is TfL's placeholder for trains either way, not one platform: a lone
        // `outbound` there must not relabel a blank inbound train and merge the two directions.
        val out = departure("mildmay", "Mildmay", "outbound", "Richmond (London)", 60, platform = "Platform Unknown", mode = "overground")
        val blank = departure("mildmay", "Mildmay", "", "Stratford (London)", 120, platform = "Platform Unknown", mode = "overground")

        val rows = DepartureRows.forStop("910GKNTSHTW", "Kentish Town West", listOf(out, blank), now)

        assertEquals(2, rows.size)
        assertEquals("", rows.single { it.destination == "Stratford (London)" }.direction)
    }

    @Test
    fun `a blank direction takes nothing from a train heading elsewhere on its platform`() {
        // A single platform can serve the line both ways: an `outbound` train to Richmond there says
        // nothing about a blank one to Stratford, so the two stay apart rather than merge.
        val out = departure("mildmay", "Mildmay", "outbound", "Richmond (London)", 60, platform = "Platform 1", mode = "overground")
        val blank = departure("mildmay", "Mildmay", "", "Stratford (London)", 120, platform = "Platform 1", mode = "overground")

        val rows = DepartureRows.forStop("910GEXAMPLE", "Example", listOf(out, blank), now)

        assertEquals(2, rows.size)
        assertEquals("", rows.single { it.destination == "Stratford (London)" }.direction)
    }

    @Test
    fun `a blank direction takes nothing from a same-named terminus with another id`() {
        // Two termini can share a display name; TfL's destination ids tell them apart.
        val out = departure("mildmay", "Mildmay", "outbound", "Example", 60, platform = "Platform 1", mode = "overground")
            .copy(destinationId = "910GEXAMPLA")
        val blank = departure("mildmay", "Mildmay", "", "Example", 120, platform = "Platform 1", mode = "overground")
            .copy(destinationId = "910GEXAMPLB")

        val rows = DepartureRows.forStop("910GEXAMPLE", "Example", listOf(out, blank), now)

        assertEquals(listOf(blank), rows.single { it.direction.isEmpty() }.upcoming)
    }

    @Test
    fun `a blank direction with no destination takes nothing`() {
        // With the destination missing too, a shared platform's two directions look the same.
        val out = departure("mildmay", "Mildmay", "outbound", "", 60, platform = "Platform 1", mode = "overground")
        val blank = departure("mildmay", "Mildmay", "", "", 120, platform = "Platform 1", mode = "overground")

        val rows = DepartureRows.forStop("910GEXAMPLE", "Example", listOf(out, blank), now)

        assertEquals(listOf(blank), rows.single { it.direction.isEmpty() }.upcoming)
    }

    @Test
    fun `a blank direction stays apart where its platform's tagged trains disagree`() {
        // The same service tagged both ways (a TfL inconsistency) gives no single direction to take.
        val out = departure("mildmay", "Mildmay", "outbound", "Clapham Junction", 60, platform = "Platform 3", mode = "overground")
        val back = departure("mildmay", "Mildmay", "inbound", "Clapham Junction", 120, platform = "Platform 3", mode = "overground")
        val blank = departure("mildmay", "Mildmay", "", "Clapham Junction", 180, platform = "Platform 3", mode = "overground")

        val rows = DepartureRows.forStop("910GEXAMPLE", "Example", listOf(out, back, blank), now)

        assertEquals(listOf(blank), rows.single { it.direction.isEmpty() }.upcoming)
    }

    @Test
    fun `a blank direction takes nothing from another line at its platform`() {
        // King's Cross Platform 8: the Metropolitan is tagged, the Hammersmith & City blank — a
        // different line's direction is no evidence (TfL tags one platform differently per line).
        val met = departure("metropolitan", "Metropolitan", "inbound", "Uxbridge", 60, platform = "Westbound - Platform 8")
        val hc = departure("hammersmith-city", "Hammersmith & City", "", "Hammersmith", 120, platform = "Westbound - Platform 8")

        val rows = DepartureRows.forStop("940GZZLUKSX", "King's Cross St. Pancras", listOf(met, hc), now)

        assertEquals("", rows.single { it.lineId == "hammersmith-city" }.direction)
    }

    @Test
    fun `a service with three named-platform trains folds in its later Platform Unknown ones`() {
        // Highbury & Islington's shape from the live feed: the next Mildmay trains to Richmond name
        // Platform 7, the later ones say "Platform Unknown". Three named ones already fill the row,
        // so no "Platform Unknown" group repeats the service.
        fun richmond(seconds: Long, platform: String) =
            departure("mildmay", "Mildmay", "outbound", "Richmond (London)", seconds, platform = platform, mode = "overground")
        val named = listOf(richmond(420, "Platform 7"), richmond(1020, "Platform 7"), richmond(1740, "Platform 7"))
        val later = listOf(richmond(2400, "Platform Unknown"), richmond(3300, "Platform Unknown"))

        val rows = DepartureRows.forStop("910GHGHI", "Highbury & Islington", named + later, now)

        // One Platform 7 row: the later trains fold in past the three times the card shows, and stay
        // on the row for the route detail's full list.
        assertEquals(listOf("7"), rows.map { it.platform })
        assertEquals(named + later, rows.single().upcoming)
        assertEquals(3, DepartureRows.destinationLines(rows.single(), maxTimes = 3).single().times.size)
    }

    @Test
    fun `a service alternating between platforms keeps its Platform Unknown ones`() {
        // Named trains on Platforms 1, 2 and 1 give no one platform for a later unknown train: folding
        // it into Platform 1 would make it that row's third shown time, under a platform nobody named.
        fun train(seconds: Long, platform: String) =
            departure("mildmay", "Mildmay", "outbound", "Richmond (London)", seconds, platform = platform, mode = "overground")
        val named = listOf(train(60, "Platform 1"), train(300, "Platform 2"), train(600, "Platform 1"))
        val later = train(2400, "Platform Unknown")

        val rows = DepartureRows.forStop("910GEXAMPLE", "Example", named + later, now)

        assertEquals(listOf(later), rows.single { it.platform == "Unknown" }.upcoming)
    }

    @Test
    fun `a Platform Unknown train to a same-named other terminus doesn't fold`() {
        fun train(seconds: Long, platform: String, id: String) =
            departure("mildmay", "Mildmay", "outbound", "Example", seconds, platform = platform, mode = "overground")
                .copy(destinationId = id)
        val named = listOf(train(60, "Platform 7", "910GEXAMPLA"), train(300, "Platform 7", "910GEXAMPLA"), train(600, "Platform 7", "910GEXAMPLA"))
        val other = train(2400, "Platform Unknown", "910GEXAMPLB")

        val rows = DepartureRows.forStop("910GEXAMPLE", "Example", named + other, now)

        assertEquals(listOf(other), rows.single { it.platform == "Unknown" }.upcoming)
    }

    @Test
    fun `a Platform Unknown train sooner than the named ones keeps its own row`() {
        // An unknown train leaving before the third named one may be the next departure: it stays
        // visible, under its own header rather than a platform nobody named.
        fun richmond(seconds: Long, platform: String) =
            departure("mildmay", "Mildmay", "outbound", "Richmond (London)", seconds, platform = platform, mode = "overground")
        val soon = richmond(60, "Platform Unknown")
        val named = listOf(richmond(300, "Platform 7"), richmond(600, "Platform 7"), richmond(900, "Platform 7"))

        val rows = DepartureRows.forStop("910GHGHI", "Highbury & Islington", listOf(soon) + named, now)

        assertEquals(listOf(soon), rows.single { it.platform == "Unknown" }.upcoming)
    }

    @Test
    fun `another branch's named trains don't hide a branch's Platform Unknown one`() {
        // High Barnet via Bank and via Charing X are separate destination lines: three named Bank
        // trains say nothing about the Charing X one, which may be the only countdown for its branch.
        fun barnet(seconds: Long, platform: String, branch: String) =
            departure("northern", "Northern", "outbound", "High Barnet", seconds, platform = platform, branch = branch)
        val bank = listOf(barnet(60, "Northbound - Platform 5", "Bank"), barnet(300, "Northbound - Platform 5", "Bank"), barnet(600, "Northbound - Platform 5", "Bank"))
        val charingX = barnet(2400, "Platform Unknown", "Charing X")

        val rows = DepartureRows.forStop("940GZZLUEUS", "Euston", bank + charingX, now)

        assertTrue(rows.any { charingX in it.upcoming })
    }

    @Test
    fun `a Platform Unknown train in another or no direction stays`() {
        // "Platform Unknown" can hide either direction: three named outbound trains say nothing
        // about an inbound or direction-less one to the same place, which may be its only countdown.
        fun loop(seconds: Long, platform: String, direction: String) =
            departure("circle", "Circle", direction, "Edgware Road", seconds, platform = platform)
        val named = listOf(loop(60, "Platform 1", "outbound"), loop(300, "Platform 1", "outbound"), loop(600, "Platform 1", "outbound"))
        val inbound = loop(2400, "Platform Unknown", "inbound")
        val blank = loop(2700, "Platform Unknown", "")

        val rows = DepartureRows.forStop("940GZZLUEXA", "Example", named + inbound + blank, now)

        val shown = rows.flatMap { it.upcoming }
        assertTrue(inbound in shown)
        assertTrue(blank in shown)
    }

    @Test
    fun `a service with fewer named-platform trains keeps its Platform Unknown ones`() {
        // Only two named: the unknown ones are its only later times, so they stay.
        fun stratford(seconds: Long, platform: String) =
            departure("mildmay", "Mildmay", "inbound", "Stratford (London)", seconds, platform = platform, mode = "overground")
        val named = listOf(stratford(300, "Platform 8"), stratford(660, "Platform 8"))
        val later = listOf(stratford(2520, "Platform Unknown"))

        val rows = DepartureRows.forStop("910GHGHI", "Highbury & Islington", named + later, now)

        assertEquals(named + later, rows.flatMap { it.upcoming })
    }

    @Test
    fun `blank direction and no platform falls back to destination`() {
        val toPimlico = departure("24", "24", "", "Pimlico", 90)
        val toHampstead = departure("24", "24", "", "Hampstead Heath", 150)

        val rows = DepartureRows.forStop("490000123A", "Trafalgar Square", listOf(toHampstead, toPimlico), now)

        assertEquals(2, rows.size)
        assertEquals(listOf("Pimlico", "Hampstead Heath"), rows.map { it.destination })
    }

    @Test
    fun `a direction running from two platforms splits into a row per platform`() {
        // Camden Town southbound: one TfL direction, trains from Platform 2 and Platform 4.
        val p2a = departure("northern", "Northern", "inbound", "Morden", 60, platform = "Southbound - Platform 2")
        val p4 = departure("northern", "Northern", "inbound", "Morden", 90, platform = "Southbound - Platform 4")
        val p2b = departure("northern", "Northern", "inbound", "Kennington", 240, platform = "Southbound - Platform 2")

        val rows = DepartureRows.forStop("940GZZLUCTN", "Camden Town", listOf(p4, p2b, p2a), now)

        assertEquals(listOf("2", "4"), rows.map { it.platform })
        assertEquals(listOf(p2a, p2b), rows[0].upcoming)
        assertEquals(listOf(p4), rows[1].upcoming)
        // Both keep the direction's key; the platform is what tells them apart.
        assertEquals(listOf("inbound", "inbound"), rows.map { it.directionKey })
    }

    @Test
    fun `a single-platform direction stays one row, platform-less predictions included`() {
        val p3 = departure("victoria", "Victoria", "inbound", "Walthamstow Central", 120, platform = "Northbound - Platform 3")
        val blank = departure("victoria", "Victoria", "inbound", "Walthamstow Central", 300, platform = "")

        val row = DepartureRows.forStop("940GZZLUVIC", "Victoria", listOf(blank, p3), now).single()

        assertEquals("3", row.platform)
        assertEquals(listOf(p3, blank), row.upcoming)
    }

    @Test
    fun `a platform-less prediction beside two platforms gets its own unnumbered row`() {
        // It can't be placed on either platform, so it isn't filed under one (SPEC principle 1).
        val p1 = departure("northern", "Northern", "outbound", "Edgware", 60, platform = "Northbound - Platform 1")
        val p3 = departure("northern", "Northern", "outbound", "High Barnet", 120, platform = "Northbound - Platform 3")
        val blank = departure("northern", "Northern", "outbound", "Mill Hill East", 180, platform = "Northbound")

        val rows = DepartureRows.forStop("940GZZLUCTN", "Camden Town", listOf(p1, p3, blank), now)

        assertEquals(listOf("1", "3", ""), rows.map { it.platform })
        assertEquals(listOf(blank), rows[2].upcoming)
    }

    @Test
    fun `platforms stay merged when splitting is off`() {
        val p2 = departure("northern", "Northern", "inbound", "Morden", 60, platform = "Southbound - Platform 2")
        val p4 = departure("northern", "Northern", "inbound", "Morden", 90, platform = "Southbound - Platform 4")

        val row = DepartureRows.forStop("940GZZLUCTN", "Camden Town", listOf(p2, p4), now, splitPlatforms = false).single()

        assertEquals("", row.platform)
        assertEquals(listOf(p2, p4), row.upcoming)
    }

    @Test
    fun `a bus never splits on its stop-local platform`() {
        val a = departure("55", "55", "outbound", "Oxford Circus", 60, platform = "Platform 1", mode = "bus")
        val b = departure("55", "55", "outbound", "Oxford Circus", 90, platform = "Platform 2", mode = "bus")

        val row = DepartureRows.forStop("490000000A", "Stop A", listOf(a, b), now).single()

        assertEquals("", row.platform)
    }

    @Test
    fun `a bus with its mode missing on the soonest prediction still doesn't split`() {
        val a = departure("55", "55", "outbound", "Oxford Circus", 60, platform = "Platform 1", mode = "")
        val b = departure("55", "55", "outbound", "Oxford Circus", 90, platform = "Platform 2", mode = "bus")

        val row = DepartureRows.forStop("490000000A", "Stop A", listOf(a, b), now).single()

        assertEquals("", row.platform)
    }

    @Test
    fun `each row carries a stable directionKey identity`() {
        // Present direction → directionKey is that direction.
        val present = DepartureRows.forStop(
            "s", "S",
            listOf(departure("victoria", "Victoria", "inbound", "Walthamstow Central", 120)),
            now,
        ).single()
        assertEquals("inbound", present.directionKey)
        assertEquals("inbound", present.direction)

        // Blank direction split by platform → each row's directionKey is its platform,
        // so (stopId, lineId, directionKey) tells the two rows apart even though both
        // carry a blank `direction` — the identity a persisted star keys on.
        val north = departure("northern", "Northern", "", "High Barnet", 120, platform = "Northbound - Platform 1")
        val south = departure("northern", "Northern", "", "Morden", 240, platform = "Southbound - Platform 4")
        val split = DepartureRows.forStop("940GZZLUEUS", "Euston", listOf(south, north), now)

        assertEquals(listOf("", ""), split.map { it.direction })
        assertEquals(
            listOf("Northbound - Platform 1", "Southbound - Platform 4"),
            split.map { it.directionKey },
        )
        assertEquals(2, split.map { it.directionKey }.toSet().size)
    }

    @Test
    fun `stamps a disrupted line's rows with its status, leaving clean lines null`() {
        val victoria = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val northern = departure("northern", "Northern", "southbound", "Morden", 180)
        val statuses = mapOf(
            "victoria" to LineStatus("victoria", 6, "Severe Delays"),
            "northern" to LineStatus("northern", LineStatus.GOOD_SERVICE, "Good Service"),
        )

        val rows = DepartureRows.forStop(
            "940GZZLUVIC", "Victoria", listOf(victoria, northern), now, statuses,
        ).associateBy { it.lineId }

        assertEquals("Severe Delays", rows.getValue("victoria").status?.description)
        // A good-service line still stamps null — a non-null row status always means
        // "disrupted", decoupled from whatever the map happens to carry.
        assertNull(rows.getValue("northern").status)

        // across() threads the same map through to every stop's rows.
        val acrossRows = DepartureRows.across(
            listOf(StopArrivals("940GZZLUVIC", "Victoria", listOf(victoria, northern), fetchedAt = now)),
            now,
            statuses,
        ).associateBy { it.lineId }
        assertEquals("Severe Delays", acrossRows.getValue("victoria").status?.description)
        assertNull(acrossRows.getValue("northern").status)
    }

    // A bus route north from Bank (made-up stops past Moorgate), listed as TfL's route data lists it,
    // and an alert in TfL's words on a stretch at its south end.
    private val busRoute = LineSequence(
        listOf(LineRoute("Bank - North End", listOf("b1", "b2", "b3", "b4", "b5"), "inbound")),
        mapOf("b1" to "Bank / King William Street", "b2" to "Example Street", "b3" to "Moorgate", "b4" to "Alpha Road", "b5" to "North End"),
    )
    private val busDiversion = LineStatus(
        "99", 5, "Diversion",
        "Buses are not serving stops between 'Bank Station/King William Street' and 'Moorgate Station'.",
        soleAlert = true,
    )

    private fun busStop(stopId: String) = StopArrivals(stopId, "Stop", listOf(departure("99", "99", "inbound", "North End", 120, mode = "bus")), now)

    @Test
    fun `a bus alert wholly behind a row's stop moves off the row, ahead of it stays`() {
        val rows = DepartureRows.across(listOf(busStop("b4"), busStop("b2")), now, mapOf("99" to busDiversion))
        val placed = DepartureRows.withAlertsBehind(rows, mapOf("99" to busRoute)).associateBy { it.stopId }
        // Past the stretch: nothing flags the row, and its page keeps the alert to show muted.
        assertNull(placed.getValue("b4").status)
        assertEquals(busDiversion, placed.getValue("b4").statusBehind)
        // Inside it: flagged as before.
        assertEquals(busDiversion, placed.getValue("b2").status)
        assertNull(placed.getValue("b2").statusBehind)
        // No route loaded keeps the alert.
        assertEquals(rows, DepartureRows.withAlertsBehind(rows, mapOf("99" to null)))
        assertEquals(rows, DepartureRows.withAlertsBehind(rows, emptyMap()))
    }

    @Test
    fun `only a bus row with buses to take has its alert placed`() {
        // A tube line's delays spread along it, whatever stations its alert names.
        val tube = DepartureRows.across(
            listOf(StopArrivals("b4", "Stop", listOf(departure("99", "99", "inbound", "North End", 120, mode = "tube")), now)),
            now, mapOf("99" to busDiversion),
        )
        assertEquals(tube, DepartureRows.withAlertsBehind(tube, mapOf("99" to busRoute)))
        // A status row has no bus to vouch for: it says what the alert does.
        val status = DepartureRows.across(
            listOf(StopArrivals("b4", "Stop", emptyList(), now, lines = listOf(LineRef("99", "99", "bus")))),
            now, mapOf("99" to busDiversion),
        ).filter { it.lineId == "99" }
        assertTrue(status.single().isStatusOnly)
        assertEquals(status, DepartureRows.withAlertsBehind(status, mapOf("99" to busRoute)))
    }

    @Test
    fun `the verdicts reached are the alerts moved off rows, by their words, stop and way, of those weighed`() {
        val rows = DepartureRows.across(listOf(busStop("b4"), busStop("b2")), now, mapOf("99" to busDiversion))
        val fingerprint = lineAlertFingerprint(busDiversion)
        // Both rows' alerts are weighed; only the one past the stretch is behind its stop.
        assertEquals(
            AlertPlacement(
                behind = setOf(AlertBehind("99", fingerprint, "b4", "inbound")),
                weighed = setOf(AlertBehind("99", fingerprint, "b4", "inbound"), AlertBehind("99", fingerprint, "b2", "inbound")),
                stops = setOf("b4", "b2"),
                alerts = setOf("99" to fingerprint),
            ),
            DepartureRows.alertsBehind(rows, mapOf("99" to busRoute)),
        )
        // Nothing is weighed without the route, though the stops and alerts are still the rows'.
        assertEquals(
            AlertPlacement(emptySet(), emptySet(), setOf("b4", "b2"), setOf("99" to fingerprint)),
            DepartureRows.alertsBehind(rows, emptyMap()),
        )
    }

    @Test
    fun `a status carrying a verdict for a stop and way moves its alert off that row only`() {
        val placed = busDiversion.copy(behindAt = setOf(StopWay("b4", "inbound")))
        val rows = DepartureRows.across(listOf(busStop("b4"), busStop("b2")), now, mapOf("99" to placed)).associateBy { it.stopId }
        // The row the verdict names shows no mark, its alert kept for its page, as in the app.
        assertNull(rows.getValue("b4").status)
        assertEquals(placed, rows.getValue("b4").statusBehind)
        // Another stop flags as before.
        assertEquals(placed, rows.getValue("b2").status)
        // As does the named stop for buses going the other way.
        val outbound = StopArrivals("b4", "Stop", listOf(departure("99", "99", "outbound", "Bank", 120, mode = "bus")), now)
        assertEquals(placed, DepartureRows.across(listOf(outbound), now, mapOf("99" to placed)).single().status)
    }

    @Test
    fun `the bus lines whose routes to load are those with an alert that might be placed`() {
        val stops = listOf(busStop("b4"), StopArrivals("t1", "Station", listOf(departure("tube1", "Tube", "inbound", "Far", 60)), now))
        val statuses = mapOf(
            "99" to LineStatus("99", LineStatus.GOOD_SERVICE, "Good Service", byDirection = mapOf("inbound" to busDiversion)),
            "tube1" to busDiversion.copy(lineId = "tube1"),
        )
        assertEquals(setOf("99"), DepartureRows.linesWithAlertsToPlace(stops, statuses, now))
        // An alert that can't be placed (one of several) costs no request, nor does one the rider dismissed.
        assertEquals(emptySet<String>(), DepartureRows.linesWithAlertsToPlace(stops, mapOf("99" to busDiversion.copy(soleAlert = false)), now))
        assertEquals(emptySet<String>(), DepartureRows.linesWithAlertsToPlace(stops, statuses, now, setOf(DismissedAlert.ofLineStatus(busDiversion))))
    }

    @Test
    fun `a bus by its stop's advertised mode, and planned work whose day has come, have routes to load`() {
        // TfL left the mode off every prediction; the stop's lines say it's a bus, as its rows do.
        val unmoded = StopArrivals(
            "b4", "Stop", listOf(departure("99", "99", "inbound", "North End", 120, mode = "")), now,
            lines = listOf(LineRef("99", "99", "bus")),
        )
        assertEquals("bus", DepartureRows.across(listOf(unmoded), now).single().mode)
        assertEquals(setOf("99"), DepartureRows.linesWithAlertsToPlace(listOf(unmoded), mapOf("99" to busDiversion), now))
        // A diversion planned for today is under way, as the rows read it; one for tomorrow isn't yet.
        val today = now.atZone(AlertStart.ZONE).toLocalDate()
        fun plannedFor(day: java.time.LocalDate) = LineStatus(
            "99", LineStatus.GOOD_SERVICE, "Good Service",
            planned = listOf(PlannedAlert("Diversion", busDiversion.fullText.orEmpty(), day)),
        )
        assertEquals(setOf("99"), DepartureRows.linesWithAlertsToPlace(listOf(busStop("b4")), mapOf("99" to plannedFor(today)), now))
        assertEquals(emptySet<String>(), DepartureRows.linesWithAlertsToPlace(listOf(busStop("b4")), mapOf("99" to plannedFor(today.plusDays(1))), now))
    }

    @Test
    fun `a row carries only the alerts for its own direction`() {
        // TfL scopes the line's diversion to buses heading in: the outbound row stays clean, the
        // inbound one is flagged, and a row TfL gave no direction for keeps the line-wide alert.
        val diversion = LineStatus("bus1", 5, "Diversion", "Northbound buses diverted")
        val status = diversion.copy(
            byDirection = mapOf(
                "inbound" to diversion,
                "outbound" to LineStatus("bus1", LineStatus.GOOD_SERVICE, "Good Service"),
            ),
        )
        val rows = DepartureRows.forStop(
            "STOP_A", "Stop A",
            listOf(
                departure("bus1", "1", "outbound", "Town B", 120, mode = "bus"),
                departure("bus1", "1", "inbound", "Town A", 180, mode = "bus"),
                departure("bus1", "1", "", "Anywhere", 240, mode = "bus"),
            ),
            now, mapOf("bus1" to status),
        ).associateBy { it.direction }

        assertNull(rows.getValue("outbound").status)
        assertEquals("Diversion", rows.getValue("inbound").status?.description)
        assertEquals("Diversion", rows.getValue("").status?.description)
    }

    @Test
    fun `planned work reaches the row without flagging it`() {
        val planned = PlannedAlert("Part Closure", "No service on Saturday 3 October.", java.time.LocalDate.of(2026, 10, 3))
        val rows = DepartureRows.forStop(
            "940GZZLUVIC", "Victoria",
            listOf(departure("victoria", "Victoria", "outbound", "Brixton", 120)),
            now, mapOf("victoria" to LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(planned))),
        )
        assertNull(rows.single().status)
        assertEquals(listOf(planned), rows.single().plannedAlerts)
    }

    @Test
    fun `planned work whose day has come is flagged, however old its status`() {
        // Sorted as to come when fetched; by the time these rows are drawn its day has come
        // (a later status check failed and this one was kept) — Codex, PR #337.
        val planned = PlannedAlert("Part Closure", "No service on Friday 18 September.", java.time.LocalDate.of(2026, 9, 18), 5)
        val rows = DepartureRows.across(
            listOf(StopArrivals("940GZZLUVIC", "Victoria", listOf(departure("victoria", "Victoria", "outbound", "Brixton", 120)), fetchedAt = now)),
            now, mapOf("victoria" to LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(planned))),
        )
        assertEquals("Part Closure", rows.single().status?.description)
        assertEquals(emptyList<PlannedAlert>(), rows.single().plannedAlerts)
    }

    @Test
    fun `a dismissed notice of work to come still flags the work once it starts`() {
        // The calendar was put away; the disruption, on its day, is a new thing to see (SPEC *Disruptions*).
        val planned = PlannedAlert("Part Closure", "No service on Friday 18 September.", java.time.LocalDate.of(2026, 9, 18), 5)
        val statuses = mapOf("victoria" to LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(planned)))
        val rows = DepartureRows.withoutDismissed(
            DepartureRows.across(
                listOf(StopArrivals("940GZZLUVIC", "Victoria", listOf(departure("victoria", "Victoria", "outbound", "Brixton", 120)), fetchedAt = now)),
                now, statuses,
            ),
            setOf(DismissedAlert.ofPlanned("victoria", planned)),
        )
        assertEquals("Part Closure", rows.single().status?.description)
    }

    @Test
    fun `a planned alert shown as started keeps its dismissal while its status is reused`() {
        // Fetched the day before and reused past midnight: rows show it as under way, and dismissing
        // it there must survive the next reconcile over the same status (Codex, PR #337).
        val planned = PlannedAlert("Part Closure", "No service on Friday 18 September.", java.time.LocalDate.of(2026, 9, 18), 5)
        val statuses = mapOf("victoria" to LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(planned)))
        val shown = LineStatus.asOf(statuses, now).getValue("victoria")
        assertTrue(DismissedAlert.ofLineStatus(shown) in DepartureRows.liveLineStatusAlerts(statuses, now))
        assertTrue(DismissedAlert.ofPlanned("victoria", planned) in DepartureRows.liveLineStatusAlerts(statuses, now))
    }

    @Test
    fun `a dismissed planned alert leaves its rows, and stays live while it's coming`() {
        val first = PlannedAlert("Part Closure", "No service on Saturday 3 October.", java.time.LocalDate.of(2026, 10, 3))
        val second = PlannedAlert("Diversion", "Buses divert from 12 October.", java.time.LocalDate.of(2026, 10, 12))
        val statuses = mapOf("victoria" to LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(first, second)))
        val rows = DepartureRows.forStop(
            "940GZZLUVIC", "Victoria", listOf(departure("victoria", "Victoria", "outbound", "Brixton", 120)), now, statuses,
        )
        val dismissal = DismissedAlert.of(rows.single().copy(plannedAlerts = listOf(first)))
        assertEquals(DismissedAlert.ofPlanned("victoria", first), dismissal)

        val kept = DepartureRows.withoutDismissed(rows, setOf(checkNotNull(dismissal)))
        assertEquals(listOf(second), kept.single().plannedAlerts)
        assertTrue(dismissal in DepartureRows.liveLineStatusAlerts(statuses))
    }

    @Test
    fun `an alert under way behind the one shown stays live, and so does its dismissal`() {
        // Dismissed while it showed, then a worse one shown over it: still under way, so not pruned
        // (Codex on #519). Made-up words.
        val milder = LineAlert(6, "Diversion", "Bus stop 'Alpha Road' will not be served.")
        val worse = LineAlert(3, "Part Suspended", "No buses between Alpha Road and Beta Road.")
        val statuses = mapOf("99" to LineStatus("99", 3, "Part Suspended", worse.fullText, underWay = listOf(worse, milder)))
        val dismissed = DismissedAlert.ofLineStatus(LineStatus("99", 6, "Diversion", milder.fullText))
        assertTrue(dismissed in DepartureRows.liveLineStatusAlerts(statuses))
    }

    @Test
    fun `dismissing a status-only row's disruption keeps its undismissed planned work`() {
        val planned = PlannedAlert("Part Closure", "No service on Saturday 3 October.", java.time.LocalDate.of(2026, 10, 3))
        val suspended = LineStatus("victoria", 2, "Suspended", planned = listOf(planned))
        val rows = DepartureRows.across(
            listOf(StopArrivals("940GZZLUVIC", "Victoria", emptyList(), fetchedAt = now, lines = listOf(LineRef("victoria", "Victoria", "tube")))),
            now,
            mapOf("victoria" to suspended),
        )
        val kept = DepartureRows.withoutDismissed(rows, setOf(DismissedAlert.ofLineStatus(suspended))).single()
        assertNull(kept.status)
        assertTrue(kept.statusDismissed)
        assertEquals(listOf(planned), kept.plannedAlerts)
        // With the planned alert dismissed too, the row has nothing left to carry.
        assertTrue(
            DepartureRows.withoutDismissed(
                rows, setOf(DismissedAlert.ofLineStatus(suspended), DismissedAlert.ofPlanned("victoria", planned)),
            ).isEmpty(),
        )
    }

    @Test
    fun `a status-only row carries the line's planned work too`() {
        val planned = PlannedAlert("Part Closure", "No service on Saturday 3 October.", java.time.LocalDate.of(2026, 10, 3))
        val rows = DepartureRows.across(
            listOf(StopArrivals("940GZZLUVIC", "Victoria", emptyList(), fetchedAt = now, lines = listOf(LineRef("victoria", "Victoria", "tube")))),
            now,
            mapOf("victoria" to LineStatus("victoria", 2, "Suspended", planned = listOf(planned))),
        )
        assertEquals(listOf(planned), rows.single { it.lineId == "victoria" }.plannedAlerts)
    }

    @Test
    fun `a direction's own alert counts among the live ones, so its dismissal isn't pruned`() {
        val whole = LineStatus("bus1", 5, "Diversion", "Both ways")
        val outbound = LineStatus("bus1", 5, "Diversion", "Southbound only")
        val alerts = DepartureRows.liveLineStatusAlerts(
            mapOf("bus1" to whole.copy(byDirection = mapOf("outbound" to outbound, "inbound" to whole))),
        )
        assertTrue(DismissedAlert.ofLineStatus(outbound) in alerts)
        assertTrue(DismissedAlert.ofLineStatus(whole) in alerts)
    }

    @Test
    fun `a service ending at a nearer place is hidden, and an expired onward one doesn't bring its line back as No departures`() {
        // An onward Victoria train that has just left, and a later one terminating at the rider's
        // nearest station: nothing live helps, but the line has departures, so no status row.
        val expired = departure("victoria", "Victoria", "outbound", "Brixton", -30)
        val endsNearby = departure("victoria", "Victoria", "outbound", "Oxford Circus", 120).copy(destinationId = "940GZZLUOXC")
        val onward = departure("northern", "Northern", "outbound", "Morden", 180)
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = listOf(expired, endsNearby, onward),
            fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("northern", "Northern", "tube")),
            nearer = Terminating.Nearer(ids = setOf("940GZZLUOXC")),
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", 6, "Severe Delays"))
        assertEquals(listOf("northern"), DepartureRows.across(listOf(stop), now, statuses).map { it.lineId })
    }

    @Test
    fun `a disrupted declared line with no predictions becomes a status row, sorted first`() {
        val victoria = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = listOf(victoria),
            fetchedAt = now,
            lines = listOf(
                LineRef("victoria", "Victoria", "tube"), // has a prediction → no status row
                LineRef("circle", "Circle", "tube"), // disrupted, no prediction → status row
                LineRef("northern", "Northern", "tube"), // good service → no row at all
            ),
        )
        val statuses = mapOf(
            "circle" to LineStatus("circle", 2, "Suspended"),
            "victoria" to LineStatus("victoria", 6, "Severe Delays"),
            "northern" to LineStatus("northern", LineStatus.GOOD_SERVICE, "Good Service"),
        )

        val rows = DepartureRows.across(listOf(stop), now, statuses)

        assertEquals(2, rows.size)
        // The Circle status row sorts first — no countdown, direction-independent.
        val statusRow = rows[0]
        assertEquals("circle", statusRow.lineId)
        assertEquals(STATUS_DIRECTION_KEY, statusRow.directionKey)
        assertEquals("", statusRow.direction)
        assertTrue(statusRow.upcoming.isEmpty())
        assertEquals("Suspended", statusRow.status?.description)
        // Victoria has predictions, so it's a timed row marked with its status — not a
        // second status row. Northern is good service with no predictions → no row.
        assertEquals("victoria", rows[1].lineId)
        assertEquals(listOf(victoria), rows[1].upcoming)
        assertEquals("Severe Delays", rows[1].status?.description)
    }

    @Test
    fun `a National Rail line's status row says why it has no times, and no other line's does`() {
        val stop = StopArrivals(
            "910GEXAMPLE",
            "Example",
            departures = emptyList(),
            fetchedAt = now,
            lines = listOf(
                LineRef("great-example", "Great Example", "national-rail"),
                LineRef("overground-example", "Overground Example", "overground"),
            ),
            railFeed = RailFeed.NO_KEY,
        )
        val statuses = mapOf(
            "great-example" to LineStatus("great-example", 6, "Severe Delays"),
            "overground-example" to LineStatus("overground-example", 2, "Suspended"),
        )
        val rows = DepartureRows.across(listOf(stop), now, statuses).associateBy { it.lineId }
        assertEquals(RailFeed.NO_KEY, rows.getValue("great-example").railFeed)
        // A TfL-run line gets nothing from a key, so it never says "No key".
        assertEquals(null, rows.getValue("overground-example").railFeed)
        assertEquals(NoTimes.NO_KEY, NoTimes.of(rows.getValue("great-example")))
        assertEquals(NoTimes.NO_TRAINS, NoTimes.of(rows.getValue("overground-example")))
    }

    @Test
    fun `a line with no departures shows a dash only when its source answered`() {
        val rail = DepartureRow(
            stopId = "910GEXAMPLE", stopName = "Example", lineId = "great-example", lineName = "Great Example",
            direction = "", directionKey = STATUS_DIRECTION_KEY, destination = "", mode = "national-rail",
            upcoming = emptyList(), fetchedAt = now,
        )
        assertEquals(NoTimes.NO_TRAINS, NoTimes.of(rail.copy(railFeed = RailFeed.LIVE)))
        assertEquals(NoTimes.NO_KEY, NoTimes.of(rail.copy(railFeed = RailFeed.NO_KEY)))
        assertEquals(NoTimes.NO_DATA, NoTimes.of(rail.copy(railFeed = RailFeed.UNAVAILABLE)))
        // No board covers it (no station code, or its twin shows the board): no source answered.
        assertEquals(NoTimes.NO_DATA, NoTimes.of(rail))
        // TfL answered for its own lines.
        assertEquals(NoTimes.NO_TRAINS, NoTimes.of(rail.copy(mode = "tube")))
    }

    @Test
    fun `a status row carries the stop's pole letter and bearing`() {
        // A suspended bus line's status row must carry the pole's letter/bearing, so it groups under
        // that pole's "(D)" header rather than a separate bare group — the warning has to say which
        // pole it belongs to at a multi-pole place (Codex P2, PR #118).
        val live = departure("17", "17", "outbound", "Farringdon", 120, mode = "bus")
        val stop = StopArrivals(
            "490000129D", "King's Cross Station",
            departures = listOf(live),
            fetchedAt = now,
            lines = listOf(LineRef("17", "17", "bus"), LineRef("45", "45", "bus")),
            stopLetter = "D",
            bearing = "E",
        )
        val statuses = mapOf("45" to LineStatus("45", 2, "Suspended"))
        val statusRow = DepartureRows.across(listOf(stop), now, statuses).first { it.lineId == "45" }
        assertEquals("D", statusRow.stopLetter)
        assertEquals("E", statusRow.bearing)
    }

    @Test
    fun `a stale stop's disrupted line does not synthesize a No-departures status row`() {
        // Arrivals are stale (aged past the threshold) and the line's predictions have all
        // expired. "No departures" would be a categorical claim the stale data can't back —
        // a delayed line's predictions may have merely expired, not stopped — so no status
        // row is synthesized; the screen's stale empty-state prompt covers the stop instead.
        val old = now.minusSeconds(600)
        val expired = departure("victoria", "Victoria", "outbound", "Brixton", -60)
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = listOf(expired),
            fetchedAt = old,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", 6, "Severe Delays"))

        assertEquals(emptyList<DepartureRow>(), DepartureRows.across(listOf(stop), now, statuses))
    }

    @Test
    fun `a fresh stop's disrupted line with no predictions still synthesizes a status row`() {
        // The same shape, but the stop is fresh, so "no departures" is trustworthy and the
        // status row is synthesized — guarding the suppression above from over-reaching.
        val fresh = now.minusSeconds(30)
        val expired = departure("victoria", "Victoria", "outbound", "Brixton", -60)
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = listOf(expired),
            fetchedAt = fresh,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", 6, "Severe Delays"))

        val rows = DepartureRows.across(listOf(stop), now, statuses)

        assertEquals(1, rows.size)
        assertEquals("victoria", rows[0].lineId)
        assertTrue(rows[0].upcoming.isEmpty())
        assertEquals("Severe Delays", rows[0].status?.description)
    }

    @Test
    fun `a disruption-only stop shows its closure but no No-departures status row`() {
        // Arrivals were never fetched (arrivalsFresh = false), though the stop is stamped
        // fresh from its disruption. Its disrupted declared line must NOT synthesize a
        // "No departures" row (we don't know its departures), but the closure still shows.
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = emptyList(),
            fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
            disruptions = listOf(StopDisruption("Stop moved to Pancras Road")),
            arrivalsFresh = false,
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", 6, "Severe Delays"))

        val rows = DepartureRows.across(listOf(stop), now, statuses)

        assertEquals(1, rows.size)
        assertEquals("Stop moved to Pancras Road", rows[0].stopDisruption)
    }

    @Test
    fun `a stop closure outside its window is not shown, and departures still are`() {
        // TfL lists a scheduled closure hours before it starts; a notice not yet in force (or
        // already over) must not sit beside the live departures as if the stop were closed now.
        val bus = departure("134", "134", "outbound", "Warren Street", 240)
        fun stopWith(vararg notices: StopDisruption) = StopArrivals(
            "490000001A", "Example Road", departures = listOf(bus), fetchedAt = now,
            disruptions = notices.toList(),
        )
        val later = StopDisruption(
            "Bus Stop Closed", validFrom = now.plusSeconds(3 * 3600), validTo = now.plusSeconds(8 * 3600),
        )
        val over = StopDisruption("Stop moved", validFrom = now.minusSeconds(7200), validTo = now.minusSeconds(60))

        val rows = DepartureRows.across(listOf(stopWith(later, over)), now)

        assertEquals(1, rows.size)
        assertNull(rows[0].stopDisruption)
        assertEquals("134", rows[0].lineId)

        // Once the window opens the same notice shows, departures unchanged.
        val during = DepartureRows.across(listOf(stopWith(later)), now.plusSeconds(3 * 3600))
        assertEquals("Bus Stop Closed", during[0].stopDisruption)
    }

    @Test
    fun `an undated or in-window stop disruption is shown`() {
        val stop = StopArrivals(
            "490000001A", "Example Road", departures = emptyList(), fetchedAt = now,
            disruptions = listOf(
                StopDisruption("Bus Stop Closed", validFrom = now.minusSeconds(60), validTo = now.plusSeconds(60)),
                StopDisruption("Bus Stop Closed", validFrom = now.minusSeconds(3600), validTo = null),
                StopDisruption("Lift out of service"),
            ),
        )

        val rows = DepartureRows.across(listOf(stop), now)

        // The same text under two windows shows once.
        assertEquals("Bus Stop Closed\n\nLift out of service", rows.single().stopDisruption)
    }

    @Test
    fun `a stop disruption becomes a stop-status row, sorted above line and timed rows`() {
        val victoria = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = listOf(victoria),
            fetchedAt = now,
            lines = listOf(LineRef("circle", "Circle", "tube")), // suspended, no prediction
            disruptions = listOf(StopDisruption("Station closed until further notice")),
        )
        val statuses = mapOf("circle" to LineStatus("circle", 2, "Suspended"))

        val rows = DepartureRows.across(listOf(stop), now, statuses)

        // Rank order: stop-status (whole stop) first, then the Circle line-status row,
        // then the Victoria timed row.
        assertEquals(3, rows.size)
        val stopStatus = rows[0]
        assertEquals("Station closed until further notice", stopStatus.stopDisruption)
        assertEquals("", stopStatus.lineId)
        assertEquals(STOP_STATUS_DIRECTION_KEY, stopStatus.directionKey)
        assertTrue(stopStatus.upcoming.isEmpty())
        assertNull(stopStatus.status)
        assertEquals("circle", rows[1].lineId)
        assertTrue(rows[1].upcoming.isEmpty())
        assertEquals("victoria", rows[2].lineId)
        assertEquals(listOf(victoria), rows[2].upcoming)
    }

    @Test
    fun `no upcoming departures yields no rows`() {
        val gone = departure("victoria", "Victoria", "outbound", "Brixton", -60)

        val rows = DepartureRows.forStop("s", "S", listOf(gone), now)

        assertEquals(emptyList<DepartureRow>(), rows)
    }

    @Test
    fun `across merges several stops into one soonest-first list`() {
        val oxc = StopArrivals(
            "940GZZLUOXC",
            "Oxford Circus",
            listOf(departure("victoria", "Victoria", "inbound", "Brixton", 300)),
            fetchedAt = now,
        )
        val ksx = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            listOf(
                departure("northern", "Northern", "southbound", "Morden", 120),
                departure("victoria", "Victoria", "outbound", "Walthamstow Central", 420),
            ),
            fetchedAt = now,
        )

        val rows = DepartureRows.across(listOf(oxc, ksx), now)

        // Soonest-first across both stops: KSX Northern (120s), OXC Victoria (300s),
        // KSX Victoria (420s) — each row stamped with the stop it came from.
        assertEquals(3, rows.size)
        assertEquals(
            listOf("King's Cross St. Pancras", "Oxford Circus", "King's Cross St. Pancras"),
            rows.map { it.stopName },
        )
        assertEquals(listOf("northern", "victoria", "victoria"), rows.map { it.lineId })
    }

    @Test
    fun `across stamps each row with its own stop's fetch age`() {
        val fresh = now.minusSeconds(30)
        val old = now.minusSeconds(600)
        val oxc = StopArrivals(
            "940GZZLUOXC",
            "Oxford Circus",
            listOf(departure("victoria", "Victoria", "inbound", "Brixton", 300)),
            fetchedAt = fresh,
        )
        val ksx = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            listOf(departure("northern", "Northern", "southbound", "Morden", 120)),
            fetchedAt = old,
            disruptions = listOf(StopDisruption("Station closed until further notice")),
        )

        val ageByStop = DepartureRows.across(listOf(oxc, ksx), now).associate { it.stopId to it.fetchedAt }

        // Each stop's rows (its timed rows and its stop-status row) carry that stop's own
        // age, so the screen withholds a stale stop's countdowns while a fresh one stays
        // live rather than one screen-wide flag deciding for both (SPEC D4).
        assertEquals(fresh, ageByStop.getValue("940GZZLUOXC"))
        assertEquals(old, ageByStop.getValue("940GZZLUKSX"))
    }

    @Test
    fun `across drops departed services and can yield an empty list`() {
        val stop = StopArrivals(
            "940GZZLUOXC",
            "Oxford Circus",
            listOf(departure("victoria", "Victoria", "outbound", "Brixton", -30)),
            fetchedAt = now,
        )

        assertEquals(emptyList<DepartureRow>(), DepartureRows.across(listOf(stop), now))
    }

    // --- nearbyDeduped: the "near me now" collapse (SPEC Finding stops → Near me now) ---

    private fun rowsFor(stopId: String, stopName: String, vararg departures: Departure): List<DepartureRow> =
        DepartureRows.forStop(stopId, stopName, departures.toList(), now)

    private fun stopStatusRow(
        stopId: String,
        stopName: String,
        text: String = "Stop closed",
        hubId: String = "",
        hubName: String = "",
        clusterId: String = "",
    ) = DepartureRow(
        stopId = stopId,
        stopName = stopName,
        clusterId = clusterId,
        lineId = "",
        lineName = "",
        direction = "",
        directionKey = STOP_STATUS_DIRECTION_KEY,
        destination = "",
        mode = "",
        upcoming = emptyList(),
        fetchedAt = now,
        stopDisruption = text,
        hubId = hubId,
        hubName = hubName,
    )

    // A line-status "No departures" row (a suspended line with no countdown) — the alert that rides
    // within a stop's section rather than as a standalone card.
    private fun lineStatusRow(stopId: String, stopName: String, lineId: String) = DepartureRow(
        stopId = stopId,
        stopName = stopName,
        lineId = lineId,
        lineName = lineId,
        direction = "",
        directionKey = STATUS_DIRECTION_KEY,
        destination = "",
        mode = "tube",
        upcoming = emptyList(),
        fetchedAt = now,
        status = LineStatus(lineId, severity = 6, description = "Suspended"),
    )

    @Test
    fun `nearbyDeduped collapses a line across adjacent stops to the nearest`() {
        // The same bus route, same direction, at three stops within the radius. The nearest
        // wins even though a farther stop's departure is sooner — you'd walk to the nearest.
        val near = rowsFor("A", "Stop A", departure("55", "55", "outbound", "Bakerloo", 120, mode = "bus"))
        val mid = rowsFor("B", "Stop B", departure("55", "55", "outbound", "Bakerloo", 60, mode = "bus"))
        val far = rowsFor("C", "Stop C", departure("55", "55", "outbound", "Bakerloo", 30, mode = "bus"))

        val deduped = DepartureRows.nearbyDeduped(
            near + mid + far,
            stopDistanceMeters = mapOf("A" to 100.0, "B" to 300.0, "C" to 500.0),
        )

        assertEquals(1, deduped.size)
        assertEquals("A", deduped[0].stopId)
    }

    @Test
    fun `glanceFolded folds by the saved order, and leaves rows alone without one`() {
        val near = rowsFor("A", "Stop A", departure("55", "55", "outbound", "Bakerloo", 120, mode = "bus"))
        val far = rowsFor("B", "Stop B", departure("55", "55", "outbound", "Bakerloo", 60, mode = "bus"))
        val other = rowsFor("B", "Stop B", departure("73", "73", "outbound", "Oxford Circus", 90, mode = "bus"))
        val rows = far + other + near
        val folded = DepartureRows.glanceFolded(rows, listOf("A", "B")) { false }
        assertEquals(listOf("A" to "55", "B" to "73"), folded.map { it.stopId to it.lineId }.sortedBy { it.second })
        assertEquals(rows, DepartureRows.glanceFolded(rows, emptyList()) { false })
    }

    @Test
    fun `glanceFolded puts fresh rows ahead of stale ones again after the fold`() {
        val stale = rowsFor("A", "Stop A", departure("55", "55", "outbound", "Bakerloo", 30, mode = "bus"))
        val fresh = rowsFor("B", "Stop B", departure("73", "73", "outbound", "Oxford Circus", 90, mode = "bus"))
        val folded = DepartureRows.glanceFolded(fresh + stale, listOf("A", "B")) { it.stopId == "A" }
        assertEquals(listOf("B", "A"), folded.map { it.stopId })
    }

    @Test
    fun `nearbyDeduped keeps both directions of a line`() {
        val out = rowsFor("A", "Stop A", departure("55", "55", "outbound", "Bakerloo", 120, mode = "bus"))
        val inbound = rowsFor("B", "Stop B", departure("55", "55", "inbound", "Walthamstow", 90, mode = "bus"))

        val deduped = DepartureRows.nearbyDeduped(out + inbound, mapOf("A" to 100.0, "B" to 120.0))

        assertEquals(2, deduped.size)
        assertEquals(setOf("outbound", "inbound"), deduped.map { it.direction }.toSet())
    }

    // A stop pair (two poles in one TfL stop area) and a lone southbound pole at another place.
    private fun pole(stopId: String, name: String, direction: String, clusterId: String = "") =
        rowsFor(stopId, name, departure("55", "55", direction, if (direction == "inbound") "North" else "South", 120, mode = "bus"))
            .map { it.copy(clusterId = clusterId) }

    private val pairNorth = pole("PN", "Pair Road", "inbound", "490GPAIR")
    private val pairSouth = pole("PS", "Pair Road", "outbound", "490GPAIR")
    private val loneSouth = pole("LS", "Lone Avenue", "outbound")

    @Test
    fun `nearbyDeduped keeps a route's directions at one place within 50 m`() {
        // The lone pole is 25 m nearer southbound; the pair serves both ways, so both come from it.
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + loneSouth,
            mapOf("PN" to 100.0, "PS" to 128.0, "LS" to 103.0),
        )
        assertEquals(setOf("PN", "PS"), deduped.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyChoices names the stop the fold keeps each line at, directions kept together`() {
        val distances = mapOf("PN" to 100.0, "PS" to 128.0, "LS" to 103.0)
        assertEquals(
            listOf(FoldChoice("55", "inbound", "PN"), FoldChoice("55", "outbound", "PS")),
            DepartureRows.nearbyChoices(pairNorth + pairSouth + loneSouth, distances),
        )
    }

    @Test
    fun `glanceFolded follows the app's choices where the order alone would split a route`() {
        val rows = pairNorth + pairSouth + loneSouth
        val order = listOf("PN", "LS", "PS")
        // By the order alone, southbound shows from the lone pole, a step nearer.
        assertEquals(setOf("PN", "LS"), DepartureRows.glanceFolded(rows, order) { false }.mapTo(HashSet()) { it.stopId })
        // With the app's choices, both directions show from the pair, as on the in-app list.
        val choices = DepartureRows.nearbyChoices(rows, mapOf("PN" to 100.0, "PS" to 128.0, "LS" to 103.0))
        assertEquals(setOf("PN", "PS"), DepartureRows.glanceFolded(rows, order, choices) { false }.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `glanceFolded falls back to the order where a chosen stop has no row for the line`() {
        val rows = pairNorth + loneSouth
        // The app chose the pair's southbound pole, which the glance surface no longer has.
        val choices = listOf(FoldChoice("55", "inbound", "PN"), FoldChoice("55", "outbound", "PS"))
        assertEquals(setOf("PN", "LS"), DepartureRows.glanceFolded(rows, listOf("PN", "LS"), choices) { false }.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped splits a route when the other place is over 50 m nearer`() {
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + loneSouth,
            mapOf("PN" to 100.0, "PS" to 200.0, "LS" to 103.0),
        )
        assertEquals(setOf("PN", "LS"), deduped.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped moves a route only to a place serving every direction`() {
        // The lone pole is nearest southbound and nearly so northbound, but serves only southbound.
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + loneSouth,
            mapOf("PN" to 60.0, "PS" to 200.0, "LS" to 50.0),
        )
        assertEquals(setOf("PN", "LS"), deduped.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped treats two stop areas of one interchange as two places`() {
        // Both areas belong to one interchange but head separate groups: the route is still split
        // across headers, so it moves to the pair serving both ways.
        val areaA = pole("AN", "Area A", "inbound", "490GAREAA").map { it.copy(hubId = "HUBX") }
        val areaB = pole("BS", "Area B", "outbound", "490GAREAB").map { it.copy(hubId = "HUBX") }
        val deduped = DepartureRows.nearbyDeduped(
            areaA + areaB + pairNorth + pairSouth,
            mapOf("AN" to 100.0, "BS" to 100.0, "PN" to 110.0, "PS" to 120.0),
        )
        assertEquals(setOf("PN", "PS"), deduped.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped keys places as the headers do when a pole shows a line warning`() {
        // The pair's southbound pole also shows another route's suspension, so the list heads it on
        // its own: the pair is two headers, and the route stays split rather than "together".
        val warning = lineStatusRow("PS", "Pair Road", "99").copy(clusterId = "490GPAIR")
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + warning + loneSouth,
            mapOf("PN" to 100.0, "PS" to 128.0, "LS" to 103.0),
        )
        assertEquals(setOf("PN", "LS"), deduped.filter { it.upcoming.isNotEmpty() }.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped keeps a pair together when its pole's warning is dismissed`() {
        // Dismissed, the warning is hidden and the pair is one header again, so the route moves to it.
        val warning = lineStatusRow("PS", "Pair Road", "99").copy(clusterId = "490GPAIR")
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + warning + loneSouth,
            mapOf("PN" to 100.0, "PS" to 128.0, "LS" to 103.0),
            dismissed = setOf(DismissedAlert.ofLineStatus(warning.status!!)),
        )
        assertEquals(setOf("PN", "PS"), deduped.filter { it.upcoming.isNotEmpty() }.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped never moves a direction onto a closed stop`() {
        // TfL still lists times at the pair's closed southbound pole; the open lone pole keeps it.
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + stopStatusRow("PS", "Pair Road", clusterId = "490GPAIR") + loneSouth,
            mapOf("PN" to 100.0, "PS" to 128.0, "LS" to 103.0),
        )
        assertEquals(setOf("PN", "LS"), deduped.filter { it.stopDisruption == null }.mapTo(HashSet()) { it.stopId })
        // Its closure notice still shows.
        assertEquals(listOf("PS"), deduped.filter { it.stopDisruption != null }.map { it.stopId })
    }

    @Test
    fun `nearbyDeduped keeps the other direction when one pole of a pair has no times`() {
        // A closed pole TfL lists no times for: the pair no longer serves southbound, so nothing moves.
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + stopStatusRow("PS", "Pair Road", clusterId = "490GPAIR") + loneSouth,
            mapOf("PN" to 100.0, "PS" to 128.0, "LS" to 103.0),
        )
        assertEquals(setOf("PN", "LS"), deduped.filter { it.stopDisruption == null }.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped keeps a route together at the place whose farther stop is nearest`() {
        val otherNorth = pole("ON", "Other Street", "inbound", "490GOTHER")
        val otherSouth = pole("OS", "Other Street", "outbound", "490GOTHER")
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + otherNorth + otherSouth + loneSouth,
            mapOf("PN" to 100.0, "PS" to 140.0, "ON" to 110.0, "OS" to 120.0, "LS" to 95.0),
        )
        assertEquals(setOf("ON", "OS"), deduped.mapTo(HashSet()) { it.stopId })
    }

    // A second route on the pair's road, timed southbound only.
    private fun otherRoute(stopId: String, name: String, clusterId: String = "") =
        rowsFor(stopId, name, departure("77", "77", "outbound", "South", 300, mode = "bus"))
            .map { it.copy(clusterId = clusterId) }

    @Test
    fun `nearbyDeduped joins a one-way route to the place its neighbors were kept together at`() {
        // Route 55 runs both ways and is kept at the pair; route 77 shows only southbound, nearest
        // at the lone pole but served by the pair's southbound pole within 50 m, so it joins the pair.
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + loneSouth + otherRoute("PS", "Pair Road", "490GPAIR") + otherRoute("LS", "Lone Avenue"),
            mapOf("PN" to 100.0, "PS" to 128.0, "LS" to 98.0),
        )
        assertEquals(setOf("PS"), deduped.filter { it.lineId == "77" }.mapTo(HashSet()) { it.stopId })
        assertEquals(setOf("PN", "PS"), deduped.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped leaves a one-way route at its nearest stop over 50 m away`() {
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + loneSouth + otherRoute("PS", "Pair Road", "490GPAIR") + otherRoute("LS", "Lone Avenue"),
            mapOf("PN" to 100.0, "PS" to 140.0, "LS" to 60.0),
        )
        assertEquals(setOf("LS"), deduped.filter { it.lineId == "77" }.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped leaves a one-way route at its nearest stop when no route is anchored elsewhere`() {
        // Route 55 is absent, so the pair hosts no anchored route and 77 keeps the nearer pole.
        val deduped = DepartureRows.nearbyDeduped(
            otherRoute("PS", "Pair Road", "490GPAIR") + otherRoute("LS", "Lone Avenue"),
            mapOf("PS" to 128.0, "LS" to 98.0),
        )
        assertEquals(listOf("LS"), deduped.map { it.stopId })
    }

    @Test
    fun `nearbyDeduped never joins a one-way route onto a closed stop`() {
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + otherRoute("PS", "Pair Road", "490GPAIR") + otherRoute("LS", "Lone Avenue") +
                stopStatusRow("PS", "Pair Road", clusterId = "490GPAIR"),
            mapOf("PN" to 100.0, "PS" to 128.0, "LS" to 98.0),
        )
        assertEquals(setOf("LS"), deduped.filter { it.lineId == "77" }.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped never joins a one-way route onto a stop with older data`() {
        // The pair's southbound pole kept a stale snapshot after a partial refresh failure.
        val stalePair = otherRoute("PS", "Pair Road", "490GPAIR").map { it.copy(fetchedAt = now.minusSeconds(600)) }
        val deduped = DepartureRows.nearbyDeduped(
            pairNorth + pairSouth + stalePair + otherRoute("LS", "Lone Avenue"),
            mapOf("PN" to 100.0, "PS" to 128.0, "LS" to 98.0),
        )
        assertEquals(setOf("LS"), deduped.filter { it.lineId == "77" }.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped joins a line's no-times row to the place its timed rows were kept at`() {
        // Route 77 is due northbound at the pair and disrupted southbound with no times: its
        // southbound status row is nearest at the lone pole but joins the pair's southbound pole.
        val north77 = rowsFor("PN", "Pair Road", departure("77", "77", "inbound", "North", 300, mode = "bus"))
            .map { it.copy(clusterId = "490GPAIR") }
        val pairStatus = lineStatusRow("PS", "Pair Road", "77").copy(clusterId = "490GPAIR", mode = "bus")
        val loneStatus = lineStatusRow("LS", "Lone Avenue", "77").copy(mode = "bus")
        val deduped = DepartureRows.nearbyDeduped(
            north77 + pairStatus + loneStatus,
            mapOf("PN" to 97.0, "PS" to 129.0, "LS" to 98.0),
        )
        assertEquals(setOf("PN", "PS"), deduped.filter { it.lineId == "77" }.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped leaves a no-times row at its nearest stop over 50 m away`() {
        val north77 = rowsFor("PN", "Pair Road", departure("77", "77", "inbound", "North", 300, mode = "bus"))
            .map { it.copy(clusterId = "490GPAIR") }
        val pairStatus = lineStatusRow("PS", "Pair Road", "77").copy(clusterId = "490GPAIR", mode = "bus")
        val loneStatus = lineStatusRow("LS", "Lone Avenue", "77").copy(mode = "bus")
        val deduped = DepartureRows.nearbyDeduped(
            north77 + pairStatus + loneStatus,
            mapOf("PN" to 97.0, "PS" to 160.0, "LS" to 98.0),
        )
        assertEquals(setOf("PN", "LS"), deduped.filter { it.lineId == "77" }.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `nearbyDeduped keeps fully unresolved opposite directions at separate stops`() {
        // TfL gave neither direction nor destination; only the (stop-local) platform told the
        // two apart. Keyed on a blank cross-stop direction they would collapse and drop the
        // farther direction — so a fully-unresolved row stays stop-specific and both survive.
        val a = rowsFor("A", "Stop A", departure("55", "55", "", "", 120, platform = "Stop A", mode = "bus"))
        val b = rowsFor("B", "Stop B", departure("55", "55", "", "", 60, platform = "Stop B", mode = "bus"))

        val deduped = DepartureRows.nearbyDeduped(a + b, mapOf("A" to 100.0, "B" to 400.0))

        assertEquals(2, deduped.size)
        assertEquals(setOf("A", "B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped never crowds out a lone farther mode`() {
        // Many nearby bus lines and one farther Tube line. With no count cap, every distinct
        // line survives — the Tube is not evicted for being farther than the buses.
        val bus1 = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 60, mode = "bus"))
        val bus2 = rowsFor("A2", "Stop A2", departure("12", "12", "outbound", "Y", 90, mode = "bus"))
        val bus3 = rowsFor("A3", "Stop A3", departure("36", "36", "outbound", "Z", 120, mode = "bus"))
        val tube = rowsFor("T", "Tube", departure("victoria", "Victoria", "inbound", "Brixton", 300, mode = "tube"))

        val deduped = DepartureRows.nearbyDeduped(
            bus1 + bus2 + bus3 + tube,
            mapOf("A" to 100.0, "A2" to 150.0, "A3" to 200.0, "T" to 800.0),
        )

        assertEquals(4, deduped.size)
        assertTrue(deduped.any { it.lineId == "victoria" })
    }

    @Test
    fun `stopStatusFolded keeps a hub-wide notice once on a list without distances`() {
        // A searched station's page (no distances): a lift notice TfL reports against each member of
        // the interchange is one card, kept in the first member's place, while line rows all stay.
        val notice = "No Step Free Access - Step free access is not available to the Victoria line"
        val hub = "King's Cross & St Pancras International"
        val line = lineStatusRow("KGX", "King's Cross St. Pancras", "victoria")
        val folded = DepartureRows.stopStatusFolded(
            listOf(
                stopStatusRow("KGX", "King's Cross St. Pancras", notice, hubId = "HUBKGX", hubName = hub),
                line,
                stopStatusRow("STP1", "St Pancras International", notice, hubId = "HUBKGX", hubName = hub),
                stopStatusRow("STP2", "St Pancras International", notice, hubId = "HUBKGX", hubName = hub),
            ),
        )
        assertEquals(listOf("KGX", "KGX"), folded.map { it.stopId })
        assertEquals(notice, folded[0].stopDisruption)
        assertEquals(line, folded[1])
    }

    @Test
    fun `stopStatusFolded keeps distinct places and distinct notices apart`() {
        val folded = DepartureRows.stopStatusFolded(
            listOf(
                stopStatusRow("A", "Alpha", "Station closed"),
                stopStatusRow("B", "Beta", "Station closed"),
                stopStatusRow("K1", "Hub One", "Lifts out", hubId = "HUBX"),
                stopStatusRow("K2", "Hub Two", "Escalator out", hubId = "HUBX"),
            ),
        )
        assertEquals(listOf("A", "B", "K1", "K2"), folded.map { it.stopId })
    }

    @Test
    fun `nearbyDeduped folds a hub-wide notice across an interchange onto the nearest member`() {
        // A hub-wide notice (a lift outage) is reported by TfL against every stop point in an
        // interchange. Those members carry distinct stop ids but one shared hubNaptanCode
        // (King's Cross and St Pancras are both HUBKGX), so the notice folds by HUB IDENTITY:
        // kept once, on the nearest member, and titled by the interchange.
        val notice = "No step free access to the Thameslink platforms due to faulty lifts"
        val hub = "King's Cross & St Pancras International"
        val deduped = DepartureRows.nearbyDeduped(
            listOf(
                stopStatusRow("STP1", "St Pancras International", notice, hubId = "HUBKGX", hubName = hub),
                stopStatusRow("STP2", "St Pancras International", notice, hubId = "HUBKGX", hubName = hub),
                stopStatusRow("KGX", "King's Cross St. Pancras", notice, hubId = "HUBKGX", hubName = hub),
            ),
            mapOf("STP1" to 120.0, "STP2" to 150.0, "KGX" to 370.0),
        )

        assertEquals(1, deduped.size)
        assertEquals("STP1", deduped[0].stopId)
        // The kept card carries the interchange name, so it titles by the hub rather than one member.
        assertEquals(hub, deduped[0].hubName)
    }

    @Test
    fun `nearbyDeduped folds a bus-stop closure reported once per pole of one junction`() {
        // A closed bus stop is reported by TfL against each pole, which share no hub but do share a
        // cluster (`stationNaptan`, else the name). Folding at the cluster collapses the identical
        // notice to one card on the nearest pole, rather than a card per pole. Stand-in stop name +
        // example bus-stop ids only (SPEC *Privacy*).
        val notice = "Bus Stop Closed"
        val deduped = DepartureRows.nearbyDeduped(
            listOf(
                stopStatusRow("490000001E", "Example Road", notice, clusterId = "490G000EXAMPLE"),
                stopStatusRow("490000001W", "Example Road", notice, clusterId = "490G000EXAMPLE"),
                stopStatusRow("490000001N", "Example Road", notice, clusterId = "490G000EXAMPLE"),
            ),
            mapOf("490000001E" to 40.0, "490000001W" to 55.0, "490000001N" to 60.0),
        )

        assertEquals(1, deduped.size)
        assertEquals("490000001E", deduped[0].stopId)
    }

    @Test
    fun `nearbyDeduped folds a hub notice led by one member's name across differently-named members`() {
        // TfL attaches one hub-wide notice, worded with a single member's name ("Bank: No Step
        // Free Access - …"), to every member of the hub — here Bank and Monument, which share one
        // hub but have different names. The place-name strip is per-member and would clean only
        // Bank's copy, so keying the fold on the stripped text would split the shared notice into
        // two cards. Because `across` stores the NORMALIZED (not stripped) text, both members carry
        // identical text and the fold collapses them to one card (Codex, PR #106).
        val notice = "Bank: No Step Free Access - Step free access is not available to/from the " +
            "King William Street entrance due to faulty lifts."
        val bank = StopArrivals(
            "940GZZLUBNK", "Bank", departures = emptyList(), fetchedAt = now,
            disruptions = listOf(StopDisruption(notice)), hubId = "HUBBAN", hubName = "Bank & Monument",
        )
        val monument = StopArrivals(
            "940GZZLUMND", "Monument", departures = emptyList(), fetchedAt = now,
            disruptions = listOf(StopDisruption(notice)), hubId = "HUBBAN", hubName = "Bank & Monument",
        )

        val rows = DepartureRows.across(listOf(bank, monument), now)
        val deduped = DepartureRows.nearbyDeduped(rows, mapOf("940GZZLUBNK" to 80.0, "940GZZLUMND" to 220.0))

        val closures = deduped.filter { it.stopDisruption != null }
        assertEquals(1, closures.size)
        assertEquals("940GZZLUBNK", closures[0].stopId)
    }

    @Test
    fun `nearbyDeduped keeps two unrelated same-named stops apart when the cluster is only a name fallback`() {
        // Two genuinely unrelated bus stops share a display name but have no TfL `stationNaptan`, so
        // each cluster falls back to the name (clusterId == stopName). Folding on that would collapse
        // them and hide one location's closure, so the fold ignores a name-fallback cluster and keys
        // each on its own stop id — both cards survive (Codex, principle 1 — no warning dropped).
        val notice = "Bus Stop Closed"
        val deduped = DepartureRows.nearbyDeduped(
            listOf(
                stopStatusRow("490000010A", "Church Road", notice, clusterId = "Church Road"),
                stopStatusRow("490000020B", "Church Road", notice, clusterId = "Church Road"),
            ),
            mapOf("490000010A" to 120.0, "490000020B" to 300.0),
        )

        assertEquals(2, deduped.size)
        assertEquals(setOf("490000010A", "490000020B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped keeps two same-named closures apart when their clusters differ`() {
        // Two genuinely distinct stops that happen to share a name but sit in different clusters are
        // different places — each keeps its card, no warning dropped (principle 1).
        val notice = "Bus Stop Closed"
        val deduped = DepartureRows.nearbyDeduped(
            listOf(
                stopStatusRow("A", "High Street", notice, clusterId = "490G000HighStreetN"),
                stopStatusRow("B", "High Street", notice, clusterId = "490G000HighStreetS"),
            ),
            mapOf("A" to 100.0, "B" to 300.0),
        )

        assertEquals(2, deduped.size)
        assertEquals(setOf("A", "B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped keeps two unrelated place-less closures apart by hub identity`() {
        // TfL's text names no stop ("Station closed until further notice."), so two UNRELATED
        // closures share identical text. They belong to different (here blank) hubs, so hub
        // identity keys them apart — each keeps its own card, no warning hidden (principle 1).
        // This is what folding by hub, not by text alone, buys over the earlier text-only dedup.
        val notice = "Station closed until further notice."
        val deduped = DepartureRows.nearbyDeduped(
            listOf(stopStatusRow("A", "Highbury & Islington", notice), stopStatusRow("B", "Canonbury", notice)),
            mapOf("A" to 100.0, "B" to 300.0),
        )

        assertEquals(2, deduped.size)
        assertEquals(setOf("A", "B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped keeps two different stop closures`() {
        // Different notices are different warnings — TfL's text names the affected place — so
        // both are kept, whatever their distance (SPEC principle 2, no warning dropped).
        val deduped = DepartureRows.nearbyDeduped(
            listOf(
                stopStatusRow("A", "Stop A", "Stop A closed until further notice"),
                stopStatusRow("B", "Stop B", "Stop B moved to Pancras Road"),
            ),
            mapOf("A" to 100.0, "B" to 200.0),
        )

        assertEquals(2, deduped.size)
        assertEquals(setOf("A", "B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped keeps a card for each different notice within one hub`() {
        // Two different notices reported against the same interchange are two warnings, so the
        // fold-by-(hub, text) keeps a card for each rather than collapsing on hub alone.
        val hub = "King's Cross & St Pancras International"
        val deduped = DepartureRows.nearbyDeduped(
            listOf(
                stopStatusRow("KGX", "King's Cross St. Pancras", "Lifts out of service", hubId = "HUBKGX", hubName = hub),
                stopStatusRow("STP", "St Pancras International", "Escalator maintenance", hubId = "HUBKGX", hubName = hub),
            ),
            mapOf("KGX" to 100.0, "STP" to 200.0),
        )

        assertEquals(2, deduped.size)
        assertEquals(
            setOf("Lifts out of service", "Escalator maintenance"),
            deduped.mapNotNull { it.stopDisruption }.toSet(),
        )
    }

    @Test
    fun `nearbyDeduped keeps blank-direction rows stop-specific even with a shared destination`() {
        // TfL omits `direction`, so there is no cross-stop direction identity. Two opposite
        // one-way stops of a line can share a destination, and `destination` can't reconstruct
        // a direction (SPEC), so blank-`direction` rows are never merged across stops — the
        // safe over-show (a same-direction service may then show at both adjacent stops)
        // against ever collapsing two opposite directions into one row (maintainer). Both
        // stops' rows survive; the earlier destination-based collapse is gone.
        val near = rowsFor("A", "Stop A", departure("55", "55", "", "Bakerloo", 120, platform = "Stop A / 1", mode = "bus"))
        val far = rowsFor("B", "Stop B", departure("55", "55", "", "Bakerloo", 60, platform = "Stop B / 2", mode = "bus"))

        val deduped = DepartureRows.nearbyDeduped(near + far, mapOf("A" to 100.0, "B" to 400.0))

        assertEquals(2, deduped.size)
        assertEquals(setOf("A", "B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped keeps blank-lineId rows stop-specific even with a shared direction`() {
        // TfL omits `lineId` on some predictions; `lineName` alone can name a different route.
        // Two unrelated routes at adjacent stops that share a nonblank `direction` must not
        // collapse onto one `(blank lineId, direction)` key and drop the farther one — a blank
        // line has no cross-stop identity, so the row stays stop-specific (Codex).
        val near = rowsFor("A", "Stop A", departure("", "N55", "inbound", "Aldwych", 120, mode = "bus"))
        val far = rowsFor("B", "Stop B", departure("", "24", "inbound", "Pimlico", 60, mode = "bus"))

        val deduped = DepartureRows.nearbyDeduped(near + far, mapOf("A" to 100.0, "B" to 400.0))

        assertEquals(2, deduped.size)
        assertEquals(setOf("A", "B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped keeps both platforms of one service at a single stop`() {
        // Two platforms of the same line and destination at ONE stop are distinct rows there;
        // the collapse is across stops, so it must not drop the second platform's departures.
        val rows = DepartureRows.forStop(
            "A", "Stop A",
            listOf(
                departure("55", "55", "", "Bakerloo", 120, platform = "Platform 1", mode = "bus"),
                departure("55", "55", "", "Bakerloo", 60, platform = "Platform 2", mode = "bus"),
            ),
            now,
        )
        assertEquals("the stop itself has two platform rows", 2, rows.size)

        val deduped = DepartureRows.nearbyDeduped(rows, mapOf("A" to 100.0))

        assertEquals(2, deduped.size)
        assertEquals(setOf("Platform 1", "Platform 2"), deduped.map { it.directionKey }.toSet())
    }

    @Test
    fun `nearbyDeduped treats a stop missing from the distance map as farthest`() {
        val known = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 120, mode = "bus"))
        val unknown = rowsFor("B", "Stop B", departure("55", "55", "outbound", "X", 60, mode = "bus"))

        // B is absent from the map, so it sorts behind A's known distance rather than winning.
        val deduped = DepartureRows.nearbyDeduped(known + unknown, mapOf("A" to 500.0))

        assertEquals(1, deduped.size)
        assertEquals("A", deduped[0].stopId)
    }

    // --- byStopDistance: the near-me display order (closest stop first, soonest same-stop tie) ---

    @Test
    fun `byStopDistance puts the closest stop first even when a farther stop leaves sooner`() {
        // Stop A is nearer; stop B is farther but its bus leaves sooner. Closest-first means the
        // near stop leads — you'd walk to the one at your feet, not chase the far sooner one.
        val near = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 600, mode = "bus"))
        val far = rowsFor("B", "Stop B", departure("134", "134", "outbound", "Y", 60, mode = "bus"))

        val ordered = DepartureRows.byStopDistance(near + far, mapOf("A" to 100.0, "B" to 400.0))

        assertEquals(listOf("A", "B"), ordered.map { it.stopId })
    }

    @Test
    fun `byStopDistance breaks a same-stop tie soonest-first`() {
        // Two lines at ONE stop (identical distance): distance can't order them, so the sooner
        // service leads. Order them into the list farther-first to prove the sort, not input order.
        val laterAtStop = rowsFor("A", "Stop A", departure("134", "134", "outbound", "Y", 540, mode = "bus"))
        val soonerAtStop = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 120, mode = "bus"))

        val ordered = DepartureRows.byStopDistance(laterAtStop + soonerAtStop, mapOf("A" to 100.0))

        assertEquals(listOf("55", "134"), ordered.map { it.lineId })
    }

    @Test
    fun `byStopDistance sorts a stop missing from the distance map last`() {
        val known = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 600, mode = "bus"))
        val unknown = rowsFor("B", "Stop B", departure("134", "134", "outbound", "Y", 60, mode = "bus"))

        // B is absent from the map → farthest, so it trails A despite leaving sooner.
        val ordered = DepartureRows.byStopDistance(known + unknown, mapOf("A" to 500.0))

        assertEquals(listOf("A", "B"), ordered.map { it.stopId })
    }

    @Test
    fun `byStopDistance keeps equidistant distinct stops grouped, not time-interleaved`() {
        // Two distinct stops that compute the same distance (e.g. StopPoints sharing
        // coordinates). Stop A has a soon and a late departure; stop B one in between. By
        // distance→time alone the order would interleave A(soon), B(mid), A(late); grouping by
        // stop id first keeps each stop's rows together (soonest is a same-stop tiebreak only).
        val aSoon = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 60, mode = "bus"))
        val aLate = rowsFor("A", "Stop A", departure("134", "134", "outbound", "Y", 600, mode = "bus"))
        val bMid = rowsFor("B", "Stop B", departure("43", "43", "outbound", "Z", 120, mode = "bus"))

        val ordered = DepartureRows.byStopDistance(aSoon + aLate + bMid, mapOf("A" to 100.0, "B" to 100.0))

        // A's two rows are adjacent (grouped), not split by B's row.
        assertEquals(listOf("A", "A", "B"), ordered.map { it.stopId })
    }

    @Test
    fun `byStopDistance keeps a closure leading above a nearer timed row`() {
        // A closure at a FAR stop must still lead above a NEAR stop's departures — it renders as a
        // standalone card ahead of the groups, so it leads the flat list too (SPEC principle 2).
        val nearTimed = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 120, mode = "bus"))
        val farClosure = listOf(stopStatusRow("Z", "Stop Z"))

        val ordered = DepartureRows.byStopDistance(nearTimed + farClosure, mapOf("A" to 100.0, "Z" to 900.0))

        assertEquals("Z", ordered.first().stopId)
        assertTrue("the closure leads", ordered.first().stopDisruption != null)
    }

    @Test
    fun `byStopDistance does not hoist a far stop's line-status alert above a nearer stop`() {
        // A suspended line (a "No departures" line-status row) at a FAR stop must NOT jump ahead of a
        // NEARER stop's departures — a stop is not hoisted just for carrying an alert (maintainer,
        // 2026-09-22). The alert's card still leads within its own stop's section (the near stop is
        // just first overall). Unlike a closure, it is not a standalone card, so distance decides.
        val nearTimed = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 120, mode = "bus"))
        val farAlert = listOf(lineStatusRow("Z", "Stop Z", "victoria"))

        val ordered = DepartureRows.byStopDistance(nearTimed + farAlert, mapOf("A" to 100.0, "Z" to 900.0))

        // The nearer stop leads; the far alert follows at its distance rather than being hoisted.
        assertEquals(listOf("A", "Z"), ordered.map { it.stopId })
    }

    @Test
    fun `byStopDistance rides a stop's alert with the stop, trailing its timed rows`() {
        // At one stop, a suspended line (no countdown) and a timed line: the alert gets no special
        // order for being an alert — it rides with the stop, trailing the timed departure rather than
        // leading it (maintainer, 2026-09-22: "no special order based on alert").
        val timed = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 120, mode = "bus"))
        val alert = listOf(lineStatusRow("A", "Stop A", "victoria"))

        val ordered = DepartureRows.byStopDistance(timed + alert, mapOf("A" to 100.0))

        assertEquals("55", ordered.first().lineId)
        assertTrue("the alert trails the stop's timed rows", ordered.last().upcoming.isEmpty())
    }

    // A minimal Northern-line topology: the two central trunks share the northern leg
    // (High Barnet → Camden) and the southern leg (Kennington → Morden), and differ only
    // between — Mornington Crescent (MTC) on Charing X, Bank (BNK) on Bank.
    private val northernTopology = RouteTopology(
        mapOf(
            "northern" to listOf(
                RoutePattern("Bank", listOf(HBT, HGT, CTN, EUS, BNK, KNG, MDN), "High Barnet", "Morden"),
                RoutePattern("Charing X", listOf(HBT, HGT, CTN, MTC, EUS, CHX, KNG, MDN), "High Barnet", "Morden"),
            ),
        ),
    )

    private fun rowAt(stopId: String, vararg upcoming: Departure): DepartureRow {
        val soonest = upcoming.first()
        return DepartureRow(
            stopId = stopId,
            stopName = stopId,
            lineId = soonest.lineId,
            lineName = soonest.lineName,
            direction = soonest.direction,
            directionKey = soonest.direction,
            destination = soonest.destination,
            mode = soonest.mode,
            upcoming = upcoming.toList(),
            fetchedAt = now,
        )
    }

    @Test
    fun `destinationLines merges two branches past the junction, dropping the label`() {
        // Highgate → High Barnet (north of Camden Town): the trunks have physically joined, so the
        // two are the same service from here — one merged line, no branch label (the maintainer's ask).
        val row = rowAt(
            HGT,
            departure("northern", "Northern", "northbound", "High Barnet", 120, branch = "Bank"),
            departure("northern", "Northern", "northbound", "High Barnet", 300, branch = "Charing X"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3, topology = northernTopology)
        assertEquals(1, lines.size)
        assertEquals("High Barnet", lines[0].destination)
        assertNull(lines[0].branch)
        assertEquals(2, lines[0].times.size)
    }

    @Test
    fun `destinationLines keeps both branches where the trunk is a choice ahead`() {
        // Southbound at Camden Town toward Morden: the trunks haven't split behind you — they
        // diverge ahead (different central stations), so both lines stay, each labeled.
        val row = rowAt(
            CTN,
            departure("northern", "Northern", "southbound", "Morden", 120, branch = "Bank"),
            departure("northern", "Northern", "southbound", "Morden", 300, branch = "Charing X"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3, topology = northernTopology)
        assertEquals(2, lines.size)
        assertEquals(listOf("Bank", "Charing X"), lines.map { it.branch })
        lines.forEach { assertEquals("Morden", it.destination) }
    }

    @Test
    fun `destinationLines keeps the branch at Euston toward High Barnet (Mornington Crescent)`() {
        // The edge case: at Euston (on both trunks) a High Barnet train's branch still tells the
        // rider whether it stops at Mornington Crescent, so it is a genuine choice and stays.
        val row = rowAt(
            EUS,
            departure("northern", "Northern", "northbound", "High Barnet", 120, branch = "Bank"),
            departure("northern", "Northern", "northbound", "High Barnet", 300, branch = "Charing X"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3, topology = northernTopology)
        assertEquals(2, lines.size)
        assertEquals(listOf("Bank", "Charing X"), lines.map { it.branch })
    }

    @Test
    fun `destinationLines drops the branch at a single-branch stop for a short-working`() {
        // The King's Cross case: a Bank-only stop, a train tagged "Bank" bound for a short-working
        // the asset models as no pattern's terminus. There is no other trunk to choose, so "/Bank"
        // adds nothing and is dropped (the user's ask) even though grouping falls back to raw.
        val row = rowAt(
            BNK,
            departure("northern", "Northern", "northbound", "Golders Green", 120, branch = "Bank"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3, topology = northernTopology)
        assertEquals(1, lines.size)
        assertEquals("Golders Green", lines[0].destination)
        assertNull(lines[0].branch)
    }

    @Test
    fun `destinationLines with the empty topology keeps every raw branch, merging nothing`() {
        // The default: no topology, so the pre-topology behavior — each branch is its own line.
        val row = rowAt(
            CTN,
            departure("northern", "Northern", "northbound", "High Barnet", 120, branch = "Bank"),
            departure("northern", "Northern", "northbound", "High Barnet", 300, branch = "Charing X"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        assertEquals(2, lines.size)
        assertEquals(listOf("Bank", "Charing X"), lines.map { it.branch })
    }

    // --- withoutDismissed: hiding the stop-closure alerts the user tapped away ---

    @Test
    fun `a dismissed line status keeps the departures, drops the warning, and returns on escalation`() {
        val stop = StopArrivals(
            "940GZZLUVIC", "Victoria",
            departures = listOf(Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(120), "tube")),
            fetchedAt = now,
        )
        val minor = LineStatus("victoria", 9, "Minor Delays", "Victoria line: minor delays.")
        val rows = DepartureRows.across(listOf(stop), now, mapOf("victoria" to minor))
        val dismissed = setOf(DismissedAlert.ofLineStatus(minor))

        val shown = DepartureRows.withoutDismissed(rows, dismissed).single()
        assertNull(shown.status)
        assertTrue(shown.statusDismissed)
        assertEquals(rows.single().upcoming, shown.upcoming)

        // Escalated to severe: a new alert, shown again.
        val severe = minor.copy(severity = 6, description = "Severe Delays")
        val escalated = DepartureRows.across(listOf(stop), now, mapOf("victoria" to severe))
        assertEquals(severe, DepartureRows.withoutDismissed(escalated, dismissed).single().status)
    }

    @Test
    fun `a dismissed status-only row is removed, since it exists only for the alert`() {
        // A suspended line with no predictions: its one row is the alert itself.
        val stop = StopArrivals(
            "940GZZLUVIC", "Victoria", departures = emptyList(), fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        val suspended = LineStatus("victoria", 1, "Suspended")
        val rows = DepartureRows.across(listOf(stop), now, mapOf("victoria" to suspended))
        assertEquals(1, rows.size)

        assertTrue(DepartureRows.withoutDismissed(rows, setOf(DismissedAlert.ofLineStatus(suspended))).isEmpty())
    }

    @Test
    fun `a one-way dismissal leaves a directionless row the other way's alert`() {
        // A status-only row (no predictions) has no direction, so it carries the line-wide status:
        // the worse way's alert. Dismissing that on a row going its way leaves the other way's.
        val stop = StopArrivals(
            "940GZZLUVIC", "Victoria", departures = emptyList(), fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        val inbound = LineStatus("victoria", 6, "Severe Delays", "Victoria line: severe delays inbound.")
        val outbound = LineStatus("victoria", 9, "Minor Delays", "Victoria line: minor delays outbound.")
        val line = inbound.copy(byDirection = mapOf("inbound" to inbound, "outbound" to outbound))
        val rows = DepartureRows.across(listOf(stop), now, mapOf("victoria" to line))

        val shown = DepartureRows.withoutDismissed(rows, setOf(DismissedAlert.ofLineStatus(inbound))).single().status!!
        assertEquals(outbound.description, shown.description)
        assertFalse(shown.forDirection("inbound").disrupted)
        assertEquals(outbound, shown.forDirection("outbound"))

        // Both ways dismissed: the row exists only for the alert, so it goes.
        val both = setOf(DismissedAlert.ofLineStatus(inbound), DismissedAlert.ofLineStatus(outbound))
        assertTrue(DepartureRows.withoutDismissed(rows, both).isEmpty())
    }

    @Test
    fun `live line status alerts are the disrupted lines`() {
        val severe = LineStatus("victoria", 6, "Severe Delays")
        val good = LineStatus("central", LineStatus.GOOD_SERVICE, "Good Service")
        assertEquals(
            setOf(DismissedAlert.ofLineStatus(severe)),
            DepartureRows.liveLineStatusAlerts(mapOf("victoria" to severe, "central" to good)),
        )
    }

    @Test
    fun `a part closure under way behind the alert shown stays live, so its dismissal holds`() {
        // Dismissed while it showed; minor delays, which TfL numbers above "Part Closed", now show over
        // it. Pruning its dismissal would sound it again on a trip it's placed on (Codex, PR #446).
        val closure = PartClosure(11, "Part Closed", "No service between A and C.", listOf(listOf("A", "B", "C")))
        val minor = LineStatus("victoria", 9, "Minor Delays", "Minor delays.", closures = listOf(closure))
        val shownBefore = LineStatus("victoria", 11, "Part Closed", "No service between A and C.")
        val live = DepartureRows.liveLineStatusAlerts(mapOf("victoria" to minor))
        assertTrue(DismissedAlert.ofLineStatus(shownBefore) in live)
        assertTrue(DismissedAlert.ofLineStatus(minor) in live)
    }

    @Test
    fun `a dismissed closure shows again when TfL extends or moves its window`() {
        fun rowsWith(vararg notices: StopDisruption) = DepartureRows.across(
            listOf(
                StopArrivals(
                    "490000001A", "Example Road", departures = emptyList(), fetchedAt = now,
                    disruptions = notices.toList(),
                ),
            ),
            now,
        )
        val start = now.minusSeconds(3600)
        val original = StopDisruption("Bus Stop Closed", validFrom = start, validTo = now.plusSeconds(3600))
        val dismissed = setOf(DismissedAlert.ofStopClosure(rowsWith(original).single()))

        // The same notice, same window: stays dismissed.
        assertTrue(DepartureRows.withoutDismissed(rowsWith(original), dismissed).isEmpty())
        // Same text, later end: a new notice, shown at once.
        val extended = original.copy(validTo = now.plusSeconds(7200))
        assertEquals(1, DepartureRows.withoutDismissed(rowsWith(extended), dismissed).size)
        // Same text, moved start: shown too.
        val moved = original.copy(validFrom = start.plusSeconds(60))
        assertEquals(1, DepartureRows.withoutDismissed(rowsWith(moved), dismissed).size)
    }

    @Test
    fun `a dismissal holds when one of two overlapping windows for the same notice ends`() {
        val short = StopDisruption("Bus Stop Closed", validFrom = now.minusSeconds(60), validTo = now.plusSeconds(60))
        val long = StopDisruption("Bus Stop Closed", validFrom = now.minusSeconds(60), validTo = now.plusSeconds(3600))
        val stop = StopArrivals(
            "490000001A", "Example Road", departures = emptyList(), fetchedAt = now,
            disruptions = listOf(short, long),
        )
        val dismissed = setOf(DismissedAlert.ofStopClosure(DepartureRows.across(listOf(stop), now).single()))

        // The short window has ended; the long one still keeps the card up — and it stays dismissed.
        val later = DepartureRows.across(listOf(stop), now.plusSeconds(120))
        assertEquals(1, later.size)
        assertTrue(DepartureRows.withoutDismissed(later, dismissed).isEmpty())
    }

    @Test
    fun `a dismissal holds when an overlapping later window for the same notice starts`() {
        val now1 = StopDisruption("Bus Stop Closed", validFrom = now.minusSeconds(60), validTo = now.plusSeconds(600))
        val next = StopDisruption("Bus Stop Closed", validFrom = now.plusSeconds(300), validTo = now.plusSeconds(3600))
        val stop = StopArrivals(
            "490000001A", "Example Road", departures = emptyList(), fetchedAt = now,
            disruptions = listOf(now1, next),
        )
        val dismissed = setOf(DismissedAlert.ofStopClosure(DepartureRows.across(listOf(stop), now).single()))

        // The later window starts, then the first ends; TfL's data is unchanged, so it stays dismissed.
        for (t in listOf(now.plusSeconds(400), now.plusSeconds(1200))) {
            val rows = DepartureRows.across(listOf(stop), t)
            assertEquals(1, rows.size)
            assertTrue(DepartureRows.withoutDismissed(rows, dismissed).isEmpty())
        }
    }

    @Test
    fun `a dismissal lapses when two notices at a stop swap windows`() {
        fun rowWith(vararg notices: StopDisruption) = DepartureRows.across(
            listOf(
                StopArrivals(
                    "490000001A", "Example Road", departures = emptyList(), fetchedAt = now,
                    disruptions = notices.toList(),
                ),
            ),
            now,
        )
        val w1 = now.minusSeconds(60) to now.plusSeconds(600)
        val w2 = now.minusSeconds(120) to now.plusSeconds(1200)
        fun notice(text: String, w: Pair<Instant, Instant>) = StopDisruption(text, validFrom = w.first, validTo = w.second)
        val before = rowWith(notice("Bus Stop Closed", w1), notice("Lift out of service", w2))
        val dismissed = setOf(DismissedAlert.ofStopClosure(before.single()))

        // Same texts, windows swapped: a change, so the card is back.
        val after = rowWith(notice("Bus Stop Closed", w2), notice("Lift out of service", w1))
        assertEquals(1, DepartureRows.withoutDismissed(after, dismissed).size)
    }

    @Test
    fun `a formatting-only change to a dated notice keeps its dismissal`() {
        fun rowWith(text: String) = DepartureRows.across(
            listOf(
                StopArrivals(
                    "490000001A", "Example Road", departures = emptyList(), fetchedAt = now,
                    disruptions = listOf(
                        StopDisruption(text, validFrom = now.minusSeconds(60), validTo = now.plusSeconds(600)),
                    ),
                ),
            ),
            now,
        )
        val dismissed = setOf(DismissedAlert.ofStopClosure(rowWith("Bus Stop Closed\\n    Use the next stop").single()))

        // Escaped vs real line breaks: the same notice as shown, so it stays dismissed.
        assertTrue(DepartureRows.withoutDismissed(rowWith("Bus Stop Closed\nUse the next stop"), dismissed).isEmpty())
    }

    @Test
    fun `a folded closure's dismissal survives a different member becoming nearest`() {
        // Two poles of one StopArea report one closure under slightly different windows.
        fun pole(id: String, end: Long) = StopArrivals(
            id, "Example Road", departures = emptyList(), fetchedAt = now, clusterId = "490G000EXAMPLE",
            disruptions = listOf(
                StopDisruption("Bus Stop Closed", validFrom = now.minusSeconds(60), validTo = now.plusSeconds(end)),
            ),
        )
        val rows = DepartureRows.across(listOf(pole("490000001E", 600), pole("490000001W", 900)), now)
        fun folded(eastM: Double, westM: Double) =
            DepartureRows.nearbyDeduped(rows, mapOf("490000001E" to eastM, "490000001W" to westM)).single()

        val dismissed = setOf(DismissedAlert.ofStopClosure(folded(40.0, 55.0)))

        val afterMoving = folded(70.0, 30.0)
        assertEquals("490000001W", afterMoving.stopId)
        assertTrue(DepartureRows.withoutDismissed(listOf(afterMoving), dismissed).isEmpty())
    }

    @Test
    fun `a folded closure's dismissal counts as live when reconciling the unfolded rows`() {
        // Reconciliation sees the unfolded per-stop rows; the folded card's identity must still be
        // live there, or the next refresh would prune its dismissal and bring the card back.
        fun pole(id: String, end: Long) = StopArrivals(
            id, "Example Road", departures = emptyList(), fetchedAt = now, clusterId = "490G000EXAMPLE",
            disruptions = listOf(
                StopDisruption("Bus Stop Closed", validFrom = now.minusSeconds(60), validTo = now.plusSeconds(end)),
            ),
        )
        val rows = DepartureRows.across(listOf(pole("490000001E", 600), pole("490000001W", 900)), now)
        val folded = DepartureRows.nearbyDeduped(rows, mapOf("490000001E" to 40.0, "490000001W" to 55.0)).single()
        val dismissed = setOf(DismissedAlert.ofStopClosure(folded))

        val live = DepartureRows.liveStopClosureAlerts(rows)
        assertEquals(dismissed, Dismissed.reconcile(dismissed, live, setOf("490G000EXAMPLE")))
    }

    @Test
    fun `an undated closure keeps its text-only dismissal identity`() {
        val stop = StopArrivals(
            "490000001A", "Example Road", departures = emptyList(), fetchedAt = now,
            disruptions = listOf(StopDisruption("Bus Stop Closed")),
        )
        val row = DepartureRows.across(listOf(stop), now).single()
        assertEquals(DismissedAlert("490000001A", "Bus Stop Closed"), DismissedAlert.ofStopClosure(row))
    }

    @Test
    fun `withoutDismissed drops a dismissed closure and keeps the rest`() {
        val kept = stopStatusRow("B", "Stop B", "Escalator out of service", clusterId = "490G000B")
        val dismissedRow = stopStatusRow("A", "Stop A", "Bus Stop Closed", clusterId = "490G000A")
        val rows = listOf(dismissedRow, kept)

        val result = DepartureRows.withoutDismissed(rows, setOf(DismissedAlert.ofStopClosure(dismissedRow)))

        assertEquals(listOf(kept), result)
    }

    @Test
    fun `withoutDismissed shows a closure again once its notice text changes`() {
        // The dismissal was recorded against the OLD text; the place now carries a reworded notice,
        // whose signature differs, so it is not matched and the card returns (reappear-on-change).
        val old = stopStatusRow("A", "Stop A", "Bus Stop Closed", clusterId = "490G000A")
        val reworded = stopStatusRow("A", "Stop A", "Bus Stop Closed until 5pm", clusterId = "490G000A")

        val result = DepartureRows.withoutDismissed(listOf(reworded), setOf(DismissedAlert.ofStopClosure(old)))

        assertEquals(listOf(reworded), result)
    }

    @Test
    fun `withoutDismissed never drops a timed or line-status row`() {
        // A closure dismissal drops only the closure row; a same-place timed row is untouched even
        // though the dismissal shares its place (a closed stop's departures still show).
        val timed = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 60, mode = "bus"))
        val closure = stopStatusRow("A", "Stop A", "Bus Stop Closed", clusterId = "490G000A")

        val result = DepartureRows.withoutDismissed(timed + closure, setOf(DismissedAlert.ofStopClosure(closure)))

        assertEquals(timed, result)
    }

    @Test
    fun `withoutDismissed returns the rows unchanged when nothing is dismissed`() {
        val rows = listOf(stopStatusRow("A", "Stop A", "Closed"))
        assertEquals(rows, DepartureRows.withoutDismissed(rows, emptySet()))
    }

    private companion object {
        const val HBT = "940GZZLUHBT"
        const val HGT = "940GZZLUHGT"
        const val CTN = "940GZZLUCTN"
        const val MTC = "940GZZLUMTC"
        const val EUS = "940GZZLUEUS"
        const val BNK = "940GZZLUBNK"
        const val CHX = "940GZZLUCHX"
        const val KNG = "940GZZLUKNG"
        const val MDN = "940GZZLUMDN"
    }

    @Test
    fun `a row a journey card shows in full is hidden below it, one it shows in part is kept`() {
        val bank = departure("northern", "Northern", "outbound", "Morden", 60, branch = "Bank")
        val bank2 = departure("northern", "Northern", "outbound", "Morden", 360, branch = "Bank")
        val charingX = departure("northern", "Northern", "outbound", "Morden", 120, branch = "Charing Cross")
        val nearby = listOf(rowWith(bank, bank2), rowWith(charingX))
        // The card shows every Bank train: that row goes; the Charing Cross row stays.
        assertEquals(listOf(rowWith(charingX)), DepartureRows.withoutShownAbove(nearby, listOf(rowWith(bank, bank2))))
        // A card showing only one of the Bank trains leaves the row (with its other train) in place.
        assertEquals(nearby, DepartureRows.withoutShownAbove(nearby, listOf(rowWith(bank))))
        // Another stop's card changes nothing.
        assertEquals(nearby, DepartureRows.withoutShownAbove(nearby, listOf(rowWith(bank, bank2).copy(stopId = "940GZZLUKSX"))))
    }

    @Test
    fun `a status row says whether its line runs at its stop, worked out with the rows`() {
        val at = Instant.parse("2026-09-18T08:00:00Z")
        val station = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            emptyList(),
            fetchedAt = at,
            lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("piccadilly", "Piccadilly", "tube")),
        )
        val statuses = mapOf(
            "victoria" to LineStatus("victoria", severity = 9, description = "Minor Delays"),
            "piccadilly" to LineStatus("piccadilly", severity = 2, description = "Suspended"),
        )
        val rows = DepartureRows.across(listOf(station), at, statuses).associateBy { it.lineId }
        assertFalse(rows.getValue("victoria").notRunningHere)
        assertTrue(rows.getValue("piccadilly").notRunningHere)
    }


    @Test
    fun `a TfL line on good service with no times gets a quiet row when asked, last`() {
        val victoria = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = listOf(victoria),
            fetchedAt = now,
            lines = listOf(
                LineRef("victoria", "Victoria", "tube"), // has times: no quiet row
                LineRef("northern", "Northern", "tube"), // good service, no times: quiet
                LineRef("circle", "Circle", "tube"), // suspended: its status row, not a quiet one
                LineRef("great-example", "Great Example", "national-rail"), // its board is the answer
            ),
            railFeed = RailFeed.LIVE,
        )
        val statuses = mapOf("circle" to LineStatus("circle", 2, "Suspended"))

        // Not asked (a glance surface): no quiet rows.
        assertTrue(DepartureRows.across(listOf(stop), now, statuses).none { it.quiet })

        val rows = DepartureRows.across(listOf(stop), now, statuses, quietRows = true)
        assertEquals(listOf("circle", "victoria", "northern"), rows.map { it.lineId })
        val quiet = rows.last()
        assertTrue(quiet.quiet)
        assertEquals(null, quiet.status)
        assertEquals(STATUS_DIRECTION_KEY, quiet.directionKey)
        assertTrue(quiet.upcoming.isEmpty())
        assertEquals("tube", quiet.mode)
        assertFalse(rows.first { it.lineId == "circle" }.quiet)
    }

    @Test
    fun `a stale stop gets no quiet rows`() {
        val stop = StopArrivals(
            "940GZZLUKSX", "King's Cross St. Pancras", departures = emptyList(),
            fetchedAt = now.minus(java.time.Duration.ofHours(1)),
            lines = listOf(LineRef("northern", "Northern", "tube")),
        )
        assertTrue(DepartureRows.across(listOf(stop), now, quietRows = true).isEmpty())
    }

    @Test
    fun `a stop with a notice in force gets no quiet candidate`() {
        fun stop(notices: List<StopDisruption>) = StopArrivals(
            "940GZZLUKSX", "King's Cross St. Pancras", departures = emptyList(), fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube")), disruptions = notices,
        )
        val distances = mapOf("940GZZLUKSX" to 100.0)
        fun candidates(notices: List<StopDisruption>) =
            DepartureRows.quietCandidates(listOf(stop(notices)), now, emptyMap(), setOf("victoria"), distances)
        assertEquals(1, candidates(emptyList()).size)
        // Closed: its notice says why there are no times.
        assertTrue(candidates(listOf(StopDisruption("Station closed"))).isEmpty())
        // A notice not yet in force leaves the gap unexplained: the candidate stays.
        val later = StopDisruption("Station closed", validFrom = now.plusSeconds(3_600))
        assertEquals(1, candidates(listOf(later)).size)
        // Its notices couldn't be checked: it may be closed, so no candidate either.
        assertTrue(
            DepartureRows.quietCandidates(listOf(stop(emptyList())), now, emptyMap(), setOf("victoria"), distances, disruptionUnknown = setOf("940GZZLUKSX"))
                .isEmpty(),
        )
    }

    @Test
    fun `a quiet candidate keeps planned work, less what the user dismissed`() {
        val first = PlannedAlert("Part Closure", "No service on Saturday 3 October.", java.time.LocalDate.of(2026, 10, 3))
        val second = PlannedAlert("Part Closure", "No service on Sunday 11 October.", java.time.LocalDate.of(2026, 10, 11))
        val stop = StopArrivals(
            "940GZZLUKSX", "King's Cross St. Pancras", departures = emptyList(), fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(first, second)))
        val distances = mapOf("940GZZLUKSX" to 100.0)
        fun candidate(dismissed: Set<DismissedAlert>) =
            DepartureRows.quietCandidates(listOf(stop), now, statuses, setOf("victoria"), distances, dismissed = dismissed).single()
        assertEquals(listOf(first, second), candidate(emptySet()).plannedAlerts)
        assertEquals(listOf(second), candidate(setOf(DismissedAlert.ofPlanned("victoria", first))).plannedAlerts)
    }

    @Test
    fun `a near-me list's quiet candidates skip a line with trains elsewhere, and keep each stop's, nearest first`() {
        fun stop(id: String, name: String, departures: List<Departure>) = StopArrivals(
            id, name, departures = departures, fetchedAt = now,
            lines = listOf(LineRef("northern", "Northern", "tube"), LineRef("victoria", "Victoria", "tube")),
        )
        val euston = stop("940GZZLUEUS", "Euston", listOf(departure("northern", "Northern", "outbound", "Morden", 120)))
        val kingsCross = stop("940GZZLUKSX", "King's Cross St. Pancras", emptyList())
        val distances = mapOf("940GZZLUKSX" to 100.0, "940GZZLUEUS" to 600.0)
        val shown = DepartureRows.byStopDistance(DepartureRows.across(listOf(euston, kingsCross), now), distances)
        val determined = setOf("northern", "victoria")
        val candidates = DepartureRows.quietCandidates(listOf(euston, kingsCross), now, emptyMap(), determined, distances)
        // The Northern has trains at Euston: no "?" for it. The Victoria has none anywhere: a
        // candidate at each stop, nearest first, the shown one picked once their timetables answer.
        assertEquals(
            listOf("victoria" to "940GZZLUKSX", "victoria" to "940GZZLUEUS"),
            candidates.map { it.lineId to it.stopId },
        )
        assertTrue(candidates.all { it.quiet })
        // A hidden mode adds none.
        assertTrue(DepartureRows.quietCandidates(listOf(euston, kingsCross), now, emptyMap(), determined, distances, setOf("tube")).isEmpty())
        // A line whose status TfL hasn't answered for may be suspended: no "?" for it.
        assertTrue(DepartureRows.quietCandidates(listOf(euston, kingsCross), now, emptyMap(), setOf("northern"), distances).isEmpty())
        // Added, it sits with its stop, after that stop's other rows.
        val merged = DepartureRows.withQuietRows(shown, candidates.take(1), distances)
        assertEquals(shown.size + 1, merged.size)
        assertEquals("940GZZLUKSX", merged.first().stopId)
        assertTrue(merged.first().quiet)
    }

    @Test
    fun `a quiet row keeps its line's planned work, and doesn't split its station's header`() {
        val planned = PlannedAlert("Part Closure", "No service next Saturday.", java.time.LocalDate.of(2099, 1, 3))
        val stop = StopArrivals(
            "940GZZLUKSX", "King's Cross St. Pancras", departures = emptyList(), fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(planned)))
        val quiet = DepartureRows.across(listOf(stop), now, statuses, quietRows = true).single()
        assertTrue(quiet.quiet)
        assertEquals(listOf(planned), quiet.plannedAlerts)
        // A "?" row warns of nothing, so its stop isn't carved out of its place as a warning's is.
        assertTrue(StopGrouping.warnedStopsOf(listOf(quiet)).isEmpty())
        assertEquals(setOf("940GZZLUKSX"), StopGrouping.warnedStopsOf(listOf(quiet.copy(quiet = false))))
    }

    @Test
    fun `a quiet row sorts after its stop's disruption, whatever the line names`() {
        val stop = StopArrivals(
            "940GZZLUKSX", "King's Cross St. Pancras", departures = emptyList(), fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("bakerloo", "Bakerloo", "tube")),
        )
        // The Victoria suspended, the Bakerloo (earlier by name) quiet.
        val statuses = mapOf("victoria" to LineStatus("victoria", 2, "Suspended"))
        val rows = DepartureRows.across(listOf(stop), now, statuses, quietRows = true)
        val sorted = DepartureRows.byStopDistance(rows.reversed(), mapOf("940GZZLUKSX" to 100.0))
        assertEquals(listOf("victoria", "bakerloo"), sorted.map { it.lineId })
        assertTrue(sorted.last().quiet)
    }
}
