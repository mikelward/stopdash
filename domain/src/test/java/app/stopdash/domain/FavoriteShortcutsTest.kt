package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which favorites the near-me list offers, on synthetic coordinates only (SPEC *Privacy*). */
class FavoriteShortcutsTest {
    // A synthetic spot in central London and places placed due north of it by a known distance
    // (1° of latitude ≈ 111,195 m on the haversine sphere).
    private val here = Coordinates(51.5, -0.12)
    private fun north(meters: Double) = Coordinates(here.latitude + meters / 111_195.0, here.longitude)
    private fun place(id: String, meters: Double) = FavoritePlace(id, FavoriteKind.CUSTOM, id, north(meters))
    private fun keys(vararg places: FavoritePlace) = places.mapTo(LinkedHashSet()) { FavoriteShortcuts.memoryKey(it) }

    @Test
    fun `a place within the radius is hidden and one beyond it is kept`() {
        val near = place("near", 150.0)
        val far = place("far", 2_000.0)
        val hidden = FavoriteShortcuts.hiddenIds(listOf(near, far), here, precise = true)
        assertEquals(keys(near), hidden)
        assertEquals(listOf(far), FavoriteShortcuts.shown(listOf(near, far), hidden))
    }

    @Test
    fun `the radius edge is inclusive`() {
        val edge = place("edge", 199.0)
        assertEquals(keys(edge), FavoriteShortcuts.hiddenIds(listOf(edge), here, precise = true))
        assertTrue(FavoriteShortcuts.hiddenIds(listOf(place("out", 260.0)), here, precise = true).isEmpty())
    }

    @Test
    fun `an imprecise fix or no fix shows every place`() {
        val home = place("home", 10.0)
        assertEquals(listOf(home), FavoriteShortcuts.shown(listOf(home), keys(home), precise = false))
        // Nothing remembered, nothing hidden: an imprecise fix never adds to the memory.
        assertTrue(FavoriteShortcuts.hiddenIds(listOf(home), here, precise = false).isEmpty())
        assertTrue(FavoriteShortcuts.hiddenIds(listOf(home), null, precise = true).isEmpty())
    }

    @Test
    fun `an imprecise fix keeps the memory for the next precise one`() {
        val band = place("band", 225.0)
        // Hidden at the door, then an approximate fix: the chip shows meanwhile, but the memory holds…
        val kept = FavoriteShortcuts.hiddenIds(listOf(band), here, precise = false, hiddenBefore = keys(band))
        assertEquals(keys(band), kept)
        // …so a precise fix back in the 200–250 m band still finds it hidden (Codex).
        assertEquals(keys(band), FavoriteShortcuts.hiddenIds(listOf(band), here, precise = true, hiddenBefore = kept))
    }

    @Test
    fun `a hidden favorite moved to a new spot starts afresh`() {
        val before = place("gym", 50.0)
        val hidden = FavoriteShortcuts.hiddenIds(listOf(before), here, precise = true)
        assertEquals(keys(before), hidden)
        // Edited (same id) to a spot in the 200–250 m band: nothing established that the rider is at
        // the new spot, so it shows (Codex).
        val moved = before.copy(coordinate = north(225.0))
        assertTrue(FavoriteShortcuts.hiddenIds(listOf(moved), here, precise = true, hiddenBefore = hidden).isEmpty())
        assertEquals(listOf(moved), FavoriteShortcuts.shown(listOf(moved), hidden))
    }

    @Test
    fun `a deleted favorite drops out of the memory`() {
        val gone = place("gone", 10.0)
        assertTrue(FavoriteShortcuts.hiddenIds(emptyList(), here, precise = false, hiddenBefore = keys(gone)).isEmpty())
    }

    @Test
    fun `inside the hysteresis band a place keeps its previous state`() {
        val band = place("band", 225.0)
        // Was hidden (the rider was at it and is drifting away): stays hidden.
        assertEquals(keys(band), FavoriteShortcuts.hiddenIds(listOf(band), here, precise = true, hiddenBefore = keys(band)))
        // Was shown (the rider is approaching): stays shown until within the hide radius.
        assertTrue(FavoriteShortcuts.hiddenIds(listOf(band), here, precise = true, hiddenBefore = emptySet()).isEmpty())
    }

    @Test
    fun `a hidden place comes back once past the show radius`() {
        val gone = place("gone", 300.0)
        assertTrue(FavoriteShortcuts.hiddenIds(listOf(gone), here, precise = true, hiddenBefore = keys(gone)).isEmpty())
    }

    @Test
    fun `only a fix with a known, close accuracy counts as accurate`() {
        assertTrue(FavoriteShortcuts.isAccurate(10f))
        assertTrue(FavoriteShortcuts.isAccurate(100f))
        assertTrue(!FavoriteShortcuts.isAccurate(1_500f))
        assertTrue(!FavoriteShortcuts.isAccurate(null))
    }

    @Test
    fun `shown keeps the saved order`() {
        val a = place("a", 1_000.0)
        val b = place("b", 50.0)
        val c = place("c", 3_000.0)
        assertEquals(listOf(a, c), FavoriteShortcuts.shown(listOf(a, b, c), keys(b)))
    }
}
