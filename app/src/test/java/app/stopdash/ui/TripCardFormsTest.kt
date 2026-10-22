package app.stopdash.ui

import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    }
}
