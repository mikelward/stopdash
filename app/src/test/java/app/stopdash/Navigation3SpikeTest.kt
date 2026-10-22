package app.stopdash

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
import app.stopdash.ui.PredictiveBack
import app.stopdash.ui.rememberBackFollow
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 1 of the move onto Navigation 3 (dev-docs/navigation3.md): what the plan relies on, confirmed on
 * this app's own stack (Compose, activity, lifecycle and navigationevent at the app's versions) before
 * any page depends on it. Each test is one of the plan's claims, and fails if a library update breaks it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class Navigation3SpikeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Keys as the app would have them: a sealed family, saved by its own serializer, so nothing is looked up by name. */
    @Serializable
    private sealed interface Page : NavKey {
        @Serializable
        data object NearMe : Page

        @Serializable
        data object Settings : Page

        @Serializable
        data class Station(val id: String) : Page

        /** A window over the page under it, as a route stop's details are. */
        @Serializable
        data object Details : Page
    }

    private lateinit var dispatcher: OnBackPressedDispatcher
    private lateinit var windowDispatcher: OnBackPressedDispatcher
    private lateinit var backStack: NavBackStack<Page>
    private val backs = ArrayList<String>()

    // Each page's lifecycle, the events it has had since it was last composed, whether it's composed now,
    // and the values it kept in rememberSaveable and in remember.
    private val lifecycles = HashMap<String, Lifecycle>()
    private val events = HashMap<String, MutableList<Lifecycle.Event>>()
    private val composed = HashSet<String>()
    private val saved = HashMap<String, Int>()
    private val plain = HashMap<String, Int>()
    private var nextValue = 0

    /** A page's view model, standing in for the station pages' hand-cleared stores. */
    class StationModel : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    private val stationModels = ArrayList<StationModel>()

    private fun state(name: String): Lifecycle.State = checkNotNull(lifecycles[name]).currentState

    @Composable
    private fun PageContent(name: String, content: @Composable () -> Unit = {}) {
        val owner = LocalLifecycleOwner.current
        saved[name] = rememberSaveable { nextValue++ }
        plain[name] = remember { nextValue++ }
        DisposableEffect(owner) {
            lifecycles[name] = owner.lifecycle
            composed += name
            val seen = ArrayList<Lifecycle.Event>().also { events[name] = it }
            val observer = LifecycleEventObserver { _, event -> seen += event }
            owner.lifecycle.addObserver(observer)
            onDispose {
                owner.lifecycle.removeObserver(observer)
                composed -= name
            }
        }
        Box(Modifier.fillMaxSize().testTag(name)) { content() }
    }

    @Composable
    private fun Pages(settingsContent: @Composable () -> Unit = {}) {
        dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
        // Registered first, so it's what Back reaches once the stack has nothing to pop: the activity
        // finishing, with the system's back-to-home animation.
        BackHandler { backs += "past the stack" }
        backStack = rememberSerializable(serializer = NavBackStackSerializer(Page.serializer())) {
            NavBackStack(Page.NearMe)
        }
        NavDisplay(
            backStack = backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            sceneStrategies = listOf(DialogSceneStrategy()),
            // Pages open and close at once, as they do today; only the predictive pop is animated.
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            entryProvider = entryProvider {
                entry<Page.NearMe> { PageContent(NEAR_ME) }
                entry<Page.Settings> { PageContent(SETTINGS, settingsContent) }
                entry<Page.Station> {
                    stationModels += viewModel<StationModel>()
                    PageContent("station ${it.id}")
                }
                entry<Page.Details>(metadata = DialogSceneStrategy.dialog()) {
                    // A window has its own Back, as today's dialogs do.
                    windowDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                    PageContent(DETAILS)
                }
            },
        )
    }

    private fun show(settingsContent: @Composable () -> Unit = {}) {
        composeRule.setContent { Pages(settingsContent) }
        composeRule.waitForIdle()
    }

    private fun open(page: Page) {
        composeRule.runOnIdle { backStack.add(page) }
        composeRule.waitForIdle()
    }

    private fun startSwipe(progress: Float = 0.3f) {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
        composeRule.runOnIdle {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(progress * 300f, 400f, progress, BackEventCompat.EDGE_LEFT))
        }
        composeRule.waitForIdle()
    }

    /** Back: it finishes a swipe under way, or is a plain Back (a back arrow, three-button navigation). */
    private fun back() {
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun `a page opened over another is drawn by the second frame and settled by the fourth`() {
        show()
        assertEquals(Lifecycle.State.RESUMED, state(NEAR_ME))

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            backStack.add(Page.Settings)
            Snapshot.sendApplyNotifications()
        }
        // With no transition, the page is drawn on the second frame, and resumed, with the page under it
        // out of composition, by the fourth (about 67 ms): a frame later than a flag's swap, and the page
        // under stays composed, at STARTED, for the two frames between.
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.onNodeWithTag(SETTINGS).assertExists()
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        assertEquals(Lifecycle.State.RESUMED, state(SETTINGS))
        assertFalse(NEAR_ME in composed)
        composeRule.onNodeWithTag(NEAR_ME).assertDoesNotExist()
    }

    @Test
    fun `back to a page brings back what it saved, but not what it only remembered`() {
        show()
        val savedBefore = saved.getValue(NEAR_ME)
        val plainBefore = plain.getValue(NEAR_ME)
        open(Page.Settings)
        open(Page.Station("940GZZLUKSX"))

        back()
        back()
        assertEquals(listOf<Page>(Page.NearMe), backStack.toList())
        assertEquals(savedBefore, saved.getValue(NEAR_ME))
        assertTrue(plain.getValue(NEAR_ME) != plainBefore)
        assertEquals(Lifecycle.State.RESUMED, state(NEAR_ME))
    }

    @Test
    fun `a popped page's saved state goes with it`() {
        show()
        open(Page.Settings)
        val first = saved.getValue(SETTINGS)
        back()
        open(Page.Settings)
        assertTrue(saved.getValue(SETTINGS) != first)
    }

    @Test
    fun `a page's view model lasts while the page is covered and is cleared when it's popped`() {
        show()
        open(Page.Station("940GZZLUKSX"))
        val model = stationModels.last()
        open(Page.Settings)
        back()
        assertTrue(stationModels.last() === model)
        assertFalse(model.cleared)

        back()
        assertTrue(model.cleared)
        open(Page.Station("940GZZLUKSX"))
        assertTrue(stationModels.last() !== model)
    }

    @Test
    fun `during a back swipe the page under is drawn but neither page is resumed`() {
        show()
        open(Page.Settings)
        assertFalse(NEAR_ME in composed)

        startSwipe()
        composeRule.onNodeWithTag(NEAR_ME).assertExists()
        composeRule.onNodeWithTag(SETTINGS).assertExists()
        assertEquals(Lifecycle.State.STARTED, state(NEAR_ME))
        assertEquals(Lifecycle.State.STARTED, state(SETTINGS))
        assertFalse(Lifecycle.Event.ON_RESUME in events.getValue(NEAR_ME))
        // The stack itself doesn't move until the swipe finishes: the page on top is still the one swiped.
        assertEquals(listOf(Page.NearMe, Page.Settings), backStack.toList())

        back()
        assertEquals(listOf<Page>(Page.NearMe), backStack.toList())
        assertEquals(Lifecycle.State.RESUMED, state(NEAR_ME))
        assertFalse(SETTINGS in composed)
    }

    @Test
    fun `a canceled swipe never resumes the page under and leaves the stack as it was`() {
        show()
        open(Page.Settings)
        startSwipe(0.6f)
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()

        assertEquals(listOf(Page.NearMe, Page.Settings), backStack.toList())
        assertEquals(Lifecycle.State.RESUMED, state(SETTINGS))
        // The page under was only glimpsed: drawn at STARTED, then dropped, never resumed.
        assertFalse(Lifecycle.Event.ON_RESUME in events.getValue(NEAR_ME))
        assertFalse(NEAR_ME in composed)
    }

    @Test
    fun `a page's own Back goes before the stack's, the app's predictive one included`() {
        val progress = mutableListOf<Float>()
        show(settingsContent = {
            val follow = rememberBackFollow()
            PredictiveBack(follow) { backs += "settings' own page" }
            progress += follow.progress
        })
        open(Page.Settings)

        startSwipe(0.5f)
        // The page's own handler has the gesture: it follows it, and the stack draws nothing under it.
        assertTrue(progress.last() > 0f)
        assertFalse(NEAR_ME in composed)
        back()
        assertEquals(listOf("settings' own page"), backs)
        assertEquals(listOf(Page.NearMe, Page.Settings), backStack.toList())
    }

    @Test
    fun `with one page left, Back goes past the stack`() {
        show()
        open(Page.Settings)
        back()
        assertEquals(emptyList<String>(), backs)
        back()
        assertEquals(listOf("past the stack"), backs)
        assertEquals(listOf<Page>(Page.NearMe), backStack.toList())
    }

    @Test
    fun `a page opened as a window keeps the page under it drawn, at STARTED`() {
        show()
        open(Page.Details)
        assertTrue(NEAR_ME in composed)
        assertTrue(DETAILS in composed)
        assertEquals(Lifecycle.State.RESUMED, state(DETAILS))
        // The page under a window isn't resumed while the window is up: anything the plan moves to
        // RESUMED pauses under a route stop's details too (dev-docs/navigation3.md, step 4).
        assertEquals(Lifecycle.State.STARTED, state(NEAR_ME))

        // Back in the window closes it, and only it.
        composeRule.runOnIdle { windowDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(listOf<Page>(Page.NearMe), backStack.toList())
        assertEquals(emptyList<String>(), backs)
        assertEquals(Lifecycle.State.RESUMED, state(NEAR_ME))
    }

    @Test
    fun `the stack comes back after the process is killed`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { Pages() }
        open(Page.Settings)
        open(Page.Station("940GZZLUKSX"))
        val savedSettings = saved.getValue(SETTINGS)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(listOf(Page.NearMe, Page.Settings, Page.Station("940GZZLUKSX")), backStack.toList())
        composeRule.onNodeWithTag("station 940GZZLUKSX").assertExists()

        // A covered page's saved state comes back with the stack.
        back()
        assertEquals(savedSettings, saved.getValue(SETTINGS))
    }

    private companion object {
        const val NEAR_ME = "near me"
        const val SETTINGS = "settings"
        const val DETAILS = "details"
    }
}
