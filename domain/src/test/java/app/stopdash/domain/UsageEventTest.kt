package app.stopdash.domain

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventTest {
    private fun stop(id: String, vararg modes: String) =
        StopLocation(id, "Stop $id", 51.5, -0.12, lines = modes.mapIndexed { i, m -> LineRef("$id-line$i", "Line $i", m) })

    // Firebase's naming rules, and what every value may be: a category or a bucket, never free text.
    private val name = Regex("[a-z][a-z0-9_]{0,39}")
    private val vocabulary = UsageEvent.Tap.entries.map { it.value } +
        UsageEvent.Grant.entries.map { it.value } +
        UsageEvent.FixOutcome.entries.map { it.value } +
        ModeGroups.ALL.map { it.key } +
        UsageEvent.Screen.entries.map { it.value } +
        UsageEvent.OpenedFrom.entries.map { it.value } +
        UsageEvent.PlanOutcome.entries.map { it.value } +
        UsageEvent.RouteChoice.entries.map { it.value } +
        listOf(
            "other", "farther_place", "faraway_favorites", "unknown",
            "0", "1", "2-3", "4+", "2", "3", "3+",
            "0-10m", "10-25m", "25-50m", "50-100m", "100-500m", "500m+",
            "0-1s", "1-3s", "3-10s", "10-30s", "30s+",
            "here", "stop", "place", "first", "again",
            // setting_change's settings, then their values.
            "walking_speed", "max_walk", "step_free", "trip_mode", "avoided_line", "hidden_mode",
            "distance_units", "disruptions_row", "disruption_line", "live_widget", "text_size",
            "pinch_resize", "tfl_key", "rail_key", "bug_report_consent",
            "slow", "average", "fast", "10", "15", "20", "30", "45", "60", "any", "station", "fully",
            "automatic", "meters", "yards", "feet", "on", "off", "added", "removed", "set", "cleared", "skipped",
            "all_shown", "0.8-0.95", "0.95-1.05", "1.05-1.25", "1.25+",
        ) +
        (ModeGroups.ALL.map { it.key } + "line" + "other").flatMap { listOf("${it}_hidden", "${it}_shown", "${it}_on", "${it}_off") }

    private fun assertClosed(event: UsageEvent) {
        assertTrue(event.name, name.matches(event.name))
        for ((key, value) in event.params) {
            assertTrue(key, name.matches(key))
            assertTrue("$key=$value", value in vocabulary)
        }
    }

    @Test
    fun `a tap is named by its kind alone`() {
        UsageEvent.Tap.entries.forEach { assertClosed(UsageEvent.Tapped(it)) }
        assertEquals(mapOf("kind" to "journey_card"), UsageEvent.Tapped(UsageEvent.Tap.JOURNEY_CARD).params)
    }

    @Test
    fun `a reveal names its mode group, never a mode id outside them`() {
        assertEquals(mapOf("what" to "farther_place", "mode" to "tube"), UsageEvent.FartherPlace(listOf("dlr")).params)
        assertEquals(mapOf("what" to "farther_place", "mode" to "tube"), UsageEvent.FartherPlace(listOf("Elizabeth-Line", "tube")).params)
        // A mode no group names, or none at all, is "other", not TfL's id.
        assertEquals("other", UsageEvent.FartherPlace(listOf("cable-car")).params["mode"])
        assertEquals("other", UsageEvent.FartherPlace(listOf("HIDDEN-LINE:central")).params["mode"])
        assertEquals("other", UsageEvent.FartherPlace(emptyList()).params["mode"])
        assertClosed(UsageEvent.FartherPlace(listOf("cable-car")))
        assertEquals(mapOf("what" to "faraway_favorites"), UsageEvent.FarawayFavorites.params)
        assertEquals("reveal", UsageEvent.FarawayFavorites.name)
    }

    @Test
    fun `a fix is reported in bands, with no accuracy for one that failed`() {
        val fresh = UsageEvent.LocationFix(UsageEvent.FixOutcome.FRESH, 18f, Duration.ofMillis(2400))
        assertEquals(mapOf("outcome" to "fresh", "accuracy" to "10-25m", "time_to_fix" to "1-3s"), fresh.params)
        val failed = UsageEvent.LocationFix(UsageEvent.FixOutcome.FAILED, 18f, Duration.ofSeconds(12))
        assertEquals(mapOf("outcome" to "failed", "accuracy" to "unknown", "time_to_fix" to "10-30s"), failed.params)
        val last = UsageEvent.LocationFix(UsageEvent.FixOutcome.LAST_KNOWN, null, null)
        assertEquals(mapOf("outcome" to "last_known", "accuracy" to "unknown", "time_to_fix" to "unknown"), last.params)
        listOf(fresh, failed, last).forEach(::assertClosed)
    }

    @Test
    fun `accuracy and time bands have closed edges`() {
        assertEquals("0-10m", UsageEvent.accuracyBand(0f))
        assertEquals("0-10m", UsageEvent.accuracyBand(10f))
        assertEquals("10-25m", UsageEvent.accuracyBand(10.1f))
        assertEquals("50-100m", UsageEvent.accuracyBand(100f))
        assertEquals("100-500m", UsageEvent.accuracyBand(500f))
        assertEquals("500m+", UsageEvent.accuracyBand(5000f))
        assertEquals("unknown", UsageEvent.accuracyBand(Float.NaN))
        assertEquals("unknown", UsageEvent.accuracyBand(-1f))
        assertEquals("0-1s", UsageEvent.timeBand(Duration.ZERO))
        assertEquals("1-3s", UsageEvent.timeBand(Duration.ofSeconds(1)))
        assertEquals("3-10s", UsageEvent.timeBand(Duration.ofSeconds(3)))
        assertEquals("30s+", UsageEvent.timeBand(Duration.ofMinutes(5)))
        assertEquals("unknown", UsageEvent.timeBand(Duration.ofSeconds(-1)))
    }

    @Test
    fun `nearby stops are counted per mode group, in buckets, every group present`() {
        val stops = listOf(
            stop("a", "tube", "overground"),
            stop("b", "bus"),
            stop("c", "bus"),
            stop("d", "bus", "bus"),
            stop("e", "bus"),
            stop("f", "dlr"),
            stop("g", "cable-car"),
        )
        val event = UsageEvent.NearbyStops(stops)
        assertEquals(
            mapOf("tube" to "2-3", "overground" to "1", "rail" to "0", "bus" to "4+", "tram" to "0", "boat" to "0"),
            event.params,
        )
        assertClosed(event)
        assertEquals(ModeGroups.ALL.map { it.key }.toSet(), UsageEvent.NearbyStops(emptyList()).params.keys)
        assertTrue(UsageEvent.NearbyStops(emptyList()).params.values.all { it == "0" })
    }

    @Test
    fun `counts fall in their buckets`() {
        assertEquals(listOf("0", "0", "1", "2-3", "2-3", "4+", "4+"), listOf(-1, 0, 1, 2, 3, 4, 40).map(UsageEvent::countBucket))
    }

    @Test
    fun `permission grants are named alone`() {
        UsageEvent.Grant.entries.forEach { assertClosed(UsageEvent.LocationPermission(it)) }
        assertEquals(UsageEvent.Grant.PRECISE, UsageEvent.Grant.of(fine = true, coarse = true))
        assertEquals(UsageEvent.Grant.APPROXIMATE, UsageEvent.Grant.of(fine = false, coarse = true))
        assertEquals(UsageEvent.Grant.DENIED, UsageEvent.Grant.of(fine = false, coarse = false))
    }

    @Test
    fun `a screen view is Analytics' own, named by the screen alone`() {
        UsageEvent.Screen.entries.forEach { assertClosed(UsageEvent.ScreenView(it)) }
        val home = UsageEvent.ScreenView(UsageEvent.Screen.HOME)
        assertEquals("screen_view", home.name)
        assertEquals(mapOf("screen_name" to "home", "screen_class" to "home"), home.params)
    }

    @Test
    fun `an open says only what opened the app`() {
        UsageEvent.OpenedFrom.entries.forEach { assertClosed(UsageEvent.Opened(it)) }
        assertEquals(mapOf("from" to "widget"), UsageEvent.Opened(UsageEvent.OpenedFrom.WIDGET).params)
    }

    @Test
    fun `a trip plan says how it went and what kind of ends, never which`() {
        val here = TripOrigin.Here(Coordinates(51.5, -0.12))
        val place = TripDestination.Place(Coordinates(51.53, -0.12), "King's Cross")
        val planned = UsageEvent.TripPlanned(UsageEvent.PlanOutcome.ROUTES, 5, here, place, again = false)
        assertEquals(mapOf("outcome" to "routes", "routes" to "4+", "from" to "here", "to" to "place", "plan" to "first"), planned.params)
        val again = UsageEvent.TripPlanned(UsageEvent.PlanOutcome.NO_ROUTES, 0, TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"), again = true)
        assertEquals(mapOf("outcome" to "no_routes", "routes" to "0", "from" to "stop", "to" to "stop", "plan" to "again"), again.params)
        listOf(planned, again).forEach(::assertClosed)
        assertTrue(planned.params.values.none { "King" in it || "51." in it })
    }

    @Test
    fun `a plan's outcome is named by how it answered, or why it failed`() {
        assertEquals(UsageEvent.PlanOutcome.ROUTES, UsageEvent.PlanOutcome.answered(routes = 2, failed = false))
        assertEquals(UsageEvent.PlanOutcome.NO_ROUTES, UsageEvent.PlanOutcome.answered(routes = 0, failed = false))
        assertEquals(UsageEvent.PlanOutcome.PARTIAL, UsageEvent.PlanOutcome.answered(routes = 2, failed = true))
        assertEquals(UsageEvent.PlanOutcome.OFFLINE, UsageEvent.PlanOutcome.failed(TflException.Offline(null)))
        assertEquals(UsageEvent.PlanOutcome.RATE_LIMITED, UsageEvent.PlanOutcome.failed(TflException.RateLimited(null)))
        assertEquals(UsageEvent.PlanOutcome.NETWORK, UsageEvent.PlanOutcome.failed(TflException.Network("timeout", null)))
        // Reached TfL and still failed: TfL's side, as the trip says it.
        assertEquals(UsageEvent.PlanOutcome.SERVER, UsageEvent.PlanOutcome.failed(TflException.Unreachable("HTTP 503", null)))
        assertEquals(UsageEvent.PlanOutcome.KEY_REJECTED, UsageEvent.PlanOutcome.failed(TflException.KeyRejected(null)))
        assertEquals(UsageEvent.PlanOutcome.SERVER, UsageEvent.PlanOutcome.failed(TflException.NotFound(null)))
    }

    @Test
    fun `a route's choice is its card's labels, other under Other, unlabeled where no card is`() {
        val fastest = listOf(RouteLabel.FASTEST)
        assertEquals(UsageEvent.RouteChoice.FASTEST, UsageEvent.RouteChoice.of(fastest, fastest))
        assertEquals(UsageEvent.RouteChoice.FASTEST_LEAST_WALKING, UsageEvent.RouteChoice.of(listOf(RouteLabel.FASTEST, RouteLabel.LEAST_WALKING), fastest))
        assertEquals(UsageEvent.RouteChoice.SIMPLEST, UsageEvent.RouteChoice.of(listOf(RouteLabel.SIMPLEST), fastest))
        // Under Other, the header itself or a card below it with none.
        assertEquals(UsageEvent.RouteChoice.OTHER, UsageEvent.RouteChoice.of(listOf(RouteLabel.OTHER), fastest))
        assertEquals(UsageEvent.RouteChoice.OTHER, UsageEvent.RouteChoice.of(emptyList(), fastest))
        assertEquals(UsageEvent.RouteChoice.UNLABELED, UsageEvent.RouteChoice.of(emptyList(), emptyList()))
        val opened = UsageEvent.RouteOpened(UsageEvent.RouteChoice.SIMPLEST, rank = 2)
        assertEquals(mapOf("choice" to "simplest", "rank" to "2"), opened.params)
        val started = UsageEvent.TripStarted(UsageEvent.RouteChoice.FASTEST, rides = 3)
        assertEquals(mapOf("choice" to "fastest", "changes" to "2"), started.params)
        listOf(opened, started).forEach(::assertClosed)
        UsageEvent.RouteChoice.entries.forEach { assertClosed(UsageEvent.TripStarted(it, 1)) }
        // Every set of labels a card can carry names its own choice.
        val labels = listOf(RouteLabel.FASTEST, RouteLabel.SIMPLEST, RouteLabel.LEAST_WALKING)
        val choices = (1 until 8).map { mask -> UsageEvent.RouteChoice.of(labels.filterIndexed { i, _ -> mask and (1 shl i) != 0 }, fastest) }
        assertEquals(7, choices.distinct().size)
    }

    @Test
    fun `ranks and changes fall in their buckets`() {
        assertEquals(listOf("1", "1", "2", "3", "4+", "4+"), listOf(0, 1, 2, 3, 4, 9).map(UsageEvent::rankBucket))
        assertEquals(listOf("0", "0", "1", "2", "3+", "3+"), listOf(0, 1, 2, 3, 4, 9).map(UsageEvent::changesBucket))
    }

    @Test
    fun `a setting change names the setting and a category for its value`() {
        val changes = WalkingSpeed.entries.map(UsageEvent.SettingChanged::walkingSpeed) +
            MaxWalk.entries.map(UsageEvent.SettingChanged::maxWalk) +
            StepFree.entries.map(UsageEvent.SettingChanged::stepFree) +
            DistanceUnits.entries.map(UsageEvent.SettingChanged::distanceUnits) +
            listOf(true, false).flatMap {
                listOf(
                    UsageEvent.SettingChanged.disruptionsRow(it), UsageEvent.SettingChanged.liveWidget(it),
                    UsageEvent.SettingChanged.pinchToResize(it), UsageEvent.SettingChanged.tflKey(it),
                    UsageEvent.SettingChanged.railKey(it),
                )
            } +
            listOf(0.8f, 1f, 1.2f, 1.6f, Float.NaN).map(UsageEvent.SettingChanged::textSize) +
            UsageEvent.SettingChanged.bugReportConsentSkipped()
        changes.forEach(::assertClosed)
        assertEquals(mapOf("setting" to "walking_speed", "value" to "slow"), UsageEvent.SettingChanged.walkingSpeed(WalkingSpeed.SLOW).params)
        assertEquals(mapOf("setting" to "step_free", "value" to "fully"), UsageEvent.SettingChanged.stepFree(StepFree.FULLY).params)
        assertEquals(mapOf("setting" to "max_walk", "value" to "45"), UsageEvent.SettingChanged.maxWalk(MaxWalk.FORTY_FIVE).params)
        assertEquals(mapOf("setting" to "tfl_key", "value" to "cleared"), UsageEvent.SettingChanged.tflKey(false).params)
    }

    @Test
    fun `a change of trip modes names each group turned on or off`() {
        val busOff = TripModes.DEFAULT.with(ModeGroups.ALL.first { it.key == "bus" }, ride = false)
        val off = UsageEvent.SettingChanged.tripModes(TripModes.DEFAULT, busOff)
        assertEquals(listOf(mapOf("setting" to "trip_mode", "value" to "bus_off")), off.map { it.params })
        assertEquals(listOf("bus_on"), UsageEvent.SettingChanged.tripModes(busOff, TripModes.DEFAULT).map { it.params["value"] })
        assertEquals(emptyList<UsageEvent>(), UsageEvent.SettingChanged.tripModes(busOff, busOff))
        off.forEach(::assertClosed)
    }

    @Test
    fun `a line avoided, hidden or added to the disruptions row is never named`() {
        val avoided = UsageEvent.SettingChanged.avoidedLines(emptySet(), setOf("victoria"))
        assertEquals(listOf(mapOf("setting" to "avoided_line", "value" to "added")), avoided.map { it.params })
        assertEquals(listOf("removed"), UsageEvent.SettingChanged.avoidedLines(setOf("victoria"), emptySet()).map { it.params["value"] })
        val row = UsageEvent.SettingChanged.disruptionLines(setOf("central"), setOf("central", "jubilee"))
        assertEquals(listOf(mapOf("setting" to "disruption_line", "value" to "added")), row.map { it.params })
        val line = HiddenModes.lineKey("victoria", "Victoria")
        val hidden = UsageEvent.SettingChanged.hiddenModes(emptySet(), setOf(line))
        assertEquals(listOf(mapOf("setting" to "hidden_mode", "value" to "line_hidden")), hidden.map { it.params })
        (avoided + row + hidden).forEach(::assertClosed)
        assertTrue((avoided + row + hidden).none { event -> event.params.values.any { "victoria" in it || "jubilee" in it } })
    }

    @Test
    fun `hiding a group says the group once, and showing every one again says so once`() {
        val train = ModeGroups.ALL.first { it.key == "rail" }
        val hidden = UsageEvent.SettingChanged.hiddenModes(emptySet(), train.modes)
        assertEquals(listOf("rail_hidden"), hidden.map { it.params["value"] })
        // The one group shown again is that group, not "all".
        assertEquals(listOf("rail_shown"), UsageEvent.SettingChanged.hiddenModes(train.modes, emptySet()).map { it.params["value"] })
        val several = train.modes + "bus" + HiddenModes.lineKey("central", "Central")
        assertEquals(listOf("all_shown"), UsageEvent.SettingChanged.hiddenModes(several, emptySet()).map { it.params["value"] })
        // A mode no group names is "other", never TfL's id.
        assertEquals(listOf("other_hidden"), UsageEvent.SettingChanged.hiddenModes(emptySet(), setOf("cable-car")).map { it.params["value"] })
        (hidden + UsageEvent.SettingChanged.hiddenModes(several, emptySet())).forEach(::assertClosed)
    }
}
