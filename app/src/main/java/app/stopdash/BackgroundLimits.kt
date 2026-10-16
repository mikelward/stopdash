package app.stopdash

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.annotation.WorkerThread

/**
 * What Android may be doing to hold this app's background work back, in a few words for the debug
 * log: its standby bucket, whether the rider restricted its battery use, whether it's exempt from
 * battery optimization, whether battery saver is on, and whether the device is charging. A widget
 * refresh that ran hours late reads very differently under each (bug report, 2026-10-16), and none
 * of it shows anywhere else. Plugged in isn't charging: battery protection that holds the charge at
 * a limit leaves a tablet plugged in but not charging, which Android may treat as on battery. App and
 * device state only, nothing about the rider. Each part is a binder call, so off the main thread; one
 * that fails reads "unknown" rather than losing the rest.
 */
@WorkerThread
internal fun backgroundLimits(context: Context): String {
    val bucket = part { standbyBucketName(context.getSystemService(UsageStatsManager::class.java).appStandbyBucket) }
    val restricted = part {
        if (context.getSystemService(ActivityManager::class.java).isBackgroundRestricted) "restricted" else "not restricted"
    }
    val power = context.getSystemService(PowerManager::class.java)
    val optimized = part {
        if (power.isIgnoringBatteryOptimizations(context.packageName)) "not optimized" else "optimized"
    }
    val saver = part { if (power.isPowerSaveMode) "on" else "off" }
    val charge = part {
        val charging = context.getSystemService(BatteryManager::class.java).isCharging
        // The sticky battery broadcast, read without registering anything.
        val plugged = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        when {
            charging -> "charging"
            plugged == null -> "charging unknown"
            plugged != 0 -> "plugged in but not charging"
            else -> "on battery"
        }
    }
    return "standby bucket $bucket, background $restricted, battery $optimized, battery saver $saver, $charge"
}

/** The bucket's name as the platform documents it, or its number for one it doesn't. */
internal fun standbyBucketName(bucket: Int): String = when (bucket) {
    // EXEMPTED is a system API, so it has no public constant.
    5 -> "exempted"
    UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
    UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
    UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
    UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
    UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
    else -> "$bucket"
}

private inline fun part(read: () -> String): String = try {
    read()
} catch (e: RuntimeException) {
    // A system service that throws or isn't there: say so in place of the part, keep the others.
    StopdashDebugLog.warning("background limits: a read failed: %s", e::class.simpleName)
    "unknown"
}

/** Logs [backgroundLimits] once, as a process starts: the state any late background work ran under. */
@WorkerThread
internal fun logBackgroundLimits(context: Context) {
    StopdashDebugLog.info("background: %s", backgroundLimits(context))
}
