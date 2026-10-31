package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRef
import app.stopdash.domain.RideGrouping
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A return to the near-me list (Back from a page over it, the swipe finishing) lands where the rider left it,
 * before the list's worker has run again: every answer the list's items are drawn from is held above it
 * ([ListWork]), so the first frame back has the same items, and the scroll position its first item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class ListReturnScrollTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    // Enough places to scroll. Synthetic stops and lines; no user data.
    private val stops = (1..12).map { i ->
        StopArrivals(
            "S$i", "Stop $i",
            listOf(Departure("l$i", "L$i", "outbound", "Dest $i", null, now.plusSeconds(60L + i * 10), "bus")),
            fetchedAt = now.minusSeconds(60),
        )
    }

    // With [farRide], a faraway favorite ride collapsed among the farther places at the foot (SPEC *Journeys*).
    // With [twins], the last two stops one station's two records, placed by its code and read as one ([twinStations]).
    // With [split], the stops at their distances and a station among them whose starred platform leads the list, its
    // other platform staying by distance, nearer stops between (maintainer, 2026-10-31).
    private fun returnKeepsPlace(
        stationGroups: Boolean,
        rideGrouping: RideGrouping = RideGrouping.RIDE,
        farRide: Boolean = false,
        twins: Boolean = false,
        split: Boolean = false,
    ) {
        val hubs = if (twins) twinStations("S11", "S12") else null
        val splitStation = StopArrivals(
            "SX", "Split Station",
            listOf(
                Departure("tx", "TX", "outbound", "North End", "Northbound - Platform 1", now.plusSeconds(90), "tube"),
                Departure("tx", "TX", "inbound", "South End", "Southbound - Platform 2", now.plusSeconds(150), "tube"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        val starred = if (split) {
            setOf(StarredRow.of(DepartureRows.across(listOf(splitStation), now).first { it.destination == "North End" }))
        } else {
            emptySet()
        }
        val meters = if (split) stops.withIndex().associate { (i, stop) -> stop.stopId to 40.0 + i * 20 } + ("SX" to 150.0) else emptyMap()
        val stops = when {
            twins -> twinPlaced(stops, "S11", "S12")
            split -> stops + splitStation
            else -> stops
        }
        val far = FavoriteJourney(JourneyEnd("FR1", "Faraway Stop"), JourneyEnd("FR2", "Faraway End"), listOf(LineRef("88", "88", "bus")))
        val scheduler = TestCoroutineScheduler()
        val worker = StandardTestDispatcher(scheduler)
        // Held above the list, as the activity holds them.
        val listWork = ListWork()
        lateinit var listState: LazyListState
        var covered by mutableStateOf(false)
        composeRule.setContent {
            listState = rememberListStateFor("set")
            StopDashTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    if (!covered) {
                        CompositionLocalProvider(LocalWorker provides worker, LocalJourneyHubs provides hubs) {
                            MainScreen(
                                DeparturesUiState.Loaded(stops, now.minusSeconds(60)), now, {},
                                listKey = "set",
                                listWork = listWork,
                                listState = listState,
                                stationGroups = stationGroups,
                                rideGrouping = rideGrouping,
                                stopDistanceMeters = meters,
                                starred = starred,
                                journeys = if (farRide) listOf(far) else emptyList(),
                                farJourneyMeters = if (farRide) mapOf(far.key to 2400.0) else emptyMap(),
                                farJourneys = if (farRide) listOf(far) else emptyList(),
                                onToggleJourney = {},
                            )
                        }
                    }
                }
            }
        }
        fun settle() = repeat(8) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
        settle()
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(listState.layoutInfo.totalItemsCount - 4) } }
        composeRule.waitForIdle()
        val left = composeRule.runOnIdle { listState.layoutInfo.visibleItemsInfo.first().key }

        // A page over the list takes it out of composition; back, with the worker not yet run again.
        covered = true
        composeRule.waitForIdle()
        covered = false
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(left, listState.layoutInfo.visibleItemsInfo.first().key) }
        // And once the worker has caught up, still there.
        settle()
        composeRule.runOnIdle { assertEquals(left, listState.layoutInfo.visibleItemsInfo.first().key) }
    }

    @Test
    fun `back to the list grouped by platform lands where it was left`() = returnKeepsPlace(stationGroups = false)

    @Test
    fun `back to the list grouped by station lands where it was left`() = returnKeepsPlace(stationGroups = true)

    @Test
    fun `back to the list grouped by platform with a starred platform leading apart from its station lands where it was left`() =
        returnKeepsPlace(stationGroups = false, split = true)

    @Test
    fun `back to the list with a faraway favorite collapsed at its foot lands where it was left`() =
        returnKeepsPlace(stationGroups = true, rideGrouping = RideGrouping.STATION, farRide = true)

    @Test
    fun `back to the list with rides grouped by station lands where it was left`() {
        returnKeepsPlace(stationGroups = false, rideGrouping = RideGrouping.STATION)
    }

    @Test
    fun `back to the list grouped by station, a station's records as one, lands where it was left`() =
        returnKeepsPlace(stationGroups = true, twins = true)
}
