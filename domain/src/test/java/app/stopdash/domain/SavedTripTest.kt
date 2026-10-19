package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedTripTest {
    // Hubs' ids and names, and a synthetic coordinate, not anyone's place.
    private val toCanaryWharf = ToChoice(stopId = "940GZZLUCYF", name = "Canary Wharf")
    private val toCanaryWharfStation = toCanaryWharf.copy(level = Pick.Level.STATION)
    private val toKingsCross = ToChoice(stopId = "HUBKGX", name = "King's Cross & St Pancras International", level = Pick.Level.HUB)
    private val toPlace = ToChoice(place = TripDestination.Place(Coordinates(51.5, -0.12), "Work"), name = "Work")
    private val toMuseum = ToChoice(
        place = TripDestination.Place(Coordinates(51.5, -0.12), "Sample Museum", PlaceArt(type = PlaceType.MUSEUM)),
        name = "Sample Museum",
    )
    private val toStreet = ToChoice(place = TripDestination.Place(Coordinates(51.5, -0.12), "Example Street", PlaceArt(PlaceKind.STREET)), name = "Example Street")

    // A To… as the build before its level was kept saved it.
    private fun toFieldsWithoutLevel(to: ToChoice) = SavedTrip.toChoiceFields(to).dropLast(1)

    // A To… as the build before a place's art was kept saved it.
    private fun toFieldsWithoutArt(to: ToChoice) = SavedTrip.toChoiceFields(to).dropLast(2)

    @Test
    fun `a To round-trips`() {
        for (to in listOf(ToChoice.NONE, ToChoice.NONE.startPicking(), toCanaryWharf, toCanaryWharfStation, toKingsCross, toPlace, toMuseum, toStreet, toCanaryWharf.startPicking())) {
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
            OriginChange.Station("940GZZLUKSX", "King's Cross St. Pancras", toCanaryWharfStation),
            OriginChange.NearMe(toKingsCross),
            // At the level the station was picked at.
            OriginChange.Station("HUBKGX", "King's Cross & St Pancras International", toCanaryWharf, level = Pick.Level.HUB),
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
        val fields = toFieldsWithoutArt(toMuseum) + "aquarium"
        assertEquals(toMuseum.withPlainPlace(), SavedTrip.toChoiceOf(fields))
    }

    private fun ToChoice.withPlainPlace() = copy(place = place?.copy(art = PlaceArt.PLAIN))

    @Test
    fun `a To saved before its level was kept restores with none, read as a row that didn't say`() {
        assertEquals(toCanaryWharf, SavedTrip.toChoiceOf(toFieldsWithoutLevel(toCanaryWharfStation)))
        assertEquals(toMuseum, SavedTrip.toChoiceOf(toFieldsWithoutLevel(toMuseum)))
        val station = listOf("940GZZLUKSX", "King's Cross St. Pancras") + toFieldsWithoutLevel(toKingsCross) + listOf(arrayListOf("tube"))
        assertEquals(
            OriginChange.Station("940GZZLUKSX", "King's Cross St. Pancras", toKingsCross.copy(level = null), lead = listOf("tube")),
            SavedTrip.originChangeOf(station),
        )
    }

    @Test
    fun `a change of start saved before its station's level was kept restores with none`() {
        val change = OriginChange.Station("HUBKGX", "St Pancras International", toCanaryWharfStation, lead = listOf("national-rail"), level = Pick.Level.STATION)
        val before = SavedTrip.originChangeFields(change).dropLast(1)
        assertEquals(change.copy(level = null), SavedTrip.originChangeOf(before))
        assertEquals(OriginChange.NearMe(toKingsCross), SavedTrip.originChangeOf(SavedTrip.originChangeFields(OriginChange.NearMe(toKingsCross)).dropLast(1)))
    }

    @Test
    fun `a level this build doesn't know restores as none`() {
        assertEquals(toCanaryWharf, SavedTrip.toChoiceOf(toFieldsWithoutLevel(toCanaryWharf) + "junction"))
    }

    @Test
    fun `a shape no build saved is no change under way`() {
        assertNull(SavedTrip.originChangeOf(listOf("940GZZLUKSX", "King's Cross St. Pancras", true)))
    }
}
