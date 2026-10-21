package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import app.stopdash.R
import app.stopdash.domain.Connections
import app.stopdash.domain.DepartureLabels
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteChoice
import app.stopdash.domain.RouteChoices
import app.stopdash.domain.RouteFocus
import app.stopdash.domain.RouteStops
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.eachCallingAt
import app.stopdash.domain.stopsOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * A route page's chips ([RouteChoices]) as the page shows them, worked out off the main thread: each choice's
 * [labels] and, for one with no train to follow, its stops from the line's routes ([lists], by
 * [RouteChoice.key]), which the page lists when it's chosen.
 */
internal class RouteChoicesUi private constructor(
    val choices: RouteChoices,
    val labels: List<String>,
    val lists: Map<String, RouteStopsUi.Loaded>,
    // Why the routes the choices come from couldn't be loaded, where it was a failure a retry may get past.
    val failure: DeparturesUiState.Error.Kind?,
    // Whether these were worked out from the page's rows as they are now: false while a refreshed row's are worked
    // out, when the ways stand, as a route's termini do, but each one's train ([RouteChoice.live]) is the old rows',
    // which a tap mustn't follow (Codex on #1038).
    val settled: Boolean,
    // Each choice's place by its key, so the page finds the one chosen without a walk.
    private val index: Map<String, Int>,
    /** Each choice's key, in their order: what keeps a chip itself as the row of them changes. */
    val keys: List<String>,
) {
    // Worked out here, where they're made, on the worker: an unsettled copy shares them ([unsettled]).
    constructor(
        choices: RouteChoices,
        labels: List<String>,
        lists: Map<String, RouteStopsUi.Loaded>,
        failure: DeparturesUiState.Error.Kind? = null,
    ) : this(
        choices, labels, lists, failure, settled = true,
        index = choices.choices.withIndex().associate { (i, choice) -> choice.key to i },
        keys = choices.choices.map { it.key },
    )

    /**
     * These choices from rows since refreshed: shown as they are, but none followed to its train ([settled]). Shares
     * their keys and index rather than walking the choices again, as it's made on the main thread (Codex on #1038).
     */
    fun unsettled(): RouteChoicesUi = RouteChoicesUi(choices, labels, lists, failure, settled = false, index, keys)

    /** The place of the choice [key] names, null where none does (it's gone since it was chosen). */
    fun indexOf(key: String?): Int? = key?.let { index[it] }

    /**
     * These choices with each way's stop list [held]'s own where it's the same: a refreshed row works the ways out
     * anew, and a list made again would lay out its map again, which took the map down and the page back to its
     * top every refresh on a way with no train (maintainer, 2026-10-21). Compared here, on the worker.
     */
    @WorkerThread
    fun keeping(held: RouteChoicesUi?): RouteChoicesUi {
        val before = held?.lists ?: return this
        if (lists.none { (key, list) -> before[key] == list }) return this
        return RouteChoicesUi(choices, labels, lists.mapValues { (key, list) -> before[key]?.takeIf { it == list } ?: list }, failure, settled, index, keys)
    }

    companion object {
        /** No chips: where no routes are wired, or TfL has none for the line. */
        val NONE = RouteChoicesUi(RouteChoices.NONE, emptyList(), emptyMap())

        /** No chips, as the routes couldn't be loaded: said, with a retry ([RouteChoicesFailed]). */
        fun failed(kind: DeparturesUiState.Error.Kind) = RouteChoicesUi(RouteChoices.NONE, emptyList(), emptyMap(), kind)

        /**
         * [row]'s line's choices at its stop from [sequence] (both ways), with the trains among [rows], [first]'s
         * way first and, within it, those calling at a stop of [reaching] ([RouteChoices.of]); [via] formats a choice
         * told apart by where it parts ("%1$s via %2$s"), and [atLeast] a train TfL names no destination for by how far
         * it goes at least, a guess ("At least to %1$s?", [RouteChoice.atLeast]).
         */
        @WorkerThread
        fun of(
            sequence: LineSequence,
            row: DepartureRow,
            rows: List<DepartureRow>,
            focus: RouteFocus?,
            topology: RouteTopology,
            via: String,
            first: String,
            atLeast: String = "%1\$s?",
            reaching: Set<String> = emptySet(),
        ): RouteChoicesUi {
            val choices = RouteChoices.of(sequence, row, rows, focus, topology, first, reaching)
            val routes = sequence.eachCallingAt(row.stopId)
            val lists = choices.choices.filter { it.path.isNotEmpty() }.associate { choice ->
                choice.key to RouteStopsUi.Loaded(
                    RouteStops.stopsOf(routes, choice.path, row.lineId),
                    choice.path.mapNotNull { id -> routes.stopPositions[id]?.let { id to it } }.toMap(),
                    routes,
                )
            }
            return RouteChoicesUi(choices, choices.choices.map { labelOf(it, row, via, atLeast, sequence) }, lists)
        }

        /**
         * The ways [ride] can be ridden on [sequence] ([RouteChoices.forRide]), each named by its via or where it
         * parts ([by]: "via %1$s"), as they all end where the ride does.
         */
        @WorkerThread
        fun forRide(sequence: LineSequence, ride: TripLeg, by: String): RouteChoicesUi {
            val choices = RouteChoices.forRide(sequence, ride)
            val routes = sequence.eachCallingAt(ride.fromId)
            val lists = choices.choices.associate { choice ->
                choice.key to RouteStopsUi.Loaded(
                    RouteStops.stopsOf(routes, choice.path, ride.lineId),
                    choice.path.mapNotNull { id -> routes.stopPositions[id]?.let { id to it } }.toMap(),
                    routes,
                )
            }
            val labels = choices.choices.map { choice ->
                val via = choice.branch.takeIf { choice.showBranch } ?: choice.parting
                if (via != null) by.format(via) else DepartureLabels.destinationLabel(choice.destination, "") ?: choice.destination
            }
            return RouteChoicesUi(choices, labels, lists)
        }

        // "Morden/Bank" where the via tells two ways to one place apart, as a card names the route; "Zulu via
        // Bravo" where only a stop does; "At least to Kennington?" for a train TfL names no destination for, a guess
        // marked as one, never its destination (TfL's own words stay on the page's title).
        private fun labelOf(choice: RouteChoice, row: DepartureRow, via: String, atLeast: String, sequence: LineSequence): String {
            choice.atLeast?.let { id -> sequence.stopNames[id]?.ifBlank { null } }?.let { return atLeast.format(it) }
            val place = DepartureLabels.destinationLabel(choice.destination, row.directionKey) ?: choice.destination
            val named = if (choice.showBranch && choice.branch != null) "$place/${choice.branch}" else place
            return choice.parting?.let { via.format(named, it) } ?: named
        }
    }
}

