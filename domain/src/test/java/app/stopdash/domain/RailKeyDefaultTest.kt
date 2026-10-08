package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RailKeyDefaultTest {
    private val rail = RailKeyDefault.GROUP.modes

    @Test
    fun `with no key and no choice National Rail is hidden, with a key or a choice it isn't`() {
        assertTrue(RailKeyDefault.applies(keySet = false, chosen = false, stored = emptySet()))
        assertFalse(RailKeyDefault.applies(keySet = true, chosen = false, stored = emptySet()))
        assertFalse(RailKeyDefault.applies(keySet = false, chosen = true, stored = emptySet()))
        assertEquals(setOf("bus") + rail, RailKeyDefault.effective(setOf("bus"), applies = true))
        assertEquals(setOf("bus"), RailKeyDefault.effective(setOf("bus"), applies = false))
    }

    @Test
    fun `a rider who hid National Rail themselves keeps it hidden once a key is added`() {
        assertFalse(RailKeyDefault.applies(keySet = false, chosen = false, stored = rail))
        assertEquals(rail, RailKeyDefault.effective(rail, applies = false))
    }

    @Test
    fun `hiding another group under the default doesn't store a hide of National Rail`() {
        val before = RailKeyDefault.effective(emptySet(), applies = true)
        val write = RailKeyDefault.write(before, before + "bus", applies = true)
        assertEquals(setOf("bus"), write.stored)
        assertFalse(write.choseRail)
    }

    @Test
    fun `showing National Rail anyway is the rider's choice, and so is hiding it`() {
        val before = RailKeyDefault.effective(setOf("bus"), applies = true)
        val shown = RailKeyDefault.write(before, ModeGroups.withGroup(before, RailKeyDefault.GROUP, hide = false), applies = true)
        assertEquals(setOf("bus"), shown.stored)
        assertTrue(shown.choseRail)

        val hidden = RailKeyDefault.write(setOf("bus"), setOf("bus") + rail, applies = false)
        assertEquals(setOf("bus") + rail, hidden.stored)
        assertTrue(hidden.choseRail)
    }

    @Test
    fun `without the default a change is stored as given`() {
        val write = RailKeyDefault.write(emptySet(), setOf("tram"), applies = false)
        assertEquals(setOf("tram"), write.stored)
        assertFalse(write.choseRail)
    }

    @Test
    fun `hiding National Rail under the default is the rider's choice, so a key added later keeps it hidden`() {
        val before = RailKeyDefault.effective(emptySet(), applies = true)
        val write = RailKeyDefault.write(before, before, applies = true, railTargeted = true)
        assertEquals(rail, write.stored)
        assertTrue(write.choseRail)
        assertFalse(RailKeyDefault.applies(keySet = true, chosen = true, stored = write.stored))
        assertEquals(rail, RailKeyDefault.effective(write.stored, applies = false))
    }

    @Test
    fun `showing all again after a National Rail hide stored before the default is the rider's choice`() {
        // An older file: National Rail hidden, no choice recorded, no key.
        assertFalse(RailKeyDefault.applies(keySet = false, chosen = false, stored = rail))
        val write = RailKeyDefault.write(rail, emptySet(), applies = false)
        assertEquals(emptySet<String>(), write.stored)
        assertTrue(write.choseRail)
    }
}
