package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyLayoutTest {
    // Public stations, by their published positions.
    private val kingsCross = StopLocation("940GZZLUKSX", "King's Cross St. Pancras Underground Station", 51.5308, -0.1238, listOf(LineRef("victoria", "Victoria", "tube")), clusterId = "940GZZLUKSX")
    private val stPancras = StopLocation("910GSTPX", "London St Pancras International Rail Station", 51.5320, -0.1270, listOf(LineRef("thameslink", "Thameslink", "national-rail")), clusterId = "910GSTPX")
    private val euston = StopLocation("940GZZLUEUS", "Euston Underground Station", 51.5282, -0.1337, listOf(LineRef("northern", "Northern", "tube")), clusterId = "940GZZLUEUS")
    private val atKingsCross = Coordinates(kingsCross.latitude, kingsCross.longitude)

    @Test
    fun `nearest first breaks a tie by id, so the app and the widget order alike`() {
        val distances = mapOf("b" to 100.0, "a" to 100.0, "c" to 50.0)
        assertEquals(listOf("c", "a", "b"), NearbyLayout.nearestFirst(listOf("b", "a", "c", "b", "d"), distances))
    }

    @Test
    fun `a hidden mode's stops aren't picked unless nothing else is near`() {
        val picked = NearbyLayout.pick(listOf(kingsCross, stPancras), atKingsCross, hidden = setOf("national-rail"))
        assertEquals(listOf(kingsCross.id), picked.eager.flatMap { it.stops }.map { it.id })
        // With only the hidden mode near, the full set is picked rather than nothing.
        val only = NearbyLayout.pick(listOf(stPancras), atKingsCross, hidden = setOf("national-rail"))
        assertEquals(listOf(stPancras.id), only.eager.flatMap { it.stops }.map { it.id })
    }

    @Test
    fun `an anchor stands at the position for picking and distance but keeps its real place`() {
        val picked = NearbyLayout.pick(listOf(kingsCross, euston), atKingsCross, hidden = emptySet(), anchorStopIds = setOf(euston.id))
        assertEquals(0.0, picked.distances.getValue(euston.id), 0.0)
        val shown = (picked.eager + picked.more).flatMap { it.stops }.single { it.id == euston.id }
        assertEquals(euston.latitude, shown.latitude, 0.0)
        assertEquals(euston.longitude, shown.longitude, 0.0)
    }

    @Test
    fun `places are each stop once, and only those with a distance`() {
        val places = NearbyLayout.places(listOf(kingsCross, kingsCross, euston), mapOf(kingsCross.id to 10.0))
        assertEquals(listOf(kingsCross.id), places.map { it.stopId })
        assertTrue(NearbyLayout.place("x", "", "X", emptyMap()) == null)
    }
}
