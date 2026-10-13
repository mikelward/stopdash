package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The compass parse behind the per-direction headers (SPEC D8). Forms taken from live TfL
 * `platformName` values (King's Cross St. Pancras, 2026-09-22). Public station data only.
 */
class PlatformDirectionTest {

    @Test
    fun `parses the compass before the platform number`() {
        assertEquals("Northbound", PlatformDirection.of("Northbound - Platform 1"))
        assertEquals("Eastbound", PlatformDirection.of("Eastbound - Platform 2"))
        assertEquals("Westbound", PlatformDirection.of("Westbound - Platform 1"))
        assertEquals("Southbound", PlatformDirection.of("Southbound - Platform 4"))
    }

    @Test
    fun `keeps a non-cardinal direction label`() {
        // Some sub-surface platforms are labeled by loop direction, not a compass point.
        assertEquals("Inner Rail", PlatformDirection.of("Inner Rail - Platform 1"))
        // A bare compass with no platform number (rare) is still a direction.
        assertEquals("Northbound", PlatformDirection.of("Northbound"))
    }

    @Test
    fun `canonicalizes casing so mixed-case compass labels group as one`() {
        // TfL could supply the same compass in different casings; all must canonicalize to one
        // spelling, or the group key would split into two identical-looking sections (Codex P2).
        assertEquals("Northbound", PlatformDirection.of("NORTHBOUND - Platform 1"))
        assertEquals("Northbound", PlatformDirection.of("northbound - Platform 1"))
        assertEquals("Eastbound", PlatformDirection.of("Eastbound - Platform 2"))
        assertEquals("Inner Rail", PlatformDirection.of("INNER RAIL - Platform 1"))
    }

    @Test
    fun `only the four cardinals are compass directions`() {
        // Only N/S/E/W — intercardinals don't appear on TfL rail platforms, so they are rejected
        // rather than admitted as a direction (Codex P2, PR #109).
        assertNull(PlatformDirection.of("Northeastbound - Platform 1"))
        assertNull(PlatformDirection.of("Southwestbound - Platform 1"))
    }

    @Test
    fun `TfL inbound-outbound jargon is not a compass direction`() {
        // "…bound" alone isn't enough — "Inbound"/"Outbound" are the direction words SPEC rejects
        // for the header; only an actual compass bearing is accepted (Codex P2, PR #109).
        assertNull(PlatformDirection.of("Inbound - Platform 1"))
        assertNull(PlatformDirection.of("Outbound - Platform 1"))
        assertNull(PlatformDirection.of("inbound"))
    }

    @Test
    fun `a bare platform number names no direction`() {
        assertNull(PlatformDirection.of("Platform 4"))
        assertNull(PlatformDirection.of("platform 12"))
        assertNull(PlatformDirection.of("3"))
    }

    @Test
    fun `a bus stop-local platform is not a direction`() {
        // A bus prediction can carry a stop-local `platform` (the stop letter), which must not be
        // read as a direction — it would mislabel a header "Stop A – Stop A" and split one cluster's
        // poles into separate groups (Codex P2, PR #109). Only a rail-direction form is accepted.
        assertNull(PlatformDirection.of("Stop A"))
        assertNull(PlatformDirection.of("Stop A / 1"))
        assertNull(PlatformDirection.of("A"))
    }

    @Test
    fun `an absent platform is no direction`() {
        assertNull(PlatformDirection.of(null))
        assertNull(PlatformDirection.of(""))
        assertNull(PlatformDirection.of("   "))
    }

    @Test
    fun `parses the platform number that a rail place splits on`() {
        // The platform grouping keys on this number (SPEC D8): the digits after "Platform", whether
        // or not a compass leads the name.
        assertEquals("2", PlatformDirection.platformNumber("Eastbound - Platform 2"))
        assertEquals("4", PlatformDirection.platformNumber("Platform 4"))
        assertEquals("12", PlatformDirection.platformNumber("Southbound - Platform 12"))
    }

    @Test
    fun `a name with no platform number has none`() {
        // A bare compass, a bus stop letter, and an empty value carry no platform to split on.
        assertNull(PlatformDirection.platformNumber("Northbound"))
        assertNull(PlatformDirection.platformNumber("Stop A"))
        assertNull(PlatformDirection.platformNumber(null))
        assertNull(PlatformDirection.platformNumber(""))
    }

    @Test
    fun `TfL's unknown platform is no known one`() {
        // A placeholder for trains in either direction, not a physical platform two trains can share.
        assertEquals("Unknown", PlatformDirection.platformNumber("Platform Unknown"))
        assertNull(PlatformDirection.knownPlatformNumber("Platform Unknown"))
        assertNull(PlatformDirection.knownPlatformNumber("platform unknown"))
        assertEquals("2", PlatformDirection.knownPlatformNumber("Southbound - Platform 2"))
        assertNull(PlatformDirection.knownPlatformNumber("Northbound"))
        assertNull(PlatformDirection.knownPlatformNumber(null))
    }
}
