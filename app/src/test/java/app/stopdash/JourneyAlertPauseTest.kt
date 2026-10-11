package app.stopdash

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyAlertResult
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.JourneyLineAlert
import java.util.concurrent.Executors
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Pausing journey alerts from an alert, and unpausing them (SPEC *Journeys → Alerts*). Public stations only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JourneyAlertPauseTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val journey = FavoriteJourney(JourneyEnd("940GZZLUEUS", "Euston"), JourneyEnd("940GZZLUWLO", "Waterloo"), "northern", "Northern", "tube")
    private val result = JourneyAlertResult(journey.key, listOf(JourneyLineAlert("northern", "Northern", "Severe Delays", "Northern line: severe delays.")))

    @Before
    fun allow() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun unpause() = runBlocking {
        JourneyAlertPause.set(context, paused = false)
        Unit
    }

    @Test
    fun an_alert_offers_Pause() {
        assertTrue(JourneyAlertNotification.post(context, journey, result))
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(listOf("Pause"), posted.actions.map { it.title.toString() })
    }

    @Test
    fun Pause_takes_the_alert_down_and_nothing_is_posted_until_Unpause() = runBlocking {
        assertTrue(JourneyAlertNotification.post(context, journey, result))
        var resyncs = 0
        assertTrue(JourneyAlertPauseReceiver.pause(context, resync = { resyncs++ }))
        // The checks are re-armed as paused.
        assertEquals(1, resyncs)
        assertTrue(JourneyAlertPause.isPaused(context))
        assertEquals(true, JourneyAlertPause.paused.value)
        assertTrue(JourneyAlertNotification.showing(context).isEmpty())
        // Paused: nothing is to be shown, though Android would show it.
        assertTrue(JourneyAlertNotification.canAlert(context))
        assertFalse(JourneyAlertNotification.mayAlert(context))
        assertFalse(JourneyAlertNotification.post(context, journey, result))
        assertTrue(JourneyAlertPause.set(context, paused = false))
        assertEquals(false, JourneyAlertPause.paused.value)
        assertTrue(JourneyAlertNotification.mayAlert(context))
        assertTrue(JourneyAlertNotification.post(context, journey, result))
    }

    @Test
    fun a_resync_that_fails_still_leaves_alerts_paused_and_down() = runBlocking {
        assertTrue(JourneyAlertNotification.post(context, journey, result))
        JourneyAlertPauseReceiver.pause(context, resync = { throw java.io.IOException("journeys unreadable") })
        assertTrue(JourneyAlertPause.isPaused(context))
        assertTrue(JourneyAlertNotification.showing(context).isEmpty())
    }

    @Test
    fun alerts_that_couldnt_be_taken_down_still_leave_alerts_paused() = runBlocking {
        var resyncs = 0
        // No throw reaches the caller: the pause is saved and holds, and the checks are still re-armed.
        assertTrue(JourneyAlertPauseReceiver.pause(context, takeDown = { throw SecurityException("no access") }, resync = { resyncs++ }))
        assertTrue(JourneyAlertPause.isPaused(context))
        assertEquals(1, resyncs)
    }

    @Test
    fun a_write_that_fails_leaves_the_pause_as_it_was_in_memory_too() = runBlocking {
        // As Android's does, a failed commit has already changed the in-memory copy.
        val store = FailingPrefs()
        store.fail = true
        assertFalse(JourneyAlertPause.set(context, paused = true, store = store))
        assertFalse(store.getBoolean("paused", true))
        assertEquals(false, JourneyAlertPause.paused.value)
        store.fail = false
        assertTrue(JourneyAlertPause.set(context, paused = true, store = store))
        store.fail = true
        assertFalse(JourneyAlertPause.set(context, paused = false, store = store))
        assertTrue(store.getBoolean("paused", false))
        assertEquals(true, JourneyAlertPause.paused.value)
    }

    @Test
    fun a_gate_never_sees_a_pause_that_isnt_saved_yet() = runBlocking {
        // Read mid-commit, after Android has put the new value in memory but before the commit has failed: the
        // gate posting an alert reads this, and acting on an unpause that never happens would post one.
        val store = FailingPrefs()
        store.fail = false
        assertTrue(JourneyAlertPause.set(context, paused = true, store = store))
        assertTrue(JourneyAlertPause.isPaused(context, store))
        val seen = ArrayList<Boolean>()
        store.fail = true
        store.onCommit = { seen += JourneyAlertPause.isPaused(context, store) }
        assertFalse(JourneyAlertPause.set(context, paused = false, store = store))
        assertEquals(listOf(true), seen)
        assertTrue(JourneyAlertPause.isPaused(context, store))
    }

    /** Preferences whose commits change memory first, then succeed or fail to reach disk, as Android's do. */
    private class FailingPrefs : android.content.SharedPreferences {
        val values = HashMap<String, Any?>()
        var fail = true
        // Runs inside a commit, between the in-memory change and its result.
        var onCommit: () -> Unit = {}
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in values
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                values.putAll(pending)
                onCommit()
                return !fail
            }
            override fun apply() { values.putAll(pending) }
        }
    }

    @Test
    fun an_unpause_waits_for_a_pause_still_under_way() = kotlinx.coroutines.test.runTest {
        // One test dispatcher for everything, so where each side stands is decided by the test, not by timing.
        val io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val order = ArrayList<String>()
        launch { JourneyAlertPauseReceiver.pause(context, io = io, takeDown = { gate.await() }, resync = { order += "paused resync" }) }
        runCurrent()
        // Saved, and still taking alerts down, when Unpause is tapped.
        assertTrue(JourneyAlertPause.isPaused(context))
        val unpausing = async { JourneyAlertPause.unpause(context, io = io).also { order += "unpaused" } }
        runCurrent()
        // Held until the pause is done, so its re-arm can't replace the unpause's.
        assertFalse(unpausing.isCompleted)
        assertTrue(JourneyAlertPause.isPaused(context))
        gate.complete(Unit)
        assertTrue(unpausing.await())
        assertEquals(listOf("paused resync", "unpaused"), order)
        assertFalse(JourneyAlertPause.isPaused(context))
    }

    @Test
    fun a_post_waiting_on_a_pause_takedown_sees_the_pause_and_posts_nothing() {
        // A post that passed its first gate before the Pause was saved, then waits while the Pause holds the
        // posting lock to take alerts down: when it gets the lock it sees the pause, so nothing lands after.
        var posted: Boolean? = null
        val poster = Thread { posted = JourneyAlertNotification.post(context, journey, result) }
        synchronized(JourneyAlertPause.posting) {
            poster.start()
            // Deterministic: the poster is parked on the lock this thread holds.
            while (poster.state != Thread.State.BLOCKED) Thread.onSpinWait()
            runBlocking { assertTrue(JourneyAlertPause.set(context, paused = true)) }
        }
        poster.join()
        assertEquals(false, posted)
        assertTrue(JourneyAlertNotification.showing(context).isEmpty())
    }

    @Test
    fun a_check_that_found_alerts_paused_says_so_even_after_asking() {
        // Paused while TfL was asked: the answer went unposted for that, whatever else held.
        assertEquals("paused", checkSkipReason(closeOnly = false, noWindow = false, paused = true, asking = true, abroad = false, away = false))
        assertEquals(null, checkSkipReason(closeOnly = false, noWindow = false, paused = false, asking = true, abroad = false, away = false))
        assertEquals("notifications off", checkSkipReason(closeOnly = false, noWindow = false, paused = false, asking = false, abroad = false, away = false))
        assertEquals("no window open", checkSkipReason(closeOnly = false, noWindow = true, paused = true, asking = false, abroad = false, away = false))
    }

    @Test
    fun a_pause_that_couldnt_be_saved_changes_nothing() = runBlocking {
        assertTrue(JourneyAlertNotification.post(context, journey, result))
        var resyncs = 0
        assertFalse(JourneyAlertPauseReceiver.pause(context, save = { false }, resync = { resyncs++ }))
        // A save that throws is the same: no crash, nothing changed.
        assertFalse(JourneyAlertPauseReceiver.pause(context, save = { throw java.io.IOException("disk") }, resync = { resyncs++ }))
        // Still up, still not paused, nothing re-armed: what shows is what holds.
        assertEquals(setOf(journey.key), JourneyAlertNotification.showing(context))
        assertFalse(JourneyAlertPause.isPaused(context))
        assertEquals(0, resyncs)
    }

    @Test
    fun an_unpause_that_couldnt_be_saved_says_so_and_stays_paused() = runBlocking {
        assertTrue(JourneyAlertPause.set(context, paused = true))
        assertFalse(JourneyAlertPause.unpause(context, write = { false }))
        assertFalse(JourneyAlertPause.unpause(context, write = { throw java.io.IOException("disk") }))
        assertTrue(JourneyAlertPause.isPaused(context))
        assertTrue(JourneyAlertPause.unpause(context))
        assertFalse(JourneyAlertPause.isPaused(context))
    }

    @Test
    fun the_Pause_button_reaches_the_receiver() {
        assertTrue(JourneyAlertNotification.post(context, journey, result))
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val sent = shadowOf(posted.actions.single().actionIntent).savedIntent
        assertEquals(JourneyAlertPauseReceiver.ACTION_PAUSE, sent.action)
        assertEquals(JourneyAlertPauseReceiver::class.java.name, sent.component?.className)
    }

    @Test
    fun pausing_and_unpausing_run_off_the_caller_thread() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            val threads = ThreadRecorder()
            val io = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    worker.dispatch(context, Runnable { threads.note(); block.run() })
                }
            }
            runBlocking(caller) {
                JourneyAlertPause.set(context, paused = true, io = io)
                JourneyAlertPause.load(context, io = io)
            }
            assertTrue(threads.threads().isNotEmpty())
            assertTrue(threads.threads().all { it == "test-worker" })
        } finally {
            caller.close()
            worker.close()
        }
    }
}
