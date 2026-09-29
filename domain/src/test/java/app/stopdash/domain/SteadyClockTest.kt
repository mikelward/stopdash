package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteadyClockTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    // A device whose wall clock has been set back by [setBack] since the process started.
    private class Device(var setBack: Duration = Duration.ZERO, override val frame: SteadyClock.Frame? = SteadyClock.Frame("device/7", 1_000L)) : SteadyClock.Source {
        override fun offset(): Duration = setBack
    }

    @After
    fun tearDown() {
        SteadyClock.source = null
    }

    @Test
    fun `with no source, stamps and ages are the wall clock's`() {
        assertEquals(now, SteadyClock.stamp(now))
        assertEquals(Duration.ofMinutes(2), SteadyClock.age(now, now.plusSeconds(120)))
        assertEquals(Duration.ZERO, SteadyClock.shiftFrom(SteadyClock.Frame("device/7", 0)))
    }

    @Test
    fun `a fetch made before the clock was set back stays as old as it is`() {
        val device = Device()
        SteadyClock.source = device
        val fetched = SteadyClock.stamp(now)
        // Two minutes on, the clock is set back an hour: the wall clock reads an hour before the fetch.
        device.setBack = Duration.ofHours(1)
        val wallNow = now.plusSeconds(120).minus(Duration.ofHours(1))
        assertEquals(Duration.ofMinutes(2), SteadyClock.age(fetched, wallNow))
        assertFalse(Staleness.isStale(fetched, wallNow))
        // And once the wall clock has caught up with the old stamp, it's an hour old, not new.
        val caughtUp = now.plusSeconds(1)
        assertEquals(Duration.ofMinutes(60).plusSeconds(1), SteadyClock.age(fetched, caughtUp))
        assertTrue(Staleness.isStale(fetched, caughtUp))
        // What's shown or scheduled by the wall clock takes the stamp back to it.
        assertEquals(now.minus(Duration.ofHours(1)), SteadyClock.toWall(fetched))
    }

    @Test
    fun `a fetch made after the clock was set back is judged in the same frame`() {
        val device = Device(setBack = Duration.ofHours(1))
        SteadyClock.source = device
        val fetched = SteadyClock.stamp(now)
        assertEquals(Duration.ofMinutes(3), SteadyClock.age(fetched, now.plusSeconds(180)))
    }

    @Test
    fun `stored stamps move between processes of one boot, never across a reboot`() {
        // The later process read the wall clock an hour behind: it was set back in between.
        val setBack = -3_600_000L
        SteadyClock.source = Device(frame = SteadyClock.Frame("device/7", setBack))
        assertEquals(Duration.ofHours(-1), SteadyClock.shiftFrom(SteadyClock.Frame("device/7", 0L)))
        assertEquals(Duration.ZERO, SteadyClock.shiftFrom(SteadyClock.Frame("device/6", 0L)))
        assertEquals(Duration.ZERO, SteadyClock.shiftFrom(null))
        // Under a minute apart is the clocks' own drift, not a setting.
        assertEquals(Duration.ZERO, SteadyClock.shiftBetween(SteadyClock.Frame("device/7", 0L), SteadyClock.Frame("device/7", 59_000L)))
        assertEquals(Duration.ofMinutes(1), SteadyClock.shiftBetween(SteadyClock.Frame("device/7", 0L), SteadyClock.Frame("device/7", 60_000L)))
        // A process that can't tell its boot moves nothing.
        SteadyClock.source = Device(frame = null)
        assertEquals(Duration.ZERO, SteadyClock.shiftFrom(SteadyClock.Frame("device/7", 0L)))
    }

    @Test
    fun `the shared arrivals cache ages an entry by the steady clock`() {
        val device = Device()
        SteadyClock.source = device
        val cache = ArrivalsCache()
        cache.put("940GEXAMPLE1", emptyList(), SteadyClock.stamp(now))
        device.setBack = Duration.ofHours(1)
        // Thirty seconds on, the wall clock reads an hour earlier: still thirty seconds old.
        assertNotNull(cache.recent("940GEXAMPLE1", now.plusSeconds(30).minus(Duration.ofHours(1))))
        // Once the clock has caught up with the old stamp, it's an hour old, not fresh again.
        assertNull(cache.get("940GEXAMPLE1", now.plusSeconds(1)))
    }

    @Test
    fun `a line check made before the clock was set back stays as old as it is`() {
        val device = Device()
        SteadyClock.source = device
        val check = LineStatusCheck(LineStatus("victoria", 6, "Severe Delays"), SteadyClock.stamp(now))
        // Two minutes on, the clock is set back an hour: still two minutes old, and not from the future.
        device.setBack = Duration.ofHours(1)
        val wallNow = now.plusSeconds(120).minus(Duration.ofHours(1))
        assertTrue(check.isLive(wallNow))
        assertFalse(check.fromFuture(wallNow))
        // Its mark goes when it's five minutes old, scheduled by the wall clock as set now.
        val snapshot = DeparturesSnapshot(emptyList(), SteadyClock.stamp(wallNow), lineStatuses = mapOf("victoria" to check))
        assertEquals(wallNow.plus(Duration.ofMinutes(3)), snapshot.nextBoundary(wallNow))
        // Once the wall clock has caught up with its stamp, it's an hour old: withheld, not live again.
        assertFalse(check.isLive(now.plusSeconds(1)))
        // And a check made since, which reads as earlier by the wall clock, is still the newer one.
        val since = LineStatusCheck(LineStatus("victoria", 10, "Good Service"), SteadyClock.stamp(wallNow))
        assertEquals(since, LineStatusCheck.newest(mapOf("victoria" to check), mapOf("victoria" to since), now = wallNow).getValue("victoria"))
    }

    @Test
    fun `a stop's closure lookup is aged by the steady clock`() {
        val device = Device(setBack = Duration.ofHours(1))
        SteadyClock.source = device
        val cache = StopClosureCache()
        // Asked at a wall time, stamped in the steady frame: an hour on from the wall clock, which was set back.
        assertEquals(now.plus(Duration.ofHours(1)), cache.ask(now).at)
        assertEquals(Duration.ofMinutes(2), SteadyClock.age(cache.ask(now).at, now.plusSeconds(120)))
    }

    @Test
    fun `an ended dismissal is kept and weighed by the steady clock`() {
        val device = Device()
        SteadyClock.source = device
        val severe = LineStatus("victoria", 6, "Severe Delays")
        val alert = DismissedAlert.ofLineStatus(severe)
        // Checked, then the clock set back an hour, then the alert's end seen: the check came first.
        val checkedAt = SteadyClock.stamp(now)
        device.setBack = Duration.ofHours(1)
        val seen = now.plusSeconds(60).minus(Duration.ofHours(1))
        val kept = Dismissed.keepingEnded(setOf(alert), emptyMap(), emptySet(), seen)
        val endedAt = kept.ended.getValue(alert)
        val dismissals = Dismissals(emptySet(), kept.ended)
        assertTrue(dismissals.hide(LineStatusCheck(severe, checkedAt)))
        // One checked after the end was seen is a recurrence: shown.
        assertFalse(dismissals.hide(LineStatusCheck(severe, SteadyClock.stamp(seen.plusSeconds(30)))))
        // Kept for one staleness window by the steady clock: four minutes on, still; six, gone.
        assertEquals(kept.ended, Dismissed.keepingEnded(setOf(alert), kept.ended, emptySet(), seen.plusSeconds(240)).ended)
        assertTrue(Dismissed.keepingEnded(setOf(alert), kept.ended, emptySet(), seen.plusSeconds(360)).ended.isEmpty())
        assertEquals(now.plusSeconds(60), endedAt)
    }
}
