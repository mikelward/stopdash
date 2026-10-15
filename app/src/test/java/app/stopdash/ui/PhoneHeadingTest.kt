package app.stopdash.ui

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

/** The phone's compass for the walk's arrow: read, steadied, and let go when no longer asked for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneHeadingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(SensorManager::class.java)

    private val sensor = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR)

    // A phone lying flat, its top turned [degrees] clockwise from north: a rotation about the z axis,
    // with the sensor's own estimate of its error ([errorDegrees]).
    private fun facing(degrees: Double, accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH, errorDegrees: Double = 5.0) =
        SensorEventBuilder.newBuilder()
            .setSensor(sensor)
            .setTimestamp(0L)
            .setValues(
                floatArrayOf(
                    0f, 0f, sin(Math.toRadians(-degrees) / 2).toFloat(), cos(Math.toRadians(-degrees) / 2).toFloat(),
                    Math.toRadians(errorDegrees).toFloat(),
                ),
            )
            .build()
            .apply { this.accuracy = accuracy }

    @Test
    fun `follows the rotation sensor, says when it's unreliable, and lets it go`() = runBlocking {
        shadowOf(manager).addSensor(sensor)
        val seen = CopyOnWriteArrayList<Float?>()
        val logged = CopyOnWriteArrayList<String>()
        val job = launch(Dispatchers.Default) { PhoneHeading.readings(app, Surface.ROTATION_0, { logged += it }) { seen += it } }
        while (shadowOf(manager).listeners.isEmpty()) yield()
        shadowOf(manager).sendSensorEventToListeners(facing(90.0))
        assertEquals(90f, seen.last()!!, 0.5f)
        shadowOf(manager).sendSensorEventToListeners(facing(90.0, SensorManager.SENSOR_STATUS_UNRELIABLE))
        assertNull(seen.last())
        // Accurate again, then far off by its own estimate: no arrow either (Codex, #775).
        shadowOf(manager).sendSensorEventToListeners(facing(180.0))
        assertEquals(180f, seen.last()!!, 0.5f)
        shadowOf(manager).sendSensorEventToListeners(facing(180.0, errorDegrees = 60.0))
        assertNull(seen.last())
        shadowOf(manager).sendSensorEventToListeners(facing(180.0, errorDegrees = 61.0))
        // Just past the limit: withheld, and said so as past it, not rounded down to it (Codex, #817).
        shadowOf(manager).sendSensorEventToListeners(facing(180.0))
        shadowOf(manager).sendSensorEventToListeners(facing(180.0, errorDegrees = 30.2))
        assertNull(seen.last())
        // Each change logged once, with why the arrow was withheld, so a report can say; the error drifting
        // while still too large is the same withholding, not a new line (Codex, #817).
        assertEquals(
            listOf(
                "arrow shown",
                "arrow withheld, sensor unreliable",
                "arrow shown",
                "arrow withheld, heading error 60° over 30°",
                "arrow shown",
                "arrow withheld, heading error 31° over 30°",
            ),
            logged.toList(),
        )
        job.cancel()
        job.join()
        assertTrue(shadowOf(manager).listeners.isEmpty())
    }

    @Test
    fun `letting the compass go is logged with why, once anything was, the why read on the caller's thread`() = runBlocking {
        shadowOf(manager).addSensor(sensor)
        val logged = CopyOnWriteArrayList<String>()
        val caller = Thread.currentThread()
        val askedOn = CopyOnWriteArrayList<Thread>()
        val stoppedOn = CopyOnWriteArrayList<Thread>()
        val reason = { askedOn += Thread.currentThread(); "no way to point" }
        val log = { line: String ->
            logged += line
            if (line.startsWith("stopped")) stoppedOn += Thread.currentThread()
        }
        // Let go before any reading: nothing was said, so nothing is said of its going.
        val quiet = launch { PhoneHeading.watch(Dispatchers.Default, app, Surface.ROTATION_0, reason, log) {} }
        while (shadowOf(manager).listeners.isEmpty()) yield()
        quiet.cancel()
        quiet.join()
        assertTrue(logged.isEmpty())
        // An arrow shown, then the compass let go: its going said, with the caller's reason (bug report, 2026-10-15).
        val job = launch { PhoneHeading.watch(Dispatchers.Default, app, Surface.ROTATION_0, reason, log) {} }
        while (shadowOf(manager).listeners.isEmpty()) yield()
        shadowOf(manager).sendSensorEventToListeners(facing(90.0))
        job.cancel()
        job.join()
        assertEquals(listOf("arrow shown", "stopped, no way to point"), logged.toList())
        // Asked back on the caller's thread, not the worker the sensor was read on: the trip screen's
        // reason reads the lifecycle's state, which is the main thread's to read (Codex, #881). The line
        // itself is written on the worker: a log line is I/O, never the main thread's (Codex, #881).
        assertEquals(listOf(caller), askedOn.toList())
        assertEquals(1, stoppedOn.size)
        assertNotEquals(caller, stoppedOn.single())
    }

    @Test
    fun `why the compass was let go is the trip screen's own`() {
        assertEquals("no way to point", PhoneHeading.stopReason(wanted = false, Lifecycle.State.RESUMED))
        // Not wanted outranks the screen: a walk's way withheld as the phone locks is still why.
        assertEquals("no way to point", PhoneHeading.stopReason(wanted = false, Lifecycle.State.CREATED))
        assertEquals("screen not shown", PhoneHeading.stopReason(wanted = true, Lifecycle.State.CREATED))
        assertEquals("trip card off screen", PhoneHeading.stopReason(wanted = true, Lifecycle.State.STARTED))
    }

    @Test
    fun `a phone without a compass gives no heading`() = runBlocking {
        val seen = mutableListOf<Float?>()
        PhoneHeading.readings(app, Surface.ROTATION_0) { seen += it }
        assertFalse(seen.any { it != null })
    }

    @Test
    fun `each reason the arrow is withheld is its own, stated as it is`() {
        val high = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        fun reason(error: Float?, accuracy: Int = high) = PhoneHeading.withheldReason(accuracy, error)
        assertNull(reason(Math.toRadians(5.0).toFloat()))
        assertNull(reason(null))
        assertNull(reason(-1f))
        // Negative is the sensor's "no estimate", −∞ as much as −1: drawn, as it always was (Codex, #817).
        assertNull(reason(Float.NEGATIVE_INFINITY))
        assertEquals("unreliable" to "arrow withheld, sensor unreliable", reason(0f, SensorManager.SENSOR_STATUS_UNRELIABLE))
        // An error the sensor can't state is its own reason, not an over-limit one (Codex, #817).
        assertEquals("invalid" to "arrow withheld, heading error not a number", reason(Float.NaN))
        assertEquals("invalid", reason(Float.POSITIVE_INFINITY)!!.first)
        // Past the limit, never rounded down to it.
        assertEquals("error" to "arrow withheld, heading error 31° over 30°", reason(Math.toRadians(30.2).toFloat()))
        assertEquals("error" to "arrow withheld, heading error 60° over 30°", reason(Math.toRadians(60.0).toFloat()))
    }
}
