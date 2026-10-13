package app.stopdash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app start's refresh of channels an update may have renamed ([refreshNotificationChannels]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationChannelRefreshTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "refresh-io") }
    private val io = pool.asCoroutineDispatcher()

    @After
    fun tearDown() {
        pool.shutdown()
    }

    private fun refresh() = runBlocking { refreshNotificationChannels(context, io) }

    @Test
    fun channels_made_under_older_names_take_the_new_ones_and_keep_the_riders_settings() {
        manager.createNotificationChannel(NotificationChannel(JourneyAlertNotification.CHANNEL_ID, "Old name", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(RouteDisruptionAlert.CHANNEL_ID, "Old name", NotificationManager.IMPORTANCE_HIGH))
        // The rider turned the ride alerts off.
        val turnedOff = manager.getNotificationChannel(JourneyAlertNotification.CHANNEL_ID)
        turnedOff.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(turnedOff)

        refresh()

        val rides = manager.getNotificationChannel(JourneyAlertNotification.CHANNEL_ID)
        assertEquals(context.getString(R.string.journey_alerts_channel), rides.name.toString())
        assertEquals(context.getString(R.string.journey_alerts_channel_description), rides.description)
        assertEquals(NotificationManager.IMPORTANCE_NONE, rides.importance)
        val disruptions = manager.getNotificationChannel(RouteDisruptionAlert.CHANNEL_ID)
        assertEquals(context.getString(R.string.route_disruption_channel), disruptions.name.toString())
        assertEquals(context.getString(R.string.route_disruption_channel_description), disruptions.description)
    }

    @Test
    fun no_channel_the_rider_never_had_is_made() {
        refresh()
        assertNull(manager.getNotificationChannel(JourneyAlertNotification.CHANNEL_ID))
        assertNull(manager.getNotificationChannel(RouteDisruptionAlert.CHANNEL_ID))
    }

    @Test
    fun each_channels_refresh_runs_off_the_callers_thread() {
        val ran = ThreadRecorder()
        val caller = Thread.currentThread().name
        runBlocking { refreshNotificationChannels(context, io, refreshes = listOf({ _ -> ran.note() }, { _ -> ran.note() })) }
        assertEquals(listOf("refresh-io", "refresh-io"), ran.threads())
        assertNotEquals("refresh-io", caller)
    }
}
