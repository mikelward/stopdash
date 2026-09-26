package app.stopdash.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parallel fresh-fix race used by `AndroidLocationProvider` (the `LocationManager` glue itself
 * needs a device). Pins the indoor fix: with fused and GPS unable to answer, the network fix is used
 * a short grace after it arrives — not after every accurate provider has run out its bound — while
 * outdoors an accurate fix still beats a quicker coarse one. Also pins that a timed-out provider is
 * reported, so a hanging provider stays diagnosable. Virtual time throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaceFixTest {
    private val timeout = 4_000L
    private val grace = 2_000L
    private val accurateFix = Coordinates(51.5, -0.12)
    private val coarseFix = Coordinates(51.6, -0.13)
    private val providers = listOf("fused", "gps", "network")
    private val accurate = { provider: String -> provider == "fused" || provider == "gps" }

    @Test
    fun `indoors the network fix is used a short grace after it arrives`() = runTest {
        val timedOut = mutableListOf<String>()
        val result = raceFix(providers, timeout, grace, accurate, onTimeout = { timedOut += it }) { provider ->
            // Fused and GPS can't see the sky; the network provider answers in under a second.
            if (provider == "network") { delay(800); coarseFix } else { delay(timeout * 2); accurateFix }
        }
        assertEquals(coarseFix, result)
        assertEquals("used at arrival + grace, not after the accurate bounds", 800 + grace, currentTime)
        assertTrue("the still-waiting providers are canceled, not reported as hung", timedOut.isEmpty())
    }

    @Test
    fun `outdoors an accurate fix within the grace beats a quicker coarse one`() = runTest {
        val result = raceFix(providers, timeout, grace, accurate) { provider ->
            when (provider) {
                "network" -> { delay(500); coarseFix }
                "gps" -> { delay(1_500); accurateFix }
                else -> { delay(timeout * 2); null }
            }
        }
        assertEquals(accurateFix, result)
        assertEquals(1_500L, currentTime)
    }

    @Test
    fun `an accurate fix is used the moment it arrives and the rest are canceled`() = runTest {
        var networkFinished = false
        val result = raceFix(providers, timeout, grace, accurate) { provider ->
            when (provider) {
                "fused" -> { delay(300); accurateFix }
                else -> { delay(1_000); networkFinished = true; coarseFix }
            }
        }
        assertEquals(accurateFix, result)
        assertEquals(300L, currentTime)
        assertFalse(networkFinished)
    }

    @Test
    fun `every provider timing out returns null and reports each`() = runTest {
        val timedOut = mutableListOf<String>()
        val result = raceFix(providers, timeout, grace, accurate, onTimeout = { timedOut += it }) {
            delay(timeout * 2)
            accurateFix
        }
        assertNull(result)
        assertEquals(providers.toSet(), timedOut.toSet())
        assertEquals("all bounded together, not one after another", timeout, currentTime)
    }

    @Test
    fun `a prompt no-fix is not reported as a timeout`() = runTest {
        val timedOut = mutableListOf<String>()
        val result = raceFix(providers, timeout, grace, accurate, onTimeout = { timedOut += it }) { null }
        assertNull(result)
        assertTrue(timedOut.isEmpty())
    }

    @Test
    fun `with every provider counted accurate, the first fix to arrive wins`() = runTest {
        // An approximate-only grant: no fix is better than another, so nothing waits on a grace.
        val result = raceFix(providers, timeout, grace, isAccurate = { true }) { provider ->
            if (provider == "network") { delay(200); coarseFix } else { delay(timeout * 2); accurateFix }
        }
        assertEquals(coarseFix, result)
        assertEquals(200L, currentTime)
    }

    @Test
    fun `a vague precise fix doesn't end the race when a sure one is still coming`() = runTest {
        // Fused answers first but vague (indoors, say); GPS answers later, sure.
        val vague = Coordinates(51.7, -0.14)
        val result = raceFix(listOf("fused", "gps"), timeout, 0, { true }, accept = { it != vague }) { provider ->
            if (provider == "fused") { delay(300); vague } else { delay(1_500); accurateFix }
        }
        assertEquals(accurateFix, result)
        // With nothing better coming, the vague one is still what's returned, for the caller to judge.
        val only = raceFix(listOf("fused", "gps"), timeout, 0, { true }, accept = { it != vague }) { provider ->
            if (provider == "fused") { delay(300); vague } else null
        }
        assertEquals(vague, only)
    }
}
