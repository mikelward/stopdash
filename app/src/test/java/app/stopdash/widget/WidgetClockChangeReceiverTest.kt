package app.stopdash.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A setting of the device's clock redraws a placed widget at once, from its stored snapshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetClockChangeReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    // Every redraw asked for, whatever its state: the test WorkManager runs one with no delay at once.
    private fun redraws(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WIDGET_STALENESS_WORK).get() +
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(WIDGET_STALENESS_WORK_NEXT).get()

    private fun placeWidget() {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).setAllowedToBindAppWidgets(true)
        assertTrue(manager.bindAppWidgetIdIfAllowed(1, ComponentName(context, StopDashWidgetReceiver::class.java)))
    }

    @Test
    fun `the app listens for the clock being set`() {
        val receivers = context.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_TIME_CHANGED).setPackage(context.packageName), 0)
        assertTrue(WidgetClockChangeReceiver::class.java.name in receivers.map { it.activityInfo.name })
    }

    @Test
    fun `setting the clock redraws a placed widget at once`() {
        placeWidget()
        WidgetClockChangeReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        val redraw = redraws().single()
        assertEquals(0L, redraw.initialDelayMillis)
    }

    @Test
    fun `with no widget placed, or another broadcast, nothing is scheduled`() {
        WidgetClockChangeReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        assertTrue(redraws().isEmpty())
        placeWidget()
        WidgetClockChangeReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(redraws().isEmpty())
    }
}
