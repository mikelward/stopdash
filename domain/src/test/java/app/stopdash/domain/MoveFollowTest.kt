package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveFollowTest {
    // Synthetic positions: about 330 m apart, north-south.
    private val shown = Coordinates(51.5, -0.12)
    private val walked = Coordinates(51.503, -0.12)
    private val nearby = Coordinates(51.5005, -0.12) // about 55 m

    private fun fix(at: Coordinates = walked, accuracy: Float? = 15f, age: Long? = 0) =
        LocationFix(at, isFallback = false, accuracyMeters = accuracy, ageMillis = age)

    private val aMinute = MoveFollow.MIN_GAP_MILLIS

    @Test
    fun `a sure fix far enough from the list, a minute after it was found, moves it`() {
        assertTrue(MoveFollow.shouldFollow(shown, fix(), aMinute))
    }

    @Test
    fun `a fix close to where the list was found doesn't`() {
        assertFalse(MoveFollow.shouldFollow(shown, fix(at = nearby), aMinute))
    }

    @Test
    fun `within a minute of the last lookup it doesn't yet, but waits out the rest of the minute`() {
        assertFalse(MoveFollow.shouldFollow(shown, fix(), aMinute - 1))
        assertEquals(1L, MoveFollow.followAfterMillis(shown, fix(), aMinute - 1))
        assertEquals(0L, MoveFollow.followAfterMillis(shown, fix(), aMinute + 5))
        // A fix that never qualifies doesn't wait at all.
        assertEquals(null, MoveFollow.followAfterMillis(shown, fix(at = nearby), 0))
        // But a sure one near the list still says where the rider is, so it can cancel a wait.
        assertTrue(MoveFollow.isSure(fix(at = nearby)))
        assertFalse(MoveFollow.isSure(fix(accuracy = MoveFollow.MAX_ACCURACY_METERS + 1)))
    }

    @Test
    fun `a vague, unmeasured, coarse, old or fallback fix doesn't`() {
        assertFalse(MoveFollow.shouldFollow(shown, fix(accuracy = MoveFollow.MAX_ACCURACY_METERS + 1), aMinute))
        assertFalse(MoveFollow.shouldFollow(shown, fix(accuracy = null), aMinute))
        assertFalse(MoveFollow.shouldFollow(shown, fix().copy(isCoarse = true), aMinute))
        assertFalse(MoveFollow.shouldFollow(shown, fix(age = MoveFollow.MAX_AGE_MILLIS + 1), aMinute))
        assertFalse(MoveFollow.shouldFollow(shown, fix().copy(isFallback = true), aMinute))
    }
}
