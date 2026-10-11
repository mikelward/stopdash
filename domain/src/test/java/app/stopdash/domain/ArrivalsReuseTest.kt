package app.stopdash.domain

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalsReuseTest {
    private val near = Duration.ofSeconds(50)
    private val far = Duration.ofSeconds(90)

    @Test
    fun `only a far stop on the timer gets the longer window`() {
        assertEquals(far, ArrivalsReuse.window(near, far, automatic = true, isFar = true))
        assertEquals(near, ArrivalsReuse.window(near, far, automatic = true, isFar = false))
        // A refresh the rider asked for reuses no longer, however far the stop.
        assertEquals(near, ArrivalsReuse.window(near, far, automatic = false, isFar = true))
        // Never shorter than the near window.
        assertEquals(near, ArrivalsReuse.window(near, Duration.ZERO, automatic = true, isFar = true))
    }
}
