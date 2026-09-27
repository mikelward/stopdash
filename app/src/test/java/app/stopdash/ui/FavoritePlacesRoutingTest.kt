package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings favorite-places list routes on a row tap (SPEC D9): tapping a row plans a trip to that
 * place, while Edit and Delete stay their own actions and never route — a nested button's tap must not
 * fall through to the row. Place name is a public TfL interchange and the coordinate synthetic (SPEC
 * *Privacy*). Layout is covered by [FavoritePlacesScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoritePlacesRoutingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val home = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12), "Victoria")

    private var routed: FavoritePlace? = null
    private var edited: FavoritePlace? = null
    private var deleted: String? = null

    private fun show() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoritePlacesScreen(
                    state = FavoritePlacesViewModel.State(
                        places = FavoritePlacesSet.Loaded(listOf(home)),
                        loaded = true,
                    ),
                    onBack = {},
                    onRouteTo = { routed = it },
                    onStartAdd = { _, _ -> },
                    onStartEdit = { edited = it },
                    onDelete = { deleted = it },
                    onQueryChange = {},
                    onPick = {},
                    onLabelChange = {},
                    onSave = {},
                    onCancelEditor = {},
                )
            }
        }
    }

    @Test
    fun `tapping a place routes to it, not edit or delete`() {
        show()
        composeRule.onNodeWithContentDescription("Plan a trip to Home").performClick()
        assertEquals(home, routed)
        assertNull(edited)
        assertNull(deleted)
    }

    @Test
    fun `Edit edits that place and doesn't route`() {
        show()
        composeRule.onNodeWithContentDescription("Edit Home").performClick()
        assertEquals(home, edited)
        assertNull(routed)
    }

    @Test
    fun `Delete removes that place and doesn't route`() {
        show()
        composeRule.onNodeWithContentDescription("Delete Home").performClick()
        assertEquals("h", deleted)
        assertNull(routed)
    }
}
