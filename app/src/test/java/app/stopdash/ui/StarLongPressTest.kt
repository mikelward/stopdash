package app.stopdash.ui

import androidx.activity.ComponentActivity
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Starring a departure is a long-press on the card, not a per-row button (SPEC D8): the button
 * ate width on every row, so it was removed entirely — a starred card carries no in-row element,
 * only a gold border and its position at the top. A long-press on a timed card toggles the pin
 * and carries the "Pin to top" / "Unpin from top" accessibility label. The gold border is a
 * paint-only mark with no queryable node, so it's covered by the screenshot tests; the ordering
 * the star drives is covered by [app.stopdash.domain.DepartureRowsPinStarredTest]. This pins the
 * gesture and its wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
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

    @Test
    fun `long-pressing an unstarred card invokes the callback for that row`() {
        var toggled: DepartureRow? = null
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = emptySet(),
                    onToggleStar = { toggled = it },
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        assertEquals(theRow.stopId, toggled?.stopId)
        assertEquals(theRow.lineId, toggled?.lineId)
        assertEquals(theRow.directionKey, toggled?.directionKey)
    }

    @Test
    fun `long-pressing a starred card invokes the callback to unpin it`() {
        var toggled: DepartureRow? = null
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = setOf(StarredRow.of(theRow)),
                    onToggleStar = { toggled = it },
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        assertEquals(theRow.lineId, toggled?.lineId)
    }

    @Test
    fun `on the near-me list a long press opens a menu to pin the row or hide its mode`() {
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
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        // The menu opens; nothing is pinned or hidden until an item is picked.
        assertNull(toggled)
        composeRule.onNodeWithText("Pin to top").assertExists()
        composeRule.onNodeWithText("Hide all Underground services").performClick()
        assertEquals("tube", hidden)
        assertNull(toggled)

        // Or just the row's own line, handed over as its key.
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.onNodeWithText("Hide Victoria line").performClick()
        assertEquals(HiddenModes.lineKey("victoria", "Victoria line"), hidden)

        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        composeRule.onNodeWithText("Pin to top").performClick()
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
    fun `long-press does not toggle when starring is unavailable`() {
        // A newer-schema star file this build can't read: the card isn't long-pressable, so a
        // long-press must not toggle (and there's no gold border to falsely imply a pin).
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
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        assertNull(toggled)
    }
}
