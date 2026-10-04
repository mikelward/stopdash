package app.stopdash.wear

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ModifiersBuilders.Border
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ModifiersBuilders.Semantics
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.stopdash.data.RouteTopologyStore
import app.stopdash.domain.NoTimes
import app.stopdash.domain.riderLineName
import app.stopdash.shared.R as SharedR
import app.stopdash.ui.PillColors
import app.stopdash.ui.pillColors
import com.google.common.util.concurrent.ListenableFuture
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The StopDash tile (dev-docs/wear-os.md *Surfaces*): the widget's rows, favorites first, as many as
 * fit, with their age, as a timeline the system steps through on its own ([TileTimeline]). It
 * renders only from the stored envelope, never the network; a new envelope asks for a re-render.
 */
class StopDashTileService : TileService() {
    /** The envelope and route-topology reads run here, never on the request's calling thread. */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            worker.execute {
                try {
                    // A request this watch sent before its process was killed is still owed an answer.
                    WatchRefresh.resume(this)
                    // The Refresh line was tapped: ask the phone (debounced), then render with its state.
                    if (requestParams.currentState.lastClickableId == TileLayout.REFRESH_ID) WatchRefresh.request(this)
                    val store = WatchEnvelopeStore.from(this)
                    store.load()
                    // As the clock reads it now, however it's been set since it arrived.
                    val envelope = store.current()
                    // The screen bounds how many lines fit; a request that doesn't say gets the default.
                    val device = requestParams.deviceConfiguration
                    val screen = TileScreen(device.screenHeightDp, device.fontScale).takeIf { it.heightDp > 0 }
                    val now = Instant.now()
                    // The watch's asset with the phone's refreshed route lines over it.
                    val topology = RouteTopologyStore.over(this, envelope?.routePatterns().orEmpty())
                    val schedule = TileTimeline.schedule(envelope, now, topology, screen)
                    val notices = RefreshPolicy.notices(WatchRefresh.state.value, now)
                    val timeline = tileTimeline(this, TileTimeline.withNotice(schedule.entries, notices))
                    val tile = TileBuilders.Tile.Builder()
                        .setResourcesVersion(RESOURCES_VERSION)
                        .setTileTimeline(timeline)
                    // The entry cap cut the timeline short: ask to be re-rendered where it stops.
                    schedule.refreshAt?.let {
                        tile.setFreshnessIntervalMillis(Duration.between(Instant.now(), it).toMillis().coerceAtLeast(MIN_FRESHNESS_MS))
                    }
                    completer.set(tile.build())
                    lookUpOnce(store)
                } catch (e: Exception) {
                    // The failure type only; the system keeps the tile's last layout.
                    Log.w(TAG, "tile render failed: ${e::class.simpleName}")
                    completer.setException(e)
                }
            }
            "stopdash-tile"
        }

    /**
     * Looks up the item the Data Layer already holds, once per process (again on a later request if
     * it failed), and re-renders if it brought a different envelope. A tile added (or its app data
     * cleared) after the phone last published has none, and one whose listener couldn't read the
     * last publish holds an older one; either would otherwise wait for the next publish.
     */
    private fun lookUpOnce(store: WatchEnvelopeStore) {
        if (!lookedUp.compareAndSet(false, true)) return
        val before = (store.state.value as? WatchReceived.Received)?.envelope
        if (!ingestExisting(this, store)) {
            // Failed (logged): the next tile request tries again, rather than never in this process.
            lookedUp.set(false)
            return
        }
        val after = (store.state.value as? WatchReceived.Received)?.envelope
        if (after != null && after != before) requestUpdate(this)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(
                ResourceBuilders.Resources.Builder()
                    .setVersion(RESOURCES_VERSION)
                    .addIdToImageMapping(
                        TileLayout.CALENDAR_ID,
                        ResourceBuilders.ImageResource.Builder()
                            .setAndroidResourceByResId(
                                ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(SharedR.drawable.ic_calendar).build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            "stopdash-tile-resources"
        }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        // Bumped with each image added, so a host holding the old set asks for the new one.
        private const val RESOURCES_VERSION = "2"
        private const val TAG = "StopDash.Tile"

        /** A floor on the re-render interval, so a cut a moment away can't ask for a tight loop. */
        private const val MIN_FRESHNESS_MS = 60_000L

        private val lookedUp = AtomicBoolean(false)

        /**
         * Asks the system to re-render the tile, after a new envelope arrives. The updater binds a
         * service, which a broadcast receiver's own context refuses ([ClockChangeReceiver]), so it
         * always binds from the application's.
         */
        fun requestUpdate(context: Context) {
            getUpdater(context.applicationContext).requestUpdate(StopDashTileService::class.java)
        }
    }
}

/**
 * The Tiles timeline for [entries], each drawn by [TileLayout]. An open-ended entry carries no
 * validity at all, the renderer's default for any time no other entry covers: a start with no end
 * reads to the renderer as a zero-width interval it never shows, which left the tile blank once every
 * stop was stale, and before the phone first synced.
 */
internal fun tileTimeline(context: Context, entries: List<TileEntry>): TimelineBuilders.Timeline {
    val timeline = TimelineBuilders.Timeline.Builder()
    for (entry in entries) {
        val layout = LayoutElementBuilders.Layout.Builder().setRoot(TileLayout.root(context, entry.frame, entry.notice)).build()
        val built = TimelineBuilders.TimelineEntry.Builder().setLayout(layout)
        entry.end?.let { end ->
            built.setValidity(
                TimelineBuilders.TimeInterval.Builder().setStartMillis(entry.start.toEpochMilli()).setEndMillis(end.toEpochMilli()).build(),
            )
        }
        timeline.addTimelineEntry(built.build())
    }
    return timeline.build()
}

/**
 * What a screen reader says for [planned]'s calendar, on the tile and in the watch app: "Planned
 * Part Closure from 13 Oct", the day short and in the watch's own locale, as the app says it.
 */
internal fun plannedDescription(context: Context, planned: TilePlanned): String {
    val date = planned.startsOn.format(DateTimeFormatter.ofPattern("d MMM", context.resources.configuration.locales[0]))
    return context.getString(R.string.planned_alert_description, planned.label, date)
}

internal object TileLayout {
    private val white = Color.White.toArgb()
    private val gray = Color(0xFFB0ABA3).toArgb()
    private val warning = Color(0xFFF2C14E).toArgb()
    private val neutralFill = Color(0xFF303030).toArgb()

    /** The clickable id of the Refresh line. */
    const val REFRESH_ID = "refresh"

    /** The clickable id of the All stops line. */
    const val ALL_STOPS_ID = "all_stops"

    fun root(context: Context, frame: TileFrame, notice: RefreshNotice.Kind? = null): LayoutElement {
        val column = Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        when (frame) {
            TileFrame.NeverSynced -> {
                column.addContent(message(context.getString(R.string.watch_open_phone)))
                // The phone may have stops this watch missed: a refresh asks it to resend them.
                column.addContent(Spacer.Builder().setHeight(dp(4f)).build())
                column.addContent(refresh(context, notice))
            }
            TileFrame.NoStops -> {
                column.addContent(message(context.getString(R.string.watch_add_stops)))
                // Stops added on the phone may not have arrived: a refresh asks it to resend them.
                column.addContent(Spacer.Builder().setHeight(dp(4f)).build())
                column.addContent(refresh(context, notice))
            }
            TileFrame.NoneLoaded -> {
                column.addContent(text(context.getString(R.string.watch_partly_out_of_date), 14f, warning, maxLines = 3))
                // Nothing loaded is exactly when a refresh is wanted.
                column.addContent(Spacer.Builder().setHeight(dp(4f)).build())
                column.addContent(refresh(context, notice))
            }
            is TileFrame.Rows -> {
                val stamp = when {
                    frame.ageMinutes < 1 -> context.getString(R.string.tile_updated_now)
                    else -> context.getString(R.string.tile_updated_ago, frame.ageMinutes)
                }
                column.addContent(text(stamp, 12f, if (frame.stale) warning else gray))
                if (frame.stale || frame.partial) {
                    val note = if (frame.stale) R.string.tile_out_of_date else R.string.watch_partly_out_of_date
                    column.addContent(text(context.getString(note), 12f, warning))
                } else if (frame.statusUnknown) {
                    column.addContent(text(context.getString(R.string.watch_disruptions_unknown), 12f, warning))
                }
                if (frame.omitted > 0) {
                    val more = context.resources.getQuantityString(R.plurals.watch_more_stops_on_phone, frame.omitted, frame.omitted)
                    column.addContent(text(more, 12f, gray))
                }
                for (line in frame.lines) {
                    column.addContent(Spacer.Builder().setHeight(dp(4f)).build())
                    column.addContent(
                        when (line) {
                            is TileLine.Header -> text(line.text, 12f, gray, bold = true, spoken = line.spoken)
                            is TileLine.Departure -> row(context, line.row)
                            is TileLine.Disruption -> disruption(context, line)
                            TileLine.OnlyHidden -> text(context.getString(R.string.tile_only_hidden), 14f, white, maxLines = 2)
                            is TileLine.EmptyStop -> {
                                val empty = if (line.uncertain) R.string.tile_may_be_out_of_date else R.string.tile_no_departures
                                text(context.getString(R.string.tile_stop_empty, line.stopName, context.getString(empty)), 14f, white)
                            }
                        },
                    )
                }
                column.addContent(Spacer.Builder().setHeight(dp(4f)).build())
                column.addContent(if (offersAllStops(frame, notice)) allStops(context) else refresh(context, notice))
            }
        }
        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(Modifiers.Builder().setPadding(Padding.Builder().setAll(dp(24f)).build()).build())
            .addContent(column.build())
            .build()
    }

    /**
     * The Refresh line: tapping it asks the phone for a refresh (the tile re-renders with
     * [REFRESH_ID] as its last click). While a refresh is pending, or after one failed, it says so
     * instead, and stays tappable.
     */
    private fun refresh(context: Context, notice: RefreshNotice.Kind?): LayoutElement {
        val label = refreshLabel(notice)
        val color = when (notice) {
            null -> white
            RefreshNotice.Kind.REFRESHING -> gray
            else -> warning
        }
        return chip(context.getString(label), color, REFRESH_ID, ActionBuilders.LoadAction.Builder().build())
    }

    /** A tappable line at the foot of the tile, in the tile's neutral chip. */
    private fun chip(label: String, color: Int, id: String, action: ActionBuilders.Action): LayoutElement =
        Box.Builder()
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(Clickable.Builder().setId(id).setOnClick(action).build())
                    .setBackground(
                        Background.Builder().setColor(argb(neutralFill)).setCorner(Corner.Builder().setRadius(dp(12f)).build()).build(),
                    )
                    .setPadding(Padding.Builder().setTop(dp(4f)).setBottom(dp(4f)).setStart(dp(12f)).setEnd(dp(12f)).build())
                    .build(),
            )
            .addContent(text(label, 12f, color))
            .build()

    /**
     * Whether the tile's foot is **All stops** rather than Refresh: fresh and complete (no stop
     * failed, none left out for size, every shown line's status checked), with no refresh under way. Otherwise Refresh, which is what
     * an out-of-date tile needs (and says why it's pending or failed); the app couldn't show stops
     * left out of the envelope anyway.
     */
    fun offersAllStops(frame: TileFrame.Rows, notice: RefreshNotice.Kind?): Boolean =
        !frame.stale && !frame.partial && !frame.statusUnknown && frame.omitted == 0 && notice == null

    /** The **All stops** line: opens the watch app, which lists every row and scrolls. */
    private fun allStops(context: Context): LayoutElement {
        val app = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(context.packageName)
            .setClassName(WatchHomeActivity::class.java.name)
            .build()
        return chip(context.getString(R.string.tile_all_stops), white, ALL_STOPS_ID, ActionBuilders.LaunchAction.Builder().setAndroidActivity(app).build())
    }

    private fun row(context: Context, row: TileRow): LayoutElement =
        Row.Builder()
            .setWidth(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(pill(row))
            .addContent(Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(
                Box.Builder().setWidth(expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                    .addContent(text(row.label, 14f, white))
                    .build(),
            )
            .addContent(Spacer.Builder().setWidth(dp(4f)).build())
            .apply {
                row.planned?.let {
                    addContent(calendar(context, it))
                    addContent(Spacer.Builder().setWidth(dp(4f)).build())
                }
            }
            .addContent(text(row.countdown, 14f, if (row.stale) warning else white, bold = true))
            .build()

    /**
     * Work still to come on a row's line: the app's and the widget's muted calendar, before the
     * countdown, and what's planned and from when to a screen reader.
     */
    private fun calendar(context: Context, planned: TilePlanned): LayoutElement =
        LayoutElementBuilders.Image.Builder()
            .setResourceId(CALENDAR_ID)
            .setWidth(dp(CALENDAR_SIZE))
            .setHeight(dp(CALENDAR_SIZE))
            .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(gray)).build())
            .setModifiers(Modifiers.Builder().setSemantics(semantics(plannedDescription(context, planned))).build())
            .build()

    /** The calendar's image resource, registered with the tile's resources ([StopDashTileService.onTileResourcesRequest]). */
    const val CALENDAR_ID = "calendar"

    // The 14sp countdown's line height, on the 4dp grid.
    private const val CALENDAR_SIZE = 16f

    /**
     * A disrupted line's "⚠ Severe Delays" in the warning color: beside its pill when the line has no
     * countdown, else under its departures, indented to the destination column (the 36dp pill and
     * the 4dp gap) so it reads as part of that service.
     */
    private fun disruption(context: Context, line: TileLine.Disruption): LayoutElement {
        // A rail line's reason for no times follows the status in the same text, so the
        // disruption always leads and a tight line cuts the reason, never the alert.
        val reason = noTimesText(context, line.noTimes)
        val status = if (reason == null) {
            text("⚠ ${line.description}", 12f, warning, spoken = "Disrupted: ${line.description}")
        } else {
            text("⚠ ${line.description} · $reason", 12f, warning, spoken = "Disrupted: ${line.description}. $reason")
        }
        return Row.Builder()
            .setWidth(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .apply {
                if (line.alone) addContent(pill(line.row)) else addContent(Spacer.Builder().setWidth(dp(36f)).build())
            }
            .addContent(Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(
                Box.Builder().setWidth(expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                    .addContent(status)
                    .build(),
            )
            .build()
    }

    private fun noTimesText(context: Context, noTimes: NoTimes?): String? = when (noTimes) {
        NoTimes.NO_KEY -> context.getString(R.string.watch_no_rail_key)
        NoTimes.NO_DATA -> context.getString(R.string.watch_no_data)
        NoTimes.NO_TRAINS, null -> null
    }

    private fun pill(row: TileRow): LayoutElement {
        val colors = pillColors(row.lineName, row.lineId, row.mode, Color.Black)
        val (fill, label, border) = when (colors) {
            is PillColors.Solid -> Triple(colors.fill.toArgb(), colors.label.toArgb(), colors.border.toArgb())
            is PillColors.Hollow -> Triple(Color.Black.toArgb(), colors.label.toArgb(), colors.border.toArgb())
            PillColors.Neutral -> Triple(neutralFill, white, neutralFill)
        }
        return Box.Builder()
            .setWidth(dp(36f))
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(Background.Builder().setColor(argb(fill)).setCorner(Corner.Builder().setRadius(dp(6f)).build()).build())
                    .setBorder(Border.Builder().setWidth(dp(1f)).setColor(argb(border)).build())
                    .setPadding(Padding.Builder().setTop(dp(4f)).setBottom(dp(4f)).build())
                    // A screen reader says the line's name, not its short code (SPEC *Line pill colors*).
                    .setSemantics(semantics(riderLineName(row.lineName, row.mode)))
                    .build(),
            )
            .addContent(text(row.code, 11f, label, bold = true))
            .build()
    }

    private fun semantics(spoken: String): Semantics = Semantics.Builder().setContentDescription(spoken).build()

    private fun message(value: String): LayoutElement = text(value, 14f, white, maxLines = 3)

    /** A line of text; [spoken], when set, is what a screen reader says in its place. */
    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int = 1,
        spoken: String? = null,
    ): LayoutElement =
        Text.Builder()
            .setText(value)
            .apply { spoken?.let { setModifiers(Modifiers.Builder().setSemantics(semantics(it)).build()) } }
            .setMaxLines(maxLines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            )
            .build()
}
