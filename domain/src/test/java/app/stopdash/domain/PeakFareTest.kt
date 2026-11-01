package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakFareTest {
    private val london = ZoneId.of("Europe/London")

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(london).toInstant()

    // A zone 1-3 tube charge into zone 1, as TfL's Planner itemizes it (recorded 2026-10-28).
    private fun tube(tapIn: Instant, pence: Int = 330, level: TripFare.Level = TripFare.Level.OFF_PEAK, counterflow: Boolean = true) =
        TripFare.Part(pence = pence, level = level, peak = 390, offPeak = 330, counterflow = counterflow, tapIn = tapIn)

    private val bus = TripFare.Part(pence = 175)

    @Test
    fun `weekday mornings are peak from half past six to half past nine`() {
        // A Wednesday.
        assertFalse(PeakFare.peak(at("2026-11-04T06:29")))
        assertTrue(PeakFare.peak(at("2026-11-04T06:30")))
        assertTrue(PeakFare.peak(at("2026-11-04T09:26")))
        assertTrue(PeakFare.peak(at("2026-11-04T09:29:59")))
        // A boarding time worked out to a fraction of a second is still before 09:30.
        assertTrue(PeakFare.peak(at("2026-11-04T09:29:59.500")))
        assertFalse(PeakFare.peak(at("2026-11-04T09:30")))
        // Morning peak holds into zone 1 too.
        assertTrue(PeakFare.peak(at("2026-11-04T08:00"), counterflow = true))
    }

    @Test
    fun `weekday evenings are peak from four to seven, but not into zone 1 against the rush`() {
        assertFalse(PeakFare.peak(at("2026-11-04T15:59")))
        assertTrue(PeakFare.peak(at("2026-11-04T16:00")))
        assertTrue(PeakFare.peak(at("2026-11-04T18:59")))
        assertTrue(PeakFare.peak(at("2026-11-04T18:59:59.500")))
        assertFalse(PeakFare.peak(at("2026-11-04T19:00")))
        assertFalse(PeakFare.peak(at("2026-11-04T17:00"), counterflow = true))
    }

    @Test
    fun `weekends and bank holidays are off-peak all day`() {
        assertFalse(PeakFare.peak(at("2026-11-07T08:00")))
        assertFalse(PeakFare.peak(at("2026-11-08T08:00")))
        // Christmas Day 2026 falls on a Friday, so Boxing Day's holiday is Monday 28th.
        assertFalse(PeakFare.peak(at("2026-12-25T08:00")))
        assertFalse(PeakFare.peak(at("2026-12-28T08:00")))
        // A working day between Christmas and New Year is still peak.
        assertTrue(PeakFare.peak(at("2026-12-29T08:00")))
        // Good Friday and Easter Monday 2027.
        assertFalse(PeakFare.peak(at("2027-03-26T08:00")))
        assertFalse(PeakFare.peak(at("2027-03-29T08:00")))
    }

    @Test
    fun `holidays at a weekend move to the weekdays after`() {
        // 2022: Christmas on a Sunday, Boxing Day Monday, so Christmas's holiday is Tuesday 27th.
        assertTrue(PeakFare.bankHoliday(LocalDate.of(2022, 12, 26)))
        assertTrue(PeakFare.bankHoliday(LocalDate.of(2022, 12, 27)))
        assertFalse(PeakFare.bankHoliday(LocalDate.of(2022, 12, 28)))
        // 2021: Christmas on a Saturday, Boxing Day Sunday: Monday 27th and Tuesday 28th.
        assertTrue(PeakFare.bankHoliday(LocalDate.of(2021, 12, 27)))
        assertTrue(PeakFare.bankHoliday(LocalDate.of(2021, 12, 28)))
        // 2028: New Year's Day on a Saturday, kept on Monday 3rd.
        assertTrue(PeakFare.bankHoliday(LocalDate.of(2028, 1, 3)))
        // Early May, spring and summer bank holidays 2026.
        assertTrue(PeakFare.bankHoliday(LocalDate.of(2026, 5, 4)))
        assertTrue(PeakFare.bankHoliday(LocalDate.of(2026, 5, 25)))
        assertTrue(PeakFare.bankHoliday(LocalDate.of(2026, 8, 31)))
        assertFalse(PeakFare.bankHoliday(LocalDate.of(2026, 8, 24)))
    }

    @Test
    fun `a train the Planner charged off-peak at 09_26 is peak, as TfL charges it`() {
        val fare = TripFare(505, parts = listOf(bus, tube(at("2026-11-04T09:26"))))
        val charged = PeakFare.charged(fare)
        assertEquals(565, charged.pence)
        // A bus has no level, so the route has none to name.
        assertNull(charged.level)
        assertEquals(TripFare.Level.PEAK, charged.parts[1].level)
    }

    @Test
    fun `each charge is priced at when the rider boards it, not the Planner's time`() {
        val planned = at("2026-11-04T09:34")
        val fare = PeakFare.charged(TripFare(330, TripFare.Level.OFF_PEAK, parts = listOf(tube(planned))))
        assertEquals(330, fare.pence)
        // Boarded at 09:15 by a live train: peak.
        val boarded = PeakFare.charged(fare) { if (it == planned) at("2026-11-04T09:15") else null }
        assertEquals(390, boarded.pence)
        assertEquals(TripFare.Level.PEAK, boarded.level)
        // And back: one boarded after half past nine is off-peak again.
        val later = PeakFare.charged(boarded) { at("2026-11-04T09:41") }
        assertEquals(330, later.pence)
        assertEquals(TripFare.Level.OFF_PEAK, later.level)
    }

    @Test
    fun `a Hopper discount the Planner took off stays off`() {
        // Total below the sum of its parts: whatever the Planner took off is kept as it moves.
        val fare = TripFare(500, parts = listOf(bus, tube(at("2026-11-04T09:40"))))
        assertEquals(560, PeakFare.charged(fare) { at("2026-11-04T09:10") }.pence)
    }

    @Test
    fun `a fare with no charge priced both ways is the Planner's own`() {
        val fare = TripFare(175, parts = listOf(bus))
        assertSame(fare, PeakFare.charged(fare))
        val bare = TripFare(390, TripFare.Level.PEAK)
        assertSame(bare, PeakFare.charged(bare))
    }

    @Test
    fun `a charge with no boarding time keeps the Planner's touch-in`() {
        val fare = TripFare(330, parts = listOf(tube(at("2026-11-04T09:20"))))
        assertEquals(390, PeakFare.charged(fare) { null }.pence)
    }

    @Test
    fun `a route's estimate charges each part at its leg's boarding`() {
        val walkStart = at("2026-11-04T09:20")
        val tapIn = at("2026-11-04T09:34")
        val route = TripRoute(
            legs = listOf(
                TripLeg(mode = TripLeg.WALKING, lineId = "", lineName = "", fromId = "", fromName = "", toId = "940GZZLUSTD", toName = "Stratford", departure = walkStart, arrival = tapIn),
                TripLeg(mode = "tube", lineId = "central", lineName = "Central", fromId = "940GZZLUSTD", fromName = "Stratford", toId = "940GZZLUBNK", toName = "Bank", departure = tapIn, arrival = tapIn.plus(Duration.ofMinutes(16))),
            ),
            fare = TripFare(330, TripFare.Level.OFF_PEAK, parts = listOf(tube(tapIn))),
        )
        val legs = listOf(
            TripTiming.LegTiming(board = at("2026-11-04T09:01"), arrive = at("2026-11-04T09:14"), train = null, live = false),
            TripTiming.LegTiming(board = at("2026-11-04T09:16"), arrive = at("2026-11-04T09:32"), train = null, live = true),
        )
        val charged = PeakFare.charged(checkNotNull(route.fare), route, legs)
        assertEquals(390, charged.pence)
    }
}
