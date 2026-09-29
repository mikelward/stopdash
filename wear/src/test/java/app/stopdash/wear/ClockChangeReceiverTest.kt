package app.stopdash.wear

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A setting of the watch's clock re-renders the surfaces the system steps by it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClockChangeReceiverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the watch listens for its clock being set`() {
        val receivers = app.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_TIME_CHANGED).setPackage(app.packageName), 0)
        assertEquals(listOf(ClockChangeReceiver::class.java.name), receivers.map { it.activityInfo.name })
    }

    @Test
    fun `setting the clock asks the complications to re-render`() {
        ClockChangeReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        assertTrue(shadowOf(app).broadcastIntents.any { it.action == COMPLICATION_UPDATE_ALL })
    }

    @Test
    fun `nothing else asks`() {
        ClockChangeReceiver().onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(shadowOf(app).broadcastIntents.none { it.action == COMPLICATION_UPDATE_ALL })
    }

    private companion object {
        const val COMPLICATION_UPDATE_ALL = "android.support.wearable.complications.ACTION_REQUEST_UPDATE_ALL"
    }
}
