package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import app.stopdash.domain.LineRef
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * *Lines…* (SPEC *Finding a line*): the search before a query (the recent lines, and none yet), with
 * matches, with none, and when TfL's lines couldn't be loaded. UI only, so it renders with no network.
 * Public TfL lines only, no user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineSearchScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val victoria = LineRef("victoria", "Victoria", "tube")
    private val elizabeth = LineRef("elizabeth", "Elizabeth line", "elizabeth-line")
    private fun bus(n: String) = LineRef(n.lowercase(), n, "bus")
    private val ready = LinesViewModel.Catalog.Ready(listOf(victoria, elizabeth, bus("29"), bus("299")))

    private fun show(state: LinesViewModel.State, onOpen: (LineRef) -> Unit = {}, onRetry: () -> Unit = {}) {
        composeRule.setContent {
            StopDashTheme {
                LineSearchScreen(state = state, onQueryChange = {}, onOpenLine = onOpen, onRetry = onRetry, onBack = {}, autoFocus = false)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun line_search_recent() {
        var opened: LineRef? = null
        show(LinesViewModel.State(recent = listOf(bus("299"), victoria), catalog = ready), onOpen = { opened = it })
        composeRule.onNodeWithText("Recent").assertIsDisplayed()
        composeRule.onNodeWithText("Tube").assertIsDisplayed()
        captureSnapshot("line-search-recent.png")
        composeRule.onNodeWithText("Victoria").performClick()
        assertEquals(victoria, opened)
    }

    @Test
    fun line_search_no_recent_lines_yet() {
        show(LinesViewModel.State(recent = emptyList(), catalog = LinesViewModel.Catalog.Loading))
        composeRule.onNodeWithText("Type a line name or route number").assertIsDisplayed()
        captureSnapshot("line-search-empty.png")
    }

    @Test
    fun line_search_matches() {
        show(LinesViewModel.State(query = "29", recent = emptyList(), catalog = ready, matches = listOf(bus("29"), bus("299")), matchesFor = "29"))
        composeRule.onNodeWithText("299").assertIsDisplayed()
        captureSnapshot("line-search-matches.png")
    }

    @Test
    fun line_search_no_matches() {
        show(LinesViewModel.State(query = "zz", recent = emptyList(), catalog = ready, matches = emptyList(), matchesFor = "zz"))
        composeRule.onNodeWithText("No lines match").assertIsDisplayed()
    }

    @Test
    fun line_search_failed() {
        var retried = false
        show(LinesViewModel.State(query = "29", recent = emptyList(), catalog = LinesViewModel.Catalog.Failed), onRetry = { retried = true })
        composeRule.onNodeWithText("Couldn't load the lines").assertIsDisplayed()
        captureSnapshot("line-search-failed.png")
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun a_failed_list_says_so_before_anything_is_typed_with_the_recent_lines_below() {
        var retried = false
        show(LinesViewModel.State(query = "", recent = listOf(victoria), catalog = LinesViewModel.Catalog.Failed), onRetry = { retried = true })
        composeRule.onNodeWithText("Couldn't load the lines").assertIsDisplayed()
        composeRule.onNodeWithText("Victoria").assertIsDisplayed()
        composeRule.onNodeWithText("Type a line name or route number").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun line_stop_details() {
        var from = 0
        var to = 0
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(name = "Oxford Circus", distance = "350 m", onFrom = { from++ }, onTo = { to++ }, onBack = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Oxford Circus (350 m)").assertIsDisplayed()
        captureSnapshot("line-stop-details.png")
        composeRule.onNodeWithText("From").performClick()
        composeRule.onNodeWithText("To").performClick()
        assertEquals(1, from)
        assertEquals(1, to)
    }

    // The Victoria line from Victoria to Warren Street, public stations, as a route page's stop list opens it.
    private val victoriaLine = LineRef("victoria", "Victoria", "tube")
    private val toWarrenStreet = app.stopdash.domain.FavoriteJourney(
        app.stopdash.domain.JourneyEnd("940GZZLUVIC", "Victoria"),
        app.stopdash.domain.JourneyEnd("940GZZLUWRR", "Warren Street"),
        "victoria",
        lineName = "Victoria",
        mode = "tube",
    )

    private fun routeMode(
        journey: StationJourneyState? = StationJourneyState(toWarrenStreet, saved = false, failed = false) {},
        onGo: (() -> Unit)? = {},
        goReady: Boolean = true,
    ) = RouteStopMode(victoriaLine, "Victoria", "Walthamstow Central", onGo, goReady, journey)

    @Test
    fun route_stop_details() {
        var goes = 0
        var saved by mutableStateOf<Boolean?>(null)
        var toggles = 0
        var departures = 0
        // Warren Street's lines, then Euston Square's, a short walk away.
        val lines = listOf(
            app.stopdash.domain.LineRef("northern", "Northern", "tube"),
            app.stopdash.domain.LineRef("victoria", "Victoria", "tube"),
            app.stopdash.domain.LineRef("circle", "Circle", "tube"),
            app.stopdash.domain.LineRef("hammersmith-city", "Hammersmith & City", "tube"),
            app.stopdash.domain.LineRef("metropolitan", "Metropolitan", "tube"),
        )
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "Warren Street",
                    distance = "350 m",
                    onFrom = {},
                    onTo = {},
                    onBack = {},
                    links = app.stopdash.domain.StopLinks(lines.take(2), emptyList(), emptyList(), nearbyLines = lines),
                    onDepartures = { departures++ },
                    zone = "1",
                    cueSlot = true,
                    access = app.stopdash.domain.StopAccess(app.stopdash.domain.StepFreeLevel.NONE, liftOut = false, byLift = false),
                    accessSlot = true,
                    route = routeMode(StationJourneyState(toWarrenStreet, saved, failed = false) { toggles++ }, onGo = { goes++ }),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("From Victoria, towards Walthamstow Central").assertIsDisplayed()
        // Go in place of From and To.
        composeRule.onNodeWithText("From").assertDoesNotExist()
        composeRule.onNodeWithText("To").assertDoesNotExist()
        // The star waits on the favorites being read, so a tap never goes the wrong way.
        composeRule.onNodeWithTag("routeStopJourney").assertIsNotEnabled()
        saved = false
        composeRule.waitForIdle()
        captureSnapshot("route-stop-details.png")
        composeRule.onNodeWithContentDescription("Favourite").performClick()
        assertEquals(1, toggles)
        saved = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Remove favourite").assertIsDisplayed()
        composeRule.onNodeWithTag("routeStopGo").performClick()
        assertEquals(1, goes)
        // Departures beside Go.
        composeRule.onNodeWithText("Departures").performClick()
        assertEquals(1, departures)
    }

    @Test
    fun route_stop_go_waits_for_its_ride_and_isnt_offered_where_it_cant_be_followed() {
        var ready by mutableStateOf(false)
        var followable by mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "Warren Street",
                    distance = null,
                    onFrom = {},
                    onTo = {},
                    onBack = {},
                    route = routeMode(onGo = if (followable) ({}) else null, goReady = ready),
                )
            }
        }
        composeRule.onNodeWithTag("routeStopGo").assertIsNotEnabled()
        ready = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routeStopGo").assertIsEnabled()
        followable = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routeStopGo").assertDoesNotExist()
    }

    @Test
    fun route_stop_says_when_the_journey_didnt_save_or_cant_be_read_with_retry() {
        var state by mutableStateOf(StationJourneyState(toWarrenStreet, saved = false, failed = true) {})
        var retries = 0
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(name = "Warren Street", distance = null, onFrom = {}, onTo = {}, onBack = {}, route = routeMode(state))
            }
        }
        composeRule.onNodeWithText("Couldn't save that change").assertIsDisplayed()
        state = StationJourneyState(toWarrenStreet, saved = null, failed = false, unavailable = true, onRetry = { retries++ }) {}
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Can't read your favourite journeys.").assertIsDisplayed()
        composeRule.onNodeWithTag("routeStopJourney").assertIsNotEnabled()
        composeRule.onNodeWithTag("routeStopJourneyRetry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun route_stop_with_no_destination_says_where_it_was_boarded() {
        // TfL named no destination for the departure: no dangling "towards".
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "Warren Street",
                    distance = null,
                    onFrom = {},
                    onTo = {},
                    onBack = {},
                    route = RouteStopMode(victoriaLine, "Victoria", "", onGo = {}, goReady = true, journey = null),
                )
            }
        }
        composeRule.onNodeWithText("From Victoria").assertIsDisplayed()
    }

    @Test
    fun route_stop_with_no_journey_has_no_star() {
        // Its boarding stop: no journey to favorite there, and no place favorite in its stead.
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "Victoria",
                    distance = null,
                    onFrom = {},
                    onTo = {},
                    onBack = {},
                    onFavorite = {},
                    route = routeMode(journey = null),
                )
            }
        }
        composeRule.onNodeWithTag("routeStopJourney").assertDoesNotExist()
        composeRule.onNodeWithTag("lineStopFavorite").assertDoesNotExist()
    }

    @Test
    fun line_stop_access() {
        // Oxford Circus, a public interchange, with its zone and its step-free line: a lift it needs is out,
        // the longest of that line's labels.
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "Oxford Circus",
                    distance = "350 m",
                    onFrom = {},
                    onTo = {},
                    onBack = {},
                    zone = "1",
                    cueSlot = true,
                    access = app.stopdash.domain.StopAccess(app.stopdash.domain.StepFreeLevel.NONE, liftOut = true, byLift = true),
                    accessSlot = true,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Not step-free: a lift is out").assertIsDisplayed()
        captureSnapshot("line-stop-access.png")
    }

    @Test
    fun line_stop_links() {
        // King's Cross St. Pancras tube station, a public interchange: one row of pills for every line here and
        // around it (its own, then its interchange's rail lines, then a short walk's), each opening its line.
        val own = listOf(
            app.stopdash.domain.LineRef("circle", "Circle", "tube"),
            app.stopdash.domain.LineRef("hammersmith-city", "Hammersmith & City", "tube"),
            app.stopdash.domain.LineRef("metropolitan", "Metropolitan", "tube"),
            app.stopdash.domain.LineRef("northern", "Northern", "tube"),
            app.stopdash.domain.LineRef("piccadilly", "Piccadilly", "tube"),
            app.stopdash.domain.LineRef("victoria", "Victoria", "tube"),
        )
        val links = app.stopdash.domain.StopLinks(
            lines = own,
            sameHub = listOf(app.stopdash.domain.NearStation("910GKNGX", "London King's Cross", 20.0)),
            nearby = listOf(app.stopdash.domain.NearStation("940GZZLUEUS", "Euston", 720.0, setOf("northern", "victoria"))),
            nearbyLines = own + listOf(
                app.stopdash.domain.LineRef("great-northern", "Great Northern", "national-rail"),
                app.stopdash.domain.LineRef("thameslink", "Thameslink", "national-rail"),
                app.stopdash.domain.LineRef("southeastern", "Southeastern", "national-rail"),
            ),
        )
        val lines = mutableListOf<String>()
        var departures = 0
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "King's Cross St. Pancras",
                    distance = "350 m",
                    onFrom = {},
                    onTo = {},
                    onBack = {},
                    links = links,
                    onOpenLine = { lines += it.id },
                    onDepartures = { departures++ },
                )
            }
        }
        composeRule.waitForIdle()
        captureSnapshot("line-stop-links.png")
        // No stations listed by name: Departures shows them (maintainer, 2026-10-10).
        composeRule.onNodeWithText("Euston", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("lineStopLine:northern").performClick()
        composeRule.onNodeWithTag("lineStopLine:thameslink").performClick()
        composeRule.onNodeWithText("Departures").performClick()
        assertEquals(listOf("northern", "thameslink"), lines)
        assertEquals(1, departures)
    }

    @Test
    fun the_buttons_wrap_rather_than_cut_off_departures_in_a_narrow_window() {
        // From, To and Departures don't fit side by side here: Departures wraps to its own row, whole (Codex on #736).
        composeRule.setContent {
            StopDashTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(240.dp)) {
                    LineStopPage(name = "Euston", distance = null, onFrom = {}, onTo = {}, onBack = {}, onDepartures = {})
                }
            }
        }
        val actions = composeRule.onNodeWithTag("lineStopActions").getUnclippedBoundsInRoot()
        val departures = composeRule.onNodeWithText("Departures").getUnclippedBoundsInRoot()
        val from = composeRule.onNodeWithText("From").getUnclippedBoundsInRoot()
        assertTrue("Departures ends at ${departures.right}, inside ${actions.right}", departures.right <= actions.right)
        assertTrue("Departures wraps below From", departures.top > from.bottom)
    }

    @Test
    fun departures_waits_for_what_it_opens() {
        // A station under several ids opens its interchange: Departures waits for the links that say so, as From
        // and To do (Codex on #664).
        var ready by mutableStateOf(false)
        var departures = 0
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "St Pancras International", distance = null, onFrom = {}, onTo = {}, onBack = {},
                    actionsReady = ready, onDepartures = { departures++ },
                )
            }
        }
        composeRule.onNodeWithText("Departures").assertIsNotEnabled()
        ready = true
        composeRule.onNodeWithText("Departures").performClick()
        assertEquals(1, departures)
    }

    @Test
    fun the_star_adds_the_stop_as_a_favorite_place_once_it_is_placed() {
        var ready by mutableStateOf(false)
        var favorited = 0
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(name = "Euston", distance = null, onFrom = {}, onTo = null, onBack = {}, actionsReady = ready, onFavorite = { favorited++ })
            }
        }
        // Waits on the stop's links, which carry where it is.
        composeRule.onNodeWithTag("lineStopFavorite").assertIsNotEnabled()
        ready = true
        composeRule.onNodeWithContentDescription("Add to favourite places").performClick()
        assertEquals(1, favorited)
    }

    @Test
    fun from_and_to_wait_for_what_they_open() {
        // Until the links are in, From and To can't open one of a station's ids alone (Codex on #664).
        var ready by androidx.compose.runtime.mutableStateOf(false)
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(name = "St Pancras International", distance = null, onFrom = {}, onTo = {}, onBack = {}, actionsReady = ready)
            }
        }
        composeRule.onNodeWithText("From").assertIsNotEnabled()
        composeRule.onNodeWithText("To").assertIsNotEnabled()
        ready = true
        composeRule.onNodeWithText("From").assertIsEnabled()
        composeRule.onNodeWithText("To").assertIsEnabled()
    }

    @Test
    fun from_and_to_stay_reachable_under_a_long_name_on_a_short_screen() {
        // A short window and a long name: the page scrolls to its buttons rather than clip them (Codex on #659).
        var from = 0
        composeRule.setContent {
            StopDashTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(180.dp)) {
                    LineStopPage(
                        name = List(12) { "King's Cross St. Pancras" }.joinToString(" "),
                        distance = "350 m",
                        onFrom = { from++ },
                        onTo = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("lineStopPage").performScrollToNode(hasText("From"))
        composeRule.onNodeWithText("From").performClick()
        assertEquals(1, from)
    }

    @Test
    fun a_station_lists_the_facilities_tfl_says_it_has_at_its_foot() {
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "King's Cross St. Pancras",
                    distance = null,
                    onFrom = {},
                    onTo = {},
                    onBack = {},
                    zone = "1",
                    facilities = facilitiesLine(
                        composeRule.activity.resources,
                        app.stopdash.domain.StationFacts(
                            zone = "1",
                            facilities = listOf(
                                app.stopdash.domain.StationFacility.ACCESSIBLE_TOILET,
                                app.stopdash.domain.StationFacility.CASH_MACHINE,
                                app.stopdash.domain.StationFacility.TAXI_RANK,
                            ),
                            toiletNote = "National Rail",
                        ),
                    ),
                    cueSlot = true,
                )
            }
        }
        composeRule.waitForIdle()
        captureSnapshot("line-stop-facilities.png")
        composeRule.onNodeWithText("Facilities").assertIsDisplayed()
        // As UK riders read them (en-GB).
        composeRule.onNodeWithText("Accessible toilet (National Rail) · Cash machine · Taxi rank").assertIsDisplayed()
    }

    @Test
    fun facilities_read_as_uk_riders_know_them_and_a_toilet_without_a_note_has_no_brackets() {
        val resources = composeRule.activity.resources
        val all = app.stopdash.domain.StationFacility.entries
        assertEquals(
            "Toilets · Accessible toilet · Waiting room · Left luggage · Car park · Cash machine · Taxi rank",
            facilitiesLine(resources, app.stopdash.domain.StationFacts(facilities = all)),
        )
        assertEquals(null, facilitiesLine(resources, app.stopdash.domain.StationFacts()))
    }

    @Test
    fun a_station_tfl_names_no_facilities_for_shows_no_heading() {
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "Euston",
                    distance = null,
                    onFrom = {},
                    onTo = null,
                    onBack = {},
                    zone = "1",
                    facilities = facilitiesLine(composeRule.activity.resources, app.stopdash.domain.StationFacts(zone = "1")),
                    cueSlot = true,
                )
            }
        }
        composeRule.onNodeWithText("Zone 1").assertIsDisplayed()
        composeRule.onNodeWithText("Facilities").assertDoesNotExist()
    }

    @Test
    fun a_stop_with_no_distance_and_no_to_shows_its_name_and_from_alone() {
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(name = "Oxford Circus", distance = null, onFrom = {}, onTo = null, onBack = {})
            }
        }
        composeRule.onNodeWithText("Oxford Circus").assertIsDisplayed()
        composeRule.onNodeWithText("From").assertIsDisplayed()
        composeRule.onNodeWithText("To").assertDoesNotExist()
    }

    @Test
    fun the_overflow_item_opens_lines_and_closes_the_menu() {
        var opened = false
        var closed = false
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalOpenLines provides { opened = true }) {
                    Column { LinesMenuItem(close = { closed = true }) }
                }
            }
        }
        composeRule.onNodeWithText("Lines…").performClick()
        assertTrue(opened)
        assertTrue(closed)
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

    // Recorded only when CI asks: a plain `./gradlew test` renders and asserts without rewriting PNGs.
    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
