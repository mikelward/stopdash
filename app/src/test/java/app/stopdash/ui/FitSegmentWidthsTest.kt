package app.stopdash.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FitSegmentWidthsTest {
    @Test
    fun room_a_short_code_leaves_goes_to_the_longer_one() {
        // A 20px and a 40px code in 52px: the short one keeps 20, the long one gets the other 32.
        assertEquals(listOf(20, 32), fitSegmentWidths(listOf(20, 40), 52))
        assertEquals(listOf(32, 20), fitSegmentWidths(listOf(40, 20), 52))
    }

    @Test
    fun codes_all_too_wide_share_the_room_alike() {
        assertEquals(listOf(20, 20, 20), fitSegmentWidths(listOf(30, 40, 50), 61))
    }

    @Test
    fun never_more_than_the_room() {
        val widths = fitSegmentWidths(listOf(7, 33, 33, 33), 100)
        assertEquals(7, widths[0])
        assertEquals(true, widths.sum() <= 100)
        assertEquals(listOf(31, 31, 31), widths.drop(1))
    }

    @Test
    fun no_room_is_no_width() {
        assertEquals(listOf(0, 0), fitSegmentWidths(listOf(10, 20), -5))
    }
}
