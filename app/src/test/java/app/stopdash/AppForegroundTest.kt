package app.stopdash

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether the app is on screen, an activity of it started, which a near-me fix waits for
 * ([AppForeground], [app.stopdash.domain.InFront]): told from the activity callbacks the app registers
 * as it starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppForegroundTest {
    @Test
    fun `on screen while an activity is started, through a hand-over from one to another`() {
        assertFalse("nothing started as the app starts", AppForeground.onScreen.value)
        val first = Robolectric.buildActivity(Activity::class.java).create().start()
        assertTrue("on screen from its start, before its resume", AppForeground.onScreen.value)
        first.resume()
        // Android starts the next before it stops the first: on screen throughout.
        first.pause()
        val second = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        assertTrue(AppForeground.onScreen.value)
        first.stop()
        assertTrue(AppForeground.onScreen.value)
        // Paused under something else but still started: on screen.
        second.pause()
        assertTrue(AppForeground.onScreen.value)
        // Away: the screen turned off, or another app came to the front.
        second.stop()
        assertFalse(AppForeground.onScreen.value)
        second.start()
        assertTrue(AppForeground.onScreen.value)
    }
}
