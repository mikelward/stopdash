package app.stopdash

import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A station's journey star: saved on the scope it's given, and a failure stands until the screen has said so. */
class JourneyStarsTest {
    private val scope = TestScope(StandardTestDispatcher())

    @Before
    fun reset() {
        JourneyStars.shown()
    }

    @Test
    fun `a failed star says so, logged without the stations, until the screen has said it`() {
        val warnings = mutableListOf<String>()
        JourneyStars.attempt(scope, warn = { warnings += it }) { throw IOException("disk") }
        scope.advanceUntilIdle()
        assertTrue(JourneyStars.failed.value)
        assertEquals(listOf("journey star not saved: IOException"), warnings)
        JourneyStars.shown()
        assertFalse(JourneyStars.failed.value)
    }

    @Test
    fun `a star that saves says nothing`() {
        var saved = 0
        JourneyStars.attempt(scope) { saved++ }
        scope.advanceUntilIdle()
        assertEquals(1, saved)
        assertFalse(JourneyStars.failed.value)
    }
}
