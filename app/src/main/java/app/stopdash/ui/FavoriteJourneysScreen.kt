package app.stopdash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.FavoriteJourneysStore
import app.stopdash.domain.JourneyPair
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationMatch
import app.stopdash.domain.TflException
import app.stopdash.domain.WidgetJourneysReport
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The favorite journeys as Settings lists them (SPEC *Journeys*): one at a time, as saved.
 * [journeys] is null while unreadable; [loaded] false before the store's first read.
 */
data class FavoriteJourneysUi(
    val journeys: List<FavoriteJourney>? = emptyList(),
    val loaded: Boolean = true,
    val writeFailed: Boolean = false,
    // A pair picked to add, as it stands (null when none is under way or left to say).
    val adding: JourneyAddNote? = null,
)

/**
 * A pair the rider picked to add ([FavoriteJourneyPicker]), named by its two stations as picked: being
 * looked up and saved, or why it wasn't added. Gone once it's saved, the list then showing it.
 */
sealed interface JourneyAddNote {
    val from: String
    val to: String

    data class Adding(override val from: String, override val to: String) : JourneyAddNote
    data class AlreadySaved(override val from: String, override val to: String) : JourneyAddNote
    data class NoDirectLine(override val from: String, override val to: String) : JourneyAddNote
    data class SameStation(override val from: String, override val to: String) : JourneyAddNote

    /** Looking the stations up failed, for [kind]. */
    data class LookupFailed(override val from: String, override val to: String, val kind: DeparturesUiState.Error.Kind) : JourneyAddNote

    /** Saving it failed (a disk error). */
    data class NotSaved(override val from: String, override val to: String) : JourneyAddNote
}

/**
 * Settings' Remove: unstars [journey] in [journeys], then drops its pins from the widget's stored
 * snapshot at once, from the journeys left starred (Codex on #589). The main screen otherwise
 * reports the unstar only when it is next composed, and every widget refresh in between keeps the
 * stored pins, so a journey starred by mistake stayed on the widget. Under [lock], the one the main
 * screen's own pin writes take, so the two don't interleave. A store that can't be read after the
 * removal leaves the pins for the main screen's next report. A failed removal throws to the caller; a failed unpin is only logged ([warn]).
 */
