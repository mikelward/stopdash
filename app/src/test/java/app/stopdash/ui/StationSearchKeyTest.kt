package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import app.stopdash.domain.Coordinates
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.PlaceType
import app.stopdash.domain.SearchEntry
import app.stopdash.domain.StationMatch
import app.stopdash.domain.TripDestination
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The keyboard's Search key (maintainer, 2026-10-13): it opens the row the whole answer is sure of, as
 * a tap would, and otherwise only closes the keyboard. Public TfL stations and a synthetic place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StationSearchKeyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))
    private val oxbow = StationMatch("940GZZOXB", "Oxbow Park", listOf("tube"))
    private val gallery = PlaceHit("Sample Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE, type = PlaceType.GALLERY)

    private var opened: StationMatch? = null
    private var routed: TripDestination.Place? = null
    private var picked: PlaceHit? = null

    private fun answer(query: String, entries: List<SearchEntry>, searchKey: SearchEntry?) = StationSearchViewModel.Result.Matches(
        matches = entries.filterIsInstance<SearchEntry.Stop>().map { it.match },
        places = entries.filterIsInstance<SearchEntry.Place>().map { it.hit },
        entries = entries,
        query = query,
        searchKey = searchKey,
    )

    private fun pressSearch(state: StationSearchViewModel.State, routesPlaces: Boolean = true) {
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = state,
                    onQueryChange = {},
                    onOpenStation = { opened = it },
                    onBack = {},
                    autoFocus = false,
                    onOpenPlace = if (routesPlaces) { place -> routed = place } else null,
                    onPlacePicked = { picked = it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stationSearchField").performImeAction()
        composeRule.waitForIdle()
    }

    private val sure = StationSearchViewModel.State(
        query = "oxford circus",
        result = answer("oxford circus", listOf(SearchEntry.Stop(oxford), SearchEntry.Stop(oxbow)), SearchEntry.Stop(oxford)),
        yoursRead = true,
    )

    @Test
    fun `a sure answer's row opens as a tap on it would`() {
        pressSearch(sure)
        assertEquals(oxford, opened)
    }

    @Test
    fun `an edit the screen hasn't drawn yet keeps the last query's row from opening`() {
        // The keyboard commits its last edit and sends Search together: the field holds the new text
        // while the state, and its sure row, are still the last query's (Codex on #828).
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = sure,
                    onQueryChange = {},
                    onOpenStation = { opened = it },
                    onBack = {},
                    autoFocus = false,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stationSearchField").performTextInput(" x")
        composeRule.onNodeWithTag("stationSearchField").performImeAction()
        composeRule.waitForIdle()
        assertNull(opened)
    }

    @Test
    fun `while a search runs the key opens nothing`() {
        // The rows still up are the last query's: the next answer is on its way.
        pressSearch(sure.copy(query = "oxford circ", searching = true))
        assertNull(opened)
    }

    @Test
    fun `an answer that isn't sure leaves the list to choose from`() {
        pressSearch(sure.copy(query = "ox", result = answer("ox", listOf(SearchEntry.Stop(oxford), SearchEntry.Stop(oxbow)), null)))
        assertNull(opened)
    }

    @Test
    fun `while places outside London are looked up the key opens nothing`() {
        pressSearch(sure.copy(outside = OutsideLondon.Searching))
        assertNull(opened)
    }

    @Test
    fun `with places outside London listed below the key opens nothing`() {
        pressSearch(sure.copy(outside = OutsideLondon.Found(listOf(gallery))))
        assertNull(opened)
    }

    @Test
    fun `a sure place is routed to and remembered, as a tap would`() {
        val place = SearchEntry.Place(gallery)
        pressSearch(sure.copy(query = "sample gallery", result = answer("sample gallery", listOf(place), place)))
        assertEquals(gallery.coordinate to gallery.name, routed?.let { it.coordinate to it.name })
        assertEquals(gallery, picked)
    }

    @Test
    fun `a sure place where no place can be routed opens nothing`() {
        val place = SearchEntry.Place(gallery)
        pressSearch(sure.copy(query = "sample gallery", result = answer("sample gallery", listOf(place), place)), routesPlaces = false)
        assertNull(routed)
        assertNull(opened)
    }
}
