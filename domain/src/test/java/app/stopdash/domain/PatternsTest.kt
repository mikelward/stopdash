package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patterns built from a stop's name compile once ([Patterns]). Stock station names. */
class PatternsTest {
    @Test
    fun `a pattern asked for again is the one already compiled, per case setting`() {
        val first = Patterns.of("""\bbetween\s+Moorgate\s+and\s+Monument""", ignoreCase = true)
        assertSame(first, Patterns.of("""\bbetween\s+Moorgate\s+and\s+Monument""", ignoreCase = true))
        assertNotSame(first, Patterns.of("""\bbetween\s+Moorgate\s+and\s+Monument"""))
        assertTrue(first.containsMatchIn("Diverted BETWEEN Moorgate and Monument"))
    }

    @Test
    fun `reading the same alert again compiles nothing new`() {
        val route = listOf("Old Street", "Moorgate", "Bank", "Monument", "Tower Hill")
            .mapIndexed { i, name -> RouteStop("S$i", name) }
        val alert = "Buses are not serving stops between 'Moorgate' and 'Monument'; diverted via Bank."
        val first = AlertStops.affected(alert, route)
        val held = Patterns.size()
        assertEquals(first, AlertStops.affected(alert, route))
        assertEquals(held, Patterns.size())
    }

    @Test
    fun `it holds a bounded number, dropping the least recently used`() {
        val kept = Patterns.of("kept-pattern")
        repeat(5000) { i -> Patterns.of("filler-$i"); if (i % 100 == 0) Patterns.of("kept-pattern") }
        assertTrue(Patterns.size() <= 4096)
        assertSame(kept, Patterns.of("kept-pattern"))
    }
}
