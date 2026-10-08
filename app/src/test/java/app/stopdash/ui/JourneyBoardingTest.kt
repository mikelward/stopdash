package app.stopdash.ui

import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.Journeys
import app.stopdash.domain.SiblingPoles
import app.stopdash.domain.LineRef
import app.stopdash.domain.StopLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops around the obviously-fake (51.5, -0.12). */
class JourneyBoardingTest {
    private val top = JourneyEnd("TOP", "Top", 51.51, -0.12)
    private val mid = JourneyEnd("MID", "Mid", 51.49, -0.12)
    private val journey = FavoriteJourney(top, mid, "example")
    private val atTop = listOf(
        StopLocation("TOP", "Top", 51.51, -0.12),
        StopLocation("TOPB", "Top Road", 51.5102, -0.12, listOf(LineRef("b7", "7", "bus"))),
    )
    private val atMid = listOf(StopLocation("MIDB", "Mid Road", 51.4905, -0.12, listOf(LineRef("b7", "7", "bus"))))

    @Test
    fun `a journey's boarding stops wait for both ends' lookups`() {
        val one = journeyBoardingOf(listOf(journey), mapOf("TOP" to atTop), 800, emptyMap(), emptyMap())
        assertTrue(journey.key in one.poleKeys)
        assertFalse(journey.key in one.poles)
        val both = journeyBoardingOf(listOf(journey), mapOf("TOP" to atTop, "MID" to atMid), 800, emptyMap(), emptyMap())
        assertEquals(listOf("TOP", "TOPB"), both.poles[journey.key]?.map { it.id })
        assertEquals(setOf("b7"), both.farEnds[journey.key]?.lineIds)
    }

    @Test
    fun `a failed lookup says so, and an area's poles join those around`() {
        val failed = journeyBoardingOf(listOf(journey), mapOf("TOP" to null, "MID" to atMid), 800, emptyMap(), emptyMap())
        assertTrue(journey.key in failed.poles)
        assertNull(failed.poles[journey.key])
        // The far end's failing says so too, rather than fall back to the journey's own mode unsaid.
        val farFailed = journeyBoardingOf(listOf(journey), mapOf("TOP" to atTop, "MID" to null), 800, emptyMap(), emptyMap())
        assertTrue(journey.key in farFailed.poles)
        assertNull(farFailed.poles[journey.key])
        val area = StopLocation("TOPA", "Top", 51.5101, -0.12)
        val joined = journeyBoardingOf(
            listOf(journey), mapOf("TOP" to atTop, "MID" to atMid), 800,
            mapOf(journey.key to "G-TOP"), mapOf(journey.key to listOf(area, atTop[1])),
        )
        assertEquals(listOf("TOP", "TOPB", "TOPA"), joined.poles[journey.key]?.map { it.id })
    }

    @Test
    fun `with no lookup wired a journey boards from its stop area alone`() {
        val none = journeyBoardingOf(emptyList(), emptyMap(), 800, emptyMap(), emptyMap(), journeys = listOf(journey))
        assertTrue(none.poleKeys.isEmpty())
        assertTrue(none.poles.isEmpty())
    }

    @Test
    fun `a stop two journeys board from is fetched for both's lines`() {
        val other = FavoriteJourney(top, JourneyEnd("LOW", "Low", 51.48, -0.12), "example")
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
    fun `an end's answer for a shorter walk waits for one that covers the walk now set`() {
        val ends = mapOf("TOP" to atTop, "MID" to atMid)
        val short = journeyBoardingOf(listOf(journey), ends, 1200, emptyMap(), emptyMap(), radius = mapOf("TOP" to 1100, "MID" to 1100), required = 1500)
        assertFalse(journey.key in short.poles)
        val covered = journeyBoardingOf(listOf(journey), ends, 1200, emptyMap(), emptyMap(), radius = mapOf("TOP" to 1500, "MID" to 1500), required = 1500)
        assertTrue(journey.key in covered.poles)
    }
}
