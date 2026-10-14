package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RideGroupingTest {
    @Test
    fun `reads back what was stored, and grouping by ride otherwise`() {
        RideGrouping.entries.forEach { assertEquals(it, RideGrouping.fromStored(it.name)) }
        assertEquals(RideGrouping.RIDE, RideGrouping.fromStored(null))
        assertEquals(RideGrouping.RIDE, RideGrouping.fromStored("LINE"))
    }
}
