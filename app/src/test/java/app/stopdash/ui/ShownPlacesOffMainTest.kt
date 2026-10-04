package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoriteShortcuts
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
 * The favorite chips ([rememberShownPlaces]) are worked out on the list's worker ([LocalWorker]), never
 * the main thread (AGENTS.md *Main thread: read and dispatch only*): every saved place measured from
 * the rider, then the memory of where they are handed back once that answer is in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class ShownPlacesOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Obviously synthetic positions near the origin, never a real place (SPEC *Privacy*).
    private val origin = Coordinates(0.0, 0.0)
    private val here = FavoritePlace("h", FavoriteKind.CUSTOM, "Here", Coordinates(0.0005, 0.0))
    private val far = FavoritePlace("f", FavoriteKind.CUSTOM, "Far", Coordinates(0.02, 0.0))

    // A rider accurately at [at], for the set found from the origin.
    private fun fix(at: Coordinates) = NearbyStopsViewModel.RiderFix(origin, at, accurate = true)

    @Test
    fun the_chips_are_worked_out_on_the_worker() {
        // Held until released by hand: worked out on the main thread, they'd be there at once.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var rider by mutableStateOf(fix(origin))
        var memory by mutableStateOf(emptySet<String>())
        var shown: List<FavoritePlace> = emptyList()
        var pending = false
        // One list throughout, as the store's flow hands the screen.
        val places = listOf(here, far)
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                shown = rememberShownPlaces(
                    places, origin, rider, banner = null,
                    hiddenPlaceIds = memory, onHiddenPlaceIds = { memory = it }, today = null,
                ).also { pending = it == null }.orEmpty()
            }
        }
        composeRule.waitForIdle()
        // No answer yet: pending, for the list to wait on, not an empty row.
        assertTrue(pending)
        assertEquals(emptySet<String>(), memory)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        // At the near place: only the far one is offered, and the near one remembered.
        assertEquals(listOf(far), shown)
        assertEquals(false, pending)
        assertEquals(setOf(FavoriteShortcuts.memoryKey(here)), memory)
        // Moved well away: the last chips stand in until the new answer is in, which brings both back.
        rider = fix(Coordinates(0.01, 0.0))
        composeRule.waitForIdle()
        assertEquals(listOf(far), shown)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf(here, far), shown)
        assertEquals(emptySet<String>(), memory)
    }

    @Test
    fun no_chips_stand_in_for_other_places_or_another_day() {
        // Unreadable (null) places, another list or another day offer none at once, not the last chips
        // while the worker runs.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val loaded = listOf(here, far)
        var places by mutableStateOf<List<FavoritePlace>?>(loaded)
        var today by mutableStateOf(java.time.DayOfWeek.MONDAY)
        var shown: List<FavoritePlace> = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                shown = rememberShownPlaces(
                    places, origin, fix(Coordinates(0.01, 0.0)), banner = null,
                    hiddenPlaceIds = emptySet(), onHiddenPlaceIds = {}, today = today,
                ).orEmpty()
            }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(loaded, shown)
        places = null
        composeRule.waitForIdle()
        assertEquals(emptyList<FavoritePlace>(), shown)
        // Another list (a place deleted): none of the old chips meanwhile either.
        places = loaded
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        places = listOf(far)
        composeRule.waitForIdle()
        assertEquals(emptyList<FavoritePlace>(), shown)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf(far), shown)
        // A new day: none of the last day's chips meanwhile.
        today = java.time.DayOfWeek.TUESDAY
        composeRule.waitForIdle()
        assertEquals(emptyList<FavoritePlace>(), shown)
    }

    @Test
    fun the_places_are_only_read_on_the_worker_thread() {
        // A worker on a thread of its own: every read of the saved places happens there, never on the
        // composition's (main) thread.
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "chips-worker") }.asCoroutineDispatcher()
        val reads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val saved = listOf(here, far)
        val places = object : AbstractList<FavoritePlace>() {
            override val size: Int get() = saved.size
            override fun get(index: Int): FavoritePlace {
                reads += Thread.currentThread().name.substringBefore(" @")
                return saved[index]
            }
        }
        var shown: List<FavoritePlace> = emptyList()
        worker.use {
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    shown = rememberShownPlaces(
                        places, origin, fix(origin), banner = null,
                        hiddenPlaceIds = emptySet(), onHiddenPlaceIds = {}, today = null,
                    ).orEmpty()
                }
            }
            composeRule.waitUntil(5_000) { shown.isNotEmpty() }
        }
        assertEquals(listOf(far), shown)
        assertTrue(reads.isNotEmpty())
        assertEquals(setOf("chips-worker"), reads.toSet())
    }

    @Test
    fun a_chip_row_drawn_again_has_its_chips_in_its_first_frame() {
        // The last answer is held by the screen's view model store, not the row's composition: a row
        // drawn again (a return to the list, a recreated screen) shows its chips at once, not a frame
        // later from the worker (Codex on #539).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val places = listOf(here, far)
        var drawn by mutableStateOf(true)
        var shown: List<FavoritePlace> = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                if (drawn) {
                    shown = rememberShownPlaces(
                        places, origin, fix(origin), banner = null,
                        hiddenPlaceIds = emptySet(), onHiddenPlaceIds = {}, today = null,
                    ).orEmpty()
                }
            }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf(far), shown)
        drawn = false
        composeRule.waitForIdle()
        shown = emptyList()
        drawn = true
        composeRule.waitForIdle()
        // The worker still held: the chips are there from the answer kept.
        assertEquals(listOf(far), shown)
    }

    @Test
    fun places_read_after_an_answer_for_none_are_still_waited_on() {
        // The first answer can be for no places (the store not read yet): once the places come, they
        // are pending until their own chips are in, not shown as none (Codex on #539).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val loaded = listOf(here, far)
        var places by mutableStateOf<List<FavoritePlace>?>(null)
        var result: List<FavoritePlace>? = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                result = rememberShownPlaces(
                    places, origin, fix(Coordinates(0.01, 0.0)), banner = null,
                    hiddenPlaceIds = emptySet(), onHiddenPlaceIds = {}, today = null,
                )
            }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        places = loaded
        composeRule.waitForIdle()
        assertEquals(null, result)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(loaded, result)
    }

    @Test
    fun a_first_place_saved_after_none_is_waited_on() {
        // An answer for an empty list (nothing saved yet), then the first place added: pending until its
        // chip is in, not shown as none (Codex on #539).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var places by mutableStateOf(emptyList<FavoritePlace>())
        var result: List<FavoritePlace>? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                result = rememberShownPlaces(
                    places, origin, fix(Coordinates(0.01, 0.0)), banner = null,
                    hiddenPlaceIds = emptySet(), onHiddenPlaceIds = {}, today = null,
                )
            }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(emptyList<FavoritePlace>(), result)
        places = listOf(far)
        composeRule.waitForIdle()
        assertEquals(null, result)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf(far), result)
    }

    @Test
    fun an_edited_list_is_waited_on_but_a_change_of_accuracy_keeps_the_row() {
        // Another list of places (an edit) is pending until its chips are in; a fix only gaining or losing
        // accuracy keeps the last chips up rather than putting the list back on its placeholder.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var places by mutableStateOf(listOf(here, far))
        var accurate by mutableStateOf(true)
        var result: List<FavoritePlace>? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                result = rememberShownPlaces(
                    places, origin, NearbyStopsViewModel.RiderFix(origin, Coordinates(0.01, 0.0), accurate), banner = null,
                    hiddenPlaceIds = emptySet(), onHiddenPlaceIds = {}, today = null,
                )
            }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf(here, far), result)
        accurate = false
        composeRule.waitForIdle()
        assertEquals(listOf(here, far), result)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        places = listOf(far)
        composeRule.waitForIdle()
        assertEquals(null, result)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf(far), result)
    }

    @Test
    fun a_fix_losing_accuracy_hides_nothing_at_once() {
        // At the near place on an accurate fix it's hidden; once the fix isn't accurate, it's offered
        // again in the same frame, not after the worker (SPEC: an imprecise fix hides nothing; Codex on #539).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val places = listOf(here, far)
        var accurate by mutableStateOf(true)
        var result: List<FavoritePlace>? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                result = rememberShownPlaces(
                    places, origin, NearbyStopsViewModel.RiderFix(origin, origin, accurate), banner = null,
                    hiddenPlaceIds = emptySet(), onHiddenPlaceIds = {}, today = null,
                )
            }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf(far), result)
        accurate = false
        composeRule.waitForIdle()
        assertEquals(listOf(here, far), result)
    }
}

