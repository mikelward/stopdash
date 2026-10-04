package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FartherStations
import app.stopdash.domain.NearestStops
import app.stopdash.domain.StopLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The near-me list's opened farther stations (SPEC *Finding stops → Farther stations*): each card
 * the rider taps open gets its own departures model, as a *From…* page's station does, fetching
 * just that station and refreshed with the list. It is kept apart from the list's own model on
 * purpose: an opened station never joins the list's fetched set, its widget snapshot, its restore
 * after a restart, or its refresh warnings. A card closes when the list stops offering it (the
 * rider moved) or with this model, whose store is the nearby set's; nothing about it is saved.
 */
class FartherCardsViewModel(
    // Looks up a farther station's stops when its card is tapped.
    private val stationStops: suspend (String) -> List<StopLocation>,
    // Builds the departures model for an opened station's stops, with each one's distance from the
    // fix (the app's is a *From…* page's).
    private val newModel: (List<StopRef>, Map<String, Double>) -> MainViewModel,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val warn: (String) -> Unit = {},
) : ViewModel() {
    private val _cards = MutableStateFlow<Map<String, FartherLoad>>(emptyMap())

    /** Where each tapped card stands, by card key; a card never tapped (or collapsed) is absent. */
    val cards: StateFlow<Map<String, FartherLoad>> = _cards.asStateFlow()

    // Each opened card's model lives in its own store, cleared when the card closes.
    private val stores = HashMap<String, ViewModelStore>()
    private val models = HashMap<String, MainViewModel>()
    private val lookups = HashMap<String, Job>()

    // The latest fix: a lookup finishing after a relocation measures from it, not from its tap's.
    private var from: Coordinates? = null

    /**
     * Open [place]'s card: look up its station's stops (a bus place carries its own, found by the
     * nearby lookup), then give them their own departures model. A tap on a card that is open
     * already retries its fetch; on a failed one, the lookup.
     */
    fun open(place: CollapsedPlaces.Place, fix: Coordinates) {
        from = fix
        if (_cards.value[place.key] is FartherLoad.Open) {
            models[place.key]?.refresh()
            return
        }
        if (lookups[place.key]?.isActive == true) return
        _cards.value = _cards.value + (place.key to FartherLoad.Loading)
        lookups[place.key] = viewModelScope.launch {
            val stops = if (place.stops.isNotEmpty()) place.stops else try {
                withContext(io) { stationStops(place.stationId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("farther station lookup failed for ${place.stationId}: ${e::class.simpleName}")
                _cards.value = _cards.value + (place.key to FartherLoad.Failed)
                return@launch
            }
            if (stops.isEmpty()) {
                warn("farther station ${place.stationId} has no stops with departures")
                _cards.value = _cards.value + (place.key to FartherLoad.Failed)
                return@launch
            }
            val store = ViewModelStore()
            stores[place.key] = store
            val refs = stops.map { it.toStopRef() }
            val distances = distancesFrom(stops, from ?: fix)
            val model = ViewModelProvider.create(
                store,
                viewModelFactory { initializer { newModel(refs, distances) } },
            )[place.key, MainViewModel::class]
            models[place.key] = model
            _cards.value = _cards.value + (place.key to FartherLoad.Open(stops, distances))
        }
    }

    // Close [key]'s card: its model and departures go, and it reads as never tapped.
    private fun close(key: String) {
        lookups.remove(key)?.cancel()
        models.remove(key)
        stores.remove(key)?.clear()
        _cards.value = _cards.value - key
    }

    /**
     * Keep only the cards still offered ([places], re-picked after a relocation), re-measured from
     * the new fix [fix]; the rest collapse.
     */
    fun retain(places: List<CollapsedPlaces.Place>, fix: Coordinates) {
        from = fix
        val byKey = places.associateBy { it.key }
        for (key in _cards.value.keys + lookups.keys) if (key !in byKey) close(key)
        // An opened bus place whose poles changed (one left the nearby lookup's reach, another came
        // into it) is rebuilt from the new ones, so a departed pole isn't shown as current and a new
        // one isn't left out. A station's card looks its stops up by id, so it has nothing to redo.
        for ((key, load) in _cards.value) {
            val place = byKey[key] ?: continue
            if (place.stops.isEmpty() || load !is FartherLoad.Open) continue
            if (load.stops.mapTo(HashSet()) { it.id } != place.stops.mapTo(HashSet()) { it.id }) {
                close(key)
                open(place, fix)
            }
        }
        _cards.value = _cards.value.mapValues { (key, load) ->
            if (load is FartherLoad.Open) {
                // The card's model takes the new distances too: its rows hide terminating services
                // by where the rider is, and its far stops refresh less often.
                val distances = distancesFrom(load.stops, fix)
                models[key]?.remeasure(distances)
                load.copy(distanceMeters = distances)
            } else {
                load
            }
        }
    }

    /** The departures model of [key]'s card, while it is open. */
    fun model(key: String): MainViewModel? = models[key]

    // The list as last shown with its opened cards merged in ([rememberWithOpenedFarther]), and what
    // it was merged from: the screen recreated (a rotation) shows it at once while it's still current.
    internal var lastMerged: MergedFarther? = null

    /** Refresh every opened card's departures, as the list refreshes its own. */
    fun refresh() {
        for (model in models.values) model.refresh()
    }

    /** Makes every opened card's next refresh ask for each stop afresh, as a pull does the list's. */
    fun forceNextFetch() {
        for (model in models.values) model.forceNextFetch()
    }

    override fun onCleared() {
        for (store in stores.values) store.clear()
        stores.clear()
        models.clear()
    }

    private fun distancesFrom(stops: List<StopLocation>, fix: Coordinates): Map<String, Double> =
        stops.associate { it.id to NearestStops.distanceMeters(fix.latitude, fix.longitude, it.latitude, it.longitude) }
}

/**
 * Where a farther station's card stands (SPEC *Finding stops → Farther stations*): its stops being
 * looked up, the lookup failed (tap to try again), or open, with its stops and each one's distance
 * from the fix; its departures come from its model ([FartherCardsViewModel.model]).
 */
sealed interface FartherLoad {
    data object Loading : FartherLoad
    data object Failed : FartherLoad
    data class Open(val stops: List<StopLocation>, val distanceMeters: Map<String, Double>) : FartherLoad
}

/**
 * [list] with each opened farther card's departures added (SPEC *Finding stops → Farther
 * stations*), so the screen renders them through the list's own rows, grouping and dedupe. Each of
 * [opened] is a card's stop ids and its model's state: loaded stops join the list (a stop the list
 * has already keeps the list's copy), with their line statuses where the list has none; a failed
 * card marks its stops unavailable, so its card offers a retry; a loading one adds nothing yet.
 * The list keeps its own stamp and failure, but a card whose last refresh failed or came back
 * incomplete marks the list partial (SPEC principle 2) where that survives the merge: a stop of
 * its shown from an older copy, or shown by nothing, is older than the stamp says, so the screen
 * says some stops couldn't refresh. A stop the list shows is the list's to count, and one another
 * card shows fresh is fresh. A card whose disruptions are unknown marks its own stops so.
 */
internal fun withOpenedFarther(
    list: DeparturesUiState.Loaded,
    opened: List<Pair<Set<String>, DeparturesUiState>>,
): DeparturesUiState.Loaded {
    if (opened.isEmpty()) return list
    val listIds = list.stops.mapTo(HashSet()) { it.stopId }
    val ids = HashSet(listIds)
    val stops = list.stops.toMutableList()
    var lineStatuses = list.lineStatuses
    val determined = list.determinedLineIds.toHashSet()
    val disruptionUnknown = list.stopsDisruptionUnknown.toHashSet()
    // A card's stops shown while their own closure check is still out, as the list's own are.
    val closurePending = list.closurePending.toHashSet()
    val unavailable = list.unavailableStopIds.toHashSet()
    // Lines whose check is still out, from the list and each card still loading: only a row on one reads
    // "checking". A finished source has none, so a card loading doesn't make its rows read "checking".
    val pendingLines = (if (list.statusPending) list.pendingLineIds else emptySet()).toHashSet()
    // The stop ids of each card whose last refresh failed or came back incomplete.
    val failedCards = ArrayList<Set<String>>()
    // A card part-shown by its own cold load. With its line status not all checked yet, the list says
    // it's still checking, as it would for its own (SPEC *Freshness → Cold load*).
    var cardStatusPending = false
    var cardStillChecking = false
    // A card still loading whose check already failed: the banner says it couldn't check.
    var cardCheckFailed = false
    val openedLoading = HashSet<String>()
    // Stops of the list's that a card shows fresh, so the banner stops naming them as failed.
    val freshFromCards = HashSet<String>()
    for ((cardIds, state) in opened) {
        when (state) {
            is DeparturesUiState.Loaded -> {
                val added = state.stops.filter { ids.add(it.stopId) }
                stops += added
                if (state.refreshFailure == null) added.filter { it.arrivalsFresh }.mapTo(freshFromCards) { it.stopId }
                lineStatuses = state.lineStatuses + lineStatuses
                determined += state.determinedLineIds
                disruptionUnknown += state.stopsDisruptionUnknown
                // Only for the rows the card adds: a stop the list already shows keeps the list's own mark.
                added.filter { it.stopId in state.closurePending }.mapTo(closurePending) { it.stopId }
                if (state.disruptionUnknown) added.mapTo(disruptionUnknown) { it.stopId }
                unavailable += state.unavailableStopIds
                // Only the lines of the rows the card adds: a stop the list already shows keeps the
                // list's own verdict on its lines.
                if (state.statusPending && state.pendingLineIds.isNotEmpty()) {
                    for (stop in added) {
                        stop.departures.forEach { if (it.lineId in state.pendingLineIds) pendingLines += it.lineId }
                        stop.lines.forEach { if (it.id in state.pendingLineIds) pendingLines += it.id }
                    }
                }
                if (state.partialRefresh || state.refreshFailure != null) failedCards += cardIds
                if (state.statusPending) {
                    cardStatusPending = true
                    if (state.disruptionUnknown) cardStillChecking = true
                    if (state.checkFailed) cardCheckFailed = true
                    openedLoading += cardIds
                }
            }
            is DeparturesUiState.Error -> unavailable += cardIds - ids
            else -> Unit
        }
    }
    // The banner names only the list's own failed stops (less any a card now shows fresh). A card's
    // failures aren't named: a card that failed makes the banner generic, "Some stops couldn't be
    // refreshed", rather than risk naming the wrong stops or reason from a card's partial state.
    val listNamed = list.partialStops.filterKeys { it !in freshFromCards }
    // Partial only for named stops a card has since shown fresh, the list isn't any more.
    val listPartial = list.partialRefresh &&
        (list.partialUnnamed || list.partialStops.isEmpty() || listNamed.isNotEmpty())
    // A failed card's stop shown from an older copy (its own, or a failed card's), or by nothing.
    val cardPartial = failedCards.any { cardIds -> cardIds.any { it !in listIds && it !in freshFromCards } }
    val partial = listPartial || cardPartial
    return list.copy(
        stops = stops,
        partialRefresh = partial,
        partialStops = if (listPartial && !cardPartial) listNamed else emptyMap(),
        partialUnnamed = listPartial && !cardPartial && list.partialUnnamed,
        lineStatuses = lineStatuses,
        determinedLineIds = determined,
        stopsDisruptionUnknown = disruptionUnknown,
        closurePending = closurePending,
        unavailableStopIds = unavailable,
        disruptionUnknown = list.disruptionUnknown || cardStillChecking,
        statusPending = list.statusPending || cardStatusPending,
        // The list's own finished "couldn't check" stays that, not "checking", while a card loads.
        checkFailed = list.checkFailed || cardCheckFailed || (list.disruptionUnknown && !list.statusPending),
        openedLoadingStopIds = openedLoading,
        pendingLineIds = pendingLines - determined,
    )
}

/** [merged], the list [list] with [opened]'s cards merged in by [withOpenedFarther]. */
/** Farther cards ([places]) as picked for the nearby set [key]. */
internal class FartherFor(val key: String, val places: List<CollapsedPlaces.Place>) {
    /** The cards if they were picked for [set], else null: another set's aren't this one's. */
    fun forSet(set: String): List<CollapsedPlaces.Place>? = places.takeIf { key == set }
}

internal class MergedFarther(
    val list: DeparturesUiState.Loaded,
    val opened: List<Pair<Set<String>, DeparturesUiState>>,
    val merged: DeparturesUiState.Loaded,
) {
    /** Merged from these very states. */
    fun isOf(list: DeparturesUiState.Loaded, opened: List<Pair<Set<String>, DeparturesUiState>>): Boolean =
        sameStates(this.list, this.opened, list, opened)
}

/** The same states and cards' stop sets, by identity, so the check walks neither their stops nor the sets. */
private fun sameStates(
    list: DeparturesUiState.Loaded,
    opened: List<Pair<Set<String>, DeparturesUiState>>,
    otherList: DeparturesUiState.Loaded,
    otherOpened: List<Pair<Set<String>, DeparturesUiState>>,
): Boolean = list === otherList && opened.size == otherOpened.size &&
    opened.indices.all { opened[it].first === otherOpened[it].first && opened[it].second === otherOpened[it].second }

/**
 * [state] with each opened card's stops shown through the list ([withOpenedFarther]), merged on
 * [LocalWorker], not in composition: the merge walks every card's stops (AGENTS *Main thread*).
 * Until it's back, [cached] if merged from the same states (the screen recreated by a rotation),
 * else the list alone, shows. Each merge is handed to [onMerged] to keep.
 */
@Composable
internal fun rememberWithOpenedFarther(
    state: DeparturesUiState,
    opened: List<Pair<Set<String>, DeparturesUiState>>,
    cached: MergedFarther?,
    onMerged: (MergedFarther) -> Unit,
    merge: (DeparturesUiState.Loaded, List<Pair<Set<String>, DeparturesUiState>>) -> DeparturesUiState.Loaded = ::withOpenedFarther,
): DeparturesUiState {
    val list = state as? DeparturesUiState.Loaded ?: return state
    if (opened.isEmpty()) return list
    val worker = LocalWorker.current
    // Keyed by identity, so a recomposition doesn't compare the states' stops to see if they changed.
    val key = MergeKey(list, opened)
    // Tagged with the states it merged, so a new list (another set's, say) shows alone until its own
    // merge is back rather than the last one's.
    val merged by produceState(initialValue = cached?.takeIf { it.isOf(list, opened) }, key) {
        val done = MergedFarther(list, opened, withContext(worker) { merge(list, opened) })
        onMerged(done)
        value = done
    }
    return merged?.takeIf { it.isOf(list, opened) }?.merged ?: list
}

/** The merge's inputs, equal only to the same ones ([sameStates]). */
private class MergeKey(val list: DeparturesUiState.Loaded, val opened: List<Pair<Set<String>, DeparturesUiState>>) {
    override fun equals(other: Any?): Boolean =
        other is MergeKey && sameStates(list, opened, other.list, other.opened)

    override fun hashCode(): Int = System.identityHashCode(list)
}

/**
 * The stops [state] counts as reached for [fartherReached]: those it has departures for, plus, while
 * a cold load is part-shown, the ones still loading — they're about to land, so their lines don't
 * flash up a farther card that then vanishes (and close one opened meanwhile). Null while nothing
 * has loaded yet (everything shown counts).
 */
internal fun reachedStopIds(state: DeparturesUiState): Set<String>? {
    val loaded = state as? DeparturesUiState.Loaded ?: return null
    val ids = loaded.stops.mapTo(HashSet()) { it.stopId }
    if (loaded.statusPending) loaded.pendingStops.mapTo(ids) { it.id }
    return ids
}

/**
 * The near-me stops the farther-station cards count as already reached: of [shown], what the list
 * loads ([MainViewModel.shownNearStops]),
 * those in [loadedIds] — the stops the loaded list actually has departures for — or all of them
 * while it is still loading (null), so cards don't flash up and vanish on the first load. Counting
 * an unloaded stop's lines, or those of one whose fetch failed, would leave a line with no row and
 * no card; a loaded one left out would get a duplicate card.
 */
internal fun fartherReached(shown: List<StopRef>, loadedIds: Set<String>? = null): List<FartherStations.ReachedStop> =
    shown.filter { loadedIds == null || it.id in loadedIds }.map { stop ->
        FartherStations.ReachedStop(
            ids = setOf(stop.id, stop.clusterId, stop.hubId).filterTo(HashSet()) { it.isNotBlank() },
            lines = stop.lines.mapTo(HashSet()) { FartherStations.Line(it.mode.lowercase(), it.id) },
        )
    }

/**
 * The [FartherCardsViewModel] key for a list, from its stores key (null for near me): distinct per
 * list, so a From… station page's opened cards never share state with near me's.
 */
internal fun fartherCardsKey(storesKey: String?): String = if (storesKey == null) "farther" else "farther|$storesKey"
