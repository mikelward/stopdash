package app.stopdash

import app.stopdash.domain.Coordinates
import app.stopdash.ui.NearbyStopsViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which near-me screen shows decides when "finding stops" fades through to what replaces it. */
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
}
