package app.stopdash.widget

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An unlock refreshes the widget's stops while the app's process runs (SPEC D5), once, and only with somewhere to show them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnlockRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    // Every refresh asked for, whatever its state: the test WorkManager may already have run it.
    private fun refreshes(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(UNLOCK_REFRESH_WORK).get()

    @Test
    fun `once listening, an unlock asks for a refresh`() {
        UnlockRefresh.start(context)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, refreshes().size)
    }

    @Test
    fun `another broadcast asks for nothing`() {
        UnlockReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        assertTrue(refreshes().isEmpty())
    }

    @Test
    fun `a refresh is wanted with a widget placed or a watch with the app, and not otherwise`() = runBlocking {
        var watchAsked = false
        // A placed widget is enough: the watch isn't asked.
        assertTrue(unlockRefreshWanted({ true }, { watchAsked = true; false }))
        assertFalse(watchAsked)
        assertTrue(unlockRefreshWanted({ false }, { true }))
        assertFalse(unlockRefreshWanted({ false }, { false }))
        // A check that fails counts as none, and the other is still asked.
        assertTrue(unlockRefreshWanted({ error("no host") }, { true }))
        assertFalse(unlockRefreshWanted({ error("no host") }, { error("no Play services") }))
    }
}
