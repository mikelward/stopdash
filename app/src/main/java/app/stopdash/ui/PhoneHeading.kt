package app.stopdash.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.stopdash.StopdashDebugLog
import app.stopdash.domain.Compass
import app.stopdash.domain.HeadingFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Which way the phone faces, for the walk's arrow (SPEC *On the way*): degrees clockwise from north,
 * or null where it isn't known (no compass, an unreliable one, or not asked for).
 */
fun interface HeadingSource {
    @Composable
    fun heading(enabled: Boolean): Float?
}

/** The phone's own compass; a screenshot test provides a fixed heading in its place. */
internal val LocalHeadingSource = staticCompositionLocalOf<HeadingSource> { HeadingSource { enabled -> PhoneHeading.follow(enabled) } }

/**
 * The phone's compass, followed only while [follow] is asked to and the screen is started: the trip
 * screen on a walk step with a way to point. Off the main thread throughout: registering the sensor
 * is a call into the system, and each reading is worked on the listener's own thread.
 */
internal object PhoneHeading {
    @Composable
    fun follow(enabled: Boolean): Float? {
        val context = LocalContext.current.applicationContext
        val worker = LocalWorker.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        // Read only: the display's turn, so a phone on its side still points the right way.
        val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
        val heading = remember { MutableStateFlow<Float?>(null) }
        LaunchedEffect(enabled, lifecycle, worker, rotation) {
            heading.value = null
            if (!enabled) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    withContext(worker) { readings(context, rotation) { heading.value = it } }
                } finally {
                    heading.value = null
                }
            }
        }
        return heading.collectAsState().value
    }

    /**
     * Passes [emit] each steadied heading from the rotation-vector sensor until cancelled, null when the
     * compass turns unreliable; returns at once, having emitted nothing, on a phone without one. Each
     * change between drawing the arrow and withholding it is logged with its reason ([log]), once per
     * change rather than per reading, so a report says why a walk showed no arrow.
     */
    suspend fun readings(context: Context, rotation: Int, log: (String) -> Unit = { StopdashDebugLog.info("compass: %s", it) }, emit: (Float?) -> Unit) {
        val manager = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            StopdashDebugLog.info("compass: no rotation sensor")
            return
        }
        val thread = HandlerThread("heading").apply { start() }
        val filter = HeadingFilter()
        val matrix = FloatArray(9)
        val remapped = FloatArray(9)
        // The last kind of state logged, so a sensor reporting many times a second logs only a change of
        // kind: an error drifting from 60° to 61° is the same withholding, not a new line.
        var state: String? = null
        fun note(kind: String, message: String) {
            if (kind != state) log(message).also { state = kind }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val withheld = withheldReason(event.accuracy, event.values.getOrNull(4))
                if (withheld != null) {
                    note(withheld.first, withheld.second)
                    filter.reset()
                    emit(null)
                    return
                }
                note("shown", "arrow shown")
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val (x, y) = axes(rotation)
                SensorManager.remapCoordinateSystem(matrix, x, y, remapped)
                // Magnetic north, about 1° off true north in London: well inside an arrow's width.
                filter.next(Compass.screenAzimuth(remapped))?.let { emit(it.toFloat()) }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, Handler(thread.looper))) {
                    StopdashDebugLog.warning("compass: rotation sensor refused the listener")
                    cont.resumeWith(Result.success(Unit))
                }
            }
        } finally {
            manager.unregisterListener(listener)
            thread.quitSafely()
        }
    }

    /**
     * Why a reading draws no arrow, as a kind (what a log line is deduplicated on) and its message, or null
     * where it draws one: the sensor calling itself unreliable, an error it can't state (NaN or +∞),
     * or an error past [Compass.MAX_HEADING_ERROR_DEGREES], in whole degrees but never down to the limit
     * itself. Each cause its own kind, so a change from one to another is logged (Codex, #817). Whether
     * the arrow is drawn is [Compass.headingUsable]'s alone, unchanged here: any negative error, −∞
     * included, is the sensor saying it has no estimate, and draws it.
     */
    internal fun withheldReason(accuracy: Int, errorRadians: Float?): Pair<String, String>? {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return "unreliable" to "arrow withheld, sensor unreliable"
        if (Compass.headingUsable(errorRadians)) return null
        if (!errorRadians!!.isFinite()) return "invalid" to "arrow withheld, heading error not a number"
        val limit = Compass.MAX_HEADING_ERROR_DEGREES.roundToInt()
        val degrees = Math.toDegrees(errorRadians.toDouble()).roundToInt().coerceAtLeast(limit + 1)
        return "error" to "arrow withheld, heading error $degrees° over $limit°"
    }

    // The device axes that stand for the screen's x and y at [rotation].
    private fun axes(rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }
}
