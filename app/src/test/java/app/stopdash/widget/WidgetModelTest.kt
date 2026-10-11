package app.stopdash.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.stopdash.domain.AlertBehind
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.WidgetJourney
import app.stopdash.domain.lineAlertFingerprint
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's render decision is derived purely from the snapshot and the clock, so it is
 * exercised here without a Glance host. Stops use `490…` example ids and canned line names
 * (SPEC *Privacy* — never a real watched-stop set).
 */
class WidgetModelTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun departure(lineId: String, offsetSeconds: Long) = Departure(
        lineId = lineId,
        lineName = lineId,
        direction = "inbound",
        destination = "Brixton",
        platform = null,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = "tube",
    )

    // One shared place by default, so a multi-stop fixture is one place and draws no stop header —
    // the budget tests below count departure lines only. A place is its cluster (a blank cluster is
    // a place of its own), so the cluster follows the name: the header tests pass distinct [name]s.
    private fun stop(id: String, departures: List<Departure>, fetchedAt: Instant, name: String = "Example Stop") = StopArrivals(
        id,
        name,
        departures,
        fetchedAt,
        disruptions = emptyList(),
        clusterId = name,
    )

    @Test
    fun `a null snapshot is the empty state`() {
        val model = widgetModel(snapshot = null, now = now)
        assertFalse(model.hasData)
        assertFalse(model.stale)
        assertNull(model.stamp)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `a snapshot with no stops is the empty state`() {
        val model = widgetModel(DeparturesSnapshot(stops = emptyList(), fetchedAt = now), now)
        assertFalse(model.hasData)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `a line two nearby stops serve shows once, from the nearer stop`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(departure("73", 60).copy(mode = "bus")), now.minusSeconds(30)),
                stop("490000001B", listOf(departure("73", 240).copy(mode = "bus")), now.minusSeconds(30)),
            ),
            fetchedAt = now.minusSeconds(30),
            nearestFirst = listOf("490000001B", "490000001A"),
        )
        // The farther stop's bus is sooner, but the nearer stop is the one the rider would walk to.
        assertEquals(listOf("490000001B"), widgetModel(snapshot, now).rows.map { it.row.stopId })
    }

    @Test
    fun `a journey from the nearer stop still drops the farther stop's copy of its line`() {
        val bus = departure("73", 60).copy(mode = "bus")
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(departure("73", 240).copy(mode = "bus")), now.minusSeconds(30)),
                stop("490000001B", listOf(bus), now.minusSeconds(30)),
            ),
            fetchedAt = now.minusSeconds(30),
            journeys = listOf(WidgetJourney("490000001B", setOf(JourneyCall.of(bus)))),
            nearestFirst = listOf("490000001B", "490000001A"),
        )
        // The line once, as the journey's row from the nearer stop; not again from the farther one.
        assertEquals(listOf("490000001B"), widgetModel(snapshot, now).rows.map { it.row.stopId })
    }

    @Test
    fun `without a saved nearest-first order every stop's rows are shown`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(departure("73", 60).copy(mode = "bus")), now.minusSeconds(30)),
                stop("490000001B", listOf(departure("73", 240).copy(mode = "bus")), now.minusSeconds(30)),
            ),
            fetchedAt = now.minusSeconds(30),
        )
        assertEquals(listOf("490000001A", "490000001B"), widgetModel(snapshot, now).rows.map { it.row.stopId })
    }

    @Test
    fun `different lines at two nearby stops are both kept`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(departure("73", 60).copy(mode = "bus")), now.minusSeconds(30)),
                stop("490000001B", listOf(departure("38", 240).copy(mode = "bus")), now.minusSeconds(30)),
            ),
            fetchedAt = now.minusSeconds(30),
            nearestFirst = listOf("490000001B", "490000001A"),
        )
        assertEquals(setOf("73", "38"), widgetModel(snapshot, now).rows.mapTo(HashSet()) { it.row.lineId })
    }

    @Test
    fun `a mode hidden from the near-me list is left out of the widget too`() {
        val bus = departure("73", 60).copy(mode = "bus")
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", listOf(departure("victoria", 120), bus), now.minusSeconds(30))),
            fetchedAt = now.minusSeconds(30),
        )
        assertEquals(listOf("victoria"), widgetModel(snapshot, now, hiddenModes = setOf("bus")).rows.map { it.row.lineId })
    }

    @Test
    fun `when every departure left is of a hidden mode the widget says so`() {
        val bus = departure("73", 60).copy(mode = "bus")
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", listOf(bus), now.minusSeconds(30))),
            fetchedAt = now.minusSeconds(30),
        )
        val model = widgetModel(snapshot, now, hiddenModes = setOf("bus"))
        assertTrue(model.rows.isEmpty())
        assertEquals("Bus", model.onlyHidden)
        // Nothing hidden: no such note.
        assertNull(widgetModel(snapshot, now).onlyHidden)
    }

    @Test
    fun `a fresh snapshot has data, a stamp, and is not stale`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", listOf(departure("victoria", 120)), now.minusSeconds(30))),
            fetchedAt = now.minusSeconds(30),
        )
        val model = widgetModel(snapshot, now)
        assertTrue(model.hasData)
        assertFalse(model.stale)
        // The clock time the data is from, in London (BST in September), not an age a frozen render keeps.
        assertEquals("Updated 08:59", model.stamp)
        assertEquals(listOf("victoria"), model.rows.map { it.row.lineId })
    }

    @Test
    fun `the stamp is a clock time, with the weekday once it isn't today`() {
        assertEquals("Updated 09:00", widgetStamp(now, now))
        assertEquals("Updated Thu 23:50", widgetStamp(Instant.parse("2026-09-17T22:50:00Z"), now))
    }

    @Test
    fun `a snapshot older than the staleness threshold is marked stale`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", listOf(departure("victoria", 120)), now.minusSeconds(600))),
            fetchedAt = now.minusSeconds(600),
        )
        val model = widgetModel(snapshot, now)
        assertTrue(model.hasData)
        assertTrue(model.stale)
    }

    @Test
    fun `an empty list with an unrefreshed stop is uncertain, not a trustworthy none`() {
        // Fresh overall (recent snapshot stamp) but one stop carried arrivalsFresh=false from a
        // partial refresh and has no departures — the empty list can't be trusted as a real "no
        // departures", so the widget must say "may be out of date", not "No upcoming departures".
        val unrefreshed = StopArrivals(
            "490000001A",
            "Example Stop",
            emptyList(),
            now,
            disruptions = emptyList(),
            arrivalsFresh = false,
        )
        val model = widgetModel(DeparturesSnapshot(stops = listOf(unrefreshed), fetchedAt = now), now)
        assertTrue(model.hasData)
        assertFalse("the snapshot is fresh overall", model.stale)
        assertTrue("but an unrefreshed stop makes the empty list uncertain", model.uncertain)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `fresh rows survive the cap ahead of stale ones`() {
        // A partial refresh: six soon departures from a stale stop (its refresh failed, so its
        // rows will be `?`-withheld) plus one later, trustworthy departure from a fresh stop.
        // Soonest-first, the six stale rows precede the fresh one, so a plain take(6) would
        // drop the only live row and the widget would show six `?`. Fresh must be ranked first.
        val staleStop = stop(
            "490000001A",
            (1..6).map { departure("stale$it", it * 60L) },
            fetchedAt = now.minusSeconds(600),
        )
        val freshStop = stop(
            "490000002B",
            listOf(departure("freshline", 900L)),
            fetchedAt = now.minusSeconds(30),
        )
        val snapshot = DeparturesSnapshot(stops = listOf(staleStop, freshStop), fetchedAt = now.minusSeconds(30))
        val model = widgetModel(snapshot, now, maxLines = 6)
        assertEquals(6, model.rows.size)
        assertTrue("the fresh stop's row must survive the cap", model.rows.any { it.row.lineId == "freshline" })
    }

    @Test
    fun `a starred row is pinned to the top past the cap`() {
        // Six soon departures plus a later, starred one. A plain take(6) would drop the starred
        // row (it's the latest), but SPEC D8 requires a starred service at the top — so the
        // widget must load the stars and pin it before the cap, exactly as the in-app list does.
        val soon = stop("490000001A", (1..6).map { departure("line$it", it * 60L) }, now)
        val starredLater = stop("490000002B", listOf(departure("starredline", 900L)), now)
        val snapshot = DeparturesSnapshot(stops = listOf(soon, starredLater), fetchedAt = now)
        val starred = setOf(StarredRow("490000002B", "starredline", "inbound"))
        val model = widgetModel(snapshot, now, starred = starred, maxLines = 6)
        assertEquals(6, model.rows.size)
        assertEquals("the starred service is pinned to the top", "starredline", model.rows.first().row.lineId)
    }

    @Test
    fun `rows are capped so a long list can't overflow the cell`() {
        // Ten distinct lines at one stop; the cap keeps the widget within its cell. Each is a
        // single-destination row (one rendered line), so the line budget maps 1:1 to rows here.
        val departures = (1..10).map { departure("line$it", it * 60L) }
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", departures, now)),
            fetchedAt = now,
        )
        val model = widgetModel(snapshot, now, maxLines = 6)
        assertEquals(6, model.rows.size)
    }

    @Test
    fun `a branching row's destination lines count toward the widget's line budget`() {
        // The Northern-style case: one (line, direction) row branches to two destinations, so it
        // renders two lines. With a plain row-count cap it would count as one and a multi-branch
        // service could push the widget past its height; the budget counts rendered lines, so the
        // branching row (2 lines) plus one single-line row fills a budget of 3 and the third row
        // is dropped — the widget stays bounded instead of overflowing (SPEC D4).
        fun toward(lineId: String, dest: String, offset: Long) = Departure(
            lineId = lineId,
            lineName = lineId,
            direction = "inbound",
            destination = dest,
            platform = null,
            expectedArrival = now.plusSeconds(offset),
            mode = "tube",
        )
        val branching = stop(
            "490000001A",
            listOf(toward("northern", "Morden", 60L), toward("northern", "Battersea Power Station", 120L)),
            now,
        )
        val single1 = stop("490000002B", listOf(departure("victoria", 180L)), now)
        val single2 = stop("490000003C", listOf(departure("central", 240L)), now)
        val snapshot = DeparturesSnapshot(stops = listOf(branching, single1, single2), fetchedAt = now)

        val model = widgetModel(snapshot, now, maxLines = 3)

        val renderedLines = model.rows.sumOf { it.groups.size }
        assertTrue("total rendered lines stay within the budget", renderedLines <= 3)
        assertTrue("the branching service is kept", model.rows.any { it.row.lineId == "northern" })
        assertFalse("the row past the budget is dropped", model.rows.any { it.row.lineId == "central" })
    }

    @Test
    fun `an oversized first branching row is itself bounded to the line budget`() {
        // A single (line, direction) that branches to more destinations than the budget: it must
        // show only as many lines as fit, not expand past the fixed-height cell (SPEC D4). This
        // guards the first-row case the whole-row budget would otherwise admit unbounded.
        fun toward(dest: String, offset: Long) = Departure(
            lineId = "northern",
            lineName = "northern",
            direction = "inbound",
            destination = dest,
            platform = null,
            expectedArrival = now.plusSeconds(offset),
            mode = "tube",
        )
        val many = (1..8).map { toward("Terminus $it", it * 60L) }
        val snapshot = DeparturesSnapshot(stops = listOf(stop("490000001A", many, now)), fetchedAt = now)

        val model = widgetModel(snapshot, now, maxLines = 6)

        assertEquals("only the one service, bounded", 1, model.rows.size)
        assertEquals("its lines are capped to the budget", 6, model.rows.first().groups.size)
    }

    // How many departure lines of one kind fit a widget [height] tall: its rows' height over one line's.
    private fun lines(
        height: Dp,
        fontScale: Float = 1f,
        withNote: Boolean = false,
        compact: Boolean = false,
        stacked: Boolean = false,
    ) = widgetRowsHeight(height, fontScale, withNote, compact) / widgetLineHeight(fontScale, stacked)

    @Test
    fun `the line budget grows with the widget's height`() {
        assertEquals("the minimum size still shows the next departure", 1, lines(110.dp))
        assertEquals(4, lines(180.dp))
        assertEquals(6, lines(250.dp))
        assertEquals("no line fits under the full header", 0, lines(110.dp, fontScale = 2f))
    }

    @Test
    fun `a row stacks when its countdown leaves too little width for its destination`() {
        val one = listOf("3 min")
        val three = listOf("3 · 6 · 9 min")
        assertFalse("the minimum width fits one time at the default font", widgetRowStacked(one, false, 180.dp))
        assertTrue("but not three", widgetRowStacked(three, false, 180.dp))
        assertFalse("the default width fits three", widgetRowStacked(three, false, 240.dp))
        // Codex on #155: the wide bucket at a large font squeezed three times' destination out.
        assertTrue(widgetRowStacked(three, false, 220.dp, 1.3f))
        assertFalse("one time still fits there", widgetRowStacked(one, false, 220.dp, 1.3f))
        assertFalse("and three fit the wider bucket", widgetRowStacked(three, false, 300.dp, 1.3f))
        assertTrue(widgetRowStacked(one, false, 180.dp, 1.3f))
        assertTrue(widgetRowStacked(one, false, 240.dp, 2f))
        // The planned-work calendar before the countdown takes its width too.
        assertFalse(widgetRowStacked(listOf("12 min"), false, 180.dp))
        assertTrue(widgetRowStacked(listOf("12 min"), true, 180.dp))
        // The widest of a branching row's countdowns decides it.
        assertTrue(widgetRowStacked(one + three, false, 180.dp))
    }

    @Test
    fun `a status fits beside the pill only when all of it does`() {
        // Codex on #457: judged on a destination's few characters, "Part Suspended" was cut at the
        // minimum width at 1.3x.
        assertFalse(widgetStatusFits("Part Suspended", null, 180.dp, 1.3f))
        assertTrue("the wide bucket has room", widgetStatusFits("Part Suspended", null, 300.dp, 1.3f))
        assertTrue(widgetStatusFits("Part Suspended", null, 220.dp))
        // The reason for no times after it takes its width too.
        assertFalse(widgetStatusFits("Part Suspended", "No data", 220.dp))
        // A short status fits the minimum width at the default font.
        assertTrue(widgetStatusFits("Suspended", null, 180.dp))
    }

    @Test
    fun `a stacked line costs its two lines' height`() {
        assertEquals(1, lines(180.dp, 2f, stacked = true))
        assertEquals("the minimum size fits no stacked departure", 0, lines(110.dp, 2f, compact = true, stacked = true))
        assertEquals("but fits one at 1.3x", 1, lines(110.dp, 1.3f, compact = true, stacked = true))
    }

    @Test
    fun `only the rows whose countdowns need it stack, and the budget counts each as it draws`() {
        // At the minimum width and the default font: a row with three times stacks, one with a
        // single time doesn't.
        val busy = listOf(departure("busy", 60L), departure("busy", 360L), departure("busy", 540L))
        val quiet = (1..4).map { departure("quiet$it", 120L + it) }
        // Every line checked, so no note takes a line.
        val checks = (listOf("busy") + (1..4).map { "quiet$it" })
            .associateWith { LineStatusCheck(LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service"), now) }
        val snapshot = DeparturesSnapshot(listOf(stop("490000001A", busy + quiet, now)), now, lineStatuses = checks)
        // 180dp tall: 128dp for rows, an unstacked line 32dp and a stacked one 53dp.
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(180.dp, 180.dp))
        assertEquals(listOf("busy", "quiet1", "quiet2"), model.rows.map { it.row.lineId })
        assertEquals(listOf(listOf(true), listOf(false), listOf(false)), model.rows.map { it.stackedLines })
        // Wider, nothing stacks, and four unstacked lines fit.
        val wide = widgetModel(snapshot, now, geometry = WidgetGeometry(240.dp, 180.dp))
        assertEquals(4, wide.rows.size)
        assertTrue(wide.rows.none { true in it.stackedLines })
        // Without a cell (the line budgets), nothing stacks.
        assertTrue(widgetModel(snapshot, now, maxLines = 4).rows.none { true in it.stackedLines })
    }

    @Test
    fun `a stop header and a status under a row cost their own drawn heights, not a line's`() {
        assertEquals("4dp above and below a 12sp line", 25, widgetHeaderHeight(1f))
        assertEquals("4dp above a 12sp line", 21, widgetStatusLineHeight(1f))
        assertEquals(31, widgetHeaderHeight(1.33f))
        // Neither is taller than a departure line, at any font.
        for (scale in listOf(0.85f, 1f, 1.3f, 2f)) {
            assertTrue(widgetHeaderHeight(scale) <= widgetLineHeight(scale))
            assertTrue(widgetStatusLineHeight(scale) < widgetLineHeight(scale))
        }
    }

    @Test
    fun `places with headers fill the height their headers really take`() {
        val lines = listOf("victoria", "piccadilly", "northern")
        val stops = lines.mapIndexed { i, line -> stop("940GZZLU$i", listOf(departure(line, 60L * (i + 1))), now, name = "Station $i") }
        val checks = lines.associateWith { LineStatusCheck(LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service"), now) }
        val snapshot = DeparturesSnapshot(stops, now, lineStatuses = checks)
        // Room for three headers and three lines, but not for three places costed a line per header.
        val place = widgetHeaderHeight() + widgetLineHeight()
        val height = (100..400).first { widgetRowsHeight(it.dp) >= 3 * place }.dp
        assertTrue(widgetRowsHeight(height) < 6 * widgetLineHeight())
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(380.dp, height))
        assertEquals(listOf("victoria", "piccadilly", "northern"), model.rows.map { it.row.lineId })
        assertTrue(model.rows.all { it.header != null })
    }

    @Test
    fun `disrupted rows fill the height their statuses really take`() {
        val lines = listOf("victoria", "piccadilly", "northern")
        val snapshot = DeparturesSnapshot(
            listOf(stop("940GZZLU0", lines.mapIndexed { i, line -> departure(line, 60L * (i + 1)) }, now)),
            now,
            lineStatuses = lines.associateWith { LineStatusCheck(LineStatus(it, 6, "Severe Delays"), now) },
        )
        // Room for three lines with a status under each, but not for six lines.
        val row = widgetLineHeight() + widgetStatusLineHeight()
        val height = (100..400).first { widgetRowsHeight(it.dp) >= 3 * row }.dp
        assertTrue(widgetRowsHeight(height) < 6 * widgetLineHeight())
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(380.dp, height))
        assertEquals(lines.toSet(), model.rows.map { it.row.lineId }.toSet())
        assertTrue(model.rows.all { it.row.status != null })
    }

    @Test
    fun `a branching row's lines stack on their own countdowns, so a dropped one can't stack the rest`() {
        // One time to Morden first, then three to Kennington: at 220dp and 1.3x the first fits one
        // line and the second needs two (Codex on #457).
        val northern = listOf(
            departure("northern", 60L).copy(destination = "Morden"),
            departure("northern", 120L).copy(destination = "Kennington"),
            departure("northern", 360L).copy(destination = "Kennington"),
            departure("northern", 540L).copy(destination = "Kennington"),
        )
        val checks = mapOf("northern" to LineStatusCheck(LineStatus("northern", LineStatus.GOOD_SERVICE, "Good Service"), now))
        val snapshot = DeparturesSnapshot(listOf(stop("490000001A", northern, now)), now, lineStatuses = checks)
        val roomy = widgetModel(snapshot, now, geometry = WidgetGeometry(220.dp, 250.dp, 1.3f)).rows.single()
        assertEquals(listOf(false, true), roomy.stackedLines)
        // 110dp tall leaves 52dp: only the first line fits, on one line, under the full header.
        val tight = widgetModel(snapshot, now, geometry = WidgetGeometry(220.dp, 110.dp, 1.3f))
        assertFalse(tight.compact)
        assertEquals(listOf("Morden"), tight.rows.single().groups.map { it.destination })
        assertEquals(listOf(false), tight.rows.single().stackedLines)
    }

    @Test
    fun `no room even in the compact layout is too small, not "no departures"`() {
        val departures = (1..3).map { departure("line$it", it * 60L) }
        val snapshot = DeparturesSnapshot(listOf(stop("490000001A", departures, now)), now)
        val model = widgetModel(snapshot, now, maxLines = 0, maxLinesCompact = 0)
        assertTrue(model.tooSmall)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `with nothing to show, a tiny widget keeps its empty state rather than "too small"`() {
        val snapshot = DeparturesSnapshot(listOf(stop("490000001A", emptyList(), now)), now)
        val model = widgetModel(snapshot, now, maxLines = 0, maxLinesCompact = 0)
        assertFalse(model.tooSmall)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `a zero budget switches to the compact layout with one departure`() {
        val departures = (1..3).map { departure("line$it", it * 60L) }
        val snapshot = DeparturesSnapshot(listOf(stop("490000001A", departures, now)), now)
        val compact = widgetModel(snapshot, now, maxLines = 0)
        assertTrue(compact.compact)
        assertEquals(1, compact.rows.size)
        assertFalse(widgetModel(snapshot, now, maxLines = 1).compact)
    }

    @Test
    fun `a larger system font leaves room for fewer lines`() {
        assertEquals(4, lines(180.dp, fontScale = 1f))
        assertEquals(3, lines(180.dp, fontScale = 1.3f))
        assertEquals(2, lines(180.dp, fontScale = 2f))
    }

    @Test
    fun `the stale note takes a line from the budget`() {
        assertEquals(3, lines(180.dp, withNote = true))
        assertEquals(1, lines(110.dp, withNote = true))
        // A stale snapshot spends the note's budget; a fresh one keeps the full budget.
        val departures = (1..6).map { departure("line$it", it * 60L) }
        val stale = DeparturesSnapshot(listOf(stop("490000001A", departures, now.minusSeconds(900))), now.minusSeconds(900))
        // Fresh and every line checked: nothing for a note to say.
        val checks = (1..6).associate { "line$it" to LineStatusCheck(LineStatus("line$it", LineStatus.GOOD_SERVICE, "Good Service"), now) }
        val fresh = DeparturesSnapshot(listOf(stop("490000001A", departures, now)), now, lineStatuses = checks)
        assertEquals(3, widgetModel(stale, now, maxLines = 4, maxLinesWithNote = 3).rows.size)
        assertEquals(4, widgetModel(fresh, now, maxLines = 4, maxLinesWithNote = 3).rows.size)
    }

    @Test
    fun `a tap's refresh takes the note's line, even over fresh data`() {
        val departures = (1..6).map { departure("line$it", it * 60L) }
        val checks = (1..6).associate { "line$it" to LineStatusCheck(LineStatus("line$it", LineStatus.GOOD_SERVICE, "Good Service"), now) }
        val fresh = DeparturesSnapshot(listOf(stop("490000001A", departures, now)), now, lineStatuses = checks)
        val model = widgetModel(fresh, now, maxLines = 4, maxLinesWithNote = 3, tap = WidgetTapNote.REFRESHING)
        assertEquals(WidgetTapNote.REFRESHING, model.tap)
        assertEquals(3, model.rows.size)
    }

    @Test
    fun `a failed tap is said only while the data it couldn't replace is out of date`() {
        val departures = listOf(departure("victoria", 120))
        val checks = mapOf("victoria" to LineStatusCheck(LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service"), now))
        val fresh = DeparturesSnapshot(listOf(stop("490000001A", departures, now)), now, lineStatuses = checks)
        val stale = DeparturesSnapshot(listOf(stop("490000001A", departures, now.minusSeconds(900))), now.minusSeconds(900))
        assertNull(widgetModel(fresh, now, tap = WidgetTapNote.UNREACHABLE).tap)
        assertEquals(WidgetTapNote.UNREACHABLE, widgetModel(stale, now, tap = WidgetTapNote.UNREACHABLE).tap)
    }

    @Test
    fun `a stale line's guess is due when its soonest train is, and a fresh line has none`() {
        val departures = listOf(departure("victoria", 300), departure("central", 120))
        val stale = DeparturesSnapshot(listOf(stop("490000001A", departures, now.minusSeconds(900))), now.minusSeconds(900))
        assertEquals(now.plusSeconds(120), widgetModel(stale, now).guessExpiresAt)
        val fresh = DeparturesSnapshot(listOf(stop("490000001A", departures, now)), now)
        assertNull(widgetModel(fresh, now).guessExpiresAt)
    }

    @Test
    fun `a single place draws no stop header`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(departure("victoria", 120)), now),
                stop("490000002B", listOf(departure("central", 180)), now),
            ),
            fetchedAt = now,
        )
        val model = widgetModel(snapshot, now)
        assertTrue("one place, no qualifier: the place is implied", model.rows.all { it.header == null })
    }

    @Test
    fun `each place gets a header above its first row`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(departure("victoria", 120), departure("central", 300)), now, name = "Stop One"),
                stop("490000002B", listOf(departure("jubilee", 180)), now, name = "Stop Two"),
            ),
            fetchedAt = now,
        )
        val model = widgetModel(snapshot, now)
        // A place's rows stay together under its header, places in the order their first row came.
        assertEquals(listOf("victoria", "central", "jubilee"), model.rows.map { it.row.lineId })
        assertEquals(listOf("Stop One", null, "Stop Two"), model.rows.map { it.header?.text })
    }

    // The compact widget (StopDashCompactWidget): departures only, no stop headers or title row.
    private fun placesSnapshot() = DeparturesSnapshot(
        stops = listOf(
            stop("490000001A", listOf(departure("victoria", 120), departure("central", 300)), now, name = "Stop One"),
            stop("490000002B", listOf(departure("jubilee", 180)), now, name = "Stop Two"),
            stop("490000003C", listOf(departure("district", 240)), now, name = "Stop Three"),
            stop("490000004D", listOf(departure("circle", 360)), now, name = "Stop Four"),
        ),
        fetchedAt = now,
    )

    @Test
    fun `the compact widget draws no stop headers`() {
        val cell = WidgetGeometry(250.dp, 400.dp)
        val full = widgetModel(placesSnapshot(), now, geometry = cell)
        assertTrue("the full widget names its places", full.rows.any { it.header != null })
        val bare = widgetModel(placesSnapshot(), now, geometry = cell.copy(bare = true))
        assertEquals(full.rows.map { it.row.lineId }, bare.rows.map { it.row.lineId })
        assertTrue(bare.rows.all { it.header == null })
    }

    @Test
    fun `the compact widget fits more departures in the same cell`() {
        val cell = WidgetGeometry(250.dp, 150.dp)
        val full = widgetModel(placesSnapshot(), now, geometry = cell)
        val bare = widgetModel(placesSnapshot(), now, geometry = cell.copy(bare = true))
        assertTrue("${bare.rows.size} > ${full.rows.size}", bare.rows.size > full.rows.size)
        assertFalse("no title row to drop", bare.compact)
    }

    @Test
    fun `the compact widget's rows leave room for its warning`() {
        val cell = WidgetGeometry(250.dp, 150.dp, bare = true)
        val fresh = widgetRowsHeight(cell.height, bare = true)
        val warned = widgetRowsHeight(cell.height, withNote = true, bare = true)
        assertTrue(warned < fresh)
        assertTrue("the padding alone is all a fresh compact widget gives up", fresh > widgetRowsHeight(cell.height))
    }

    // Two columns with nothing to split (one departure, or none): the layout ends, rather than retrying
    // forever with headers that cost nothing (Codex on #759).
    @Test(timeout = 10_000)
    fun `a wide compact widget with one departure or none lays out`() {
        val wide = WidgetGeometry(600.dp, 300.dp, bare = true)
        val one = DeparturesSnapshot(stops = listOf(stop("490000001A", listOf(departure("victoria", 120)), now, name = "Stop One")), fetchedAt = now)
        assertEquals(listOf("victoria"), widgetModel(one, now, geometry = wide).rows.map { it.row.lineId })
        val none = DeparturesSnapshot(stops = listOf(stop("490000001A", emptyList(), now, name = "Stop One")), fetchedAt = now)
        assertTrue(widgetModel(none, now, geometry = wide).rows.isEmpty())
        val many = widgetModel(placesSnapshot(), now, geometry = wide)
        assertTrue(many.rows.all { it.header == null })
    }

    @Test
    fun `a compact widget too small for a departure says so`() {
        val bare = widgetModel(placesSnapshot(), now, geometry = WidgetGeometry(180.dp, 30.dp, bare = true))
        assertTrue(bare.tooSmall)
    }

    @Test
    fun `a header costs a line of the budget and is never left orphaned`() {
        // Budget 3: "Stop One" header + its row (2 lines), then "Stop Two" would need its header plus
        // a row (2 more) but only 1 line is left — so it's dropped whole rather than drawing a header
        // with nothing under it.
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(departure("victoria", 120)), now, name = "Stop One"),
                stop("490000002B", listOf(departure("jubilee", 180)), now, name = "Stop Two"),
            ),
            fetchedAt = now,
        )
        val model = widgetModel(snapshot, now, maxLines = 3)
        assertEquals(listOf("victoria"), model.rows.map { it.row.lineId })
        assertEquals("the surviving place still names itself", "Stop One", model.rows.single().header?.text)
        val used = model.rows.sumOf { it.groups.size + (if (it.header != null) 1 else 0) }
        assertTrue("headers and lines together stay within the budget", used <= 3)
    }

    @Test
    fun `a rail platform names itself in the header`() {
        fun onPlatform(lineId: String, offset: Long) = departure(lineId, offset).copy(platform = "Southbound - Platform 1")
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", listOf(onPlatform("victoria", 120)), now)),
            fetchedAt = now,
        )
        val header = widgetModel(snapshot, now).rows.first().header
        assertEquals("Example Stop – Platform 1", header?.text)
        assertEquals("the spoken label keeps the direction", "Example Stop, Platform 1, Southbound", header?.spoken)
    }

    @Test
    fun `a one-line budget drops headers rather than show no departures`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(departure("victoria", 120)), now, name = "Stop One"),
                stop("490000002B", listOf(departure("jubilee", 180)), now, name = "Stop Two"),
            ),
            fetchedAt = now,
        )
        val model = widgetModel(snapshot, now, maxLines = 1)
        assertEquals(listOf("victoria"), model.rows.map { it.row.lineId })
        assertNull(model.rows.single().header)
    }

    @Test
    fun `a lower-priority sibling can't take a fresher row's line at another place`() {
        // Fresh A, fresh B, then a stale row at A (a second, unrefreshed stop of the same place).
        // Grouping before the cap would put A's stale row next to its fresh one and spend B's lines
        // on it; choosing by priority first keeps both fresh rows and drops the stale one.
        val freshA = stop("490000001A", listOf(departure("aline", 60)), now, name = "Stop A")
        val freshB = stop("490000002B", listOf(departure("bline", 120)), now, name = "Stop B")
        val staleA = stop("490000003C", listOf(departure("staleline", 30)), now.minusSeconds(600), name = "Stop A")
        val snapshot = DeparturesSnapshot(listOf(freshA, freshB, staleA), now)
        // Two headers and two lines fill a budget of 4.
        val model = widgetModel(snapshot, now, maxLines = 4)
        assertEquals(listOf("aline", "bline"), model.rows.map { it.row.lineId })
        assertEquals(listOf("Stop A", "Stop B"), model.rows.map { it.header?.text })
    }

    @Test
    fun `a row that can't afford its place's header doesn't stop a later row that fits`() {
        // Budget 3: A's header and first row fit (2). B's would need its own header and a line (4),
        // so it's skipped — but A's second row still fits under A's header (3).
        val a1 = stop("490000001A", listOf(departure("aline", 60)), now, name = "Stop A")
        val b = stop("490000002B", listOf(departure("bline", 120)), now, name = "Stop B")
        val a2 = stop("490000003C", listOf(departure("aline2", 180)), now, name = "Stop A")
        val model = widgetModel(DeparturesSnapshot(listOf(a1, b, a2), now), now, maxLines = 3)
        assertEquals(listOf("aline", "aline2"), model.rows.map { it.row.lineId })
        assertEquals("A keeps its header though B didn't fit", listOf("Stop A", null), model.rows.map { it.header?.text })
    }

    @Test
    fun `a later route that ends a shared-terminus header still fits`() {
        // A letterless bus stop: one route alone gets a "-> terminus" header (header + row = 2, the
        // whole budget). A second route to another terminus ends that header, so both routes fit.
        fun bus(lineId: String, destination: String, offset: Long) = Departure(
            lineId = lineId,
            lineName = lineId,
            direction = "outbound",
            destination = destination,
            platform = null,
            expectedArrival = now.plusSeconds(offset),
            mode = "bus",
        )
        val stop = stop("490000001A", listOf(bus("73", "Stoke Newington", 60), bus("38", "Clapton Pond", 120)), now)
        val model = widgetModel(DeparturesSnapshot(listOf(stop), now), now, maxLines = 2)
        assertEquals(listOf("73", "38"), model.rows.map { it.row.lineId })
        assertTrue("two termini: no shared-terminus header", model.rows.all { it.header == null })
    }

    @Test
    fun `a route that didn't fit still keeps its stop from claiming one terminus`() {
        // A letterless bus stop with routes to two termini, beside another place. Budget 4: Stop A's
        // header and 73 (2), Stop B's header and its row (4); 38 doesn't fit. The header is judged
        // from every route at Stop A, so it stays the bare name rather than "➔ Stoke Newington".
        fun bus(lineId: String, destination: String, offset: Long) = Departure(
            lineId = lineId,
            lineName = lineId,
            direction = "outbound",
            destination = destination,
            platform = null,
            expectedArrival = now.plusSeconds(offset),
            mode = "bus",
        )
        val a = stop("490000001A", listOf(bus("73", "Stoke Newington", 60), bus("38", "Clapton Pond", 180)), now, name = "Stop A")
        val b = stop("490000002B", listOf(departure("victoria", 120)), now, name = "Stop B")
        val model = widgetModel(DeparturesSnapshot(listOf(a, b), now), now, maxLines = 4)
        assertEquals(listOf("73", "victoria"), model.rows.map { it.row.lineId })
        assertEquals(listOf("Stop A", "Stop B"), model.rows.map { it.header?.text })
    }

    private fun toward(lineId: String, destination: String, offsetSeconds: Long) =
        departure(lineId, offsetSeconds).copy(destination = destination)

    @Test
    fun `a favorite journey's departures lead the widget, and only those from a journey-only stop`() {
        // 490000001A is nearby; 490000009Z is only a journey origin, where the b1 to Hill is the journey.
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(departure("victoria", 60)), now.minusSeconds(30)),
                stop(
                    "490000009Z",
                    listOf(toward("b1", "Hill", 300), toward("b1", "Dale", 120), toward("b2", "Hill", 240)),
                    now.minusSeconds(30),
                ),
            ),
            fetchedAt = now.minusSeconds(30),
            journeys = listOf(
                WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null), JourneyCall("b2", "Hill", null))),
            ),
            journeyOnlyStopIds = setOf("490000009Z"),
        )
        val rows = widgetModel(snapshot, now).rows.map { it.row }
        // The journey's buses first (soonest first), then the nearby stop; the Dale bus isn't shown.
        assertEquals(listOf("b2", "b1", "victoria"), rows.map { it.lineId })
        assertTrue(rows.flatMap { it.upcoming }.none { it.destination == "Dale" })
    }

    @Test
    fun `a bus alert the app found behind a stop doesn't flag the widget's row there`() {
        val diversion = LineStatus("b1", 5, "Diversion", "Not serving stops between 'Bank Station' and 'Moorgate Station'.", soleAlert = true)
        val bus = departure("b1", 120).copy(mode = "bus")
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", listOf(bus), now)),
            fetchedAt = now,
            lineStatuses = mapOf("b1" to LineStatusCheck(diversion, now)),
        )
        assertEquals(diversion.description, widgetModel(snapshot, now).rows.single().row.status?.description)
        val placed = snapshot.withAlertsBehind(setOf(AlertBehind("b1", lineAlertFingerprint(diversion), "490000001A", "inbound")))
        val row = widgetModel(placed, now).rows.single().row
        assertNull(row.status)
        // Its line still counts as checked, so the widget doesn't say it couldn't check.
        assertTrue(placed.statusKnown("b1", now))
    }

    @Test
    fun `a journey line's suspension with no trains shows in the journey band, once`() {
        val suspended = LineStatus("b1", 5, "Suspended")
        val origin = stop("490000009Z", emptyList(), now)
            .copy(lines = listOf(LineRef("b1", "B1", "bus")))
        val journeys = listOf(WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null))))
        val checks = mapOf("b1" to LineStatusCheck(suspended, now))
        // A journey-only origin: the suspension is the journey's row, not "no departures".
        val only = DeparturesSnapshot(listOf(origin), now, journeys = journeys, journeyOnlyStopIds = setOf("490000009Z"), lineStatuses = checks)
        val rows = widgetModel(only, now).rows.map { it.row }
        assertEquals(listOf("b1"), rows.map { it.lineId })
        assertEquals(suspended, rows.single().status)
        // A nearby origin: the same row, not repeated below the band.
        val nearby = only.copy(journeyOnlyStopIds = emptySet())
        assertEquals(listOf("b1"), widgetModel(nearby, now).rows.map { it.row.lineId })
    }

    @Test
    fun `a journey-only stop shows nothing until its journey is worked out`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(departure("victoria", 60)), now.minusSeconds(30)),
                stop("490000009Z", listOf(toward("b1", "Hill", 300)), now.minusSeconds(30)),
            ),
            fetchedAt = now.minusSeconds(30),
            journeyOnlyStopIds = setOf("490000009Z"),
        )
        assertEquals(listOf("victoria"), widgetModel(snapshot, now).rows.map { it.row.lineId })
    }

    @Test
    fun `every journey leads, even past another row at an earlier journey's stop`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(toward("b1", "Hill", 60), departure("victoria", 90)), now.minusSeconds(30), name = "Stop A"),
                stop("490000002B", listOf(toward("b2", "Hill", 120)), now.minusSeconds(30), name = "Stop B"),
            ),
            fetchedAt = now.minusSeconds(30),
            journeys = listOf(
                WidgetJourney("490000001A", setOf(JourneyCall("b1", "Hill", null))),
                WidgetJourney("490000002B", setOf(JourneyCall("b2", "Hill", null))),
            ),
        )
        assertEquals(listOf("b1", "b2", "victoria"), widgetModel(snapshot, now).rows.map { it.row.lineId })
    }

    @Test
    fun `a fresh journey row leads a sooner stale one`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(toward("b1", "Hill", 60)), now.minusSeconds(900)),
                stop("490000002B", listOf(toward("b2", "Hill", 300)), now.minusSeconds(30)),
            ),
            fetchedAt = now.minusSeconds(30),
            journeys = listOf(
                WidgetJourney("490000001A", setOf(JourneyCall("b1", "Hill", null))),
                WidgetJourney("490000002B", setOf(JourneyCall("b2", "Hill", null))),
            ),
            journeyOnlyStopIds = setOf("490000001A", "490000002B"),
        )
        assertEquals("b2", widgetModel(snapshot, now, maxLines = 1).rows.first().row.lineId)
    }

    @Test
    fun `only an unworked-out journey stop is no data, not "no departures"`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000009Z", listOf(toward("b1", "Hill", 300)), now.minusSeconds(30))),
            fetchedAt = now.minusSeconds(30),
            journeyOnlyStopIds = setOf("490000009Z"),
        )
        assertFalse(widgetModel(snapshot, now).hasData)
    }

    @Test
    fun `at a nearby journey origin the journey's departures aren't shown twice`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("490000001A", listOf(toward("b1", "Hill", 300), toward("b1", "Dale", 120)), now.minusSeconds(30)),
            ),
            fetchedAt = now.minusSeconds(30),
            journeys = listOf(WidgetJourney("490000001A", setOf(JourneyCall("b1", "Hill", null)))),
        )
        val rows = widgetModel(snapshot, now).rows.map { it.row }
        assertEquals(listOf(listOf("Hill"), listOf("Dale")), rows.map { r -> r.upcoming.map { it.destination } })
    }

    @Test
    fun `a stop the refresh couldn't get makes the rest uncertain, however fresh`() {
        val fresh = stop("490000001A", listOf(departure("victoria", 120)), now)
        val complete = widgetModel(DeparturesSnapshot(stops = listOf(fresh), fetchedAt = now), now)
        assertFalse(complete.uncertain)
        val missing = widgetModel(
            DeparturesSnapshot(stops = listOf(fresh), fetchedAt = now, missingStopIds = setOf("490000002B")),
            now,
        )
        assertFalse("the shown stop is still fresh", missing.stale)
        assertTrue("but a requested stop is absent, so the widget isn't complete", missing.uncertain)
    }

    @Test
    fun `a snapshot left with only missing stops says they may be out of date, not load the app`() {
        val onlyMissing = DeparturesSnapshot(stops = emptyList(), fetchedAt = now, missingStopIds = setOf("490000002B"))
        val model = widgetModel(onlyMissing, now)
        assertTrue(model.hasData)
        assertTrue(model.uncertain)
        assertTrue(model.rows.isEmpty())
        assertFalse(widgetModel(DeparturesSnapshot(stops = emptyList(), fetchedAt = now), now).hasData)
    }

    private val severe = LineStatus("victoria", 6, "Severe Delays")

    @Test
    fun `a disrupted line's row carries its status, which costs a line of the budget`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490A", listOf(departure("victoria", 120), departure("jubilee", 240)), now)),
            fetchedAt = now,
            lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now)),
        )
        val model = widgetModel(snapshot, now, maxLines = 2)
        // Two lines: Victoria's countdown and its status; Jubilee no longer fits.
        assertEquals(listOf("victoria"), model.rows.map { it.row.lineId })
        assertEquals(severe, model.rows.single().row.status)
        // With room for all three, both rows show, only Victoria marked.
        val roomy = widgetModel(snapshot, now, maxLines = 3)
        assertEquals(listOf(severe, null), roomy.rows.map { it.row.status })
    }

    @Test
    fun `a disrupted departure too tall for the full layout goes compact, not "no departures"`() {
        // Codex on #457: at 220x110dp and 1.3x, one countdown fits the full layout's 52dp, but not
        // with the status it draws under it, nor the status stacked alone.
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490A", listOf(departure("victoria", 120)), now)),
            fetchedAt = now,
            lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now)),
        )
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(220.dp, 110.dp, 1.3f))
        assertTrue(model.compact)
        assertFalse(model.tooSmall)
        assertEquals(severe, model.rows.single().row.status)
    }

    @Test
    fun `a status under a row's countdown starts under the pill when it doesn't fit beside it`() {
        // Codex on #457: lined up with an unstacked destination, the status has only the
        // destination's width, and "Severe Delays" was cut at the compact width at 1.3x.
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490A", listOf(departure("victoria", 120)), now)),
            fetchedAt = now,
            lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now)),
        )
        val narrow = widgetModel(snapshot, now, geometry = WidgetGeometry(220.dp, 250.dp, 1.3f)).rows.single()
        assertEquals("one time fits beside its destination", listOf(false), narrow.stackedLines)
        assertFalse(narrow.statusBeside)
        val wide = widgetModel(snapshot, now, geometry = WidgetGeometry(300.dp, 250.dp, 1.3f)).rows.single()
        assertTrue(wide.statusBeside)
    }

    @Test
    fun `a line with work still to come carries its calendar, which costs no line, until it's dismissed`() {
        val closure = PlannedAlert("Part Closure", "No service between Stop A and Stop B.", LocalDate.of(2026, 9, 27))
        val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(closure))
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490A", listOf(departure("victoria", 120), departure("jubilee", 240)), now)),
            fetchedAt = now,
            lineStatuses = mapOf("victoria" to LineStatusCheck(good, now)),
        )
        val model = widgetModel(snapshot, now, maxLines = 2)
        // Both rows fit two lines: the calendar sits beside Victoria's countdown, not under it.
        assertEquals(listOf("victoria", "jubilee"), model.rows.map { it.row.lineId })
        assertEquals(listOf(listOf(closure), emptyList()), model.rows.map { it.row.plannedAlerts })
        assertNull(model.rows.first().row.status)
        // Dismissed in the app, it's put away here too.
        val dismissed = snapshot.withDismissals(setOf(DismissedAlert.ofPlanned("victoria", closure)))
        assertEquals(emptyList<PlannedAlert>(), widgetModel(dismissed, now, maxLines = 2).rows.first().row.plannedAlerts)
    }

    @Test
    fun `a status past the staleness threshold is withheld, like an old countdown`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490A", listOf(departure("victoria", 900)), now)),
            fetchedAt = now,
            lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now.minusSeconds(300))),
        )
        assertNull(widgetModel(snapshot, now).rows.single().row.status)
    }

    @Test
    fun `a suspended line with no predictions shows as its status alone`() {
        val suspended = LineStatus("waterloo-city", 5, "Suspended")
        val stop = stop("490A", listOf(departure("victoria", 120)), now)
            .copy(lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("waterloo-city", "Waterloo & City", "tube")))
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop),
            fetchedAt = now,
            lineStatuses = mapOf("waterloo-city" to LineStatusCheck(suspended, now)),
        )
        val rows = widgetModel(snapshot, now).rows
        // The warning leads, as on the in-app list.
        assertEquals(listOf("waterloo-city", "victoria"), rows.map { it.row.lineId })
        assertTrue(rows.first().groups.isEmpty())
        assertEquals(suspended, rows.first().row.status)
    }

    @Test
    fun `a fresh row whose line has no live check says disruptions couldn't be checked`() {
        val stop = stop("490A", listOf(departure("victoria", 120)), now)
        val unchecked = DeparturesSnapshot(stops = listOf(stop), fetchedAt = now)
        assertTrue(widgetModel(unchecked, now).statusUnknown)
        val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")
        val checked = unchecked.copy(lineStatuses = mapOf("victoria" to LineStatusCheck(good, now)))
        assertFalse(widgetModel(checked, now).statusUnknown)
        // An aged-out check is no check.
        val aged = unchecked.copy(lineStatuses = mapOf("victoria" to LineStatusCheck(good, now.minusSeconds(300))))
        assertTrue(widgetModel(aged, now).statusUnknown)
    }

    @Test
    fun `the unchecked note takes the note's line of the budget`() {
        val stop = stop("490A", listOf(departure("victoria", 120), departure("jubilee", 240)), now)
        val model = widgetModel(DeparturesSnapshot(stops = listOf(stop), fetchedAt = now), now, maxLines = 2, maxLinesWithNote = 1)
        assertEquals(1, model.rows.size)
    }

    @Test
    fun `a suspension stays marked past its stop's boundary while its own check is live`() {
        val suspended = LineStatus("waterloo-city", 5, "Suspended")
        val stop = stop("490A", emptyList(), now.minusSeconds(320))
            .copy(lines = listOf(LineRef("waterloo-city", "Waterloo & City", "tube")))
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop),
            fetchedAt = now.minusSeconds(320),
            lineStatuses = mapOf("waterloo-city" to LineStatusCheck(suspended, now.minusSeconds(60))),
        )
        assertEquals(suspended, widgetModel(snapshot, now).rows.single().row.status)
    }

    @Test
    fun `a live suspension at a stale stop isn't pushed below the cap by fresh rows`() {
        val suspended = LineStatus("waterloo-city", 5, "Suspended")
        val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")
        val staleStop = stop("490A", emptyList(), now.minusSeconds(320))
            .copy(lines = listOf(LineRef("waterloo-city", "Waterloo & City", "tube")))
        val freshStop = stop("490B", listOf(departure("victoria", 120)), now)
        val snapshot = DeparturesSnapshot(
            stops = listOf(freshStop, staleStop),
            fetchedAt = now,
            lineStatuses = mapOf(
                "waterloo-city" to LineStatusCheck(suspended, now.minusSeconds(30)),
                "victoria" to LineStatusCheck(good, now),
            ),
        )
        val model = widgetModel(snapshot, now, maxLines = 1, maxLinesWithNote = 1)
        assertEquals(listOf("waterloo-city"), model.rows.map { it.row.lineId })
    }

    @Test
    fun `an unchecked line that doesn't fit raises no note`() {
        val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")
        // Victoria (checked) fits; Jubilee (unchecked) is below the cap.
        val stop = stop("490A", listOf(departure("victoria", 120), departure("jubilee", 240)), now)
        val snapshot = DeparturesSnapshot(listOf(stop), now, lineStatuses = mapOf("victoria" to LineStatusCheck(good, now)))
        val model = widgetModel(snapshot, now, maxLines = 1, maxLinesWithNote = 1)
        assertFalse(model.statusUnknown)
        assertEquals(listOf("victoria"), model.rows.map { it.row.lineId })
    }

    @Test
    fun `the size summary names each reported size and its rows, widest first`() {
        val summary = widgetSizesSummary(mapOf(DpSize(411.dp, 620.dp) to 12, DpSize(720.5.dp, 290.dp) to 9))
        // Widest first, in whole dp.
        assertEquals("720x290 9 rows, 411x620 12 rows", summary)
        assertEquals("none", widgetSizesSummary(emptyMap()))
    }
}
