package app.stopdash.data

import app.stopdash.domain.AlertBehind
import app.stopdash.domain.AlertStart
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.NoTimes
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.RailFeed
import app.stopdash.domain.RoutePattern
import app.stopdash.domain.StarredRow
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopWay
import app.stopdash.domain.Terminating
import app.stopdash.domain.lineAlertFingerprint
import app.stopdash.domain.plannedAlertFingerprint
import app.stopdash.domain.plannedShownFingerprint
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone-to-watch envelope (dev-docs/wear-os.md): synthetic stops only, no user data. */
class WatchEnvelopeTest {
    private val now: Instant = Instant.parse("2026-09-24T08:00:00Z")

    private fun departure(
        min: Long,
        line: String = "victoria",
        destination: String = "Brixton",
        direction: String = "inbound",
        platform: String? = null,
        mode: String = "tube",
    ) = Departure(
        lineId = line,
        lineName = line.replaceFirstChar { it.uppercase() },
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrival = now.plusSeconds(min * 60),
        mode = mode,
    )

    private fun stop(id: String, departures: List<Departure>, fetchedAt: Instant = now) = StopArrivals(
        stopId = id,
        stopName = "Stop $id",
        departures = departures,
        fetchedAt = fetchedAt,
        lines = departures.map { LineRef(it.lineId, it.lineName, it.mode) }.distinct(),
    )

    private fun decoded(payload: WatchPayload): WatchEnvelope =
        (WatchEnvelopes.decode(payload.bytes) as WatchDecode.Ok).envelope

    @Test
    fun `a stop's trains with no time are never saved, so the widget and the watch never have them`() {
        val canceled = app.stopdash.domain.UntimedTrain(departure(2, line = "great-example", direction = "", mode = "national-rail"), canceled = true)
        val stop = stop("910GEXAMPLE", listOf(departure(5, line = "great-example", direction = "", mode = "national-rail")))
            .copy(untimed = listOf(canceled))
        val back = stop.toPersisted().toDomain()
        assertEquals(emptyList<app.stopdash.domain.UntimedTrain>(), back.untimed)
        assertEquals(stop.departures, back.departures)
    }

