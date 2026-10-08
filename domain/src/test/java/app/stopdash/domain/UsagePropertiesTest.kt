package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsagePropertiesTest {
    private val defaults = UsageState(
        walkingSpeed = WalkingSpeed.AVERAGE,
        maxWalk = MaxWalk.DEFAULT,
        stepFree = StepFree.DEFAULT,
        tripModes = TripModes.DEFAULT,
        avoidedLines = 0,
        hiddenModes = emptySet(),
        distanceUnits = DistanceUnits.AUTOMATIC,
        disruptionsRow = true,
        liveWidget = false,
        textSize = DEFAULT_FONT_SCALE,
        pinchToResize = true,
        tflKey = false,
        railKey = false,
        widgets = 0,
        watch = UsageState.Watch.NONE,
        starredRows = 0,
        favoritePlaces = 0,
        favoriteJourneys = 0,
        notifications = true,
        location = UsageEvent.Grant.PRECISE,
    )

    // Analytics' limits: a name of letters, digits and underscores up to 24 characters, a value up to
    // 36, and 25 properties at most.
    private val name = Regex("[a-z][a-z0-9_]{0,23}")

    private fun assertWithinLimits(properties: Map<String, String>) {
        assertTrue(properties.size <= 25)
        for ((key, value) in properties) {
            assertTrue(key, name.matches(key))
            assertTrue("$key=$value", value.length <= 36)
            assertTrue(key, !key.startsWith("firebase_") && !key.startsWith("google_") && !key.startsWith("ga_"))
        }
    }

    @Test
    fun `a rider who changed nothing reads as the defaults`() {
        val properties = usageProperties(defaults)
        assertEquals("average", properties["walking_speed"])
        assertEquals("20", properties["max_walk"])
        assertEquals("any", properties["step_free"])
        assertEquals("none", properties["trip_modes_off"])
        assertEquals("none", properties["hidden_modes"])
        assertEquals("0.95-1.05", properties["text_size"])
        assertEquals("no", properties["own_tfl_key"])
        assertEquals("none", properties["watch"])
        assertWithinLimits(properties)
    }

    @Test
    fun `choices read as categories and counts as buckets`() {
        val properties = usageProperties(
            defaults.copy(
                walkingSpeed = WalkingSpeed.SLOW,
                stepFree = StepFree.FULLY,
                tripModes = TripModes(setOf("bus", "boat")),
                avoidedLines = 2,
                widgets = 1,
                watch = UsageState.Watch.WITH_APP,
                starredRows = 7,
                tflKey = true,
                location = UsageEvent.Grant.APPROXIMATE,
            ),
        )
        assertEquals("slow", properties["walking_speed"])
        assertEquals("fully", properties["step_free"])
        // In the menu's order, whatever order they were turned off in.
        assertEquals("bus+boat", properties["trip_modes_off"])
        assertEquals("2-3", properties["avoided_lines"])
        assertEquals("1", properties["widgets"])
        assertEquals("app", properties["watch"])
        assertEquals("4+", properties["starred_rows"])
        assertEquals("yes", properties["own_tfl_key"])
        assertEquals("approximate", properties["location"])
        assertWithinLimits(properties)
    }

    @Test
    fun `hidden modes are their groups and hidden lines a count, never a line`() {
        val hidden = ModeGroups.ALL.first { it.key == "rail" }.modes + "bus" + "cable-car" +
            HiddenModes.lineKey("victoria", "Victoria") + HiddenModes.lineKey("central", "Central")
        val properties = usageProperties(defaults.copy(hiddenModes = hidden))
        assertEquals("rail+bus+other", properties["hidden_modes"])
        assertEquals("2-3", properties["hidden_lines"])
        assertTrue(properties.values.none { "victoria" in it.lowercase() || "central" in it.lowercase() })
    }

    @Test
    fun `what couldn't be read is unknown, and every group at once stays within the limit`() {
        val properties = usageProperties(
            defaults.copy(
                widgets = null, watch = null, starredRows = null, favoritePlaces = null, favoriteJourneys = null,
                notifications = null, location = null,
                hiddenModes = ModeGroups.ALL.flatMap { it.modes }.toSet() + "cable-car",
            ),
        )
        listOf("widgets", "watch", "starred_rows", "favorite_places", "favorite_journeys", "notifications", "location")
            .forEach { assertEquals(it, "unknown", properties[it]) }
        assertWithinLimits(properties)
    }
}
