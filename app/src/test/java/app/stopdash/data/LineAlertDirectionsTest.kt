package app.stopdash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Instant
import org.junit.Test

class LineAlertDirectionsTest {
    private val now = Instant.parse("2026-09-29T08:00:00Z")

    private fun line(id: String, vararg alerts: Pair<String, List<String>>) = TflLineDto(
        id = id,
        lineStatuses = alerts.map { (reason, directions) ->
            TflLineStatusEntryDto(
                statusSeverity = 0,
                statusSeverityDescription = "Special Service",
                reason = reason,
                disruption = TflLineDisruptionDto(directions.map { TflAffectedRouteDto(it) }),
            )
        },
    )

    @Test
    fun `an unseen alert's line is claimed once, then answered from what was recorded`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList()))

        val claimed = cache.claimUnknown(plain, now)
        assertEquals(listOf("bus1"), claimed.map { it.id })
        // A refresh landing mid-lookup doesn't ask again, but knows the answer is still coming.
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, now))
        assertTrue(cache.anyUnknown(plain.single()))
        assertNull(cache.directionsOf("bus1", "Diverted"))

        cache.record(claimed, listOf(line("bus1", "Diverted" to listOf("inbound"))))
        assertEquals(setOf("inbound"), cache.directionsOf("bus1", "Diverted"))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, now))
        assertFalse(cache.anyUnknown(plain.single()))
    }

    @Test
    fun `a new alert on a known line is claimed again`() {
        val cache = LineAlertDirections()
        val claimed = cache.claimUnknown(listOf(line("bus1", "Old" to emptyList())), now)
        cache.record(claimed, listOf(line("bus1", "Old" to listOf("inbound"))))

        assertEquals(listOf("bus1"), cache.claimUnknown(listOf(line("bus1", "Old" to emptyList(), "New" to emptyList())), now).map { it.id })
    }

    @Test
    fun `an alert TfL scopes to no direction is remembered, and counts for both`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList()))
        cache.record(cache.claimUnknown(plain, now), listOf(line("bus1", "Diverted" to emptyList())))

        assertNull(cache.directionsOf("bus1", "Diverted"))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, now))
    }

    @Test
    fun `an alert the detail no longer carries isn't looked up again`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Withdrawn" to emptyList()))
        cache.record(cache.claimUnknown(plain, now), emptyList())

        assertNull(cache.directionsOf("bus1", "Withdrawn"))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, now))
    }

    @Test
    fun `entries sharing an alert's text keep every direction between them`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList(), "Diverted" to emptyList()))
        cache.record(
            cache.claimUnknown(plain, now),
            listOf(line("bus1", "Diverted" to listOf("inbound"), "Diverted" to listOf("outbound"))),
        )
        assertEquals(setOf("inbound", "outbound"), cache.directionsOf("bus1", "Diverted"))
    }

    @Test
    fun `a failed lookup waits before it's asked again, longer after each failure`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList()))
        cache.fail(cache.claimUnknown(plain, now), now)
        // Not on the next refresh: a minute on.
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, now.plusSeconds(30)))
        assertTrue(cache.anyUnknown(plain.single()))
        val second = now.plus(LineAlertDirections.RETRY_AFTER)
        assertEquals(listOf("bus1"), cache.claimUnknown(plain, second).map { it.id })
        // A second failure waits twice as long.
        cache.fail(plain, second)
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, second.plus(LineAlertDirections.RETRY_AFTER)))
        val third = second.plus(LineAlertDirections.RETRY_AFTER.multipliedBy(2))
        assertEquals(listOf("bus1"), cache.claimUnknown(plain, third).map { it.id })
        // Never longer than the cap, however many failures.
        var at = third
        repeat(20) {
            cache.fail(plain, at)
            at = at.plus(LineAlertDirections.RETRY_AFTER_MAX)
            assertEquals(listOf("bus1"), cache.claimUnknown(plain, at).map { it.id })
        }
        // An answer clears the wait: a new alert on the line is asked about at once.
        cache.record(plain, listOf(line("bus1", "Diverted" to listOf("inbound"))))
        val reworded = listOf(line("bus1", "Diverted again" to emptyList()))
        assertEquals(listOf("bus1"), cache.claimUnknown(reworded, at).map { it.id })
    }

    @Test
    fun `a new alert on a line waiting after failures is looked up at once, and starts its wait over`() {
        val cache = LineAlertDirections()
        val old = listOf(line("bus1", "Diverted" to emptyList()))
        var at = now
        // Failed three times: the next wait is four minutes.
        repeat(3) {
            cache.fail(cache.claimUnknown(old, at), at)
            at = at.plus(LineAlertDirections.RETRY_AFTER.multipliedBy(1L shl it))
        }
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(old, now.plus(LineAlertDirections.RETRY_AFTER.multipliedBy(4))))
        // TfL rewords it: a new alert, looked up at once, whose first failure waits a minute.
        val reworded = listOf(line("bus1", "Diverted via Street A" to emptyList()))
        val newAt = now.plus(LineAlertDirections.RETRY_AFTER.multipliedBy(4))
        cache.fail(cache.claimUnknown(reworded, newAt).also { assertEquals(listOf("bus1"), it.map { l -> l.id }) }, newAt)
        assertEquals(listOf("bus1"), cache.claimUnknown(reworded, newAt.plus(LineAlertDirections.RETRY_AFTER)).map { it.id })
        // An alert joining the one that failed is new too.
        cache.fail(reworded, newAt)
        val joined = listOf(line("bus1", "Diverted via Street A" to emptyList(), "Stop closed" to emptyList()))
        assertEquals(listOf("bus1"), cache.claimUnknown(joined, newAt).map { it.id })
    }

    @Test
    fun `a lookup given up on, or failed before a clock set back, is asked again at once`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList()))
        cache.release(cache.claimUnknown(plain, now))
        assertEquals(listOf("bus1"), cache.claimUnknown(plain, now).map { it.id })
        cache.fail(plain, now)
        assertEquals(listOf("bus1"), cache.claimUnknown(plain, now.minusSeconds(60)).map { it.id })
    }

    @Test
    fun `the least recently read alert is dropped past capacity`() {
        val cache = LineAlertDirections(capacity = 1)
        val a = listOf(line("a", "A" to listOf("inbound")))
        val b = listOf(line("b", "B" to listOf("outbound")))
        cache.record(a, a)
        cache.record(b, b)

        assertNull(cache.directionsOf("a", "A"))
        assertEquals(setOf("outbound"), cache.directionsOf("b", "B"))
    }
}
