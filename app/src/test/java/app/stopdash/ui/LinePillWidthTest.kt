package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every line pill is exactly the same width down the column — the fixed label width (see
 * [LinePill]) makes it uniform by construction, so a two-digit bus number, a three-letter tube
 * code, and a four-character bus route all measure the same, at the default font scale and at
 * a large one, and none of them truncates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinePillWidthTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Pills() {
        Surface {
            Row {
                LinePill(lineName = "12", lineId = "12", mode = "bus")
                LinePill(lineName = "Victoria", lineId = "victoria", mode = "tube")
                // Hammersmith & City → "HAM", a wide three-letter code.
                LinePill(lineName = "Hammersmith & City", lineId = "hammersmith-city", mode = "tube")
                // A four-character night-bus route — the widest code lineCode() emits.
                LinePill(lineName = "N550", lineId = "n550", mode = "bus")
            }
        }
    }

    private fun widthOf(label: String) =
        composeRule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot().width.value.toDouble()

    @Test
    fun `every code, two-digit through four-character, is the same width`() {
        composeRule.setContent { StopDashTheme { Pills() } }
        val bus = widthOf("12")
        val tube = widthOf("Victoria")
        val wide = widthOf("Hammersmith & City")
        val route = widthOf("N550")
        assertEquals(tube, bus, 0.5)
        assertEquals(tube, wide, 0.5)
        assertEquals(tube, route, 0.5)
    }

    @Test
    fun `a four-character route code is not truncated`() {
        // The fixed box must hold the widest code the app shows without ellipsizing it —
        // uniform width is worthless if N550 and N551 both collapse to "N5…". Compare the
        // pill against a bare label of the same code, same style and padding but no width
        // cap: if the pill (fixed box) is at least as wide as the unconstrained text, the
        // code fits and nothing is dropped.
        composeRule.setContent {
            StopDashTheme {
                Surface {
                    Row {
                        LinePill(lineName = "N550", lineId = "n550", mode = "bus")
                        Text(
                            text = "N550",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .semantics { contentDescription = "ref-N550" },
                        )
                    }
                }
            }
        }
        val pill = widthOf("N550")
        val natural = widthOf("ref-N550")
        assertTrue(
            "the fixed pill ($pill) must hold the full N550 code (natural $natural) without ellipsis",
            pill + 0.5 >= natural,
        )
    }

    @Test
    fun `pills stay uniform and grow with the font scale`() {
        // At 2x font scale the pill scales up (holding a wide code rather than clipping it)
        // and both codes still measure the same width. Only two pills here: at 2x, four wide
        // pills overflow the test window's width (the app never puts more than one pill in a
        // row), which would clamp the trailing pill and defeat the uniformity check.
        composeRule.setContent { AtScale(2f) { Row { Pill("12"); Pill("N550") } } }
        val bus2x = widthOf("12")
        val route2x = widthOf("N550")
        assertEquals(bus2x, route2x, 0.5)
        assertTrue("pill should widen at 2x font scale", bus2x > 64.0 + 16.0)
    }

    @Test
    fun `the label box grows as the text does, not by the raw font scale`() {
        // Android scales large text non-linearly, so at 2x a 14 sp label grows by less than 2x.
        // The box must grow by the text's own growth: a box grown by the raw scale padded the
        // code out at a large font size. Compare a bare N550 label's growth with the pill's.
        composeRule.setContent {
            Column {
                AtScale(1f) { Pill("N550", "pill-1x"); Bare("bare-1x") }
                AtScale(2f) { Pill("N550", "pill-2x"); Bare("bare-2x") }
            }
        }
        val padding = 16.0
        val textGrowth = widthOf("bare-2x") / widthOf("bare-1x")
        val boxGrowth = (widthOf("pill-2x") - padding) / (widthOf("pill-1x") - padding)
        assertTrue("2x text should be wider than 1x", textGrowth > 1.2)
        assertEquals("pill 1x ${widthOf("pill-1x")} 2x ${widthOf("pill-2x")}, text 1x ${widthOf("bare-1x")} 2x ${widthOf("bare-2x")}", textGrowth, boxGrowth, 0.03)
    }

    @Composable
    private fun AtScale(fontScale: Float, content: @Composable () -> Unit) {
        StopDashTheme {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density = base.density, fontScale = fontScale)) {
                Surface { Row { content() } }
            }
        }
    }

    @Composable
    private fun Pill(line: String, label: String = line) {
        Box(Modifier.semantics(mergeDescendants = true) { contentDescription = label }) {
            LinePill(lineName = line, lineId = line.lowercase(), mode = "bus")
        }
    }

    @Composable
    private fun Bare(label: String) {
        Text(
            text = "N550",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
