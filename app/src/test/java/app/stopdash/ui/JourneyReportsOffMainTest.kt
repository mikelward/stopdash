package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.Departure
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopLocation
import app.stopdash.domain.WidgetJourneysReport
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The near-me screen's journey reports (the stops each journey is fetched from, the widget's pins, the
 * far ends checked) and its farther cards are worked out on the list's worker, never in composition
 * (AGENTS.md *Main thread: read and dispatch only*), and reported only as they change: never from an
 * answer standing in for older cards, and never again the same.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class JourneyReportsOffMainTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val victoriaLine = LineSequence(
        routes = listOf(LineRoute("Brixton ↔ Walthamstow Central", listOf("940GZZLUVIC", "940GZZLUWRR", "940GZZLUWWL"))),
        stopNames = mapOf("940GZZLUVIC" to "Victoria", "940GZZLUWRR" to "Warren Street", "940GZZLUWWL" to "Walthamstow Central"),
    )
    private val victoriaToWarrenStreet = FavoriteJourney(
        JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUWRR", "Warren Street"), "victoria",
    )
    private val victoriaToWalthamstow = FavoriteJourney(
        JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUWWL", "Walthamstow Central"), "victoria",
    )
    private val victoria = StopArrivals(
        stopId = "940GZZLUVIC",
        stopName = "Victoria",
        departures = listOf(Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(240), "tube")),
        fetchedAt = now,
    )
    private val routes = RouteStopsRepository(
        object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
        },
        io = Dispatchers.Unconfined,
    )

    // Runs the held worker until nothing more is queued on it: each stage starts once the one before is in.
    private fun settle(scheduler: TestCoroutineScheduler) {
        repeat(8) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun the_journey_stops_are_worked_out_on_the_worker_and_reported_stops_first() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val listWork = ListWork()
        val events = mutableListOf<String>()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides routes) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(victoria), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        journeys = listOf(victoriaToWarrenStreet),
                        listKey = "victoria",
                        listWork = listWork,
                        onJourneyOrigins = { refs -> events += "stops ${refs.map { it.id }}" },
                        onJourneyStopIds = { ids, _ -> events += "ids ${ids.keys}" },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Nothing works them out, or reports them, until the worker runs.
        assertNull(listWork.journeyOrigins.value)
        assertEquals(emptyList<String>(), events)

        settle(scheduler)
        assertNotNull(listWork.journeyStops.value)
        // The stops, then the ids that say they're in, each once: answers the same as the last
        // (once the route places the journey) report nothing again.
        assertEquals(listOf("stops [940GZZLUVIC]", "ids [${victoriaToWarrenStreet.key}]"), events)
    }

    @Test
    fun a_journeys_stop_ids_never_go_ahead_of_its_stops() {
        // A journey starred while the list is up: the ids that say its stops are in are reported only
        // after the stops themselves, never worked out against the origins standing in from before (Codex,
        // #625), which would release a refresh waiting on them without the new origin.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var journeys by mutableStateOf(listOf(victoriaToWarrenStreet))
        val events = mutableListOf<String>()
        val warrenStreetToWalthamstow = FavoriteJourney(
            JourneyEnd("940GZZLUWRR", "Warren Street"), JourneyEnd("940GZZLUWWL", "Walthamstow Central"), "victoria",
        )
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides routes) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(victoria), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        journeys = journeys,
                        listKey = "victoria",
                        onJourneyOrigins = { refs -> events += "stops ${refs.map { it.id }}" },
                        onJourneyStopIds = { ids, _ -> events += "ids ${ids.keys.sorted()}" },
                    )
                }
            }
        }
        settle(scheduler)
        events.clear()

        journeys = listOf(victoriaToWarrenStreet, warrenStreetToWalthamstow)
        settle(scheduler)
        assertEquals(
            listOf("stops [940GZZLUVIC, 940GZZLUWRR]", "ids ${listOf(victoriaToWarrenStreet.key, warrenStreetToWalthamstow.key).sorted()}"),
            events,
        )
    }

    @Test
    fun each_route_a_card_needs_is_asked_for_once() {
        // The lines at a journey's origin are worked out after its origin: until they are, no route is
        // loaded, or a starred line's request under way would be canceled and asked again as they land.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val asked = mutableListOf<String>()
        val released = CompletableDeferred<Unit>()
        val slowRoutes = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    asked += "$lineId $direction"
                    released.await()
                    return if (lineId == "victoria") victoriaLine else LineSequence(emptyList(), emptyMap())
                }
            },
            io = Dispatchers.Unconfined,
        )
        // Victoria with a District line train too: its route is one more the card needs.
        val district = Departure("district", "District", "eastbound", "Upminster", null, now.plusSeconds(300), "tube")
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides slowRoutes) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(victoria.copy(departures = victoria.departures + district)), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        journeys = listOf(victoriaToWarrenStreet),
                        listKey = "victoria",
                    )
                }
            }
        }
        settle(scheduler)
        assertTrue(asked.any { it.startsWith("victoria ") } && asked.any { it.startsWith("district ") })
        assertEquals(asked.distinct(), asked)
        released.complete(Unit)
        settle(scheduler)
        assertEquals(asked.distinct(), asked)
    }

    @Test
    fun a_journey_starred_while_up_asks_for_each_route_once() {
        // The lines its card needs wait for its origin too: worked out against the origins standing in,
        // a line set that drops out as the origin lands would cancel its routes' requests, asked again
        // after (Codex, #625).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val asked = mutableListOf<String>()
        val released = CompletableDeferred<Unit>()
        val northernLine = LineSequence(
            listOf(LineRoute("Edgware ↔ Morden", listOf("940GZZLUEUS", "940GZZLUWRR"))),
            mapOf("940GZZLUEUS" to "Euston", "940GZZLUWRR" to "Warren Street"),
        )
        val gatedRoutes = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    asked += "$lineId $direction"
                    if (lineId == "victoria") return victoriaLine
                    released.await()
                    return if (lineId == "northern") northernLine else LineSequence(emptyList(), emptyMap())
                }
            },
            io = Dispatchers.Unconfined,
        )
        val warrenStreetToEuston = FavoriteJourney(
            JourneyEnd("940GZZLUWRR", "Warren Street"), JourneyEnd("940GZZLUEUS", "Euston"), "northern",
        )
        // Warren Street with trains on two more lines, both of whose routes its card needs.
        val warrenStreet = StopArrivals(
            stopId = "940GZZLUWRR",
            stopName = "Warren Street",
            departures = listOf(
                Departure("northern", "Northern", "southbound", "Morden", null, now.plusSeconds(180), "tube"),
                Departure("elizabeth", "Elizabeth", "eastbound", "Abbey Wood", null, now.plusSeconds(360), "elizabeth-line"),
            ),
            fetchedAt = now,
        )
        var journeys by mutableStateOf(listOf(victoriaToWarrenStreet))
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides gatedRoutes) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(victoria, warrenStreet), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        journeys = journeys,
                        listKey = "victoria",
                    )
                }
            }
        }
        settle(scheduler)
        asked.clear()

        journeys = listOf(victoriaToWarrenStreet, warrenStreetToEuston)
        settle(scheduler)
        assertTrue(asked.any { it.startsWith("northern ") } && asked.any { it.startsWith("elizabeth ") })
        assertEquals(asked.distinct(), asked)
        released.complete(Unit)
        settle(scheduler)
        assertEquals(asked.distinct(), asked)
    }

    @Test
    fun a_new_lists_work_reports_its_journey_stops_to_its_own_model() {
        // A relocation to another set of nearby stops brings a new model and a new list's work together:
        // the new model hears the journey stops, though they're the same as the last model heard (Codex,
        // #625).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var listWork by mutableStateOf(ListWork())
        var model by mutableStateOf(mutableListOf<String>())
        val first = model
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides routes) {
                    val events = model
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(victoria), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        journeys = listOf(victoriaToWarrenStreet),
                        listKey = "victoria",
                        listWork = listWork,
                        onJourneyOrigins = { refs -> events += "stops ${refs.map { it.id }}" },
                        onJourneyStopIds = { ids, _ -> events += "ids ${ids.keys}" },
                    )
                }
            }
        }
        settle(scheduler)
        assertEquals(listOf("stops [940GZZLUVIC]", "ids [${victoriaToWarrenStreet.key}]"), first)

        val next = mutableListOf<String>()
        listWork = ListWork()
        model = next
        settle(scheduler)
        assertEquals(listOf("stops [940GZZLUVIC]", "ids [${victoriaToWarrenStreet.key}]"), next)
        assertEquals(2, first.size)
    }

    @Test
    fun a_refresh_that_changes_no_report_reports_nothing_again() {
        // A new snapshot judges the cards again; the far ends and the widget's pins come out the same, and
        // the worker hands back the reports already made, so the model hears nothing new.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var state by mutableStateOf(DeparturesUiState.Loaded(stops = listOf(victoria.copy(fetchedAt = now.minusSeconds(30))), fetchedAt = now.minusSeconds(30)))
        val listWork = ListWork()
        val destinations = mutableListOf<List<String>>()
        val widget = mutableListOf<WidgetJourneysReport>()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides routes) {
                    MainScreen(
                        state = state,
                        now = now,
                        onRefresh = {},
                        journeys = listOf(victoriaToWarrenStreet),
                        listKey = "victoria",
                        listWork = listWork,
                        onJourneyDestinations = { refs -> destinations += refs.map { it.id } },
                        onWidgetJourneys = { keys, checks, from, boarding -> widget += WidgetJourneysReport(keys, checks, from, boarding) },
                    )
                }
            }
        }
        settle(scheduler)
        assertEquals(1, destinations.size)
        val widgetReports = widget.size
        assertTrue(widgetReports > 0)

        // Fetched again: a new snapshot, the same trains.
        val before = listWork.cardReports.value
        state = DeparturesUiState.Loaded(stops = listOf(victoria), fetchedAt = now)
        settle(scheduler)
        // Judged again, and found the same.
        assertTrue(listWork.cardReports.value !== before)
        assertSame(before?.value?.destinations, listWork.cardReports.value?.value?.destinations)
        assertEquals(1, destinations.size)
        assertEquals(widgetReports, widget.size)
    }

    @Test
    fun a_report_is_told_apart_by_identity_alone_on_the_main_thread() {
        // The worker decides whether a value changed ([Reported.of]); where it's reported, a new value
        // is a new [Reported], so nothing compares collections there.
        val compared = mutableListOf<Boolean>()
        val probe = { Probe { compared += true } }
        var reported by mutableStateOf(Reported(probe()))
        val heard = mutableListOf<Probe>()
        val owner = Any()
        composeRule.setContent { ReportChanges(reported, owner) { heard += it } }
        composeRule.waitForIdle()
        reported = Reported(probe())
        composeRule.waitForIdle()
        assertEquals(2, heard.size)
        assertEquals(emptyList<Boolean>(), compared)
    }

    // A value whose comparison is noted.
    private class Probe(private val onCompared: () -> Unit) {
        override fun equals(other: Any?): Boolean {
            onCompared()
            return other is Probe
        }
        override fun hashCode(): Int = 0
    }

    @Test
    fun the_widget_is_never_told_of_a_journey_without_its_card_judged() {
        // A journey starred while the list is up: until its card is judged, an answer standing in from the
        // older cards would name it with no check, which would read as nothing found for it (#593).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val listWork = ListWork()
        var journeys by mutableStateOf(listOf(victoriaToWarrenStreet))
        val reports = mutableListOf<WidgetJourneysReport>()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides routes) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(victoria), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        journeys = journeys,
                        listKey = "victoria",
                        listWork = listWork,
                        onWidgetJourneys = { keys, checks, from, boarding -> reports += WidgetJourneysReport(keys, checks, from, boarding) },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Worked out on the worker, with the cards, before anything is reported.
        assertNull(listWork.cardReports.value)
        assertEquals(emptyList<WidgetJourneysReport>(), reports)
        settle(scheduler)
        assertEquals(setOf(victoriaToWarrenStreet.key), reports.last().keys)

        // From the star on, one pass of the worker at a time, every report names only journeys it has a
        // check for. (Before any cards are drawn, a card is judged at once, still checking: that report
        // names its journey with nothing found yet, as it always has.)
        val before = reports.size
        journeys = listOf(victoriaToWarrenStreet, victoriaToWalthamstow)
        repeat(12) {
            scheduler.runCurrent()
            composeRule.waitForIdle()
            reports.drop(before).forEach { report -> assertEquals(report.keys, report.checks.mapTo(HashSet()) { it.key }) }
        }
        settle(scheduler)
        assertEquals(setOf(victoriaToWarrenStreet.key, victoriaToWalthamstow.key), reports.last().keys)
    }

    @Test
    fun the_farther_cards_are_picked_on_the_worker() {
        val scheduler = TestCoroutineScheduler()
        val worker = Marking(StandardTestDispatcher(scheduler))
        val listWork = ListWork()
        val farther = Watched(listOf(FartherCard(busPlace("490G00000009", "Farther Road", 650.0, "73"))), worker::running)
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides worker) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(victoria), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        stopDistanceMeters = mapOf("940GZZLUVIC" to 40.0),
                        listKey = "victoria",
                        listWork = listWork,
                        farther = farther,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertNull(listWork.farther.value)
        settle(scheduler)
        assertEquals(listOf("bus:490G00000009"), listWork.farther.value?.value?.map { it.place.key })
        assertTrue(farther.read)
        assertFalse("the farther cards were read off the worker", farther.readElsewhere)
    }

    private fun busPlace(id: String, name: String, meters: Double, vararg routes: String) =
        CollapsedPlaces.Place(
            key = "bus:$id",
            stationId = "",
            name = name,
            meters = meters,
            lines = routes.map { LineRef(it, it, "bus") },
            stops = listOf(StopLocation("${id}A", name, 0.0, 0.0, routes.map { LineRef(it, it, "bus") }, clusterId = id)),
        )

    // Runs [held]'s work marked as on the worker, so a read can tell where it ran.
    private class Marking(private val held: CoroutineDispatcher) : CoroutineDispatcher() {
        private val marked = ThreadLocal.withInitial { false }
        fun running(): Boolean = marked.get()
        override fun dispatch(context: CoroutineContext, block: Runnable) = held.dispatch(context) {
            marked.set(true)
            try {
                block.run()
            } finally {
                marked.set(false)
            }
        }
    }

    // A list that notes whether it was read, and whether anywhere but on the worker.
    private class Watched<T>(private val items: List<T>, private val onWorker: () -> Boolean) : AbstractList<T>() {
        var read = false
        var readElsewhere = false
        private fun note() {
            read = true
            if (!onWorker()) readElsewhere = true
        }
        override val size: Int get() = items.size.also { note() }
        override fun get(index: Int): T = items[index].also { note() }
    }
}
