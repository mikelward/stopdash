package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.stopdash.domain.DistanceUnits
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.NearbyGrouping
import app.stopdash.domain.RideGrouping
import app.stopdash.domain.StepFree
import app.stopdash.domain.WalkingSpeed
import app.stopdash.sync.DriveSyncStatus
import app.stopdash.sync.DriveSyncView
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings screen in both toggle states (SPEC D5): the "Automatically update widget" row off
 * (the default) and on. The composable is UI-only — persistence and the WorkManager scheduler are
 * the caller's job — so it renders under Robolectric with no Android services and no user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settings_off() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Automatically update widget").performScrollTo().assertIsDisplayed()
        captureSnapshot("settings-off.png")
    }

    @Test
    fun remembering_wifi_at_saved_places_is_on_by_default_and_a_tap_turns_it_off() {
        val chosen = mutableListOf<Boolean>()
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {}, onRememberWifiChange = { chosen += it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Remember Wi-Fi at saved places").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Fix slow or wrong location at home and at work").assertIsDisplayed()
        composeRule.onNodeWithTag("wifiPlacesSwitch").assertIsOn().performClick()
        assertEquals(listOf(false), chosen)
        // Under the location row it's about.
        val location = composeRule.onNodeWithTag("locationRow").getUnclippedBoundsInRoot().top
        val wifi = composeRule.onNodeWithTag("wifiPlacesSwitch").getUnclippedBoundsInRoot().top
        org.junit.Assert.assertTrue("location above Wi-Fi", location < wifi)
    }

    @Test
    fun remembering_wifi_is_not_offered_until_the_stored_choice_is_read_and_says_when_it_did_not_save() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {},
                    rememberWifi = null, rememberWifiWriteFailed = true,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("wifiPlacesSwitch").performScrollTo().assertIsOff().assertIsNotEnabled()
        composeRule.onNodeWithText("Couldn't save that; it resets when StopDash restarts").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fixing_train_spacing_is_on_by_default_and_a_tap_turns_it_off() {
        val chosen = mutableListOf<Boolean>()
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {}, onTrainSpacingChange = { chosen += it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Fix train spacing").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Adjust times when trains are impossibly close").assertIsDisplayed()
        captureSnapshot("settings-train-spacing.png")
        composeRule.onNodeWithTag("trainSpacingSwitch").assertIsOn().performClick()
        assertEquals(listOf(false), chosen)
        // Above the text size, with the rows that decide what the boards show.
        val spacing = composeRule.onNodeWithTag("trainSpacingSwitch").getUnclippedBoundsInRoot().top
        val distance = composeRule.onNodeWithText("Distance units").getUnclippedBoundsInRoot().top
        org.junit.Assert.assertTrue("spacing above distance units", spacing < distance)
    }

    @Test
    fun favorite_rides_group_by_ride_by_default_and_a_tap_groups_them_by_stop() {
        val chosen = mutableListOf<RideGrouping>()
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {}, onRideGroupingChange = { chosen += it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Group favourite rides").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("rideGrouping-RIDE").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("rideGrouping-STOP").assertIsNotSelected()
        // Each option by its own row: the nearby grouping says "By stop/platform" too.
        composeRule.onNodeWithTag("rideGrouping-RIDE").assert(hasText("By ride")).assertIsDisplayed()
        composeRule.onNodeWithTag("rideGrouping-STOP").assert(hasText("By stop/platform")).assertIsDisplayed()
        captureSnapshot("settings-ride-grouping.png")
        composeRule.onNodeWithTag("rideGrouping-STOP").performClick()
        assertEquals(listOf(RideGrouping.STOP), chosen)
        // Under the train spacing and above the distance units, with the rows that decide how the boards read.
        val spacing = composeRule.onNodeWithTag("trainSpacingSwitch").getUnclippedBoundsInRoot().top
        val grouping = composeRule.onNodeWithTag("rideGrouping-RIDE").getUnclippedBoundsInRoot().top
        val distance = composeRule.onNodeWithText("Distance units").getUnclippedBoundsInRoot().top
        org.junit.Assert.assertTrue("grouping under spacing", spacing < grouping)
        org.junit.Assert.assertTrue("grouping above distance units", grouping < distance)
    }

    @Test
    fun favorite_rides_can_be_grouped_by_station_too() {
        val chosen = mutableListOf<RideGrouping>()
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {}, onRideGroupingChange = { chosen += it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rideGrouping-STATION").performScrollTo().assert(hasText("By station")).assertIsNotSelected()
        composeRule.onNodeWithTag("rideGrouping-STATION").performClick()
        assertEquals(listOf(RideGrouping.STATION), chosen)
    }

    @Test
    fun nearby_departures_group_by_platform_by_default_and_a_tap_groups_them_by_station() {
        val chosen = mutableListOf<NearbyGrouping>()
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {}, onNearbyGroupingChange = { chosen += it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Group nearby departures").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nearbyGrouping-PLATFORM").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("nearbyGrouping-STATION").assertIsNotSelected()
        // By its own row: the favorite rides' grouping says "By stop/platform" too.
        composeRule.onNodeWithTag("nearbyGrouping-PLATFORM").assert(hasText("By stop/platform")).assertIsDisplayed()
        // By its own row too: the favorite rides' grouping offers By station as well.
        composeRule.onNodeWithTag("nearbyGrouping-STATION").assert(hasText("By station")).assertIsDisplayed()
        captureSnapshot("settings-nearby-grouping.png")
        composeRule.onNodeWithTag("nearbyGrouping-STATION").performClick()
        assertEquals(listOf(NearbyGrouping.STATION), chosen)
        // Under the train spacing and above the favorite rides' grouping, with the rows that decide how the boards read.
        val spacing = composeRule.onNodeWithTag("trainSpacingSwitch").getUnclippedBoundsInRoot().top
        val nearby = composeRule.onNodeWithTag("nearbyGrouping-PLATFORM").getUnclippedBoundsInRoot().top
        val rides = composeRule.onNodeWithTag("rideGrouping-RIDE").getUnclippedBoundsInRoot().top
        org.junit.Assert.assertTrue("nearby grouping under spacing", spacing < nearby)
        org.junit.Assert.assertTrue("nearby grouping above ride grouping", nearby < rides)
    }

    @Test
    fun nearby_grouping_selects_nothing_until_the_stored_choice_is_read_and_says_when_it_did_not_save() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {},
                    nearbyGrouping = NearbyGrouping.PLATFORM, nearbyGroupingLoaded = false, nearbyGroupingWriteFailed = true,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nearbyGrouping-PLATFORM").performScrollTo().assertIsNotSelected().assertIsNotEnabled()
        composeRule.onNodeWithTag("nearbyGrouping-STATION").assertIsNotSelected().assertIsNotEnabled()
        composeRule.onNodeWithText("Couldn't save that; it resets when StopDash restarts").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ride_grouping_selects_nothing_until_the_stored_choice_is_read_and_says_when_it_did_not_save() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {},
                    rideGrouping = RideGrouping.RIDE, rideGroupingLoaded = false, rideGroupingWriteFailed = true,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rideGrouping-RIDE").performScrollTo().assertIsNotSelected().assertIsNotEnabled()
        composeRule.onNodeWithTag("rideGrouping-STOP").assertIsNotSelected().assertIsNotEnabled()
        composeRule.onNodeWithText("Couldn't save that; it resets when StopDash restarts").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun train_spacing_is_not_offered_until_the_stored_choice_is_read_and_says_when_it_did_not_save() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {},
                    trainSpacing = null, trainSpacingWriteFailed = true,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("trainSpacingSwitch").performScrollTo().assertIsOff().assertIsNotEnabled()
        composeRule.onNodeWithText("Couldn't save that; it resets when StopDash restarts").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun the_disruptions_summary_comes_third_and_its_page_turns_the_row_off() {
        val chosen = mutableListOf<Boolean>()
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {},
                    showDisruptionsRow = true, onShowDisruptionsRowChange = { chosen += it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Show alerts and delays on the home screen").assertIsDisplayed()
        // Favorite places, then favorite journeys, then the disruptions summary (maintainer, 2026-10-10).
        val places = composeRule.onNodeWithTag("favoritePlacesRow").getUnclippedBoundsInRoot().top
        val journeys = composeRule.onNodeWithTag("favoriteJourneysRow").getUnclippedBoundsInRoot().top
        val disruptions = composeRule.onNodeWithTag("disruptionsSummaryRow").getUnclippedBoundsInRoot().top
        org.junit.Assert.assertTrue("places above journeys", places < journeys)
        org.junit.Assert.assertTrue("journeys above the disruptions summary", journeys < disruptions)
        captureSnapshot("settings-disruptions-summary.png")
        composeRule.onNodeWithTag("disruptionsSummaryRow").performClick()
        composeRule.onNodeWithTag("disruptionsSummaryPage").assertIsDisplayed()
        // Settings itself is gone from under it, so nothing hidden can be reached.
        composeRule.onNodeWithTag("disruptionsSummaryRow").assertDoesNotExist()
        composeRule.onNodeWithTag("disruptionsRowSwitch").performClick()
        org.junit.Assert.assertEquals(listOf(false), chosen)
    }

    @Test
    fun opened_for_the_disruptions_summary_it_opens_on_its_page_and_back_goes_up_to_settings() {
        var backs = 0
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = { backs++ },
                    showDisruptionsRow = true, startOnDisruptions = true,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("disruptionsSummaryPage").assertIsDisplayed()
        composeRule.onNodeWithTag("disruptionsSummaryRow").assertDoesNotExist()
        // Back goes up to Settings, never straight out of it.
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithTag("disruptionsSummaryPage").assertDoesNotExist()
        composeRule.onNodeWithTag("disruptionsSummaryRow").assertIsDisplayed()
        org.junit.Assert.assertEquals(0, backs)
        // And Back from Settings leaves it.
        composeRule.onNodeWithText("Back").performClick()
        org.junit.Assert.assertEquals(1, backs)
    }

    @Test
    fun once_up_from_the_disruptions_summary_settings_composed_again_opens_at_the_top() {
        // As the activity holds it: the flag it opened with, dropped once the page closes.
        var onDisruptions by mutableStateOf(true)
        // Settings off the screen while a page opened from it (favorite places, say) is on top.
        var shown by mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme {
                if (shown) {
                    SettingsScreen(
                        liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {},
                        showDisruptionsRow = true, startOnDisruptions = onDisruptions,
                        onDisruptionsClosed = { onDisruptions = false },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("disruptionsSummaryPage").assertIsDisplayed()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithTag("disruptionsSummaryRow").assertIsDisplayed()
        shown = false
        composeRule.waitForIdle()
        shown = true
        composeRule.waitForIdle()
        // Back where the rider left it: Settings, not the page it was first opened on (Codex, #607).
        composeRule.onNodeWithTag("disruptionsSummaryPage").assertDoesNotExist()
        composeRule.onNodeWithTag("disruptionsSummaryRow").assertIsDisplayed()
    }

    @Test
    fun the_disruptions_summary_says_what_it_does_or_that_its_off() {
        var show by mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {}, showDisruptionsRow = show)
            }
        }
        composeRule.onNodeWithText("Show alerts and delays on the home screen").assertIsDisplayed()
        show = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Off").assertIsDisplayed()
        composeRule.onNodeWithText("Show alerts and delays on the home screen").assertDoesNotExist()
    }

    @Test
    fun the_disruptions_summary_says_nothing_until_its_choices_are_read() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {},
                    showDisruptionsRow = true, showDisruptionsRowLoaded = false,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Off").assertDoesNotExist()
        composeRule.onNodeWithText("Show alerts and delays on the home screen").assertDoesNotExist()
        composeRule.onNodeWithText("–").assertIsDisplayed()
        // Nor does its page open, its controls not yet known.
        composeRule.onNodeWithTag("disruptionsSummaryRow").assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag("disruptionsSummaryPage").assertDoesNotExist()
    }

    @Test
    fun a_restored_disruptions_page_shows_no_controls_until_its_choices_are_read() {
        var loaded by mutableStateOf(false)
        composeRule.setContent {
            StopDashTheme {
                DisruptionsSummaryPage(
                    show = true, onShowChange = {}, showLoaded = loaded, showWriteFailed = false, onDismissShowError = {},
                    networks = setOf("tube"), onToggleLine = {}, networksLoaded = loaded, networksWriteFailed = false,
                    onDismissNetworksError = {}, onBack = {}, nearbyLoaded = loaded, ridesLoaded = loaded,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("disruptionsSummaryPage").assertIsDisplayed()
        composeRule.onNodeWithTag("disruptionsRowSwitch").assertDoesNotExist()
        composeRule.onNodeWithTag("summaryNearbySwitch").assertDoesNotExist()
        composeRule.onNodeWithTag("summaryRidesSwitch").assertDoesNotExist()
        composeRule.onNodeWithTag("summaryLine-victoria").assertDoesNotExist()
        loaded = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("disruptionsRowSwitch").assertIsOn()
        composeRule.onNodeWithTag("summaryNearbySwitch").assertIsOn()
        composeRule.onNodeWithTag("summaryRidesSwitch").assertIsOn()
        composeRule.onNodeWithTag("summaryLine-victoria").assertIsSelected()
    }

    @Test
    fun the_summary_page_turns_the_lines_near_the_rider_off_while_the_row_shows() {
        // On by default, so the row shows something before anything's chosen; off, only the rider's own lines and
        // the chosen ones are watched (maintainer, 2026-10-21).
        val chosen = mutableListOf<Boolean>()
        var show by mutableStateOf(true)
        var nearby by mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme {
                DisruptionsSummaryPage(
                    show = show, onShowChange = {}, showLoaded = true, showWriteFailed = false, onDismissShowError = {},
                    networks = emptySet(), onToggleLine = {}, networksLoaded = true, networksWriteFailed = false,
                    onDismissNetworksError = {}, onBack = {},
                    nearby = nearby, onNearbyChange = { chosen += it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Lines near you").assertIsDisplayed()
        composeRule.onNodeWithText("Watch the lines your home screen shows").assertIsDisplayed()
        // Above the favorite rides' switch.
        val switch = composeRule.onNodeWithTag("summaryNearbySwitch").assertIsOn().getUnclippedBoundsInRoot().top
        val rides = composeRule.onNodeWithTag("summaryRidesSwitch").getUnclippedBoundsInRoot().top
        org.junit.Assert.assertTrue("the nearby switch above the rides'", switch < rides)
        composeRule.onNodeWithTag("summaryNearbySwitch").performClick()
        org.junit.Assert.assertEquals(listOf(false), chosen)
        nearby = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("summaryNearbySwitch").assertIsOff()
        // With the row off, it says nothing, as the rest of what the row watches.
        show = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("summaryNearbySwitch").assertDoesNotExist()
    }

    @Test
    fun a_nearby_lines_choice_that_didnt_save_is_said_under_its_switch() {
        composeRule.setContent {
            StopDashTheme {
                DisruptionsSummaryPage(
                    show = true, onShowChange = {}, showLoaded = true, showWriteFailed = false, onDismissShowError = {},
                    networks = emptySet(), onToggleLine = {}, networksLoaded = true, networksWriteFailed = false,
                    onDismissNetworksError = {}, onBack = {},
                    nearby = false, nearbyWriteFailed = true,
                )
            }
        }
        composeRule.waitForIdle()
        val switch = composeRule.onNodeWithTag("summaryNearbySwitch").getUnclippedBoundsInRoot().bottom
        val error = composeRule.onNodeWithText("Couldn't save that; it resets when StopDash restarts").assertIsDisplayed()
        org.junit.Assert.assertTrue("the error under its switch", switch <= error.getUnclippedBoundsInRoot().top)
    }

    @Test
    fun the_summary_page_turns_the_favorite_rides_off_and_opens_them() {
        // A choice, on by default, where "Always watch" said they always were (maintainer, 2026-10-21).
        val chosen = mutableListOf<Boolean>()
        var show by mutableStateOf(true)
        var rides by mutableStateOf(true)
        var opened = 0
        composeRule.setContent {
            StopDashTheme {
                DisruptionsSummaryPage(
                    show = show, onShowChange = {}, showLoaded = true, showWriteFailed = false, onDismissShowError = {},
                    networks = emptySet(), onToggleLine = {}, networksLoaded = true, networksWriteFailed = false,
                    onDismissNetworksError = {}, onBack = {}, onOpenFavoriteJourneys = { opened++ },
                    rides = rides, onRidesChange = { chosen += it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Always watch").assertDoesNotExist()
        composeRule.onNodeWithText("Favourite rides").assertIsDisplayed()
        composeRule.onNodeWithText("Watch your favourite rides' lines").assertIsDisplayed()
        composeRule.onNodeWithTag("summaryRidesSwitch").assertIsOn().performClick()
        org.junit.Assert.assertEquals(listOf(false), chosen)
        rides = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("summaryRidesSwitch").assertIsOff()
        // The rides stay a tap away either way, under their switch and above the chosen lines.
        val switch = composeRule.onNodeWithTag("summaryRidesSwitch").getUnclippedBoundsInRoot().bottom
        val button = composeRule.onNodeWithTag("summaryFavoriteRides").assertTextEquals("Edit favourite rides")
        val alsoWatch = composeRule.onNodeWithTag("summaryAlsoWatch").getUnclippedBoundsInRoot().top
        org.junit.Assert.assertTrue("the button under the switch", switch <= button.getUnclippedBoundsInRoot().top)
        org.junit.Assert.assertTrue("the button above the chosen lines", button.getUnclippedBoundsInRoot().bottom <= alsoWatch)
        button.performClick()
        org.junit.Assert.assertEquals(1, opened)
        // With the row off, neither shows.
        show = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("summaryRidesSwitch").assertDoesNotExist()
        composeRule.onNodeWithTag("summaryFavoriteRides").assertDoesNotExist()
    }

    @Test
    fun a_favorite_rides_choice_that_didnt_save_is_said_under_its_switch() {
        composeRule.setContent {
            StopDashTheme {
                DisruptionsSummaryPage(
                    show = true, onShowChange = {}, showLoaded = true, showWriteFailed = false, onDismissShowError = {},
                    networks = emptySet(), onToggleLine = {}, networksLoaded = true, networksWriteFailed = false,
                    onDismissNetworksError = {}, onBack = {},
                    rides = false, ridesWriteFailed = true,
                )
            }
        }
        composeRule.waitForIdle()
        val switch = composeRule.onNodeWithTag("summaryRidesSwitch").getUnclippedBoundsInRoot().bottom
        val error = composeRule.onNodeWithText("Couldn't save that; it resets when StopDash restarts").assertIsDisplayed()
        org.junit.Assert.assertTrue("the error under its switch", switch <= error.getUnclippedBoundsInRoot().top)
    }

    @Test
    fun the_summary_always_includes_the_chosen_networks() {
        val tapped = mutableListOf<String>()
        var ridesOpened = 0
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {},
                    summaryNetworks = setOf("tube"), onToggleSummaryLine = { tapped += it },
                    onOpenFavoriteJourneysFromDisruptions = { ridesOpened++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("disruptionsSummaryRow").performClick()
        // The rider's favorite rides are watched, not their places (maintainer, 2026-10-17), a tap away.
        composeRule.onNodeWithTag("summaryRidesSwitch").assertIsOn()
        composeRule.onNodeWithTag("summaryFavoriteRides").assertTextEquals("Edit favourite rides").performClick()
        org.junit.Assert.assertEquals(1, ridesOpened)
        // The lines chosen one by one go under their own heading (maintainer, 2026-10-17).
        composeRule.onNodeWithTag("summaryAlsoWatch").assertTextEquals("Also watch")
        waitForSelected("summaryLine-victoria")
        captureSnapshot("settings-summary-networks.png")
        // A tap names its line; the setting works out the choice ([SummaryNetworksSetting.toggle]).
        composeRule.onNodeWithTag("summaryLine-dlr").performScrollTo().performClick()
        org.junit.Assert.assertEquals(listOf("dlr"), tapped)
    }

    @Test
    fun an_included_line_is_ticked_and_filled_in_its_own_color() {
        // On reads at a glance (maintainer, 2026-10-17): a tick on the chip, none on one left off.
        var chosen by mutableStateOf(setOf("victoria"))
        var chipLabel: TextUnit? = null
        composeRule.setContent {
            StopDashTheme {
                chipLabel = MaterialTheme.typography.labelLarge.fontSize
                DisruptionsSummaryPage(
                    show = true, onShowChange = {}, showLoaded = true, showWriteFailed = false, onDismissShowError = {},
                    networks = chosen, onToggleLine = { chosen = HomeLines.toggle(chosen, it) }, networksLoaded = true, networksWriteFailed = false,
                    onDismissNetworksError = {}, onBack = {},
                )
            }
        }
        waitForSelected("summaryLine-victoria")
        composeRule.onNodeWithTag("summaryLineOn-victoria", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("summaryLineOn-central", useUnmergedTree = true).assertDoesNotExist()
        // Off, a + where the tick goes (maintainer, 2026-10-17), so the chip is as wide either way and turning
        // it on never moves the chips after it (Codex, #923).
        composeRule.onNodeWithTag("summaryLineOff-central", useUnmergedTree = true).assertExists()
        val offWidth = composeRule.onNodeWithTag("summaryLine-central").getUnclippedBoundsInRoot().let { it.right - it.left }
        val nextLeft = composeRule.onNodeWithTag("summaryLine-circle").getUnclippedBoundsInRoot().left
        composeRule.onNodeWithTag("summaryLine-central").performClick()
        waitForSelected("summaryLine-central")
        composeRule.onNodeWithTag("summaryLineOn-central", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("summaryLineOff-central", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(offWidth, composeRule.onNodeWithTag("summaryLine-central").getUnclippedBoundsInRoot().let { it.right - it.left })
        assertEquals(nextLeft, composeRule.onNodeWithTag("summaryLine-circle").getUnclippedBoundsInRoot().left)
        // Every label keeps the chip's own type, on or off, so the halo never widens a chip (Codex, #923).
        fun labelSize(name: String): TextUnit {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.fontSize
        }
        assertEquals(chipLabel, labelSize("Victoria"))
        assertEquals(chipLabel, labelSize("Bakerloo"))
        captureSnapshot("settings-summary-lines-on.png", heightPx = 2400)
    }

    @Test
    fun an_included_line_is_ticked_in_the_dark_too() {
        composeRule.setContent {
            StopDashTheme(darkTheme = true) {
                DisruptionsSummaryPage(
                    show = true, onShowChange = {}, showLoaded = true, showWriteFailed = false, onDismissShowError = {},
                    networks = setOf("victoria", "elizabeth", "windrush", "tram"), onToggleLine = {}, networksLoaded = true, networksWriteFailed = false,
                    onDismissNetworksError = {}, onBack = {},
                )
            }
        }
        waitForSelected("summaryLine-windrush")
        composeRule.onNodeWithTag("summaryLineOn-windrush", useUnmergedTree = true).assertExists()
        captureSnapshot("settings-summary-lines-on-dark.png", heightPx = 2400)
    }

    @Test
    fun the_summary_picks_lines_one_by_one_in_groups() {
        // The tube's Victoria line picked alone, then an Overground line and the DLR (maintainer, 2026-10-06).
        var chosen by mutableStateOf(setOf("victoria"))
        composeRule.setContent {
            StopDashTheme {
                DisruptionsSummaryPage(
                    show = true, onShowChange = {}, showLoaded = true, showWriteFailed = false, onDismissShowError = {},
                    networks = chosen, onToggleLine = { chosen = HomeLines.toggle(chosen, it) }, networksLoaded = true, networksWriteFailed = false,
                    onDismissNetworksError = {}, onBack = {},
                )
            }
        }
        waitForSelected("summaryLine-victoria")
        composeRule.onNodeWithTag("summaryLine-central").assertIsNotSelected()
        captureSnapshot("settings-summary-lines.png", heightPx = 2400)
        composeRule.onNodeWithTag("summaryLine-liberty").performScrollTo().performClick()
        waitForSelected("summaryLine-liberty")
        org.junit.Assert.assertEquals(setOf("victoria", "liberty"), chosen)
        composeRule.onNodeWithTag("summaryLine-dlr").performScrollTo().performClick()
        waitForSelected("summaryLine-dlr")
        org.junit.Assert.assertEquals(setOf("victoria", "liberty", "dlr"), chosen)
        composeRule.onNodeWithTag("summaryLine-victoria").performScrollTo().performClick()
        waitForSelected("summaryLine-victoria", selected = false)
        org.junit.Assert.assertEquals(setOf("liberty", "dlr"), chosen)
    }

    @Test
    fun a_line_choice_that_did_not_save_shows_above_the_chips() {
        composeRule.setContent {
            StopDashTheme {
                DisruptionsSummaryPage(
                    show = true, onShowChange = {}, showLoaded = true, showWriteFailed = false, onDismissShowError = {},
                    networks = setOf("victoria"), onToggleLine = {}, networksLoaded = true, networksWriteFailed = true,
                    onDismissNetworksError = {}, onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
        // Above every chip, so it's in view wherever on the page the tap was (Codex, #642).
        val error = composeRule.onNodeWithText(composeRule.activity.getString(app.stopdash.R.string.settings_disruptions_row_write_failed))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val firstChip = composeRule.onNodeWithTag("summaryLine-bakerloo").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("$error above $firstChip", error.bottom <= firstChip.top)
    }

    /** Waits for a tap's choice, worked out on the worker, to show on its chip. */
    private fun waitForSelected(tag: String, selected: Boolean = true) {
        val state = if (selected) androidx.compose.ui.test.isSelected() else androidx.compose.ui.test.isNotSelected()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag) and state).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun settings_with_the_app_menu() {
        // As the activity hosts it: Back and the app's overflow share the header's end.
        composeRule.setContent {
            StopDashTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalAppMenu provides AppMenuActions(false, {}, {}, {})) {
                    SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        captureSnapshot("settings-app-menu.png")
    }

    @Test
    fun settings_installOnWatch() {
        var installs = 0
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    onInstallOnWatch = { installs++ },
                )
            }
        }
        composeRule.waitForIdle()
        // Below the fold, among the widget and telemetry rows.
        composeRule.onNodeWithTag("watchInstallRow").performScrollTo()
        composeRule.onNodeWithText("Install on watch").assertIsDisplayed()
        captureSnapshot("settings-install-on-watch.png", heightPx = 2400)
        // On its label: scrolled to the edge, its middle sits under the scroll chevron, which pages.
        composeRule.onNodeWithTag("watchInstallRow").performTouchInput { click(centerLeft + Offset(48f, 0f)) }
        assertEquals(1, installs)
    }

    @Test
    fun settings_noWatchToInstallOn() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.onNodeWithTag("watchInstallRow").assertDoesNotExist()
    }

    /**
     * Drive sync (dev-docs/drive-sync.md), offered in debug builds only until the Play Data Safety form says
     * so: under the favorite rides it keeps the same on each phone, off saying what it does, disabled until
     * read, a tap reported. The grant gone, it reads off and says to turn it on again.
     */
    @Test
    fun settings_driveSync() {
        var view by mutableStateOf<DriveSyncView?>(null)
        var latest: Boolean? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    driveSyncOffered = true,
                    driveSync = view,
                    onDriveSyncChange = { latest = it },
                )
            }
        }
        composeRule.onNodeWithTag("driveSyncSwitch").assertIsNotEnabled()
        view = DriveSyncView(false, DriveSyncStatus.Waiting)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("driveSyncSwitch").assertIsOff()
        composeRule.onNodeWithText("Keep your favourite rides the same on each phone").assertIsDisplayed()
        captureSnapshot("settings-drive-sync.png")
        composeRule.onNodeWithText("Sync with Google Drive").performClick()
        composeRule.runOnIdle { assertEquals(true, latest) }
        view = DriveSyncView(true, DriveSyncStatus.SignIn)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("driveSyncSwitch").assertIsOff()
        composeRule.onNodeWithText("Turn on to sign in again").assertIsDisplayed()
        view = DriveSyncView(true, DriveSyncStatus.Offline)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("driveSyncSwitch").assertIsOn()
        composeRule.onNodeWithText("Couldn't reach Google Drive").assertIsDisplayed()
        // Granted, but this phone couldn't keep the switch: it stays off, and says so.
        view = DriveSyncView(false, DriveSyncStatus.TurnOnFailed)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("driveSyncSwitch").assertIsOff()
        composeRule.onNodeWithText("Couldn't turn on sync").assertIsDisplayed()
        view = DriveSyncView(false, DriveSyncStatus.OffNotSaved)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Couldn't save that; it resets when StopDash restarts").assertIsDisplayed()
    }

    @Test
    fun settings_noDriveSyncWhereNotOffered() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.onNodeWithTag("driveSyncSwitch").assertDoesNotExist()
    }

    @Test
    fun settings_on() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = true, onLiveWidgetRefreshChange = {}, onBack = {}, keepUpdatingWhenIdle = false)
            }
        }
        composeRule.waitForIdle()

        captureSnapshot("settings-on.png")
    }

    /**
     * "Keep updating when idle" (SPEC D5): offered only under automatic updates, it shows
     * whether StopDash's battery use is Unrestricted and reports a tap, which opens Android's settings
     * for the app. The tap doesn't flip it: only Android's setting does, read again on return.
     */
    @Test
    fun keep_updating_is_offered_under_automatic_updates_and_shows_the_battery_setting() {
        var automatic by mutableStateOf(false)
        var unrestricted by mutableStateOf<Boolean?>(null)
        var taps = 0
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = automatic, onLiveWidgetRefreshChange = {}, onBack = {},
                    keepUpdatingWhenIdle = unrestricted, onKeepUpdatingWhenIdleClick = { taps++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("keepUpdatingSwitch").assertDoesNotExist()

        automatic = true
        composeRule.waitForIdle()
        // Not read yet: shown, but not offered as off.
        composeRule.onNodeWithTag("keepUpdatingSwitch").performScrollTo().assertIsOff().assertIsNotEnabled()
        unrestricted = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Keep updating when idle").assertIsDisplayed()
        composeRule.onNodeWithText("Uses more battery").assertIsDisplayed()
        composeRule.onNodeWithTag("keepUpdatingSwitch").assertIsOff().performClick()
        assertEquals(1, taps)
        composeRule.onNodeWithTag("keepUpdatingSwitch").assertIsOff()
        // Back from Android's settings with Unrestricted chosen.
        unrestricted = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("keepUpdatingSwitch").assertIsOn()
        // Directly under the switch it depends on.
        val automaticTop = composeRule.onNodeWithTag("liveWidgetSwitch").getUnclippedBoundsInRoot().top
        val keepTop = composeRule.onNodeWithTag("keepUpdatingSwitch").getUnclippedBoundsInRoot().top
        assertTrue(keepTop > automaticTop)
    }

    @Test
    fun settings_scheduleError() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = true,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    liveWidgetRefreshFailed = true,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Couldn't update live refresh").performScrollTo().assertIsDisplayed()
        captureSnapshot("settings-error.png")
    }

    /** The error notice is shown only when scheduling failed, and Dismiss reports the dismissal. */
    @Test
    fun dismissingTheError_reportsIt() {
        var dismissed = false
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = true,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    liveWidgetRefreshFailed = true,
                    onDismissLiveWidgetRefreshError = { dismissed = true },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Dismiss").performScrollTo().performClick()
        composeRule.runOnIdle { assert(dismissed) }
    }

    @Test
    fun noErrorRow_whenSchedulingSucceeded() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = true,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    liveWidgetRefreshFailed = false,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Couldn't update live refresh").assertDoesNotExist()
    }

    /**
     * Until the persisted setting has been read (a slow or persistently-failing DataStore read
     * leaves the flow silent), the switch is disabled so the user can't act on an off value that
     * may not reflect the stored choice (Codex P2 on #56).
     */
    @Test
    fun theSwitchIsDisabled_whileTheSettingHasNotLoaded() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    liveWidgetRefreshEnabled = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("liveWidgetSwitch").assertIsNotEnabled()
    }

    /**
     * The row reflects its state and reports a flip: tapping the row (not just the switch) toggles
     * it, since the whole row is the tap target.
     */
    @Test
    fun tappingTheRow_reportsTheToggle() {
        var latest: Boolean? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = { latest = it },
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Automatically update widget").performScrollTo().performClick()
        composeRule.runOnIdle { assert(latest == true) }
    }

    /** The switch reflects the state passed in — off by default, on when enabled. */
    @Test
    fun theSwitchReflectsTheState() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = true, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("liveWidgetSwitch").assertIsOn()
    }

    /**
     * Crash reports and usage stats wait for the user (SPEC *Privacy*): the switch is off by
     * default, disabled until the stored choice is read, and a tap on its row reports the opt-in.
     */
    @Test
    fun telemetry_isOffByDefault_disabledUntilRead_andATapOptsIn() {
        var telemetry by mutableStateOf<Boolean?>(null)
        var latest: Boolean? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    telemetryOptIn = telemetry,
                    onTelemetryOptInChange = { latest = it },
                )
            }
        }
        composeRule.onNodeWithTag("telemetrySwitch").assertIsNotEnabled()
        telemetry = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("telemetrySwitch").assertIsOff()
        // On its label's start: scrolled to the edge, its middle sits under the scroll chevron, which pages.
        composeRule.onNodeWithText("Help make StopDash better").performScrollTo().performTouchInput { click(centerLeft + Offset(8f, 0f)) }
        composeRule.runOnIdle { assert(latest == true) }
    }

    @Test
    fun theSwitchIsOffByDefault() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("liveWidgetSwitch").assertIsOff()
    }

    /**
     * The text-size controls render inside the theme, which provides the shared font-size state:
     * the labeled slider (at the default system size) and the pinch switch (SPEC *Display size*).
     * They appear above the live-widget row, so the recorded settings snapshots capture them too.
     */
    @Test
    fun textSizeControls_render() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Text size").assertIsDisplayed()
        composeRule.onNodeWithText("105%").assertIsDisplayed()
        composeRule.onNodeWithTag("textSizeSlider").assertExists()
        composeRule.onNodeWithText("Pinch to resize text").assertIsDisplayed()
        composeRule.onNodeWithTag("pinchSwitch").assertIsOn()
    }

    /**
     * The TfL API-key control renders (SPEC D7): the title, the paste field, and Save — which
     * starts disabled since an untouched field has nothing new to persist. It sits below the
     * live-widget row, so the recorded settings snapshots capture it too.
     */
    @Test
    fun apiKeyControl_render() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("TfL API key").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("apiKeyField").performScrollTo().assertExists()
        composeRule.onNodeWithTag("apiKeySave").performScrollTo().assertIsNotEnabled()
    }

    /**
     * Until the stored key has been read (a slow or persistently-failing DataStore read leaves the
     * flow silent), the field is disabled so it can't be edited over a value that hasn't loaded yet
     * and would reset the draft on arrival (Codex P2, mirroring the switch).
     */
    @Test
    fun apiKeyField_disabled_whileNotLoaded() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    userApiKey = "",
                    userApiKeyLoaded = false,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("apiKeyField").performScrollTo().assertIsNotEnabled()
    }

    /** The National Rail key has its own row, saved separately from the TfL key. */
    @Test
    fun railKey_savesSeparately() {
        var rail: String? = null
        var tfl: String? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    onUserApiKeyChange = { tfl = it },
                    onRailApiKeyChange = { rail = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("National Rail API key").assertExists()
        composeRule.onNodeWithTag("railKeyField").performScrollTo().performTextInput("EXAMPLE")
        composeRule.onNodeWithTag("railKeySave").performScrollTo().performClick()
        composeRule.runOnIdle {
            assert(rail == "EXAMPLE")
            assert(tfl == null)
        }
    }

    /** Typing a key then tapping Save reports the pasted value; Clear is absent until one is saved. */
    @Test
    fun pastingAndSaving_reportsTheKey() {
        var saved: String? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    userApiKey = "",
                    onUserApiKeyChange = { saved = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("apiKeyClear").assertDoesNotExist()
        composeRule.onNodeWithTag("apiKeyField").performScrollTo().performTextInput("EXAMPLE")
        composeRule.onNodeWithTag("apiKeySave").performScrollTo().performClick()
        composeRule.runOnIdle { assert(saved == "EXAMPLE") }
    }

    /**
     * The key is masked by default (a credential), with a Show/Hide toggle that appears only once
     * there's text to reveal, so an empty field stays uncluttered (Codex P2).
     */
    @Test
    fun theKeyIsMasked_withARevealToggleWhenPresent() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    userApiKey = "EXAMPLE",
                )
            }
        }
        composeRule.waitForIdle()

        // Masked by default → the Show affordance is present; toggling flips it to Hide.
        composeRule.onNodeWithText("Show").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("apiKeyReveal").performScrollTo().performClick()
        composeRule.onNodeWithText("Hide").performScrollTo().assertIsDisplayed()
    }

    /**
     * An in-progress edit survives a saved-value change arriving underneath it (a save's own async
     * store echo, or a late load) — the field follows a new saved value only when it isn't dirty, so
     * the user's typing isn't discarded (Codex).
     */
    @Test
    fun anInProgressEditSurvivesALateSavedValueChange() {
        val stored = mutableStateOf("")
        var saved: String? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    userApiKey = stored.value,
                    onUserApiKeyChange = { saved = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("apiKeyField").performScrollTo().performTextInput("ABC")
        // A saved value lands from elsewhere while the user is still editing.
        composeRule.runOnIdle { stored.value = "XYZ" }
        composeRule.waitForIdle()

        // The edit is kept; saving reports it, not the value that arrived underneath.
        composeRule.onNodeWithTag("apiKeySave").performScrollTo().performClick()
        composeRule.runOnIdle { assert(saved == "ABC") }
    }

    /**
     * An edit that lands back on the previously-saved value is still dirty, so a save's echo can't
     * overwrite it — the dirty flag tracks that the user edited, not whether the text happens to
     * equal a saved value (Codex).
     */
    @Test
    fun anEditBackToTheOldValueIsStillDirty() {
        val stored = mutableStateOf("A")
        var saved: String? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    userApiKey = stored.value,
                    onUserApiKeyChange = { saved = it },
                )
            }
        }
        composeRule.waitForIdle()

        // Edit away and back to "A" (the current saved value), then the save of "B" echoes in.
        composeRule.onNodeWithTag("apiKeyField").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("apiKeyField").performScrollTo().performTextInput("A")
        composeRule.runOnIdle { stored.value = "B" }
        composeRule.waitForIdle()

        // The edit is kept (not replaced by "B"): saving reports "A".
        composeRule.onNodeWithTag("apiKeySave").performScrollTo().performClick()
        composeRule.runOnIdle { assert(saved == "A") }
    }

    /** Clearing re-arms masking, so a key pasted afterward isn't shown in plaintext (Codex). */
    @Test
    fun clearing_remasksTheField() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    userApiKey = "EXAMPLE",
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("apiKeyReveal").performScrollTo().performClick() // reveal → "Hide"
        composeRule.onNodeWithText("Hide").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("apiKeyClear").performScrollTo().performClick() // clears and re-masks
        composeRule.onNodeWithTag("apiKeyField").performScrollTo().performTextInput("NEW")
        // Masked again: the toggle offers Show, not Hide.
        composeRule.onNodeWithText("Show").performScrollTo().assertIsDisplayed()
    }

    /** Tapping a distance-units segment reports that choice; the stored one shows selected. */
    @Test
    fun distanceUnits_reportsTheTappedChoice() {
        var chosen: DistanceUnits? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    distanceUnits = DistanceUnits.METERS,
                    onDistanceUnitsChange = { chosen = it },
                )
            }
        }
        composeRule.onNodeWithTag("distanceUnits-METERS").assertIsSelected()
        // The walking speed below it in view too, so Feet isn't left under the scroll cue at the screen's foot.
        composeRule.onNodeWithText("Walking speed").performScrollTo()
        composeRule.onNodeWithTag("distanceUnits-FEET").performScrollTo().performClick()
        assertEquals(DistanceUnits.FEET, chosen)
    }

    /** Tapping a walking speed reports that choice; the stored one shows selected. */
    @Test
    fun walkingSpeed_reportsTheTappedChoice() {
        var chosen: WalkingSpeed? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    walkingSpeed = WalkingSpeed.SLOW,
                    onWalkingSpeedChange = { chosen = it },
                )
            }
        }
        composeRule.onNodeWithTag("walkingSpeedSetting-SLOW").performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("Medium").performScrollTo().assertIsDisplayed()
        // Off its middle: scrolled to the edge, that sits under the scroll chevron, which pages.
        composeRule.onNodeWithTag("walkingSpeedSetting-FAST").performScrollTo().performTouchInput { click(centerRight - Offset(8f, 0f)) }
        assertEquals(WalkingSpeed.FAST, chosen)
    }

    /** The max walk shows the stored limit and offers every one; a pick is reported. */
    @Test
    fun maxWalk_reportsTheTappedChoice() {
        var chosen: MaxWalk? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    maxWalk = MaxWalk.THIRTY,
                    onMaxWalkChange = { chosen = it },
                )
            }
        }
        composeRule.onNodeWithTag("maxWalkSetting").performScrollTo().assertContentDescriptionEquals("Max walk, 30 min")
        composeRule.onNodeWithTag("maxWalkSetting").performClick()
        MaxWalk.entries.forEach { composeRule.onNodeWithTag("maxWalkSetting-${it.name}").assertExists() }
        composeRule.onNodeWithTag("maxWalkSetting-SIXTY").performClick()
        assertEquals(MaxWalk.SIXTY, chosen)
    }

    /** Until the stored max walk is read nothing is selected or tappable; a failed save says so. */
    @Test
    fun maxWalk_disabledUntilLoaded_andAFailedSaveIsShown() {
        var dismissed = false
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    maxWalkLoaded = false,
                    maxWalkWriteFailed = true,
                    onDismissMaxWalkError = { dismissed = true },
                )
            }
        }
        // No limit shown, so the default can't pass for the choice, and nothing to open.
        composeRule.onNodeWithTag("maxWalkSetting").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("maxWalkSetting").assertContentDescriptionEquals("Max walk, –")
        composeRule.onNodeWithText("Couldn't save that", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performScrollTo().performClick()
        assertEquals(true, dismissed)
    }

    /** Step-free shows the stored level and offers the three; a pick is reported. */
    @Test
    fun stepFree_reportsTheTappedChoice() {
        var chosen: StepFree? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    stepFree = StepFree.STATION,
                    onStepFreeChange = { chosen = it },
                )
            }
        }
        composeRule.onNodeWithTag("stepFreeSetting").performScrollTo().assertContentDescriptionEquals("Step-free, Station")
        composeRule.onNodeWithTag("stepFreeSetting").performClick()
        StepFree.entries.forEach { composeRule.onNodeWithTag("stepFreeSetting-${it.name}").assertExists() }
        composeRule.onNodeWithTag("stepFreeSetting-FULLY").performClick()
        assertEquals(StepFree.FULLY, chosen)
    }

    /** Until the stored step-free level is read nothing shows or opens; a failed save says so. */
    @Test
    fun stepFree_disabledUntilLoaded_andAFailedSaveIsShown() {
        var dismissed = false
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    stepFreeLoaded = false,
                    stepFreeWriteFailed = true,
                    onDismissStepFreeError = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithTag("stepFreeSetting").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("stepFreeSetting").assertContentDescriptionEquals("Step-free, –")
        composeRule.onNodeWithText("Couldn't save that", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performScrollTo().performClick()
        assertEquals(true, dismissed)
    }

    /** Until the stored choice is read the segments are disabled; a failed save says so. */
    @Test
    fun distanceUnits_disabledUntilLoaded_andAFailedSaveIsShown() {
        var dismissed = false
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    distanceUnitsLoaded = false,
                    distanceUnitsWriteFailed = true,
                    onDismissDistanceUnitsError = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithTag("distanceUnits-FEET").assertIsNotEnabled()
        composeRule.onNodeWithTag("distanceUnits-AUTOMATIC").assertIsNotSelected()
        composeRule.onNodeWithText("Couldn't save that", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performScrollTo().performClick()
        assertEquals(true, dismissed)
    }

    /** No reveal toggle on an empty field — nothing to reveal. */
    @Test
    fun noRevealToggle_whenTheFieldIsEmpty() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("apiKeyReveal").assertDoesNotExist()
    }

    /** Save reports the trimmed value, matching how the store normalizes a pasted key (Codex). */
    @Test
    fun saving_reportsTheTrimmedValue() {
        var saved: String? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    userApiKey = "",
                    onUserApiKeyChange = { saved = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("apiKeyField").performScrollTo().performTextInput("  EXAMPLE  ")
        composeRule.onNodeWithTag("apiKeySave").performScrollTo().performClick()
        composeRule.runOnIdle { assert(saved == "EXAMPLE") }
    }

    /**
     * A draft that differs from the saved key only by surrounding whitespace normalizes to the same
     * value, so Save stays disabled rather than getting stuck enabled after a no-op save (Codex).
     */
    @Test
    fun save_disabledForAWhitespaceOnlyDifference() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    userApiKey = "EXAMPLE",
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("apiKeyField").performScrollTo().performTextInput(" ")
        composeRule.onNodeWithTag("apiKeySave").performScrollTo().assertIsNotEnabled()
    }

    /** With a key already stored, Clear reports an empty string (back to keyless). */
    @Test
    fun clearing_reportsEmpty() {
        var saved: String? = null
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    userApiKey = "EXAMPLE",
                    onUserApiKeyChange = { saved = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("apiKeyClear").performScrollTo().performClick()
        composeRule.runOnIdle { assert(saved == "") }
    }

    @Test
    fun settings_hidden_list() {
        val northern = HiddenModes.lineKey("northern", "Northern line")
        var hidden by mutableStateOf(linkedSetOf(northern, "bus", "tube", "dlr") as Set<String>)
        val shown = mutableListOf<String>()
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    hiddenModes = hidden,
                    onShowHidden = { group ->
                        shown += group.key
                        hidden = ModeGroups.withGroup(hidden, group, hide = false)
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Under the places: each group in menu order, then the lines, each with its own Show.
        composeRule.onNodeWithText("Hidden").assertIsDisplayed()
        composeRule.onNodeWithText("Underground").assertIsDisplayed()
        composeRule.onNodeWithText("Bus").assertIsDisplayed()
        composeRule.onNodeWithText("Northern line").assertIsDisplayed()
        captureSnapshot("settings-hidden.png")

        // Show brings back just that one; the rest stays listed.
        composeRule.onNodeWithContentDescription("Show Northern line").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(northern), shown)
        composeRule.onNodeWithText("Northern line").assertDoesNotExist()
        composeRule.onNodeWithText("Bus").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show Underground").performClick()
        composeRule.onNodeWithContentDescription("Show Bus").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(northern, "tube", "bus"), shown)
        // Nothing hidden: no list at all.
        composeRule.onNodeWithTag("hiddenList").assertDoesNotExist()
    }

    @Test
    fun aShowThatDidNotSave_isSaidInSettings_evenWithTheListGone() {
        var failed by mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    // The last hidden item was shown again, and that didn't save.
                    hiddenModes = emptySet(),
                    hiddenWriteFailed = failed,
                    onDismissHiddenError = { failed = false },
                )
            }
        }
        composeRule.waitForIdle()
        val message = composeRule.activity.getString(app.stopdash.R.string.hidden_modes_write_failed)
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun settings_avoided_lines() {
        val northern = AvoidedLines.key("northern", "Northern line")
        val central = AvoidedLines.key("central", "Central line")
        // A station found closed, avoided beside the lines.
        val bank = AvoidedLines.stopKey("940GZZLUBNK", "Bank")
        var avoided by mutableStateOf(linkedSetOf(northern, central, bank) as Set<String>)
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    avoidedLines = avoided,
                    onStopAvoiding = { avoided = avoided - it },
                )
            }
        }
        composeRule.waitForIdle()

        // Each line and stop trips avoid, in the order avoided, with its own Remove.
        composeRule.onNodeWithText("Avoided").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Left out of trips").assertIsDisplayed()
        composeRule.onNodeWithText("Northern line").assertIsDisplayed()
        composeRule.onNodeWithText("Central line").assertIsDisplayed()
        composeRule.onNodeWithText("Bank").assertIsDisplayed()
        captureSnapshot("settings-avoided.png")

        // Remove stops avoiding just that one.
        composeRule.onNodeWithContentDescription("Stop avoiding Northern line").performClick()
        composeRule.waitForIdle()
        assertEquals(setOf(central, bank), avoided)
        composeRule.onNodeWithText("Northern line").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Stop avoiding Central line").performClick()
        composeRule.onNodeWithContentDescription("Stop avoiding Bank").performClick()
        composeRule.waitForIdle()
        // None avoided: no list at all.
        composeRule.onNodeWithTag("avoidedList").assertDoesNotExist()
    }

    @Test
    fun aRemoveThatDidNotSave_isSaidInSettings_evenWithTheListGone() {
        var failed by mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    avoidedLines = emptySet(),
                    avoidedWriteFailed = failed,
                    onDismissAvoidedError = { failed = false },
                )
            }
        }
        composeRule.waitForIdle()
        val message = composeRule.activity.getString(app.stopdash.R.string.hidden_modes_write_failed)
        composeRule.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun settings_hidden_list_isAbsent_whenNothingIsHidden() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hiddenList").assertDoesNotExist()
    }

    /**
     * Whether this run is one that touches PNGs at all — matches [LicensesScreenshotTest]. Without
     * a flag the screenshot tests still render and assert, they just don't record, so
     * `./gradlew test` doesn't rewrite snapshots on every machine.
     */
    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"

    @Test
    fun the_location_row_leads_the_widget_settings_and_says_how_much_is_allowed() {
        var access by mutableStateOf<app.stopdash.LocationAccess?>(null)
        var taps = 0
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {},
                    locationAccess = access, onLocationClick = { taps++ },
                )
            }
        }
        composeRule.waitForIdle()
        // Not read yet: held, so a tap can't act on a grant that isn't known.
        composeRule.onNodeWithTag("locationRow").performScrollTo().assertIsNotEnabled()
        access = app.stopdash.LocationAccess.APPROXIMATE
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Approximate").performScrollTo().assertIsDisplayed()
        // On its label: scrolled to the edge, its middle sits under the scroll chevron, which pages.
        composeRule.onNodeWithTag("locationRow").performTouchInput { click(centerLeft + Offset(48f, 0f)) }
        assertEquals(1, taps)
        access = app.stopdash.LocationAccess.PRECISE
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Precise").assertIsDisplayed()
        access = app.stopdash.LocationAccess.NONE
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Not allowed").assertIsDisplayed()
        // Location, then the widget following the rider, then how often the widget updates.
        val location = composeRule.onNodeWithTag("locationRow").fetchSemanticsNode().positionInRoot.y
        val follows = composeRule.onNodeWithTag("widgetFollowsSwitch").fetchSemanticsNode().positionInRoot.y
        val live = composeRule.onNodeWithTag("liveWidgetSwitch").fetchSemanticsNode().positionInRoot.y
        org.junit.Assert.assertTrue("location above follows", location < follows)
        org.junit.Assert.assertTrue("follows above live refresh", follows < live)
        access = app.stopdash.LocationAccess.APPROXIMATE
        composeRule.waitForIdle()
        captureSnapshot("settings-location-row.png")
    }

    @Test
    fun opened_for_the_widget_settings_it_opens_scrolled_to_them() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {}, startOnWidgets = true)
            }
        }
        composeRule.waitForIdle()
        // Scrolled to the widget rows: the top of Settings is out of view, the widget rows in it.
        composeRule.onNodeWithTag("favoritePlacesRow").assertIsNotDisplayed()
        composeRule.onNodeWithTag("widgetFollowsSwitch").assertIsDisplayed()
        composeRule.onNodeWithTag("liveWidgetSwitch").assertIsDisplayed()
    }

    @Test
    fun opened_for_the_rail_key_it_opens_scrolled_to_it_and_flashes_it_twice() {
        var shown = 0
        // Driven frame by frame, to see the flashes as they run.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    startOnRailKey = true,
                    onRailKeyShown = { shown++ },
                )
            }
        }
        // Near the top of the first flash: scrolled to the key, the top of Settings out of view.
        composeRule.mainClock.advanceTimeBy(140)
        composeRule.onNodeWithTag("favoritePlacesRow").assertIsNotDisplayed()
        composeRule.onNodeWithTag("railKeyField").assertIsDisplayed()
        // At the window's own height: a shorter capture would lay the screen out again under the scroll
        // already made for this one, and cut the row off.
        captureSnapshot("settings-rail-key-flash.png", heightPx = composeRule.activity.window.decorView.rootView.height)
        // Into the second flash (each takes 300 ms): still flashing.
        composeRule.mainClock.advanceTimeBy(360)
        assertEquals(0, shown)
        // Past 600 ms: done, and said so once.
        composeRule.mainClock.advanceTimeBy(200)
        assertEquals(1, shown)
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(1, shown)
        composeRule.onNodeWithTag("railKeyField").assertIsDisplayed()
    }

    @Test
    fun the_rail_key_flashes_twice_and_ends_unlit() {
        // Unlit at either end and between flashes, fully lit at each of the two peaks.
        assertEquals(0f, railKeyFlashAlpha(0f), 0.001f)
        assertEquals(0f, railKeyFlashAlpha(1f), 0.001f)
        for (flash in 0 until 2) {
            assertEquals(1f, railKeyFlashAlpha((flash + 0.4f) / 2f), 0.001f)
            assertEquals(0.5f, railKeyFlashAlpha((flash + 0.2f) / 2f), 0.001f)
            assertEquals(0.5f, railKeyFlashAlpha((flash + 0.7f) / 2f), 0.001f)
        }
        assertEquals(0f, railKeyFlashAlpha(0.5f), 0.001f)
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
}
