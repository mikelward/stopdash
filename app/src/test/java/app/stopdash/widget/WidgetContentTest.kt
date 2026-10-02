package app.stopdash.widget

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.compose.ui.unit.DpSize
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.dp
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.lineCode
import app.stopdash.domain.riderLineName
import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Node coverage for the Glance widget: renders [WidgetContent] and asserts the emitted layout
 * nodes (text, structure) for each state. The drawn result — clipping, sizing, color — is
 * pixel-captured separately by [WidgetScreenshotTest] (which renders the widget to RemoteViews
 * and inflates them), and the `widgetModel` decision by [WidgetModelTest]; the three together
 * are the widget's screenshot/layout coverage (SPEC *Testing*).
 *
 * Runs under Robolectric because the Glance unit-test harness builds a real `android.os.Bundle`
 * (a plain JVM unit test throws "not mocked" on it); no pixels are rendered, so no graphics mode
 * is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetContentTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun row(lineId: String, offsetSeconds: Long, fetchedAt: Instant) = DepartureRow(
        stopId = "490000001A",
        stopName = "Example Stop",
        lineId = lineId,
        lineName = lineId,
        direction = "inbound",
        directionKey = "inbound",
        destination = "Brixton",
        mode = "tube",
        upcoming = listOf(
            Departure(
                lineId = lineId,
                lineName = lineId,
                direction = "inbound",
                destination = "Brixton",
                platform = null,
                expectedArrival = now.plusSeconds(offsetSeconds),
                mode = "tube",
            ),
        ),
        fetchedAt = fetchedAt,
    )

    // Wrap a row in the render model the widget now takes: the per-(destination, branch) groups
    // are chosen in widgetModel, so here they're just the row's own grouping (a small time cap).
    private fun rowModel(r: DepartureRow) = WidgetRowModel(r, DepartureRows.destinationLines(r, 3))

    @Test
    fun `no data renders the open-app prompt`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(WidgetModel(hasData = false, stale = false, uncertain = false, stamp = null, rows = emptyList()), now)
        }
        onNode(hasText("Open Routemo")).assertExists()
    }

    @Test
    fun `a snapshot with no rows renders the no-departures message`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(hasData = true, stale = false, uncertain = false, stamp = "Updated just now", rows = emptyList()),
                now,
            )
        }
        onNode(hasText("No upcoming departures")).assertExists()
    }

    @Test
    fun `a stale empty snapshot says the data may be out of date`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(hasData = true, stale = true, uncertain = true, stamp = "Updated 12 min ago", rows = emptyList()),
                now,
            )
        }
        onNode(hasText("out of date")).assertExists()
    }

    @Test
    fun `a fresh row renders its destination and stamp`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(
                    hasData = true,
                    stale = false, uncertain = false,
                    stamp = "Updated just now",
                    rows = listOf(rowModel(row("victoria", 120, now.minusSeconds(30)))),
                ),
                now,
            )
        }
        onNode(hasText("Brixton")).assertExists()
        onNode(hasText("Updated just now")).assertExists()
    }

    @Test
    fun `a row with work still to come shows its calendar, named for TalkBack, unless it's disrupted`() = runGlanceAppWidgetUnitTest {
        val closure = PlannedAlert("Part Closure", "No service.", LocalDate.of(2026, 9, 27))
        val planned = row("victoria", 120, now.minusSeconds(30)).copy(plannedAlerts = listOf(closure))
        val disrupted = row("jubilee", 240, now.minusSeconds(30))
            .copy(status = LineStatus("jubilee", 6, "Severe Delays"), plannedAlerts = listOf(closure))
        // The calendar's description is read from the app's strings.
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable {
            WidgetContent(
                WidgetModel(
                    hasData = true,
                    stale = false, uncertain = false,
                    stamp = "Updated just now",
                    rows = listOf(rowModel(planned), rowModel(disrupted)),
                ),
                now,
            )
        }
        // One calendar: the disrupted row's ⚠ stands in for its, as in the app.
        onAllNodes(hasContentDescription("Planned Part Closure from 27 ")).assertCountEquals(1)
        onNode(hasContentDescriptionEqualTo("Disrupted: Severe Delays")).assertExists()
    }

    @Test
    fun `the compact layout swaps the title row for a short warning`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(
                    hasData = true,
                    stale = false, uncertain = true,
                    stamp = "Updated just now",
                    rows = listOf(rowModel(row("victoria", 120, now.minusSeconds(30)))),
                    compact = true,
                ),
                now,
            )
        }
        onNode(hasTextEqualTo("Partly stale")).assertExists()
        onNode(hasTextEqualTo("Routemo")).assertDoesNotExist()
        onNode(hasTextEqualTo(lineCode("victoria", "tube"))).assertExists()
        onNode(hasText("Brixton")).assertExists()
    }

    @Test
    fun `a stacked row keeps the line code, the destination and the countdown`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(
                    hasData = true,
                    stale = false, uncertain = false,
                    stamp = "Updated just now",
                    rows = listOf(rowModel(row("victoria", 120, now.minusSeconds(30))).copy(stackedLines = listOf(true))),
                ),
                now,
            )
        }
        onNode(hasTextEqualTo(lineCode("victoria", "tube"))).assertExists()
        onNode(hasTextEqualTo("Brixton")).assertExists()
        onNode(hasTextEqualTo("2 min")).assertExists()
    }

    @Test
    fun `a widget too small for one departure asks to be made taller`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(
                    hasData = true,
                    stale = false, uncertain = false,
                    stamp = "Updated just now",
                    rows = emptyList(),
                    compact = true,
                    tooSmall = true,
                ),
                now,
            )
        }
        onNode(hasTextEqualTo("Too small")).assertExists()
        onNode(hasText("No upcoming departures")).assertDoesNotExist()
    }

    @Test
    fun `a stop header renders above its rows`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(
                    hasData = true,
                    stale = false, uncertain = false,
                    stamp = "Updated just now",
                    rows = listOf(
                        rowModel(row("victoria", 120, now.minusSeconds(30)))
                            .copy(header = WidgetHeader("Example Stop – Platform 1", "Example Stop, Platform 1, Southbound")),
                    ),
                ),
                now,
            )
        }
        onNode(hasTextEqualTo("Example Stop – Platform 1")).assertExists()
    }

    @Test
    fun `a narrow widget drops the stamp's Updated prefix`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(180.dp, 110.dp))
        provideComposable { WidgetContent(stampOnly("Updated 14 min ago"), now) }
        onNode(hasTextEqualTo("14 min ago")).assertExists()
    }

    @Test
    fun `a wide widget keeps the full stamp`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(WIDGET_COMPACT_WIDTH, 110.dp))
        provideComposable { WidgetContent(stampOnly("Updated 14 min ago"), now) }
        onNode(hasTextEqualTo("Updated 14 min ago")).assertExists()
    }

    private fun stampOnly(stamp: String) = WidgetModel(
        hasData = true,
        stale = false, uncertain = false,
        stamp = stamp,
        rows = listOf(rowModel(row("victoria", 120, now.minusSeconds(30)))),
    )

    @Test
    fun `a partial snapshot with rows flags the header as uncertain`() = runGlanceAppWidgetUnitTest {
        // Fresh overall (recent stamp) but uncertain — one stop carried arrivalsFresh=false and
        // is under the age threshold, so its row still shows a live countdown. The header must
        // say so rather than a clean "just now" that masks the unrefreshed stop.
        provideComposable {
            WidgetContent(
                WidgetModel(
                    hasData = true,
                    stale = false, uncertain = true,
                    stamp = "Updated just now",
                    rows = listOf(rowModel(row("victoria", 120, now.minusSeconds(30)))),
                ),
                now,
            )
        }
        onNode(hasText("Some stops out of date")).assertExists()
    }

    @Test
    fun `a branching row renders each destination on its own line`() = runGlanceAppWidgetUnitTest {
        // Same line and direction, two destinations — the widget keeps each on its own line
        // (parity with the in-app card), so a divergent train is shown, not dropped (SPEC D8).
        val branching = DepartureRow(
            stopId = "490000001A",
            stopName = "Example Stop",
            lineId = "victoria",
            lineName = "victoria",
            direction = "outbound",
            directionKey = "outbound",
            destination = "Brixton",
            mode = "tube",
            upcoming = listOf(
                Departure("victoria", "victoria", "outbound", "Brixton", null, now.plusSeconds(120), "tube"),
                Departure("victoria", "victoria", "outbound", "Walthamstow Central", null, now.plusSeconds(300), "tube"),
            ),
            fetchedAt = now.minusSeconds(30),
        )
        provideComposable {
            WidgetContent(
                WidgetModel(hasData = true, stale = false, uncertain = false, stamp = "Updated just now", rows = listOf(rowModel(branching))),
                now,
            )
        }
        onNode(hasText("Brixton")).assertExists()
        onNode(hasText("Walthamstow Central")).assertExists()
        // Each destination line carries its own pill, as each row of the in-app card does.
        onAllNodes(hasTextEqualTo(lineCode("victoria", "tube"))).assertCountEquals(2)
    }

    @Test
    fun `a named Overground line's hollow pill still shows its code, and TalkBack its name`() = runGlanceAppWidgetUnitTest {
        val windrush = DepartureRow(
            stopId = "910GEXAMPLE",
            stopName = "Example Station",
            lineId = "windrush",
            lineName = "Windrush",
            direction = "outbound",
            directionKey = "outbound",
            destination = "Highbury & Islington",
            mode = "overground",
            upcoming = listOf(
                Departure("windrush", "Windrush", "outbound", "Highbury & Islington", null, now.plusSeconds(120), "overground"),
            ),
            fetchedAt = now.minusSeconds(30),
        )
        provideComposable {
            WidgetContent(
                WidgetModel(hasData = true, stale = false, uncertain = false, stamp = "Updated just now", rows = listOf(rowModel(windrush))),
                now,
            )
        }
        onNode(hasTextEqualTo(lineCode("Windrush", "overground", "windrush"))).assertExists()
        onNode(hasContentDescriptionEqualTo(riderLineName("Windrush", "overground"))).assertExists()
    }

    @Test
    fun `a via-branch row shows the abbreviated branch beside the destination`() = runGlanceAppWidgetUnitTest {
        // A branching-line service shows its via-trunk in the board's short form (Cross → X),
        // so the rider can tell two same-terminus trains apart in the compact row.
        val viaRow = DepartureRow(
            stopId = "490000001A",
            stopName = "Example Stop",
            lineId = "northern",
            lineName = "northern",
            direction = "southbound",
            directionKey = "southbound",
            destination = "Battersea Power",
            mode = "tube",
            upcoming = listOf(
                Departure("northern", "northern", "southbound", "Battersea Power", null, now.plusSeconds(120), "tube", branch = "Charing Cross"),
            ),
            fetchedAt = now.minusSeconds(30),
        )
        provideComposable {
            WidgetContent(
                WidgetModel(hasData = true, stale = false, uncertain = false, stamp = "Updated just now", rows = listOf(rowModel(viaRow))),
                now,
            )
        }
        // "Battersea Power" renders as the renamed "Battersea" (DepartureLabels display rename).
        onNode(hasText("Battersea/Charing X")).assertExists()
    }

    @Test
    fun `a stale row withholds its countdown as a question mark`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(
                    hasData = true,
                    stale = true, uncertain = true,
                    stamp = "Updated 12 min ago",
                    rows = listOf(rowModel(row("victoria", 120, now.minusSeconds(900)))),
                ),
                now,
            )
        }
        // The stamp invites a refresh, and the withheld countdown is "?" (never a live number).
        onNode(hasText("Tap to refresh")).assertExists()
        onNode(hasText("?")).assertExists()
    }
}
