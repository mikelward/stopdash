package app.stopdash.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The widget's pill slot is fixed (Glance can't measure), so it can't widen for a code that doesn't
 * fit the way the app's pill does. It holds the widest code with room over, so a system font wider
 * than the Roboto measured here doesn't cut it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPillWidthTest {
    @Test
    fun `the slot holds a four-character code with room for a wider font`() {
        val metrics = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics
        // The widget label's style: 12sp bold, in the system font.
        val paint = Paint().apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, metrics)
        }
        val slot = WIDGET_PILL_LABEL_WIDTH.value
        for (code in listOf("LNWR", "N550")) {
            val width = paint.measureText(code) / metrics.density
            // A real measurement, not a stub's zero.
            assertTrue("$code measured ${width}dp", width > 20f)
            assertTrue("$code is ${width}dp, the ${slot}dp slot leaves under a fifth over", slot >= width * 1.2f)
        }
    }
}
