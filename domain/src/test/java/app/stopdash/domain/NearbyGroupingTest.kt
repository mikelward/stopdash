package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyGroupingTest {
    @Test
    fun `reads back what was stored, and grouping by platform otherwise`() {
        NearbyGrouping.entries.forEach { assertEquals(it, NearbyGrouping.fromStored(it.name)) }
        assertEquals(NearbyGrouping.PLATFORM, NearbyGrouping.fromStored(null))
        assertEquals(NearbyGrouping.PLATFORM, NearbyGrouping.fromStored("HUB"))
    }
}
