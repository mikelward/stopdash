package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CallingPortionTest {
    @Test
    fun `a portion's stops are hashed once, where it's built, never at each lookup`() {
        // A train is a map key on the trip page's render path, and its hash takes in its portions (Codex, #650).
        var walks = 0
        val stops = object : AbstractSet<String>() {
            private val inner = setOf("910GWOKING", "910GGUILDFD")
            override val size get() = inner.size
            override fun iterator() = inner.iterator()
            override fun hashCode(): Int = inner.hashCode().also { walks++ }
        }
        val portion = CallingPortion(stops, complete = true)
        val built = walks
        repeat(3) { portion.hashCode() }
        assertEquals(built, walks)
        // Equal portions still hash and compare alike; a different one doesn't.
        assertEquals(CallingPortion(setOf("910GWOKING", "910GGUILDFD"), true), portion)
        assertEquals(CallingPortion(setOf("910GWOKING", "910GGUILDFD"), true).hashCode(), portion.hashCode())
        assertNotEquals(CallingPortion(setOf("910GWOKING"), true), portion)
        assertNotEquals(CallingPortion(setOf("910GWOKING", "910GGUILDFD"), false), portion)
    }
}
