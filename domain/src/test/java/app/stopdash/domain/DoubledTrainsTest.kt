package app.stopdash.domain

import java.time.Instant
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull

class DoubledTrainsTest {
    private val now = Instant.parse("2026-10-09T10:43:00Z")

    private fun train(
        seconds: Long,
        destination: String,
        branch: String?,
        vehicle: String,
        platform: String? = "Southbound - Platform 2",
        line: String = "northern",
    ) = Departure(
        lineId = line, lineName = "Northern", direction = "inbound", destination = destination, platform = platform,
        expectedArrival = now.plusSeconds(seconds), mode = "tube", branch = branch, vehicleId = vehicle,
    )

    @Test
    fun `a board with its trains spread out has nothing to say`() {
        val board = listOf(
            train(70, "Morden", "Bank", "031"),
            train(200, "Battersea Power", "Charing X", "034"),
            train(410, "Morden", "Bank", "143"),
        )
        assertNull(DoubledTrains.describe(board, now))
    }

    @Test
    fun `one train listed under both branches is named, each time`() {
        val board = listOf(
            train(50, "Battersea Power", "Charing X", "053"),
            train(55, "Morden", "Bank", "053"),
            train(410, "Morden", "Bank", "143"),
        )
        assertEquals(
            "northern P2: 053 Battersea Power via Charing X 50 s, 053 Morden via Bank 55 s (same train, close)",
            DoubledTrains.describe(board, now),
        )
    }

    @Test
    fun `a train drawn twice as equal entries is named twice`() {
        val twice = train(130, "Morden", "Bank", "031")
        assertEquals(
            "northern P2: 031 Morden via Bank 130 s, 031 Morden via Bank 130 s (same train, close)",
            DoubledTrains.describe(listOf(twice, twice), now),
        )
    }

    @Test
    fun `two trains under a minute apart at one platform are named as close`() {
        val board = listOf(train(10, "Battersea Power", "Charing X", "053"), train(50, "Morden", "Bank", "031"))
        assertEquals(
            "northern P2: 053 Battersea Power via Charing X 10 s, 031 Morden via Bank 50 s (close)",
            DoubledTrains.describe(board, now),
        )
    }

    @Test
    fun `trains a minute or more apart, or at different platforms, are not close`() {
        val board = listOf(
            train(10, "Battersea Power", "Charing X", "053"),
            train(70, "Morden", "Bank", "031"),
            train(20, "High Barnet", "Bank", "065", platform = "Northbound - Platform 1"),
        )
        assertNull(DoubledTrains.describe(board, now))
    }

    @Test
    fun `bunched buses are not close, but one bus listed twice is named`() {
        fun bus(seconds: Long, vehicle: String) = train(seconds, "Somewhere", null, vehicle, platform = null, line = "1")
        assertNull(DoubledTrains.describe(listOf(bus(10, "AB1"), bus(20, "AB2")), now))
        assertEquals("1: AB1 Somewhere 10 s, AB1 Somewhere 20 s (same train)", DoubledTrains.describe(listOf(bus(10, "AB1"), bus(20, "AB1")), now))
    }

    @Test
    fun `trains without an id are only ever close, never the same train`() {
        val board = listOf(train(10, "Morden", "Bank", ""), train(100, "Morden", "Bank", ""))
        assertNull(DoubledTrains.describe(board, now))
        assertEquals(
            "northern P2: no id Morden via Bank 10 s, no id Morden via Bank 40 s (close)",
            DoubledTrains.describe(listOf(train(10, "Morden", "Bank", ""), train(40, "Morden", "Bank", "")), now),
        )
    }

    @Test
    fun `the same suspects read again later have the same key, other suspects another`() {
        val first = DoubledTrains.find(listOf(train(50, "Battersea Power", "Charing X", "053"), train(55, "Morden", "Bank", "053")), now)!!
        val later = DoubledTrains.find(listOf(train(20, "Battersea Power", "Charing X", "053"), train(25, "Morden", "Bank", "053")), now)!!
        val other = DoubledTrains.find(listOf(train(20, "Battersea Power", "Charing X", "034"), train(25, "Morden", "Bank", "034")), now)!!
        assertEquals(first.key, later.key)
        assertNotEquals(first.key, other.key)
    }

    @Test
    fun `look-alike trains without an id, or a train repeated more times, are new suspects`() {
        fun pair(first: Long) = listOf(train(first, "Morden", "Bank", ""), train(first + 30, "Morden", "Bank", ""))
        val first = DoubledTrains.find(pair(10), now)!!
        assertEquals(first.key, DoubledTrains.find(pair(10), now.minusSeconds(5))!!.key)
        assertNotEquals(first.key, DoubledTrains.find(pair(200), now)!!.key)
        val twice = train(130, "Morden", "Bank", "031")
        assertNotEquals(
            DoubledTrains.find(listOf(twice, twice), now)!!.key,
            DoubledTrains.find(listOf(twice, twice, twice), now)!!.key,
        )
    }

    @Test
    fun `one train listed again under another platform, or none, is still the same train`() {
        val board = listOf(
            train(50, "Morden", "Bank", "053"),
            train(300, "Battersea Power", "Charing X", "053", platform = "Southbound - Platform 6"),
            train(400, "Morden", "Bank", "053", platform = null),
            train(500, "Morden", "Bank", "053", line = "victoria"),
        )
        assertEquals(
            "northern P2: 053 Morden via Bank 50 s (same train); northern P6: 053 Battersea Power via Charing X 300 s (same train); " +
                "northern: 053 Morden via Bank 400 s (same train)",
            DoubledTrains.describe(board, now),
        )
    }
}
