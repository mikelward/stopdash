package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FixedLocation
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceHits
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.SearchEntry
import app.stopdash.domain.SearchResults
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StationMatch
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import app.stopdash.domain.UkPostcode
import app.stopdash.domain.Workers
import app.stopdash.domain.YourStops
import app.stopdash.domain.sameStationIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Remembers a geocoded place picked from a To… search, in the same recent list; blocking, run on
    // [io]. Only a To… picker supplies it — the default keeps none.
    private val recordPlace: suspend (PlaceHit) -> Unit = {},
    // Takes a pick off the recent list, the rider having confirmed it; false when it couldn't be saved.
    // Blocking, run on [io].
    private val recordRemove: suspend (SearchEntry) -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Ranks and merges each answer, off the main thread (AGENTS.md *Main thread*).
    private val compute: CoroutineDispatcher = Workers.compute,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val warn: (String) -> Unit = {},
) : ViewModel() {
    data class State(
        val query: String = "",
        val result: Result = Result.Idle,
        val searching: Boolean = false,
        // The user's starred and recently opened stops, listed before anything is typed; unread
        // (false) until the first read lands, so the screen doesn't flash its prompt first. The recent
        // list holds the geocoded places a To… search picked too, in the order picked.
        val favorites: List<StationMatch> = emptyList(),
        val recent: List<SearchEntry> = emptyList(),
        // A Recent row's removal couldn't be saved, so it's listed again: the search says so until the
        // rider next long-presses a Recent row ([askRemoveRecent]), not merely until it's shown again, so a
        // failure landing while the search is closed is still said when it reopens (Codex, #745).
        val removeRecentFailed: Boolean = false,
        // The Recent row a long press asked to remove, until the rider confirms or cancels.
        val pendingRemoval: SearchEntry? = null,
        // What the location-free home lists (SPEC *Without location*): the starred stations and the
        // recent ones less those starred, each bounded so the home's rows don't grow with the
        // rider's data (Find a station lists the rest). Worked out with the read, off the main thread.
        val homeStarred: List<StationMatch> = emptyList(),
        val recentStations: List<StationMatch> = emptyList(),
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
            // The rows in the order shown. A search only ever appends to it, so a row never moves under
            // a finger about to tap it (maintainer, 2026-09-28); stops then places by default.
            val entries: List<SearchEntry> = matches.map(SearchEntry::Stop) + places.map(SearchEntry::Place),
        ) : Result
        /** TfL answered, with no match. */
        data object NoMatches : Result
        data class Failed(val kind: DeparturesUiState.Error.Kind) : Result
    }

    private val _state = MutableStateFlow(
        State(
            query = savedState.get<String>(KEY_QUERY).orEmpty(),
            // Saved too, so a failure is still said after process death (Codex, #745).
            removeRecentFailed = savedState.get<Boolean>(KEY_REMOVE_FAILED) == true,
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var search: Job? = null

    // Taken by each recent-list write in turn ([record]).
    private val recording = Mutex()

    // The Recent rows whose removal is confirmed but not yet written, by [SearchEntry.key]: every read
    // published leaves them out, so no read of the file from before a write can list one again, however
    // the reads and writes interleave (Codex, #745). Each leaves once its write is done.
    private val removing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // TfL's answer behind the matches on screen, for the query it answered: an open re-ranks it
    // rather than asking again. Dropped as each search starts, so a failed one never borrows it.
    private var remoteFor: Pair<String, List<StationMatch>>? = null

    // The query the rows on screen ([Result.Matches.entries]) answer, so a search of the same query
    // again (a Retry) adds to them rather than starting the list over.
    private var entriesFor: String? = null

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
        savedState.remove<String>(KEY_PENDING_REMOVAL)
        remoteFor = null
        entriesFor = null
        _state.update {
            State(
                favorites = it.favorites,
                recent = it.recent,
                homeStarred = it.homeStarred,
                recentStations = it.recentStations,
                favoritePlaces = it.favoritePlaces,
                favoritePlacesFailed = it.favoritePlacesFailed,
                yoursRead = it.yoursRead,
                // Said until the next long press, not until the search closes (Codex, #745).
                removeRecentFailed = it.removeRecentFailed,
            )
        }
    }

    /**
     * Read the user's own stops again — when the search opens, since a star may have changed since
     * it last did. The typed search picks the new read up from its next letter.
     */
    fun refreshYours() {
        reread()
    }

    // The user's stops read again after a write of this model's own.
    private fun reread() {
        yours = readYours()
    }

    /**
     * [refreshYours] for the location-free home: a read still going (the one started as the model was
     * made, or Find a station's, left a moment ago) is as fresh as a new one, so it isn't read twice
     * (Codex on #733). Once it's done, a call reads again.
     */
    fun refreshYoursUnlessReading() {
        if (yours.isActive) return
        refreshYours()
    }

    /**
     * Remember [match] as opened, for the recent list; the write finishes even if the search closes.
     * The matches still up are ranked again, so on Back the match just opened leads its tier.
     */
    fun onOpened(match: StationMatch) {
        viewModelScope.launch {
            record { recordOpen(match) }
            reread()
            rerank()
        }
    }

    /**
     * Remember the geocoded place [hit] as picked, for the recent list, as [onOpened] does a station;
     * the write finishes even if the search closes. A place matches nothing typed, so nothing re-ranks.
     */
    fun onPlaceOpened(hit: PlaceHit) {
        viewModelScope.launch {
            record { recordPlace(hit) }
            reread()
        }
    }

    /**
     * A long press on Recent's [entry]: ask whether to remove it. Held here, not in the screen, so a
     * rotation keeps the question up for the same row; its key is saved too, so after process death the
     * question comes back once Recent is read again (Codex, #745).
     */
    fun askRemoveRecent(entry: SearchEntry) {
        savedState[KEY_PENDING_REMOVAL] = entry.key
        // A new removal is under way, so an earlier one's failure has been read: it's no longer said.
        savedState.remove<Boolean>(KEY_REMOVE_FAILED)
        _state.update { it.copy(pendingRemoval = entry, removeRecentFailed = false) }
    }

    /** The removal asked about isn't wanted. */
    fun cancelRemoveRecent() {
        savedState.remove<String>(KEY_PENDING_REMOVAL)
        _state.update { it.copy(pendingRemoval = null) }
    }

    /**
     * Take [entry] off the recent list (a long press, confirmed): gone from the list at once, then from
     * the device, after any pick still being written, and the user's stops read again. The write
     * finishes even if the search closes.
     */
    fun onRemoveRecent(entry: SearchEntry) {
        savedState.remove<String>(KEY_PENDING_REMOVAL)
        _state.update { it.copy(pendingRemoval = null) }
        // Left out of every read from now until its write is done, whatever else is written meanwhile.
        removing += entry.key
        // Gone from the list at once, filtered on compute, not in the tap (AGENTS.md *Main thread*): apart
        // from the write, since [removing] already keeps every read from listing it.
        val hidden = viewModelScope.launch(compute) {
            _state.update { st ->
                // A save that failed before this ran has let the row back: it stays.
                if (entry.key !in removing) return@update st
                st.copy(
                    recent = st.recent.filterNot { it.key == entry.key },
                    recentStations = st.recentStations.filterNot { it.key == entry.key },
                )
            }
        }
        viewModelScope.launch {
            var saved = true
            // Its place among the writes is taken at once, so a pick tapped after it is written after it
            // (Codex, #745).
            record { saved = recordRemove(entry) }
            // A failed save leaves the row on the device: read back, it returns, and the search says why,
            // marked first so it never shows again unexplained. Only the rider's next long press clears it,
            // never another write's read, nor the search shown again.
            if (saved) {
                // The row off the screen first, however busy compute is, so it can't be left up and tappable
                // once its key is gone (Codex, #745).
                hidden.join()
                // A read begun before the write may hold the file as it was: superseded before the row is
                // let back into reads, so only one begun after the write can publish.
                reread()
                removing -= entry.key
            } else {
                savedState[KEY_REMOVE_FAILED] = true
                _state.update { it.copy(removeRecentFailed = true) }
                // Still on the device, so back in reads before the read that lists it again.
                removing -= entry.key
                reread()
            }
            rerank()
        }
    }

    // Runs one recent-list write on [io], after every write tapped before it ([recording], whose
    // waiters are served in order), so the last pick leads however long each takes (Codex, PR #478).
    // Neither the wait nor the write is cancelled by the search closing.
    private suspend fun record(write: suspend () -> Unit) {
        withContext(NonCancellable) { recording.withLock { withContext(io) { write() } } }
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
            // Nothing is being tapped on the way back, so the whole list is ranked afresh here.
            val result = withContext(compute) { reranked(stations, trimmed, local, remote, shown) }
            if (result.matches.isNotEmpty() || result.places.isNotEmpty()) _state.update { it.copy(result = result) }
        }
    }

    // [rerank]'s answer: [local] ranked with [remote] (TfL's answer behind the matches on screen) when
    // there is one, else alone with [shown]'s failure kept; [shown]'s places kept either way.
    private fun reranked(stations: StationIndex, trimmed: String, local: List<StationMatch>, remote: List<StationMatch>?, shown: Result.Matches): Result.Matches =
        if (remote != null) {
                val ranked = stations.rank(trimmed, local, remote)
                Result.Matches(ranked, places = shown.places, entries = SearchResults.merge(trimmed, ranked, shown.places))
            } else {
                Result.Matches(
                    local,
                    places = shown.places,
                    remoteFailure = shown.remoteFailure,
                    entries = SearchResults.merge(trimmed, local, shown.places),
                )
            }

    private fun readYours(): Deferred<YourStops> {
        val generation = ++yoursGeneration
        return viewModelScope.async(io) {
            // A star the device couldn't name may be a listed station: name it from the bundled list.
            val loaded = loadYours()
            val unfiltered = if (loaded.unnamedStarred.isEmpty()) loaded else loaded.namedFrom(index.await())
            // Read alongside the stops so the To… picker shows both from the same open; independent, so
            // a places read failure never drops the stops. Null = couldn't read (a retryable notice),
            // distinct from an empty list (genuinely no saved places).
            val places = loadPlaces()
            // From here to publishing nothing suspends: the removals are those confirmed by now, not when
            // the read began, so one confirmed while it read stays gone (Codex, #745).
            val named = unfiltered.withoutRecent(removing.toSet())
            val homeStarred = named.homeStarred
            val recentStations = named.homeRecent()
            // A question about a removal that process death interrupted, asked again of the row read
            // back; gone if the row is. The key as saved now, so one asked while this read ran is kept.
            val pendingKey = savedState.get<String>(KEY_PENDING_REMOVAL)
            val restoredPending = pendingKey?.let { key -> named.recentPicks.firstOrNull { it.key == key } }
            named.also { read ->
                if (generation == yoursGeneration) {
                    // Dropped only while it is still the key this read looked for and found no row for.
                    if (pendingKey != null && restoredPending == null && savedState.get<String>(KEY_PENDING_REMOVAL) == pendingKey) {
                        savedState.remove<String>(KEY_PENDING_REMOVAL)
                    }
                    _state.update {
                        // Still asked at the publish itself: a Cancel or Remove on the main thread since the
                        // read began has dropped the key (Codex, #745).
                        val asked = restoredPending?.takeIf { p -> savedState.get<String>(KEY_PENDING_REMOVAL) == p.key }
                        // The removals as they stand at the publish itself, inside the atomic update: one
                        // confirmed on the main thread after the filter above is still left out, and the
                        // optimistic filter it launches runs after it in any case (Codex, #745).
                        val shown = read.withoutRecent(removing.toSet())
                        it.copy(
                            pendingRemoval = it.pendingRemoval ?: asked,
                            favorites = read.favorites,
                            recent = shown.recentPicks,
                            homeStarred = homeStarred,
                            recentStations = if (shown === read) recentStations else shown.homeRecent(),
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
            // The rows on screen for this query, which later answers only ever add to: a row never
            // moves once shown (maintainer, 2026-09-28). The index's best few go up at once; the rest
            // join TfL's answer, ranked together, below them.
            // A search of the query already on screen (a Retry) starts from its rows, so nothing moves.
            var shown: List<SearchEntry> =
                (_state.value.result as? Result.Matches)?.takeIf { entriesFor == trimmed }?.entries.orEmpty()
            if (local.isNotEmpty()) {
                val before = shown
                shown = withContext(compute) { SearchResults.appended(before, trimmed, local.take(LOCAL_PREVIEW), emptyList()) }
                entriesFor = trimmed
                _state.update { it.copy(result = Result.Matches(local, entries = shown), searching = true) }
            }
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
                val before = shown
                val (ranked, entries) = withContext(compute) {
                    // Uncapped, to tell a row folded into its twin from one only past the cap; capped for
                    // what's newly added.
                    val everyStop = stations.rank(trimmed, local, remote, limit = Int.MAX_VALUE)
                    val ranked = everyStop.take(StationIndex.DEFAULT_LIMIT)
                    ranked to SearchResults.appended(before, trimmed, ranked, emptyList(), everyStop = everyStop)
                }
                shown = entries
                Result.Matches(ranked, entries = shown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                warn("station search failed: ${e.message}")
                failureKind = errorKindOf(e)
                // No local stops to stand on → let the places below decide error vs place-only; otherwise
                // show the local stops now, with the failure noted so the "bus stops not searched" line
                // still tells the rider the stop results are incomplete (SPEC principle 2).
                if (local.isEmpty()) {
                    null
                } else {
                    val before = shown
                    shown = withContext(compute) { SearchResults.appended(before, trimmed, local, emptyList()) }
                    Result.Matches(local, remoteFailure = failureKind, entries = shown)
                }
            }
            if (stops != null && stops.matches.isNotEmpty()) {
                entriesFor = trimmed
                _state.update { it.copy(result = stops) }
            }
            // Fold in the geocoded places (or none) — the stops are already on screen.
            val places = placesDeferred.await()
            // The places join below whatever is already listed, ranked among themselves.
            val before = shown
            // The rows on screen are the answer: the stops and places the result carries are read off
            // them, so a row a Retry kept (a place an earlier geocode found) is neither dropped from
            // the state nor hidden behind a failure the new answer alone would have shown.
            val (withPlaces, shownStops, shownPlaces) = withContext(compute) {
                val rows = SearchResults.appended(before, trimmed, emptyList(), places)
                Triple(rows, rows.filterIsInstance<SearchEntry.Stop>().map { it.match }, rows.filterIsInstance<SearchEntry.Place>().map { it.hit })
            }
            shown = withPlaces
            val result: Result = when {
                shown.isNotEmpty() -> Result.Matches(
                    stops?.matches ?: shownStops,
                    places = shownPlaces,
                    remoteFailure = stops?.remoteFailure ?: failureKind,
                    entries = shown,
                )
                stops != null -> Result.NoMatches
                failureKind != null -> Result.Failed(failureKind)
                else -> Result.NoMatches
            }
            entriesFor = trimmed
            _state.update { it.copy(result = result, searching = false) }
            // The user's stops were read again meanwhile (a match opened mid-search): rank with that.
            if (yours !== read) rerank()
        }
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        // How many of the index's matches show before TfL answers: enough to tap the obvious station
        // at once, few enough that most of the list arrives below them rather than around them.
        const val LOCAL_PREVIEW = 4
        const val DEBOUNCE_MILLIS = 300L
        private const val KEY_QUERY = "query"
        private const val KEY_PENDING_REMOVAL = "pendingRemoval"
        private const val KEY_REMOVE_FAILED = "removeRecentFailed"
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
    // Centers and maps the stops TfL returns, off the main thread (AGENTS.md *Main thread*).
    private val compute: CoroutineDispatcher = Workers.compute,
    private val warn: (String) -> Unit = {},
    // The bundled index, for the other ids TfL lists this same station under (Weybridge's two records, no
    // interchange to open instead): asked for too, so none of its stops is left out. Read on [io].
    private val loadIndex: () -> StationIndex = { StationIndex.EMPTY },
) : ViewModel() {
    sealed interface State {
        data object Loading : State
        /** The station's stops, and its [center] (null when TfL placed none) — where From… stands. */
        data class Ready(
            val stops: List<StopRef>,
            val center: Coordinates? = null,
            // The stops' ids, made with the state (on the worker), so a page never walks [stops] to find them.
            val stopIds: Set<String> = stops.mapTo(HashSet()) { it.id },
        ) : State
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
                val ids = withContext(io) { listOf(stationId) + loadIndex().sameStationIds(stationId) }
                // Every id or none: a page missing one record's stops would look whole (SPEC *Finding stops*).
                val found = withContext(io) { ids.flatMap { finder.stationStops(it) } }
                withContext(compute) {
                    val stops = found.distinctBy { it.id }
                    if (stops.isEmpty()) State.NoStops else State.Ready(stops.map(StopLocation::toStopRef), FixedLocation.centerOf(stops))
                }
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
