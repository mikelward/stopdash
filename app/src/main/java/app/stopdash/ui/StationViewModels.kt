package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.BusStopIndex
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FixedLocation
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceHits
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.PlaceIndex
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
import kotlinx.coroutines.ensureActive
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
 * Places outside London, asked of TfL (maintainer, 2026-10-11): the bundle covers London only. A
 * complete postcode the bundle doesn't hold is asked at once; anything else waits for a tap on
 * "Search outside London", so no other text typed leaves the phone unless the rider asks.
 */
sealed interface OutsideLondon {
    /** Not offered: no search finished yet, or this picker can't route to a place. */
    data object Hidden : OutsideLondon
    data object Offered : OutsideLondon
    data object Searching : OutsideLondon
    data class Found(val places: List<PlaceHit>) : OutsideLondon
    data object NoMatches : OutsideLondon
    data object Failed : OutsideLondon
}

/**
 * "Find a station" search (SPEC *Finding stops*): the typed [State.query] and the bundled stations,
 * bus stops and places that match it, all searched on the device: nothing typed is sent anywhere
 * (maintainer, 2026-10-11), and it's never logged (SPEC *Privacy*). A search runs as each letter is
 * typed, for a query of at least [MIN_QUERY_LENGTH] characters; a newer query cancels the one in
 * flight. While a search runs, the previous result stays on screen with [State.searching] set, rather
 * than blanking on each letter.
 *
 * Before anything is typed it lists the user's own stops — [State.favorites] and [State.recent]
 * opens — and as they type those stops, and the ones lately shown near them, match on the device
 * with the bundled stations ([YourStops]), none of it sent anywhere.
 */
