package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStop
import app.stopdash.domain.RouteStopsRepository
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineRef
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A long press on a departure's route row is "Add favorite…" (maintainer, 2026-10-23): it opens the row's route page,
 * as a tap does, asking "To…", and a tap on a stop there saves the favorite ride to it ([MainScreen]'s `onAddRide`)
 * and closes the page, its outcome said on the list. The long press carries the "Add favourite ride…" accessibility label.
 * Pins are going: nothing new is pinned, and a row pinned before keeps its gold bar (a paint-only mark covered by the
 * screenshot tests) and is unpinned from its menu or its page. The page's pick mode itself is
 * [RoutePickScreenshotTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StarLongPressTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val stop = StopArrivals(
        stopId = "940GZZLUKSX",
        stopName = "King's Cross St. Pancras",
        departures = listOf(
            Departure("victoria", "Victoria", "southbound", "Brixton", null, now.plusSeconds(120), "tube"),
        ),
        fetchedAt = now,
    )

    // The one row the fixture produces, so the test asserts the callback carries that identity.
    private val theRow: DepartureRow = DepartureRows.across(listOf(stop), now).single()

    private fun loaded() = DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = now)

    // The Victoria line southbound from King's Cross, public stations only.
    private val southbound = LineSequence(
        routes = listOf(
            LineRoute(
                "Walthamstow Central ↔ Brixton",
                listOf("940GZZLUWWL", "940GZZLUKSX", "940GZZLUEUS", "940GZZLUWRR", "940GZZLUOXC", "940GZZLUGPK", "940GZZLUVIC", "940GZZLUBXN"),
            ),
        ),
        stopNames = mapOf(
            "940GZZLUWWL" to "Walthamstow Central", "940GZZLUKSX" to "King's Cross St. Pancras", "940GZZLUEUS" to "Euston",
            "940GZZLUWRR" to "Warren Street", "940GZZLUOXC" to "Oxford Circus", "940GZZLUGPK" to "Green Park",
            "940GZZLUVIC" to "Victoria", "940GZZLUBXN" to "Brixton",
        ),
    )

    @Test
    fun `long-pressing a card opens its route page asking where the ride goes, and a stop tapped saves it`() {
        val added = mutableListOf<Pair<DepartureRow, RouteStop>>()
        var toggled: DepartureRow? = null
        var outcome by mutableStateOf<RideAdded?>(null)
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = southbound
            },
            io = Dispatchers.Main,
            compute = Dispatchers.Main,
        )
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalRouteStops provides repository, LocalWorker provides Dispatchers.Main) {
                    MainScreen(
                        state = loaded(),
                        now = now,
                        onRefresh = {},
                        starred = emptySet(),
                        onToggleStar = { toggled = it },
                        onAddRide = { row, _, to -> added += row to to },
                        rideAdded = outcome,
                        onRideAddedShown = { outcome = null },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("routePickTitle").assertDoesNotExist()
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.waitForIdle()
        // The row's own page, asking "To…"; nothing is pinned.
        composeRule.onNodeWithTag("routePickTitle").assertIsDisplayed()
        assertNull(toggled)

        composeRule.onNodeWithText("Warren Street", substring = true).performClick()
        composeRule.waitForIdle()
        assertEquals(theRow.detailKey(), added.single().first.detailKey())
        assertEquals(RouteStop("940GZZLUWRR", "Warren Street"), added.single().second)
        // The page closes to the list, which says how it went.
        composeRule.onNodeWithTag("routePickTitle").assertDoesNotExist()
        composeRule.runOnIdle { outcome = RideAdded("King's Cross St. Pancras", "Warren Street", null) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Added King's Cross St. Pancras ⇄ Warren Street").assertExists()

        // A plain tap still opens the page as it was, its stops opening nothing new and saving nothing.
        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routePickTitle").assertDoesNotExist()
        composeRule.onNodeWithText("Warren Street", substring = true).performClick()
        composeRule.waitForIdle()
        assertEquals(1, added.size)
    }

    @Test
    fun `the long press is labeled Add favourite for a screen reader`() {
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = loaded(), now = now, onRefresh = {}, onAddRide = { _, _, _ -> })
            }
        }
        val labels = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsActions.OnLongClick)?.label }
        assertTrue(labels.toString(), "Add favourite ride…" in labels)
        assertTrue(labels.toString(), "Pin to top" !in labels)
    }

    @Test
    fun `long-pressing a pinned card asks where the ride goes too, never pinning or unpinning`() {
        var toggled: DepartureRow? = null
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = setOf(StarredRow.of(theRow)),
                    onToggleStar = { toggled = it },
                    onAddRide = { _, _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routePickTitle").assertIsDisplayed()
        assertNull(toggled)
        // Back from it returns to the list, where the long press was.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithTag("routePickTitle").assertDoesNotExist()
        composeRule.onNodeWithText("Brixton").assertExists()
    }

    @Test
    fun `on the near-me list a long press opens a menu to add a favorite ride or hide its mode`() {
        var toggled: DepartureRow? = null
        var hidden: String? = null
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = emptySet(),
                    onToggleStar = { toggled = it },
                    onHideMode = { hidden = it },
                    onAddRide = { _, _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        // The menu opens; nothing is opened or hidden until an item is picked, and nothing offers a pin.
        composeRule.onNodeWithTag("routePickTitle").assertDoesNotExist()
        composeRule.onNodeWithText("Add favourite ride…").assertExists()
        composeRule.onNodeWithText("Pin to top").assertDoesNotExist()
        composeRule.onNodeWithText("Unpin from top").assertDoesNotExist()
        composeRule.onNodeWithText("Hide all Underground services").performClick()
        assertEquals("tube", hidden)

        // Or just the row's own line, handed over as its key.
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.onNodeWithText("Hide Victoria line").performClick()
        assertEquals(HiddenModes.lineKey("victoria", "Victoria line"), hidden)

        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.onNodeWithText("Add favourite ride…").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routePickTitle").assertIsDisplayed()
        assertNull(toggled)
    }

    @Test
    fun `a row pinned before pins went is unpinned from its menu`() {
        var toggled: DepartureRow? = null
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = setOf(StarredRow.of(theRow)),
                    onToggleStar = { toggled = it },
                    onHideMode = {},
                    onAddRide = { _, _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.onNodeWithText("Unpin from top").performClick()
        assertEquals(theRow.lineId, toggled?.lineId)
    }

    @Test
    fun `a hide from the menu offers Undo, which shows just what was hidden`() {
        var hidden: String? = null
        val shown = mutableListOf<Pair<String, Boolean>>()
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    onHideMode = { hidden = it },
                    onSetModeGroupShown = { group, show -> shown += group.key to show },
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.onNodeWithText("Hide Victoria line").performClick()
        composeRule.waitForIdle()
        val victoria = HiddenModes.lineKey("victoria", "Victoria line")
        assertEquals(victoria, hidden)
        composeRule.onNodeWithText("Victoria line hidden").assertExists()
        // A second hide takes the first one's offer away: Undo is for the latest.
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.onNodeWithText("Hide all Underground services").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria line hidden").assertDoesNotExist()
        composeRule.onNodeWithText("Underground hidden").assertExists()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("tube" to true), shown)
        composeRule.onNodeWithText("Underground hidden").assertDoesNotExist()
    }

    @Test
    fun `without a way to show it again, a hide offers no Undo`() {
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = loaded(), now = now, onRefresh = {}, onHideMode = {})
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.onNodeWithText("Hide Victoria line").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun `a header's long press offers every mode the place serves, not just those with trains due`() {
        var hidden: String? = null
        val mixed = stop.copy(
            lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("73", "73", "bus")),
        )
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loaded(stops = listOf(mixed), fetchedAt = now),
                    now = now,
                    onRefresh = {},
                    stopDistanceMeters = mapOf(mixed.stopId to 100.0),
                    onHideMode = { hidden = it },
                )
            }
        }
        composeRule.onNodeWithContentDescription("King's Cross St. Pancras", substring = true)
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("Hide all Underground services").assertExists()
        // No bus is due, but the place serves one, so it can still be hidden.
        composeRule.onNodeWithText("Hide all bus services").performClick()
        assertEquals("bus", hidden)
    }

    @Test
    fun `a header offers a mode only a sibling stop of the place serves`() {
        var hidden: String? = null
        // Two stops of one place: this one has the Victoria line due; its sibling serves a bus only.
        val here = stop.copy(clusterId = "HUBEXAMPLE")
        val sibling = StopArrivals(
            stopId = "490000001B",
            stopName = "King's Cross St. Pancras",
            departures = emptyList(),
            fetchedAt = now,
            lines = listOf(LineRef("73", "73", "bus")),
            clusterId = "HUBEXAMPLE",
        )
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loaded(stops = listOf(here, sibling), fetchedAt = now),
                    now = now,
                    onRefresh = {},
                    stopDistanceMeters = mapOf(here.stopId to 100.0, sibling.stopId to 120.0),
                    onHideMode = { hidden = it },
                )
            }
        }
        composeRule.onNodeWithContentDescription("King's Cross St. Pancras", substring = true)
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("Hide all bus services").performClick()
        assertEquals("bus", hidden)
    }

    @Test
    fun `a long press does nothing where the list offers no row actions`() {
        // A board that offers no row actions: nothing happens.
        var toggled: DepartureRow? = null
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = emptySet(),
                    onToggleStar = { toggled = it },
                    starringAvailable = false,
                    onAddRide = { _, _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routePickTitle").assertDoesNotExist()
        assertNull(toggled)
    }

    @Test
    fun `without a way to save a ride a long press does nothing`() {
        var toggled: DepartureRow? = null
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = loaded(), now = now, onRefresh = {}, onToggleStar = { toggled = it })
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routePickTitle").assertDoesNotExist()
        assertNull(toggled)
    }
}