/**
 * Whether a route page on [row]'s line holds room for its chips before they're worked out, so they come in
 * without moving what's below (SPEC *hold still*): a rail line's stop has a way on in each direction, two
 * choices, all but at its ends. A bus pole is one way's, so it seldom has two, and a National Rail line has
 * none yet ([RouteChoices.of]).
 */
internal fun holdsRouteChoices(row: DepartureRow): Boolean {
    if (row.lineId.isBlank()) return false
    // The row's mode, else its line's known one: no walk of its trains in composition (AGENTS.md *Main thread*).
    val mode = row.mode.ifBlank { Connections.knownMode(row.lineId).orEmpty() }
    return Connections.isRail(mode, row.lineId) && !mode.equals("national-rail", ignoreCase = true)
}

/**
 * The route chips of [row]'s line at its stop ([RouteChoicesUi]), with the trains among [siblings] and the page's
 * [focus], [first]'s way first and, within it, those calling at a stop of [reaching] (a favorite ride's far end): from
 * the line's routes both ways ([LocalRouteStops], one day-long request per line), worked out on the worker ([LocalWorker]). Null until the first answer is in; [RouteChoicesUi.NONE] where no routes are wired or
 * TfL has none for the line; one carrying its [RouteChoicesUi.failure] where they couldn't be loaded (said by the
 * repository), asked again on [retry]. A refreshed row's answer replaces the last, which stands meanwhile, so the
 * chips never blink, but unsettled ([RouteChoicesUi.settled]): its trains are the old rows'.
 */
