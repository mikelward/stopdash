@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)

package app.stopdash.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.NoTimes
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.RailFeed
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.STATUS_DIRECTION_KEY
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.StopArrivals
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel coverage for the Glance widget (SPEC *Testing* — Glance layouts get Roborazzi
 * screenshots). Glance emits RemoteViews rather than a Compose tree, so we render
 * [WidgetContent] to real RemoteViews with [GlanceRemoteViews.compose], inflate them to a
 * `View`, and capture that — which catches clipping, sizing, color, and alignment
 * regressions the node-based [WidgetContentTest] can't see. The render decision is covered
 * separately by [WidgetModelTest]; this exercises the drawn result of each state.
 *
 * Public infrastructure/line names only in the fixtures — no user route data (SPEC *Privacy*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetScreenshotTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun row(
        lineId: String,
        lineName: String,
        destination: String,
        offsetSeconds: Long,
        fetchedAt: Instant = now.minusSeconds(30),
        branch: String? = null,
        mode: String = "tube",
    ) = DepartureRow(
        stopId = "490000001A",
        stopName = "Example Stop",
        lineId = lineId,
        lineName = lineName,
        direction = "inbound",
        directionKey = "inbound",
        destination = destination,
        mode = mode,
        upcoming = listOf(
            Departure(lineId, lineName, "inbound", destination, null, now.plusSeconds(offsetSeconds), mode, branch = branch),
        ),
        fetchedAt = fetchedAt,
    )

    private fun branchingRow() = DepartureRow(
        stopId = "490000002B",
        stopName = "Example Stop",
        lineId = "northern",
        lineName = "Northern",
        direction = "southbound",
        directionKey = "southbound",
        destination = "Battersea Power",
        mode = "tube",
        upcoming = listOf(
            Departure("northern", "Northern", "southbound", "Battersea Power", null, now.plusSeconds(180), "tube", branch = "Charing Cross"),
            Departure("northern", "Northern", "southbound", "Edgware", null, now.plusSeconds(420), "tube", branch = "Bank"),
        ),
        fetchedAt = now.minusSeconds(30),
    )

    // The groups are chosen in widgetModel; here a row's own grouping stands in for the model.
    private fun rowModel(r: DepartureRow) = WidgetRowModel(r, DepartureRows.destinationLines(r, 3))

    @Test
    fun `fresh, light`() {
        capture(
            "widget-fresh.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated just now",
                rows = listOf(
                    rowModel(row("victoria", "Victoria", "Brixton", 120)),
                    rowModel(branchingRow()),
                ),
            ),
        )
    }

    @Test
    fun `fresh, dark`() {
        capture(
            "widget-fresh-dark.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated just now",
                rows = listOf(
                    rowModel(row("victoria", "Victoria", "Brixton", 120)),
                    rowModel(branchingRow()),
                ),
            ),
            dark = true,
        )
    }

    // Two named Overground lines, hollow, around a tube line, solid: the same size down the column.
    private fun overgroundModel() = WidgetModel(
        hasData = true,
        stale = false,
        uncertain = false,
        stamp = "Updated just now",
        rows = listOf(
            rowModel(row("windrush", "Windrush", "Highbury & Islington", 120, mode = "overground")),
            rowModel(row("central", "Central", "Ealing Broadway", 180)),
            rowModel(row("lioness", "Lioness", "Watford Junction", 240, mode = "overground")),
        ),
    )

    @Test
    fun `named Overground lines are hollow pills, light`() = capture("widget-overground.png", overgroundModel())

    @Test
    fun `named Overground lines are hollow pills, dark`() = capture("widget-overground-dark.png", overgroundModel(), dark = true)

    @Test
    fun `a disrupted line is marked, and a suspended one shows as its status`() {
        val suspended = row("waterloo-city", "Waterloo & City", "", 0).copy(
            directionKey = STATUS_DIRECTION_KEY,
            upcoming = emptyList(),
            status = LineStatus("waterloo-city", 5, "Suspended"),
        )
        val delayed = row("victoria", "Victoria", "Brixton", 120).copy(status = LineStatus("victoria", 6, "Severe Delays"))
        capture(
            "widget-disrupted.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated just now",
                // Three lines, inside the default widget's four: the suspension, then a countdown
                // and its status, as the budget would choose them.
                rows = listOf(WidgetRowModel(suspended, emptyList()), rowModel(delayed)),
            ),
        )
    }

    @Test
    fun `a line with work still to come shows a calendar before its countdown`() {
        val closure = PlannedAlert("Part Closure", "No service.", LocalDate.of(2026, 9, 27))
        val planned = row("victoria", "Victoria", "Brixton", 120).copy(plannedAlerts = listOf(closure))
        // A disruption under way leads: its ⚠ line, and no calendar.
        val delayed = row("jubilee", "Jubilee", "Stratford", 240)
            .copy(status = LineStatus("jubilee", 6, "Severe Delays"), plannedAlerts = listOf(closure))
        val model = WidgetModel(
            hasData = true,
            stale = false,
            uncertain = false,
            stamp = "Updated just now",
            rows = listOf(rowModel(planned), rowModel(delayed)),
        )
        capture("widget-planned.png", model)
        capture("widget-planned-dark.png", model, dark = true)
    }

    @Test
    fun `a disrupted rail line with no key says so where its times would be`() {
        val rail = row("southern", "Southern", "", 0).copy(
            stopId = "910GEXAMPLE",
            directionKey = STATUS_DIRECTION_KEY,
            mode = "national-rail",
            upcoming = emptyList(),
            railFeed = RailFeed.NO_KEY,
            status = LineStatus("southern", 6, "Part Suspended"),
        )
        capture(
            "widget-disrupted-rail.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated just now",
                rows = listOf(WidgetRowModel(rail, emptyList(), noTimes = NoTimes.NO_KEY), rowModel(row("victoria", "Victoria", "Brixton", 120))),
            ),
            fontScale = 1.3f,
        )
    }

    @Test
    fun `a narrow widget keeps a rail line's status ahead of its reason`() {
        val rail = row("southern", "Southern", "", 0).copy(
            stopId = "910GEXAMPLE",
            directionKey = STATUS_DIRECTION_KEY,
            mode = "national-rail",
            upcoming = emptyList(),
            railFeed = RailFeed.NO_KEY,
            status = LineStatus("southern", 6, "Part Suspended"),
        )
        capture(
            "widget-disrupted-rail-stacked.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated just now",
                rows = listOf(
                    WidgetRowModel(rail, emptyList(), noTimes = NoTimes.NO_KEY, statusStacked = true),
                    rowModel(row("victoria", "Victoria", "Brixton", 120)).copy(stackedLines = listOf(true)),
                ),
            ),
            size = DpSize(180.dp, 180.dp),
            fontScale = 1.3f,
        )
    }

    @Test
    fun `stale, countdowns withheld`() {
        capture(
            "widget-stale.png",
            WidgetModel(
                hasData = true,
                stale = true,
                uncertain = true,
                stamp = "Updated 12 min ago",
                rows = listOf(rowModel(row("victoria", "Victoria", "Brixton", 120, fetchedAt = now.minusSeconds(900)))),
            ),
        )
    }

    @Test
    fun `no data prompts opening the app`() {
        capture(
            "widget-empty.png",
            WidgetModel(hasData = false, stale = false, uncertain = false, stamp = null, rows = emptyList()),
        )
    }

    // Two places, so each row sits under its stop header, as in the in-app list — two headers and two
    // lines, the default size's four-line budget.
    @Test
    fun `stop headers name each place`() {
        capture(
            "widget-stop-headers.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated just now",
                rows = listOf(
                    rowModel(row("victoria", "Victoria", "Brixton", 120))
                        .copy(header = WidgetHeader("Oxford Circus – Platform 3", "Oxford Circus, Platform 3")),
                    rowModel(row("northern", "Northern", "Morden", 240))
                        .copy(header = WidgetHeader("Euston – Platform 1", "Euston, Platform 1")),
                ),
            ),
        )
    }

    @Test
    fun `partial refresh flags some stops out of date`() {
        capture(
            "widget-partial.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = true,
                stamp = "Updated just now",
                rows = listOf(rowModel(row("victoria", "Victoria", "Brixton", 120))),
            ),
        )
    }

    @Test
    fun `fresh snapshot with nothing due says so`() {
        capture(
            "widget-no-departures.png",
            WidgetModel(hasData = true, stale = false, uncertain = false, stamp = "Updated just now", rows = emptyList()),
        )
    }

    // The provider's minWidth x minHeight (180x110dp): the title and the longest ordinary stamp
    // must share the header row without clipping, and the rows below get what's left.
    @Test
    fun `minimum size keeps the stamp beside the title`() {
        capture(
            "widget-min-size.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated 14 min ago",
                // The minimum height's rows (widgetRowsHeight) fit one line.
                rows = listOf(rowModel(row("victoria", "Victoria", "Brixton", 120))),
            ),
            size = DpSize(180.dp, 110.dp),
        )
    }

    // The default size filled to its height's line budget by the real model, with two services that
    // each branch three ways: every line has its pill, and the last one still clears the bottom edge.
    @Test
    fun `a full line budget fits the default size`() = captureFullBudget("widget-full-budget.png", fontScale = 1f)

    // The same at a large system font: taller lines, so the budget admits fewer of them.
    @Test
    fun `a full line budget fits the default size at a large font`() =
        captureFullBudget("widget-full-budget-large-font.png", fontScale = 1.3f)

    // A partial refresh at full budget: the "Some stops out of date" note takes a line, and the budget
    // gives it one, so the last departure still clears the bottom edge.
    @Test
    fun `a full line budget with the partial note fits the default size`() =
        captureFullBudget("widget-full-budget-partial.png", fontScale = 1f, arrivalsFresh = false)

    // The minimum size at a 1.3x font with the partial note: no line fits under the full header,
    // so the compact layout shows the short warning in its place and one whole (stacked) departure.
    @Test
    fun `minimum size at a large font falls back to the compact layout`() =
        captureFullBudget("widget-min-size-compact.png", fontScale = 1.3f, arrivalsFresh = false, size = DpSize(180.dp, 110.dp))

    // A narrow widget at a 2x font: too narrow for pill, destination and countdown on one line, so
    // each departure stacks on two.
    @Test
    fun `narrow widget at a 2x font stacks its rows`() =
        captureFullBudget("widget-stacked.png", fontScale = 2f, size = DpSize(180.dp, 180.dp))

    // The minimum size at a 2x font: not even one stacked departure fits, so it says it's too small.
    @Test
    fun `minimum size at a 2x font says it's too small`() =
        captureFullBudget("widget-too-small.png", fontScale = 2f, arrivalsFresh = false, size = DpSize(180.dp, 110.dp))

    private fun captureFullBudget(
        name: String,
        fontScale: Float,
        arrivalsFresh: Boolean = true,
        size: DpSize = DpSize(240.dp, 180.dp),
    ) {
        fun dep(lineId: String, lineName: String, destination: String, offsetSeconds: Long) =
            Departure(lineId, lineName, "inbound", destination, null, now.plusSeconds(offsetSeconds), "tube")
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    "490000001A",
                    "Example Stop",
                    listOf(
                        dep("northern", "Northern", "Morden", 60),
                        dep("northern", "Northern", "Battersea Power", 120),
                        dep("northern", "Northern", "Kennington", 180),
                        dep("district", "District", "Richmond", 240),
                        dep("district", "District", "Wimbledon", 300),
                        dep("district", "District", "Ealing Broadway", 360),
                    ),
                    now.minusSeconds(30),
                    disruptions = emptyList(),
                    arrivalsFresh = arrivalsFresh,
                ),
            ),
            fetchedAt = now.minusSeconds(30),
            // Both lines checked good with the arrivals, as a complete refresh leaves them: these
            // captures are about the line budget, not the "couldn't check" note.
            lineStatuses = listOf("northern", "district").associateWith {
                LineStatusCheck(LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service"), now.minusSeconds(30))
            },
        )
        // The same cell StopDashWidget.provideGlance passes for this size and font.
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(size.width, size.height, fontScale))
        capture(name, model, size = size, fontScale = fontScale)
    }

    // The compact width at a large font (Codex on #155): the row with three times stacks, so its
    // destination isn't squeezed out, while the rows with one time stay on one line each.
    @Test
    fun `a row with three times stacks where one with a single time needn't`() {
        fun dep(lineId: String, lineName: String, destination: String, offsetSeconds: Long) =
            Departure(lineId, lineName, "inbound", destination, null, now.plusSeconds(offsetSeconds), "tube")
        val size = DpSize(220.dp, 250.dp)
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    "490000001A",
                    "Example Stop",
                    listOf(
                        dep("victoria", "Victoria", "Brixton", 60),
                        dep("victoria", "Victoria", "Brixton", 240),
                        dep("victoria", "Victoria", "Brixton", 480),
                        dep("district", "District", "Richmond", 120),
                        dep("northern", "Northern", "Morden", 180),
                    ),
                    now.minusSeconds(30),
                    disruptions = emptyList(),
                ),
            ),
            fetchedAt = now.minusSeconds(30),
            lineStatuses = listOf("victoria", "district", "northern").associateWith {
                LineStatusCheck(LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service"), now.minusSeconds(30))
            },
        )
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(size.width, size.height, 1.3f))
        assertEquals(listOf(listOf(true), listOf(false), listOf(false)), model.rows.map { it.stackedLines })
        capture("widget-stacked-per-row.png", model, size = size, fontScale = 1.3f)
    }

    // A status under one time at the compact width and a large font (Codex on #457): beside the
    // pill's column it would be cut, so it starts under the pill.
    @Test
    fun `a status that doesn't fit beside the pill starts under it`() {
        fun dep(lineId: String, lineName: String, destination: String, offsetSeconds: Long) =
            Departure(lineId, lineName, "inbound", destination, null, now.plusSeconds(offsetSeconds), "tube")
        val size = DpSize(220.dp, 180.dp)
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    "490000001A",
                    "Example Stop",
                    listOf(dep("victoria", "Victoria", "Brixton", 120), dep("district", "District", "Richmond", 240)),
                    now.minusSeconds(30),
                    disruptions = emptyList(),
                ),
            ),
            fetchedAt = now.minusSeconds(30),
            lineStatuses = mapOf(
                "victoria" to LineStatusCheck(LineStatus("victoria", 3, "Part Suspended"), now.minusSeconds(30)),
                "district" to LineStatusCheck(LineStatus("district", LineStatus.GOOD_SERVICE, "Good Service"), now.minusSeconds(30)),
            ),
        )
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(size.width, size.height, 1.3f))
        assertEquals(listOf(listOf(false), listOf(false)), model.rows.map { it.stackedLines })
        assertEquals(listOf(false, true), model.rows.map { it.statusBeside })
        capture("widget-status-under-pill.png", model, size = size, fontScale = 1.3f)
    }

    // The narrowest width at a large system font, tall enough for the title row: the title gives
    // way, the freshness stamp stays whole, and the row stacks as it does at this width and font
    // (widgetRowStacked).
    @Test
    fun `narrowest width at a large font keeps the stamp whole`() {
        capture(
            "widget-min-size-large-font.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated 14 min ago",
                rows = listOf(
                    rowModel(row("victoria", "Victoria", "Brixton", 120))
                        .copy(stackedLines = listOf(widgetRowStacked(listOf("2 min"), false, 180.dp, 1.3f))),
                ),
            ),
            size = DpSize(180.dp, 180.dp),
            fontScale = 1.3f,
        )
    }

    // A tablet-portrait cell (about 760dp wide, twice a phone's full-width widget), in two columns, and
    // a phone's, in one, with the same stops: each drawn at its own size, as the launcher reports it.
    @Test
    fun `a double-width cell is drawn at its own size`() = captureCell("widget-double-width.png", DpSize(760.dp, 400.dp))

    @Test
    fun `a phone-width cell is drawn at its own size`() = captureCell("widget-phone-width.png", DpSize(380.dp, 400.dp))

    private fun captureCell(name: String, cell: DpSize) {
        fun dep(lineId: String, lineName: String, destination: String, offsetSeconds: Long) =
            Departure(lineId, lineName, "inbound", destination, null, now.plusSeconds(offsetSeconds), "tube")
        val lines = listOf("victoria", "piccadilly", "northern")
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    "940GZZLUKSX",
                    "King's Cross St. Pancras",
                    listOf(
                        dep("victoria", "Victoria", "Brixton", 60),
                        dep("victoria", "Victoria", "Brixton", 180),
                        dep("victoria", "Victoria", "Brixton", 360),
                        dep("piccadilly", "Piccadilly", "Heathrow Terminal 5", 120),
                        dep("piccadilly", "Piccadilly", "Cockfosters", 240),
                    ),
                    now.minusSeconds(30),
                    disruptions = emptyList(),
                ),
                StopArrivals(
                    "940GZZLUEUS",
                    "Euston",
                    listOf(
                        dep("northern", "Northern", "Morden", 90),
                        dep("northern", "Northern", "Battersea Power", 210),
                        dep("northern", "Northern", "Edgware", 300),
                    ),
                    now.minusSeconds(30),
                    disruptions = emptyList(),
                ),
            ),
            fetchedAt = now.minusSeconds(30),
            lineStatuses = lines.associateWith {
                LineStatusCheck(LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service"), now.minusSeconds(30))
            },
        )
        val models = runBlocking {
            widgetModels(snapshot, now, emptySet(), 1f, RouteTopology.EMPTY, emptySet(), listOf(cell), worker = Dispatchers.Unconfined)
        }
        capture(name, models[cell], size = cell)
    }

    private fun capture(
        name: String,
        model: WidgetModel,
        dark: Boolean = false,
        size: DpSize = DpSize(240.dp, 180.dp),
        fontScale: Float = 1f,
    ) {
        if (dark) RuntimeEnvironment.setQualifiers("+night") else RuntimeEnvironment.setQualifiers("+notnight")
        // Set after the qualifiers so they can't override it; the inflated widget reads its sp sizes
        // from this context's configuration, as a real host does.
        RuntimeEnvironment.setFontScale(fontScale)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = inflate(context, model, size, fontScale)
        // Capture at the widget's own size in px (420dpi), so a small size shows its real clipping.
        val density = context.resources.displayMetrics.density
        captureSnapshot(view, name, (size.width.value * density).toInt(), (size.height.value * density).toInt())
    }

    private fun inflate(context: Context, model: WidgetModel, size: DpSize, fontScale: Float = 1f): View {
        val result = runBlocking {
            GlanceRemoteViews().compose(context, size = size) {
                WidgetContent(model, now, fontScale)
            }
        }
        return result.remoteViews.apply(context, FrameLayout(context))
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    // Glance drops a Column's children past the tenth, which once cut a tall widget off after its
    // first few rows (or left a stop's header with none under it): every row in the model is drawn.
    @Test
    fun `every row in the model is drawn, past Glance's ten children per column`() {
        val destinations = listOf(
            "Brixton", "Walthamstow Central", "Cockfosters", "Heathrow Terminal 5", "Morden", "Edgware",
            "High Barnet", "Richmond", "Upminster", "Stanmore", "Stratford", "Ealing Broadway",
        )
        val rows = destinations.mapIndexed { i, destination ->
            rowModel(row("line$i", "Line $i", destination, 60L * (i + 1)))
                .let { if (i % 3 == 0) it.copy(header = WidgetHeader("Stop $i", "Stop $i")) else it }
        }
        val model = WidgetModel(hasData = true, stale = false, uncertain = false, stamp = "Updated just now", rows = rows)
        RuntimeEnvironment.setQualifiers("+notnight")
        RuntimeEnvironment.setFontScale(1f)
        val drawn = texts(inflate(ApplicationProvider.getApplicationContext(), model, DpSize(380.dp, 900.dp)))
        assertEquals(destinations, drawn.filter { it in destinations })
        assertEquals(listOf("Stop 0", "Stop 3", "Stop 6", "Stop 9"), drawn.filter { it.startsWith("Stop ") })
    }

    // In two columns, every row in the model is drawn too, across both.
    @Test
    fun `every row in the model is drawn across two columns`() {
        val destinations = listOf("Brixton", "Walthamstow Central", "Cockfosters", "Heathrow Terminal 5", "Morden", "Edgware")
        val rows = destinations.mapIndexed { i, destination ->
            rowModel(row("line$i", "Line $i", destination, 60L * (i + 1)))
                .let { if (i % 3 == 0) it.copy(header = WidgetHeader("Stop $i", "Stop $i")) else it }
        }
        val model = WidgetModel(hasData = true, stale = false, uncertain = false, stamp = "Updated just now", rows = rows, columnBreak = 3)
        RuntimeEnvironment.setQualifiers("+notnight")
        RuntimeEnvironment.setFontScale(1f)
        val drawn = texts(inflate(ApplicationProvider.getApplicationContext(), model, DpSize(760.dp, 400.dp)))
        assertEquals(destinations, drawn.filter { it in destinations })
    }

    /**
     * Draws the inflated widget view into a PNG. Measured and laid out explicitly — Robolectric's
     * views have no real surface, so an unmeasured view captures blank. Same shape as the sibling
     * screen screenshot tests; only recording/verifying actually writes a file.
     */
    private fun captureSnapshot(view: View, name: String, widthPx: Int, heightPx: Int) {
        val recording = System.getProperty("roborazzi.test.record") == "true"
        val verifying = System.getProperty("roborazzi.test.verify") == "true"
        if (!recording && !verifying) return

        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
