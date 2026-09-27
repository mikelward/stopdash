package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.FavoritePlacesStore
import app.stopdash.domain.FixedLocation
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.PostcodeResolver
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StationMatch
import app.stopdash.domain.TflException
import app.stopdash.domain.UkPostcode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The favorite-places editor's state (SPEC D9): the saved places (from [FavoritePlacesStore]) and,
 * while adding or editing one, an [Editor] holding the stop/station search and the chosen location.
 *
 * A favorite is saved by **coordinate** — resolved from a **stop/station** the user types. The picker
 * matches with the same bundled [StationIndex] plus TfL merge and ranking the From…/To… search uses
 * (SPEC *Finding stops*), so it behaves identically; the typed query is sent to TfL (via
 * [StationFinder.searchStations], `/StopPoint/Search`) and nowhere else, and never logged; only its
 * kind — a status is logged on failure (SPEC *Privacy*). A match with no inline position is resolved
 * on pick from its stops' members ([StationFinder.stationStops] → center); only a result whose stops
 * carry no position at all can't anchor a place and stays unselectable (SPEC D9). Editing a place keeps its saved coordinate until
 * the user picks a new location. Postcode/place resolution (SPEC D9's Journey Planner disambiguation)
 * is a follow-up; v1 resolves via the stop/station search.
 *
 * The editor's draft (kind, label and picked coordinate) is mirrored into [SavedStateHandle], so a
 * process death while adding/editing restores the in-progress place rather than dropping it. The typed
 * **query is deliberately not persisted** — it is the text sent to TfL and, per *Privacy*, isn't saved
 * — so the search field starts blank on restore while the picked place (label + coordinate) stands.
 */
class FavoritePlacesViewModel(
    private val store: FavoritePlacesStore,
    private val finder: StationFinder,
    // Resolves a typed postcode to place candidates (TfL Journey Planner). Defaults to a no-op so the
    // other surfaces/tests that don't exercise postcodes need no resolver; production passes the client.
    private val postcodes: PostcodeResolver = PostcodeResolver { emptyList() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // The bundled station index, so the picker matches on the device as the user types and returns the
    // same ranked list as From…/To… (SPEC *Finding stops*); loaded once, off the main thread, on first
    // search. Empty by default (tests, and before it loads) leaves matching to TfL's search alone.
    loadIndex: suspend () -> StationIndex = { StationIndex.EMPTY },
    // Restores the in-progress editor draft across process death; the saved copy stays on the device.
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    // A fresh id for a newly-added place; overridable so tests get stable ids.
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val warn: (String) -> Unit = {},
) : ViewModel() {

    data class State(
        val places: FavoritePlacesSet = FavoritePlacesSet.Loaded(emptyList()),
        // False until the store's flow first emits, so the screen shows a blank rather than "empty"
        // before the read lands.
        val loaded: Boolean = false,
        val editor: Editor? = null,
        // A save or delete threw (e.g. DataStore unavailable): the change didn't persist, so the
        // screen says so rather than dropping it silently (SPEC principle 2).
        val writeFailed: Boolean = false,
    )

    /**
     * The add/edit sheet. [existingId] is null while adding. [coordinate] is the chosen location —
     * seeded from the edited place, then replaced when the user picks a search result — and a save
     * needs both it and a non-blank [label]. [saving] is true while a write is in flight, so a second
     * Save tap can't add a duplicate.
     */
    data class Editor(
        val kind: FavoriteKind,
        val existingId: String?,
        // The id a newly-added place is saved under, assigned when the editor opens and persisted, so a
        // save retried after a write failure or restored after process death reuses the same id rather
        // than minting a new one (a custom place would otherwise duplicate; a reserved kind would keep
        // its slot but change identity). Null when editing (the existing id stands). Codex.
        val draftId: String? = null,
        val label: String,
        val query: String = "",
        val searching: Boolean = false,
        val results: List<StationMatch> = emptyList(),
        val searchFailed: Boolean = false,
        // TfL's search failed but the bundled index still matched: the local list stands, and the
        // screen says bus stops / newer stations weren't searched — as the station search does.
        val remoteFailed: Boolean = false,
        val coordinate: Coordinates? = null,
        val placeName: String? = null,
        // True when [label] was auto-filled from a picked result (a blank custom label takes the stop
        // name), so a later pick refreshes it; false once the user types their own label or it's a
        // reserved default (Home/Work/School), which a pick then leaves untouched.
        val labelFromPick: Boolean = false,
        val saving: Boolean = false,
        // A result the user tapped that had no inline position: its coordinate is being resolved from
        // the stop's members (TfL stationStops → center), so that row shows a spinner. Null when none.
        val resolvingId: String? = null,
        // Results with **definitively** no location (the lookup succeeded but the stop has no positioned
        // members): shown, but unselectable — not a transient failure. Reset per search. Codex.
        val unresolvableIds: Set<String> = emptySet(),
        // Results whose resolve **failed transiently** (TfL/network unreachable): distinct from
        // unresolvable — the row says so and stays tappable to retry, rather than presenting a temporary
        // outage as "no location". Reset per search. Codex.
        val resolveFailedIds: Set<String> = emptySet(),
        // Postcode entry (SPEC D9): when the query looks like a postcode, the screen offers a "Postcode"
        // row; tapping a complete one resolves it to place candidates the user chooses from (never
        // auto-picked). These track that lookup; all reset when the query changes.
        val postcodeResolving: Boolean = false,
        val postcodeCandidates: List<PlaceCandidate> = emptyList(),
        // The lookup ran and returned nothing (TfL places the postcode nowhere) — distinct from "not yet
        // looked up", so the row can say "no places" rather than re-offer the tap.
        val postcodeNoResults: Boolean = false,
        // The lookup failed (TfL unreachable): the row says so and stays tappable to retry.
        val postcodeFailed: Boolean = false,
    ) {
        val editing: Boolean get() = existingId != null
        val canSave: Boolean get() = coordinate != null && label.isNotBlank() && !saving
    }

    private val _state = MutableStateFlow(State(editor = restoreEditor()))
    val state: StateFlow<State> = _state.asStateFlow()

    private var search: Job? = null
    private var resolve: Job? = null
    private var postcode: Job? = null

    // Bumped whenever the editor is opened, replaced or closed, so an in-flight save can tell whether
    // the editor it started for is still the one on screen (see [commit]). Not changed by field edits
    // within one editing session.
    private var editorEpoch = 0

    // The bundled index, loaded once on first use so opening the editor never waits on the asset read.
    private val index = viewModelScope.async(io, start = CoroutineStart.LAZY) { loadIndex() }

    init {
        // A read failure (IOException before the first emit) must not escape the scope and crash the
        // screen; it reads as Unavailable — the honest "couldn't read" state — rather than hanging
        // unloaded (SPEC principle 2 / *Error handling*).
        viewModelScope.launch {
            try {
                store.places().collect { set ->
                    _state.update { it.copy(places = set, loaded = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("favorite places read failed: ${e.javaClass.simpleName}")
                _state.update { it.copy(places = FavoritePlacesSet.Unavailable, loaded = true) }
            }
        }
        // A draft restored from saved state re-runs its search so the results match the query again.
        _state.value.editor?.query?.let { if (it.isNotBlank()) startSearch(it) }
    }

    /** Begin adding a place of [kind]; [defaultLabel] pre-fills the label (e.g. "Home"). */
    fun startAdd(kind: FavoriteKind, defaultLabel: String) {
        search?.cancel()
        resolve?.cancel()
        postcode?.cancel()
        // Every new place gets its id now (persisted with the draft), so a save retried after a failure
        // or restored after process death reuses it and keeps a stable id — a reserved kind upserts by
        // kind, but a changing id would still break identity for anything that references it (Codex).
        setEditor(Editor(kind = kind, existingId = null, draftId = newId(), label = defaultLabel))
    }

    /** Begin editing [place]; its saved location stands until the user picks a new one. */
    fun startEdit(place: FavoritePlace) {
        search?.cancel()
        resolve?.cancel()
        postcode?.cancel()
        setEditor(
            Editor(
                kind = place.kind,
                existingId = place.id,
                label = place.label,
                // A label equal to the resolved place name was auto-derived from the original pick, so a
                // repick should refresh it; a label the user chose (or a reserved default) differs and
                // stands (Codex).
                labelFromPick = place.placeName != null && place.label == place.placeName,
                coordinate = place.coordinate,
                placeName = place.placeName,
            ),
        )
    }

    fun cancelEditor() {
        search?.cancel()
        resolve?.cancel()
        postcode?.cancel()
        setEditor(null)
    }

    fun onLabelChange(label: String) {
        if (savingNow()) return // a save is in flight; ignore edits so completion can't discard them
        // The user is naming the place: keep this label as-is on a later pick.
        updateEditor { it.copy(label = label, labelFromPick = false) }
    }

    /** Re-run the current query's search after a failure, without the user retyping (Codex). */
    fun retrySearch() {
        if (savingNow()) return
        _state.value.editor?.query?.let { startSearch(it) }
    }

    fun onQueryChange(query: String) {
        if (savingNow()) return // don't disturb a draft mid-write; completion would discard the change
        // Editing the query after a pick invalidates that selection: otherwise its coordinate would
        // linger and Save could store the old location under the new query/label (Codex P1). A pending
        // postcode lookup and any candidates it offered belong to the old text, so drop them too.
        resolve?.cancel()
        postcode?.cancel()
        updateEditor {
            it.copy(
                query = query, coordinate = null, placeName = null, resolvingId = null,
                postcodeResolving = false, postcodeCandidates = emptyList(),
                postcodeNoResults = false, postcodeFailed = false,
            )
        }
        // A postcode-shaped query goes to the Journey Planner when the user taps the "Postcode" row, not
        // to the stop search — so don't also fire an automatic /StopPoint/Search for it (SPEC D9; a
        // needless request otherwise). The **digit** is the discriminator: a plain two-letter prefix is
        // how station names start ("Ba" → Bank), so those still search; a query with a digit ("N1",
        // "SW1A") is a postcode, not a station name.
        if (UkPostcode.looksLikePartial(query) && query.any(Char::isDigit)) {
            search?.cancel()
            updateEditor {
                it.copy(
                    searching = false, results = emptyList(), searchFailed = false, remoteFailed = false,
                    unresolvableIds = emptySet(), resolveFailedIds = emptySet(),
                )
            }
        } else {
            startSearch(query)
        }
    }

    // True while a save is in flight: the editor's inputs are frozen (also disabled in the UI), so a
    // slow write's completion can't close the editor over edits made after Save was tapped (Codex).
    private fun savingNow(): Boolean = _state.value.editor?.saving == true

    /**
     * Adopt a picked search result as the place's location. A match TfL already placed is adopted at
     * once; one with no inline position has its coordinate **resolved from the stop's members** (TfL
     * [StationFinder.stationStops] → [FixedLocation.centerOf], as To… does), so a station the bundled
     * index and search both left positionless is still selectable. If the stop has no positioned members,
     * or TfL 404s it ([TflException.NotFound], which won't change on retry), it is marked **unresolvable**
     * (definitively no location, unselectable); if the lookup fails **transiently** (TfL/network down) it
     * is marked **resolve-failed** — a state the row reports and can retry on another tap, never presented
     * as "no location" (SPEC D9; TODO shared place search; Codex).
     */
    fun onPick(match: StationMatch) {
        if (savingNow()) return // frozen while saving (Codex)
        if (_state.value.editor?.unresolvableIds?.contains(match.id) == true) return
        val direct = match.coordinate()
        if (direct != null) {
            adopt(match, direct)
            return
        }
        // No inline position: resolve it from the stop's members before giving up. Clear any prior
        // transient failure on this row — this tap is the retry.
        resolve?.cancel()
        updateEditor { it.copy(resolvingId = match.id, resolveFailedIds = it.resolveFailedIds - match.id) }
        resolve = viewModelScope.launch {
            val center: Coordinates?
            try {
                center = FixedLocation.centerOf(withContext(io) { finder.stationStops(match.id) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                // 404: TfL doesn't know this stop, and its contract says retrying won't help — mark it
                // unresolvable (definitively no location), never retryable.
                warn("favorite place resolve failed: ${e.javaClass.simpleName}")
                if (_state.value.editor?.resolvingId == match.id) {
                    updateEditor { it.copy(resolvingId = null, unresolvableIds = it.unresolvableIds + match.id) }
                }
                return@launch
            } catch (e: Exception) {
                warn("favorite place resolve failed: ${e.javaClass.simpleName}")
                // Ignore a failure the user has moved on from.
                if (_state.value.editor?.resolvingId == match.id) {
                    updateEditor { it.copy(resolvingId = null, resolveFailedIds = it.resolveFailedIds + match.id) }
                }
                return@launch
            }
            // Ignore a completion the user has moved on from (a newer pick/search changed resolvingId).
            if (_state.value.editor?.resolvingId != match.id) return@launch
            if (center != null) {
                adopt(match, center)
            } else {
                // Placed nowhere — the lookup succeeded but the stop has no positioned members: keep it
                // shown but unselectable, honestly (distinct from a transient failure above).
                updateEditor { it.copy(resolvingId = null, unresolvableIds = it.unresolvableIds + match.id) }
            }
        }
    }

    /** Adopt [match] at [coordinate] as the editor's chosen location. */
    private fun adopt(match: StationMatch, coordinate: Coordinates) = adoptLocation(match.name, coordinate)

    /**
     * Look up the typed postcode and offer the place(s) it names (SPEC D9). A no-op unless the query is a
     * complete postcode ([UkPostcode.isComplete]); the caller only enables the affordance then. Nothing
     * is auto-picked — the user chooses a candidate ([onPickCandidate]). The postcode is the rider's own
     * input, sent to TfL like a routing query and never logged (SPEC *Privacy*).
     */
    fun resolvePostcode() {
        if (savingNow()) return
        val query = _state.value.editor?.query ?: return
        val code = UkPostcode.format(query) ?: return
        postcode?.cancel()
        updateEditor {
            it.copy(postcodeResolving = true, postcodeFailed = false, postcodeNoResults = false, postcodeCandidates = emptyList())
        }
        postcode = viewModelScope.launch {
            val candidates = try {
                withContext(io) { postcodes.resolvePostcode(code) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("favorite place postcode resolve failed: ${e.javaClass.simpleName}")
                // Ignore a failure the user has moved on from (a newer query cancels/replaces this).
                if (_state.value.editor?.query == query) {
                    updateEditor { it.copy(postcodeResolving = false, postcodeFailed = true) }
                }
                return@launch
            }
            // Ignore a completion the user has moved on from.
            if (_state.value.editor?.query != query) return@launch
            updateEditor {
                it.copy(
                    postcodeResolving = false,
                    postcodeCandidates = candidates,
                    postcodeNoResults = candidates.isEmpty(),
                    postcodeFailed = false,
                )
            }
        }
    }

    /** Adopt a resolved postcode [candidate] as the place's location, like picking a search result. */
    fun onPickCandidate(candidate: PlaceCandidate) {
        if (savingNow()) return
        adoptLocation(candidate.name, candidate.coordinate)
    }

    /** Adopt a chosen place [name] at [coordinate] — a picked station, or a resolved postcode candidate. */
    private fun adoptLocation(name: String, coordinate: Coordinates) {
        search?.cancel()
        resolve?.cancel()
        postcode?.cancel()
        updateEditor {
            // Fill the label from the stop only when it's blank or was itself auto-filled by an earlier
            // pick — so repicking after changing the query updates the name too, but a user-typed label
            // (or a reserved default like "Home") stands (Codex).
            val deriveLabel = it.label.isBlank() || it.labelFromPick
            it.copy(
                coordinate = coordinate,
                placeName = name,
                label = if (deriveLabel) name else it.label,
                labelFromPick = deriveLabel,
                query = name,
                results = emptyList(),
                searching = false,
                searchFailed = false,
                resolvingId = null,
                postcodeResolving = false,
                postcodeCandidates = emptyList(),
                postcodeNoResults = false,
                postcodeFailed = false,
            )
        }
    }

    /**
     * Save the editor's place, closing the editor once the write lands. A no-op if it isn't complete,
     * or if a save is already in flight (so a double tap can't add two custom places, Codex). The
     * editor stays open if the write throws, so the user's entry isn't lost and [State.writeFailed]
     * can surface the failure (SPEC *Error handling*).
     */
    fun commit() {
        val editor = _state.value.editor ?: return
        if (editor.saving) return
        val coordinate = editor.coordinate ?: return
        val label = editor.label.trim()
        if (label.isEmpty()) return
        // Wait for the store's first emission before writing: a draft restored after process death has
        // Save enabled while [places] is still the default empty set, and a newer-schema file wouldn't
        // be known until it emits Unavailable — saving first would no-op and drop the draft (Codex). The
        // Save button is likewise disabled until loaded.
        if (!_state.value.loaded) return
        // A newer-schema stored file we won't overwrite: store.save no-ops against it, so a save can't
        // persist. Surface it and keep the editor rather than closing it on a silent no-op that drops a
        // restored draft (SPEC principle 2; Codex).
        if (_state.value.places is FavoritePlacesSet.Unavailable) {
            _state.update { it.copy(writeFailed = true) }
            return
        }
        val place = FavoritePlace(
            id = editor.existingId ?: editor.draftId ?: newId(),
            kind = editor.kind,
            label = label,
            coordinate = coordinate,
            placeName = editor.placeName,
        )
        search?.cancel()
        updateEditor { it.copy(saving = true) }
        // Tie this write's completion to the editor that started it: if the user cancels it and opens
        // another editor while the write is in flight, a slow completion must not close or disturb the
        // replacement (Codex).
        val epoch = editorEpoch
        viewModelScope.launch {
            try {
                store.save(place)
                // Don't clear writeFailed on success: an overlapping write's failure must stay visible
                // until the user dismisses it, rather than being wiped by a later success they may never
                // have seen the failure behind (Codex). Only dismissWriteError clears it.
                if (editorEpoch == epoch) setEditor(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The label/coordinate aren't logged (Privacy); only that a save failed.
                warn("favorite place save failed: ${e.javaClass.simpleName}")
                // Clear the in-flight flag only on the editor that started this write (a later editor
                // must be left alone), but always surface the failure — the notice sits above both views,
                // so a save that failed after the user moved on isn't lost silently (Codex).
                if (editorEpoch == epoch) updateEditor { it.copy(saving = false) }
                _state.update { it.copy(writeFailed = true) }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                store.remove(id)
                // As with save: a success doesn't clear a prior failure's notice (Codex).
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("favorite place delete failed: ${e.javaClass.simpleName}")
                _state.update { it.copy(writeFailed = true) }
            }
        }
    }

    /** The user dismisses the write-failure notice — the only thing that clears it, so an overlapping
     *  success can't wipe a failure before it's seen (Codex). */
    fun dismissWriteError() {
        _state.update { it.copy(writeFailed = false) }
    }

    private fun startSearch(query: String) {
        search?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            updateEditor {
                it.copy(
                    searching = false, results = emptyList(), searchFailed = false, remoteFailed = false,
                    unresolvableIds = emptySet(), resolveFailedIds = emptySet(),
                )
            }
            return
        }
        // Clear the previous query's results (and any resolve outcomes) at once, so a stale match can't
        // be tapped (and saved) during this query's debounce and request.
        updateEditor {
            it.copy(
                searching = true, searchFailed = false, remoteFailed = false, results = emptyList(),
                unresolvableIds = emptySet(), resolveFailedIds = emptySet(),
            )
        }
        search = viewModelScope.launch {
            // The bundled index answers on the device at once (an abbreviation or code finds its
            // station before the pause is over); TfL's search (bus stops, newer stations) follows the
            // pause, and the two are merged and ranked exactly as From…/To… do (SPEC *Finding stops*).
            val stations = index.await()
            val local = withContext(io) { stations.search(trimmed) }
            // Bundled matches are positionless from search/rank; fill their own coordinate from the
            // index so a station favorite is selectable even when TfL is unreachable (Codex). Applied
            // to the displayed lists only, so rank/fold behavior stays identical to From…/To….
            val localShown = stations.withBundledPositions(local)
            _state.update {
                val editor = it.editor?.takeIf { e -> e.query.trim() == trimmed } ?: return@update it
                if (localShown.isEmpty()) it else it.copy(editor = editor.copy(results = localShown))
            }
            delay(debounceMillis)
            try {
                val remote = withContext(io) { finder.searchStations(trimmed) }
                val merged = withContext(io) { stations.withBundledPositions(stations.rank(trimmed, local, remote)) }
                _state.update {
                    // Guard against a result landing after the editor closed or moved on.
                    val editor = it.editor?.takeIf { e -> e.query.trim() == trimmed } ?: return@update it
                    it.copy(editor = editor.copy(searching = false, results = merged, searchFailed = false, remoteFailed = false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                warn("favorite place search failed: ${e.javaClass.simpleName}")
                _state.update {
                    val editor = it.editor?.takeIf { e2 -> e2.query.trim() == trimmed } ?: return@update it
                    // Keep the on-device matches if any (with their bundled positions, so they're still
                    // selectable offline) and say TfL's (bus stops, newer stations) weren't searched;
                    // report a total failure only when nothing matched locally.
                    it.copy(
                        editor = editor.copy(
                            searching = false,
                            results = localShown,
                            searchFailed = localShown.isEmpty(),
                            remoteFailed = localShown.isNotEmpty(),
                        ),
                    )
                }
            }
        }
    }

    // --- Editor state + its saved-state mirror -------------------------------------------------

    private fun setEditor(editor: Editor?) {
        editorEpoch++
        _state.update { it.copy(editor = editor) }
        persistEditor(editor)
    }

    private fun updateEditor(transform: (Editor) -> Editor) {
        _state.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = transform(editor))
        }
        persistEditor(_state.value.editor)
    }

    // Persist only the durable draft fields; the transient search (results/searching) is re-derived on
    // restore. The typed **query is intentionally omitted** — it's the text sent to TfL and isn't saved
    // (*Privacy*), so the field starts blank on restore. Cleared (nulled) when the editor closes.
    private fun persistEditor(editor: Editor?) {
        savedState[KEY_KIND] = editor?.kind?.name
        savedState[KEY_ID] = editor?.existingId
        savedState[KEY_DRAFT_ID] = editor?.draftId
        savedState[KEY_LABEL] = editor?.label
        savedState[KEY_LABEL_FROM_PICK] = editor?.labelFromPick
        savedState[KEY_LAT] = editor?.coordinate?.latitude
        savedState[KEY_LON] = editor?.coordinate?.longitude
        savedState[KEY_PLACE] = editor?.placeName
    }

    private fun restoreEditor(): Editor? {
        val kind = savedState.get<String>(KEY_KIND)
            ?.let { runCatching { FavoriteKind.valueOf(it) }.getOrNull() } ?: return null
        val lat = savedState.get<Double>(KEY_LAT)
        val lon = savedState.get<Double>(KEY_LON)
        return Editor(
            kind = kind,
            existingId = savedState.get<String>(KEY_ID),
            draftId = savedState.get<String>(KEY_DRAFT_ID),
            label = savedState.get<String>(KEY_LABEL).orEmpty(),
            labelFromPick = savedState.get<Boolean>(KEY_LABEL_FROM_PICK) ?: false,
            coordinate = if (lat != null && lon != null) Coordinates(lat, lon) else null,
            placeName = savedState.get<String>(KEY_PLACE),
        )
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val DEBOUNCE_MILLIS = 300L
        private const val KEY_KIND = "editor.kind"
        private const val KEY_ID = "editor.id"
        private const val KEY_DRAFT_ID = "editor.draftId"
        private const val KEY_LABEL = "editor.label"
        private const val KEY_LABEL_FROM_PICK = "editor.labelFromPick"
        private const val KEY_LAT = "editor.lat"
        private const val KEY_LON = "editor.lon"
        private const val KEY_PLACE = "editor.placeName"
    }
}

/**
 * Fill each match's coordinate from the bundled index by id when it has none of its own, so a bundled
 * station is selectable as a favorite even without TfL's response (SPEC D9). A match already positioned
 * (a TfL result, or one rank lent a position) is left as is; a bus stop the index doesn't hold stays
 * positionless.
 */
private fun StationIndex.withBundledPositions(matches: List<StationMatch>): List<StationMatch> =
    matches.map { match ->
        if (match.latitude != null && match.longitude != null) {
            match
        } else {
            positionOf(match.id)?.let { match.copy(latitude = it.latitude, longitude = it.longitude) } ?: match
        }
    }

/**
 * The match's inline coordinate when TfL placed it, else null — the caller resolves a null from the
 * match's stops before treating it as unselectable (SPEC D9), so null is "no inline position", not
 * "definitively unresolvable".
 */
fun StationMatch.coordinate(): Coordinates? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    return Coordinates(lat, lon)
}