class StationSearchViewModel(
    // Keeps the typed query across process death, alongside the activity's saved navigation, so a
    // restored search (or Back from a restored station) finds the query and searches it again. The
    // saved state stays on the device.
    private val savedState: SavedStateHandle = SavedStateHandle(),
    // The bundled station index, searched on the device as the user types (read off the main
    // thread, once). Empty by default, which finds no station.
    loadIndex: suspend () -> StationIndex = { StationIndex.EMPTY },
    // The user's own stops, read from the device each time the search opens ([refreshYours]). The
    // loader handles its own read failures: whatever it can't read is simply not listed.
    private val loadYours: suspend () -> YourStops = { YourStops.EMPTY },
    // The user's saved favorite places (SPEC D9), read from the device each time the search opens.
    // Only a To… picker offers them (they're trip destinations, not stops to browse), so the default
    // is none. Null means the read couldn't complete (as opposed to an empty list = genuinely none),
    // so the picker says so honestly and offers a retry rather than pretending there are none.
    private val loadPlaces: suspend () -> List<FavoritePlace>? = { emptyList() },
    // The bundled London place index (dev-docs/PLACES.md): places, streets and postcodes found on the
    // device as the rider types. Only a To… picker supplies it — the default finds none. Read off the
    // main thread, once.
    loadPlaceIndex: suspend () -> PlaceIndex = { PlaceIndex.EMPTY },
    // Whether a typed query searches the place index for places. False where none can be picked (the
    // favorite-journey picker), which reads the index only for each stop's postcode district.
    private val searchPlaces: Boolean = true,
    // London's bus stops, bundled, searched on the device with the stations. Empty by default, which
    // finds no bus stop. Read off the main thread, once.
    loadBusStops: suspend () -> BusStopIndex = { BusStopIndex.EMPTY },
    // Looks a place or postcode up with TfL's Journey Planner, for what the bundle can't hold: a place
    // outside London (maintainer, 2026-10-11). Sends only the typed text. Null, the default, offers no
    // such search; only a picker that routes to or from a place passes one.
    private val findOutside: (suspend (String) -> List<PlaceCandidate>)? = null,
    // Notes an outside-London lookup that failed, in the debug log; never the query.
    private val warn: (String) -> Unit = {},
    // With no start set ([setNear]), rank same-named stops and places from the latest Recent pick that
    // has a position: From…'s last start, where the rider's position isn't known (maintainer, 2026-10-12).
    private val nearFromRecent: Boolean = false,
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
        // Each starred and Recent stop's postcode district ("EC4M"), by [StationMatch.key], as the
        // typed results' [Result.Matches.areas] are; filled in once the place index is read.
        val yourAreas: Map<String, String> = emptyMap(),
        // TfL's places for the query, listed under the bundled rows ([OutsideLondon]).
        val outside: OutsideLondon = OutsideLondon.Hidden,
    )


    sealed interface Result {
        /** Nothing searched yet: the query is too short. */
        data object Idle : Result
        data class Matches(
            val matches: List<StationMatch>,
            // Geocoded places for the query (SPEC *Find a station*), shown after the stops in a To…
            // picker with a Place/Postcode tag. Empty for a plain station search, which geocodes none.
            val places: List<PlaceHit> = emptyList(),
            // The rows in the order shown. A search only ever appends to it, so a row never moves under
            // a finger about to tap it (maintainer, 2026-09-28); stops then places by default.
            val entries: List<SearchEntry> = matches.map(SearchEntry::Stop) + places.map(SearchEntry::Place),
            // Each stop row's postcode district ("EC4M"), by [StationMatch.key], so same-named stops
            // read apart (maintainer, 2026-10-11). A row without one (not yet worked out, or no
            // postcode near it) shows none.
            val areas: Map<String, String> = emptyMap(),
        ) : Result
        /** Nothing bundled matches. */
        data object NoMatches : Result
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

    // Where the trip starts, as last set ([setNear]): what puts the nearer of same-named stops first.
    @Volatile private var near: Coordinates? = null

    // The TfL lookup for places outside London, cancelled with each new query.
    private var outsideSearch: Job? = null

    // Taken by each recent-list write in turn ([record]).
    private val recording = Mutex()

    // The Recent rows whose removal is confirmed but not yet written, by [SearchEntry.key]: every read
    // published leaves them out, so no read of the file from before a write can list one again, however
    // the reads and writes interleave (Codex, #745). Each leaves once its write is done.
    private val removing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // The query the rows on screen ([Result.Matches.entries]) answer, so a search of the same query
    // again (a Retry) adds to them rather than starting the list over.
    private var entriesFor: String? = null

    // Loaded once, on first use, so opening the search never waits on the asset read.
    private val index = viewModelScope.async(io, start = CoroutineStart.LAZY) { loadIndex() }

    // Started at once (it takes a moment to read), so it's usually ready by the first letter typed.
    private val placeIndex = viewModelScope.async(io) { loadPlaceIndex() }

    // Started at once for the same reason.
    private val busStops = viewModelScope.async(io) { loadBusStops() }

    // Which read is the latest: an older one that lands after a newer one started is ignored, so a
    // star changed between the two can't be overwritten by the stale list. Declared before [yours],
    // whose initializer starts the first read.
    @Volatile
    private var yoursGeneration = 0

    private var yours: Deferred<YourStops> = readYours()

    init {
        // A restored query is searched again at once; a fresh one is empty and searches nothing.
        start(_state.value.query)
    }

    fun onQueryChange(query: String) {
        savedState[KEY_QUERY] = query
        _state.update { it.copy(query = query) }
        start(query)
    }

    /**
     * Forget the search when it closes: cancel a pending request and drop the query and matches
     * (and the saved copy), so reopening starts blank and nothing typed outlives the screen.
     */
    fun clear() {
        search?.cancel()
        outsideSearch?.cancel()
        savedState.remove<String>(KEY_QUERY)
        savedState.remove<String>(KEY_PENDING_REMOVAL)
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
                yourAreas = it.yourAreas,
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
    /**
     * Where the trip starts, for the next search: same-named stops and places nearer it list first
     * (maintainer, 2026-10-12). To… passes the trip's start: the rider's position, else the From…
     * station's center. From… passes the rider's position when known.
     * Rows already listed don't move. A search under way when the start changes (a query restored after
     * process death, searched before its screen set the start) runs again from the new one, adding to
     * the rows it has listed.
     */
    fun setNear(at: Coordinates?) {
        if (at == near) return
        near = at
        val current = _state.value
        if (current.searching && search?.isActive == true) start(current.query)
    }

    // The start a search ranks from: as set, else (From…) the latest Recent pick's position. Placing a
    // stop waits on the bundle, off the main thread.
    private suspend fun nearNow(): Coordinates? {
        near?.let { return it }
        if (!nearFromRecent) return null
        // The latest pick, a stop placed from the bundle: the Recent file keeps a stop's id, not its position.
        // From the read itself, not the state it publishes, which a search restored at once can precede. On
        // [compute], the hop first: the pick is found by walking the list.
        val read = yours
        return withContext(compute) {
            when (val latest = read.await().recentPicks.firstOrNull { it.key !in removing }) {
                null -> null
                is SearchEntry.Place -> latest.hit.coordinate
                is SearchEntry.Stop -> {
                    val match = latest.match
                    val lat = match.latitude
                    val lon = match.longitude
                    if (lat != null && lon != null) Coordinates(lat, lon)
                    else index.await().positionOf(match.id) ?: busStops.await().positionOf(match.id)
                }
            }
        }
    }

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
     * the write finishes even if the search closes. In From… the place is now the last start, which
     * same-named stops rank from where the rider's position isn't known, so they re-rank as [onOpened]'s do.
     */
    fun onPlaceOpened(hit: PlaceHit) {
        viewModelScope.launch {
            record { recordPlace(hit) }
            reread()
            if (nearFromRecent) rerank()
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

    // The matches on screen ranked again with the user's stops as now read, from the bundled
    // stations and bus stops behind them, so Back keeps what it showed. A search still running
    // re-ranks when it lands instead ([start]).
    private fun rerank() {
        val current = _state.value
        val trimmed = current.query.trim()
        if (current.searching || current.result !is Result.Matches) return
        search = viewModelScope.launch {
            val stations = withContext(io) { index.await().withYours(yours.await()) }
            val buses = busStops.await()
            val shown = _state.value.result as? Result.Matches ?: return@launch
            val from = nearNow()
            // Nothing is being tapped on the way back, so the whole list is ranked afresh here, its
            // places too: the same ones, ordered from the start as it now is.
            val places = if (shown.places.isEmpty()) shown.places
            else bundledPlacesFor(placeIndex.await(), PlaceHits.withoutHouseNumber(trimmed), from).ifEmpty { shown.places }
            val result = withContext(compute) {
                val ranked = stations.rank(
                    trimmed, stations.search(trimmed, near = from), buses.search(trimmed, checkpoint = { ensureActive() }, near = from), near = from,
                )
                val entries = SearchResults.merge(trimmed, ranked, places)
                Result.Matches(ranked, places = places, entries = entries, areas = areasOf(entries, stations))
            }
            if (result.matches.isNotEmpty() || result.places.isNotEmpty()) _state.update { it.copy(result = result) }
        }
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
                    readYourAreas(read, generation)
                }
            }
        }
    }

    // [index]'s places for [query], searched on [compute]; none, with no hop, when there's no index.
    private suspend fun bundledPlacesFor(index: PlaceIndex, query: String, near: Coordinates?): List<PlaceHit> =
        if (index.size == 0) emptyList() else withContext(compute) {
            // A search the rider has typed past stops partway rather than scanning the whole index. A
            // postcode-shaped query (a digit, as the favorite editor tells them apart) lists postcodes
            // only, so places named like one ("E12 Cafe") can't take every slot.
            val postcodesOnly = UkPostcode.looksLikePartial(query) && query.any(Char::isDigit)
            PlaceHits.fromIndex(index, query, checkpoint = { ensureActive() }, postcodesOnly = postcodesOnly, near = near)
        }

    private fun start(query: String) {
        search?.cancel()
        outsideSearch?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            _state.update { it.copy(result = Result.Idle, searching = false, outside = OutsideLondon.Hidden) }
            return
        }
        // Marked searching at once: the matches on screen are the previous query's until the new
        // answer lands, and the progress bar says so rather than letting them read as the answer to
        // what's now typed.
        _state.update { it.copy(searching = true, outside = OutsideLondon.Hidden) }
        search = viewModelScope.launch {
            // Read once, so every part of this search ranks from the same start.
            val from = nearNow()
            // Everything is searched on the device (maintainer, 2026-10-11): the bundled stations, bus
            // stops and places, with nothing sent. The rows on screen for this query only ever grow: a
            // row never moves once shown (maintainer, 2026-09-28).
            var shown: List<SearchEntry> =
                (_state.value.result as? Result.Matches)?.takeIf { entriesFor == trimmed }?.entries.orEmpty()
            // Guards [shown] between this search and its places below: each adds to the rows as read.
            val rows = Mutex()
            // The stops listed so far, for a result published before they are.
            var stopsShown: List<StationMatch> = emptyList()
            // Whether the places are on screen yet, by whichever of the two paths below came first.
            var placesListed = false
            // The places, looked up the moment the place index is read, whatever else is still loading.
            // An address's house number isn't in the bundle, so its street is what's searched.
            val placeLookup: Deferred<List<PlaceHit>> =
                async { if (searchPlaces) bundledPlacesFor(placeIndex.await(), PlaceHits.withoutHouseNumber(trimmed), from) else emptyList() }
            launch {
                val places = placeLookup.await()
                if (places.isEmpty()) return@launch
                rows.withLock {
                    if (placesListed) return@withLock
                    placesListed = true
                    val before = shown
                    shown = withContext(compute) { SearchResults.appended(before, trimmed, emptyList(), places) }
                    entriesFor = trimmed
                    _state.update {
                        val areas = (it.result as? Result.Matches)?.areas.orEmpty()
                        it.copy(result = Result.Matches(stopsShown, places = places, entries = shown, areas = areas))
                    }
                }
            }
            val read = yours
            val own = read.await()
            val stations = withContext(io) { index.await().withYours(own) }
            val local = withContext(io) { stations.search(trimmed, near = from) }
            // While the bus stops are still being read (the first letters typed), the stations' first
            // few go up at once, and the rest join the bus stops below them.
            if (!busStops.isCompleted && local.isNotEmpty()) {
                rows.withLock {
                    val before = shown
                    val first = local.take(LOCAL_PREVIEW)
                    val (entries, listed, areas) = withContext(compute) {
                        val entries = SearchResults.appended(before, trimmed, first, emptyList())
                        Triple(entries, shownPlaces(entries), areasOf(entries, stations))
                    }
                    shown = entries
                    stopsShown = first
                    entriesFor = trimmed
                    _state.update { it.copy(result = Result.Matches(first, places = listed, entries = entries, areas = areas)) }
                }
            }
            val buses = busStops.await()
            rows.withLock {
                // The places, when already found (the place index is read as the model is made, so
                // usually they are): ranked in with the stops at once, rather than below them.
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                val places = if (!placesListed && placeLookup.isCompleted) placeLookup.getCompleted() else emptyList()
                if (places.isNotEmpty()) placesListed = true
                val before = shown
                val (ranked, entries, listed) = withContext(compute) {
                    // Uncapped, to tell a row folded into its twin from one only past the cap; capped
                    // for what's newly added.
                    val busMatches = buses.search(trimmed, checkpoint = { ensureActive() }, near = from)
                    val everyStop = stations.rank(trimmed, local, busMatches, limit = Int.MAX_VALUE, near = from)
                    val ranked = everyStop.take(StationIndex.DEFAULT_LIMIT)
                    val entries = SearchResults.appended(before, trimmed, ranked, places, everyStop = everyStop)
                    Triple(ranked, entries, shownPlaces(entries) to areasOf(entries, stations))
                }
                shown = entries
                stopsShown = ranked
                entriesFor = trimmed
                if (shown.isNotEmpty()) {
                    _state.update { it.copy(result = Result.Matches(ranked, places = listed.first, entries = entries, areas = listed.second)) }
                }
            }
            // The places, if they weren't ready with the stops: below whatever is listed.
            val places = placeLookup.await()
            // The place index too, for the rows' districts, where no place search waited on it (the
            // favorite-journey picker): read once per process, so only a first search waits.
            placeIndex.await()
            val result: Result = rows.withLock {
                val before = shown
                val (entries, listed, areas) = withContext(compute) {
                    val entries = SearchResults.appended(before, trimmed, emptyList(), places)
                    Triple(entries, shownPlaces(entries), areasOf(entries, stations))
                }
                shown = entries
                entriesFor = trimmed
                if (entries.isEmpty()) Result.NoMatches else Result.Matches(stopsShown, places = listed, entries = entries, areas = areas)
            }
            _state.update { it.copy(result = result, searching = false) }
            offerOutside(trimmed, places)
            // The user's stops were read again meanwhile (a match opened mid-search): rank with that.
            if (yours !== read) rerank()
        }
    }

    // Once the bundle has answered [query]: a complete postcode it doesn't hold ([bundled] has no exact
    // match) is asked of TfL at once, as it's outside London; anything else is
    // offered as a "Search outside London" row, asked only when tapped.
    private suspend fun offerOutside(query: String, bundled: List<PlaceHit>) {
        val find = findOutside ?: return
        // Classified on [compute]: the query is the rider's, of any length (AGENTS.md *Main thread*).
        val unheldPostcode = withContext(compute) {
            UkPostcode.isComplete(query) && bundled.none {
                it.kind == PlaceKind.POSTCODE && UkPostcode.normalize(it.name) == UkPostcode.normalize(query)
            }
        }
        if (unheldPostcode) {
            outsideSearch = viewModelScope.launch { lookOutside(query, find) }
        } else {
            _state.update { it.copy(outside = OutsideLondon.Offered) }
        }
    }

    /** Ask TfL for places outside London matching what's typed: "Search outside London", or its Retry. */
    fun searchOutside() {
        val find = findOutside ?: return
        val typed = _state.value.query
        outsideSearch?.cancel()
        outsideSearch = viewModelScope.launch {
            val query = withContext(compute) { typed.trim() }
            if (query.length >= MIN_QUERY_LENGTH) lookOutside(query, find)
        }
    }

    // Run in [outsideSearch], which a new query cancels, so what it publishes is always this query's.
    private suspend fun lookOutside(query: String, find: suspend (String) -> List<PlaceCandidate>) {
        _state.update { it.copy(outside = OutsideLondon.Searching) }
        val from = nearNow()
        val outcome = try {
            val candidates = withContext(io) { find(query) }
            withContext(compute) {
                val kind = if (UkPostcode.isComplete(query)) PlaceKind.POSTCODE else PlaceKind.PLACE
                val places = PlaceHits.rank(query, PlaceHits.outsideBundle(candidates), kind, near = from)
                if (places.isEmpty()) OutsideLondon.NoMatches else OutsideLondon.Found(places)
            }
        } catch (e: TflException) {
            // Said on the row, with a Retry; the bundled rows above are untouched.
            warn("search outside London failed: ${e.javaClass.simpleName}")
            OutsideLondon.Failed
        }
        _state.update { it.copy(outside = outcome) }
    }

    // Each stop row's postcode district among [entries]; none until the place index is read. Called on
    // [compute].
    @androidx.annotation.WorkerThread
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun areasOf(entries: List<SearchEntry>, stations: StationIndex): Map<String, String> {
        val places = placeIndex.takeIf { it.isCompleted }?.getCompleted() ?: return emptyMap()
        // The bus stops, once read, place a saved stop known only by its stop area ("490G…").
        val buses = busStops.takeIf { it.isCompleted }?.getCompleted()
        return districtsOf(entries.mapNotNull { (it as? SearchEntry.Stop)?.match }, stations, buses, places)
    }

    // Each of [matches]' postcode district, from the nearest bundled postcode to where the stop is: its
    // own position, else [stations]' or [buses]'. Called on [compute].
    @androidx.annotation.WorkerThread
    private fun districtsOf(matches: List<StationMatch>, stations: StationIndex, buses: BusStopIndex?, places: PlaceIndex): Map<String, String> {
        if (places.size == 0) return emptyMap()
        return buildMap {
            for (match in matches) {
                val lat = match.latitude
                val lon = match.longitude
                val at = if (lat != null && lon != null) Coordinates(lat, lon) else stations.positionOf(match.id) ?: buses?.positionOf(match.id)
                at?.let { places.districtNear(it.latitude, it.longitude) }?.let { put(match.key, it) }
            }
        }
    }

    // The starred and Recent stops' districts, once the place index is read, published if [generation]
    // is still the latest read: apart from the read itself, so neither the list nor a search waits on it.
    private fun readYourAreas(read: YourStops, generation: Int) {
        viewModelScope.launch {
            val places = placeIndex.await()
            if (places.size == 0) return@launch
            val stations = index.await()
            val buses = busStops.await()
            val areas = withContext(compute) {
                val stops = read.favorites + read.recentPicks.mapNotNull { (it as? SearchEntry.Stop)?.match }
                districtsOf(stops, stations, buses, places)
            }
            if (generation == yoursGeneration) _state.update { it.copy(yourAreas = areas) }
        }
    }

    // The places among [entries], in the order shown; called on [compute].
    private fun shownPlaces(entries: List<SearchEntry>): List<PlaceHit> =
        entries.filterIsInstance<SearchEntry.Place>().map { it.hit }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        // How many of the index's matches show before the bus stops are read: enough to tap the obvious
        // station at once, few enough that most of the list arrives below them rather than around them.
        const val LOCAL_PREVIEW = 4
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
