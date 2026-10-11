package app.stopdash.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.stopdash.ThreadRecorder
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
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
        runBlocking { clockChanged(context) }
        val redraw = redraws().single()
        assertEquals(0L, redraw.initialDelayMillis)
    }

    @Test
    fun `a compact widget counts as placed`() {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).setAllowedToBindAppWidgets(true)
        assertTrue(manager.bindAppWidgetIdIfAllowed(2, ComponentName(context, StopDashCompactWidgetReceiver::class.java)))
        runBlocking { clockChanged(context) }
        assertEquals(1, redraws().size)
    }

    @Test
    fun `with no widget placed, or another broadcast, nothing is scheduled`() {
        runBlocking { clockChanged(context) }
        assertTrue(redraws().isEmpty())
        placeWidget()
        WidgetClockChangeReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(redraws().isEmpty())
    }

    // The host is asked off the caller's thread: the receiver is called on the main thread (Codex on #759).
    @Test
    fun `the host is asked on the io dispatcher, not the caller's thread`() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }
        val io = Executors.newSingleThreadExecutor { Thread(it, "test-io") }
        try {
            val ranOn = ThreadRecorder()
            val base = io.asCoroutineDispatcher()
            val recording = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) =
                    base.dispatch(context) { ranOn.note(); block.run() }
            }
            runBlocking(caller.asCoroutineDispatcher()) { clockChanged(context, recording) }
            assertEquals(listOf("test-io"), ranOn.threads().distinct())
        } finally {
            caller.shutdown()
            io.shutdown()
        }
    }
}
