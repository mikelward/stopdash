package app.stopdash.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stopdash.domain.AppSettings
import app.stopdash.domain.FontSizeSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The store's one read at startup against changes the rider makes before it lands. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FontSizeSettingTest {
    // What the store holds, taken by the read one value at a time.
    private val stored = Channel<FontSizeSettings>(Channel.UNLIMITED)

    // The read holds the value it has taken until this completes, as a read can be when a change
    // comes in.
    private val readGoesOn = CompletableDeferred<Unit>()

    private val settings = object : AppSettings by AppSettings.NONE {
        override fun fontSize(): Flow<FontSizeSettings> = flow {
            for (value in stored) {
                readGoesOn.await()
                emit(value)
            }
        }
        override suspend fun setFontScale(scale: Float) {}
        override suspend fun setPinchEnabled(enabled: Boolean) {}
    }

    // The read and the writes run only as the test advances its dispatcher.
    private fun TestScope.warm() {
        FontSizeSetting.resetForTest(StandardTestDispatcher(testScheduler))
        FontSizeSetting.warm(settings)
        runCurrent()
    }

    @After
    fun reset() = FontSizeSetting.resetForTest()

    @Test
    fun `a size set before the read lands is kept, not the stored one`() = runTest {
        warm()
        FontSizeSetting.setScale(1.4f)
        read(FontSizeSettings(scale = 1f, pinchEnabled = false))
        assertEquals(1.4f, FontSizeSetting.current.scale)
        assertEquals(1.4f, FontSizeSetting.shownScale.value)
        // What wasn't changed comes from the store.
        assertFalse(FontSizeSetting.current.pinchEnabled)
    }

    @Test
    fun `a pinch switch set before the read lands is kept, not the stored one`() = runTest {
        warm()
        FontSizeSetting.setPinchEnabled(false)
        read(FontSizeSettings(scale = 1.2f, pinchEnabled = true))
        assertFalse(FontSizeSetting.current.pinchEnabled)
        assertEquals(1.2f, FontSizeSetting.current.scale)
    }

    @Test
    fun `a size set while the read holds an older value is kept`() = runTest {
        warm()
        // The read has taken 1.0 from the store but not applied it when the rider sets 1.4.
        stored.trySend(FontSizeSettings(scale = 1f))
        runCurrent()
        assertNull(FontSizeSetting.inForce())
        FontSizeSetting.setScale(1.4f)
        readGoesOn.complete(Unit)
        runCurrent()
        assertEquals(1.4f, FontSizeSetting.inForce()?.scale)
        assertEquals(1.4f, FontSizeSetting.shownScale.value)
    }

    @Test
    fun `with nothing set first, the stored size is the one in force`() = runTest {
        warm()
        read(FontSizeSettings(scale = 1.2f))
        assertEquals(1.2f, FontSizeSetting.inForce()?.scale)
        assertEquals(1.2f, FontSizeSetting.shownScale.value)
    }

    @Test
    fun `the store is read once, so a value from it can't undo a later choice`() = runTest {
        warm()
        read(FontSizeSettings(scale = 1f))
        FontSizeSetting.setScale(1.4f)
        // An older value turning up in the store afterwards isn't taken: nothing reads it.
        stored.trySend(FontSizeSettings(scale = 1.2f))
        runCurrent()
        assertTrue(stored.tryReceive().isSuccess)
        assertEquals(1.4f, FontSizeSetting.current.scale)
        assertEquals(1.4f, FontSizeSetting.shownScale.value)
    }

    /** Hands the read [value] and lets it apply it. */
    private fun TestScope.read(value: FontSizeSettings) {
        stored.trySend(value)
        readGoesOn.complete(Unit)
        runCurrent()
    }
}
