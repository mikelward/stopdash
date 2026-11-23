package app.stopdash.data

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTimingsStoreTest {
    @Test
    fun `the table reads by stop, line, platform and location`() {
        val text = """{"version": 1, "stops": {"940GZZLUHGT": {"northern": {"2": {"left station a": [[0, 150], [2, 110], [3]]}}}}}"""
        val timings = LocationTimingsStore.parse(text)
        assertEquals(Duration.ofSeconds(150), timings.estimate("940GZZLUHGT", "northern", "2", "left station a", Duration.ofSeconds(5)))
        assertEquals(Duration.ofSeconds(110), timings.estimate("940GZZLUHGT", "northern", "2", "left station a", Duration.ofSeconds(41)))
        // A pair cut short is left out, not read as a time.
        assertNull(timings.estimate("940GZZLUHGT", "northern", "2", "left station a", Duration.ofSeconds(61)))
    }

    @Test
    fun `a newer table than this build reads is TfL's own times`() {
        val warned = mutableListOf<String>()
        val timings = LocationTimingsStore.parse("""{"version": 2, "stops": {}}""") { warned += it }
        assertTrue(timings.isEmpty)
        assertEquals(1, warned.size)
    }

    @Test
    fun `the bundled table reads`() {
        val text = java.io.File("src/main/assets/stations/location_timings.json").readText()
        val timings = LocationTimingsStore.parse(text)
        assertTrue(!timings.isEmpty)
    }
}
