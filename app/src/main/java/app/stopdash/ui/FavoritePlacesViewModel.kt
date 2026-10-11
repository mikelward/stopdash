package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.BusStopIndex
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.ChipLabel
import app.stopdash.domain.FavoritePlaceIcon
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.FavoritePlacesStore
import app.stopdash.domain.FixedLocation
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceHits
import app.stopdash.domain.PlaceIndex
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StationMatch
import app.stopdash.domain.TflException
import app.stopdash.domain.UkPostcode
import app.stopdash.domain.Workers
import java.time.DayOfWeek
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * matches with the same bundled stations, bus stops and places, merged and ranked as the From…/To…
 * search does (SPEC *Finding stops*), all on the device: the typed query is sent nowhere and never
 * logged (SPEC *Privacy*). A match with no inline position is resolved
 * on pick from its stops' members ([StationFinder.stationStops] → center); only a result whose stops
 * carry no position at all can't anchor a place and stays unselectable (SPEC D9). Editing a place keeps
 * its saved coordinate until the user picks a new location. A complete **postcode** the user types is
 * adopted from the bundled postcodes (SPEC D9), with nothing sent; one the bundle doesn't hold (outside
 * London) is looked up with TfL, and any other place outside London only when the rider taps "Search
 * outside London" ([OutsideLondon]).
 *
 * The editor's draft (kind, label and picked coordinate) is mirrored into [SavedStateHandle], so a
 * process death while adding/editing restores the in-progress place rather than dropping it. The typed
 * **query is deliberately not persisted** — it's the rider's own words and, per *Privacy*, isn't saved
 * — so the search field starts blank on restore while the picked place (label + coordinate) stands.
 */
