package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.RouteStop
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.StepFreeAccess
import app.stopdash.domain.StepFreeLevel
import app.stopdash.domain.StepFreePlatform
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A route page's per-recomposition passes (AGENTS.md *Main thread: read and dispatch only*): the saved
 * journeys that board here ([rememberJourneysHere]) and each listed station's step-free level
 * ([rememberStepFreeLevels]) are worked out on the worker ([LocalWorker]), never in composition.
 * Example stop ids only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class RoutePageOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val here = RouteStop("940GZZEXMPA", "Example A")
    private val there = RouteStop("940GZZEXMPB", "Example B")
    private val page = RouteStopsUi.Loaded(listOf(here, there))
    private val journey = FavoriteJourney(JourneyEnd(here.id, here.name), JourneyEnd(there.id, there.name), "victoria", "Victoria", "tube")
    private val table = StepFreeAccess(
        mapOf(
            here.id to mapOf("victoria" to listOf(StepFreePlatform(StepFreeLevel.LEVEL))),
            there.id to mapOf("victoria" to listOf(StepFreePlatform(StepFreeLevel.RAMP))),
        ),
    )

    @Test
    fun the_stars_wait_for_the_worker_and_stand_in_for_the_same_row() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var journeys by mutableStateOf(listOf(journey))
        var stopId by mutableStateOf(here.id)
        var result: JourneysShown? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) { result = rememberJourneysHere(journeys, page, stopId, "victoria") }
        }
        composeRule.waitForIdle()
        assertEquals("none until the worker has run", emptySet<String>(), result?.here?.starredStopIds)
        assertEquals("and no taps", false, result?.current)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(setOf(there.id), result?.here?.starredStopIds)
        assertEquals(true, result?.current)

        // Another journey saved on the same row: the stars stay while it's worked out, but a tap
        // waits for the new answer, which the stand-in may lack.
        journeys = listOf(journey, journey.copy(lineName = "Victoria line"))
        composeRule.waitForIdle()
        assertEquals(setOf(there.id), result?.here?.starredStopIds)
        assertEquals(false, result?.current)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(true, result?.current)

        // Another row: its own answer or none, never this row's.
        stopId = there.id
        composeRule.waitForIdle()
        assertEquals(emptySet<String>(), result?.here?.starredStopIds)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(setOf(here.id), result?.here?.starredStopIds)
    }

    @Test
    fun a_reopened_page_has_its_stars_in_its_first_frame() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val journeys = listOf(journey)
        var shown by mutableStateOf(true)
        var result: JourneysShown? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                if (shown) result = rememberJourneysHere(journeys, page, here.id, "victoria")
            }
        }
        composeRule.waitForIdle()
        assertEquals(false, result?.current)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(setOf(there.id), result?.here?.starredStopIds)

        // Closed and opened again on the same stops and journeys, with the worker held.
        shown = false
        composeRule.waitForIdle()
        result = null
        shown = true
        composeRule.waitForIdle()
        assertEquals(setOf(there.id), result?.here?.starredStopIds)
        assertEquals(true, result?.current)
    }

    @Test
    fun the_step_free_marks_wait_for_the_worker_and_come_back_at_once() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        // A table of its own, so no other test's answer is the one kept.
        val table = StepFreeAccess(
            mapOf(
                here.id to mapOf("victoria" to listOf(StepFreePlatform(StepFreeLevel.LEVEL))),
                there.id to mapOf("victoria" to listOf(StepFreePlatform(StepFreeLevel.RAMP))),
            ),
        )
        val marked = mapOf(here.id to StepFreeLevel.LEVEL, there.id to StepFreeLevel.RAMP)
        var liftsOut by mutableStateOf(emptySet<String>())
        var cleared by mutableStateOf<LiftsCleared?>(null)
        var shown by mutableStateOf(true)
        var listed by mutableStateOf(page.stops, androidx.compose.runtime.referentialEqualityPolicy())
        var shownMarks: StepFreeShown? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                if (shown) shownMarks = rememberStepFreeLevels(table, liftsOut, listed, "victoria", "tube", cleared)
            }
        }
        composeRule.waitForIdle()
        assertEquals("pending until the worker has run, so the rail waits", null, shownMarks?.levels)
        assertEquals(false, shownMarks?.known)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(marked, shownMarks?.levels)
        assertEquals(true, shownMarks?.known)

        // The same stops resolved again, a new copy: their marks stay while it's worked out, being exact,
        // but aren't known for it, so they never ready a rail that's waiting.
        listed = page.stops.toList()
        composeRule.waitForIdle()
        assertEquals(marked, shownMarks?.levels)
        assertEquals(false, shownMarks?.known)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(true, shownMarks?.known)

        // Another route on the same line: the last route's marks never count as its answer.
        listed = page.stops.take(1)
        composeRule.waitForIdle()
        assertEquals(false, shownMarks?.known)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(true, shownMarks?.known)
        assertEquals(mapOf(here.id to StepFreeLevel.LEVEL), shownMarks?.levels)
        listed = page.stops
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()

        // A lift newly out: no marks while it's worked out, never the last ones, which may be wrong now.
        liftsOut = setOf("lift-1")
        composeRule.waitForIdle()
        assertEquals(null, shownMarks?.levels)
        assertEquals("nor ready a waiting rail", false, shownMarks?.known)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(marked, shownMarks?.levels)

        // That lift back in service: the marks stay while it's worked out, since one can only be added.
        val outBefore = liftsOut
        val outNow = emptySet<String>().toHashSet()
        cleared = LiftsCleared(outBefore, outNow)
        liftsOut = outNow
        composeRule.waitForIdle()
        assertEquals(marked, shownMarks?.levels)
        assertEquals("nor ready a waiting rail", false, shownMarks?.known)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(marked, shownMarks?.levels)
        assertEquals(true, shownMarks?.known)

        // The page closed and opened again on the same stops: its marks are there in the first frame.
        shown = false
        composeRule.waitForIdle()
        shownMarks = null
        shown = true
        composeRule.waitForIdle()
        assertEquals(marked, shownMarks?.levels)
        assertEquals(true, shownMarks?.known)

        // Another route opened in between: the first, reopened, still finds its own marks at once.
        val other = page.stops.take(1)
        shown = false
        composeRule.waitForIdle()
        listed = other
        shown = true
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(mapOf(here.id to StepFreeLevel.LEVEL), shownMarks?.levels)
        shown = false
        composeRule.waitForIdle()
        listed = page.stops
        shownMarks = null
        shown = true
        composeRule.waitForIdle()
        assertEquals(marked, shownMarks?.levels)
        assertEquals(true, shownMarks?.known)
    }

    @Test
    fun the_journeys_and_stations_are_only_gone_through_on_the_worker_thread() {
        val reads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val journeys = NotingList(listOf(journey), reads)
        val listed = NotingList(page.stops, reads)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "route-worker") }.asCoroutineDispatcher()
        var stars: Set<String>? = null
        var marks: Map<String, StepFreeLevel>? = null
        var lifts: Boolean? = null
        worker.use {
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    stars = rememberJourneysHere(journeys, page, here.id, "victoria").here.starredStopIds
                    marks = rememberStepFreeLevels(table, emptySet(), listed, "victoria", "tube").levels
                    lifts = rememberLiftDependent(table, listed)
                }
            }
            composeRule.waitUntil(5_000) { stars?.isNotEmpty() == true && marks?.isNotEmpty() == true }
        }
        assertEquals(setOf(there.id), stars)
        assertEquals(2, marks?.size)
        assertEquals(false, lifts)
        assertTrue(reads.isNotEmpty())
        assertEquals(setOf("route-worker"), reads.toSet())
    }

    @Test
    fun the_tip_shows_while_the_taps_wait_for_the_journeys() {
        var ready by mutableStateOf(false)
        val tapped = mutableListOf<String>()
        composeRule.setContent {
            RouteStopsSection(
                state = page,
                railColor = androidx.compose.ui.graphics.Color.Blue,
                onRetry = {},
                onToggleJourneyTo = { tapped += it.id },
                onDismissJourneyTip = {},
                journeyTapsReady = ready,
            )
        }
        val tip = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(app.stopdash.R.string.journey_tip)
        // The tip is up from the first frame, so the rail under it never shifts.
        composeRule.onNodeWithText(tip, substring = true).assertExists()
        composeRule.onNodeWithText(there.name).performTouchInput { longClick() }
        assertEquals("no tap before the journeys are in", emptyList<String>(), tapped)

        ready = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText(tip, substring = true).assertExists()
        composeRule.onNodeWithText(there.name).performTouchInput { longClick() }
        assertEquals(listOf(there.id), tapped)
    }

    @Test
    fun the_rail_waits_for_its_first_marks_and_never_goes_back_to_its_placeholder() {
        var ready by mutableStateOf(false)
        var stops by mutableStateOf<RouteStopsUi>(page, androidx.compose.runtime.referentialEqualityPolicy())
        var rail: RouteStopsUi? = null
        var route by mutableStateOf("here|victoria|Example C")
        composeRule.setContent { rail = rememberRailState(stops, ready, page = route) }
        composeRule.waitForIdle()
        assertEquals("behind its placeholder until its marks are in", RouteStopsUi.Loading, rail)

        // Its marks are in (no table yet, an empty answer, say): the rail shows.
        ready = true
        composeRule.waitForIdle()
        assertEquals(page, rail)

        // The table lands and its answer is worked out: the rail the user is reading stays put.
        ready = false
        composeRule.waitForIdle()
        assertEquals(page, rail)

        // The same page's stops resolved again, a new copy, with its marks being worked out: still shown.
        val again = RouteStopsUi.Loaded(page.stops.toList())
        stops = again
        composeRule.waitForIdle()
        assertTrue(rail === again)

        // The followed train now another branch: its route loads afresh, and its new stops wait for their marks.
        stops = RouteStopsUi.Loading
        composeRule.waitForIdle()
        val branch = RouteStopsUi.Loaded(listOf(here))
        stops = branch
        composeRule.waitForIdle()
        assertEquals(RouteStopsUi.Loading, rail)
        ready = true
        composeRule.waitForIdle()
        assertTrue(rail === branch)

        // The followed train gone, its next on another route straight from the memo, with no Loading
        // between: that route's stops wait for their own marks too.
        ready = false
        val other = RouteStopsUi.Loaded(listOf(there))
        route = "here|victoria|Example D"
        stops = other
        composeRule.waitForIdle()
        assertEquals(RouteStopsUi.Loading, rail)
        ready = true
        composeRule.waitForIdle()
        assertTrue(rail === other)
    }

    @Test
    fun no_table_yet_is_pending_while_it_is_read_and_none_once_it_is() {
        var reading by mutableStateOf(true)
        var result: StepFreeShown? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalStepFreeLoading provides reading) {
                result = rememberStepFreeLevels(null, emptySet(), page.stops, "victoria", "tube")
            }
        }
        composeRule.waitForIdle()
        assertEquals("still being read: pending, so the rail waits", null, result?.levels)
        assertEquals(false, result?.known)
        reading = false
        composeRule.waitForIdle()
        assertEquals("read, and none: nothing to mark", emptyMap<String, StepFreeLevel>(), result?.levels)
        assertEquals(true, result?.known)
    }

    @Test
    fun a_journeys_answer_from_before_the_stops_or_journeys_loaded_isnt_current_for_them() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var journeys by mutableStateOf(listOf(journey))
        var stops by mutableStateOf<RouteStopsUi>(RouteStopsUi.Loading)
        var result: JourneysShown? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) { result = rememberJourneysHere(journeys, stops, here.id, "victoria") }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals("current for the page as it was", true, result?.current)

        // The stops land: the answer from before stands in, but isn't current for them until theirs is in.
        stops = page
        composeRule.waitForIdle()
        assertEquals(false, result?.current)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(true, result?.current)
        assertEquals(setOf(there.id), result?.here?.starredStopIds)
        // The saved journeys read in after, with the stops unchanged: not current until worked out for them.
        journeys = listOf(journey, journey.reversed())
        composeRule.waitForIdle()
        assertEquals(false, result?.current)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(true, result?.current)
    }

    /** [items], noting the thread of each pass over it in [reads]. */
    private class NotingList<T>(private val items: List<T>, private val reads: MutableList<String>) : AbstractList<T>() {
        override val size: Int get() = items.size
        override fun get(index: Int): T = items[index]
        override fun iterator(): Iterator<T> {
            reads += Thread.currentThread().name.substringBefore(" @")
            return items.iterator()
        }
    }
}
