package app.stopdash.wear

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
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
@Config(sdk = [36], qualifiers = "w227dp-h227dp-round-watch-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchHomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, frame: TileFrame?, notice: RefreshNotice.Kind? = null, refresh: Boolean = false) {
        compose.setContent { WatchHomeScreen(frame, notice, onRefresh = if (refresh) ({}) else null) }
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
        if (line is TileLine.Departure) line.copy(row = line.row.copy(countdown = "?", stale = true)) else line
    }

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
