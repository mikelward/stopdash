package app.stopdash.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.stopdash.data.WatchTrip
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.SteadyClock
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The trip on the way as the widget shows it (SPEC *On the way*, maintainer 2026-10-05: "show the
 * departures at the next change"): the same summary the phone sends a watch ([WatchTrip]) — the next
 * step in the phone's words and the trains that take the rider on the next ride — kept on the phone
 * in a file of its own, since the widget renders only what is stored. Written by
 * [app.stopdash.watch.WatchTripSync] while the trip is followed, each write redrawing the widget;
 * removed when the trip ends. Never leaves the phone.
 */
/**
 * The trip as kept for the widget, with when it was written by the steady clock ([SteadyClock]) and
 * that clock's frame, so its age survives the device's clock being set either way (Codex on #600).
 */
@kotlinx.serialization.Serializable
internal data class StoredWidgetTrip(
    val trip: WatchTrip,
    // The write's time in its process's steady frame, epoch millis.
    val steadyAt: Long,
    val boot: String? = null,
    val origin: Long? = null,
    // The writing process ([WidgetTripStore.PROCESS]): within it, [steadyAt] needs no frame to read.
    val process: String? = null,
)

/** A kept trip and its write time in this process's steady frame ([SteadyClock.stamp]). */
internal data class KeptWidgetTrip(val trip: WatchTrip, val steadyAt: Instant)

internal object WidgetTripStore {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private const val FILE_NAME = "widget-trip.json"

    /** This process, told apart from any other that wrote the file. */
    internal val PROCESS: String = java.util.UUID.randomUUID().toString()

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    private fun tmp(context: Context) = File(context.applicationContext.noBackupFilesDir, "$FILE_NAME.tmp")

    // Set once a trip is cleared and until the next is written: a file a failed delete left behind
    // is never read back as a live trip in this process (Codex on #600).
    @Volatile private var cleared = false

    /** The kept trip, or null when there's none or it can't be read (logged, never the trip itself). */
    suspend fun load(context: Context, io: CoroutineDispatcher = Dispatchers.IO): KeptWidgetTrip? = withContext(io) {
        val from = file(context)
        if (cleared || !from.exists()) return@withContext null
        try {
            val stored = json.decodeFromString(StoredWidgetTrip.serializer(), from.readText())
            if (stored.trip.version != WatchTrip.CURRENT_VERSION) return@withContext null
            val frame = stored.boot?.let { boot -> stored.origin?.let { SteadyClock.Frame(boot, it) } }
            val current = SteadyClock.source?.frame
            val steadyAt = when {
                // Written by this process: already in its steady frame.
                stored.process == PROCESS -> Instant.ofEpochMilli(stored.steadyAt)
                // Same boot: into this process's frame.
                frame != null && current != null && frame.boot == current.boot ->
                    Instant.ofEpochMilli(stored.steadyAt).plus(SteadyClock.shiftFrom(frame))
                // From before a reboot, or a frame that can't be told: its age can't be trusted, and
                // the next write (every 30 s while a trip is followed) replaces it (Codex on #600).
                else -> return@withContext null
            }
            KeptWidgetTrip(stored.trip, steadyAt)
        } catch (e: IOException) {
            logWidgetSnapshotWarning("widget trip read failed: ${e::class.simpleName}")
            null
        } catch (e: kotlinx.serialization.SerializationException) {
            logWidgetSnapshotWarning("widget trip unreadable: ${e::class.simpleName}")
            null
        } catch (e: IllegalArgumentException) {
            logWidgetSnapshotWarning("widget trip unreadable: ${e::class.simpleName}")
            null
        }
    }

    /**
     * Keeps [trip] (null removes it) and redraws the widget to show it ([write], then [redraw]).
     */
    suspend fun show(
        context: Context,
        trip: WatchTrip?,
        io: CoroutineDispatcher = Dispatchers.IO,
        redraw: suspend (Context) -> Unit = { redrawWidgets(it) },
    ) {
        write(context, trip, io)
        redrawNow(context, redraw)
    }

    /**
     * Keeps [trip] (null removes it), written whole and renamed into place, so a render never reads half
     * a trip. A failed write is logged; the widget then shows what it last could, marked out of date as
     * that ages ([widgetTripModel]). The file only: the redraw is [redraw]'s, so a slow render can't
     * hold up whoever writes (Codex on #600).
     */
    suspend fun write(context: Context, trip: WatchTrip?, io: CoroutineDispatcher = Dispatchers.IO) {
        withContext(io) {
            try {
                val to = file(context)
                if (trip == null) {
                    cleared = true
                    scrub(tmp(context))
                    scrub(to)
                } else {
                    val tmp = tmp(context)
                    val frame = SteadyClock.source?.frame
                    val stored = StoredWidgetTrip(
                        trip,
                        steadyAt = SteadyClock.stamp(Instant.ofEpochMilli(trip.sentAt)).toEpochMilli(),
                        boot = frame?.boot,
                        origin = frame?.originMillis,
                        process = PROCESS,
                    )
                    tmp.writeText(json.encodeToString(StoredWidgetTrip.serializer(), stored))
                    if (!tmp.renameTo(to)) throw IOException("rename failed")
                    cleared = false
                }
            } catch (e: IOException) {
                // A half-written or unrenamed copy is the trip too: it doesn't outlive the write that
                // failed (Codex on #600).
                logWidgetSnapshotWarning("widget trip write failed: ${e::class.simpleName}")
                scrub(tmp(context))
            }
        }
    }

    /**
     * Removes [file]; one that won't delete is emptied instead, so not even a later process reads the
     * trip back. Logs a file that can be neither (Codex on #600).
     */
    @VisibleForTesting
    internal fun scrub(file: File) {
        if (!file.exists() || file.delete()) return
        try {
            file.writeBytes(ByteArray(0))
            logWidgetSnapshotWarning("widget trip delete failed: ${file.name} emptied")
        } catch (e: IOException) {
            logWidgetSnapshotWarning("widget trip delete failed: ${file.name} not emptied: ${e::class.simpleName}")
        }
    }

    private suspend fun redrawNow(context: Context, redraw: suspend (Context) -> Unit) {
        try {
            redraw(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget re-render failed: ${e::class.simpleName}")
        }
    }

    // Redraws asked for while one runs fold into one more after it: a slow render never queues a
    // backlog, and whatever is written last is what the next render reads.
    private val redraws = kotlinx.coroutines.channels.Channel<Context>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default).launch {
            for (context in redraws) redrawNow(context) { redrawWidgets(it) }
        }
    }

    /** Asks for a redraw without waiting for it, folded with any already asked for. */
    fun redraw(context: Context) {
        redraws.trySend(context.applicationContext)
    }
}

