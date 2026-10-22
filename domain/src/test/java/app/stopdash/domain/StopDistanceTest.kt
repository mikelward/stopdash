package app.stopdash.domain

import app.stopdash.domain.DistanceSystem.FEET
import app.stopdash.domain.DistanceSystem.METERS
import app.stopdash.domain.DistanceSystem.YARDS
import org.junit.Assert.assertEquals
import org.junit.Test

class StopDistanceTest {
    @Test
    fun `meters round to the nearest 10 below about 500 m`() {
        assertEquals("40 m", StopDistance.label(44.0))
        assertEquals("120 m", StopDistance.label(118.0))
        assertEquals("450 m", StopDistance.label(450.0))
        assertEquals("490 m", StopDistance.label(494.0))
    }

    @Test
    fun `a fix on top of the stop reads 10, not 0`() {
        // Rounding to the nearest 10 would give "0 m" for a fix at the stop; the floor keeps
        // it honest and readable, in either system.
        assertEquals("10 m", StopDistance.label(0.0))
        assertEquals("10 m", StopDistance.label(4.0))
        assertEquals("10 yd", StopDistance.label(0.0, YARDS))
        assertEquals("10 yd", StopDistance.label(-3.0, YARDS))
    }

    @Test
    fun `from about 500 m it reads as a fraction of a km`() {
        // 495 m would round to "500 m"; it reads in km instead, so the meters branch never shows it.
        assertEquals("0.5 km", StopDistance.label(495.0))
        assertEquals("0.6 km", StopDistance.label(620.0))
        assertEquals("1.0 km", StopDistance.label(995.0))
        assertEquals("1.2 km", StopDistance.label(1200.0))
        // The near-me outer radius is ~1609 m, the far end of what a header shows.
        assertEquals("1.6 km", StopDistance.label(1609.0))
    }

    @Test
    fun `yards read yards up close`() {
        assertEquals("50 yd", StopDistance.label(44.0, YARDS))
        assertEquals("130 yd", StopDistance.label(118.0, YARDS))
        assertEquals("540 yd", StopDistance.label(494.0, YARDS))
    }

    @Test
    fun `yards read a fraction of a mile from about 500 m`() {
        assertEquals("0.3 mi", StopDistance.label(495.0, YARDS))
        assertEquals("0.4 mi", StopDistance.label(620.0, YARDS))
        assertEquals("1.0 mi", StopDistance.label(1609.0, YARDS))
        assertEquals("1.9 mi", StopDistance.label(3000.0, YARDS))
    }

    @Test
    fun `feet read feet up to a tenth of a mile, then miles`() {
        assertEquals("10 ft", StopDistance.label(0.0, FEET))
        assertEquals("140 ft", StopDistance.label(44.0, FEET))
        assertEquals("390 ft", StopDistance.label(118.0, FEET))
        assertEquals("520 ft", StopDistance.label(159.0, FEET))
        assertEquals("0.1 mi", StopDistance.label(160.0, FEET))
        assertEquals("0.4 mi", StopDistance.label(620.0, FEET))
        assertEquals("1.0 mi", StopDistance.label(1609.0, FEET))
    }

    @Test
    fun `a reading carries the unit and its count, a long unit's in tenths`() {
        assertEquals(DistanceReading(DistanceUnit.METERS, 120), StopDistance.reading(118.0))
        assertEquals(DistanceReading(DistanceUnit.KILOMETERS, 6), StopDistance.reading(620.0))
        assertEquals(0.6, StopDistance.reading(620.0).amount, 0.0)
        assertEquals(DistanceReading(DistanceUnit.YARDS, 130), StopDistance.reading(118.0, YARDS))
        assertEquals(DistanceReading(DistanceUnit.FEET, 390), StopDistance.reading(118.0, FEET))
        assertEquals(DistanceReading(DistanceUnit.MILES, 4), StopDistance.reading(620.0, FEET))
        // The long unit's floor: a tenth, never none.
        assertEquals(DistanceReading(DistanceUnit.MILES, 1), StopDistance.reading(160.0, FEET))
        assertEquals(120.0, StopDistance.reading(118.0).amount, 0.0)
    }

    @Test
    fun `meters are the default system`() {
        assertEquals(StopDistance.label(620.0, METERS), StopDistance.label(620.0))
    }

    @Test
    fun `automatic follows the locale and the others pin a system`() {
        assertEquals(YARDS, DistanceUnits.AUTOMATIC.resolve(YARDS))
        assertEquals(METERS, DistanceUnits.AUTOMATIC.resolve(METERS))
        assertEquals(METERS, DistanceUnits.METERS.resolve(YARDS))
        assertEquals(YARDS, DistanceUnits.YARDS.resolve(METERS))
        assertEquals(FEET, DistanceUnits.FEET.resolve(YARDS))
        assertEquals(FEET, DistanceUnits.AUTOMATIC.resolve(FEET))
    }

    @Test
    fun `a stored choice reads back, and an unknown one is automatic`() {
        assertEquals(DistanceUnits.YARDS, DistanceUnits.fromStored("YARDS"))
        assertEquals(DistanceUnits.FEET, DistanceUnits.fromStored("FEET"))
        assertEquals(DistanceUnits.METERS, DistanceUnits.fromStored("METERS"))
        assertEquals(DistanceUnits.AUTOMATIC, DistanceUnits.fromStored(null))
        assertEquals(DistanceUnits.AUTOMATIC, DistanceUnits.fromStored("NAUTICAL"))
    }
}
