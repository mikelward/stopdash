package app.stopdash.wear

import android.content.ComponentName
import android.content.Context
import android.util.Log
import app.stopdash.data.RouteTopologyStore
import app.stopdash.domain.RouteTopology
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The complication (dev-docs/wear-os.md): the next departure of its row on the watch face, the row
 * the user picked ([ComplicationConfigActivity]) or else the widget's top row, as a
 * [ComplicationTimeline] the system counts down and advances by itself. It renders only the stored
 * envelope, never the network, and is pushed a fresh timeline when a new one arrives
 * ([requestUpdate]); there's no update period.
 */
class StopDashComplicationService : SuspendingTimelineComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val id = request.complicationInstanceId
        val (envelope, topology, row) = try {
            withContext(Dispatchers.IO) {
                val context = this@StopDashComplicationService
                val store = WatchEnvelopeStore.from(context)
                store.load()
                // A complication added before any other surface ran looks up what the phone already
                // sent, off this render; a newer envelope then re-renders it.
                WatchSurfaces.lookUpOnce(context, store)
                // As the clock reads it now, however it's been set since it arrived.
                val envelope = store.current()
                // A picked stop that left the widget's scope: drop the pick (and tell the phone), so
                // the complication shows the default row rather than hold the old one.
                ComplicationSelections.clearIfGone(context, id, envelope)
                // Cheap when unchanged, and heals a sync that failed.
                ComplicationSelections.sync(context)
                // The watch's asset with the phone's refreshed route lines over it.
                val topology = RouteTopologyStore.over(context, envelope?.routePatterns().orEmpty())
                Triple(envelope, topology, ComplicationSelections.get(context, id))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreadable storage: the no-data dash, never an old row passed off as current.
            Log.w(TAG, "complication envelope unreadable: ${e::class.simpleName}")
            Triple(null, RouteTopology.EMPTY, null)
        }
        val entries = ComplicationTimeline.entries(envelope, Instant.now(), row = row, topology = topology)
        return ComplicationRender.timeline(this, request.complicationType, entries)
    }

    /** A complication removed from the watch face: its pick goes, and the phone stops keeping it. */
    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        ComplicationSelections.set(this, complicationInstanceId, null)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val preview = ComplicationContent.Departure("N29", "N29", "Trafalgar Square", Instant.now().plusSeconds(180), uncertain = false)
        return ComplicationRender.data(this, type, preview)
    }

    companion object {
        private const val TAG = "StopDash.Complication"

        /** Asks the system for a fresh timeline for every LDN Go complication, after a new envelope. */
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, StopDashComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}

/** Turns [ComplicationEntry]s into the watch face's complication data. */
internal object ComplicationRender {
    /**
     * The timeline: each bounded entry for its own interval, and the open-ended last one (the stale
     * form, or no data) as the default the system shows outside them.
     */
    fun timeline(context: Context, type: ComplicationType, entries: List<ComplicationEntry>): ComplicationDataTimeline? {
        val default = data(context, type, entries.last().content) ?: return null
        val timed = entries.filter { it.end != null }.mapNotNull { entry ->
            data(context, type, entry.content)?.let { TimelineEntry(TimeInterval(entry.start, entry.end!!), it) }
        }
        return ComplicationDataTimeline(default, timed)
    }

    /** [content] as [type], or null for a type this complication doesn't offer. */
    fun data(context: Context, type: ComplicationType, content: ComplicationContent): ComplicationData? {
        if (content == ComplicationContent.NoData) return NoDataComplicationData()
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                val (code, text) = shortText(context, content)
                ShortTextComplicationData.Builder(text, longText(context, content))
                    .setTitle(plain(code))
                    .build()
            }
            ComplicationType.LONG_TEXT -> {
                val text = longText(context, content)
                LongTextComplicationData.Builder(text, text).build()
            }
            else -> null
        }
    }

    /** The short form: the line's code as the title, the countdown (or "None", "?") as the text. */
    private fun shortText(context: Context, content: ComplicationContent): Pair<String, ComplicationText> = when (content) {
        is ComplicationContent.Departure ->
            marked(content.code, content.disruption) to
                countdown(content.at, if (content.uncertain) context.getString(R.string.complication_uncertain_time) else null)
        is ComplicationContent.Empty ->
            marked(content.code, content.disruption) to
                plain(context.getString(if (content.uncertain) R.string.complication_unknown else R.string.complication_none))
        is ComplicationContent.Stale -> marked(content.code, content.disruption) to plain(context.getString(R.string.complication_unknown))
        ComplicationContent.NoData -> "" to plain("")
    }

    /** The long form, also every form's content description: line, destination and countdown. */
    private fun longText(context: Context, content: ComplicationContent): ComplicationText = when (content) {
        is ComplicationContent.Departure -> {
            val format = if (content.uncertain) R.string.complication_long_departure_uncertain else R.string.complication_long_departure
            countdown(content.at, context.getString(format, lineWith(context, content.lineName, content.disruption), content.destination))
        }
        is ComplicationContent.Empty ->
            plain(
                context.getString(
                    if (content.uncertain) R.string.complication_long_unsure else R.string.complication_long_none,
                    lineWith(context, content.lineName, content.disruption),
                ),
            )
        is ComplicationContent.Stale ->
            plain(context.getString(R.string.complication_long_stale, lineWith(context, content.lineName, content.disruption)))
        ComplicationContent.NoData -> plain("")
    }

    /** The short form's line code, "⚠VIC" while the line is disrupted, so the face shows it. */
    private fun marked(code: String, disruption: String?): String = if (disruption != null) "⚠$code" else code

    /** The line's name, with its disruption beside it while there is one: "Victoria (Severe Delays)". */
    private fun lineWith(context: Context, lineName: String, disruption: String?): String =
        disruption?.let { context.getString(R.string.complication_line_disrupted, lineName, it) } ?: lineName

    /** A countdown to [at], in whole minutes, that the system keeps current; [text] places it at `^1`. */
    private fun countdown(at: Instant, text: String?): ComplicationText =
        TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(at))
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .apply { if (text != null) setText(text) }
            .build()

    private fun plain(text: String): ComplicationText = PlainComplicationText.Builder(text).build()
}
