package app.stopdash.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FarRevealTest {
    private val saver = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test
    fun `opening rides keeps the others and reads back the saved set itself`() {
        val opened = mutableStateOf(FarOpened.None)
        val state = FarRevealState(opened, "set")
        state.reveal("a")
        state.reveal("b")
        assertEquals(setOf("a", "b"), state.revealed)
        assertSame("a read does no work", opened.value.keys, state.revealed)
        val before = state.revealed
        state.reveal("a")
        assertSame("opening one already open changes nothing", before, state.revealed)
    }

    @Test
    fun `rides opened for another set don't show`() {
        val opened = mutableStateOf(FarOpened.None)
        FarRevealState(opened, "here").reveal("a")
        val there = FarRevealState(opened, "there")
        assertTrue(there.revealed.isEmpty())
        there.reveal("b")
        assertEquals(setOf("b"), there.revealed)
    }

    @Test
    fun `a restore brings back the rides with the set they were opened for`() {
        val opened = mutableStateOf(FarOpened.None)
        FarRevealState(opened, "set").apply { reveal("a"); reveal("b") }
        val saved = with(FarOpened.Saver) { saver.save(opened.value) }!!
        val restored = FarOpened.Saver.restore(saved)!!
        assertEquals("set", restored.forKey)
        assertEquals(setOf("a", "b"), restored.keys)
        assertEquals(setOf("a", "b"), FarRevealState(mutableStateOf(restored), "set").revealed)
        assertTrue(FarRevealState(mutableStateOf(restored), "elsewhere").revealed.isEmpty())
        assertSame(FarOpened.None, FarOpened.Saver.restore(with(FarOpened.Saver) { saver.save(FarOpened.None) }!!))
    }

    @Test
    fun `a run stays while rides are added and starts again after another set`() {
        val opened = mutableStateOf(FarOpened.None)
        val here = FarRevealState(opened, "here")
        here.reveal("a")
        val run = here.run
        here.reveal("b")
        assertSame("adding a ride keeps the run", run, here.run)
        val there = FarRevealState(opened, "there")
        there.reveal("c")
        assertTrue("another set starts a new run", run !== there.run)
        assertTrue("a set not opened for this one has no run of its own", here.run !== there.run)
    }
}
