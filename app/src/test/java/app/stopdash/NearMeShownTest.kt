package app.stopdash

import app.stopdash.domain.Coordinates
import app.stopdash.ui.NearbyStopsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which near-me screen shows decides when what replaces "finding stops" comes in row by row. */
class NearMeShownTest {
    private val here = Coordinates(51.5, -0.12)
    private val ready = NearbyStopsViewModel.State.Ready(eager = emptyList(), more = emptyList(), distanceMeters = emptyMap(), location = here)

    @Test
    fun `ready is the list, or the trip when it is open`() {
        assertEquals(NearMeScreen.LIST, NearMeShown(ready, tripOpen = false).screen)
        assertEquals(NearMeScreen.TRIP, NearMeShown(ready, tripOpen = true).screen)
    }

    @Test
    fun `no stops nearby is the gate, or the trip from there when it is open`() {
        assertEquals(NearMeScreen.GATE, NearMeShown(NearbyStopsViewModel.State.Empty(here), tripOpen = false).screen)
        assertEquals(NearMeScreen.TRIP, NearMeShown(NearbyStopsViewModel.State.Empty(here), tripOpen = true).screen)
    }

    @Test
    fun `finding stops and every stuck state are the gate, trip or not`() {
        for (state in listOf(
            NearbyStopsViewModel.State.Locating,
            NearbyStopsViewModel.State.PermissionRequired,
            NearbyStopsViewModel.State.NoLocation,
        )) {
            assertEquals(NearMeScreen.GATE, NearMeShown(state, tripOpen = false).screen)
            assertEquals(NearMeScreen.GATE, NearMeShown(state, tripOpen = true).screen)
        }
    }

    @Test
    fun `the trip's spinner while its origin is worked out is its own screen, and loading`() {
        val pending = NearMeShown(ready, tripOpen = true, originPending = true)
        assertEquals(NearMeScreen.ORIGIN, pending.screen)
        assertTrue(pending.loading)
        // So the trip that replaces it is a new screen out of a loading one: it comes in row by row.
        val trip = NearMeShown(ready, tripOpen = true)
        assertFalse(trip.loading)
        assertEquals(NearMeScreen.TRIP, trip.screen)
    }

    @Test
    fun `finding stops is loading, and nothing else on its own is`() {
        assertTrue(NearMeShown(NearbyStopsViewModel.State.Locating, tripOpen = false).loading)
        assertFalse(NearMeShown(NearbyStopsViewModel.State.NoLocation, tripOpen = false).loading)
        assertFalse(NearMeShown(ready, tripOpen = false).loading)
    }
}
