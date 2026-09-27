package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.Coordinates
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FixedLocation
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceHits
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StationMatch
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import app.stopdash.domain.UkPostcode
import app.stopdash.domain.YourStops
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Find a station" search (SPEC *Finding stops*): the typed [State.query] and TfL's matches for it.
 * A search runs [debounceMillis] after the last keystroke, and only for a query of at least
 * [MIN_QUERY_LENGTH] characters, so typing a name costs one request, not one per letter — the
 * keyless budget is ~50 req/min. A newer query cancels the one in flight. While a search runs, the
 * previous result stays on screen with [State.searching] set, rather than blanking on each letter.
 *
 * The query is the user's own words and may name where they live, so it is sent to TfL and nowhere
 * else, and never logged (SPEC *Privacy*); a failure logs only its kind.
 *
 * Before anything is typed it lists the user's own stops — [State.favorites] and [State.recent]
 * opens — and as they type those stops, and the ones lately shown near them, match on the device
 * with the bundled stations ([YourStops]), none of it sent anywhere.
 */
class StationSearchViewModel(
    private val finder: StationFinder,
    // Keeps the typed query across process death, alongside the activity's saved navigation, so a
    // restored search (or Back from a restored station) finds the query and searches it again. The
    // saved state stays on the device.
    private val savedState: SavedStateHandle = SavedStateHandle(),
    // The bundled station index, searched on the device as the user types (read off the main
    // thread, once). Empty by default, which leaves the search on TfL's matching alone.
    loadIndex: suspend () -> StationIndex = { StationIndex.EMPTY },
    // The user's own stops, read from the device each time the search opens ([refreshYours]). The
    // loader handles its own read failures: whatever it can't read is simply not listed.
    private val loadYours: suspend () -> YourStops = { YourStops.EMPTY },
    // The user's saved favorite places (SPEC D9), read from the device each time the search opens.
    // Only a To… picker offers them (they're trip destinations, not stops to browse), so the default
    // is none. Null means the read couldn't complete (as opposed to an empty list = genuinely none),
    // so the picker says so honestly and offers a retry rather than pretending there are none.
    private val loadPlaces: suspend () -> List<FavoritePlace>? = { emptyList() },
    // Geocodes the typed query to candidate places, so a To… search offers arbitrary destinations
    // (a landmark, an address, a postcode) alongside stops (SPEC *Find a station*). Only a To… picker
    // supplies it — the default searches no places — and it's additive: a geocode failure yields none
    // and the stops still stand.
    private val searchPlaces: suspend (String) -> List<PlaceCandidate> = { emptyList() },
    // Remembers a station opened from the search, for the recent list; blocking, run on [io].
    private val recordOpen: suspend (StationMatch) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val warn: (String) -> Unit = {},
) : ViewModel() {
    data class State(
        val query: String = "",
        val result: Result = Result.Idle,
        val searching: Boolean = false,
        // The user's starred and recently opened stops, listed before anything is typed; unread
        // (false) until the first read lands, so the screen doesn't flash its prompt first.
        val favorites: List<StationMatch> = emptyList(),
        val recent: List<StationMatch> = emptyList(),
        // The user's saved favorite places, offered at the top of a To… picker so they can route to
        // one without typing (SPEC D9). Empty outside a To… picker, which passes no [onOpenPlace].
        val favoritePlaces: List<FavoritePlace> = emptyList(),
        // The last places read couldn't complete (transient I/O, or a newer-schema file this build
        // can't read): the picker shows an honest, retryable notice instead of silently implying there
        // are none (SPEC principle 2). A discarded corrupt file reads as genuinely empty, not failed.
        val favoritePlacesFailed: Boolean = false,
        val yoursRead: Boolean = false,
    )

    sealed interface Result {
        /** Nothing searched yet: the query is too short. */
        data object Idle : Result
        /**
         * [remoteFailure] is set when TfL's search failed but the bundled index still matched: its
         * matches stand, and the screen says TfL's (bus stops) couldn't be added, with a retry.
         */
        data class Matches(
            val matches: List<StationMatch>,
            // Geocoded places for the query (SPEC *Find a station*), shown after the stops in a To…
            // picker with a Place/Postcode tag. Empty for a plain station search, which geocodes none.
            val places: List<PlaceHit> = emptyList(),
            val remoteFailure: DeparturesUiState.Error.Kind? = null,
        ) : Result
        /** TfL answered, with no match. */
        data object NoMatches : Result
        data class Failed(val kind: DeparturesUiState.Error.Kind) : Result
    }

    private val _state = MutableStateFlow(State(query = savedState.get<String>(KEY_QUERY).orEmpty()))
    val state: StateFlow<State> = _state.asStateFlow()

    private var search: Job? = null

    // TfL's answer behind the matches on screen, for the query it answered: an open re-ranks it
    // rather than asking again. Dropped as each search starts, so a failed one never borrows it.
    private var remoteFor: Pair<String, List<StationMatch>>? = null

    // Loaded once, on first use, so opening the search never waits on the asset read.
    private val index = viewModelScope.async(io, start = CoroutineStart.LAZY) { loadIndex() }

    // Which read is the latest: an older one that lands after a newer one started is ignored, so a
    // star changed between the two can't be overwritten by the stale list. Declared before [yours],
    // whose initializer starts the first read.
    @Volatile
    private var yoursGeneration = 0

    private var yours: Deferred<YourStops> = readYours()

    init {
        // A restored query is searched again at once; a fresh one is empty and searches nothing.
        start(_state.value.query, debounce = false)
    }

    fun onQueryChange(query: String) {
        savedState[KEY_QUERY] = query
        _state.update { it.copy(query = query) }
        start(query, debounce = true)
    }

    /**
     * Forget the search when it closes: cancel a pending request and drop the query and matches
     * (and the saved copy), so reopening starts blank and nothing typed outlives the screen.
     */
    fun clear() {
        search?.cancel()
        savedState.remove<String>(KEY_QUERY)
        remoteFor = null
        _state.update {
            State(
                favorites = it.favorites,
                recent = it.recent,
                favoritePlaces = it.favoritePlaces,
                favoritePlacesFailed = it.favoritePlacesFailed,
                yoursRead = it.yoursRead,
            )
        }
    }

    /**
     * Read the user's own stops again — when the search opens, since a star may have changed since
     * it last did. The typed search picks the new read up from its next letter.
     */
    fun refreshYours() {
        yours = readYours()
    }

    /**
     * Remember [match] as opened, for the recent list; the write finishes even if the search closes.
     * The matches still up are ranked again, so on Back the match just opened leads its tier.
     */
    fun onOpened(match: StationMatch) {
        viewModelScope.launch {
            withContext(NonCancellable + io) { recordOpen(match) }
            refreshYours()
            rerank()
        }
    }

    // The matches on screen ranked again with the user's stops as now read — from the bundled index
    // and TfL's answer behind them, with no new request, so Back keeps what it showed. A search still
    // running re-ranks when it lands instead ([start]).
    private fun rerank() {
        val current = _state.value
        val trimmed = current.query.trim()
        if (current.searching || current.result !is Result.Matches) return
        val remote = remoteFor?.takeIf { it.first == trimmed }?.second
        if (remote == null && current.result.remoteFailure == null) return
        search = viewModelScope.launch {
            val stations = withContext(io) { index.await().withYours(yours.await()) }
            val local = withContext(io) { stations.search(trimmed) }
            val shown = _state.value.result as? Result.Matches ?: return@launch
            // Re-ranks the stops with the newly-read stops; the geocoded places are unchanged, so keep
            // the ones already shown rather than dropping them or re-geocoding.
            val result = if (remote != null) {
                Result.Matches(stations.rank(trimmed, local, remote), places = shown.places)
            } else {
                Result.Matches(local, places = shown.places, remoteFailure = shown.remoteFailure)
            }
            if (result.matches.isNotEmpty() || result.places.isNotEmpty()) _state.update { it.copy(result = result) }
        }
    }

    private fun readYours(): Deferred<YourStops> {
        val generation = ++yoursGeneration
        return viewModelScope.async(io) {
            // A star the device couldn't name may be a listed station: name it from the bundled list.
            val loaded = loadYours()
            val named = if (loaded.unnamedStarred.isEmpty()) loaded else loaded.namedFrom(index.await())
            // Read alongside the stops so the To… picker shows both from the same open; independent, so
            // a places read failure never drops the stops. Null = couldn't read (a retryable notice),
            // distinct from an empty list (genuinely no saved places).
            val places = loadPlaces()
            named.also { read ->
                if (generation == yoursGeneration) {
                    _state.update {
                        it.copy(
                            favorites = read.favorites,
                            recent = read.recent,
                            favoritePlaces = places.orEmpty(),
                            favoritePlacesFailed = places == null,
                            yoursRead = true,
                        )
                    }
                }
            }
        }
    }

    // A geocoded result's tag: a complete postcode query resolves to a location (Postcode); anything
    // else is a named place, landmark or address (Place).
    private fun placeKindOf(query: String): PlaceKind =
        if (UkPostcode.isComplete(query)) PlaceKind.POSTCODE else PlaceKind.PLACE

    /** Search the current query again now — the Retry after a failure. */
    fun retry() = start(_state.value.query, debounce = false)

    private fun start(query: String, debounce: Boolean) {
        search?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            _state.update { it.copy(result = Result.Idle, searching = false) }
            return
        }
        // Marked searching at once, not after the pause: the matches on screen are the previous
        // query's until the new answer lands, and the progress bar says so rather than letting them
        // read as the answer to what's now typed.
        _state.update { it.copy(searching = true) }
        remoteFor = null
        search = viewModelScope.launch {
            // The bundled index answers at once — an abbreviation or code finds its station before
            // the typing pause is over. TfL's search (for what the index doesn't hold, like bus
            // stops) follows the pause, and the two are merged and ranked together.
            val bundled = index.await()
            val read = yours
            val own = read.await()
            val stations = withContext(io) { bundled.withYours(own) }
            val local = withContext(io) { stations.search(trimmed) }
            if (local.isNotEmpty()) _state.update { it.copy(result = Result.Matches(local), searching = true) }
            if (debounce) delay(debounceMillis)
            // Geocode places in parallel with the stop search — a separate, heavier TfL call (SPEC *Cost*)
            // that only a To… picker makes. Best-effort: its failure yields no places, never fails the
            // stop search, so it's caught here rather than in the shared catch below.
            val placesDeferred = async(io) {
                try {
                    PlaceHits.rank(trimmed, searchPlaces(trimmed), placeKindOf(trimmed))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("place search failed: ${e.message}")
                    emptyList()
                }
            }
            // Resolve the stop search and publish it at once — the geocode is heavier and best-effort, so
            // it must not hold the stops back: a bus-stop query whose stops are ready would otherwise sit
            // on the progress state until the Planner answers or times out (Codex).
            var failureKind: DeparturesUiState.Error.Kind? = null
            val stops: Result.Matches? = try {
                val remote = withContext(io) { finder.searchStations(trimmed) }
                remoteFor = trimmed to remote
                Result.Matches(stations.rank(trimmed, local, remote))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                warn("station search failed: ${e.message}")
                failureKind = errorKindOf(e)
                // No local stops to stand on → let the places below decide error vs place-only; otherwise
                // show the local stops now, with the failure noted so the "bus stops not searched" line
                // still tells the rider the stop results are incomplete (SPEC principle 2).
                if (local.isEmpty()) null else Result.Matches(local, remoteFailure = failureKind)
            }
            if (stops != null && stops.matches.isNotEmpty()) _state.update { it.copy(result = stops) }
            // Fold in the geocoded places (or none) — the stops are already on screen.
            val places = placesDeferred.await()
            val result: Result = when {
                stops != null && (stops.matches.isNotEmpty() || places.isNotEmpty()) -> stops.copy(places = places)
                stops != null -> Result.NoMatches
                places.isNotEmpty() -> Result.Matches(emptyList(), places = places, remoteFailure = failureKind)
                failureKind != null -> Result.Failed(failureKind)
                else -> Result.NoMatches
            }
            _state.update { it.copy(result = result, searching = false) }
            // The user's stops were read again meanwhile (a match opened mid-search): rank with that.
            if (yours !== read) rerank()
        }
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val DEBOUNCE_MILLIS = 300L
        private const val KEY_QUERY = "query"
    }
}

