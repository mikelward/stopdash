package app.stopdash.telemetry

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.stopdash.domain.UsageEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** [ReportScreen] across the activity being made again: a rotation isn't a visit of its own, a return to the app is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReportScreenRotationTest {
    /** An activity drawing one screen, as the app's does. */
    class ReportingActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent { ReportScreen(UsageEvent.Screen.HOME) }
        }
    }

    private val sent = mutableListOf<UsageEvent>()

    @After
    fun reset() = UsageEvents.resetForTest()

    @Test
    fun `a rotation doesn't count the screen again, a return to the app does`() {
        UsageEvents.consent = { true }
        UsageEvents.install { sent += it }
        val controller = Robolectric.buildActivity(ReportingActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, sent.size)
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, sent.size)
        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("home", "home"), sent.map { it.params["screen_name"] })
        controller.pause().stop().destroy()
    }
}
