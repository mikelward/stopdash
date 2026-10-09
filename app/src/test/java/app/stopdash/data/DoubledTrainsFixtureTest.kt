package app.stopdash.data

import app.stopdash.domain.DoubledTrains
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DoubledTrains] against a recorded (trimmed) `/StopPoint/{id}/Arrivals` for Euston's Underground: the
 * Northern line's two southbound platforms and the Victoria line's. TfL listed two different trains due at
 * the same second at each Northern platform, on one of them with two destinations, which is what the log
 * exists to name.
 */
class DoubledTrainsFixtureTest {
    private val departures = Json { ignoreUnknownKeys = true }
        .decodeFromString<List<TflArrivalDto>>(checkNotNull(javaClass.getResource("/fixtures/stoppoint_arrivals_euston.json")).readText())
        .map { it.toDeparture() }

    // Recorded at about 12:24:44 UTC.
    private val now = Instant.parse("2026-10-09T12:24:44Z")

    @Test
    fun `two trains due at the same second at one platform are named, and nothing else is`() {
        assertTrue(departures.size > 40)
        val found = checkNotNull(DoubledTrains.find(departures, now))
        val parts = found.text.split("; ")
        assertEquals(2, parts.size)
        // Platform 2: a Battersea and a Kennington train, both via Charing Cross, due together.
        assertTrue(parts[0], parts[0].startsWith("northern P2: 141 "))
        assertTrue(parts[0], " 154 " in parts[0] && "Kennington" in parts[0] && parts[0].endsWith("(close)"))
        // Platform 6: two Morden trains via Bank, due together.
        assertTrue(parts[1], parts[1].startsWith("northern P6: 053 Morden via Bank "))
        assertTrue(parts[1], " 131 Morden via Bank " in parts[1] && parts[1].endsWith("(close)"))
        // The Victoria line's platforms, and the Northern line's northbound ones, have nothing to say.
        assertTrue(found.key.all { it.startsWith("northern/Southbound") })
        assertEquals(4, found.key.size)
    }

    @Test
    fun `a train TfL lists twice on the recorded board is named as the same train`() {
        val listedTwice = departures + departures.first { it.vehicleId == "216" }.copy(destination = "Kennington", branch = "Charing X")
        val text = checkNotNull(DoubledTrains.describe(listedTwice, now))
        assertTrue(text, "northern P6: 216 Morden via Bank 140 s, 216 Kennington via Charing X 140 s, " in text)
        assertTrue(text, text.endsWith("(same train, close)"))
    }
}
