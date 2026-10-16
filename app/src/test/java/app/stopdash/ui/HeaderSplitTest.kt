package app.stopdash.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a stop heading's line shares its width between the place name and its qualifier ([headerSplit]). */
class HeaderSplitTest {
    @Test
    fun `both show whole when they fit`() {
        assertEquals(200 to 100, headerSplit(available = 600, name = 200, qualifier = 100))
    }

    @Test
    fun `a long name takes all a short qualifier leaves, not just half`() {
        // The even split clipped this name at 300 with 200 of the qualifier's half empty.
        assertEquals(500 to 100, headerSplit(available = 600, name = 1_000, qualifier = 100))
    }

    @Test
    fun `a long qualifier takes all a short name leaves`() {
        assertEquals(100 to 500, headerSplit(available = 600, name = 100, qualifier = 1_000))
    }

    @Test
    fun `two long ones each keep half, so neither crowds the other out`() {
        assertEquals(300 to 300, headerSplit(available = 600, name = 1_000, qualifier = 1_000))
        // An odd pixel goes to the name.
        assertEquals(301 to 300, headerSplit(available = 601, name = 1_000, qualifier = 1_000))
    }

    @Test
    fun `no qualifier leaves the name the whole line`() {
        assertEquals(600 to 0, headerSplit(available = 600, name = 1_000, qualifier = 0))
    }

    @Test
    fun `no room gives neither a negative width`() {
        assertEquals(0 to 0, headerSplit(available = -10, name = 100, qualifier = 100))
    }
}