@Composable
internal fun rememberRouteChoices(
    row: DepartureRow,
    siblings: List<DepartureRow>,
    focus: RouteFocus?,
    first: String,
    retry: Int,
    reaching: Set<String> = emptySet(),
): RouteChoicesUi? {
    val repository = LocalRouteStops.current
    val worker = LocalWorker.current
    val topology = LocalRouteTopology.current
    val via = stringResource(R.string.route_choice_via)
    val atLeast = stringResource(R.string.route_choice_at_least)
    val ask = remember(row, focus, topology, ByIdentity(siblings), ByIdentity(reaching)) { Any() }
    if (repository == null || row.lineId.isBlank()) return RouteChoicesUi.NONE
    // Another stop or line starts with none; a refreshed row keeps the last answer until its own is in.
    return key(row.stopId, row.lineId, repository) {
        // Each answer with the ask it was worked out for, so one for rows since refreshed is known for what it is.
        val answer by produceState<Pair<Any, RouteChoicesUi>?>(null, ask, worker, via, atLeast, first, retry) {
            val held = value?.second
            value = ask to try {
                withContext(worker) {
                    RouteChoicesUi.of(repository.load(row.lineId, ""), row, siblings, focus, topology, via, first, atLeast, reaching)
                        .keeping(held)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                // TfL has no route for this line: no chips, and no retry that could ever find any.
                RouteChoicesUi.NONE
            } catch (e: TflException) {
                // Already logged (sanitized) by the repository: the failure is said, with a retry, never read as a line
                // with no other routes, nor left standing on chips whose trains are a since-refreshed row's (Codex on
                // #1038).
                RouteChoicesUi.failed(errorKindOf(e))
            }
        }
        // The unsettled copy made once for each answer and ask, not on every recomposition.
        remember(answer, ask) { answer?.let { (asked, choices) -> if (asked === ask) choices else choices.unsettled() } }
    }
}

/**
 * The ways a favorite [ride]'s page can list it by ([RouteChoicesUi.forRide]), from its line's routes both ways
 * ([LocalRouteStops]), worked out on the worker ([LocalWorker]): null until in, [RouteChoicesUi.NONE] where no
 * routes are wired or TfL has none, and one carrying its [RouteChoicesUi.failure] where they couldn't be loaded
 * (said by the repository), asked again on the stop list's [retry].
 */
@Composable
internal fun rememberRideChoices(ride: TripLeg, retry: Int): RouteChoicesUi? {
    val repository = LocalRouteStops.current ?: return RouteChoicesUi.NONE
    val worker = LocalWorker.current
    val by = stringResource(R.string.route_choice_by)
    return key(repository, ride, worker, by) {
        // Asked again with the page's stop list ([retry]) after a failure, as the routes it failed for may load now
        // (Codex on #1039); an answer in hand stands meanwhile.
        val answer by produceState<RouteChoicesUi?>(null, retry) {
            if (value != null && value?.failure == null) return@produceState
            value = try {
                withContext(worker) { RouteChoicesUi.forRide(repository.load(ride.lineId, ""), ride, by) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                // TfL has no route for this line: no ways to offer, and no retry that could ever find one.
                RouteChoicesUi.NONE
            } catch (e: TflException) {
                // Already logged (sanitized) by the repository: no chips, and why, so the page can offer its retry.
                RouteChoicesUi.failed(errorKindOf(e))
            }
        }
        answer
    }
}

/**
 * The route chips atop a route page (maintainer, 2026-10-21): one per way its line runs on from the stop, each
 * keyed by its [keys], the [selected] one the page's, a tap on another [onSelect]ing it. Scrolls sideways where they
 * don't fit, composing only those in view, as a line can run many ways from one stop.
 */
@Composable
internal fun RouteChips(
    labels: List<String>,
    keys: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().testTag("routeChips"),
    ) {
        itemsIndexed(labels, key = { index, _ -> keys[index] }) { index, label ->
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("routeChoice-$index"),
            )
        }
    }
}

/**
 * In the chips' place, where the routes they come from couldn't be loaded: said, with a [onRetry], so a transient
 * failure never reads as a line with no other way on (SPEC principle 2; Codex on #1038). In their slot
 * ([RouteChipsSlot]), so it's as tall as they are.
 */
@Composable
internal fun RouteChoicesFailed(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.route_choices_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(stringResource(R.string.route_stops_retry))
        }
    }
}

/**
 * The chips' slot atop a route page, whatever is in it ([RouteChips], [RouteChoicesFailed], or nothing while they're
 * worked out): one height for all three, the chips' own, so nothing below moves as one gives way to another (SPEC
 * *hold still*). That's a chip's touch height, Material's minimum interactive size, not the 32dp it's drawn at
 * (Codex on #1038).
 */
@Composable
internal fun RouteChipsSlot(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val touch = LocalMinimumInteractiveComponentSize.current
    val height = if (touch.isSpecified && touch > FilterChipDefaults.Height) touch else FilterChipDefaults.Height
    Box(modifier.fillMaxWidth().heightIn(min = height).testTag("routeChipsSlot"), contentAlignment = Alignment.CenterStart) {
        content()
    }
}
