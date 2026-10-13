package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedTripTest {
    // Hubs' ids and names, and a synthetic coordinate, not anyone's place.
    private val toCanaryWharf = ToChoice(stopId = "940GZZLUCYF", name = "Canary Wharf")
    private val toPlace = ToChoice(place = TripDestination.Place(Coordinates(51.5, -0.12), "Work"), name = "Work")
    private val toMuseum = ToChoice(
        place = TripDestination.Place(Coordinates(51.5, -0.12), "Sample Museum", PlaceArt(type = PlaceType.MUSEUM)),
        name = "Sample Museum",
    )
    private val toStreet = ToChoice(place = TripDestination.Place(Coordinates(51.5, -0.12), "Example Street", PlaceArt(PlaceKind.STREET)), name = "Example Street")

    // A To… as the build before a place's art was kept saved it.
    private fun toFieldsWithoutArt(to: ToChoice) = SavedTrip.toChoiceFields(to).dropLast(1)

    @Test
    fun `a To round-trips`() {
        for (to in listOf(ToChoice.NONE, ToChoice.NONE.startPicking(), toCanaryWharf, toPlace, toMuseum, toStreet, toCanaryWharf.startPicking())) {
            assertEquals(to, SavedTrip.toChoiceOf(SavedTrip.toChoiceFields(to)))
        }
    }

    @Test
    fun `a change of start round-trips with its To`() {
        val changes = listOf(
            OriginChange.NearMe(ToChoice.NONE.startPicking()),
            OriginChange.NearMe(toPlace),
            OriginChange.NearMe(toMuseum),
            OriginChange.Station("940GZZLUKSX", "King's Cross St. Pancras", toCanaryWharf),
            // Opened from one of King's Cross & St Pancras's station names, its lead goes with it.
            OriginChange.Station("HUBKGX", "St Pancras International", toCanaryWharf, lead = listOf("national-rail")),
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
    fun `a change of start saved before leads were kept restores with none`() {
        val fields = listOf("HUBKGX", "King's Cross & St Pancras International") + toFieldsWithoutArt(toMuseum)
        assertEquals(
            OriginChange.Station("HUBKGX", "King's Cross & St Pancras International", toMuseum.withPlainPlace()),
            SavedTrip.originChangeOf(fields),
        )
    }

    @Test
    fun `a To saved before a place's art was kept restores as a plain place`() {
        assertEquals(toMuseum.withPlainPlace(), SavedTrip.toChoiceOf(toFieldsWithoutArt(toMuseum)))
        assertEquals(toCanaryWharf, SavedTrip.toChoiceOf(toFieldsWithoutArt(toCanaryWharf)))
    }

    @Test
    fun `a change of start saved before a place's art was kept restores with its lead`() {
        val fields = listOf("HUBKGX", "St Pancras International") + toFieldsWithoutArt(toMuseum) + listOf(arrayListOf("national-rail"))
        assertEquals(
            OriginChange.Station("HUBKGX", "St Pancras International", toMuseum.withPlainPlace(), lead = listOf("national-rail")),
            SavedTrip.originChangeOf(fields),
        )
        val nearMe = listOf(null, null) + toFieldsWithoutArt(toMuseum) + listOf(arrayListOf<String>())
        assertEquals(OriginChange.NearMe(toMuseum.withPlainPlace()), SavedTrip.originChangeOf(nearMe))
    }

    @Test
    fun `an art this build doesn't know restores as a plain place`() {
        val fields = SavedTrip.toChoiceFields(toMuseum).dropLast(1) + "aquarium"
        assertEquals(toMuseum.withPlainPlace(), SavedTrip.toChoiceOf(fields))
    }

    private fun ToChoice.withPlainPlace() = copy(place = place?.copy(art = PlaceArt.PLAIN))

    @Test
    fun `a shape no build saved is no change under way`() {
        assertNull(SavedTrip.originChangeOf(listOf("940GZZLUKSX", "King's Cross St. Pancras", true)))
    }
}
