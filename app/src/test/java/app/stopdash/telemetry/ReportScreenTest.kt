package app.stopdash.telemetry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.UsageEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [ReportScreen]: a screen counted once as it comes up, and again as it changes to another. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReportScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sent = mutableListOf<UsageEvent>()

    @After
    fun reset() = UsageEvents.resetForTest()

    @Test
    fun `a screen is counted as it comes up and as it changes, not on every recomposition`() {
        UsageEvents.consent = { true }
        UsageEvents.install { sent += it }
        var screen by mutableStateOf(UsageEvent.Screen.HOME)
        var tick by mutableStateOf(0)
        composeRule.setContent {
            // Read so a change of it recomposes the screen without changing it.
            tick.toString()
            ReportScreen(screen)
        }
        composeRule.waitForIdle()
        tick++
        composeRule.waitForIdle()
        screen = UsageEvent.Screen.ROUTE
        composeRule.waitForIdle()
        assertEquals(listOf("home", "route"), sent.map { it.params["screen_name"] })
        assertEquals(setOf("screen_view"), sent.map { it.name }.toSet())
    }

    @Test
    fun `a page that takes over from its placeholder is the same visit, counted once`() {
        UsageEvents.consent = { true }
        UsageEvents.install { sent += it }
        var loaded by mutableStateOf(false)
        var screen by mutableStateOf(UsageEvent.Screen.STATION)
        composeRule.setContent {
            // Two reporters of one screen, one taking over from the other, as a station's page does.
            if (!loaded) ReportScreen(UsageEvent.Screen.STATION) else ReportScreen(screen)
        }
        composeRule.waitForIdle()
        loaded = true
        composeRule.waitForIdle()
        // Another screen between counts the station again.
        screen = UsageEvent.Screen.ROUTE
        composeRule.waitForIdle()
        screen = UsageEvent.Screen.STATION
        composeRule.waitForIdle()
        assertEquals(listOf("station", "route", "station"), sent.map { it.params["screen_name"] })
    }
}
