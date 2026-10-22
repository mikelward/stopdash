package app.stopdash.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.Countdown
import app.stopdash.domain.CountdownWords
import app.stopdash.domain.Departure
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A countdown's words come from the app's string resources and read as the English always has. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class CountdownWordsTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun departure(seconds: Long) = Departure("victoria", "Victoria", "", "Brixton", null, now.plusSeconds(seconds), "tube")

    @Test
    fun `the app's words write a countdown as the English always has`() {
        val words = countdownWords(resources)
        val trains = listOf(departure(30), departure(180), departure(360))
        assertEquals("0 · 3 · 6 min", Countdown.mergedLabel(trains, now, words))
        assertEquals(Countdown.mergedLabel(trains, now, CountdownWords.ENGLISH), Countdown.mergedLabel(trains, now, words))
        assertEquals(" · ", words.between)
        assertEquals("" to " min", words.around)
    }
}
