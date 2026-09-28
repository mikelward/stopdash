package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedTripTest {
    // Hubs' ids and names, and a synthetic coordinate, not anyone's place.
    private val toCanaryWharf = ToChoice(stopId = "940GZZLUCYF", name = "Canary Wharf")
    private val toPlace = ToChoice(place = TripDestination.Place(Coordinates(51.5, -0.12), "Work"), name = "Work")

    @Test
    fun `a To round-trips`() {
        for (to in listOf(ToChoice.NONE, ToChoice.NONE.startPicking(), toCanaryWharf, toPlace, toCanaryWharf.startPicking())) {
            assertEquals(to, SavedTrip.toChoiceOf(SavedTrip.toChoiceFields(to)))
        }
    }

    @Test
    fun `a change of start round-trips with its To`() {
        val changes = listOf(
            OriginChange.NearMe(ToChoice.NONE.startPicking()),
            OriginChange.NearMe(toPlace),
            OriginChange.Station("940GZZLUKSX", "King's Cross St. Pancras", toCanaryWharf),
        )
        for (change in changes) {
            assertEquals(change, SavedTrip.originChangeOf(SavedTrip.originChangeFields(change)))
        }
        assertNull(SavedTrip.originChangeOf(SavedTrip.originChangeFields(null)))
    }

    @Test
    fun `a change of start the build before saved restores at a To search`() {
        // That build kept a station's id and name alone, and "" for near me; its changes began at a
        // To… search. Restoring one after an update must not crash.
        assertEquals(
            OriginChange.Station("940GZZLUKSX", "King's Cross St. Pancras", ToChoice.NONE.startPicking()),
            SavedTrip.originChangeOf(listOf("940GZZLUKSX", "King's Cross St. Pancras")),
        )
        assertEquals(OriginChange.NearMe(ToChoice.NONE.startPicking()), SavedTrip.originChangeOf(listOf("")))
    }

    @Test
    fun `a shape no build saved is no change under way`() {
        assertNull(SavedTrip.originChangeOf(listOf("940GZZLUKSX", "King's Cross St. Pancras", true)))
    }
}
