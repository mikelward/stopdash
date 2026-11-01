package app.stopdash.ui

import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A trip card's stop names come in the forms they shorten through, worked out with the card in the page's
 * frame ([TripCardView.startForms], [TripCardView.stopForms]), so the card only measures them. Synthetic stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TripCardFormsTest {
    private val now: Instant = Instant.parse("2026-09-26T08:00:00Z")

    private fun leg(lineId: String, from: String, to: String, departs: Long, arrives: Long) = TripLeg(
        mode = "tube",
        lineId = lineId,
        lineName = lineId,
        fromId = from,
        fromName = from,
        toId = to,
        toName = to,
        departure = now.plus(Duration.ofMinutes(departs)),
        arrival = now.plus(Duration.ofMinutes(arrives)),
        path = listOf(to),
    )

    @Test
    fun `a trip card's stop names come in the forms they shorten through`() {
        val route = TripRoute(listOf(leg("red", "East Finchley", "Wood Lane", 5, 15), leg("blue", "Wood Lane", "Shepherd's Bush Market", 20, 30)))
        val state = TripViewModel.State(routes = listOf(route))
        val frame = tripFrame(
            null, state, now, Duration.ZERO, emptyMap(), emptySet(), false, rideLines(listOf(route), state, StepFree.DEFAULT, emptyMap()),
            route.legs.toSet(), null, emptySet(), emptySet(), null, state, StepFree.DEFAULT,
            resources = RuntimeEnvironment.getApplication().resources,
        )
        val card = checkNotNull(frame.list).cards.single()
        // The stop the walk starts at, then each ride's stop it gets off at, each in full, shortened and at its floor.
        assertEquals(DestinationAbbreviations.forms("East Finchley"), card.startForms)
        assertEquals(listOf("Wood Lane", "Shepherd's Bush Market").map(DestinationAbbreviations::forms), card.stopForms)
        // Shortened forms, not the name over again.
        assertNotEquals("East Finchley", checkNotNull(card.startForms)[1])
        assertNotEquals("Wood Lane", card.stopForms[0][1])
        // No walk after the last ride, no row for one.
        assertNull(card.endWalk)
        assertNull(card.endForms)
    }

    private fun walk(from: String, to: String, departs: Long, arrives: Long) =
        leg("", from, to, departs, arrives).copy(mode = TripLeg.WALKING, lineName = "", path = emptyList())

    @Test
    fun a_long_walk_after_the_last_ride_comes_with_the_card() {
        // 3 min then 4 min on foot after the ride: 7 min in all, to where the last walk ends.
        val route = TripRoute(listOf(leg("red", "East Finchley", "Wood Lane", 5, 15), walk("Wood Lane", "Gate", 15, 18), walk("Gate", "Shepherd's Bush Market", 18, 22)))
        val state = TripViewModel.State(routes = listOf(route))
        val frame = tripFrame(
            null, state, now, Duration.ZERO, emptyMap(), emptySet(), false, rideLines(listOf(route), state, StepFree.DEFAULT, emptyMap()),
            route.legs.toSet(), null, emptySet(), emptySet(), null, state, StepFree.DEFAULT,
            resources = RuntimeEnvironment.getApplication().resources,
        )
        val card = checkNotNull(frame.list).cards.single()
        assertEquals(Duration.ofMinutes(7), card.endWalk)
        assertEquals(DestinationAbbreviations.forms("Shepherd's Bush Market"), card.endForms)
    }

    @Test
    fun a_walk_after_the_last_ride_shows_from_five_minutes() {
        val ride = leg("red", "East Finchley", "Wood Lane", 5, 15)
        assertNull(walkToEnd(TripRoute(listOf(ride, walk("Wood Lane", "Gate", 15, 19)))))
        assertEquals(Duration.ofMinutes(5) to "Gate", walkToEnd(TripRoute(listOf(ride, walk("Wood Lane", "Gate", 15, 20)))))
        // A walk before or between rides isn't the walk at the end, nor is a route on foot alone.
        assertNull(walkToEnd(TripRoute(listOf(walk("Home", "East Finchley", 0, 10), ride))))
        assertNull(walkToEnd(TripRoute(listOf(walk("East Finchley", "Gate", 0, 30)))))
    }
}