/** Whether a widget is placed to show a trip on: none, and the trip isn't built or kept for it. */
internal object WidgetTrips {
    fun placed(context: Context): Boolean = placedWidgetIds(context).isNotEmpty()
}

/** A train row on the widget's trip: a line, where it goes, and its countdown or guessed time. */
internal data class WidgetTripTrain(
    val lineId: String,
    val lineName: String,
    val mode: String,
    val destination: String,
    val countdown: String,
    // The pole it boards at, when the stop pair's poles each have trains ([WatchTrip.Train.stop]).
    val stop: String,
    // Leaves before the rider can be there: drawn muted, as the trip's screen grays it.
    val missed: Boolean,
    // From a board the phone holds as too old to stand behind ([WatchTrip.oldDepartures]): its time a
    // marked guess, drawn dimmed, though the trip itself is current.
    val guess: Boolean = false,
)

/**
 * What the widget draws for a trip on the way: the next step ([title], [detail]), the trains at the
 * next change, and the phone's [note] on them.
 */
internal data class WidgetTripModel(
    val title: String,
    val detail: String,
    val trains: List<WidgetTripTrain>,
    val note: String,
    val stamp: String,
    // Not updated for [WIDGET_TRIP_STALE_AFTER]: the trains read as guesses ("21:14?"), and it says so.
    val stale: Boolean,
    // When this drawing next goes wrong — it goes stale, the trip is too old to show, or a guessed
    // train is due — so the widget is redrawn then even if the phone stops updating it.
    val redrawAt: Instant,
)

/** How long a trip goes without an update before the widget marks it out of date, as the watch does. */
internal val WIDGET_TRIP_STALE_AFTER: Duration = Duration.ofMinutes(2)

/** How far ahead of the clock a trip's stamp may be (the clock nudged) and still be shown. */
internal val WIDGET_TRIP_CLOCK_SKEW: Duration = Duration.ofMinutes(1)

