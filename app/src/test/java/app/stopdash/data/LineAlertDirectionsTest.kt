package app.stopdash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineAlertDirectionsTest {
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

        val claimed = cache.claimUnknown(plain)
        assertEquals(listOf("bus1"), claimed.map { it.id })
        // A refresh landing mid-lookup doesn't ask again, but knows the answer is still coming.
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain))
        assertTrue(cache.anyUnknown(plain.single()))
        assertNull(cache.directionsOf("bus1", "Diverted"))

        cache.record(claimed, listOf(line("bus1", "Diverted" to listOf("inbound"))))
        assertEquals(setOf("inbound"), cache.directionsOf("bus1", "Diverted"))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain))
        assertFalse(cache.anyUnknown(plain.single()))
    }

    @Test
    fun `a new alert on a known line is claimed again`() {
        val cache = LineAlertDirections()
        val claimed = cache.claimUnknown(listOf(line("bus1", "Old" to emptyList())))
        cache.record(claimed, listOf(line("bus1", "Old" to listOf("inbound"))))

        assertEquals(listOf("bus1"), cache.claimUnknown(listOf(line("bus1", "Old" to emptyList(), "New" to emptyList()))).map { it.id })
    }

    @Test
    fun `an alert TfL scopes to no direction is remembered, and counts for both`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList()))
        cache.record(cache.claimUnknown(plain), listOf(line("bus1", "Diverted" to emptyList())))

        assertNull(cache.directionsOf("bus1", "Diverted"))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain))
    }

    @Test
    fun `an alert the detail no longer carries isn't looked up again`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Withdrawn" to emptyList()))
        cache.record(cache.claimUnknown(plain), emptyList())

        assertNull(cache.directionsOf("bus1", "Withdrawn"))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain))
    }

    @Test
    fun `entries sharing an alert's text keep every direction between them`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList(), "Diverted" to emptyList()))
        cache.record(
            cache.claimUnknown(plain),
            listOf(line("bus1", "Diverted" to listOf("inbound"), "Diverted" to listOf("outbound"))),
        )
        assertEquals(setOf("inbound", "outbound"), cache.directionsOf("bus1", "Diverted"))
    }

    @Test
    fun `a failed lookup is asked again next time`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList()))
        cache.release(cache.claimUnknown(plain))

        assertEquals(listOf("bus1"), cache.claimUnknown(plain).map { it.id })
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