internal suspend fun removeFavoriteJourney(
    journey: FavoriteJourney,
    journeys: FavoriteJourneysStore,
    widget: SnapshotStore,
    lock: Mutex = WidgetJourneysWrites.lock,
    warn: (String) -> Unit = {},
    // The hop first (AGENTS.md *Main thread*): it's called from the process's main-thread scope, and
    // the keys it works out grow with the starred list (Codex on #589).
    worker: CoroutineDispatcher = Dispatchers.Default,
) = withContext(worker) {
    journeys.remove(journey)
    try {
        val left = journeys.journeys().first() ?: return@withContext
        lock.withLock {
            widget.updateWidgetJourneys(WidgetJourneysReport(left.mapTo(HashSet()) { it.key }, emptyList()), emptyList())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The journey is unstarred; only the prompt unpin is lost, and the main screen's next report
        // drops it. Not the removal's failure, so not said as one.
        warn("widget unpin after removal failed: ${e::class.simpleName}")
    }
}

/**
 * Adds the journey between two stations the rider picked, [from] then [to] ([FavoriteJourneyPicker]):
 * the stops under each looked up ([StationFinder.stationStops]), a line both serve picked
 * ([JourneyPair]), and the journey saved unless it already is. Null once saved; else why it wasn't,
 * said on the list. Off the main thread, the hop first (AGENTS.md *Main thread*).
 */
internal suspend fun addFavoriteJourneyPair(
    from: StationMatch,
    to: StationMatch,
    finder: StationFinder,
    journeys: FavoriteJourneysStore,
    warn: (String) -> Unit = {},
    worker: CoroutineDispatcher = Dispatchers.Default,
): JourneyAddNote? = withContext(worker) {
    val result = try {
        JourneyPair.resolve(from.name, finder.stationStops(from.id), to.name, finder.stationStops(to.id))
    } catch (e: TflException) {
        // Logged without the stations (SPEC *Privacy*).
        warn("favorite journey add lookup failed: ${e::class.simpleName}")
        return@withContext JourneyAddNote.LookupFailed(from.name, to.name, errorKindOf(e))
    }
    when (result) {
        JourneyPair.Result.SameStation -> JourneyAddNote.SameStation(from.name, to.name)
        JourneyPair.Result.NoDirectLine -> JourneyAddNote.NoDirectLine(from.name, to.name)
        is JourneyPair.Result.Found -> {
            val journey = result.journey
            try {
                // Unreadable (a newer StopDash's file): the store keeps that file rather than write over
                // it, so the journey wouldn't be saved; said, never passed off as added.
                val saved = journeys.journeys().first() ?: return@withContext JourneyAddNote.NotSaved(from.name, to.name)
                if (saved.any { it.key == journey.key }) {
                    return@withContext JourneyAddNote.AlreadySaved(from.name, to.name)
                }
                journeys.add(journey)
                null
            } catch (e: IOException) {
                warn("favorite journey add not saved: ${e::class.simpleName}")
                JourneyAddNote.NotSaved(from.name, to.name)
            }
        }
    }
}

/**
 * What became of the last pair picked to add, held for the process so it lands whatever the rider does
 * meanwhile (Back, a rotation), as [removeFavoriteJourney]'s failure is. Only the latest attempt's
 * outcome is said.
 */
internal object JourneyAdds {
    val note = MutableStateFlow<JourneyAddNote?>(null)
    private val latest = AtomicLong()

    fun attempt(scope: CoroutineScope, from: StationMatch, to: StationMatch, add: suspend () -> JourneyAddNote?): Job {
        val mine = latest.incrementAndGet()
        note.value = JourneyAddNote.Adding(from.name, to.name)
        return scope.launch {
            val outcome = add()
            if (latest.get() == mine) note.value = outcome
        }
    }

    fun dismiss() {
        latest.incrementAndGet()
        note.value = null
    }
}

/**
 * The favorite journeys, reached from Settings and hosted as an activity-level overlay like
 * [FavoritePlacesScreen], so its own Back closes it (maintainer, 2026-10-05: a journey starred by
 * mistake had no place to be seen and removed but its own card). Each row names the journey and its
 * line, with Remove. Add opens the station search: from there a line's route page, where a tapped
 * stop's page offers the journey there (a long press on the stop saves it at once).
 *
 * UI-only: it reflects [state] and reports a removal through [onRemove], so it stays
 * JVM/Robolectric-renderable for the screenshot test without a store.
 */
@Composable
fun FavoriteJourneysScreen(
    state: FavoriteJourneysUi,
    onBack: () -> Unit,
    onRemove: (FavoriteJourney) -> Unit,
    onDismissWriteError: () -> Unit = {},
    // Reads the store again after it couldn't be read (a disk error, or a newer StopDash's file).
    onRetry: () -> Unit = {},
    // Starts adding one: the station search, then a line's route page, where a tapped stop's page
    // offers the journey there (maintainer, 2026-10-06). Null shows no Add.
    onAdd: (() -> Unit)? = null,
    onDismissAddNote: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.favorite_journeys_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onAdd != null) {
                        TextButton(onClick = onAdd, modifier = Modifier.testTag("addJourney")) {
                            Text(stringResource(R.string.favorite_journeys_add))
                        }
                    }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    AppMenuOverflow()
                }
            }
            // Outside the scroll, so a failed removal says so wherever the list is scrolled.
            if (state.writeFailed) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.favorite_journeys_write_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    TextButton(onClick = onDismissWriteError, modifier = Modifier.testTag("dismissJourneyWriteError")) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            }
            state.adding?.let { AddNoteRow(it, onDismissAddNote) }
            // Lazy, so only the rows on screen are composed however many journeys are starred (Codex on #589).
            val listState = rememberLazyListState()
            val journeys = state.journeys
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollEdgeCue(listState, scrollCueColors(MaterialTheme.colorScheme.surface)),
            ) {
                when {
                    // Before the first read: the screen shows at once, with nothing to remove yet.
                    !state.loaded -> item { Note(stringResource(R.string.favorite_journeys_loading)) }
                    // Unreadable: a disk error or a newer schema, which the store doesn't tell apart. Said,
                    // not shown as none (principle 2), with Retry, since a disk error may pass (Codex on #589).
                    journeys == null -> item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
                                Note(stringResource(R.string.favorite_journeys_unavailable), error = true)
                            }
                            TextButton(
                                onClick = onRetry,
                                modifier = Modifier.padding(end = 8.dp).testTag("retryJourneys"),
                            ) { Text(stringResource(R.string.favorite_journeys_retry)) }
                        }
                    }
                    journeys.isEmpty() -> item { Note(stringResource(R.string.favorite_journeys_empty)) }
                    else -> items(journeys, key = { it.key }) { journey ->
                        JourneyRow(journey, onRemove = { onRemove(journey) })
                    }
                }
            }
        }
    }
}

// What became of a pair picked to add, outside the scroll like a failed removal, with Dismiss once settled.
@Composable
private fun AddNoteRow(note: JourneyAddNote, onDismiss: () -> Unit) {
    val text = when (note) {
        is JourneyAddNote.Adding -> stringResource(R.string.favorite_journeys_adding, note.from, note.to)
        is JourneyAddNote.AlreadySaved -> stringResource(R.string.favorite_journeys_add_already, note.from, note.to)
        is JourneyAddNote.NoDirectLine -> stringResource(R.string.favorite_journeys_add_no_line, note.from, note.to)
        is JourneyAddNote.SameStation -> stringResource(R.string.favorite_journeys_add_same)
        is JourneyAddNote.LookupFailed -> stringResource(R.string.favorite_journeys_add_failed, note.from, note.to, stringResource(partialReason(note.kind)))
        is JourneyAddNote.NotSaved -> stringResource(R.string.favorite_journeys_add_not_saved, note.from, note.to)
    }
    val error = note is JourneyAddNote.LookupFailed || note is JourneyAddNote.NotSaved
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("journeyAddNote"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (note !is JourneyAddNote.Adding) {
            Spacer(modifier = Modifier.width(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("dismissJourneyAddNote")) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun JourneyRow(journey: FavoriteJourney, onRemove: () -> Unit) {
    val spoken = stringResource(R.string.journey_title_spoken, journey.from.name, journey.to.name)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = stringResource(R.string.journey_title, journey.from.name, journey.to.name),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            journey.lineName.takeIf { it.isNotBlank() }?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // TalkBack names the journey, not just "Remove", so the right row's action is clear.
        val removeDescription = stringResource(R.string.favorite_journey_remove_description, journey.from.name, journey.to.name)
        TextButton(
            onClick = onRemove,
            modifier = Modifier
                .testTag("remove-${journey.key}")
                .semantics { contentDescription = removeDescription },
        ) { Text(stringResource(R.string.favorite_journey_remove)) }
    }
}
