package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreciseFixMemoryTest {
    // Obviously-synthetic coordinates around the origin, never a real position (SPEC Privacy).
    private val origin = Coordinates(0.0, 0.0)

    private fun north(meters: Double) = Coordinates(meters / 111_195.0, 0.0)

    private val minute = 60_000L

    @Test
    fun `nothing remembered, the coarse fix stands`() {
        assertNull(PreciseFixMemory().instead(origin, 400f, 0))
    }

    @Test
    fun `a recent precise fix inside the coarse circle is used instead`() {
        val memory = PreciseFixMemory()
        memory.remember(north(250.0), atElapsedMillis = 0)
        val recalled = memory.instead(origin, 400f, nowElapsedMillis = 3 * minute)
        assertEquals(north(250.0), recalled?.coordinates)
        assertEquals(3 * minute, recalled?.ageMillis)
    }

    @Test
    fun `a recall no older than the fast path's cache stands as current`() {
        val memory = PreciseFixMemory()
        memory.remember(north(10.0), atElapsedMillis = 0)
        // Exactly as old as a cached fix the fast path would still use: stands.
        assertTrue(memory.instead(origin, 150f, FixSelection.FRESH_ENOUGH_MILLIS)!!.standsAsCurrent)
        // Any older and it is only a guess, to be shown as approximate and checked.
        assertFalse(memory.instead(origin, 150f, FixSelection.FRESH_ENOUGH_MILLIS + 1)!!.standsAsCurrent)
    }

    @Test
    fun `a precise fix outside the coarse circle means the rider moved`() {
        val memory = PreciseFixMemory()
        memory.remember(north(600.0), atElapsedMillis = 0)
        assertNull(memory.instead(origin, 400f, nowElapsedMillis = minute))
    }

    @Test
    fun `a precise fix is recalled with its age, whatever it's set against`() {
        val memory = PreciseFixMemory()
        assertNull(memory.recall(minute))
        memory.remember(north(50.0), atElapsedMillis = 0, accuracyMeters = 12f)
        val recalled = checkNotNull(memory.recall(minute))
        assertEquals(north(50.0), recalled.coordinates)
        assertEquals(minute, recalled.ageMillis)
        // Past its ten minutes, forgotten; and never from a clock gone back.
        assertNull(memory.recall(PreciseFixMemory.TTL_MILLIS + 1))
        memory.remember(north(50.0), atElapsedMillis = minute)
        assertNull(memory.recall(0))
    }

    @Test
    fun `a precise fix older than ten minutes is forgotten`() {
        val memory = PreciseFixMemory()
        memory.remember(north(50.0), atElapsedMillis = 0)
        assertEquals(north(50.0), memory.instead(origin, 400f, PreciseFixMemory.TTL_MILLIS)?.coordinates)
        assertNull(memory.instead(origin, 400f, PreciseFixMemory.TTL_MILLIS + 1))
    }

    @Test
    fun `an expired precise fix is deleted, not just ignored`() {
        val memory = PreciseFixMemory()
        memory.remember(north(50.0), atElapsedMillis = 0)
        assertNull(memory.instead(origin, 400f, PreciseFixMemory.TTL_MILLIS + 1))
        // Asked again at a time when it would have been fresh: it's gone.
        assertNull(memory.instead(origin, 400f, 0))
    }

    @Test
    fun `expire deletes only a fix past the TTL`() {
        val memory = PreciseFixMemory()
        memory.remember(north(50.0), atElapsedMillis = 0)
        memory.expire(PreciseFixMemory.TTL_MILLIS)
        assertEquals(north(50.0), memory.instead(origin, 400f, 0)?.coordinates)
        memory.expire(PreciseFixMemory.TTL_MILLIS + 1)
        assertNull(memory.instead(origin, 400f, 0))
    }

    @Test
    fun `a recall carries the remembered fix's provider and accuracy for the log`() {
        val memory = PreciseFixMemory()
        memory.remember(north(50.0), atElapsedMillis = 0, provider = "gps", accuracyMeters = 8f)
        val recalled = memory.instead(origin, 400f, 1_000)!!
        assertEquals("gps", recalled.provider)
        assertEquals(8f, recalled.accuracyMeters)
        assertEquals(
            "location fix: remembered precise from gps, accuracy 8 m, 1 s old",
            FixDiagnostics.describe(FixDiagnostics.Source.REMEMBERED, recalled.provider, recalled.accuracyMeters, recalled.ageMillis),
        )
    }

    @Test
    fun `the log line says how far apart the fixes are, used or not`() {
        val memory = PreciseFixMemory()
        memory.remember(north(250.0), atElapsedMillis = 0, provider = "gps", accuracyMeters = 8f)
        val inside = memory.consider(origin, 400f, 60_000)!!
        assertEquals(true, inside.used)
        assertEquals(
            "location fix: remembered precise from gps, accuracy 8 m, 60 s old, " +
                "250 m from the network fix, inside its 400 m accuracy, used",
            FixDiagnostics.describeRemembered(inside, 400f),
        )
        val outside = memory.consider(origin, 100f, 60_000)!!
        assertEquals(false, outside.used)
        assertNull(memory.instead(origin, 100f, 60_000))
        assertEquals(
            "location fix: remembered precise from gps, accuracy 8 m, 60 s old, " +
                "250 m from the network fix, outside its 100 m accuracy, not used",
            FixDiagnostics.describeRemembered(outside, 100f),
        )
    }

    @Test
    fun `a coarse fix with no accuracy has no circle to be inside`() {
        val memory = PreciseFixMemory()
        memory.remember(origin, atElapsedMillis = 0)
        assertNull(memory.instead(origin, null, minute))
        assertNull(memory.instead(origin, 0f, minute))
    }

    @Test
    fun `the newest precise fix is the one remembered`() {
        val memory = PreciseFixMemory()
        memory.remember(north(100.0), atElapsedMillis = 2 * minute)
        // An older one arriving late doesn't replace it.
        memory.remember(north(300.0), atElapsedMillis = minute)
        assertEquals(north(100.0), memory.instead(origin, 400f, 3 * minute)?.coordinates)
    }
}
