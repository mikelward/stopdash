package app.stopdash.ui

import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.RideGrouping
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopLocation
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
 * A tap that opens or folds part of the list holds still (SPEC *Engineering quality bar*, hold still): what was tapped
 * stays where it was, and what it opens grows below it, every frame, the list's worker run or not. Each grouping and
 * each tap that reshapes the list gets a case here, run with the worker held, since a frame drawn from an answer
 * worked out before the tap is when a list measures short and its position snaps (AGENTS.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class TapHoldsStillTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    // Enough nearby places to scroll. Synthetic stops and lines; no user data.
    private val stops = (1..12).map { i ->
        StopArrivals(
            "S$i", "Stop $i",
            listOf(Departure("l$i", "L$i", "outbound", "Dest $i", null, now.plusSeconds(60L + i * 10), "bus")),
            fetchedAt = now.minusSeconds(60),
        )
    }
    private val distances = stops.withIndex().associate { (i, stop) -> stop.stopId to 40.0 + i * 20 }

    // A faraway bus place below them, its departures arriving once it's opened.
    private val fartherPlace = CollapsedPlaces.Place(
        key = "bus:FAR",
        stationId = "",
        name = "Farther Road",
        meters = 900.0,
        lines = listOf(LineRef("55", "55", "bus")),
        stops = listOf(StopLocation("FARA", "Farther Road", 0.0, 0.0, listOf(LineRef("55", "55", "bus")), clusterId = "FAR")),
    )
    private val fartherStop = StopArrivals(
        "FARA", "Farther Road",
        (1..4).map { Departure("55", "55", "outbound", "Oxford Circus $it", null, now.plusSeconds(120L * it), "bus") },
        fetchedAt = now.minusSeconds(60),
    )

    private val scheduler = TestCoroutineScheduler()
    private val worker = StandardTestDispatcher(scheduler)
    private lateinit var listState: LazyListState

    private fun settle() = repeat(8) {
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
    }

    private fun top(matcher: SemanticsMatcher) = composeRule.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    /**
     * Taps [tapped] at the foot of a list scrolled to its end, letting [change] happen as the app would, and checks
     * the tapped node keeps its place with the worker held, then once it has run.
     */
    private fun holdsStill(tapped: SemanticsMatcher, changes: List<() -> Unit>) {
        val before = top(tapped)
        composeRule.onNode(tapped, useUnmergedTree = true).performClick()
        changes.forEachIndexed { change, apply ->
            apply()
            composeRule.waitForIdle()
            assertEquals("change $change, before the worker has run", before, top(tapped), 0.5f)
            // One round of the worker's queued work at a time, each answer drawn before the next is worked out: the
            // frames between, drawn from some answers worked out for the tap and some from before it.
            repeat(12) { step ->
                scheduler.runCurrent()
                composeRule.waitForIdle()
                assertEquals("change $change, worker step $step", before, top(tapped), 0.5f)
            }
        }
        settle()
        assertEquals("once the worker has run", before, top(tapped), 0.5f)
    }

    private fun scrollToEnd() {
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1) } }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue("the list scrolls", listState.firstVisibleItemIndex > 0) }
    }

    // With [rideGrouping], a favorite ride from the first stop rides along, grouped that way.
    private fun opensFartherInPlace(stationGroups: Boolean, rideGrouping: RideGrouping? = null) {
        val journeys = if (rideGrouping == null) emptyList() else listOf(FavoriteJourney(JourneyEnd("S1", "Stop 1"), JourneyEnd("D1", "Dest 1"), listOf(LineRef("l1", "", "bus"))))
        var farther by mutableStateOf(listOf(FartherCard(fartherPlace)))
        val departures = MutableStateFlow<DeparturesUiState>(DeparturesUiState.Loading)
        // The activity merges an opened card's departures and distances into the list's own ([FartherMerge]).
        var state by mutableStateOf<DeparturesUiState>(DeparturesUiState.Loaded(stops, now.minusSeconds(60)))
        var meters by mutableStateOf(distances)
        composeRule.setContent {
            listState = rememberListStateFor("set")
            StopDashTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        MainScreen(
                            state, now, {},
                            listKey = "set",
                            listWork = remember { ListWork() },
                            listState = listState,
                            stopDistanceMeters = meters,
                            farther = farther,
                            onOpenFarther = {},
                            stationGroups = stationGroups,
                            journeys = journeys,
                            onToggleJourney = {},
                            rideGrouping = rideGrouping ?: RideGrouping.RIDE,
                        )
                    }
                }
            }
        }
        settle()
        scrollToEnd()
        // Its name, as its heading or card shows it.
        val name = SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Farther Road")))
        holdsStill(
            name,
            listOf(
            // Its stops looked up, then opened with its departures still being fetched, then fetched.
            { farther = listOf(FartherCard(fartherPlace, FartherLoad.Loading)) },
            { farther = listOf(FartherCard(fartherPlace, FartherLoad.Open(fartherPlace.stops, mapOf("FARA" to 900.0), departures = departures))) },
            {
                departures.value = DeparturesUiState.Loaded(listOf(fartherStop), now.minusSeconds(60))
                state = DeparturesUiState.Loaded(stops + fartherStop, now.minusSeconds(60))
                meters = distances + ("FARA" to 900.0)
            },
            ),
        )
    }

    @Test
    fun `grouped by platform, opening a faraway place holds it still`() = opensFartherInPlace(stationGroups = false)

    @Test
    fun `grouped by station, opening a faraway place holds it still`() = opensFartherInPlace(stationGroups = true)

    @Test
    fun `grouped by platform with rides By station, opening a faraway place holds it still`() =
        opensFartherInPlace(stationGroups = false, rideGrouping = RideGrouping.STATION)

    @Test
    fun `grouped by station with rides By station, opening a faraway place holds it still`() =
        opensFartherInPlace(stationGroups = true, rideGrouping = RideGrouping.STATION)

    @Test
    fun `grouped by station with rides By stop, opening a faraway place holds it still`() =
        opensFartherInPlace(stationGroups = true, rideGrouping = RideGrouping.STOP)

    // A faraway favorite ride, collapsed among the farther places until tapped (SPEC *Journeys*): opened, it grows
    // below its heading as its stop is fetched and its trains come in.
    /**
     * Opens a faraway favorite. With [another], a nearer one is already open first, which stays open (its rows drawn)
     * while the worker prepares the second.
     */
    private fun opensFarRideInPlace(stationGroups: Boolean, rideGrouping: RideGrouping, another: Boolean = false) {
        val far = FavoriteJourney(JourneyEnd("FR1", "Faraway Stop"), JourneyEnd("FR2", "Faraway End"), listOf(LineRef("88", "88", "bus")))
        val nearer = FavoriteJourney(JourneyEnd("FR3", "Nearer Stop"), JourneyEnd("FR4", "Nearer End"), listOf(LineRef("77", "77", "bus")))
        val rides = if (another) listOf(nearer, far) else listOf(far)
        val farStop = StopArrivals(
            "FR1", "Faraway Stop",
            (1..4).map { Departure("88", "88", "outbound", "Faraway End", null, now.plusSeconds(120L * it), "bus") },
            fetchedAt = now.minusSeconds(60),
        )
        var state by mutableStateOf<DeparturesUiState>(DeparturesUiState.Loaded(stops, now.minusSeconds(60)))
        composeRule.setContent {
            listState = rememberListStateFor("set")
            StopDashTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        MainScreen(
                            state, now, {},
                            listKey = "set",
                            nearbyKey = "set",
                            listWork = remember { ListWork() },
                            listState = listState,
                            stopDistanceMeters = distances,
                            farther = listOf(FartherCard(fartherPlace)),
                            onOpenFarther = {},
                            stationGroups = stationGroups,
                            journeys = rides,
                            farJourneyMeters = mapOf(far.key to 2400.0, nearer.key to 2000.0).filterKeys { key -> rides.any { it.key == key } },
                            farJourneys = rides,
                            onToggleJourney = {},
                            rideGrouping = rideGrouping,
                        )
                    }
                }
            }
        }
        settle()
        if (another) {
            scrollToEnd()
            composeRule.onAllNodesWithTag("farJourneyCard")[0].performClick()
            settle()
        }
        scrollToEnd()
        val heading = SemanticsMatcher("the ride's heading") { node ->
            node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.any {
                // By ride, its own heading; rides By station, headed as a station by where it boards (its name, not its chevron).
                it.startsWith("Faraway Stop to Faraway End") || it.startsWith("Faraway Stop, Bus, ")
            } == true
        }
        val before = top(heading)
        composeRule.onNodeWithTag("farJourneyCard").performClick()
        val changes = listOf<() -> Unit>(
            {},
            // Its origin fetched with the list's own stops, as the activity reports it ([onJourneyOrigins]): no distance of
            // its own on the list, so it's no nearby place.
            { state = DeparturesUiState.Loaded(stops + farStop, now.minusSeconds(60)) },
        )
        changes.forEachIndexed { change, apply ->
            apply()
            composeRule.waitForIdle()
            assertEquals("change $change, before the worker has run", before, top(heading), 0.5f)
            repeat(12) { step ->
                scheduler.runCurrent()
                composeRule.waitForIdle()
                assertEquals("change $change, worker step $step", before, top(heading), 0.5f)
            }
        }
        settle()
        assertEquals("once the worker has run", before, top(heading), 0.5f)
    }

    @Test
    fun `grouped by platform, opening a faraway favorite holds it still`() = opensFarRideInPlace(stationGroups = false, RideGrouping.RIDE)

    @Test
    fun `grouped by station with rides By station, opening a faraway favorite holds it still`() =
        opensFarRideInPlace(stationGroups = true, RideGrouping.STATION)

    @Test
    fun `opening a second faraway favorite keeps the first open and holds still`() =
        opensFarRideInPlace(stationGroups = false, RideGrouping.RIDE, another = true)

    @Test
    fun `grouped by station, unfolding a station holds its heading still`() {
        composeRule.setContent {
            listState = rememberListStateFor("set")
            StopDashTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        MainScreen(
                            DeparturesUiState.Loaded(stops, now.minusSeconds(60)), now, {},
                            listKey = "set",
                            listWork = remember { ListWork() },
                            listState = listState,
                            stopDistanceMeters = distances,
                            stationGroups = true,
                        )
                    }
                }
            }
        }
        settle()
        scrollToEnd()
        // The last station's chevron: folded, then unfolded again with the list scrolled to its end.
        val chevron = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Stop 12, Bus")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)
        composeRule.onNode(chevron, useUnmergedTree = true).performClick()
        settle()
        holdsStill(chevron, emptyList())
    }

    @Test
    fun `grouped by station, unfolding a station of two records holds its heading still`() {
        // The last two stops one station's records, placed by its code ([twinPlaced]): one heading, named for the nearer.
        val hubs = twinStations("S11", "S12")
        val stops = twinPlaced(stops, "S11", "S12")
        composeRule.setContent {
            listState = rememberListStateFor("set")
            StopDashTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWorker provides worker, LocalJourneyHubs provides hubs) {
                        MainScreen(
                            DeparturesUiState.Loaded(stops, now.minusSeconds(60)), now, {},
                            listKey = "set",
                            listWork = remember { ListWork() },
                            listState = listState,
                            stopDistanceMeters = distances,
                            stationGroups = true,
                        )
                    }
                }
            }
        }
        settle()
        scrollToEnd()
        // One heading for the two records.
        val twelve = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Stop 12, Bus"))
        assertTrue(composeRule.onAllNodes(twelve, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val chevron = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Stop 11, Bus")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)
        composeRule.onNode(chevron, useUnmergedTree = true).performClick()
        settle()
        holdsStill(chevron, emptyList())
    }

    @Test
    fun `grouped by station, the station model arriving holds the list still`() = modelArrivalHoldsStill(repeated = false)

    @Test
    fun `grouped by station, the station model arriving holds the list still when a record's every row goes`() =
        modelArrivalHoldsStill(repeated = true)

    // With [repeated], the second record has only an empty row for a disrupted line the first lists trains of (as St
    // Pancras's high-speed record did), so merging takes every row of it away.
    private fun modelArrivalHoldsStill(repeated: Boolean) {
        // The model loads after the list is drawn: two stops in view, one station's records placed by its code, are read
        // together ([StationRows]). Every frame between, their heading stays put: no frame draws the stations standing in
        // with a record's group gone, or none at all, which shrank the list (Codex, #1242).
        val stops = twinPlaced(stops, "S3", "S4").let { placed ->
            if (!repeated) placed else placed.map { stop ->
                if (stop.stopId == "S4") stop.copy(departures = emptyList(), lines = listOf(LineRef("l3", "L3", "bus"))) else stop
            }
        }
        val statuses = if (repeated) mapOf("l3" to app.stopdash.domain.LineStatus("l3", 9, "Diverted")) else emptyMap()
        var hubs by mutableStateOf<app.stopdash.domain.Hubs?>(null)
        composeRule.setContent {
            listState = rememberListStateFor("set")
            StopDashTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWorker provides worker, LocalJourneyHubs provides hubs) {
                        MainScreen(
                            DeparturesUiState.Loaded(stops, now.minusSeconds(60), lineStatuses = statuses), now, {},
                            listKey = "set",
                            listWork = remember { ListWork() },
                            listState = listState,
                            stopDistanceMeters = distances,
                            stationGroups = true,
                        )
                    }
                }
            }
        }
        settle()
        val heading = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Stop 3, Bus")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)
        val four = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Stop 4, Bus"))
        // Placed by the station's code, the two records are one station before the model has loaded.
        assertTrue(composeRule.onAllNodes(four, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val before = top(heading)
        hubs = twinStations("S3", "S4")
        composeRule.waitForIdle()
        assertEquals("before the worker has run", before, top(heading), 0.5f)
        repeat(12) { step ->
            scheduler.runCurrent()
            composeRule.waitForIdle()
            assertEquals("worker step $step", before, top(heading), 0.5f)
        }
        settle()
        assertEquals("once the worker has run", before, top(heading), 0.5f)
        assertTrue(composeRule.onAllNodes(four, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `grouped by station, an interchange arriving while the last stations stand in is drawn once`() {
        // A place new to the list whose groups alternate by mode (Tube, Rail, Tube) has no rank in the stations standing
        // in, so they stay in that order; each station's heading was drawn at each of its runs, the same key twice, and
        // the list crashed (Codex, #1183). Synthetic ids, stand-in names.
        fun hub(id: String, mode: String, platform: String) = StopArrivals(
            id, "Interchange",
            listOf(Departure("l$id", "L$id", "outbound", "Away $id", platform, now.plusSeconds(300), mode)),
            fetchedAt = now.minusSeconds(60),
            clusterId = "HUBX",
            interchangeId = "HUBX",
        )
        val arriving = listOf(hub("HX1", "tube", "Platform 1"), hub("HX2", "national-rail", "Platform 2"), hub("HX3", "tube", "Platform 3"))
        var state by mutableStateOf<DeparturesUiState>(DeparturesUiState.Loaded(stops, now.minusSeconds(60)))
        var meters by mutableStateOf(distances)
        composeRule.setContent {
            listState = rememberListStateFor("set")
            StopDashTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        MainScreen(
                            state, now, {},
                            listKey = "set",
                            listWork = remember { ListWork() },
                            listState = listState,
                            stopDistanceMeters = meters,
                            stationGroups = true,
                        )
                    }
                }
            }
        }
        settle()
        state = DeparturesUiState.Loaded(stops + arriving, now.minusSeconds(60))
        meters = distances + mapOf("HX1" to 1_000.0, "HX2" to 1_001.0, "HX3" to 1_002.0)
        composeRule.waitForIdle()
        // Every frame between, as each answer lands: a key drawn twice throws.
        repeat(12) {
            scheduler.runCurrent()
            composeRule.waitForIdle()
            composeRule.runOnIdle { runBlocking { listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1) } }
            composeRule.waitForIdle()
        }
        settle()
    }

    // A ride the list is asked to draw twice, by whichever of its places, drew its heading twice under one key and the
    // app crashed (bug report, 2026-10-25): it's drawn once, by each grouping. Two copies of it in, one either way round,
    // stand in for whatever path asked twice.
    private fun rideTwiceDrawnOnce(stationGroups: Boolean, rideGrouping: RideGrouping) {
        val ride = FavoriteJourney(JourneyEnd("S1", "Stop 1"), JourneyEnd("D1", "Dest 1"), listOf(LineRef("l1", "", "bus")))
        val turned = ride.copy(from = ride.to, to = ride.from)
        composeRule.setContent {
            listState = rememberListStateFor("set")
            StopDashTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        MainScreen(
                            DeparturesUiState.Loaded(stops, now.minusSeconds(60)), now, {},
                            listKey = "set",
                            listWork = remember { ListWork() },
                            listState = listState,
                            stopDistanceMeters = distances,
                            stationGroups = stationGroups,
                            journeys = listOf(ride, turned),
                            onToggleJourney = {},
                            rideGrouping = rideGrouping,
                        )
                    }
                }
            }
        }
        // Every frame, as each answer lands: a key drawn twice throws.
        repeat(12) {
            scheduler.runCurrent()
            composeRule.waitForIdle()
        }
        settle()
    }

    @Test
    fun `a ride reaching the list twice is drawn once, grouped by station`() =
        rideTwiceDrawnOnce(stationGroups = true, rideGrouping = RideGrouping.RIDE)

    @Test
    fun `a ride reaching the list twice is drawn once, grouped by station with rides by station`() =
        rideTwiceDrawnOnce(stationGroups = true, rideGrouping = RideGrouping.STATION)

    @Test
    fun `a ride reaching the list twice is drawn once, grouped by platform`() =
        rideTwiceDrawnOnce(stationGroups = false, rideGrouping = RideGrouping.RIDE)
}
