package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.data.TflRouteSequenceDto
import app.stopdash.domain.LineMap
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PartClosure
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A line's map ([rememberLineMap]) is loaded, laid out and folded on the worker ([LocalWorker]), never
 * in composition (AGENTS.md *Main thread: read and dispatch only*): nothing is drawn until the worker
 * runs, and a fold opened keeps the last folding up until the new one is in, so nothing moves under
 * the finger. The real Northern line, from a recorded TfL sequence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class LineMapOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val northern: LineSequence = Json { ignoreUnknownKeys = true }
        .decodeFromString<TflRouteSequenceDto>(checkNotNull(javaClass.getResource("/fixtures/route_sequence_northern_outbound.json")).readText())
        .toLineSequence()

    private val source = object : RouteSequenceSource {
        override suspend fun routeSequence(lineId: String, direction: String): LineSequence = northern
    }

    private val partSuspended = LineStatus(
        "northern", 3, "Part Suspended",
        closures = listOf(PartClosure(3, "Part Suspended", null, listOf(listOf("940GZZLUKNG", "940GZZNEUGST", "940GZZBPSUST"), listOf("940GZZBPSUST", "940GZZNEUGST", "940GZZLUKNG")))),
    )

    @Test
    fun the_map_is_laid_out_and_folded_on_the_worker_never_in_composition() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val repository = RouteStopsRepository(source, io = held, compute = held)
        var opened by mutableStateOf<OpenedFolds?>(null)
        var ui: LineMapUi? = null
        val seen = ArrayList<LineMapUi?>()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository) {
                ui = rememberLineMap("northern", partSuspended, emptySet(), emptySet(), opened, all = false, retry = 0)
                seen += ui
            }
        }
        composeRule.waitForIdle()
        assertEquals("nothing laid out with the worker held", LineMapUi.Loading, ui)

        fun runWorker() = repeat(4) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
        runWorker()
        val first = ui as LineMapUi.Ready
        // Never "no map" for a moment while the route data came in and was laid out.
        assertTrue(seen.none { it == LineMapUi.Unavailable })
        assertTrue(first.items.any { it is LineMap.Item.Station && it.row.name == "Kennington" })
        assertTrue(first.items.any { it is LineMap.Item.Fold && it.level == LineMap.Level.CLOSURE })

        // A fold opened: the folding it was tapped on stays up while the new one is worked out.
        opened = OpenedFolds(first.items.first { it is LineMap.Item.Fold }.key, null)
        composeRule.waitForIdle()
        assertSame(first.items, (ui as LineMapUi.Ready).items)
        runWorker()
        assertNotSame(first.items, (ui as LineMapUi.Ready).items)
        assertTrue((ui as LineMapUi.Ready).items.size > first.items.size)
    }

    @Test
    fun a_map_laid_out_for_another_status_never_stands_in_for_the_new_one() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val repository = RouteStopsRepository(source, io = held, compute = held)
        var status by mutableStateOf(partSuspended)
        var key by mutableStateOf<Any?>(LineMap.alertKey(partSuspended))
        var ui: LineMapUi? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository) {
                ui = rememberLineMap("northern", status, emptySet(), emptySet(), null, all = false, retry = 0, statusKey = key)
            }
        }
        fun runWorker() = repeat(4) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
        runWorker()
        val suspended = ui as LineMapUi.Ready
        assertTrue(suspended.map.rows.any { it.unserved })

        // The same alert in a status rebuilt (whether it's the line's only one now known, say): the map stays
        // up meanwhile. (With no words, its label is what picks out the closure shown, so that stays.)
        status = partSuspended.copy(soleAlert = true)
        key = LineMap.alertKey(status)
        composeRule.waitForIdle()
        assertSame(suspended.items, (ui as LineMapUi.Ready).items)
        runWorker()

        // The closure over: never its closed track under a status saying so, but loading until the new map is in.
        val good = LineStatus("northern", LineStatus.GOOD_SERVICE, "Good Service")
        status = good
        key = LineMap.alertKey(good)
        composeRule.waitForIdle()
        assertEquals(LineMapUi.Loading, ui)
        runWorker()
        assertTrue((ui as LineMapUi.Ready).map.rows.none { it.unserved })
    }
}
