package app.stopdash.ui

import androidx.annotation.WorkerThread
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
import app.stopdash.domain.Workers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
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
    // Where work over the cards and their stops runs (AGENTS.md *Main thread: read and dispatch only*);
    // [viewModelScope] only publishes it.
    private val compute: CoroutineDispatcher = Workers.compute,
) : ViewModel() {
    // The places offered as cards (null until the first [retain]) and where each tapped one stands,
    // in one flow, so a relocation's new places and the cards it closed are published together.
    private val _picked = MutableStateFlow(Picked(null, emptyMap()))

    /** The places offered as cards and where each tapped one stands. */
    internal val picked: StateFlow<Picked> = _picked.asStateFlow()

    // Where each tapped card stands, by card key; a card never tapped (or collapsed) is absent.
    private var loads: Map<String, FartherLoad>
        get() = _picked.value.loads
        set(value) {
            _picked.value = _picked.value.copy(loads = value)
        }

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
        if (loads[place.key] is FartherLoad.Open) {
            models[place.key]?.refresh()
            return
        }
        if (lookups[place.key]?.isActive == true) return
        loads = loads + (place.key to FartherLoad.Loading)
        lookups[place.key] = viewModelScope.launch {
            val stops = if (place.stops.isNotEmpty()) place.stops else try {
                withContext(io) { stationStops(place.stationId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("farther station lookup failed for ${place.stationId}: ${e::class.simpleName}")
                loads = loads + (place.key to FartherLoad.Failed)
                return@launch
            }
            if (stops.isEmpty()) {
                warn("farther station ${place.stationId} has no stops with departures")
                loads = loads + (place.key to FartherLoad.Failed)
                return@launch
            }
            // Measured from the latest fix: a relocation while this is worked out measures it again.
            val refs = withContext(compute) { stops.map { it.toStopRef() } }
            var measuredFrom: Coordinates
            var distances: Map<String, Double>
            do {
                measuredFrom = from ?: fix
                val at = measuredFrom
                distances = withContext(compute) { distancesFrom(stops, at) }
            } while ((from ?: fix) != measuredFrom)
            val store = ViewModelStore()
            stores[place.key] = store
            val model = ViewModelProvider.create(
                store,
                viewModelFactory { initializer { newModel(refs, distances) } },
            )[place.key, MainViewModel::class]
            models[place.key] = model
            loads = loads + (place.key to FartherLoad.Open(stops, distances, model.state))
        }
    }

    // Close [key]'s card: its model and departures go, and it reads as never tapped.
    private fun close(key: String) {
        lookups.remove(key)?.cancel()
        models.remove(key)
        stores.remove(key)?.clear()
        loads = loads - key
    }

    /**
     * Keep only the cards still offered ([places], re-picked after a relocation), re-measured from
     * the new fix [fix]; the rest collapse. Worked out on [compute] from the cards as they stand, and
     * applied if no newer fix has come since; one worked out from cards changed meanwhile (a lookup
     * landed, a card was tapped) is worked out again from them.
     */
    fun retain(places: List<CollapsedPlaces.Place>, fix: Coordinates) {
        from = fix
        val asked = ++retains
        viewModelScope.launch {
            while (true) {
                val cards = loads
                val plan = withContext(compute) { retainPlan(cards, places, fix) }
                if (asked != retains) return@launch
                if (loads !== cards) continue
                // Every key with a lookup or a model has a card, so closing by the cards closes them all.
                for (key in plan.closed) close(key)
                // The new places with the cards kept for them, in one update.
                _picked.value = Picked(places, plan.cards)
                // The card's model takes the new distances too: its rows hide terminating services
                // by where the rider is, and its far stops refresh less often.
                for ((key, distances) in plan.remeasured) models[key]?.remeasure(distances)
                for (place in plan.reopened) open(place, fix)
                return@launch
            }
        }
    }

    // How many fixes [retain] has taken: a plan for an older one isn't applied.
    private var retains = 0

    /** What [retain] does to [cards]: which close, which reopen on new poles, and the rest re-measured. */
    private class RetainPlan(
        val closed: List<String>,
        val reopened: List<CollapsedPlaces.Place>,
        val remeasured: List<Pair<String, Map<String, Double>>>,
        val cards: Map<String, FartherLoad>,
    )

    @WorkerThread
    private fun retainPlan(cards: Map<String, FartherLoad>, places: List<CollapsedPlaces.Place>, fix: Coordinates): RetainPlan {
        val byKey = places.associateBy { it.key }
        val closed = ArrayList<String>()
        val reopened = ArrayList<CollapsedPlaces.Place>()
        val remeasured = ArrayList<Pair<String, Map<String, Double>>>()
        val kept = LinkedHashMap<String, FartherLoad>()
        for ((key, load) in cards) {
            val place = byKey[key]
            when {
                place == null -> closed += key
                // An opened bus place whose poles changed (one left the nearby lookup's reach, another
                // came into it) is rebuilt from the new ones, so a departed pole isn't shown as current
                // and a new one isn't left out. A station's card looks its stops up by id: nothing to redo.
                load is FartherLoad.Open && place.stops.isNotEmpty() &&
                    load.stops.mapTo(HashSet()) { it.id } != place.stops.mapTo(HashSet()) { it.id } -> {
                    closed += key
                    reopened += place
                }
                load is FartherLoad.Open -> {
                    val distances = distancesFrom(load.stops, fix)
                    remeasured += key to distances
                    kept[key] = load.copy(distanceMeters = distances)
                }
                else -> kept[key] = load
            }
        }
        return RetainPlan(closed, reopened, remeasured, kept)
    }

    /** The departures model of [key]'s card, while it is open. */
    fun model(key: String): MainViewModel? = models[key]

    /** [picked] with each open card's stop ids and its model's state, worked out on [compute]. */
    private val opened: StateFlow<OpenedCards> = openedCards(picked, compute)
        .stateIn(viewModelScope, SharingStarted.Eagerly, OpenedCards.NONE)

    // The list this set's screen shows, with its merge ([shownWith]); one per list, kept across a rotation.
    private var shown: Pair<StateFlow<DeparturesUiState>, StateFlow<ShownFarther>>? = null

    /**
     * [list] with the open cards' departures merged in, and the cards' standing they were merged with
     * ([ShownFarther]), worked out on [compute] whenever either changes ([shownWithOpened]). The screen
     * reads only finished merges, so it never draws the list without the opened rows (which would
     * collapse them and lose the list's place) or with rows from a moment other than their cards' and
     * their failures'. Kept here, in the set's store, so a recreated screen draws the last one at once.
     * A call from composition reads or builds the flow, no more.
     */
    fun shownWith(list: StateFlow<DeparturesUiState>): StateFlow<ShownFarther> {
        shown?.let { (from, flow) -> if (from === list) return flow }
        // No card standing yet, so no rows to miss, until the first merge is in.
        val flow = shownWithOpened(list, opened, compute)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ShownFarther(list.value, null, emptyMap()))
        shown = list to flow
        return flow
    }

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

    @WorkerThread
    private fun distancesFrom(stops: List<StopLocation>, fix: Coordinates): Map<String, Double> =
        stops.associate { it.id to NearestStops.distanceMeters(fix.latitude, fix.longitude, it.latitude, it.longitude) }
}

