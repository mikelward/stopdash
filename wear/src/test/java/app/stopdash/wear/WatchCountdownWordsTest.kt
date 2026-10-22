package app.stopdash.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The watch's countdown words come from its own string resources and read as the English always has. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class WatchCountdownWordsTest {
    @Test
    fun `the watch's words write a countdown as the English always has`() {
        val words = countdownWords(ApplicationProvider.getApplicationContext<Context>().resources)
        assertEquals("3 · 7 min", words.minutes("3${words.between}7"))
        assertEquals(" · ", words.between)
        assertEquals("" to " min", words.around)
    }
}
