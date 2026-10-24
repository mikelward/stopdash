package app.stopdash.ui

import app.stopdash.domain.BundledStops
import app.stopdash.domain.Departure
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.Hubs
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.Journeys
import app.stopdash.domain.SiblingPoles
import app.stopdash.domain.LineRef
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StopLocation
import java.io.BufferedReader
import java.io.StringReader
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops around the obviously-fake (51.5, -0.12). */
class JourneyBoardingTest {
    private val top = JourneyEnd("TOP", "Top", 51.51, -0.12)
    private val mid = JourneyEnd("MID", "Mid", 51.49, -0.12)
    private val journey = FavoriteJourney(top, mid, listOf(LineRef("example", "", "")))
    // Each end's stops in its hub, by the station model: a ride runs between hubs (maintainer, 2026-10-21).
    // TOPX and MIDX, a bus stop just outside each, are each a hub of their own; so is TOPY, a pole TfL files
    // in Top's stop area.
    private val hubs: Hubs = run {
        val index = StationIndex(
            listOf("TOP" to "HUBTOP", "MID" to "HUBMID").map { (id, hub) ->
                IndexedStation(id, id, listOf("tube"), latitude = 51.5, longitude = -0.12, station = id, modelHub = hub)
            },
            revision = "abc",
        )
        val poles = mapOf("TOPB" to "HUBTOP", "TOPA" to "HUBTOP", "MIDB" to "HUBMID", "TOPX" to "TOPX", "MIDX" to "MIDX", "TOPY" to "TOPY")
        val tsv = listOf("# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=abc", "L\t1\tbus\t1") +
            poles.map { (id, hub) -> "S\t$id\tB\t$id\t51.500000\t-0.120000\t\t\t\t\t\t1\t\t$id\t\t$hub" }
        Hubs.of(index, BundledStops.read(BufferedReader(StringReader(tsv.joinToString("\n")))))!!
    }
    private val atTop = listOf(
        StopLocation("TOP", "Top", 51.51, -0.12),
        StopLocation("TOPB", "Top Road", 51.5102, -0.12, listOf(LineRef("b7", "7", "bus"))),
    )
    private val atMid = listOf(
        StopLocation("MID", "Mid", 51.49, -0.12),
        StopLocation("MIDB", "Mid Road", 51.4905, -0.12, listOf(LineRef("b7", "7", "bus"))),
    )

    @Test
    fun `a journey's boarding stops wait for both ends' lookups`() {
        val one = journeyBoardingOf(listOf(journey), mapOf("TOP" to atTop), hubs, emptyMap(), emptyMap())
        assertTrue(journey.key in one.poleKeys)
        assertFalse(journey.key in one.poles)
        val both = journeyBoardingOf(listOf(journey), mapOf("TOP" to atTop, "MID" to atMid), hubs, emptyMap(), emptyMap())
        assertEquals(listOf("TOP", "TOPB"), both.poles[journey.key]?.map { it.id })
        assertEquals(setOf("b7"), both.farEnds[journey.key]?.lineIds)
    }

    @Test
    fun `a failed lookup says so, and an area's poles join those around`() {
        val failed = journeyBoardingOf(listOf(journey), mapOf("TOP" to null, "MID" to atMid), hubs, emptyMap(), emptyMap())
        assertTrue(journey.key in failed.poles)
        assertNull(failed.poles[journey.key])
        // The far end's failing says so too, rather than fall back to the journey's own mode unsaid.
        val farFailed = journeyBoardingOf(listOf(journey), mapOf("TOP" to atTop, "MID" to null), hubs, emptyMap(), emptyMap())
        assertTrue(journey.key in farFailed.poles)
        assertNull(farFailed.poles[journey.key])
        val area = StopLocation("TOPA", "Top", 51.5101, -0.12)
        val joined = journeyBoardingOf(
            listOf(journey), mapOf("TOP" to atTop, "MID" to atMid), hubs,
            mapOf(journey.key to "G-TOP"), mapOf(journey.key to listOf(area, atTop[1])),
        )
        assertEquals(listOf("TOP", "TOPB", "TOPA"), joined.poles[journey.key]?.map { it.id })
        // A pole of the stop area the model files in another hub never joins (Codex, #1047), unless the model
        // can't place the origin.
        val wide = StopLocation("TOPY", "Top", 51.5103, -0.12)
        val split = journeyBoardingOf(
            listOf(journey), mapOf("TOP" to atTop, "MID" to atMid), hubs,
            mapOf(journey.key to "G-TOP"), mapOf(journey.key to listOf(area, wide)),
        )
        assertEquals(listOf("TOP", "TOPB", "TOPA"), split.poles[journey.key]?.map { it.id })
        val unplaced = journeyBoardingOf(
            listOf(journey), mapOf("TOP" to atTop, "MID" to atMid), null,
            mapOf(journey.key to "G-TOP"), mapOf(journey.key to listOf(area, wide)),
        )
        assertEquals(listOf("TOP", "TOPA", "TOPY"), unplaced.poles[journey.key]?.map { it.id })
    }

