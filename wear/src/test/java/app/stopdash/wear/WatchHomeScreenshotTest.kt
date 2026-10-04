package app.stopdash.wear

import app.stopdash.data.WatchTrip
import app.stopdash.domain.NoTimes
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The watch app on a round watch: the setup states, the dense list (fresh, out of date, after a
 * failed refresh) and a large font. Captures land beside the phone's (app/src/test/snapshots),
 * so CI's one screenshot pipeline records, diffs and commits them. Synthetic stops only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w227dp-h227dp-round-watch-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchHomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, frame: TileFrame?, notice: RefreshNotice.Kind? = null, refresh: Boolean = false, trip: ShownTrip? = null) {
        compose.setContent { WatchHomeScreen(frame, notice, onRefresh = if (refresh) ({}) else null, trip = trip, now = tripNow) }
        compose.onRoot().captureRoboImage(filePath = "../app/src/test/snapshots/images/wear_$name.png")
    }

    private fun row(lineId: String, name: String, mode: String, code: String, label: String, countdown: String, starred: Boolean = false, stale: Boolean = false) =
        TileLine.Departure(TileRow(name, lineId, mode, code, label, countdown, starred, stale))

    private val lines = listOf(
        row("northern", "Northern", "tube", "NOR", "Morden/Bank", "2 · 6 · 9 min", starred = true),
        TileLine.Header("Oxford Circus"),
        row("victoria", "Victoria", "tube", "VIC", "Brixton", "1 · 4 min"),
        row("central", "Central", "tube", "CEN", "Ealing Broadway", "3 min"),
        TileLine.Header("Tottenham Court Road · towards Holborn"),
        row("73", "73", "bus", "73", "Stoke Newington", "5 · 12 min"),
        row("weaver", "Weaver", "overground", "WEA", "Chingford", "8 min"),
    )

    private fun stale(lines: List<TileLine>) = lines.map { line ->
        if (line is TileLine.Departure) line.copy(row = line.row.copy(countdown = "21:14?", stale = true)) else line
    }

    // A trip on the way: a walk to King's Cross, then the Victoria line to Victoria. Stock stations.
    private val tripNow = Instant.parse("2026-10-03T08:00:00Z")
    private val trip = WatchTrip(
        title = "Walk to King's Cross St. Pancras",
        detail = "4 min",
        steps = listOf(
            WatchTrip.Step("Walk to King's Cross St. Pancras", walk = true, mode = "walking"),
            WatchTrip.Step("King's Cross St. Pancras → Victoria", "victoria", "Victoria", "tube"),
            WatchTrip.Step("Ride to Victoria", "victoria", "Victoria", "tube"),
        ),
        current = 0,
        departures = listOf(
            WatchTrip.Train("victoria", "Victoria", "tube", "Brixton", tripNow.plusSeconds(6 * 60).toEpochMilli()),
            WatchTrip.Train("victoria", "Victoria", "tube", "Brixton", tripNow.plusSeconds(9 * 60).toEpochMilli()),
        ),
        departuresAt = 1,
        sentAt = tripNow.toEpochMilli(),
    )

    @Test
    fun trip() = capture(
        "trip",
        TileFrame.Rows(lines, ageMinutes = 0, stale = false, partial = false),
        refresh = true,
        trip = ShownTrip(trip, stale = false),
    )

    @Test
    fun tripOutOfDate() = capture("trip_out_of_date", TileFrame.NeverSynced, refresh = true, trip = ShownTrip(trip, stale = true))

    // A bus stop pair: each pole's buses under its own letter, one leaving before the rider is there grayed.
    @Test
    fun tripBusPoles() = capture(
        "trip_bus_poles",
        TileFrame.NeverSynced,
        refresh = true,
        trip = ShownTrip(
            trip.copy(
                steps = listOf(
                    WatchTrip.Step("Walk to Victoria Bus Station", walk = true, mode = "walking"),
                    WatchTrip.Step("Victoria Bus Station → Oxford Circus", "73", "73", "bus"),
                    WatchTrip.Step("Ride to Oxford Circus", "73", "73", "bus"),
                ),
                title = "Walk to Victoria Bus Station",
                departures = listOf(
                    WatchTrip.Train("73", "73", "bus", "Oxford Circus", tripNow.plusSeconds(2 * 60).toEpochMilli(), stop = "Stop A", missed = true),
                    WatchTrip.Train("73", "73", "bus", "Oxford Circus", tripNow.plusSeconds(7 * 60).toEpochMilli(), stop = "Stop A"),
                    WatchTrip.Train("38", "38", "bus", "Oxford Circus", tripNow.plusSeconds(9 * 60).toEpochMilli(), stop = "Stop B"),
                ),
            ),
            stale = false,
        ),
    )

    // The phone's word that the trains' last update failed, over the last good board's rows.
    @Test
    fun tripTrainsFailed() = capture(
        "trip_trains_failed",
        TileFrame.NeverSynced,
        refresh = true,
        trip = ShownTrip(trip.copy(departuresNote = "Couldn't update just now"), stale = false),
    )

    @Test
    fun neverSynced() = capture("never_synced", TileFrame.NeverSynced)

    @Test
    fun neverSyncedOutOfReach() = capture(
        "never_synced_out_of_reach",
        TileFrame.NeverSynced,
        notice = RefreshNotice.Kind.PHONE_OUT_OF_REACH,
        refresh = true,
    )

    @Test
    fun disrupted() = capture(
        "disrupted",
        TileFrame.Rows(
            listOf(
                TileLine.Disruption(TileRow("Waterloo & City", "waterloo-city", "tube", "WAT", "", "", false, false), "Suspended", alone = true),
                row("victoria", "Victoria", "tube", "VIC", "Brixton", "1 · 4 min"),
                TileLine.Disruption(TileRow("Victoria", "victoria", "tube", "VIC", "", "", false, false), "Severe Delays", alone = false),
                row("central", "Central", "tube", "CEN", "Ealing Broadway", "3 min"),
            ),
            ageMinutes = 0,
            stale = false,
            partial = false,
        ),
        refresh = true,
    )

    @Test
    fun planned() = capture(
        "planned",
        TileFrame.Rows(
            listOf(
                TileLine.Departure(
                    TileRow("Victoria", "victoria", "tube", "VIC", "Brixton", "1 · 4 min", false, false, TilePlanned("Part Closure", LocalDate.of(2026, 10, 3))),
                ),
                row("central", "Central", "tube", "CEN", "Ealing Broadway", "3 min"),
            ),
            ageMinutes = 0,
            stale = false,
            partial = false,
        ),
        refresh = true,
    )

    @Test
    fun disruptedRailLargeFont() {
        // The tight case: a long status beside the pill and the rail reason, at a large font.
        RuntimeEnvironment.setFontScale(2f)
        capture(
            "disrupted_rail_large_font",
            TileFrame.Rows(
                listOf(
                    TileLine.Disruption(
                        TileRow("Southern", "southern", "national-rail", "SOU", "", "", false, false),
                        "Part Suspended",
                        alone = true,
                        noTimes = NoTimes.NO_KEY,
                    ),
                    row("victoria", "Victoria", "tube", "VIC", "Brixton", "1 · 4 min"),
                ),
                ageMinutes = 0,
                stale = false,
                partial = false,
            ),
            refresh = true,
        )
    }

    @Test
    fun noStops() = capture("no_stops", TileFrame.NoStops)

    @Test
    fun stops() = capture("stops", TileFrame.Rows(lines, ageMinutes = 0, stale = false, partial = false), refresh = true)

    @Test
    fun stopsOutOfDate() = capture(
        "stops_out_of_date",
        TileFrame.Rows(stale(lines), ageMinutes = 7, stale = true, partial = false, omitted = 2),
        refresh = true,
    )

    @Test
    fun refreshFailed() = capture(
        "refresh_failed",
        TileFrame.Rows(lines.take(3), ageMinutes = 2, stale = false, partial = true),
        notice = RefreshNotice.Kind.RATE_LIMITED,
        refresh = true,
    )

    @Test
    fun stopsLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        capture("stops_large_font", TileFrame.Rows(lines, ageMinutes = 1, stale = false, partial = false), refresh = true)
    }
}
