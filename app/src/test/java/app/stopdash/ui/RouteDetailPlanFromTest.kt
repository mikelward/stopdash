package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A route page's Directions plans a trip from the stop it was opened at: the glyph in its bar, To… for a screen
 * reader, shown only where the page is given somewhere to plan from. Stations are well-known; no user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class RouteDetailPlanFromTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-18T08:00:00Z")
    private val row = DepartureRows.across(
        listOf(
            StopArrivals(
                "940GZZLUEUS", "Euston",
                listOf(Departure("victoria", "Victoria", "outbound", "Brixton", "Southbound - Platform 5", now.plusSeconds(180), "tube")),
                fetchedAt = now,
            ),
        ),
        now,
    ).first { it.upcoming.isNotEmpty() }

    private fun show(onPlanFrom: (() -> Unit)?) {
        composeRule.setContent {
            StopDashTheme {
                RouteDetailScreen(
                    row = row, isStarred = false, starrable = true, disruptionUnknown = false, stale = false, now = now,
                    onToggleStar = {}, onBack = {}, routeStops = RouteStopsUi.Loading, onPlanFrom = onPlanFrom,
                )
            }
        }
    }

    @Test
    fun directions_plans_from_the_pages_stop() {
        var planned = 0
        show { planned++ }
        composeRule.onNodeWithContentDescription("To…").performClick()
        composeRule.runOnIdle { assertEquals(1, planned) }
    }

    @Test
    fun no_directions_without_somewhere_to_plan_from() {
        show(null)
        composeRule.onAllNodesWithTag("routePlanFrom").assertCountEquals(0)
    }
}
