package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MaxWalkTest {
    @Test
    fun `reads back what was stored, and 30 minutes otherwise`() {
        MaxWalk.entries.forEach { assertEquals(it, MaxWalk.fromStored(it.name)) }
        assertEquals(MaxWalk.THIRTY, MaxWalk.fromStored(null))
        assertEquals(MaxWalk.THIRTY, MaxWalk.fromStored("NINETY"))
    }

    @Test
    fun `offers the limits the maintainer chose, shortest first`() {
        assertEquals(listOf(10, 15, 20, 30, 45, 60), MaxWalk.entries.map { it.minutes })
    }
}
