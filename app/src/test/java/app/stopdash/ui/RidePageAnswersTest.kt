package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.stopdash.domain.LegTimetableSource
import app.stopdash.domain.LegTimetables
import app.stopdash.domain.StopTimetable
import app.stopdash.domain.TripLeg
import java.time.DayOfWeek
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A favorite ride's page shows only what was worked out for the ride it's on ([rememberPlacedRide],
 * [rememberTimetabledTrains]): every frame composed is recorded, so another ride's answer shown for even the one frame
 * before the next is worked out fails here. Synthetic stops, on a held worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class RidePageAnswersTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // 05:30 BST on Tuesday 2026-10-20.
    private val now = Instant.parse("2026-10-20T04:30:00Z")

    private val rideA = TripLeg("tube", "victoria", "Victoria", "A", "A", "B", "B", Instant.EPOCH, Instant.EPOCH)
    private val rideC = rideA.copy(fromId = "C", fromName = "C")

    private fun settle(scheduler: TestCoroutineScheduler) {
        repeat(5) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun another_rides_timetabled_trains_never_show_on_this_one_even_for_a_frame() {
        val scheduler = TestCoroutineScheduler()
        // A's first train 05:41, C's 05:50.
        val timetables = LegTimetables(
            object : LegTimetableSource {
                override suspend fun legTimetable(lineId: String, fromId: String, toId: String) = StopTimetable(
                    listOf(StopTimetable.DaySchedule(setOf(DayOfWeek.TUESDAY), listOf(if (fromId == "A") 5 * 60 + 41 else 5 * 60 + 50))),
                )
            },
            clock = { now },
        )
        var ride by mutableStateOf(rideA)
        val shown = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalLegTimetables provides timetables) {
                val on = ride
                val trains = rememberTimetabledTrains(on, now)
                SideEffect { shown += on.fromId to trains.text }
            }
        }
        settle(scheduler)
        assertEquals("A" to "05:41", shown.last())

        // Moved on to C: none until C's are worked out, never A's.
        composeRule.runOnIdle { ride = rideC }
        composeRule.waitForIdle()
        assertTrue(shown.filter { it.first == "C" }.all { it.second.isEmpty() })
        settle(scheduler)
        assertEquals("C" to "05:50", shown.last())
        assertTrue(shown.none { it == ("C" to "05:41") })
    }

    @Test
    fun a_rides_trains_from_before_it_was_ineligible_never_come_back_when_it_is_again() {
        val scheduler = TestCoroutineScheduler()
        // Trains at 05:41 and 05:50.
        val timetables = LegTimetables(
            object : LegTimetableSource {
                override suspend fun legTimetable(lineId: String, fromId: String, toId: String) = StopTimetable(
                    listOf(StopTimetable.DaySchedule(setOf(DayOfWeek.TUESDAY), listOf(5 * 60 + 41, 5 * 60 + 50))),
                )
            },
            clock = { now },
        )
        var ride by mutableStateOf<TripLeg?>(rideA)
        var at by mutableStateOf(now)
        val shown = mutableListOf<Pair<TripLeg?, String>>()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalLegTimetables provides timetables) {
                val on = ride
                val trains = rememberTimetabledTrains(on, at)
                SideEffect { shown += on to trains.text }
            }
        }
        settle(scheduler)
        assertEquals(rideA to "05:41 · 05:50", shown.last())

        // Not eligible (its checks down, say) past the 05:41, then eligible again: never the 05:41 again.
        composeRule.runOnIdle { ride = null }
        settle(scheduler)
        composeRule.runOnIdle { at = now.plusSeconds(15 * 60) }
        settle(scheduler)
        val back = shown.size
        composeRule.runOnIdle { ride = rideA }
        composeRule.waitForIdle()
        assertTrue(shown.drop(back).none { it.second.contains("05:41") })
        settle(scheduler)
        assertEquals(rideA to "05:50", shown.last())
        assertTrue(shown.drop(back).none { it.second.contains("05:41") })
    }

    @Test
    fun a_rides_trains_worked_out_before_its_planned_work_never_show_once_it_is() {
        val scheduler = TestCoroutineScheduler()
        val timetables = LegTimetables(
            object : LegTimetableSource {
                override suspend fun legTimetable(lineId: String, fromId: String, toId: String) = StopTimetable(
                    listOf(StopTimetable.DaySchedule(setOf(DayOfWeek.TUESDAY), listOf(5 * 60 + 41))),
                )
            },
            clock = { now },
        )
        var planned by mutableStateOf(emptyList<java.time.LocalDate>())
        val shown = mutableListOf<Pair<List<java.time.LocalDate>, String>>()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalLegTimetables provides timetables) {
                val work = planned
                val trains = rememberTimetabledTrains(rideA, now, work)
                SideEffect { shown += work to trains.text }
            }
        }
        settle(scheduler)
        assertEquals("05:41", shown.last().second)

        // Planned work from today: the 05:41 is the timetable's no longer, from the very frame it's known.
        val today = listOf(java.time.LocalDate.of(2026, 10, 20))
        composeRule.runOnIdle { planned = today }
        composeRule.waitForIdle()
        settle(scheduler)
        assertTrue(shown.filter { it.first == today }.none { it.second.contains("05:41") })
        assertEquals(today to "", shown.last())
    }

    @Test
    fun another_ride_placed_never_stands_for_this_one_even_for_a_frame() {
        val scheduler = TestCoroutineScheduler()
        var ride by mutableStateOf(rideA)
        val shown = mutableListOf<Pair<String, String?>>()
        composeRule.setContent {
            // No routes to place it by: a ride is placed as it was saved, once worked out.
            CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler)) {
                val on = ride
                val placed = rememberPlacedRide(on)
                SideEffect { shown += on.fromId to placed?.fromId }
            }
        }
        settle(scheduler)
        assertEquals("A" to "A", shown.last())

        composeRule.runOnIdle { ride = rideC }
        composeRule.waitForIdle()
        assertTrue(shown.filter { it.first == "C" }.none { it.second == "A" })
        settle(scheduler)
        assertEquals("C" to "C", shown.last())
    }
}
