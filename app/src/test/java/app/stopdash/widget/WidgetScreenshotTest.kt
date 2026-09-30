@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)

package app.stopdash.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
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
import app.stopdash.domain.RailFeed
import app.stopdash.domain.STATUS_DIRECTION_KEY
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.StopArrivals
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
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
                rows = listOf(WidgetRowModel(rail, emptyList(), noTimes = NoTimes.NO_KEY), rowModel(row("victoria", "Victoria", "Brixton", 120))),
                stacked = true,
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
                // The minimum height's line budget (widgetLineBudget) is one line.
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
        // The same budgets StopDashWidget.provideGlance derives for this size and font.
        val stacked = widgetRowsStacked(size.width, fontScale)
        val model = widgetModel(
            snapshot,
            now,
            maxLines = widgetLineBudget(size.height, fontScale, stacked = stacked),
            maxLinesWithNote = widgetLineBudget(size.height, fontScale, withNote = true, stacked = stacked),
            maxLinesCompact = widgetLineBudget(size.height, fontScale, compact = true, stacked = stacked),
            stacked = stacked,
        )
        capture(name, model, size = size, fontScale = fontScale)
    }

    // The narrowest width at a large system font, tall enough for the title row: the title gives
    // way, the freshness stamp stays whole, and the row stacks as it does at this width and font
    // (widgetRowsStacked).
    @Test
    fun `narrowest width at a large font keeps the stamp whole`() {
        capture(
            "widget-min-size-large-font.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated 14 min ago",
                rows = listOf(rowModel(row("victoria", "Victoria", "Brixton", 120))),
                stacked = widgetRowsStacked(180.dp, 1.3f),
            ),
            size = DpSize(180.dp, 180.dp),
            fontScale = 1.3f,
        )
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
        val result = runBlocking {
            GlanceRemoteViews().compose(context, size = size) {
                WidgetContent(model, now, fontScale)
            }
        }
        val view = result.remoteViews.apply(context, FrameLayout(context))
        // Capture at the widget's own size in px (420dpi), so a small size shows its real clipping.
        val density = context.resources.displayMetrics.density
        captureSnapshot(view, name, (size.width.value * density).toInt(), (size.height.value * density).toInt())
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
