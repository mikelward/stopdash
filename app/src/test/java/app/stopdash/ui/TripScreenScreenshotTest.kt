package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineRef
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.R
import app.stopdash.domain.Departure
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Duration
import java.time.Instant
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A trip with a change (SPEC *Trips with a change*): the routes, a route leg by leg, and the
 * planning and failed states. A trip between two well-known stations; the times and the Jubilee
 * delay are made up. No user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TripScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")

    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

    private fun leg(
        mode: String,
        lineId: String,
        lineName: String,
        from: Pair<String, String>,
        to: Pair<String, String>,
        departs: Long,
        arrives: Long,
        stops: Int,
        change: Long = 0,
    ) = TripLeg(
        mode = mode,
        lineId = lineId,
        lineName = lineName,
        fromId = from.first,
        fromName = from.second,
        toId = to.first,
        toName = to.second,
        departure = at(departs),
        arrival = at(arrives),
        path = List(stops - 1) { "stop$it" } + to.first,
        changeAfter = Duration.ofMinutes(change),
    )

    private val highbury = "910GHGHI" to "Highbury & Islington"
    private val whitechapel = "910GWCHAPEL" to "Whitechapel"
    private val whitechapelXr = "910GWCHAPXR" to "Whitechapel"
    private val canaryWharfXr = "910GCANWHRF" to "Canary Wharf"
    private val canadaWater = "910GCNDAW" to "Canada Water"
    private val canadaWaterTube = "940GZZLUCWR" to "Canada Water"
    private val canaryWharf = "940GZZLUCYF" to "Canary Wharf"
    private val stratford = "910GSTFD" to "Stratford (London)"
    private val stratfordTube = "940GZZLUSTD" to "Stratford"

    private val viaWhitechapel = TripRoute(
        listOf(
            leg("overground", "windrush", "Windrush", highbury, whitechapel, 3, 16, 6, change = 3),
            leg("elizabeth-line", "elizabeth", "Elizabeth line", whitechapelXr, canaryWharfXr, 19, 23, 2),
        ),
    )
    private val viaCanadaWater = TripRoute(
        listOf(
            leg("overground", "windrush", "Windrush", highbury, canadaWater, 3, 21, 9, change = 3),
            leg("tube", "jubilee", "Jubilee", canadaWaterTube, canaryWharf, 24, 26, 1),
        ),
    )
    private val viaStratford = TripRoute(
        listOf(
            leg("overground", "mildmay", "Mildmay", highbury, stratford, 5, 21, 6, change = 6),
            leg("tube", "jubilee", "Jubilee", stratfordTube, canaryWharf, 28, 37, 4),
        ),
    )

    private fun train(lineId: String, lineName: String, mode: String, destination: String, inMinutes: Long, platform: String) =
        Departure(
            lineId = lineId,
            lineName = lineName,
            direction = "outbound",
            destination = destination,
            platform = platform,
            expectedArrival = at(inMinutes),
            mode = mode,
        )

    private val live = mapOf(
        highbury.first to TripViewModel.StopLive(
            listOf(
                train("windrush", "Windrush", "overground", "Crystal Palace", 3, "Platform 2"),
                train("windrush", "Windrush", "overground", "West Croydon", 7, "Platform 2"),
                train("windrush", "Windrush", "overground", "Crystal Palace", 11, "Platform 2"),
                train("mildmay", "Mildmay", "overground", "Stratford (London)", 5, "Platform 7"),
            ),
            now,
        ),
        whitechapelXr.first to TripViewModel.StopLive(
            listOf(
                train("elizabeth", "Elizabeth line", "elizabeth-line", "Abbey Wood", 14, "Platform A"),
                train("elizabeth", "Elizabeth line", "elizabeth-line", "Abbey Wood", 18, "Platform A"),
                train("elizabeth", "Elizabeth line", "elizabeth-line", "Abbey Wood", 24, "Platform A"),
            ),
            now,
        ),
        canadaWaterTube.first to TripViewModel.StopLive(
            listOf(train("jubilee", "Jubilee", "tube", "Stratford", 25, "Eastbound - Platform 2")),
            now,
        ),
        stratfordTube.first to TripViewModel.StopLive(
            listOf(train("jubilee", "Jubilee", "tube", "Stanmore", 29, "Westbound - Platform 13")),
            now,
        ),
    )

    private val sequences = mapOf(
        "windrush" to LineSequence(
            routes = listOf(
                LineRoute("Highbury ↔ Crystal Palace", listOf("910GHGHI", "910GWCHAPEL", "910GCNDAW", "910GCRYSTLP")),
                LineRoute("Highbury ↔ West Croydon", listOf("910GHGHI", "910GWCHAPEL", "910GCNDAW", "910GWCROYDN")),
            ),
            stopNames = mapOf(
                "910GHGHI" to "Highbury & Islington",
                "910GWCHAPEL" to "Whitechapel",
                "910GCNDAW" to "Canada Water",
                "910GCRYSTLP" to "Crystal Palace",
                "910GWCROYDN" to "West Croydon",
            ),
        ),
        "mildmay" to LineSequence(
            routes = listOf(LineRoute("Highbury ↔ Stratford", listOf("910GHGHI", "910GSTFD"))),
            stopNames = mapOf("910GHGHI" to "Highbury & Islington", "910GSTFD" to "Stratford (London)"),
        ),
        "elizabeth" to LineSequence(
            routes = listOf(LineRoute("Whitechapel ↔ Abbey Wood", listOf("910GWCHAPXR", "910GCANWHRF", "910GABWDXR"))),
            stopNames = mapOf("910GWCHAPXR" to "Whitechapel", "910GCANWHRF" to "Canary Wharf", "910GABWDXR" to "Abbey Wood"),
        ),
        "jubilee" to LineSequence(
            routes = listOf(
                LineRoute("Canada Water ↔ Stratford", listOf("940GZZLUCWR", "940GZZLUCYF", "940GZZLUSTD")),
                LineRoute("Stratford ↔ Stanmore", listOf("940GZZLUSTD", "940GZZLUCYF", "940GZZLUCWR", "940GZZLUSTM")),
            ),
            stopNames = mapOf(
                "940GZZLUCWR" to "Canada Water",
                "940GZZLUCYF" to "Canary Wharf",
                "940GZZLUSTD" to "Stratford",
                "940GZZLUSTM" to "Stanmore",
            ),
        ),
    )

    private val source = object : RouteSequenceSource {
        override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
            sequences.getValue(lineId)
    }

    private val planned = TripViewModel.State(
        routes = listOf(viaStratford, viaCanadaWater, viaWhitechapel),
        plannedAt = now,
        live = live,
        statuses = mapOf(
            "jubilee" to LineStatus("jubilee", 9, "Minor Delays"),
            "windrush" to LineStatus("windrush", LineStatus.GOOD_SERVICE, "Good Service"),
        ),
    )

    private fun show(
        state: TripViewModel.State,
        routeStops: RouteStopsRepository = RouteStopsRepository(source),
        menu: AppMenuActions? = null,
        access: Duration = Duration.ofMinutes(2),
    ) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                // No outer provider: the screen checks its trains against the repository it's given.
                TripScreen(
                    title = "To Canary Wharf",
                    state = state,
                    now = now,
                    access = access,
                    routeStops = routeStops,
                    onBack = {},
                    onRetry = {},
                    menu = menu,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun trip_routes() {
        show(planned)
        // Via Canada Water: the 3 min Windrush (a 2 min walk to it), 18 min on, 3 to change, the
        // Jubilee at 25, 2 min on: 27 min. Via Whitechapel misses the Elizabeth line at 18 for 24.
        composeRule.onNodeWithText("27 min · ~08:29").assertIsDisplayed()
        composeRule.onNodeWithText("28 min · ~08:30").assertIsDisplayed()
        composeRule.onNodeWithText("38 min · ~08:40").assertIsDisplayed()
        // Screen readers hear each first-leg time with its destination.
        composeRule.onAllNodesWithContentDescription(" min to ", substring = true).onFirst().assertExists()
        captureSnapshot("trip-routes.png")
    }

    // Three rides whose Planner train on the last is missed with no live one known, on a narrow
    // screen: the pills leave room beside them for a word of the arrival, not all of it.
    @Test
    @Config(qualifiers = "w411dp-h720dp-420dpi")
    fun a_route_arrival_that_does_not_fit_beside_its_pills_is_shown_whole() {
        val threeRides = TripRoute(
            listOf(
                leg("overground", "windrush", "Windrush", highbury, whitechapel, 3, 16, 6, change = 3),
                leg("elizabeth-line", "elizabeth", "Elizabeth line", whitechapelXr, canaryWharfXr, 19, 23, 2, change = 5),
                leg("tube", "jubilee", "Jubilee", canaryWharf, canadaWaterTube, 32, 34, 1),
            ),
        )
        show(planned.copy(routes = listOf(threeRides)))
        val text = composeRule.activity.getString(R.string.trip_arrival_unknown)
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        val layout = results.single()
        // One line, nothing cut: clipped at a word, "Arrival unknown" read as "Arrival" (and
        // "44 min · est. 10:29" as "44 min · est.").
        assertEquals(1, layout.lineCount)
        assertEquals(text.length, layout.getLineEnd(0))
        assertTrue("arrival ellipsized", !layout.isLineEllipsized(0))
        assertTrue("arrival clipped", layout.getLineRight(0) - layout.getLineLeft(0) <= layout.size.width + 1f)
    }

    // Under each card's header, a row per ride with where it gets off, so two routes on the same
    // lines read apart by where they change.
    @Test
    fun a_route_card_names_where_each_ride_gets_off() {
        show(planned.copy(routes = listOf(viaCanadaWater, viaWhitechapel)))
        val stops = composeRule.onAllNodes(hasTestTag("rideStops"), useUnmergedTree = true)
        stops.assertCountEquals(2)
        composeRule.onAllNodesWithText("Canada Water", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("Whitechapel", useUnmergedTree = true).onFirst().assertExists()
        composeRule.onAllNodesWithText("Canary Wharf", useUnmergedTree = true).assertCountEquals(2)
        // A later ride says how often its line runs, from its live trains: the Elizabeth line at 14,
        // 18 and 24 is every 4 to 6 minutes. The Jubilee, with one train known, says nothing.
        // ↻ for "every", to save width, read out as the word.
        composeRule.onAllNodesWithText("↻ 4–6 min", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("↻", substring = true, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Every 4 to 6 min", useUnmergedTree = true).assertCountEquals(1)
        // Above the rides, the walk to where each starts: 2 min, so a train sooner than that reads
        // as grayed for a reason. It takes the place of "From ‹stop›" in the top row.
        composeRule.onAllNodes(hasTestTag("walkToStart"), useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("Walk to Highbury & Islington (~2 min)", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("From", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    // A first stop right there has no walk to show: the top row says where the trip starts instead.
    @Test
    fun a_route_card_with_no_walk_says_where_it_starts() {
        show(planned.copy(routes = listOf(viaCanadaWater, viaWhitechapel)), access = Duration.ZERO)
        composeRule.onAllNodes(hasTestTag("walkToStart"), useUnmergedTree = true).assertCountEquals(0)
        // "From" drawn beside the stop, not in it, so a narrow row cuts the stop's name, never "From".
        composeRule.onAllNodesWithText("From ", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("Highbury & Islington", useUnmergedTree = true).assertCountEquals(2)
    }

    // A trip's stop names shorten as the main screen's destinations do: whole words first, each
    // part of a slash-separated name alike, the full name kept for a screen reader.
    @Test
    fun a_stop_name_shortens_before_it_is_cut() {
        val name = "Shepherd's Bush Market / Wood Lane"
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.foundation.layout.Column {
                    Box(Modifier.width(400.dp).testTag("wide")) { ShortenedName(name, androidx.compose.material3.MaterialTheme.typography.bodyLarge) }
                    Box(Modifier.width(160.dp).testTag("narrow")) { ShortenedName(name, androidx.compose.material3.MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        composeRule.onNode(hasText(name) and hasAnyAncestor(hasTestTag("wide")), useUnmergedTree = true).assertExists()
        val narrow = composeRule.onNode(hasText("Wood Ln") and hasAnyAncestor(hasTestTag("narrow")), useUnmergedTree = true)
        narrow.assertExists()
        // Too narrow even for the floor, each place elides on its own: "Wood Ln" is drawn whole,
        // not lost behind one trailing "…". The full name stays the screen-reader label.
        composeRule.onNode(hasTestTag("narrow"), useUnmergedTree = true).onChild().assert(hasContentDescription(name))
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNode(hasText("Wood Ln") and hasAnyAncestor(hasTestTag("narrow")), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        assertTrue("Wood Ln elided", !results.single().isLineEllipsized(0))
    }

    // A slash-separated name with nothing to shorten still splits, rather than eliding once at the end.
    @Test
    fun a_stop_name_with_nothing_to_shorten_still_keeps_each_place() {
        val name = "Kensington / Hammersmith"
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Box(Modifier.width(120.dp).testTag("narrow")) { ShortenedName(name, androidx.compose.material3.MaterialTheme.typography.bodyLarge) }
            }
        }
        // Each place is its own text, so neither is lost behind the other's "…".
        composeRule.onNode(hasText("Kensington") and hasAnyAncestor(hasTestTag("narrow")), useUnmergedTree = true).assertExists()
        composeRule.onNode(hasText("Hammersmith") and hasAnyAncestor(hasTestTag("narrow")), useUnmergedTree = true).assertExists()
        composeRule.onNode(hasTestTag("narrow"), useUnmergedTree = true).onChild().assert(hasContentDescription(name))
    }

    // The arrival is as large as the first ride's times (maintainer, 2026-09-27): when the trip gets
    // there matters as much as when it leaves.
    @Test
    fun a_route_cards_arrival_is_as_large_as_its_times() {
        show(planned)
        fun fontSizeOf(node: androidx.compose.ui.test.SemanticsNodeInteraction): androidx.compose.ui.unit.TextUnit {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
            return results.single().layoutInput.style.fontSize
        }
        val arrival = fontSizeOf(composeRule.onAllNodes(hasTestTag("tripArrival"), useUnmergedTree = true).onFirst())
        val times = fontSizeOf(composeRule.onAllNodes(hasTestTag("firstRideTimes"), useUnmergedTree = true).onFirst())
        assertEquals(times, arrival)
    }

    @Test
    fun an_arrivals_range_ends_in_minutes_within_its_hour() {
        val arrival = Instant.parse("2026-09-26T10:26:00Z") // 11:26 in London
        assertEquals("34", arrivalEnd(arrival, Duration.ofMinutes(8)))
        assertEquals("12:04", arrivalEnd(Instant.parse("2026-09-26T10:56:00Z"), Duration.ofMinutes(8)))
        // Under three minutes it's no range at all.
        assertEquals(null, arrivalEnd(arrival, Duration.ofMinutes(2)))
        assertEquals("29", arrivalEnd(arrival, Duration.ofMinutes(3)))
        // Across the autumn clock change the hour repeats: 01:58 BST + 8 min is 01:06 GMT, shown whole.
        assertEquals("01:06", arrivalEnd(Instant.parse("2026-10-25T00:58:00Z"), Duration.ofMinutes(8)))
    }

    @Test
    fun a_route_cards_first_ride_times_are_part_of_the_card() {
        show(planned.copy(statuses = planned.statuses + ("windrush" to LineStatus("windrush", 6, "Severe Delays"))))
        // The disrupted Windrush warns beside each card's pill: two cards start on the Windrush.
        assertEquals(2, composeRule.onAllNodesWithContentDescription("Severe Delays").fetchSemanticsNodes().size)
        captureSnapshot("trip-routes-disrupted.png")
        // The first ride's times are part of the card (maintainer, 2026-09-27): none opens its line's
        // page, and tapped, they open the card's route.
        val details = composeRule.activity.getString(R.string.departure_details)
        composeRule.onAllNodes(
            SemanticsMatcher("opens its line's page") { it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == details },
        ).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(" min to ", substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripRoutes")).assertCountEquals(0)
        composeRule.onNodeWithTag("tripLegs").assertExists()
    }

    @Test
    fun a_route_cards_long_press_offers_every_legs_mode() {
        val hidden = mutableListOf<String>()
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, onHideMode = { hidden += it },
                )
            }
        }
        composeRule.waitForIdle()
        // Anywhere on a card, a long press opens the card's menu: one per card.
        val more = composeRule.activity.getString(R.string.more_actions)
        val menus = composeRule.onAllNodes(
            SemanticsMatcher("long-presses to its menu") { it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == more },
        )
        assertEquals(3, menus.fetchSemanticsNodes().size)
        // The first card rides the Windrush then the Jubilee: both groups, not just the first leg's.
        menus.onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hide all train services").assertIsDisplayed()
        composeRule.onNodeWithText("Hide all Tube & DLR services").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("tube"), hidden)
        // Each leg's line too, by itself.
        menus.onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hide Windrush line").assertIsDisplayed()
        composeRule.onNodeWithText("Hide Jubilee line").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("tube", HiddenModes.lineKey("jubilee", "Jubilee line")), hidden)
    }

    @Test
    fun trip_route_legs() {
        show(planned)
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        composeRule.onNodeWithText("┊  3 min to change").assertIsDisplayed()
        composeRule.onNodeWithText("2 stops to Canary Wharf").assertIsDisplayed()
        captureSnapshot("trip-route-legs.png")
    }

    @Test
    fun an_open_route_starts_on_the_way() {
        var started: TripRoute? = null
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    onStart = { started = it },
                )
            }
        }
        // The list itself offers no Start: only an open route does.
        composeRule.onNodeWithText("Start").assertDoesNotExist()
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start").performClick()
        composeRule.runOnIdle { assertEquals(viaCanadaWater, started) }
    }

    @Test
    fun start_waits_while_the_origin_is_found_again() {
        // A re-locate in flight: the route may change with the new fix, so it can't be started yet.
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    relocating = true, onStart = {},
                )
            }
        }
        // Its times read as estimates meanwhile.
        composeRule.onAllNodesWithText("27 min", substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test
    fun a_route_with_a_national_rail_train_says_it_cant_be_followed() {
        // The same route, its first train a National Rail one: its departures name no train to follow.
        val byRail = TripRoute(listOf(viaCanadaWater.legs[0].copy(mode = "national-rail"), viaCanadaWater.legs[1]))
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned.copy(routes = listOf(byRail)), now = now,
                    access = Duration.ofMinutes(2), routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    onStart = {},
                )
            }
        }
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start").assertDoesNotExist()
        composeRule.onNodeWithText("Can't follow National Rail trains yet").assertIsDisplayed()
    }

    @Test
    fun a_trip_already_on_the_way_is_opened_not_replaced() {
        var started = false
        var opened = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    onStart = { started = true }, onOpenTrip = { opened = true },
                )
            }
        }
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start").assertDoesNotExist()
        composeRule.onNodeWithText("Open current trip").performClick()
        composeRule.runOnIdle {
            assertTrue(opened)
            assertFalse(started)
        }
    }

    @Test
    fun trip_leg_opens_its_line() {
        show(planned)
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        // The Jubilee leg's row opens the line's page, with its service alert, as the main screen's does.
        composeRule.onNodeWithText("Stratford").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
        composeRule.onAllNodesWithText("Minor Delays", substring = true).onFirst().assertExists()
        captureSnapshot("trip-leg-line.png")
    }

    @Test
    fun a_leg_page_dismisses_its_line_alert_as_the_list_does() {
        val dismissedRows = mutableListOf<DepartureRow>()
        val dismissed = androidx.compose.runtime.mutableStateOf(emptySet<DismissedAlert>())
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    dismissed = dismissed.value, onDismissAlert = { dismissedRows += it },
                )
            }
        }
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stratford").performClick()
        composeRule.waitForIdle()
        // The Jubilee's Minor Delays carries the list page's ×, dismissing it line-wide.
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.alert_dismiss)).performClick()
        assertEquals(listOf("jubilee"), dismissedRows.map { it.lineId })
        // Once dismissed, the page keeps its times and says so, rather than claim a clean line.
        dismissed.value = setOf(DismissedAlert.ofLineStatus(planned.statuses.getValue("jubilee")))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_alert_dismissed)).assertExists()
    }

    @Test
    fun a_no_trains_leg_whose_alert_was_dismissed_still_opens() {
        // No Jubilee trains at Canada Water, and its Minor Delays dismissed: the leg still opens its
        // line's stops, saying the alert was dismissed rather than dropping the page.
        val dismissed = setOf(DismissedAlert.ofLineStatus(planned.statuses.getValue("jubilee")))
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned.copy(live = planned.live - canadaWaterTube.first), now = now,
                    access = Duration.ofMinutes(2), routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    dismissed = dismissed, onDismissAlert = {},
                )
            }
        }
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Jubilee"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_alert_dismissed)).assertExists()
    }

    @Test
    fun a_leg_opened_after_a_failed_status_check_claims_nothing() {
        // The last statuses are held, but the latest check failed: the line's page can't vouch for it.
        show(planned.copy(statusFailed = true))
        composeRule.onNodeWithText("27 min · ~08:29", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stratford").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.disruptions_unknown)).assertExists()
    }

    @Test
    fun a_leg_with_no_trains_opens_to_its_stops() {
        // No live trains at Canada Water: the Jubilee leg's row opens its line's stops all the same.
        show(planned.copy(live = planned.live - canadaWaterTube.first))
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(1)
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Jubilee"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
        composeRule.onNodeWithText("Canary Wharf").assertExists()
    }

    @Test
    fun a_dismissed_line_alert_leaves_the_trip_cards_as_it_leaves_the_list() {
        val severe = LineStatus("windrush", 6, "Severe Delays")
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned.copy(statuses = planned.statuses + ("windrush" to severe)),
                    now = now, access = Duration.ofMinutes(2), routeStops = RouteStopsRepository(source),
                    onBack = {}, onRetry = {}, dismissed = setOf(DismissedAlert.ofLineStatus(severe)),
                )
            }
        }
        composeRule.waitForIdle()
        // Dismissed: neither the pill nor the line row warns, as the list's rows don't.
        composeRule.onAllNodesWithContentDescription("Severe Delays").assertCountEquals(0)
        // The trains still show.
        composeRule.onAllNodesWithContentDescription(" min to ", substring = true).onFirst().assertExists()
    }

    @Test
    fun a_leg_with_no_trains_offers_hide_as_the_list_does() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned.copy(live = planned.live - canadaWaterTube.first),
                    now = now, access = Duration.ofMinutes(2), routeStops = RouteStopsRepository(source),
                    onBack = {}, onRetry = {}, onHideMode = {},
                )
            }
        }
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        // The Jubilee leg, with no trains to show, still carries the list row's long-press menu.
        val more = composeRule.activity.getString(R.string.more_actions)
        composeRule.onNode(
            hasAnyDescendant(hasContentDescription("Jubilee")) and
                SemanticsMatcher("long-presses to its menu") { it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == more },
        ).assertExists()
    }

    @Test
    fun a_hide_that_did_not_save_is_said_on_the_trip() {
        var acknowledged = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    hiddenModesWriteFailed = true, onHiddenModesWriteFailureShown = { acknowledged++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.hidden_modes_write_failed)).assertExists()
        assertEquals(1, acknowledged)
    }

    @Test
    fun a_no_trains_leg_whose_route_is_unknown_logs_why() {
        val warnings = mutableListOf<String>()
        // TfL knows no route for the line: the page says "unavailable", and the log says why, once.
        val unknown = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                    if (lineId == "jubilee") throw TflException.NotFound(null) else sequences.getValue(lineId)
            },
            warn = { warnings += it },
        )
        show(planned.copy(live = planned.live - canadaWaterTube.first), routeStops = unknown)
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Jubilee"))).performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf("route stops unavailable for line jubilee at stop ${canadaWaterTube.first}: line not known to TfL"),
            warnings.filter { it.startsWith("route stops unavailable") },
        )
    }

    @Test
    fun a_leg_whose_refresh_failed_opens_as_stale() {
        // The Jubilee stop's last refresh failed: its held arrivals open with the stale caveat.
        val failed = planned.live + (canadaWaterTube.first to planned.live.getValue(canadaWaterTube.first).copy(failed = true))
        show(planned.copy(live = failed))
        composeRule.onNodeWithText("27 min · ~08:29", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stratford").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_status_stale)).assertExists()
    }

    @Test
    fun a_no_trains_page_closes_once_trains_come() {
        val trip = androidx.compose.runtime.mutableStateOf(planned.copy(live = planned.live - canadaWaterTube.first))
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = trip.value, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                )
            }
        }
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Jubilee"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
        // Its trains come: the page opened from the no-trains row closes, back to the route.
        trip.value = planned
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(1)
    }

    @Test
    fun a_failed_replan_shows_over_an_open_route() {
        show(planned.copy(planError = DeparturesUiState.Error.Kind.OFFLINE))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't plan the trip: You're offline").assertIsDisplayed()
    }

    @Test
    fun a_first_ride_with_no_train_yet_says_its_times_are_loading() {
        show(planned.copy(routes = listOf(viaStratford, viaCanadaWater), live = emptyMap()))
        // The boarding stop's arrivals aren't in yet: the first ride's times say so rather than show
        // a dash, beside the stop it gets off at.
        composeRule.onAllNodes(hasText("Loading") and hasAnyAncestor(hasTestTag("rideStops")), useUnmergedTree = true)
            .assertCountEquals(2)
        composeRule.onAllNodesWithText("Canada Water", useUnmergedTree = true).onFirst().assertExists()
    }

    @Test
    fun trip_shared_first_leg() {
        // Either bus from one stop to Canada Water, then the Jubilee: one card, a cut 47/188 pill, and
        // both buses' times together on the first ride's row (maintainer, 2026-09-27).
        val busStop = "490000001A" to "Surrey Docks"
        val busStation = "490000002B" to "Canada Water Bus Station"
        fun bus(line: String, departs: Long) = TripRoute(
            listOf(
                leg("bus", line, line, busStop, busStation, departs, departs + 6, 3, change = 4),
                leg("tube", "jubilee", "Jubilee", canadaWaterTube, canaryWharf, departs + 11, departs + 13, 1),
            ),
        )
        val busRoute = { line: String -> LineRoute(line, listOf(busStop.first, "stop0", "stop1", busStation.first)) }
        val busSequences = sequences + mapOf(
            "47" to LineSequence(routes = listOf(busRoute("47")), stopNames = mapOf(busStop.first to "Surrey Docks", busStation.first to "Canada Water")),
            "188" to LineSequence(routes = listOf(busRoute("188")), stopNames = mapOf(busStop.first to "Surrey Docks", busStation.first to "Canada Water")),
        )
        val busLive = live + (
            busStop.first to TripViewModel.StopLive(
                listOf(
                    train("47", "47", "bus", "Catford", 4, "Stop A"),
                    train("188", "188", "bus", "North Greenwich", 6, "Stop A"),
                    train("47", "47", "bus", "Catford", 12, "Stop A"),
                    train("188", "188", "bus", "North Greenwich", 15, "Stop A"),
                ),
                now,
            )
            )
        show(
            planned.copy(
                routes = listOf(bus("47", 4), bus("188", 6), viaWhitechapel),
                live = busLive,
                statuses = planned.statuses + listOf("47", "188").associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") },
            ),
            routeStops = RouteStopsRepository(
                object : RouteSequenceSource {
                    override suspend fun routeSequence(lineId: String, direction: String): LineSequence = busSequences.getValue(lineId)
                },
            ),
        )
        composeRule.onNodeWithContentDescription("47 or 188").assertIsDisplayed()
        // The soonest three the rider can reach (a 2 min walk), whichever bus: 47, 188, 47.
        composeRule.onNodeWithText("4 · 6 · 12 min", useUnmergedTree = true).assertExists()
        // A screen reader still hears where each goes.
        composeRule.onAllNodesWithContentDescription("Catford", substring = true, useUnmergedTree = true).onFirst().assertExists()
        composeRule.onAllNodesWithContentDescription("North Greenwich", substring = true, useUnmergedTree = true).onFirst().assertExists()
        captureSnapshot("trip-shared-first-leg.png")
    }

    @Test
    fun a_cut_pill_warns_of_each_disrupted_line() {
        val busStop = "490000001A" to "Surrey Docks"
        val busStation = "490000002B" to "Canada Water Bus Station"
        fun bus(line: String, departs: Long) = TripRoute(
            listOf(
                leg("bus", line, line, busStop, busStation, departs, departs + 6, 3, change = 4),
                leg("tube", "jubilee", "Jubilee", canadaWaterTube, canaryWharf, departs + 11, departs + 13, 1),
            ),
        )
        show(
            planned.copy(
                routes = listOf(bus("47", 4), bus("188", 6)),
                statuses = planned.statuses + mapOf(
                    "47" to LineStatus("47", 9, "Minor Delays"),
                    "188" to LineStatus("188", 6, "Severe Delays"),
                ),
            ),
            // The buses' routes aren't needed here: none loads, and the pill stands on the plan.
            routeStops = RouteStopsRepository(
                object : RouteSequenceSource {
                    override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                        sequences[lineId] ?: LineSequence(routes = emptyList(), stopNames = emptyMap())
                },
            ),
        )
        // One ⚠ beside the cut pill, reading out both lines' details.
        composeRule.onNodeWithContentDescription("47: Minor Delays; 188: Severe Delays").assertExists()
    }

    @Test
    fun a_cut_pill_reads_as_its_combined_label() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Box(Modifier.semantics(mergeDescendants = true) {}.testTag("row")) {
                    SharedLinePill(listOf(LineRef("47", "47", "bus"), LineRef("188", "188", "bus")), "47 or 188")
                }
            }
        }
        // A screen reader hears "47 or 188" once, not the segments' codes as well.
        val config = composeRule.onNodeWithTag("row").fetchSemanticsNode().config
        assertEquals(listOf("47 or 188"), config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() })
        assertTrue(config.getOrElse(SemanticsProperties.Text) { emptyList() }.isEmpty())
    }

    @Test
    fun a_cut_pill_keeps_its_lines_in_order_right_to_left() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SharedLinePill(listOf(LineRef("victoria", "Victoria", "tube"), LineRef("central", "Central", "tube")), "either")
                }
            }
        }
        // Painted left to right, so the first line's code stays over its own (left) segment.
        val first = composeRule.onNodeWithText("VIC", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithText("CEN", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(first.left < second.left)
    }

    @Test
    fun a_cut_pill_fits_the_room_it_has() {
        val lines = listOf("47", "188", "199", "225", "381", "N1").map { LineRef(it, it, "bus") }
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Box(Modifier.width(120.dp)) {
                    SharedLinePill(lines, "any", Modifier.testTag("cut"))
                }
            }
        }
        // Six lines in 120dp: every segment shrinks alike, so each label stays over its own color
        // rather than the last few being squeezed out.
        val bounds = composeRule.onNodeWithTag("cut").getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left <= 120.dp)
        val widths = lines.map { line ->
            composeRule.onNodeWithText(line.name, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.right - it.left }
        }
        assertTrue(widths.all { it > 0.dp })
        assertTrue(widths.maxOf { it.value } - widths.minOf { it.value } < 1f)
    }

    @Test
    fun a_trip_has_the_app_overflow() {
        var reported = 0
        show(planned, menu = AppMenuActions(updateAvailable = true, onOpenAppListing = {}, onSendBugReport = { reported++ }, onOpenLicenses = {}))
        // The update dot, as on the list, and the menu's report and About.
        composeRule.onNodeWithTag(UPDATE_AVAILABLE_DOT_TAG, useUnmergedTree = true).assertExists()
        // The button and its dot; the open menu is a popup window of its own, which this capture of
        // the activity's window doesn't draw.
        captureSnapshot("trip-overflow.png")
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.menu_more)).performClick()
        val update = composeRule.onNodeWithText(composeRule.activity.getString(R.string.update_available))
            .getUnclippedBoundsInRoot()
        val about = composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_about))
            .getUnclippedBoundsInRoot()
        // "Update available" sits last, so the screen's own items keep their positions.
        assertTrue(update.top > about.top)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_send_bug_report)).performClick()
        assertEquals(1, reported)
    }

    @Test
    fun an_open_route_outlasts_the_screen_leaving() {
        val openRoute = mutableStateOf<String?>(null)
        val showing = mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                // Taken out of composition and back, as an overlay (the licenses) does.
                if (showing.value) {
                    TripScreen(
                        title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, openRoute = openRoute,
                    )
                }
            }
        }
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        showing.value = false
        composeRule.waitForIdle()
        showing.value = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
    }

    @Test
    fun an_open_route_is_restored_after_the_process_is_recreated() {
        val restoration = StateRestorationTester(composeRule)
        val openRoute = mutableStateOf<String?>(null)
        restoration.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, openRoute = openRoute,
                )
            }
        }
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        // A new trip model, as after a kill: it starts with no route open.
        openRoute.value = null
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
    }

    @Test
    fun trip_planning() {
        show(TripViewModel.State(planning = true))
        composeRule.onNodeWithText("Planning…").assertIsDisplayed()
        captureSnapshot("trip-planning.png")
    }

    @Test
    fun trip_plan_failed() {
        show(TripViewModel.State(planError = DeparturesUiState.Error.Kind.OFFLINE))
        composeRule.onNodeWithText("Couldn't plan the trip: You're offline").assertIsDisplayed()
        captureSnapshot("trip-plan-failed.png")
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        if (!capturing()) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
