package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.TflException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which lines' route data is loading ([rememberLineLoads]): a retry of a failed load included. Synthetic lines only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LineLoadsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val route = LineSequence(routes = listOf(LineRoute("A ↔ B", listOf("A", "B"))), stopNames = mapOf("A" to "A", "B" to "B"))

    @Test
    fun `a failed route's retry is under way, not failed`() {
        var failing = true
        // Each request after the first waits here until the test lets it answer.
        var gate: CompletableDeferred<Unit>? = null
        val source = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                if (failing) throw TflException.Offline(null)
                gate?.await()
                return route
            }
        }
        val repository = RouteStopsRepository(source)
        var now by mutableStateOf(Instant.parse("2026-09-30T08:00:00Z"))
        var loads: LineLoads? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides repository) {
                loads = rememberLineLoads(listOf("1"), now)
            }
        }
        composeRule.waitForIdle()
        // Failed: held as null, and nothing under way.
        assertTrue("1" in loads!!.sequences)
        assertNull(loads!!.sequences["1"])
        assertFalse("1" in loads!!.loading)
        // The hourly retry: still null while it runs, but under way.
        failing = false
        val slow = CompletableDeferred<Unit>().also { gate = it }
        now = now.plus(Duration.ofHours(1))
        composeRule.waitForIdle()
        assertNull(loads!!.sequences["1"])
        assertTrue("1" in loads!!.loading)
        // Answered: loaded, and no longer under way.
        slow.complete(Unit)
        composeRule.waitForIdle()
        // Both directions, each answered with the one route.
        assertEquals(route.routes + route.routes, loads!!.sequences["1"]?.routes)
        assertFalse("1" in loads!!.loading)
    }
}