/** How long a trip goes without an update before the widget stops showing it, as the watch does. */
internal val WIDGET_TRIP_GONE_AFTER: Duration = Duration.ofMinutes(15)

/**
 * [widgetTripModel] worked out on [worker], the hop first (AGENTS.md *Main thread*): it sorts and
 * groups the trip's trains, and is called from the widget's render.
 */
internal suspend fun widgetTripModelOn(
    kept: KeptWidgetTrip?,
    now: Instant,
    worker: CoroutineDispatcher = Dispatchers.Default,
): WidgetTripModel? = withContext(worker) {
    kept?.let { widgetTripModel(it.trip, now, SteadyClock.age(it.steadyAt, now)) }
}

/**
 * [trip] as the widget draws it at [now], [age] old by the steady clock ([SteadyClock], so setting the
 * clock either way can't make it read as newer; the wall clock's when not given), or null when there is
 * none or it went [WIDGET_TRIP_GONE_AFTER] without an update (the phone stopped following it), so the
 * widget shows its departures again. The
 * trains are grouped as the watch groups them: one row per pole, line and destination, its next times,
 * or the soonest's time as a guess once out of date (SPEC D4); departed trains left out.
 */
internal fun widgetTripModel(
    trip: WatchTrip?,
    now: Instant,
    age: Duration = trip?.let { Duration.between(Instant.ofEpochMilli(it.sentAt), now) } ?: Duration.ZERO,
): WidgetTripModel? {
    if (trip == null) return null
    // Its time as the wall clock reads it now, for the stamp and the redraws, which go by the wall clock.
    val sentAt = now.minus(age)
    // Stamped well ahead (from before a reboot, the clock since set back): its age can't be told, so it
    // isn't shown as live, nor at all, and the widget shows its departures (Codex on #600).
    if (age >= WIDGET_TRIP_GONE_AFTER || age < WIDGET_TRIP_CLOCK_SKEW.negated()) return null
    val stale = age >= WIDGET_TRIP_STALE_AFTER
    // An old board's trains ([WatchTrip.oldDepartures]) read as guesses, as the trip's screen draws them (D4).
    val old = trip.departures.isEmpty() && trip.oldDepartures.isNotEmpty()
    val due = (if (old) trip.oldDepartures else trip.departures)
        .map { Departure(it.lineId, it.lineName, "", it.destination, null, Instant.ofEpochMilli(it.dueAt), it.mode) to it }
        .filter { Countdown.upcoming(listOf(it.first), now).isNotEmpty() }
        .sortedBy { it.first.expectedArrival }
    val trains = due.groupBy { it.second.stop }.flatMap { (stop, rows) ->
        rows.groupBy { (d, t) -> listOf(t.missed, d.lineId, d.lineName, d.destination) }.map { (_, group) ->
            val first = group.first().first
            val departures = group.map { it.first }
            WidgetTripTrain(
                lineId = first.lineId,
                lineName = first.lineName,
                mode = first.mode,
                destination = first.destination,
                countdown = if (stale || old) Countdown.staleLabel(departures) else Countdown.mergedLabel(departures, now),
                stop = stop,
                missed = group.first().second.missed,
                guess = old,
            )
        }
    }
    val soonest = due.firstOrNull()?.first?.expectedArrival
    val redrawAt = listOfNotNull(
        sentAt.plus(WIDGET_TRIP_STALE_AFTER).takeIf { !stale },
        sentAt.plus(WIDGET_TRIP_GONE_AFTER),
        soonest,
    ).min()
    return WidgetTripModel(
        title = trip.title,
        detail = trip.detail,
        trains = trains,
        note = trip.departuresNote,
        stamp = widgetStamp(sentAt, now),
        stale = stale,
        redrawAt = redrawAt,
    )
}

/**
 * One train row in a laid-out trip, with the pole header ([WidgetTripTrain.stop]) it starts, if any, and
 * whether it [stacked] its destination under the pill and countdown, too narrow at its font for all
 * three on one line ([widgetRowStacked], as a departure row does).
 */
internal data class WidgetTripRow(val train: WidgetTripTrain, val header: String?, val stacked: Boolean = false)

/**
 * What a trip draws at one size: whether the step fits too, and the train rows that do; [tooSmall]
 * when neither a train nor the step fits, so it says so rather than show a header over nothing.
 */
