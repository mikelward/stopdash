package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Trains TfL lists at a terminus as arriving there. Public station ids and names only. */
class TurnbackTest {
    private val now = Instant.parse("2026-10-09T18:00:00Z")
    private val wwl = "940GZZLUWWL"
    private val brixton = mapOf("victoria" to listOf(Turnback.End("940GZZLUBXN", "Brixton")))

    private fun arriving(vehicle: String, platform: String, seconds: Long) = Departure(
        "victoria", "Victoria", "", "Walthamstow Central", platform, now.plusSeconds(seconds), "tube",
        destinationId = wwl, vehicleId = vehicle,
    )

    @Test
    fun `a train arriving at the terminus becomes one to the far end, once, under its compass`() {
        val listed = listOf(
            arriving("216", "Southbound - Platform 1", 26),
            arriving("216", "Southbound - Platform 2", 55),
            arriving("217", "Southbound - Platform 2", 85),
            arriving("217", "Southbound - Platform 1", 56),
        )
        val out = Turnback.relabel(listed, wwl, brixton)
        assertEquals(listOf("216", "217"), out.map { it.vehicleId })
        assertEquals(listOf(now.plusSeconds(26), now.plusSeconds(56)), out.map { it.expectedArrival })
        assertEquals(setOf("Brixton"), out.map { it.destination }.toSet())
        assertEquals(setOf("940GZZLUBXN"), out.map { it.destinationId }.toSet())
        assertEquals(setOf("Southbound"), out.map { it.platform }.toSet())
    }

    @Test
    fun `trains with no vehicle id are told apart by matching each platform's list`() {
        // The same two trains on both platforms, TfL naming neither: one each, at its soonest.
        val listed = listOf(
            arriving("", "Southbound - Platform 1", 26),
            arriving("", "Southbound - Platform 2", 55),
            arriving("", "Southbound - Platform 2", 85),
            arriving("", "Southbound - Platform 1", 56),
        )
        val out = Turnback.relabel(listed, wwl, brixton)
        assertEquals(listOf(now.plusSeconds(26), now.plusSeconds(56)), out.map { it.expectedArrival })
        assertEquals(setOf("Brixton"), out.map { it.destination }.toSet())
    }

    @Test
    fun `trains with no vehicle id are all kept where the platforms' lists don't match`() {
        // Three listed across two platforms: not each train on each, so none is taken for a copy.
        val listed = listOf(
            arriving("", "Southbound - Platform 1", 26),
            arriving("", "Southbound - Platform 2", 55),
            arriving("", "Southbound - Platform 1", 300),
        )
        assertEquals(3, Turnback.relabel(listed, wwl, brixton).size)
    }

    @Test
    fun `trains with no vehicle id are all kept where one names no platform`() {
        val listed = listOf(arriving("", "Southbound - Platform 1", 26), arriving("", "", 55))
        assertEquals(2, Turnback.relabel(listed, wwl, brixton).size)
    }

    @Test
    fun `a platform with no compass is dropped, not kept as the one it will use`() {
        val numbered = listOf(arriving("216", "Platform 2", 26), arriving("216", "Platform 1", 55))
        assertEquals(listOf(null), Turnback.relabel(numbered, wwl, brixton).map { it.platform })
    }

    @Test
    fun `the way a train arrived is not the way it leaves`() {
        val inbound = arriving("216", "Southbound - Platform 1", 26).copy(direction = "inbound")
        assertEquals("outbound", Turnback.relabel(listOf(inbound), wwl, brixton).single().direction)
        val outbound = arriving("217", "Southbound - Platform 1", 56).copy(direction = "outbound")
        assertEquals("inbound", Turnback.relabel(listOf(outbound), wwl, brixton).single().direction)
        // None given, or one TfL doesn't name this way: none, rather than a guess.
        assertEquals("", Turnback.relabel(listOf(arriving("218", "Southbound - Platform 1", 86)), wwl, brixton).single().direction)
    }

    @Test
    fun `a terminus with more than one far end leaves the destination unknown`() {
        val morden = "940GZZLUMDN"
        val ends = mapOf("northern" to listOf(Turnback.End("940GZZLUEGW", "Edgware"), Turnback.End("940GZZLUHBT", "High Barnet")))
        val train = Departure("northern", "Northern", "", "Morden", "Northbound - Platform 2", now, "tube", destinationId = morden, vehicleId = "1")
        val out = Turnback.relabel(listOf(train), morden, ends).single()
        assertEquals("Check Front of Train", out.destination)
        assertEquals(true, RouteStops.isUnknownDestination(out.destination))
        assertEquals("", out.destinationId)
    }

    @Test
    fun `other trains, and a station with no far ends, are left alone`() {
        val onward = Departure("victoria", "Victoria", "", "Brixton", "Southbound - Platform 1", now, "tube", destinationId = "940GZZLUBXN", vehicleId = "9")
        val turnedShort = arriving("216", "Northbound - Platform 1", 30).copy(destinationId = "940GZZLUSVS")
        assertEquals(listOf(onward, turnedShort), Turnback.relabel(listOf(onward, turnedShort), wwl, brixton))
        val here = arriving("216", "Southbound - Platform 1", 26)
        assertEquals(listOf(here), Turnback.relabel(listOf(here), wwl, emptyMap()))
    }
}
