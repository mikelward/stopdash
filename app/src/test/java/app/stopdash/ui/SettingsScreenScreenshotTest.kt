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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.stopdash.domain.DistanceUnits
import app.stopdash.domain.MaxWalk
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
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
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
        composeRule.onNodeWithText("Refresh widget every minute").assertIsDisplayed()
        captureSnapshot("settings-off.png")
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

        composeRule.onNodeWithText("Couldn't update live refresh").assertIsDisplayed()
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

        composeRule.onNodeWithText("Dismiss").performClick()
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

        composeRule.onNodeWithText("Refresh widget every minute").performClick()
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
        composeRule.onNodeWithText("Help make StopDash better").performClick()
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
