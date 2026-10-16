package app.stopdash.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Waiting for the app to be in front before a near-me fix ([InFront]): Android gives no fix to an app
 * it doesn't count in front, so asking then read as "couldn't get your location" (bug report,
 * 2026-10-16). Virtual time; [InFront.await]'s two inputs are plain fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InFrontTest {
    private val onScreen = MutableStateFlow(false)
    private var inFront = false
    private var asked = 0
    private val logs = mutableListOf<String>()

    private fun check(): Boolean {
        asked++
        return inFront
    }

    @Test
    fun `an app on screen and in front goes ahead at once, saying nothing`() = runTest {
        onScreen.value = true
        inFront = true
        InFront.await(::check, onScreen, logs::add)
        assertEquals(0L, currentTime)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `counted in front with nothing on screen, as with a trip's foreground service, it still waits`() = runTest {
        // A foreground service ranks the process in front with no activity started (Codex).
        inFront = true
        val wait = async { InFront.await(::check, onScreen, logs::add) }
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertFalse(wait.isCompleted)
        assertEquals("not even asked: off screen decides", 0, asked)
        onScreen.value = true
        runCurrent()
        assertTrue(wait.isCompleted)
        assertEquals(listOf("location fix put off: the app isn't in front"), logs)
    }

    @Test
    fun `an app away waits for its return without asking Android again meanwhile`() = runTest {
        val wait = async { InFront.await(::check, onScreen, logs::add) }
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertFalse("still waiting while nothing is on screen", wait.isCompleted)
        assertEquals("off screen, Android isn't asked: it waits on the screen alone", 0, asked)
        assertEquals(listOf("location fix put off: the app isn't in front"), logs)
        // Back on screen and in front: the fix goes ahead at once.
        inFront = true
        onScreen.value = true
        runCurrent()
        assertTrue(wait.isCompleted)
        assertEquals(1, logs.size)
    }

    @Test
    fun `just after an unlock it waits on screen until Android counts the app in front`() = runTest {
        onScreen.value = true
        val wait = async { InFront.await(::check, onScreen, logs::add) }
        advanceTimeBy(InFront.SETTLE_MILLIS / 2)
        runCurrent()
        assertFalse(wait.isCompleted)
        inFront = true
        advanceTimeBy(InFront.CHECK_EVERY_MILLIS)
        runCurrent()
        assertTrue(wait.isCompleted)
        assertTrue("within the settle, so it took no fix it was refused", currentTime < InFront.SETTLE_MILLIS)
        assertEquals(listOf("location fix put off: the app isn't in front"), logs)
    }

    @Test
    fun `on screen but never counted in front, it asks anyway once settled, and says so`() = runTest {
        onScreen.value = true
        val wait = async { InFront.await(::check, onScreen, logs::add) }
        advanceTimeBy(InFront.SETTLE_MILLIS - 1)
        runCurrent()
        assertFalse(wait.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(wait.isCompleted)
        assertEquals(InFront.SETTLE_MILLIS, currentTime)
        assertEquals(
            listOf(
                "location fix put off: the app isn't in front",
                "location fix: the app is on screen but still not in front after 2 s; asking anyway",
            ),
            logs,
        )
    }

    @Test
    fun `leaving the screen in the settle's last moment still waits for the next return`() = runTest {
        // Off screen between the last look and the settle running out (Codex).
        onScreen.value = true
        val wait = async { InFront.await(::check, onScreen, logs::add) }
        advanceTimeBy(InFront.SETTLE_MILLIS - InFront.CHECK_EVERY_MILLIS / 2)
        runCurrent()
        onScreen.value = false
        advanceTimeBy(10 * InFront.SETTLE_MILLIS)
        runCurrent()
        assertFalse("not asked for with the app away", wait.isCompleted)
        assertEquals(1, logs.size)
        onScreen.value = true
        inFront = true
        runCurrent()
        assertTrue(wait.isCompleted)
    }

    @Test
    fun `leaving the screen while Android is asked waits for the next return`() = runTest {
        // The activity stops during the in-front check, with a trip's foreground service keeping the rank (Codex).
        onScreen.value = true
        inFront = true
        var leaves = true
        val asking = {
            if (leaves) onScreen.value = false
            check()
        }
        val wait = async { InFront.await(asking, onScreen, logs::add) }
        advanceTimeBy(10 * InFront.SETTLE_MILLIS)
        runCurrent()
        assertFalse("not asked for with the app away", wait.isCompleted)
        assertEquals(listOf("location fix put off: the app isn't in front"), logs)
        leaves = false
        onScreen.value = true
        runCurrent()
        assertTrue(wait.isCompleted)
    }

    @Test
    fun `leaving the screen while Android is asked mid-settle waits for the next return`() = runTest {
        onScreen.value = true
        var leaves = false
        val asking = {
            if (leaves) onScreen.value = false
            check()
        }
        val wait = async { InFront.await(asking, onScreen, logs::add) }
        advanceTimeBy(InFront.SETTLE_MILLIS / 2)
        runCurrent()
        // Counted in front at last, but the app is gone by the time Android answers.
        inFront = true
        leaves = true
        advanceTimeBy(10 * InFront.SETTLE_MILLIS)
        runCurrent()
        assertFalse("not asked for with the app away", wait.isCompleted)
        assertEquals(1, logs.size)
        leaves = false
        onScreen.value = true
        runCurrent()
        assertTrue(wait.isCompleted)
    }

    @Test
    fun `leaving the screen before it settles waits for the next return rather than asking`() = runTest {
        onScreen.value = true
        val wait = async { InFront.await(::check, onScreen, logs::add) }
        advanceTimeBy(InFront.SETTLE_MILLIS / 2)
        runCurrent()
        onScreen.value = false
        // Long past the settle while off screen: nothing is asked for.
        advanceTimeBy(10 * InFront.SETTLE_MILLIS)
        runCurrent()
        assertFalse(wait.isCompleted)
        // The next return gets a settle of its own.
        onScreen.value = true
        runCurrent()
        inFront = true
        advanceTimeBy(InFront.CHECK_EVERY_MILLIS)
        runCurrent()
        assertTrue(wait.isCompleted)
        assertEquals(1, logs.size)
    }
}
