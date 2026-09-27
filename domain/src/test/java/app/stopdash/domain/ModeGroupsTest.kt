package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeGroupsTest {
    @Test
    fun `the DLR rides with the Tube, and every heavy-rail mode is Train`() {
        assertEquals("tube", ModeGroups.of("dlr").key)
        assertEquals(setOf("train"), setOf("overground", "elizabeth-line", "national-rail").map { ModeGroups.of(it).key }.toSet())
        assertEquals("boat", ModeGroups.of("river-bus").key)
        // A mode no group names is a group of its own.
        assertEquals(setOf("cable-car"), ModeGroups.of("cable-car").modes)
    }

    @Test
    fun `hiding a group hides every mode in it, and showing it brings them all back`() {
        val train = ModeGroups.of("national-rail")
        val hidden = ModeGroups.withGroup(setOf("bus"), train, hide = true)
        assertEquals(setOf("bus", "overground", "elizabeth-line", "national-rail"), hidden)
        assertTrue(ModeGroups.isHidden(train, hidden))
        assertEquals(setOf("bus"), ModeGroups.withGroup(hidden, train, hide = false))
    }

    @Test
    fun `the banner names each hidden group once, in menu order`() {
        val hidden = ModeGroups.withGroup(setOf("bus"), ModeGroups.of("dlr"), hide = true)
        assertEquals(listOf("tube", "bus"), ModeGroups.hiddenGroups(hidden).map { it.key })
        assertFalse(ModeGroups.isHidden(ModeGroups.of("tram"), hidden))
    }

    @Test
    fun `a hidden line is a group of its own, kept as it is, and never named as a mode group`() {
        val key = HiddenModes.lineKey("northern", "Northern line")
        val line = ModeGroups.of(key)
        assertEquals(setOf(key), line.modes)
        val hidden = ModeGroups.withGroup(setOf("bus"), line, hide = true)
        assertEquals(setOf("bus", key), hidden)
        assertEquals(listOf("bus"), ModeGroups.hiddenGroups(hidden).map { it.key })
        assertEquals(setOf("bus"), ModeGroups.withGroup(hidden, line, hide = false))
    }
}
