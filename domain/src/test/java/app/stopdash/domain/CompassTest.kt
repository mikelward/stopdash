package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassTest {
    @Test
    fun `bearing is degrees clockwise from north`() {
        assertEquals(0.0, Compass.bearingDegrees(51.50, -0.12, 51.51, -0.12), 0.01)
        assertEquals(90.0, Compass.bearingDegrees(51.50, -0.12, 51.50, -0.11), 0.1)
        assertEquals(180.0, Compass.bearingDegrees(51.51, -0.12, 51.50, -0.12), 0.01)
        assertEquals(270.0, Compass.bearingDegrees(51.50, -0.11, 51.50, -0.12), 0.1)
        // King's Cross St Pancras to Euston: west-southwest, about 247°.
        assertEquals(247.0, Compass.bearingDegrees(51.5308, -0.1238, 51.5282, -0.1337), 1.0)
    }

    @Test
    fun `each point covers 45 degrees centered on it`() {
        assertEquals(Compass.Point.N, Compass.point(0.0))
        assertEquals(Compass.Point.N, Compass.point(22.4))
        assertEquals(Compass.Point.NE, Compass.point(22.5))
        assertEquals(Compass.Point.NE, Compass.point(62.0))
        assertEquals(Compass.Point.E, Compass.point(90.0))
        assertEquals(Compass.Point.NW, Compass.point(337.4))
        assertEquals(Compass.Point.N, Compass.point(337.5))
        assertEquals(Compass.Point.N, Compass.point(359.9))
        assertEquals(Compass.Point.W, Compass.point(-90.0))
    }

    @Test
    fun `relative turns the shorter way round`() {
        assertEquals(32.0, Compass.relative(62.0, 30.0), 1e-9)
        assertEquals(-20.0, Compass.relative(350.0, 10.0), 1e-9)
        assertEquals(20.0, Compass.relative(10.0, 350.0), 1e-9)
        assertEquals(180.0, Compass.relative(180.0, 0.0), 1e-9)
    }

    @Test
    fun `the screen points by its top lying flat, by its back held upright`() {
        // Flat, its top to the north: 0.
        assertEquals(0.0, Compass.screenAzimuth(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)), 1e-6)
        // Flat, its top to the east: device y is world east.
        assertEquals(90.0, Compass.screenAzimuth(floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)), 1e-6)
        // Upright, facing north: its top points up, its back (device -z) north.
        assertEquals(0.0, Compass.screenAzimuth(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)), 1e-6)
        // Upright, facing west: its back points west.
        assertEquals(270.0, Compass.screenAzimuth(floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f)), 1e-6)
    }

    @Test
    fun `a reading is used only when its own error is small or unknown`() {
        assertTrue(Compass.headingUsable(null))
        assertTrue(Compass.headingUsable(-1f))
        assertTrue(Compass.headingUsable(Math.toRadians(10.0).toFloat()))
        assertFalse(Compass.headingUsable(Math.toRadians(45.0).toFloat()))
    }

    @Test
    fun `the filter steadies the heading and skips wobbles`() {
        val filter = HeadingFilter(share = 0.5, step = 2.0)
        assertEquals(10.0, filter.next(10.0)!!, 1e-9)
        // Half way to 11 is 10.5: under the step, not passed on.
        assertNull(filter.next(11.0))
        // Toward 30 from 10.5: 20.25, past the step.
        assertEquals(20.25, filter.next(30.0)!!, 1e-9)
        // Across north the short way: from 20.25 half way to 350 is 5.125, not round through 180.
        assertEquals(5.125, filter.next(350.0)!!, 1e-9)
        // Reset, the next reading shows at once.
        filter.reset()
        assertNotNull(filter.next(200.0))
    }
}
