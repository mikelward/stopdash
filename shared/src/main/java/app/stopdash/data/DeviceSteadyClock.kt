package app.stopdash.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.stopdash.domain.Staleness
import app.stopdash.domain.SteadyClock
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.UUID
import kotlin.math.abs

/**
 * The device's monotonic clock as [SteadyClock]'s source: time since boot, which setting the clock
 * doesn't move, against the wall clock. The frame's origin is the wall time the device booted at as
 * this process first reads it; the boot is this install's own id and the system's boot count
 * ([bootOf]), so a process can tell whether what it stored came from this boot. Worked out the
 * first time a frame is asked for, off the start-up path: it reads a file and the system settings.
 */
class DeviceSteadyClock(context: Context) : SteadyClock.Source {
    private val app = context.applicationContext
    private val origin = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    override val frame: SteadyClock.Frame? by lazy { bootOf(app)?.let { SteadyClock.Frame(it, origin) } }

    override fun offset(): Duration {
        val raw = origin + SystemClock.elapsedRealtime() - System.currentTimeMillis()
        // Under a minute apart, it's the clocks' own drift and the two reads' jitter, not the clock
        // being set: nothing to correct, and a fetch's age is no more exact for it.
        return if (abs(raw) < Staleness.CLOCK_SKEW.inWholeMilliseconds) Duration.ZERO else Duration.ofMillis(raw)
    }

    companion object {
        private const val TAG = "StopDash.Clock"

        /** Installs the device's clock as [SteadyClock]'s source, once per process; call at each entry point. */
        fun install(context: Context) {
            if (SteadyClock.source != null) return
            synchronized(this) {
                if (SteadyClock.source == null) SteadyClock.source = DeviceSteadyClock(context)
            }
        }

        /**
         * The device's frame as of now: the boot, and the wall time it booted at as the wall clock
         * reads now. Two readings of one boot differ by how far the clock was set between them
         * ([SteadyClock.shiftBetween]). Null where the boot can't be told.
         */
        fun frameNow(context: Context): SteadyClock.Frame? =
            bootOf(context.applicationContext)?.let { SteadyClock.Frame(it, System.currentTimeMillis() - SystemClock.elapsedRealtime()) }

        // This process's boot ([bootOf]), once worked out: a process never outlives its boot.
        @Volatile
        private var boot: String? = null

        /**
         * This boot, as "install/count": the system's boot count, which only tells boots of one
         * device apart, qualified by a random id for this install, kept where backup never takes it
         * (`noBackupFilesDir`), so a file restored onto another device (the count may well match)
         * reads as from another boot. Null where either can't be had, so nothing stored is moved.
         * Read once per process, the first time it's asked for.
         */
        private fun bootOf(app: Context): String? =
            boot ?: synchronized(this) {
                boot ?: run {
                    val count = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
                    count?.let { c -> installId(app)?.let { "$it/$c" } }
                }?.also { boot = it }
            }

        private fun installId(app: Context): String? {
            val file = File(app.noBackupFilesDir, "steady-clock-install")
            return try {
                file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: UUID.randomUUID().toString().also { id ->
                        val tmp = File(file.parentFile, "${file.name}.tmp")
                        tmp.writeText(id)
                        if (!tmp.renameTo(file)) throw IOException("rename failed")
                    }
            } catch (e: IOException) {
                // Then no frame: what's stored is read as the wall clock's, as an older build's is.
                Log.w(TAG, "install id unreadable: ${e::class.simpleName}")
                null
            }
        }
    }
}
