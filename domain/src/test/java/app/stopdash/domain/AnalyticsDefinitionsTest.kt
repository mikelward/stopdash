package app.stopdash.domain

import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dev-docs/analytics-definitions.tsv`, which `scripts/register_analytics_definitions.py` registers as
 * Analytics custom definitions, names every event parameter and user property the app sends, and nothing
 * else: a parameter renamed or added here (as the mode groups were) can't leave the reports a step behind.
 */
class AnalyticsDefinitionsTest {
    // Found from wherever the tests run, up to the repository's root.
    private val file: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "dev-docs/analytics-definitions.tsv") }
        .first { it.isFile }

    private val rows: List<List<String>> = file.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }

    private fun defined(scope: String) = rows.filter { it[0] == scope }.map { it[1] }

    // One of each kind of event. [kind] is an exhaustive `when`: a new kind of event fails to compile until
    // it's named there, and gets its sample here beside it.
    private val samples: List<UsageEvent> = listOf(
        UsageEvent.Tapped(UsageEvent.Tap.SETTINGS),
        UsageEvent.FartherPlace(listOf("bus")),
        UsageEvent.FarawayFavorites,
        UsageEvent.LocationPermission(UsageEvent.Grant.PRECISE),
        UsageEvent.LocationFix(UsageEvent.FixOutcome.FRESH, 20f, Duration.ofSeconds(2)),
        UsageEvent.NearbyStops(emptyList()),
        UsageEvent.ScreenView(UsageEvent.Screen.HOME),
        UsageEvent.Opened(UsageEvent.OpenedFrom.LAUNCHER),
        UsageEvent.TripPlanned(UsageEvent.PlanOutcome.ROUTES, 2, TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"), again = false),
        UsageEvent.RouteOpened(UsageEvent.RouteChoice.FASTEST, 1),
        UsageEvent.TripStarted(UsageEvent.RouteChoice.FASTEST, 2),
        UsageEvent.SettingChanged.walkingSpeed(WalkingSpeed.SLOW),
    )

    private fun kind(event: UsageEvent): String = when (event) {
        is UsageEvent.Tapped -> "tap"
        is UsageEvent.FartherPlace -> "farther_place"
        UsageEvent.FarawayFavorites -> "faraway_favorites"
        is UsageEvent.LocationPermission -> "location_permission"
        is UsageEvent.LocationFix -> "location_fix"
        is UsageEvent.NearbyStops -> "nearby_stops"
        is UsageEvent.ScreenView -> "screen_view"
        is UsageEvent.Opened -> "open"
        is UsageEvent.TripPlanned -> "trip_plan"
        is UsageEvent.RouteOpened -> "route_open"
        is UsageEvent.TripStarted -> "trip_start"
        is UsageEvent.SettingChanged -> "setting_change"
    }

    // Analytics' own, in its reports without a definition.
    private val builtIn = setOf("screen_name", "screen_class")

    @Test
    fun `every event parameter the app sends is defined, and nothing else`() {
        assertEquals("one sample of each kind", samples.size, samples.map(::kind).toSet().size)
        val sent = samples.flatMap { it.params.keys }.toSet() - builtIn
        assertEquals(sent.sorted(), defined("event").sorted())
    }

    @Test
    fun `every user property the app sends is defined, and nothing else`() {
        val state = UsageState(
            walkingSpeed = WalkingSpeed.AVERAGE, maxWalk = MaxWalk.DEFAULT, stepFree = StepFree.DEFAULT,
            tripModes = TripModes.DEFAULT, avoidedLines = 0, hiddenModes = emptySet(), distanceUnits = DistanceUnits.AUTOMATIC,
            disruptionsRow = true, liveWidget = false, textSize = DEFAULT_FONT_SCALE, pinchToResize = true, tflKey = false,
            railKey = false, widgets = 0, watch = UsageState.Watch.NONE, starredRows = 0, favoritePlaces = 0, favoriteJourneys = 0,
            notifications = true, location = UsageEvent.Grant.PRECISE,
        )
        assertEquals(usageProperties(state).keys.sorted(), defined("user").sorted())
    }

    @Test
    fun `each is defined once, within Analytics' rules and limits`() {
        // Scope, name, display name; a name each scope's custom dimensions may take, once.
        assertTrue(rows.all { it.size == 3 && it[0] in setOf("event", "user") })
        assertEquals(rows.size, rows.map { it[1] }.toSet().size)
        assertTrue(defined("event").all { Regex("[a-z][a-z0-9_]{0,39}").matches(it) })
        assertTrue(defined("user").all { Regex("[a-z][a-z0-9_]{0,23}").matches(it) })
        // A display name starts with a letter and holds letters, digits, spaces and underscores, up to 82.
        assertTrue(rows.all { Regex("[A-Za-z][A-Za-z0-9 _]{0,81}").matches(it[2]) })
        // A standard property's custom dimensions: 50 event-scoped, 25 user-scoped.
        assertTrue(defined("event").size <= 50)
        assertTrue(defined("user").size <= 25)
    }
}