class FavoritePlacesViewModel(
    private val store: FavoritePlacesStore,
    private val finder: StationFinder,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Centers a picked station's stops and places the bundled matches, off the main thread (AGENTS.md
    // *Main thread*).
    private val compute: CoroutineDispatcher = Workers.compute,
    // The bundled station index, so the picker matches on the device as the user types and returns the
    // same ranked list as From…/To… (SPEC *Finding stops*); loaded once, off the main thread, on first
    // search. Empty by default (tests, and before it loads), which finds no station.
    loadIndex: suspend () -> StationIndex = { StationIndex.EMPTY },
    // The bundled London places, streets and postcodes (dev-docs/PLACES.md), offered as places to save
    // alongside the stops, found on the device with nothing sent. A complete postcode it holds is
    // adopted from it without asking TfL. Read once, off the main thread. None by default.
    loadPlaceIndex: suspend () -> PlaceIndex = { PlaceIndex.EMPTY },
    // London's bus stops, bundled: searched with the stations; a bundled stop carries its position, so
    // picking one needs no lookup. Read once, off the main thread. None by default.
    loadBusStops: suspend () -> BusStopIndex = { BusStopIndex.EMPTY },
    // Restores the in-progress editor draft across process death; the saved copy stays on the device.
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    // A fresh id for a newly-added place; overridable so tests get stable ids.
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val warn: (String) -> Unit = {},
    // Looks a place or postcode up with TfL's Journey Planner, for one outside London (maintainer,
    // 2026-10-11): only the typed text is sent. Null offers no such search.
    private val findOutside: (suspend (String) -> List<PlaceCandidate>)? = null,
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
        // The bundled places, streets and postcodes the query finds, below the stops; each carries its
        // coordinate, so picking one needs no lookup.
        val places: List<PlaceHit> = emptyList(),
        // How many of [results] sit above [places]: the stops shown when the places appeared. The bus
        // stops ranked in after go below the places, so nothing the rider is reading moves down (*hold still*).
        val placesAfter: Int = Int.MAX_VALUE,
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
        // Each result's postcode district ("EC4M"), by [StationMatch.key], so same-named stops read apart.
        val areas: Map<String, String> = emptyMap(),
        // TfL's places outside London for the query, below the bundled ones; reset when the query changes.
        val outside: OutsideLondon = OutsideLondon.Hidden,
        // The days this place is offered as a route chip on the near-me list; every day for a new place.
        val showOnDays: Set<DayOfWeek> = FavoritePlace.EVERY_DAY,
        // The place's icon, or null for none, and what its chip shows (null: the default).
        val icon: String? = null,
        val chipShows: ChipLabel? = null,
        // The stop a stop's details' Favorite opened this editor for ([startAddAt]), until its position is
        // in: saved with the draft, so a restore resumes its lookup rather than losing the stop (Codex on #670).
        val pendingStop: StationMatch? = null,
    ) {
        val editing: Boolean get() = existingId != null
        val canSave: Boolean get() = coordinate != null && label.isNotBlank() && !saving
    }

    private val _state = MutableStateFlow(State(editor = restoreEditor()))
    val state: StateFlow<State> = _state.asStateFlow()

    private var search: Job? = null
    private var resolve: Job? = null
    private var postcode: Job? = null
    private var outside: Job? = null

    // Bumped whenever the editor is opened, replaced or closed, so an in-flight save can tell whether
    // the editor it started for is still the one on screen (see [commit]). Not changed by field edits
    // within one editing session.
    private var editorEpoch = 0

    // The bundled index, loaded once on first use so opening the editor never waits on the asset read.
    private val index = viewModelScope.async(io, start = CoroutineStart.LAZY) { loadIndex() }

    // Started at once (it takes a moment to read), so it's usually ready by the first letter typed.
    private val placeIndex = viewModelScope.async(io) { loadPlaceIndex() }

    // Started at once for the same reason.
    private val busStops = viewModelScope.async(io) { loadBusStops() }

    // The bundled places for [query], searched on [compute]; none, with no hop, when there's no index.
    private suspend fun bundledPlaces(query: String, postcodesOnly: Boolean = false): List<PlaceHit> {
        val places = placeIndex.await()
        return if (places.size == 0) emptyList() else withContext(compute) {
            // A search typed past stops partway rather than scanning the whole index.
            PlaceHits.fromIndex(places, query, checkpoint = { ensureActive() }, postcodesOnly = postcodesOnly)
        }
    }

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
        // A draft restored from saved state re-runs its search so the results match the query again,
        // or, opened for a stop whose position never came, that stop's lookup, the rest of the draft as
        // the rider left it (Codex on #670).
        val restored = _state.value.editor
        val pending = restored?.pendingStop
        if (restored != null && pending != null && restored.coordinate == null) {
            updateEditor { it.copy(query = pending.name, results = listOf(pending)) }
            onPick(pending)
        } else {
            restored?.query?.let { if (it.isNotBlank()) startSearch(it) }
        }
    }

    /** Begin adding a place of [kind]; [defaultLabel] pre-fills the label (e.g. "Home"). */
    fun startAdd(kind: FavoriteKind, defaultLabel: String) {
        search?.cancel()
        resolve?.cancel()
        postcode?.cancel()
        // Every new place gets its id now (persisted with the draft), so a save retried after a failure
        // or restored after process death reuses it and keeps a stable id — a reserved kind upserts by
        // kind, but a changing id would still break identity for anything that references it (Codex).
        setEditor(
            Editor(
                kind = kind,
                existingId = null,
                draftId = newId(),
                label = defaultLabel,
                icon = FavoritePlaceIcon.defaultFor(kind),
            ),
        )
    }

    /**
     * Begin adding [match] as a place (a stop's details' Favorite): a custom place named for it at its
     * position, resolved from its stops where it carries none, as a pick from the search is; the rider
     * can still change its name, icon and days before saving. The stop stands as the search's one
     * result, so a lookup that fails shows on its row with a retry, as on a searched one (Codex on #670).
     */
    fun startAddAt(match: StationMatch) {
        startAdd(FavoriteKind.CUSTOM, "")
        updateEditor { it.copy(query = match.name, results = listOf(match), pendingStop = match) }
        onPick(match)
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
                showOnDays = place.showOnDays,
                icon = place.icon,
                chipShows = place.chipShows,
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
    /** Choose [icon] for the place; choosing the one already chosen clears it. */
    fun onIconChange(icon: String) {
        if (savingNow()) return // a save is in flight; ignore edits so completion can't discard them
        updateEditor { it.copy(icon = if (it.icon == icon) null else icon) }
    }

    /** Choose what the place's chip on the near-me list shows. */
    fun onChipShowsChange(shows: ChipLabel) {
        if (savingNow()) return // a save is in flight; ignore edits so completion can't discard them
        updateEditor { it.copy(chipShows = shows) }
    }

    /** Turn [day] on or off for the place's chip on the near-me list. */
    fun onToggleDay(day: DayOfWeek) {
        if (savingNow()) return // a save is in flight; ignore edits so completion can't discard them
        updateEditor { it.copy(showOnDays = if (day in it.showOnDays) it.showOnDays - day else it.showOnDays + day) }
    }

    fun onQueryChange(query: String) {
        if (savingNow()) return // don't disturb a draft mid-write; completion would discard the change
        // Editing the query after a pick invalidates that selection: otherwise its coordinate would
        // linger and Save could store the old location under the new query/label (Codex P1). A pending
        // postcode lookup belongs to the old text, so drop it too.
        resolve?.cancel()
        postcode?.cancel()
        outside?.cancel()
        updateEditor {
            it.copy(
                query = query, coordinate = null, placeName = null, resolvingId = null, places = emptyList(),
                // The rider is searching for another place: a restore no longer goes back to the stop.
                pendingStop = null,
                outside = OutsideLondon.Hidden,
            )
        }
        // A postcode-shaped query lists the bundled postcodes it starts, not stops (SPEC D9). The **digit**
        // is the discriminator: a plain two-letter prefix is
        // how station names start ("Ba" → Bank), so those still search; a query with a digit ("N1",
        // "SW1A") is a postcode, not a station name.
        if (UkPostcode.looksLikePartial(query) && query.any(Char::isDigit)) {
            search?.cancel()
            updateEditor {
                it.copy(
                    searching = false, results = emptyList(),
                    unresolvableIds = emptySet(), resolveFailedIds = emptySet(),
                )
            }
            // The bundled postcodes it starts, to pick from as it's typed ("SW1A" lists SW1A's): postcodes
            // only, so places named like one ("E12 Cafe") can't take every slot.
            search = viewModelScope.launch {
                val places = bundledPlaces(query.trim(), postcodesOnly = true)
                _state.update {
                    val editor = it.editor?.takeIf { e -> e.query == query } ?: return@update it
                    it.copy(editor = editor.copy(places = places))
                }
            }
            // A complete postcode is adopted from the bundle (debounced, so a pause confirms it), so the happy
            // path is type → Save; while it's still partial there's nothing to look up yet (SPEC D9).
            if (UkPostcode.isComplete(query)) startPostcodeResolve(query)
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
                val stops = withContext(io) { finder.stationStops(match.id) }
                center = withContext(compute) { FixedLocation.centerOf(stops) }
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
     * Adopt the bundled postcode [query] names, after the typing pause (SPEC D9). The bundle holds every
     * London postcode at its center, and nothing is sent to TfL for one; a postcode it doesn't hold
     * (outside London) is looked up with TfL (maintainer, 2026-10-11).
     */
    private fun startPostcodeResolve(query: String) {
        if (savingNow()) return
        val code = UkPostcode.format(query) ?: return
        postcode?.cancel()
        postcode = viewModelScope.launch {
            // Coalesce keystrokes; a newer query cancels this job (via onQueryChange), so a stale one
            // that slips through the debounce is dropped by the guard below.
            delay(debounceMillis)
            if (_state.value.editor?.query != query) return@launch
            val bundled = bundledPlaces(code).firstOrNull { it.kind == PlaceKind.POSTCODE && it.name.equals(code, ignoreCase = true) }
            if (_state.value.editor?.query != query) return@launch
            if (bundled != null) {
                adoptLocation(bundled.name, bundled.coordinate)
            } else {
                searchOutside()
            }
        }
    }

    /** Adopt a bundled place, street or postcode [hit] as the place's location, like picking a stop. */
    fun onPickPlace(hit: PlaceHit) {
        if (savingNow()) return
        adoptLocation(hit.name, hit.coordinate)
    }

    /** Adopt a chosen place [name] at [coordinate] — a picked station, place or postcode. */
    private fun adoptLocation(name: String, coordinate: Coordinates) {
        search?.cancel()
        resolve?.cancel()
        postcode?.cancel()
        outside?.cancel()
        updateEditor {
            // Fill the label from the stop only when it's blank or was itself auto-filled by an earlier
            // pick — so repicking after changing the query updates the name too, but a user-typed label
            // (or a reserved default like "Home") stands (Codex).
            val deriveLabel = it.label.isBlank() || it.labelFromPick
            it.copy(
                coordinate = coordinate,
                pendingStop = null,
                placeName = name,
                label = if (deriveLabel) name else it.label,
                labelFromPick = deriveLabel,
                query = name,
                results = emptyList(),
                places = emptyList(),
                searching = false,
                resolvingId = null,
                outside = OutsideLondon.Hidden,
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
            showOnDays = editor.showOnDays,
            icon = editor.icon,
            chipShows = editor.chipShows,
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
                    searching = false, results = emptyList(),
                    unresolvableIds = emptySet(), resolveFailedIds = emptySet(),
                )
            }
            return
        }
        // Clear the previous query's results (and any resolve outcomes) at once, so a stale match can't
        // be tapped (and saved) during this query's debounce and request.
        updateEditor {
            it.copy(
                searching = true, results = emptyList(),
                placesAfter = Int.MAX_VALUE,
                unresolvableIds = emptySet(), resolveFailedIds = emptySet(),
            )
        }
        search = viewModelScope.launch {
            // Searched on the device only (maintainer, 2026-10-11): the bundled stations, bus stops and
            // places, merged and ranked exactly as From…/To… do (SPEC *Finding stops*), with nothing sent.
            val stations = index.await()
            val local = withContext(io) { stations.search(trimmed) }
            // Bundled matches are positionless from search/rank; fill their own coordinate from the
            // index so a station favorite is selectable at once. Applied to the displayed lists only, so
            // rank/fold behavior stays identical to From…/To….
            val localShown = withContext(compute) { stations.withBundledPositions(local) }
            // An address's house number isn't in the bundle, so its street is what's searched.
            val places = bundledPlaces(PlaceHits.withoutHouseNumber(trimmed))
            _state.update {
                val editor = it.editor?.takeIf { e -> e.query.trim() == trimmed } ?: return@update it
                if (localShown.isEmpty() && places.isEmpty()) {
                    it
                } else {
                    it.copy(editor = editor.copy(results = localShown, places = places, placesAfter = localShown.size))
                }
            }
            // The bundled bus stops, ranked in with the stations; each carries its position.
            val buses = busStops.await()
            // The place index is read alongside, for each result's postcode district.
            val placeIndexRead = placeIndex.await()
            val (merged, areas) = withContext(compute) {
                val busMatches = buses.search(trimmed, checkpoint = { ensureActive() })
                val merged = holdStill(localShown, stations.withBundledPositions(stations.rank(trimmed, local, busMatches)))
                merged to districtsOf(merged, placeIndexRead)
            }
            _state.update {
                // Guard against a result landing after the editor closed or moved on.
                val editor = it.editor?.takeIf { e -> e.query.trim() == trimmed } ?: return@update it
                // Anything the bundle missed may be outside London: offered, asked only when tapped.
                val offer = if (findOutside != null && editor.outside == OutsideLondon.Hidden) OutsideLondon.Offered else editor.outside
                it.copy(editor = editor.copy(searching = false, results = merged, areas = areas, outside = offer))
            }
        }
    }

    /**
     * Ask TfL for places outside London matching what's typed: "Search outside London", its Retry, or a
     * complete postcode the bundle doesn't hold. Picked with [onPickPlace], as a bundled place is.
     */
    fun searchOutside() {
        if (savingNow()) return
        val typed = _state.value.editor?.query ?: return
        val find = findOutside
        outside?.cancel()
        // A new query, a pick or another editor cancels this, so what it publishes is this query's.
        outside = viewModelScope.launch {
            // Trimmed and classified on [compute]: the query is the rider's, of any length (AGENTS.md
            // *Main thread*).
            val query = withContext(compute) { typed.trim() }
            if (query.length < MIN_QUERY_LENGTH) return@launch
            if (find == null) {
                updateEditor { it.copy(outside = OutsideLondon.NoMatches) }
                return@launch
            }
            updateEditor { it.copy(outside = OutsideLondon.Searching) }
            val outcome = try {
                val candidates = withContext(io) { find(query) }
                withContext(compute) {
                    val kind = if (UkPostcode.isComplete(query)) PlaceKind.POSTCODE else PlaceKind.PLACE
                    val places = PlaceHits.rank(query, PlaceHits.outsideBundle(candidates), kind)
                    if (places.isEmpty()) OutsideLondon.NoMatches else OutsideLondon.Found(places)
                }
            } catch (e: TflException) {
                // Said on the row, with a Retry; the bundled rows above are untouched.
                warn("favorite place search outside London failed: ${e.javaClass.simpleName}")
                OutsideLondon.Failed
            }
            updateEditor { it.copy(outside = outcome) }
        }
    }

    // Each match's postcode district, from the nearest bundled postcode to its position; called on [compute].
    @androidx.annotation.WorkerThread
    private fun districtsOf(matches: List<StationMatch>, places: PlaceIndex): Map<String, String> {
        if (places.size == 0) return emptyMap()
        return buildMap {
            for (match in matches) {
                val lat = match.latitude ?: continue
                val lon = match.longitude ?: continue
                places.districtNear(lat, lon)?.let { put(match.key, it) }
            }
        }
    }

    // --- Editor state + its saved-state mirror -------------------------------------------------

    private fun setEditor(editor: Editor?) {
        editorEpoch++
        // A lookup outside London belongs to the editor it started in.
        outside?.cancel()
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
        savedState[KEY_ICON] = editor?.icon
        savedState[KEY_CHIP_SHOWS] = editor?.chipShows?.name
        savedState[KEY_SHOW_ON_DAYS] = editor?.showOnDays?.map(DayOfWeek::getValue)?.toIntArray()
        // The stop's public id and name, never what the rider typed.
        val pending = editor?.pendingStop
        savedState[KEY_PENDING] = pending?.let { arrayListOf(it.id, it.name) }
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
            showOnDays = savedState.get<IntArray>(KEY_SHOW_ON_DAYS)
                ?.filter { it in 1..7 }?.map(DayOfWeek::of)
                ?.toSet()
                ?: FavoritePlace.EVERY_DAY,
            icon = savedState.get<String>(KEY_ICON),
            chipShows = savedState.get<String>(KEY_CHIP_SHOWS)
                ?.let { name -> ChipLabel.values().firstOrNull { it.name == name } },
            pendingStop = savedState.get<ArrayList<String>>(KEY_PENDING)
                ?.takeIf { it.size == 2 }?.let { StationMatch(it[0], it[1]) },
        )
    }

    companion object {
        /**
         * TfL's merged stops, kept still: the stops already [shown] stay first, in their order (each
         * with TfL's fuller record where it has one), and the stops TfL added follow them, up to the
         * usual [limit] of stop rows in all.
         */
        @androidx.annotation.WorkerThread
        internal fun holdStill(
            shown: List<StationMatch>,
            merged: List<StationMatch>,
            limit: Int = StationIndex.DEFAULT_LIMIT,
        ): List<StationMatch> {
            // By [StationMatch.key], not id: an interchange's names share one id as distinct rows.
            val byKey = merged.associateBy { it.key }
            val shownKeys = shown.mapTo(HashSet()) { it.key }
            val kept = shown.map { byKey[it.key] ?: it }
            return kept + merged.filter { it.key !in shownKeys }.take((limit - kept.size).coerceAtLeast(0))
        }

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
        private const val KEY_SHOW_ON_DAYS = "editor.showOnDays"
        private const val KEY_PENDING = "editor.pendingStop"
        private const val KEY_ICON = "editor.icon"
        private const val KEY_CHIP_SHOWS = "editor.chipShows"
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
