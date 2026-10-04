package app.stopdash.wear

import android.app.Activity
import android.os.Bundle
import androidx.annotation.WorkerThread
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import app.stopdash.data.WatchEnvelope
import app.stopdash.data.toDomain
import app.stopdash.domain.DepartureLabels
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.StarredRow
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.isStatusOnly
import app.stopdash.domain.lineCode
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A row the complication can be set to: its [code] and [destination] on one line, its [stopName] below. */
data class ComplicationChoice(val row: StarredRow, val code: String, val destination: String, val stopName: String)

/** The rows a complication can be set to, from the envelope. */
object ComplicationChoices {
    /**
     * Every row the widget shows at [now], in its order (starred first, hidden modes left out),
     * labeled as the tile labels them. The [current] pick is always offered while the widget would
     * still show it, even with no departures right now (the complication shows its empty form),
     * so the picker can mark it: first, labeled from the stop's line and the row's direction.
     */
    @WorkerThread
    fun of(envelope: WatchEnvelope?, now: Instant, current: StarredRow? = null): List<ComplicationChoice> {
        envelope ?: return emptyList()
        val stops = envelope.stops.map { it.toDomain() }
        val choices = ComplicationTimeline.widgetRows(envelope, now)
            // A suspension's status row stands for the warning, not a direction anyone can catch
            // a train in: it lasts only as long as its check, so it's never a pick to keep.
            .filterNot { it.isStatusOnly }
            .distinctBy { StarredRow.of(it) }
            .map { row ->
                ComplicationChoice(
                    row = StarredRow.of(row),
                    code = lineCode(row.lineName, row.mode, row.lineId),
                    destination = DepartureLabels.destinationLabel(row.destination, row.directionKey) ?: "—",
                    stopName = row.stopName,
                )
            }
        if (current == null || choices.any { it.row == current } || !ComplicationTimeline.shows(envelope, current, now)) return choices
        val stop = stops.first { it.stopId == current.stopId }
        val line = stop.lines.firstOrNull { it.id == current.lineId }
        val (name, mode) = line?.let { it.name to it.mode }
            ?: stop.departures.first { it.lineId == current.lineId }.let { it.lineName to it.mode }
        val quiet = ComplicationChoice(
            row = current,
            code = lineCode(name, mode, current.lineId),
            destination = DepartureLabels.destinationLabel("", current.directionKey) ?: "—",
            stopName = stop.stopName,
        )
        return listOf(quiet) + choices
    }

    /**
     * [of], worked out on [worker], never on the caller's thread: it folds every stop's rows
     * ([DepartureRows.glanceFolded]), which grows with the envelope (AGENTS.md *Main thread*).
     */
    suspend fun load(
        envelope: WatchEnvelope?,
        now: Instant,
        current: StarredRow? = null,
        worker: CoroutineDispatcher = Dispatchers.Default,
    ): List<ComplicationChoice> = withContext(worker) { of(envelope, now, current) }
}

/**
 * Picks the row a StopDash complication shows (opened from the watch face's complication editor).
 * Picking one saves it and syncs it to the phone ([ComplicationSelections]); "Top row" goes back to
 * the default. Renders from the stored envelope at once; the read runs off the main thread.
 */
class ComplicationConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(ComplicationDataSourceService.EXTRA_CONFIG_COMPLICATION_ID, -1)
        if (id == -1) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val store = WatchEnvelopeStore.from(this)
        // The current pick, read off the main thread with the envelope; unknown until then.
        val pick = MutableStateFlow<PickState>(PickState.Loading)
        // The watch's clock frame, worked out off the main thread before the envelope is read (it
        // reads a file and the system settings the first time), and handed to the list below, which
        // waits for it rather than work it out itself (Codex, PR #371).
        val frame = MutableStateFlow<FrameRead?>(null)
        lifecycleScope.launch(Dispatchers.IO) {
            frame.value = FrameRead(store.frameNow())
            store.load()
            pick.value = PickState.Loaded(ComplicationSelections.get(this@ComplicationConfigActivity, id))
            // Opened before anything was stored (a fresh install, cleared data): look up what the
            // phone already sent, retried while the picker is open; a newer envelope fills the list.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WatchSurfaces.lookUpRetrying(this@ComplicationConfigActivity, store)
            }
        }
        setContent {
            val received by store.state.collectAsStateWithLifecycle()
            val current by pick.collectAsStateWithLifecycle()
            val read by frame.collectAsStateWithLifecycle()
            // Worked out off the main thread; the picker shows at once with "Top row" and fills in.
            val choices by produceState(emptyList<ComplicationChoice>(), received, current, read) {
                val envelope = read?.let { (received as? WatchReceived.Received)?.current(it.frame) }
                value = ComplicationChoices.load(envelope, Instant.now(), (current as? PickState.Loaded)?.row)
            }
            ComplicationPickerScreen(choices, current) { row ->
                ComplicationSelections.set(this, id, row)
                StopDashComplicationService.requestUpdate(this)
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }
}

/** The watch's clock frame as read ([WatchEnvelopeStore.frameNow]); [frame] null where it can't be told. */
private class FrameRead(val frame: SteadyClock.Frame?)

/** The complication's current pick as the picker knows it: still loading, or loaded (null: the default). */
sealed interface PickState {
    data object Loading : PickState

    data class Loaded(val row: StarredRow?) : PickState
}

/**
 * The picker: "Top row" (the default), then each row, the [selected] one highlighted once known.
 * Nothing can be picked until the current pick has loaded, so a tap can't replace a pick unseen.
 */
@Composable
fun ComplicationPickerScreen(choices: List<ComplicationChoice>, selected: PickState, onPick: (StarredRow?) -> Unit) {
    val picked = (selected as? PickState.Loaded)
    val ready = picked != null
    MaterialTheme {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            item {
                Text(
                    text = stringResource(R.string.complication_pick_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                PickButton(
                    label = stringResource(R.string.complication_pick_default),
                    secondary = stringResource(R.string.complication_pick_default_detail),
                    chosen = picked != null && picked.row == null,
                    enabled = ready,
                ) { onPick(null) }
            }
            if (choices.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.complication_pick_none),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(choices) { choice ->
                PickButton(
                    label = stringResource(R.string.complication_pick_row, choice.code, choice.destination),
                    secondary = choice.stopName,
                    chosen = picked?.row == choice.row,
                    enabled = ready,
                ) { onPick(choice.row) }
            }
        }
    }
}

@Composable
private fun PickButton(label: String, secondary: String, chosen: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = if (chosen) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
