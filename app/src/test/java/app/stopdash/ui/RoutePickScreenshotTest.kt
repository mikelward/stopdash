package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.RouteStop
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The route page picking where a favorite ride goes ("Add favorite…", SPEC *Journeys*; maintainer, 2026-10-23):
 * titled "Add favourite ride", then where it's from, "To…" and the stop list alone (no times, notices or actions;
 * maintainer, 2026-10-23), and a tap on a stop after the boarding stop picks it rather than opening its
 * details; the boarding stop picks nothing, and a long press doesn't toggle a ride while picking. Its menu's
 * "Add favorite…" puts the page into it. Public Victoria line stations only (SPEC *Privacy*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutePickScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-10-23T08:00:00Z")

    private val row: DepartureRow = DepartureRows.across(
        listOf(
            StopArrivals(
                "940GZZLUVIC",
                "Victoria",
                listOf(Departure("victoria", "Victoria", "northbound", "Walthamstow Central", "Northbound - Platform 5", now.plusSeconds(120), "tube")),
                fetchedAt = now,
            ),
        ),
        now,
    ).first { it.upcoming.isNotEmpty() }

    private val stops = RouteStopsUi.Loaded(
        listOf(
            RouteStop("940GZZLUVIC", "Victoria"),
            RouteStop("940GZZLUGPK", "Green Park"),
            RouteStop("940GZZLUOXC", "Oxford Circus"),
            RouteStop("940GZZLUWRR", "Warren Street"),
        ),
    )

    private fun setPage(content: @Composable () -> Unit) = composeRule.setContent {
        CompositionLocalProvider(LocalWorker provides Dispatchers.Main.immediate) {
            StopDashTheme(dynamicColor = false) { content() }
        }
    }

    @Test
    fun picking_titles_the_page_To_and_a_stop_tapped_is_where_the_ride_goes() {
        val picked = mutableListOf<RouteStop>()
        val opened = mutableListOf<RouteStopOpen>()
        val toggled = mutableListOf<FavoriteJourney>()
        setPage {
            RouteDetailScreen(
                row = row,
                isStarred = false,
                starrable = true,
                disruptionUnknown = false,
                stale = false,
                now = now,
                onToggleStar = {},
                onBack = {},
                routeStops = stops,
                onToggleJourney = { toggled += it },
                onOpenStop = { opened += it },
                picking = true,
                onPickTo = { loaded, stop ->
                    assertEquals(stops, loaded)
                    picked += stop
                },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routePickTitle").assertIsDisplayed()
        composeRule.onNodeWithText("Add favourite ride").assertIsDisplayed()
        composeRule.onNodeWithText("From Victoria", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("To…").assertIsDisplayed()
        // The departure's own lines are left off: its line heading, countdown and disruption note.
        composeRule.onNodeWithText("Victoria line").assertDoesNotExist()
        composeRule.onNodeWithText("2 min").assertDoesNotExist()
        composeRule.onNodeWithText("No disruptions reported").assertDoesNotExist()
        captureSnapshot("route-pick.png")
        // A screen reader hears the tap save the ride there, not open the stop (Codex on #1179).
        fun saidAs(label: String) = composeRule.onAllNodes(
            SemanticsMatcher("tap said as $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertTrue(saidAs("Add favourite ride") > 0)
        assertEquals(0, saidAs("Show stop"))

        composeRule.onNodeWithText("Oxford Circus", substring = true).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(RouteStop("940GZZLUOXC", "Oxford Circus")), picked)
        // No details open, and nothing is toggled.
        assertTrue(opened.isEmpty())
        // The boarding stop is no ride's end.
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Your stop"))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(1, picked.size)
        // A long press picks as a tap does, never toggling a ride behind the pick (Codex on #1179).
        composeRule.onNodeWithText("Green Park", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertTrue(toggled.isEmpty())
        assertEquals(RouteStop("940GZZLUGPK", "Green Park"), picked.last())
    }

    @Test
    fun outside_picking_a_stop_tapped_opens_its_details_as_ever() {
        val picked = mutableListOf<RouteStop>()
        val opened = mutableListOf<RouteStopOpen>()
        setPage {
            RouteDetailScreen(
                row = row,
                isStarred = false,
                starrable = true,
                disruptionUnknown = false,
                stale = false,
                now = now,
                onToggleStar = {},
                onBack = {},
                routeStops = stops,
                onToggleJourney = {},
                onOpenStop = { opened += it },
                onPickTo = { _, stop -> picked += stop },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routePickTitle").assertDoesNotExist()
        composeRule.onNodeWithText("Oxford Circus", substring = true).performClick()
        composeRule.waitForIdle()
        assertEquals("940GZZLUOXC", opened.single().stopId)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun the_menus_add_favorite_starts_picking() {
        var started = 0
        setPage {
            CompositionLocalProvider(LocalAppMenu provides AppMenuActions(false, {}, {}, {})) {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = stops,
                    onPickTo = { _, _ -> },
                    onStartPicking = { started++ },
                )
            }
        }
        composeRule.waitForIdle()
        // No pin star: nothing new is pinned.
        composeRule.onNodeWithContentDescription("Pin to top").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Add favourite ride…").performClick()
        composeRule.runOnIdle { assertEquals(1, started) }
    }

    @Test
    fun a_trips_route_page_offers_add_favorite_ride_and_picks_as_the_lists_does() {
        // A trip's route page pins nothing and stars no stop's ride, but its menu still offers "Add favorite ride…"
        // (maintainer, 2026-10-24), picking as the near-me list's page does.
        var picking by mutableStateOf(false)
        val picked = mutableListOf<RouteStop>()
        setPage {
            CompositionLocalProvider(LocalAppMenu provides AppMenuActions(false, {}, {}, {})) {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = false,
                    ofTrip = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = stops,
                    picking = picking,
                    onPickTo = { _, stop -> picked += stop },
                    onStartPicking = { picking = true },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Add favourite ride…").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routePickTitle").assertIsDisplayed()
        composeRule.onNodeWithText("Warren Street", substring = true).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(RouteStop("940GZZLUWRR", "Warren Street")), picked)
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
