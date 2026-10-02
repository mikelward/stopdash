package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.R
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withJson
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The open-source licenses screen. The library list is built synchronously from the committed
 * res/raw/aboutlibraries.json so the snapshot is deterministic (production loads the same JSON
 * asynchronously via `rememberLibraries`). Public component names only — no user route data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LicensesScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun licenses() {
        composeRule.setContent {
            StopDashTheme {
                LicensesContent(loadLibraries())
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Open source licences").assertExists()
        captureSnapshot("licenses.png")
    }

    /**
     * Tapping a component opens a dialog with its version, its authors and a tappable license
     * link (the bundled export carries no license text, so the link opens the full text in the
     * browser), and Done dismisses it. Interaction-only: a Compose dialog renders in its own
     * window, which the decorView snapshot helper can't capture.
     */
    @Test
    fun licenseDialog_showsVersionAndOpensLicenseUrl() {
        var openedUrl: String? = null
        composeRule.setContent {
            StopDashTheme {
                LicensesContent(loadLibraries(), onOpenLicenseUrl = { openedUrl = it })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertIsDisplayed()
        // Apache-2.0 §4 asks for attribution, and the license name alone does not carry it —
        // the dialog names who wrote the component too.
        composeRule.onNodeWithText("By The Android Open Source Project").assertIsDisplayed()
        composeRule.onNodeWithText("Apache License 2.0").assertIsDisplayed()

        // The license name links out to the full text.
        composeRule.onNodeWithText("Apache License 2.0").performClick()
        composeRule.runOnIdle {
            assertEquals("https://spdx.org/licenses/Apache-2.0.html", openedUrl)
        }

        // Done dismisses the dialog.
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertDoesNotExist()
    }

    /**
     * The POM's declared developers are the usual authors line, but plenty of components name
     * only the organization that published them, and a few name nobody at all. All three shapes
     * come from a fixture rather than the bundled export, which today happens to declare a
     * developer for every component that names anyone.
     */
    @Test
    fun licenseDialog_namesEveryAuthor_orNoneWhenTheComponentNamesNobody() {
        composeRule.setContent {
            StopDashTheme {
                LicensesContent(Libs.Builder().withJson(ATTRIBUTION_FIXTURE).build())
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Organization only").performClick()
        composeRule.onNodeWithText("By Example Organization").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()

        composeRule.onNodeWithText("Two developers").performClick()
        composeRule.onNodeWithText("By Ada Example, Grace Example").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()

        composeRule.onNodeWithText("Nobody named").performClick()
        composeRule.onNodeWithText("Version 3.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("By ", substring = true).assertDoesNotExist()
    }

    /**
     * The details a component row opens — version, authors, and the license link — as a
     * snapshot. Captured from the dialog's own node rather than the activity's decor view: a
     * Compose dialog renders in its own window, which [captureSnapshot] can't reach, so these
     * rows would otherwise have no visual cover. Pinned to one fixed component rather than "the
     * first in the list", so a dependency change can't silently re-point the snapshot.
     */
    @Test
    fun licenseDialog_snapshot() {
        composeRule.setContent {
            StopDashTheme {
                LicensesContent(loadLibraries())
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertIsDisplayed()
        composeRule.onNodeWithText("By The Android Open Source Project").assertIsDisplayed()

        if (capturing()) {
            composeRule.onNode(isDialog())
                .captureRoboImage(filePath = "src/test/snapshots/images/license-details.png")
        }
    }

    private fun loadLibraries(): Libs =
        Libs.Builder().withJson(composeRule.activity, R.raw.aboutlibraries).build()

    /**
     * Whether this run is one that touches PNGs at all. Without a flag the screenshot tests
     * still run — they render and assert without recording — which is what keeps
     * `./gradlew test` from rewriting snapshots on every developer's machine.
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

/**
 * A hand-written export covering the attribution shapes the bundled one does not: an
 * organization with no developer, more than one developer, and no attribution at all. Stock
 * stand-in names throughout — nothing here is anybody's.
 */
private const val ATTRIBUTION_FIXTURE = """
{
  "libraries": [
    {
      "uniqueId": "com.example:organization-only",
      "artifactVersion": "1.0.0",
      "name": "Organization only",
      "developers": [],
      "organization": { "name": "Example Organization" },
      "licenses": ["Apache-2.0"]
    },
    {
      "uniqueId": "com.example:two-developers",
      "artifactVersion": "2.0.0",
      "name": "Two developers",
      "developers": [{ "name": "Ada Example" }, { "name": "Grace Example" }],
      "licenses": ["Apache-2.0"]
    },
    {
      "uniqueId": "com.example:nobody-named",
      "artifactVersion": "3.0.0",
      "name": "Nobody named",
      "developers": [],
      "licenses": ["Apache-2.0"]
    }
  ],
  "licenses": {
    "Apache-2.0": {
      "name": "Apache License 2.0",
      "url": "https://spdx.org/licenses/Apache-2.0.html",
      "hash": "Apache-2.0",
      "spdxId": "Apache-2.0"
    }
  }
}
"""