/**
 * The stops behind one searched station (SPEC *Finding stops*): resolved once from TfL when the
 * station opens, then handed to a departures view as its seed. [retry] resolves again after a failure.
 */
class StationStopsViewModel(
    private val finder: StationFinder,
    private val stationId: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val warn: (String) -> Unit = {},
    // A To… destination: the stops around the station's center, taken in with its own
    // ([DirectTrips.destinationStops]) so the buses at its door count as arriving there. Null for
    // a From… station, whose page finds its surroundings itself.
    private val around: (suspend (Coordinates) -> List<StopLocation>)? = null,
) : ViewModel() {
    sealed interface State {
        data object Loading : State
        /** The station's stops, and its [center] (null when TfL placed none) — where From… stands. */
        data class Ready(val stops: List<StopRef>, val center: Coordinates? = null) : State
        /** TfL knows the station but nothing under it carries departures stopdash shows. */
        data object NoStops : State
        data class Failed(val kind: DeparturesUiState.Error.Kind) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private var load: Job? = null

    init {
        retry()
    }

    fun retry() {
        load?.cancel()
        _state.value = State.Loading
        load = viewModelScope.launch {
            _state.value = try {
                val own = withContext(io) { finder.stationStops(stationId) }
                val center = FixedLocation.centerOf(own)
                // Looked up with the station, so a destination is never shown narrower than it is
                // (a failure fails the whole lookup, with Retry, rather than quietly dropping buses).
                val stops = if (around != null && center != null) {
                    DirectTrips.destinationStops(own, withContext(io) { around.invoke(center) }, center)
                } else {
                    own
                }
                if (stops.isEmpty()) State.NoStops else State.Ready(stops.map(StopLocation::toStopRef), center)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                // A station id is a public TfL id, safe to log (SPEC *Privacy*); the query that found it isn't.
                warn("station lookup failed: ${e.message} for $stationId")
                State.Failed(errorKindOf(e))
            }
        }
    }
}
