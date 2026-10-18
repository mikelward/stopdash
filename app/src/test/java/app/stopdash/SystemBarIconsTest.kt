package app.stopdash

import android.app.Application
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The system bars' icons contrast the app in both themes. A full-screen Compose Dialog (a line's,
 * stop's or trip's page) takes them from the activity's platform theme rather than from
 * enableEdgeToEdge, so a dark-only theme left the three-button nav white on a light page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemBarIconsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun lightBars(): List<Boolean> {
        val attrs = intArrayOf(android.R.attr.windowLightStatusBar, android.R.attr.windowLightNavigationBar)
        val styled = ContextThemeWrapper(app, R.style.Theme_StopDash).obtainStyledAttributes(attrs)
        try {
            return attrs.indices.map { styled.getBoolean(it, false) }
        } finally {
            styled.recycle()
        }
    }

    @Test
    @Config(qualifiers = "en-rGB-notnight")
    fun `light theme draws dark bar icons`() {
        assertEquals(listOf(true, true), lightBars())
    }

    @Test
    @Config(qualifiers = "en-rGB-night")
    fun `dark theme draws light bar icons`() {
        assertEquals(listOf(false, false), lightBars())
    }
}