    @Test
    fun `a ride boards and alights only in its ends' hubs, however near another stop is`() {
        // A bus stop just outside each end's hub, a short walk from it, serving a line that reaches the other:
        // neither boards nor ends the ride (maintainer, 2026-10-21: a bus to a village a walk from the station
        // isn't a way to the station).
        val nearTop = atTop + StopLocation("TOPX", "Top Lane", 51.5104, -0.12, listOf(LineRef("b9", "9", "bus")))
        val nearMid = atMid + StopLocation("MIDX", "Mid Village", 51.4895, -0.12, listOf(LineRef("b9", "9", "bus")))
        val ride = journeyBoardingOf(listOf(journey), mapOf("TOP" to nearTop, "MID" to nearMid), hubs, emptyMap(), emptyMap())
        assertEquals(listOf("TOP", "TOPB"), ride.poles[journey.key]?.map { it.id })
        assertEquals(setOf("MID", "MIDB"), ride.farEnds[journey.key]?.ids)
        assertEquals(setOf("b7"), ride.farEnds[journey.key]?.lineIds)
        // Before the station model has loaded, each end is its own stop alone; its hub's others join once it has.
        val early = journeyBoardingOf(listOf(journey), mapOf("TOP" to nearTop, "MID" to nearMid), null, emptyMap(), emptyMap())
        assertEquals(listOf("TOP"), early.poles[journey.key]?.map { it.id })
        assertEquals(setOf("MID"), early.farEnds[journey.key]?.ids)
    }

    @Test
    fun `with no lookup wired a journey boards from its stop area alone`() {
        val none = journeyBoardingOf(emptyList(), emptyMap(), hubs, emptyMap(), emptyMap(), journeys = listOf(journey))
        assertTrue(none.poleKeys.isEmpty())
        assertTrue(none.poles.isEmpty())
    }

    @Test
    fun `a stop two journeys board from is fetched for both's lines`() {
        val other = FavoriteJourney(top, JourneyEnd("LOW", "Low", 51.48, -0.12), listOf(LineRef("example", "", "")))
        val shared = StopLocation("TOPB", "Top Road", 51.5102, -0.12, listOf(LineRef("b7", "7", "bus"), LineRef("b9", "9", "bus")))
        val siblings = mapOf(
            journey.key to SiblingPoles(listOf(shared), emptySet()),
            other.key to SiblingPoles(listOf(shared), emptySet()),
        )
        val farEnds = mapOf(
            journey.key to Journeys.FarEnd(setOf("MIDB"), setOf("b7")),
            other.key to Journeys.FarEnd(setOf("LOWB"), setOf("b9")),
        )
        val stops = journeyStopsOf(emptyList(), listOf(journey, other), emptyMap(), siblings, null, farEnds = farEnds)
        assertEquals(listOf("b7", "b9"), stops.fetched.value.single { it.id == "TOPB" }.lines.map { it.id })
    }

    @Test
    fun `an origin's own line of another mode reaching the far end is declared there`() {
        val origin = StopLocation("TOP", "Top", 51.51, -0.12, listOf(LineRef("b7", "7", "bus")))
        val refs = journeyOriginsOf(
            listOf(journey), emptyMap(), emptyMap(), mapOf(journey.key to listOf(origin)),
            mapOf(journey.key to Journeys.FarEnd(setOf("MIDB"), setOf("b7"))),
        )
        assertEquals(listOf("example", "b7"), refs.single().lines.map { it.id })
        val lines = journeyLineIdsOf(emptyList(), refs, emptyList(), listOf(journey), emptyMap(), emptyMap())
        assertTrue("b7" in lines)
    }

    @Test
    fun `a line hidden from every ride boarding at an origin isn't declared or loaded there`() {
        val origin = StopLocation("TOP", "Top", 51.51, -0.12, listOf(LineRef("b7", "7", "bus")))
        val hiding = journey.copy(hiddenLines = setOf(HiddenModes.lineKey("b7", "7")))
        val farEnds = mapOf(hiding.key to Journeys.FarEnd(setOf("MIDB"), setOf("b7")))
        val refs = journeyOriginsOf(listOf(hiding), emptyMap(), emptyMap(), mapOf(hiding.key to listOf(origin)), farEnds)
        assertEquals(listOf("example"), refs.single().lines.map { it.id })
        val departures = listOf(StopArrivals("TOP", "Top", listOf(Departure("b7", "7", "outbound", "Mid", null, Instant.EPOCH, "bus")), Instant.EPOCH))
        assertEquals(listOf("example"), journeyLineIdsOf(emptyList(), refs, departures, listOf(hiding), emptyMap(), emptyMap()))
        // Another ride boarding there that still shows it keeps its route loaded.
        val other = FavoriteJourney(top, JourneyEnd("LOW", "Low", 51.48, -0.12), listOf(LineRef("example", "", "")))
        assertTrue("b7" in journeyLineIdsOf(emptyList(), refs, departures, listOf(hiding, other), emptyMap(), emptyMap()))
    }

    @Test
    fun `an end's answer for a shorter walk waits for one that covers the walk now set`() {
        val ends = mapOf("TOP" to atTop, "MID" to atMid)
        val short = journeyBoardingOf(listOf(journey), ends, hubs, emptyMap(), emptyMap(), radius = mapOf("TOP" to 1100, "MID" to 1100), required = 1500)
        assertFalse(journey.key in short.poles)
        val covered = journeyBoardingOf(listOf(journey), ends, hubs, emptyMap(), emptyMap(), radius = mapOf("TOP" to 1500, "MID" to 1500), required = 1500)
        assertTrue(journey.key in covered.poles)
    }
}
