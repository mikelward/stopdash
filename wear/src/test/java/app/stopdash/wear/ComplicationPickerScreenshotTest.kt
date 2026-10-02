package app.stopdash.wear

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.stopdash.domain.StarredRow
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The complication row picker on a round watch; captures land beside the phone's, as the app's do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w227dp-h227dp-round-watch-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComplicationPickerScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val choices = listOf(
        ComplicationChoice(StarredRow("940GA", "victoria", "inbound"), "VIC", "Brixton", "Oxford Circus"),
        ComplicationChoice(StarredRow("940GB", "northern", "inbound"), "NOR", "Morden/Bank", "Euston"),
    )

    private fun capture(name: String, choices: List<ComplicationChoice>, selected: PickState) {
        compose.setContent { ComplicationPickerScreen(choices, selected) {} }
        compose.onRoot().captureRoboImage(filePath = "../app/src/test/snapshots/images/wear_$name.png")
    }

    @Test
    fun picker() = capture("complication_picker", choices, selected = PickState.Loaded(choices[1].row))

    @Test
    fun pickerEmpty() = capture("complication_picker_empty", emptyList(), selected = PickState.Loaded(null))
}
