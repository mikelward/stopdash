package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.RouteFocus
import app.stopdash.domain.RouteStop
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.TflException
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.StarredJourney
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tap-to-open route detail (SPEC D8 / *Disruptions*): a full-screen page — the discoverable home
 * for the star (the list card only long-presses to pin) and for the line's full disruption text,
 * which the compact chip stands in for. Collapsed the alert is the reason's first line; tapping
 * expands it — the same widget the stop-closure card uses. The app bar names the route (line pill +
 * destination) and the body names the boarding stop. The composable is pure, so it renders under
 * Robolectric with nothing wired. Pins the app-bar + body layout as a golden and the star / expand
 * behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteDetailScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    // TfL's prose behind the "Severe Delays" chip — deliberately long, so the collapsed one-line
    // clip and the tap-to-expand are exercised. Synthetic wording, a well-known line as an example.
    private val reason =
        "Victoria line: Severe delays while we fix a signal failure at Victoria. " +
            "Tickets will be accepted on local buses and London Overground."

    private fun disruptedRow(): DepartureRow {
        val stop = StopArrivals(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = listOf(
                Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(120), "tube"),
                Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(360), "tube"),
            ),
            fetchedAt = now,
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", 6, "Severe Delays", reason))
        return DepartureRows.across(listOf(stop), now, statuses).first { it.upcoming.isNotEmpty() }
    }

    private fun healthyRow(platform: String? = null): DepartureRow {
        val stop = StopArrivals(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = listOf(
                Departure("victoria", "Victoria", "northbound", "Walthamstow Central", platform, now.plusSeconds(120), "tube"),
            ),
            fetchedAt = now,
        )
        return DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
    }

    @Test
    fun manyDepartures_listsThemAllNotJustTheCardsThree() {
        val stop = StopArrivals(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = (1..7).map { i ->
                Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(i * 150L), "tube")
            },
            fetchedAt = now,
        )
        val row = DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loading,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("2 · 5 · 7 · 10 · 12 · 15 · 17 min").assertIsDisplayed()

        captureSnapshot("route-detail-many-departures.png")
    }

    @Test
    fun aTappedBranchRoute_namesItsBranchInTheTitle() {
        // A loop line whose two routes share a terminus: the card splits them by branch
        // ("Hainault/Newbury Park"), so the page opened from one names the branch too, not just
        // the terminus both routes share.
        val stop = StopArrivals(
            stopId = "940GZZLUOXC",
            stopName = "Oxford Circus",
            departures = listOf(
                Departure("central", "Central", "outbound", "Hainault", null, now.plusSeconds(240), "tube"),
                Departure(
                    "central", "Central", "outbound", "Hainault", null, now.plusSeconds(420), "tube",
                    branch = "Newbury Park",
                ),
            ),
            fetchedAt = now,
        )
        val row = DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loading,
                    focus = RouteFocus("Hainault", "Newbury Park"),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Hainault").assertIsDisplayed()
        composeRule.onNodeWithText("Newbury Park", substring = true).assertIsDisplayed()
        // The page follows that branch's train, not the sooner one on the other route.
        composeRule.onNodeWithText("7 min").assertIsDisplayed()

        captureSnapshot("route-detail-branch-title.png")
    }

    @Test
    fun disruptedRoute_showsChipStarAndCollapsedAlert() {
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    onDismissAlert = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Severe Delays").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss alert").assertIsDisplayed()
        // The discoverable star — an app-bar icon whose contentDescription labels the action.
        composeRule.onNodeWithContentDescription("Pin to top").assertIsDisplayed()
        // The app bar names where the service is going; with no stop list shown, the body names the
        // boarding stop.
        composeRule.onNodeWithText("Walthamstow Central", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("From Victoria").assertIsDisplayed()
        // Collapsed: the alert shows its first line (clipped), the chevron marks it expandable.
        composeRule.onNodeWithText(reason, substring = true).assertIsDisplayed()

        captureSnapshot("route-detail-disrupted.png")
    }

    @Test
    fun plannedWork_isListedWithItsStartNotFlagged() {
        // Work that hasn't started: listed with the day it starts, in the muted style, while the
        // line reads as it does today — no disruption chip, no dismiss.
        val planned = PlannedAlert(
            label = "Part Closure",
            fullText = "Saturday 3 October: no service between Station A and Station B. Use local buses.",
            startsOn = LocalDate.of(2026, 10, 3),
        )
        val stop = StopArrivals(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = listOf(
                Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(120), "tube"),
            ),
            fetchedAt = now,
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(planned)))
        val row = DepartureRows.across(listOf(stop), now, statuses).first { it.upcoming.isNotEmpty() }
        assertEquals(null, row.status)
        val dismissed = mutableListOf<PlannedAlert>()
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    onDismissAlert = {},
                    onDismissPlanned = { dismissed += it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Part Closure").assertIsDisplayed()
        composeRule.onNodeWithText("From 3 Oct").assertIsDisplayed()
        composeRule.onNodeWithText(planned.fullText, substring = true).assertIsDisplayed()
        // Only the planned alert's own ×: there is no disruption to dismiss.
        composeRule.onNodeWithContentDescription("Dismiss alert").performClick()
        assertEquals(listOf(planned), dismissed)

        captureSnapshot("route-detail-planned.png")
    }

    @Test
    fun tappingDismiss_reportsTheDismiss() {
        var dismissed = 0
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    onDismissAlert = { dismissed++ },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Dismiss alert").performClick()
        composeRule.waitForIdle()

        assertEquals(1, dismissed)
    }

    @Test
    fun dismissedAlert_saysSoRatherThanClaimingACleanLine() {
        // The user dismissed the line's status: the chip and prose are gone, but the line is still
        // disrupted, so the page must not read "No disruptions reported" (SPEC principle 1).
        val row = disruptedRow().copy(status = null, statusDismissed = true)
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loading,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Service alert dismissed").assertIsDisplayed()
        composeRule.onNodeWithText("No disruptions reported").assertDoesNotExist()
        composeRule.onNodeWithText("Severe Delays").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Dismiss alert").assertDoesNotExist()

        captureSnapshot("route-detail-alert-dismissed.png")
    }

    @Test
    fun dismissedAlert_showsBesideAnUnknownStopCheck() {
        // The stop's own disruption lookup failed but the line's was dismissed: two independent facts,
        // so both notes show (SPEC principle 1).
        val row = disruptedRow().copy(status = null, statusDismissed = true)
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = true,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loading,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Service alert dismissed").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't check for disruptions").assertIsDisplayed()
        composeRule.onNodeWithText("No disruptions reported").assertDoesNotExist()
    }

    @Test
    fun tappingTheAlert_expandsToTheFullText() {
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Tapping the alert (its first-line text, inside the clickable surface) toggles expand;
        // the golden captures the full text unclipped. "Show full alert"/"Show less" are the
        // surface's click-action LABEL, not displayed text, so the golden is the assertion here.
        composeRule.onNodeWithText(reason, substring = true).performTouchInput { click() }
        composeRule.waitForIdle()

        captureSnapshot("route-detail-expanded.png")
    }

    @Test
    fun tappingTheStar_togglesTheRow() {
        var toggled = false
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = { toggled = true },
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Pin to top").performClick()
        composeRule.runOnIdle { assertEquals(true, toggled) }
    }

    @Test
    fun aStarredRoute_offersToUnpin() {
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = disruptedRow(),
                    isStarred = true,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Unpin from top").assertIsDisplayed()
    }

    @Test
    fun aRailOperator_isNamedInFullAtTheTop() {
        // The pill only says "LNR"; the page spells out whose train it is: London Northwestern
        // Railway, the brand of every train West Midlands Trains runs from London, rather than the
        // rail feed's "LNR & WMR" for both its brands.
        val stop = StopArrivals(
            stopId = "910GEUSTON",
            stopName = "London Euston",
            departures = listOf(
                Departure(
                    "west-midlands-trains", "LNR & WMR", "", "Crewe",
                    "Platform 10", now.plusSeconds(360), "national-rail",
                ),
            ),
            fetchedAt = now,
        )
        val row = DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(
                        listOf(RouteStop("910GEUSTON", "London Euston"), RouteStop("910GCREWE", "Crewe")),
                    ),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("London Northwestern Railway").assertIsDisplayed()
        composeRule.onNodeWithText("London Northwestern Railway")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("LNR").assertIsDisplayed()
        captureSnapshot("route-detail-rail-operator.png")
    }

    @Test
    fun aTubeLine_isNamedAsALine() {
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = healthyRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria line").assertIsDisplayed()
    }

    @Test
    fun aTubeLineWithNoModeFromTfl_isStillNamedAsALine() {
        // TfL can omit modeName; the line id still says it's the tube.
        val stop = StopArrivals(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = listOf(
                Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(120), ""),
            ),
            fetchedAt = now,
        )
        val row = DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria line").assertIsDisplayed()
    }

    @Test
    fun aHealthyRoute_saysNoDisruptions_andStillStars() {
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = healthyRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("No disruptions reported").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pin to top").assertIsDisplayed()
    }

    @Test
    fun aKnownLineAlert_stillFlagsUncheckedStopDisruption() {
        // The line status is known (a disruption is shown), but this stop's own disruption lookup
        // (a closure/move) failed — disruptionUnknown is set. The alert isn't the whole story, so
        // the detail must still say the stop-level disruption wasn't checked (SPEC principle 1).
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = false,
                    disruptionUnknown = true,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Severe Delays").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't check for disruptions").assertIsDisplayed()
    }

    @Test
    fun whenStaleAndUnchecked_showsBothCaveats_notJustAge() {
        // A stale row whose stop-disruption lookup also failed: "may be out of date" (the age) and
        // "couldn't check for disruptions" (the failed stop-level check) are independent facts, so
        // both must show — the age warning must not stand in for the failed check (SPEC principle 1).
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = false,
                    disruptionUnknown = true,
                    stale = true,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Severe Delays").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't check for disruptions").assertIsDisplayed()
        composeRule.onNodeWithText("Status may be out of date").assertIsDisplayed()
    }

    @Test
    fun whenDisruptionUnknown_saysCouldntCheck_notNoDisruptions() {
        // The lookup failed, so a null status is unchecked, not clean: the detail must not claim
        // "No disruptions reported" (SPEC principle 1).
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = healthyRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = true,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Couldn't check for disruptions").assertIsDisplayed()
        composeRule.onNodeWithText("No disruptions reported").assertDoesNotExist()
    }

    @Test
    fun whenStale_saysStatusMayBeOutOfDate_notNoDisruptions() {
        // A stale snapshot: the disruption status is from an old fetch, so the detail must not
        // claim "No disruptions reported" (SPEC D4) — it caveats instead.
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = healthyRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = true,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Status may be out of date").assertIsDisplayed()
        composeRule.onNodeWithText("No disruptions reported").assertDoesNotExist()
    }

    /**
     * Draws the activity window into a PNG. Measured and laid out explicitly at the device size —
     * Robolectric's window has no real surface, so a full-screen composable captures blank
     * otherwise (the same helper shape as [MainScreenScreenshotTest]).
     */
    @Test
    fun stopList_showsEveryStationToTheTerminus() {
        // Victoria line northbound from Victoria: public station names, a well-known example route.
        val stops = listOf(
            "Victoria", "Green Park", "Oxford Circus", "Warren Street", "Euston", "King's Cross St. Pancras",
            "Highbury & Islington", "Finsbury Park", "Seven Sisters", "Tottenham Hale", "Blackhorse Road",
            "Walthamstow Central",
        ).mapIndexed { i, name -> RouteStop("stop$i", name, victoriaLineConnections[name].orEmpty()) }
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = healthyRow(platform = "Northbound - Platform 5"),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(stops),
                )
            }
        }
        composeRule.waitForIdle()

        // The list is headed by the way the train runs, read off its platform; it opens on the
        // boarding stop, so no "From" label repeats it — and a screen reader hears it as "Your stop".
        composeRule.onNodeWithText("Northbound").assertIsDisplayed()
        composeRule.onNodeWithText("From Victoria").assertDoesNotExist()
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Your stop"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Seven Sisters").assertIsDisplayed()
        // A connection chip beside its station: the Lioness line at Euston, named for a screen reader.
        composeRule.onNodeWithContentDescription("Lioness").assertIsDisplayed()

        captureSnapshot("route-detail-stops.png")
    }

    @Test
    fun stationsTheAlertNames_carryAWarning() {
        // Synthetic alert wording over public station names: it names two stations on this list, the
        // line itself (not a station) and a station off the list.
        val alert = "Victoria line: no service between Oxford Circus and Euston while we fix a signal " +
            "failure. Use the Northern line via Tottenham Court Road."
        val stops = listOf("Victoria", "Green Park", "Oxford Circus", "Warren Street", "Euston", "King's Cross St. Pancras")
            .mapIndexed { i, name -> RouteStop("stop$i", name) }
        val statuses = mapOf("victoria" to LineStatus("victoria", 6, "Part Suspended", alert))
        val stop = StopArrivals(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = listOf(
                Departure("victoria", "Victoria", "northbound", "Walthamstow Central", "Northbound - Platform 5", now.plusSeconds(120), "tube"),
            ),
            fetchedAt = now,
        )
        val row = DepartureRows.across(listOf(stop), now, statuses).first { it.upcoming.isNotEmpty() }
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(stops),
                )
            }
        }
        composeRule.waitForIdle()

        val inAlert = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Named in the service alert")
        composeRule.onNodeWithText("Oxford Circus \u26A0", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Euston \u26A0", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Warren Street", useUnmergedTree = true).assertExists()
        assertEquals(2, composeRule.onAllNodes(inAlert).fetchSemanticsNodes().size)
        // And beside the chip, in route order, so where it is reads next to what it is.
        composeRule.onNodeWithText("Oxford Circus, Euston").assertIsDisplayed()
        // The boarding stop isn't named (only its line is), so it stays "Your stop" alone.
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Your stop")).assertExists()

        captureSnapshot("route-detail-alert-stops.png")
    }

    @Test
    fun tappingAStation_starsTheJourneyThere_andAStarredOneShowsAStar() {
        val stops = listOf(
            RouteStop("940GZZLUVIC", "Victoria"),
            RouteStop("940GZZLUGPK", "Green Park"),
            RouteStop("940GZZLUOXC", "Oxford Circus"),
        )
        // Synthetic positions: the journey keeps them to pick its nearer end.
        val positions = mapOf("940GZZLUVIC" to (51.5 to -0.12), "940GZZLUOXC" to (51.51 to -0.12))
        val starredToOxford = StarredJourney(
            JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUOXC", "Oxford Circus"), "victoria",
        )
        val toggled = mutableListOf<StarredJourney>()
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = healthyRow(platform = "Northbound - Platform 5"),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(stops, positions),
                    journeys = listOf(starredToOxford),
                    onToggleJourney = { toggled += it },
                )
            }
        }
        composeRule.waitForIdle()

        // The starred journey's end carries a star, and a screen reader hears it.
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Starred journey"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Oxford Circus", substring = true).assertIsDisplayed()

        // An unstarred station stars a new journey from here, with the positions it has.
        composeRule.onNodeWithText("Green Park", substring = true).performClick()
        composeRule.waitForIdle()
        assertEquals(
            StarredJourney(
                JourneyEnd("940GZZLUVIC", "Victoria", 51.5, -0.12),
                JourneyEnd("940GZZLUGPK", "Green Park"),
                "victoria",
                lineName = "Victoria",
                mode = "tube",
            ),
            toggled.single(),
        )
        // The starred one toggles the saved journey itself (off).
        composeRule.onNodeWithText("Oxford Circus", substring = true).performClick()
        composeRule.waitForIdle()
        assertEquals(starredToOxford, toggled.last())

        // The boarding stop itself isn't a journey end: it has no tap action.
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Your stop"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun theJourneyTip_showsAboveAStarrableList_untilDismissed() {
        val stops = listOf(RouteStop("940GZZLUVIC", "Victoria"), RouteStop("940GZZLUGPK", "Green Park"))
        var dismissed = 0
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = healthyRow(platform = "Northbound - Platform 5"),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(stops),
                    onToggleJourney = {},
                    onDismissJourneyTip = { dismissed++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tap a stop to star the journey there").assertIsDisplayed()
        captureSnapshot("route-detail-journey-tip.png")
        composeRule.onNodeWithText("Got it").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun theJourneyTip_isHiddenWhereNoJourneyCanBeStarred() {
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = healthyRow(platform = "Northbound - Platform 5"),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(listOf(RouteStop("940GZZLUVIC", "Victoria"))),
                    onDismissJourneyTip = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tap a stop to star the journey there").assertDoesNotExist()
    }

    @Test
    fun tappingAnUnnamedStation_savesTheIdItShows() {
        val stops = listOf(RouteStop("940GZZLUVIC", "Victoria"), RouteStop("940GZZLUGPK", ""))
        val toggled = mutableListOf<StarredJourney>()
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = healthyRow(platform = "Northbound - Platform 5"),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(stops, emptyMap()),
                    journeys = emptyList(),
                    onToggleJourney = { toggled += it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("940GZZLUGPK", substring = true).performClick()
        composeRule.waitForIdle()
        assertEquals("940GZZLUGPK", toggled.single().to.name)
    }

    @Test
    fun aBusRoute_starsAJourneyToo() {
        val stop = StopArrivals(
            stopId = "490000001N",
            stopName = "Park",
            // TfL left the mode off the soonest bus; a later one says it's a bus.
            departures = listOf(
                Departure("b1", "B1", "outbound", "Hill", null, now.plusSeconds(120), ""),
                Departure("b1", "B1", "outbound", "Hill", null, now.plusSeconds(600), "bus"),
            ),
            fetchedAt = now,
        )
        val row = DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
        val toggled = mutableListOf<StarredJourney>()
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(
                        listOf(RouteStop("490000001N", "Park"), RouteStop("490000002N", "Hill")),
                        emptyMap(),
                    ),
                    journeys = emptyList(),
                    onToggleJourney = { toggled += it },
                )
            }
        }
        composeRule.waitForIdle()
        // The one station after the boarding stop, found by its "Star journey" tap action (its name
        // is also the header's destination).
        composeRule.onNode(
            SemanticsMatcher("star journey action") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == "Star journey"
            },
        ).performClick()
        composeRule.waitForIdle()
        assertEquals("490000002N", toggled.single().to.stopId)
        assertEquals("bus", toggled.single().mode)
    }

    @Test
    fun theWayBackPage_showsAndTogglesTheSameJourney() {
        // Synthetic poles: each stop has one per direction in a shared stop area.
        val sequence = LineSequence(
            routes = listOf(
                LineRoute("Park ↔ Hill", listOf("490000001N", "490000002N")),
                LineRoute("Hill ↔ Park", listOf("490000002S", "490000001S")),
            ),
            stopNames = mapOf("490000001N" to "Park", "490000001S" to "Park", "490000002N" to "Hill", "490000002S" to "Hill"),
            stopAreas = mapOf("490000001N" to "490G1", "490000001S" to "490G1", "490000002N" to "490G2", "490000002S" to "490G2"),
        )
        val stop = StopArrivals(
            stopId = "490000002S",
            stopName = "Hill",
            departures = listOf(Departure("b1", "B1", "inbound", "Park", null, now.plusSeconds(120), "bus")),
            fetchedAt = now,
        )
        val row = DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
        val starredOutbound = StarredJourney(JourneyEnd("490000001N", "Park"), JourneyEnd("490000002N", "Hill"), "b1", "B1", "bus")
        val toggled = mutableListOf<StarredJourney>()
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(
                        listOf(RouteStop("490000002S", "Hill"), RouteStop("490000001S", "Park")),
                        emptyMap(),
                        sequence,
                    ),
                    journeys = listOf(starredOutbound),
                    onToggleJourney = { toggled += it },
                )
            }
        }
        composeRule.waitForIdle()
        val star = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Starred journey")
        composeRule.onNode(star).assertIsDisplayed()
        composeRule.onNode(star).performClick()
        composeRule.waitForIdle()
        // The saved journey itself is toggled off, not a second one starred under these poles.
        assertEquals(starredOutbound.key, toggled.single().key)
    }

    @Test
    fun theWayBackPage_findsTheJourneyWhenOnlyOneEndChangesPole() {
        // Hill has one pole (both ways); Park has one per direction in a shared stop area.
        val sequence = LineSequence(
            routes = listOf(
                LineRoute("Park ↔ Hill", listOf("490000001N", "490000002X")),
                LineRoute("Hill ↔ Park", listOf("490000002X", "490000001S")),
            ),
            stopNames = mapOf("490000001N" to "Park", "490000001S" to "Park", "490000002X" to "Hill"),
            stopAreas = mapOf("490000001N" to "490G1", "490000001S" to "490G1"),
        )
        val stop = StopArrivals(
            stopId = "490000002X",
            stopName = "Hill",
            departures = listOf(Departure("b1", "B1", "inbound", "Park", null, now.plusSeconds(120), "bus")),
            fetchedAt = now,
        )
        val row = DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
        val saved = StarredJourney(JourneyEnd("490000001N", "Park"), JourneyEnd("490000002X", "Hill"), "b1", "B1", "bus")
        val toggled = mutableListOf<StarredJourney>()
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(
                        listOf(RouteStop("490000002X", "Hill"), RouteStop("490000001S", "Park")),
                        emptyMap(),
                        sequence,
                    ),
                    journeys = listOf(saved),
                    onToggleJourney = { toggled += it },
                )
            }
        }
        composeRule.waitForIdle()
        val star = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Starred journey")
        composeRule.onNode(star).assertIsDisplayed()
        composeRule.onNode(star).performClick()
        composeRule.waitForIdle()
        assertEquals(saved.key, toggled.single().key)
    }

    @Test
    fun aSegmentStarredFromAnotherLine_showsStarredHereToo() {
        val stop = StopArrivals(
            stopId = "490000001N",
            stopName = "Park",
            departures = listOf(Departure("b2", "B2", "outbound", "Hill", null, now.plusSeconds(120), "bus")),
            fetchedAt = now,
        )
        val row = DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
        // Starred from the b1's page; this is the b2's.
        val starredOnB1 = StarredJourney(JourneyEnd("490000001N", "Park"), JourneyEnd("490000002N", "Hill"), "b1", "B1", "bus")
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row,
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(
                        listOf(RouteStop("490000001N", "Park"), RouteStop("490000002N", "Hill")),
                        emptyMap(),
                    ),
                    journeys = listOf(starredOnB1),
                    onToggleJourney = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Starred journey"))
            .assertIsDisplayed()
    }

    @Test
    fun staleRow_withholdsTheStopList() {
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = healthyRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = true,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                    routeStops = RouteStopsUi.Loaded(listOf(RouteStop("a", "Green Park"))),
                )
            }
        }
        composeRule.onNodeWithText("Stops shown once departures refresh").assertIsDisplayed()
        composeRule.onNodeWithText("Green Park").assertDoesNotExist()
    }

    @Test
    fun aStatusRowWithNoTrains_stillNamesTheAlertsStationsBesideTheChip() {
        // A suspension leaves no train to follow, so there is no stop list; the line's stations are
        // loaded just to name the ones the alert mentions. Public names, synthetic ids and wording.
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                    LineSequence(
                        // Each way calls at its own ids under the same names, as a bus line's poles on
                        // either side of the road do, so each name is matched twice.
                        routes = listOf(
                            LineRoute("Victoria - Walthamstow", listOf("s1", "s2", "s3")),
                            LineRoute("Walthamstow - Victoria", listOf("r3", "r2", "r1")),
                        ),
                        stopNames = mapOf(
                            "s1" to "Victoria", "s2" to "Green Park", "s3" to "Oxford Circus",
                            "r1" to "Victoria", "r2" to "Green Park", "r3" to "Oxford Circus",
                        ),
                    )
            },
            // Loaded on the test's own thread, so the page settles on its answer without a wait.
            io = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val statusRow = disruptedRow().let { row ->
            row.copy(
                upcoming = emptyList(),
                status = row.status!!.copy(description = "Suspended", fullText = "Victoria line: suspended between Green Park and Oxford Circus."),
            )
        }
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalRouteStops provides repository) {
                    RouteDetailScreen(
                        row = statusRow,
                        isStarred = false,
                        starrable = false,
                        disruptionUnknown = false,
                        stale = false,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Each name once, in the first direction's order.
        composeRule.onNodeWithText("Green Park, Oxford Circus").assertIsDisplayed()
        // Named, not listed: no station rows appear on a page with no train to follow.
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Named in the service alert"))
            .assertCountEquals(0)
    }

    @Test
    fun aStaleRow_stillNamesTheAlertsStationsBesideTheChip() {
        // A stale row's train-specific stop list is withheld, but the alert's stations are still
        // named, from the line's stations. Public names, synthetic ids and wording.
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                    LineSequence(
                        routes = listOf(LineRoute("Victoria - Walthamstow", listOf("s1", "s2", "s3"))),
                        stopNames = mapOf("s1" to "Victoria", "s2" to "Green Park", "s3" to "Oxford Circus"),
                    )
            },
            io = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val row = disruptedRow().let { it.copy(status = it.status!!.copy(fullText = "No service between Green Park and Oxford Circus.")) }
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalRouteStops provides repository) {
                    RouteDetailScreen(
                        row = row,
                        isStarred = false,
                        starrable = false,
                        disruptionUnknown = false,
                        stale = true,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Green Park, Oxford Circus").assertIsDisplayed()
        // The list itself stays withheld.
        composeRule.onNodeWithText("Stops shown once departures refresh").assertIsDisplayed()
    }

    @Test
    fun anUnresolvedStopList_stillNamesTheAlertsStationsBesideTheChip() {
        // The line's stations load, but the row's stop isn't among them, so its list is
        // unavailable; the alert's stations are still named from them. Public names, synthetic ids.
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                    LineSequence(
                        routes = listOf(LineRoute("Victoria - Walthamstow", listOf("s1", "s2", "s3"))),
                        stopNames = mapOf("s1" to "Victoria", "s2" to "Green Park", "s3" to "Oxford Circus"),
                    )
            },
            io = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val row = disruptedRow().let { it.copy(status = it.status!!.copy(fullText = "No service between Green Park and Oxford Circus.")) }
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalRouteStops provides repository) {
                    RouteDetailScreen(
                        row = row,
                        isStarred = false,
                        starrable = false,
                        disruptionUnknown = false,
                        stale = false,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stop list unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Green Park, Oxford Circus").assertIsDisplayed()
    }

    @Test
    fun staleStatusRow_showsNoStopMessage() {
        val statusRow = disruptedRow().copy(upcoming = emptyList())
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = statusRow,
                    isStarred = false,
                    starrable = false,
                    disruptionUnknown = false,
                    stale = true,
                    now = now,
                    onToggleStar = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Stops shown once departures refresh").assertDoesNotExist()
    }

    @Test
    fun stopListFailure_saysWhyAndRetries() {
        var retried = 0
        composeRule.setContent {
            StopDashTheme {
                RouteStopsSection(
                    state = RouteStopsUi.Failed(DeparturesUiState.Error.Kind.OFFLINE),
                    railColor = androidx.compose.ui.graphics.Color.Blue,
                    onRetry = { retried++ },
                )
            }
        }
        composeRule.onNodeWithText("Couldn't load stops — you're offline").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun aLineTfLDoesntKnow_doesNotAskAgainToNameTheAlertsStations() {
        // Its route request already came back not found, so naming the alert's stations from the
        // line's stations would only repeat a request that can't succeed.
        val calls = mutableListOf<String>()
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    calls += direction
                    throw TflException.NotFound(null)
                }
            },
            io = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val row = disruptedRow().let { it.copy(status = it.status!!.copy(fullText = "No service between Green Park and Oxford Circus.")) }
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalRouteStops provides repository) {
                    RouteDetailScreen(
                        row = row,
                        isStarred = false,
                        starrable = false,
                        disruptionUnknown = false,
                        stale = false,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stop list unavailable").assertIsDisplayed()
        // Resolving the route asks for each direction once; nothing asks again.
        assertEquals(calls.distinct(), calls)
        assertTrue(calls.isNotEmpty())
    }

    @Test
    fun stopListForALineTfLDoesntKnow_saysUnavailableWithNoRetry() {
        // TfL answers 404 for a line it has no entry for (a National Rail service it doesn't carry):
        // asking again can't help, so the page says unavailable rather than offer a Retry.
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                    throw TflException.NotFound(null)
            },
        )
        val row = healthyRow()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalRouteStops provides repository) {
                    RouteStopsSection(
                        state = rememberRouteStops(row, row.upcoming.first(), retry = 0),
                        railColor = androidx.compose.ui.graphics.Color.Blue,
                        onRetry = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stop list unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    // Public network facts for the Victoria line example: the lines a rider can change to.
    private val victoriaLineConnections = mapOf(
        "Green Park" to listOf(LineRef("jubilee", "Jubilee", "tube"), LineRef("piccadilly", "Piccadilly", "tube")),
        "Oxford Circus" to listOf(LineRef("bakerloo", "Bakerloo", "tube"), LineRef("central", "Central", "tube")),
        "Warren Street" to listOf(LineRef("northern", "Northern", "tube")),
        "Euston" to listOf(LineRef("northern", "Northern", "tube"), LineRef("lioness", "Lioness", "overground")),
        "King's Cross St. Pancras" to listOf(
            LineRef("circle", "Circle", "tube"),
            LineRef("hammersmith-city", "Hammersmith & City", "tube"),
            LineRef("metropolitan", "Metropolitan", "tube"),
            LineRef("northern", "Northern", "tube"),
            LineRef("piccadilly", "Piccadilly", "tube"),
        ),
        "Highbury & Islington" to listOf(LineRef("mildmay", "Mildmay", "overground"), LineRef("windrush", "Windrush", "overground")),
        "Finsbury Park" to listOf(LineRef("piccadilly", "Piccadilly", "tube")),
        "Seven Sisters" to listOf(LineRef("weaver", "Weaver", "overground")),
        "Walthamstow Central" to listOf(LineRef("weaver", "Weaver", "overground")),
    )

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 2400) {
        val recording = System.getProperty("roborazzi.test.record") == "true"
        val verifying = System.getProperty("roborazzi.test.verify") == "true"
        if (!recording && !verifying) return

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
}
