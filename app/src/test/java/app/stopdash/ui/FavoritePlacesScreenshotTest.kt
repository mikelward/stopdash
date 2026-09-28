package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.ChipLabel
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlaceIcon
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.StationMatch
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The favorite-places editor (SPEC D9): the empty list, a list with saved places, and the add editor
 * mid-search. Coordinates are synthetic; the stop name is a well-known station used as a documentation
 * example. No user data (SPEC *Privacy*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoritePlacesScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Place names are public TfL interchanges (never a real neighborhood tied to "Home"); coordinates
    // are synthetic (SPEC *Privacy*).
    private val home = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12), "Victoria")
    private val gym = FavoritePlace("g", FavoriteKind.CUSTOM, "Gym", Coordinates(51.6, -0.10), "Green Park")
    private val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"), 51.5, -0.12)
    private val positionless = StationMatch("490000000A", "Somewhere Road", listOf("bus"))

    private fun show(
        state: FavoritePlacesViewModel.State,
        onToggleDay: (DayOfWeek) -> Unit = {},
        onIconChange: (String) -> Unit = {},
        onChipShowsChange: (ChipLabel) -> Unit = {},
    ) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoritePlacesScreen(
                    state = state,
                    onBack = {},
                    onRouteTo = {},
                    onStartAdd = { _, _ -> },
                    onStartEdit = {},
                    onDelete = {},
                    onQueryChange = {},
                    onPick = {},
                    onLabelChange = {},
                    onToggleDay = onToggleDay,
                    onIconChange = onIconChange,
                    onChipShowsChange = onChipShowsChange,
                    onSave = {},
                    onCancelEditor = {},
                )
            }
        }
    }

    @Test
    fun favorite_places_empty() {
        show(FavoritePlacesViewModel.State(places = FavoritePlacesSet.Loaded(emptyList()), loaded = true))
        composeRule.onNodeWithText("Nothing saved yet").assertIsDisplayed()
        composeRule.onNodeWithText("Add Home").assertIsDisplayed()
        captureSnapshot("favorite-places-empty.png")
    }

    @Test
    fun favorite_places_loading() {
        show(FavoritePlacesViewModel.State(places = FavoritePlacesSet.Loaded(emptyList()), loaded = false))
        composeRule.onNodeWithText("Loading…").assertIsDisplayed()
        captureSnapshot("favorite-places-loading.png")
    }

    @Test
    fun favorite_places_discarded_reports_loss_and_allows_adding() {
        // A corrupt file was discarded: the screen says the places were lost AND still offers to add.
        show(FavoritePlacesViewModel.State(places = FavoritePlacesSet.Discarded, loaded = true))
        composeRule.onNodeWithText("Your saved places were lost and had to be reset. You can add them again.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Add Home").assertIsDisplayed()
    }

    @Test
    fun favorite_places_list() {
        show(
            FavoritePlacesViewModel.State(
                places = FavoritePlacesSet.Loaded(listOf(home, gym)),
                loaded = true,
            ),
        )
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Gym").assertIsDisplayed()
        captureSnapshot("favorite-places-list.png")
    }

    @Test
    fun favorite_places_editor() {
        show(
            FavoritePlacesViewModel.State(
                places = FavoritePlacesSet.Loaded(emptyList()),
                loaded = true,
                editor = FavoritePlacesViewModel.Editor(
                    kind = FavoriteKind.CUSTOM,
                    existingId = null,
                    label = "",
                    query = "oxf",
                    results = listOf(oxford, positionless),
                    // The positionless result was tapped and couldn't be resolved, so it's shown as
                    // unselectable ("No location for this result").
                    unresolvableIds = setOf(positionless.id),
                ),
            ),
        )
        composeRule.onNodeWithText("Oxford Circus").assertIsDisplayed()
        composeRule.onNodeWithText("No location for this result").assertIsDisplayed()
        captureSnapshot("favorite-places-editor.png")
    }

    @Test
    fun favorite_places_editor_selected() {
        // A draft restored after process death: the picked place stands (coordinate + placeName), but the
        // typed query isn't persisted, so the field is blank. The editor confirms the selection rather
        // than prompting to search, so Save isn't offered under a "type a stop" message (Codex).
        show(
            FavoritePlacesViewModel.State(
                places = FavoritePlacesSet.Loaded(emptyList()),
                loaded = true,
                editor = FavoritePlacesViewModel.Editor(
                    kind = FavoriteKind.HOME,
                    existingId = null,
                    label = "Home",
                    query = "",
                    coordinate = Coordinates(51.5, -0.12),
                    placeName = "Oxford Circus",
                ),
            ),
        )
        composeRule.onNodeWithText("Selected: Oxford Circus").assertIsDisplayed()
        captureSnapshot("favorite-places-editor-selected.png")
    }

    @Test
    fun favorite_places_editor_postcode() {
        // The query is a postcode, resolved to a couple of candidate places to choose from (SPEC D9).
        // Synthetic postcodes/coordinates — no real place (SPEC *Privacy*).
        show(
            FavoritePlacesViewModel.State(
                places = FavoritePlacesSet.Loaded(emptyList()),
                loaded = true,
                editor = FavoritePlacesViewModel.Editor(
                    kind = FavoriteKind.CUSTOM,
                    existingId = null,
                    label = "",
                    query = "X1 9XX",
                    postcodeCandidates = listOf(
                        PlaceCandidate("X1 9XX", Coordinates(51.5, -0.12)),
                        PlaceCandidate("X1 9XY", Coordinates(51.5, -0.11)),
                    ),
                ),
            ),
        )
        composeRule.onNodeWithText("Postcode X1 9XX").assertIsDisplayed()
        composeRule.onNodeWithText("X1 9XY").assertIsDisplayed()
        captureSnapshot("favorite-places-editor-postcode.png")
    }

    @Test
    fun favorite_places_editor_weekdays() {
        // A Work place set to weekdays: the weekend chips are off, and tapping one reports that day.
        val toggled = mutableListOf<DayOfWeek>()
        show(
            FavoritePlacesViewModel.State(
                places = FavoritePlacesSet.Loaded(emptyList()),
                loaded = true,
                editor = FavoritePlacesViewModel.Editor(
                    kind = FavoriteKind.WORK,
                    existingId = "w",
                    label = "Work",
                    coordinate = Coordinates(51.5, -0.12),
                    placeName = "Oxford Circus",
                    showOnDays = FavoritePlace.EVERY_DAY - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY,
                ),
            ),
            onToggleDay = { toggled += it },
        )
        composeRule.onNodeWithText("Show on main screen").assertIsDisplayed()
        composeRule.onNodeWithTag("placeDay-MONDAY").assertIsOn()
        composeRule.onNodeWithTag("placeDay-SATURDAY").assertIsOff().performClick()
        assertEquals(listOf(DayOfWeek.SATURDAY), toggled)
        captureSnapshot("favorite-places-editor-weekdays.png")
    }

    @Test
    fun favorite_places_editor_icon() {
        // Home wearing its house: that cell is on, the chip choice shows (Icon by default), and tapping
        // another icon or chip label reports it.
        val chosen = mutableListOf<Any>()
        show(
            FavoritePlacesViewModel.State(
                places = FavoritePlacesSet.Loaded(emptyList()),
                loaded = true,
                editor = FavoritePlacesViewModel.Editor(
                    kind = FavoriteKind.HOME,
                    existingId = "h",
                    label = "Home",
                    coordinate = Coordinates(51.5, -0.12),
                    placeName = "Victoria",
                    icon = FavoritePlaceIcon.HOME,
                ),
            ),
            onIconChange = { chosen += it },
            onChipShowsChange = { chosen += it },
        )
        composeRule.onNodeWithTag("placeIcon-home").assertIsOn()
        composeRule.onNodeWithTag("placeIcon-office").assertIsOff().performClick()
        composeRule.onNodeWithTag("placeChipShows-ICON").assertIsSelected()
        composeRule.onNodeWithTag("placeChipShows-BOTH").performClick()
        assertEquals(listOf<Any>(FavoritePlaceIcon.OFFICE, ChipLabel.BOTH), chosen)
        captureSnapshot("favorite-places-editor-icon.png")
    }

    @Test
    fun favorite_places_editor_no_icon_hides_chip_choice() {
        show(
            FavoritePlacesViewModel.State(
                places = FavoritePlacesSet.Loaded(emptyList()),
                loaded = true,
                editor = FavoritePlacesViewModel.Editor(
                    kind = FavoriteKind.CUSTOM,
                    existingId = "g",
                    label = "Gym",
                    coordinate = Coordinates(51.5, -0.12),
                ),
            ),
        )
        composeRule.onNodeWithTag("placeChipShows").assertDoesNotExist()
    }

    @Test
    fun favorite_places_list_shows_icon() {
        show(
            FavoritePlacesViewModel.State(
                places = FavoritePlacesSet.Loaded(listOf(home.copy(icon = FavoritePlaceIcon.HOME), gym)),
                loaded = true,
            ),
        )
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Gym").assertIsDisplayed()
        captureSnapshot("favorite-places-list-icons.png")
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
