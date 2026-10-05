package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import app.stopdash.domain.StepFree
import app.stopdash.domain.WalkingSpeed
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings screen in both toggle states (SPEC D5): the "refresh widget every minute" row off
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
        composeRule.onNodeWithText("Refresh widget every minute").performScrollTo().assertIsDisplayed()
        captureSnapshot("settings-off.png")
    }

    @Test
    fun the_disruptions_summary_comes_second_and_its_page_turns_the_row_off() {
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
        composeRule.onNodeWithText("Tube and lines near you").assertIsDisplayed()
        captureSnapshot("settings-disruptions-summary.png")
        composeRule.onNodeWithTag("disruptionsSummaryRow").performClick()
        composeRule.onNodeWithTag("disruptionsSummaryPage").assertIsDisplayed()
        // Settings itself is gone from under it, so nothing hidden can be reached.
        composeRule.onNodeWithTag("disruptionsSummaryRow").assertDoesNotExist()
        composeRule.onNodeWithTag("disruptionsRowSwitch").performClick()
        org.junit.Assert.assertEquals(listOf(false), chosen)
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
        composeRule.onNodeWithText("Tube and lines near you").assertDoesNotExist()
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
                    networks = setOf("tube"), onNetworksChange = {}, networksLoaded = loaded, networksWriteFailed = false,
                    onDismissNetworksError = {}, onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("disruptionsSummaryPage").assertIsDisplayed()
        composeRule.onNodeWithTag("disruptionsRowSwitch").assertDoesNotExist()
        composeRule.onNodeWithTag("summaryNetwork-tube").assertDoesNotExist()
        loaded = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("disruptionsRowSwitch").assertIsOn()
        composeRule.onNodeWithTag("summaryNetwork-tube").assertIsSelected()
    }

    @Test
    fun the_summary_always_includes_the_chosen_networks() {
        val chosen = mutableListOf<Set<String>>()
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(
                    liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {},
                    summaryNetworks = setOf("tube"), onSummaryNetworksChange = { chosen += it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("disruptionsSummaryRow").performClick()
        composeRule.onNodeWithText("Always include").assertIsDisplayed()
        captureSnapshot("settings-summary-networks.png")
        composeRule.onNodeWithTag("summaryNetwork-overground").performScrollTo().performClick()
        composeRule.onNodeWithTag("summaryNetwork-tube").performScrollTo().performClick()
        org.junit.Assert.assertEquals(listOf(setOf("tube", "overground"), emptySet<String>()), chosen)
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
        composeRule.onNodeWithTag("watchInstallRow").performClick()
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

    @Test
    fun settings_on() {
        composeRule.setContent {
            StopDashTheme {
                SettingsScreen(liveWidgetRefresh = true, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        captureSnapshot("settings-on.png")
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

        composeRule.onNodeWithText("Refresh widget every minute").performScrollTo().performClick()
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
        composeRule.onNodeWithText("Help make StopDash better").performScrollTo().performClick()
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
        composeRule.onNodeWithText("100%").assertIsDisplayed()
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
        composeRule.onNodeWithTag("walkingSpeedSetting-FAST").performScrollTo().performClick()
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
        composeRule.onNodeWithText("Couldn't save that", substring = true).assertIsDisplayed()
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
        composeRule.onNodeWithText("Tube & DLR").assertIsDisplayed()
        composeRule.onNodeWithText("Bus").assertIsDisplayed()
        composeRule.onNodeWithText("Northern line").assertIsDisplayed()
        captureSnapshot("settings-hidden.png")

        // Show brings back just that one; the rest stays listed.
        composeRule.onNodeWithContentDescription("Show Northern line").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(northern), shown)
        composeRule.onNodeWithText("Northern line").assertDoesNotExist()
        composeRule.onNodeWithText("Bus").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show Tube & DLR").performClick()
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
        var avoided by mutableStateOf(linkedSetOf(northern, central) as Set<String>)
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

        // Each line trips avoid, in the order avoided, with its own Remove.
        composeRule.onNodeWithText("Avoided lines").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Left out of trips").assertIsDisplayed()
        composeRule.onNodeWithText("Northern line").assertIsDisplayed()
        composeRule.onNodeWithText("Central line").assertIsDisplayed()
        captureSnapshot("settings-avoided.png")

        // Remove stops avoiding just that one.
        composeRule.onNodeWithContentDescription("Stop avoiding Northern line").performClick()
        composeRule.waitForIdle()
        assertEquals(setOf(central), avoided)
        composeRule.onNodeWithText("Northern line").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Stop avoiding Central line").performClick()
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
