package app.stopdash

import android.os.StrictMode
import com.mikelward.androidlog.DebugLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [36])
@RunWith(RobolectricTestRunner::class)
class MainThreadWatchInstallTest {
    private val before = StrictMode.getThreadPolicy()

    @After fun restore() = StrictMode.setThreadPolicy(before)

    // The policy's toString is "[StrictMode.ThreadPolicy; mask=N]"; its mask is all it exposes.
    private fun mask(policy: StrictMode.ThreadPolicy): Int =
        Regex("mask=(-?\\d+)").find(policy.toString())!!.groupValues[1].toInt()

    @Test
    fun `installing keeps every check and penalty already in place`() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build())
        val existing = mask(StrictMode.getThreadPolicy())

        MainThreadViolations.install(DebugLog(), "com.example.app")

        assertEquals(existing, mask(StrictMode.getThreadPolicy()) and existing)
    }
}
