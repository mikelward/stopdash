package app.stopdash.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stopdash.domain.AppSettings
import app.stopdash.domain.DistanceUnits
import app.stopdash.domain.FontSizeSettings
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import app.stopdash.domain.UsageEvent
import app.stopdash.domain.UsageState
import app.stopdash.domain.WalkingSpeed
import app.stopdash.telemetry.UsageProperties
import app.stopdash.telemetry.UsagePropertiesPublisher
import app.stopdash.widget.widgetTextScale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A text size the rider sets reaches the usage stats' properties only once it's stored: they read the
 * stored size ([app.stopdash.telemetry.UsageStateReader]), so a refresh asked before the write lands
 * would send the size before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FontSizeUsageTest {
    // The size the fake store holds, written on the setting's own thread.
    @Volatile
    private var stored = 1f

    @After
    fun reset() {
        UsageProperties.resetForTest()
        FontSizeSetting.setScale(1f)
    }

    @Test
    fun `the properties are sent again only once the new text size is stored`() = runTest {
        val writeLands = CompletableDeferred<Unit>()
        val settings = object : AppSettings {
            override fun liveWidgetRefresh() = flowOf(false)
            override suspend fun setLiveWidgetRefresh(enabled: Boolean) {}
            override fun fontSize() = flowOf(FontSizeSettings())
            override suspend fun setFontScale(scale: Float) {
                if (scale == 1.4f) writeLands.await()
                stored = scale
            }
            override suspend fun setPinchEnabled(enabled: Boolean) {}
            override fun skipBugReportConsent() = flowOf(false)
            override suspend fun setSkipBugReportConsent(enabled: Boolean) {}
            override fun userApiKey() = flowOf<String?>(null)
            override suspend fun setUserApiKey(key: String?) {}
        }
        val sent = mutableListOf<String?>()
        // The publisher runs only as this test advances it; the setting writes on its own thread.
        UsageProperties.install(
            UsagePropertiesPublisher(
                consent = MutableStateFlow(true),
                read = { state(stored) },
                send = { sent += it["text_size"] },
                worker = StandardTestDispatcher(testScheduler),
                warn = {},
            ),
            backgroundScope,
        )
        runCurrent()
        // The opt-in's own send, before anything changed.
        assertEquals(listOf<String?>("0.95-1.05"), sent)
        sent.clear()
        FontSizeSetting.warm(settings)
        FontSizeSetting.setScale(1.4f)
        runCurrent()
        // The write hasn't landed: nothing is read for it yet, so the old size isn't sent.
        assertEquals(emptyList<String?>(), sent)
        writeLands.complete(Unit)
        // The refresh follows the write on the setting's own thread: advance the publisher until it has
        // answered it, for at most a few seconds of real time.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (sent.isEmpty() && System.nanoTime() < deadline) {
            runCurrent()
            Thread.yield()
        }
        assertEquals(listOf<String?>("1.25+"), sent)
    }

    @Test
    fun `a text size that didn't save is still the one sent, as it's the one in force`() = runTest {
        val settings = object : AppSettings {
            override fun liveWidgetRefresh() = flowOf(false)
            override suspend fun setLiveWidgetRefresh(enabled: Boolean) {}
            override fun fontSize() = flowOf(FontSizeSettings())
            override suspend fun setFontScale(scale: Float) {
                if (scale == 1.4f) throw java.io.IOException("disk full")
            }
            override suspend fun setPinchEnabled(enabled: Boolean) {}
            override fun skipBugReportConsent() = flowOf(false)
            override suspend fun setSkipBugReportConsent(enabled: Boolean) {}
            override fun userApiKey() = flowOf<String?>(null)
            override suspend fun setUserApiKey(key: String?) {}
        }
        val sent = mutableListOf<String?>()
        UsageProperties.install(
            UsagePropertiesPublisher(
                consent = MutableStateFlow(true),
                // As the reader does: the size in force once loaded, else the stored one (here, the default).
                read = { state(FontSizeSetting.inForce()?.scale ?: 1f) },
                send = { sent += it["text_size"] },
                worker = StandardTestDispatcher(testScheduler),
                warn = {},
            ),
            backgroundScope,
        )
        runCurrent()
        sent.clear()
        FontSizeSetting.warm(settings)
        // The stored size read in first, as it is before the app can be resized.
        val loaded = System.nanoTime() + 5_000_000_000L
        while (FontSizeSetting.inForce() == null && System.nanoTime() < loaded) Thread.yield()
        FontSizeSetting.setScale(1.4f)
        // Published at once for the widget's redraw, though the write will fail.
        assertEquals(1.4f, FontSizeSetting.shownScale.value)
        // And the widget draws at it, read or not.
        assertEquals(1.4f, widgetTextScale(ApplicationProvider.getApplicationContext()))
        // The failed write still asks for the properties again, and they carry the size in force.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (sent.isEmpty() && System.nanoTime() < deadline) {
            runCurrent()
            Thread.yield()
        }
        assertEquals(listOf<String?>("1.25+"), sent)
    }

    private fun state(textSize: Float) = UsageState(
        walkingSpeed = WalkingSpeed.AVERAGE, maxWalk = MaxWalk.DEFAULT, stepFree = StepFree.DEFAULT,
        tripModes = TripModes.DEFAULT, avoidedLines = 0, hiddenModes = emptySet(),
        distanceUnits = DistanceUnits.AUTOMATIC, disruptionsRow = true, liveWidget = false,
        textSize = textSize, pinchToResize = true, tflKey = false, railKey = false, widgets = 0,
        watch = UsageState.Watch.NONE, starredRows = 0, favoritePlaces = 0, favoriteJourneys = 0,
        notifications = true, location = UsageEvent.Grant.PRECISE,
    )
}