/** The places offered as cards ([offered], null until first picked) and where each tapped one stands. */
internal data class Picked(val offered: List<CollapsedPlaces.Place>?, val loads: Map<String, FartherLoad>)

/**
 * The places offered as cards ([offered]), where every tapped card stands ([loads]), and the open
 * ones' stop ids and states ([cards]), published together, so a card's new standing (reopening on
 * new poles, say) never meets its old rows, nor a place no longer offered its rows.
 */
class OpenedCards(
    val offered: List<CollapsedPlaces.Place>?,
    val loads: Map<String, FartherLoad>,
    val cards: List<Pair<Set<String>, DeparturesUiState>>,
) {
    companion object {
        val NONE = OpenedCards(null, emptyMap(), emptyList())
    }
}

/**
 * The list as the screen shows it, its opened cards merged in ([state]), with the places offered as
 * cards ([offered], null until first picked) and those cards' standing ([loads]) it was merged with.
 */
class ShownFarther(val state: DeparturesUiState, val offered: List<CollapsedPlaces.Place>?, val loads: Map<String, FartherLoad>)

/**
 * [list] with [opened]'s departures merged in ([merge]), alongside the cards' standing, each change
 * worked out on [compute]: the merge walks every card's stops (AGENTS *Main thread*). A newer change
 * supersedes a merge still running, so the screen gets the latest whole picture, never a part one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun shownWithOpened(
    list: Flow<DeparturesUiState>,
    opened: Flow<OpenedCards>,
    compute: CoroutineDispatcher,
    merge: (DeparturesUiState.Loaded, List<Pair<Set<String>, DeparturesUiState>>) -> DeparturesUiState.Loaded = ::withOpenedFarther,
): Flow<ShownFarther> = combine(list, opened) { state, cards -> state to cards }
    .mapLatest { (state, cards) ->
        val merged = if (state is DeparturesUiState.Loaded && cards.cards.isNotEmpty()) merge(state, cards.cards) else state
        ShownFarther(merged, cards.offered, cards.loads)
    }
    .flowOn(compute)

/**
 * [cards] with the open ones' departures, each change worked out on [compute]: an open card's stop
 * ids are its [FartherLoad.Open.distanceMeters] keys, and its departures the model it was opened
 * with ([FartherLoad.Open.departures]), carried in the load itself, so a card and its rows are
 * always one snapshot — never a card read open against a model since closed or replaced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun openedCards(
    cards: Flow<Picked>,
    compute: CoroutineDispatcher,
): Flow<OpenedCards> = cards
    .flatMapLatest { (offered, loads) ->
        val each = loads.values.mapNotNull { load ->
            val open = load as? FartherLoad.Open ?: return@mapNotNull null
            val ids = open.distanceMeters.keys
            open.departures.map { ids to it }
        }
        if (each.isEmpty()) {
            flowOf(OpenedCards(offered, loads, emptyList()))
        } else {
            combine(each) { states -> OpenedCards(offered, loads, states.toList()) }
        }
    }
    .flowOn(compute)

/**
 * Where a farther station's card stands (SPEC *Finding stops → Farther stations*): its stops being
 * looked up, the lookup failed (tap to try again), or open, with its stops and each one's distance
 * from the fix; its departures come from its model ([FartherCardsViewModel.model]).
 */
sealed interface FartherLoad {
    data object Loading : FartherLoad
    data object Failed : FartherLoad
    data class Open(
        val stops: List<StopLocation>,
        val distanceMeters: Map<String, Double>,
        // The card's own departures model's state, published with the card so they never part.
        val departures: StateFlow<DeparturesUiState> = NO_DEPARTURES,
    ) : FartherLoad
}

// An open card built without a model (a preview): its departures read as still loading.
private val NO_DEPARTURES: StateFlow<DeparturesUiState> = MutableStateFlow(DeparturesUiState.Loading).asStateFlow()

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
