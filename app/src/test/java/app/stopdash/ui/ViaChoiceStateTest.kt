package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.stopdash.domain.TripVia
import app.stopdash.domain.ViaChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A trip's via is kept while the trip is open: over its To… picker, which shows in the trip's place (Codex on
 * #887), and over a recreation, and dropped for another destination. Public interchanges as examples.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class ViaChoiceStateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val finsburyPark = TripVia("HUBFPK", "Finsbury Park")

    @Test
    fun the_via_is_kept_over_the_to_picker_and_dropped_for_another_destination() {
        var picking by mutableStateOf(false)
        var destination by mutableStateOf<String?>("940GZZLUKSX")
        lateinit var via: MutableState<ViaChoice>
        composeRule.setContent {
            // As the trip holds it: above the picker, which shows in the trip's place.
            via = rememberViaChoice(destination)
            if (!picking) Text(via.value.via?.name ?: "any way")
        }
        composeRule.runOnIdle { via.value = ViaChoice(via = finsburyPark) }

        picking = true
        composeRule.waitForIdle()
        picking = false
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(ViaChoice(via = finsburyPark), via.value) }

        destination = "940GZZLUVIC"
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(ViaChoice(), via.value) }
    }

    @Test
    fun the_via_and_a_pick_pending_survive_a_recreation() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var via: MutableState<ViaChoice>
        restoration.setContent { via = rememberViaChoice("940GZZLUKSX") }
        val highbury = TripVia("940GZZLUHAI", "Highbury & Islington")
        composeRule.runOnIdle { via.value = ViaChoice(via = highbury, pending = finsburyPark) }

        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(ViaChoice(via = highbury, pending = finsburyPark), via.value) }
    }
}
