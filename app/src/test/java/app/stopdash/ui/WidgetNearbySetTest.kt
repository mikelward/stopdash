package app.stopdash.ui

import app.stopdash.domain.Coordinates
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.StopLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which nearby set each locating state stores for the widget; synthetic ids and position. */
class WidgetNearbySetTest {
    private val here = Coordinates(51.5, -0.12)
    private fun cluster(id: String) = NearbySelection.NearbyCluster("c:$id", listOf(StopLocation(id, id, 0.0, 0.0)), 0.0)

    @Test
    fun `a ready set stores the stops fetched at once, not the farther ones`() {
        val ready = NearbyStopsViewModel.State.Ready(
            eager = listOf(cluster("490000001A")),
            more = listOf(cluster("490000002B")),
            distanceMeters = emptyMap(),
            location = here,
        )
        assertEquals(setOf("490000001A"), ready.widgetNearbySet(locationAllowed = true))
    }

    @Test
    fun `no stops here stores an empty set`() {
        assertEquals(emptySet<String>(), NearbyStopsViewModel.State.Empty(here).widgetNearbySet(locationAllowed = true))
    }

    @Test
    fun `a failed lookup, no fix, or no permission stores no stops`() {
        assertEquals(emptySet<String>(), NearbyStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.OFFLINE, here).widgetNearbySet(locationAllowed = true))
        assertEquals(emptySet<String>(), NearbyStopsViewModel.State.NoLocation.widgetNearbySet(locationAllowed = true))
        assertEquals(emptySet<String>(), NearbyStopsViewModel.State.PermissionRequired.widgetNearbySet(locationAllowed = false))
    }

    @Test
    fun `a locate under way, or not yet started with permission held, leaves the stored set alone`() {
        assertNull(NearbyStopsViewModel.State.Locating.widgetNearbySet(locationAllowed = true))
        assertNull(NearbyStopsViewModel.State.PermissionRequired.widgetNearbySet(locationAllowed = true))
    }
}
