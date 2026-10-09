package app.stopdash

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The trip's notification's small icon, which Android draws in the status bar and at the start of a
 * Live Update's chip: the app's arrow filling its frame, not the launcher mark's arrow, a quarter of a
 * 108 viewport off to one side, that shrinks to a dot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationIconTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-09-26T07:02:00Z")
    private val leg = TripLeg("overground", "mildmay", "Mildmay", "910GHGHI", "Highbury & Islington", "910GSTFD", "Stratford", now, now.plusSeconds(600))
    private val trip = ActiveTrip(TripRoute(listOf(leg)), "Stratford", startedAt = now, vehicleId = "EXAMPLE", boarded = true)

    @Test
    fun `the trip's notification uses the arrow drawn to fill its frame`() {
        val riding = TripProgress.Riding(leg, "Stratford", 3, now.plusSeconds(300), getOffSoon = false)
        val notification = OnTheWayNotification.build(app, trip, riding, failed = false, updatedAt = now, now = now)
        assertEquals(R.drawable.ic_stat_route_arrow, notification.smallIcon.resId)
    }

    @Test
    fun `the arrow spans most of its frame, centered`() {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val icon = checkNotNull(ContextCompat.getDrawable(app, R.drawable.ic_stat_route_arrow))
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bitmap))
        var left = size; var right = -1; var top = size; var bottom = -1
        for (y in 0 until size) for (x in 0 until size) {
            if (bitmap.getPixel(x, y) ushr 24 > 128) {
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("nothing drawn", right >= 0)
        // The old mark's arrow filled about a fifth of its width; this one most of it.
        assertTrue("arrow ${right - left + 1}px wide of $size", right - left + 1 >= size * 0.8)
        assertEquals("not centered across", size / 2.0, (left + right) / 2.0, size * 0.06)
        assertEquals("not centered down", size / 2.0, (top + bottom) / 2.0, size * 0.06)
    }
}
