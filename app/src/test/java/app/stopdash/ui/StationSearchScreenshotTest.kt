package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.SearchEntry
import app.stopdash.domain.StationMatch
import app.stopdash.domain.TripDestination
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import app.stopdash.domain.ChipLabel
import app.stopdash.domain.FavoritePlaceIcon
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Find a station" (SPEC *Finding stops*): the search with matches, before a query (with and
 * without the user's own stops, and as the To… destination search), and after a failure, plus a station page still loading its stops. UI-only, so it renders with no network.
 * Public station names only, no user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StationSearchScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val matches = listOf(
        StationMatch("HUBKGX", "King's Cross St. Pancras", listOf("tube", "national-rail", "bus")),
        StationMatch("940GZZLUKSX", "King's Cross St. Pancras", listOf("tube")),
        StationMatch("490G00000001", "King's Cross Station", listOf("bus")),
    )

    private fun show(state: StationSearchViewModel.State, onOpen: (StationMatch) -> Unit = {}) {
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = state,
                    onQueryChange = {},
                    onOpenStation = onOpen,
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun station_search_matches() {
        var opened: StationMatch? = null
        show(
            StationSearchViewModel.State(query = "kings", result = StationSearchViewModel.Result.Matches(matches)),
            onOpen = { opened = it },
        )
        composeRule.onNodeWithText("Tube · National Rail · Bus").assertIsDisplayed()
        captureSnapshot("station-search-matches.png")
        composeRule.onNodeWithText("King's Cross Station").performClick()
        assertEquals("490G00000001", opened?.id)
    }

    @Test
    fun station_search_long_names() {
        // The two hard cases on one row each: a very long name with few modes (the name keeps priority
        // and ellipsizes only what's left, the short modes shown in full on the right), and a short name
        // with many modes (the modes bounded and ellipsized so they can't squeeze the name). Public
        // station names only.
        show(
            StationSearchViewModel.State(
                query = "st",
                result = StationSearchViewModel.Result.Matches(
                    listOf(
                        StationMatch("HUBKGX", "King's Cross & St Pancras International", listOf("national-rail", "tube")),
                        StationMatch("HUBSRA", "Stratford", listOf("dlr", "elizabeth-line", "national-rail", "overground", "tube")),
                    ),
                ),
            ),
        )
        composeRule.onNodeWithText("Stratford").assertIsDisplayed()
        // The row merges into one TalkBack label: the FULL name (not the visual "Intl") plus the modes,
        // so the abbreviation and the modes' visual ellipsis don't reach the screen reader.
        composeRule.onNodeWithContentDescription("King's Cross & St Pancras International, National Rail · Tube")
            .assertIsDisplayed()
        captureSnapshot("station-search-long-names.png")
    }

    @Test
    fun station_search_to_places() {
        // The To… picker offers saved favorite places at the top, before any typing, so a rider routes
        // home in one tap. Synthetic coordinates and generic labels — no user data.
        var routed: TripDestination.Place? = null
        var edited = false
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(
                        favoritePlaces = listOf(
                            FavoritePlace("home", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12)),
                            FavoritePlace(
                                "work", FavoriteKind.WORK, "Work", Coordinates(51.51, -0.10),
                                icon = FavoritePlaceIcon.WORK, chipShows = ChipLabel.ICON,
                            ),
                        ),
                        recent = listOf(SearchEntry.Stop(StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube")))),
                        yoursRead = true,
                    ),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    hint = "To station or stop",
                    onOpenPlace = { routed = it },
                    onEditPlaces = { edited = true },
                )
            }
        }
        composeRule.waitForIdle()
        // The places are one row of chips leading the list, above the recent stops; no "Here" on To….
        val placesTop = composeRule.onNodeWithTag("favoriteChip-home").fetchSemanticsNode().boundsInRoot.top
        assertTrue(placesTop < composeRule.onNodeWithText("Recent").fetchSemanticsNode().boundsInRoot.top)
        assertEquals(placesTop, composeRule.onNodeWithTag("favoriteChip-work").fetchSemanticsNode().boundsInRoot.top)
        composeRule.onNodeWithTag("stationSearchHere").assertDoesNotExist()
        // Room for both: a place set to show only its icon on the near-me list still shows its name here.
        composeRule.onNodeWithText("Work").assertIsDisplayed()
        captureSnapshot("station-search-to-places.png")
        composeRule.onNodeWithTag("favoriteChip-home").performClick()
        assertEquals(TripDestination.Place(Coordinates(51.5, -0.12), "Home"), routed)
        // A long press edits the places, as on the near-me list's chips.
        composeRule.onNodeWithTag("favoriteChip-work").performTouchInput { longClick() }
        assertTrue(edited)
    }

    @Test
    fun station_search_to_recent_place() {
        // A place picked from an earlier To… search is listed under Recent with the stops, in the order
        // picked, tagged as a place; a tap routes to it again and moves it to the front. Synthetic
        // coordinates and a generic name — no user data.
        val gallery = PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE)
        var routed: TripDestination.Place? = null
        var picked: PlaceHit? = null
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(
                        recent = listOf(
                            SearchEntry.Place(gallery),
                            SearchEntry.Stop(StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))),
                        ),
                        yoursRead = true,
                    ),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    hint = "To station or stop",
                    onOpenPlace = { routed = it },
                    onPlacePicked = { picked = it },
                )
            }
        }
        composeRule.waitForIdle()
        val placeTop = composeRule.onNodeWithText("Example Gallery").fetchSemanticsNode().boundsInRoot.top
        assertTrue(placeTop < composeRule.onNodeWithText("Oxford Circus").fetchSemanticsNode().boundsInRoot.top)
        composeRule.onNodeWithText("Place").assertIsDisplayed()
        captureSnapshot("station-search-to-recent-place.png")
        composeRule.onNodeWithText("Example Gallery").performClick()
        assertEquals(gallery, picked)
        assertEquals(TripDestination.Place(Coordinates(51.5, -0.12), "Example Gallery"), routed)
    }

    @Test
    fun station_search_recent_place_not_listed_where_no_place_can_be_routed() {
        // A search that routes to no place (From…, a station browse) lists the recent stops alone.
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(
                        recent = listOf(
                            SearchEntry.Place(PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE)),
                            SearchEntry.Stop(StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))),
                        ),
                        yoursRead = true,
                    ),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Oxford Circus").assertIsDisplayed()
        composeRule.onNodeWithText("Example Gallery").assertDoesNotExist()
    }

    @Test
    fun station_search_from_offers_here() {
        // The From… search heads its list with "Here", the rider's own position, above the recent stops
        // (maintainer, 2026-09-28). Public station names only.
        var here = false
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(
                        recent = listOf(SearchEntry.Stop(StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube")))),
                        yoursRead = true,
                    ),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    onPickHere = { here = true },
                )
            }
        }
        composeRule.waitForIdle()
        val hereTop = composeRule.onNodeWithTag("stationSearchHere").fetchSemanticsNode().boundsInRoot.top
        assertTrue(hereTop < composeRule.onNodeWithText("Recent").fetchSemanticsNode().boundsInRoot.top)
        captureSnapshot("station-search-from-here.png")
        composeRule.onNodeWithTag("stationSearchHere").performClick()
        assertTrue(here)
    }

    @Test
    fun station_search_from_offers_here_before_the_saved_stops_are_read() {
        // "Here" needs no read, so it shows on the first frame while the recent and starred stops load.
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(yoursRead = false),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    onPickHere = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stationSearchHere").assertIsDisplayed()
    }

    @Test
    fun station_search_from_here_hidden_once_typing_and_on_to() {
        // Typing lists only the matches, and the To… search offers no "Here" at all.
        show(StationSearchViewModel.State(query = "kings", result = StationSearchViewModel.Result.Matches(matches)))
        composeRule.onNodeWithTag("stationSearchHere").assertDoesNotExist()
    }

    @Test
    fun station_search_to_offers_no_here() {
        // The To… search lists the rider's picks with no "Here" row: a trip to where they are goes nowhere.
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(
                        recent = listOf(SearchEntry.Stop(StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube")))),
                        yoursRead = true,
                    ),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    hint = "To station or stop",
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Oxford Circus").assertIsDisplayed()
        composeRule.onNodeWithTag("stationSearchHere").assertDoesNotExist()
    }

    @Test
    fun station_search_to_from_here() {
        // The To… search's bar is "From" over "To" (maintainer, 2026-09-28): From names the start ("Here"
        // behind the crosshair) and a tap changes it; To is the search field; the places' chips follow.
        // Synthetic coordinates and generic labels — no user data.
        var changed = false
        var typed: String? = null
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(
                        favoritePlaces = listOf(
                            FavoritePlace("home", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12)),
                            FavoritePlace("work", FavoriteKind.WORK, "Work", Coordinates(51.51, -0.10)),
                        ),
                        recent = listOf(SearchEntry.Stop(StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube")))),
                        yoursRead = true,
                    ),
                    onQueryChange = { typed = it },
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    hint = "Station, stop or place",
                    onOpenPlace = {},
                    onChangeFrom = { changed = true },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("From Here").assertIsDisplayed()
        composeRule.onNodeWithText("To").assertIsDisplayed()
        composeRule.onNodeWithText("Station, stop or place").assertIsDisplayed()
        // From above To, and both above the places' chips.
        val fromTop = composeRule.onNodeWithTag("fromField").fetchSemanticsNode().boundsInRoot.top
        val toTop = composeRule.onNodeWithTag("stationSearchField").fetchSemanticsNode().boundsInRoot.top
        assertTrue(fromTop < toTop)
        assertTrue(toTop < composeRule.onNodeWithTag("favoriteChip-home").fetchSemanticsNode().boundsInRoot.top)
        // The two fields start at the same edge, whatever the labels' widths, and the place chips
        // line up under the To field, as its quick picks.
        val fieldLeft = composeRule.onNodeWithTag("stationSearchField").fetchSemanticsNode().boundsInRoot.left
        assertEquals(fieldLeft, composeRule.onNodeWithTag("fromField").fetchSemanticsNode().boundsInRoot.left)
        assertEquals(fieldLeft, composeRule.onNodeWithTag("favoriteChip-home").fetchSemanticsNode().boundsInRoot.left)
        // "Here" is the start, never a To… destination.
        composeRule.onNodeWithTag("stationSearchHere").assertDoesNotExist()
        captureSnapshot("station-search-to-from-here.png")
        composeRule.onNodeWithTag("fromField").performClick()
        assertTrue(changed)
        composeRule.onNodeWithTag("stationSearchField").performTextInput("bank")
        assertEquals("bank", typed)
    }

    @Test
    fun station_search_to_from_station() {
        // From a From… station, the From row names it: abbreviated to fit, the full name read out.
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(yoursRead = true),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    hint = "Station, stop or place",
                    fromStation = "King's Cross & St Pancras International",
                    onChangeFrom = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("From King's Cross & St Pancras International").assertIsDisplayed()
        composeRule.onNodeWithText("King's Cross & St Pancras Intl", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun station_search_plain_bar_without_a_from_row() {
        // The From… search (and any search given no From row) keeps the one-field bar.
        show(StationSearchViewModel.State(yoursRead = true))
        composeRule.onNodeWithTag("stationSearchField").assertIsDisplayed()
        composeRule.onNodeWithTag("tripEndsBar").assertDoesNotExist()
        composeRule.onNodeWithTag("fromField").assertDoesNotExist()
    }

    @Test
    fun station_search_to_places_error() {
        // The saved places couldn't be read: the To… picker says so with a Retry rather than hiding the
        // section as "no places", and station search stays usable below.
        var retried = false
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(
                        favoritePlacesFailed = true,
                        recent = listOf(SearchEntry.Stop(StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube")))),
                        yoursRead = true,
                    ),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    hint = "To station or stop",
                    onOpenPlace = {},
                    onRetryPlaces = { retried = true },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Couldn't load your places").assertIsDisplayed()
        captureSnapshot("station-search-to-places-error.png")
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun station_search_to_place_results() {
        // A To… search blends geocoded places under the stops, each tagged Place/Postcode on the right;
        // tapping one routes to its coordinate. An interchange (public TfL data) plus synthetic place and
        // postcode stand-ins — no real landmark, postcode or user data (AGENTS *Privacy*).
        var routed: TripDestination.Place? = null
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(
                        query = "victoria",
                        result = StationSearchViewModel.Result.Matches(
                            matches = listOf(StationMatch("HUBVIC", "Victoria", listOf("tube", "national-rail"))),
                            places = listOf(
                                PlaceHit("Sample Gallery", Coordinates(51.50, -0.10), PlaceKind.PLACE),
                                PlaceHit("X1 9XX", Coordinates(51.49, -0.13), PlaceKind.POSTCODE),
                            ),
                        ),
                    ),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    hint = "To station or stop",
                    onOpenPlace = { routed = it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria").assertIsDisplayed()
        composeRule.onNodeWithText("Sample Gallery").assertIsDisplayed()
        composeRule.onNodeWithText("Place").assertIsDisplayed()
        composeRule.onNodeWithText("Postcode").assertIsDisplayed()
        captureSnapshot("station-search-to-place-results.png")
        composeRule.onNodeWithText("Sample Gallery").performClick()
        assertEquals(TripDestination.Place(Coordinates(51.50, -0.10), "Sample Gallery"), routed)
    }

    @Test
    fun station_search_prompt() {
        show(StationSearchViewModel.State(yoursRead = true))
        composeRule.onNodeWithText("Type a name to search").assertIsDisplayed()
        captureSnapshot("station-search-prompt.png")
    }

    @Test
    fun station_search_to() {
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(yoursRead = true),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    hint = "To station or stop",
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("To station or stop").assertIsDisplayed()
        captureSnapshot("station-search-to.png")
    }

    @Test
    fun station_search_yours() {
        var opened: StationMatch? = null
        show(
            StationSearchViewModel.State(
                favorites = listOf(StationMatch("490G00000001", "King's Cross Station", listOf("bus"))),
                recent = listOf(SearchEntry.Stop(StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube")))),
                yoursRead = true,
            ),
            onOpen = { opened = it },
        )
        composeRule.onNodeWithText("Starred").assertIsDisplayed()
        composeRule.onNodeWithText("Recent").assertIsDisplayed()
        // By last use: the recent picks head the list, the starred stops not picked lately after.
        val recentTop = composeRule.onNodeWithText("Recent").fetchSemanticsNode().boundsInRoot.top
        assertTrue(recentTop < composeRule.onNodeWithText("Starred").fetchSemanticsNode().boundsInRoot.top)
        assertTrue(recentTop < composeRule.onNodeWithText("King's Cross Station").fetchSemanticsNode().boundsInRoot.top)
        captureSnapshot("station-search-yours.png")
        composeRule.onNodeWithText("Oxford Circus").performClick()
        assertEquals("940GZZLUOXC", opened?.id)
    }

    @Test
    fun station_search_failed() {
        show(
            StationSearchViewModel.State(
                query = "kings",
                result = StationSearchViewModel.Result.Failed(DeparturesUiState.Error.Kind.OFFLINE),
            ),
        )
        composeRule.onNodeWithText("You're offline").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        captureSnapshot("station-search-failed.png")
    }

    @Test
    fun station_search_more_on_its_way() {
        // The index's best few are up while TfL's answer is still on its way: a spinner under them says
        // where more will land (maintainer, 2026-09-28: append, don't reorder).
        show(StationSearchViewModel.State(query = "kings", result = StationSearchViewModel.Result.Matches(matches), searching = true))
        composeRule.onNodeWithTag(SEARCH_LOADING_MORE_TAG).assertExists()
        captureSnapshot("station-search-more-on-its-way.png")
    }

    @Test
    fun station_search_done_shows_no_spinner() {
        show(StationSearchViewModel.State(query = "kings", result = StationSearchViewModel.Result.Matches(matches)))
        composeRule.onNodeWithTag(SEARCH_LOADING_MORE_TAG).assertDoesNotExist()
    }

    @Test
    fun station_loading() {
        composeRule.setContent {
            StopDashTheme {
                StationPlaceholderScreen(
                    title = "King's Cross St. Pancras",
                    state = StationStopsViewModel.State.Loading,
                    onRetry = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("King's Cross St. Pancras").assertIsDisplayed()
        captureSnapshot("station-loading.png")
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        if (!capturing()) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