internal data class WidgetTripLayout(val showStep: Boolean, val rows: List<WidgetTripRow>, val tooSmall: Boolean = false)

/**
 * [model] laid out in a widget [height] tall at [fontScale] (Codex on #600): the trains come first, as
 * many as fit with the pole headers they start; the step shows above them only where it leaves room for
 * every train (maintainer, 2026-10-05). Heights are estimates in dp, erring short, the text ones growing
 * with the font as the departures' budget does ([widgetLineHeight]).
 */
internal fun widgetTripLayout(model: WidgetTripModel, width: Float, height: Float, fontScale: Float): WidgetTripLayout {
    val text = 16f * fontScale
    val fixed = 24f + 20f * fontScale + 8f + (if (model.stale) text else 0f) + (if (model.note.isNotEmpty()) text else 0f)
    val step = 36f * fontScale + (if (model.detail.isNotEmpty()) text else 0f) + 8f
    val poles = model.trains.map { it.stop }.distinct().size > 1
    val all = model.trains.mapIndexed { index, train ->
        val starts = poles && train.stop.isNotEmpty() && (index == 0 || model.trains[index - 1].stop != train.stop)
        WidgetTripRow(train, train.stop.takeIf { starts }, widgetRowStacked(listOf(train.countdown), false, width.dp, fontScale))
    }
    fun fitting(room: Float): List<WidgetTripRow> {
        var used = 0f
        return all.takeWhile { row ->
            used += widgetLineHeight(fontScale, row.stacked) + (if (row.header != null) 20f * fontScale else 0f)
            used <= room
        }
    }
    // No trains yet (loading, none, or a failed fetch): the step, where it fits, else the board's status
    // line alone where it fits, which says more than "Too small" (Codex on #600).
    if (all.isEmpty()) {
        return when {
            height - fixed >= step -> WidgetTripLayout(true, emptyList())
            model.note.isNotEmpty() && height >= fixed -> WidgetTripLayout(false, emptyList())
            else -> WidgetTripLayout(false, emptyList(), tooSmall = true)
        }
    }
    val withStep = fitting(height - fixed - step)
    if (withStep.size == all.size) return WidgetTripLayout(true, all)
    val alone = fitting(height - fixed)
    return when {
        alone.isNotEmpty() -> WidgetTripLayout(false, alone)
        // No train fits (a small widget at a large font): the step alone, where it fits, else the board's
        // status line where it has one and it fits, else say why there's nothing (Codex on #600).
        height - fixed >= step -> WidgetTripLayout(true, emptyList())
        model.note.isNotEmpty() && height >= fixed -> WidgetTripLayout(false, emptyList())
        else -> WidgetTripLayout(false, emptyList(), tooSmall = true)
    }
}

/**
 * [model]'s layout for each size the host asked for, and the minimum size's for any other, worked out
 * on [worker] so composition only looks one up; a size reported later is added there too.
 */
internal class WidgetTripLayouts(
    private val model: WidgetTripModel,
    private val fontScale: Float,
    private val bySize: Map<androidx.compose.ui.unit.DpSize, WidgetTripLayout>,
    private val fallback: WidgetTripLayout,
    private val worker: CoroutineDispatcher,
) {
    operator fun get(size: androidx.compose.ui.unit.DpSize): WidgetTripLayout = bySize[size] ?: fallback

    suspend fun including(size: androidx.compose.ui.unit.DpSize): WidgetTripLayouts =
        if (size in bySize) {
            this
        } else {
            withContext(worker) {
                WidgetTripLayouts(model, fontScale, bySize + (size to widgetTripLayout(model, size.width.value, size.height.value, fontScale)), fallback, worker)
            }
        }

    companion object {
        suspend fun of(
            model: WidgetTripModel,
            fontScale: Float,
            sizes: Collection<androidx.compose.ui.unit.DpSize>,
            min: androidx.compose.ui.unit.DpSize,
            worker: CoroutineDispatcher = Dispatchers.Default,
        ): WidgetTripLayouts = withContext(worker) {
            WidgetTripLayouts(
                model,
                fontScale,
                sizes.associateWith { widgetTripLayout(model, it.width.value, it.height.value, fontScale) },
                widgetTripLayout(model, min.width.value, min.height.value, fontScale),
                worker,
            )
        }
    }
}
