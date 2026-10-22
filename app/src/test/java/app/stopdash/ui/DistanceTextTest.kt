package app.stopdash.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.DistanceSystem
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stop's distance in words: the units are the app's string resources, the decimal mark its language's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class DistanceTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources = context.resources

    @Test
    fun `each unit reads as the English labels always have`() {
        assertEquals("120 m", distanceText(118.0, DistanceSystem.METERS, resources))
        assertEquals("0.6 km", distanceText(620.0, DistanceSystem.METERS, resources))
        assertEquals("1.0 km", distanceText(995.0, DistanceSystem.METERS, resources))
        assertEquals("130 yd", distanceText(118.0, DistanceSystem.YARDS, resources))
        assertEquals("390 ft", distanceText(118.0, DistanceSystem.FEET, resources))
        assertEquals("0.4 mi", distanceText(620.0, DistanceSystem.YARDS, resources))
    }

    @Test
    fun `a language with a decimal comma writes one`() {
        val config = Configuration(resources.configuration).apply { setLocale(Locale.FRANCE) }
        val french = context.createConfigurationContext(config).resources
        assertEquals("0,6 km", distanceText(620.0, DistanceSystem.METERS, french))
        assertEquals("120 m", distanceText(118.0, DistanceSystem.METERS, french))
    }
}
