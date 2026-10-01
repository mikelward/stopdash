package app.stopdash.domain

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventTest {
    private fun stop(id: String, vararg modes: String) =
        StopLocation(id, "Stop $id", 51.5, -0.12, lines = modes.mapIndexed { i, m -> LineRef("$id-line$i", "Line $i", m) })

    // Firebase's naming rules, and what every value may be: a category or a bucket, never free text.
    private val name = Regex("[a-z][a-z0-9_]{0,39}")
    private val vocabulary = UsageEvent.Tap.entries.map { it.value } +
        UsageEvent.Grant.entries.map { it.value } +
        UsageEvent.FixOutcome.entries.map { it.value } +
        ModeGroups.ALL.map { it.key } +
        listOf(
            "other", "farther_place", "faraway_favorites", "unknown",
            "0", "1", "2-3", "4+",
            "0-10m", "10-25m", "25-50m", "50-100m", "100-500m", "500m+",
            "0-1s", "1-3s", "3-10s", "10-30s", "30s+",
        )

    private fun assertClosed(event: UsageEvent) {
        assertTrue(event.name, name.matches(event.name))
        for ((key, value) in event.params) {
            assertTrue(key, name.matches(key))
            assertTrue("$key=$value", value in vocabulary)
        }
    }

    @Test
    fun `a tap is named by its kind alone`() {
        UsageEvent.Tap.entries.forEach { assertClosed(UsageEvent.Tapped(it)) }
        assertEquals(mapOf("kind" to "journey_card"), UsageEvent.Tapped(UsageEvent.Tap.JOURNEY_CARD).params)
    }

    @Test
    fun `a reveal names its mode group, never a mode id outside them`() {
        assertEquals(mapOf("what" to "farther_place", "mode" to "tube"), UsageEvent.FartherPlace(listOf("dlr")).params)
        assertEquals(mapOf("what" to "farther_place", "mode" to "train"), UsageEvent.FartherPlace(listOf("Elizabeth-Line", "tube")).params)
        // A mode no group names, or none at all, is "other", not TfL's id.
        assertEquals("other", UsageEvent.FartherPlace(listOf("cable-car")).params["mode"])
        assertEquals("other", UsageEvent.FartherPlace(listOf("HIDDEN-LINE:central")).params["mode"])
        assertEquals("other", UsageEvent.FartherPlace(emptyList()).params["mode"])
        assertClosed(UsageEvent.FartherPlace(listOf("cable-car")))
        assertEquals(mapOf("what" to "faraway_favorites"), UsageEvent.FarawayFavorites.params)
        assertEquals("reveal", UsageEvent.FarawayFavorites.name)
    }

    @Test
    fun `a fix is reported in bands, with no accuracy for one that failed`() {
        val fresh = UsageEvent.LocationFix(UsageEvent.FixOutcome.FRESH, 18f, Duration.ofMillis(2400))
        assertEquals(mapOf("outcome" to "fresh", "accuracy" to "10-25m", "time_to_fix" to "1-3s"), fresh.params)
        val failed = UsageEvent.LocationFix(UsageEvent.FixOutcome.FAILED, 18f, Duration.ofSeconds(12))
        assertEquals(mapOf("outcome" to "failed", "accuracy" to "unknown", "time_to_fix" to "10-30s"), failed.params)
        val last = UsageEvent.LocationFix(UsageEvent.FixOutcome.LAST_KNOWN, null, null)
        assertEquals(mapOf("outcome" to "last_known", "accuracy" to "unknown", "time_to_fix" to "unknown"), last.params)
        listOf(fresh, failed, last).forEach(::assertClosed)
    }

    @Test
    fun `accuracy and time bands have closed edges`() {
        assertEquals("0-10m", UsageEvent.accuracyBand(0f))
        assertEquals("0-10m", UsageEvent.accuracyBand(10f))
        assertEquals("10-25m", UsageEvent.accuracyBand(10.1f))
        assertEquals("50-100m", UsageEvent.accuracyBand(100f))
        assertEquals("100-500m", UsageEvent.accuracyBand(500f))
        assertEquals("500m+", UsageEvent.accuracyBand(5000f))
        assertEquals("unknown", UsageEvent.accuracyBand(Float.NaN))
        assertEquals("unknown", UsageEvent.accuracyBand(-1f))
        assertEquals("0-1s", UsageEvent.timeBand(Duration.ZERO))
        assertEquals("1-3s", UsageEvent.timeBand(Duration.ofSeconds(1)))
        assertEquals("3-10s", UsageEvent.timeBand(Duration.ofSeconds(3)))
        assertEquals("30s+", UsageEvent.timeBand(Duration.ofMinutes(5)))
        assertEquals("unknown", UsageEvent.timeBand(Duration.ofSeconds(-1)))
    }

    @Test
    fun `nearby stops are counted per mode group, in buckets, every group present`() {
        val stops = listOf(
            stop("a", "tube", "overground"),
            stop("b", "bus"),
            stop("c", "bus"),
            stop("d", "bus", "bus"),
            stop("e", "bus"),
            stop("f", "dlr"),
            stop("g", "cable-car"),
        )
        val event = UsageEvent.NearbyStops(stops)
        assertEquals(
            mapOf("tube" to "2-3", "train" to "1", "bus" to "4+", "tram" to "0", "boat" to "0", "coach" to "0"),
            event.params,
        )
        assertClosed(event)
        assertEquals(ModeGroups.ALL.map { it.key }.toSet(), UsageEvent.NearbyStops(emptyList()).params.keys)
        assertTrue(UsageEvent.NearbyStops(emptyList()).params.values.all { it == "0" })
    }

    @Test
    fun `counts fall in their buckets`() {
        assertEquals(listOf("0", "0", "1", "2-3", "2-3", "4+", "4+"), listOf(-1, 0, 1, 2, 3, 4, 40).map(UsageEvent::countBucket))
    }

    @Test
    fun `permission grants are named alone`() {
        UsageEvent.Grant.entries.forEach { assertClosed(UsageEvent.LocationPermission(it)) }
        assertEquals(UsageEvent.Grant.PRECISE, UsageEvent.Grant.of(fine = true, coarse = true))
        assertEquals(UsageEvent.Grant.APPROXIMATE, UsageEvent.Grant.of(fine = false, coarse = true))
        assertEquals(UsageEvent.Grant.DENIED, UsageEvent.Grant.of(fine = false, coarse = false))
    }
}