    @Test
    fun `round trips to the same stops, and the same rows the widget builds`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                stop("940GEXAMPLE1", listOf(departure(1), departure(3), departure(4, destination = "Walthamstow Central", direction = "outbound"))),
                stop("490000EXAMPLE", listOf(departure(2, line = "24", destination = "Pimlico", mode = "bus")))
                    .copy(stopLetter = "K", towards = "Victoria", clusterId = "cluster-1"),
            ),
            fetchedAt = now,
        )
        val envelope = decoded(WatchEnvelopes.build(snapshot, starred = emptySet(), now = now))

        assertEquals(snapshot.stops.map { it.toPersisted() }, envelope.stops)
        assertEquals(
            DepartureRows.across(snapshot.stops, now),
            DepartureRows.across(envelope.stops.map { it.toDomain() }, now),
        )
    }

    @Test
    fun `terminating inputs and every rail feed state survive the trip`() {
        for (feed in RailFeed.entries) {
            val withNearer = stop("910GEXAMPLE", emptyList())
                .copy(nearer = Terminating.Nearer(setOf("940GNEARER"), setOf("Nearer")), railFeed = feed)
            val snapshot = DeparturesSnapshot(stops = listOf(withNearer), fetchedAt = now)
            val back = decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now)).stops.single().toDomain()
            assertEquals(feed, back.railFeed)
            assertEquals(withNearer.nearer, back.nearer)
        }
    }

    @Test
    fun `starred keys use the resolved direction key, so blank-direction siblings stay apart`() {
        val byPlatform = StarredRow("940GEXAMPLE1", "victoria", "Platform 1")
        val byOtherPlatform = StarredRow("940GEXAMPLE1", "victoria", "Platform 2")
        val snapshot = DeparturesSnapshot(stops = listOf(stop("940GEXAMPLE1", listOf(departure(2)))), fetchedAt = now)
        val envelope = decoded(WatchEnvelopes.build(snapshot, setOf(byPlatform, byOtherPlatform), now = now))
        assertEquals(setOf(byPlatform, byOtherPlatform), envelope.starred.mapTo(HashSet()) { it.toDomain() })
    }

    @Test
    fun `journey-only stops are left out`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("940GNEARBY", listOf(departure(2))), stop("940GJOURNEYONLY", listOf(departure(3)))),
            fetchedAt = now,
            journeyOnlyStopIds = setOf("940GJOURNEYONLY"),
        )
        assertEquals(listOf("940GNEARBY"), decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now)).stops.map { it.stopId })
    }

    @Test
    fun `the trim keeps the whole freshness window, plus a full countdown list past it`() {
        // Every 30 s inside the 5-minute window, then far more after it than any renderer shows.
        val inWindow = (0 until 10).map { departure(0).copy(expectedArrival = now.plusSeconds(30L * it + 15)) }
        val after = (6..20L).map { departure(it) }
        val otherGroup = (6..20L).map { departure(it, destination = "Walthamstow Central", direction = "outbound") }
        val snapshot = DeparturesSnapshot(stops = listOf(stop("940GEXAMPLE1", inWindow + after + otherGroup)), fetchedAt = now)

        val kept = decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now)).stops.single().departures
        val boundary = now.plusSeconds(5 * 60).toEpochMilli()
        assertEquals(10, kept.count { it.expectedArrivalMillis < boundary })
        assertEquals(3, kept.count { it.expectedArrivalMillis >= boundary && it.destination == "Brixton" })
        assertEquals(3, kept.count { it.expectedArrivalMillis >= boundary && it.destination == "Walthamstow Central" })
    }

    @Test
    fun `a fresh stop whose next departures are all past the boundary keeps the full countdown list`() {
        val snapshot = DeparturesSnapshot(stops = listOf(stop("940GEXAMPLE1", listOf(departure(6), departure(8), departure(10)))), fetchedAt = now)
        assertEquals(3, decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now)).stops.single().departures.size)
    }

    @Test
    fun `each stop trims to its own boundary`() {
        val older = stop("940GOLDER", listOf(departure(-2), departure(1), departure(2), departure(3), departure(4)), fetchedAt = now.minusSeconds(4 * 60))
        val snapshot = DeparturesSnapshot(stops = listOf(older), fetchedAt = now)
        // The older stop's boundary is 1 min from now: what's still to come before it (not the one
        // that has gone), then three after.
        val kept = decoded(WatchEnvelopes.build(snapshot, emptySet(), threshold = 5.minutes, now = now)).stops.single().departures
        assertEquals(listOf(1L, 2L, 3L).map { now.plusSeconds(it * 60).toEpochMilli() }, kept.map { it.expectedArrivalMillis })
    }

    @Test
    fun `a busy interchange fits a data item`() {
        val busy = (1..25).map { g -> (0 until 10).map { departure(it.toLong() / 2, line = "l$g", destination = "Destination $g") } }.flatten()
        val payload = WatchEnvelopes.build(DeparturesSnapshot(stops = listOf(stop("940GBUSY", busy)), fetchedAt = now), emptySet(), now = now)
        assertFalse(payload.asAsset)
        assertTrue(payload.bytes.size <= WatchEnvelopes.DATA_ITEM_BUDGET_BYTES)
    }

    @Test
    fun `a watched set over the data item budget goes whole, as an asset`() {
        val stops = (1..10).map { stop("940GSTOP$it", listOf(departure(2))) }
        val payload = WatchEnvelopes.build(DeparturesSnapshot(stops = stops, fetchedAt = now), emptySet(), dataItemBudget = 500, now = now)
        assertTrue(payload.asAsset)
        assertEquals(10, decoded(payload).stops.size)
        assertEquals(0, decoded(payload).omittedStops)
    }

    @Test
    fun `past the transfer ceiling, the last unstarred stops go first and are counted`() {
        val stops = (1..10).map { stop("940GSTOP$it", listOf(departure(2))) }
        val starred = StarredRow("940GSTOP10", "victoria", "inbound")
        val selected = StarredRow("940GSTOP9", "victoria", "inbound")
        val full = WatchEnvelopes.build(DeparturesSnapshot(stops = stops, fetchedAt = now), setOf(starred), now = now).bytes.size
        val payload = WatchEnvelopes.build(
            DeparturesSnapshot(stops = stops, fetchedAt = now),
            starred = setOf(starred),
            selected = setOf(selected),
            dataItemBudget = 100,
            transferCeiling = full / 2, now = now)
        val envelope = decoded(payload)
        assertTrue(payload.bytes.size <= full / 2)
        assertTrue(envelope.stops.any { it.stopId == "940GSTOP10" })
        assertTrue(envelope.stops.any { it.stopId == "940GSTOP9" })
        assertTrue(envelope.stops.any { it.stopId == "940GSTOP1" })
        assertEquals(10 - envelope.stops.size, envelope.omittedStops)
        assertTrue(envelope.omittedStops > 0)
    }

    @Test
    fun `a picked row's stop with no predictions right now is still protected`() {
        val busy = (1..10).map { stop("940GSTOP$it", listOf(departure(2))) }
        val quiet = StopArrivals(
            stopId = "940GQUIET",
            stopName = "Stop 940GQUIET",
            departures = emptyList(),
            fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        val snapshot = DeparturesSnapshot(stops = busy + quiet, fetchedAt = now)
        val full = WatchEnvelopes.build(snapshot, emptySet(), now = now).bytes.size
        val pick = StarredRow("940GQUIET", "victoria", "inbound")
        val envelope = decoded(WatchEnvelopes.build(snapshot, emptySet(), selected = setOf(pick), dataItemBudget = 100, transferCeiling = full / 2, now = now))
        assertTrue(envelope.omittedStops > 0)
        assertTrue("the complication shows its empty form, not the default", envelope.stops.any { it.stopId == "940GQUIET" })
        // A pick on a line the stop doesn't serve protects nothing.
        val stray = StarredRow("940GQUIET", "central", "inbound")
        val without = decoded(WatchEnvelopes.build(snapshot, emptySet(), selected = setOf(stray), dataItemBudget = 100, transferCeiling = full / 2, now = now))
        assertFalse(without.stops.any { it.stopId == "940GQUIET" })
    }

    @Test
    fun `a pick whose services all terminate nearer protects nothing`() {
        val busy = (1..10).map { stop("940GSTOP$it", listOf(departure(2))) }
        val ending = departure(2, destination = "Near").copy(destinationId = "940GNEAR")
        val filtered = stop("940GFILTERED", listOf(ending)).copy(nearer = Terminating.Nearer(ids = setOf("940GNEAR")))
        val snapshot = DeparturesSnapshot(stops = busy + filtered, fetchedAt = now)
        val full = WatchEnvelopes.build(snapshot, emptySet(), now = now).bytes.size
        val pick = StarredRow("940GFILTERED", "victoria", "inbound")
        val envelope = decoded(WatchEnvelopes.build(snapshot, emptySet(), selected = setOf(pick), dataItemBudget = 100, transferCeiling = full / 2, now = now))
        assertTrue(envelope.omittedStops > 0)
        assertFalse(envelope.stops.any { it.stopId == "940GFILTERED" })
    }

    @Test
    fun `a row the terminating filter removed stays removed after its services leave`() {
        // Fetched 2 min ago: the one service ending nearer has departed, the stop is still fresh.
        val ending = departure(-1, destination = "Near").copy(destinationId = "940GNEAR")
        val other = departure(3, destination = "Walthamstow Central", direction = "outbound")
        val filtered = stop("940GFILTERED", listOf(ending, other), fetchedAt = now.minusSeconds(120))
            .copy(nearer = Terminating.Nearer(ids = setOf("940GNEAR")))
        val row = StarredRow("940GFILTERED", "victoria", "inbound")
        assertFalse(DepartureRows.shows(filtered, row, emptySet(), now))

        val republished = decoded(WatchEnvelopes.build(DeparturesSnapshot(listOf(filtered), now), emptySet(), now = now)).stops.single()
        assertFalse(DepartureRows.shows(republished.toDomain(), row, emptySet(), now))
        // It carries no departed service a live direction doesn't need, and none past the boundary.
        assertEquals(1, republished.departures.count { it.expectedArrivalMillis <= now.toEpochMilli() })
        val stale = decoded(WatchEnvelopes.build(DeparturesSnapshot(listOf(filtered), now), emptySet(), now = now.plusSeconds(240))).stops.single()
        assertEquals(0, stale.departures.count { it.expectedArrivalMillis <= now.plusSeconds(240).toEpochMilli() })
    }

    @Test
    fun `an envelope from a newer phone is refused, not misread`() {
        val newer = """{"version":${WatchEnvelope.CURRENT_VERSION + 1},"stops":[{"somethingNew":1}]}"""
        assertEquals(WatchDecode.UnsupportedVersion(WatchEnvelope.CURRENT_VERSION + 1), WatchEnvelopes.decode(newer.encodeToByteArray()))
        val garbled = WatchEnvelopes.decode("not json".encodeToByteArray())
        assertTrue(garbled is WatchDecode.Unreadable)
        // The reason names the failure, never the payload.
        assertFalse("not json" in (garbled as WatchDecode.Unreadable).reason)
        assertEquals(WatchDecode.Unreadable("no version"), WatchEnvelopes.decode("{}".encodeToByteArray()))
    }

    @Test
    fun `the wire format has no coordinate or key field`() {
        val names = buildList {
            for (d in listOf(WatchEnvelope.serializer().descriptor, PersistedStop.serializer().descriptor, PersistedDeparture.serializer().descriptor)) {
                for (i in 0 until d.elementsCount) add(d.getElementName(i).lowercase())
            }
        }
        val forbidden = setOf("lat", "lon", "lng", "latitude", "longitude", "coordinate", "coordinates", "location", "appkey", "app_key", "apikey", "key")
        assertTrue(names.toString(), names.none { it in forbidden || it.startsWith("lat") || it.startsWith("lon") })
    }

    @Test
    fun `each via branch keeps its own countdown list past the boundary`() {
        val bank = (6..12L).map { departure(it, line = "northern", destination = "Edgware").copy(branch = "Bank") }
        val charingX = listOf(departure(20, line = "northern", destination = "Edgware").copy(branch = "Charing X"))
        val snapshot = DeparturesSnapshot(stops = listOf(stop("940GEXAMPLE1", bank + charingX)), fetchedAt = now)
        val kept = decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now)).stops.single().departures
        assertEquals(3, kept.count { it.branch == "Bank" })
        assertEquals(1, kept.count { it.branch == "Charing X" })
    }

    @Test
    fun `past the ceiling, the stop the widget ranks lowest goes first, not the last stored`() {
        val soon = stop("940GSOON", listOf(departure(1)))
        val later = stop("940GLATER", listOf(departure(20, line = "central", destination = "Ealing")))
        // Stored with the lower-priority stop first.
        val snapshot = DeparturesSnapshot(stops = listOf(later, soon), fetchedAt = now)
        val full = WatchEnvelopes.build(snapshot, emptySet(), now = now).bytes.size
        val envelope = decoded(
            WatchEnvelopes.build(snapshot, emptySet(), dataItemBudget = 10, transferCeiling = full - 1, now = now),
        )
        assertEquals(listOf("940GSOON"), envelope.stops.map { it.stopId })
        assertEquals(1, envelope.omittedStops)
    }

    @Test
    fun `past the ceiling, a stale stop's live suspension outranks a fresh stop`() {
        val fresh = stop("940GFRESH", listOf(departure(2)))
        val suspended = StopArrivals(
            "940GSUSP", "Stop 940GSUSP", emptyList(), now.minusSeconds(320),
            lines = listOf(LineRef("waterloo-city", "Waterloo-city", "tube")),
        )
        val snapshot = DeparturesSnapshot(
            stops = listOf(fresh, suspended),
            fetchedAt = now,
            lineStatuses = mapOf(
                "waterloo-city" to LineStatusCheck(LineStatus("waterloo-city", 5, "Suspended"), now.minusSeconds(30)),
            ),
        )
        val full = WatchEnvelopes.build(snapshot, emptySet(), now = now).bytes.size
        val envelope = decoded(
            WatchEnvelopes.build(snapshot, emptySet(), dataItemBudget = 10, transferCeiling = full - 1, now = now),
        )
        assertEquals(listOf("940GSUSP"), envelope.stops.map { it.stopId })
    }

    @Test
    fun `the ceiling holds even when every stop is starred`() {
        val stops = (1..6).map { stop("940GSTOP$it", listOf(departure(it.toLong()))) }
        val stars = stops.mapTo(HashSet()) { StarredRow(it.stopId, "victoria", "inbound") }
        val snapshot = DeparturesSnapshot(stops = stops, fetchedAt = now)
        val full = WatchEnvelopes.build(snapshot, stars, now = now).bytes.size
        val payload = WatchEnvelopes.build(snapshot, stars, dataItemBudget = 10, transferCeiling = full / 2, now = now)
        assertTrue(payload.bytes.size <= full / 2)
        assertTrue(decoded(payload).omittedStops > 0)
        // The soonest (highest-ranked) starred stop is kept.
        assertTrue(decoded(payload).stops.any { it.stopId == "940GSTOP1" })
    }

    @Test
    fun `only stars for stops it carries travel, so the ceiling holds whatever is starred`() {
        val snapshot = DeparturesSnapshot(stops = listOf(stop("940GEXAMPLE1", listOf(departure(2)))), fetchedAt = now)
        val here = StarredRow("940GEXAMPLE1", "victoria", "inbound")
        // Thousands of stars at stops the snapshot doesn't hold.
        val elsewhere = (1..5_000).mapTo(HashSet()) { StarredRow("940GELSEWHERE$it", "victoria", "inbound") }
        val envelope = decoded(WatchEnvelopes.build(snapshot, elsewhere + here, now = now))
        assertEquals(listOf(WatchStarKey.of(here)), envelope.starred)

        val payload = WatchEnvelopes.build(snapshot, elsewhere + here, dataItemBudget = 10, transferCeiling = 200, now = now)
        assertTrue(payload.bytes.size <= 200)
    }

    @Test
    fun `a decoded rail stop renders its feed state once a line status asks for a row`() {
        // A status row (a line with nothing to count) needs a disrupted line status, which the
        // snapshot doesn't carry yet (TODO Phase 4); given one, the decoded stop says "No key".
        val rail = StopArrivals(
            "910GEXAMPLE", "Example", emptyList(), now,
            lines = listOf(LineRef("southern", "Southern", "national-rail")),
            railFeed = RailFeed.NO_KEY,
        )
        val decodedStops = decoded(WatchEnvelopes.build(DeparturesSnapshot(listOf(rail), now), emptySet(), now = now))
            .stops.map { it.toDomain() }
        val statuses = mapOf("southern" to LineStatus("southern", severity = 6, description = "Severe delays"))
        val row = DepartureRows.across(decodedStops, now, lineStatuses = statuses).single()
        assertEquals(NoTimes.NO_KEY, NoTimes.of(row))
    }

    @Test
    fun `departed predictions can't spend the cap past a stale stop's boundary`() {
        // Carried forward: fetched 10 min ago, so its boundary passed 5 min ago.
        val stale = stop(
            "940GSTALE",
            listOf(departure(-4), departure(-3), departure(-2), departure(5)),
            fetchedAt = now.minusSeconds(600),
        ).copy(arrivalsFresh = false)
        val kept = decoded(WatchEnvelopes.build(DeparturesSnapshot(listOf(stale), now), emptySet(), now = now)).stops.single().departures
        assertEquals(listOf(now.plusSeconds(300).toEpochMilli()), kept.map { it.expectedArrivalMillis })
    }

    @Test
    fun `same-named termini with different IDs keep their own lists`() {
        val nearer = (6..8L).map { departure(it, destination = "Walthamstow Central").copy(destinationId = "940GNEARER") }
        val farther = listOf(departure(20, destination = "Walthamstow Central").copy(destinationId = "940GFARTHER"))
        val kept = decoded(WatchEnvelopes.build(DeparturesSnapshot(listOf(stop("940GEXAMPLE1", nearer + farther)), now), emptySet(), now = now))
            .stops.single().departures
        assertEquals(1, kept.count { it.destinationId == "940GFARTHER" })
    }

    @Test
    fun `the stops the last refresh couldn't get travel with it`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("940GEXAMPLE1", listOf(departure(2)))),
            fetchedAt = now,
            missingStopIds = setOf("940GMISSING"),
        )
        assertEquals(listOf("940GMISSING"), decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now)).missingStopIds)
    }

    @Test
    fun `a long missing-stop list can't hold the payload over the ceiling`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("940GEXAMPLE1", listOf(departure(2)))),
            fetchedAt = now,
            missingStopIds = (1..5_000).mapTo(HashSet()) { "940GMISSING$it" },
        )
        val payload = WatchEnvelopes.build(snapshot, emptySet(), dataItemBudget = 10, transferCeiling = 2_000, now = now)
        assertTrue(payload.bytes.size <= 2_000)
        val envelope = decoded(payload)
        assertEquals(1, envelope.missingStopIds.size)
        assertEquals(listOf("940GEXAMPLE1"), envelope.stops.map { it.stopId })
    }

    @Test
    fun `the hidden modes travel with it`() {
        val snapshot = DeparturesSnapshot(listOf(stop("940GEXAMPLE1", listOf(departure(2)))), now)
        val payload = WatchEnvelopes.build(snapshot, emptySet(), hiddenModes = setOf("tube", "bus"), now = now)
        assertEquals(listOf("bus", "tube"), decoded(payload).hiddenModes)
    }

    private val bank = RoutePattern("Bank", listOf("940GA", "940GB", "940GC"), "Edgware", "Morden")
    private val charingX = RoutePattern("Charing X", listOf("940GA", "940GX", "940GC"), "Edgware", "Morden")

    @Test
    fun `the phone's refreshed route lines travel with it, and read back as patterns`() {
        val snapshot = DeparturesSnapshot(listOf(stop("940GEXAMPLE1", listOf(departure(2)))), now)
        val envelope = decoded(WatchEnvelopes.build(snapshot, emptySet(), routeLines = mapOf("northern" to listOf(bank, charingX)), now = now))
        assertEquals(mapOf("northern" to listOf(bank, charingX)), envelope.routePatterns())
        // None by default, as when TfL's routes match the asset: nothing extra on the wire.
        assertEquals(emptyMap<String, List<RoutePattern>>(), decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now)).routeLines)
    }

    @Test
    fun `the route lines encode the same whatever order they came in, so an unchanged set isn't republished`() {
        val snapshot = DeparturesSnapshot(listOf(stop("940GEXAMPLE1", listOf(departure(2)))), now)
        val central = listOf(RoutePattern(null, listOf("940GD", "940GE"), "Ealing Broadway", "Epping"))
        val one = linkedMapOf("northern" to listOf(bank), "central" to central)
        val other = linkedMapOf("central" to central, "northern" to listOf(bank))
        assertEquals(
            WatchEnvelopes.build(snapshot, emptySet(), routeLines = one, now = now).bytes.decodeToString(),
            WatchEnvelopes.build(snapshot, emptySet(), routeLines = other, now = now).bytes.decodeToString(),
        )
    }

    @Test
    fun `the route lines stay when stops are dropped past the ceiling`() {
        val stops = listOf(stop("940GONE", listOf(departure(1))), stop("940GTWO", listOf(departure(5))))
        val snapshot = DeparturesSnapshot(stops, now)
        val lines = mapOf("northern" to listOf(bank, charingX))
        val full = WatchEnvelopes.build(snapshot, emptySet(), routeLines = lines, now = now).bytes.size
        val envelope = decoded(WatchEnvelopes.build(snapshot, emptySet(), routeLines = lines, dataItemBudget = 10, transferCeiling = full - 1, now = now))
        assertEquals(1, envelope.omittedStops)
        assertEquals(lines, envelope.routePatterns())
    }

    @Test
    fun `a route line with a pattern that doesn't read is left out, and an older envelope has none`() {
        val envelope = WatchEnvelope(
            routeLines = mapOf(
                "northern" to listOf(PersistedRoutePattern.of(bank), PersistedRoutePattern("Bank", listOf("940GA"), "Edgware", "Morden")),
                "central" to listOf(PersistedRoutePattern(null, listOf("940GD", "940GE"), "Ealing Broadway", "Epping")),
            ),
        )
        assertEquals(setOf("central"), envelope.routePatterns().keys)
        val older = """{"version":${WatchEnvelope.CURRENT_VERSION},"stops":[]}""".encodeToByteArray()
        assertEquals(emptyMap<String, List<RoutePattern>>(), (WatchEnvelopes.decode(older) as WatchDecode.Ok).envelope.routePatterns())
    }

    @Test
    fun `past the ceiling a stop of hidden modes goes before one the tile would show`() {
        val busOnly = stop("940GBUS", listOf(departure(1, line = "73", mode = "bus")))
        val tube = stop("940GTUBE", listOf(departure(5)))
        val snapshot = DeparturesSnapshot(stops = listOf(busOnly, tube), fetchedAt = now)
        // Starred and soonest, but of a hidden mode: neither protects it nor ranks it first.
        val star = setOf(StarredRow("940GBUS", "73", "inbound"))
        val full = WatchEnvelopes.build(snapshot, star, hiddenModes = setOf("bus"), now = now).bytes.size
        val payload = WatchEnvelopes.build(
            snapshot, star, hiddenModes = setOf("bus"), dataItemBudget = 10, transferCeiling = full - 1, now = now,
        )
        assertEquals(listOf("940GTUBE"), decoded(payload).stops.map { it.stopId })
    }

    @Test
    fun `a disruption dismissed on the phone isn't marked on the watch, and its line counts as checked`() {
        val severe = LineStatus("victoria", 6, "Severe Delays")
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("940GEXAMPLE1", listOf(departure(3)))),
            fetchedAt = now,
            lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now, dismissed = true)),
        )
        val envelope = decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now))
        assertTrue(envelope.lineStatuses.single().dismissed)
        assertTrue(envelope.liveLineStatuses(now).isEmpty())
        assertTrue(envelope.statusKnown("victoria", now))
    }

    @Test
    fun `the app's verdict that a bus alert is behind a stop reaches the watch, which then doesn't flag it`() {
        val diversion = LineStatus(
            "99", 5, "Diversion", "Buses are not serving stops between 'Bank Station' and 'Moorgate Station'.", soleAlert = true,
        )
        val bus = departure(3, line = "99", destination = "North End", mode = "bus")
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490GEXAMPLE1", listOf(bus)), stop("490GEXAMPLE2", listOf(bus))),
            fetchedAt = now,
            lineStatuses = mapOf("99" to LineStatusCheck(diversion, now)),
        ).withAlertsBehind(
            setOf(
                AlertBehind("99", lineAlertFingerprint(diversion), "490GEXAMPLE1", "inbound"),
                // A stop the snapshot doesn't carry: its verdict goes nowhere, the watch least of all.
                AlertBehind("99", lineAlertFingerprint(diversion), "490GELSEWHERE", "inbound"),
            ),
        )
        val envelope = decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now))
        assertEquals(listOf(PersistedStopWay("490GEXAMPLE1", "inbound")), envelope.lineStatuses.single().behind)
        val rows = DepartureRows.across(envelope.stops.map { it.toDomain() }, now, envelope.liveLineStatuses(now)).associateBy { it.stopId }
        assertNull(rows.getValue("490GEXAMPLE1").status)
        assertEquals("Diversion", rows.getValue("490GEXAMPLE2").status?.description)
    }

    @Test
    fun `a stop trimmed from an envelope over its budget isn't named by a verdict either`() {
        val diversion = LineStatus("99", 5, "Diversion", "Not serving 'A' to 'B'.", soleAlert = true)
        val stops = (1..30).map { i -> stop("490GEXAMPLE$i", (1..6).map { departure(it.toLong(), line = "99", destination = "Far End $i", mode = "bus") }) }
        val snapshot = DeparturesSnapshot(stops = stops, fetchedAt = now, lineStatuses = mapOf("99" to LineStatusCheck(diversion, now)))
            .withAlertsBehind(stops.mapTo(HashSet()) { AlertBehind("99", lineAlertFingerprint(diversion), it.stopId, "inbound") })
        val full = WatchEnvelopes.build(snapshot, emptySet(), now = now).bytes.size
        val trimmed = decoded(WatchEnvelopes.build(snapshot, emptySet(), dataItemBudget = 100, transferCeiling = full / 2, now = now))
        val sent = trimmed.stops.map { it.stopId }.toSet()
        assertTrue(sent.size < stops.size)
        assertEquals(sent, trimmed.lineStatuses.single().behind.map { it.stopId }.toSet())
    }

    @Test
    fun `the phone keeps whether an alert is the line's only one, never a verdict applied to it`() {
        val diversion = LineStatus("99", 5, "Diversion", "Not serving 'A' to 'B'.", soleAlert = true)
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490GEXAMPLE1", listOf(departure(3, line = "99", mode = "bus")))),
            fetchedAt = now,
            lineStatuses = mapOf("99" to LineStatusCheck(diversion, now)),
        )
        val placed = snapshot.withAlertsBehind(setOf(AlertBehind("99", lineAlertFingerprint(diversion), "490GEXAMPLE1", "inbound")))
        val stored = placed.toPersisted().toDomain()!!.lineStatuses.getValue("99").status
        assertTrue(stored.soleAlert)
        assertEquals(emptySet<StopWay>(), stored.behindAt)
        // So a verdict applied as it's read again places it again.
        assertEquals(
            setOf(StopWay("490GEXAMPLE1", "inbound")),
            placed.toPersisted().toDomain()!!.withAlertsBehind(setOf(AlertBehind("99", lineAlertFingerprint(diversion), "490GEXAMPLE1", "inbound")))
                .lineStatuses.getValue("99").status.behindAt,
        )
    }

    @Test
    fun `planned work starting today takes the app's verdict through the stored snapshot and on the watch`() {
        val today = now.atZone(AlertStart.ZONE).toLocalDate()
        val work = PlannedAlert("Diversion", "Buses are not serving stops between 'Bank Station' and 'Moorgate Station'.", today, severity = 5)
        val plannedOnly = LineStatus("99", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(work))
        val bus = departure(3, line = "99", destination = "North End", mode = "bus")
        val fresh = DeparturesSnapshot(
            stops = listOf(stop("490GEXAMPLE1", listOf(bus)), stop("490GEXAMPLE2", listOf(bus))),
            fetchedAt = now,
            lineStatuses = mapOf("99" to LineStatusCheck(plannedOnly, now)),
        )
        // Stored, its prose left out, and read back with the verdict the app reached on the work's words.
        val verdict = AlertBehind("99", lineAlertFingerprint(plannedOnly.asOf(today)), "490GEXAMPLE1", "inbound")
        val stored = fresh.toPersisted().toDomain()!!
        val placed = stored.withAlertsBehind(setOf(verdict))
        fun drawn(statuses: Map<String, LineStatus>, stops: List<StopArrivals>) =
            DepartureRows.across(stops, now, statuses).associateBy { it.stopId }
        val widget = drawn(placed.liveLineStatuses(now), placed.stops)
        assertNull(widget.getValue("490GEXAMPLE1").status)
        assertEquals("Diversion", widget.getValue("490GEXAMPLE2").status?.description)
        // The phone keeps none of it, as for a status's own verdict.
        assertEquals(emptySet<StopWay>(), placed.toPersisted().toDomain()!!.lineStatuses.getValue("99").status.planned.single().behindAt)
        // The watch is sent it, and draws the same.
        val envelope = decoded(WatchEnvelopes.build(placed, emptySet(), now = now))
        assertEquals(listOf(PersistedStopWay("490GEXAMPLE1", "inbound")), envelope.lineStatuses.single().planned.single().behind)
        val watch = drawn(envelope.liveLineStatuses(now), envelope.stops.map { it.toDomain() })
        assertNull(watch.getValue("490GEXAMPLE1").status)
        assertEquals("Diversion", watch.getValue("490GEXAMPLE2").status?.description)
    }

    @Test
    fun `a stop trimmed from an envelope isn't named by a verdict on planned work either`() {
        val today = now.atZone(AlertStart.ZONE).toLocalDate()
        val work = PlannedAlert("Diversion", "Not serving 'A' to 'B'.", today, severity = 5)
        val plannedOnly = LineStatus("99", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(work))
        val stops = (1..30).map { i -> stop("490GEXAMPLE$i", (1..6).map { departure(it.toLong(), line = "99", destination = "Far End $i", mode = "bus") }) }
        val snapshot = DeparturesSnapshot(stops = stops, fetchedAt = now, lineStatuses = mapOf("99" to LineStatusCheck(plannedOnly, now)))
            .withAlertsBehind(stops.mapTo(HashSet()) { AlertBehind("99", plannedShownFingerprint(work), it.stopId, "inbound") })
        val full = WatchEnvelopes.build(snapshot, emptySet(), now = now).bytes.size
        val trimmed = decoded(WatchEnvelopes.build(snapshot, emptySet(), dataItemBudget = 100, transferCeiling = full / 2, now = now))
        val sent = trimmed.stops.map { it.stopId }.toSet()
        assertTrue(sent.size < stops.size)
        assertEquals(sent, trimmed.lineStatuses.single().planned.single().behind.map { it.stopId }.toSet())
    }

    @Test
    fun `carries the kept stops' line checks, age-stamped, and marks only a live disruption`() {
        val severe = LineStatus("victoria", 6, "Severe Delays")
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("940GEXAMPLE1", listOf(departure(3)))),
            fetchedAt = now,
            lineStatuses = mapOf(
                "victoria" to LineStatusCheck(severe.copy(fullText = "Signal failure"), now),
                // A line no carried stop shows goes no further.
                "jubilee" to LineStatusCheck(severe.copy(lineId = "jubilee"), now),
            ),
        )
        val envelope = decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now))
        assertEquals(listOf(PersistedLineStatus("victoria", 6, "Severe Delays", now.toEpochMilli())), envelope.lineStatuses)
        assertEquals(mapOf("victoria" to severe), envelope.liveLineStatuses(now.plusSeconds(60)))
        assertTrue(envelope.liveLineStatuses(now.plus(java.time.Duration.ofMinutes(5))).isEmpty())
        assertEquals(listOf(now.plus(java.time.Duration.ofMinutes(5))), envelope.lineStatusExpiries(now))
        // A check from the future (the clock moved back) has no expiry that matters.
        assertTrue(envelope.lineStatusExpiries(now.minusSeconds(1)).isEmpty())
    }

    @Test
    fun `an envelope from an older phone, with no line checks, reads as none`() {
        val older = """{"version":1,"stops":[]}""".encodeToByteArray()
        val envelope = (WatchEnvelopes.decode(older) as WatchDecode.Ok).envelope
        assertTrue(envelope.lineStatuses.isEmpty())
    }

    @Test
    fun `a no-verdict check marks nothing and leaves the line unchecked`() {
        val envelope = WatchEnvelope(lineStatuses = listOf(LineStatusCheck.noVerdict("victoria", now).toPersisted()))
        assertTrue(envelope.liveLineStatuses(now).isEmpty())
        assertFalse(envelope.statusKnown("victoria", now))
    }

    @Test
    fun `each direction's status reaches the watch, dismissals applied, without its fingerprint`() {
        val north = LineStatus("victoria", 6, "Severe Delays", "Signal failure northbound.")
        val south = LineStatus("victoria", 9, "Minor Delays", "Train fault southbound.")
        val split = LineStatus("victoria", 6, "Severe Delays", "Both.", byDirection = mapOf("inbound" to north, "outbound" to south))
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("940GEXAMPLE1", listOf(departure(3)))),
            fetchedAt = now,
            lineStatuses = mapOf("victoria" to LineStatusCheck(split, now, dismissedDirections = setOf("inbound"))),
        )
        val envelope = decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now))
        val directions = envelope.lineStatuses.single().directions
        assertEquals(listOf("inbound", "outbound"), directions.map { it.direction })
        assertTrue(directions.all { it.fingerprint == null })
        assertEquals(listOf(true, false), directions.map { it.dismissed })
        val live = envelope.liveLineStatuses(now).getValue("victoria")
        assertEquals(false, live.forDirection("inbound").disrupted)
        assertEquals(9, live.forDirection("outbound").severity)
    }

    @Test
    fun `work still to come reaches the watch with its calendar, a dismissed one put away`() {
        val closure = PlannedAlert("Part Closure", "No service between Stop A and Stop B.", LocalDate.of(2026, 10, 3))
        val diversion = PlannedAlert("Diversion", "Buses diverted.", LocalDate.of(2026, 10, 10))
        val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(closure, diversion))
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("940GEXAMPLE1", listOf(departure(3)))),
            fetchedAt = now,
            lineStatuses = mapOf("victoria" to LineStatusCheck(good, now)),
        ).withDismissals(setOf(DismissedAlert.ofPlanned("victoria", closure)))
        val envelope = decoded(WatchEnvelopes.build(snapshot, emptySet(), now = now))
        val planned = envelope.lineStatuses.single().planned
        // TfL's prose stays on the phone; its identity goes, as what the dismissed flag marks.
        assertEquals(listOf("Part Closure" to true, "Diversion" to false), planned.map { it.label to it.dismissed })
        assertEquals(listOf(closure, diversion).map(::plannedAlertFingerprint), planned.map { it.fingerprint })
        val live = envelope.liveLineStatuses(now).getValue("victoria")
        assertEquals(false, live.disrupted)
        assertEquals(listOf("Diversion"), live.planned.map { it.label })
        assertEquals(LocalDate.of(2026, 10, 10), live.planned.single().startsOn)
        // On its day the dismissed calendar's work is under way, and marked as such.
        val onTheDay = Instant.parse("2026-10-02T23:01:00Z")
        val shifted = envelope.copy(lineStatuses = envelope.lineStatuses.map { it.copy(checkedAtMillis = onTheDay.minusSeconds(60).toEpochMilli()) })
        assertEquals("Part Closure", shifted.liveLineStatuses(onTheDay).getValue("victoria").description)
    }

    @Test
    fun `a fetch goes out as the phone's wall clock reads it, whatever the clock was set since`() {
        // Fetched a minute before the phone's clock was set back an hour: its steady stamp is an
        // hour ahead of the wall clock now, but it's a minute old.
        SteadyClock.source = object : SteadyClock.Source {
            override val frame: SteadyClock.Frame? = SteadyClock.Frame("device/7", 0L)
            override fun offset(): java.time.Duration = java.time.Duration.ofHours(1)
        }
        try {
            val wallNow = now.minusSeconds(3600 - 60)
            val severe = LineStatus("victoria", 6, "Severe Delays")
            val snapshot = DeparturesSnapshot(
                listOf(stop("940GEXAMPLE1", listOf(departure(3)))),
                now,
                lineStatuses = mapOf("victoria" to LineStatusCheck(severe, now)),
            )
            val envelope = decoded(WatchEnvelopes.build(snapshot, starred = emptySet(), now = wallNow))
            // The watch ages it by its own wall clock, which reads as the phone's does: a minute old.
            assertEquals(now.minusSeconds(3600).toEpochMilli(), envelope.stops.single().fetchedAtMillis)
            // A line check, stamped by the steady clock as a fetch is, goes out the same way, so the
            // watch still marks the disruption: it's a minute old, not an hour from the future.
            assertEquals(now.minusSeconds(3600).toEpochMilli(), envelope.lineStatuses.single().checkedAtMillis)
        } finally {
            SteadyClock.source = null
        }
    }
}
