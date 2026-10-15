package app.stopdash.ui

import androidx.compose.runtime.saveable.SaverScope
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTime
import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaterTripNavTest {
    private val saturday = TripTime(TripTime.Kind.LEAVE, LocalDateTime.of(2026, 10, 17, 10, 0))
    private val sunday = TripTime(TripTime.Kind.ARRIVE, LocalDateTime.of(2026, 10, 18, 9, 30))

    @Test
    fun `a route opened from the later list goes back to it when it closes`() {
        val opened = LaterTripNav().picked(saturday).opened("route-key")
        // Live now: no later list, but which it came from and the route.
        assertNull(opened.later)
        assertEquals(saturday, opened.openedFrom)
        assertEquals("route-key", opened.openedKey)
        assertEquals(LaterTripNav(later = saturday), opened.routeClosed())
    }

    @Test
    fun `a route closed on the trip for now goes nowhere else`() {
        assertEquals(LaterTripNav(), LaterTripNav().routeClosed())
    }

    @Test
    fun `back from the later list is the trip for now, and a new time replaces the last`() {
        assertEquals(LaterTripNav(), LaterTripNav().picked(saturday).leftLater())
        assertEquals(LaterTripNav(later = sunday), LaterTripNav().picked(saturday).picked(sunday))
        // Picked from a route opened off the later list: the new list, the route forgotten.
        assertEquals(LaterTripNav(later = sunday), LaterTripNav().picked(saturday).opened("route-key").picked(sunday))
    }

    @Test
    fun `an unreadable saved time restores as the trip for now, and says so`() {
        val warned = mutableListOf<String>()
        val restored = LaterTripNav.saver(warned::add).restore(listOf("SOON@2026-10-17T10:00", null, null))
        assertEquals(LaterTripNav(), restored)
        assertEquals(1, warned.size)
    }

    @Test
    fun `a route is said to be not planned for now only by a settled plan`() {
        fun leg(line: String, from: String, to: String) = TripLeg(
            "tube", line, line, from, from, to, to, Instant.parse("2026-10-15T08:05:00Z"), Instant.parse("2026-10-15T08:25:00Z"),
        )
        val offered = TripRoute(listOf(leg("red", "A", "C")))
        val other = TripRoute(listOf(leg("blue", "A", "C")))
        val key = routeKey(other)
        assertTrue(notPlannedForNow(TripViewModel.State(routes = listOf(offered)), key))
        assertFalse(notPlannedForNow(TripViewModel.State(routes = listOf(offered, other)), key))
        // Still planning (Back before the fresh plan lands), failed, or nothing yet: it may yet be offered.
        assertFalse(notPlannedForNow(TripViewModel.State(routes = listOf(offered), planning = true), key))
        assertFalse(notPlannedForNow(TripViewModel.State(routes = listOf(offered), planError = DeparturesUiState.Error.Kind.OFFLINE), key))
        assertFalse(notPlannedForNow(TripViewModel.State(), key))
    }

    @Test
    fun `is kept whole over a recreated process`() {
        val scope = SaverScope { true }
        for (nav in listOf(LaterTripNav(), LaterTripNav(later = saturday), LaterTripNav(later = saturday).opened("route-key"))) {
            val saved = checkNotNull(with(LaterTripNav.Saver) { scope.save(nav) })
            assertEquals(nav, LaterTripNav.Saver.restore(saved))
        }
    }
}
