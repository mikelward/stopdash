package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.Departure
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TripClosures
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.WalkingSpeed
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Duration
import java.time.Instant
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
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
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

    // Every stop [routes] board or get off at, checked with nothing to report, as a trip holds them
    // once its closure check has answered.
    private fun checkedOpen(vararg routes: TripRoute): Map<String, List<StopDisruption>> =
        routes.flatMap(TripClosures::ends).associate { it.id to emptyList() }

    private val planned = TripViewModel.State(
        routes = listOf(viaStratford, viaCanadaWater, viaWhitechapel),
        plannedAt = now,
        live = live,
        closures = checkedOpen(viaStratford, viaCanadaWater, viaWhitechapel),
        statuses = mapOf(
            "jubilee" to LineStatus("jubilee", 9, "Minor Delays"),
            "windrush" to LineStatus("windrush", LineStatus.GOOD_SERVICE, "Good Service"),
        ),
        statusesAt = mapOf("jubilee" to now, "windrush" to now),
    )

    private fun show(
        state: TripViewModel.State,
        routeStops: RouteStopsRepository = RouteStopsRepository(source),
        menu: AppMenuActions? = null,
        access: Duration = Duration.ofMinutes(2),
        ends: TripEnds? = null,
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
                    ends = ends,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun the_first_route_is_headed_fastest_and_the_rest_other() {
        // Every route here rides twice, so the first card is fastest, none is simplest, and the
        // other two share one "Other" header.
        show(planned)
        val headers = composeRule.onAllNodesWithTag("routeLabel")
        headers.assertCountEquals(2)
        headers[0].assertTextEquals("Fastest")
        headers[1].assertTextEquals("Other")
        headers[0].assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        headers[1].assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onAllNodesWithText("Simplest").assertCountEquals(0)
        // Fastest over the first card, the one arriving soonest; Other between it and the rest.
        val fastest = headers[0].getUnclippedBoundsInRoot()
        val other = headers[1].getUnclippedBoundsInRoot()
        val first = composeRule.onNodeWithText("27 min · ~08:29").getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithText("28 min · ~08:30").getUnclippedBoundsInRoot()
        val third = composeRule.onNodeWithText("38 min · ~08:40").getUnclippedBoundsInRoot()
        assertTrue(fastest.bottom <= first.top && first.bottom <= other.top && other.bottom <= second.top && second.top < third.top)
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

    @Test
    fun trip_routes_walking_speed() {
        // The walking speed heads the routes (maintainer, 2026-09-28), the max walk under it
        // (2026-09-30); a pick of either is reported to its setting.
        var chosen: WalkingSpeed? = null
        var chosenMaxWalk: MaxWalk? = null
        var chosenStepFree: StepFree? = null
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    walkingSpeed = WalkingSpeed.AVERAGE,
                    onWalkingSpeedChange = { chosen = it },
                    maxWalk = MaxWalk.THIRTY,
                    onMaxWalkChange = { chosenMaxWalk = it },
                    stepFree = StepFree.ANY,
                    onStepFreeChange = { chosenStepFree = it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Walking speed").assertIsDisplayed()
        composeRule.onNodeWithText("Max walk").assertIsDisplayed()
        composeRule.onNodeWithTag("maxWalk").assertContentDescriptionEquals("Max walk, 30 min")
        captureSnapshot("trip-routes-walking-speed.png")
        composeRule.onNodeWithTag("walkingSpeed").performClick()
        composeRule.onNodeWithTag("walkingSpeed-FAST").performClick()
        assertEquals(WalkingSpeed.FAST, chosen)
        // Every limit is offered, 60 minutes the longest.
        composeRule.onNodeWithTag("maxWalk").performClick()
        MaxWalk.entries.forEach { composeRule.onNodeWithTag("maxWalk-${it.name}").assertExists() }
        composeRule.onNodeWithText("60 min").assertIsDisplayed()
        composeRule.onNodeWithTag("maxWalk-SIXTY").performClick()
        assertEquals(MaxWalk.SIXTY, chosenMaxWalk)
        // Step-free under them: the maintainer's three levels, by their names, each but Any saying
        // what it's for, so a rider with luggage sees Station suits them too.
        composeRule.onNodeWithTag("stepFree").assertContentDescriptionEquals("Step-free, Any").performClick()
        StepFree.entries.forEach { composeRule.onNodeWithTag("stepFree-${it.name}").assertExists() }
        composeRule.onNodeWithTag("stepFree-ANY").assertTextEquals("Any")
        composeRule.onNodeWithTag("stepFree-STATION")
            .assertTextEquals("Station", "Street to platform, for luggage or a buggy")
        composeRule.onNodeWithTag("stepFree-FULLY").assertTextEquals("Fully", "Onto the train too, for a wheelchair")
        composeRule.onNodeWithTag("stepFree-FULLY").performClick()
        assertEquals(StepFree.FULLY, chosenStepFree)
    }

    @Test
    fun trip_walk_pickers_wait_for_the_stored_choices() {
        // Until the walking speed and max walk are read, neither picker shows a value or opens, so a
        // pick can't be saved over a choice not yet read.
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    onWalkingSpeedChange = {},
                    onMaxWalkChange = {},
                    onStepFreeChange = {},
                    onTripModesChange = {},
                    planOptionsLoaded = false,
                )
            }
        }
        composeRule.onNodeWithTag("walkingSpeed").assertIsNotEnabled().assertContentDescriptionEquals("Walking speed, –")
        composeRule.onNodeWithTag("maxWalk").assertIsNotEnabled().assertContentDescriptionEquals("Max walk, –")
        composeRule.onNodeWithTag("stepFree").assertIsNotEnabled().assertContentDescriptionEquals("Step-free, –")
        // No mode chip reads as riding, nor responds, until the rider's choice is read.
        ModeGroups.ALL.forEach { composeRule.onNodeWithTag("tripMode-${it.key}").assertIsNotEnabled().assertIsNotSelected() }
    }

    @Test
    fun trip_mode_chips() {
        // One chip per kind of transport under the pickers, by the list's hide-mode names, selected
        // while the trip rides it; a tap turns it off or on, but the last one riding stays on.
        val group = { key: String -> ModeGroups.ALL.single { it.key == key } }
        var modes by mutableStateOf(TripModes.DEFAULT.with(group("bus"), ride = false))
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    onWalkingSpeedChange = {},
                    onMaxWalkChange = {},
                    onStepFreeChange = {},
                    tripModes = modes,
                    onTripModesChange = { modes = it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tripMode-tube").assertTextEquals("Tube & DLR").assertIsSelected()
        composeRule.onNodeWithTag("tripMode-train").assertTextEquals("Train").assertIsSelected()
        composeRule.onNodeWithTag("tripMode-bus").assertTextEquals("Bus").assertIsNotSelected()
        ModeGroups.ALL.forEach { composeRule.onNodeWithTag("tripMode-${it.key}").assertExists() }
        captureSnapshot("trip-routes-modes.png")
        // Off, then on again.
        composeRule.onNodeWithTag("tripMode-train").performClick()
        assertEquals(TripModes(setOf("bus", "train")), modes)
        composeRule.onNodeWithTag("tripMode-train").assertIsNotSelected()
        composeRule.onNodeWithTag("tripMode-bus").performClick()
        assertEquals(TripModes(setOf("train")), modes)
        composeRule.onNodeWithTag("tripMode-bus").assertIsSelected()
        // Down to one: a tap on it changes nothing, since a trip riding nothing has no route.
        modes = ModeGroups.ALL.filter { it.key != "tram" }.fold(TripModes.DEFAULT) { m, g -> m.with(g, ride = false) }
        composeRule.onNodeWithTag("tripMode-tram").performClick()
        composeRule.onNodeWithTag("tripMode-tram").assertIsSelected()
        assertEquals(TripModes(ModeGroups.ALL.map { it.key }.toSet() - "tram"), modes)
    }

    @Test
    fun trip_from_to_bar() {
        // The routes keep the To… search's From/To bar (maintainer, 2026-09-28): each end a tap to change.
        var changedFrom = 0
        var changedTo = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    menu = AppMenuActions(updateAvailable = false, onOpenAppListing = {}, onSendBugReport = {}, onOpenLicenses = {}),
                    walkingSpeed = WalkingSpeed.AVERAGE,
                    onWalkingSpeedChange = {},
                    ends = TripEnds(fromStation = null, toName = "Canary Wharf", onChangeFrom = { changedFrom++ }, onChangeTo = { changedTo++ }),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("fromField").assertContentDescriptionEquals("From Here")
        composeRule.onNodeWithTag("toField").assertContentDescriptionEquals("To Canary Wharf")
        // The bar says where the trip goes, so the title doesn't say it again.
        composeRule.onAllNodesWithText("To Canary Wharf").assertCountEquals(0)
        // The app's overflow stays, at the From row's end.
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.menu_more)).assertIsDisplayed()
        captureSnapshot("trip-from-to.png")
        composeRule.onNodeWithTag("fromField").performClick()
        assertEquals(1, changedFrom)
        composeRule.onNodeWithTag("toField").performClick()
        assertEquals(1, changedTo)
    }

    @Test
    fun trip_from_to_bar_from_a_station() {
        // From the station the routes start at, so no walk to it.
        show(
            planned,
            access = Duration.ZERO,
            ends = TripEnds(fromStation = "Highbury & Islington", toName = "Canary Wharf", onChangeFrom = {}, onChangeTo = {}),
        )
        composeRule.onNodeWithTag("fromField").assertContentDescriptionEquals("From Highbury & Islington")
        captureSnapshot("trip-from-to-station.png")
    }

    @Test
    fun pulling_the_routes_down_asks_for_them_again() {
        var pulled = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    onPullRefresh = { pulled++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tripRoutes").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertEquals(1, pulled)
        // The routes stay up while the new plan and times come in.
        composeRule.onNodeWithText("28 min · ~08:30").assertIsDisplayed()
        // An open route is one choice already made: no pull there, its times refresh on the tick.
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        composeRule.onAllNodesWithTag("tripRoutesPull").assertCountEquals(0)
    }

    @Test
    fun an_open_route_is_titled_and_back_returns_to_the_from_to_bar() {
        show(planned, ends = TripEnds(fromStation = null, toName = "Canary Wharf", onChangeFrom = {}, onChangeTo = {}))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        // One route open: its legs under the trip's title, not ends to change.
        composeRule.onNodeWithText("To Canary Wharf").assertIsDisplayed()
        composeRule.onAllNodesWithTag("tripEndsBar").assertCountEquals(0)
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.action_back)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tripEndsBar").assertIsDisplayed()
    }

    @Test
    fun an_open_route_offers_the_walking_speed_too() {
        // Its walks are timed at the speed as the list's are, so it can be changed from there.
        var chosen: WalkingSpeed? = null
        val state = mutableStateOf(planned)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = state.value,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    walkingSpeed = WalkingSpeed.AVERAGE,
                    onWalkingSpeedChange = { chosen = it },
                )
            }
        }
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        composeRule.onNodeWithText("Walking speed").assertIsDisplayed()
        captureSnapshot("trip-route-legs-walking-speed.png")
        composeRule.onNodeWithTag("walkingSpeed").performClick()
        composeRule.onNodeWithTag("walkingSpeed-SLOW").performClick()
        assertEquals(WalkingSpeed.SLOW, chosen)
        // The pick plans again: no routes while it runs, then the new plan, which still offers the
        // route through Whitechapel, so it opens again.
        state.value = planned.copy(routes = null, planning = true)
        composeRule.waitForIdle()
        state.value = planned
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
    }

    @Test
    fun an_open_route_a_new_plan_drops_stays_closed_when_a_later_plan_offers_it_again() {
        val state = mutableStateOf(planned)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = state.value,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    onWalkingSpeedChange = {},
                )
            }
        }
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        // A finished plan without the route: back to the list.
        state.value = planned.copy(routes = listOf(viaStratford, viaCanadaWater))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertDoesNotExist()
        // A later plan offering it again (the old pace's plan, say) leaves the rider on the list.
        state.value = planned
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertDoesNotExist()
        composeRule.onNodeWithText("28 min · ~08:30").assertIsDisplayed()
    }

    @Test
    fun a_walking_speed_that_did_not_save_is_said_once() {
        var shown = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    onWalkingSpeedChange = {},
                    walkingSpeedWriteFailed = true,
                    onWalkingSpeedWriteFailureShown = { shown++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Couldn't save that", substring = true).assertIsDisplayed()
        assertEquals(1, shown)
    }

    // Three rides whose Planner train on the last is missed with no live one known, on a narrow
    // screen: the pills leave room beside them for a word of the arrival, not all of it.
    @Test
    @Config(qualifiers = "en-rGB-w411dp-h720dp-420dpi")
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
        // Any whole minute is a range, so the order estimates rank in can be read off them; under a
        // minute there's none.
        assertEquals(null, arrivalEnd(arrival, Duration.ofSeconds(50)))
        assertEquals("27", arrivalEnd(arrival, Duration.ofMinutes(1)))
        assertEquals("28", arrivalEnd(arrival, Duration.ofMinutes(2)))
        // Across the autumn clock change the hour repeats: 01:58 BST + 8 min is 01:06 GMT, shown whole.
        assertEquals("01:06", arrivalEnd(Instant.parse("2026-10-25T00:58:00Z"), Duration.ofMinutes(8)))
    }

    // Real predictions leave seconds on both halves: 16m30s away with 1m40s of slack is 18m10s at the
    // latest, so the range reads 16–18 min beside 11:16–18, not 16–17.
    @Test
    fun an_arrivals_range_rounds_its_latest_minutes_once() {
        val duration = Duration.ofMinutes(16).plusSeconds(30)
        val slack = Duration.ofMinutes(1).plusSeconds(40)
        assertEquals(18, latestMinutes(duration, slack))
        assertEquals(16, latestMinutes(duration, Duration.ZERO))
        assertEquals(3, latestMinutes(null, Duration.ofMinutes(3)))
        val arrival = Instant.parse("2026-09-26T10:16:30Z") // 11:16:30 in London
        assertEquals("18", arrivalEnd(arrival, slack))
    }

    @Test
    fun a_route_cards_first_ride_times_are_part_of_the_card() {
        show(planned.copy(statuses = planned.statuses + ("windrush" to LineStatus("windrush", 6, "Severe Delays"))))
        // The disrupted Windrush warns on each card's row: two cards start on the Windrush.
        assertEquals(2, composeRule.onAllNodesWithContentDescription("Severe Delays").fetchSemanticsNodes().size)
        val glyphs = composeRule.onAllNodesWithContentDescription("Severe Delays", useUnmergedTree = true).fetchSemanticsNodes()
        // Just before the row's times, as the main screen puts it (maintainer, 2026-09-28): each ⚠
        // ends left of the times on its own row, past the row's middle, after the stop's name.
        val times = composeRule.onAllNodesWithTag("firstRideTimes", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        val width = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        glyphs.map { it.boundsInRoot }.forEach { glyph ->
                val row = times.single { it.top < glyph.bottom && glyph.top < it.bottom }
            assertTrue(glyph.right <= row.left)
            assertTrue(glyph.left > width / 2)
        }
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
    fun a_route_cards_warning_is_only_for_the_way_its_ride_goes() {
        // The Windrush's delays are for trains the other way: the ride's trains go outbound (train()).
        val severe = LineStatus("windrush", 6, "Severe Delays")
        val good = LineStatus("windrush", LineStatus.GOOD_SERVICE, "Good Service")
        show(planned.copy(statuses = planned.statuses + ("windrush" to severe.copy(byDirection = mapOf("inbound" to severe, "outbound" to good)))))
        composeRule.onAllNodesWithContentDescription("Severe Delays").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(" min to ", substring = true).onFirst().assertExists()
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
    fun a_line_avoided_from_a_cards_long_press_leaves_its_routes_out_until_its_chip_is_tapped() {
        var avoided by mutableStateOf(emptySet<String>())
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, onHideMode = {},
                    onTripModesChange = {},
                    avoidedLines = avoided,
                    onAvoidLine = { avoided = avoided + it },
                    onStopAvoiding = { avoided = avoided - it },
                )
            }
        }
        composeRule.waitForIdle()
        val more = composeRule.activity.getString(R.string.more_actions)
        val menus = composeRule.onAllNodes(
            SemanticsMatcher("long-presses to its menu") { it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == more },
        )
        assertEquals(3, menus.fetchSemanticsNodes().size)
        // Nothing avoided, no chips.
        composeRule.onNodeWithTag("avoidedLines").assertDoesNotExist()
        // The first card rides the Windrush then the Jubilee: each can be avoided, beside its hide.
        menus.onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Avoid Windrush line").assertIsDisplayed()
        composeRule.onNodeWithText("Avoid Jubilee line").performClick()
        composeRule.waitForIdle()
        val jubilee = AvoidedLines.key("jubilee", "Jubilee line")
        assertEquals(setOf(jubilee), avoided)
        // Both routes riding it are left out, and its chip atop the routes says so.
        assertEquals(1, menus.fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("avoidedLines").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop avoiding Jubilee line").assertIsDisplayed()
        captureSnapshot("trip-routes-avoiding.png")
        // A tap on the chip stops avoiding it: the routes come back, and the chips go.
        composeRule.onNodeWithContentDescription("Stop avoiding Jubilee line").performClick()
        composeRule.waitForIdle()
        assertEquals(emptySet<String>(), avoided)
        assertEquals(3, menus.fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("avoidedLines").assertDoesNotExist()
    }

    @Test
    fun an_avoided_line_is_no_hidden_line_on_a_trip() {
        // Avoided, the Jubilee leaves the trip by its own chip: the hidden banner, and its Show all,
        // are for what's hidden from every list.
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    avoidedLines = setOf(AvoidedLines.key("jubilee", "Jubilee line")),
                    onStopAvoiding = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.modes_show_all)).assertDoesNotExist()
        composeRule.onNodeWithText("Avoiding").assertIsDisplayed()
        // Only the route by the Elizabeth line is left.
        composeRule.onAllNodesWithText("Jubilee", substring = true).assertCountEquals(1)
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
    fun an_open_routes_later_leg_says_how_often_its_line_runs() {
        show(planned)
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 stops to Canary Wharf").assertIsDisplayed()
        // The Elizabeth line, ridden after the change, says how often it runs instead of counting
        // down (maintainer, 2026-09-29): its trains at 14, 18 and 24 are every 4 to 6 minutes, the
        // figure its row on the list's card gives, and none of its countdowns show.
        composeRule.onAllNodesWithText("↻ 4–6 min", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Every 4 to 6 min", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("14 · 18 · 24 min", useUnmergedTree = true).assertCountEquals(0)
        // The next ride still counts down, reached after the 2 min walk: its train in 3 min is caught,
        // nothing grays, and its times are read as shown.
        assertEquals(emptyList<String>(), grayedTimes("3 · 11 min"))
        assertTrue(SemanticsProperties.ContentDescription !in composeRule.onNodeWithText("3 · 11 min").fetchSemanticsNode().config)
        composeRule.onAllNodesWithText("↻", substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun an_open_routes_next_ride_counts_down_after_a_walk_to_it() {
        // A route starting with a walk leg of the Planner's own: the ride after it is the rider's next,
        // so it counts down, and its trains gone before the rider gets there are grayed.
        val walkFirst = TripRoute(
            listOf(
                TripLeg(
                    mode = "walking", lineId = "", lineName = "", fromId = "", fromName = "Here",
                    toId = whitechapelXr.first, toName = whitechapelXr.second, departure = now, arrival = at(16),
                ),
                leg("elizabeth-line", "elizabeth", "Elizabeth line", whitechapelXr, canaryWharfXr, 19, 23, 2),
            ),
        )
        show(planned.copy(routes = listOf(walkFirst)), access = Duration.ZERO)
        composeRule.onNodeWithTag("rideStops", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 stops to Canary Wharf").assertIsDisplayed()
        // On the Elizabeth line's platform at 16: its train in 14 min leaves too soon.
        assertEquals(listOf("14"), grayedTimes("14 · 18 · 24 min"))
        composeRule.onAllNodesWithText("↻", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun an_open_routes_later_leg_with_too_few_trains_known_shows_no_times() {
        show(planned)
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 stop to Canary Wharf").assertIsDisplayed()
        // One Jubilee train known at Canada Water says nothing of how often the line runs, and its
        // countdown would say nothing the rider can use there: the row shows neither.
        composeRule.onAllNodesWithText("↻", substring = true, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("25 min", useUnmergedTree = true).assertCountEquals(0)
    }

    // The times a countdown shows grayed: those it styles apart from the rest of its label.
    private fun grayedTimes(label: String): List<String> {
        val text = composeRule.onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        return text.spanStyles.map { text.text.substring(it.start, it.end) }
    }

    @Test
    fun an_open_route_stays_open_when_six_faster_routes_crowd_it_out_of_the_list() {
        val state = mutableStateOf(planned)
        showWith(state)
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        // A settled plan with six routes arriving sooner: the list would time only those, but the
        // open route is still offered, so it stays timed and open.
        val faster = (1..TripViewModel.MAX_ROUTES).map { i ->
            TripRoute(listOf(leg("tube", "jubilee", "Jubilee", "940GFAST$i" to "Stop $i", canaryWharf, 3, 8L + i, 3)))
        }
        state.value = planned.copy(routes = faster + planned.routes.orEmpty())
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
    }

    @Test
    fun an_open_route_whose_mode_is_hidden_stays_closed_when_the_mode_is_shown_again() {
        val hidden = mutableStateOf(emptySet<String>())
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    hiddenModes = hidden.value,
                )
            }
        }
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        // Its Elizabeth line hidden: the route isn't shown, so the list is, and the route is closed.
        hidden.value = setOf("elizabeth-line")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertDoesNotExist()
        // Shown again, it's back in the list, not reopened over it.
        hidden.value = emptySet()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("28 min · ~08:30").assertIsDisplayed()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertDoesNotExist()
    }

    @Test
    fun an_open_train_through_a_change_shows_at_once_while_a_restore_reloads_its_route_data() {
        // Red Aston → Beck, change, blue on to Cole; blue to Dale also runs from Aston through Beck
        // to Cole, and one leaves in 5 min: a route of its own, with no change ([withThroughRoutes]).
        val names = listOf("Aston", "Beck", "Mead", "Cole", "Dale", "Red End").associateWith { it }
        val lines = mapOf(
            "red" to LineSequence(listOf(LineRoute("red", listOf("Aston", "Beck", "Red End"))), names),
            "blue" to LineSequence(listOf(LineRoute("to Dale", listOf("Aston", "Beck", "Mead", "Cole", "Dale"))), names),
        )
        val changing = TripRoute(
            listOf(
                leg("tube", "red", "Red", "Aston" to "Aston", "Beck" to "Beck", 1, 3, 1, change = 4),
                leg("tube", "blue", "Blue", "Beck" to "Beck", "Cole" to "Cole", 9, 15, 2),
            ),
        )
        val trip = TripViewModel.State(
            routes = listOf(changing),
            plannedAt = now,
            closures = checkedOpen(changing),
            live = mapOf(
                "Aston" to TripViewModel.StopLive(listOf(train("red", "Red", "tube", "Red End", 1, "Platform 1"), train("blue", "Blue", "tube", "Dale", 5, "Platform 2")), now),
                "Beck" to TripViewModel.StopLive(listOf(train("blue", "Blue", "tube", "Dale", 9, "Platform 2")), now),
            ),
            statuses = mapOf(
                "red" to LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service"),
                "blue" to LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service"),
            ),
        )
        val through = withThroughRoutes(trip, lines).routes.orEmpty().single { routeKey(it) != routeKey(changing) }
        val key = openRouteOf(through, trip, lines).encode()
        // Restored with the train through open, before its lines' route data has reloaded.
        val loads = kotlinx.coroutines.CompletableDeferred<Unit>()
        val routeStops = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    loads.await()
                    return lines.getValue(lineId)
                }
            },
        )
        val openRoute = mutableStateOf<String?>(key)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Cole", state = trip, now = now, access = Duration.ZERO,
                    routeStops = routeStops, onBack = {}, onRetry = {}, openRoute = openRoute,
                )
            }
        }
        composeRule.waitForIdle()
        // Kept whole, so shown at once: its trains still to come, as nothing has made it yet.
        assertEquals(key, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        loads.complete(Unit)
        composeRule.waitForIdle()
        assertEquals(key, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
    }

    @Test
    fun an_open_train_through_a_change_stays_open_while_its_stop_pairs_other_pole_answers() {
        // Red boards at a stop pair, Aston and Aston 2; blue to Dale runs through Beck to Cole from
        // Aston 2 alone, so it's that pole's arrivals that predict the train through.
        val names = listOf("Aston", "Aston2", "Beck", "Mead", "Cole", "Dale", "Red End").associateWith { it }
        val lines = mapOf(
            "red" to LineSequence(listOf(LineRoute("red", listOf("Aston", "Beck", "Red End"))), names),
            "blue" to LineSequence(listOf(LineRoute("to Dale", listOf("Aston2", "Beck", "Mead", "Cole", "Dale"))), names),
        )
        val changing = TripRoute(
            listOf(
                leg("tube", "red", "Red", "Aston" to "Aston", "Beck" to "Beck", 1, 3, 1, change = 4).copy(fromArea = "AstonPair"),
                leg("tube", "blue", "Blue", "Beck" to "Beck", "Cole" to "Cole", 9, 15, 2),
            ),
        )
        val statuses = mapOf(
            "red" to LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service"),
            "blue" to LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service"),
        )
        val throughTrain = "Aston2" to TripViewModel.StopLive(listOf(train("blue", "Blue", "tube", "Dale", 5, "Platform 2")), now)
        val refreshed = TripViewModel.State(
            routes = listOf(changing),
            plannedAt = now,
            closures = checkedOpen(changing),
            live = mapOf(
                "Aston" to TripViewModel.StopLive(listOf(train("red", "Red", "tube", "Red End", 1, "Platform 1")), now),
                "Beck" to TripViewModel.StopLive(listOf(train("blue", "Blue", "tube", "Dale", 9, "Platform 2")), now),
                throughTrain,
            ),
            statuses = statuses,
            areaPoles = mapOf("AstonPair" to listOf("Aston", "Aston2")),
        )
        val through = withThroughRoutes(refreshed, lines).routes.orEmpty().single { routeKey(it) != routeKey(changing) }
        val key = openRouteOf(through, refreshed, lines).encode()
        // Restored with the train through open: the Planner's own poles have answered (from the shared
        // arrivals), the other pole's not yet.
        val state = mutableStateOf(refreshed.copy(live = refreshed.live - throughTrain.first))
        val openRoute = mutableStateOf<String?>(key)
        val routeStops = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = lines.getValue(lineId)
            },
        )
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Cole", state = state.value, now = now, access = Duration.ZERO,
                    routeStops = routeStops, onBack = {}, onRetry = {}, openRoute = openRoute,
                )
            }
        }
        composeRule.waitForIdle()
        // No train through predicted yet: shown all the same, and not closed.
        assertEquals(key, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        state.value = refreshed
        composeRule.waitForIdle()
        assertEquals(key, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
    }

    // Red Aston → Beck, change, blue on to Cole; blue to Dale also runs from Aston through Beck to
    // Cole: a route of its own when one is predicted at Aston ([withThroughRoutes]). [throughIn] is
    // when that train leaves (none predicted when null), in arrivals fetched at [fetchedAt].
    private val throughNames = listOf("Aston", "Beck", "Mead", "Cole", "Dale", "Red End").associateWith { it }
    private val throughLines = mapOf(
        "red" to LineSequence(listOf(LineRoute("red", listOf("Aston", "Beck", "Red End"))), throughNames),
        "blue" to LineSequence(listOf(LineRoute("to Dale", listOf("Aston", "Beck", "Mead", "Cole", "Dale"))), throughNames),
    )
    private val throughChanging = TripRoute(
        listOf(
            leg("tube", "red", "Red", "Aston" to "Aston", "Beck" to "Beck", 1, 3, 1, change = 4),
            leg("tube", "blue", "Blue", "Beck" to "Beck", "Cole" to "Cole", 9, 15, 2),
        ),
    )

    private fun throughTrip(throughIn: Long?, fetchedAt: Instant = now) = TripViewModel.State(
        routes = listOf(throughChanging),
        plannedAt = now,
        closures = checkedOpen(throughChanging),
        live = mapOf(
            "Aston" to TripViewModel.StopLive(
                listOfNotNull(
                    train("red", "Red", "tube", "Red End", 1, "Platform 1"),
                    throughIn?.let { train("blue", "Blue", "tube", "Dale", it, "Platform 2") },
                ),
                fetchedAt,
            ),
            "Beck" to TripViewModel.StopLive(listOf(train("blue", "Blue", "tube", "Dale", 9, "Platform 2")), fetchedAt),
        ),
        statuses = mapOf(
            "red" to LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service"),
            "blue" to LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service"),
        ),
    )

    // The train through, open ([OpenRoute]), as tapped on the list while one was predicted.
    private val throughKey by lazy {
        val trip = throughTrip(throughIn = 5)
        val through = withThroughRoutes(trip, throughLines).routes.orEmpty().single { routeKey(it) != routeKey(throughChanging) }
        openRouteOf(through, trip, throughLines).encode()
    }

    private fun showThrough(state: androidx.compose.runtime.MutableState<TripViewModel.State>, openRoute: androidx.compose.runtime.MutableState<String?>) {
        val routeStops = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = throughLines.getValue(lineId)
            },
        )
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Cole", state = state.value, now = now, access = Duration.ZERO,
                    routeStops = routeStops, onBack = {}, onRetry = {}, openRoute = openRoute,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun an_open_train_through_a_change_stays_open_on_a_return_to_stale_arrivals() {
        // A retained trip shown again, its arrivals aged past standing: the route shows, its train
        // withheld until the return's refresh lands.
        val state = mutableStateOf(throughTrip(throughIn = 5, fetchedAt = now.minus(Duration.ofMinutes(10))))
        val openRoute = mutableStateOf<String?>(throughKey)
        showThrough(state, openRoute)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        assertEquals(throughKey, openRoute.value)
        state.value = state.value.copy(refreshing = true)
        composeRule.waitForIdle()
        assertEquals(throughKey, openRoute.value)
        // The return's refresh lands, and the train is still coming.
        state.value = throughTrip(throughIn = 5)
        composeRule.waitForIdle()
        assertEquals(throughKey, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
    }

    @Test
    fun an_open_train_through_a_change_stays_open_with_a_dash_once_its_train_is_no_longer_predicted() {
        val state = mutableStateOf(throughTrip(throughIn = 5))
        val openRoute = mutableStateOf<String?>(throughKey)
        showThrough(state, openRoute)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        composeRule.onNodeWithText("Arrival unknown", substring = true).assertDoesNotExist()
        // The plan still offers the change it runs through, so the route stays (maintainer,
        // 2026-09-29): its line reads "–", as a line with no trains does on the list, and its arrival
        // is withheld rather than taken from the Planner's times for the change.
        state.value = throughTrip(throughIn = null)
        composeRule.waitForIdle()
        assertEquals(throughKey, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        composeRule.onNodeWithText("Arrival unknown", substring = true).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("–").fetchSemanticsNodes().isNotEmpty())
        captureSnapshot("trip-through-no-train.png")
        // Predicted again: timed from it once more.
        state.value = throughTrip(throughIn = 5)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Arrival unknown", substring = true).assertDoesNotExist()
    }

    @Test
    fun an_open_train_through_a_change_stays_open_when_a_new_plan_offers_it_as_a_route_of_its_own() {
        val state = mutableStateOf(throughTrip(throughIn = 5))
        val openRoute = mutableStateOf<String?>(throughKey)
        showThrough(state, openRoute)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        // A re-plan drops the change but plans blue from Aston to Cole itself: the same lines and stops.
        val direct = TripRoute(listOf(leg("tube", "blue", "Blue", "Aston" to "Aston", "Cole" to "Cole", 5, 13, 3)))
        state.value = throughTrip(throughIn = 5).copy(routes = listOf(direct))
        composeRule.waitForIdle()
        assertEquals(throughKey, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
    }

    @Test
    fun an_open_train_through_a_change_closes_for_good_once_the_plan_drops_its_change() {
        val state = mutableStateOf(throughTrip(throughIn = 5))
        val openRoute = mutableStateOf<String?>(throughKey)
        showThrough(state, openRoute)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        // A new plan without the change: closed, and not reopened when a later plan offers it again.
        state.value = throughTrip(throughIn = 5).copy(routes = listOf(TripRoute(listOf(throughChanging.legs.first()))))
        composeRule.waitForIdle()
        assertEquals(null, openRoute.value)
        state.value = throughTrip(throughIn = 5)
        composeRule.waitForIdle()
        assertEquals(null, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertDoesNotExist()
    }

    private fun showWith(state: androidx.compose.runtime.MutableState<TripViewModel.State>) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = state.value, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                )
            }
        }
        composeRule.waitForIdle()
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
    fun an_open_routes_header_warns_just_before_its_arrival() {
        show(planned)
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        // One ⚠ for the disrupted Jubilee, just before the arrival (maintainer, 2026-09-28), not
        // beside its pill: on the arrival's line, ending left of it.
        val arrival = composeRule.onAllNodesWithText("27 min · ~08:29", useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        val glyph = composeRule.onAllNodesWithContentDescription("Minor Delays", useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }.single { it.top < arrival.bottom && arrival.top < it.bottom }
        assertTrue(glyph.right <= arrival.left)
        assertTrue(arrival.left - glyph.right < 40f)
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
    fun a_route_getting_off_at_a_closed_station_goes_last_and_says_why() {
        // The Elizabeth line's Canary Wharf station closed: the route getting off there ranks below
        // the others, still timed, its ride's ⚠ naming the stop; opened, the closure card is where it
        // gets off, as the list's closure card is.
        // Every line checked as running, so only the closure moves the route.
        val running = planned.copy(statuses = planned.statuses + ("elizabeth" to LineStatus("elizabeth", LineStatus.GOOD_SERVICE, "Good Service")))
        fun arrivals() = composeRule.onAllNodes(hasTestTag("tripArrival"), useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        }
        val state = androidx.compose.runtime.mutableStateOf(running)
        showWith(state)
        assertEquals(listOf("27 min · ~08:29", "28 min · ~08:30", "38 min · ~08:40"), arrivals())
        state.value = running.copy(closures = running.closures + (canaryWharfXr.first to listOf(StopDisruption("Canary Wharf Station: Station closed due to a power failure"))))
        composeRule.waitForIdle()
        assertEquals(listOf("27 min · ~08:29", "38 min · ~08:40", "28 min · ~08:30"), arrivals())
        composeRule.onNodeWithContentDescription("Canary Wharf: Station closed due to a power failure", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 stops to Canary Wharf").assertIsDisplayed()
        composeRule.onNodeWithText("Station closed due to a power failure", substring = true).assertExists()
        captureSnapshot("trip-route-closure.png")
    }

    @Test
    fun a_route_list_says_it_couldnt_check_only_for_a_stop_a_route_shown_uses() {
        // Every line checked as running, the routes' own and the others at their stops.
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val unknown = composeRule.activity.getString(R.string.disruptions_unknown)
        // The failed check was for a stop no route shown uses (one of a hidden mode's, say).
        val state = androidx.compose.runtime.mutableStateOf(running.copy(closuresFailed = setOf("940GZZLUNONE")))
        showWith(state)
        composeRule.onNodeWithText(unknown, substring = true).assertDoesNotExist()
        // One a shown route gets off at: it can't vouch for that route, and names the stop.
        state.value = running.copy(closuresFailed = setOf(highbury.first))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.trip_disruptions_unknown_named, highbury.second)).assertExists()
    }

    @Test
    fun a_route_list_says_it_couldnt_check_only_for_a_line_a_route_shown_rides() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val unknown = composeRule.activity.getString(R.string.disruptions_unknown)
        // The failed or unanswered line is one no route shown rides (a hidden mode's, say).
        val state = androidx.compose.runtime.mutableStateOf(
            running.copy(statusFailed = true, statusFailedLines = setOf("hidden"), statusUnknown = setOf("hidden")),
        )
        showWith(state)
        composeRule.onNodeWithText(unknown, substring = true).assertDoesNotExist()
        // One a shown route rides: it can't vouch for that route, and names the line.
        val windrush = composeRule.activity.getString(R.string.trip_disruptions_unknown_named, "Windrush")
        state.value = running.copy(statusFailed = true, statusFailedLines = setOf("windrush"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(windrush).assertExists()
        state.value = running.copy(statusUnknown = setOf("windrush"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(windrush).assertExists()
        // A line and a stop together: the line first.
        state.value = running.copy(statusUnknown = setOf("windrush"), closuresFailed = setOf(highbury.first))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.trip_disruptions_unknown_named, "Windrush, ${highbury.second}")).assertExists()
    }

    @Test
    fun a_leg_page_vouches_once_its_line_and_its_stop_are_checked() {
        // Windrush checked as running and Highbury & Islington checked open: nothing to report.
        show(planned.copy(closuresAt = mapOf(highbury.first to now)))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_no_disruption)).assertExists()
    }

    @Test
    fun a_leg_page_vouches_for_its_line_when_only_another_lines_check_failed() {
        // The latest check failed for another line's request, but answered Windrush.
        show(
            planned.copy(
                closuresAt = mapOf(highbury.first to now),
                statusFailed = true,
                statusFailedLines = setOf("jubilee"),
            ),
        )
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_no_disruption)).assertExists()
    }

    @Test
    fun a_leg_page_at_a_closed_stop_never_calls_it_clean() {
        // Windrush checked as running, but Highbury & Islington checked and closed.
        show(planned.copy(closures = planned.closures + (highbury.first to listOf(StopDisruption("Station closed due to a power failure")))))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_no_disruption)).assertDoesNotExist()
    }

    @Test
    fun a_leg_page_whose_checks_are_as_old_as_a_stale_countdown_claims_nothing() {
        // Checked running and open, but longer ago than a countdown is trusted: a trip shown again
        // holds them while its re-check is out, and ranks by them, but its page doesn't vouch.
        val old = now.minus(Duration.ofMinutes(6))
        val checked = planned.copy(closuresAt = mapOf(highbury.first to now))
        val state = androidx.compose.runtime.mutableStateOf(checked.copy(closuresAt = mapOf(highbury.first to old)))
        showWith(state)
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        val unknown = composeRule.activity.getString(R.string.disruptions_unknown)
        composeRule.onNodeWithText(unknown).assertExists()
        // The same for an old status check.
        state.value = checked.copy(statusesAt = planned.statusesAt + ("windrush" to old))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(unknown).assertExists()
        // Both checked again: it vouches.
        state.value = checked
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_no_disruption)).assertExists()
    }

    @Test
    fun a_leg_page_whose_stop_is_not_yet_checked_claims_nothing() {
        show(planned.copy(closures = planned.closures - highbury.first, closuresUnknown = setOf(highbury.first)))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.disruptions_unknown)).assertExists()
    }

    @Test
    fun a_leg_page_whose_stop_check_just_failed_claims_nothing() {
        // Highbury & Islington checked open before, but its latest check failed.
        show(planned.copy(closuresFailed = setOf(highbury.first)))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.disruptions_unknown)).assertExists()
    }

    @Test
    fun a_leg_opened_after_a_failed_status_check_claims_nothing() {
        // The last statuses are held, but the latest check failed: the line's page can't vouch for it.
        show(planned.copy(statusFailed = true, statusFailedLines = setOf("mildmay", "jubilee", "windrush")))
        composeRule.onNodeWithText("27 min · ~08:29", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stratford").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.disruptions_unknown)).assertExists()
    }

    @Test
    fun an_open_ride_shows_every_line_between_its_stops_even_with_no_train() {
        // A made-up second line serving Highbury & Islington and Canada Water as Windrush does, in the
        // plan by a route of its own and with no train due: its own row on the open ride, which opens
        // its line's page as the Planner's line's row does.
        val testLine = LineSequence(
            routes = listOf(LineRoute("Highbury ↔ Canada Water", listOf("910GHGHI", "910GWCHAPEL", "910GCNDAW"))),
            stopNames = sequences.getValue("windrush").stopNames,
        )
        val withTestLine = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                if (lineId == "testline") testLine else sequences.getValue(lineId)
        }
        val byTestLine = TripRoute(listOf(viaCanadaWater.legs[0].copy(lineId = "testline", lineName = "Test Line"), viaCanadaWater.legs[1]))
        show(planned.copy(routes = listOf(viaCanadaWater, byTestLine)), routeStops = RouteStopsRepository(withTestLine))
        // The card's pill names both lines as one ("Windrush or Test Line").
        composeRule.onAllNodes(
            hasClickAction() and hasContentDescription("Test Line", substring = true) and hasContentDescription("Jubilee"),
        ).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(1)
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Test Line"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
    }

    @Test
    fun another_line_not_yet_checked_shows_its_row_and_opens_its_page() {
        // As above, but the made-up line has a train due and no status known: the open ride shows no
        // countdown for it, only its row, and that row still opens its line's page.
        val testLine = LineSequence(
            routes = listOf(LineRoute("Highbury ↔ Canada Water", listOf("910GHGHI", "910GWCHAPEL", "910GCNDAW"))),
            stopNames = sequences.getValue("windrush").stopNames,
        )
        val withTestLine = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                if (lineId == "testline") testLine else sequences.getValue(lineId)
        }
        val byTestLine = TripRoute(listOf(viaCanadaWater.legs[0].copy(lineId = "testline", lineName = "Test Line"), viaCanadaWater.legs[1]))
        val atHighbury = live.getValue(highbury.first)
        val withTrain = live + (highbury.first to atHighbury.copy(departures = atHighbury.departures + train("testline", "Test Line", "overground", "Canada Water", 4, "Platform 2")))
        show(planned.copy(routes = listOf(viaCanadaWater, byTestLine), live = withTrain), routeStops = RouteStopsRepository(withTestLine))
        composeRule.onAllNodes(
            hasClickAction() and hasContentDescription("Test Line", substring = true) and hasContentDescription("Jubilee"),
        ).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(1)
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Test Line"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
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
    fun a_hide_from_a_leg_offers_undo_as_the_list_does() {
        var hidden: String? = null
        var unhidden: String? = null
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned.copy(live = planned.live - canadaWaterTube.first),
                    now = now, access = Duration.ofMinutes(2), routeStops = RouteStopsRepository(source),
                    onBack = {}, onRetry = {}, onHideMode = { hidden = it }, onUnhideMode = { unhidden = it },
                )
            }
        }
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        val more = composeRule.activity.getString(R.string.more_actions)
        composeRule.onNode(
            hasAnyDescendant(hasContentDescription("Jubilee")) and
                SemanticsMatcher("long-presses to its menu") { it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == more },
        ).performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithText("Hide all Tube & DLR services").performClick()
        composeRule.waitForIdle()
        assertEquals("tube", hidden)
        composeRule.onNodeWithText("Tube & DLR hidden").assertExists()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals("tube", unhidden)
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
    fun cut_pills_beside_lone_pills() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Surface {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun bus(vararg ids: String) = ids.map { LineRef(it, it, "bus") }
                        LinePill("390", "390", "bus")
                        LinePill("Northern", "northern", "tube")
                        SharedLinePill(bus("43", "134"), "43 or 134")
                        SharedLinePill(bus("47", "188"), "47 or 188")
                        SharedLinePill(bus("4", "N20"), "4 or N20")
                        SharedLinePill(listOf(LineRef("victoria", "Victoria", "tube"), LineRef("piccadilly", "Piccadilly", "tube")), "either")
                        SharedLinePill(bus("43", "134", "263"), "any")
                        SharedLinePill(bus("47", "188", "199", "225"), "any")
                    }
                }
            }
        }
        captureSnapshot("cut-pills.png", heightPx = 800)
    }

    @Test
    fun a_cut_pills_segments_take_their_own_codes_width() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                SharedLinePill(listOf(LineRef("43", "43", "bus"), LineRef("134", "134", "bus")), "43 or 134")
            }
        }
        // A two-digit route takes less room than a three-digit one (maintainer, 2026-09-28).
        fun width(code: String) = composeRule.onNodeWithText(code, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue(width("43") < width("134"))
    }

    @Test
    fun a_cut_pills_one_character_code_still_gets_a_two_character_width() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Column {
                    SharedLinePill(listOf(LineRef("4", "4", "bus"), LineRef("N20", "N20", "bus")), "4 or N20")
                    SharedLinePill(listOf(LineRef("43", "43", "bus"), LineRef("N20", "N20", "bus")), "43 or N20")
                }
            }
        }
        // "4/N20" read too tight: a segment is never narrower than a two-character code
        // (maintainer, 2026-09-28).
        fun width(code: String) = composeRule.onNodeWithText(code, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue(width("4") > 0.dp)
        assertTrue(kotlin.math.abs(width("4").value - width("43").value) < 1f)
    }

    @Test
    fun a_cut_pill_fits_the_room_it_has() {
        val lines = listOf("188", "199").map { LineRef(it, it, "bus") }
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Box(Modifier.width(48.dp)) {
                    SharedLinePill(lines, "188 or 199", Modifier.testTag("cut"))
                }
            }
        }
        // Two codes in 48dp: both shrink alike, so each label stays over its own color rather than
        // the second being squeezed out.
        val bounds = composeRule.onNodeWithTag("cut").getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left <= 48.dp)
        val widths = lines.map { line ->
            composeRule.onNodeWithText(line.name, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.right - it.left }
        }
        assertTrue(widths.all { it > 0.dp })
        assertTrue(widths.maxOf { it.value } - widths.minOf { it.value } < 1f)
    }

    @Test
    fun a_cut_pill_for_three_or_more_lines_names_the_first_then_more() {
        val lines = listOf("47", "188", "199", "225").map { LineRef(it, it, "bus") }
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Box(Modifier.semantics(mergeDescendants = true) {}.testTag("row")) {
                    SharedLinePill(lines, "47, 188, 199 or 225")
                }
            }
        }
        // "47/…": the first line, then "…" for the rest, so the pill stays two parts wide
        // (maintainer, 2026-09-28); a screen reader still hears every line.
        assertEquals(listOf("47", "…"), cutPillCodes(lines))
        assertEquals(listOf("43", "134"), cutPillCodes(listOf(LineRef("43", "43", "bus"), LineRef("134", "134", "bus"))))
        val config = composeRule.onNodeWithTag("row").fetchSemanticsNode().config
        assertEquals(listOf("47, 188, 199 or 225"), config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() })
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
    fun a_pull_on_the_routes_is_remembered_across_a_configuration_change() {
        val restoration = StateRestorationTester(composeRule)
        var lastPull: MutableState<Instant?>? = null
        restoration.setContent { lastPull = rememberLastPull("C") }
        composeRule.runOnIdle { lastPull!!.value = now }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(now, lastPull!!.value) }
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
