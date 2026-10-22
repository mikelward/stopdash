package app.stopdash.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.Compass
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The way a walk's end lies, in letters: each point's are the app's string resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class CompassLettersTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `each point reads in the letters the card and the chip have always written`() {
        assertEquals(8, Compass.Point.entries.size)
        Compass.Point.entries.forEach { point ->
            assertEquals(point.name, resources.getString(compassLetters(point)))
        }
    }

    @Test
    fun `each point has its own letters`() {
        assertEquals(Compass.Point.entries.size, Compass.Point.entries.map(::compassLetters).toSet().size)
    }
}
