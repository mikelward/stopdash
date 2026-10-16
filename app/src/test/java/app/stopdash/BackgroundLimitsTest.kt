package app.stopdash

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What Android is holding the app's background work to, as the debug log reads it ([backgroundLimits]):
 * a widget refresh hours late reads differently under each (bug report, 2026-10-16).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackgroundLimitsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Plugged in or not, as the sticky battery broadcast says, and charging or not, as BatteryManager does.
    private fun power(plugged: Int, charging: Boolean) {
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged))
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIsCharging(charging)
    }

    @Test
    fun `names each limit Android holds the app to`() {
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)
        shadowOf(context.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        // A wall tablet whose battery protection holds the charge at a limit (bug report, 2026-10-16).
        power(BatteryManager.BATTERY_PLUGGED_AC, charging = false)
        assertEquals(
            "standby bucket rare, background restricted, battery optimized, battery saver on, plugged in but not charging",
            backgroundLimits(context),
        )
    }

    @Test
    fun `tells charging from on battery`() {
        power(BatteryManager.BATTERY_PLUGGED_USB, charging = true)
        assertTrue(backgroundLimits(context).endsWith(", charging"))
        power(0, charging = false)
        assertTrue(backgroundLimits(context).endsWith(", on battery"))
    }

    @Test
    fun `says so when it holds the app to none`() {
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_ACTIVE)
        shadowOf(context.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(false)
        val powerManager = shadowOf(context.getSystemService(PowerManager::class.java))
        powerManager.setIgnoringBatteryOptimizations(context.packageName, true)
        powerManager.setIsPowerSaveMode(false)
        power(BatteryManager.BATTERY_PLUGGED_AC, charging = true)
        assertEquals(
            "standby bucket active, background not restricted, battery not optimized, battery saver off, charging",
            backgroundLimits(context),
        )
    }

    @Test
    fun `a bucket the platform doesn't name reads as its number`() {
        assertEquals("exempted", standbyBucketName(5))
        assertEquals("restricted", standbyBucketName(UsageStatsManager.STANDBY_BUCKET_RESTRICTED))
        assertEquals("50", standbyBucketName(50))
    }
}
