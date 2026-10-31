package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class ShortenedTextTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a name with shorter forms is only as wide as the form shown, so what follows sits after it`() {
        // A ride's star after where it goes (maintainer, 2026-10-31): a name with shorter forms took the whole room it was
        // chosen for, pushing the star to the far edge, where a name with none kept it beside the name.
        composeRule.setContent {
            Row(Modifier.width(400.dp)) {
                ShortenedText(
                    listOf("Walthamstow Central", "Walthamstow Ctrl"),
                    MaterialTheme.typography.titleMedium,
                    Modifier.weight(1f, fill = false).testTag("name"),
                )
                Box(Modifier.size(16.dp).testTag("star"))
            }
        }
        val name = composeRule.onNodeWithTag("name").getBoundsInRoot()
        val star = composeRule.onNodeWithTag("star").getBoundsInRoot()
        assertTrue("the name wraps its text, not the row: ${name.right}", name.right < 300.dp)
        assertTrue("the star follows the name", star.left == name.right)
    }
}
