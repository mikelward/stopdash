package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.pointer.pointerInput
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.LineStatus
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.ReplanOrigin
import app.stopdash.domain.RouteDisruption
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A trip on the way (SPEC *On the way*): waiting for the train, on it, told to get off, and arrived.
 * A trip between well-known stations; the times are made up. No user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnTheWayScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")
    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

    private val mildmay = TripLeg("overground", "mildmay", "Mildmay", "910GHGHI", "Highbury & Islington", "910GSTFD", "Stratford", at(4), at(20))
    private val walk = TripLeg(TripLeg.WALKING, "", "", "910GSTFD", "Stratford", "940GZZLUSTD", "Stratford", at(20), at(24))
    private val jubilee = TripLeg("tube", "jubilee", "Jubilee", "940GZZLUSTD", "Stratford", "940GZZLUCYF", "Canary Wharf", at(26), at(33))
    private val trip = ActiveTrip(TripRoute(listOf(mildmay, walk, jubilee)), "Canary Wharf", startedAt = now, vehicleId = "EXAMPLE")

    private fun show(
        trip: ActiveTrip?,
        progress: TripProgress?,
        failed: Boolean = false,
        current: Boolean = true,
        notKept: Boolean = false,
        endFailed: Boolean = false,
        onEnd: () -> Unit = {},
        onBack: () -> Unit = {},
        alertsOff: Boolean = false,
        appOpenOnly: Boolean = false,
        nextTrains: NextTrains? = null,
        onGoTo: (OnTheWay.Step, OnTheWay.Step) -> Unit = { _, _ -> },
        disruptions: List<RouteDisruption.Signal> = emptyList(),
        // As the tracker hands them over, worked out before the screen draws them.
        cards: List<RouteDisruption.Signal> = RouteDisruption.cards(disruptions),
        replanFrom: ReplanOrigin.Stop? = null,
        onPlanAgain: ((ReplanOrigin.Stop) -> Unit)? = null,
        onDismissDisruptions: ((List<RouteDisruption.Signal>) -> Unit)? = null,
    ) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                OnTheWayScreen(
                    trip, progress, failed, now, onEnd, onBack, current = current, notKept = notKept, endFailed = endFailed, alertsOff = alertsOff,
                    appOpenOnly = appOpenOnly, nextTrains = nextTrains, onGoTo = onGoTo, disruptions = disruptions, cards = cards,
                    replanFrom = replanFrom, onPlanAgain = onPlanAgain, onDismissDisruptions = onDismissDisruptions,
                )
            }
        }
    }

    @Test
    fun on_the_way_reads_only_the_disruption_cards_it_draws() {
        // A bus line with many alerts: registered by count, not walked on every recomposition, so the
        // main thread reads only the cards near the screen (the lazy list keys a bounded window around
        // what's shown), however many there are (Codex on #519). Made-up words.
        val signals = (1..1000).map { n ->
            RouteDisruption.Signal.Line(2, "jubilee", "Jubilee", LineStatus("jubilee", 6, "Severe Delays", fullText = "Alert $n."), RouteDisruption.Tier.HIGH, placed = true)
        }
        val read = mutableSetOf<Int>()
        val cards = object : AbstractList<RouteDisruption.Signal>() {
            override val size: Int get() = signals.size
            override fun get(index: Int): RouteDisruption.Signal = signals[index].also { read += index }
        }
        show(trip, TripProgress.Waiting(mildmay, at(4)), disruptions = signals, cards = cards)
        composeRule.onNodeWithText("Alert 1.").assertIsDisplayed()
        assertTrue("read ${read.size} of 1000", read.size < 200)
    }

    @Test
    fun on_the_way_says_where_the_route_is_disrupted() {
        // What tapping the route disruption alert opens: where and how, not only "Part Suspended"
        // (maintainer, 2026-10-01). The alert's words are made up.
        val status = LineStatus("jubilee", 3, "Part Suspended", fullText = "No service between Stratford and Canary Wharf while we fix a signal failure.")
        val signal = RouteDisruption.Signal.Line(2, "jubilee", "Jubilee", status, RouteDisruption.Tier.HIGH, placed = true)
        show(trip, TripProgress.Waiting(mildmay, at(4)), disruptions = listOf(signal, signal.copy(legIndex = 1)))
        composeRule.onNodeWithText("Jubilee: Part Suspended").assertIsDisplayed()
        composeRule.onNodeWithText(status.fullText!!).assertIsDisplayed()
        // The ride it's on, as its step below reads.
        assertEquals(2, composeRule.onAllNodesWithText("Stratford → Canary Wharf").fetchSemanticsNodes().size)
        // The same alert on two legs reads once.
        assertEquals(1, composeRule.onAllNodesWithText("Jubilee: Part Suspended").fetchSemanticsNodes().size)
        captureSnapshot("on-the-way-disruption.png")
    }

    @Test
    fun a_disruption_offers_to_plan_again_from_the_station_ahead() {
        // The station still ahead nearest the rider (maintainer, 2026-10-02); the alert's words are made up.
        val status = LineStatus("jubilee", 3, "Part Suspended", fullText = "No service between Stratford and Canary Wharf.")
        val signal = RouteDisruption.Signal.Line(2, "jubilee", "Jubilee", status, RouteDisruption.Tier.HIGH, placed = true)
        val from = ReplanOrigin.Stop("910GHGHI", "Highbury & Islington")
        val asked = mutableListOf<ReplanOrigin.Stop>()
        show(trip, TripProgress.Waiting(mildmay, at(4)), disruptions = listOf(signal), replanFrom = from, onPlanAgain = { asked += it })
        composeRule.onNodeWithText("Plan again from Highbury & Islington").assertIsDisplayed().performClick()
        assertEquals(listOf(from), asked)
        captureSnapshot("on-the-way-plan-again.png")
    }

    @Test
    fun keep_going_sits_beside_plan_again() {
        // Read and keeping going (maintainer, 2026-10-03): Keep going hands over every signal shown. Made-up words.
        val status = LineStatus("jubilee", 3, "Part Suspended", fullText = "No service between Stratford and Canary Wharf.")
        val signal = RouteDisruption.Signal.Line(2, "jubilee", "Jubilee", status, RouteDisruption.Tier.HIGH, placed = true)
        val dismissed = mutableListOf<List<RouteDisruption.Signal>>()
        show(
            trip, TripProgress.Waiting(mildmay, at(4)), disruptions = listOf(signal),
            replanFrom = ReplanOrigin.Stop("910GHGHI", "Highbury & Islington"), onPlanAgain = {}, onDismissDisruptions = { dismissed += it },
        )
        composeRule.onNodeWithTag("onTheWayPlanAgain").assertIsDisplayed()
        captureSnapshot("on-the-way-keep-going.png")
        composeRule.onNodeWithText("Keep going").assertIsDisplayed().performClick()
        assertEquals(listOf(listOf(signal)), dismissed)
    }

    @Test
    fun a_disruption_with_nowhere_to_plan_from_still_offers_keep_going() {
        val status = LineStatus("jubilee", 3, "Part Suspended", fullText = "No service between Stratford and Canary Wharf.")
        val signal = RouteDisruption.Signal.Line(2, "jubilee", "Jubilee", status, RouteDisruption.Tier.HIGH, placed = true)
        show(trip, TripProgress.Waiting(mildmay, at(4)), disruptions = listOf(signal), onDismissDisruptions = {})
        composeRule.onNodeWithTag("onTheWayPlanAgain").assertDoesNotExist()
        composeRule.onNodeWithTag("onTheWayKeepGoing").assertIsDisplayed()
    }

    @Test
    fun a_station_the_trip_has_since_passed_isnt_offered() {
        // Worked out before the trip moved on to the Jubilee: Highbury & Islington is behind the rider now.
        val status = LineStatus("jubilee", 3, "Part Suspended", fullText = "No service between Stratford and Canary Wharf.")
        val signal = RouteDisruption.Signal.Line(2, "jubilee", "Jubilee", status, RouteDisruption.Tier.HIGH, placed = true)
        show(trip.copy(legIndex = 2), TripProgress.Waiting(jubilee, at(26)), disruptions = listOf(signal), replanFrom = ReplanOrigin.Stop("910GHGHI", "Highbury & Islington"), onPlanAgain = {})
        composeRule.onNodeWithTag("onTheWayPlanAgain").assertDoesNotExist()
    }

    @Test
    fun nothing_known_wrong_offers_no_plan_again() {
        show(trip, TripProgress.Waiting(mildmay, at(4)), replanFrom = ReplanOrigin.Stop("910GHGHI", "Highbury & Islington"), onPlanAgain = {})
        composeRule.onNodeWithTag("onTheWayPlanAgain").assertDoesNotExist()
    }

    @Test
    fun a_disruption_card_shows_the_ride_as_taken() {
        // Another of the ride's lines' train followed: the signals are that ride's, its line and its own
        // stops, and so is the card's footer (Codex, PR #453). The stops are made up for the test.
        val taken = jubilee.copy(lineId = "circle", lineName = "Circle", toId = "940GZZLUWHM", toName = "West Ham")
        val following = trip.copy(legIndex = 2, vehicleId = "EXAMPLE", vehicleLeg = taken)
        val status = LineStatus("circle", 6, "Severe Delays", fullText = "Severe delays while we fix a signal failure.")
        val line = RouteDisruption.Signal.Line(2, "circle", "Circle", status, RouteDisruption.Tier.MEDIUM)
        val stop = RouteDisruption.Signal.Stop(2, "940GZZLUWHM", "West Ham", closed = true)
        show(following, TripProgress.Waiting(jubilee, at(26)), disruptions = listOf(stop, line))
        composeRule.onNodeWithText("Circle: Severe Delays").assertIsDisplayed()
        composeRule.onNodeWithText("West Ham closed").assertIsDisplayed()
        // Both cards name the ride taken, its pill announcing the full line name.
        assertEquals(2, composeRule.onAllNodesWithText("Stratford → West Ham").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithContentDescription("Circle", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun on_the_way_says_when_get_off_alerts_are_off() {
        show(trip, TripProgress.Waiting(mildmay, at(4)), alertsOff = true)
        composeRule.onNodeWithText("Get-off alerts are off").assertIsDisplayed()
    }

    @Test
    fun on_the_way_says_when_it_can_follow_only_while_open() {
        show(trip, TripProgress.Waiting(mildmay, at(4)), appOpenOnly = true)
        composeRule.onNodeWithText("Updates only while the app is open").assertIsDisplayed()
    }

    @Test
    fun the_main_view_pins_the_trip_on_the_way_and_opens_it() {
        var opened = false
        val state = OnTheWayBannerState(trip.copy(boarded = true, onBoardSeen = true), TripProgress.Riding(mildmay, "Hackney Central", 4, at(16), getOffSoon = false), now) {
            opened = true
        }
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    MainScreen(DeparturesUiState.Loaded(emptyList(), now), now, {})
                }
            }
        }
        composeRule.onNodeWithText("To Canary Wharf").assertIsDisplayed()
        composeRule.onNodeWithText("Ride to Stratford").assertIsDisplayed()
        captureSnapshot("on-the-way-banner.png")
        composeRule.onNodeWithText("Ride to Stratford").performClick()
        assertTrue(opened)
    }

    @Test
    fun the_pinned_card_doesnt_show_an_old_answer_as_live() {
        // Last brought up to date ten minutes ago: its "next stop" is no longer known.
        val state = OnTheWayBannerState(trip.copy(boarded = true, onBoardSeen = true), TripProgress.Riding(mildmay, "Stratford", 1, at(1), getOffSoon = true), at(-10)) {}
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    MainScreen(DeparturesUiState.Loaded(emptyList(), now), now, {})
                }
            }
        }
        // The stop being next is no longer known either, so it's the ride, not "Get off at" (Codex, PR #456).
        composeRule.onNodeWithText("Ride to Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("Updating…").assertIsDisplayed()
    }

    @Test
    fun the_location_gate_pins_the_trip_too() {
        // Location denied, so the near-me list never comes up: the trip is still a tap away.
        var opened = false
        val state = OnTheWayBannerState(trip, TripProgress.Waiting(mildmay, at(4)), now) { opened = true }
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    LocationGate(NearbyStopsViewModel.State.PermissionRequired, onAllow = {}, onRetry = {}, onOpenSettings = {})
                }
            }
        }
        composeRule.onNodeWithText("To Canary Wharf").performClick()
        assertTrue(opened)
    }

    @Test
    fun the_location_gate_pins_the_trip_card_at_the_top() {
        // While near me is being found: at the top under the app bar, where the near-me list pins it
        // (maintainer, 2026-09-29), not centered with the spinner.
        val state = OnTheWayBannerState(trip, TripProgress.Waiting(mildmay, at(4)), now) {}
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    LocationGate(NearbyStopsViewModel.State.Locating, onAllow = {}, onRetry = {}, onOpenSettings = {}, now = now)
                }
            }
        }
        captureSnapshot("on-the-way-gate-locating.png")
        val card = composeRule.onNodeWithText("To Canary Wharf").getUnclippedBoundsInRoot()
        val finding = composeRule.onNodeWithText("Finding stops near you…").getUnclippedBoundsInRoot()
        assertTrue(card.top < androidx.compose.ui.unit.Dp(64f + 16f))
        // The gate's own content stays centered in the room below it.
        assertTrue(finding.top > androidx.compose.ui.unit.Dp(300f))
    }

    @Test
    fun the_location_gate_card_scrolls_with_the_gate_on_a_short_window() {
        // Landscape-short with large text: pinned, the card could leave the gate no room, and its
        // action out of reach. It scrolls with the gate instead, and the action is still there.
        val state = OnTheWayBannerState(trip, TripProgress.Waiting(mildmay, at(4)), now) {}
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                val base = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalOnTheWayBanner provides state,
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale = 2f),
                ) {
                    LocationGate(
                        NearbyStopsViewModel.State.PermissionRequired,
                        onAllow = {},
                        onRetry = {},
                        onOpenSettings = {},
                        modifier = androidx.compose.ui.Modifier.height(androidx.compose.ui.unit.Dp(360f)),
                        now = now,
                    )
                }
            }
        }
        composeRule.onNodeWithText("Allow location").performScrollTo().assertIsDisplayed()
        // Scrolled to the action, the card has gone up with the rest of the gate: it isn't pinned.
        assertTrue(composeRule.onNodeWithText("To Canary Wharf").getUnclippedBoundsInRoot().top < androidx.compose.ui.unit.Dp(0f))
    }

    @Test
    fun the_location_gate_card_reads_the_clock_it_is_given() {
        // Updated just now by the clock the gate is handed: live, not "Updating…".
        val state = OnTheWayBannerState(trip, TripProgress.Waiting(mildmay, at(4)), now) {}
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    LocationGate(NearbyStopsViewModel.State.PermissionRequired, onAllow = {}, onRetry = {}, onOpenSettings = {}, now = now)
                }
            }
        }
        composeRule.onNodeWithText("Due in 4 min").assertIsDisplayed()
    }

    @Test
    fun a_station_page_leaves_the_trip_card_to_the_near_me_list() {
        val state = OnTheWayBannerState(trip, TripProgress.Waiting(mildmay, at(4)), now) {}
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOnTheWayBanner provides state) {
                    MainScreen(DeparturesUiState.Loaded(emptyList(), now), now, {}, stationTitle = "Highbury & Islington")
                }
            }
        }
        composeRule.onNodeWithText("To Canary Wharf").assertDoesNotExist()
    }

    @Test
    fun on_the_way_waiting_for_the_train() {
        show(trip, TripProgress.Waiting(mildmay, at(4)))
        composeRule.onNodeWithText("Board Mildmay at Highbury & Islington").assertIsDisplayed()
        // When it's due as a clock time, then how long (maintainer, 2026-10-03).
        composeRule.onNodeWithText("4 min · 08:06").assertIsDisplayed()
        // The walk between the rides stays within Stratford, so it has no row: the Jubilee's card names
        // where to go (maintainer, 2026-10-03). Nor the word "Walk" anywhere.
        composeRule.onNodeWithContentDescription("Walk").assertDoesNotExist()
        composeRule.onNodeWithText("Walk").assertDoesNotExist()
        captureSnapshot("on-the-way-waiting.png")
    }

    @Test
    fun a_walk_between_two_places_is_a_walker_row() {
        // Both directions of the rule above: a walk to another place keeps its row, a walker in the
        // pills' column rather than the word "Walk".
        val across = walk.copy(toId = "940GZZLUCYF", toName = "Canary Wharf")
        show(ActiveTrip(TripRoute(listOf(mildmay, across)), "Canary Wharf", startedAt = now), TripProgress.Waiting(mildmay, at(4)))
        composeRule.onNodeWithContentDescription("Walk").assertIsDisplayed()
        composeRule.onNodeWithText("Stratford → Canary Wharf").assertIsDisplayed()
        composeRule.onNodeWithText("Walk").assertDoesNotExist()
    }

    @Test
    fun on_the_way_with_the_app_menu() {
        // As the activity hosts it: the app's overflow at the bar's end, for a bug report.
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalAppMenu provides AppMenuActions(false, {}, {}, {})) {
                    OnTheWayScreen(trip, TripProgress.Waiting(mildmay, at(4)), false, now, {}, {})
                }
            }
        }
        composeRule.onNodeWithContentDescription("More options").assertIsDisplayed()
        captureSnapshot("on-the-way-app-menu.png")
    }

    @Test
    fun on_the_way_due_reads_as_the_board_below_it_does() {
        // Four and a half minutes away: the card and the board under it name the same train, so
        // they round alike rather than reading 5 and 4 (maintainer's report, 2026-09-29).
        val due = at(4).plusSeconds(30)
        val train = Departure("mildmay", "Mildmay", "outbound", "Stratford", null, due, "overground")
        show(trip, TripProgress.Waiting(mildmay, due), nextTrains = NextTrains(mildmay, listOf(train), readyAt = now))
        composeRule.onNodeWithText("4 min · 08:06").assertIsDisplayed()
        composeRule.onNodeWithText("4 min").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Due in 5 min").fetchSemanticsNodes().isEmpty())
    }

    private fun jubileeTrain(destination: String, minutes: Long) =
        Departure("jubilee", "Jubilee", "outbound", destination, null, at(minutes), "tube")

    @Test
    fun on_the_way_walking_shows_the_next_rides_trains() {
        val walking = trip.copy(legIndex = 1)
        val trains = listOf(jubileeTrain("Stanmore", 21), jubileeTrain("Stanmore", 24), jubileeTrain("Wembley Park", 27))
        // The walk ends at 24: the 21-minute train leaves first, so it's grayed.
        show(walking, TripProgress.Walking(walk, at(24)), nextTrains = NextTrains(jubilee, trains, readyAt = at(24)))
        composeRule.onNodeWithText("21 · 24 min").assertIsDisplayed()
        // Right under the card, over the route (maintainer, 2026-10-03): the board the rider is heading for.
        val board = composeRule.onNodeWithTag("onTheWayTrains").getUnclippedBoundsInRoot()
        val card = composeRule.onNodeWithTag("onTheWayNext").getUnclippedBoundsInRoot()
        val ride = composeRule.onNodeWithText("Stratford → Canary Wharf").getUnclippedBoundsInRoot()
        assertTrue(board.top >= card.bottom && board.bottom <= ride.top)
        // Nothing to open from here: no row takes a tap or announces one.
        assertTrue(composeRule.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("Stanmore"))).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Wembley Park").assertIsDisplayed()
        captureSnapshot("on_the_way_walking_next_trains")
    }

    @Test
    fun a_bus_ride_board_is_headed_by_its_stop_letter_as_the_main_view_heads_it() {
        // Headed "Stratford – Stop D", as the main view heads that pole, not by where the buses go
        // (maintainer, 2026-09-29).
        val bus = TripLeg("bus", "25", "25", "490000000001D", "Stratford", "490000000002A", "Bow", at(4), at(20))
        val buses = listOf(Departure("25", "25", "outbound", "Bow", null, at(6), "bus"))
        show(trip.copy(route = TripRoute(listOf(bus))), TripProgress.Waiting(bus, at(6)), nextTrains = NextTrains(bus, buses, readyAt = now, stopLetter = "D", towards = "Bow"))
        composeRule.onNodeWithText("Stop D", substring = true).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("➔", substring = true).fetchSemanticsNodes().isEmpty())
        captureSnapshot("on-the-way-bus-board-stop-letter.png")
    }

    @Test
    fun a_letterless_bus_board_is_headed_by_its_towards_never_a_bus_destination() {
        // A pole TfL gives no letter: its sign's "towards" heads the board, not where its buses go.
        val bus = TripLeg("bus", "25", "25", "490000000001D", "Stratford", "490000000002A", "Liverpool Street", at(4), at(20))
        val buses = listOf(Departure("25", "25", "outbound", "Oxford Circus", null, at(6), "bus"))
        show(trip.copy(route = TripRoute(listOf(bus))), TripProgress.Waiting(bus, at(6)), nextTrains = NextTrains(bus, buses, readyAt = now, towards = "Liverpool Street Or Bank", bearing = "W"))
        composeRule.onNodeWithText("➔ Liverpool Street", substring = true).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("➔ Oxford Circus", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun a_bus_board_with_no_letter_or_towards_is_headed_by_its_bearing() {
        // No towards: the bearing, and never the bus's destination.
        val bus = TripLeg("bus", "25", "25", "490000000001D", "Stratford", "490000000002A", "Liverpool Street", at(4), at(20))
        val buses = listOf(Departure("25", "25", "outbound", "Oxford Circus", null, at(6), "bus"))
        show(trip.copy(route = TripRoute(listOf(bus))), TripProgress.Waiting(bus, at(6)), nextTrains = NextTrains(bus, buses, readyAt = now, bearing = "W"))
        composeRule.onNodeWithText("Westbound", substring = true).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("➔ Oxford Circus", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun a_bus_board_shows_the_other_pole_of_the_pair_under_its_own_letter() {
        // The 25 boards at Stop D; the 86 runs the same way from Stop E, across the road: each pole's
        // buses under its own header, the ride's own first.
        val bus = TripLeg("bus", "25", "25", "490000000001D", "Stratford", "490000000002A", "Bow", at(4), at(20))
        val buses = listOf(Departure("25", "25", "outbound", "Bow", null, at(6), "bus"))
        val across = PoleTrains("490000000001E", "Stratford", listOf(Departure("86", "86", "outbound", "Bow", null, at(8), "bus")), stopLetter = "E", towards = "Bow")
        show(
            trip.copy(route = TripRoute(listOf(bus))), TripProgress.Waiting(bus, at(6)),
            nextTrains = NextTrains(bus, buses, readyAt = now, stopLetter = "D", towards = "Bow", others = listOf(across)),
        )
        val own = composeRule.onNodeWithText("Stop D", substring = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        val other = composeRule.onNodeWithText("Stop E", substring = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(other.top >= own.bottom)
        composeRule.onNodeWithText("86").assertIsDisplayed()
        captureSnapshot("on-the-way-bus-board-pair-poles.png")
    }

    @Test
    fun a_board_row_with_nothing_to_open_leaves_the_touch_to_what_holds_it() {
        // No tap and no long press: the row takes no gesture at all, so a touch goes through.
        var touched = false
        val group = app.stopdash.domain.StopGrouping.groupByStop(
            app.stopdash.domain.DepartureRows.forStop("940GZZLUSTD", "Stratford", listOf(jubileeTrain("Stanmore", 21)), now),
        ).single()
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.pointerInput(Unit) { detectTapGestures(onTap = { touched = true }) },
                ) {
                    StopGroupCard(group, now, starred = emptySet(), onToggleStar = {}, starringAvailable = false, onOpenDetail = null)
                }
            }
        }
        composeRule.onNodeWithText("Stanmore").performTouchInput { click() }
        assertTrue(touched)
    }

    @Test
    fun on_the_way_next_trains_too_old_say_updating() {
        val walking = trip.copy(legIndex = 1)
        show(walking, TripProgress.Walking(walk, at(24)), nextTrains = NextTrains(jubilee, listOf(jubileeTrain("Stanmore", 21)), stale = true))
        composeRule.onNodeWithText("Updating…").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("21 min").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun on_the_way_no_next_trains_says_so() {
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(24)), nextTrains = NextTrains(jubilee, emptyList()))
        composeRule.onNodeWithText("None going to Canary Wharf").assertIsDisplayed()
    }

    @Test
    fun on_the_way_next_trains_say_when_an_update_failed() {
        // The last good board still shown, the failure said beside it.
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(24)), nextTrains = NextTrains(jubilee, listOf(jubileeTrain("Stanmore", 21)), failed = true))
        composeRule.onNodeWithText("21 min").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't update just now").assertIsDisplayed()
    }

    @Test
    fun on_the_way_next_trains_never_read_say_so_not_none() {
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(24)), nextTrains = NextTrains(jubilee, emptyList(), failed = true))
        composeRule.onNodeWithText("Couldn't update just now").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("None going to Canary Wharf").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun on_the_way_next_trains_show_loading_before_the_first_board() {
        // Just started: the ride ahead is known, its board not yet in.
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                val next = rememberNextTrains(board = null, now = now, readyAt = at(24), ride = jubilee)
                OnTheWayScreen(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(24)), false, now, {}, {}, nextTrains = next)
            }
        }
        composeRule.onNodeWithText("Loading").assertIsDisplayed()
    }

    @Test
    fun on_the_way_next_trains_say_when_a_line_is_still_being_checked() {
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(24)), nextTrains = NextTrains(jubilee, listOf(jubileeTrain("Stanmore", 21)), pending = true))
        composeRule.onNodeWithText("21 min").assertIsDisplayed()
        composeRule.onNodeWithText("Checking more lines…").assertIsDisplayed()
    }

    @Test
    fun on_the_way_next_trains_that_couldnt_be_checked_arent_called_none() {
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(24)), nextTrains = NextTrains(jubilee, emptyList(), unresolved = true))
        composeRule.onNodeWithText("Couldn't check every line").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("None going to Canary Wharf").fetchSemanticsNodes().isEmpty())
    }

    // [text] on the next step's card, not the step of the same name in the list below it.
    private fun onCard(text: String) =
        composeRule.onNode(hasText(text) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("onTheWayNext")))

    @Test
    fun on_the_way_on_the_train() {
        show(trip.copy(boarded = true, onBoardSeen = true), TripProgress.Riding(mildmay, "Hackney Central", 4, at(16), getOffSoon = false))
        onCard("Ride to Stratford").assertIsDisplayed()
        // The time left on the ride, as its stop is predicted (maintainer, 2026-09-29).
        composeRule.onNodeWithText("16 min · 08:18").assertIsDisplayed()
        // The stops beside the time, the minutes not said twice (maintainer, 2026-10-03).
        onCard("4 stops").assertIsDisplayed()
        captureSnapshot("on-the-way-riding.png")
    }

    @Test
    fun on_the_way_train_gone_but_rider_not_seen_on_it_still_says_take_it() {
        // The train followed has left, but no fix or word says the rider is on it: underground at the
        // boarding station, they look the same (maintainer, 2026-09-29). Still the ride's step, with its
        // board, until location or Next says they're on.
        val train = Departure("mildmay", "Mildmay", "outbound", "Stratford", null, at(6), "overground")
        show(
            trip.copy(boarded = true),
            TripProgress.Riding(mildmay, "Hackney Central", 4, at(16), getOffSoon = false, seen = false),
            nextTrains = NextTrains(mildmay, listOf(train), readyAt = now),
        )
        onCard("Take the train to Stratford").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("4 stops · next Hackney Central").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("6 min").assertIsDisplayed()
        captureSnapshot("on-the-way-train-left-not-seen-on-it.png")
    }

    @Test
    fun on_the_way_get_off_soon() {
        show(trip.copy(boarded = true, onBoardSeen = true), TripProgress.Riding(mildmay, "Stratford", 1, at(1), getOffSoon = true))
        // The card says the moment; the ride's own row still names the ride (maintainer, 2026-10-01).
        onCard("Get off at Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("Highbury & Islington → Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("1 min · 08:03").assertIsDisplayed()
        onCard("Next stop").assertIsDisplayed()
        captureSnapshot("on-the-way-get-off.png")
    }

    @Test
    fun get_off_soon_says_its_time_seen_on_board_or_not() {
        // Underground, the rider may never be seen on board: told to get off, the time still shows.
        show(trip.copy(boarded = true), TripProgress.Riding(mildmay, "Stratford", 1, at(1), getOffSoon = true, seen = false))
        onCard("Get off at Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("1 min · 08:03").assertIsDisplayed()
        onCard("Next stop").assertIsDisplayed()
    }

    @Test
    fun a_walk_says_how_far_is_left() {
        // Where a stop count would be on a ride, the distance left to the walk's end (maintainer, 2026-10-03).
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(3), metersLeft = 450.0))
        onCard("450 m").assertIsDisplayed()
        composeRule.onNodeWithText("3 min · 08:05").assertIsDisplayed()
    }

    @Test
    fun a_walks_distance_waits_for_the_riders_units() {
        // The units chosen still loading: no distance in the wrong ones (Codex, PR #521).
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDistanceSystem provides null) {
                StopDashTheme(dynamicColor = false) {
                    OnTheWayScreen(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(3), metersLeft = 450.0), false, now, {}, {}, onGoTo = { _, _ -> })
                }
            }
        }
        assertTrue(composeRule.onAllNodesWithText("450 m").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("3 min · 08:05").assertIsDisplayed()
    }

    @Test
    fun a_walk_past_its_time_says_only_how_far_is_left() {
        // The walk to the destination runs on until the rider is seen there: no "About 0 min".
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(-2), metersLeft = 120.0))
        onCard("120 m").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("About 0 min").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun a_step_time_gone_by_isnt_shown() {
        // The walk's end has passed before the next update: its own words, not "0 min · 08:00".
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(-2)))
        assertTrue(composeRule.onAllNodesWithText("0 min · 08:00").fetchSemanticsNodes().isEmpty())
        assertEquals(null, stepTime(TripProgress.Walking(walk, at(-2)), current = true, now))
        assertEquals(at(3), stepTime(TripProgress.Walking(walk, at(3)), current = true, now))
    }

    @Test
    fun on_the_way_a_change_shows_its_time() {
        // A change between two rides with no walk of its own, as the route shows it: "N min to change".
        show(trip.copy(legIndex = 2, vehicleId = ""), TripProgress.Changing(jubilee, at(3)))
        composeRule.onNodeWithText("Change to Jubilee at Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("3 min · 08:05").assertIsDisplayed()
    }

    @Test
    fun on_the_way_claims_no_time_left_beyond_the_predictions() {
        // The stop beyond TfL's predictions: its stops counted from the plan, no time claimed.
        show(trip.copy(boarded = true), TripProgress.Riding(mildmay, "Hackney Central", 4, null, getOffSoon = false))
        composeRule.onNodeWithText("4 stops · next Hackney Central").assertIsDisplayed()
    }

    @Test
    fun on_the_way_stops_left_unknown_names_the_next_stop() {
        // A bus beyond its predictions: on it, its stops left not counted.
        show(trip.copy(boarded = true, onBoardSeen = true), TripProgress.Riding(mildmay, "Hackney Central", null, null, getOffSoon = false))
        composeRule.onNodeWithText("Next: Hackney Central").assertIsDisplayed()
    }

    @Test
    fun on_the_way_an_old_answer_isnt_shown_as_live() {
        // Back after a while away: the last answer said get off next, but that's no longer known.
        show(trip.copy(boarded = true, onBoardSeen = true), TripProgress.Riding(mildmay, "Stratford", 1, at(1), getOffSoon = true), current = false)
        // Nor told to get off now: the step is the ride until the next answer (Codex, PR #456).
        onCard("Ride to Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("Get off at Stratford").assertDoesNotExist()
        composeRule.onNodeWithText("Updating…").assertIsDisplayed()
        // Only taken to be on board, it's still the ride, not boarding again (Codex, PR #456).
        val unseen = TripProgress.Riding(mildmay, "Stratford", 1, at(1), getOffSoon = true, seen = false)
        assertEquals("Ride to Stratford", nextStepText(composeRule.activity.resources, unseen, now, current = false).first)
        composeRule.onNodeWithText("Next stop").assertDoesNotExist()
    }

    @Test
    fun next_and_a_tapped_step_put_the_rider_at_that_step() {
        val went = mutableListOf<Pair<OnTheWay.Step, OnTheWay.Step>>()
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(3)), onGoTo = { from, to -> went += from to to })
        captureSnapshot("on-the-way-next.png")
        // The walk within Stratford is no step (maintainer, 2026-10-03): the rider is at boarding the
        // Jubilee meanwhile, so Next is getting on it.
        val walking = OnTheWay.Step(2)
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoNext")).performClick()
        // Back is getting off the ride before it: a ride is two steps (maintainer, 2026-09-29).
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoBack")).performClick()
        // A ride is one row (maintainer, 2026-10-03): tapping it goes to boarding it, the Jubilee's
        // included, since the rider is still walking to it.
        composeRule.onNodeWithText("Stratford → Canary Wharf").performClick()
        composeRule.onNodeWithText("Highbury & Islington → Stratford").performClick()
        // No row of its own for the walk.
        assertTrue(composeRule.onAllNodesWithText("Stratford → Stratford").fetchSemanticsNodes().isEmpty())
        assertEquals(
            listOf(
                walking to OnTheWay.Step(2, onBoard = true),
                walking to OnTheWay.Step(0, onBoard = true),
                walking to OnTheWay.Step(2),
                walking to OnTheWay.Step(0),
            ),
            went,
        )
    }

    @Test
    fun next_from_boarding_is_getting_off_and_is_off_on_the_last_step() {
        // Waiting for the last ride's train: Next says they're on it.
        val went = mutableListOf<Pair<OnTheWay.Step, OnTheWay.Step>>()
        show(trip.copy(legIndex = 2), TripProgress.Waiting(jubilee, at(26)), onGoTo = { from, to -> went += from to to })
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoNext")).performClick()
        assertEquals(listOf(OnTheWay.Step(2) to OnTheWay.Step(2, onBoard = true)), went)
    }

    @Test
    fun next_is_off_on_the_last_step() {
        // On the last ride: Next there would arrive and forget the trip, with no Back to undo it.
        show(trip.copy(legIndex = 2, boarded = true, onBoardSeen = true), TripProgress.Riding(jubilee, "Canary Wharf", 1, at(33), getOffSoon = true))
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoNext")).assertIsNotEnabled()
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoBack")).assertIsDisplayed()
    }

    @Test
    fun next_and_back_are_off_on_an_arrival_kept_because_forgetting_it_failed() {
        // Arrived, but the trip couldn't be forgotten: Next mustn't start it over from the first step,
        // and Back can't move an arrival being forgotten (Codex, PR #384). End trip is the way out.
        val went = mutableListOf<Pair<OnTheWay.Step, OnTheWay.Step>>()
        show(trip.copy(legIndex = 3), TripProgress.Arrived, endFailed = true, onGoTo = { from, to -> went += from to to })
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoNext")).assertIsNotEnabled()
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoBack")).assertIsNotEnabled()
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoNext")).performClick()
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoBack")).performClick()
        // Nor is a step's row a way back (Codex, PR #384).
        composeRule.onNodeWithText("Stratford → Canary Wharf").performClick()
        assertTrue(composeRule.onAllNodes(hasClickAction() and androidx.compose.ui.test.hasText("Stratford → Canary Wharf")).fetchSemanticsNodes().isEmpty())
        assertEquals(emptyList<Pair<OnTheWay.Step, OnTheWay.Step>>(), went)
        composeRule.onNodeWithText("End trip").assertIsEnabled()
    }

    @Test
    fun the_trip_buttons_wrap_rather_than_clip_at_large_text() {
        // About the largest text offered (160% of a 200% system size): the buttons wrap, none clipped.
        composeRule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale = 3f),
            ) {
                StopDashTheme(dynamicColor = false) {
                    OnTheWayScreen(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(3)), false, now, {}, {}, onGoTo = { _, _ -> })
                }
            }
        }
        captureSnapshot("on-the-way-buttons-large-text.png")
        // The trip's name keeps its "To" and a letter, however little room its time leaves: never a bare
        // "…" (Codex, PR #518).
        // What's drawn, from the text's own layout (the characters before its "…"), not its backing string.
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithContentDescription("To Canary Wharf").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(layouts)
        val layout = layouts.single()
        val shown = layout.layoutInput.text.text.take(layout.getLineEnd(0, visibleEnd = true))
        assertTrue(shown, shown.startsWith("To C"))
        composeRule.onNodeWithText("End trip").assertIsDisplayed()
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoBack")).assertIsDisplayed()
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoNext")).assertIsDisplayed()
    }

    @Test
    fun the_cards_text_shortens_rather_than_lose_its_time_at_large_text() {
        // At the largest text the trip's name and its time can't both fit: the time stays whole on the
        // row, and the name shortens as a board's place does before any "…" (maintainer, 2026-10-03).
        composeRule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale = 2f),
            ) {
                StopDashTheme(dynamicColor = false) {
                    val last = trip.copy(legIndex = 2, boarded = true, onBoardSeen = true)
                    OnTheWayScreen(last, TripProgress.Riding(jubilee, "Canning Town", 2, at(31), getOffSoon = false), false, now, {}, {}, onGoTo = { _, _ -> })
                }
            }
        }
        captureSnapshot("on-the-way-card-large-text.png")
        val eta = composeRule.onNodeWithTag("onTheWayEta").assertIsDisplayed().assertTextEquals("31 min · ~08:33").getUnclippedBoundsInRoot()
        // Its floor, the "To" whole, on the time's row; the full name stays the screen reader's.
        val name = composeRule.onNodeWithContentDescription("To Canary Wharf").getUnclippedBoundsInRoot()
        assertTrue("$name beside $eta", name.top < eta.bottom && eta.top < name.bottom)
        // The time on one line, as tall as the name's, not wrapped at a fixed share of the row (Codex, PR #518).
        assertTrue("$eta one line beside $name", eta.bottom - eta.top <= (name.bottom - name.top) * 1.2f)
    }

    @Test
    fun a_step_takes_its_abbreviations_rather_than_wrap() {
        // Too long for one line as named, one line abbreviated (synthetic place): shortened rather than
        // wrapped (maintainer, 2026-10-03), the full step the screen reader's.
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk.copy(toName = "Upper Great Junction Market Road"), at(3)))
        onCard("Walk to U. Gt Jct Mkt Rd").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Walk to Upper Great Junction Market Road").assertIsDisplayed()
    }

    @Test
    fun a_long_step_shortens_its_places_before_it_cuts() {
        // "Walk to …" too long for the card's two lines (synthetic places): each shortens as a board's does ("Road" →
        // "Rd"), the words around it kept, the full step the screen reader's (maintainer, 2026-10-03).
        val far = walk.copy(toName = "North Example Road/South Example Road/East Example Road/West Example Road/Upper Example Road")
        show(trip.copy(legIndex = 1), TripProgress.Walking(far, at(3)))
        val full = "Walk to North Example Road/South Example Road/East Example Road/West Example Road/Upper Example Road"
        composeRule.onNodeWithContentDescription(full).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText(full).fetchSemanticsNodes().isEmpty())
        captureSnapshot("on-the-way-long-step.png")
    }

    @Test
    fun back_is_off_on_the_first_leg() {
        show(trip, TripProgress.Waiting(mildmay, at(4)))
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("onTheWayGoBack")).assertIsNotEnabled()
    }

    @Test
    fun on_the_way_says_when_the_trip_couldnt_be_saved() {
        show(trip, TripProgress.Waiting(mildmay, at(4)), notKept = true)
        composeRule.onNodeWithText("Couldn't save the trip on this phone").assertIsDisplayed()
    }

    @Test
    fun on_the_way_says_when_the_trip_couldnt_be_ended() {
        show(trip, TripProgress.Waiting(mildmay, at(4)), endFailed = true)
        composeRule.onNodeWithText("Couldn't end the trip. Try again.").assertIsDisplayed()
    }

    @Test
    fun on_the_way_a_failed_update_says_so() {
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(3)), failed = true)
        composeRule.onNodeWithText("Walk to Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't update just now").assertIsDisplayed()
    }

    @Test
    fun on_the_way_end_trip_ends_it() {
        var ended = false
        show(trip, TripProgress.Waiting(mildmay, null), onEnd = { ended = true })
        composeRule.onNodeWithText("Finding your train…").assertIsDisplayed()
        composeRule.onNodeWithText("End trip").performClick()
        composeRule.runOnIdle { assertTrue(ended) }
    }

    @Test
    fun stops_counted_from_where_the_rider_was_seen_dont_wait_for_tfl() {
        val resources = composeRule.activity.resources
        // Restored with no answer yet: counted from location, not TfL, so shown as they were (Codex, PR #449).
        val byPosition = TripProgress.Riding(mildmay, "Hackney Central", 4, null, getOffSoon = false, byPosition = true)
        assertEquals("4 stops · next Hackney Central", nextStepText(resources, byPosition, now, current = false).second)
        // A train's, from an answer too old to stand behind, still waits.
        val fromTrain = TripProgress.Riding(mildmay, "Hackney Central", 4, null, getOffSoon = false)
        assertEquals("Updating…", nextStepText(resources, fromTrain, now, current = false).second)
    }

    @Test
    fun a_next_stop_not_named_is_left_out_of_the_stops_left() {
        val resources = composeRule.activity.resources
        // Not called by where they get off (Codex, PR #449): the stops alone, timed where predicted.
        assertEquals("4 stops", nextStepText(resources, TripProgress.Riding(mildmay, null, 4, null, getOffSoon = false), now).second)
        assertEquals("4 stops (~16 min)", nextStepText(resources, TripProgress.Riding(mildmay, null, 4, at(16), getOffSoon = false), now).second)
        assertEquals("4 stops · next Hackney Central", nextStepText(resources, TripProgress.Riding(mildmay, "Hackney Central", 4, null, getOffSoon = false), now).second)
        // No stops counted and none named: no detail rather than a guess.
        assertEquals("", nextStepText(resources, TripProgress.Riding(mildmay, null, null, null, getOffSoon = false), now).second)
    }

    @Test
    fun the_trip_says_its_time_left_and_when_it_gets_there() {
        val resources = composeRule.activity.resources
        assertEquals("14 min · est. 08:16", etaText(resources, OnTheWay.Eta(at(14), live = false), now))
        assertEquals("14 min · ~08:16", etaText(resources, OnTheWay.Eta(at(14), live = true), now))
        // On the last ride, TfL's time where they get off; not from an answer too old to stand behind.
        val last = trip.copy(legIndex = 2, boarded = true, onBoardSeen = true)
        val riding = TripProgress.Riding(jubilee, "Canning Town", 2, at(31), getOffSoon = false)
        show(last, riding)
        // On the card, under where the trip goes, over the step at hand (maintainer, 2026-10-03).
        onCard("To Canary Wharf").assertIsDisplayed()
        composeRule.onNodeWithTag("onTheWayEta").assertTextEquals("31 min · ~08:33")
    }

    @Test
    fun a_train_followed_gone_by_times_the_trip_from_the_boards_next() {
        // The train followed past its time, the board's next due in 10 min: timed from it (maintainer, 2026-10-03).
        val gone = TripProgress.Waiting(mildmay, at(-1))
        show(trip, gone, nextTrains = NextTrains(mildmay, emptyList(), readyAt = now, nextDue = at(10)))
        composeRule.onNodeWithTag("onTheWayEta").assertTextEquals("37 min · est. 08:39")
    }

    @Test
    fun another_rides_board_doesnt_time_the_trip() {
        // The board shown is the ride after a walk's: its trains aren't the one the rider waits for.
        show(trip, TripProgress.Waiting(mildmay, at(-1)), nextTrains = NextTrains(jubilee, emptyList(), nextDue = at(10)))
        composeRule.onNodeWithTag("onTheWayEta").assertDoesNotExist()
    }

    @Test
    fun the_trip_doesnt_say_when_it_gets_there_from_an_old_answer() {
        val last = trip.copy(legIndex = 2, boarded = true, onBoardSeen = true)
        show(last, TripProgress.Riding(jubilee, "Canning Town", 2, at(31), getOffSoon = false), current = false)
        composeRule.onNodeWithTag("onTheWayEta").assertDoesNotExist()
    }

    @Test
    fun each_mode_is_looked_for_by_its_own_name() {
        val resources = composeRule.activity.resources
        fun leg(mode: String) = TripLeg(mode, "x", "X", "A", "A", "B", "B", at(4), at(20))
        val expected = mapOf(
            "bus" to "bus", "replacement-bus" to "bus", "coach" to "coach", "tram" to "tram",
            "river-bus" to "boat", "river-tour" to "boat", "cable-car" to "cable car",
            "tube" to "train", "dlr" to "train", "overground" to "train", "elizabeth-line" to "train", "national-rail" to "train",
        )
        expected.forEach { (mode, name) ->
            assertEquals("Finding your $name…", nextStepText(resources, TripProgress.Waiting(leg(mode), null), now).second)
            assertEquals("Can't find your $name", nextStepText(resources, TripProgress.Lost(leg(mode)), now).first)
        }
    }

    @Test
    fun the_step_names_the_line_of_the_train_followed() {
        val resources = composeRule.activity.resources
        val leg = TripLeg("tube", "red", "Red", "A", "Example Station", "B", "B", at(4), at(20))
        // Another of the ride's lines' train followed: that's the line to board (Codex, PR #451).
        assertEquals("Board Blue at Example Station", nextStepText(resources, TripProgress.Waiting(leg, at(6), "Blue"), now).first)
        assertEquals("Board Red at Example Station", nextStepText(resources, TripProgress.Waiting(leg, null), now).first)
    }

    @Test
    fun on_the_way_looks_for_a_bus_as_a_bus() {
        val bus = TripLeg("bus", "134", "134", "490000000001A", "Example Road", "490000000002B", "Example Street", at(4), at(20))
        show(ActiveTrip(TripRoute(listOf(bus)), "Example Street", startedAt = now), TripProgress.Waiting(bus, null))
        composeRule.onNodeWithText("Finding your bus…").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Finding your train…").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun on_the_way_lost_bus_is_a_bus() {
        val bus = TripLeg("bus", "134", "134", "490000000001A", "Example Road", "490000000002B", "Example Street", at(4), at(20))
        show(ActiveTrip(TripRoute(listOf(bus)), "Example Street", startedAt = now), TripProgress.Lost(bus))
        composeRule.onNodeWithText("Can't find your bus").assertIsDisplayed()
        composeRule.onNodeWithText("Finding your bus…").assertIsDisplayed()
    }

    @Test
    fun on_the_way_arrived() {
        var closed = false
        show(null, TripProgress.Arrived, onBack = { closed = true })
        composeRule.onNodeWithText("You've arrived").assertIsDisplayed()
        captureSnapshot("on-the-way-arrived.png")
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertTrue(closed) }
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
