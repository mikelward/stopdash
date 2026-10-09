package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A started trip as a progress bar and a status-bar chip, on synthetic stops A–E. */
class TripMeterTest {
    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    // 10 min riding A to C through B, a 5 min walk, 8 min riding D to E.
    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val walk = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
    private val second = TripLeg("tube", "blue", "Blue", "D", "D", "E", "E", at(22), at(30), path = listOf("E"))
    private val trip = ActiveTrip(TripRoute(listOf(ride, walk, second)), "E", startedAt = t0)

    private fun riding(left: Int?, offAt: Instant? = null) = TripProgress.Riding(ride, "B", left, offAt, getOffSoon = false)

    @Test
    fun `one segment per leg, as long as its planned time`() {
        val meter = TripMeter.meter(trip, TripProgress.Waiting(ride, at(6)), at(4))
        assertNotNull(meter)
        assertEquals(listOf(600, 300, 480), meter!!.segments.map { it.length })
        assertEquals(listOf(0, 1, 2), meter.segments.map { it.legIndex })
        assertEquals(1380, meter.total)
    }

    @Test
    fun `a leg too short to see still gets a stretch`() {
        val hop = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(15))
        val meter = TripMeter.meter(ActiveTrip(TripRoute(listOf(ride, hop)), "D", startedAt = t0), null, at(4))!!
        assertEquals(60, meter.segments[1].length)
    }

    @Test
    fun `waiting for the first train is at the start, arriving at the end`() {
        assertEquals(0, TripMeter.meter(trip, TripProgress.Waiting(ride, at(6)), at(4))!!.progress)
        assertEquals(1380, TripMeter.meter(trip, TripProgress.Arrived, at(31))!!.progress)
    }

    @Test
    fun `a ride moves on by the stops counted, not the clock`() {
        // Two stops on the ride, one to go: halfway along it.
        assertEquals(300, TripMeter.meter(trip, riding(1), at(5))!!.progress)
        // Still at the first stop: nothing ridden yet, however late it is.
        assertEquals(0, TripMeter.meter(trip, riding(2), at(14))!!.progress)
    }

    @Test
    fun `a ride on another of its lines is counted along that line's stops`() {
        // The train followed is on a line that calls only at C: one stop to go is the whole ride, not half.
        val express = ride.copy(lineId = "pink", lineName = "Pink", path = listOf("C"))
        val onExpress = trip.copy(vehicleLeg = express, vehicleId = "EXAMPLE")
        assertEquals(0, TripMeter.meter(onExpress, riding(1), at(5))!!.progress)
        assertEquals(600, TripMeter.meter(onExpress, riding(0), at(14))!!.progress)
        assertEquals(600, TripMeter.meter(onExpress, riding(1), at(5))!!.segments[0].length)
    }

    @Test
    fun `a ride with no stops counted moves on by when it gets in`() {
        assertEquals(300, TripMeter.meter(trip, riding(null, offAt = at(10)), at(5))!!.progress)
        // Nothing to go by: held at the ride's start rather than guessed.
        assertEquals(0, TripMeter.meter(trip, riding(null), at(10))!!.progress)
    }

    @Test
    fun `a walk moves on by the clock, the rides before it done`() {
        val walking = TripProgress.Walking(walk, until = at(20))
        assertEquals(600, TripMeter.meter(trip.copy(legIndex = 1), walking, at(15))!!.progress)
        assertEquals(840, TripMeter.meter(trip.copy(legIndex = 1), walking, at(19))!!.progress)
        // Run past its time: the walk is full, never past it.
        assertEquals(900, TripMeter.meter(trip.copy(legIndex = 1), walking, at(25))!!.progress)
    }

    @Test
    fun `a change is at the start of the ride it's onto`() {
        // The trip is still on the walk's leg; the step names the ride after it.
        val changing = TripProgress.Changing(second, until = at(22))
        assertEquals(900, TripMeter.meter(trip.copy(legIndex = 1), changing, at(20))!!.progress)
    }

    @Test
    fun `a train that can't be placed draws no bar, rather than a guess`() {
        assertNull(TripMeter.meter(trip, TripProgress.Lost(ride), at(10)))
    }

    @Test
    fun `a long route is cut to ten segments, the rides kept, the total and the place unchanged`() {
        // Eleven legs: six 10-minute rides with five 1-minute walks between.
        val legs = (0 until 11).map { i ->
            if (i % 2 == 0) TripLeg("tube", "line$i", "Line $i", "S$i", "S$i", "S${i + 1}", "S${i + 1}", at(i * 10L), at(i * 10L + 10), path = listOf("S${i + 1}"))
            else TripLeg(TripLeg.WALKING, "", "", "S$i", "S$i", "S${i + 1}", "S${i + 1}", at(i * 10L), at(i * 10L + 1))
        }
        val long = ActiveTrip(TripRoute(legs), "S11", startedAt = t0, legIndex = 4)
        val meter = TripMeter.meter(long, TripProgress.Waiting(legs[4], null), t0)!!
        assertEquals(TripMeter.MAX_SEGMENTS, meter.segments.size)
        assertEquals(6 * 600 + 5 * 60, meter.total)
        assertEquals(2 * 600 + 2 * 60, meter.progress)
        // A walk folded into a ride: every ride still has its own segment.
        assertEquals((0 until 11 step 2).toSet(), meter.segments.map { it.legIndex }.filter { it % 2 == 0 }.toSet())
    }

    @Test
    fun `ten segments or fewer are left alone`() {
        val segments = (0 until 10).map { TripMeter.Segment(it, 60, ride) }
        assertEquals(segments, TripMeter.coalesce(segments))
    }

    @Test
    fun `no legs, no bar`() {
        assertNull(TripMeter.meter(ActiveTrip(TripRoute(emptyList()), "E", startedAt = t0), null, t0))
    }

    @Test
    fun `the chip counts stops on a ride and minutes otherwise`() {
        assertEquals(TripMeter.Chip.Stops(2), TripMeter.chip(riding(2), at(5)))
        // Stops not counted: minutes to getting off, where predicted.
        assertEquals(TripMeter.Chip.Minutes(5), TripMeter.chip(riding(null, offAt = at(10)), at(5)))
        assertNull(TripMeter.chip(riding(null), at(5)))
        assertEquals(TripMeter.Chip.Minutes(2), TripMeter.chip(TripProgress.Waiting(ride, at(6)), at(4)))
        assertNull(TripMeter.chip(TripProgress.Waiting(ride, null), at(4)))
        assertEquals(TripMeter.Chip.Minutes(3), TripMeter.chip(TripProgress.Walking(walk, at(20)), at(17)))
        assertEquals(TripMeter.Chip.Minutes(2), TripMeter.chip(TripProgress.Changing(second, at(22)), at(20)))
        // A part minute left on a walk or a change counts, as the step's own line rounds it.
        assertEquals(TripMeter.Chip.Minutes(4), TripMeter.chip(TripProgress.Walking(walk, at(20)), at(16).plusSeconds(30)))
        assertEquals(TripMeter.Chip.Minutes(2), TripMeter.chip(TripProgress.Changing(second, at(22)), at(20).plusSeconds(30)))
    }

    @Test
    fun `the chip is empty where there's nothing to count down`() {
        assertNull(TripMeter.chip(TripProgress.Walking(walk, at(20)), at(21)))
        assertNull(TripMeter.chip(TripProgress.Lost(ride), at(5)))
        assertNull(TripMeter.chip(TripProgress.Arrived, at(31)))
        assertNull(TripMeter.chip(null, at(5)))
    }
}
