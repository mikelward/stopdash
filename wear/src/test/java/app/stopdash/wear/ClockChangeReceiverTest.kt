package app.stopdash.wear

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ReceiverCallNotAllowedException
import android.content.ServiceConnection
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
    fun `setting the clock asks from a receiver's context, which can't bind a service`() {
        // The watch's tile host, whose update service the tile's updater binds to.
        val host = ComponentName("com.google.android.wearable.app", "TileUpdateRequesterService")
        shadowOf(app.packageManager).addServiceIfNotPresent(host)
        shadowOf(app.packageManager).addIntentFilterForService(host, IntentFilter("androidx.wear.tiles.action.BIND_UPDATE_REQUESTER"))
        // As the system hands a manifest receiver its context: one that refuses to bind.
        val receiverContext = object : ContextWrapper(app) {
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
                throw ReceiverCallNotAllowedException("BroadcastReceiver components are not allowed to bind to services")
        }
        ClockChangeReceiver().onReceive(receiverContext, Intent(Intent.ACTION_TIME_CHANGED))
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
