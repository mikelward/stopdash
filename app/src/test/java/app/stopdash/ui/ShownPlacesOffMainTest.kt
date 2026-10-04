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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
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
        // One list throughout, as the store's flow hands the screen.
        val places = listOf(here, far)
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                shown = rememberShownPlaces(
                    places, origin, rider, banner = null,
                    hiddenPlaceIds = memory, onHiddenPlaceIds = { memory = it }, today = null,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<FavoritePlace>(), shown)
        assertEquals(emptySet<String>(), memory)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        // At the near place: only the far one is offered, and the near one remembered.
        assertEquals(listOf(far), shown)
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
}
